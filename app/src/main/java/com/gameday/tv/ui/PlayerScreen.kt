package com.gameday.tv.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.GameState
import com.gameday.tv.data.GameStats
import com.gameday.tv.data.StreamFormat
import com.gameday.tv.data.Subtitles
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import java.io.File

internal val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** Remote buttons that show/hide the score bug (Info, Guide, and the red/green color keys). */
internal val SCORE_KEYS = setOf(Key.Info, Key.Guide, Key.ProgramRed, Key.ProgramGreen)

/** True on the first auto-repeat of a held key, i.e. a long press. */
internal fun KeyEvent.isLongPressRepeat(): Boolean = nativeKeyEvent.repeatCount == 1

private enum class Panel { None, Stats, Settings, SubtitleStyle, CatchUp, Subtitles, Audio, Speed, Sources, Episodes }

/** What the player is showing, flattened from the [PlayRequest]. */
private data class Now(
    val key: String,
    val urls: List<String>,
    val title: String,
    val subtitle: String,
    val channel: Channel?,
    val startAt: Long,
    val resumeItem: VodItem?,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Full-screen player with YouTube TV's controls: title and progress at the bottom, a row of round
 * buttons, a "more to watch" strip below them, and side panels for stats and playback settings.
 */
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val req = vm.playback
    if (req == null) {
        EmptyState("Nothing to play", modifier = Modifier.padding(top = 120.dp)) { PillButton("Back", { vm.back() }, primary = true) }
        return
    }
    val now = remember(req) { describe(vm, req) }
    // The shared player: a channel already playing behind the menus continues without restarting.
    val stream = vm.mainStream
    val decoding = vm.decoderMode(DecoderSlot.PLAYER)
    // Before load(), so the first start already uses the chosen decoder.
    LaunchedEffect(stream, decoding) { stream.applyDecoderMode(decoding) }
    LaunchedEffect(now.key, now.urls) {
        stream.volume = 1f
        // Speed is for movies and shows; live TV (and what plays behind the menus) is always 1×.
        if (req !is PlayRequest.Vod && req !is PlayRequest.Rec) stream.speed = 1f
        stream.load(now.key, now.urls, now.startAt, now.headers)
        if (req is PlayRequest.Live) now.channel?.let { vm.noteRecent(it) }
        vm.noteWatching(if (req is PlayRequest.Live) now.channel else null)
    }
    // ---- subtitles: the stream's own captions, or a file from a subtitle add-on ----
    val resumeItem = now.resumeItem
    val subs = remember(now.key) { PlayerSubtitles() }
    LaunchedEffect(vm.captions, stream, subs.chosen, vm.subtitleLanguage) {
        // An add-on file replaces the stream's captions, so they don't show twice.
        stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(vm.subtitleLanguage)
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !vm.captions || subs.chosen != null).build()
    }
    val subRequest = resumeItem?.subtitles
    if (subRequest != null) {
        LaunchedEffect(now.key) {
            if (!vm.addons.hasSubtitleAddons && subRequest.fromSource.isEmpty()) return@LaunchedEffect
            subs.searching = true
            subs.tracks = Subtitles.sortTracks(vm.addons.subtitles(subRequest), vm.subtitleLanguage)
            subs.searching = false
        }
    }
    // Captions on: use the stream's captions in the chosen language, else an add-on file in it.
    LaunchedEffect(vm.captions, subs.tracks, stream.tracks, vm.subtitleLanguage) {
        if (!vm.captions || subs.decided || subs.chosen != null) return@LaunchedEffect
        val embedded = stream.tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val ownMatch = embedded.firstOrNull { g -> Subtitles.sameLanguage(g.getTrackFormat(0).language, vm.subtitleLanguage) }
        when {
            ownMatch != null -> {
                subs.decided = true
                stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(ownMatch.mediaTrackGroup, 0)).build()
            }
            else -> subs.tracks.firstOrNull { Subtitles.sameLanguage(it.lang, vm.subtitleLanguage) }?.let {
                subs.decided = true
                subs.chosen = it
            }
        }
    }
    LaunchedEffect(subs.chosen) {
        val track = subs.chosen ?: run { subs.cues = emptyList(); return@LaunchedEffect }
        subs.loading = true
        subs.cues = emptyList()
        try {
            subs.cues = vm.addons.loadSubtitle(track)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.showMessage("Couldn't load those subtitles: ${vm.friendly(e)}")
            subs.chosen = null
        } finally {
            subs.loading = false
        }
    }

    DisposableEffect(stream) { onDispose { stream.speed = 1f } }

    // ---- remember where movies, episodes and recordings were left ----
    if (resumeItem != null) {
        LaunchedEffect(resumeItem.key) {
            while (true) {
                delay(10_000)
                if (stream.player.duration > 0) vm.saveResume(resumeItem, stream.player.currentPosition, stream.player.duration)
            }
        }
        DisposableEffect(resumeItem.key) {
            onDispose { if (stream.player.duration > 0) vm.saveResume(resumeItem, stream.player.currentPosition, stream.player.duration) }
        }
    }
    LaunchedEffect(stream.ended) {
        if (!stream.ended) return@LaunchedEffect
        resumeItem?.let { vm.saveResume(it, 0, 0) }
        val next = resumeItem?.next
        when {
            vm.autoplayNext && vm.nextVod() -> Unit
            vm.autoplayNext && next != null -> vm.addons.playNext(next)
            else -> vm.back()
        }
    }

    // ---- sports context: the event we came from, or one this channel looks like it's showing ----
    val liveChannel = (req as? PlayRequest.Live)?.current
    val liveGame = remember(req, vm.games) {
        (req as? PlayRequest.Live)?.eventId?.let { vm.gameById(it) } ?: liveChannel?.let { vm.liveGameFor(it) }
    }
    val liveTournament = remember(req, vm.tournaments) {
        if (liveGame != null) null
        else (req as? PlayRequest.Live)?.eventId?.let { vm.tournamentById(it) } ?: liveChannel?.let { vm.liveTournamentFor(it) }
    }
    // The score bug follows the stream, which runs behind the live scoreboard.
    val linkedGame = vm.delayedGame(liveGame)
    val linkedTournament = vm.delayedTournament(liveTournament)
    val bug = rememberBugState(
        key = now.key,
        scoreKey = scoreKeyOf(linkedGame, linkedTournament),
        mode = vm.scoreBugMode,
        popOnScore = vm.scoreAlerts && !vm.hideScores,
        startVisible = !vm.hideScores,
    )

    // ---- controls ----
    var controls by remember { mutableStateOf(true) }
    var nonce by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(Panel.None) }
    // Subtitle style opens from Settings or Subtitles, and Back returns there.
    var styleFrom by remember { mutableStateOf(Panel.Settings) }
    // Sources for another episode (picked in Episodes); null = what's playing.
    var sourcesFor by remember { mutableStateOf<com.gameday.tv.data.MetaVideo?>(null) }
    var okLongPressed by remember { mutableStateOf(false) }
    // Video stats stay up (over the video) until turned off, like YouTube's "stats for nerds".
    var videoStats by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    fun poke() { nonce++ }
    fun showControls() { controls = true; poke() }

    LaunchedEffect(now.key) { showControls() }
    LaunchedEffect(nonce, controls, panel) {
        if (!controls || panel != Panel.None) return@LaunchedEffect
        delay(6_000)
        controls = false
    }
    LaunchedEffect(controls, panel) {
        when {
            panel != Panel.None -> Unit
            controls -> playFocus.requestFocusSafely(40)
            else -> rootFocus.requestFocusSafely(40)
        }
    }
    BackHandler(enabled = panel != Panel.None) {
        when {
            panel == Panel.SubtitleStyle -> panel = styleFrom
            panel == Panel.Sources && sourcesFor != null -> { sourcesFor = null; panel = Panel.Episodes }
            else -> { panel = Panel.None; showControls() }
        }
    }
    BackHandler(enabled = panel == Panel.None && controls) { controls = false }

    val live = req is PlayRequest.Live
    val channel = now.channel

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                // Any key keeps the controls up while they're showing.
                if (controls && ev.type == KeyEventType.KeyDown) poke()
                false
            }
            .focusRequester(rootFocus)
            .onKeyEvent { ev ->
                if (panel != Panel.None) return@onKeyEvent false
                // Media keys work whether or not the controls are showing.
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { stream.togglePause(); showControls(); return@onKeyEvent true }
                        Key.MediaFastForward -> { stream.seekBy(30_000); showControls(); return@onKeyEvent true }
                        Key.MediaRewind -> { stream.seekBy(-10_000); showControls(); return@onKeyEvent true }
                        Key.ChannelUp -> { vm.zap(+1); return@onKeyEvent true }
                        Key.ChannelDown -> { vm.zap(-1); return@onKeyEvent true }
                        in SCORE_KEYS -> { bug.toggle(); return@onKeyEvent true }
                        else -> Unit
                    }
                }
                if (controls) return@onKeyEvent false
                // Controls hidden: OK shows them (and the score); hold OK opens Multiview.
                if (ev.key in OK_KEYS) {
                    when {
                        ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0 -> okLongPressed = false
                        ev.type == KeyEventType.KeyDown && ev.isLongPressRepeat() && channel != null && live -> {
                            okLongPressed = true
                            OkKeyGate.swallowRelease()
                            vm.multiviewWith(channel)
                        }
                        ev.type == KeyEventType.KeyUp -> if (!okLongPressed) {
                            if (stream.error != null) stream.retry()
                            bug.show()
                            showControls()
                        }
                    }
                    return@onKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    // Up only shows or hides the score bug (nothing else), like ScoreBox.
                    Key.DirectionUp -> {
                        when {
                            linkedGame != null || linkedTournament != null -> bug.toggle()
                            live -> vm.showMessage("No live score for this channel")
                            else -> showControls()
                        }
                        true
                    }
                    Key.DirectionDown -> { showControls(); true }
                    Key.DirectionLeft -> { if (stream.canSeek) stream.seekBy(-10_000); showControls(); true }
                    Key.DirectionRight -> { if (stream.canSeek) stream.seekBy(30_000); showControls(); true }
                    Key.Menu -> { panel = Panel.Settings; true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(now.key) {
                detectTapGestures(
                    onTap = { if (controls) controls = false else showControls() },
                    onLongPress = { if (channel != null && live) vm.multiviewWith(channel) },
                )
            },
    ) {
        // Captions are drawn by SubtitleOverlay (in the viewer's style), not by the video view.
        VideoSurface(stream, Modifier.fillMaxSize(), showSubtitles = false)
        if (vm.captions) {
            var addonLines by remember(subs) { mutableStateOf<List<String>>(emptyList()) }
            if (subs.chosen != null) {
                LaunchedEffect(subs.cues, subs.delayMs) {
                    while (true) {
                        val at = stream.player.currentPosition - subs.delayMs
                        val lines = Subtitles.activeAt(subs.cues, at).map { it.text }
                        if (lines != addonLines) addonLines = lines
                        delay(80)
                    }
                }
            }
            val own = stream.cues
            SubtitleOverlay(
                lines = if (subs.chosen != null) addonLines else textOfCues(own),
                bitmaps = if (subs.chosen != null) emptyList() else own.filter { it.bitmap != null },
                style = vm.subtitleStyle,
            )
        }

        if (stream.buffering && stream.error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(48.dp)
                val note = when {
                    stream.reconnecting -> "Stream dropped — reconnecting…"
                    stream.attempt > 0 -> "Trying alternate stream format…"
                    stream.softwareDecoding -> null
                    else -> null
                }
                if (note != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(note, fontSize = 13.sp, color = AppColors.TextDim)
                }
            }
        }

        stream.error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).width(540.dp).background(Color(0xF0212121), RoundedCornerShape(12.dp)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play ${now.title}", fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(msg, fontSize = 14.sp, color = AppColors.TextDim, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text(if (live) "OK  Try again      CH+ / CH−  Other channel" else "OK  Try again", fontSize = 13.sp, color = AppColors.Text)
            }
        }

        // With the controls up, the bug moves below the channel name and clock.
        val bugTop by animateDpAsState(if (controls && panel == Panel.None) 92.dp else 24.dp, tween(260, easing = FastOutSlowInEasing), label = "bugTop")
        EventBug(linkedGame, linkedTournament, bug, Modifier.align(Alignment.TopEnd).padding(top = bugTop, end = 32.dp))

        // Video stats: top left, below the channel name while the controls are up.
        if (videoStats) {
            val statsTop by animateDpAsState(if (controls && panel == Panel.None && live) 92.dp else 28.dp, tween(260, easing = FastOutSlowInEasing), label = "statsTop")
            VideoStatsOverlay(stream, Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = statsTop))
        }

        AnimatedVisibility(
            visible = controls && panel == Panel.None,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (req is PlayRequest.Vod || req is PlayRequest.Rec) {
                // Movies, shows and recordings: Nuvio's player.
                VodControls(vm, req, now, stream, playFocus, onPanel = { sourcesFor = null; panel = it }, onPoke = ::poke,
                    videoStats = videoStats, onVideoStats = { videoStats = !videoStats })
            } else {
                // Live TV and catch-up: YouTube TV's.
                LiveControls(
                    vm = vm,
                    req = req,
                    now = now,
                    stream = stream,
                    playFocus = playFocus,
                    hasStats = linkedGame != null && linkedGame.state != GameState.PRE,
                    onPanel = { panel = it },
                    onPoke = ::poke,
                    subs = subs,
                    videoStats = videoStats,
                    onVideoStats = { videoStats = !videoStats },
                )
            }
        }

        AnimatedVisibility(
            visible = panel == Panel.Stats && linkedGame != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            linkedGame?.let { Box(Modifier.trapFocus()) { StatsPanel(vm, it) } }
        }

        SidePanel(panel == Panel.Settings) {
            SettingsPanel(vm, stream, live, subs, subRequest != null, onStyle = { styleFrom = Panel.Settings; panel = Panel.SubtitleStyle })
        }
        SidePanel(panel == Panel.Subtitles) {
            SubtitlesPanel(vm, stream, subs, subRequest != null, onStyle = { styleFrom = Panel.Subtitles; panel = Panel.SubtitleStyle })
        }
        SidePanel(panel == Panel.Audio) { AudioPanel(stream) }
        SidePanel(panel == Panel.Speed) { SpeedPanel(stream) }
        SidePanel(panel == Panel.CatchUp && channel != null) {
            channel?.let { ch ->
                CatchUpPanel(vm, ch, (req as? PlayRequest.Catchup)?.program, onDone = { panel = Panel.None })
            }
        }
        SidePanel(panel == Panel.Sources && resumeItem != null) {
            resumeItem?.let { item ->
                SourcesPanel(vm, item, sourcesFor, beforeSwitch = {
                    if (stream.player.duration > 0) vm.saveResume(item, stream.player.currentPosition, stream.player.duration)
                }, onDone = { sourcesFor = null; panel = Panel.None })
            }
        }
        SidePanel(panel == Panel.Episodes) {
            EpisodesPanel(vm, req, resumeItem, onPickAddonEpisode = { sourcesFor = it; panel = Panel.Sources }, onDone = { panel = Panel.None })
        }

        SidePanel(panel == Panel.SubtitleStyle) { SubtitleStylePanel(vm) }
    }
}

