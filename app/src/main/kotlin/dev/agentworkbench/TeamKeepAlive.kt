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
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the Sociedade Prisma working with the screen off or the app in the background: a
 * foreground service with a visible notification and a partial wake lock, alive while a group is
 * working or the local web server is on. It also owns [LocalWebServer], so a game or page the
 * prismas serve does not die between tool calls.
 */
class TeamKeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = buildNotification()
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        if (foreground.isFailure) {
            // Android refused a background restart; the work resumes when the app opens.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            LocalWebServer.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (LocalWebServer.wanted(this)) LocalWebServer.start(this)
        if (intent == null) {
            // Android restarted us after the process died: pick the work back up on our own.
            runCatching { GroupEngine.resumeAfterDeath(this) }
            runCatching { MissionEngine.resumeAll(this) }
        }
        scope.launch {
            // Stay while there is work; leave on our own when everything is quiet for a while.
            var quietFor = 0
            while (isActive) {
                delay(30_000)
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
                runCatching { PrismaDisplays.releaseIdle() }
                quietFor = if (GroupEngine.anyActive() || LocalWebServer.running || MissionEngine.anyRunning() || PrismaDisplays.active().isNotEmpty()) 0 else quietFor + 1
                if (quietFor >= 4) {
                    stopSelf()
                    break
                }
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock?.acquire(WAKE_LOCK_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:team")
            .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MS) }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 41, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 42, Intent(this, TeamKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val parts = buildList {
            val working = GroupEngine.workingSummary()
            if (working.isNotBlank()) add(working)
            if (LocalWebServer.running) add("servidor em ${LocalWebServer.baseUrl()}")
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_refrator_mark)
            .setContentTitle("Sociedade Prisma ativa")
            .setContentText(parts.joinToString(" · ").ifBlank { "Pronta para continuar o trabalho" })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Parar servidor", stop).build())
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Time trabalhando", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantém os Prismas e o servidor local funcionando com a tela desligada"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL = "team-keepalive"
        private const val NOTIFICATION_ID = 7_410
        private const val ACTION_STOP = "dev.agentworkbench.TEAM_STOP"
        private const val WAKE_LOCK_MS = 10 * 60_000L

        fun ensure(context: Context) {
            runCatching {
                val intent = Intent(context.applicationContext, TeamKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            }
        }
    }
}

/**
 * A tiny static file server for the workspace, on 127.0.0.1 only (nothing is exposed to the
 * network). Lives inside [TeamKeepAliveService], so pages the prismas serve keep working between
 * tool calls and with the screen off. Paths are confined to the workspace root.
 */
internal object LocalWebServer {
    const val PORT = 8780
    @Volatile var running = false
        private set
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private const val PREFS = "local-web-server"

    fun baseUrl() = "http://127.0.0.1:$PORT"

    /** The server was turned on (remembered across restarts until turned off). */
    fun wanted(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

    /** Turns the server on (through the keep-alive service) and returns the URL of [relative]. */
    fun serve(context: Context, relative: String): String {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", true).apply()
        start(context)
        TeamKeepAliveService.ensure(context)
        val path = relative.trim().trim('/').split('/').filter(String::isNotBlank).joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "${baseUrl()}/$path"
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    @Synchronized
    fun start(context: Context) {
        if (running) return
        val root = File(context.applicationContext.filesDir, "workspace").apply { mkdirs() }.canonicalFile
        val server = try {
            ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"))
        } catch (_: IOException) {
            return
        }
        socket = server
        running = true
        Thread({
            while (running) {
                val client = try { server.accept() } catch (_: IOException) { break }
                pool.execute { handle(client, root) }
            }
            running = false
        }, "refrator-web").apply { isDaemon = true }.start()
    }

    private fun handle(client: Socket, root: File) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = 15_000
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                var path = URLDecoder.decode(parts.getOrNull(1).orEmpty().substringBefore('?'), "UTF-8")
                val out = socket.getOutputStream()
                if (method != "GET" && method != "HEAD") {
                    out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                if (path.endsWith("/")) path += "index.html"
                val target = File(root, path.trimStart('/')).canonicalFile
                if (!target.path.startsWith(root.path) || !target.isFile) {
                    val body = "404 — não encontrado: $path".toByteArray()
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    return
                }
                val header = "HTTP/1.1 200 OK\r\nContent-Type: ${mime(target.name)}\r\nContent-Length: ${target.length()}\r\n" +
                    "Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                if (method == "GET") target.inputStream().use { it.copyTo(out) }
                out.flush()
            }
        }
    }

    private fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs", "cjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "wasm" -> "application/wasm"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "txt", "md" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
