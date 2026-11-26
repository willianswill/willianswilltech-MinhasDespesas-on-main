package com.willianswill.novatv.core.parser

import com.willianswill.novatv.core.database.entity.EpgProgramEntity
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

object StreamingXmltvParser {

    private const val CHUNK_SIZE = 1000
    private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L

    suspend fun parseInChunks(
        profileId: Long,
        input: InputStream,
        onProgramsChunk: suspend (List<EpgProgramEntity>) -> Unit
    ): Int {
        val buffered = BufferedInputStream(input, 32 * 1024)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()

        val actualStream: InputStream = if (b0 == 0x1f && b1 == 0x8b) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }

        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(actualStream, "UTF-8")

        val formatWithTz = SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US)
        val formatNoTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val cutoffMs = System.currentTimeMillis() - ONE_DAY_MS
        val batch = ArrayList<EpgProgramEntity>(CHUNK_SIZE)
        var totalInserted = 0

        var inProgramme = false
        var channelId = ""
        var startMs = 0L
        var endMs = 0L
        var title = ""
        var desc = ""
        var currentTag = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.lowercase()
                    if (currentTag == "programme") {
                        inProgramme = true
                        channelId = parser.getAttributeValue(null, "channel")?.trim()?.lowercase().orEmpty()
                        startMs = parseXmltvDate(parser.getAttributeValue(null, "start"), formatWithTz, formatNoTz)
                        endMs = parseXmltvDate(parser.getAttributeValue(null, "stop"), formatWithTz, formatNoTz)
                        title = ""
                        desc = ""
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inProgramme) {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (currentTag) {
                                "title" -> if (title.isEmpty()) title = text
                                "desc" -> if (desc.isEmpty()) desc = text
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tag == "programme" && inProgramme) {
                        inProgramme = false
                        if (channelId.isNotEmpty() && title.isNotEmpty() && endMs > cutoffMs && startMs < endMs) {
                            batch.add(
                                EpgProgramEntity(
                                    profileId = profileId,
                                    channelId = channelId,
                                    title = title,
                                    description = desc,
                                    startMs = startMs,
                                    endMs = endMs
                                )
                            )
                            totalInserted++
                            if (batch.size >= CHUNK_SIZE) {
                                onProgramsChunk(batch.toList())
                                batch.clear()
                            }
                        }
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }

        if (batch.isNotEmpty()) {
            onProgramsChunk(batch.toList())
            batch.clear()
        }

        return totalInserted
    }

    private fun parseXmltvDate(
        raw: String?,
        withTz: SimpleDateFormat,
        noTz: SimpleDateFormat
    ): Long {
        if (raw.isNullOrBlank()) return 0L
        // Normaliza "20261126113000 -03:00" ou "20261126113000 -0300" -> "20261126113000-0300"
        val compact = raw.trim().replace(" ", "").replace(":", "")
        return try {
            if (compact.length >= 19) {
                withTz.parse(compact.substring(0, 19))?.time ?: 0L
            } else if (compact.length >= 14) {
                noTz.parse(compact.substring(0, 14))?.time ?: 0L
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
