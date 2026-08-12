package dev.agentworkbench

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class WorkspacePatchResult(
    val path: String,
    val addedLines: Int,
    val removedLines: Int,
    val originalSha256: String,
    val updatedSha256: String,
)

class WorkspaceEditor(
    context: Context,
    workspaceRoot: File,
) {
    private val workspace = workspaceRoot.canonicalFile.apply { mkdirs() }
    private val checkpoints = File(context.filesDir, "workspace-checkpoints").apply { mkdirs() }
    private val trash = File(context.filesDir, "workspace-trash").apply { mkdirs() }
    private val snapshots = File(context.filesDir, "workspace-snapshots").apply { mkdirs() }

    fun stat(path: String): JSONObject {
        val file = resolve(path, mustExist = true)
        return JSONObject()
            .put("path", relative(file))
            .put("type", if (file.isDirectory) "directory" else "file")
            .put("size", if (file.isFile) file.length() else JSONObject.NULL)
            .put("modified_at", file.lastModified())
            .put("readable", file.canRead())
            .put("writable", file.canWrite())
            .put("executable", file.canExecute())
            .put("sha256", if (file.isFile && file.length() <= MAX_HASH_FILE_BYTES) sha256(file) else JSONObject.NULL)
    }

    fun tree(path: String, depth: Int, includeHidden: Boolean): JSONArray {
        val start = resolve(path, mustExist = true)
        require(start.isDirectory) { "O caminho não é uma pasta." }
        val result = JSONArray()
        var count = 0
        fun visit(directory: File, level: Int) {
            if (count >= MAX_TREE_ENTRIES || level > depth) return
            directory.listFiles()
                .orEmpty()
                .filter { includeHidden || !it.name.startsWith('.') }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { child ->
                    if (count >= MAX_TREE_ENTRIES) return@forEach
                    result.put(
                        JSONObject()
                            .put("path", relative(child))
                            .put("type", if (child.isDirectory) "directory" else "file")
                            .put("size", if (child.isFile) child.length() else JSONObject.NULL)
                            .put("modified_at", child.lastModified()),
                    )
                    count += 1
                    if (child.isDirectory) visit(child, level + 1)
                }
        }
        visit(start, 0)
        return result
    }

    fun mkdir(path: String): String {
        val target = resolve(path, mustExist = false)
        require(!target.exists()) { "O caminho já existe." }
        require(target.mkdirs()) { "Não foi possível criar a pasta." }
        return relative(target)
    }

    fun copy(sourcePath: String, destinationPath: String, overwrite: Boolean): JSONObject {
        val source = resolve(sourcePath, mustExist = true)
        val destination = resolve(destinationPath, mustExist = false)
        require(source != workspace) { "Copiar a raiz inteira não é permitido nesta ferramenta." }
        require(!destination.exists() || overwrite) { "Destino já existe; use overwrite=true." }
        require(!source.isDirectory || !destination.canonicalPath.startsWith(source.canonicalPath + File.separator)) {
            "Destino não pode ficar dentro da própria origem."
        }
        val budget = CopyBudget()
        copyRecursive(source, destination, overwrite, budget)
        return JSONObject()
            .put("source", relative(source))
            .put("destination", relative(destination))
            .put("files", budget.files)
            .put("bytes", budget.bytes)
    }

    fun move(sourcePath: String, destinationPath: String, overwrite: Boolean): JSONObject {
        val source = resolve(sourcePath, mustExist = true)
        val destination = resolve(destinationPath, mustExist = false)
        require(source != workspace) { "Mover a raiz do workspace não é permitido." }
        require(!destination.exists() || overwrite) { "Destino já existe; use overwrite=true." }
        destination.parentFile?.mkdirs()
        if (destination.exists()) moveToTrash(destination)
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
        return JSONObject()
            .put("source", sourcePath)
            .put("destination", relative(destination))
    }

    fun trash(path: String): JSONObject {
        val target = resolve(path, mustExist = true)
        require(target != workspace) { "A raiz do workspace não pode ser enviada para a lixeira." }
        return moveToTrash(target)
    }

    fun applyUnifiedPatch(path: String, patch: String): WorkspacePatchResult {
        val target = resolve(path, mustExist = true)
        require(target.isFile) { "Patch exige um arquivo existente." }
        require(target.length() <= MAX_EDIT_FILE_BYTES) { "Arquivo maior que 1 MiB." }
        require(patch.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATCH_BYTES) { "Patch maior que 256 KiB." }
        val original = target.readText(Charsets.UTF_8)
        val result = UnifiedPatch.apply(original, patch)
        atomicWrite(target, result.text.toByteArray(Charsets.UTF_8))
        return WorkspacePatchResult(
            path = relative(target),
            addedLines = result.added,
            removedLines = result.removed,
            originalSha256 = sha256(original.toByteArray(Charsets.UTF_8)),
            updatedSha256 = sha256(result.text.toByteArray(Charsets.UTF_8)),
        )
    }

    fun replaceLines(
        path: String,
        startLine: Int,
        endLine: Int,
        replacement: String,
        expectedSha256: String?,
    ): JSONObject {
        val target = resolve(path, mustExist = true)
        require(target.isFile && target.length() <= MAX_EDIT_FILE_BYTES) { "Arquivo inválido ou maior que 1 MiB." }
        require(replacement.toByteArray(Charsets.UTF_8).size <= MAX_PATCH_BYTES) { "Substituição maior que 256 KiB." }
        val original = target.readText(Charsets.UTF_8)
        val originalHash = sha256(original.toByteArray(Charsets.UTF_8))
        if (!expectedSha256.isNullOrBlank()) {
            require(originalHash.equals(expectedSha256, ignoreCase = true)) {
                "Arquivo mudou desde a leitura; hash esperado não confere."
            }
        }
        val trailingNewline = original.endsWith('\n')
        val lines = original.removeSuffix("\n").split('\n').toMutableList()
        require(startLine in 1..(lines.size + 1)) { "Linha inicial inválida." }
        require(endLine >= startLine - 1 && endLine <= lines.size) { "Linha final inválida." }
        val replacementLines = if (replacement.isEmpty()) emptyList() else replacement.removeSuffix("\n").split('\n')
        val from = startLine - 1
        val to = endLine
        val updated = lines.toMutableList().apply {
            subList(from, to).clear()
            addAll(from, replacementLines)
        }.joinToString("\n") + if (trailingNewline || replacement.endsWith('\n')) "\n" else ""
        atomicWrite(target, updated.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("path", relative(target))
            .put("original_sha256", originalHash)
            .put("updated_sha256", sha256(updated.toByteArray(Charsets.UTF_8)))
            .put("replaced_from", startLine)
            .put("replaced_to", endLine)
    }

    fun checkpoint(label: String): JSONObject {
        val id = UUID.randomUUID().toString()
        val archive = File(checkpoints, "$id.zip")
        var files = 0
        var bytes = 0L
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            workspace.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    require(file.length() <= MAX_ATOMIC_WRITE_BYTES) {
                        "Checkpoint contém arquivo maior que 8 MiB: ${relative(file)}"
                    }
                    files += 1
                    bytes += file.length()
                    require(files <= MAX_CHECKPOINT_FILES && bytes <= MAX_CHECKPOINT_BYTES) {
                        "Workspace excede o limite de checkpoint."
                    }
                    zip.putNextEntry(ZipEntry(relative(file).replace(File.separatorChar, '/')))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        val metadata = JSONObject()
            .put("id", id)
            .put("label", label.take(120))
            .put("created_at", System.currentTimeMillis())
            .put("files", files)
            .put("bytes", bytes)
            .put("sha256", sha256(archive))
        atomicWrite(File(checkpoints, "$id.json"), metadata.toString().toByteArray(Charsets.UTF_8))
        pruneCheckpoints()
        return metadata
    }

    fun restoreCheckpoint(id: String): JSONObject {
        require(id.matches(ID_PATTERN)) { "ID de checkpoint inválido." }
        val archive = File(checkpoints, "$id.zip")
        require(archive.isFile && archive.length() <= MAX_CHECKPOINT_ARCHIVE_BYTES) { "Checkpoint não existe ou é inválido." }
        var restored = 0
        var bytes = 0L
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(!entry.isDirectory) { "Checkpoint contém pasta inesperada." }
                require(entry.size in 0..MAX_ATOMIC_WRITE_BYTES.toLong()) {
                    "Entrada do checkpoint tem tamanho inválido ou maior que 8 MiB."
                }
                val target = resolve(entry.name, mustExist = false)
                require(target.canonicalPath.startsWith(workspace.path + File.separator)) { "Checkpoint tentou escapar do workspace." }
                restored += 1
                bytes += entry.size.coerceAtLeast(0)
                require(restored <= MAX_CHECKPOINT_FILES && bytes <= MAX_CHECKPOINT_BYTES) { "Checkpoint excede limites." }
                zip.getInputStream(entry).use { input ->
                    atomicWrite(target, input.readBytes())
                }
            }
        }
        return JSONObject()
            .put("checkpoint_id", id)
            .put("restored_files", restored)
            .put("restored_bytes", bytes)
            .put("note", "Arquivos extras criados depois do checkpoint foram preservados.")
    }

    fun checkpointDiff(id: String): JSONObject {
        require(id.matches(ID_PATTERN)) { "ID de checkpoint inválido." }
        val archive = File(checkpoints, "$id.zip")
        require(archive.isFile) { "Checkpoint não existe." }
        val before = linkedMapOf<String, String>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    before[entry.name] = zip.getInputStream(entry).use { sha256(it.readBytes()) }
                }
            }
        }
        val now = workspace.walkTopDown()
            .filter { it.isFile }
            .associate { relative(it).replace(File.separatorChar, '/') to sha256(it) }
        return JSONObject()
            .put("checkpoint_id", id)
            .put("added", JSONArray((now.keys - before.keys).sorted()))
            .put("removed", JSONArray((before.keys - now.keys).sorted()))
            .put("modified", JSONArray((before.keys intersect now.keys).filter { before[it] != now[it] }.sorted()))
    }

    fun createSnapshot(): JSONObject {
        val id = UUID.randomUUID().toString()
        val entries = JSONObject()
        var count = 0
        workspace.walkTopDown().filter { it.isFile }.forEach { file ->
            count += 1
            require(count <= MAX_SNAPSHOT_FILES) { "Workspace possui arquivos demais para snapshot." }
            entries.put(
                relative(file).replace(File.separatorChar, '/'),
                JSONObject()
                    .put("size", file.length())
                    .put("modified_at", file.lastModified())
                    .put("sha256", if (file.length() <= MAX_HASH_FILE_BYTES) sha256(file) else JSONObject.NULL),
            )
        }
        val root = JSONObject()
            .put("id", id)
            .put("created_at", System.currentTimeMillis())
            .put("entries", entries)
        atomicWrite(File(snapshots, "$id.json"), root.toString().toByteArray(Charsets.UTF_8))
        return JSONObject().put("snapshot_id", id).put("files", count)
    }

    fun changesSinceSnapshot(id: String): JSONObject {
        require(id.matches(ID_PATTERN)) { "ID de snapshot inválido." }
        val file = File(snapshots, "$id.json")
        require(file.isFile && file.length() <= MAX_SNAPSHOT_BYTES) { "Snapshot não existe ou é inválido." }
        val old = JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("entries")
        val current = createCurrentEntries()
        val oldKeys = old.keys().asSequence().toSet()
        val currentKeys = current.keys().asSequence().toSet()
        val modified = (oldKeys intersect currentKeys).filter { path ->
            old.getJSONObject(path).toString() != current.getJSONObject(path).toString()
        }.sorted()
        return JSONObject()
            .put("snapshot_id", id)
            .put("added", JSONArray((currentKeys - oldKeys).sorted()))
            .put("removed", JSONArray((oldKeys - currentKeys).sorted()))
            .put("modified", JSONArray(modified))
    }

    private fun createCurrentEntries(): JSONObject = JSONObject().also { entries ->
        workspace.walkTopDown().filter { it.isFile }.take(MAX_SNAPSHOT_FILES + 1).forEachIndexed { index, file ->
            require(index < MAX_SNAPSHOT_FILES) { "Workspace possui arquivos demais para comparação." }
            entries.put(
                relative(file).replace(File.separatorChar, '/'),
                JSONObject()
                    .put("size", file.length())
                    .put("modified_at", file.lastModified())
                    .put("sha256", if (file.length() <= MAX_HASH_FILE_BYTES) sha256(file) else JSONObject.NULL),
            )
        }
    }

    private fun moveToTrash(target: File): JSONObject {
        val id = UUID.randomUUID().toString()
        val destination = File(trash, id)
        try {
            Files.move(target.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(target.toPath(), destination.toPath())
        }
        val metadata = JSONObject()
            .put("trash_id", id)
            .put("original_path", relativePathBeforeMove(target))
            .put("deleted_at", System.currentTimeMillis())
        atomicWrite(File(trash, "$id.json"), metadata.toString().toByteArray(Charsets.UTF_8))
        return metadata
    }

    private fun relativePathBeforeMove(file: File): String =
        workspace.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun copyRecursive(source: File, destination: File, overwrite: Boolean, budget: CopyBudget) {
        if (source.isDirectory) {
            require(destination.exists() || destination.mkdirs()) { "Não foi possível criar ${relative(destination)}." }
            source.listFiles().orEmpty().forEach { child ->
                copyRecursive(child, File(destination, child.name), overwrite, budget)
            }
        } else {
            budget.files += 1
            budget.bytes += source.length()
            require(budget.files <= MAX_COPY_FILES && budget.bytes <= MAX_COPY_BYTES) { "Cópia excede limites." }
            destination.parentFile?.mkdirs()
            val options = if (overwrite) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            } else {
                arrayOf(StandardCopyOption.COPY_ATTRIBUTES)
            }
            Files.copy(source.toPath(), destination.toPath(), *options)
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        require(bytes.size <= MAX_ATOMIC_WRITE_BYTES) { "Escrita excede 8 MiB." }
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(".awb-", ".tmp", target.parentFile ?: workspace)
        try {
            temporary.writeBytes(bytes)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun resolve(path: String, mustExist: Boolean): File {
        require(path.isNotBlank() && path.length <= 1_024 && '\u0000' !in path) { "Caminho inválido." }
        val candidate = File(workspace, path).canonicalFile
        require(candidate == workspace || candidate.path.startsWith(workspace.path + File.separator)) {
            "O caminho tentou escapar do workspace."
        }
        if (mustExist) require(candidate.exists()) { "Caminho não existe: $path" }
        return candidate
    }

    private fun relative(file: File): String =
        if (file == workspace) "." else workspace.toPath().relativize(file.toPath()).toString()

    private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16_384)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun pruneCheckpoints() {
        val metadataFiles = checkpoints.listFiles { file -> file.extension == "json" }
            .orEmpty().sortedByDescending(File::lastModified)
        var retainedBytes = 0L
        metadataFiles.forEachIndexed { index, metadata ->
            val archive = File(checkpoints, "${metadata.nameWithoutExtension}.zip")
            retainedBytes += archive.length()
            if (index >= MAX_CHECKPOINTS || retainedBytes > MAX_CHECKPOINT_TOTAL_BYTES) {
                File(checkpoints, "${metadata.nameWithoutExtension}.zip").delete()
                metadata.delete()
            }
        }
    }

    private data class CopyBudget(var files: Int = 0, var bytes: Long = 0)

    private companion object {
        const val MAX_TREE_ENTRIES = 2_000
        const val MAX_EDIT_FILE_BYTES = 1L * 1_024 * 1_024
        const val MAX_PATCH_BYTES = 256 * 1_024
        const val MAX_ATOMIC_WRITE_BYTES = 8 * 1_024 * 1_024
        const val MAX_HASH_FILE_BYTES = 16L * 1_024 * 1_024
        const val MAX_COPY_FILES = 2_000
        const val MAX_COPY_BYTES = 64L * 1_024 * 1_024
        const val MAX_CHECKPOINTS = 100
        const val MAX_CHECKPOINT_TOTAL_BYTES = 1_024L * 1_024 * 1_024
        const val MAX_CHECKPOINT_FILES = 5_000
        const val MAX_CHECKPOINT_BYTES = 128L * 1_024 * 1_024
        const val MAX_CHECKPOINT_ARCHIVE_BYTES = 128L * 1_024 * 1_024
        const val MAX_SNAPSHOT_FILES = 10_000
        const val MAX_SNAPSHOT_BYTES = 8L * 1_024 * 1_024
        val ID_PATTERN = Regex("[0-9a-fA-F-]{36}")
    }
}

internal object UnifiedPatch {
    data class Result(val text: String, val added: Int, val removed: Int)

    fun apply(original: String, patch: String): Result {
        val hadTrailingNewline = original.endsWith('\n')
        val source = if (original.isEmpty()) emptyList() else original.removeSuffix("\n").split('\n')
        val patchLines = patch.replace("\r\n", "\n").split('\n')
        val output = mutableListOf<String>()
        var sourceCursor = 0
        var patchCursor = 0
        var added = 0
        var removed = 0
        var sawHunk = false

        while (patchCursor < patchLines.size) {
            val header = HUNK.matchEntire(patchLines[patchCursor])
            if (header == null) {
                patchCursor += 1
                continue
            }
            sawHunk = true
            val oldStart = header.groupValues[1].toInt()
            val oldCount = header.groupValues[2].takeIf(String::isNotBlank)?.toInt() ?: 1
            val newStart = header.groupValues[3].toInt()
            val newCount = header.groupValues[4].takeIf(String::isNotBlank)?.toInt() ?: 1
            val hunkStart = if (oldStart == 0) 0 else oldStart - 1
            val newIndex = if (newStart == 0) 0 else newStart - 1
            require(hunkStart >= sourceCursor && hunkStart <= source.size) { "Hunk fora de ordem ou além do arquivo." }
            output += source.subList(sourceCursor, hunkStart)
            require(output.size == newIndex) { "Posição nova do hunk não confere com o conteúdo produzido." }
            sourceCursor = hunkStart
            patchCursor += 1
            var consumedOld = 0
            var producedNew = 0

            while (patchCursor < patchLines.size && !patchLines[patchCursor].startsWith("@@")) {
                val line = patchLines[patchCursor]
                when {
                    line.startsWith(" ") -> {
                        val expected = line.drop(1)
                        require(source.getOrNull(sourceCursor) == expected) {
                            "Contexto do patch não confere na linha ${sourceCursor + 1}."
                        }
                        output += expected
                        sourceCursor += 1
                        consumedOld += 1
                        producedNew += 1
                    }
                    line.startsWith("-") -> {
                        val expected = line.drop(1)
                        require(source.getOrNull(sourceCursor) == expected) {
                            "Linha removida não confere na linha ${sourceCursor + 1}."
                        }
                        sourceCursor += 1
                        removed += 1
                        consumedOld += 1
                    }
                    line.startsWith("+") -> {
                        output += line.drop(1)
                        added += 1
                        producedNew += 1
                    }
                    line.startsWith("\\ No newline at end of file") || line.isEmpty() -> Unit
                    else -> throw IllegalArgumentException("Linha de patch inválida.")
                }
                patchCursor += 1
            }
            require(consumedOld == oldCount && producedNew == newCount) {
                "Contagem do hunk não confere: esperado -$oldCount/+$newCount, obtido -$consumedOld/+$producedNew."
            }
        }
        require(sawHunk) { "Patch não contém hunks @@ válidos." }
        output += source.subList(sourceCursor, source.size)
        val text = output.joinToString("\n") + if (hadTrailingNewline) "\n" else ""
        return Result(text, added, removed)
    }

    private val HUNK = Regex("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*")
}
