package dev.agentworkbench

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        AgentAccessibilityRuntime.service = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (AgentAccessibilityRuntime.service === this) AgentAccessibilityRuntime.service = null
        super.onDestroy()
    }
}

private object AgentAccessibilityRuntime {
    @Volatile var service: AgentAccessibilityService? = null
}

class AgentAccessibilityController(context: Context) : AccessibilityAutomationBridge {
    private val authorization = CapabilityAuthorizationStore(context)

    override fun status(): String = JSONObject()
        .put("service_connected", AgentAccessibilityRuntime.service != null)
        .put("app_authorized", authorization.isAuthorized(CAPABILITY))
        .put("root_available", authorization.isAuthorized(CAPABILITY) && AgentAccessibilityRuntime.service?.rootInActiveWindow != null)
        .toString(2)

    override fun snapshot(maxNodes: Int): String {
        requireAuthorized()
        val service = AgentAccessibilityRuntime.service ?: error("AccessibilityService não está conectado.")
        val root = service.rootInActiveWindow ?: error("A janela ativa não expôs árvore de acessibilidade.")
        val output = JSONArray()
        appendNode(root, "n0", 0, maxNodes.coerceIn(1, 300), output)
        return JSONObject()
            .put("package", root.packageName?.toString())
            .put("window_id", root.windowId)
            .put("nodes", output)
            .put("truncated", output.length() >= maxNodes)
            .toString(2)
    }

    /**
     * Reads the active screen as plain text in reading order. Controls carry their node id so the
     * model can act on them right away (`[n0.3.1] botão "Enviar"`); text inside a control is
     * folded into its label, and repeated plain text is dropped. Passwords stay redacted.
     */
    override fun screenText(maxLines: Int, withBounds: Boolean): String {
        requireAuthorized()
        val service = AgentAccessibilityRuntime.service ?: error("AccessibilityService não está conectado.")
        val root = service.rootInActiveWindow ?: error("A janela ativa não expôs árvore de acessibilidade.")
        val limit = maxLines.coerceIn(1, 400)
        val lines = mutableListOf<String>()
        val seen = HashSet<String>()
        var truncated = false

        fun label(node: AccessibilityNodeInfo): String {
            if (node.isPassword) return "[PROTEGIDO]"
            val own = safe(node.text).ifBlank { safe(node.contentDescription) }
            val hint = if (own.isBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safe(node.hintText) else ""
            return own.ifBlank { hint }.replace(Regex("\\s+"), " ").trim().take(160)
        }

        fun innerText(node: AccessibilityNodeInfo, depth: Int, out: StringBuilder) {
            if (depth > 4 || out.length > 120) return
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val text = label(child)
                if (text.isNotEmpty() && !out.contains(text)) {
                    if (out.isNotEmpty()) out.append(" · ")
                    out.append(text)
                }
                innerText(child, depth + 1, out)
            }
        }

        fun where(node: AccessibilityNodeInfo): String {
            if (!withBounds) return ""
            val bounds = Rect().also(node::getBoundsInScreen)
            return " @${bounds.centerX()},${bounds.centerY()}"
        }

        fun walk(node: AccessibilityNodeInfo, id: String, depth: Int, insideControl: Boolean) {
            if (lines.size >= limit) {
                truncated = true
                return
            }
            if (depth > 18 || !node.isVisibleToUser) return
            val kind = when {
                node.isEditable -> "campo"
                node.isCheckable -> if (node.isChecked) "marcado" else "desmarcado"
                node.isClickable || node.isLongClickable -> "botão"
                else -> null
            }
            if (kind != null) {
                var text = label(node)
                if (text.isEmpty()) text = StringBuilder().also { innerText(node, 0, it) }.toString().take(160)
                val value = if (node.isEditable && !node.isPassword && text.isEmpty()) "vazio" else text
                lines += "[$id] $kind \"$value\"${where(node)}"
            } else if (node.isScrollable) {
                lines += "[$id] lista rolável${where(node)}"
            } else if (!insideControl) {
                val text = label(node)
                if (text.isNotEmpty() && seen.add(text)) lines += text
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> walk(child, "$id.$index", depth + 1, insideControl || kind != null) }
            }
        }

