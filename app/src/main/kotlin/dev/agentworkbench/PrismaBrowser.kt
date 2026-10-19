package dev.agentworkbench

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Each Prisma's own browser: a WebView that runs hidden, with its own profile (cookies, logins,
 * storage) when the phone's WebView supports profiles, read as compact text with numbered
 * elements instead of screenshots. Password fields are never read nor filled by the model: the
 * user logs in through [login], on screen, and the session stays in that Prisma's profile.
 */
internal object PrismaBrowsers {
    private const val MAX_ACTIVE = 3
    private const val LOAD_TIMEOUT_MS = 15_000L

    private class Tab(val botId: String, val view: WebView, val profiled: Boolean) {
        var lastUsed = System.currentTimeMillis()

        @Volatile var loading = false
    }

    /** A Prisma waiting for the user to log in on its browser. */
    class LoginRequest(val botId: String, val botName: String, val url: String, val reason: String) {
        internal val done = CompletableDeferred<Boolean>()
    }

    private val tabs = LinkedHashMap<String, Tab>()
    private val loginFlow = MutableStateFlow<LoginRequest?>(null)
    val loginRequest: StateFlow<LoginRequest?> = loginFlow

    // ---- tools ----

    suspend fun open(context: Context, bot: Bot, handle: String, url: String, maxChars: Int): String {
        val target = normalizeUrl(url)
        withContext(Dispatchers.Main) {
            val tab = tab(context, bot, handle)
            tab.loading = true
            tab.view.loadUrl(target)
        }
        awaitLoad(bot.id)
        return snapshot(bot.id, maxChars)
    }

    suspend fun read(context: Context, bot: Bot, handle: String, maxChars: Int): String {
        withContext(Dispatchers.Main) { tab(context, bot, handle) }
        return snapshot(bot.id, maxChars)
    }

