package dev.agentworkbench

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class ProjectCommand(
    val phase: String,
    val command: String,
    val mutatesFiles: Boolean,
)

data class ProjectProfile(
    val kind: String,
    val root: String,
    val markers: List<String>,
    val commands: List<ProjectCommand>,
    val confidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("root", root)
        .put("markers", JSONArray(markers))
        .put("confidence", confidence)
        .put(
            "commands",
            JSONArray(
                commands.map { command ->
                    JSONObject()
                        .put("phase", command.phase)
                        .put("command", command.command)
                        .put("mutates_files", command.mutatesFiles)
                },
            ),
        )
}

class CodeIntelligence(private val workspaceRoot: File) {
    private val workspace = workspaceRoot.canonicalFile.apply { mkdirs() }

    fun projectSummary(): JSONObject {
        val languageCounts = linkedMapOf<String, Int>()
        var files = 0
        var bytes = 0L
        var lines = 0L
        sourceFiles().forEach { file ->
            files += 1
            bytes += file.length()
            val language = languageFor(file)
            languageCounts[language] = (languageCounts[language] ?: 0) + 1
            if (file.length() <= MAX_PARSE_FILE_BYTES) {
                lines += file.useLines { sequence -> sequence.count().toLong() }
            }
        }
        val profiles = ProjectDetector.detect(workspace)
        return JSONObject()
            .put("workspace", workspace.path)
            .put("source_files", files)
            .put("source_bytes", bytes)
            .put("source_lines", lines)
            .put(
                "languages",
                JSONArray(
                    languageCounts.entries
                        .sortedByDescending(Map.Entry<String, Int>::value)
                        .map { JSONObject().put("language", it.key).put("files", it.value) },
                ),
            )
            .put("projects", JSONArray(profiles.map(ProjectProfile::toJson)))
            .put("engine", "índice lexical limitado; LSP/tree-sitter podem ser instalados como packs do runtime interno")
    }

