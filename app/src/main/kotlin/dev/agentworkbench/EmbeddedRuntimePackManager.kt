package dev.agentworkbench

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Self-contained Linux toolchains for Refrator. The bootstrap is immutable,
 * bundled with the signed APK, and extracted into app-private storage. Downloaded
 * compiler packages are executed through Android's immutable system linker, so
 * targetSdk 36 keeps its W^X protection and no Termux app or root is required.
 */
internal class EmbeddedRuntimePackManager(
    private val context: Context,
    runtimeRoot: File,
    private val workspace: File,
) : RuntimePackBridge {
    private val root = File(runtimeRoot, "linux-runtime")
    private val prefix = File(root, "usr")
    private val home = File(root, "home")
    private val temp = File(prefix, "tmp")
    private val markerDirectory = File(root, "packs")
    private val bash get() = File(prefix, "bin/bash")
    private val linker = "/system/bin/linker64"

    override fun catalog(): JSONArray = JSONArray(PACKS.map { pack ->
        JSONObject()
            .put("id", pack.id)
            .put("name", pack.name)
            .put("description", pack.description)
            .put("commands", JSONArray(pack.commands))
            .put("download_estimate_mb", pack.estimateMb)
            .put("installed", isInstalled(pack))
            .put("requires", JSONArray(pack.requires))
    })

    override fun status(): JSONObject = JSONObject()
        .put("supported", Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"))
        .put("architecture", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        .put("backend", "signed_bootstrap_system_linker")
        .put("requires_termux", false)
        .put("requires_root", false)
        .put("w_x_policy", "Android targetSdk 36 preserved; writable ELF runs through /system/bin/linker64")
        .put("prefix", prefix.path)
        .put("base_ready", bash.isFile)
        .put("packs", catalog())

    override suspend fun install(packId: String, onProgress: (String) -> Unit): JSONObject =
        withContext(Dispatchers.IO) {
            require(Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) {
                "Os packs nativos atuais exigem arm64-v8a."
            }
            val target = PACKS.firstOrNull { it.id == packId }
                ?: error("Pack desconhecido: $packId")
            synchronized(INSTALL_LOCK) {
                ensureBase(onProgress)
                installDependencies(target, onProgress, mutableSetOf())
            }
            testPack(target)
        }

    override fun commandLine(command: String): List<String>? =
        if (!bash.isFile) null else listOf(linker, bash.path, "--noprofile", "--norc", "-c", command)

    override fun configureEnvironment(environment: MutableMap<String, String>) {
        if (!bash.isFile) return
        val currentPath = environment["PATH"].orEmpty()
        environment["PREFIX"] = prefix.path
        environment["TERMUX__PREFIX"] = prefix.path
        environment["TERMUX__ROOTFS"] = root.path
        environment["TERMUX_APP__PACKAGE_NAME"] = context.packageName
        environment["TERMUX_APP__DATA_DIR"] = context.applicationInfo.dataDir
        environment["TERMUX_APP__LEGACY_DATA_DIR"] = context.applicationInfo.dataDir
        environment["ANDROID__BUILD_VERSION_SDK"] = Build.VERSION.SDK_INT.toString()
        environment["HOME"] = home.path
        environment["TMPDIR"] = temp.path
        environment["APT_CONFIG"] = File(prefix, "etc/apt/apt.conf").path
        environment["SSL_CERT_FILE"] = File(prefix, "etc/tls/cert.pem").path
        environment["CURL_CA_BUNDLE"] = File(prefix, "etc/tls/cert.pem").path
        environment["GIT_SSL_CAINFO"] = File(prefix, "etc/tls/cert.pem").path
        environment["PATH"] = "${File(prefix, "bin").path}:$currentPath"
        environment["LD_LIBRARY_PATH"] = File(prefix, "lib").path
        environment["TERMUX_EXEC__EXECVE_CALL__INTERCEPT"] = "enable"
        environment["TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE"] = "force"
        File(prefix, "lib/libtermux-exec-linker-ld-preload.so").takeIf(File::isFile)?.let {
            environment["LD_PRELOAD"] = it.path
        }
    }

    private fun installDependencies(pack: Pack, onProgress: (String) -> Unit, seen: MutableSet<String>) {
        if (!seen.add(pack.id) || isInstalled(pack)) return
        pack.requires.forEach { dependency ->
            installDependencies(PACKS.first { it.id == dependency }, onProgress, seen)
        }
        if (pack.packages.isNotEmpty()) {
            onProgress("Baixando ${pack.name} e dependencias...")
            val debDirectory = File(temp, "awb-debs").apply { deleteRecursively(); mkdirs() }
            val index = loadRepositoryIndex()
            val packages = resolvePackages(pack.packages, index)
            packages.forEachIndexed { position, record ->
                onProgress("${pack.name}: ${position + 1}/${packages.size} ${record.name} ${record.version}")
                downloadRecord(record, debDirectory)
            }
            val script = """
                set -e
                cd ${quote(temp.path)}/awb-debs
                rm -rf ${quote(temp.path)}/awb-stage
                mkdir -p ${quote(temp.path)}/awb-stage
                for f in *.deb; do dpkg-deb -x "${'$'}f" ${quote(temp.path)}/awb-stage; done
                if [ -d ${quote(temp.path)}/awb-stage/data/data/com.termux/files/usr ]; then
                  cp -a ${quote(temp.path)}/awb-stage/data/data/com.termux/files/usr/. ${quote(prefix.path)}/
                elif [ -d ${quote(temp.path)}/awb-stage/usr ]; then
                  cp -a ${quote(temp.path)}/awb-stage/usr/. ${quote(prefix.path)}/
                fi
                rm -rf ${quote(temp.path)}/awb-stage ${quote(temp.path)}/awb-debs
            """.trimIndent()
            run(script, INSTALL_TIMEOUT_MS)
        }
        markerDirectory.mkdirs()
        File(markerDirectory, "${pack.id}.json").writeText(
            JSONObject()
                .put("id", pack.id)
                .put("installed_at", System.currentTimeMillis())
                .put("commands", JSONArray(pack.commands))
                .toString(),
        )
    }

    private fun ensureBase(onProgress: (String) -> Unit) {
        if (bash.isFile) {
            repairRuntimeTree()
            fixConfiguration()
            return
        }
        onProgress("Preparando runtime Linux interno assinado...")
        val staging = File(root.parentFile, "${root.name}-staging")
        staging.deleteRecursively()
        val stagingPrefix = File(staging, "usr")
        val links = mutableListOf<Pair<String, String>>()
        context.assets.open(BOOTSTRAP_ASSET).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "SYMLINKS.txt") {
                        val text = zip.bufferedReader().readText()
                        text.lineSequence().filter(String::isNotBlank).forEach { line ->
                            val split = line.split('←', limit = 2)
                            if (split.size == 2) links += split[0] to split[1].removePrefix("./")
                        }
                    } else {
                        val target = File(stagingPrefix, entry.name).canonicalFile
                        require(target == stagingPrefix.canonicalFile || target.path.startsWith(stagingPrefix.canonicalPath + File.separator))
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use(zip::copyTo)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        links.forEach { (rawTarget, rawLink) ->
            val link = File(stagingPrefix, rawLink)
            link.parentFile?.mkdirs()
            link.delete()
            val target = rawTarget.replace(TERMUX_PREFIX, prefix.path)
            Os.symlink(target, link.path)
        }
        root.deleteRecursively()
        check(staging.renameTo(root)) { "Falha ao ativar o runtime Linux interno." }
        home.mkdirs()
        temp.mkdirs()
        markerDirectory.mkdirs()
        repairRuntimeTree()
        fixConfiguration()
        check(bash.isFile) { "Bootstrap extraido sem bash." }
        run("echo AWB_LINUX_RUNTIME_OK", 30_000)
    }

    private fun fixConfiguration() {
        val apt = File(prefix, "etc/apt/apt.conf")
        apt.parentFile?.mkdirs()
        apt.writeText(
            """
            Dir "/";
            Dir::State "${prefix.path}/var/lib/apt/";
            Dir::State::status "${prefix.path}/var/lib/dpkg/status";
            Dir::Cache "${prefix.path}/var/cache/apt/";
            Dir::Log "${prefix.path}/var/log/apt/";
            Dir::Etc "${prefix.path}/etc/apt/";
            Dir::Etc::SourceList "${prefix.path}/etc/apt/sources.list";
            Dir::Etc::SourceParts "${prefix.path}/etc/apt/sources.list.d";
            Dir::Bin::dpkg "${prefix.path}/bin/dpkg";
            Dir::Bin::Methods "${prefix.path}/lib/apt/methods/";
            Acquire::https::CaInfo "${prefix.path}/etc/tls/cert.pem";
            Acquire::AllowInsecureRepositories "false";
            """.trimIndent() + "\n",
        )
        listOf("var/log/apt", "var/lib/apt/lists/partial", "var/cache/apt/archives/partial", "var/lib/dpkg/info")
            .forEach { File(prefix, it).mkdirs() }
    }

    private fun repairRuntimeTree() {
        listOf(
            File(prefix, "bin"),
            File(prefix, "libexec"),
            File(prefix, "lib/apt/methods"),
        ).forEach { directory ->
            directory.walkTopDown().filter(File::isFile).forEach { file ->
                check(file.setReadable(true, true)) { "Falha ao liberar leitura de ${file.name}." }
                check(file.setExecutable(true, true)) { "Falha ao liberar execucao de ${file.name}." }
            }
        }
        File(prefix, "lib").walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { file ->
            file.setReadable(true, true)
        }
    }

    private fun testPack(pack: Pack): JSONObject {
        val probes = pack.commands.mapNotNull { command ->
            File(prefix, "bin/$command").takeIf(File::exists)?.let { "$command --version 2>&1 | head -n 2" }
        }
        val output = if (probes.isEmpty()) "installed" else run(probes.joinToString("\n"), 120_000)
        return JSONObject()
            .put("id", pack.id)
            .put("installed", true)
            .put("commands", JSONArray(pack.commands))
            .put("test_output", output.take(8_192))
            .put("status", status())
    }

    private fun loadRepositoryIndex(): Map<String, RepositoryRecord> {
        val connection = URL(REPOSITORY_INDEX).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "Refrator-Runtime/${BuildConfig.VERSION_NAME} Android")
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        connection.disconnect()
        return text.split("\n\n").mapNotNull { paragraph ->
            val fields = linkedMapOf<String, String>()
            var current: String? = null
            paragraph.lineSequence().forEach { line ->
                if ((line.startsWith(' ') || line.startsWith('\t')) && current != null) {
                    val field = current
                    fields[field] = fields[field].orEmpty() + " " + line.trim()
                } else {
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        current = line.substring(0, separator)
                        fields[current.orEmpty()] = line.substring(separator + 1).trim()
                    }
                }
            }
            val name = fields["Package"] ?: return@mapNotNull null
            val filename = fields["Filename"] ?: return@mapNotNull null
            val sha256 = fields["SHA256"] ?: return@mapNotNull null
            RepositoryRecord(
                name = name,
                version = fields["Version"].orEmpty(),
                filename = filename,
                sha256 = sha256,
                size = fields["Size"]?.toLongOrNull() ?: 0,
                dependencies = parseDependencies(fields["Depends"].orEmpty()),
            )
        }.associateBy(RepositoryRecord::name)
    }

    private fun resolvePackages(requested: List<String>, index: Map<String, RepositoryRecord>): List<RepositoryRecord> {
        val resolved = linkedMapOf<String, RepositoryRecord>()
        fun visit(name: String) {
            if (name in resolved || name in BOOTSTRAP_PROVIDED) return
            val record = index[name] ?: error("Pacote $name nao existe no repositorio ARM64 configurado.")
            resolved[name] = record
            record.dependencies.forEach(::visit)
        }
        requested.forEach(::visit)
        return resolved.values.toList()
    }

    private fun parseDependencies(value: String): List<String> = value.split(',').mapNotNull { group ->
        group.split('|').asSequence().map { candidate ->
            candidate.trim().substringBefore(' ').substringBefore(':').trim()
        }.firstOrNull(String::isNotBlank)
    }

    private fun downloadRecord(record: RepositoryRecord, destination: File) {
        require(record.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "SHA-256 invalido para ${record.name}." }
        val target = File(destination, record.filename.substringAfterLast('/'))
        val connection = URL("$REPOSITORY_BASE/${record.filename}").openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Refrator-Runtime/${BuildConfig.VERSION_NAME} Android")
        require(connection.responseCode in 200..299) { "Download de ${record.name} falhou: HTTP ${connection.responseCode}." }
        require(record.size <= 0 || connection.contentLengthLong <= 0 || connection.contentLengthLong == record.size) {
            "Tamanho divergente para ${record.name}."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        connection.inputStream.buffered().use { input ->
            FileOutputStream(target).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_PACKAGE_DOWNLOAD) { "Pacote ${record.name} excedeu o limite individual." }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        connection.disconnect()
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(record.sha256, ignoreCase = true)) {
            "SHA-256 divergente para ${record.name}: esperado ${record.sha256}, recebido $actual."
        }
    }

    private fun isInstalled(pack: Pack): Boolean =
        File(markerDirectory, "${pack.id}.json").isFile && pack.commands.all { File(prefix, "bin/$it").exists() }

    private fun run(script: String, timeoutMs: Long): String {
        val command = commandLine(script) ?: error("Runtime base indisponivel.")
        val builder = ProcessBuilder(command).directory(workspace).redirectErrorStream(true)
        configureEnvironment(builder.environment())
        val process = builder.start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (output.length < MAX_OUTPUT) output.appendLine(line) }
            }
        }.apply { start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("Instalacao do runtime excedeu ${timeoutMs / 1000}s.\n$output")
        }
        reader.join(2_000)
        check(process.exitValue() == 0) { "Runtime saiu com codigo ${process.exitValue()}.\n$output" }
        return output.toString()
    }

    private fun quote(value: String) = "'${value.replace("'", "'\"'\"'")}'"

    private data class Pack(
        val id: String,
        val name: String,
        val description: String,
        val packages: List<String>,
        val commands: List<String>,
        val estimateMb: Int,
        val requires: List<String> = emptyList(),
    )

    private data class RepositoryRecord(
        val name: String,
        val version: String,
        val filename: String,
        val sha256: String,
        val size: Long,
        val dependencies: List<String>,
    )

    private companion object {
        const val BOOTSTRAP_ASSET = "bootstrap-aarch64.zip"
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val INSTALL_TIMEOUT_MS = 1_800_000L
        const val MAX_OUTPUT = 512_000
        const val MAX_PACKAGE_DOWNLOAD = 256L * 1024 * 1024
        const val REPOSITORY_BASE = "https://packages.termux.dev/apt/termux-main"
        const val REPOSITORY_INDEX = "$REPOSITORY_BASE/dists/stable/main/binary-aarch64/Packages"
        val BOOTSTRAP_PROVIDED = setOf(
            "apt", "bash", "ca-certificates", "command-not-found", "coreutils", "curl", "dash", "debianutils",
            "dialog", "diffutils", "dos2unix", "dpkg", "ed", "findutils", "gawk", "gpgv", "grep", "gzip",
            "inetutils", "less", "libandroid-support", "libassuan", "libbz2", "libc++", "libcurl", "libevent",
            "libgcrypt", "libgmp", "libgnutls", "libgpg-error", "libiconv", "libidn2", "liblzma", "libmpfr",
            "libnettle", "libnghttp2", "libnghttp3", "libnpth", "libsmartcols", "libssh2", "libunbound",
            "libunistring", "nano", "ncurses", "openssl", "pcre2", "procps", "psmisc", "readline", "sed",
            "tar", "termux-am", "termux-am-socket", "termux-core", "termux-exec", "termux-keyring", "termux-licenses",
            "termux-tools", "unzip", "util-linux", "xxhash", "xz-utils", "zlib", "zstd",
        )
        val INSTALL_LOCK = Any()
        val PACKS = listOf(
            Pack("linux-base", "Linux base", "Shell, apt/dpkg, curl, coreutils e termux-exec internos.", emptyList(), listOf("bash", "curl"), 32),
            Pack(
                "native-build", "C/C++ Build", "Clang/LLVM, LLD, NDK sysroot, Make, CMake, Ninja e pkg-config.",
                listOf("clang", "lld", "llvm", "libllvm", "ndk-sysroot", "libcompiler-rt", "make", "cmake", "ninja", "pkg-config"),
                listOf("clang", "clang++", "ld.lld", "make", "cmake", "ninja", "pkg-config"), 310, listOf("linux-base"),
            ),
            Pack("node", "Node.js", "Node.js e npm para TypeScript e ferramentas web.", listOf("nodejs", "npm"), listOf("node", "npm", "npx"), 85, listOf("linux-base")),
            Pack("java-android", "Java/Android", "OpenJDK 21 e Gradle para Java, Kotlin/JVM e builds Android.", listOf("openjdk-21", "gradle"), listOf("java", "javac", "jar", "gradle"), 430, listOf("linux-base")),
            Pack("go", "Go", "Compilador e ferramentas Go.", listOf("golang"), listOf("go", "gofmt"), 220, listOf("native-build")),
            Pack("rust", "Rust", "Rustc, Cargo e standard library ARM64 Android.", listOf("rust"), listOf("rustc", "cargo", "rustfmt"), 720, listOf("native-build")),
        )
    }
}
