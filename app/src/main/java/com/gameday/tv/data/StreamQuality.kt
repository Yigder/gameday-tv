package com.gameday.tv.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A stream's picture: [height] in lines (1080, 720…) and frames per second. 0 = unknown.
 * [measured] is true when it came from actually playing the stream rather than the channel's name.
 */
data class StreamQuality(val height: Int = 0, val fps: Float = 0f, val measured: Boolean = false) {
    val known: Boolean get() = height > 0 || fps > 0f

    /** "1080p · 60 fps", "4K", "720p" — or "" when nothing is known. */
    val label: String
        get() = listOfNotNull(resolutionLabel(height), fps.takeIf { it > 0f }?.let { "${fpsText(it)} fps" }).joinToString(" · ")

    companion object {
        val UNKNOWN = StreamQuality()

        private val SPLIT = Regex("[^A-Z0-9]+")
        /** "1080P60", "720P50", "2160P60". */
        private val RES_FPS = Regex("^(2160|1440|1080|720|576|540|480|360)[PI](\\d{2,3})$")
        /** "60FPS", "FPS60", "50HZ". */
        private val FPS_TAG = Regex("^(?:(\\d{2,3})(?:FPS|HZ)|FPS(\\d{2,3}))$")

        /** Resolution and frame rate guessed from tags in a channel name ("US: ESPN FHD 60FPS", "NFL 01 1080p60"). */
        fun fromName(name: String): StreamQuality {
            val words = name.uppercase(Locale.US).split(SPLIT).filter { it.isNotEmpty() }
            var height = 0
            var fps = 0f
            words.forEachIndexed { i, w ->
                RES_FPS.matchEntire(w)?.let { m ->
                    height = maxOf(height, m.groupValues[1].toInt())
                    fps = maxOf(fps, m.groupValues[2].toFloat())
                    return@forEachIndexed
                }
                FPS_TAG.matchEntire(w)?.let { m ->
                    fps = maxOf(fps, (m.groupValues[1].ifEmpty { m.groupValues[2] }).toFloat())
                    return@forEachIndexed
                }
                // "60 FPS" written as two words.
                if (w == "FPS" && i > 0) words[i - 1].toIntOrNull()?.takeIf { it in 20..240 }?.let { fps = maxOf(fps, it.toFloat()) }
                val h = when (w) {
                    "8K", "4320P", "4320" -> 4320
                    "4K", "UHD", "2160P", "2160", "UHD4K" -> 2160
                    "1440P", "QHD", "2K" -> 1440
                    "FHD", "1080P", "1080I", "1080", "FULLHD" -> 1080
                    "HD", "720P", "720" -> 720
                    "SD", "576P", "576I", "480P", "480I", "480" -> 480
                    else -> 0
                }
                // "HD" alongside "FHD" etc. must not lower the guess.
                if (h > height) height = h
            }
            return StreamQuality(height, fps)
        }

        fun resolutionLabel(height: Int): String? = when {
            height <= 0 -> null
            height >= 4320 -> "8K"
            height >= 2160 -> "4K"
            else -> "${height}p"
        }

        /** 60 → "60", 59.94 → "59.94", 29.97 → "29.97". */
        fun fpsText(fps: Float): String {
            val r = fps.roundToInt()
            return if (abs(fps - r) < 0.05f) r.toString() else String.format(Locale.US, "%.2f", fps).trimEnd('0').trimEnd('.')
        }

        /**
         * Best first: the higher frame rate, then the sharper picture. Unknown values count as an
         * ordinary 30 fps / 720p stream, and a known value beats an unknown one at the same level.
         */
        val BEST_FIRST: Comparator<StreamQuality> = compareByDescending<StreamQuality> { fpsClass(it.fps.takeIf { f -> f > 0f } ?: 30f) }
            .thenByDescending { it.height.takeIf { h -> h > 0 } ?: 720 }
            .thenByDescending { it.fps > 0f }
            .thenByDescending { it.height > 0 }

        /** 59.94 and 60 are the same thing; so are 25, 29.97 and 30. */
        private fun fpsClass(fps: Float): Int = when {
            fps >= 100f -> 120
            fps >= 48f -> 60
            fps >= 23f -> 30
            else -> 15
        }
    }
}
