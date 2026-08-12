package dev.agentworkbench

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Inet4Address
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class AgentBrowserSession private constructor(
    private val context: Context,
    private val workspace: File,
    val sessionKey: String,
    val profileName: String,
) {
    private val activity: Activity? = context as? Activity
    private var webView: WebView? = null
    private var pendingLoad: CompletableDeferred<Unit>? = null
    private var lastError: String? = null
    private var lastBlockedUrl: String? = null
    private var pendingDownload: PendingBrowserDownload? = null
    private val navigationRevision = AtomicLong(0)
    private val consoleEvents = ArrayDeque<JSONObject>()
    private val networkEvents = ArrayDeque<JSONObject>()
    private val errorEvents = ArrayDeque<JSONObject>()
    private val historyEvents = ArrayDeque<JSONObject>()
    private val recordedActions = ArrayDeque<JSONObject>()
    private var recording = false
    @Volatile private var controlOwner = "agent"

    suspend fun open(url: String, waitMillis: Long): String {
        requireAgentControl()
        val validated = withContext(Dispatchers.IO) {
            BrowserUrlPolicy.requirePublicHttps(url)
        }
        return withContext(Dispatchers.Main.immediate) {
            val browser = ensureWebView()
            lastError = null
            lastBlockedUrl = null
            pendingDownload = null
            val revision = navigationRevision.incrementAndGet()
            val completion = CompletableDeferred<Unit>()
            pendingLoad?.cancel()
            pendingLoad = completion
            browser.loadUrl(validated.toString())
            recordAction("open", JSONObject().put("url", validated.toString()))
            val finished = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
                completion.await()
                true
            } ?: false
            delay(waitMillis.coerceIn(0L, MAX_WAIT_MS))
            snapshotOnMain(
                maxChars = DEFAULT_SNAPSHOT_CHARS,
                maxElements = DEFAULT_MAX_ELEMENTS,
                extra = JSONObject()
                    .put("navigation_revision", revision)
                    .put("page_finished", finished),
            )
        }
    }

    suspend fun search(query: String, engine: String, waitMillis: Long): String {
        val url = when (engine.lowercase()) {
            "duckduckgo" -> "https://duckduckgo.com/?q=${Uri.encode(query)}"
            "google" -> "https://www.google.com/search?q=${Uri.encode(query)}"
            "brave" -> "https://search.brave.com/search?q=${Uri.encode(query)}"
            "bing" -> "https://www.bing.com/search?q=${Uri.encode(query)}"
            else -> throw IllegalArgumentException("Mecanismo de busca não suportado: $engine")
        }
        return open(url, waitMillis)
    }

    suspend fun snapshot(maxChars: Int, maxElements: Int): String =
        withContext(Dispatchers.Main.immediate) {
            snapshotOnMain(maxChars, maxElements, JSONObject())
        }

    suspend fun click(elementId: String, waitMillis: Long): String =
        withContext(Dispatchers.Main.immediate) {
            val script = """
                (() => {
                  const id = ${JSONObject.quote(elementId)};
                  const el = document.querySelector('[data-agent-id="' + CSS.escape(id) + '"]');
                  if (!el) return JSON.stringify({ok:false,error:'element_not_found',element_id:id});
                  el.scrollIntoView({block:'center',inline:'center'});
                  const before = location.href;
                  el.click();
                  return JSON.stringify({ok:true,element_id:id,before_url:before,tag:el.tagName});
                })()
            """.trimIndent()
            val action = evaluateJson(script)
            recordAction("click", JSONObject().put("element_id", elementId))
            delay(waitMillis.coerceIn(0L, MAX_WAIT_MS))
            snapshotOnMain(
                DEFAULT_SNAPSHOT_CHARS,
                DEFAULT_MAX_ELEMENTS,
                JSONObject().put("action", jsonValue(action)),
            )
        }

    suspend fun type(elementId: String, text: String, submit: Boolean, waitMillis: Long): String =
        withContext(Dispatchers.Main.immediate) {
            val script = """
                (() => {
                  const id = ${JSONObject.quote(elementId)};
                  const value = ${JSONObject.quote(text)};
                  const shouldSubmit = $submit;
                  const el = document.querySelector('[data-agent-id="' + CSS.escape(id) + '"]');
                  if (!el) return JSON.stringify({ok:false,error:'element_not_found',element_id:id});
                  el.focus();
                  if (el.isContentEditable) {
                    el.textContent = value;
                  } else if ('value' in el) {
                    const proto = Object.getPrototypeOf(el);
                    const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                    if (setter) setter.call(el, value); else el.value = value;
                  } else {
                    return JSON.stringify({ok:false,error:'element_not_editable',element_id:id});
                  }
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));
                  el.dispatchEvent(new Event('change',{bubbles:true}));
                  if (shouldSubmit) {
                    const form = el.form || el.closest('form');
                    if (form?.requestSubmit) form.requestSubmit();
                    else {
                      el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',bubbles:true}));
                      el.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',bubbles:true}));
                    }
                  }
                  return JSON.stringify({ok:true,element_id:id,submitted:shouldSubmit});
                })()
            """.trimIndent()
            val action = evaluateJson(script)
            recordAction(
                "type",
                JSONObject().put("element_id", elementId).put("submitted", submit).put("text_redacted", true),
            )
            delay(waitMillis.coerceIn(0L, MAX_WAIT_MS))
            snapshotOnMain(
                DEFAULT_SNAPSHOT_CHARS,
                DEFAULT_MAX_ELEMENTS,
                JSONObject().put("action", jsonValue(action)),
            )
        }

    suspend fun scroll(deltaY: Int, waitMillis: Long): String =
        withContext(Dispatchers.Main.immediate) {
            val action = evaluateJson(
                """
                (() => {
                  window.scrollBy({top:${deltaY.coerceIn(-10_000, 10_000)},behavior:'instant'});
                  return JSON.stringify({ok:true,scroll_x:window.scrollX,scroll_y:window.scrollY,
                    viewport_height:window.innerHeight,document_height:document.documentElement.scrollHeight});
                })()
                """.trimIndent(),
            )
            delay(waitMillis.coerceIn(0L, MAX_WAIT_MS))
            snapshotOnMain(
                DEFAULT_SNAPSHOT_CHARS,
                DEFAULT_MAX_ELEMENTS,
                JSONObject().put("action", jsonValue(action)),
            )
        }

    suspend fun back(waitMillis: Long): String = withContext(Dispatchers.Main.immediate) {
        requireAgentControl()
        val browser = ensureWebView()
        val moved = browser.canGoBack()
        if (moved) browser.goBack()
        delay(waitMillis.coerceIn(0L, MAX_WAIT_MS))
        snapshotOnMain(
            DEFAULT_SNAPSHOT_CHARS,
            DEFAULT_MAX_ELEMENTS,
            JSONObject().put("went_back", moved),
        )
    }

    suspend fun waitForPage(waitMillis: Long): String = withContext(Dispatchers.Main.immediate) {
        requireAgentControl()
        delay(waitMillis.coerceIn(0L, MAX_WAIT_MS))
        snapshotOnMain(DEFAULT_SNAPSHOT_CHARS, DEFAULT_MAX_ELEMENTS, JSONObject())
    }

    suspend fun screenshot(name: String): String {
        requireAgentControl()
        val capture = withContext(Dispatchers.Main.immediate) {
            val browser = ensureWebView()
            browser.measure(
                View.MeasureSpec.makeMeasureSpec(BROWSER_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(BROWSER_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            browser.layout(0, 0, BROWSER_WIDTH, BROWSER_HEIGHT)
            val bitmap = Bitmap.createBitmap(BROWSER_WIDTH, BROWSER_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            browser.draw(canvas)
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            bitmap.recycle()
            Triple(bytes, safeObservedUrl(browser.url.orEmpty()), browser.title.orEmpty())
        }
        return withContext(Dispatchers.IO) {
            val directory = File(workspace, "browser").apply { mkdirs() }
            val target = File(directory, "$name.png")
            val temporary = File.createTempFile(".browser-", ".png", directory)
            try {
                temporary.writeBytes(capture.first)
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                temporary.delete()
            }
            JSONObject()
                .put("path", workspace.toPath().relativize(target.toPath()).toString().replace('\\', '/'))
                .put("bytes", target.length())
                .put("width", BROWSER_WIDTH)
                .put("height", BROWSER_HEIGHT)
                .put("url", capture.second)
                .put("title", capture.third)
                .toString(2)
        }
    }

    suspend fun downloadStatus(): String = withContext(Dispatchers.Main.immediate) {
        requireAgentControl()
        pendingDownload?.publicJson()?.toString(2)
            ?: JSONObject()
                .put("available", false)
                .put("message", "Nenhuma solicitação de download foi capturada nesta sessão.")
                .toString(2)
    }

    suspend fun startPendingDownload(requestedName: String?): String {
        requireAgentControl()
        val captured = withContext(Dispatchers.Main.immediate) {
            pendingDownload ?: throw IllegalStateException(
                "Nenhum download pendente. Abra a página e clique no botão de download primeiro.",
            )
        }
        withContext(Dispatchers.IO) {
            BrowserUrlPolicy.requirePublicHttps(captured.url)
        }
        return withContext(Dispatchers.Main.immediate) {
            val guessed = URLUtil.guessFileName(
                captured.url,
                captured.contentDisposition,
                captured.mimeType,
            )
            val fileName = sanitizeDownloadName(requestedName ?: guessed)
            val request = DownloadManager.Request(Uri.parse(captured.url))
                .setTitle(fileName)
                .setDescription("Download iniciado pelo Refrator")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            captured.mimeType?.takeIf { it.isNotBlank() }?.let(request::setMimeType)
            captured.userAgent.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("User-Agent", it)
            }
            captured.cookies?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Cookie", it)
            }
            captured.referer?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Referer", it)
            }
            val manager = context.getSystemService(DownloadManager::class.java)
                ?: throw IllegalStateException("DownloadManager indisponível neste aparelho.")
            val id = manager.enqueue(request)
            pendingDownload = null
            JSONObject()
                .put("started", true)
                .put("download_id", id)
                .put("file_name", fileName)
                .put("destination", "Downloads/$fileName")
                .put("session_headers_applied", true)
                .toString(2)
        }
    }

    suspend fun openCurrentPageExternally(): String {
        requireAgentControl()
        val url = withContext(Dispatchers.Main.immediate) {
            ensureWebView().url ?: throw IllegalStateException("Nenhuma página aberta.")
        }
        withContext(Dispatchers.IO) { BrowserUrlPolicy.requirePublicHttps(url) }
        return withContext(Dispatchers.Main.immediate) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            JSONObject()
                .put("opened", true)
                .put("url", safeObservedUrl(url))
                .put("requires_user_action", true)
                .put("message", "Conclua CAPTCHA, login ou confirmação no navegador visível.")
                .toString(2)
        }
    }

    suspend fun close(): String = withContext(Dispatchers.Main.immediate) {
        requireAgentControl()
        destroyOnMain()
        JSONObject().put("closed", true).toString(2)
    }

    fun attachTo(container: ViewGroup): WebView {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val browser = ensureWebView()
        (browser.parent as? ViewGroup)?.removeView(browser)
        container.addView(
            browser,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        browser.visibility = View.VISIBLE
        return browser
    }

    fun detachFrom(container: ViewGroup) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        webView?.takeIf { it.parent === container }?.let(container::removeView)
    }

    suspend fun state(): String = withContext(Dispatchers.Main.immediate) {
        val browser = ensureWebView()
        JSONObject()
            .put("session_key", sessionKey)
            .put("profile", profileName)
            // Never expose JSON null here: JSONObject.optString would turn it into the
            // literal text "null", which used to be persisted as the tab address.
            .put("url", safeObservedUrl(browser.url.orEmpty()))
            .put("title", browser.title.orEmpty())
            .put("can_go_back", browser.canGoBack())
            .put("can_go_forward", browser.canGoForward())
            .put("control_owner", controlOwner)
            .put("recording", recording)
            .toString(2)
    }

    suspend fun waitFor(
        selector: String?,
        text: String?,
        urlContains: String?,
        readyState: String?,
        timeoutMillis: Long,
    ): String = withContext(Dispatchers.Main.immediate) {
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceIn(100, 60_000)
        var last: JSONObject = JSONObject().put("matched", false)
        do {
            val raw = evaluateJson(
                """
                (() => {
                  const selector = ${selector?.let(JSONObject::quote) ?: "null"};
                  const wantedText = ${text?.let(JSONObject::quote) ?: "null"};
                  const wantedUrl = ${urlContains?.let(JSONObject::quote) ?: "null"};
                  const wantedState = ${readyState?.let(JSONObject::quote) ?: "null"};
                  const selectorOk = !selector || !!document.querySelector(selector);
                  const textOk = !wantedText || (document.body?.innerText || '').includes(wantedText);
                  const urlOk = !wantedUrl || location.href.includes(wantedUrl);
                  const stateOk = !wantedState || document.readyState === wantedState;
                  return JSON.stringify({matched:selectorOk&&textOk&&urlOk&&stateOk,
                    selector_ok:selectorOk,text_ok:textOk,url_ok:urlOk,state_ok:stateOk,
                    url:location.href,ready_state:document.readyState});
                })()
                """.trimIndent(),
            )
            last = when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("matched", false) }
                else -> JSONObject().put("matched", false)
            }
            if (last.optBoolean("matched")) break
            delay(200)
        } while (System.currentTimeMillis() < deadline)
        last.put("timed_out", !last.optBoolean("matched")).toString(2)
    }

    suspend fun find(query: String, maxResults: Int): String = withContext(Dispatchers.Main.immediate) {
        val result = evaluateJson(
            """
            (() => {
              const q=${JSONObject.quote(query.lowercase())}, limit=${maxResults.coerceIn(1, 100)};
              const nodes=Array.from(document.querySelectorAll('body *')).filter(el =>
                el.children.length===0 && (el.innerText||'').toLowerCase().includes(q)).slice(0,limit);
              return JSON.stringify(nodes.map((el,index)=>({index,text:(el.innerText||'').trim().slice(0,500),
                tag:el.tagName.toLowerCase(),role:el.getAttribute('role')})));
            })()
            """.trimIndent(),
        )
        JSONObject().put("query", query).put("results", jsonValue(result)).toString(2)
    }

    suspend fun consoleLog(limit: Int): String = eventJson(consoleEvents, limit)
    suspend fun networkLog(limit: Int): String = eventJson(networkEvents, limit)
    suspend fun errors(limit: Int): String = eventJson(errorEvents, limit)
    suspend fun history(limit: Int): String = eventJson(historyEvents, limit)

    suspend fun startRecording(): String = withContext(Dispatchers.Main.immediate) {
        requireAgentControl()
        recordedActions.clear()
        recording = true
        JSONObject().put("recording", true).toString(2)
    }

    suspend fun stopRecording(): String = withContext(Dispatchers.Main.immediate) {
        requireAgentControl()
        recording = false
        JSONObject().put("recording", false).put("actions", recordedActions.size).toString(2)
    }

    suspend fun exportRecording(name: String): String {
        requireAgentControl()
        val payload = withContext(Dispatchers.Main.immediate) {
            JSONArray(recordedActions.toList()).toString(2)
        }
        return withContext(Dispatchers.IO) {
            val directory = File(workspace, "browser/recordings").apply { mkdirs() }
            val target = File(directory, "${sanitizeDownloadName(name).substringBeforeLast('.', name)}.json")
            target.writeText(payload, Charsets.UTF_8)
            JSONObject()
                .put("path", workspace.toPath().relativize(target.toPath()).toString().replace('\\', '/'))
                .put("actions", JSONArray(payload).length())
                .toString(2)
        }
    }

    suspend fun handoff(): String = withContext(Dispatchers.Main.immediate) {
        controlOwner = "user"
        JSONObject().put("control_owner", controlOwner).put("requires_user_action", true).toString(2)
    }

    suspend fun resumeControl(): String = withContext(Dispatchers.Main.immediate) {
        controlOwner = "agent"
        JSONObject().put("control_owner", controlOwner).toString(2)
    }

    private suspend fun eventJson(events: ArrayDeque<JSONObject>, limit: Int): String =
        withContext(Dispatchers.Main.immediate) {
            requireAgentControl()
            JSONArray(events.takeLast(limit.coerceIn(1, MAX_EVENTS))).toString(2)
        }

    private fun recordAction(type: String, payload: JSONObject) {
        if (!recording) return
        appendEvent(
            recordedActions,
            JSONObject().put("type", type).put("payload", payload).put("at", System.currentTimeMillis()),
        )
    }

    private fun appendEvent(queue: ArrayDeque<JSONObject>, value: JSONObject) {
        queue.addLast(value)
        while (queue.size > MAX_EVENTS) queue.removeFirst()
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val created = WebView(context)
        if (profileName != DEFAULT_PROFILE_NAME) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                created.destroy()
                throw IllegalStateException(
                    "O Android System WebView precisa ser atualizado para habilitar perfis isolados.",
                )
            }
            WebViewCompat.setProfile(created, profileName)
        }
        val browser = created.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = settings.userAgentString + " Refrator/${BuildConfig.VERSION_NAME}"
            settings.safeBrowsingEnabled = true
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    appendEvent(
                        consoleEvents,
                        JSONObject()
                            .put("level", consoleMessage.messageLevel().name)
                            .put(
                                "message",
                                RemoteContextRedactor.redactText(consoleMessage.message()).take(2_000),
                            )
                            .put("source", safeObservedUrl(consoleMessage.sourceId()))
                            .put("line", consoleMessage.lineNumber())
                            .put("at", System.currentTimeMillis()),
                    )
                    return true
                }
            }
            webViewClient = browserClient()
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                if (!BrowserUrlPolicy.isSyntacticallyAllowed(url)) {
                    lastBlockedUrl = safeObservedUrl(url)
                    lastError = "A página solicitou um download por URL não permitida."
                    return@setDownloadListener
                }
                pendingDownload = PendingBrowserDownload(
                    url = url,
                    userAgent = userAgent.orEmpty(),
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    cookies = CookieManager.getInstance().getCookie(url),
                    referer = this.url,
                )
                lastError = "Download capturado. Use browser_download_start para salvá-lo em Downloads."
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(browser, false)
        }
        activity?.addContentView(
            browser,
            FrameLayout.LayoutParams(BROWSER_WIDTH, BROWSER_HEIGHT).apply {
                leftMargin = -BROWSER_WIDTH * 2
                topMargin = 0
            },
        )
        installServiceWorkerPolicy()
        webView = browser
        return browser
    }

    private fun browserClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val allowed = BrowserUrlPolicy.isSyntacticallyAllowed(request.url.toString())
            if (!allowed) lastBlockedUrl = safeObservedUrl(request.url.toString())
            return !allowed
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            appendEvent(
                networkEvents,
                JSONObject()
                    .put("method", request.method)
                    .put("url", safeObservedUrl(request.url.toString()))
                    .put("main_frame", request.isForMainFrame)
                    .put("gesture", request.hasGesture())
                    .put("at", System.currentTimeMillis()),
            )
            return intercept(request.url.toString())
        }

        override fun onPageFinished(view: WebView, url: String) {
            appendEvent(
                historyEvents,
                JSONObject()
                    .put("url", safeObservedUrl(url))
                    .put("title", view.title?.take(500) ?: JSONObject.NULL)
                    .put("at", System.currentTimeMillis()),
            )
            pendingLoad?.complete(Unit)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (request.isForMainFrame) {
                lastError = error.description?.toString()?.take(320)
                appendEvent(
                    errorEvents,
                    JSONObject()
                        .put("type", "load")
                        .put("code", error.errorCode)
                        .put("description", error.description?.toString()?.take(500))
                        .put("url", safeObservedUrl(request.url.toString()))
                        .put("at", System.currentTimeMillis()),
                )
                pendingLoad?.complete(Unit)
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            appendEvent(
                errorEvents,
                JSONObject()
                    .put("type", "http")
                    .put("status", errorResponse.statusCode)
                    .put("reason", errorResponse.reasonPhrase?.take(300))
                    .put("url", safeObservedUrl(request.url.toString()))
                    .put("main_frame", request.isForMainFrame)
                    .put("at", System.currentTimeMillis()),
            )
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            lastError = "Erro TLS bloqueado pelo navegador."
            appendEvent(
                errorEvents,
                JSONObject()
                    .put("type", "ssl")
                    .put("primary_error", error.primaryError)
                    .put("url", error.url?.let(::safeObservedUrl))
                    .put("at", System.currentTimeMillis()),
            )
            pendingLoad?.complete(Unit)
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse,
        ) {
            lastError = "Navegação bloqueada pelo Safe Browsing (tipo $threatType)."
            callback.backToSafety(true)
            pendingLoad?.complete(Unit)
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            lastError = if (detail.didCrash()) {
                "O processo isolado do navegador falhou e será recriado."
            } else {
                "O Android encerrou o processo do navegador; ele será recriado."
            }
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            webView = null
            pendingLoad?.complete(Unit)
            return true
        }
    }

    private fun installServiceWorkerPolicy() {
        ServiceWorkerController.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    intercept(request.url.toString())
            },
        )
    }

    private fun intercept(url: String): WebResourceResponse? {
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme in setOf("data", "blob", "about")) return null
        val allowed = runCatching { BrowserUrlPolicy.requirePublicHttps(url) }.isSuccess
        if (allowed) return null
        lastBlockedUrl = safeObservedUrl(url)
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Bloqueado pela política de navegação do Refrator",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream("Blocked navigation".toByteArray()),
        )
    }

    private suspend fun snapshotOnMain(
        maxChars: Int,
        maxElements: Int,
        extra: JSONObject,
    ): String {
        val script = snapshotScript(
            maxChars.coerceIn(1_000, MAX_SNAPSHOT_CHARS),
            maxElements.coerceIn(1, MAX_ELEMENTS),
        )
        val value = evaluateJson(script)
        val snapshot = when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrElse {
                JSONObject().put("error", "invalid_snapshot").put("raw", value.take(1_000))
            }
            else -> JSONObject().put("error", "snapshot_unavailable")
        }
        snapshot.optString("url").takeIf(String::isNotBlank)?.let {
            snapshot.put("url", safeObservedUrl(it))
        }
        snapshot.optJSONArray("elements")?.let { elements ->
            for (index in 0 until elements.length()) {
                elements.optJSONObject(index)?.let { element ->
                    element.optString("href").takeIf(String::isNotBlank)?.let {
                        element.put("href", safeObservedUrl(it))
                    }
                }
            }
        }
        extra.keys().forEach { key -> snapshot.put(key, extra.opt(key)) }
        snapshot.put("last_error", lastError ?: JSONObject.NULL)
        snapshot.put("blocked_url", lastBlockedUrl ?: JSONObject.NULL)
        snapshot.put("pending_download", pendingDownload?.publicJson() ?: JSONObject.NULL)
        return snapshot.toString(2)
    }

    private suspend fun evaluateJson(script: String): Any? {
        requireAgentControl()
        val browser = ensureWebView()
        val result = CompletableDeferred<String>()
        browser.evaluateJavascript(script) { raw -> result.complete(raw ?: "null") }
        val raw = withTimeoutOrNull(JAVASCRIPT_TIMEOUT_MS) { result.await() }
            ?: return JSONObject().put("error", "javascript_timeout")
        return runCatching { JSONTokener(raw).nextValue() }.getOrElse { raw }
    }

    private fun snapshotScript(maxChars: Int, maxElements: Int): String = """
        (() => {
          const maxChars = $maxChars;
          const maxElements = $maxElements;
          const visible = (el) => {
            const s = getComputedStyle(el), r = el.getBoundingClientRect();
            return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
          };
          const protectedField = (el) => {
            const type=(el.getAttribute('type')||'').toLowerCase();
            const autocomplete=(el.getAttribute('autocomplete')||'').toLowerCase();
            const identity=((el.getAttribute('name')||'')+' '+(el.id||'')+' '+(el.getAttribute('aria-label')||'')).toLowerCase();
            return type==='password' || ['current-password','new-password','one-time-code','cc-number','cc-csc','cc-exp'].includes(autocomplete) ||
              /(?:pass(?:word|code)?|senha|otp|2fa|verification|cvv|cvc|card|cartao|token|secret|pin)/i.test(identity);
          };
          const label = (el) => (protectedField(el) ? '[protected]' :
            (el.getAttribute('aria-label') || el.innerText || el.value || el.getAttribute('placeholder') ||
            el.getAttribute('alt') || el.getAttribute('title') || '')).trim();
          const nodes = Array.from(document.querySelectorAll(
            'a[href],button,input,textarea,select,[role="button"],[role="link"],[contenteditable="true"],summary'
          )).filter(visible).slice(0,maxElements);
          const elements = nodes.map((el,index) => {
            const id = 'e' + (index + 1);
            el.setAttribute('data-agent-id',id);
            const r = el.getBoundingClientRect();
            return {id,tag:el.tagName.toLowerCase(),role:el.getAttribute('role'),type:el.getAttribute('type'),
              text:label(el).slice(0,300),href:el.href || null,disabled:!!el.disabled,
              checked:typeof el.checked === 'boolean' ? el.checked : null,
              x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height)};
          });
          const text = (document.body?.innerText || document.documentElement?.innerText || '').trim();
          const injectionPatterns=[/ignore (all|previous) instructions/i,/system prompt/i,
            /you are (chatgpt|claude|an ai)/i,/developer message/i,/call (this )?tool/i];
          const injectionSignals=injectionPatterns.filter(p=>p.test(text)).map(p=>p.source);
          return JSON.stringify({url:location.href,title:document.title,ready_state:document.readyState,
            language:document.documentElement?.lang || null,text:text.slice(0,maxChars),
            text_truncated:text.length > maxChars,scroll:{x:scrollX,y:scrollY,viewport_height:innerHeight,
            document_height:document.documentElement?.scrollHeight || 0},elements,
            trust:'untrusted_web_content',prompt_injection_signals:injectionSignals});
        })()
    """.trimIndent()

    private fun destroyOnMain() {
        pendingLoad?.cancel()
        pendingLoad = null
        pendingDownload = null
        webView?.let { browser ->
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.stopLoading()
            browser.loadUrl("about:blank")
            browser.clearHistory()
            browser.removeAllViews()
            browser.destroy()
        }
        webView = null
        synchronized(Companion) {
            sessions.remove(runtimeKey(workspace, sessionKey))
        }
    }

    private fun jsonValue(value: Any?): Any = value ?: JSONObject.NULL

    private fun requireAgentControl() {
        check(controlOwner == "agent") {
            "A aba está sob controle do usuário. Aguarde browser_resume_control antes de inspecionar ou agir."
        }
    }

    companion object {
        // Activity and foreground/background services must resolve the exact same WebView.
        // Context identity is not stable across those owners, so the runtime is keyed by the
        // canonical workspace and persisted tab id instead.
        private val sessions = linkedMapOf<String, AgentBrowserSession>()

        @Synchronized
        fun get(
            context: Context,
            workspace: File,
            sessionKey: String = "default",
            profileName: String = DEFAULT_PROFILE_NAME,
        ): AgentBrowserSession {
            val key = runtimeKey(workspace, sessionKey)
            return sessions.getOrPut(key) {
                AgentBrowserSession(
                    context = context.applicationContext,
                    workspace = workspace.canonicalFile,
                    sessionKey = sessionKey,
                    profileName = profileName,
                )
            }
        }

        fun supportsMultipleProfiles(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

        fun availableProfileNames(): List<String> = if (supportsMultipleProfiles()) {
            ProfileStore.getInstance().allProfileNames
        } else {
            listOf(DEFAULT_PROFILE_NAME)
        }

        fun release(activity: Activity) {
            // A configuration change or Activity destruction must not kill work owned by the
            // persistent agent service. Android will reclaim all WebViews on process death.
        }

        private fun runtimeKey(workspace: File, sessionKey: String): String =
            "${workspace.canonicalPath}::$sessionKey"

        private const val BROWSER_WIDTH = 1080
        private const val BROWSER_HEIGHT = 1920
        private const val DEFAULT_SNAPSHOT_CHARS = 65_536
        private const val DEFAULT_MAX_ELEMENTS = 160
        private const val MAX_SNAPSHOT_CHARS = 131_072
        private const val MAX_ELEMENTS = 300
        private const val PAGE_LOAD_TIMEOUT_MS = 25_000L
        private const val JAVASCRIPT_TIMEOUT_MS = 10_000L
        private const val MAX_WAIT_MS = 15_000L
        private const val MAX_EVENTS = 500
        const val DEFAULT_PROFILE_NAME = "Default"
    }
}

