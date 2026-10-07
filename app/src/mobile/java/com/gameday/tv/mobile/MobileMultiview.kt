package com.gameday.tv.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gameday.tv.data.Channel
import com.gameday.tv.data.MultiviewLayout
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.ChannelLogo
import com.gameday.tv.ui.DecoderSlot
import com.gameday.tv.ui.EventBug
import com.gameday.tv.ui.Icons
import com.gameday.tv.ui.IptvStatus
import com.gameday.tv.ui.RedZoneBugs
import com.gameday.tv.ui.StreamController
import com.gameday.tv.ui.Suggestion
import com.gameday.tv.ui.VideoSurface
import com.gameday.tv.ui.WatchStalls
import com.gameday.tv.ui.cleanChannelName
import com.gameday.tv.ui.rememberBugState
import com.gameday.tv.ui.scoreKeyOf
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Each channel's player, kept by channel rather than by screen, so changing the layout carries on
 * playing. A channel that came from full screen keeps using the shared player.
 */
private class TileStreams(private val context: android.content.Context, private val vm: AppViewModel) {
    private val own = HashMap<String, StreamController>()

    fun forChannel(channel: Channel): StreamController =
        if (vm.multiviewUsesMain(channel)) vm.mainStream
        else own.getOrPut(channel.id) { StreamController(context, handleAudioFocus = false) }

    fun isShared(stream: StreamController): Boolean = vm.hasMainStream && stream === vm.mainStream

    fun keepOnly(ids: Set<String>) {
        own.keys.filter { it !in ids }.forEach { own.remove(it)?.release() }
        if (vm.multiviewMainId?.let { it !in ids } == true) vm.releaseMultiviewMain()
    }

    fun forEachOwn(block: (StreamController) -> Unit) = own.values.forEach(block)

    fun releaseAll() {
        own.values.forEach { it.release() }
        own.clear()
    }
}