/** A panel that slides in from the right edge of the player. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SidePanel(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        // Up / Down past the ends stay in the panel.
        Box(Modifier.trapFocus()) { content() }
    }
}

private fun describe(vm: AppViewModel, req: PlayRequest): Now = when (req) {
    is PlayRequest.Live -> {
        val ch = req.current!!
        Now(ch.id, vm.streamCandidates(ch), cleanChannelName(ch.name), ch.group, ch, 0, null)
    }
    is PlayRequest.Catchup -> Now(
        "cu:${req.channel.id}@${req.program.startMillis}", listOf(req.url), req.program.title,
        "${cleanChannelName(req.channel.name)} · ${formatDay(req.program.startMillis)} ${formatRange(req.program.startMillis, req.program.endMillis)}",
        req.channel, 0, null,
    )
    is PlayRequest.Vod -> {
        val item = req.current
        Now(item.key, listOf(item.url), item.title, item.subtitle, null, vm.resumeFor(item.key)?.positionMs ?: 0, item, item.headers)
    }
    is PlayRequest.Rec -> {
        val r = req.recording
        val item = VodItem("rec:${r.id}", r.title, "Recorded ${formatDay(r.startMillis)} · ${cleanChannelName(r.channelName)}", r.image,
            Uri.fromFile(File(r.file!!)).toString())
        Now(item.key, listOf(item.url), r.title, item.subtitle, null, vm.resumeFor(item.key)?.positionMs ?: 0, item)
    }
}

// ---------------------------------------------------------------------------------------------
// Controls overlay
// ---------------------------------------------------------------------------------------------

/** Live TV and catch-up: YouTube TV's controls, with a LIVE button and catch-up. */
@Composable
private fun LiveControls(
    vm: AppViewModel,
    req: PlayRequest,
    now: Now,
    stream: StreamController,
    playFocus: FocusRequester,
    hasStats: Boolean,
    onPanel: (Panel) -> Unit,
    onPoke: () -> Unit,
    subs: PlayerSubtitles,
    videoStats: Boolean,
    onVideoStats: () -> Unit,
) {
    val channel = now.channel
    val (btn, playBtn) = playerButtonSizes(vm.playerButtons)
    // Positions are polled while the controls are up.
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var behindLive by remember { mutableStateOf(false) }
    var canSeek by remember { mutableStateOf(false) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = stream.player.currentPosition
            duration = stream.player.duration.coerceAtLeast(0)
            playing = stream.player.playWhenReady
            behindLive = stream.isBehindLive()
            canSeek = stream.canSeek
            clock = System.currentTimeMillis()
            delay(500)
        }
    }
    val catchup = req as? PlayRequest.Catchup
    val program = when (req) {
        is PlayRequest.Live -> channel?.let { vm.nowPlaying(it.id, clock) }
        is PlayRequest.Catchup -> req.program
        else -> null
    }
    if (req is PlayRequest.Live && channel != null) LaunchedEffect(channel.id) { vm.requestEpg(channel) }
    val atLive = catchup == null && !behindLive

    Box(Modifier.fillMaxSize()) {
        // Top: channel and clock.
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(horizontal = 48.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (channel != null) {
                ChannelLogo(channel, 44.dp, background = Color(0x33FFFFFF))
                Spacer(Modifier.width(12.dp))
                Text(cleanChannelName(channel.name), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            Text(formatTime(clock), fontSize = 16.sp, color = AppColors.TextDim)
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000), Color(0xF2000000))))
                .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 14.dp),
        ) {
            Text(program?.title ?: now.title, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    req is PlayRequest.Live && program != null -> "${now.title} · ${formatRange(program.startMillis, program.endMillis)}"
                    else -> now.subtitle
                },
                fontSize = 14.sp,
                color = AppColors.TextDim,
                maxLines = 1,
            )
            Spacer(Modifier.height(12.dp))

            // Progress: the live program's progress, or a seek bar for catch-up.
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    req is PlayRequest.Live -> {
                        if (atLive) LiveBadge() else Tag("BEHIND LIVE", color = Color(0x55FFFFFF))
                        Spacer(Modifier.width(12.dp))
                        if (program != null) {
                            Text(formatTime(program.startMillis), fontSize = 12.sp, color = AppColors.TextDim)
                            Spacer(Modifier.width(10.dp))
                            ProgressLine(program.progress(clock), Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text(formatTime(program.endMillis), fontSize = 12.sp, color = AppColors.TextDim)
                        } else {
                            ProgressLine(1f, Modifier.weight(1f))
                        }
                    }
                    canSeek -> SeekBar(position, duration, stream, onPoke, Modifier.weight(1f))
                    else -> {
                        Text("Replay", fontSize = 12.sp, color = AppColors.TextDim)
                        Spacer(Modifier.width(10.dp))
                        ProgressLine(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // Buttons (Settings › Playback › Player buttons sets their size).
            CompositionLocalProvider(LocalButtonSize provides btn) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
                // LIVE: red while watching live; otherwise it jumps back to live (from catch-up, a pause or a rewind).
                LiveButton(atLive) {
                    when {
                        catchup != null -> vm.goLive()
                        behindLive -> stream.goToLiveEdge()
                        else -> vm.showMessage("You're watching live")
                    }
                    onPoke()
                }
                if (canSeek) IconCircleButton(Icons.Replay, "Back 10 seconds", { stream.seekBy(-10_000) }, badge = "10")
                IconCircleButton(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", { stream.togglePause() },
                    Modifier.focusRequester(playFocus), size = playBtn)
                if (canSeek) IconCircleButton(Icons.ForwardArrow, "Forward 30 seconds", { stream.seekBy(30_000) }, badge = "30")
                if (channel != null && channel.archiveDays > 0) {
                    IconCircleButton(Icons.History, if (catchup != null) "Catch-up" else "Start over & catch-up", { onPanel(Panel.CatchUp) }, active = catchup != null)
                }
                if (req is PlayRequest.Live && channel != null) {
                    val game = req.eventId?.let { vm.gameById(it) }
                    val rec = game?.let { vm.recordingForEvent(it.id) }
                    IconCircleButton(Icons.Record, if (rec != null) "Recording" else "Record", {
                        when {
                            rec != null -> recordingMenu(vm, rec)
                            game != null -> vm.recordGame(game, channel)
                            else -> recordMenu(vm, channel)
                        }
                    }, active = rec != null, tint = if (rec != null) AppColors.Live else null)
                    IconCircleButton(Icons.Multiview, "Multiview", { vm.multiviewWith(channel) })
                }
                if (hasStats) IconCircleButton(Icons.Stats, "Game stats", { onPanel(Panel.Stats) })
                IconCircleButton(Icons.Captions, if (vm.captions) "Captions on" else "Captions off", {
                    val on = !vm.captions
                    vm.updateCaptions(on)
                    val any = stream.tracks.groups.any { it.type == C.TRACK_TYPE_TEXT } || subs.tracks.isNotEmpty()
                    if (on && !any && !subs.searching) vm.showMessage("No captions found for this channel")
                }, active = vm.captions)
                IconCircleButton(Icons.Settings, "Settings", { onPanel(Panel.Settings) })
                IconCircleButton(Icons.VideoStats, "Video stats", onVideoStats, active = videoStats)
                if (channel != null && req is PlayRequest.Live) {
                    val fav = vm.isFavoriteChannel(channel.id)
                    IconCircleButton(Icons.Star, if (fav) "Favorite" else "Add favorite", { vm.toggleFavoriteChannel(channel) }, active = fav)
                }
            }
            }

            // More to watch: the channels to zap through.
            MoreStrip(vm, req, onPoke)
        }
    }
}

