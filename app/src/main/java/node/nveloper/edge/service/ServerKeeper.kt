package node.nveloper.edge.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import node.nveloper.edge.EdgeApplication
import node.nveloper.edge.R
import node.nveloper.edge.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Brings the server back after the OS kills it. The user's intent ("server should be running")
 * is persisted, and three paths restart it: START_STICKY, BOOT_COMPLETED / app update, and a
 * 15-minute WorkManager watchdog. Background FGS starts are allowed once the app is exempt from
 * battery optimization; when they are not, a notification lets the user restart with one tap.
 *
 * Crash-loop guard: abnormal process deaths are read back from ApplicationExitInfo on the next
 * start and keyed by cause + what the app was doing (the phase breadcrumb: loading or generating,
 * on which backend and model). When the same key repeats Settings.crashLimit times within an
 * hour, auto restart is suspended and the user is notified; starting the server by hand resumes.
 */
object ServerKeeper {
    enum class Reason { USER, STICKY, BOOT, UPDATE, WATCHDOG, NOTIFICATION }

    data class Stats(
        val restarts: Int,
        val lastReason: Reason?,
        val lastRestartMs: Long,
        /** Death cause that suspended auto restart, null while it is active. */
        val suspendedBy: String?,
    )

    private const val PREFS = "keeper"
    private const val WORK = "server-watchdog"
    private const val CHANNEL_ID = "keeper"
    private const val NOTIFICATION_ID = 2
    private const val SUSPEND_NOTIFICATION_ID = 4
    private const val GPU_NOTIFICATION_ID = 5
    private const val CRASH_WINDOW_MS = 60 * 60 * 1000L
    const val EXTRA_REASON = "reason"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isWanted(context: Context) = prefs(context).getBoolean("wanted", false)

    /** Called when the user starts or stops the server. */
    fun setWanted(context: Context, wanted: Boolean) {
        prefs(context).edit().putBoolean("wanted", wanted).apply()
        val work = WorkManager.getInstance(context)
        if (wanted && autoRestart(context)) {
            work.enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
            )
        } else {
            work.cancelUniqueWork(WORK)
        }
        if (!wanted) context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** Re-applies the watchdog schedule after the auto-restart setting changes. */
    fun refresh(context: Context) = setWanted(context, isWanted(context))

    private fun autoRestart(context: Context) =
        (context.applicationContext as EdgeApplication).container.settings.current.autoRestart

    fun stats(context: Context): Stats {
        val p = prefs(context)
        return Stats(
            restarts = p.getInt("restarts", 0),
            lastReason = p.getString("lastReason", null)?.let { name -> Reason.entries.firstOrNull { it.name == name } },
            lastRestartMs = p.getLong("lastRestartMs", 0),
            suspendedBy = p.getString("suspendedBy", null),
        )
    }

    /** Records a start that the user did not trigger. */
    fun recordRestart(context: Context, reason: Reason) {
        if (reason == Reason.USER) return
        val p = prefs(context)
        p.edit()
            .putInt("restarts", p.getInt("restarts", 0) + 1)
            .putString("lastReason", reason.name)
            .putLong("lastRestartMs", System.currentTimeMillis())
            .apply()
    }

    /** Starts the server if the user wants it running and it is not. */
    fun ensureRunning(context: Context, reason: Reason) {
        if (!isWanted(context) || !autoRestart(context) || isSuspended(context) || InferenceService.running.value) return
        val log = (context.applicationContext as EdgeApplication).container.log
        try {
            InferenceService.start(context, reason)
            log.i("keeper", "restarting server ($reason)")
        } catch (e: ForegroundServiceStartNotAllowedException) {
            log.w("keeper", "background start blocked ($reason); asking the user", e)
            notifyStopped(context)
        } catch (e: IllegalStateException) {
            log.w("keeper", "background start blocked ($reason); asking the user", e)
            notifyStopped(context)
        }
    }

