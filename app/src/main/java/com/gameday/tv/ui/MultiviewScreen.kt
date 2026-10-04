package com.gameday.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.GameState
import com.gameday.tv.data.SportPick
import com.gameday.tv.data.MultiviewLayout
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

private sealed interface Sidebar {
    data class Picker(val slot: Int) : Sidebar
    data class Options(val slot: Int) : Sidebar
}

private val SidebarBackground = Color(0xFF0B111B)

/**
 * Multiview: up to four streams at once. Move between screens with the D-pad — the highlighted
 * screen plays the audio. OK on a screen opens its menu (change channel, full screen, remove, add a
 * screen, layout). Holding OK / Menu does the same.
 */
@Composable
fun MultiviewScreen(vm: AppViewModel) {
    var sidebar by remember { mutableStateOf<Sidebar?>(null) }
    var lastSidebar by remember { mutableStateOf<Sidebar>(Sidebar.Picker(0)) }
    val tileFocus = remember { List(4) { FocusRequester() } }
    var hintNonce by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(true) }
    var bugSignal by remember { mutableIntStateOf(0) }
    // The screen whose sidebar is open; focus returns there when the sidebar closes.
    var returnSlot by remember { mutableIntStateOf(vm.multiviewAudio) }
    val layout = vm.multiviewLayout

    fun open(s: Sidebar) {
        lastSidebar = s
        sidebar = s
        returnSlot = when (s) {
            is Sidebar.Picker -> s.slot
            is Sidebar.Options -> s.slot
        }
    }

    fun closeSidebar() {
        sidebar = null
    }

    BackHandler(enabled = sidebar != null) { closeSidebar() }
    LaunchedEffect(Unit) { if (vm.multiviewCount == 0) open(Sidebar.Picker(0)) }
    LaunchedEffect(sidebar, layout) {
        if (sidebar != null) return@LaunchedEffect
        val target = tileFocus[returnSlot.coerceAtMost(layout.screens - 1)]
        target.requestFocusSafely(80)
        // When a sidebar finishes sliding out, its focused row is removed and Android hands focus to
        // the first view; claim it again once the exit animation is over.
        target.requestFocusSafely(450)
    }
    LaunchedEffect(hintNonce) {
        hintVisible = true
        delay(6_000)
        hintVisible = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        MultiviewGrid(layout, gap = 4.dp) { slot, modifier ->
            key(layout, slot) {
                MultiviewTile(
                    vm = vm,
                    slot = slot,
                    modifier = modifier
                        .focusRequester(tileFocus[slot])
                        .then(pipFocusLinks(layout, slot, tileFocus)),
                    focusEnabled = sidebar == null,
                    bugSignal = bugSignal,
                    onFocused = { hintNonce++ },
                    onPick = { open(Sidebar.Picker(slot)) },
                    onMenu = { open(Sidebar.Options(slot)) },
                    onShowBugs = { bugSignal++ },
                )
            }
        }

        // Warn when more screens come from one provider than its plan allows.
        val onScreen = (0 until layout.screens).mapNotNull { vm.multiview[it] }
        val overLimit = onScreen.groupBy { it.providerId }.entries.firstOrNull { (_, list) ->
            val limit = vm.connectionLimit(list.first())
            limit != null && list.size > limit
        }
        if (overLimit != null) {
            val maxConnections = vm.connectionLimit(overLimit.value.first()) ?: 0
            Text(
                "Your IPTV plan allows $maxConnections connection${if (maxConnections == 1) "" else "s"} — some screens may not play.",
                fontSize = 13.sp,
                color = Color.Black,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(AppColors.Accent, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        AnimatedVisibility(
            visible = hintVisible && sidebar == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            Text(
                if (layout.screens == 1) {
                    "OK  Screen menu · add screens · layout      Info  Score      BACK  Exit"
                } else {
                    "◀▲▼▶  Switch screen (audio follows)      OK  Screen menu · layout      Info  Scores      BACK  Exit"
                },
                fontSize = 13.sp,
                color = AppColors.Text,
                modifier = Modifier
                    .background(Color(0xE60B111B), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = sidebar != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            when (val s = lastSidebar) {
                is Sidebar.Picker -> MultiviewPicker(
                    vm = vm,
                    slot = s.slot,
                    onPick = { ch ->
                        vm.setMultiviewSlot(s.slot, ch)
                        closeSidebar()
                    },
                )
                is Sidebar.Options -> ScreenMenu(
                    vm = vm,
                    slot = s.slot,
                    onChangeChannel = { open(Sidebar.Picker(s.slot)) },
                    onFullscreen = {
                        closeSidebar()
                        vm.fullscreenFromMultiview(s.slot)
                    },
                    onRemove = {
                        vm.setMultiviewSlot(s.slot, null)
                        closeSidebar()
                    },
                    onAddScreen = {
                        vm.changeMultiviewLayout(MultiviewLayout.forCount(layout.screens + 1))
                        vm.firstEmptyMultiviewSlot()?.let { open(Sidebar.Picker(it)) } ?: closeSidebar()
                    },
                    onLayout = { l ->
                        vm.changeMultiviewLayout(l)
                        // Land on the first new (empty) screen so OK fills it.
                        returnSlot = vm.firstEmptyMultiviewSlot() ?: returnSlot.coerceAtMost(l.screens - 1)
                        closeSidebar()
                    },
                )
            }
        }
    }
}

/** The PiP window sits inside the main one, so the D-pad needs explicit links between them. */
private fun pipFocusLinks(layout: MultiviewLayout, slot: Int, tiles: List<FocusRequester>): Modifier =
    if (layout != MultiviewLayout.TWO_PIP) {
        Modifier
    } else {
        Modifier.focusProperties {
            if (slot == 0) {
                right = tiles[1]
                down = tiles[1]
            } else {
                left = tiles[0]
                up = tiles[0]
            }
        }
    }

/** Arranges [tile] slots for a layout. Also used to draw the layout icons. */
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
                tile(1, Modifier.align(Alignment.BottomEnd).padding(gap * 4).fillMaxWidth(0.32f).aspectRatio(16f / 9f))
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
private fun MultiviewTile(
    vm: AppViewModel,
    slot: Int,
    modifier: Modifier,
    focusEnabled: Boolean,
    bugSignal: Int,
    onFocused: () -> Unit,
    onPick: () -> Unit,
    onMenu: () -> Unit,
    onShowBugs: () -> Unit,
) {
    val channel = vm.multiview[slot]
    val layout = vm.multiviewLayout
    val isAudio = channel != null && vm.multiviewAudio == slot && layout.screens > 1
    var focused by remember { mutableStateOf(false) }
    var okLongPressed by remember { mutableStateOf(false) }

    fun onOk() {
        if (channel == null) onPick() else onMenu()
    }

    Box(
        modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                    // Like TiviMate: the highlighted screen is the one you hear.
                    if (channel != null) vm.selectMultiviewAudio(slot)
                }
            }
            .onKeyEvent { ev ->
                when {
                    ev.key in OK_KEYS -> {
                        when {
                            ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0 -> okLongPressed = false
                            ev.type == KeyEventType.KeyDown && ev.isLongPressRepeat() -> {
                                okLongPressed = true
                                OkKeyGate.swallowRelease() // the menu opens while OK is still held
                                onMenu()
                            }
                            ev.type == KeyEventType.KeyUp -> if (!okLongPressed) onOk()
                        }
                        true
                    }
                    ev.type != KeyEventType.KeyDown -> false
                    ev.key == Key.Menu -> { onMenu(); true }
                    ev.key in SCORE_KEYS -> { onShowBugs(); true }
                    else -> false
                }
            }
            .focusable(enabled = focusEnabled)
            .pointerInput(slot, channel?.id) {
                detectTapGestures(onTap = { onOk() }, onLongPress = { onMenu() })
            }
            .background(Color(0xFF05080D))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) AppColors.Focus else AppColors.Border,
            ),
    ) {
        if (channel == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("+", fontSize = 40.sp, color = if (focused) AppColors.Text else AppColors.TextDim, fontWeight = FontWeight.Light)
                Text("Screen ${slot + 1}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Press OK to add a channel", fontSize = 12.sp, color = AppColors.TextDim)
            }
        } else {
            ActiveTile(
                vm = vm,
                slot = slot,
                channel = channel,
                isAudio = isAudio,
                focused = focused,
                bugSignal = bugSignal,
                onTop = layout == MultiviewLayout.TWO_PIP && slot == 1,
            )
        }
    }
}

