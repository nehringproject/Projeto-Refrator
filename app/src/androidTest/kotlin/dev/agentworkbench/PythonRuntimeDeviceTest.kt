package dev.agentworkbench

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonRuntimeDeviceTest {
    @Test
    fun embeddedPythonLoadsRealLiteLlmAndRunsCode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workspace = java.io.File(context.filesDir, "device-test-workspace").apply { mkdirs() }
        val bridge = EmbeddedPythonRuntimeBridge(context, workspace)

        val status = JSONObject(bridge.status())
        assertTrue(status.getBoolean("ready"))
        assertEquals("1.90.6", status.getString("litellm"))

        val result = JSONObject(
            bridge.execute(
                "import litellm, pydantic\nprint(6 * 7)\nprint(litellm.__version__ if hasattr(litellm, '__version__') else 'loaded')",
                120_000,
            ),
        )
        assertTrue(result.toString(2), result.getBoolean("ok"))
        assertTrue(result.getString("stdout").lineSequence().first() == "42")
    }
}
