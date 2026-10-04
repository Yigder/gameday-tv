package com.gameday.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.MissingResourceException
import java.util.zip.GZIPInputStream

/*
 * Subtitles from subtitle add-ons (OpenSubtitles and other Stremio-compatible ones) and from
 * sources that list their own. An add-on serves  subtitles/{type}/{id}[/{extra}].json  with
 * { "subtitles": [ { "id", "url", "lang" } ] }. Files are SubRip, WebVTT or SSA/ASS.
 */

/** A subtitle file offered by an add-on (or by the source itself). */
data class SubtitleTrack(val id: String, val url: String, val lang: String, val addon: String) {
    val language: String get() = Subtitles.languageName(lang)
}

/** One timed line (or lines, separated by \n) of a subtitle file. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** What the player needs to ask subtitle add-ons for an add-on title. */
data class SubtitleRequest(val type: String, val id: String, val filename: String?, val videoSize: Long?, val fromSource: List<SubtitleTrack>)

/** How subtitles look. Saved per profile; every field is an index into the option lists below. */
data class SubtitleStyle(
    val size: Int = 1,
    val color: Int = 0,
    val background: Int = 0,
    val edge: Int = 0,
    val position: Int = 0,
    val font: Int = 0,
    val bold: Boolean = false,
) {
    fun encode(): String = listOf(size, color, background, edge, position, font, if (bold) 1 else 0).joinToString(",")

    companion object {
        val SIZES = listOf("Small" to 19, "Medium" to 23, "Large" to 28, "Extra large" to 34)
        val COLORS = listOf("White" to 0xFFFFFFFF, "Yellow" to 0xFFFFEB3B, "Light gray" to 0xFFCCCCCC, "Cyan" to 0xFF4DD0E1, "Green" to 0xFF8BC34A)
        /** Box behind the text: label to ARGB (0 = none). */
        val BACKGROUNDS = listOf("None" to 0x00000000L, "See-through box" to 0x99000000L, "Black box" to 0xFF000000L)
        val EDGES = listOf("Outline", "Drop shadow", "None")
        /** Distance from the bottom, as a fraction of the screen height. */
        val POSITIONS = listOf("Bottom" to 0.05f, "Raised" to 0.13f, "High" to 0.22f)
        val FONTS = listOf("Standard", "Condensed", "Serif", "Monospace")

        fun decode(s: String?): SubtitleStyle {
            val n = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return SubtitleStyle()
            fun at(i: Int, max: Int, default: Int) = n.getOrNull(i)?.takeIf { it in 0 until max } ?: default
            return SubtitleStyle(
                size = at(0, SIZES.size, 1),
                color = at(1, COLORS.size, 0),
                background = at(2, BACKGROUNDS.size, 0),
                edge = at(3, EDGES.size, 0),
                position = at(4, POSITIONS.size, 0),
                font = at(5, FONTS.size, 0),
                bold = n.getOrNull(6) == 1,
            )
        }
    }
}

object Subtitles {
    /** The official OpenSubtitles add-on for Stremio, offered in Settings when no subtitle add-on is installed. */
    const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"

    /** Languages offered as the preferred subtitle language (ISO 639-1). */
    val LANGUAGES = listOf("en", "es", "fr", "de", "pt", "it", "nl", "pl", "ru", "ar", "tr", "hi", "ja", "ko", "zh", "sv")

    // ---- add-on responses ----

    fun parseList(body: String, addonName: String): List<SubtitleTrack> {
        val arr = runCatching { JSONObject(body).optJSONArray("subtitles") }.getOrNull() ?: return emptyList()
        return parseArray(arr, addonName)
    }

