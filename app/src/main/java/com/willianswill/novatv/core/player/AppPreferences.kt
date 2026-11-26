package com.willianswill.novatv.core.player

import android.content.Context
import com.willianswill.novatv.core.network.HttpClientProvider

enum class BufferProfile(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
) {
    LOW(
        label = "Baixo (Zapping Rápido)",
        minBufferMs = 5_000,
        maxBufferMs = 15_000,
        bufferForPlaybackMs = 1_000,
        bufferForPlaybackAfterRebufferMs = 2_000
    ),
    STANDARD(
        label = "Padrão (Equilibrado)",
        minBufferMs = 15_000,
        maxBufferMs = 50_000,
        bufferForPlaybackMs = 2_500,
        bufferForPlaybackAfterRebufferMs = 5_000
    ),
    HIGH(
        label = "Alto (Anti-Travamento)",
        minBufferMs = 30_000,
        maxBufferMs = 90_000,
        bufferForPlaybackMs = 5_000,
        bufferForPlaybackAfterRebufferMs = 10_000
    )
}

/**
 * Preferências isoladas por Perfil de Usuário (cada perfil tem seu buffer, User-Agent, PIN e controle adulto).
 */
class AppPreferences(context: Context) {
    private val userId = UserProfileManager.getActiveUserId(context)
    private val prefs = context.getSharedPreferences("novatv_user_prefs_$userId", Context.MODE_PRIVATE)

    var bufferProfile: BufferProfile
        get() {
            val name = prefs.getString("buffer_profile", BufferProfile.STANDARD.name)
            return BufferProfile.entries.find { it.name == name } ?: BufferProfile.STANDARD
        }
        set(value) = prefs.edit().putString("buffer_profile", value.name).apply()

    var customUserAgent: String
        get() = prefs.getString("custom_user_agent", HttpClientProvider.DEFAULT_USER_AGENT)
            ?: HttpClientProvider.DEFAULT_USER_AGENT
        set(value) = prefs.edit().putString("custom_user_agent", value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(value) = prefs.edit().putBoolean("auto_reconnect", value).apply()

    var parentalPin: String
        get() = prefs.getString("parental_pin", "") ?: ""
        set(value) = prefs.edit().putString("parental_pin", value).apply()

    var showAdultContent: Boolean
        get() = prefs.getBoolean("show_adult_content", false)
        set(value) = prefs.edit().putBoolean("show_adult_content", value).apply()
}
