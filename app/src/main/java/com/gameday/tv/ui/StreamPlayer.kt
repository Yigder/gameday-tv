package com.gameday.tv.ui

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gameday.tv.data.Http
import kotlinx.coroutines.delay

/**
 * One live stream: an ExoPlayer plus the IPTV-specific handling around it —
 * trying each candidate URL in turn (TS, then HLS), detecting stalls, and recovering from falling
 * behind the live window. Used by the full-screen player and by every multiview tile.
 */
@Stable
class StreamController(context: Context, handleAudioFocus: Boolean) {
    val player: ExoPlayer = buildPlayer(context, handleAudioFocus)

    var buffering by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var attempt by mutableIntStateOf(0); private set
    /** Changes every time a URL starts loading, so stall timers restart. */
    var loadToken by mutableIntStateOf(0); private set

    private var key: String? = null
    private var candidates: List<String> = emptyList()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                } else {
                    fallback(describe(e))
                }
            }
        })
    }

    /** Starts [urls] unless this exact stream is already loaded. */
    fun load(key: String, urls: List<String>) {
        if (key == this.key && urls == candidates) return
        this.key = key
        candidates = urls
        attempt = 0
        start()
    }

    fun retry() {
        attempt = 0
        start()
    }

    fun onStalled() {
        if (buffering && !player.isPlaying) fallback("The stream isn't responding. The channel may be offline.")
    }

    fun togglePause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.seekToDefaultPosition()
            player.play()
        }
    }

    var volume: Float
        get() = player.volume
        set(v) {
            player.volume = v
        }

    /** Caps resolution for small tiles, which saves bandwidth and decoder capacity on adaptive (HLS) streams. */
    fun setMaxVideoSize(width: Int, height: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(width, height)
            .build()
    }

    fun onAppStopped() = player.stop()

    fun onAppStarted() {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 && error == null) {
            player.prepare()
            player.seekToDefaultPosition()
            player.play()
        }
    }

    fun release() = player.release()

    private fun start() {
        error = null
        val url = candidates.getOrNull(attempt)
        if (url == null) {
            player.stop()
            error = "This channel has no stream address."
            return
        }
        buffering = true
        loadToken++
        player.setMediaItem(mediaItemFor(url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun fallback(message: String) {
        if (attempt + 1 < candidates.size) {
            attempt++
            start()
        } else {
            player.stop()
            buffering = false
            error = message
        }
    }
}

/**
 * Creates a [StreamController] tied to this composition: released when it leaves, stopped while the
 * app is in the background (frees the IPTV connection), and watched for stalls.
 */
@Composable
fun rememberStreamController(handleAudioFocus: Boolean = true): StreamController {
    val context = LocalContext.current
    val controller = remember { StreamController(context, handleAudioFocus) }
    DisposableEffect(controller) { onDispose { controller.release() } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onAppStopped()
                Lifecycle.Event.ON_START -> controller.onAppStarted()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Stalled streams are common with IPTV: give up after 25s and try the alternate format.
    LaunchedEffect(controller.buffering, controller.loadToken) {
        if (controller.buffering) {
            delay(25_000)
            controller.onStalled()
        }
    }
    return controller
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(controller: StreamController, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                player = controller.player
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                keepScreenOn = true
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
        },
        update = { it.player = controller.player },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------------------------

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, handleAudioFocus: Boolean): ExoPlayer {
    // OkHttp carries the configurable User-Agent and follows http<->https redirects, which IPTV panels use a lot.
    val dataSource = OkHttpDataSource.Factory(Http.client)
    val mediaSources = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15_000, 50_000, 2_000, 4_000)
        .build()
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSources)
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            handleAudioFocus,
        )
        .build()
}

private fun mediaItemFor(url: String): MediaItem {
    val builder = MediaItem.Builder().setUri(url)
    // Playlist URLs often lack an extension, so hint HLS explicitly when we can tell.
    if (url.contains(".m3u8", ignoreCase = true) || url.contains("type=m3u8", ignoreCase = true)) {
        builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    }
    return builder.build()
}

@OptIn(UnstableApi::class)
private fun describe(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
        when (val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode) {
            401, 403 -> "Access denied by your provider (HTTP $code). Your account may be expired or at its connection limit."
            404 -> "This channel wasn't found on the server (HTTP 404)."
            null -> "The server rejected the stream."
            else -> "The server returned HTTP $code."
        }
    }
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Couldn't connect to the stream. Check your internet connection."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        "The stream format isn't supported. Try switching between MPEG-TS and HLS in Account."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
        "This device can't decode this stream (or has no free video decoder)."
    else -> "Playback error: ${e.errorCodeName}"
}