@Composable
private fun ActiveTile(vm: AppViewModel, slot: Int, channel: Channel, isAudio: Boolean, focused: Boolean, bugSignal: Int, onTop: Boolean) {
    // Tiles don't request audio focus: several players fighting over it would pause each other.
    val stream = rememberStreamController(handleAudioFocus = false)
    val decoding = vm.decoderMode(DecoderSlot.multiview(slot))
    // Before load(), so the first start already uses this screen's decoder.
    LaunchedEffect(stream, decoding) { stream.applyDecoderMode(decoding) }
    LaunchedEffect(stream.softwareDecoding) { vm.multiviewSoftware[slot] = stream.softwareDecoding }
    val candidates = remember(channel.id, vm.streamFormat) { vm.streamCandidates(channel) }
    val single = vm.multiviewLayout.screens == 1
    LaunchedEffect(stream, single) { if (single) stream.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE) else stream.setMaxVideoSize(1280, 720) }
    LaunchedEffect(channel.id, candidates) {
        stream.load(channel.id, candidates)
        vm.noteRecent(channel)
    }
    LaunchedEffect(isAudio, single) { stream.volume = if (isAudio || single) 1f else 0f }

    val liveGame = remember(channel.id, vm.games) { vm.liveGameFor(channel) }
    val liveTournament = remember(channel.id, vm.tournaments) { if (liveGame == null) vm.liveTournamentFor(channel) else null }
    // The stream runs behind the scoreboard: show the score from that long ago.
    val game = vm.delayedGame(liveGame)
    val tournament = vm.delayedTournament(liveTournament)
    val bug = rememberBugState(channel.id, scoreKeyOf(game, tournament), vm.scoreBugMode, vm.scoreAlerts, showMillis = 6_000)
    LaunchedEffect(bugSignal) { if (bugSignal > 0) bug.show() }

    Box(Modifier.fillMaxSize()) {
        // Captions only on the screen you are listening to.
        VideoSurface(stream, Modifier.fillMaxSize(), onTop = onTop, showSubtitles = vm.captions && (isAudio || single))

        if (stream.buffering && stream.error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(32.dp)
                if (stream.reconnecting) Text("Reconnecting…", fontSize = 11.sp, color = AppColors.TextDim)
            }
        }
        stream.error?.let {
            Column(
                Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play this channel", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(it, fontSize = 11.sp, color = AppColors.TextDim, textAlign = TextAlign.Center, maxLines = 3)
            }
        }

        EventBug(game, tournament, bug, Modifier.align(Alignment.TopEnd).padding(8.dp), compact = !single)

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color(0xCC0B111B), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isAudio) {
                Text("🔊", fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                cleanChannelName(channel.name),
                fontSize = 12.sp,
                fontWeight = if (focused || isAudio) FontWeight.Bold else FontWeight.Medium,
                color = if (isAudio) AppColors.Accent else AppColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Layout chooser (shown when a Multiview session starts) and the per-screen menu
// ---------------------------------------------------------------------------------------------

@Composable
private fun LayoutIcon(layout: MultiviewLayout, selected: Boolean, modifier: Modifier) {
    Box(modifier.background(Color(0xFF05080D), RoundedCornerShape(4.dp))) {
        MultiviewGrid(layout, gap = 2.dp) { slot, m ->
            Box(
                m.background(
                    when {
                        selected -> AppColors.Accent
                        slot == 0 -> AppColors.Text.copy(alpha = 0.75f)
                        else -> AppColors.TextDim
                    },
                    RoundedCornerShape(2.dp),
                ),
            )
        }
    }
}

@Composable
private fun ScreenMenu(
    vm: AppViewModel,
    slot: Int,
    onChangeChannel: () -> Unit,
    onFullscreen: () -> Unit,
    onRemove: () -> Unit,
    onAddScreen: () -> Unit,
    onLayout: (MultiviewLayout) -> Unit,
) {
    val channel = vm.multiview[slot]
    val first = remember { FocusRequester() }
    LaunchedEffect(slot) { first.requestFocusSafely(120) }

    Column(
        Modifier
            .width(400.dp)
            .fillMaxHeight()
            .background(SidebarBackground)
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 12.dp),
    ) {
        Text("Screen ${slot + 1}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(channel?.name ?: "Empty", fontSize = 13.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            OptionRow(if (channel == null) "Choose a channel" else "Change channel", onChangeChannel, Modifier.focusRequester(first))
            if (channel != null) {
                OptionRow("Watch full screen", onFullscreen)
                OptionRow("Remove this screen's channel", onRemove)
            }
            val decSlot = DecoderSlot.multiview(slot)
            val mode = vm.decoderMode(decSlot)
            OptionRow(
                "Video decoding: ${mode.label}",
                { vm.setDecoderMode(decSlot, mode.next()) },
                subtitle = if (channel != null) decodingStatus(mode, vm.multiviewSoftware[slot] == true) else null,
            )
            if (vm.multiviewLayout.screens < 4) OptionRow("＋ Add a screen", onAddScreen)
        }
        Spacer(Modifier.height(16.dp))
        Text("Layout", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppColors.Text)
        Spacer(Modifier.height(8.dp))
        MultiviewLayout.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { l -> LayoutChip(l, selected = vm.multiviewLayout == l) { onLayout(l) } }
            }
        }
    }
}

