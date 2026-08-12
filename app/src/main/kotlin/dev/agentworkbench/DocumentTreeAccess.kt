package dev.agentworkbench

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

data class DocumentTreeStatus(
    val granted: Boolean,
    val displayName: String?,
    val uri: String?,
    val canRead: Boolean,
    val canWrite: Boolean,
)

class DocumentTreeAccess(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun status(): DocumentTreeStatus {
        val uri = storedUri()
        val permission = uri?.let { target ->
            resolver.persistedUriPermissions.firstOrNull { it.uri == target }
        }
        val root = uri?.let { DocumentFile.fromTreeUri(appContext, it) }
        val granted = permission != null && root?.exists() == true
        return DocumentTreeStatus(
            granted = granted,
            displayName = root?.name,
            uri = uri?.toString(),
            canRead = granted && permission.isReadPermission && root.canRead(),
            canWrite = granted && permission.isWritePermission && root.canWrite(),
        )
    }

    fun grant(uri: Uri): DocumentTreeStatus {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, flags)
        preferences.edit().putString(KEY_URI, uri.toString()).apply()
        return status()
    }

    fun revoke() {
        storedUri()?.let { uri ->
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        preferences.edit().remove(KEY_URI).apply()
    }

    fun list(path: String, depth: Int, maxEntries: Int = 250): List<String> {
        val start = resolve(path, requireExists = true)
        require(start.isDirectory) { "O caminho externo não é uma pasta." }
        val output = mutableListOf<String>()
        fun visit(directory: DocumentFile, prefix: String, level: Int) {
            if (output.size >= maxEntries) return
            directory.listFiles()
                .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name.orEmpty().lowercase() }))
                .forEach { child ->
                    if (output.size >= maxEntries) return@forEach
                    val name = child.name ?: "<sem-nome>"
                    val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                    output += "${if (child.isDirectory) "d" else "f"} $relative" +
                        if (child.isFile) " (${child.length()} B)" else ""
                    if (child.isDirectory && level < depth) visit(child, relative, level + 1)
                }
        }
        visit(start, normalize(path).joinToString("/"), 0)
        if (output.size >= maxEntries) output += "… limite de $maxEntries entradas"
        return output
    }

    fun read(path: String, maxBytes: Int): String {
        val file = resolve(path, requireExists = true)
        require(file.isFile) { "O caminho externo não é um arquivo." }
        val declared = file.length()
        require(declared < 0 || declared <= maxBytes) {
            "Arquivo possui $declared bytes; limite solicitado é $maxBytes."
        }
        return resolver.openInputStream(file.uri)?.buffered()?.use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 16_384))
            val buffer = ByteArray(4_096)
            while (output.size() <= maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            require(output.size() <= maxBytes) { "Arquivo excede $maxBytes bytes." }
            output.toByteArray().toString(Charsets.UTF_8)
        } ?: throw FileNotFoundException("Não foi possível abrir o arquivo externo.")
    }

    fun write(path: String, content: String, overwrite: Boolean): DocumentTreeWriteResult {
        val segments = normalize(path)
        require(segments.isNotEmpty()) { "Informe um caminho de arquivo." }
        val fileName = segments.last()
        var parent = root(requireWrite = true)
        segments.dropLast(1).forEach { segment ->
            val existing = parent.findFile(segment)
            parent = when {
                existing == null -> parent.createDirectory(segment)
                    ?: error("Não foi possível criar a pasta $segment.")
                existing.isDirectory -> existing
                else -> error("$segment existe e não é uma pasta.")
            }
        }
        val existing = parent.findFile(fileName)
        require(existing == null || overwrite) { "Arquivo externo já existe." }
        require(existing == null || existing.isFile) { "O destino externo não é um arquivo." }
        val target = existing ?: parent.createFile("text/plain", fileName)
            ?: error("Não foi possível criar o arquivo externo.")
        resolver.openOutputStream(target.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(content)
        } ?: throw FileNotFoundException("Não foi possível abrir o destino externo.")
        return DocumentTreeWriteResult(
            path = segments.joinToString("/"),
            bytes = content.toByteArray(Charsets.UTF_8).size,
            uri = target.uri.toString(),
        )
    }

    fun importFile(
        path: String,
        source: File,
        mimeType: String,
        overwrite: Boolean,
    ): DocumentTreeImportResult {
        require(source.isFile) { "Arquivo temporario de download nao existe." }
        val segments = normalize(path)
        require(segments.isNotEmpty()) { "Informe um caminho de arquivo." }
        val fileName = segments.last()
        var parent = root(requireWrite = true)
        segments.dropLast(1).forEach { segment ->
            val existing = parent.findFile(segment)
            parent = when {
                existing == null -> parent.createDirectory(segment)
                    ?: error("Nao foi possivel criar a pasta $segment.")
                existing.isDirectory -> existing
                else -> error("$segment existe e nao e uma pasta.")
            }
        }
        val existing = parent.findFile(fileName)
        require(existing == null || overwrite) { "Arquivo externo ja existe." }
        require(existing == null || existing.isFile) { "O destino externo nao e um arquivo." }
        val created = existing == null
        val target = existing ?: parent.createFile(
            mimeType.ifBlank { "application/octet-stream" },
            fileName,
        ) ?: error("Nao foi possivel criar o arquivo externo.")
        try {
            resolver.openOutputStream(target.uri, "wt")?.buffered()?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw FileNotFoundException("Nao foi possivel abrir o destino externo.")
        } catch (error: Exception) {
            if (created) runCatching { target.delete() }
            throw error
        }
        return DocumentTreeImportResult(
            path = segments.joinToString("/"),
            bytes = source.length(),
            uri = target.uri.toString(),
        )
    }

    fun publishToDownloads(
        sourcePath: String,
        destinationPath: String,
        downloads: PublicDownloadsAccess,
        mimeType: String,
        overwrite: Boolean,
        maxBytes: Long,
    ): PublicDownloadResult {
        val source = resolve(sourcePath, requireExists = true)
        require(source.isFile) { "A origem SAF nao e um arquivo." }
        val declared = source.length()
        require(declared < 0L || declared <= maxBytes) {
            "Arquivo possui $declared bytes; espaco seguro disponivel e $maxBytes."
        }
        val resolvedMime = mimeType.ifBlank {
            resolver.getType(source.uri).orEmpty().ifBlank { "application/octet-stream" }
        }
        return resolver.openInputStream(source.uri)?.buffered()?.use { input ->
            downloads.writeFrom(
                path = destinationPath,
                mimeType = resolvedMime,
                overwrite = overwrite,
                input = input,
                maxBytes = maxBytes,
            )
        } ?: throw FileNotFoundException("Nao foi possivel abrir o arquivo da arvore SAF.")
    }

    private fun resolve(path: String, requireExists: Boolean): DocumentFile {
        var current = root(requireWrite = false)
        normalize(path).forEach { segment ->
            current = current.findFile(segment)
                ?: if (requireExists) {
                    throw FileNotFoundException("Caminho externo não existe: $path")
                } else {
                    return current
                }
        }
        return current
    }

    private fun root(requireWrite: Boolean): DocumentFile {
        val status = status()
        require(status.granted && status.canRead) {
            "Escolha uma pasta externa na aba Ferramentas."
        }
        if (requireWrite) require(status.canWrite) { "A pasta externa não permite escrita." }
        return DocumentFile.fromTreeUri(appContext, Uri.parse(status.uri))
            ?: error("A pasta externa concedida ficou indisponível.")
    }

    private fun normalize(path: String): List<String> {
        val value = path.trim().replace('\\', '/')
        require(!value.startsWith('/')) { "Caminho externo deve ser relativo." }
        val segments = value.split('/').filter(String::isNotBlank)
        require(segments.none { it == "." || it == ".." }) { "Travessia de pasta não é permitida." }
        require(segments.all { '\u0000' !in it && it.length <= 255 }) { "Caminho externo inválido." }
        return segments
    }

    private fun storedUri(): Uri? = preferences.getString(KEY_URI, null)?.let(Uri::parse)

    private companion object {
        const val PREFERENCES_NAME = "document_tree_access"
        const val KEY_URI = "tree_uri"
    }
}

data class DocumentTreeWriteResult(
    val path: String,
    val bytes: Int,
    val uri: String,
)

data class DocumentTreeImportResult(
    val path: String,
    val bytes: Long,
    val uri: String,
)