private data class PendingBrowserDownload(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
    val cookies: String?,
    val referer: String?,
) {
    fun publicJson(): JSONObject = JSONObject()
        .put("available", true)
        .put("url", safeObservedUrl(url))
        .put("mime_type", mimeType ?: JSONObject.NULL)
        .put("content_length", contentLength.takeIf { it >= 0 } ?: JSONObject.NULL)
        .put("has_session_cookies", !cookies.isNullOrBlank())
}

/** URLs exposed to the model, diagnostics or recordings omit credentials and secret-bearing parts. */
internal fun safeObservedUrl(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        val uri = URI(value)
        when {
            uri.scheme.equals("about", true) -> "about:blank"
            uri.host == null -> "[redacted-url]"
            else -> URI(
                uri.scheme,
                null,
                uri.host,
                uri.port,
                sanitizeObservedPath(uri.path).take(1_500),
                null,
                null,
            ).toASCIIString().take(2_048)
        }
    }.getOrDefault("[redacted-url]")
}

private fun sanitizeObservedPath(path: String?): String {
    if (path.isNullOrEmpty() || path == "/") return path.orEmpty()
    var redactNext = false
    return path.split('/').joinToString("/") { segment ->
        val sensitiveLabel = OBSERVED_PATH_SENSITIVE_LABEL.containsMatchIn(segment)
        val secretLike = redactNext ||
            OBSERVED_PATH_EMAIL.matches(segment) ||
            (segment.length >= 20 && segment.any(Char::isDigit) && OBSERVED_PATH_TOKEN.matches(segment)) ||
            (segment.length >= 32 && OBSERVED_PATH_TOKEN.matches(segment))
        redactNext = sensitiveLabel
        if (secretLike) "redacted" else segment
    }
}