@Composable
private fun OptionRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.02f, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = AppColors.TextDim)
        }
    }
}

@Composable
private fun LayoutChip(layout: MultiviewLayout, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(110.dp),
        focusedScale = 1.06f,
        shape = RoundedCornerShape(8.dp),
        containerColor = if (selected) AppColors.Accent.copy(alpha = 0.25f) else AppColors.Card,
    ) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LayoutIcon(layout, selected, Modifier.size(width = 64.dp, height = 36.dp))
            Spacer(Modifier.height(4.dp))
            Text(layout.description, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = if (selected) AppColors.Accent else AppColors.Text)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Channel picker sidebar: Search, then sports → events → streams
// ---------------------------------------------------------------------------------------------

private sealed interface PickerPage {
    val title: String

    data object Menu : PickerPage { override val title = "Choose a channel" }
    data object LiveNow : PickerPage { override val title = "Live now" }
    data object MyTeams : PickerPage { override val title = "Your teams" }
    data class Sport(val league: SportPick) : PickerPage { override val title = league.label }
    data object Recent : PickerPage { override val title = "Recent channels" }
    data object Categories : PickerPage { override val title = "Categories" }
    data class Category(val name: String) : PickerPage { override val title = name }
}

@Composable
private fun MultiviewPicker(vm: AppViewModel, slot: Int, onPick: (Channel) -> Unit) {
    val catalog = (vm.iptv as? IptvStatus.Ready)?.catalog
    val pages = remember(slot) { mutableStateListOf<PickerPage>(PickerPage.Menu) }
    val page = pages.last()
    var query by remember(slot) { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Channel>?>(null) }
    val firstFocus = remember { FocusRequester() }
    // Channels already on another screen aren't offered again.
    val onScreens = (0 until vm.multiviewLayout.screens).mapNotNull { vm.multiview[it]?.id }.toSet()

    BackHandler(enabled = pages.size > 1) { pages.removeAt(pages.lastIndex) }
    LaunchedEffect(query) {
        results = if (query.isBlank() || catalog == null) {
            null
        } else {
            delay(250)
            vm.searchChannels(query).filter { it.id !in onScreens }
        }
    }
    fun go(p: PickerPage) {
        query = ""
        pages.add(p)
    }

    Column(
        Modifier
            .width(470.dp)
            .fillMaxHeight()
            .background(SidebarBackground)
            .padding(start = 20.dp, end = 24.dp, top = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Screen ${slot + 1}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (page != PickerPage.Menu) {
                Text("  ›  ${page.title}", fontSize = 18.sp, color = AppColors.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            if (page == PickerPage.Menu) "Pick a sport, or search" else "BACK to go back",
            fontSize = 12.sp,
            color = AppColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))

        if (catalog == null) {
            Text("Your channels aren't loaded yet.", fontSize = 14.sp, color = AppColors.TextDim)
            return@Column
        }

        if (page == PickerPage.Menu) {
            TvTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search all channels",
                imeAction = ImeAction.Search,
                onSubmit = {},
            )
            Spacer(Modifier.height(10.dp))
        }

        val found = results
        when {
            // Search results must not take focus away from the search field while typing.
            page == PickerPage.Menu && found != null -> ChannelList(found, onPick, firstFocus, emptyText = "No channels found.", autoFocus = false)
            page == PickerPage.Menu -> PickerMenu(vm, onScreens, firstFocus, ::go)
            page == PickerPage.LiveNow -> LiveNowPage(vm, onScreens, onPick, firstFocus)
            page == PickerPage.MyTeams -> EventsPage(vm, null, onScreens, onPick, firstFocus)
            page is PickerPage.Sport -> EventsPage(vm, page.league, onScreens, onPick, firstFocus)
            page == PickerPage.Recent -> ChannelList(vm.recentChannels.filter { it.id !in onScreens }, onPick, firstFocus, "Nothing watched yet.")
            page == PickerPage.Categories -> CategoriesPage(catalog.sportsGroups.ifEmpty { catalog.groups }, catalog.byGroup.mapValues { it.value.size }, firstFocus) { go(PickerPage.Category(it)) }
            page is PickerPage.Category -> ChannelList(catalog.byGroup[page.name].orEmpty().filter { it.id !in onScreens }, onPick, firstFocus, "This category is empty.")
        }
    }
}

