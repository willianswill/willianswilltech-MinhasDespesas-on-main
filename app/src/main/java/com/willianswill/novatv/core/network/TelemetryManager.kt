package com.willianswill.novatv.core.network

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.PackageInfoCompat
import com.willianswill.novatv.core.database.NovaTvDatabase
import com.willianswill.novatv.core.player.UserProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Locale
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
    val serverName: String,
    val serverDns: String,
    val serverUser: String,
    val serverPass: String,
    val location: String,
    val lastSeenAt: Long
)

data class RemoteCommand(
    val action: String, // "NONE", "KICK", "BLOCK"
    val issuedAt: Long,
    val reason: String
)

data class RemoteMacPlaylist(
    val name: String,
    val m3uUrl: String,
    val updatedAt: Long
)

object TelemetryManager {

    private const val PREFS_NAME = "novatv_telemetry_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_MAC = "device_mac"
    private const val KEY_FIREBASE_URL = "firebase_rtdb_url"
    private const val KEY_LAST_KICK_TS = "last_handled_kick_ts"
    private const val KEY_LAST_MAC_PLAYLIST_TS = "last_applied_mac_playlist_ts"
    private const val ACTIVE_WINDOW_MS = 90_000L
    private const val LOCATION_CACHE_MS = 30L * 60L * 1000L

    @Volatile
    private var cachedLocation: String = ""
    @Volatile
    private var lastLocationFetchMs: Long = 0L

    // URL ofuscada do Firebase Realtime Database para envio silencioso de status dos aparelhos
    private val DEFAULT_FIREBASE_URL: String
        get() = com.willianswill.novatv.core.security.SecretVault.firebaseRtdbUrl()

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

    fun getDeviceMac(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_DEVICE_MAC, null)
        if (!cached.isNullOrBlank()) return cached

