package com.willianswill.novatv.core.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class AppUpdateInfo(
    val versionName: String,
    val buildNumber: Long,
    val notes: String,
    val apkDownloadUrl: String,
    val releasePageUrl: String
)

object AppUpdateChecker {

    private val LATEST_RELEASE_URL: String
        get() = com.willianswill.novatv.core.security.SecretVault.latestReleaseUrl()

    suspend fun checkForUpdate(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NovaTV-Updater")
                .build()

            HttpClientProvider.sharedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)

                val tag = json.optString("tag_name", "").trim()
                val remoteBuild = parseBuildNumber(tag)
                val currentBuild = getCurrentVersionCode(context)

                if (remoteBuild <= currentBuild) return@withContext null

                var apkUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                AppUpdateInfo(
                    versionName = tag.ifBlank { "v1.0.$remoteBuild" },
                    buildNumber = remoteBuild,
                    notes = json.optString("body", "Nova versão disponível com melhorias."),
                    apkDownloadUrl = apkUrl,
                    releasePageUrl = json.optString("html_url", "")
                )
            }
        }.getOrNull()
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "novatv-update.apk")
        if (apkFile.exists()) apkFile.delete()

        val req = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "NovaTV-Updater")
            .build()

        HttpClientProvider.sharedClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Erro HTTP ${resp.code} ao baixar atualização")
            }
            val body = resp.body ?: throw IllegalStateException("Arquivo APK vazio")
            val totalBytes = body.contentLength()
            var downloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0L) {
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }
        }

        withContext(Dispatchers.Main) {
            installApk(context, apkFile)
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permIntent)
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    private fun getCurrentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    private fun parseBuildNumber(tag: String): Long {
        val lastSegment = tag.substringAfterLast('.', "")
        return lastSegment.filter { it.isDigit() }.toLongOrNull() ?: 0L
    }
}
