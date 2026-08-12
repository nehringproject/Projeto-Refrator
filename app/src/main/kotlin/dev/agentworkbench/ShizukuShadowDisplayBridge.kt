package dev.agentworkbench

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ShizukuShadowDisplayBridge private constructor(private val context: Context) : ShadowDisplayBridge {
    private val frameLock = Any()
    @Volatile private var reader: ImageReader? = null
    @Volatile private var frameThread: HandlerThread? = null
    @Volatile private var latest: Bitmap? = null
    @Volatile private var lastFrameAt: Long? = null
    @Volatile private var displayId: Int? = null
    @Volatile private var width: Int = 0
    @Volatile private var height: Int = 0
    @Volatile private var density: Int = 0
    @Volatile private var detail: String = "ShadowDisplay parado."

    override fun snapshot(): ShadowDisplaySnapshot {
        val shell = ShizukuShellBridge.snapshot()
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.currentThermalStatus
        } else {
            0
        }
        return ShadowDisplaySnapshot(
            supported = shell.permissionGranted,
            active = displayId != null,
            displayId = displayId,
            width = width,
            height = height,
            densityDpi = density,
            lastFrameAtMillis = lastFrameAt,
            detail = if (!shell.permissionGranted) shell.detail else "$detail · térmico=$thermal",
        )
    }

    override suspend fun start(width: Int, height: Int, densityDpi: Int): ShadowDisplaySnapshot {
        require(width in 360..1920 && height in 640..3200)
        require(densityDpi in 120..640)
        releaseRuntime(clearPreference = false, stopKeepAlive = false)
        val thread = HandlerThread("shadow-display-frames").apply { start() }
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ source -> consumeFrame(source, width, height) }, Handler(thread.looper))
        try {
            val id = withContext(Dispatchers.IO) {
                ShizukuShellBridge.connectedService().createShadowDisplay(
                    imageReader.surface,
                    width,
                    height,
                    densityDpi,
                )
            }
            this.width = width
            this.height = height
            density = densityDpi
            displayId = id
            reader = imageReader
            frameThread = thread
            detail = "Tela paralela ativa no display $id; conteúdo FLAG_SECURE permanece oculto."
            preferences().edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_WIDTH, width)
                .putInt(KEY_HEIGHT, height)
                .putInt(KEY_DENSITY, densityDpi)
                .apply()
            ShadowDisplayKeepAliveService.start(context, id)
            return snapshot()
        } catch (error: Throwable) {
            imageReader.close()
            thread.quitSafely()
            throw error
        }
    }

    override suspend fun stop() = releaseRuntime(clearPreference = true, stopKeepAlive = true)

    private suspend fun releaseRuntime(clearPreference: Boolean, stopKeepAlive: Boolean) {
        runCatching {
            withContext(Dispatchers.IO) {
                ShizukuShellBridge.connectedService().releaseShadowDisplay()
            }
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
        detail = "ShadowDisplay parado."
        if (clearPreference) preferences().edit().putBoolean(KEY_ENABLED, false).apply()
        if (stopKeepAlive) ShadowDisplayKeepAliveService.stop(context)
    }

    override suspend fun launch(packageName: String): String = serviceCall {
        launchPackageOnShadowDisplay(packageName)
    }

    override suspend fun tap(x: Int, y: Int): String = serviceCall { shadowTap(x, y) }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
        serviceCall { shadowSwipe(x1, y1, x2, y2, durationMs) }

    override suspend fun text(value: String): String = serviceCall { shadowText(value) }

    override suspend fun keyEvent(keyCode: Int): String = serviceCall { shadowKeyEvent(keyCode) }

    override suspend fun saveScreenshot(destination: File): File = withContext(Dispatchers.IO) {
        val frame = latestFrame() ?: error("Nenhum frame recebido da tela paralela.")
        try {
            destination.canonicalFile.parentFile?.mkdirs()
            val temporary = File.createTempFile(".shadow-", ".png", destination.parentFile)
            try {
                FileOutputStream(temporary).use { output ->
                    check(frame.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.fd.sync()
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
            } finally {
                temporary.delete()
            }
            destination
        } finally {
            frame.recycle()
        }
    }

    override fun latestFrame(): Bitmap? = synchronized(frameLock) {
        latest?.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun consumeFrame(source: ImageReader, targetWidth: Int, targetHeight: Int) {
        val image = source.acquireLatestImage() ?: return
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedWidth = rowStride / pixelStride
            plane.buffer.rewind()
            val padded = Bitmap.createBitmap(paddedWidth, targetHeight, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (paddedWidth == targetWidth) padded else {
                Bitmap.createBitmap(padded, 0, 0, targetWidth, targetHeight).also { padded.recycle() }
            }
            synchronized(frameLock) {
                latest?.recycle()
                latest = cropped
                lastFrameAt = System.currentTimeMillis()
            }
        } finally {
            image.close()
        }
    }

    private suspend fun <T> serviceCall(block: IPrivilegedShellService.() -> T): T =
        withContext(Dispatchers.IO) { ShizukuShellBridge.connectedService().block() }

    companion object {
        const val PREFERENCES = "shadow-display"
        const val KEY_ENABLED = "enabled"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_DENSITY = "density"
        // The singleton retains only the process-wide application context.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ShizukuShadowDisplayBridge? = null
        fun get(context: Context): ShizukuShadowDisplayBridge = instance ?: synchronized(this) {
            instance ?: ShizukuShadowDisplayBridge(context.applicationContext).also { instance = it }
        }
    }

    private fun preferences() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
