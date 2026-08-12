package dev.agentworkbench

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.arm.aichat.gguf.GgufMetadataReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalGgufInfo(
    val displayName: String,
    val path: String,
    val bytes: Long,
    val sha256: String,
    val architecture: String? = null,
    val contextLength: Int? = null,
    val fileType: Int? = null,
)

class LocalGgufRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val modelDirectory = File(appContext.filesDir, "local-models")

    fun current(): LocalGgufInfo? {
        val path = preferences.getString(KEY_PATH, null) ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return LocalGgufInfo(
            displayName = preferences.getString(KEY_NAME, file.name) ?: file.name,
            path = file.absolutePath,
            bytes = file.length(),
            sha256 = preferences.getString(KEY_SHA256, "").orEmpty(),
            architecture = preferences.getString(KEY_ARCHITECTURE, null),
            contextLength = preferences.getInt(KEY_CONTEXT_LENGTH, -1).takeIf { it > 0 },
            fileType = preferences.getInt(KEY_FILE_TYPE, -1).takeIf { it >= 0 },
        )
    }

    suspend fun import(uri: Uri): LocalGgufInfo = withContext(Dispatchers.IO) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null else Pair(
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)),
                )
            }
        val displayName = metadata?.first?.takeIf(String::isNotBlank) ?: "model.gguf"
        require(displayName.endsWith(".gguf", ignoreCase = true)) { "Selecione um arquivo .gguf." }
        val declaredBytes = metadata?.second ?: -1L
        require(declaredBytes < 0 || declaredBytes in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            "O GGUF deve ter entre 64 MiB e 4 GiB neste aparelho."
        }
        require(modelDirectory.mkdirs() || modelDirectory.isDirectory) { "Nao foi possivel criar a pasta de modelos." }
        val temporary = File(modelDirectory, ".import-${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                val header = ByteArray(4)
                require(input.read(header) == 4 && header.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
                    "O arquivo nao possui cabecalho GGUF valido."
                }
                DigestOutputStream(FileOutputStream(temporary).buffered(), digest).use { output ->
                    output.write(header)
                    total = 4
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total = Math.addExact(total, count.toLong())
                        require(total <= MAX_MODEL_BYTES) { "O GGUF excede 4 GiB." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Nao foi possivel abrir o GGUF selecionado.")
            require(total >= MIN_MODEL_BYTES) { "O arquivo e pequeno demais para ser um modelo GGUF." }
            val parsed = FileInputStream(temporary).buffered().use { input ->
                GgufMetadataReader.create().readStructuredMetadata(input)
            }
            val architecture = parsed.architecture?.architecture?.takeIf(String::isNotBlank)
                ?: error("O GGUF não informa uma arquitetura de modelo compatível.")
            require(parsed.tensorCount > 0) { "O GGUF não contém tensores de modelo." }
            val contextLength = parsed.dimensions?.contextLength
            val fileType = parsed.architecture?.fileType
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(modelDirectory, "active-$sha256.gguf")
            if (target.isFile) {
                temporary.delete()
            } else {
                require(temporary.renameTo(target)) { "Nao foi possivel ativar o modelo importado." }
            }
            val previous = current()?.path?.let(::File)
            preferences.edit()
                .putString(KEY_SOURCE_URI, uri.toString())
                .putString(KEY_NAME, displayName.take(240))
                .putString(KEY_PATH, target.absolutePath)
                .putString(KEY_SHA256, sha256)
                .putString(KEY_ARCHITECTURE, architecture)
                .putInt(KEY_CONTEXT_LENGTH, contextLength ?: -1)
                .putInt(KEY_FILE_TYPE, fileType ?: -1)
                .apply()
            if (previous != null && previous != target) previous.delete()
            LocalGgufInfo(
                displayName = displayName,
                path = target.absolutePath,
                bytes = total,
                sha256 = sha256,
                architecture = architecture,
                contextLength = contextLength,
                fileType = fileType,
            )
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "local_gguf"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_NAME = "display_name"
        const val KEY_PATH = "private_path"
        const val KEY_SHA256 = "sha256"
        const val KEY_ARCHITECTURE = "architecture"
        const val KEY_CONTEXT_LENGTH = "context_length"
        const val KEY_FILE_TYPE = "file_type"
        const val MIN_MODEL_BYTES = 64L * 1024 * 1024
        const val MAX_MODEL_BYTES = 4L * 1024 * 1024 * 1024
    }
}