        walk(root, "n0", 0, insideControl = false)
        val header = "app: ${root.packageName ?: "?"} · ${lines.size} linhas"
        val footer = if (truncated) "\n… tela maior que $limit linhas; peça max_lines maior ou role a lista." else ""
        return header + "\n" + lines.joinToString("\n") + footer
    }

    override fun snapshotDisplay(displayId: Int, maxNodes: Int): String {
        requireAuthorized()
        val service = AgentAccessibilityRuntime.service ?: error("AccessibilityService não está conectado.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            error("Inspeção por display exige Android 11 ou superior.")
        }
        return snapshotDisplayApi30(service, displayId, maxNodes)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun snapshotDisplayApi30(
        service: AccessibilityService,
        displayId: Int,
        maxNodes: Int,
    ): String {
        val output = JSONArray()
        // service.windows only covers the default display; virtual displays need windowsOnAllDisplays.
        val matching = service.windowsOnAllDisplays.get(displayId).orEmpty()
        matching.forEach { window ->
            val nodes = JSONArray()
            window.root?.let { root -> appendNode(root, "w${window.id}.n0", 0, maxNodes, nodes) }
            output.put(
                JSONObject()
                    .put("window_id", window.id)
                    .put("display_id", window.displayId)
                    .put("type", window.type)
                    .put("active", window.isActive)
                    .put("focused", window.isFocused)
                    .put("title", safe(window.title))
                    .put("nodes", nodes),
            )
        }
        return JSONObject()
            .put("display_id", displayId)
            .put("windows", output)
            .put("window_count", matching.size)
            .toString(2)
    }

    override fun nodeAction(nodeId: String, action: String, text: String?): String {
        requireAuthorized()
        val node = resolve(nodeId)
        val performed = when (action) {
            "click" -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "long_click" -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            "focus" -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            "scroll_forward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "scroll_backward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "set_text" -> {
                require(!node.isPassword) { "Campos protegidos não aceitam texto vindo do modelo." }
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text.orEmpty().take(16_384),
                        )
                    },
                )
            }
            else -> error("Ação de nó não suportada.")
        }
        return JSONObject().put("node_id", nodeId).put("action", action).put("performed", performed).toString(2)
    }

    override fun globalAction(action: String): String {
        requireAuthorized()
        val service = AgentAccessibilityRuntime.service ?: error("AccessibilityService não está conectado.")
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> error("Ação global não suportada.")
        }
        return JSONObject().put("action", action).put("performed", service.performGlobalAction(code)).toString(2)
    }

    override fun gesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMillis: Long,
    ): String {
        requireAuthorized()
        val service = AgentAccessibilityRuntime.service ?: error("AccessibilityService não está conectado.")
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val dispatched = service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis.coerceIn(50, 5_000)))
                .build(),
            null,
            null,
        )
        return JSONObject().put("dispatched", dispatched).toString(2)
    }

    private fun appendNode(
        node: AccessibilityNodeInfo,
        id: String,
        depth: Int,
        limit: Int,
        output: JSONArray,
    ) {
        if (output.length() >= limit || depth > 18) return
        val bounds = Rect().also(node::getBoundsInScreen)
        val protected = node.isPassword
        output.put(
            JSONObject()
                .put("id", id)
                .put("class", node.className?.toString())
                .put("text", if (protected) "[PROTECTED]" else safe(node.text))
                .put("description", if (protected) "[PROTECTED]" else safe(node.contentDescription))
                .put("view_id", node.viewIdResourceName)
                .put("clickable", node.isClickable)
                .put("editable", node.isEditable && !protected)
                .put("scrollable", node.isScrollable)
                .put("password", protected)
                .put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"),
        )
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> appendNode(child, "$id.$index", depth + 1, limit, output) }
        }
    }

    private fun resolve(id: String): AccessibilityNodeInfo {
        // "n0.2.1" walks the active window; "w42.n0.2.1" (from a display snapshot) walks window 42,
        // which may live on the parallel ShadowDisplay.
        val match = NODE_ID.matchEntire(id) ?: error("ID de nó inválido.")
        val windowId = match.groupValues[1].toIntOrNull()
        val service = AgentAccessibilityRuntime.service ?: error("AccessibilityService não está conectado.")
        var node = if (windowId == null) {
            service.rootInActiveWindow ?: error("A janela ativa não expôs árvore de acessibilidade.")
        } else {
            findWindow(service, windowId)?.root ?: error("A janela $windowId não existe mais; crie novo snapshot.")
        }
        match.groupValues[2].split('.').drop(1).forEach { segment ->
            node = node.getChild(segment.toInt()) ?: error("O nó expirou; crie novo snapshot.")
        }
        return node
    }

    private fun findWindow(service: AccessibilityService, windowId: Int): AccessibilityWindowInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val all = service.windowsOnAllDisplays
            (0 until all.size()).asSequence().flatMap { all.valueAt(it).asSequence() }.firstOrNull { it.id == windowId }
        } else {
            service.windows.firstOrNull { it.id == windowId }
        }

    /**
     * Types into the focused field of [displayId] through ACTION_SET_TEXT, so accents and emoji
     * survive (the shell `input text` path only handles ASCII). Appends to what is already typed.
     */
    override fun setFocusedText(displayId: Int, text: String): String {
        requireAuthorized()
        val service = AgentAccessibilityRuntime.service ?: error("AccessibilityService não está conectado.")
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "Digitação por display exige Android 11 ou superior." }
        val field = service.windowsOnAllDisplays.get(displayId).orEmpty().firstNotNullOfOrNull { window ->
            window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        } ?: error("Nenhum campo de texto focado na tela paralela; toque no campo antes.")
        require(!field.isPassword) { "Campos protegidos não aceitam texto vindo do modelo." }
        val current = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) { "O campo recusou o texto." }
        return JSONObject()
            .put("ok", true)
            .put("display_id", displayId)
            .put("method", "accessibility_set_text")
            .put("chars", text.length)
            .toString()
    }

    private fun safe(value: CharSequence?): String =
        RemoteContextRedactor.redactText(value?.toString().orEmpty()).take(2_000)

    private fun requireAuthorized() {
        require(authorization.isAuthorized(CAPABILITY)) { "Accessibility não foi autorizada na Central de Capacidades." }
    }

    companion object {
        private const val CAPABILITY = "accessibility"
        private val NODE_ID = Regex("(?:w(\\d{1,10})\\.)?(n0(?:\\.[0-9]{1,3}){0,18})")

        fun disconnect(context: Context) {
            AgentAccessibilityRuntime.service?.disableSelf()
            AgentAccessibilityRuntime.service = null
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, AgentAccessibilityService::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
