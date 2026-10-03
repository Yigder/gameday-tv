package com.gameday.tv.dvr

import com.gameday.tv.data.Http
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.net.URI

/** Copies a live stream to a file until a deadline (which may move, e.g. a game in overtime). */
object StreamRecorder {

    /** Called with the number of bytes written; return false to stop (e.g. storage is full). */
    fun interface Progress {
        fun onBytes(total: Long): Boolean
    }

    /** @return true when the source is finished for good (a fixed-length playlist), so there's nothing to reconnect to. */
    suspend fun record(url: String, out: OutputStream, deadline: () -> Long, progress: Progress): Boolean =
        if (isHls(url)) recordHls(url, out, deadline, progress) else {
            recordContinuous(url, out, deadline, progress)
            false
        }

    fun isHls(url: String) = url.contains(".m3u8", ignoreCase = true) || url.contains("type=m3u8", ignoreCase = true)

    /** A plain MPEG-TS stream: one long HTTP response. */
    private suspend fun recordContinuous(url: String, out: OutputStream, deadline: () -> Long, progress: Progress) =
        withContext(Dispatchers.IO) {
            val call = Http.client.newCall(Request.Builder().url(url).build())
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException(Http.describeHttpError(resp.code))
                val type = resp.header("Content-Type").orEmpty()
                if (type.contains("mpegurl", ignoreCase = true)) throw IOException("Playlist, not a stream")
                val input = resp.body.byteStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (System.currentTimeMillis() < deadline()) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) throw IOException("The stream ended.")
                    out.write(buf, 0, n)
                    total += n
                    if (!progress.onBytes(total)) return@use
                }
            }
        }

    /** HLS: keep downloading new segments of the (best) media playlist and append them. */
    private suspend fun recordHls(url: String, out: OutputStream, deadline: () -> Long, progress: Progress): Boolean =
        withContext(Dispatchers.IO) {
            // Relative URIs resolve against the address after redirects (IPTV panels redirect a lot).
            var (text, base) = fetchText(url)
            var playlistUrl = base
            if (text.contains("#EXT-X-STREAM-INF")) {
                playlistUrl = parseMaster(base, text).maxByOrNull { it.bandwidth }?.url ?: throw IOException("Empty playlist")
                fetchText(playlistUrl).let { text = it.first; base = it.second }
            }
            var lastSeq = -1L
            var wroteInit = false
            var total = 0L
            var first = true
            while (System.currentTimeMillis() < deadline()) {
                currentCoroutineContext().ensureActive()
                if (!first) fetchText(playlistUrl).let { text = it.first; base = it.second }
                first = false
                val pl = parseMedia(base, text)
                if (pl.encrypted) throw IOException("This channel is encrypted and can't be recorded.")
                if (!wroteInit && pl.initUrl != null) {
                    val bytes = fetchBytes(pl.initUrl)
                    out.write(bytes)
                    total += bytes.size
                    wroteInit = true
                }
                // Live: start at the live edge, not the start of the window. Fixed-length: from the start.
                if (lastSeq < 0 && !pl.endList && pl.segments.size > 3) lastSeq = pl.segments[pl.segments.size - 4].first
                for ((seq, segUrl) in pl.segments) {
                    if (seq <= lastSeq) continue
                    val bytes = fetchBytes(segUrl)
                    out.write(bytes)
                    total += bytes.size
                    lastSeq = seq
                    if (!progress.onBytes(total)) return@withContext false
                    if (System.currentTimeMillis() >= deadline()) return@withContext false
                }
                if (pl.endList) return@withContext true
                delay((pl.targetDurationSec * 500L).coerceIn(1_000L, 6_000L))
            }
            false
        }

    /** Body and final URL (after redirects). */
    private fun fetchText(url: String): Pair<String, String> =
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(Http.describeHttpError(resp.code))
            resp.body.string() to resp.request.url.toString()
        }

    private fun fetchBytes(url: String): ByteArray =
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(Http.describeHttpError(resp.code))
            resp.body.bytes()
        }

    // ---- playlist parsing (pure, unit tested) ----

    data class Variant(val bandwidth: Long, val url: String)

    data class MediaPlaylist(
        val targetDurationSec: Int,
        val segments: List<Pair<Long, String>>,
        val initUrl: String?,
        val encrypted: Boolean,
        val endList: Boolean,
    )

    fun parseMaster(base: String, text: String): List<Variant> {
        val out = ArrayList<Variant>()
        var pendingBandwidth: Long? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") ->
                    pendingBandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                line.isEmpty() || line.startsWith("#") -> Unit
                pendingBandwidth != null -> {
                    out += Variant(pendingBandwidth, resolve(base, line))
                    pendingBandwidth = null
                }
            }
        }
        return out
    }

    fun parseMedia(base: String, text: String): MediaPlaylist {
        var seq = 0L
        var target = 6
        var init: String? = null
        var encrypted = false
        var end = false
        val segments = ArrayList<Pair<Long, String>>()
        var expectSegment = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toIntOrNull() ?: 6
                line.startsWith("#EXT-X-MAP:") -> init = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let { resolve(base, it) }
                line.startsWith("#EXT-X-KEY:") -> encrypted = !line.contains("METHOD=NONE")
                line.startsWith("#EXT-X-ENDLIST") -> end = true
                line.startsWith("#EXTINF") -> expectSegment = true
                line.isEmpty() || line.startsWith("#") -> Unit
                expectSegment -> {
                    segments += (seq + segments.size) to resolve(base, line)
                    expectSegment = false
                }
            }
        }
        return MediaPlaylist(target, segments, init, encrypted, end)
    }

    fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) ref
        else runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
}