    /** The "subtitles" array of an add-on response or a stream object. */
    fun parseArray(arr: JSONArray, addonName: String): List<SubtitleTrack> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val url = o.optString("url").trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return@mapNotNull null
        SubtitleTrack(
            id = o.optString("id").trim().ifEmpty { url },
            url = url,
            lang = o.optString("lang").trim().ifEmpty { "und" },
            addon = addonName,
        )
    }.distinctBy { it.url }

    // ---- languages ----

    private val SPECIAL = mapOf(
        "pob" to "Portuguese (Brazil)", "pt-br" to "Portuguese (Brazil)", "ptbr" to "Portuguese (Brazil)",
        "spn" to "Spanish (Latin America)", "es-419" to "Spanish (Latin America)", "ea" to "Spanish (Latin America)",
        "zht" to "Chinese (traditional)", "zhs" to "Chinese (simplified)", "ze" to "Chinese (bilingual)",
        "und" to "Unknown language",
    )

    /** "eng" / "en" / "English" → "English". */
    fun languageName(lang: String): String {
        val l = lang.trim()
        SPECIAL[l.lowercase()]?.let { return it }
        if (l.length > 3 && !l.contains('-') && !l.contains('_')) return l.replaceFirstChar { it.uppercase() }
        val name = runCatching { Locale.forLanguageTag(l.replace('_', '-')).getDisplayLanguage(Locale.ENGLISH) }.getOrNull().orEmpty()
        return name.ifBlank { l }.replaceFirstChar { it.uppercase() }
    }

    /** Three-letter code for comparing languages ("en", "eng", "English" → "eng"). */
    fun iso3(lang: String): String {
        val l = lang.trim().lowercase().replace('_', '-')
        if (l == "pob" || l.startsWith("pt-")) return "por"
        if (l == "spn" || l.startsWith("es-") || l == "ea") return "spa"
        if (l == "zht" || l == "zhs" || l == "ze" || l.startsWith("zh-")) return "zho"
        val base = l.substringBefore('-')
        if (base.length == 3) return normalizeBibliographic(base)
        if (base.length == 2) return try { Locale.forLanguageTag(base).isO3Language.ifEmpty { base } } catch (e: MissingResourceException) { base }
        // A language name ("English").
        return Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).equals(lang.trim(), true) }
            ?.let { runCatching { it.isO3Language }.getOrNull() } ?: l
    }

    /** ISO 639-2 has two codes for some languages (fre/fra); Java uses the terminology one. */
    private fun normalizeBibliographic(code: String): String = when (code) {
        "fre" -> "fra"; "ger" -> "deu"; "chi" -> "zho"; "dut" -> "nld"; "cze" -> "ces"; "gre" -> "ell"
        "per" -> "fas"; "rum" -> "ron"; "slo" -> "slk"; "alb" -> "sqi"; "arm" -> "hye"; "baq" -> "eus"
        "bur" -> "mya"; "geo" -> "kat"; "ice" -> "isl"; "mac" -> "mkd"; "may" -> "msa"; "wel" -> "cym"
        else -> code
    }

    fun sameLanguage(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return iso3(a) == iso3(b)
    }

    /** Preferred language first, then by language name; each language keeps the add-on's order. */
    fun sortTracks(tracks: List<SubtitleTrack>, preferred: String): List<SubtitleTrack> =
        tracks.withIndex().sortedWith(
            compareBy<IndexedValue<SubtitleTrack>> { if (sameLanguage(it.value.lang, preferred)) 0 else 1 }
                .thenBy { it.value.language }
                .thenBy { it.index },
        ).map { it.value }

    // ---- subtitle files ----

    /** Text from a downloaded file: gunzipped if needed, UTF-8 or (for old files) Windows-1252. */
    fun decodeBytes(raw: ByteArray): String {
        var bytes = raw
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            bytes = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('�')) String(bytes, charset("windows-1252")) else utf8
    }

    /** Parses SubRip, WebVTT or SSA/ASS into cues sorted by start time. */
    fun parse(content: String): List<SubtitleCue> {
        val text = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val head = text.trimStart().take(200)
        val cues = when {
            head.startsWith("[Script Info]", true) || text.contains("\nDialogue:") -> parseAss(text)
            else -> parseTimed(text)
        }
        return cues.filter { it.endMs > it.startMs && it.text.isNotBlank() }.sortedBy { it.startMs }
    }

    private val TIMING = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")

    /** SubRip and WebVTT: blocks with a "start --> end" line followed by text. */
    private fun parseTimed(text: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val t = lines.indexOfFirst { TIMING.containsMatchIn(it) }
            if (t < 0) continue
            val m = TIMING.find(lines[t]) ?: continue
            val start = time(m.groupValues[1]) ?: continue
            val end = time(m.groupValues[2]) ?: continue
            val body = lines.drop(t + 1).joinToString("\n") { cleanLine(it) }.trim()
            if (body.isNotEmpty()) out += SubtitleCue(start, end, body)
        }
        return out
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val out = ArrayList<SubtitleCue>()
        for (line in text.lines()) {
            when {
                line.startsWith("Format:", true) && out.isEmpty() ->
                    format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                line.startsWith("Dialogue:", true) -> {
                    val parts = line.substringAfter(':').split(',', limit = format.size)
                    if (parts.size < format.size) continue
                    val start = time(parts[format.indexOf("start").coerceAtLeast(0)].trim()) ?: continue
                    val end = time(parts[format.indexOf("end").coerceAtLeast(0)].trim()) ?: continue
                    val body = parts[format.indexOf("text").takeIf { it >= 0 } ?: (format.size - 1)]
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                        .lines().joinToString("\n") { cleanLine(it) }.trim()
                    if (body.isNotEmpty()) out += SubtitleCue(start, end, body)
                }
            }
        }
        return out
    }

    /** "01:02:03,456", "02:03.456", "0:01:02.34" → milliseconds. */
    fun time(s: String): Long? {
        val parts = s.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val h = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val m = parts[parts.size - 2].toLongOrNull() ?: return null
        val sec = parts.last()
        val whole = sec.substringBefore('.').toLongOrNull() ?: return null
        val frac = sec.substringAfter('.', "0").let { f -> (f + "00").take(3).toLongOrNull() ?: 0L }
        return ((h * 60 + m) * 60 + whole) * 1000 + frac
    }

    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    /** Drops formatting tags (<i>, <font…>, {\an8}) and decodes the common HTML entities. */
    private fun cleanLine(line: String): String =
        line.replace(TAGS, "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&quot;", "\"").trim()

    /** Cues showing at [positionMs] (binary search; cues are sorted by start). */
    fun activeAt(cues: List<SubtitleCue>, positionMs: Long): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) lo = mid + 1 else hi = mid
        }
        // Overlapping cues are rare and short; look back a little.
        val out = ArrayList<SubtitleCue>(2)
        var i = lo - 1
        while (i >= 0 && i >= lo - 8) {
            val c = cues[i]
            if (c.endMs > positionMs) out += c
            i--
        }
        return out.asReversed()
    }
}
