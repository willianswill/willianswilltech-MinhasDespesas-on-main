package com.willianswill.novatv.core.parser

data class ParsedChannelQuality(
    val baseName: String,
    val qualityLabel: String,
    val qualityRank: Int // 1=SD, 2=HD/Padrão, 3=FHD, 4=4K/UHD
)

object ChannelQualityParser {

    private val tagRegex = Regex(
        """(?i)(?:[\[\(\|\-]\s*|\b)(4K|UHD|FHD|FULL\s*HD|1080P|H\.?265|HEVC|HD|720P|SD|480P|360P)(?:\s*[\]\)]|\b)"""
    )

    fun parse(rawName: String): ParsedChannelQuality {
        val matches = tagRegex.findAll(rawName).toList()
        var bestRank = 2
        var bestLabel = "Padrão"
        var hasExplicitTag = false

        for (m in matches) {
            val token = m.groupValues[1].uppercase().replace(" ", "").replace(".", "")
            val (rank, label) = when (token) {
                "4K", "UHD" -> 4 to "4K"
                "FHD", "FULLHD", "1080P" -> 3 to "FHD"
                "H265", "HEVC" -> 3 to "FHD H265"
                "HD", "720P" -> 2 to "HD"
                "SD", "480P", "360P" -> 1 to "SD"
                else -> 2 to "Padrão"
            }
            if (!hasExplicitTag || rank > bestRank) {
                bestRank = rank
                bestLabel = label
                hasExplicitTag = true
            }
        }

        val cleaned = rawName
            .replace(tagRegex, " ")
            .replace(Regex("""[\[\]\(\)\|]+"""), " ")
            .replace(Regex("""\s+-\s*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        val base = cleaned.ifBlank { rawName.trim() }
        return ParsedChannelQuality(
            baseName = base,
            qualityLabel = bestLabel,
            qualityRank = bestRank
        )
    }
}
