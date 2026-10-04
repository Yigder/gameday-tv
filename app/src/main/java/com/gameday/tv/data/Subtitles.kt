package com.gameday.tv.data

import java.util.Locale
import java.util.MissingResourceException

/** How captions look. Saved per profile; every field is an index into the option lists below. */
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

/** Caption and audio track languages. */
object Subtitles {
    /** Languages offered as the preferred caption language (ISO 639-1). */
    val LANGUAGES = listOf("en", "es", "fr", "de", "pt", "it", "nl", "pl", "ru", "ar", "tr", "hi", "ja", "ko", "zh", "sv")

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
}
