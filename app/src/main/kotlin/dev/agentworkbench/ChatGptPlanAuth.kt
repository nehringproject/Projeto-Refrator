package dev.agentworkbench

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * "Sign in with ChatGPT" for open-source apps (OpenAI's ChatGPT plan usage program): the user's
 * Go/Plus/Pro plan pays for inference on https://api.openai.com/v1/responses.
 *
 * Follows developers.openai.com/siwc/token-sharing-open-source: dynamic client registration with a
 * per-device ext_agent_host_id, PKCE S256 + state + nonce, a 127.0.0.1 loopback redirect, RS256 ID
 * token validation against OpenAI's JWKS, and the chatgpt.tokens.use.direct scope check. Tokens
 * live encrypted in the Android Keystore-backed [AndroidSecretStore] and are never logged.
 */
internal class ChatGptPlanAuth(context: Context) {
    private val appContext = context.applicationContext
    private val secrets = AndroidSecretStore(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    data class Account(
        val email: String?,
        val subject: String,
        val clientId: String,
        val idToken: String,
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMillis: Long,
        val scopes: Set<String>,
    )

    data class PlanModel(val slug: String, val displayName: String)

    fun account(): Account? = secrets.get(SECRET_ID)?.let { raw ->
        runCatching {
            val json = JSONObject(raw)
            Account(
                email = json.optString("email").ifBlank { null },
                subject = json.getString("subject"),
                clientId = json.getString("client_id"),
                idToken = json.getString("id_token"),
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifBlank { null },
                expiresAtMillis = json.getLong("expires_at"),
                scopes = json.optString("scopes").split(' ').filter(String::isNotBlank).toSet(),
            )
        }.getOrNull()
    }

    val isSignedIn: Boolean get() = account() != null

    fun signOut() {
        if (secrets.contains(SECRET_ID)) secrets.delete(SECRET_ID)
    }

    /** First run of the plan-usage welcome notice (OpenAI UI guidelines: show it only once). */
    fun consumeWelcome(): Boolean {
        if (preferences.getBoolean(KEY_WELCOMED, false)) return false
        preferences.edit().putBoolean(KEY_WELCOMED, true).apply()
        return true
    }

    /**
     * Runs the whole browser sign-in. [openBrowser] must show the URL in the system browser; this
     * suspends until OpenAI redirects back to the loopback listener (or [CALLBACK_TIMEOUT_MS]).
     */
    suspend fun signIn(openBrowser: (Uri) -> Unit): Account = withContext(Dispatchers.IO) {
        val previous = account()
        val clientId = previous?.clientId ?: DYNAMIC_CLIENT
        val verifier = randomUrlToken(48)
        val state = randomUrlToken(24)
        val nonce = randomUrlToken(24)
        val server = runCatching { ServerSocket(PREFERRED_PORT, 1, LOOPBACK) }
            .getOrElse { ServerSocket(0, 1, LOOPBACK) }
        server.use { listener ->
            listener.soTimeout = CALLBACK_TIMEOUT_MS
            val redirectUri = "http://127.0.0.1:${listener.localPort}/auth/callback"
            val query = buildList {
                add("client_id" to clientId)
                if (previous == null) add("agent_name_hint" to "Refrator")
                add("ext_agent_host_id" to hostId())
                previous?.let {
                    add("id_token_hint" to it.idToken)
                    it.email?.let { email -> add("login_hint" to email) }
                }
                add("response_type" to "code")
                add("redirect_uri" to redirectUri)
                add("scope" to SCOPES)
                add("resource" to RESOURCE)
                add("state" to state)
                add("nonce" to nonce)
                add("code_challenge_method" to "S256")
                add("code_challenge" to sha256Url(verifier))
            }
            withContext(Dispatchers.Main) { openBrowser(Uri.parse("$AUTHORIZE_URL?${formEncode(query)}")) }

            val callback = awaitCallback(listener)
            require(callback["state"] == state) { "Resposta de login inválida (state)." }
            callback["error"]?.let { error ->
                error(if (error == "access_denied") "Login cancelado." else "Login recusado: $error")
            }
            val code = callback["code"] ?: error("A OpenAI não devolveu um código de autorização.")
            val issuedClientId = callback["client_id"]?.takeIf(String::isNotBlank)
            if (clientId != DYNAMIC_CLIENT && issuedClientId != null && issuedClientId != clientId) {
                error("A OpenAI devolveu um client_id diferente do registrado; login descartado.")
            }
            val finalClientId = if (clientId == DYNAMIC_CLIENT) {
                issuedClientId ?: error("Registro sem client_id emitido.")
            } else {
                clientId
            }
            val tokens = postForm(
                TOKEN_URL,
                listOf(
                    "grant_type" to "authorization_code",
                    "client_id" to finalClientId,
                    "code" to code,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirectUri,
                    "resource" to RESOURCE,
                ),
            )
            // A different account here is the user's explicit choice in OpenAI's account picker,
            // so it simply replaces the stored one.
            val account = accountFrom(tokens, finalClientId, expectedNonce = nonce, fallbackRefresh = null)
            save(account)
            account
        }
    }

    /** A valid access token, refreshed when it is about to expire; null when signed out. */
    suspend fun validAccessToken(): String? = refreshLock.withLock {
        val current = account() ?: return@withLock null
        if (current.expiresAtMillis - System.currentTimeMillis() > REFRESH_MARGIN_MS) {
            return@withLock current.accessToken
        }
        val refreshToken = current.refreshToken ?: return@withLock current.accessToken
        withContext(Dispatchers.IO) {
            runCatching {
                val tokens = postForm(
                    TOKEN_URL,
                    listOf(
                        "grant_type" to "refresh_token",
                        "client_id" to current.clientId,
                        "refresh_token" to refreshToken,
                        "resource" to RESOURCE,
                    ),
                )
                accountFrom(tokens, current.clientId, expectedNonce = null, fallbackRefresh = refreshToken, previous = current)
                    .also(::save)
                    .accessToken
            }.getOrElse { error ->
                // invalid_grant means the refresh token is gone: the user has to sign in again.
                if (error.message.orEmpty().contains("invalid_grant")) signOut()
                null
            }
        }
    }

    /** Models this account may use with its plan (GET /v1/models, entries with visibility "list"). */
    suspend fun listModels(): List<PlanModel> = withContext(Dispatchers.IO) {
        val token = validAccessToken() ?: return@withContext emptyList()
        val connection = (URL("$RESOURCE/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val models = root.optJSONArray("models") ?: root.optJSONArray("data") ?: return@withContext emptyList()
            (0 until models.length()).mapNotNull { index ->
                val model = models.optJSONObject(index) ?: return@mapNotNull null
                if (model.has("visibility") && model.optString("visibility") != "list") return@mapNotNull null
                val slug = model.optString("slug").ifBlank { model.optString("id") }.ifBlank { return@mapNotNull null }
                PlanModel(slug, model.optString("display_name").ifBlank { slug })
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun accountFrom(
        tokens: JSONObject,
        clientId: String,
        expectedNonce: String?,
        fallbackRefresh: String?,
        previous: Account? = null,
    ): Account {
        val idToken = tokens.optString("id_token").ifBlank { previous?.idToken ?: error("Resposta sem id_token.") }
        val claims = if (tokens.has("id_token")) verifyIdToken(idToken, clientId, expectedNonce) else null
        val scopes = tokens.optString("scope").split(' ').filter(String::isNotBlank).toSet()
            .ifEmpty { previous?.scopes.orEmpty() }
        require(PLAN_SCOPE in scopes) {
            "A conta não autorizou o uso do plano do ChatGPT (escopo $PLAN_SCOPE ausente)."
        }
        val subject = claims?.getString("sub") ?: previous?.subject ?: error("ID token sem sub.")
        previous?.let { require(it.subject == subject) { "O token renovado pertence a outra conta." } }
        return Account(
            email = claims?.optString("email")?.ifBlank { null } ?: previous?.email,
            subject = subject,
            clientId = clientId,
            idToken = idToken,
            accessToken = tokens.getString("access_token"),
            refreshToken = tokens.optString("refresh_token").ifBlank { fallbackRefresh },
            expiresAtMillis = System.currentTimeMillis() + tokens.optLong("expires_in", 3_600) * 1_000,
            scopes = scopes,
        )
    }

    /** RS256 signature against OpenAI's JWKS, plus iss / aud / exp / nonce. Returns the claims. */
    private fun verifyIdToken(token: String, clientId: String, expectedNonce: String?): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3) { "ID token malformado." }
        val header = JSONObject(String(base64Url(parts[0]), StandardCharsets.UTF_8))
        val claims = JSONObject(String(base64Url(parts[1]), StandardCharsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Algoritmo do ID token inesperado." }
        val jwks = getJson(JWKS_URL).getJSONArray("keys")
        val kid = header.optString("kid")
        val jwk = (0 until jwks.length()).map(jwks::getJSONObject).firstOrNull { it.optString("kid") == kid }
            ?: error("Chave de assinatura do ID token não encontrada.")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(
                BigInteger(1, base64Url(jwk.getString("n"))),
                BigInteger(1, base64Url(jwk.getString("e"))),
            ),
        )
        val valid = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
            verify(base64Url(parts[2]))
        }
        require(valid) { "Assinatura do ID token inválida." }
        require(claims.optString("iss") == ISSUER) { "Emissor do ID token inválido." }
        val audience = claims.opt("aud")
        val audienceOk = when (audience) {
            is String -> audience == clientId
            is org.json.JSONArray -> (0 until audience.length()).any { audience.optString(it) == clientId }
            else -> false
        }
        require(audienceOk) { "Audiência do ID token inválida." }
        require(claims.optLong("exp") * 1_000 > System.currentTimeMillis() - CLOCK_SKEW_MS) { "ID token expirado." }
        expectedNonce?.let { require(claims.optString("nonce") == it) { "Nonce do ID token inválido." } }
        return claims
    }

    private fun save(account: Account) {
        secrets.put(
            SECRET_ID,
            JSONObject()
                .put("email", account.email.orEmpty())
                .put("subject", account.subject)
                .put("client_id", account.clientId)
                .put("id_token", account.idToken)
                .put("access_token", account.accessToken)
                .put("refresh_token", account.refreshToken.orEmpty())
                .put("expires_at", account.expiresAtMillis)
                .put("scopes", account.scopes.joinToString(" "))
                .toString(),
        )
    }

    /** Stable, opaque per-device host id (urn:uuid form accepted by OpenAI); not a credential. */
    private fun hostId(): String = preferences.getString(KEY_HOST_ID, null) ?: "urn:uuid:${UUID.randomUUID()}".also {
        preferences.edit().putString(KEY_HOST_ID, it).apply()
    }

    private fun awaitCallback(listener: ServerSocket): Map<String, String> {
        while (true) {
            val socket = listener.accept()
            socket.use { client ->
                val requestLine = readRequestLine(client) ?: return@use
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                if (!target.startsWith("/auth/callback")) {
                    respond(client, 404, "Não encontrado")
                    return@use
                }
                val params = Uri.parse("http://127.0.0.1$target").let { uri ->
                    uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
                }
                respond(client, 200, if (params.containsKey("code")) SUCCESS_PAGE else FAILURE_PAGE)
                return params
            }
        }
    }

    private fun readRequestLine(socket: Socket): String? {
        val input = socket.getInputStream()
        val line = ByteArrayOutputStream()
        while (line.size() < 8_192) {
            val byte = input.read()
            if (byte == -1 || byte == '\n'.code) break
            line.write(byte)
        }
        return line.toString(StandardCharsets.UTF_8.name()).trimEnd('\r').ifBlank { null }
    }

    private fun respond(socket: Socket, status: Int, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun postForm(url: String, fields: List<Pair<String, String>>): JSONObject {
        val body = formEncode(fields).toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body) }
            val ok = connection.responseCode in 200..299
            val text = (if (ok) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) {
                val code = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                error("Falha na troca de token (${connection.responseCode}${if (code.isNotBlank()) ": $code" else ""}).")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
        const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
        const val JWKS_URL = "https://auth.openai.com/.well-known/jwks.json"
        const val ISSUER = "https://auth.openai.com"
        const val RESOURCE = "https://api.openai.com/v1"
        const val DYNAMIC_CLIENT = "dynamic_agent_client"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
        const val PREFERRED_PORT = 1455
        const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000
        const val REFRESH_MARGIN_MS = 2 * 60 * 1000L
        const val CLOCK_SKEW_MS = 5 * 60 * 1000L
        const val SECRET_ID = "chatgpt_plan_account"
        const val PREFERENCES = "refrator_chatgpt_plan"
        const val KEY_HOST_ID = "ext_agent_host_id"
        const val KEY_WELCOMED = "plan_welcome_shown"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        val refreshLock = Mutex()

        // "Voltar ao Refrator" uses the refrator://return deep link declared on MainActivity.
        const val SUCCESS_PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="background:#070707;color:#f2f2f2;font-family:sans-serif;text-align:center;padding:48px 24px">
<h2>Conta do ChatGPT conectada</h2><p style="color:#9a9a9a">Você já pode voltar ao Refrator.</p>
<p><a href="refrator://return" style="display:inline-block;margin-top:16px;padding:14px 28px;border-radius:24px;background:#f2f2f2;color:#070707;text-decoration:none;font-weight:600">Voltar ao Refrator</a></p></body>"""
        const val FAILURE_PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="background:#070707;color:#f2f2f2;font-family:sans-serif;text-align:center;padding:48px 24px">
<h2>Login não concluído</h2><p style="color:#9a9a9a">Volte ao Refrator e tente de novo.</p>
<p><a href="refrator://return" style="color:#f2f2f2">Voltar ao Refrator</a></p></body>"""

        fun randomUrlToken(bytes: Int): String =
            Base64.encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        fun sha256Url(value: String): String = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

        fun base64Url(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        fun formEncode(fields: List<Pair<String, String>>): String =
            fields.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
            }
    }
}
