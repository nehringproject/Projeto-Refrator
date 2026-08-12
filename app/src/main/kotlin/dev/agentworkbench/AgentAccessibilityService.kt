package dev.agentworkbench

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        .put("root_available", AgentAccessibilityRuntime.service?.rootInActiveWindow != null)
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
        val matching = service.windows.filter { it.displayId == displayId }
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
        require(id.matches(Regex("n0(?:\\.[0-9]{1,3}){0,18}"))) { "ID de nó inválido." }
        var node = AgentAccessibilityRuntime.service?.rootInActiveWindow
            ?: error("A janela ativa não expôs árvore de acessibilidade.")
        id.split('.').drop(1).forEach { segment ->
            node = node.getChild(segment.toInt()) ?: error("O nó expirou; crie novo snapshot.")
        }
        return node
    }

    private fun safe(value: CharSequence?): String =
        RemoteContextRedactor.redactText(value?.toString().orEmpty()).take(2_000)

    private fun requireAuthorized() {
        require(authorization.isAuthorized(CAPABILITY)) { "Accessibility não foi autorizada na Central de Capacidades." }
    }

    private companion object { const val CAPABILITY = "accessibility" }
}
