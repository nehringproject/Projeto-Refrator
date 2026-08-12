package dev.agentworkbench

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.view.Surface
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs as Android shell/root through Shizuku, outside a normal app process. */
class ShizukuUserService() : IPrivilegedShellService.Stub() {
    @Volatile private var shadowCallback: Any? = null
    @Volatile private var shadowDisplayId: Int? = null
    @Volatile private var shadowWidth: Int = 0
    @Volatile private var shadowHeight: Int = 0
    @Volatile private var shadowDensity: Int = 0

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun destroy() {
        releaseShadowDisplay()
        System.exit(0)
    }

    @Synchronized
    override fun createShadowDisplay(surface: Surface, width: Int, height: Int, densityDpi: Int): Int {
        require(width in 360..1920 && height in 640..3200) { "Dimensoes de ShadowDisplay invalidas." }
        require(densityDpi in 120..640) { "Densidade de ShadowDisplay invalida." }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            error("ShadowDisplay paralelo exige Android 14 ou superior.")
        }
        return createShadowDisplayApi34(surface, width, height, densityDpi)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @SuppressLint("WrongConstant") // Flags de sistema deliberadas: este serviço executa como UID shell via Shizuku.
    private fun createShadowDisplayApi34(surface: Surface, width: Int, height: Int, densityDpi: Int): Int {
        releaseShadowDisplay()
        val flags = FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TRUSTED or
            FLAG_ALWAYS_UNLOCKED or FLAG_OWN_FOCUS
        val config = VirtualDisplayConfig.Builder(
            "Refrator Shadow",
            width,
            height,
            densityDpi,
        ).setSurface(surface).setFlags(flags).build()
        val callback = createVirtualDisplayCallback()
        val manager = displayManagerBinder()
        val create = manager.javaClass.methods.firstOrNull {
            it.name == "createVirtualDisplay" && it.parameterTypes.size == 4
        } ?: error("IDisplayManager.createVirtualDisplay indisponivel neste Android.")
        val createdId = create.invoke(manager, config, callback, null, SHELL_PACKAGE) as Int
        check(createdId >= 0) { "O Android recusou a criacao da tela virtual ($createdId)." }
        shadowCallback = callback
        shadowDisplayId = createdId
        shadowWidth = width
        shadowHeight = height
        shadowDensity = densityDpi
        return createdId
    }

    @Synchronized
    override fun releaseShadowDisplay() {
        shadowCallback?.let { callback ->
            runCatching {
                val manager = displayManagerBinder()
                manager.javaClass.methods.first {
                    it.name == "releaseVirtualDisplay" && it.parameterTypes.size == 1
                }.invoke(manager, callback)
            }
        }
        shadowCallback = null
        shadowDisplayId = null
        shadowWidth = 0
        shadowHeight = 0
        shadowDensity = 0
    }

    override fun shadowDisplayState(): String = org.json.JSONObject()
        .put("available", shadowDisplayId != null)
        .put("display_id", shadowDisplayId ?: org.json.JSONObject.NULL)
        .put("width", shadowWidth)
        .put("height", shadowHeight)
        .put("density_dpi", shadowDensity)
        .put("display_state", if (shadowDisplayId != null) 2 else 0)
        .put("uid", Os.getuid())
        .put("owner_package", SHELL_PACKAGE)
        .put("secure_content_capture", false)
        .toString()

    override fun launchPackageOnShadowDisplay(packageName: String): String {
        require(PACKAGE_NAME.matches(packageName)) { "Nome de pacote invalido." }
        val displayId = requireShadowDisplayId()
        val component = runProcess(
            listOf(
                "/system/bin/cmd", "package", "resolve-activity", "--brief",
                "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, packageName,
            ),
        ).lineSequence().map(String::trim).lastOrNull { '/' in it }
            ?: error("O pacote nao possui Activity inicializavel.")
        val launched = runProcess(
            listOf(
                "/system/bin/am", "start", "--display", displayId.toString(),
                "-n", component,
            ),
        )
        check("Error:" !in launched && "Exception" !in launched) { launched }
        return org.json.JSONObject()
            .put("launched", true)
            .put("package", packageName)
            .put("component", component)
            .put("display_id", displayId)
            .toString()
    }

