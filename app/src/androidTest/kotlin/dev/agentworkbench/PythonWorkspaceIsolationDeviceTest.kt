package dev.agentworkbench

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonWorkspaceIsolationDeviceTest {
    @Test
    fun pipOverlayIsVersionedPerWorkspace() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.filesDir, "python-isolation-test").apply { mkdirs() }
        val workspaceA = File(root, "a").apply { mkdirs() }
        val workspaceB = File(root, "b").apply { mkdirs() }
        val pythonA = EmbeddedPythonRuntimeBridge(context, workspaceA)
        val pythonB = EmbeddedPythonRuntimeBridge(context, workspaceB)
        pythonA.environmentReset()
        pythonB.environmentReset()
        try {
            val installed = JSONObject(pythonA.packageInstall("humanize==4.11.0"))
            assertTrue(installed.toString(2), installed.getBoolean("ok"))

            val inA = JSONObject(pythonA.execute("import humanize; print(humanize.__version__)", 60_000))
            assertTrue(inA.toString(2), inA.getBoolean("ok"))
            assertTrue(inA.getString("stdout").contains("4.11.0"))

            val inB = JSONObject(pythonB.execute("import humanize", 60_000))
            assertFalse(inB.toString(2), inB.getBoolean("ok"))
            assertTrue(inB.getString("error").contains("ModuleNotFoundError"))
        } finally {
            pythonA.environmentReset()
            pythonB.environmentReset()
        }
    }
}