/** YouTube TV's LIVE chip, as a button: red dot at the live edge, grey when behind (press to catch up). */
@Composable
private fun LiveButton(atLive: Boolean, onClick: () -> Unit) {
    val size = LocalButtonSize.current
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.height(size).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0x29FFFFFF),
                contentColor = AppColors.Text,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
                pressedContainerColor = Color(0xFFDDDDDD),
                pressedContentColor = Color.Black,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        ) {
            Row(Modifier.fillMaxHeight().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (atLive) AppColors.Live else Color(0xFF8A8A8A), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("LIVE", fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            if (atLive) "Watching live" else "Jump to live",
            fontSize = 12.sp,
            color = if (focused) AppColors.Text else Color.Transparent,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true),
        )
    }
}

/**
 * Movies, shows and recordings: Nuvio's controls. Title and episode on the left, a full-width
 * progress bar, then a row of icon buttons (play, next episode, subtitles, audio, sources,
 * episodes, more), the position on the right, and the clock with "Ends at" in the corner.
 */
@Composable
private fun VodControls(
    vm: AppViewModel,
    req: PlayRequest,
    now: Now,
    stream: StreamController,
    playFocus: FocusRequester,
    onPanel: (Panel) -> Unit,
    onPoke: () -> Unit,
    videoStats: Boolean,
    onVideoStats: () -> Unit,
) {
    val (btn, playBtn) = playerButtonSizes(vm.playerButtons)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = stream.player.currentPosition
            duration = stream.player.duration.coerceAtLeast(0)
            playing = stream.player.playWhenReady
            speed = stream.speed
            clock = System.currentTimeMillis()
            delay(500)
        }
    }
    val item = now.resumeItem
    val addon = item?.key?.let { AddonsModel.parseResumeKey(it) }
    val queue = req as? PlayRequest.Vod
    val hasNext = (queue != null && queue.index + 1 < queue.queue.size) || item?.next != null
    val addonEpisode = addon != null && addon.first != "movie" && addon.second != addon.third
    val hasEpisodes = (queue != null && queue.queue.size > 1) || addonEpisode
    // The add-on episode before this one, for Previous.
    var addonPrevious by remember(item?.key) { mutableStateOf<AddonNext?>(null) }
    if (addonEpisode) {
        LaunchedEffect(item.key) {
            val (type, metaId, videoId) = addon
            val meta = runCatching { vm.addons.meta(type, metaId) }.getOrNull() ?: return@LaunchedEffect
            addonPrevious = vm.addons.previousEpisode(meta, videoId, item.next?.bingeGroup)
        }
    }
    val hasPrevious = (queue != null && queue.index > 0) || addonPrevious != null
    var more by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(280.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))))

        // The clock, and when this will end at the current speed.
        Column(Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 44.dp), horizontalAlignment = Alignment.End) {
            Text(formatTime(clock), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (duration > 0) {
                val left = ((duration - position).coerceAtLeast(0) / speed.coerceAtLeast(0.25f)).toLong()
                Text("Ends at ${formatTime(clock + left)}", fontSize = 12.sp, color = Color(0xCCFFFFFF))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 22.dp)) {
            Text(now.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now.subtitle.isNotBlank()) {
                Text(now.subtitle, fontSize = 16.sp, color = Color(0xE6FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            ScrubBar(position, duration, stream, onPoke, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            CompositionLocalProvider(LocalButtonSize provides btn) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Top) {
                        // Restart; in the first seconds of an episode it goes to the previous one instead.
                        // One button whose action changes, so focus stays on it as the position moves.
                        val toPrevious = hasPrevious && position < PREVIOUS_WITHIN_MS
                        IconCircleButton(if (toPrevious) Icons.SkipPrevious else Icons.Restart, if (toPrevious) "Previous episode" else "Restart", {
                            if (toPrevious) {
                                if (item != null && duration > 0) vm.saveResume(item, position, duration)
                                val prev = addonPrevious
                                when {
                                    vm.previousVod() -> Unit
                                    prev != null -> vm.addons.playPrevious(prev)
                                }
                            } else {
                                stream.seekTo(0)
                                position = 0
                            }
                        }, transparent = true)
                        IconCircleButton(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", { stream.togglePause() },
                            Modifier.focusRequester(playFocus), size = playBtn, transparent = true)
                        if (hasNext) {
                            IconCircleButton(Icons.SkipNext, "Next episode", {
                                when {
                                    vm.nextVod() -> Unit
                                    item?.next != null -> {
                                        if (duration > 0) vm.saveResume(item, position, duration)
                                        vm.addons.playNext(item.next)
                                    }
                                }
                            }, transparent = true)
                        }
                        IconCircleButton(Icons.Subtitles, "Subtitles", { onPanel(Panel.Subtitles) }, active = vm.captions, transparent = true)
                        IconCircleButton(Icons.Audio, "Audio", { onPanel(Panel.Audio) }, transparent = true)
                        if (addon != null) IconCircleButton(Icons.Sources, "Sources", { onPanel(Panel.Sources) }, transparent = true)
                        if (hasEpisodes) IconCircleButton(Icons.Episodes, "Episodes", { onPanel(Panel.Episodes) }, transparent = true)
                        if (more) {
                            IconCircleButton(Icons.Speed, if (speed == 1f) "Speed" else "Speed ${speedLabel(speed)}", { onPanel(Panel.Speed) },
                                active = speed != 1f, transparent = true)
                            IconCircleButton(Icons.AspectRatio, if (stream.zoom) "Zoom to fill" else "Fit", { stream.zoom = !stream.zoom }, transparent = true)
                            IconCircleButton(Icons.Settings, "Settings", { onPanel(Panel.Settings) }, transparent = true)
                            IconCircleButton(Icons.VideoStats, "Video stats", onVideoStats, active = videoStats, transparent = true)
                        }
                        IconCircleButton(if (more) Icons.ChevronLeft else Icons.ChevronRight, if (more) "Less" else "More", { more = !more }, transparent = true)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${clockText(position)} / ${clockText(duration)}",
                        fontSize = 15.sp,
                        color = Color(0xE6FFFFFF),
                        modifier = Modifier.padding(top = (playBtn - 20.dp) / 2),
                    )
                }
            }
        }
    }
}

