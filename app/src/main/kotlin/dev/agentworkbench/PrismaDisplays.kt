package dev.agentworkbench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Each prisma's own "computer screen": a private parallel display (virtual display through
 * Shizuku), so several prismas can operate apps at the same time without fighting over one
 * screen. Created on demand by the prisma's shadow_* tools and released after a while unused.
 */
internal object PrismaDisplays {
    private const val IDLE_RELEASE_MS = 15 * 60_000L
    private val bridges = ConcurrentHashMap<String, SlotShadowDisplayBridge>()

    /** Display slot name for a prisma (stable, safe for the privileged service). */
    fun slotFor(botId: String): String = "p-" + botId.lowercase().filter { it.isLetterOrDigit() }.take(40)

    fun forBot(context: Context, botId: String): ShadowDisplayBridge =
        bridges.getOrPut(slotFor(botId)) { SlotShadowDisplayBridge(context.applicationContext, slotFor(botId), botId) }

    /** Running screens, for the team panel ("tela da Nina"). */
    fun active(): List<SlotShadowDisplayBridge> = bridges.values.filter { it.snapshot().active }

    fun bridgeFor(botId: String): SlotShadowDisplayBridge? = bridges[slotFor(botId)]

    /** Frees screens nobody used for a while (memory and battery). */
    suspend fun releaseIdle() {
        val now = System.currentTimeMillis()
        bridges.values.filter { it.snapshot().active && now - it.lastUsedAt > IDLE_RELEASE_MS }.forEach { runCatching { it.stop() } }
    }
}

internal class SlotShadowDisplayBridge(
    private val context: Context,
    val slot: String,
    val botId: String,
) : ShadowDisplayBridge {
    private val frameLock = Any()
    @Volatile private var reader: ImageReader? = null
    @Volatile private var frameThread: HandlerThread? = null
    @Volatile private var latest: Bitmap? = null
    @Volatile private var lastFrameAt: Long? = null
    @Volatile private var displayId: Int? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var density = 0
    @Volatile var lastUsedAt = System.currentTimeMillis()
        private set

    override fun snapshot(): ShadowDisplaySnapshot {
        val shell = ShizukuShellBridge.snapshot()
        return ShadowDisplaySnapshot(
            supported = shell.permissionGranted,
            active = displayId != null,
            displayId = displayId,
            width = width,
            height = height,
            densityDpi = density,
            lastFrameAtMillis = lastFrameAt,
            detail = if (!shell.permissionGranted) shell.detail else if (displayId != null) {
                "Tela própria deste Prisma ativa no display $displayId."
            } else {
                "Tela própria deste Prisma parada (use shadow_display_start)."
            },
        )
    }

    override suspend fun start(width: Int, height: Int, densityDpi: Int): ShadowDisplaySnapshot {
        touch()
        requireAuthorized()
        release()
        val thread = HandlerThread("prisma-display-$slot").apply { start() }
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener({ source -> consumeFrame(source, width, height) }, Handler(thread.looper))
        try {
            val id = withContext(Dispatchers.IO) {
                ShizukuShellBridge.connectedService().createSlotDisplay(slot, imageReader.surface, width, height, densityDpi)
            }
            this.width = width
            this.height = height
            density = densityDpi
            displayId = id
            reader = imageReader
            frameThread = thread
            return snapshot()
        } catch (error: Throwable) {
            imageReader.close()
            thread.quitSafely()
            throw error
        }
    }

    override suspend fun stop() = release()

    private suspend fun release() {
        if (displayId != null || reader != null) {
            runCatching { withContext(Dispatchers.IO) { ShizukuShellBridge.connectedService().releaseSlotDisplay(slot) } }
        }
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        frameThread?.quitSafely()
        frameThread = null
        synchronized(frameLock) {
            latest?.recycle()
            latest = null
        }
        displayId = null
        width = 0
        height = 0
        density = 0
        lastFrameAt = null
    }

    override suspend fun launch(packageName: String): String = call { slotLaunch(slot, packageName) }
    override suspend fun tap(x: Int, y: Int): String = call { slotInput(slot, "tap", arrayOf(x.toString(), y.toString())) }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
        call { slotInput(slot, "swipe", arrayOf(x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())) }
    override suspend fun text(value: String): String = call { slotInput(slot, "text", arrayOf(value)) }
    override suspend fun keyEvent(keyCode: Int): String = call { slotInput(slot, "keyevent", arrayOf(keyCode.toString())) }

    override suspend fun saveScreenshot(destination: File): File = withContext(Dispatchers.IO) {
        touch()
        val frame = latestFrame() ?: error("Nenhum frame recebido da tela deste Prisma.")
        try {
            destination.canonicalFile.parentFile?.mkdirs()
            FileOutputStream(destination).use { output -> check(frame.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            destination
        } finally {
            frame.recycle()
        }
    }

    override fun latestFrame(): Bitmap? = synchronized(frameLock) { latest?.copy(Bitmap.Config.ARGB_8888, false) }

    private fun consumeFrame(source: ImageReader, targetWidth: Int, targetHeight: Int) {
        val image = source.acquireLatestImage() ?: return
        try {
            val plane = image.planes.firstOrNull() ?: return
            val paddedWidth = plane.rowStride / plane.pixelStride
            plane.buffer.rewind()
            val padded = Bitmap.createBitmap(paddedWidth, targetHeight, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (paddedWidth == targetWidth) padded else Bitmap.createBitmap(padded, 0, 0, targetWidth, targetHeight).also { padded.recycle() }
            synchronized(frameLock) {
                latest?.recycle()
                latest = cropped
                lastFrameAt = System.currentTimeMillis()
            }
        } finally {
            image.close()
        }
    }

    private fun touch() {
        lastUsedAt = System.currentTimeMillis()
    }

    private suspend fun <T> call(block: IPrivilegedShellService.() -> T): T = withContext(Dispatchers.IO) {
        touch()
        requireAuthorized()
        ShizukuShellBridge.connectedService().block()
    }

    private fun requireAuthorized() {
        check(CapabilityAuthorizationStore(context).isAuthorized("shizuku")) { "Shizuku não foi autorizado na Central de Capacidades." }
    }
}
