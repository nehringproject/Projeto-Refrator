package dev.agentworkbench.provider.http

import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProviderModelOption(
    val id: String,
    val name: String?,
    val owner: String?,
)

sealed interface ModelCatalogResult {
    data class Success(val models: List<ProviderModelOption>) : ModelCatalogResult
    data class Failure(val safeMessage: String) : ModelCatalogResult
}

object HttpModelCatalog {
    suspend fun fetch(config: HttpProviderConfig): ModelCatalogResult =
        withContext(Dispatchers.IO) {
            val catalogUri = deriveCatalogUri(config.endpoint)
                ?: return@withContext ModelCatalogResult.Failure(
                    "Não foi possível derivar o endpoint /models.",
                )
            val catalogConfig = config.copy(endpoint = catalogUri.toString())
            val validation = EndpointPolicy.validate(catalogConfig)
            if (validation is EndpointValidation.Rejected) {
                return@withContext ModelCatalogResult.Failure(validation.reason)
            }

            val connection = try {
                catalogUri.toURL().openConnection() as HttpURLConnection
            } catch (_: Exception) {
                return@withContext ModelCatalogResult.Failure(
                    "Endpoint do catálogo é inválido.",
                )
            }
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = config.connectTimeoutMillis
                connection.readTimeout = minOf(config.readTimeoutMillis, 30_000)
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                config.authorizationToken?.let { token ->
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    val detail = connection.errorStream
                        ?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { readBounded(it, MAX_ERROR_CHARS) }
                        .orEmpty()
                    return@withContext ModelCatalogResult.Failure(
                        safeHttpError(status, detail),
                    )
                }
                val body = connection.inputStream
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { readBounded(it, MAX_CATALOG_CHARS) }
                ModelCatalogResult.Success(parseModels(body))
            } catch (error: Exception) {
                ModelCatalogResult.Failure(safeFailure(error))
            } finally {
                connection.disconnect()
            }
        }

    internal fun deriveCatalogUri(endpoint: String): URI? {
        val original = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return null
        val path = original.path?.trimEnd('/') ?: return null
        val catalogPath = when {
            path.endsWith("/chat/completions") ->
                path.removeSuffix("/chat/completions") + "/models"

            path.endsWith("/responses") ->
                path.removeSuffix("/responses") + "/models"

            else -> return null
        }
        return runCatching {
            URI(
                original.scheme,
                null,
                original.host,
                original.port,
                catalogPath,
                null,
                null,
            )
        }.getOrNull()
    }

    internal fun parseModels(body: String): List<ProviderModelOption> {
        val data = JSONObject(body).optJSONArray("data")
            ?: throw IOException("Provider model catalog has no data array")
        return buildList {
            for (index in 0 until minOf(data.length(), MAX_MODELS)) {
                val model = data.optJSONObject(index) ?: continue
                val id = model.optString("id").trim()
                if (id.isBlank() || id.length > MAX_MODEL_ID_CHARS) continue
                val name = model.optString("name")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.take(MAX_LABEL_CHARS)
                val owner = model.optString("owned_by")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.take(MAX_LABEL_CHARS)
                add(ProviderModelOption(id = id, name = name, owner = owner))
            }
        }
            .distinctBy { it.id }
            .sortedBy { it.name?.lowercase() ?: it.id.lowercase() }
    }

    private fun readBounded(reader: BufferedReader, maxChars: Int): String {
        val output = StringBuilder()
        val buffer = CharArray(8_192)
        while (output.length < maxChars) {
            val count = reader.read(
                buffer,
                0,
                minOf(buffer.size, maxChars - output.length),
            )
            if (count < 0) return output.toString()
            output.append(buffer, 0, count)
        }
        if (reader.read() >= 0) throw IOException("Provider catalog exceeded safety limit")
        return output.toString()
    }

    private fun safeHttpError(status: Int, body: String): String {
        val detail = runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")
                ?: root.optString("message")
        }.getOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_SAFE_ERROR_CHARS)
            .orEmpty()
        return if (detail.isBlank()) {
            "Catálogo retornou HTTP $status."
        } else {
            "Catálogo retornou HTTP $status: $detail"
        }
    }

    private fun safeFailure(error: Exception): String =
        when (error) {
            is java.net.SocketTimeoutException -> "Catálogo de modelos expirou."
            is java.net.UnknownHostException -> "Host do provider não foi encontrado."
            is SSLException -> "Conexão segura com o provider falhou."
            is IOException -> "Falha ao ler o catálogo de modelos."
            else -> "Não foi possível carregar os modelos."
        }

    private const val MAX_CATALOG_CHARS = 4 * 1_024 * 1_024
    private const val MAX_ERROR_CHARS = 4_096
    private const val MAX_SAFE_ERROR_CHARS = 240
    private const val MAX_MODELS = 5_000
    private const val MAX_MODEL_ID_CHARS = 200
    private const val MAX_LABEL_CHARS = 200
}
