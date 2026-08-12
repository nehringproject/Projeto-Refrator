package dev.agentworkbench

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

object ShizukuShellBridge : PrivilegedShellBridge {
    @Volatile
    private var service: IPrivilegedShellService? = null

    @Volatile
    private var pendingConnection: CompletableDeferred<IPrivilegedShellService>? = null

    private val connectionMutex = Mutex()
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("privileged_shell")
            .debuggable(BuildConfig.DEBUG)
            // Shizuku can keep this process alive across an in-place APK update.
            // A dedicated protocol version prevents reusing an older AIDL service.
            .version(SHIZUKU_SERVICE_VERSION)
            .tag("agent-workbench-shell-v$SHIZUKU_SERVICE_VERSION")
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            val connected = binder
                ?.takeIf(IBinder::pingBinder)
                ?.let(IPrivilegedShellService.Stub::asInterface)
            service = connected
            val waiter = pendingConnection
            if (connected != null) {
                waiter?.complete(connected)
            } else {
                waiter?.completeExceptionally(
                    IllegalStateException("Shizuku retornou um binder inválido."),
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun snapshot(): PrivilegedShellSnapshot {
        return try {
            val running = Shizuku.pingBinder()
            val granted = running &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val connected = service?.asBinder()?.pingBinder() == true
            PrivilegedShellSnapshot(
                supported = true,
                serverRunning = running,
                permissionGranted = granted,
                serviceConnected = connected,
                uid = if (granted) Shizuku.getUid() else null,
                detail = when {
                    !running -> "Inicie o serviço no app Shizuku após cada reinicialização."
                    granted && Shizuku.getUid() == 0 -> "Backend root ativo."
                    granted -> "Backend ADB shell; isto não é root."
                    else -> "A permissão é controlada e revogável pelo Shizuku."
                },
            )
        } catch (error: Throwable) {
            PrivilegedShellSnapshot(
                supported = true,
                serverRunning = false,
                permissionGranted = false,
                serviceConnected = false,
                uid = null,
                detail = error.message.orEmpty().take(160),
            )
        }
    }

    override fun requestPermission(activity: Activity, requestCode: Int): Boolean {
        @Suppress("UNUSED_VARIABLE")
        val keepActivityReferenceUntilCallReturns = activity
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            if (Shizuku.shouldShowRequestPermissionRationale()) return false
            Shizuku.requestPermission(requestCode)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun execute(
        script: String,
        timeoutMs: Int,
        maxOutputBytes: Int,
    ): String {
        val snapshot = snapshot()
        check(snapshot.serverRunning) { "O serviço Shizuku não está rodando." }
        check(snapshot.permissionGranted) {
            "Autorize o Refrator no cartão Shizuku da aba Ferramentas."
        }
        val connected = connect()
        return withContext(Dispatchers.IO) {
            connected.execute(script, timeoutMs, maxOutputBytes)
        }
    }

    internal suspend fun connectedService(): IPrivilegedShellService {
        val current = snapshot()
        check(current.serverRunning) { "O serviço Shizuku não está rodando." }
        check(current.permissionGranted) { "Autorize o Refrator no Shizuku." }
        return connect()
    }

    private suspend fun connect(): IPrivilegedShellService = connectionMutex.withLock {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val waiter = CompletableDeferred<IPrivilegedShellService>()
        pendingConnection = waiter
        try {
            withContext(Dispatchers.Main.immediate) {
                Shizuku.bindUserService(userServiceArgs, connection)
            }
            withTimeout(CONNECTION_TIMEOUT_MS) { waiter.await() }
        } finally {
            if (pendingConnection === waiter) pendingConnection = null
        }
    }

    private const val SHIZUKU_SERVICE_VERSION = 5
    private const val CONNECTION_TIMEOUT_MS = 8_000L
}
