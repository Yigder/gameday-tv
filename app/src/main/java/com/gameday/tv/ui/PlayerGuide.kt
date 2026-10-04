package com.gameday.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.IptvCatalog
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// The player's guide (hold Back): channels with what's on, and the games, over the video, which
// keeps playing. Each side has one filter. Picking something plays it.
// ---------------------------------------------------------------------------------------------

/** A guide filter: [key] is what's stored ([AppViewModel.guideChannelFilter] / [AppViewModel.guideSportsFilter]). */
private data class GuideFilter(val key: String, val label: String)

@Composable
fun PlayerGuide(vm: AppViewModel, current: Channel?, live: Boolean, onClose: () -> Unit) {
    val catalog = vm.catalog
    // The filter list replaces the list while it's open; Back returns from it first.
    var choosing by remember { mutableStateOf(false) }
    BackHandler(enabled = choosing) { choosing = false }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val channelFilters = remember(catalog, vm.favoriteChannels.size, vm.recentChannels.size) { channelFilters(vm, catalog) }
    val channelKey = (vm.guideChannelFilter ?: current?.group?.let { "g:$it" })
        ?.takeIf { k -> channelFilters.any { it.key == k } } ?: "all"
    val sportsFilters = sportsFilters(vm)
    val sportsKey = vm.guideSportsFilter.takeIf { k -> sportsFilters.any { it.key == k } } ?: "all"
    val sports = vm.guideTab == 1
    val filters = if (sports) sportsFilters else channelFilters
    val selected = if (sports) sportsKey else channelKey

    Box(Modifier.fillMaxSize()) {
        // Dark behind the guide, clear on the right so the video stays in view.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xF5000000), 0.42f to Color(0xEB000000), 0.62f to Color(0x99000000), 0.78f to Color.Transparent),
            ),
        )
        Column(Modifier.fillMaxHeight().width(580.dp).padding(start = 48.dp, end = 24.dp, top = 32.dp).trapFocus()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Channels", !sports, { vm.guideTab = 0; choosing = false })
                Chip("Sports", sports, { vm.guideTab = 1; choosing = false })
                Spacer(Modifier.weight(1f))
                Chip(filters.firstOrNull { it.key == selected }?.label ?: "All", false, { choosing = !choosing }, icon = Icons.Filter)
            }
            Spacer(Modifier.height(16.dp))
            when {
                choosing -> FilterList(filters, selected) { key ->
                    if (sports) vm.guideSportsFilter = key else vm.guideChannelFilter = key
                    choosing = false
                }
                sports -> key(sportsKey) { GuideSports(vm, sportsKey, current.takeIf { live }, onClose) }
                catalog == null -> Text("Your channels aren't loaded yet.", fontSize = 14.sp, color = AppColors.TextDim)
                else -> key(channelKey) {
                    GuideChannels(vm, channelsFor(vm, catalog, channelKey), current, now) { ch, list ->
                        if (ch.id != current?.id) vm.playChannel(ch, list)
                        onClose()
                    }
                }
            }
        }
    }
}

private fun channelFilters(vm: AppViewModel, catalog: IptvCatalog?): List<GuideFilter> = buildList {
    if (vm.favoriteChannels.isNotEmpty()) add(GuideFilter("fav", "Favorites"))
    if (vm.recentChannels.isNotEmpty()) add(GuideFilter("recent", "Recent"))
    add(GuideFilter("all", "All channels"))
    if (catalog == null) return@buildList
    if (catalog.sportsGroups.isNotEmpty()) add(GuideFilter("sports", "Sports channels"))
    catalog.groups.forEach { add(GuideFilter("g:$it", it)) }
}

private fun channelsFor(vm: AppViewModel, catalog: IptvCatalog, key: String): List<Channel> = when {
    key == "fav" -> vm.favoriteChannels
    key == "recent" -> vm.recentChannels
    key == "sports" -> catalog.sportsChannels
    key.startsWith("g:") -> catalog.byGroup[key.removePrefix("g:")].orEmpty()
    else -> catalog.channels
}

private fun sportsFilters(vm: AppViewModel): List<GuideFilter> = buildList {
    add(GuideFilter("all", "All sports"))
    if (vm.favorites.isNotEmpty()) add(GuideFilter("mine", "Your teams"))
    vm.followedPicks.forEach { add(GuideFilter(it.key, it.label)) }
}

@Composable
private fun FilterList(filters: List<GuideFilter>, selected: String, onPick: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val start = filters.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) { focus.requestFocusSafely(80) }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        items(filters, key = { it.key }) { f ->
            val on = f.key == selected
            FocusSurface(
                onClick = { onPick(f.key) },
                modifier = Modifier.fillMaxWidth().then(if (on) Modifier.focusRequester(focus) else Modifier),
                containerColor = Color.Transparent,
                focusedContainerColor = Color(0x29FFFFFF),
                focusedScale = 1f,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (on) Icon(Icons.Check, null, Modifier.size(18.dp), tint = AppColors.Text)
                }
            }
        }
    }
}

// ---- Channels ----