/** Previous episode only this early in an episode; later the button restarts it. */
private const val PREVIOUS_WITHIN_MS = 15_000L

private fun speedLabel(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"

/** Catch-up's seek bar: times on either side, a dot while focused. */
@Composable
private fun SeekBar(position: Long, duration: Long, stream: StreamController, onPoke: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(clockText(position), fontSize = 13.sp, color = AppColors.TextDim)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(20.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { ev -> seekKeys(ev, stream, onPoke) }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val p = if (duration > 0) position.toFloat() / duration else 0f
            ProgressLine(p, Modifier.height(if (focused) 6.dp else 3.dp))
            if (focused) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0.005f, 1f)), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.size(14.dp).background(AppColors.Live, CircleShape))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(clockText(duration), fontSize = 13.sp, color = AppColors.TextDim)
    }
}

/** Nuvio's progress bar: thin and white, thicker with a thumb while focused; ◀ ▶ scrub (faster when held). */
@Composable
private fun ScrubBar(position: Long, duration: Long, stream: StreamController, onPoke: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val p = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0) (stream.player.bufferedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .height(18.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev -> seekKeys(ev, stream, onPoke) }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = if (focused) 7.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(barHeight).background(Color(0x4DFFFFFF), RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(buffered).height(barHeight).background(Color(0x40FFFFFF), RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(p).height(barHeight).background(Color.White, RoundedCornerShape(4.dp)))
        if (focused) {
            Box(Modifier.fillMaxWidth(p.coerceIn(0.008f, 1f)), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(16.dp).background(Color.White, CircleShape))
            }
        }
    }
}

