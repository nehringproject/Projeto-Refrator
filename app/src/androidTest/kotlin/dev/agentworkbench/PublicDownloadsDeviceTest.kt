package dev.agentworkbench

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicDownloadsDeviceTest {
    @Test
    fun streamsIntoPublicDownloadsAndCleansUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = "Refrator MediaStore smoke test\n".toByteArray()
        val result = PublicDownloadsAccess(context).writeFrom(
            path = "AgentWorkbench/aw-mediastore-smoke.txt",
            mimeType = "text/plain",
            overwrite = true,
            input = ByteArrayInputStream(payload),
            maxBytes = payload.size.toLong(),
        )
        try {
            assertEquals(payload.size.toLong(), result.bytes)
            val restored = context.contentResolver.openInputStream(android.net.Uri.parse(result.uri))
                ?.use { it.readBytes() }
            assertTrue(payload.contentEquals(restored))
        } finally {
            context.contentResolver.delete(android.net.Uri.parse(result.uri), null, null)
        }
    }
}
