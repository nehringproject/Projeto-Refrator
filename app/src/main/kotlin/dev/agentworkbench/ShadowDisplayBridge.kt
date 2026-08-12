package dev.agentworkbench

import android.graphics.Bitmap
import java.io.File

data class ShadowDisplaySnapshot(
    val supported: Boolean,
    val active: Boolean,
    val displayId: Int?,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val lastFrameAtMillis: Long?,
    val detail: String,
)

/** Automation surface backed by the optional Shizuku capability. */
interface ShadowDisplayBridge {
    fun snapshot(): ShadowDisplaySnapshot
    suspend fun start(width: Int = 720, height: Int = 1600, densityDpi: Int = 280): ShadowDisplaySnapshot
    suspend fun stop()
    suspend fun launch(packageName: String): String
    suspend fun tap(x: Int, y: Int): String
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String
    suspend fun text(value: String): String
    suspend fun keyEvent(keyCode: Int): String
    suspend fun saveScreenshot(destination: File): File
    fun latestFrame(): Bitmap?
}