    override fun shadowTap(x: Int, y: Int): String {
        requirePoint(x, y)
        return runInput("tap", x.toString(), y.toString())
    }

    override fun shadowSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        requirePoint(x1, y1)
        requirePoint(x2, y2)
        return runInput(
            "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(),
            durationMs.coerceIn(50, 10_000).toString(),
        )
    }

    override fun shadowText(text: String): String {
        require(text.length in 1..500) { "Texto vazio ou grande demais." }
        return runInput("text", text.replace("%", "%25").replace(" ", "%s"))
    }

    override fun shadowKeyEvent(keyCode: Int): String {
        require(keyCode in 0..1_000) { "KeyCode invalido." }
        return runInput("keyevent", keyCode.toString())
    }

    private fun requireShadowDisplayId(): Int = shadowDisplayId
        ?: error("ShadowDisplay nao esta ativo.")

    private fun requirePoint(x: Int, y: Int) {
        require(x in 0 until shadowWidth && y in 0 until shadowHeight) {
            "Coordenada fora da tela virtual ${shadowWidth}x$shadowHeight."
        }
    }

    private fun runInput(vararg arguments: String): String {
        val displayId = requireShadowDisplayId()
        runProcess(listOf("/system/bin/input", "-d", displayId.toString()) + arguments)
        return org.json.JSONObject()
            .put("ok", true)
            .put("display_id", displayId)
            .put("action", arguments.firstOrNull().orEmpty())
            .toString()
    }

    private fun displayManagerBinder(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, Context.DISPLAY_SERVICE) as IBinder
        val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        return requireNotNull(
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder),
        ) { "DisplayManager binder indisponivel." }
    }

    private fun createVirtualDisplayCallback(): Any {
        val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        val token = Binder().apply { attachInterface(null, callbackClass.name) }
        return Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, _ ->
            when (method.name) {
                "asBinder" -> token
                "toString" -> "RefratorVirtualDisplayCallback"
                else -> null
            }
        }
    }

    private fun runProcess(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val completed = process.waitFor(15, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText().take(16_384) }
        check(completed && process.exitValue() == 0) { output.ifBlank { "Comando Android falhou." } }
        return output
    }

    @Synchronized
    override fun execute(script: String, timeoutMs: Int, maxOutputBytes: Int): String {
        require(script.isNotBlank() && script.length <= MAX_SCRIPT_CHARS) {
            "Script vazio ou maior que $MAX_SCRIPT_CHARS caracteres."
        }
        val boundedTimeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_MS)
        val boundedOutput = maxOutputBytes.coerceIn(1_024, MAX_OUTPUT_BYTES)
        val process = ProcessBuilder("/system/bin/sh", "-c", script)
            .directory(java.io.File("/data/local/tmp"))
            .redirectErrorStream(true)
            .start()
        val bytes = ByteArrayOutputStream(minOf(boundedOutput, 16_384))
        var truncated = false
        val reader = thread(start = true, isDaemon = true, name = "privileged-shell-output") {
            process.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(bytes) {
                        val remaining = boundedOutput - bytes.size()
                        if (remaining > 0) bytes.write(buffer, 0, minOf(count, remaining))
                        if (count > remaining) truncated = true
                    }
                }
            }
        }
        val completed = process.waitFor(boundedTimeout.toLong(), TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        reader.join(1_500)
        val output = synchronized(bytes) { bytes.toByteArray().toString(Charsets.UTF_8) }
        return buildString {
            appendLine("[shizuku uid=${Os.getuid()} pid=${Os.getpid()}]")
            append(output)
            if (output.isNotEmpty() && !output.endsWith('\n')) appendLine()
            if (truncated) appendLine("[output truncated at $boundedOutput bytes]")
            if (completed) append("[exit ${process.exitValue()}]") else append("[timeout ${boundedTimeout}ms]")
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        const val SHELL_PACKAGE = "com.android.shell"
        const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAG_TRUSTED = 1 shl 10
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        const val FLAG_OWN_FOCUS = 1 shl 14
        const val MAX_SCRIPT_CHARS = 8_192
        const val MAX_TIMEOUT_MS = 30_000
        const val MAX_OUTPUT_BYTES = 131_072
    }
}