@Composable
private fun PickerMenu(vm: AppViewModel, onScreens: Set<String>, firstFocus: FocusRequester, go: (PickerPage) -> Unit) {
    val now = System.currentTimeMillis()
    val soon = now + 12 * 60 * 60_000L
    LaunchedEffect(Unit) { firstFocus.requestFocusSafely(150) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 24.dp)) {
        val liveCount = vm.games.count { it.state == GameState.LIVE } + vm.tournaments.count { it.roundInProgress }
        item(key = "live") {
            MenuRow("Live now", if (liveCount > 0) "$liveCount live · best channel for each" else "Nothing live right now", live = liveCount > 0, modifier = Modifier.focusRequester(firstFocus)) { go(PickerPage.LiveNow) }
        }
        if (vm.favorites.isNotEmpty()) {
            item(key = "teams") {
                val fav = vm.favoriteGames
                MenuRow(
                    "Your teams",
                    eventsSummary(fav.count { it.state == GameState.LIVE }, fav.count { it.state == GameState.PRE && it.startMillis < soon }),
                    live = fav.any { it.state == GameState.LIVE },
                ) { go(PickerPage.MyTeams) }
            }
        }
        item(key = "sports-h") { PickerHeader("Sports") }
        items(vm.followedPicks, key = { "l:" + it.key }) { pick ->
            val keys = pick.leagueKeys
            val summary = if (pick.sport == "golf") {
                val t = vm.tournaments.filter { it.tour.key in keys && it.state != GameState.FINAL }
                t.firstOrNull()?.let { "${it.name} · ${it.detail}" } ?: "No tournament this week"
            } else {
                val lg = vm.games.filter { it.league.key in keys }
                eventsSummary(lg.count { it.state == GameState.LIVE }, lg.count { it.state == GameState.PRE && it.startMillis < soon })
            }
            val live = if (pick.sport == "golf") vm.tournaments.any { it.tour.key in keys && it.roundInProgress }
            else vm.games.any { it.league.key in keys && it.state == GameState.LIVE }
            MenuRow(pick.label, summary, live = live) { go(PickerPage.Sport(pick)) }
        }
        item(key = "more-h") { PickerHeader("More") }
        if (vm.recentChannels.any { it.id !in onScreens }) {
            item(key = "recent") { MenuRow("Recent channels", "${vm.recentChannels.count { it.id !in onScreens }} channels") { go(PickerPage.Recent) } }
        }
        item(key = "cats") { MenuRow("Browse categories", "Your provider's channel groups") { go(PickerPage.Categories) } }
    }
}

