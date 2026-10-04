package com.gameday.tv.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.gameday.tv.data.Http
import com.gameday.tv.data.Trailers
import kotlinx.coroutines.delay

/**
 * The trailer player for On Demand (one for the whole app, owned by [AppViewModel]): plays the
 * focused title's trailer inside its card, like Nuvio. Separate from the live TV player.
 */
@Stable
@OptIn(UnstableApi::class)
class TrailerController(context: Context) {
    private val dataSource = OkHttpDataSource.Factory(Http.client)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
        // Trailers are short: start quickly, keep little in memory.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 15_000, 1_000, 2_000).build())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        .build()

    /** Which card the trailer belongs to. */
    var key by mutableStateOf<String?>(null); private set
    /** The first frame is on screen (the card's art can give way). */
    var showing by mutableStateOf(false); private set
    var ended by mutableStateOf(false); private set

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                showing = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    ended = true
                    showing = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                ended = true
                showing = false
            }
        })
    }

    var volume: Float
        get() = player.volume
        set(v) {
            player.volume = v
        }

    fun play(key: String, source: Trailers.Source) {
        if (this.key == key && !ended && player.playbackState != Player.STATE_IDLE) return
        this.key = key
        showing = false
        ended = false
        dataSource.setUserAgent(source.userAgent)
        val item = MediaItem.Builder().setUri(source.url).apply { if (source.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    /** Stops the trailer (only if it still belongs to [forKey], when given). */
    fun stop(forKey: String? = null) {
        if (forKey != null && forKey != key) return
        player.stop()
        player.clearMediaItems()
        key = null
        showing = false
        ended = false
    }

    fun release() = player.release()
}

/**
 * Plays the trailer of a title in this box while [active] (after resting [delayMs] on it): the
 * first of [videoIds] that YouTube will stream. Draws nothing until the first frame is ready, so
 * whatever is underneath (the card's art) stays visible meanwhile — and for good when there's no
 * trailer. [onEnded] runs when it finishes.
 */
@Composable
fun TrailerPreview(
    vm: AppViewModel,
    key: String,
    videoIds: List<String>,
    active: Boolean,
    modifier: Modifier = Modifier,
    delayMs: Long = 0,
    onEnded: () -> Unit = {},
) {
    if (vm.trailerPreviews == "off") return
    val trailer = vm.trailer
    val wanted = active && videoIds.isNotEmpty()
    LaunchedEffect(key, wanted, videoIds) {
        if (!wanted) return@LaunchedEffect
        delay(delayMs)
        val source = videoIds.take(3).firstNotNullOfOrNull { Trailers.resolve(it) } ?: return@LaunchedEffect
        trailer.volume = if (vm.trailerPreviews == "sound") 1f else 0f
        trailer.play(key, source)
    }
    // Leaving the card (or the screen) stops its trailer; so does the app going to the background.
    DisposableEffect(key, wanted) { onDispose { trailer.stop(key) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, key) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) trailer.stop(key) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val mine = wanted && trailer.key == key
    val currentOnEnded by rememberUpdatedState(onEnded)
    LaunchedEffect(mine, trailer.ended) { if (mine && trailer.ended) currentOnEnded() }
    val alpha by animateFloatAsState(if (mine && trailer.showing) 1f else 0f, tween(450), label = "trailer")
    if (mine) {
        Box(modifier.alpha(alpha)) { CroppedVideoSurface(trailer.player, Modifier.fillMaxSize()) }
    }
}
