package com.gameday.tv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/**
 * Streams an XMLTV guide and keeps only what the app needs: programs for channels in the lineup,
 * inside a time window. Full provider guides can be hundreds of megabytes, so nothing else is kept.
 */
object Xmltv {
    private val TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** "20261003130000 +0000" (offset optional) → epoch millis. Pure, unit tested. */
    fun parseTime(s: String?): Long? {
        val t = s?.trim()?.takeIf { it.length >= 14 } ?: return null
        return try {
            val local = LocalDateTime.parse(t.substring(0, 14), TIME)
            val rest = t.substring(14).trim()
            val offset = if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '-') -1 else 1
                val h = rest.substring(1, 3).toInt()
                val m = rest.substring(3, 5).toInt()
                ZoneOffset.ofTotalSeconds(sign * (h * 3600 + m * 60))
            } else {
                ZoneOffset.UTC
            }
            local.toInstant(offset).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * @param channelsByEpgId XMLTV channel id → app channel ids that use it.
     */
    fun parse(
        input: InputStream,
        channelsByEpgId: Map<String, List<String>>,
        fromMillis: Long,
        toMillis: Long,
    ): Map<String, List<Program>> {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (gz) GZIPInputStream(buffered, 64 * 1024) else buffered

        val out = HashMap<String, MutableList<Program>>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(stream, null)

        var targets: List<String>? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "programme" -> {
                        targets = channelsByEpgId[p.getAttributeValue(null, "channel")]
                        start = parseTime(p.getAttributeValue(null, "start")) ?: 0
                        stop = parseTime(p.getAttributeValue(null, "stop")) ?: 0
                        if (stop < fromMillis || start > toMillis || stop <= start) targets = null
                        title = null
                        desc = null
                    }
                    "title" -> if (targets != null && title == null) title = p.nextText()
                    "desc" -> if (targets != null && desc == null) desc = p.nextText()
                }
                XmlPullParser.END_TAG -> if (p.name == "programme") {
                    targets?.forEach { id ->
                        out.getOrPut(id) { ArrayList() } += Program(id, title?.trim().orEmpty().ifBlank { "Untitled" }, desc?.trim().orEmpty(), start, stop)
                    }
                    targets = null
                }
            }
            event = p.next()
        }
        return out.mapValues { (_, v) -> v.distinctBy { it.startMillis }.sortedBy { it.startMillis } }
    }
}
