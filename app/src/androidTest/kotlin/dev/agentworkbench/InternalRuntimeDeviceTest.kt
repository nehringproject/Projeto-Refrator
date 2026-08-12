package dev.agentworkbench

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InternalRuntimeDeviceTest {
    @Test
    fun installsAndRunsHashPinnedShellPackInsideAppSandbox() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
        val runtime = InternalRuntime(context, workspace, externalPackagesAllowed = true)
        val request = RuntimePackageRequest(
            name = "gcc-config-guess",
            version = "15.1.0-1b306039",
            url = "https://raw.githubusercontent.com/gcc-mirror/gcc/" +
                "1b306039ac49f8ad91ca71d3de3150a3c9fa792a/config.guess",
            sha256 = "7d1e3c79b86de601c3a0457855ab854dffd15163f53c91edac54a7be2e9c931b",
            format = "raw",
            entrypoints = mapOf("config_guess" to RuntimeEntrypoint("payload")),
            maxDownloadBytes = 1_048_576,
        )

        val installed = runtime.install(request)
        assertEquals("gcc-config-guess", installed.getString("name"))
        assertEquals(request.sha256, installed.getString("sha256"))

        val result = runtime.execute(
            RuntimeCommandRequest(
                id = UUID.randomUUID().toString(),
                command = "config_guess",
                workingDirectory = ".",
                timeoutMillis = 30_000,
                outputLimitBytes = 65_536,
            ),
        )
        assertEquals(result.output, 0, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.truncated)
        assertTrue(result.output.isNotBlank())
        assertTrue(
            result.output,
            result.output.contains("android", ignoreCase = true) ||
                result.output.contains("linux", ignoreCase = true),
        )
    }
}
