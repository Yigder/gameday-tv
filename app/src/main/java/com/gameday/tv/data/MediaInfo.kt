package com.gameday.tv.data

import java.util.Locale

/** Readable names for the player's video stats (codecs, channels, rates). */
object MediaInfo {

    /** "H.264", "HEVC", "AV1"… from a format's sample MIME type and codecs string. */
    fun videoCodec(mime: String?, codecs: String?): String? {
        val c = codecs?.substringBefore(',')?.trim()?.lowercase(Locale.US).orEmpty()
        val m = mime?.lowercase(Locale.US).orEmpty()
        return when {
            c.startsWith("avc") || m == "video/avc" -> "H.264"
            c.startsWith("hev") || c.startsWith("hvc") || m == "video/hevc" -> "HEVC"
            c.startsWith("dvh") || c.startsWith("dva") || m == "video/dolby-vision" -> "Dolby Vision"
            c.startsWith("av01") || m == "video/av01" -> "AV1"
            c.startsWith("vp09") || c == "vp9" || m == "video/x-vnd.on2.vp9" -> "VP9"
            c.startsWith("vp8") || m == "video/x-vnd.on2.vp8" -> "VP8"
            m == "video/mpeg2" || c.startsWith("mp2v") -> "MPEG-2"
            m == "video/mp4v-es" -> "MPEG-4"
            m.startsWith("video/") -> m.removePrefix("video/").uppercase(Locale.US)
            else -> null
        }
    }

    /** "AAC", "Dolby Digital Plus", "MP3"… */
    fun audioCodec(mime: String?, codecs: String?): String? {
        val c = codecs?.substringBefore(',')?.trim()?.lowercase(Locale.US).orEmpty()
        val m = mime?.lowercase(Locale.US).orEmpty()
        return when {
            m == "audio/eac3-joc" || c == "ec+3" -> "Dolby Atmos (DD+)"
            m == "audio/eac3" || c == "ec-3" -> "Dolby Digital Plus"
            m == "audio/ac3" || c == "ac-3" -> "Dolby Digital"
            m == "audio/ac4" || c.startsWith("ac-4") -> "Dolby AC-4"
            m == "audio/true-hd" -> "Dolby TrueHD"
            m.startsWith("audio/vnd.dts") -> "DTS"
            m == "audio/mp4a-latm" || c.startsWith("mp4a.40") -> if (c == "mp4a.40.5" || c == "mp4a.40.29") "HE-AAC" else "AAC"
            m == "audio/mpeg" || c == "mp4a.40.34" || c == "mp4a.6b" -> "MP3"
            m == "audio/mpeg-l2" -> "MP2"
            m == "audio/opus" || c == "opus" -> "Opus"
            m == "audio/vorbis" -> "Vorbis"
            m == "audio/flac" -> "FLAC"
            m == "audio/raw" -> "PCM"
            m.startsWith("audio/") -> m.removePrefix("audio/").uppercase(Locale.US)
            else -> null
        }
    }

    /** "Stereo", "5.1", "7.1"… */
    fun channels(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "Mono"
        count == 2 -> "Stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count ch"
    }

    /** "6.2 Mbps", "128 kbps". */
    fun bitrate(bitsPerSecond: Long): String? = when {
        bitsPerSecond <= 0 -> null
        bitsPerSecond >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        else -> "${bitsPerSecond / 1000} kbps"
    }

    /** "59.94 fps", "30 fps". */
    fun frameRate(fps: Float): String? = when {
        fps <= 0f || fps.isNaN() -> null
        kotlin.math.abs(fps - Math.round(fps)) < 0.01f -> "${Math.round(fps)} fps"
        else -> String.format(Locale.US, "%.2f fps", fps)
    }

    /** "1080p", "4K"… for a picture [height] (and [width], so cropped widescreen still counts). */
    fun quality(width: Int, height: Int): String? = when {
        width <= 0 || height <= 0 -> null
        height >= 2000 || width >= 3800 -> "4K"
        height >= 1400 || width >= 2500 -> "1440p"
        height >= 1000 || width >= 1900 -> "1080p"
        height >= 700 || width >= 1260 -> "720p"
        else -> "${height}p"
    }

    /** How a stream is delivered, from its address: "HLS", "MPEG-TS", "MP4"… */
    fun container(url: String?): String? {
        val path = url?.substringBefore('?')?.lowercase(Locale.US) ?: return null
        return when {
            path.endsWith(".m3u8") || path.contains("/hls/") -> "HLS"
            path.endsWith(".ts") -> "MPEG-TS"
            path.endsWith(".mpd") -> "DASH"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "MP4"
            path.endsWith(".mkv") -> "MKV"
            path.endsWith(".avi") -> "AVI"
            path.startsWith("file:") -> "Recording"
            else -> null
        }
    }

    /** Hardware or software, from a MediaCodec decoder's name ("c2.android.*" and "OMX.google.*" are software). */
    fun isSoftwareDecoder(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.startsWith("c2.android.") || n.startsWith("omx.google.") || n.startsWith("ffmpeg") || n.contains(".sw.")
    }
}