    fun symbols(path: String?): JSONArray {
        val files = if (path.isNullOrBlank()) {
            sourceFiles()
        } else {
            listOf(resolve(path)).filter(File::isFile).asSequence()
        }
        val output = JSONArray()
        files.forEach { file ->
            if (output.length() >= MAX_RESULTS || file.length() > MAX_PARSE_FILE_BYTES) return@forEach
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                if (output.length() >= MAX_RESULTS) return@forEachIndexed
                symbolFor(line, file.extension.lowercase())?.let { (kind, name) ->
                    output.put(
                        JSONObject()
                            .put("name", name)
                            .put("kind", kind)
                            .put("path", relative(file))
                            .put("line", index + 1)
                            .put("preview", line.trim().take(240)),
                    )
                }
            }
        }
        return output
    }

    fun definition(symbol: String): JSONArray = searchSymbol(symbol, definitionsOnly = true)

    fun references(symbol: String): JSONArray = searchSymbol(symbol, definitionsOnly = false)

    fun dependencyGraph(): JSONObject {
        val nodes = linkedSetOf<String>()
        val edges = JSONArray()
        sourceFiles().forEach { file ->
            if (file.length() > MAX_PARSE_FILE_BYTES || edges.length() >= MAX_RESULTS) return@forEach
            val source = relative(file)
            nodes += source
            file.useLines(Charsets.UTF_8) { lines ->
                lines.take(500).forEach { line ->
                    importTarget(line)?.let { target ->
                        if (edges.length() < MAX_RESULTS) {
                            edges.put(JSONObject().put("from", source).put("imports", target))
                        }
                    }
                }
            }
        }
        return JSONObject()
            .put("nodes", JSONArray(nodes.toList()))
            .put("edges", edges)
            .put("truncated", edges.length() >= MAX_RESULTS)
    }

    fun findTests(): JSONArray {
        val output = JSONArray()
        sourceFiles().forEach { file ->
            if (output.length() >= MAX_RESULTS) return@forEach
            val path = relative(file).replace('\\', '/')
            val name = file.name.lowercase()
            if (
                "/test/" in "/$path" ||
                "/tests/" in "/$path" ||
                name.endsWith("test.kt") ||
                name.endsWith("test.java") ||
                name.startsWith("test_") ||
                name.endsWith("_test.py") ||
                name.endsWith(".spec.ts") ||
                name.endsWith(".test.ts") ||
                name.endsWith("_test.go")
            ) {
                output.put(JSONObject().put("path", path).put("language", languageFor(file)))
            }
        }
        return output
    }

    fun parseDiagnostics(text: String): JSONArray {
        require(text.length <= MAX_DIAGNOSTIC_INPUT_CHARS) { "Log de diagnóstico muito grande." }
        val output = JSONArray()
        text.lineSequence().forEach { line ->
            if (output.length() >= MAX_DIAGNOSTICS) return@forEach
            val match = DIAGNOSTIC.find(line) ?: return@forEach
            output.put(
                JSONObject()
                    .put("path", match.groupValues[1].take(1_024))
                    .put("line", match.groupValues[2].toIntOrNull() ?: JSONObject.NULL)
                    .put("column", match.groupValues[3].toIntOrNull() ?: JSONObject.NULL)
                    .put("severity", match.groupValues[4].lowercase().ifBlank { "diagnostic" })
                    .put("message", match.groupValues[5].trim().take(1_000)),
            )
        }
        return output
    }

    private fun searchSymbol(symbol: String, definitionsOnly: Boolean): JSONArray {
        require(SYMBOL_NAME.matches(symbol)) { "Símbolo inválido." }
        val word = Regex("(?<![A-Za-z0-9_])${Regex.escape(symbol)}(?![A-Za-z0-9_])")
        val output = JSONArray()
        sourceFiles().forEach { file ->
            if (output.length() >= MAX_RESULTS || file.length() > MAX_PARSE_FILE_BYTES) return@forEach
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                if (output.length() >= MAX_RESULTS || !word.containsMatchIn(line)) return@forEachIndexed
                val symbolDefinition = symbolFor(line, file.extension.lowercase())
                if (!definitionsOnly || symbolDefinition?.second == symbol) {
                    output.put(
                        JSONObject()
                            .put("path", relative(file))
                            .put("line", index + 1)
                            .put("definition", symbolDefinition?.second == symbol)
                            .put("preview", line.trim().take(300)),
                    )
                }
            }
        }
        return output
    }

    private fun sourceFiles(): Sequence<File> = workspace.walkTopDown()
        .onEnter { directory ->
            directory == workspace || directory.name !in IGNORED_DIRECTORIES
        }
        .filter { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }
        .take(MAX_SOURCE_FILES)

    private fun symbolFor(line: String, extension: String): Pair<String, String>? {
        val patterns = when (extension) {
            "kt", "kts" -> KOTLIN_SYMBOLS
            "java" -> JAVA_SYMBOLS
            "py" -> PYTHON_SYMBOLS
            "js", "jsx", "ts", "tsx" -> JAVASCRIPT_SYMBOLS
            "c", "h", "cc", "cpp", "cxx", "hpp" -> C_SYMBOLS
            "rs" -> RUST_SYMBOLS
            "go" -> GO_SYMBOLS
            else -> emptyList()
        }
        patterns.forEach { (kind, regex) ->
            regex.find(line)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)?.let { return kind to it }
        }
        return null
    }

    private fun importTarget(line: String): String? {
        val trimmed = line.trim()
        val match = IMPORT_PATTERNS.firstNotNullOfOrNull { it.find(trimmed) }
        return match?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)?.take(300)
    }

    private fun resolve(path: String): File {
        val file = File(workspace, path).canonicalFile
        require(file == workspace || file.path.startsWith(workspace.path + File.separator)) { "Caminho fora do workspace." }
        require(file.exists()) { "Caminho não existe." }
        return file
    }

    private fun relative(file: File): String = workspace.toPath().relativize(file.toPath()).toString()

    private fun languageFor(file: File): String = when (file.extension.lowercase()) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "py" -> "Python"
        "js", "jsx" -> "JavaScript"
        "ts", "tsx" -> "TypeScript"
        "c", "h" -> "C"
        "cc", "cpp", "cxx", "hpp" -> "C++"
        "rs" -> "Rust"
        "go" -> "Go"
        "dart" -> "Dart"
        "sh", "bash" -> "Shell"
        "xml" -> "XML"
        "json" -> "JSON"
        "yaml", "yml" -> "YAML"
        "md" -> "Markdown"
        else -> file.extension.ifBlank { "Other" }
    }

    companion object {
        private const val MAX_SOURCE_FILES = 10_000
        private const val MAX_PARSE_FILE_BYTES = 1L * 1_024 * 1_024
        private const val MAX_RESULTS = 500
        private const val MAX_DIAGNOSTICS = 200
        private const val MAX_DIAGNOSTIC_INPUT_CHARS = 1_048_576
        private val SYMBOL_NAME = Regex("[A-Za-z_][A-Za-z0-9_$]{0,199}")
        private val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "c", "h", "cc", "cpp", "cxx", "hpp",
            "rs", "go", "dart", "sh", "bash", "xml", "json", "yaml", "yml", "md",
        )
        private val IGNORED_DIRECTORIES = setOf(
            ".git", ".gradle", ".idea", ".kotlin", "build", "dist", "target", "node_modules", ".venv", "venv",
        )
        private val KOTLIN_SYMBOLS = listOf(
            "class" to Regex("\\b(?:data\\s+|sealed\\s+|enum\\s+)?class\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            "interface" to Regex("\\binterface\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            "object" to Regex("\\bobject\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            "function" to Regex("\\bfun\\s+(?:<[^>]+>\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
        )
        private val JAVA_SYMBOLS = listOf(
            "type" to Regex("\\b(?:class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            "method" to Regex("\\b[A-Za-z_][A-Za-z0-9_<>, ?\\[\\]]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
        )
        private val PYTHON_SYMBOLS = listOf(
            "class" to Regex("^\\s*class\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            "function" to Regex("^\\s*(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
        )
        private val JAVASCRIPT_SYMBOLS = listOf(
            "type" to Regex("\\b(?:class|interface|type|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"),
            "function" to Regex("\\bfunction\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\("),
            "function" to Regex("\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:async\\s*)?\\("),
        )
        private val C_SYMBOLS = listOf(
            "type" to Regex("\\b(?:struct|enum|class)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            "function" to Regex("\\b[A-Za-z_][A-Za-z0-9_* ]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;]*\\)\\s*\\{"),
        )
        private val RUST_SYMBOLS = listOf(
            "type" to Regex("\\b(?:struct|enum|trait|type)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            "function" to Regex("\\bfn\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
        )
        private val GO_SYMBOLS = listOf(
            "type" to Regex("\\btype\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            "function" to Regex("\\bfunc\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
        )
        private val IMPORT_PATTERNS = listOf(
            Regex("^(?:import|from)\\s+[\"']?([^\"'; ]+)"),
            Regex("^#include\\s+[<\"]([^>\"]+)"),
            Regex("^use\\s+([^;]+)"),
            Regex("^require\\([\"']([^\"']+)"),
        )
        private val DIAGNOSTIC = Regex(
            "(?:^|\\s)([^\\s:][^:]*?):(\\d+)(?::(\\d+))?:?\\s*(?:(error|warning|info|note)[: ]+)?(.+)$",
            RegexOption.IGNORE_CASE,
        )
    }
}

object ProjectDetector {
    fun detect(workspace: File): List<ProjectProfile> {
        val root = workspace.canonicalFile
        val profiles = mutableListOf<ProjectProfile>()
        if (File(root, "settings.gradle.kts").exists() || File(root, "settings.gradle").exists() || File(root, "build.gradle.kts").exists()) {
            val wrapper = if (File(root, "gradlew").exists()) "./gradlew" else "gradle"
            profiles += ProjectProfile(
                kind = "gradle",
                root = ".",
                markers = existing(root, "settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "gradlew"),
                commands = listOf(
                    ProjectCommand("build", "$wrapper --no-daemon assemble", false),
                    ProjectCommand("test", "$wrapper --no-daemon test", false),
                ),
                confidence = "high",
            )
        }
        if (File(root, "pom.xml").exists()) {
            val wrapper = if (File(root, "mvnw").exists()) "./mvnw" else "mvn"
            profiles += ProjectProfile("maven", ".", existing(root, "pom.xml", "mvnw"), listOf(ProjectCommand("test", "$wrapper test", false)), "high")
        }
        val packageJson = File(root, "package.json")
        if (packageJson.exists()) {
            val manager = when {
                File(root, "pnpm-lock.yaml").exists() -> "pnpm"
                File(root, "yarn.lock").exists() -> "yarn"
                else -> "npm"
            }
            val scripts = runCatching { JSONObject(packageJson.readText(Charsets.UTF_8)).optJSONObject("scripts") }.getOrNull()
            val commands = buildList {
                if (scripts?.has("format") == true) add(ProjectCommand("format", "$manager run format", true))
                if (scripts?.has("lint") == true) add(ProjectCommand("lint", "$manager run lint", false))
                if (scripts?.has("typecheck") == true) add(ProjectCommand("typecheck", "$manager run typecheck", false))
                if (scripts?.has("build") == true) add(ProjectCommand("build", "$manager run build", false))
                if (scripts?.has("test") == true) add(ProjectCommand("test", "$manager test -- --runInBand", false))
            }
            profiles += ProjectProfile("node", ".", existing(root, "package.json", "pnpm-lock.yaml", "yarn.lock", "package-lock.json"), commands, "high")
        }
        if (File(root, "pyproject.toml").exists() || File(root, "pytest.ini").exists() || File(root, "requirements.txt").exists()) {
            profiles += ProjectProfile(
                "python", ".", existing(root, "pyproject.toml", "pytest.ini", "requirements.txt"),
                listOf(ProjectCommand("test", "python -m pytest", false)), "medium",
            )
        }
        if (File(root, "Cargo.toml").exists()) {
            profiles += ProjectProfile(
                "rust", ".", existing(root, "Cargo.toml", "Cargo.lock"),
                listOf(ProjectCommand("format_check", "cargo fmt --check", false), ProjectCommand("test", "cargo test", false)), "high",
            )
        }
        if (File(root, "go.mod").exists()) {
            profiles += ProjectProfile(
                "go", ".", existing(root, "go.mod", "go.sum"),
                listOf(ProjectCommand("format_check", "test -z \"$(gofmt -l .)\"", false), ProjectCommand("test", "go test ./...", false)), "high",
            )
        }
        if (File(root, "CMakeLists.txt").exists()) {
            profiles += ProjectProfile(
                "cmake", ".", listOf("CMakeLists.txt"),
                listOf(
                    ProjectCommand("configure", "cmake -S . -B build", true),
                    ProjectCommand("build", "cmake --build build", true),
                    ProjectCommand("test", "ctest --test-dir build --output-on-failure", false),
                ), "high",
            )
        }
        if (File(root, "pubspec.yaml").exists()) {
            profiles += ProjectProfile(
                "flutter", ".", existing(root, "pubspec.yaml", "pubspec.lock"),
                listOf(ProjectCommand("analyze", "flutter analyze", false), ProjectCommand("test", "flutter test", false)), "high",
            )
        }
        return profiles
    }

    private fun existing(root: File, vararg names: String): List<String> = names.filter { File(root, it).exists() }
}

object VerificationEngine {
    fun selectProfile(workspace: File, requestedKind: String?): ProjectProfile {
        val profiles = ProjectDetector.detect(workspace)
        require(profiles.isNotEmpty()) { "Nenhum sistema de build suportado foi detectado." }
        return if (requestedKind.isNullOrBlank() || requestedKind == "auto") {
            profiles.first()
        } else {
            profiles.firstOrNull { it.kind == requestedKind }
                ?: throw IllegalArgumentException("Projeto $requestedKind não detectado.")
        }
    }

    fun command(profile: ProjectProfile, phases: Set<String>, includeMutatingFormat: Boolean): String {
        val selected = profile.commands.filter { command ->
            (phases.isEmpty() || command.phase in phases) && (includeMutatingFormat || !command.mutatesFiles)
        }
        require(selected.isNotEmpty()) { "Nenhuma etapa de verificação selecionada." }
        return buildString {
            appendLine("set -o pipefail")
            selected.forEach { step ->
                appendLine("printf '\\n=== AWB:${step.phase}:start ===\\n'")
                appendLine(step.command)
                appendLine("awb_exit=${'$'}?")
                appendLine("printf '=== AWB:${step.phase}:exit=%s ===\\n' \"${'$'}awb_exit\"")
                appendLine("test \"${'$'}awb_exit\" -eq 0 || exit \"${'$'}awb_exit\"")
            }
        }.trim()
    }
}