    suspend fun act(context: Context, bot: Bot, handle: String, steps: JSONArray, maxChars: Int): String {
        withContext(Dispatchers.Main) { tab(context, bot, handle) }
        val done = JSONArray()
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index) ?: throw SocietyTools.Invalid("Passo ${index + 1} inválido.")
            val outcome = runCatching { step(bot.id, step) }
            done.put(JSONObject().put("passo", index + 1).put("ok", outcome.getOrNull() == "ok").put("info", outcome.getOrElse { it.message ?: "erro" }))
            if (outcome.getOrNull() != "ok") break
        }
        awaitLoad(bot.id)
        return "Passos: $done\n\n" + snapshot(bot.id, maxChars)
    }

    suspend fun login(context: Context, bot: Bot, handle: String, url: String, reason: String): String {
        if (loginFlow.value != null) throw SocietyTools.Invalid("O usuário já está fazendo outro login agora; tente daqui a pouco.")
        val target = normalizeUrl(url)
        withContext(Dispatchers.Main) {
            val tab = tab(context, bot, handle)
            tab.loading = true
            tab.view.loadUrl(target)
        }
        val request = LoginRequest(bot.id, bot.name, target, reason.ifBlank { "Entrar na conta" })
        loginFlow.value = request
        PrismaNotifier.notify(
            context,
            "${bot.name} precisa que você entre numa conta",
            "${request.reason} — ${hostOf(target)}. Toque para abrir o navegador dele; o Prisma não vê sua senha.",
            kind = PrismaNotifications.Kind.PERMISSION,
        )
        val finished = withTimeoutOrNull(15 * 60_000L) { request.done.await() } ?: false
        if (loginFlow.value === request) loginFlow.value = null
        withContext(Dispatchers.Main) {
            tabs[bot.id]?.let { tab ->
                if (tab.profiled) runCatching { ProfileStore.getInstance().getProfile(profileName(handle))?.cookieManager?.flush() }
                else android.webkit.CookieManager.getInstance().flush()
            }
        }
        awaitLoad(bot.id)
        val page = snapshot(bot.id, 3_000)
        return if (finished) "O usuário terminou o login. A sessão fica salva no seu navegador.\n\n$page"
        else "O usuário não concluiu o login (cancelou ou não respondeu em 15 min).\n\n$page"
    }

    // ---- login screen (MainActivity shows the live page while a request is open) ----

    /** The request's WebView, detached from wherever it was, ready to be shown on screen. */
    fun viewFor(request: LoginRequest): View? {
        val view = tabs[request.botId]?.view ?: return null
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return view
    }

    /** Puts the WebView back to running hidden after the login screen closes. */
    fun releaseView(request: LoginRequest, context: Context) {
        val view = tabs[request.botId]?.view ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        layoutHidden(view, context)
    }

    fun finishLogin(request: LoginRequest, success: Boolean) {
        request.done.complete(success)
        if (loginFlow.value === request) loginFlow.value = null
    }

    // ---- internals (main thread) ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun tab(context: Context, bot: Bot, handle: String): Tab {
        tabs[bot.id]?.let { return it.also { tab -> tab.lastUsed = System.currentTimeMillis() } }
        while (tabs.size >= MAX_ACTIVE) {
            val waiting = loginFlow.value?.botId
            val victim = tabs.values.filter { it.botId != waiting }.minByOrNull { it.lastUsed } ?: break
            tabs.remove(victim.botId)
            (victim.view.parent as? ViewGroup)?.removeView(victim.view)
            victim.view.destroy()
        }
        val app = context.applicationContext
        val view = WebView(app)
        val profiled = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) && runCatching {
            ProfileStore.getInstance().getOrCreateProfile(profileName(handle))
            WebViewCompat.setProfile(view, profileName(handle))
        }.isSuccess
        val tab = Tab(bot.id, view, profiled)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tab.loading = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.loading = false
            }
        }
        layoutHidden(view, app)
        view.onResume()
        view.resumeTimers()
        tabs[bot.id] = tab
        return tab
    }

    private fun layoutHidden(view: WebView, context: Context) {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(720)
        val height = metrics.heightPixels.coerceAtLeast(1280)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun profileName(handle: String) = "prisma_" + handle.filter { it.isLetterOrDigit() || it == '_' }

    private suspend fun awaitLoad(botId: String) {
        withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            delay(250)
            while (withContext(Dispatchers.Main) { tabs[botId]?.let { it.loading || it.view.progress < 100 } ?: false }) delay(200)
        }
        // Let scripts paint the page after load.
        delay(600)
    }

    private suspend fun eval(botId: String, script: String): String = withContext(Dispatchers.Main) {
        val view = tabs[botId]?.view ?: throw SocietyTools.Invalid("Abra uma página antes com web_open.")
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value ?: "null") }
        }
    }

    /** evaluateJavascript returns a JSON-encoded value; our scripts return strings. */
    private fun decoded(raw: String): String =
        (runCatching { JSONTokener(raw).nextValue() }.getOrNull() as? String) ?: raw

    private suspend fun snapshot(botId: String, maxChars: Int): String {
        val raw = decoded(eval(botId, SNAPSHOT_JS))
        val page = runCatching { JSONObject(raw) }.getOrNull() ?: return "(não consegui ler a página: $raw)"
        val items = page.optJSONArray("items") ?: JSONArray()
        return buildString {
            append("Título: ").append(page.optString("title")).append('\n')
            append("Endereço: ").append(page.optString("url")).append("\n\n")
            if (items.length() > 0) {
                append("Elementos:\n")
                for (i in 0 until items.length()) append(items.optString(i)).append('\n')
                append('\n')
            }
            val text = page.optString("text").trim()
            if (text.isNotEmpty()) {
                append("Texto da página:\n")
                append(if (text.length > maxChars) text.take(maxChars) + "\n… (continua; use scroll ou max_chars maior)" else text)
            }
        }
    }

    private suspend fun step(botId: String, step: JSONObject): String = when {
        step.has("click") -> decoded(eval(botId, "window.__rfxAct && __rfxAct('click', ${step.getInt("click")}, null)"))
        step.has("fill") -> step.getJSONArray("fill").let { decoded(eval(botId, "window.__rfxAct && __rfxAct('fill', ${it.getInt(0)}, ${JSONObject.quote(it.getString(1))})")) }
        step.has("select") -> step.getJSONArray("select").let { decoded(eval(botId, "window.__rfxAct && __rfxAct('select', ${it.getInt(0)}, ${JSONObject.quote(it.getString(1))})")) }
        step.has("submit") -> decoded(eval(botId, "window.__rfxAct && __rfxAct('submit', ${step.getInt("submit")}, null)"))
        step.has("scroll") -> decoded(eval(botId, "(function(){window.scrollBy(0, ${if (step.optString("scroll") == "up") "-" else ""}Math.round(innerHeight*0.8));return 'ok'})()"))
        step.has("back") -> withContext(Dispatchers.Main) {
            val view = tabs[botId]?.view
            if (view?.canGoBack() == true) {
                view.goBack()
                "ok"
            } else {
                "não há página anterior"
            }
        }
        step.has("open") -> withContext(Dispatchers.Main) {
            val tab = tabs[botId] ?: throw SocietyTools.Invalid("Navegador fechado.")
            tab.loading = true
            tab.view.loadUrl(normalizeUrl(step.getString("open")))
            "ok"
        }
        step.has("wait_ms") -> {
            delay(step.getLong("wait_ms").coerceIn(50, 10_000))
            "ok"
        }
        else -> throw SocietyTools.Invalid("Passo sem ação conhecida: $step")
    }.also { awaitLoad(botId) }.let { if (it == "null") "a página mudou; leia de novo (web_read)" else it }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotEmpty()) { "Endereço vazio." }
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        if (!Regex("^(https?|file)://", RegexOption.IGNORE_CASE).containsMatchIn(withScheme) && !withScheme.startsWith("about:")) {
            throw SocietyTools.Invalid("Use um endereço http ou https.")
        }
        return withScheme
    }

    private fun hostOf(url: String): String = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url

    /**
     * Lists visible interactive elements as "[N] kind "label"" and installs __rfxAct for actions
     * by number. Password values are never read; filling a password field is refused.
     */
    private val SNAPSHOT_JS = """
        (function(){
          var MAX=200, els=[], out=[];
          function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';}
          function clean(t){return (t||'').replace(/\s+/g,' ').trim().slice(0,80);}
          function label(e){return clean(e.getAttribute('aria-label')||e.innerText||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||e.getAttribute('name')||'');}
          var sel='a[href],button,input,textarea,select,[role=button],[role=link],[role=tab],[role=checkbox],[role=menuitem],[role=switch],[onclick],[contenteditable=true],summary';
          var nodes=document.querySelectorAll(sel);
          for(var k=0;k<nodes.length&&els.length<MAX;k++){
            var e=nodes[k]; if(!vis(e)||e.disabled) continue;
            var i=els.length, tag=e.tagName.toLowerCase(), d;
            if(tag==='input'){
              var ty=(e.type||'text').toLowerCase();
              if(ty==='hidden') continue;
              if(ty==='password'){d='campo de senha'+(e.value?' (preenchido)':'')+' — use web_login';}
              else if(ty==='checkbox'||ty==='radio'){d=ty+(e.checked?' marcado':' desmarcado')+' "'+label(e)+'"';}
              else if(ty==='submit'||ty==='button'||ty==='image'){d='botão "'+clean(e.value||label(e))+'"';}
              else {d='campo '+ty+' "'+clean(e.getAttribute('placeholder')||e.getAttribute('aria-label')||e.name||'')+'"'+(e.value?' = "'+clean(e.value)+'"':'');}
            } else if(tag==='textarea'){d='caixa de texto "'+clean(e.getAttribute('placeholder')||e.getAttribute('aria-label')||'')+'"'+(e.value?' = "'+clean(e.value)+'"':'');}
            else if(tag==='select'){var o=e.options[e.selectedIndex];d='lista "'+label(e)+'" = "'+clean(o?o.text:'')+'"';}
            else if(tag==='a'){d='link "'+label(e)+'"';}
            else if(e.isContentEditable){d='editor "'+label(e)+'"';}
            else {d='botão "'+label(e)+'"';}
            els.push(e); out.push('['+i+'] '+d);
          }
          window.__rfx={els:els};
          window.__rfxAct=function(kind,i,value){
            var e=(window.__rfx&&window.__rfx.els[i]); if(!e||!document.contains(e)) return 'elemento '+i+' não existe mais; leia a página de novo';
            try{e.scrollIntoView({block:'center'});}catch(x){}
            if(kind==='click'){e.focus&&e.focus();e.click();return 'ok';}
            if(kind==='submit'){var f=e.form||(e.closest&&e.closest('form'));if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();return 'ok';}
              e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));e.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));return 'ok';}
            if(kind==='fill'){
              if(e.type&&e.type.toLowerCase()==='password') return 'senha só o usuário digita: use web_login';
              e.focus&&e.focus();
              if(e.isContentEditable){e.textContent=value;}
              else{var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var s=Object.getOwnPropertyDescriptor(p,'value');if(s&&s.set)s.set.call(e,value);else e.value=value;}
              e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}
            if(kind==='select'){if(e.tagName!=='SELECT')return 'não é uma lista';var ok=false;for(var j=0;j<e.options.length;j++){var op=e.options[j];if(op.text.trim()===value||op.value===value){e.selectedIndex=j;ok=true;break;}}
              if(!ok)return 'opção não encontrada';e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}
            return 'ação desconhecida';
          };
          var text=document.body?document.body.innerText:'';
          text=text.replace(/\n{3,}/g,'\n\n');
          return JSON.stringify({title:document.title,url:location.href,items:out,text:text.slice(0,40000)});
        })()
    """.trimIndent()
}