    private fun notifyStopped(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_keeper), NotificationManager.IMPORTANCE_DEFAULT),
        )
        // Starting a foreground service from a notification tap is always allowed.
        val restart = PendingIntent.getForegroundService(
            context, 3,
            Intent(context, InferenceService::class.java).putExtra(EXTRA_REASON, Reason.NOTIFICATION.name),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_stopped_title))
                .setContentText(context.getString(R.string.notif_stopped_text))
                .setContentIntent(restart)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun isSuspended(context: Context) = prefs(context).getString("suspendedBy", null) != null

    /** A manual start: forget past crashes and lift a suspension. */
    fun resume(context: Context) {
        prefs(context).edit().remove("suspendedBy").remove("deaths").apply()
        context.getSystemService(NotificationManager::class.java).cancel(SUSPEND_NOTIFICATION_ID)
    }

    /**
     * Breadcrumb for what the process is doing ("load · LLAMA_VULKAN · model.gguf"), or null when
     * idle. Written with commit() so it survives a native crash right after.
     */
    fun setPhase(context: Context, phase: String?) {
        val edit = prefs(context).edit()
        if (phase == null) edit.remove("phase").apply() else edit.putString("phase", phase).commit()
    }

    /** Reads deaths since the last scan; call once per process start, before any restart. */
    fun scanExits(context: Context) {
        val p = prefs(context)
        val lastSeen = p.getLong("lastExitTs", 0)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, 8)
            .filter { it.processName == context.packageName && it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
        if (exits.isEmpty()) return
        val phase = p.getString("phase", null)
        val deaths = p.getString("deaths", "").orEmpty().lines().filter { it.isNotBlank() }.toMutableList()
        val log = (context.applicationContext as EdgeApplication).container.log
        exits.forEachIndexed { i, exit ->
            // The breadcrumb belongs to the most recent process only.
            val at = if (i == exits.lastIndex) phase else null
            val cause = causeOf(exit, at) ?: return@forEachIndexed
            val key = listOfNotNull(cause, at).joinToString(" · ")
            log.w("keeper", "previous process died: $key (${exit.description ?: "no description"})")
            deaths += "${exit.timestamp}|$key"
        }
        val recent = deaths.takeLast(20)
        p.edit()
            .putLong("lastExitTs", exits.last().timestamp)
            .putString("deaths", recent.joinToString("\n"))
            .remove("phase")
            .apply()

        val last = recent.lastOrNull()?.split('|', limit = 2) ?: return
        val key = last[1]
        val since = last[0].toLong() - CRASH_WINDOW_MS
        val count = recent.count { line -> line.split('|', limit = 2).let { it[1] == key && it[0].toLong() >= since } }
        val limit = (context.applicationContext as EdgeApplication).container.settings.current.crashLimit
        if (count >= limit && !isSuspended(context)) suspend(context, key, count)
    }

    /**
     * Deaths that indicate a fault rather than power management. A plain SIGKILL is also what
     * vendor battery managers send, so it only counts when it hit during load or generation
     * (typically the low-memory killer).
     */
    private fun causeOf(exit: ApplicationExitInfo, phase: String?): String? = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init failure"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_SIGNALED -> if (phase != null) "killed (signal ${exit.status})" else null
        else -> null
    }

    private fun suspend(context: Context, key: String, count: Int) {
        prefs(context).edit().putString("suspendedBy", key).apply()
        val log = (context.applicationContext as EdgeApplication).container.log
        log.e("keeper", "auto restart suspended: $key x$count")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_keeper), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = context.getString(R.string.suspended_text, key, count)
        manager.notify(
            SUSPEND_NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_suspended_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The model could not go on the GPU; the app shows a dialog with the choices when opened. */
    fun notifyGpuFailure(context: Context, error: node.nveloper.edge.inference.GpuLoadException) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_keeper), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(context, 5, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = "${error.accelerator}: ${error.reason}"
        manager.notify(
            GPU_NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.gpu_failed_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun dismissGpuFailure(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(GPU_NOTIFICATION_ID)
    }

    fun dismissStoppedNotice(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ServerKeeper.ensureRunning(applicationContext, ServerKeeper.Reason.WATCHDOG)
        return Result.success()
    }
}

/** Restarts the server after a reboot or an app update if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ServerKeeper.Reason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ServerKeeper.Reason.UPDATE
            else -> return
        }
        ServerKeeper.ensureRunning(context, reason)
        // WorkManager drops periodic work on some vendor ROMs across reboots; re-arm it.
        ServerKeeper.refresh(context)
    }
}
