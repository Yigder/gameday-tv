package com.gameday.tv.mobile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import com.gameday.tv.data.Channel
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.DecoderSlot
import com.gameday.tv.ui.EventBug
import com.gameday.tv.ui.Icons
import com.gameday.tv.ui.PlayRequest
import com.gameday.tv.ui.ProgressLine
import com.gameday.tv.ui.RedZoneBugs
import com.gameday.tv.ui.StreamController
import com.gameday.tv.ui.SubtitleOverlay
import com.gameday.tv.ui.VideoSurface
import com.gameday.tv.ui.VodItem
import com.gameday.tv.ui.cleanChannelName
import com.gameday.tv.ui.clockText
import com.gameday.tv.ui.formatDay
import com.gameday.tv.ui.formatRange
import com.gameday.tv.ui.minutesLeft
import com.gameday.tv.ui.rememberBugState
import com.gameday.tv.ui.scoreKeyOf
import com.gameday.tv.ui.textOfCues
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

/** What the player is showing, flattened from the [PlayRequest]. */
private data class Playing(
    val key: String,
    val urls: List<String>,
    val title: String,
    val subtitle: String,
    val channel: Channel?,
    val startAt: Long,
    val resumeItem: VodItem?,
)

private fun describe(vm: AppViewModel, req: PlayRequest): Playing = when (req) {
    is PlayRequest.Live -> {
        val ch = req.current!!
        Playing(ch.id, vm.streamCandidates(ch), cleanChannelName(ch.name), ch.group, ch, 0, null)
    }
    is PlayRequest.Catchup -> Playing(
        "cu:${req.channel.id}@${req.program.startMillis}", listOf(req.url), req.program.title,
        "${cleanChannelName(req.channel.name)} · ${formatDay(req.program.startMillis)} ${formatRange(req.program.startMillis, req.program.endMillis)}",
        req.channel, 0, null,
    )
    is PlayRequest.Rec -> {
        val r = req.recording
        val item = VodItem("rec:${r.id}", r.title, "Recorded ${formatDay(r.startMillis)} · ${cleanChannelName(r.channelName)}", r.image,
            Uri.fromFile(File(r.file!!)).toString())
        Playing(item.key, listOf(item.url), r.title, item.subtitle, null, vm.resumeFor(item.key)?.positionMs ?: 0, item)
    }
}

private enum class Panel { None, Channels, Options }

