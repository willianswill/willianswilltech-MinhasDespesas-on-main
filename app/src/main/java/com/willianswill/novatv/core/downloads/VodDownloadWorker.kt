package com.willianswill.novatv.core.downloads

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.willianswill.novatv.R
import com.willianswill.novatv.core.network.HttpClientProvider
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class VodDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        OfflineDownloadManager.ensureLoaded(applicationContext)
        val item = OfflineDownloadManager.downloadsFlow.value.firstOrNull { it.id == id }
            ?: return Result.failure()

        OfflineDownloadManager.updateItem(applicationContext, id) {
            it.copy(status = DownloadStatus.DOWNLOADING, errorMessage = "", downloadedBytes = 0L)
        }

        val destFile = File(item.localPath)
        return try {
            val reqBuilder = Request.Builder()
                .url(item.streamUrl)
                .header(
                    "User-Agent",
                    item.userAgent.ifBlank { HttpClientProvider.DEFAULT_USER_AGENT }
                )
            if (item.referrer.isNotBlank()) {
                reqBuilder.header("Referer", item.referrer)
            }

            HttpClientProvider.sharedClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("Resposta vazia")
                val totalBytes = body.contentLength().coerceAtLeast(0L)
                OfflineDownloadManager.updateItem(applicationContext, id) {
                    it.copy(totalBytes = totalBytes)
                }

                destFile.parentFile?.mkdirs()
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastNotifyMs = 0L

                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        while (true) {
                            if (isStopped) {
                                runCatching { destFile.delete() }
                                return Result.failure()
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastNotifyMs >= 500L) {
                                lastNotifyMs = now
                                OfflineDownloadManager.updateItem(applicationContext, id) {
                                    it.copy(downloadedBytes = downloaded, totalBytes = totalBytes)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                OfflineDownloadManager.updateItem(applicationContext, id) {
                    it.copy(
                        downloadedBytes = downloaded,
                        totalBytes = if (totalBytes > 0L) totalBytes else downloaded,
                        status = DownloadStatus.COMPLETED,
                        errorMessage = ""
                    )
                }
                showCompletedNotification(item.title, id.hashCode())
            }
            Result.success()
        } catch (e: Exception) {
            runCatching { destFile.delete() }
            OfflineDownloadManager.updateItem(applicationContext, id) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Erro no download"
                )
            }
            Result.failure()
        }
    }

    private fun showCompletedNotification(title: String, notifId: Int) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channelId = "novatv_downloads"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "Downloads Offline", NotificationManager.IMPORTANCE_DEFAULT)
                nm.createNotificationChannel(ch)
            }
            val notif = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(R.drawable.ic_wtv_launcher)
                .setContentTitle("✓ Download concluído")
                .setContentText("$title está pronto na aba ⬇ Offline")
                .setAutoCancel(true)
                .build()
            nm.notify(notifId, notif)
        }
    }
}
