package com.gameday.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.Program
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

private const val SLOT_MS = 30 * 60_000L
private const val WINDOW_MS = 2 * 60 * 60_000L
private val CHANNEL_COL = 196.dp
private val ROW_HEIGHT = 58.dp

private fun floorSlot(t: Long) = t - Math.floorMod(t, SLOT_MS)

/** A guide cell to focus after the time window moves. */
private data class PendingFocus(val channelId: String, val time: Long)

/** The live guide: channels down the side, programs across time, like YouTube TV's Live tab. */
@Composable
fun LiveTab(vm: AppViewModel) {
    val screenKey = "main:${Tab.LIVE}"
    val catalog = vm.catalog
    if (catalog == null) {
        Box(Modifier.fillMaxSize().padding(top = 80.dp)) {
            EmptyState(
                when (vm.iptv) {
                    IptvStatus.Loading -> "Loading your channels…"
                    is IptvStatus.Failed -> "Couldn't load your channels"
                    else -> "No TV provider yet"
                },
                (vm.iptv as? IptvStatus.Failed)?.message ?: "Connect your IPTV provider to see the live guide.",
            ) {
                if (vm.iptv is IptvStatus.Failed) PillButton("Retry", { vm.reloadChannels() })
                PillButton("Set up provider", { vm.navigate(Screen.Provider()) }, primary = true)
            }
        }
        return
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    var windowStart by rememberSaveable { mutableLongStateOf(floorSlot(System.currentTimeMillis())) }
    var pending by remember { mutableStateOf<PendingFocus?>(null) }
    var focused by remember { mutableStateOf<Pair<Channel, Program?>?>(null) }
    var pickingGroup by remember { mutableStateOf(false) }
    val filter = vm.guideFilter
    val channels = remember(filter, catalog, vm.hiddenGroups, vm.guideSort, vm.favoriteChannelIds.size, vm.recentChannelIds.size) { vm.guideChannels(filter) }
    val hasArchive = remember(channels) { channels.any { it.archiveDays > 0 } }
    val minStart = floorSlot(now) - if (hasArchive) 24 * 3_600_000L else 0L
    val maxStart = floorSlot(now) + 22 * 3_600_000L
    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    val selectedChip = remember { FocusRequester() }
    val tracker = remember { FocusTracker() }
    if (vm.tabWantsFocus) InitialFocus(vm, screenKey, gridFocus, tracker = tracker)

    /** Moves the time window so [target] (the next or previous program) is on screen, and focuses it. */
    fun shift(right: Boolean, channelId: String, target: Long) {
        val wanted = if (right) maxOf(windowStart + SLOT_MS, floorSlot(target) - SLOT_MS)
        else minOf(windowStart - SLOT_MS, floorSlot(target) - SLOT_MS)
        val next = wanted.coerceIn(minStart, maxStart)
        if (next == windowStart) return
        windowStart = next
        pending = PendingFocus(channelId, target.coerceIn(next, next + WINDOW_MS - 60_000L))
    }

    // After a category is picked, focus goes to the top of the guide (never back to the top bar).
    var focusGridAfterPick by remember { mutableStateOf(false) }
    LaunchedEffect(focusGridAfterPick, channels) {
        if (!focusGridAfterPick) return@LaunchedEffect
        listState.scrollToItem(0)
        if (!gridFocus.requestFocusSafely(120)) chipFocus.requestFocusSafely(0)
        focusGridAfterPick = false
    }

    Column(Modifier.fillMaxSize().padding(top = 64.dp)) {
        // ---- focused program details (live video plays to the right) ----
        Box(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 48.dp), contentAlignment = Alignment.BottomStart) {
            val (ch, prog) = focused ?: (null to null)
            if (ch != null) {
                Column(Modifier.fillMaxWidth(0.55f).padding(bottom = 8.dp)) {
                    Text(prog?.title ?: cleanChannelName(ch.name), fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (prog?.isOnNow(now) == true) {
                            LiveBadge(small = true)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            listOfNotNull(
                                cleanChannelName(ch.name),
                                prog?.let { "${formatDay(it.startMillis)} ${formatRange(it.startMillis, it.endMillis)}" },
                                prog?.takeIf { it.isOnNow(now) }?.let { minutesLeft(it.endMillis, now) },
                                if (prog != null && prog.endMillis <= now && vm.catchupUrl(ch, prog) != null) "Replay available" else null,
                            ).joinToString("  •  "),
                            fontSize = 13.sp,
                            color = AppColors.TextDim,
                            maxLines = 1,
                        )
                    }
                    prog?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 13.sp, color = Color(0xFFBBBBBB), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    vm.providerName(ch)?.let { Text(it, fontSize = 11.sp, color = AppColors.TextFaint, maxLines = 1) }
                }
            }
        }

        // ---- filters ----
        LazyRow(
            // Up from the guide comes back to the chosen filter.
            Modifier.focusRequester(chipFocus).focusRestorer(selectedChip),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val opts = listOf("sports" to "Sports", "all" to "All channels", "favorites" to "Favorites", "recent" to "Recently watched")
            items(opts, key = { it.first }) { (k, label) ->
                Chip(label, filter == k, { vm.updateGuideFilter(k) }, if (filter == k) Modifier.focusRequester(selectedChip) else Modifier)
            }
            item(key = "groups") {
                val isGroup = filter.startsWith("group:")
                Chip(if (isGroup) filter.removePrefix("group:") else "Categories", isGroup, { pickingGroup = true },
                    if (isGroup) Modifier.focusRequester(selectedChip) else Modifier, icon = Icons.Guide)
            }
        }
        Spacer(Modifier.height(6.dp))

        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 48.dp, end = 24.dp)) {
            val areaWidth = maxWidth - CHANNEL_COL - 8.dp
            val dpPerMs = areaWidth.value / WINDOW_MS
            fun x(t: Long): Dp = ((t - windowStart) * dpPerMs).dp

            Column(Modifier.fillMaxSize()) {
                // ---- time ruler ----
                Row(Modifier.fillMaxWidth().height(26.dp)) {
                    Text(formatDay(windowStart), fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.width(CHANNEL_COL + 8.dp))
                    Box(Modifier.width(areaWidth).fillMaxHeight()) {
                        var t = windowStart
                        while (t < windowStart + WINDOW_MS) {
                            Text(formatTime(t), fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.offset(x = x(t) + 4.dp))
                            t += SLOT_MS
                        }
                        if (now in windowStart until windowStart + WINDOW_MS) {
                            Box(Modifier.offset(x = x(now) - 4.dp, y = 16.dp).size(8.dp).background(AppColors.Live, CircleShape))
                        }
                    }
                }
                if (channels.isEmpty()) {
                    EmptyState(
                        when (filter) {
                            "favorites" -> "No favorite channels yet"
                            "recent" -> "Nothing watched yet"
                            else -> "No channels here"
                        },
                        if (filter == "favorites") "Long-press any channel and choose \"Add to favorite channels\"." else null,
                    )
                    return@Column
                }
                PivotScroll(offset = ROW_HEIGHT) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().trackFocus(tracker),
                        contentPadding = PaddingValues(bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(channels, key = { _, c -> c.id }) { index, ch ->
                            GuideRow(
                                vm = vm,
                                channel = ch,
                                channels = channels,
                                screenKey = screenKey,
                                windowStart = windowStart,
                                now = now,
                                areaWidth = areaWidth,
                                dpPerMs = dpPerMs,
                                pending = pending,
                                onPendingDone = { pending = null },
                                onFocus = { p ->
                                    focused = ch to p
                                    // Resting on a channel previews it in the corner, like YouTube TV.
                                    vm.heroFocus = heroFor(ch, p?.takeIf { it.isOnNow(System.currentTimeMillis()) })
                                },
                                onEdge = { right, cellStart, cellEnd ->
                                    if (right) shift(true, ch.id, cellEnd) else shift(false, ch.id, cellStart - 60_000L)
                                },
                                canGoBack = windowStart > minStart,
                                canGoForward = windowStart < maxStart,
                                areaModifier = if (index == 0) Modifier.focusRequester(gridFocus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }

    if (pickingGroup) {
        GroupPicker(
            groups = catalog.sportsGroups + catalog.groups.filter { it !in catalog.sportsGroupSet },
            counts = { catalog.byGroup[it]?.size ?: 0 },
            onPick = { g ->
                vm.updateGuideFilter("group:$g")
                pickingGroup = false
                focusGridAfterPick = true
            },
            onDismiss = {
                pickingGroup = false
                focusGridAfterPick = true
            },
        )
    }
}

@Composable
private fun GuideRow(
    vm: AppViewModel,
    channel: Channel,
    channels: List<Channel>,
    screenKey: String,
    windowStart: Long,
    now: Long,
    areaWidth: Dp,
    dpPerMs: Float,
    pending: PendingFocus?,
    onPendingDone: () -> Unit,
    onFocus: (Program?) -> Unit,
    onEdge: (right: Boolean, cellStart: Long, cellEnd: Long) -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    areaModifier: Modifier,
) {
    // Fetch the guide for rows that stay on screen for a moment (not while scrolling past).
    LaunchedEffect(channel.id) {
        delay(250)
        vm.requestEpg(channel)
    }
    val windowEnd = windowStart + WINDOW_MS
    val programs = vm.programsFor(channel.id)
    val cells = remember(programs, windowStart) { buildCells(programs, windowStart, windowEnd) }

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        ChannelCell(vm, channel, channels, Modifier.width(CHANNEL_COL).fillMaxHeight(), onFocus = { onFocus(vm.nowPlaying(channel.id, now)) })
        Spacer(Modifier.width(8.dp))
        Box(areaModifier.width(areaWidth).fillMaxHeight()) {
            cells.forEachIndexed { i, cell ->
                val start = maxOf(cell.start, windowStart)
                val end = minOf(cell.end, windowEnd)
                val width = ((end - start) * dpPerMs).dp - 3.dp
                if (width <= 2.dp) return@forEachIndexed
                // Keyed by time so moving the window gives each airing its own cell (and focus events).
                key(cell.start) {
                ProgramCell(
                    vm = vm,
                    channel = channel,
                    channels = channels,
                    program = cell.program,
                    cellStart = cell.start,
                    cellEnd = cell.end,
                    now = now,
                    modifier = Modifier
                        .offset(x = ((start - windowStart) * dpPerMs).dp)
                        .width(width)
                        .fillMaxHeight(),
                    screenKey = screenKey,
                    focusNow = pending != null && pending.channelId == channel.id && pending.time >= cell.start && pending.time < cell.end,
                    onFocusTaken = onPendingDone,
                    onFocus = { onFocus(cell.program) },
                    onLeftEdge = if (i == 0 && cell.start <= windowStart && canGoBack) ({ onEdge(false, cell.start, cell.end) }) else null,
                    onRightEdge = if (i == cells.lastIndex && cell.end >= windowEnd && canGoForward) ({ onEdge(true, cell.start, cell.end) }) else null,
                )
                }
            }
            if (now in windowStart until windowEnd) {
                Box(Modifier.offset(x = ((now - windowStart) * dpPerMs).dp - 1.dp).width(2.dp).fillMaxHeight().background(AppColors.Live.copy(alpha = 0.8f)))
            }
        }
    }
}

private data class GuideCell(val start: Long, val end: Long, val program: Program?)

/** Programs overlapping the window, with "no information" cells filling any gaps. */
private fun buildCells(programs: List<Program>, from: Long, to: Long): List<GuideCell> {
    val out = ArrayList<GuideCell>()
    var cursor = from
    for (p in programs) {
        if (p.endMillis <= cursor || p.startMillis >= to) continue
        val start = maxOf(p.startMillis, cursor)
        if (start > cursor) out += GuideCell(cursor, start, null)
        out += GuideCell(start, p.endMillis, p)
        cursor = p.endMillis
    }
    if (cursor < to) out += GuideCell(cursor, maxOf(to, cursor + SLOT_MS), null)
    return out
}

@Composable
private fun ChannelCell(vm: AppViewModel, channel: Channel, channels: List<Channel>, modifier: Modifier, onFocus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = { vm.playChannel(channel, channels) },
        onLongClick = { channelMenu(vm, channel, channels) },
        modifier = modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(6.dp),
        containerColor = Color(0xFF1A1A1A),
        focusedContainerColor = Color.White,
        focusedScale = 1.02f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, 42.dp, background = if (focused) Color(0xFF222222) else Color(0xFF262626))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    cleanChannelName(channel.name),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (focused) Color.Black else AppColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp,
                )
                if (channel.num > 0) Text("${channel.num}", fontSize = 11.sp, color = if (focused) Color(0xFF555555) else AppColors.TextFaint)
            }
            if (vm.isFavoriteChannel(channel.id)) Icon(Icons.Star, null, Modifier.size(14.dp), tint = if (focused) Color.Black else AppColors.Warn)
        }
    }
}

