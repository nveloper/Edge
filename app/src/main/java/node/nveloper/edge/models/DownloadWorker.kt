package node.nveloper.edge.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import node.nveloper.edge.EdgeApplication
import node.nveloper.edge.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one model file into the model directory as foreground work, the way AI Edge Gallery
 * does: progress notification, resumable through a `.part` file and HTTP Range requests, and an
 * optional Hugging Face token for gated repos.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE = "file"
        const val KEY_TITLE = "title"
        const val KEY_EXPECTED = "expected"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_SPEED = "speed"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "downloads"
    }

    private val fileName = inputData.getString(KEY_FILE)!!
    private val title = inputData.getString(KEY_TITLE) ?: fileName
    private val notificationId = 1000 + (fileName.hashCode() and 0x0FFF)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as EdgeApplication
        val store = app.container.models
        val url = inputData.getString(KEY_URL)!!
        val expected = inputData.getLong(KEY_EXPECTED, -1)
        val token = app.container.settings.current.hfToken
        val target = File(store.dir, fileName)
        val part = File(store.dir, "$fileName.part")

        setForeground(foregroundInfo(0, 0, expected))
        try {
            var offset = if (part.isFile) part.length() else 0L
            if (expected > 0) {
                val needed = expected - offset + 64L * 1024 * 1024
                if (store.dir.usableSpace < needed) {
                    return@withContext fail(app.getString(R.string.err_no_space, formatBytes(needed), formatBytes(store.dir.usableSpace)))
                }
            }

            val connection = open(url, token, offset)
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN ->
                    return@withContext fail(
                        if (token.isBlank()) app.getString(R.string.err_gated_no_token) else app.getString(R.string.err_denied, code),
                    )
                code == 416 && expected > 0 && offset == expected -> Unit // already complete
                code == HttpURLConnection.HTTP_OK -> offset = 0L // server ignored Range: start over
                code == HttpURLConnection.HTTP_PARTIAL -> Unit
                else -> return@withContext fail(app.getString(R.string.err_http, code))
            }

            if (code != 416) {
                val total = if (code == HttpURLConnection.HTTP_PARTIAL) {
                    connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: expected
                } else {
                    connection.contentLengthLong.takeIf { it > 0 } ?: expected
                }
                connection.inputStream.use { input ->
                    FileOutputStream(part, offset > 0).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var bytes = offset
                        var lastReport = 0L
                        var windowStart = SystemClock.elapsedRealtime()
                        var windowBytes = 0L
                        var speed = 0L
                        while (true) {
                            ensureActive() // cancelled: keep the .part file so the next attempt resumes
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            bytes += n
                            windowBytes += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - windowStart >= 1_000) {
                                speed = windowBytes * 1000 / (now - windowStart)
                                windowStart = now
                                windowBytes = 0
                            }
                            if (now - lastReport >= 500) {
                                lastReport = now
                                setProgress(workDataOf(KEY_BYTES to bytes, KEY_TOTAL to total, KEY_SPEED to speed))
                                notify(bytes, speed, total)
                            }
                        }
                    }
                }
            }

            if (expected > 0 && part.length() != expected) {
                return@withContext fail(app.getString(R.string.err_size_mismatch, part.length(), expected))
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) return@withContext fail(app.getString(R.string.err_save))
            store.refresh()
            app.container.log.i("models", "downloaded $fileName (${formatBytes(target.length())})")
            Result.success()
        } catch (e: IOException) {
            app.container.log.w("models", "download of $fileName interrupted", e)
            if (runAttemptCount < 3) Result.retry() else fail(app.getString(R.string.err_network, e.message))
        }
    }

    /**
     * Follows redirects by hand so the Hugging Face token is only ever sent to huggingface.co,
     * not to the CDN host the file is actually served from.
     */
    private fun open(start: String, token: String, offset: Long): HttpURLConnection {
        var url = URL(start)
        repeat(8) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                if (token.isNotBlank() && url.protocol == "https" && url.host == "huggingface.co") {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location") ?: return connection
            connection.disconnect()
            url = URL(url, location)
            if (url.protocol != "https") throw IOException("refusing non-HTTPS redirect to ${url.host}")
        }
        throw IOException("too many redirects")
    }

    private fun fail(message: String): Result {
        (applicationContext as EdgeApplication).container.log.e("models", "download of $fileName failed: $message")
        return Result.failure(Data.Builder().putString(KEY_ERROR, message).build())
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, -1)

    private fun notify(bytes: Long, speed: Long, total: Long) {
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification(bytes, speed, total))
    }

    private fun foregroundInfo(bytes: Long, speed: Long, total: Long) =
        ForegroundInfo(notificationId, notification(bytes, speed, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(bytes: Long, speed: Long, total: Long): Notification {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW))
        val percent = if (total > 0) (bytes * 100 / total).toInt() else 0
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notif_downloading, title))
            .setContentText(
                if (total > 0) "${formatBytes(bytes)} / ${formatBytes(total)} · ${formatBytes(speed)}/s" else applicationContext.getString(R.string.notif_starting),
            )
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .build()
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