/**
 * Full-screen player for phones: tap for controls, swipe left or right to change channel,
 * double-tap the sides to skip in recordings, and picture-in-picture when you leave the app.
 */
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val req = vm.playback
    if (req == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MessageBlock("Nothing to play") { Button(onClick = { vm.back() }) { Text("Back") } }
        }
        return
    }
    val playing = remember(req) { describe(vm, req) }
    // The shared player: a channel already playing in the mini player continues without restarting.
    val stream = vm.mainStream
    val decoding = vm.decoderMode(DecoderSlot.PLAYER)
    LaunchedEffect(stream, decoding) { stream.applyDecoderMode(decoding) }
    LaunchedEffect(playing.key, playing.urls) {
        stream.volume = 1f
        if (req !is PlayRequest.Rec) stream.speed = 1f
        stream.load(playing.key, playing.urls, playing.startAt)
        if (req is PlayRequest.Live) playing.channel?.let { vm.noteRecent(it) }
        vm.noteWatching(if (req is PlayRequest.Live) playing.channel else null)
    }
    // Captions: the stream's own, in the chosen language.
    LaunchedEffect(vm.captions, stream, vm.subtitleLanguage) {
        stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(vm.subtitleLanguage)
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !vm.captions).build()
    }
    DisposableEffect(stream) { onDispose { stream.speed = 1f } }

    // Remember where recordings were left.
    val resumeItem = playing.resumeItem
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

    // Sports context: the event we came from, or one this channel looks like it's showing.
    val live = req is PlayRequest.Live
    val liveReq = req as? PlayRequest.Live
    val channel = playing.channel
    val redZone = vm.isRedZone(liveReq?.current)
    val liveGame = remember(req, vm.games) {
        if (redZone) null else liveReq?.eventId?.let { vm.gameById(it) } ?: liveReq?.current?.let { vm.liveGameFor(it) }
    }
    val liveTournament = remember(req, vm.tournaments) {
        if (liveGame != null || redZone) null
        else liveReq?.eventId?.let { vm.tournamentById(it) } ?: liveReq?.current?.let { vm.liveTournamentFor(it) }
    }
    // The score bug follows the stream, which runs behind the live scoreboard.
    val linkedGame = vm.delayedGame(liveGame)
    val linkedTournament = vm.delayedTournament(liveTournament)
    val redZoneGames = if (redZone) vm.liveNflGames.mapNotNull { vm.delayedGame(it) } else emptyList()
    val bug = rememberBugState(
        key = playing.key,
        scoreKey = if (redZone) redZoneGames.joinToString("|") { "${it.id}:${scoreKeyOf(it, null)}" }.ifEmpty { null }
            else scoreKeyOf(linkedGame, linkedTournament),
        mode = vm.scoreBugMode,
        popOnScore = vm.scoreAlerts && !vm.hideScores,
        startVisible = !vm.hideScores,
    )
    val hasScore = linkedGame != null || linkedTournament != null || redZoneGames.isNotEmpty()
    if (live && channel != null) NoteStreamQuality(vm, channel, stream)

    // Controls hide after a few seconds without a touch.
    var controls by remember { mutableStateOf(true) }
    var nonce by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(Panel.None) }
    fun poke() { nonce++ }
    fun showControls() { controls = true; poke() }
    LaunchedEffect(playing.key) { showControls() }
    LaunchedEffect(nonce, controls, panel) {
        if (!controls || panel != Panel.None) return@LaunchedEffect
        delay(4_500)
        controls = false
    }
    BackHandler(enabled = panel != Panel.None) { panel = Panel.None }

    val inPip = LocalInPip.current
    val multipleChannels = (liveReq?.channels?.size ?: 0) > 1
    val swipeThreshold = with(LocalDensity.current) { 90.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(playing.key) {
                detectTapGestures(
                    onTap = { if (panel != Panel.None) panel = Panel.None else if (controls) controls = false else showControls() },
                    onDoubleTap = { pos ->
                        if (stream.canSeek) {
                            stream.seekBy(if (pos.x < size.width / 2) -10_000 else 30_000)
                            showControls()
                        }
                    },
                    onLongPress = { if (channel != null && live) vm.quickMultiview(channel) },
                )
            }
            .pointerInput(playing.key, multipleChannels) {
                if (!multipleChannels) return@pointerInput
                // Swipe across the picture: next or previous channel in the list.
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dx -> total += dx },
                    onDragEnd = { if (abs(total) > swipeThreshold) vm.zap(if (total < 0) +1 else -1) },
                )
            },
    ) {
        VideoSurface(stream, Modifier.fillMaxSize(), showSubtitles = false)
        if (vm.captions) {
            val own = stream.cues
            SubtitleOverlay(lines = textOfCues(own), bitmaps = own.filter { it.bitmap != null }, style = vm.subtitleStyle,
                scale = if (inPip) 0.4f else 0.7f, sidePadding = 24.dp)
        }

        if (stream.buffering && stream.error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AppColors.Live)
                val note = when {
                    stream.reconnecting -> "Stream dropped — reconnecting…"
                    stream.attempt > 0 -> "Trying alternate stream format…"
                    else -> null
                }
                if (note != null && !inPip) {
                    Spacer(Modifier.height(10.dp))
                    Text(note, fontSize = 13.sp, color = AppColors.TextDim)
                }
            }
        }

        stream.error?.let { msg ->
            if (inPip) return@let
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 460.dp).padding(24.dp)
                    .background(Color(0xF0212121), RoundedCornerShape(12.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play ${playing.title}", fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(msg, fontSize = 13.sp, color = AppColors.TextDim, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { stream.retry() }) { Text("Try again") }
                    if (multipleChannels) OutlinedButton(onClick = { vm.zap(+1) }) { Text("Next channel") }
                }
            }
        }

        val bugPadding = if (controls && !inPip && panel == Panel.None) Modifier.padding(top = 64.dp, end = 16.dp) else Modifier.padding(top = 12.dp, end = 12.dp)
        EventBug(linkedGame, linkedTournament, bug, Modifier.align(Alignment.TopEnd).safeDrawingPadding().then(bugPadding), compact = true)
        RedZoneBugs(redZoneGames, bug, Modifier.align(Alignment.TopEnd).safeDrawingPadding().then(bugPadding).padding(start = 12.dp))

        if (!inPip) {
            AnimatedVisibility(visible = controls && panel == Panel.None, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Controls(
                    vm = vm,
                    req = req,
                    playing = playing,
                    stream = stream,
                    hasScore = hasScore,
                    onScore = { bug.toggle(); poke() },
                    onPanel = { panel = it },
                    onPoke = ::poke,
                )
            }
            SidePanel(panel == Panel.Channels && liveReq != null) {
                liveReq?.let { ChannelsPanel(vm, it) }
            }
            SidePanel(panel == Panel.Options) { OptionsPanel(vm, stream, isRecording = req is PlayRequest.Rec) }
        }
    }
}

