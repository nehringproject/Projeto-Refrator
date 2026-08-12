package dev.agentworkbench

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonWorkerRecoveryDeviceTest {
    @Test
    fun timedOutPythonIsKilledAndNextBindStartsCleanWorker() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workspace = File(context.filesDir, "python-timeout-test").apply { mkdirs() }
        val bridge = EmbeddedPythonRuntimeBridge(context, workspace)

        val timeout = JSONObject(bridge.execute("while True: pass", 1_000))
        assertFalse(timeout.toString(2), timeout.getBoolean("ok"))
        assertTrue(timeout.getBoolean("restart_required"))

        delay(2_000)
        val status = JSONObject(bridge.status())
        assertTrue(status.toString(2), status.getBoolean("ready"))
        val result = JSONObject(bridge.execute("print(21 * 2)", 30_000))
        assertTrue(result.toString(2), result.getBoolean("ok"))
        assertTrue(result.getString("stdout").startsWith("42"))
    }
}
