package com.willianswill.novatv.core.downloads

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.willianswill.novatv.core.player.PlayableItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

object DownloadStatus {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

@Serializable
data class OfflineDownloadItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val iconUrl: String = "",
    val mediaType: String,
    val streamUrl: String,
    val userAgent: String = "",
    val referrer: String = "",
    val localPath: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: String = DownloadStatus.QUEUED,
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

object OfflineDownloadManager {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val lock = Any()
    private var loaded = false

    private val _downloads = MutableStateFlow<List<OfflineDownloadItem>>(emptyList())
    val downloadsFlow: StateFlow<List<OfflineDownloadItem>> = _downloads.asStateFlow()

    private fun getRootDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, "offline_downloads").apply { if (!exists()) mkdirs() }
    }

    private fun getIndexFile(context: Context): File = File(getRootDir(context), "index.json")

    fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (loaded) return
            val file = getIndexFile(context)
            val list = if (file.exists()) {
                runCatching {
                    json.decodeFromString<List<OfflineDownloadItem>>(file.readText())
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _downloads.value = list.sortedByDescending { it.createdAt }
            loaded = true
        }
    }

    private fun saveListLocked(context: Context, list: List<OfflineDownloadItem>) {
        val sorted = list.sortedByDescending { it.createdAt }
        _downloads.value = sorted
        runCatching {
            getIndexFile(context).writeText(json.encodeToString(sorted))
        }
    }

    fun idForUrl(url: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    fun findByUrl(context: Context, streamUrl: String): OfflineDownloadItem? {
        ensureLoaded(context)
        val id = idForUrl(streamUrl)
        return _downloads.value.firstOrNull { it.id == id }
    }

    fun findCompletedLocalUri(context: Context, streamUrl: String): String? {
        val item = findByUrl(context, streamUrl) ?: return null
        if (item.status != DownloadStatus.COMPLETED) return null
        val file = File(item.localPath)
        return if (file.exists() && file.length() > 0L) Uri.fromFile(file).toString() else null
    }

    fun enqueueDownload(context: Context, playable: PlayableItem): OfflineDownloadItem {
        val appCtx = context.applicationContext
        ensureLoaded(appCtx)
        val id = idForUrl(playable.streamUrl)
        val existing = _downloads.value.firstOrNull { it.id == id }
        if (existing != null &&
            (existing.status == DownloadStatus.QUEUED || existing.status == DownloadStatus.DOWNLOADING)
        ) {
            return existing
        }
        if (existing != null && existing.status == DownloadStatus.COMPLETED && File(existing.localPath).exists()) {
            return existing
        }

        val cleanExt = playable.streamUrl.substringBefore('?').substringAfterLast('.', "mp4")
            .lowercase().takeIf { it in setOf("mp4", "mkv", "avi", "mov", "ts") } ?: "mp4"
        val destFile = File(getRootDir(appCtx), "$id.$cleanExt")

        val item = OfflineDownloadItem(
            id = id,
            title = playable.title,
            subtitle = playable.subtitle,
            iconUrl = playable.iconUrl,
            mediaType = playable.mediaType,
            streamUrl = playable.streamUrl,
            userAgent = playable.userAgent.orEmpty(),
            referrer = playable.referrer.orEmpty(),
            localPath = destFile.absolutePath,
            status = DownloadStatus.QUEUED,
            createdAt = System.currentTimeMillis()
        )

        synchronized(lock) {
            val updated = _downloads.value.filterNot { it.id == id } + item
            saveListLocked(appCtx, updated)
        }

        val input = Data.Builder()
            .putString(VodDownloadWorker.KEY_DOWNLOAD_ID, id)
            .build()

        val req = OneTimeWorkRequestBuilder<VodDownloadWorker>()
            .setInputData(input)
            .build()

        WorkManager.getInstance(appCtx)
            .enqueueUniqueWork("dl_$id", ExistingWorkPolicy.REPLACE, req)

        return item
    }

    fun updateItem(context: Context, id: String, transform: (OfflineDownloadItem) -> OfflineDownloadItem) {
        val appCtx = context.applicationContext
        ensureLoaded(appCtx)
        synchronized(lock) {
            val updated = _downloads.value.map { if (it.id == id) transform(it) else it }
            saveListLocked(appCtx, updated)
        }
    }

    fun deleteDownload(context: Context, id: String) {
        val appCtx = context.applicationContext
        ensureLoaded(appCtx)
        WorkManager.getInstance(appCtx).cancelUniqueWork("dl_$id")
        synchronized(lock) {
            val target = _downloads.value.firstOrNull { it.id == id }
            if (target != null) {
                runCatching { File(target.localPath).delete() }
            }
            saveListLocked(appCtx, _downloads.value.filterNot { it.id == id })
        }
    }
}