private fun eventsSummary(live: Int, upcoming: Int): String = when {
    live > 0 && upcoming > 0 -> "$live live · $upcoming upcoming"
    live > 0 -> "$live live"
    upcoming > 0 -> "$upcoming upcoming"
    else -> "No games soon · see channels"
}

@Composable
private fun MenuRow(title: String, subtitle: String, modifier: Modifier = Modifier, live: Boolean = false, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.02f, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                LiveDot()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", fontSize = 22.sp, color = AppColors.TextDim)
        }
    }
}

@Composable
private fun LiveNowPage(vm: AppViewModel, onScreens: Set<String>, onPick: (Channel) -> Unit, firstFocus: FocusRequester) {
    var suggestions by remember { mutableStateOf<List<Suggestion>?>(null) }
    LaunchedEffect(Unit) { suggestions = vm.liveSuggestions() }
    val sugg = suggestions?.filter { it.channel.id !in onScreens }
    LaunchedEffect(sugg != null) { if (!sugg.isNullOrEmpty()) firstFocus.requestFocusSafely(80) }

    when {
        sugg == null -> LoadingState("Finding channels for live events…")
        sugg.isEmpty() -> Text("No live events matched your channels.", fontSize = 13.sp, color = AppColors.TextDim)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 24.dp)) {
            items(sugg, key = { "s:" + it.channel.id }) { s ->
                SuggestionRow(s, onClick = { onPick(s.channel) }, modifier = if (s == sugg.first()) Modifier.focusRequester(firstFocus) else Modifier)
            }
        }
    }
}