@Composable
private fun rememberTileStreams(vm: AppViewModel): TileStreams {
    val context = LocalContext.current
    val streams = remember { TileStreams(context, vm) }
    val ids = (0 until vm.multiviewLayout.screens).mapNotNull { vm.multiview[it]?.id }.toSet()
    LaunchedEffect(ids) { streams.keepOnly(ids) }
    DisposableEffect(Unit) {
        onDispose {
            streams.releaseAll()
            if (vm.hasMainStream) vm.mainStream.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> streams.forEachOwn { it.onAppStopped() }
                Lifecycle.Event.ON_START -> streams.forEachOwn { it.onAppStarted() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return streams
}

private sealed interface Sheet {
    data class Picker(val slot: Int) : Sheet
    data class Options(val slot: Int) : Sheet
}

/** Up to four channels at once. Tap a screen to hear it; long-press for its options. */
@Composable
fun MultiviewScreen(vm: AppViewModel) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var bar by remember { mutableStateOf(true) }
    var barNonce by remember { mutableIntStateOf(0) }
    val layout = vm.multiviewLayout
    val streams = rememberTileStreams(vm)
    val inPip = LocalInPip.current

    BackHandler(enabled = sheet == null) { vm.leaveMultiview() }
    BackHandler(enabled = sheet != null) { sheet = null }
    LaunchedEffect(Unit) {
        val pick = vm.multiviewPickSlot
        vm.multiviewPickSlot = null
        when {
            pick != null -> sheet = Sheet.Picker(pick)
            vm.multiviewCount == 0 -> sheet = Sheet.Picker(0)
        }
    }
    LaunchedEffect(barNonce, bar) {
        if (!bar) return@LaunchedEffect
        delay(4_000)
        bar = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            MultiviewGrid(layout, gap = 3.dp) { slot, modifier ->
                key(layout, slot) {
                    Tile(
                        vm = vm,
                        slot = slot,
                        streams = streams,
                        modifier = modifier,
                        onTap = {
                            bar = true
                            barNonce++
                            if (vm.multiview[slot] == null) sheet = Sheet.Picker(slot) else vm.selectMultiviewAudio(slot)
                        },
                        onLongPress = { if (vm.multiview[slot] == null) sheet = Sheet.Picker(slot) else sheet = Sheet.Options(slot) },
                    )
                }
            }
        }

        if (!inPip) {
            AnimatedVisibility(bar && sheet == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
                Row(
                    Modifier.safeDrawingPadding().padding(8.dp).background(Color(0xCC111111), RoundedCornerShape(28.dp)).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.leaveMultiview() }) { AppIcon(Icons.Back, "Leave Multiview") }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
                        items(MultiviewLayout.entries.filter { it.screens <= vm.maxStreams.coerceAtLeast(1) || it == layout }, key = { it.name }) { l ->
                            FilterChip(l == layout, { vm.changeMultiviewLayout(l); barNonce++ }, label = { Text(l.label) })
                        }
                    }
                }
            }
            AnimatedVisibility(bar && sheet == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Text(
                    "Tap a screen to hear it · Long-press for options",
                    fontSize = 12.sp,
                    color = AppColors.TextDim,
                    modifier = Modifier.safeDrawingPadding().padding(bottom = 6.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            val onScreen = (0 until layout.screens).mapNotNull { vm.multiview[it] }
            val overLimit = onScreen.groupBy { it.providerId }.entries.firstOrNull { (_, list) ->
                val limit = vm.connectionLimit(list.first())
                limit != null && list.size > limit
            }
            if (overLimit != null) {
                val max = vm.connectionLimit(overLimit.value.first()) ?: 0
                Text(
                    "Your IPTV plan allows $max connection${if (max == 1) "" else "s"}. Some screens may not play.",
                    fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 60.dp)
                        .background(AppColors.Accent, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }

            AnimatedVisibility(
                visible = sheet != null,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Box(
                    Modifier.width(340.dp).fillMaxHeight().background(Color(0xF2181818))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .safeDrawingPadding(),
                ) {
                    when (val s = sheet) {
                        is Sheet.Picker -> ChannelPicker(
                            vm = vm,
                            onScreens = onScreen.map { it.id }.toSet(),
                            title = "Screen ${s.slot + 1}",
                            onClose = { sheet = null },
                        ) { ch ->
                            vm.setMultiviewSlot(s.slot, ch)
                            sheet = null
                        }
                        is Sheet.Options -> ScreenOptions(vm, s.slot, onChange = { sheet = Sheet.Picker(s.slot) }, onClose = { sheet = null })
                        null -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenOptions(vm: AppViewModel, slot: Int, onChange: () -> Unit, onClose: () -> Unit) {
    val channel = vm.multiview[slot] ?: return
    val layout = vm.multiviewLayout
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(cleanChannelName(channel.name), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Screen ${slot + 1}", fontSize = 12.sp, color = AppColors.TextDim)
                }
                IconButton(onClick = onClose) { AppIcon(Icons.Close, "Close") }
            }
        }
        item { SettingItem("Full screen", { onClose(); vm.fullscreenFromMultiview(slot) }, icon = Icons.Fullscreen) }
        item { SettingItem("Change channel", onChange, icon = Icons.Refresh) }
        if (layout.screens < 4 && vm.firstEmptyMultiviewSlot() == null) {
            item {
                SettingItem("Add a screen", {
                    vm.changeMultiviewLayout(MultiviewLayout.forCount(layout.screens + 1))
                    onClose()
                }, icon = Icons.Add)
            }
        }
        item { SettingItem("Remove from Multiview", { vm.setMultiviewSlot(slot, null); onClose() }, icon = Icons.Close) }
        item {
            val key = DecoderSlot.multiview(slot)
            val mode = vm.decoderMode(key)
            SettingItem("Video decoding", { vm.setDecoderMode(key, mode.next()) },
                subtitle = "If this screen says there's no hardware decoder free, use Software", value = mode.label)
        }
    }
}

/** Live games first (their best channel), then favorites, then a search over every channel. */
@Composable
fun ChannelPicker(
    vm: AppViewModel,
    onScreens: Set<String>,
    title: String,
    onClose: (() -> Unit)?,
    selected: Set<String> = emptySet(),
    onPick: (Channel) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Suggestion>?>(null) }
    var found by remember { mutableStateOf<List<Channel>>(emptyList()) }
    LaunchedEffect(vm.catalog, vm.games) { suggestions = vm.liveSuggestions() }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            found = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        found = vm.searchChannels(query).take(40)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (onClose != null) IconButton(onClick = onClose) { AppIcon(Icons.Close, "Close") }
        }
        Box(Modifier.padding(horizontal = 12.dp)) {
            FormField(query, { query = it }, "Search channels", imeAction = ImeAction.Search)
        }
        if (vm.iptv !is IptvStatus.Ready) {
            MessageBlock("No channels yet", "Connect a TV provider in Settings to use Multiview.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (query.trim().length >= 2) {
                pickerRows(found, "Channels", onScreens, selected, onPick)
            } else {
                val s = suggestions
                if (s == null) {
                    item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (s.isNotEmpty()) {
                    item { PickerHeader("Live now") }
                    items(s, key = { "s:" + it.channel.id + it.eventId }) { sug ->
                        PickerRow(sug.channel, sug.title, sug.subtitle, sug.channel.id in onScreens, sug.channel.id in selected) { onPick(sug.channel) }
                    }
                }
                pickerRows(vm.favoriteChannels, "Favorite channels", onScreens, selected, onPick)
                pickerRows(vm.recentChannels.take(10), "Recently watched", onScreens, selected, onPick)
            }
        }
    }
}

private fun LazyListScope.pickerRows(list: List<Channel>, header: String, onScreens: Set<String>, selected: Set<String>, onPick: (Channel) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h:$header") { PickerHeader(header) }
    items(list, key = { "$header:" + it.id }) { c ->
        PickerRow(c, cleanChannelName(c.name), c.group, c.id in onScreens, c.id in selected) { onPick(c) }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppColors.TextDim, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun PickerRow(channel: Channel, title: String, subtitle: String, onScreen: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(channel, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (onScreen) "On a screen now" else subtitle, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) AppIcon(Icons.Check, "Selected")
    }
}

/** Arranges the screens for a layout. */
@Composable
private fun MultiviewGrid(layout: MultiviewLayout, gap: Dp, tile: @Composable (slot: Int, modifier: Modifier) -> Unit) {
    val spaced = Arrangement.spacedBy(gap)
    Box(Modifier.fillMaxSize().padding(gap), contentAlignment = Alignment.Center) {
        when (layout) {
            MultiviewLayout.ONE -> tile(0, Modifier.fillMaxSize())
            MultiviewLayout.TWO -> Row(Modifier.fillMaxWidth(), horizontalArrangement = spaced, verticalAlignment = Alignment.CenterVertically) {
                tile(0, Modifier.weight(1f).aspectRatio(16f / 9f))
                tile(1, Modifier.weight(1f).aspectRatio(16f / 9f))
            }
            MultiviewLayout.TWO_PIP -> Box(Modifier.fillMaxSize()) {
                tile(0, Modifier.fillMaxSize())
                tile(1, Modifier.align(Alignment.BottomEnd).padding(gap * 4).fillMaxWidth(0.34f).aspectRatio(16f / 9f))
            }
            MultiviewLayout.THREE -> Row(Modifier.fillMaxSize(), horizontalArrangement = spaced) {
                tile(0, Modifier.weight(2f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = spaced) {
                    tile(1, Modifier.weight(1f).fillMaxWidth())
                    tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
            MultiviewLayout.FOUR -> Column(Modifier.fillMaxSize(), verticalArrangement = spaced) {
                for (row in 0..1) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = spaced) {
                        tile(row * 2, Modifier.weight(1f).fillMaxHeight())
                        tile(row * 2 + 1, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            MultiviewLayout.ONE_PLUS_THREE -> Row(Modifier.fillMaxSize(), horizontalArrangement = spaced) {
                tile(0, Modifier.weight(3f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = spaced) {
                    for (slot in 1..3) tile(slot, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Tile(vm: AppViewModel, slot: Int, streams: TileStreams, modifier: Modifier, onTap: () -> Unit, onLongPress: () -> Unit) {
    val channel = vm.multiview[slot]
    val layout = vm.multiviewLayout
    val isAudio = channel != null && vm.multiviewAudio == slot && layout.screens > 1
    Box(
        modifier
            .pointerInput(slot, channel?.id) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) }
            .background(Color(0xFF05080D))
            .border(if (isAudio) 2.dp else 1.dp, if (isAudio) AppColors.Focus else AppColors.Border),
    ) {
        if (channel == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(Icons.Add, null, tint = AppColors.TextDim, size = 32.dp)
                Text("Screen ${slot + 1}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Tap to add a channel", fontSize = 11.sp, color = AppColors.TextDim)
            }
            return@Box
        }
        val stream = streams.forChannel(channel)
        val shared = streams.isShared(stream)
        val decoding = vm.decoderMode(DecoderSlot.multiview(slot))
        if (!shared) {
            LaunchedEffect(stream, decoding) { stream.applyDecoderMode(decoding) }
            WatchStalls(stream)
        }
        val candidates = remember(channel.id, vm.streamFormat) { vm.streamCandidates(channel) }
        val single = layout.screens == 1
        LaunchedEffect(stream, isAudio, single) { stream.volume = if (isAudio || single) 1f else 0f }
        LaunchedEffect(stream, single) { if (single) stream.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE) else stream.setMaxVideoSize(1280, 720, frameRate = 30) }
        LaunchedEffect(stream, channel.id, candidates) {
            stream.load(channel.id, candidates)
            vm.noteRecent(channel)
        }
        val redZone = vm.isRedZone(channel)
        val liveGame = remember(channel.id, vm.games) { if (redZone) null else vm.liveGameFor(channel) }
        val liveTournament = remember(channel.id, vm.tournaments) { if (liveGame == null && !redZone) vm.liveTournamentFor(channel) else null }
        val game = vm.delayedGame(liveGame)
        val tournament = vm.delayedTournament(liveTournament)
        val redZoneGames = if (redZone) vm.liveNflGames.mapNotNull { vm.delayedGame(it) } else emptyList()
        val scoreKey = if (redZone) redZoneGames.joinToString("|") { "${it.id}:${scoreKeyOf(it, null)}" }.ifEmpty { null } else scoreKeyOf(game, tournament)
        val bug = rememberBugState(channel.id, scoreKey, vm.scoreBugMode, vm.scoreAlerts, showMillis = 6_000, startVisible = !vm.hideScores)

        VideoSurface(stream, Modifier.fillMaxSize(), onTop = layout == MultiviewLayout.TWO_PIP && slot == 1, showSubtitles = vm.captions && (isAudio || single))
        if (stream.buffering && stream.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = AppColors.Live, strokeWidth = 3.dp)
        }
        stream.error?.let {
            Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can't play this channel", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(it, fontSize = 10.sp, color = AppColors.TextDim, textAlign = TextAlign.Center, maxLines = 3)
            }
        }
        EventBug(game, tournament, bug, Modifier.align(Alignment.TopEnd).padding(6.dp), compact = true)
        RedZoneBugs(redZoneGames, bug, Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (isAudio) {
            Box(Modifier.align(Alignment.BottomStart).padding(6.dp).size(22.dp).background(Color(0xCC000000), CircleShape), contentAlignment = Alignment.Center) {
                AppIcon(Icons.Volume, "Playing sound", size = 14.dp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Builder: pick up to four, then watch them together
// ---------------------------------------------------------------------------------------------

@Composable
fun MultiviewBuilderScreen(vm: AppViewModel) {
    val picks = remember { mutableStateListOf<Channel>().apply { addAll(vm.builderSeed) } }
    val max = vm.maxStreams.coerceIn(1, 4)
    Column(Modifier.fillMaxSize()) {
        BackBar(vm, "Build a Multiview")
        Text(
            "Pick up to $max channels to watch together.",
            fontSize = 14.sp, color = AppColors.TextDim, modifier = Modifier.padding(horizontal = GUTTER),
        )
        if (picks.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = GUTTER, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(picks.toList(), key = { it.id }) { c ->
                    InputChip(
                        selected = true,
                        onClick = { picks.remove(c) },
                        label = { Text(cleanChannelName(c.name), maxLines = 1) },
                        trailingIcon = { AppIcon(Icons.Close, "Remove", size = 16.dp) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            ChannelPicker(vm, onScreens = emptySet(), title = "Channels", onClose = null, selected = picks.map { it.id }.toSet()) { ch ->
                when {
                    picks.any { it.id == ch.id } -> picks.removeAll { it.id == ch.id }
                    picks.size >= max -> vm.showMessage("Up to $max screens with your provider")
                    else -> picks.add(ch)
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(GUTTER), verticalAlignment = Alignment.CenterVertically) {
            Text("${picks.size} of $max", fontSize = 14.sp, color = AppColors.TextDim, modifier = Modifier.weight(1f))
            Button(onClick = { vm.startMultiview(picks.toList()) }, enabled = picks.isNotEmpty()) {
                AppIcon(Icons.Multiview, null, tint = Color.Black, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Watch")
            }
        }
    }
}