@Composable
private fun ProgramCell(
    vm: AppViewModel,
    channel: Channel,
    channels: List<Channel>,
    program: Program?,
    cellStart: Long,
    cellEnd: Long,
    now: Long,
    modifier: Modifier,
    screenKey: String,
    focusNow: Boolean,
    onFocusTaken: () -> Unit,
    onFocus: () -> Unit,
    onLeftEdge: (() -> Unit)?,
    onRightEdge: (() -> Unit)?,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(focusNow) {
        if (focusNow && requester.requestFocusSafely(40)) onFocusTaken()
    }
    var focused by remember { mutableStateOf(false) }
    val onNow = now in cellStart until cellEnd
    val past = cellEnd <= now
    val replay = program != null && past && vm.catchupUrl(channel, program) != null
    val recording = program != null && vm.recordingForProgram(channel.id, program.startMillis) != null
    val shape = RoundedCornerShape(6.dp)

    Surface(
        onClick = {
            when {
                onNow -> vm.playChannel(channel, channels)
                program != null -> programMenu(vm, channel, program, channels)
                else -> channelMenu(vm, channel, channels)
            }
        },
        onLongClick = {
            OkKeyGate.swallowRelease()
            if (program != null) programMenu(vm, channel, program, channels) else channelMenu(vm, channel, channels)
        },
        modifier = modifier
            .rememberFocus(vm, screenKey, "${channel.id}@$cellStart")
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    ev.key == Key.DirectionRight && onRightEdge != null -> { onRightEdge(); true }
                    ev.key == Key.DirectionLeft && onLeftEdge != null -> { onLeftEdge(); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (onNow) Color(0xFF2A2A2A) else Color(0xFF1C1C1C),
            contentColor = if (past && !replay) AppColors.TextFaint else AppColors.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    program?.title ?: if (onNow) cleanChannelName(channel.name) else "No information",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (program != null) {
                    Text(
                        if (onNow) minutesLeft(program.endMillis, now) else formatRange(program.startMillis, program.endMillis),
                        fontSize = 11.sp,
                        color = if (focused) Color(0xFF555555) else AppColors.TextDim,
                        maxLines = 1,
                    )
                }
            }
            if (recording) Box(Modifier.size(8.dp).background(AppColors.Live, CircleShape))
            if (replay) Icon(Icons.Restart, "Replay", Modifier.size(14.dp))
        }
    }
}

/** Provider categories, sports first. Focus stays inside the sheet until something is picked. */
@Composable
private fun GroupPicker(groups: List<String>, counts: (String) -> Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(120) }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color(0xB3000000))) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(420.dp).fillMaxHeight().background(Color(0xFF1F1F1F))
                .trapFocus().padding(start = 20.dp, end = 20.dp, top = 84.dp, bottom = 28.dp),
        ) {
            Text("Categories", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("From your provider", fontSize = 13.sp, color = AppColors.TextDim)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(groups, key = { _, g -> g }) { i, g ->
                    SettingRow(g, { onPick(g) }, if (i == 0) Modifier.focusRequester(first) else Modifier, value = "${counts(g)}")
                }
            }
        }
    }
}
