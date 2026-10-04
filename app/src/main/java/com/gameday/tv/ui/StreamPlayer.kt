package com.gameday.tv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
@OptIn(UnstableApi::class)
class StreamController(context: Context, handleAudioFocus: Boolean) {
    /** Decode video on the CPU: set when this device is out of hardware decoders (see [DecoderBudget]). */
    @Volatile
    private var preferSoftware = false
    private var forcedSoftware = false

    private val dataSource = OkHttpDataSource.Factory(Http.client)

    val player: ExoPlayer = buildPlayer(context, dataSource, handleAudioFocus) { preferSoftware }

    /** True while this stream is decoding in software. */
    var softwareDecoding by mutableStateOf(false); private set

    /** Hardware, software, or automatic: chosen per stream in Settings › Playback or the stream's menu. */
    var decoderMode by mutableStateOf(DecoderMode.AUTO); private set

    var buffering by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var attempt by mutableIntStateOf(0); private set
    /** Changes every time a URL starts loading, so stall timers restart. */
    var loadToken by mutableIntStateOf(0); private set
    /** A stream that was playing dropped and is being reconnected. */
    var reconnecting by mutableStateOf(false); private set
    /** A movie, episode or recording reached its end. */
    var ended by mutableStateOf(false); private set
    /** The stream's audio, video and caption tracks. */
    var tracks by mutableStateOf(Tracks.EMPTY); private set
    /** Captions from the stream itself, drawn by [SubtitleOverlay] in the viewer's style. */
    var cues by mutableStateOf<List<Cue>>(emptyList()); private set

    /** Where to start (or resume after a dropped connection) for seekable video. */
    private var startAt = 0L

    private var key: String? = null
    private var candidates: List<String> = emptyList()
    private var headers: Map<String, String> = emptyMap()

