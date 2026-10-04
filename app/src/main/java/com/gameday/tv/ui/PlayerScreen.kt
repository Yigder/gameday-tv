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

private enum class Panel { None, Stats, Settings, SubtitleStyle, CatchUp, Subtitles, Audio, Speed }

/** What the player is showing, flattened from the [PlayRequest]. */
private data class Now(
    val key: String,
    val urls: List<String>,
    val title: String,
    val subtitle: String,
    val channel: Channel?,
    val startAt: Long,
    val resumeItem: VodItem?,
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
        // Speed is for recordings; live TV (and what plays behind the menus) is always 1×.
        if (req !is PlayRequest.Rec) stream.speed = 1f
        stream.load(now.key, now.urls, now.startAt)
        if (req is PlayRequest.Live) now.channel?.let { vm.noteRecent(it) }
        vm.noteWatching(if (req is PlayRequest.Live) now.channel else null)
    }
    // ---- captions: the stream's own ----
    val resumeItem = now.resumeItem
    val subs = remember(now.key) { PlayerSubtitles() }
    LaunchedEffect(vm.captions, stream, vm.subtitleLanguage) {
        stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(vm.subtitleLanguage)
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !vm.captions).build()
    }
    // Captions on: use the stream's captions in the chosen language.
    LaunchedEffect(vm.captions, stream.tracks, vm.subtitleLanguage) {
        if (!vm.captions || subs.decided) return@LaunchedEffect
        val embedded = stream.tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val ownMatch = embedded.firstOrNull { g -> Subtitles.sameLanguage(g.getTrackFormat(0).language, vm.subtitleLanguage) } ?: return@LaunchedEffect
        subs.decided = true
        stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(ownMatch.mediaTrackGroup, 0)).build()
    }

    DisposableEffect(stream) { onDispose { stream.speed = 1f } }

    // ---- remember where recordings were left ----
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
        vm.back()
    }

    // ---- sports context: the event we came from, or one this channel looks like it's showing ----
    val liveChannel = (req as? PlayRequest.Live)?.current
    // RedZone jumps between every NFL game, so it gets a bug for each live one instead of one game's.
    val redZone = vm.isRedZone(liveChannel)
    val liveGame = remember(req, vm.games) {
        if (redZone) null
        else (req as? PlayRequest.Live)?.eventId?.let { vm.gameById(it) } ?: liveChannel?.let { vm.liveGameFor(it) }
    }
    val liveTournament = remember(req, vm.tournaments) {
        if (liveGame != null || redZone) null
        else (req as? PlayRequest.Live)?.eventId?.let { vm.tournamentById(it) } ?: liveChannel?.let { vm.liveTournamentFor(it) }
    }
    // The score bug follows the stream, which runs behind the live scoreboard.
    val linkedGame = vm.delayedGame(liveGame)
    val linkedTournament = vm.delayedTournament(liveTournament)
    val redZoneGames = if (redZone) vm.liveNflGames.mapNotNull { vm.delayedGame(it) } else emptyList()
    val bug = rememberBugState(
        key = now.key,
        scoreKey = if (redZone) redZoneGames.joinToString("|") { "${it.id}:${scoreKeyOf(it, null)}" }.ifEmpty { null }
            else scoreKeyOf(linkedGame, linkedTournament),
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
    var okLongPressed by remember { mutableStateOf(false) }
    // Video stats stay up (over the video) until turned off, like YouTube's "stats for nerds".
    var videoStats by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    fun poke() { nonce++ }
    fun showControls() { controls = true; poke() }

    // Hold Back (live TV): the guide, channels and games over the video, which keeps playing.
    var guide by remember { mutableStateOf(false) }
    val guideAvailable = req is PlayRequest.Live || req is PlayRequest.Catchup
    DisposableEffect(guideAvailable) {
        val open: () -> Unit = {
            panel = Panel.None
            controls = false
            guide = true
        }
        if (guideAvailable) BackHold.action = open
        onDispose { if (BackHold.action === open) BackHold.action = null }
    }

    LaunchedEffect(now.key) { if (!guide) showControls() }
    LaunchedEffect(nonce, controls, panel) {
        if (!controls || panel != Panel.None) return@LaunchedEffect
        delay(6_000)
        controls = false
    }
    LaunchedEffect(controls, panel, guide) {
        when {
            guide || panel != Panel.None -> Unit
            controls -> playFocus.requestFocusSafely(40)
            else -> rootFocus.requestFocusSafely(40)
        }
    }
    BackHandler(enabled = panel != Panel.None) {
        when {
            panel == Panel.SubtitleStyle -> panel = styleFrom
            else -> { panel = Panel.None; showControls() }
        }
    }
    BackHandler(enabled = panel == Panel.None && controls) { controls = false }
    // Declared last, so it goes first.
    BackHandler(enabled = guide) { guide = false }

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
                if (panel != Panel.None || guide) return@onKeyEvent false
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
                // Controls hidden: OK shows them (and the score); hold OK opens Multiview with this
                // channel carrying on, like TiviMate.
                if (ev.key in OK_KEYS) {
                    when {
                        ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0 -> okLongPressed = false
                        ev.type == KeyEventType.KeyDown && ev.isLongPressRepeat() && channel != null && live -> {
                            okLongPressed = true
                            OkKeyGate.swallowRelease()
                            vm.quickMultiview(channel)
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
                            linkedGame != null || linkedTournament != null || redZoneGames.isNotEmpty() -> bug.toggle()
                            redZone -> vm.showMessage("No NFL games are live right now")
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
                    onLongPress = { if (channel != null && live) vm.quickMultiview(channel) },
                )
            },
    ) {
        // Captions are drawn by SubtitleOverlay (in the viewer's style), not by the video view.
        VideoSurface(stream, Modifier.fillMaxSize(), showSubtitles = false)
        if (vm.captions) {
            val own = stream.cues
            SubtitleOverlay(lines = textOfCues(own), bitmaps = own.filter { it.bitmap != null }, style = vm.subtitleStyle)
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
        RedZoneBugs(redZoneGames, bug, Modifier.align(Alignment.TopEnd).padding(top = bugTop, start = 32.dp, end = 32.dp))

        // Remember the picture each channel really plays at, so its streams sort by it next time.
        if (req is PlayRequest.Live && channel != null) NoteStreamQuality(vm, channel, stream)

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
            if (req is PlayRequest.Rec) {
                // Recordings: a seekable player.
                RecordingControls(vm, now, stream, playFocus, onPanel = { panel = it }, onPoke = ::poke,
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
            SettingsPanel(vm, stream, live, subs, onStyle = { styleFrom = Panel.Settings; panel = Panel.SubtitleStyle })
        }
        SidePanel(panel == Panel.Subtitles) {
            SubtitlesPanel(vm, stream, subs, onStyle = { styleFrom = Panel.Subtitles; panel = Panel.SubtitleStyle })
        }
        SidePanel(panel == Panel.Audio) { AudioPanel(stream) }
        SidePanel(panel == Panel.Speed) { SpeedPanel(stream) }
        SidePanel(panel == Panel.CatchUp && channel != null) {
            channel?.let { ch ->
                CatchUpPanel(vm, ch, (req as? PlayRequest.Catchup)?.program, onDone = { panel = Panel.None })
            }
        }
        SidePanel(panel == Panel.SubtitleStyle) { SubtitleStylePanel(vm) }

        AnimatedVisibility(
            visible = guide,
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(240)) { -it / 8 },
            exit = fadeOut(tween(160)),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerGuide(vm, channel, live, onClose = { guide = false })
        }
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
    var more by remember { mutableStateOf(false) }

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
                if (hasStats) IconCircleButton(Icons.Stats, "Game stats", { onPanel(Panel.Stats) })
                IconCircleButton(Icons.Captions, if (vm.captions) "Captions on" else "Captions off", {
                    val on = !vm.captions
                    vm.updateCaptions(on)
                    val any = stream.tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
                    if (on && !any) vm.showMessage("No captions found for this channel")
                }, active = vm.captions)
                // Everything else waits behind More.
                if (more) {
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
                        val fav = vm.isFavoriteChannel(channel.id)
                        IconCircleButton(Icons.Star, if (fav) "Favorite" else "Add favorite", { vm.toggleFavoriteChannel(channel) }, active = fav)
                    }
                    IconCircleButton(Icons.VideoStats, "Video stats", onVideoStats, active = videoStats)
                    IconCircleButton(Icons.Settings, "Settings", { onPanel(Panel.Settings) })
                }
                IconCircleButton(if (more) Icons.ChevronLeft else Icons.ChevronRight, if (more) "Less" else "More", { more = !more })
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
 * Recordings: title on the left, a full-width progress bar, then a row of icon buttons (restart,
 * play, subtitles, audio, more), the position on the right, and the clock with "Ends at" in the corner.
 */
@Composable
private fun RecordingControls(
    vm: AppViewModel,
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
                        IconCircleButton(Icons.Restart, "Restart", {
                            stream.seekTo(0)
                            position = 0
                        }, transparent = true)
                        IconCircleButton(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", { stream.togglePause() },
                            Modifier.focusRequester(playFocus), size = playBtn, transparent = true)
                        IconCircleButton(Icons.Subtitles, "Subtitles", { onPanel(Panel.Subtitles) }, active = vm.captions, transparent = true)
                        IconCircleButton(Icons.Audio, "Audio", { onPanel(Panel.Audio) }, transparent = true)
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

/** The recordings progress bar: thin and white, thicker with a thumb while focused; ◀ ▶ scrub (faster when held). */
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

/** Records the resolution and frame rate [channel] actually plays at, once they've held for a few seconds. */
@Composable
private fun NoteStreamQuality(vm: AppViewModel, channel: Channel, stream: StreamController) {
    LaunchedEffect(channel.id, stream.loadToken) {
        // Give the frame count a few seconds to settle, then keep it current while the channel plays.
        delay(5_000)
        while (true) {
            val q = stream.quality()
            if (q.height > 0 && q.fps > 0f && stream.currentKey == channel.id) vm.noteStreamQuality(channel, q)
            delay(10_000)
        }
    }
}

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
private fun SettingsPanel(vm: AppViewModel, stream: StreamController, live: Boolean, subs: PlayerSubtitles, onStyle: () -> Unit) {
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
                subtitle = decodingStatus(mode), value = mode.label)
        }
        if (live) {
            item(key = "scoreDelay") {
                SliderRow("Score delay", vm.scoreDelaySec, SCORE_DELAY_RANGE, SCORE_DELAY_STEP, vm::updateScoreDelay,
                    subtitle = "How long the score bug waits, to match this stream", label = ::delayLabel)
            }
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
        subtitleItems(vm, stream, subs, onStyle)
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

/** Subtitles: off, the stream's own captions, and style. */
private fun LazyListScope.subtitleItems(
    vm: AppViewModel,
    stream: StreamController,
    subs: PlayerSubtitles,
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
                    stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                },
                subtitle = "In the video",
                checked = vm.captions && g.isSelected,
            )
        }
    }
    if (text.isEmpty()) item(key = "cc-none") { PanelNote("No subtitles for this video.") }
    item(key = "cc-style") { SettingRow("Subtitle style", onStyle, subtitle = "Size, color, background, position, language", chevron = true) }
}

@Composable
private fun SubtitlesPanel(vm: AppViewModel, stream: StreamController, subs: PlayerSubtitles, onStyle: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(150) }
    LazyColumn(Modifier.width(400.dp).fillMaxHeight().background(Color(0xF2181818)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)) {
        item(key = "title") { PanelTitle("Subtitles") }
        subtitleItems(vm, stream, subs, onStyle, Modifier.focusRequester(first))
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