private val OBSERVED_PATH_SENSITIVE_LABEL =
    Regex("(?i)^(?:access[-_]?token|auth|callback|code|invite|magic|oauth|otp|reset|secret|token|verify|verification)$")
private val OBSERVED_PATH_EMAIL = Regex("(?i)^[^/@\\s]+@[^/@\\s]+\\.[^/@\\s]+$")
private val OBSERVED_PATH_TOKEN = Regex("^[A-Za-z0-9._~+=%:-]+$")

private fun sanitizeDownloadName(value: String): String {
    val cleaned = value
        .substringAfterLast('/')
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .trim('.')
        .take(180)
    require(cleaned.isNotBlank() && cleaned !in setOf(".", "..")) {
        "Nome de arquivo de download inválido."
    }
    return cleaned
}

object BrowserUrlPolicy {
    fun isSyntacticallyAllowed(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) &&
            uri.host != null &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port in 1..65_535) &&
            !isReservedHost(uri.host)
    }.getOrDefault(false)

    fun requirePublicHttps(value: String): URI {
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("URL inválida.") }
        require(uri.scheme.equals("https", true)) { "O navegador aceita somente HTTPS." }
        require(uri.userInfo == null) { "Credenciais embutidas na URL não são aceitas." }
        require(uri.port == -1 || uri.port in 1..65_535) { "Porta HTTPS inválida." }
        val host = uri.host ?: throw IllegalArgumentException("Host HTTPS ausente.")
        require(!isReservedHost(host)) { "Host local ou reservado bloqueado pelo navegador." }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
            "O host resolveu para endereço local, reservado ou não público."
        }
        return uri
    }

    private fun isReservedHost(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        return normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") ||
            normalized.endsWith(".internal") ||
            normalized == "metadata.google.internal"
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false
        if (address is Inet4Address) {
            val bytes = address.address.map { it.toInt() and 0xff }
            val first = bytes[0]
            val second = bytes[1]
            if (
                first == 0 ||
                first == 10 ||
                first == 100 && second in 64..127 ||
                first == 127 ||
                first == 169 && second == 254 ||
                first == 172 && second in 16..31 ||
                first == 192 && second == 0 && bytes[2] in setOf(0, 2) ||
                first == 192 && second == 88 && bytes[2] == 99 ||
                first == 192 && second == 168 ||
                first == 198 && second in 18..19 ||
                first == 198 && second == 51 && bytes[2] == 100 ||
                first == 203 && second == 0 && bytes[2] == 113 ||
                first >= 224
            ) return false
        }
        if (address is Inet6Address) {
            val bytes = address.address
            val first = bytes.first().toInt() and 0xff
            if (first and 0xfe == 0xfc) return false // fc00::/7 (ULA)
            if (
                first == 0x20 &&
                (bytes[1].toInt() and 0xff) == 0x01 &&
                (bytes[2].toInt() and 0xff) == 0x0d &&
                (bytes[3].toInt() and 0xff) == 0xb8
            ) return false // 2001:db8::/32 documentation
        }
        return true
    }
}