/** ◀ ▶ on a seek bar: 10 s steps, faster the longer the button is held; OK pauses. */
private fun seekKeys(ev: KeyEvent, stream: StreamController, onPoke: () -> Unit): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    val repeat = ev.nativeKeyEvent.repeatCount
    val step = when {
        repeat > 20 -> 120_000L
        repeat > 8 -> 60_000L
        repeat > 2 -> 30_000L
        else -> 10_000L
    }
    return when (ev.key) {
        Key.DirectionLeft -> { stream.seekBy(-step); onPoke(); true }
        Key.DirectionRight -> { stream.seekBy(step); onPoke(); true }
        in OK_KEYS -> { stream.togglePause(); true }
        else -> false
    }
}

@Composable
private fun MoreStrip(vm: AppViewModel, req: PlayRequest, onPoke: () -> Unit) {
    when (req) {
        is PlayRequest.Live -> {
            if (req.channels.size < 2) return
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (req.index - 1).coerceAtLeast(0))
            Text("Channels", fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 48.dp)) {
                itemsIndexed(req.channels, key = { _, c -> c.id }) { i, c ->
                    LaunchedEffect(c.id) { delay(300); vm.requestEpg(c) }
                    MiniCard(
                        title = vm.nowPlaying(c.id)?.title ?: cleanChannelName(c.name),
                        subtitle = cleanChannelName(c.name),
                        selected = i == req.index,
                        onFocus = onPoke,
                        onClick = { vm.zapTo(i) },
                    ) { ChannelLogo(c, 34.dp, background = Color.Transparent) }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun MiniCard(title: String, subtitle: String, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit, icon: @Composable () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(210.dp).height(54.dp).onFocusChanged { if (it.isFocused) onFocus() },
        containerColor = if (selected) Color(0x55FFFFFF) else Color(0x26FFFFFF),
        focusedContainerColor = Color(0x66FFFFFF),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 11.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Side panels
// ---------------------------------------------------------------------------------------------

@Composable
private fun StatsPanel(vm: AppViewModel, game: com.gameday.tv.data.Game) {
    var stats by remember { mutableStateOf<GameStats?>(null) }
    var reveal by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(game.id) {
        while (true) {
            runCatching { vm.gameStats(game) }.onSuccess { stats = it }
            delay(30_000)
        }
    }
    LaunchedEffect(Unit) { first.requestFocusSafely(150) }
    val hide = vm.hideScores && !reveal

    Column(Modifier.width(430.dp).fillMaxHeight().background(Color(0xF2181818)).padding(top = 24.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Stats", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(if (hide) game.title else statusLine(game, false), fontSize = 13.sp, color = AppColors.TextDim, maxLines = 1)
            }
            PillButton(if (hide) "Show" else "Game page", { if (hide) reveal = true else vm.openEventFromPlayer(game.id) }, Modifier.focusRequester(first))
        }
        Spacer(Modifier.height(10.dp))
        val s = stats
        when {
            hide -> Text("Scores are hidden. Select Show to see stats.", fontSize = 14.sp, color = AppColors.TextDim, modifier = Modifier.padding(20.dp))
            s == null -> LoadingState("Loading stats…")
            else -> {
                // The rows aren't focusable: the list itself takes focus and scrolls with Up/Down.
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .onKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (ev.key) {
                                Key.DirectionDown -> { scope.launch { listState.animateScrollBy(180f) }; true }
                                Key.DirectionUp -> {
                                    val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                                    if (!atTop) scope.launch { listState.animateScrollBy(-180f) }
                                    !atTop
                                }
                                else -> false
                            }
                        }
                        .focusable(),
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) { statsItems(s, game, padding = 20) }
            }
        }
    }
}

@Composable
private fun SettingsPanel(vm: AppViewModel, stream: StreamController, live: Boolean, subs: PlayerSubtitles, addonTitle: Boolean, onStyle: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(150) }
    val tracks = stream.tracks
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }

    LazyColumn(
        Modifier.width(400.dp).fillMaxHeight().background(Color(0xF2181818)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
    ) {
        item(key = "title") { PanelTitle("Playback settings") }
        item(key = "zoom") {
            SettingRow("Picture size", { stream.zoom = !stream.zoom }, Modifier.focusRequester(first), value = if (stream.zoom) "Zoom to fill" else "Fit")
        }
        item(key = "dec") {
            val mode = vm.decoderMode(DecoderSlot.PLAYER)
            SettingRow("Video decoding", { vm.setDecoderMode(DecoderSlot.PLAYER, mode.next()) },
                subtitle = decodingStatus(mode, stream.softwareDecoding), value = mode.label)
        }
        if (live) {
            item(key = "format") {
                SettingRow("Stream format", { vm.updateStreamFormat(if (vm.streamFormat == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS) },
                    subtitle = "Switch if this channel stutters or won't play", value = vm.streamFormat.label)
            }
        }
        item(key = "buttons") {
            val sizes = listOf("small", "medium", "large")
            SettingRow("Button size", { vm.updatePlayerButtons(sizes[(sizes.indexOf(vm.playerButtons) + 1) % sizes.size]) },
                value = vm.playerButtons.replaceFirstChar { it.uppercase() })
        }
        if (audio.size > 1) {
            item(key = "audio-h") { PanelHeader("Audio") }
            audioItems(stream)
        }
        item(key = "cc-h") { PanelHeader("Subtitles") }
        subtitleItems(vm, stream, subs, addonTitle, onStyle)
        val heights = video.flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }.filter { it > 0 }.distinct().sortedDescending()
        if (heights.size > 1) {
            item(key = "q-h") { PanelHeader("Quality") }
            item(key = "q-auto") {
                SettingRow("Auto", {
                    stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).build()
                })
            }
            heights.forEach { h ->
                item(key = "q$h") {
                    SettingRow("${h}p", {
                        stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE, h).build()
                    })
                }
            }
        }
    }
}

