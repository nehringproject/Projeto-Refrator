package dev.agentworkbench

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ShadowDisplayKeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitor: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { ShizukuShadowDisplayBridge.get(applicationContext).stop() }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Tela paralela ativa", "Agente trabalhando sem ocupar a tela física"))
        acquireWakeLock()
        restoreIfNecessary()
        startMonitor()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitor?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun restoreIfNecessary() {
        val prefs = getSharedPreferences(ShizukuShadowDisplayBridge.PREFERENCES, MODE_PRIVATE)
        if (!prefs.getBoolean(ShizukuShadowDisplayBridge.KEY_ENABLED, false)) return
        val bridge = ShizukuShadowDisplayBridge.get(applicationContext)
        if (bridge.snapshot().active) return
        scope.launch {
            runCatching {
                bridge.start(
                    prefs.getInt(ShizukuShadowDisplayBridge.KEY_WIDTH, 720),
                    prefs.getInt(ShizukuShadowDisplayBridge.KEY_HEIGHT, 1600),
                    prefs.getInt(ShizukuShadowDisplayBridge.KEY_DENSITY, 280),
                )
            }.onFailure { error ->
                updateNotification("Retomada pendente", error.message ?: "Reative o Shizuku")
                releaseAndStop()
            }
        }
    }

    private fun startMonitor() {
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            while (isActive) {
                acquireWakeLock()
                val power = getSystemService(PowerManager::class.java)
                val battery = getSystemService(BatteryManager::class.java)
                val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    power.currentThermalStatus
                } else {
                    0
                }
                val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = battery.isCharging
                when {
                    thermal >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                        updateNotification("Pausado por temperatura crítica", "ShadowDisplay foi desligado para proteger o aparelho")
                        ShizukuShadowDisplayBridge.get(applicationContext).stop()
                        break
                    }
                    percent in 0..5 && !charging -> {
                        updateNotification("Pausado com bateria em $percent%", "Conecte o carregador para continuar")
                        ShizukuShadowDisplayBridge.get(applicationContext).stop()
                        break
                    }
                    thermal >= PowerManager.THERMAL_STATUS_SEVERE ->
                        updateNotification("Temperatura elevada", "Agente ativo · reduza FPS/gráficos do jogo")
                    else -> updateNotification("Tela paralela ativa", "Agente ativo · bateria $percent%")
                }
                delay(10_000)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:shadow-display")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            }
    }

    private fun releaseAndStop() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ShadowDisplay", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(title: String, text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_terminal)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(0, "Parar", PendingIntent.getService(
            this, 1, Intent(this, ShadowDisplayKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
        .build()

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
    }

    companion object {
        private const val CHANNEL_ID = "shadow-display"
        private const val NOTIFICATION_ID = 7302
        private const val ACTION_STOP = "dev.agentworkbench.shadow.STOP"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 2 * 60 * 1_000L

        fun start(context: Context, displayId: Int) {
            context.startForegroundService(
                Intent(context, ShadowDisplayKeepAliveService::class.java).putExtra("display_id", displayId),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShadowDisplayKeepAliveService::class.java))
        }
    }
}