/** A sport (or My Teams when [league] is null): each live/upcoming event with its best streams, then the sport's channels. */
@Composable
private fun EventsPage(vm: AppViewModel, league: SportPick?, onScreens: Set<String>, onPick: (Channel) -> Unit, firstFocus: FocusRequester) {
    var events by remember(league) { mutableStateOf<List<EventStreams>?>(null) }
    var channels by remember(league) { mutableStateOf<List<Channel>>(emptyList()) }
    LaunchedEffect(league) {
        events = vm.eventStreams(league?.key)
        channels = league?.leagues?.flatMap { vm.leagueChannels(it) }?.distinctBy { it.id }.orEmpty()
    }
    val evs = events
    val firstChannel = evs?.flatMap { e -> e.matches.map { it.channel } }?.firstOrNull { it.id !in onScreens }
        ?: channels.firstOrNull { it.id !in onScreens }
    LaunchedEffect(evs != null, firstChannel) { if (firstChannel != null) firstFocus.requestFocusSafely(80) }

    if (evs == null) {
        LoadingState("Finding streams…")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 24.dp)) {
        if (evs.isEmpty()) {
            item(key = "none") {
                Text(
                    if (league == null) "None of your teams are playing soon." else "No ${league.label} events live or coming up soon.",
                    fontSize = 13.sp,
                    color = AppColors.TextDim,
                )
            }
        }
        evs.forEach { e ->
            item(key = "e:" + e.eventId) { EventHeader(e) }
            val picks = e.matches.filter { it.channel.id !in onScreens }
            if (picks.isEmpty()) {
                item(key = "e0:" + e.eventId) { Text("No matching stream", fontSize = 12.sp, color = AppColors.TextDim, modifier = Modifier.padding(start = 8.dp)) }
            }
            items(picks, key = { "m:${e.eventId}:${it.channel.id}" }) { m ->
                ChannelRow(
                    channel = m.channel,
                    subtitle = m.reasons.joinToString("  •  ").ifBlank { m.channel.group },
                    badge = if (m.exact) "Best" else null,
                    onClick = { onPick(m.channel) },
                    modifier = if (m.channel == firstChannel) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        if (league != null) {
            val rest = channels.filter { it.id !in onScreens }
            item(key = "ch-h") { PickerHeader("${league.label} channels") }
            if (rest.isEmpty()) {
                item(key = "ch0") { Text("No ${league.label} channels found in your lineup.", fontSize = 12.sp, color = AppColors.TextDim) }
            }
            // An event's channel may also appear here; keys stay unique because sections differ.
            channelItems(rest, "c:", onPick, firstFocus.takeIf { evs.isEmpty() || evs.all { e -> e.matches.none { it.channel.id !in onScreens } } }, rest.firstOrNull())
        }
    }
}

private fun LazyListScope.channelItems(
    channels: List<Channel>,
    prefix: String,
    onPick: (Channel) -> Unit,
    focus: FocusRequester?,
    focusTarget: Channel?,
) {
    items(channels, key = { prefix + it.id }) { ch ->
        ChannelRow(
            ch, ch.group, { onPick(ch) },
            modifier = if (focus != null && ch == focusTarget) Modifier.focusRequester(focus) else Modifier,
        )
    }
}

@Composable
private fun EventHeader(e: EventStreams) {
    Row(Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (e.live) {
            LiveDot()
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.subtitle, fontSize = 11.sp, color = if (e.live) AppColors.Live else AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<Channel>,
    onPick: (Channel) -> Unit,
    firstFocus: FocusRequester,
    emptyText: String,
    autoFocus: Boolean = true,
) {
    LaunchedEffect(channels.firstOrNull()?.id) { if (autoFocus && channels.isNotEmpty()) firstFocus.requestFocusSafely(80) }
    if (channels.isEmpty()) {
        Text(emptyText, fontSize = 13.sp, color = AppColors.TextDim)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 24.dp)) {
        channelItems(channels.take(300), "q:", onPick, firstFocus, channels.first())
    }
}

@Composable
private fun CategoriesPage(groups: List<String>, counts: Map<String, Int>, firstFocus: FocusRequester, onOpen: (String) -> Unit) {
    LaunchedEffect(Unit) { firstFocus.requestFocusSafely(120) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 24.dp)) {
        items(groups, key = { "g:$it" }) { g ->
            MenuRow(g, "${counts[g] ?: 0} channels", if (g == groups.first()) Modifier.focusRequester(firstFocus) else Modifier) { onOpen(g) }
        }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppColors.Text, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun SuggestionRow(s: Suggestion, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth().height(64.dp), focusedScale = 1.02f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(s.channel, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.channel.name}  ·  ${s.subtitle}", fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