@Composable
private fun BoxScope.SidePanel(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        Box(
            Modifier.width(340.dp).fillMaxHeight().background(Color(0xF2181818))
                // Taps inside the panel stay in it.
                .pointerInput(Unit) { detectTapGestures { } }
                .safeDrawingPadding(),
        ) { content() }
    }
}

@Composable
private fun Controls(
    vm: AppViewModel,
    req: PlayRequest,
    playing: Playing,
    stream: StreamController,
    hasScore: Boolean,
    onScore: () -> Unit,
    onPanel: (Panel) -> Unit,
    onPoke: () -> Unit,
) {
    val live = req is PlayRequest.Live
    val liveReq = req as? PlayRequest.Live
    val channel = playing.channel
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var behindLive by remember { mutableStateOf(false) }
    LaunchedEffect(stream) {
        while (true) {
            position = stream.player.currentPosition
            duration = stream.player.duration.coerceAtLeast(0)
            isPlaying = stream.player.playWhenReady
            behindLive = stream.isBehindLive()
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xB3000000), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color(0xCC000000)))) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            // ---- top: back, title, actions ----
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.back() }) { AppIcon(Icons.Back, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(playing.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(playing.subtitle, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hasScore) TopAction(Icons.Trophy, "Score", onScore)
                TopAction(Icons.Captions, if (vm.captions) "Turn off subtitles" else "Turn on subtitles", { vm.updateCaptions(!vm.captions); onPoke() },
                    tint = if (vm.captions) AppColors.Text else AppColors.TextFaint)
                if (live && channel != null) TopAction(Icons.Multiview, "Multiview", { vm.quickMultiview(channel) })
                if (liveReq != null && liveReq.channels.size > 1) TopAction(Icons.Guide, "Channels", { onPanel(Panel.Channels) })
                TopAction(Icons.Settings, "Options", { onPanel(Panel.Options) })
            }

            // ---- middle: previous / play-pause / next, or skip back / forward ----
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    stream.canSeek -> RoundButton(Icons.Replay, "Back 10 seconds", 48.dp) { stream.seekBy(-10_000); onPoke() }
                    liveReq != null && liveReq.channels.size > 1 -> RoundButton(Icons.ChevronLeft, "Previous channel", 48.dp) { vm.zap(-1) }
                    else -> Spacer(Modifier.size(48.dp))
                }
                RoundButton(if (isPlaying) Icons.Pause else Icons.Play, if (isPlaying) "Pause" else "Play", 68.dp) {
                    stream.togglePause()
                    isPlaying = !isPlaying
                    onPoke()
                }
                when {
                    stream.canSeek -> RoundButton(Icons.ForwardArrow, "Forward 30 seconds", 48.dp) { stream.seekBy(30_000); onPoke() }
                    liveReq != null && liveReq.channels.size > 1 -> RoundButton(Icons.ChevronRight, "Next channel", 48.dp) { vm.zap(+1) }
                    else -> Spacer(Modifier.size(48.dp))
                }
            }

            // ---- bottom: progress, or what's on and the LIVE button ----
            Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (stream.seekable && duration > 0) {
                    var dragging by remember { mutableStateOf(false) }
                    var dragValue by remember { mutableFloatStateOf(0f) }
                    Slider(
                        value = if (dragging) dragValue else position.toFloat() / duration,
                        onValueChange = { dragging = true; dragValue = it; onPoke() },
                        onValueChangeFinished = {
                            stream.seekTo((dragValue * duration).toLong())
                            dragging = false
                        },
                        colors = SliderDefaults.colors(thumbColor = AppColors.Live, activeTrackColor = AppColors.Live, inactiveTrackColor = Color(0x55FFFFFF)),
                    )
                    Row {
                        Text(clockText(if (dragging) (dragValue * duration).toLong() else position), fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(clockText(duration), fontSize = 12.sp, color = AppColors.TextDim)
                    }
                    if (req is PlayRequest.Catchup) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { vm.goLive() }) { Text("Go to live TV") }
                    }
                } else {
                    val program = channel?.let { vm.nowPlaying(it.id) }
                    val now = System.currentTimeMillis()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .background(if (behindLive) Color(0x33FFFFFF) else AppColors.LiveBadge, RoundedCornerShape(4.dp))
                                .clickable(enabled = behindLive) { stream.goToLiveEdge(); onPoke() }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text(if (behindLive) "GO LIVE" else "LIVE", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        if (program != null) {
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(program.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProgressLine(program.progress(now), Modifier.width(120.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(minutesLeft(program.endMillis, now), fontSize = 11.sp, color = AppColors.TextDim)
                                }
                            }
                            if (vm.catchupUrl(channel, program) != null) {
                                OutlinedButton(onClick = { vm.playCatchup(channel, program) }) { Text("Start over") }
                            }
                        } else if (liveReq != null && liveReq.channels.size > 1) {
                            Spacer(Modifier.width(12.dp))
                            Text("Swipe to change channel · ${liveReq.index + 1} of ${liveReq.channels.size}", fontSize = 12.sp, color = AppColors.TextDim)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopAction(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = AppColors.Text) {
    IconButton(onClick = onClick) { AppIcon(icon, description, tint = tint) }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).background(Color(0x66000000), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { AppIcon(icon, description, size = size * 0.55f) }
}

/** The channel list being played (a game's matched channels, a guide filter): tap to switch. */
@Composable
private fun ChannelsPanel(vm: AppViewModel, req: PlayRequest.Live) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (req.index - 2).coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        Text("Channels", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(16.dp))
        LazyColumn(state = state) {
            itemsIndexed(req.channels, key = { _, c -> c.id }) { i, c ->
                ChannelListRow(vm, c, req.channels, selected = i == req.index, onClick = { vm.zapTo(i) })
            }
        }
    }
}

@Composable
private fun OptionsPanel(vm: AppViewModel, stream: StreamController, isRecording: Boolean) {
    val audio = stream.tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Options", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(16.dp)) }
        item {
            SettingItem("Fill the screen", { stream.zoom = !stream.zoom }, subtitle = "Zoom to fill a wide phone screen (crops the edges)", checked = stream.zoom)
        }
        item { SettingItem("Subtitles", { vm.updateCaptions(!vm.captions) }, checked = vm.captions) }
        if (isRecording) {
            item {
                val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                val next = speeds[(speeds.indexOf(stream.speed).coerceAtLeast(0) + 1) % speeds.size]
                SettingItem("Speed", { stream.speed = next }, value = if (stream.speed % 1f == 0f) "${stream.speed.toInt()}×" else "${stream.speed}×")
            }
        }
        if (audio.size > 1) {
            item { SectionHeader("Audio") }
            itemsIndexed(audio) { i, g ->
                val f = g.getTrackFormat(0)
                val name = listOfNotNull(f.label, f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }).firstOrNull() ?: "Track ${i + 1}"
                SettingItem(name, {
                    stream.player.trackSelectionParameters = stream.player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                }, checked = g.isSelected)
            }
        }
        item { SectionHeader("Video decoding") }
        item {
            val mode = vm.decoderMode(DecoderSlot.PLAYER)
            SettingItem("Decoder", { vm.setDecoderMode(DecoderSlot.PLAYER, mode.next()) },
                subtitle = "Hardware is smoothest. Try Software if a channel shows a decoder error.", value = mode.label)
        }
        item {
            SettingItem("Live stream format", { vm.updateStreamFormat(vm.streamFormat.other()) },
                subtitle = "Applies to the next channel you open", value = vm.streamFormat.label)
        }
    }
}

/** Records the resolution and frame rate [channel] actually plays at, so its streams sort by it next time. */
@Composable
private fun NoteStreamQuality(vm: AppViewModel, channel: Channel, stream: StreamController) {
    LaunchedEffect(channel.id, stream.loadToken) {
        delay(5_000)
        while (true) {
            val q = stream.quality()
            if (q.height > 0 && q.fps > 0f && stream.currentKey == channel.id) vm.noteStreamQuality(channel, q)
            delay(10_000)
        }
    }
}
