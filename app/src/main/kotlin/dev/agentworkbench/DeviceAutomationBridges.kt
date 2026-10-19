package dev.agentworkbench

interface AccessibilityAutomationBridge {
    fun status(): String
    fun snapshot(maxNodes: Int): String
    /** The active screen as compact text (one line per text or control), far cheaper than [snapshot]. */
    fun screenText(maxLines: Int, withBounds: Boolean): String = snapshot(maxLines)
    fun snapshotDisplay(displayId: Int, maxNodes: Int): String
    fun setFocusedText(displayId: Int, text: String): String
    fun nodeAction(nodeId: String, action: String, text: String?): String
    fun globalAction(action: String): String
    fun gesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long): String
}

interface NotificationAccessBridge {
    fun status(): String
    fun list(limit: Int): String
}
