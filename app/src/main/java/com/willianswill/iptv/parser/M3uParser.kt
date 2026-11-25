package com.willianswill.iptv.parser

import com.willianswill.iptv.model.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object M3uParser {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun downloadM3u(url: String): String {
        val request = Request.Builder()
            .url(url.trim())
            .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
            .header("Accept", "*/*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Erro HTTP ${response.code} ao baixar a lista M3U")
            }
            val body = response.body ?: throw IllegalStateException("Lista M3U vazia")
            return body.string()
        }
    }

    fun parseStream(input: InputStream, favoriteUrls: Set<String> = emptySet()): List<Channel> {
        val channels = ArrayList<Channel>(256)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 32 * 1024).use { reader ->
            var pendingName = ""
            var pendingLogo = ""
            var pendingGroup = "Geral"
            var pendingTvgId = ""
            var pendingUa: String? = null
            var pendingRef: String? = null
            var hasExtInf = false

            var rawLine = reader.readLine()
            while (rawLine != null) {
                val line = rawLine.trim()
                if (line.isNotEmpty()) {
                    when {
                        line.startsWith("#EXTINF:", ignoreCase = true) -> {
                            hasExtInf = true
                            pendingLogo = ""
                            pendingGroup = "Geral"
                            pendingTvgId = ""

                            val commaIndex = findMetadataSeparator(line)
                            val metaPart = if (commaIndex != -1) line.substring(0, commaIndex) else line
                            val titlePart = if (commaIndex != -1 && commaIndex + 1 < line.length) {
                                line.substring(commaIndex + 1).trim()
                            } else {
                                ""
                            }

                            var tvgName = ""
                            for (match in attrRegex.findAll(metaPart)) {
                                val key = match.groupValues[1].lowercase()
                                val value = match.groupValues[2].trim()
                                when (key) {
                                    "tvg-logo" -> pendingLogo = value
                                    "group-title" -> if (value.isNotEmpty()) pendingGroup = value
                                    "tvg-id" -> pendingTvgId = value
                                    "tvg-name" -> tvgName = value
                                }
                            }
                            pendingName = titlePart.ifEmpty { tvgName }.ifEmpty { "Canal ${channels.size + 1}" }
                        }

                        line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                            val grp = line.substringAfter(":").trim()
                            if (grp.isNotEmpty()) pendingGroup = grp
                        }

                        line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                            val opt = line.substringAfter(":").trim()
                            when {
                                opt.startsWith("http-user-agent=", ignoreCase = true) -> {
                                    pendingUa = opt.substringAfter("=").trim()
                                }
                                opt.startsWith("http-referrer=", ignoreCase = true) ||
                                    opt.startsWith("http-referer=", ignoreCase = true) -> {
                                    pendingRef = opt.substringAfter("=").trim()
                                }
                            }
                        }

                        !line.startsWith("#") -> {
                            val parsed = parseUrlWithHeaders(line, pendingUa, pendingRef)
                            val streamUrl = parsed.first
                            if (streamUrl.startsWith("http://", ignoreCase = true) ||
                                streamUrl.startsWith("https://", ignoreCase = true) ||
                                streamUrl.startsWith("rtsp://", ignoreCase = true) ||
                                streamUrl.startsWith("rtmp://", ignoreCase = true)
                            ) {
                                val finalName = if (hasExtInf && pendingName.isNotEmpty()) {
                                    pendingName
                                } else {
                                    "Canal ${channels.size + 1}"
                                }
                                val id = "${streamUrl.hashCode()}_${finalName.hashCode()}"
                                channels.add(
                                    Channel(
                                        id = id,
                                        name = finalName,
                                        url = streamUrl,
                                        logo = pendingLogo,
                                        group = pendingGroup,
                                        tvgId = pendingTvgId,
                                        userAgent = parsed.second,
                                        referrer = parsed.third,
                                        isFavorite = favoriteUrls.contains(streamUrl)
                                    )
                                )
                            }
                            hasExtInf = false
                            pendingName = ""
                            pendingLogo = ""
                            pendingGroup = "Geral"
                            pendingTvgId = ""
                            pendingUa = null
                            pendingRef = null
                        }
                    }
                }
                rawLine = reader.readLine()
            }
        }
        return channels
    }

    private fun findMetadataSeparator(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    private fun parseUrlWithHeaders(
        rawUrl: String,
        defaultUa: String?,
        defaultRef: String?
    ): Triple<String, String?, String?> {
        val pipeIndex = rawUrl.indexOf('|')
        if (pipeIndex == -1) {
            return Triple(rawUrl, defaultUa, defaultRef)
        }
        val cleanUrl = rawUrl.substring(0, pipeIndex).trim()
        val params = rawUrl.substring(pipeIndex + 1).split('&')
        var ua = defaultUa
        var ref = defaultRef
        for (param in params) {
            val eq = param.indexOf('=')
            if (eq > 0) {
                val key = param.substring(0, eq).trim().lowercase()
                val valStr = param.substring(eq + 1).trim()
                if (key == "user-agent") ua = valStr
                if (key == "referer" || key == "referrer") ref = valStr
            }
        }
        return Triple(cleanUrl, ua, ref)
    }
}
