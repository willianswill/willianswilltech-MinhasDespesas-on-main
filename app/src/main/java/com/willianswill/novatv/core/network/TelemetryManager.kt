package com.willianswill.novatv.core.network

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.willianswill.novatv.core.player.UserProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

data class ActiveSessionInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val profileName: String,
    val appVersion: String,
    val status: String,
    val watchingTitle: String,
    val watchingType: String,
    val lastSeenAt: Long
)

object TelemetryManager {

    private const val PREFS_NAME = "novatv_telemetry_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FIREBASE_URL = "firebase_rtdb_url"
    private const val ACTIVE_WINDOW_MS = 90_000L

    // Se quiser deixar uma URL fixa em todos os aparelhos sem precisar colar manualmente,
    // basta colocar a URL do seu Firebase Realtime Database aqui:
    private const val DEFAULT_FIREBASE_URL = ""

    fun getFirebaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_FIREBASE_URL, "")?.trim().orEmpty()
        return saved.ifBlank { DEFAULT_FIREBASE_URL }.trimEnd('/')
    }

    fun setFirebaseUrl(context: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FIREBASE_URL, clean)
            .apply()
    }

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    private fun getDeviceType(context: Context): String {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        return if (isTv) "TV Box" else "Celular"
    }

    private fun getAppVersion(context: Context): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = PackageInfoCompat.getLongVersionCode(info)
            "${info.versionName} (#$code)"
        }.getOrDefault("1.0")
    }

    fun sendHeartbeat(
        context: Context,
        status: String,
        watchingTitle: String = "",
        watchingType: String = ""
    ) {
        val baseUrl = getFirebaseUrl(context)
        if (baseUrl.isBlank() || !baseUrl.startsWith("http", ignoreCase = true)) return

        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val deviceId = getDeviceId(appCtx)
                val user = UserProfileManager.getActiveUser(appCtx)
                val payload = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    put("deviceType", getDeviceType(appCtx))
                    put("profileName", user.name)
                    put("appVersion", getAppVersion(appCtx))
                    put("status", status)
                    put("watchingTitle", watchingTitle)
                    put("watchingType", watchingType)
                    put("lastSeenAt", System.currentTimeMillis())
                }

                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("$baseUrl/sessions/$deviceId.json")
                    .put(body)
                    .build()

                HttpClientProvider.sharedClient.newCall(req).execute().close()
            }
        }
    }

    suspend fun fetchActiveSessions(context: Context): List<ActiveSessionInfo> = withContext(Dispatchers.IO) {
        val baseUrl = getFirebaseUrl(context)
        if (baseUrl.isBlank()) throw IllegalStateException("Configure a URL do Firebase Realtime Database primeiro.")

        val req = Request.Builder()
            .url("$baseUrl/sessions.json")
            .get()
            .build()

        HttpClientProvider.sharedClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Erro HTTP ${resp.code} ao consultar painel")
            }
            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank() || raw == "null") return@withContext emptyList()

            val json = JSONObject(raw)
            val now = System.currentTimeMillis()
            val list = mutableListOf<ActiveSessionInfo>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = json.optJSONObject(key) ?: continue
                val lastSeen = obj.optLong("lastSeenAt", 0L)
                if (now - lastSeen <= ACTIVE_WINDOW_MS) {
                    list.add(
                        ActiveSessionInfo(
                            deviceId = obj.optString("deviceId", key),
                            deviceName = obj.optString("deviceName", "Dispositivo"),
                            deviceType = obj.optString("deviceType", "Android"),
                            profileName = obj.optString("profileName", "Principal"),
                            appVersion = obj.optString("appVersion", ""),
                            status = obj.optString("status", "Online"),
                            watchingTitle = obj.optString("watchingTitle", ""),
                            watchingType = obj.optString("watchingType", ""),
                            lastSeenAt = lastSeen
                        )
                    )
                }
            }
            list.sortedByDescending { it.lastSeenAt }
        }
    }
}
