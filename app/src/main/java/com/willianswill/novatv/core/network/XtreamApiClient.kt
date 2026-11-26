package com.willianswill.novatv.core.network

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.InputStream

class XtreamApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun normalizeBaseUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun buildPlayerApiUrl(
        serverUrl: String,
        username: String,
        password: String,
        action: String? = null,
        extraParams: Map<String, String> = emptyMap()
    ): String {
        val base = normalizeBaseUrl(serverUrl)
        val urlBuilder = "$base/player_api.php".toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("URL do servidor inválida: $serverUrl")
        urlBuilder.addQueryParameter("username", username)
        urlBuilder.addQueryParameter("password", password)
        if (!action.isNullOrBlank()) {
            urlBuilder.addQueryParameter("action", action)
        }
        extraParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        return urlBuilder.build().toString()
    }

    private fun executeGet(url: String, userAgent: String? = null): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: HttpClientProvider.DEFAULT_USER_AGENT)
            .header("Accept", "application/json")
            .build()

        HttpClientProvider.sharedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Erro HTTP ${response.code} no servidor Xtream")
            }
            return response.body?.string() ?: throw IllegalStateException("Resposta vazia do servidor")
        }
    }

    fun openStream(url: String, userAgent: String? = null): InputStream {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: HttpClientProvider.DEFAULT_USER_AGENT)
            .build()
        val response = HttpClientProvider.sharedClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("Erro HTTP ${response.code} ao baixar $url")
        }
        return response.body?.byteStream() ?: run {
            response.close()
            throw IllegalStateException("Stream vazio")
        }
    }

    fun authenticate(serverUrl: String, user: String, pass: String, ua: String? = null): XtreamAuthResponse {
        val body = executeGet(buildPlayerApiUrl(serverUrl, user, pass), ua)
        return json.decodeFromString(body)
    }

    fun getLiveCategories(serverUrl: String, user: String, pass: String, ua: String? = null): List<XtreamCategoryDto> {
        val body = executeGet(buildPlayerApiUrl(serverUrl, user, pass, "get_live_categories"), ua)
        return json.decodeFromString(body)
    }

    fun getLiveStreams(serverUrl: String, user: String, pass: String, ua: String? = null): List<XtreamLiveDto> {
        val body = executeGet(buildPlayerApiUrl(serverUrl, user, pass, "get_live_streams"), ua)
        return json.decodeFromString(body)
    }

    fun getVodCategories(serverUrl: String, user: String, pass: String, ua: String? = null): List<XtreamCategoryDto> {
        val body = executeGet(buildPlayerApiUrl(serverUrl, user, pass, "get_vod_categories"), ua)
        return json.decodeFromString(body)
    }

    fun getVodStreams(serverUrl: String, user: String, pass: String, ua: String? = null): List<XtreamVodDto> {
        val body = executeGet(buildPlayerApiUrl(serverUrl, user, pass, "get_vod_streams"), ua)
        return json.decodeFromString(body)
    }

    fun getSeriesCategories(serverUrl: String, user: String, pass: String, ua: String? = null): List<XtreamCategoryDto> {
        val body = executeGet(buildPlayerApiUrl(serverUrl, user, pass, "get_series_categories"), ua)
        return json.decodeFromString(body)
    }

    fun getSeries(serverUrl: String, user: String, pass: String, ua: String? = null): List<XtreamSeriesDto> {
        val body = executeGet(buildPlayerApiUrl(serverUrl, user, pass, "get_series"), ua)
        return json.decodeFromString(body)
    }

    fun getSeriesInfo(serverUrl: String, user: String, pass: String, seriesId: String, ua: String? = null): XtreamSeriesInfoResponse {
        val body = executeGet(
            buildPlayerApiUrl(serverUrl, user, pass, "get_series_info", mapOf("series_id" to seriesId)),
            ua
        )
        return json.decodeFromString(body)
    }

    fun buildLiveUrl(serverUrl: String, user: String, pass: String, streamId: Long, ext: String = "ts"): String {
        val base = normalizeBaseUrl(serverUrl)
        return "$base/live/$user/$pass/$streamId.$ext"
    }

    fun buildMovieUrl(serverUrl: String, user: String, pass: String, streamId: Long, ext: String): String {
        val base = normalizeBaseUrl(serverUrl)
        val cleanExt = ext.ifBlank { "mp4" }
        return "$base/movie/$user/$pass/$streamId.$cleanExt"
    }

    fun buildSeriesEpisodeUrl(serverUrl: String, user: String, pass: String, episodeId: String, ext: String): String {
        val base = normalizeBaseUrl(serverUrl)
        val cleanExt = ext.ifBlank { "mp4" }
        return "$base/series/$user/$pass/$episodeId.$cleanExt"
    }

    fun buildCatchupUrl(
        serverUrl: String,
        user: String,
        pass: String,
        streamId: String,
        startFormatted: String,
        durationMinutes: Int
    ): String {
        val base = normalizeBaseUrl(serverUrl)
        return "$base/timeshift/$user/$pass/$durationMinutes/$startFormatted/$streamId.ts"
    }

    fun buildXmltvEpgUrl(serverUrl: String, user: String, pass: String): String {
        val base = normalizeBaseUrl(serverUrl)
        return "$base/xmltv.php?username=$user&password=$pass"
    }
}