        val hwMac = readHardwareMacAddress()
        val resolved = if (!hwMac.isNullOrBlank()) {
            hwMac
        } else {
            val androidId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty().ifBlank { getDeviceId(context) }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(androidId.toByteArray(Charsets.UTF_8))
            (0 until 6).joinToString(":") { i ->
                String.format(Locale.US, "%02X", digest[i].toInt() and 0xFF)
            }
        }
        prefs.edit().putString(KEY_DEVICE_MAC, resolved).apply()
        return resolved
    }

    fun getDeviceMacKey(context: Context): String =
        getDeviceMac(context).replace(":", "").replace("-", "").trim().uppercase(Locale.US)

    private fun readHardwareMacAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val preferred = interfaces.sortedBy { iface ->
                when (iface.name.lowercase(Locale.US)) {
                    "eth0" -> 0
                    "wlan0" -> 1
                    else -> 2
                }
            }
            for (iface in preferred) {
                val bytes = iface.hardwareAddress ?: continue
                if (bytes.size != 6) continue
                val formatted = bytes.joinToString(":") { b ->
                    String.format(Locale.US, "%02X", b.toInt() and 0xFF)
                }
                if (formatted != "02:00:00:00:00:00" && formatted != "00:00:00:00:00:00") {
                    return@runCatching formatted
                }
            }
            null
        }.getOrNull()
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

    private suspend fun extractActiveServerDetails(context: Context): Array<String> {
        return runCatching {
            val active = NovaTvDatabase.getInstance(context).profileDao().getActiveProfile()
                ?: return arrayOf("", "", "", "")
            val parsedUrl = active.serverUrl.trim().toHttpUrlOrNull()
            val dns = if (parsedUrl != null) {
                val portPart = if ((parsedUrl.scheme == "http" && parsedUrl.port != 80) ||
                    (parsedUrl.scheme == "https" && parsedUrl.port != 443)
                ) ":${parsedUrl.port}" else ""
                "${parsedUrl.scheme}://${parsedUrl.host}$portPart"
            } else {
                active.serverUrl
            }
            val user = active.username.ifBlank { parsedUrl?.queryParameter("username").orEmpty() }
            val pass = active.password.ifBlank { parsedUrl?.queryParameter("password").orEmpty() }
            arrayOf(active.name, dns, user, pass)
        }.getOrDefault(arrayOf("", "", "", ""))
    }

    private fun resolveCityState(): String {
        val now = System.currentTimeMillis()
        if (cachedLocation.isNotBlank() && now - lastLocationFetchMs < LOCATION_CACHE_MS) {
            return cachedLocation
        }
        val fetched = runCatching {
            val req = Request.Builder()
                .url("https://ipwho.is/")
                .header("User-Agent", "NovaTV-Location")
                .get()
                .build()
            HttpClientProvider.sharedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val json = JSONObject(resp.body?.string().orEmpty())
                if (!json.optBoolean("success", true)) return@use ""
                val city = json.optString("city", "").trim()
                val regionCode = json.optString("region_code", "").trim()
                    .ifBlank { json.optString("region", "").trim() }
                if (city.isNotBlank() && regionCode.isNotBlank()) "$city - $regionCode"
                else city.ifBlank { regionCode }
            }
        }.getOrDefault("")

        if (fetched.isNotBlank()) {
            cachedLocation = fetched
            lastLocationFetchMs = now
        }
        return cachedLocation
    }

    fun sendHeartbeat(
        context: Context,
        status: String,
        watchingTitle: String = "",
        watchingType: String = "",
        onCommandReceived: ((RemoteCommand) -> Unit)? = null
    ) {
        val baseUrl = getFirebaseUrl(context)
        if (baseUrl.isBlank() || !baseUrl.startsWith("http", ignoreCase = true)) return

        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val deviceId = getDeviceId(appCtx)
                val user = UserProfileManager.getActiveUser(appCtx)
                val (srvName, srvDns, srvUser, srvPass) = extractActiveServerDetails(appCtx)
                val locationStr = resolveCityState()

                val payload = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceMac", getDeviceMac(appCtx))
                    put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    put("deviceType", getDeviceType(appCtx))
                    put("profileName", user.name)
                    put("appVersion", getAppVersion(appCtx))
                    put("status", status)
                    put("watchingTitle", watchingTitle)
                    put("watchingType", watchingType)
                    put("serverName", srvName)
                    put("serverDns", srvDns)
                    put("serverUser", srvUser)
                    put("serverPass", srvPass)
                    if (locationStr.isNotBlank()) put("location", locationStr)
                    put("lastSeenAt", System.currentTimeMillis())
                }

                val authQuery = resolveAnonAuthQuery(appCtx)
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("$baseUrl/sessions/$deviceId.json$authQuery")
                    .patch(body)
                    .build()

                HttpClientProvider.sharedClient.newCall(req).execute().close()

                val cmd = fetchRemoteCommandInternal(appCtx, baseUrl, deviceId)
                if (cmd != null && cmd.action != "NONE" && onCommandReceived != null) {
                    withContext(Dispatchers.Main) {
                        onCommandReceived(cmd)
                    }
                }
            }
        }
    }

    suspend fun checkCommandNow(context: Context): RemoteCommand? = withContext(Dispatchers.IO) {
        val baseUrl = getFirebaseUrl(context)
        if (baseUrl.isBlank()) return@withContext null
        val deviceId = getDeviceId(context)
        runCatching { fetchRemoteCommandInternal(context, baseUrl, deviceId) }.getOrNull()
    }

    @Volatile
    private var cachedAnonToken: String = ""
    @Volatile
    private var cachedAnonExpiryMs: Long = 0L

    private fun resolveAnonAuthQuery(context: Context): String {
        val apiKey = com.willianswill.novatv.core.security.SecretVault.firebaseApiKey().trim()
        if (apiKey.isBlank()) return ""
        val now = System.currentTimeMillis()
        if (cachedAnonToken.isNotBlank() && now < cachedAnonExpiryMs) {
            return "?auth=$cachedAnonToken"
        }
        val token = runCatching {
            val req = Request.Builder()
                .url("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey")
                .post("{\"returnSecureToken\":true}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            HttpClientProvider.sharedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val json = JSONObject(resp.body?.string().orEmpty())
                val idToken = json.optString("idToken", "")
                val expiresSec = json.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
                if (idToken.isNotBlank()) {
                    cachedAnonToken = idToken
                    cachedAnonExpiryMs = now + (expiresSec * 1000L) - 60_000L
                }
                idToken
            }
        }.getOrDefault("")
        return if (token.isNotBlank()) "?auth=$token" else ""
    }

    private fun fetchRemoteCommandInternal(context: Context, baseUrl: String, deviceId: String): RemoteCommand? {
        val authQuery = resolveAnonAuthQuery(context)
        val req = Request.Builder()
            .url("$baseUrl/commands/$deviceId.json$authQuery")
            .get()
            .build()

        HttpClientProvider.sharedClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank() || raw == "null") return null
            val json = JSONObject(raw)
            val action = json.optString("action", "NONE").uppercase()
            val issuedAt = json.optLong("issuedAt", 0L)
            val reason = json.optString("reason", "")

            if (action == "KICK") {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastHandled = prefs.getLong(KEY_LAST_KICK_TS, 0L)
                if (issuedAt <= lastHandled) {
                    return RemoteCommand("NONE", issuedAt, reason)
                }
            }
            return RemoteCommand(action, issuedAt, reason)
        }
    }

    fun markKickHandled(context: Context, issuedAt: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_KICK_TS, issuedAt)
            .apply()
    }

    suspend fun fetchRemoteMacPlaylist(context: Context): RemoteMacPlaylist? = withContext(Dispatchers.IO) {
        val baseUrl = getFirebaseUrl(context)
        if (baseUrl.isBlank()) return@withContext null
        val macKey = getDeviceMacKey(context)
        val authQuery = resolveAnonAuthQuery(context)
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/mac_playlists/$macKey.json$authQuery")
                .get()
                .build()
            HttpClientProvider.sharedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank() || raw == "null") return@use null
                val json = JSONObject(raw)
                val url = json.optString("m3uUrl", "").trim()
                if (url.isBlank()) return@use null
                val name = json.optString("name", "").trim().ifBlank { "Lista via MAC" }
                val updatedAt = json.optLong("updatedAt", 0L)
                RemoteMacPlaylist(name = name, m3uUrl = url, updatedAt = updatedAt)
            }
        }.getOrNull()
    }

    fun getLastAppliedMacPlaylistTs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_MAC_PLAYLIST_TS, 0L)

    fun markMacPlaylistApplied(context: Context, updatedAt: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_MAC_PLAYLIST_TS, updatedAt)
            .apply()
    }

    interface PlaybackStopper {
        fun stopPlaybackForRemoteCommand()
    }

    fun handleCommandOnActivity(activity: Activity, cmd: RemoteCommand, onRetryUnblock: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        (activity as? PlaybackStopper)?.stopPlaybackForRemoteCommand()
        when (cmd.action) {
            "KICK" -> {
                markKickHandled(activity, cmd.issuedAt)
                Toast.makeText(
                    activity,
                    cmd.reason.ifBlank { "Sessão encerrada pelo administrador." },
                    Toast.LENGTH_LONG
                ).show()
                activity.finishAffinity()
            }
            "BLOCK" -> {
                AlertDialog.Builder(activity)
                    .setTitle("🔒 Acesso Bloqueado")
                    .setMessage(
                        cmd.reason.ifBlank {
                            "Este dispositivo está temporariamente bloqueado pelo administrador."
                        }
                    )
                    .setCancelable(false)
                    .setPositiveButton("↻ Verificar Liberação") { _, _ ->
                        onRetryUnblock()
                    }
                    .setNegativeButton("Sair") { _, _ ->
                        activity.finishAffinity()
                    }
                    .show()
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
                            serverName = obj.optString("serverName", ""),
                            serverDns = obj.optString("serverDns", ""),
                            serverUser = obj.optString("serverUser", ""),
                            serverPass = obj.optString("serverPass", ""),
                            location = obj.optString("location", ""),
                            lastSeenAt = lastSeen
                        )
                    )
                }
            }
            list.sortedByDescending { it.lastSeenAt }
        }
    }
}
