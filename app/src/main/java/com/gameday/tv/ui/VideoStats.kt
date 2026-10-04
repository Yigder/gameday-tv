package com.gameday.tv.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.gameday.tv.data.MediaInfo
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Video stats ("stats for nerds") drawn over the video: picture, frame rate, codecs, decoder,
 * bitrates, buffer and connection, refreshed every second. [compact] is the short version for a
 * Multiview screen.
 */
@Composable
fun VideoStatsOverlay(stream: StreamController, modifier: Modifier = Modifier, compact: Boolean = false) {
    var rows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(stream, compact) {
        while (true) {
            rows = videoStats(stream, compact)
            delay(1_000)
        }
    }
    Column(
        modifier
            .width(if (compact) 230.dp else 340.dp)
            .background(Color(0xB8000000), RoundedCornerShape(10.dp))
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 8.dp else 12.dp),
    ) {
        if (!compact) {
            Text("Video stats", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Text)
            Spacer(Modifier.height(6.dp))
        }
        if (rows.isEmpty()) Text("Waiting for video…", fontSize = 12.sp, color = AppColors.TextDim)
        rows.forEach { (label, value) ->
            Row(Modifier.padding(vertical = 1.dp)) {
                Text(label, fontSize = if (compact) 10.sp else 12.sp, color = Color(0xFF9E9E9E), modifier = Modifier.width(if (compact) 72.dp else 104.dp), maxLines = 1)
                Text(value, fontSize = if (compact) 10.sp else 12.sp, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun videoStats(s: StreamController, compact: Boolean): List<Pair<String, String>> {
    val p = s.player
    val video = p.videoFormat
    val audio = p.audioFormat
    val out = ArrayList<Pair<String, String>>()

    val size = p.videoSize
    val w = if (size.width > 0) size.width else video?.width ?: 0
    val h = if (size.height > 0) size.height else video?.height ?: 0
    if (w > 0 && h > 0) out += "Resolution" to listOfNotNull("$w×$h", MediaInfo.quality(w, h)).joinToString(" · ")
    // Measured from the frames shown: most IPTV streams don't declare a frame rate.
    out += "Frame rate" to (MediaInfo.frameRate(s.frameRate()) ?: "Measuring…")
    if (video != null) {
        val hdr = when (video.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        listOfNotNull(MediaInfo.videoCodec(video.sampleMimeType, video.codecs), hdr).joinToString(" · ").ifEmpty { null }
            ?.let { out += "Video" to it }
        if (!compact) MediaInfo.bitrate(bitrateOf(video))?.let { out += "Video bitrate" to it }
    }
    s.videoDecoder?.let { name ->
        val kind = if (MediaInfo.isSoftwareDecoder(name)) "Software" else "Hardware"
        out += "Decoder" to if (compact) kind else "$kind · $name"
    }
    if (audio != null) {
        val rate = audio.sampleRate.takeIf { it > 0 }?.let { r ->
            if (r % 1000 == 0) "${r / 1000} kHz" else String.format(Locale.US, "%.1f kHz", r / 1000.0)
        }
        listOfNotNull(MediaInfo.audioCodec(audio.sampleMimeType, audio.codecs), MediaInfo.channels(audio.channelCount), rate.takeIf { !compact })
            .joinToString(" · ").ifEmpty { null }?.let { out += "Audio" to it }
        if (!compact) MediaInfo.bitrate(bitrateOf(audio))?.let { out += "Audio bitrate" to it }
    }
    if (!compact) MediaInfo.container(s.currentUrl)?.let { out += "Stream" to it }
    MediaInfo.bitrate(s.bandwidth)?.let { out += "Connection" to it }
    val buffered = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
    if (p.playbackState != androidx.media3.common.Player.STATE_IDLE) {
        out += "Buffer" to String.format(Locale.US, "%.1f s", buffered / 1000.0)
    }
    if (!compact && p.isCurrentMediaItemLive) {
        val offset = p.currentLiveOffset
        if (offset != C.TIME_UNSET) out += "Behind live" to String.format(Locale.US, "%.1f s", offset / 1000.0)
    }
    out += "Dropped frames" to s.droppedFrames.toString()
    return out
}

private fun bitrateOf(f: Format): Long = listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it > 0 }?.toLong() ?: 0L