@Composable
private fun GuideChannels(vm: AppViewModel, channels: List<Channel>, current: Channel?, now: Long, onPick: (Channel, List<Channel>) -> Unit) {
    if (channels.isEmpty()) {
        Text("No channels here.", fontSize = 14.sp, color = AppColors.TextDim)
        return
    }
    // Opens on the channel playing (or the top of the list).
    val start = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusSafely(80) }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        items(channels, key = { it.id }) { ch ->
            GuideChannelRow(
                vm, ch, now,
                watching = ch.id == current?.id,
                onClick = { onPick(ch, channels) },
                modifier = if (ch == channels[start]) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

@Composable
private fun GuideChannelRow(vm: AppViewModel, channel: Channel, now: Long, watching: Boolean, onClick: () -> Unit, modifier: Modifier) {
    LaunchedEffect(channel.id) { vm.requestEpg(channel) }
    val program = vm.nowPlaying(channel.id, now)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
        focusedContainerColor = Color(0x29FFFFFF),
        focusedScale = 1f,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, 40.dp, background = Color(0x1AFFFFFF))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    cleanChannelName(channel.name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (watching) AppColors.Live else AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    program?.let { "${it.title}  ·  ${minutesLeft(it.endMillis, now)}" } ?: channel.group,
                    fontSize = 13.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (program != null) {
                    Spacer(Modifier.height(5.dp))
                    ProgressLine(program.progress(now), Modifier.width(140.dp), track = Color(0x33FFFFFF))
                }
            }
            if (watching) {
                Spacer(Modifier.width(10.dp))
                LiveDot()
            }
        }
    }
}

// ---- Sports ----

private const val GUIDE_CARD_WIDTH = 236

@Composable
private fun GuideSports(vm: AppViewModel, filter: String, multiviewWith: Channel?, onClose: () -> Unit) {
    val pick = Leagues.pick(filter)
    val keys = pick?.leagueKeys.orEmpty()
    val games: List<Game> = when {
        filter == "all" -> vm.games
        filter == "mine" -> vm.favoriteGames
        else -> vm.games.filter { it.league.key in keys }
    }
    val tours: List<Tournament> = when {
        filter == "all" -> vm.tournaments
        pick?.sport == "golf" -> vm.tournaments.filter { it.tour.key in keys }
        else -> emptyList()
    }
    // Same split as Sports: a tournament is only live while a round is being played.
    val soon = System.currentTimeMillis() + 24 * 60 * 60_000L
    val live: List<Any> = games.filter { it.state == GameState.LIVE } + tours.filter { it.roundInProgress }
    val upcoming: List<Any> = (games.filter { it.state == GameState.PRE && it.startMillis < soon } +
        tours.filter { it.state == GameState.PRE || (it.state == GameState.LIVE && !it.roundInProgress) })
        .sortedBy { if (it is Game) it.startMillis else (it as Tournament).startMillis }

    if (live.isEmpty() && upcoming.isEmpty()) {
        Text("Nothing live or coming up today.", fontSize = 14.sp, color = AppColors.TextDim)
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusSafely(80) }
    val first = (live + upcoming).first()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp, start = 4.dp, end = 4.dp),
    ) {
        if (live.isNotEmpty()) {
            item(key = "live-h", span = { GridItemSpan(maxLineSpan) }) { GuideHeader("Live now") }
            items(live, key = { "l:" + itemKey(it) }) { GuideEventCard(vm, it, if (it === first) focus else null, multiviewWith, onClose) }
        }
        if (upcoming.isNotEmpty()) {
            item(key = "up-h", span = { GridItemSpan(maxLineSpan) }) { GuideHeader("Coming up") }
            items(upcoming, key = { "u:" + itemKey(it) }) { GuideEventCard(vm, it, if (it === first) focus else null, multiviewWith, onClose) }
        }
    }
}

private fun itemKey(item: Any): String = if (item is Game) item.id else (item as Tournament).id

@Composable
private fun GuideHeader(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextDim, modifier = Modifier.padding(top = 4.dp))
}

/**
 * A Sports card: live ones play (here, in the player); upcoming ones open their page. Holding OK
 * offers Multiview with what's playing kept on screen.
 */
@Composable
private fun GuideEventCard(vm: AppViewModel, item: Any, focus: FocusRequester?, multiviewWith: Channel?, onClose: () -> Unit) {
    // Back from the menu returns here, not to wherever Android puts focus.
    val self = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(vm.dialog == null) {
        if (vm.dialog == null && menuOpen) {
            menuOpen = false
            self.requestFocusSafely(60)
        }
    }
    val modifier = (if (focus != null) Modifier.focusRequester(focus) else Modifier).focusRequester(self)
    val game = item as? Game
    val tournament = item as? Tournament
    val live = game?.state == GameState.LIVE || tournament?.roundInProgress == true
    val title = game?.title ?: tournament?.name.orEmpty()
    fun watch() {
        onClose()
        when {
            game != null -> if (live) vm.watchGame(game) else vm.openGame(game)
            tournament != null -> if (live) vm.watchTournament(tournament) else vm.openTournament(tournament)
        }
    }
    fun menu() {
        menuOpen = true
        vm.showDialog(
            AppDialog(
                title = title,
                actions = listOfNotNull(
                    DialogAction("Add to Multiview", Icons.Multiview) {
                        menuOpen = false
                        vm.dismissDialog()
                        onClose()
                        vm.multiviewWithEvent(game, tournament, multiviewWith)
                    },
                    if (live) DialogAction("Watch", Icons.Play) {
                        menuOpen = false
                        vm.dismissDialog()
                        watch()
                    } else null,
                ),
            ),
        )
    }
    when {
        game != null -> MediaCard(
            title = game.title,
            subtitle = "${game.league.label} · ${statusLine(game, vm.hideScores)}",
            onClick = { watch() },
            onLongClick = { menu() },
            modifier = modifier,
            width = GUIDE_CARD_WIDTH.dp,
        ) { GameThumb(game, vm.hideScores, vm.recordingForEvent(game.id) != null) }
        tournament != null -> MediaCard(
            title = tournament.name,
            subtitle = "${tournament.tour.label} · ${tournament.detail}",
            onClick = { watch() },
            onLongClick = { menu() },
            modifier = modifier,
            width = GUIDE_CARD_WIDTH.dp,
        ) { TournamentThumb(tournament, vm.hideScores) }
    }
}
