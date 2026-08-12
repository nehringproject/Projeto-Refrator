package dev.agentworkbench

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

data class PublicDownloadResult(
    val path: String,
    val uri: String,
    val bytes: Long,
    val sha256: String,
)

/** Writes large files straight into Android's public Downloads collection. */
class PublicDownloadsAccess(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun safeWritableBytes(reserveBytes: Long): Long {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return (StatFs(root.absolutePath).availableBytes - reserveBytes).coerceAtLeast(0L)
    }

    fun importFile(
        path: String,
        source: File,
        mimeType: String,
        overwrite: Boolean,
    ): PublicDownloadResult {
        require(source.isFile) { "O arquivo do workspace nao existe." }
        return source.inputStream().buffered().use { input ->
            writeFrom(path, mimeType, overwrite, input, source.length())
        }
    }

    fun writeFrom(
        path: String,
        mimeType: String,
        overwrite: Boolean,
        input: InputStream,
        maxBytes: Long,
    ): PublicDownloadResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("Downloads publico direto exige Android 10 ou superior.")
        }
        return writeApi29(path, mimeType, overwrite) { output ->
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            DigestOutputStream(output, digest).use { digested ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total = Math.addExact(total, count.toLong())
                    require(total <= maxBytes) {
                        "Download excedeu o espaco seguro disponivel ($maxBytes bytes)."
                    }
                    digested.write(buffer, 0, count)
                }
            }
            require(total > 0L) { "O download retornou um arquivo vazio." }
            total to digest.digest().toHex()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeApi29(
        path: String,
        mimeType: String,
        overwrite: Boolean,
        copy: (java.io.OutputStream) -> Pair<Long, String>,
    ): PublicDownloadResult {
        val segments = normalize(path)
        val displayName = segments.last()
        val relativePath = buildString {
            append(Environment.DIRECTORY_DOWNLOADS)
            append('/')
            if (segments.size > 1) {
                append(segments.dropLast(1).joinToString("/"))
                append('/')
            }
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val existing = find(collection, displayName, relativePath)
        require(existing == null || overwrite) { "Downloads/$path ja existe." }
        val created = existing == null
        val target = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("Android recusou criar Downloads/$path.")
        try {
            val copied = resolver.openOutputStream(target, "rwt")?.buffered()?.use(copy)
                ?: throw FileNotFoundException("Nao foi possivel abrir Downloads/$path para escrita.")
            if (created) {
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            val (bytes, sha256) = copied
            return PublicDownloadResult(
                path = segments.joinToString("/"),
                uri = target.toString(),
                bytes = bytes,
                sha256 = sha256,
            )
        } catch (error: Throwable) {
            if (created) runCatching { resolver.delete(target, null, null) }
            throw error
        }
    }

    private fun find(collection: Uri, name: String, relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        return resolver.query(collection, projection, selection, arrayOf(name, relativePath), null)?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    private fun normalize(path: String): List<String> {
        var value = path.trim().replace('\\', '/')
        value = value.removePrefix("/storage/emulated/0/").removePrefix("/sdcard/")
        value = value.removePrefix("Download/").removePrefix("Downloads/").trim('/')
        val segments = value.split('/').filter(String::isNotBlank)
        require(segments.isNotEmpty()) { "Informe o nome do arquivo em Downloads." }
        require(segments.none { it == "." || it == ".." }) { "Travessia de pasta nao e permitida." }
        require(segments.all { it.length <= 255 && '\u0000' !in it }) { "Caminho de Downloads invalido." }
        return segments
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