/** The audio tracks, to pick one. */
private fun LazyListScope.audioItems(stream: StreamController, first: Modifier = Modifier) {
    val audio = stream.tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    audio.forEachIndexed { i, g ->
        val f = g.getTrackFormat(0)
        item(key = "a$i") {
            SettingRow(
                f.label ?: f.language?.let { Subtitles.languageName(it) } ?: "Track ${i + 1}",
                {
                    stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                },
                if (i == 0) first else Modifier,
                subtitle = listOfNotNull(f.codecs, f.channelCount.takeIf { it > 0 }?.let { "$it ch" }).joinToString(" · ").ifBlank { null },
                checked = g.isSelected,
            )
        }
    }
}

/** Subtitles: off, the stream's own, then add-on files (preferred language first), timing and style. */
private fun LazyListScope.subtitleItems(
    vm: AppViewModel,
    stream: StreamController,
    subs: PlayerSubtitles,
    addonTitle: Boolean,
    onStyle: () -> Unit,
    first: Modifier = Modifier,
) {
    val text = stream.tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    item(key = "cc-off") { SettingRow("Off", { vm.updateCaptions(false) }, first, checked = !vm.captions) }
    text.forEachIndexed { i, g ->
        val f = g.getTrackFormat(0)
        item(key = "t$i") {
            SettingRow(
                f.label ?: f.language?.let { Subtitles.languageName(it) } ?: "Captions ${i + 1}",
                {
                    vm.updateCaptions(true)
                    subs.decided = true
                    subs.chosen = null
                    stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                },
                subtitle = "In the video",
                checked = vm.captions && subs.chosen == null && g.isSelected,
            )
        }
    }
    // Several files in one language are numbered: "English 2".
    val counts = HashMap<String, Int>()
    subs.tracks.forEach { t ->
        val n = (counts[t.language] ?: 0) + 1
        counts[t.language] = n
        item(key = "s:" + t.url) {
            SettingRow(
                if (n > 1) "${t.language} $n" else t.language,
                {
                    vm.updateCaptions(true)
                    subs.decided = true
                    subs.chosen = t
                },
                subtitle = if (subs.chosen == t && subs.loading) "Loading…" else t.addon,
                checked = vm.captions && subs.chosen == t,
            )
        }
    }
    when {
        subs.searching -> item(key = "cc-wait") { PanelNote("Looking for subtitles…") }
        addonTitle && !vm.addons.hasSubtitleAddons ->
            item(key = "cc-hint") { PanelNote("Add a subtitle add-on (like OpenSubtitles) in Settings › Add-ons for subtitles in more languages.") }
        text.isEmpty() && subs.tracks.isEmpty() -> item(key = "cc-none") { PanelNote("No subtitles for this video.") }
    }
    if (subs.chosen != null) {
        item(key = "cc-delay") {
            val d = subs.delayMs
            StepperRow(
                "Timing", if (d == 0L) "0 s" else "%+.2f s".format(d / 1000.0),
                onMinus = { subs.delayMs -= 250 }, onPlus = { subs.delayMs += 250 },
                subtitle = "+ shows them later, − earlier",
            )
        }
    }
    item(key = "cc-style") { SettingRow("Subtitle style", onStyle, subtitle = "Size, color, background, position, language", chevron = true) }
}

