package dev.agentworkbench

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentBrowserDeviceTest {
    @Test
    fun webViewLoadsHttpsAndReturnsJavascriptDomSnapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
        val browser = AgentBrowserSession.get(activity, workspace)

        try {
            val snapshot = runBlocking {
                browser.open("https://example.com", 500)
            }.let(::JSONObject)

            assertEquals("complete", snapshot.getString("ready_state"))
            assertTrue(snapshot.getString("url").startsWith("https://example.com"))
            assertTrue(snapshot.getString("text").contains("Example Domain"))
            assertTrue(snapshot.getJSONArray("elements").length() >= 1)
        } finally {
            runBlocking { browser.close() }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
