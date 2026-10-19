package dev.agentworkbench

import dev.agentworkbench.core.ToolEffect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissionApprovalTest {
    private val auto = PrismaGroup(name = "time", topic = "", memberIds = emptyList(), autoApprove = true)
    private val manual = auto.copy(autoApprove = false)

    @Test
    fun `routine tools run by themselves with auto approval`() {
        assertTrue(MissionEngine.autoApproved(auto, "workspace_write", ToolEffect.WORKSPACE_MUTATION))
        assertTrue(MissionEngine.autoApproved(auto, "shadow_tap", ToolEffect.EXTERNAL_MUTATION))
        assertTrue(MissionEngine.autoApproved(auto, "android_shell", ToolEffect.EXTERNAL_MUTATION))
    }

    @Test
    fun `sensitive and destructive actions still ask`() {
        assertFalse(MissionEngine.autoApproved(auto, "whatsapp_send", ToolEffect.EXTERNAL_MUTATION))
        assertFalse(MissionEngine.autoApproved(auto, "email_compose", ToolEffect.EXTERNAL_MUTATION))
        assertFalse(MissionEngine.autoApproved(auto, "accessibility_action", ToolEffect.EXTERNAL_MUTATION))
        assertFalse(MissionEngine.autoApproved(auto, "file_trash", ToolEffect.DESTRUCTIVE))
    }

    @Test
    fun `turning auto approval off asks for everything`() {
        assertFalse(MissionEngine.autoApproved(manual, "workspace_write", ToolEffect.WORKSPACE_MUTATION))
    }
}
