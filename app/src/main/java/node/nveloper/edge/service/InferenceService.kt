package node.nveloper.edge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import node.nveloper.edge.AppContainer
import node.nveloper.edge.EdgeApplication
import node.nveloper.edge.R
import node.nveloper.edge.inference.InferenceManager
import node.nveloper.edge.settings.WakeLockMode
import node.nveloper.edge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (foreground service, type specialUse) and hosts the HTTP server.
 * Also holds the wake/Wi-Fi locks that keep aggressive vendor power management
 * from suspending the CPU or radio while serving.
 */
class InferenceService : Service() {
    companion object {
        private const val ACTION_STOP = "node.nveloper.edge.action.STOP"
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "service"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        /** [reason] USER marks the server as wanted, so [ServerKeeper] brings it back if killed. */
        fun start(context: Context, reason: ServerKeeper.Reason = ServerKeeper.Reason.USER) {
            if (reason == ServerKeeper.Reason.USER) {
                ServerKeeper.resume(context)
                ServerKeeper.setWanted(context, true)
            }
            context.startForegroundService(
                Intent(context, InferenceService::class.java).putExtra(ServerKeeper.EXTRA_REASON, reason.name),
            )
        }

        /** User-initiated stop: the keeper will not restart it. */
        fun stop(context: Context) {
            ServerKeeper.setWanted(context, false)
            context.stopService(Intent(context, InferenceService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var started = false

    override fun onCreate() {
        super.onCreate()
        container = (application as EdgeApplication).container
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_server), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServerKeeper.setWanted(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notif_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when restarted from the background.
            container.log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            // A null intent means the system recreated the service after killing the process.
            val reason = intent?.getStringExtra(ServerKeeper.EXTRA_REASON)
                ?.let { name -> ServerKeeper.Reason.entries.firstOrNull { it.name == name } }
                ?: ServerKeeper.Reason.STICKY
            val manual = reason == ServerKeeper.Reason.USER || reason == ServerKeeper.Reason.NOTIFICATION
            if (!manual && ServerKeeper.isSuspended(this)) {
                // Crash loop: stay down until the user starts the server by hand.
                container.log.w(TAG, "not restarting ($reason): auto restart is suspended")
                stopSelf()
                return START_NOT_STICKY
            }
            ServerKeeper.recordRestart(this, reason)
            ServerKeeper.dismissStoppedNotice(this)
            if (reason != ServerKeeper.Reason.USER) container.log.i(TAG, "restarted ($reason)")
            begin()
        }
        return START_STICKY
    }

    private fun begin() {
        val settings = container.settings.current
        acquireWifiLocks()
        if (settings.wakeLockMode == WakeLockMode.ALWAYS) acquireWakeLock()

        try {
            container.server.start(settings.port)
        } catch (e: Exception) {
            container.log.e(TAG, "server failed to start on port ${settings.port}", e)
            stopSelf()
            return
        }
        _running.value = true
        container.ensureBackendLoaded()

        if (settings.wakeLockMode == WakeLockMode.DURING_REQUEST) {
            scope.launch {
                container.inference.active.collect { n -> if (n > 0) acquireWakeLock() else releaseWakeLock() }
            }
        }
        scope.launch {
            combine(container.inference.state, container.inference.stats, container.inference.active) { state, stats, active ->
                val model = when (state) {
                    InferenceManager.State.Empty -> getString(R.string.no_model)
                    is InferenceManager.State.Loading -> getString(R.string.pill_loading)
                    is InferenceManager.State.Ready -> state.modelId
                    is InferenceManager.State.Failed -> getString(R.string.model_load_failed)
                }
                getString(R.string.notif_server_text, settings.port, model, stats.completed, active)
            }.distinctUntilChanged().collect { text ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
        container.log.i(TAG, "started (wakeLock=${settings.wakeLockMode})")
    }

    override fun onDestroy() {
        scope.cancel()
        container.server.stop()
        releaseWakeLock()
        wifiLocks.forEach { if (it.isHeld) it.release() }
        wifiLocks.clear()
        _running.value = false
        container.log.i(TAG, "stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Edge:serve")
            .also { it.setReferenceCounted(false); wakeLock = it }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLocks() {
        val wifi = getSystemService(WifiManager::class.java) ?: return
        // LOW_LATENCY only applies while the screen is on; HIGH_PERF is what keeps Wi-Fi
        // responsive with the screen off, but it is a no-op from Android 14 onwards.
        wifiLocks += wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Edge:wifi-ll")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            wifiLocks += wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Edge:wifi-hp")
        }
        wifiLocks.forEach { it.setReferenceCounted(false); it.acquire() }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, InferenceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_server_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }
}