@Composable
private fun SubtitlesPanel(vm: AppViewModel, stream: StreamController, subs: PlayerSubtitles, addonTitle: Boolean, onStyle: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(150) }
    LazyColumn(Modifier.width(400.dp).fillMaxHeight().background(Color(0xF2181818)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)) {
        item(key = "title") { PanelTitle("Subtitles") }
        subtitleItems(vm, stream, subs, addonTitle, onStyle, Modifier.focusRequester(first))
    }
}

@Composable
private fun AudioPanel(stream: StreamController) {
    val first = remember { FocusRequester() }
    val audio = stream.tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    LaunchedEffect(audio.isEmpty()) { first.requestFocusSafely(150) }
    LazyColumn(Modifier.width(400.dp).fillMaxHeight().background(Color(0xF2181818)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)) {
        item(key = "title") { PanelTitle("Audio") }
        if (audio.isEmpty()) {
            item(key = "none") {
                Column {
                    PanelNote("This video has no other audio tracks.")
                    // Something focusable, so Back and the remote stay in the panel.
                    SettingRow("OK", {}, Modifier.focusRequester(first))
                }
            }
        } else {
            audioItems(stream, Modifier.focusRequester(first))
            if (audio.size == 1) item(key = "one") { PanelNote("This video has one audio track.") }
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun SpeedPanel(stream: StreamController) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(150) }
    var current by remember { mutableFloatStateOf(stream.speed) }
    LazyColumn(Modifier.width(360.dp).fillMaxHeight().background(Color(0xF2181818)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)) {
        item(key = "title") { PanelTitle("Playback speed") }
        SPEEDS.forEach { v ->
            item(key = "sp$v") {
                SettingRow(
                    if (v == 1f) "Normal" else speedLabel(v),
                    { stream.speed = v; current = v },
                    if (v == current) Modifier.focusRequester(first) else Modifier,
                    checked = v == current,
                )
            }
        }
    }
}

/**
 * Catch-up for a channel: back to live, start the current program over, or replay an earlier one
 * from the provider's archive.
 */
@Composable
private fun CatchUpPanel(vm: AppViewModel, channel: Channel, playing: com.gameday.tv.data.Program?, onDone: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(150) }
    LaunchedEffect(channel.id) { vm.requestEpg(channel) }
    val now = System.currentTimeMillis()
    val onNow = vm.nowPlaying(channel.id, now)
    val programs = vm.programsFor(channel.id)
        .filter { it.startMillis <= now && vm.catchupUrl(channel, it) != null }
        .sortedByDescending { it.startMillis }
        .take(48)
    LazyColumn(Modifier.width(430.dp).fillMaxHeight().background(Color(0xF2181818)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)) {
        item(key = "title") {
            Column(Modifier.padding(start = 12.dp, bottom = 10.dp)) {
                Text("Catch-up", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text("${cleanChannelName(channel.name)} · replays from the last ${channel.archiveDays} day${if (channel.archiveDays == 1) "" else "s"}",
                    fontSize = 13.sp, color = AppColors.TextDim)
            }
        }
        item(key = "live") {
            SettingRow(
                if (playing != null) "Back to live" else "Watching live",
                { if (playing != null) vm.goLive(); onDone() },
                Modifier.focusRequester(first),
                subtitle = onNow?.let { "Now: ${it.title}" },
                icon = Icons.LiveTv,
            )
        }
        if (programs.isEmpty()) {
            item(key = "none") { PanelNote("Nothing to replay yet. Programs show up here once the guide has loaded for this channel.") }
        } else {
            item(key = "h") { PanelHeader("Replay") }
        }
        items(programs, key = { it.key }) { p ->
            val isNow = p.isOnNow(now)
            SettingRow(
                if (isNow) "Start over: ${p.title}" else p.title,
                { vm.playCatchup(channel, p); onDone() },
                subtitle = "${formatDay(p.startMillis)} · ${formatRange(p.startMillis, p.endMillis)}",
                icon = if (isNow) Icons.Restart else Icons.History,
                checked = if (playing?.startMillis == p.startMillis) true else null,
            )
        }
    }
}

/**
 * Other sources for an add-on title (or, with [video], another episode), like Nuvio's: switching
 * keeps the position. [beforeSwitch] saves where the viewer is.
 */
@Composable
private fun SourcesPanel(vm: AppViewModel, item: VodItem, video: com.gameday.tv.data.MetaVideo?, beforeSwitch: () -> Unit, onDone: () -> Unit) {
    val ids = remember(item.key) { AddonsModel.parseResumeKey(item.key) }
    val first = remember { FocusRequester() }
    var meta by remember(item.key) { mutableStateOf<com.gameday.tv.data.MetaDetail?>(null) }
    var result by remember(item.key, video?.id) { mutableStateOf<StreamList?>(null) }
    LaunchedEffect(item.key, video?.id) {
        val (type, metaId, videoId) = ids ?: return@LaunchedEffect
        meta = runCatching { vm.addons.meta(type, metaId) }.getOrNull()
        result = runCatching { vm.addons.streams(type, video?.id ?: videoId) }.getOrDefault(StreamList(emptyList(), emptySet(), 0))
    }
    val r = result
    LaunchedEffect(r != null) { first.requestFocusSafely(120) }
    val target = video ?: ids?.let { (_, _, videoId) -> meta?.videos?.firstOrNull { it.id == videoId } }
    Column(Modifier.width(520.dp).fillMaxHeight().background(Color(0xF2181818)).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)) {
        Text(if (video != null) "S${video.season} E${video.episode} · ${video.title}" else "Sources", fontSize = 20.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (video != null) "Choose a source" else "Switch to another source — playback continues from here", fontSize = 13.sp, color = AppColors.TextDim)
        Spacer(Modifier.height(12.dp))
        vm.addons.resolving?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Spinner(20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Getting the stream from TorBox…", fontSize = 13.sp)
            }
        }
        val m = meta
        when {
            r == null -> {
                LoadingState("Asking your add-ons for sources…")
                PillButton("Cancel", onDone, Modifier.focusRequester(first))
            }
            r.streams.isEmpty() || m == null -> {
                Text("No other sources found right now.", fontSize = 14.sp, color = AppColors.TextDim)
                Spacer(Modifier.height(12.dp))
                PillButton("Back", onDone, Modifier.focusRequester(first))
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(r.streams, key = { i, s -> "$i:${s.url ?: s.infoHash}" }) { i, s ->
                    StreamRow(s, cached = s.infoHash != null && s.infoHash in r.cached, torbox = vm.addons.torboxConnected,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        if (vm.addons.resolving == null) {
                            beforeSwitch()
                            vm.addons.play(s, m, target)
                            onDone()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Episodes: the provider show's queue, or every episode of an add-on show (choosing one asks for
 * its sources).
 */
@Composable
private fun EpisodesPanel(
    vm: AppViewModel,
    req: PlayRequest,
    item: VodItem?,
    onPickAddonEpisode: (com.gameday.tv.data.MetaVideo) -> Unit,
    onDone: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val ids = remember(item?.key) { item?.key?.let { AddonsModel.parseResumeKey(it) } }
    var meta by remember(ids) { mutableStateOf<com.gameday.tv.data.MetaDetail?>(null) }
    var loaded by remember(ids) { mutableStateOf(ids == null) }
    LaunchedEffect(ids) {
        val (type, metaId, _) = ids ?: return@LaunchedEffect
        meta = runCatching { vm.addons.meta(type, metaId) }.getOrNull()
        loaded = true
    }
    val queue = req as? PlayRequest.Vod
    val currentId = ids?.third
    val videos = meta?.videos.orEmpty()
    val start = when {
        ids != null -> videos.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        queue != null -> queue.index
        else -> 0
    }
    val state = rememberLazyListState()
    // Open at the episode that's playing (the title is item 0, so this leaves one episode above it).
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        state.scrollToItem(start)
        first.requestFocusSafely(150)
    }
    LazyColumn(Modifier.width(500.dp).fillMaxHeight().background(Color(0xF2181818)), state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)) {
        item(key = "title") { PanelTitle("Episodes") }
        when {
            !loaded -> item(key = "wait") { LoadingState("Loading episodes…") }
            ids != null && videos.isNotEmpty() -> itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
                val now = v.id == currentId
                val resume = vm.resumeFor(AddonsModel.resumeKey(ids.first, ids.second, v.id))
                EpisodePanelRow(
                    title = "S${v.season} E${v.episode} · ${v.title}",
                    subtitle = when {
                        now -> "Playing now"
                        resume != null -> "${durationText(resume.durationMs - resume.positionMs)} left"
                        else -> v.released?.let { formatDate(it) }
                    },
                    image = v.thumbnail ?: meta?.preview?.background ?: meta?.preview?.poster,
                    progress = resume?.progress,
                    playing = now,
                    onClick = { if (now) onDone() else onPickAddonEpisode(v) },
                    modifier = if (i == start) Modifier.focusRequester(first) else Modifier,
                )
            }
            queue != null && queue.queue.size > 1 -> itemsIndexed(queue.queue, key = { _, e -> e.key }) { i, e ->
                val resume = vm.resumeFor(e.key)
                EpisodePanelRow(
                    title = e.subtitle,
                    subtitle = if (i == queue.index) "Playing now" else resume?.let { "${durationText(it.durationMs - it.positionMs)} left" },
                    image = e.image,
                    progress = resume?.progress,
                    playing = i == queue.index,
                    onClick = { vm.jumpVod(i); onDone() },
                    modifier = if (i == start) Modifier.focusRequester(first) else Modifier,
                )
            }
            else -> item(key = "none") {
                Column {
                    PanelNote("No other episodes.")
                    SettingRow("OK", onDone, Modifier.focusRequester(first))
                }
            }
        }
    }
}

/** An episode in the Episodes panel: its thumbnail (with progress) beside the title. */
@Composable
private fun EpisodePanelRow(
    title: String,
    subtitle: String?,
    image: String?,
    progress: Float?,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        focusedScale = 1.02f,
        containerColor = if (playing) Color(0x26FFFFFF) else Color.Transparent,
        focusedContainerColor = Color(0xFFF1F1F1),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(144.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                PosterThumb(image, title)
                if (playing) {
                    Box(Modifier.fillMaxSize().background(Color(0x80000000)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Play, null, Modifier.size(28.dp), tint = Color.White)
                    }
                }
                if (progress != null && !playing) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (focused) Color.Black else AppColors.Text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 12.sp, color = if (focused) Color(0xFF444444) else AppColors.TextDim, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
}

@Composable
private fun PanelNote(text: String) {
    Text(text, fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** Subtitle look, with a live preview; Back returns to the settings panel. */
@Composable
private fun SubtitleStylePanel(vm: AppViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(150) }
    Column(Modifier.width(400.dp).fillMaxHeight().background(Color(0xF2181818)).padding(horizontal = 16.dp, vertical = 24.dp)) {
        Text("Subtitle style", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
        SubtitlePreview(vm.subtitleStyle)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.focusRequester(first), contentPadding = PaddingValues(bottom = 24.dp)) { subtitleStyleItems(vm, "p") }
    }
}

@Composable
private fun PanelHeader(text: String) {
    Text(text, fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
}
