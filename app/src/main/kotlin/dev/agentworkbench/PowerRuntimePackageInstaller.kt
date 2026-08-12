package dev.agentworkbench

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Audited remote package installation surface for sideload builds.
 *
 * The common runtime can execute system commands and already-installed
 * interpreted packs, but it has no network/archive installer. Keeping this
 * Package verification is a release boundary, not a remote feature flag.
 */
internal object PowerRuntimePackageInstaller {
    suspend fun install(runtime: InternalRuntime, request: RuntimePackageRequest): JSONObject =
        runtime.withPackageLock {
            withContext(Dispatchers.IO) {
                check(runtime.externalPackagesAllowed) {
                    "Packs externos não estão disponíveis neste build."
                }
                runtime.validatePackageRequest(request)
                validatePublicHttpsUri(request.url)
                runtime.ensureEntrypointOwnership(request.name, request.entrypoints.keys)

                val staging = File(runtime.stagingDirectory, UUID.randomUUID().toString()).canonicalFile
                check(staging.path.startsWith(runtime.stagingDirectory.path + File.separator))
                check(staging.mkdirs()) { "Nao foi possivel criar staging do pacote." }
                val download = File(staging, "download")
                val content = File(staging, "content")
                try {
                    val downloaded = downloadHttps(request.url, download, request.maxDownloadBytes)
                    check(downloaded.sha256.equals(request.sha256, ignoreCase = true)) {
                        "SHA-256 divergente. Esperado ${request.sha256.lowercase()}, recebido ${downloaded.sha256}."
                    }
                    check(content.mkdir()) { "Nao foi possivel criar o conteudo do pacote." }
                    when (request.format) {
                        "raw" -> Files.move(download.toPath(), File(content, "payload").toPath())
                        "zip" -> extractZip(runtime, download, content)
                        else -> error("Formato de pacote nao suportado: ${request.format}")
                    }
                    runtime.validateAndHardenEntrypoints(content, request.entrypoints)
                    val metadata = runtime.packageMetadata(request, downloaded.bytes)
                    File(content, PACKAGE_METADATA).writeText(metadata.toString(), Charsets.UTF_8)

                    val packageRoot = File(runtime.packagesDirectory, request.name)
                    check(packageRoot.mkdirs() || packageRoot.isDirectory)
                    val target = File(packageRoot, request.version).canonicalFile
                    check(target.path.startsWith(packageRoot.canonicalPath + File.separator))
                    if (target.exists()) {
                        val existing = runCatching {
                            JSONObject(File(target, PACKAGE_METADATA).readText()).optString("sha256")
                        }.getOrNull()
                        if (existing.equals(request.sha256, ignoreCase = true)) {
                            runtime.activate(request, target)
                            return@withContext metadata.put("already_installed", true)
                        }
                        runtime.moveToRuntimeTrash(target, "replaced")
                    }
                    runtime.atomicMove(content, target)
                    runtime.activate(request, target)
                    metadata
                        .put("installed", true)
                        .put("path", target.path)
                        .put("already_installed", false)
                } catch (error: Exception) {
                    staging.deleteRecursively()
                    throw error
                } finally {
                    download.delete()
                    staging.deleteRecursively()
                }
            }
        }

    private fun extractZip(runtime: InternalRuntime, archive: File, destination: File) {
        val prefix = destination.canonicalPath + File.separator
        var entries = 0
        var expanded = 0L
        ZipInputStream(archive.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                require(entries++ < MAX_ARCHIVE_ENTRIES) { "ZIP possui entradas demais." }
                runtime.requireSafeRelativePath(entry.name.trimEnd('/', '\\'))
                val target = File(destination, entry.name).canonicalFile
                require(target.path.startsWith(prefix)) { "ZIP tentaria escapar do pacote." }
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory)
                } else {
                    check(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true)
                    require(!target.exists()) { "ZIP contem entradas duplicadas." }
                    FileOutputStream(target).buffered().use { output ->
                        expanded = copyLimited(input, output, expanded, MAX_EXPANDED_BYTES)
                    }
                }
                input.closeEntry()
            }
        }
        require(entries > 0) { "ZIP vazio." }
    }

    private fun downloadHttps(url: String, destination: File, limit: Long): DownloadedFile {
        var uri = validatePublicHttpsUri(url)
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            validateResolvedAddresses(uri.host)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "Refrator-Runtime/${BuildConfig.VERSION_NAME} Android")
            connection.setRequestProperty("Accept", "application/octet-stream,application/zip,text/x-shellscript")
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    require(redirectIndex < MAX_REDIRECTS) { "Redirecionamentos demais." }
                    val location = connection.getHeaderField("Location") ?: error("Redirect sem Location.")
                    uri = validatePublicHttpsUri(uri.resolve(location).toString())
                    return@repeat
                }
                require(status in 200..299) { "Download HTTPS falhou com HTTP $status." }
                val declared = connection.contentLengthLong
                require(declared < 0 || declared <= limit) { "Servidor declarou $declared bytes; limite $limit." }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.buffered().use { input ->
                    FileOutputStream(destination).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total = Math.addExact(total, count.toLong())
                            require(total <= limit) { "Download excedeu $limit bytes." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(total > 0) { "Download vazio." }
                return DownloadedFile(total, digest.digest().toHex())
            } finally {
                connection.disconnect()
            }
        }
        error("Download nao concluido.")
    }

    private fun validatePublicHttpsUri(value: String): URI {
        val uri = URI(value)
        require(uri.scheme.equals("https", true)) { "Somente HTTPS e aceito para pacotes." }
        require(uri.userInfo == null && uri.fragment == null) { "URL de pacote contem credenciais ou fragmento." }
        require(uri.port == -1 || uri.port == 443) { "Somente porta HTTPS 443 e aceita." }
        val host = uri.host?.lowercase() ?: error("Host HTTPS ausente.")
        require(host !in BLOCKED_HOSTS && !host.endsWith(".local")) { "Host local/metadata bloqueado." }
        return uri
    }

    private fun validateResolvedAddresses(host: String) {
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty()) { "Host sem endereco." }
        require(addresses.all(RuntimePackageRules::isPublicAddress)) {
            "Host resolveu para rede local, loopback ou metadata."
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream, alreadyWritten: Long, limit: Long): Long {
        var total = alreadyWritten
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total = Math.addExact(total, count.toLong())
            require(total <= limit) { "Pacote expandido excedeu $limit bytes." }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class DownloadedFile(val bytes: Long, val sha256: String)

    private const val PACKAGE_METADATA = "package.json"
    private const val MAX_ARCHIVE_ENTRIES = 4_096
    private const val MAX_EXPANDED_BYTES = 768L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val BLOCKED_HOSTS = setOf(
        "localhost", "127.0.0.1", "0.0.0.0", "::1", "169.254.169.254", "metadata.google.internal",
    )
}
