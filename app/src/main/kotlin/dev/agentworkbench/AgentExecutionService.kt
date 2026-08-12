package dev.agentworkbench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Process anchor for approved persistent runs.
 *
 * The service deliberately owns no provider credentials and performs no polling. The execution
 * coordinator publishes durable state in Room. This service owns the Activity-independent engine,
 * keeps Android execution rights only while a QUEUED/RUNNING/RECOVERING step exists, and exits as
 * soon as the durable queue becomes idle. WorkManager is responsible for later recovery.
 */
class AgentExecutionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: ExecutionRepository
    private var observerJob: Job? = null
    private var statusJob: Job? = null
    private val executionJobs = ConcurrentHashMap<String, Job>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRefreshJob: Job? = null
    private var foregroundStarted = false
    private var requestedRunId: String? = null
    private var requestedSummary: String? = null

    override fun onCreate() {
        super.onCreate()
        repository = ExecutionRepository(applicationContext)
        createNotificationChannel()
        statusJob = scope.launch {
            AgentExecutionStatusBus.updates.collect { update ->
                if (requestedRunId == null || requestedRunId == update.runId) {
                    requestedRunId = update.runId
                    requestedSummary = update.notificationText
                    if (foregroundStarted) enterForeground(update.notificationText)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestedRunId = intent?.getStringExtra(EXTRA_RUN_ID) ?: requestedRunId
        requestedSummary = intent?.getStringExtra(EXTRA_SUMMARY) ?: requestedSummary

        when (intent?.action ?: ACTION_START) {
            ACTION_PAUSE -> {
                pauseRuns(requestedRunId)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                pauseRuns(requestedRunId)
                shutdown(startId)
                return START_NOT_STICKY
            }
        }

        enterForeground(requestedSummary ?: getString(R.string.app_name))
        observeExecutableRuns(startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observerJob?.cancel()
        statusJob?.cancel()
        executionJobs.values.forEach(Job::cancel)
        executionJobs.clear()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun observeExecutableRuns(startId: Int) {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            repository.recoverInterrupted()
            repository.observeActiveRuns().collect { runs ->
                val online = ConnectivityMonitor.isOnline(this@AgentExecutionService)
                val executable = runs.filter { run ->
                    run.state == AgentRunState.QUEUED.name ||
                        run.state == AgentRunState.RUNNING.name ||
                        run.state == AgentRunState.RECOVERING.name
                }.filter { run ->
                    // Um run parado por falta de rede fica em RECOVERING, que normalmente conta
                    // como executável. Sem esta guarda o motor seria relançado no mesmo instante,
                    // falharia de novo por falta de rede e giraria em loop quente segurando
                    // wake lock. Offline, deixamos o serviço encerrar: quem retoma é o
                    // AgentRecoveryWorker, que só roda quando o Android confirma conexão.
                    run.state != AgentRunState.RECOVERING.name || online || !run.requiresNetwork
                }
                    .sortedBy(AgentRunEntity::createdAtMillis)
                    .fold(mutableListOf<AgentRunEntity>()) { selected, candidate ->
                        val localAlreadySelected = selected.any {
                            it.providerPreset == ProviderPreset.LOCAL_GGUF.name
                        }
                        if (
                            selected.size < MAX_CONCURRENT_RUNS &&
                            !(localAlreadySelected && candidate.providerPreset == ProviderPreset.LOCAL_GGUF.name)
                        ) {
                            selected += candidate
                        }
                        selected
                    }
                if (executable.isEmpty() && runs.any { it.state == AgentRunState.RECOVERING.name }) {
                    notifyWaitingForNetwork()
                }
                val executableIds = executable.mapTo(hashSetOf(), AgentRunEntity::id)
                executionJobs.entries
                    .filter { (runId, _) -> runId !in executableIds }
                    .forEach { (runId, job) ->
                        if (executionJobs.remove(runId, job)) job.cancel()
                    }
                if (executable.isEmpty()) {
                    shutdown(startId)
                } else {
                    // Voltou a rodar: o aviso de "aguardando rede" não faz mais sentido.
                    getSystemService(NotificationManager::class.java)
                        .cancel(WAITING_NETWORK_NOTIFICATION_ID)
                    acquireWakeLock()
                    val visible = requestedRunId?.let { id ->
                        executable.firstOrNull { it.id == id }
                    } ?: executable.first()
                    enterForeground(visible.summary)
                    executable.forEach(::startRunIfNeeded)
                }
            }
        }
    }

    private fun startRunIfNeeded(run: AgentRunEntity) {
        if (executionJobs[run.id]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            AgentBackgroundEngine(applicationContext, repository).execute(run)
        }
        val previous = executionJobs.putIfAbsent(run.id, job)
        if (previous == null) {
            job.invokeOnCompletion { executionJobs.remove(run.id, job) }
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun pauseRuns(runId: String?) {
        scope.launch {
            val targets = repository.activeRuns().filter { run ->
                (runId == null || run.id == runId) &&
                    run.state !in setOf(
                        AgentRunState.SUCCEEDED.name,
                        AgentRunState.FAILED.name,
                    )
            }
            targets.forEach { repository.checkpointRun(it.id, AgentRunState.PAUSED) }
            if (targets.isEmpty()) shutdown()
        }
    }

    private fun enterForeground(summary: String) {
        val notification = buildNotification(summary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildNotification(summary: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pausePendingIntent = servicePendingIntent(ACTION_PAUSE, 1)
        val stopPendingIntent = servicePendingIntent(ACTION_STOP, 2)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Refrator em execução")
            .setContentText(summary.take(100))
            .setStyle(Notification.BigTextStyle().bigText(summary.take(500)))
            .setContentIntent(openPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Abrir", openPendingIntent).build())
            .addAction(Notification.Action.Builder(null, "Pausar", pausePendingIntent).build())
            .addAction(Notification.Action.Builder(null, "Parar", stopPendingIntent).build())
            .build()
    }

    /**
     * O serviço encerra enquanto espera a rede, então a notificação contínua some junto. Sem
     * aviso nenhum a tarefa pareceria ter morrido — esta fica no lugar dela até a retomada.
     */
    private fun notifyWaitingForNetwork() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            this,
            3,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        getSystemService(NotificationManager::class.java).notify(
            WAITING_NETWORK_NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Aguardando rede")
                .setContentText("A missão retoma sozinha assim que a conexão voltar.")
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AgentExecutionService::class.java).apply {
            this.action = action
            putExtra(EXTRA_RUN_ID, requestedRunId)
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Execucao persistente do agente",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Tarefa, ferramenta e modelo da execução persistente"
                setShowBadge(false)
            },
        )
    }

    private fun acquireWakeLock() {
        val current = wakeLock
        if (current?.isHeld != true) {
            wakeLock = (current ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agent-execution")
                .apply { setReferenceCounted(false) })
                .also { it.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        }
        if (wakeLockRefreshJob?.isActive == true) return
        wakeLockRefreshJob = scope.launch {
            while (isActive) {
                delay(WAKE_LOCK_REFRESH_MILLIS)
                if (executionJobs.isEmpty()) break
                wakeLock?.let { lock ->
                    if (lock.isHeld) lock.release()
                    lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                }
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun shutdown(startId: Int? = null) {
        releaseWakeLock()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    companion object {
        private const val CHANNEL_ID = "agent_execution"
        private const val NOTIFICATION_ID = 4101
        private const val WAITING_NETWORK_NOTIFICATION_ID = 4103
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 11 * 60 * 1_000L
        private const val WAKE_LOCK_REFRESH_MILLIS = 10 * 60 * 1_000L
        // CPython, WebView and large provider contexts make unbounded parallel runs unsafe on a
        // phone. Remaining QUEUED runs stay durable and start when one of these slots finishes.
        private const val MAX_CONCURRENT_RUNS = 2
        private const val ACTION_START = "dev.agentworkbench.action.START_EXECUTION"
        private const val ACTION_PAUSE = "dev.agentworkbench.action.PAUSE_EXECUTION"
        private const val ACTION_STOP = "dev.agentworkbench.action.STOP_EXECUTION"
        private const val EXTRA_RUN_ID = "run_id"
        private const val EXTRA_SUMMARY = "summary"
        fun start(context: Context, runId: String? = null, summary: String? = null) {
            val intent = Intent(context, AgentExecutionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RUN_ID, runId)
                putExtra(EXTRA_SUMMARY, summary)
            }
            context.startForegroundService(intent)
        }

        fun pause(context: Context, runId: String? = null) =
            sendAction(context, ACTION_PAUSE, runId)

        fun stop(context: Context, runId: String? = null) =
            sendAction(context, ACTION_STOP, runId)

        private fun sendAction(context: Context, action: String, runId: String?) {
            context.startService(
                Intent(context, AgentExecutionService::class.java).apply {
                    this.action = action
                    putExtra(EXTRA_RUN_ID, runId)
                },
            )
        }
    }
}
