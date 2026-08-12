package dev.agentworkbench

interface AccessibilityAutomationBridge {
    fun status(): String
    fun snapshot(maxNodes: Int): String
    fun snapshotDisplay(displayId: Int, maxNodes: Int): String
    fun nodeAction(nodeId: String, action: String, text: String?): String
    fun globalAction(action: String): String
    fun gesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long): String
}

interface NotificationAccessBridge {
    fun status(): String
    fun list(limit: Int): String
}
