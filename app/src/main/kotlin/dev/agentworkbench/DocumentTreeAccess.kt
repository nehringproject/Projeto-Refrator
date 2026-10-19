package dev.agentworkbench

import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class DocumentTreeStatus(
    val granted: Boolean,
    val displayName: String?,
    val uri: String?,
    val canRead: Boolean,
    val canWrite: Boolean,
)

/**
 * The external folder the user granted (SAF tree), often owned by another app such as Termux.
 *
 * Every query and stream goes through an *unstable* provider client: if the owner app dies
 * mid-operation we get an error instead of Android killing Refrator along with it (a stable
 * reference — what ContentResolver.query/openInputStream hold — makes the system kill clients
 * of a dying provider, and with it every Prisma at work).
 */
class DocumentTreeAccess(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private data class Doc(
        val uri: Uri,
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val flags: Int,
    ) {
        val isDirectory: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    fun status(): DocumentTreeStatus {
        val uri = storedUri()
        val permission = uri?.let { target ->
            resolver.persistedUriPermissions.firstOrNull { it.uri == target }
        }
        val root = if (uri != null && permission != null) runCatching { rootDoc(uri) }.getOrNull() else null
        val granted = permission != null && root != null
        return DocumentTreeStatus(
            granted = granted,
            displayName = root?.name,
            uri = uri?.toString(),
            canRead = granted && permission.isReadPermission,
            canWrite = granted && permission.isWritePermission &&
                (root.flags and Document.FLAG_DIR_SUPPORTS_CREATE) != 0,
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
        val tree = root(requireWrite = false)
        val start = resolve(tree, path, requireExists = true)
        require(start.isDirectory) { "O caminho externo não é uma pasta." }
        val output = mutableListOf<String>()
        fun visit(directory: Doc, prefix: String, level: Int) {
            if (output.size >= maxEntries) return
            children(tree, directory)
                .sortedWith(compareBy<Doc>({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { child ->
                    if (output.size >= maxEntries) return@forEach
                    val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    output += "${if (child.isDirectory) "d" else "f"} $relative" +
                        if (!child.isDirectory && child.size >= 0) " (${child.size} B)" else ""
                    if (child.isDirectory && level < depth) visit(child, relative, level + 1)
                }
        }
        visit(start, normalize(path).joinToString("/"), 0)
        if (output.size >= maxEntries) output += "… limite de $maxEntries entradas"
        return output
    }

    fun read(path: String, maxBytes: Int): String {
        val tree = root(requireWrite = false)
        val file = resolve(tree, path, requireExists = true)
        require(!file.isDirectory) { "O caminho externo não é um arquivo." }
        require(file.size < 0 || file.size <= maxBytes) {
            "Arquivo possui ${file.size} bytes; limite solicitado é $maxBytes."
        }
        return openInput(file.uri).buffered().use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 16_384))
            val buffer = ByteArray(4_096)
            while (output.size() <= maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            require(output.size() <= maxBytes) { "Arquivo excede $maxBytes bytes." }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    }

    fun write(path: String, content: String, overwrite: Boolean): DocumentTreeWriteResult {
        val segments = normalize(path)
        require(segments.isNotEmpty()) { "Informe um caminho de arquivo." }
        val tree = root(requireWrite = true)
        val parent = ensureDirectories(tree, segments.dropLast(1))
        val target = fileTarget(tree, parent, segments.last(), "text/plain", overwrite)
        openOutput(target.uri).bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(content) }
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
        val tree = root(requireWrite = true)
        val parent = ensureDirectories(tree, segments.dropLast(1))
        val created = children(tree, parent).none { it.name == segments.last() }
        val target = fileTarget(tree, parent, segments.last(), mimeType.ifBlank { "application/octet-stream" }, overwrite)
        try {
            openOutput(target.uri).buffered().use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            }
        } catch (error: Exception) {
            if (created) runCatching { DocumentsContract.deleteDocument(resolver, target.uri) }
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
        val tree = root(requireWrite = false)
        val source = resolve(tree, sourcePath, requireExists = true)
        require(!source.isDirectory) { "A origem SAF nao e um arquivo." }
        require(source.size < 0L || source.size <= maxBytes) {
            "Arquivo possui ${source.size} bytes; espaco seguro disponivel e $maxBytes."
        }
        val resolvedMime = mimeType.ifBlank { source.mime.ifBlank { "application/octet-stream" } }
        return openInput(source.uri).buffered().use { input ->
            downloads.writeFrom(
                path = destinationPath,
                mimeType = resolvedMime,
                overwrite = overwrite,
                input = input,
                maxBytes = maxBytes,
            )
        }
    }

    // ---- unstable provider access ----

    private inline fun <T> withProvider(uri: Uri, block: (ContentProviderClient) -> T): T {
        val client = resolver.acquireUnstableContentProviderClient(uri)
            ?: throw FileNotFoundException("O app dono da pasta externa não está disponível.")
        try {
            return block(client)
        } catch (error: RemoteException) {
            throw IOException("O app dono da pasta externa (${uri.authority}) fechou no meio da operação; tente de novo.", error)
        } finally {
            client.close()
        }
    }

    private fun query(tree: Uri, uri: Uri): List<Doc> = withProvider(uri) { client ->
        client.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    add(
                        Doc(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            id = id,
                            name = cursor.getString(1) ?: "<sem-nome>",
                            mime = cursor.getString(2).orEmpty(),
                            size = if (cursor.isNull(3)) -1L else cursor.getLong(3),
                            flags = if (cursor.isNull(4)) 0 else cursor.getInt(4),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun rootDoc(tree: Uri): Doc {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return query(tree, DocumentsContract.buildDocumentUriUsingTree(tree, id)).firstOrNull()
            ?: throw FileNotFoundException("A pasta externa concedida ficou indisponível.")
    }

    private fun children(tree: Uri, directory: Doc): List<Doc> =
        query(tree, DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory.id))

    private fun openInput(uri: Uri): InputStream {
        val descriptor = withProvider(uri) { it.openFile(uri, "r") }
            ?: throw FileNotFoundException("Não foi possível abrir o arquivo externo.")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    private fun openOutput(uri: Uri): OutputStream {
        val descriptor = withProvider(uri) { it.openFile(uri, "wt") }
            ?: throw FileNotFoundException("Não foi possível abrir o destino externo.")
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    }

    private fun ensureDirectories(tree: Uri, segments: List<String>): Doc {
        var parent = rootDoc(tree)
        segments.forEach { segment ->
            val existing = children(tree, parent).firstOrNull { it.name == segment }
            parent = when {
                existing == null -> create(tree, parent, Document.MIME_TYPE_DIR, segment)
                existing.isDirectory -> existing
                else -> error("$segment existe e não é uma pasta.")
            }
        }
        return parent
    }

    private fun fileTarget(tree: Uri, parent: Doc, name: String, mime: String, overwrite: Boolean): Doc {
        val existing = children(tree, parent).firstOrNull { it.name == name }
        require(existing == null || overwrite) { "Arquivo externo já existe." }
        require(existing == null || !existing.isDirectory) { "O destino externo não é um arquivo." }
        return existing ?: create(tree, parent, mime, name)
    }

    private fun create(tree: Uri, parent: Doc, mime: String, name: String): Doc {
        val uri = try {
            DocumentsContract.createDocument(resolver, parent.uri, mime, name)
        } catch (error: Exception) {
            throw IOException("Não foi possível criar $name na pasta externa: ${error.message}", error)
        } ?: error("Não foi possível criar $name na pasta externa.")
        return Doc(uri, DocumentsContract.getDocumentId(uri), name, mime, -1L, 0)
    }

    private fun resolve(tree: Uri, path: String, requireExists: Boolean): Doc {
        var current = rootDoc(tree)
        normalize(path).forEach { segment ->
            current = children(tree, current).firstOrNull { it.name == segment }
                ?: if (requireExists) {
                    throw FileNotFoundException("Caminho externo não existe: $path")
                } else {
                    return current
                }
        }
        return current
    }

    private fun root(requireWrite: Boolean): Uri {
        val status = status()
        require(status.granted && status.canRead) {
            "Escolha uma pasta externa na aba Ferramentas."
        }
        if (requireWrite) require(status.canWrite) { "A pasta externa não permite escrita." }
        return Uri.parse(status.uri)
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
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )
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