    /** What's loaded (a channel id, a resume key…), or null when stopped. */
    val currentKey: String? get() = key
    private var playedOk = false
    private var reconnects = 0
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        attempt = 0
        start()
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                ended = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) {
                    playedOk = true
                    reconnecting = false
                    reconnects = 0
                }
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onPlayerError(e: PlaybackException) {
                when {
                    e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    isDecoderError(e) && !preferSoftware -> {
                        // Another stream took this one's hardware decoder. Don't take it back (that
                        // would kill the other stream); continue in software. In Automatic mode,
                        // also remember the limit; a stream forced to hardware says nothing about it.
                        if (decoderMode == DecoderMode.AUTO) DecoderBudget.learnFromFailure(this@StreamController)
                        else DecoderBudget.release(this@StreamController)
                        forcedSoftware = true
                        start()
                    }
                    isDecoderError(e) -> {
                        reconnecting = false
                        player.stop()
                        buffering = false
                        error = if (decoderMode == DecoderMode.SOFTWARE) {
                            "Software decoding can't play this stream. Set this screen's video decoding to Hardware or Automatic."
                        } else {
                            "This TV can't decode another video right now. Try a layout with fewer screens."
                        }
                    }
                    else -> fallback(describe(e))
                }
            }
        })
    }

    /**
     * Starts [urls] unless this exact stream is already playing (so moving between the menus and the
     * full-screen player doesn't interrupt it). [startPositionMs] resumes seekable video.
     */
    fun load(key: String, urls: List<String>, startPositionMs: Long = 0, headers: Map<String, String> = emptyMap()) {
        val running = player.playbackState != Player.STATE_IDLE && !ended && error == null
        if (key == this.key && urls == candidates && running) return
        this.key = key
        candidates = urls
        this.headers = headers
        startAt = startPositionMs.coerceAtLeast(0)
        ended = false
        attempt = 0
        resetReconnects()
        start()
    }

    /** Switches decoding; a playing stream restarts (from the same spot when seekable) to pick new codecs. */
    fun applyDecoderMode(mode: DecoderMode) {
        if (mode == decoderMode) return
        decoderMode = mode
        forcedSoftware = false
        if (key == null) return
        if (seekable) startAt = player.currentPosition
        resetReconnects()
        start()
    }

    fun retry() {
        attempt = 0
        resetReconnects()
        start()
    }

    private fun resetReconnects() {
        handler.removeCallbacks(reconnectRunnable)
        playedOk = false
        reconnects = 0
        reconnecting = false
    }

    fun onStalled() {
        if (buffering && !player.isPlaying) fallback("The stream isn't responding. The channel may be offline.")
    }

    fun togglePause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            // Live streams resume at the live edge; movies and recordings where they paused.
            if (player.isCurrentMediaItemLive || !player.isCurrentMediaItemSeekable) player.seekToDefaultPosition()
            player.play()
        }
    }

    val seekable: Boolean get() = player.isCurrentMediaItemSeekable && !player.isCurrentMediaItemLive && player.duration > 0

    /** A live stream with a rewind window (HLS with DVR), where the viewer can go back and forth. */
    val liveSeekable: Boolean
        get() = player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable && player.duration != C.TIME_UNSET && player.duration > 30_000

    /** Back / forward work: movies, recordings, catch-up, and live streams with a rewind window. */
    val canSeek: Boolean get() = seekable || liveSeekable

    /**
     * Live, but not at the live edge: paused, or rewound within the live window. (Streams that
     * aren't marked live, like most IPTV TS channels, only fall behind by pausing.)
     */
    fun isBehindLive(): Boolean {
        if (!player.playWhenReady) return true
        if (!player.isCurrentMediaItemLive) return false
        val offset = player.currentLiveOffset
        if (offset == C.TIME_UNSET) return false
        val target = player.currentMediaItem?.liveConfiguration?.targetOffsetMs?.takeIf { it != C.TIME_UNSET } ?: 0L
        return offset - target > BEHIND_LIVE_MS
    }

    /** Jumps to the live edge and plays. */
    fun goToLiveEdge() {
        player.seekToDefaultPosition()
        player.play()
    }

    fun seekBy(deltaMs: Long) {
        if (!canSeek) return
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, player.duration))
    }

    /** Playback speed (movies and shows): 1 = normal. */
    var speed: Float
        get() = player.playbackParameters.speed
        set(v) {
            player.setPlaybackSpeed(v)
        }

    fun seekTo(positionMs: Long) {
        if (seekable) player.seekTo(positionMs.coerceIn(0, player.duration))
    }

    /** Video sizing: fit (letterbox) or zoom to fill. */
    var zoom by mutableStateOf(false)

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

    fun onAppStopped() {
        handler.removeCallbacks(reconnectRunnable)
        player.stop()
        DecoderBudget.release(this)
    }

    /** Stops and forgets the stream: frees the provider connection and the decoder. */
    fun stop() {
        handler.removeCallbacks(reconnectRunnable)
        player.stop()
        player.clearMediaItems()
        DecoderBudget.release(this)
        key = null
        candidates = emptyList()
        error = null
        buffering = false
        reconnecting = false
        ended = false
        cues = emptyList()
    }

    fun onAppStarted() {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 && error == null) start()
    }

    fun release() {
        handler.removeCallbacks(reconnectRunnable)
        DecoderBudget.release(this)
        player.release()
    }

    private fun start() {
        error = null
        val url = candidates.getOrNull(attempt)
        if (url == null) {
            player.stop()
            DecoderBudget.release(this)
            error = "This channel has no stream address."
            return
        }
        // Claim a hardware decoder if the device has one free (or this stream insists on one);
        // otherwise decode in software.
        DecoderBudget.release(this)
        preferSoftware = when (decoderMode) {
            DecoderMode.SOFTWARE -> true
            DecoderMode.HARDWARE -> forcedSoftware
            DecoderMode.AUTO -> forcedSoftware || !DecoderBudget.canUseHardware()
        }
        if (!preferSoftware) DecoderBudget.acquire(this)
        softwareDecoding = preferSoftware
        buffering = true
        cues = emptyList()
        loadToken++
        dataSource.setDefaultRequestProperties(headers)
        player.stop() // codecs are chosen at prepare time, so start clean
        if (startAt > 0) player.setMediaItem(mediaItemFor(url), startAt) else player.setMediaItem(mediaItemFor(url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun fallback(message: String) {
        if (playedOk && reconnects < MAX_RECONNECTS) {
            // It was playing, so the address is right: the stream dropped. Reconnect with backoff.
            if (seekable) startAt = player.currentPosition
            reconnects++
            playedOk = false
            reconnecting = true
            buffering = true
            player.stop()
            handler.postDelayed(reconnectRunnable, 2_000L * reconnects)
        } else if (attempt + 1 < candidates.size) {
            attempt++
            start()
        } else {
            reconnecting = false
            player.stop()
            buffering = false
            error = message
        }
    }

    private companion object {
        const val MAX_RECONNECTS = 3
        /** Further behind the live edge than this counts as "not live". */
        const val BEHIND_LIVE_MS = 20_000L
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

    WatchStalls(controller)
    return controller
}

/** Stalled streams are common with IPTV: give up after 25s and try the alternate format. */
@Composable
fun WatchStalls(controller: StreamController) {
    LaunchedEffect(controller, controller.buffering, controller.loadToken) {
        if (controller.buffering) {
            delay(25_000)
            controller.onStalled()
        }
    }
}

/** Pauses the shared player while the app is in the background (frees the IPTV connection). */
@Composable
fun FollowAppLifecycle(controller: StreamController) {
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
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(controller: StreamController, modifier: Modifier = Modifier, onTop: Boolean = false, showSubtitles: Boolean = true) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                // A video window drawn inside another (picture-in-picture) must layer above it.
                if (onTop) (videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
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
        update = {
            it.player = controller.player
            it.resizeMode = if (controller.zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            it.subtitleView?.visibility = if (showSubtitles) android.view.View.VISIBLE else android.view.View.GONE
        },
        // The shared player moves between screens: let go of this view's surface when it leaves.
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/**
 * Video for the background of the menus. Unlike [VideoSurface] (a SurfaceView, which Android draws
 * outside the normal view layers) this one fades with its container and fills its area like a
 * header image (cropped, never letterboxed).
 */
@Composable
fun BackgroundVideoSurface(controller: StreamController, modifier: Modifier = Modifier) {
    CroppedVideoSurface(controller.player, modifier)
}

/** [player]'s picture, center-cropped to fill [modifier]'s area (a TextureView, so it fades and clips). */
@OptIn(UnstableApi::class)
@Composable
fun CroppedVideoSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                isFocusable = false
                var videoAspect = 16f / 9f
                // Center-crop: scale the picture (not the view) so it covers the whole area.
                fun fit() {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    if (w <= 0f || h <= 0f) return
                    val viewAspect = w / h
                    val sx = if (videoAspect > viewAspect) videoAspect / viewAspect else 1f
                    val sy = if (videoAspect > viewAspect) 1f else viewAspect / videoAspect
                    setTransform(android.graphics.Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
                }
                val listener = object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                            fit()
                        }
                    }
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
                tag = listener
                listener.onVideoSizeChanged(player.videoSize)
                player.addListener(listener)
                player.setVideoTextureView(this)
            }
        },
        onRelease = { view ->
            (view.tag as? Player.Listener)?.let { player.removeListener(it) }
            player.clearVideoTextureView(view)
        },
        modifier = modifier.clipToBounds(),
    )
}

// ---------------------------------------------------------------------------------------------

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, dataSource: OkHttpDataSource.Factory, handleAudioFocus: Boolean, preferSoftware: () -> Boolean): ExoPlayer {
    DecoderBudget.init(context)
    // OkHttp carries the configurable User-Agent and follows http<->https redirects, which IPTV panels use a lot.
    val mediaSources = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)
    // Video decoders are listed hardware-first by default; flip that when this stream must use software.
    val codecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        if (preferSoftware() && MimeTypes.isVideo(mimeType)) all.sortedBy { if (it.softwareOnly) 0 else 1 } else all
    }
    val renderers = DefaultRenderersFactory(context)
        .setMediaCodecSelector(codecSelector)
        .setEnableDecoderFallback(true)
    val loadControl = DefaultLoadControl.Builder()
        // Start after 1.5 s of video (faster channel changes); 3 s after a rebuffer.
        .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
        .build()
    return ExoPlayer.Builder(context, renderers)
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

private fun isDecoderError(e: PlaybackException): Boolean = e.errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
)

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
    // IPTV servers answer offline channels with a non-video response, which shows up as "unsupported".
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
        "This channel isn't sending video right now. It may be offline, or an event channel between events."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        "The stream format isn't supported. Try switching between MPEG-TS and HLS in Settings › Playback."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
        "This device can't decode this stream (or has no free video decoder)."
    else -> "Playback error: ${e.errorCodeName}"
}
