package com.willianswill.novatv.core.parser

import com.willianswill.novatv.core.database.entity.CategoryEntity
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.StreamEntity
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class M3uParseStats(
    val totalChannels: Int,
    val totalMovies: Int,
    val epgUrlHeader: String?
)

object StreamingM3uParser {

    private const val CHUNK_SIZE = 500
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val adultKeywords = listOf("adult", "xxx", "+18", "18+", "porn", "erotic", "sex")
    private val vodExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv")

    suspend fun parseInChunks(
        profileId: Long,
        input: InputStream,
        favoriteUrls: Set<String>,
        onStreamsChunk: suspend (List<StreamEntity>) -> Unit,
        onCategoriesCollected: suspend (List<CategoryEntity>) -> Unit
    ): M3uParseStats {
        val batch = ArrayList<StreamEntity>(CHUNK_SIZE)
        val categoriesMap = LinkedHashMap<String, CategoryEntity>()
        var epgHeaderUrl: String? = null
        var liveCount = 0
        var movieCount = 0
        var indexCounter = 0

        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 32 * 1024).use { reader ->
            var pendingName = ""
            var pendingLogo = ""
            var pendingGroup = "Geral"
            var pendingTvgId = ""
            var pendingCatchupDays = 0
            var pendingUa: String? = null
            var pendingRef: String? = null
            var hasExtInf = false

            var rawLine = reader.readLine()
            while (rawLine != null) {
                val line = rawLine.trim()
                if (line.isNotEmpty()) {
                    when {
                        line.startsWith("#EXTM3U", ignoreCase = true) -> {
                            for (match in attrRegex.findAll(line)) {
                                val key = match.groupValues[1].lowercase()
                                if (key == "url-tvg" || key == "x-tvg-url") {
                                    epgHeaderUrl = match.groupValues[2].trim().takeIf { it.isNotBlank() }
                                }
                            }
                        }

                        line.startsWith("#EXTINF:", ignoreCase = true) -> {
                            hasExtInf = true
                            pendingLogo = ""
                            pendingGroup = "Geral"
                            pendingTvgId = ""
                            pendingCatchupDays = 0

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
                                    "catchup-days" -> pendingCatchupDays = value.toIntOrNull() ?: 0
                                }
                            }
                            pendingName = titlePart.ifEmpty { tvgName }.ifEmpty { "Canal ${indexCounter + 1}" }
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
                            val (streamUrl, ua, ref) = parseUrlWithHeaders(line, pendingUa, pendingRef)
                            if (isSupportedScheme(streamUrl)) {
                                indexCounter++
                                val finalName = if (hasExtInf && pendingName.isNotEmpty()) {
                                    pendingName
                                } else {
                                    "Canal $indexCounter"
                                }
                                val mediaType = detectMediaType(streamUrl, pendingGroup)
                                if (mediaType == MediaType.MOVIE) movieCount++ else liveCount++

                                val catKey = "${mediaType}_${pendingGroup.lowercase()}"
                                val isAdult = isAdultText(pendingGroup) || isAdultText(finalName)
                                val catId = catKey.hashCode().toString()

                                if (!categoriesMap.containsKey(catKey)) {
                                    categoriesMap[catKey] = CategoryEntity(
                                        profileId = profileId,
                                        categoryId = catId,
                                        name = pendingGroup,
                                        mediaType = mediaType,
                                        isAdult = isAdult,
                                        sortOrder = categoriesMap.size
                                    )
                                }

                                batch.add(
                                    StreamEntity(
                                        profileId = profileId,
                                        streamId = indexCounter.toString(),
                                        mediaType = mediaType,
                                        name = finalName,
                                        streamUrl = streamUrl,
                                        iconUrl = pendingLogo,
                                        categoryId = catId,
                                        categoryName = pendingGroup,
                                        epgChannelId = pendingTvgId,
                                        hasCatchup = pendingCatchupDays > 0,
                                        catchupDays = pendingCatchupDays,
                                        userAgent = ua,
                                        referrer = ref,
                                        isFavorite = favoriteUrls.contains(streamUrl),
                                        isAdult = isAdult,
                                        addedAt = System.currentTimeMillis()
                                    )
                                )

                                if (batch.size >= CHUNK_SIZE) {
                                    onStreamsChunk(batch.toList())
                                    batch.clear()
                                }
                            }

                            hasExtInf = false
                            pendingName = ""
                            pendingLogo = ""
                            pendingGroup = "Geral"
                            pendingTvgId = ""
                            pendingCatchupDays = 0
                            pendingUa = null
                            pendingRef = null
                        }
                    }
                }
                rawLine = reader.readLine()
            }
        }

        if (batch.isNotEmpty()) {
            onStreamsChunk(batch.toList())
            batch.clear()
        }

        if (categoriesMap.isNotEmpty()) {
            onCategoriesCollected(categoriesMap.values.toList())
        }

        return M3uParseStats(
            totalChannels = liveCount,
            totalMovies = movieCount,
            epgUrlHeader = epgHeaderUrl
        )
    }

    private fun isSupportedScheme(url: String): Boolean {
        return url.startsWith("http://", true) ||
            url.startsWith("https://", true) ||
            url.startsWith("rtsp://", true) ||
            url.startsWith("rtmp://", true)
    }

    private fun detectMediaType(url: String, group: String): String {
        val lowerUrl = url.lowercase().substringBefore('?')
        if (vodExtensions.any { lowerUrl.endsWith(it) }) {
            return MediaType.MOVIE
        }
        val lowerGroup = group.lowercase()
        if (lowerGroup.contains("filme") || lowerGroup.contains("movie") || lowerGroup.contains("vod")) {
            return MediaType.MOVIE
        }
        return MediaType.LIVE
    }

    private fun isAdultText(text: String): Boolean {
        val lower = text.lowercase()
        return adultKeywords.any { lower.contains(it) }
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
        if (pipeIndex == -1) return Triple(rawUrl, defaultUa, defaultRef)

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
