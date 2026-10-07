package com.gameday.tv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameday.tv.data.Channel
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.RecStatus
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.AppDialog
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.Avatar
import com.gameday.tv.ui.CroppedVideoSurface
import com.gameday.tv.ui.DialogAction
import com.gameday.tv.ui.Icons
import com.gameday.tv.ui.IptvStatus
import com.gameday.tv.ui.LiveDot
import com.gameday.tv.ui.Logo
import com.gameday.tv.ui.Screen
import com.gameday.tv.ui.Tab
import com.gameday.tv.ui.bytesText
import com.gameday.tv.ui.cleanChannelName
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

/** Sports, Live and Library, with a tab bar at the bottom and the mini player above it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel, stateHolder: SaveableStateHolder) {
    val tab = vm.tab
    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            TopAppBar(
                title = { Logo(18.dp) },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.Search) }) { AppIcon(Icons.Search, "Search") }
                    IconButton(onClick = { profileMenu(vm) }) { Avatar(vm.profile, 30.dp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Background),
            )
        },
        bottomBar = {
            Column {
                MiniPlayer(vm)
                NavigationBar(containerColor = AppColors.Surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { vm.selectTab(t) },
                            icon = { AppIcon(tabIcon(t), null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                indicatorColor = AppColors.Text,
                                unselectedIconColor = AppColors.TextDim,
                                unselectedTextColor = AppColors.TextDim,
                                selectedTextColor = AppColors.Text,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            stateHolder.SaveableStateProvider("main:$tab") {
                when (tab) {
                    Tab.SPORTS -> SportsTab(vm)
                    Tab.LIVE -> LiveTab(vm)
                    Tab.LIBRARY -> LibraryTab(vm)
                }
            }
        }
    }
}

private fun tabIcon(t: Tab) = when (t) {
    Tab.SPORTS -> Icons.Trophy
    Tab.LIVE -> Icons.LiveTv
    Tab.LIBRARY -> Icons.Library
}

private fun profileMenu(vm: AppViewModel) {
    val p = vm.profile
    vm.showDialog(
        AppDialog(
            title = p?.name ?: "Profile",
            subtitle = vm.account?.email,
            actions = listOf(
                DialogAction("Switch profile", Icons.Person) { vm.dismissDialog(); vm.switchProfile() },
                DialogAction("Multiview", Icons.Multiview) { vm.dismissDialog(); vm.multiviewWith(null) },
                DialogAction("Settings", Icons.Settings) { vm.dismissDialog(); vm.openSettings() },
                DialogAction("Sign out", Icons.Logout) { vm.dismissDialog(); vm.signOut() },
            ),
        ),
    )
}

/**
 * What you were watching keeps playing in a small window above the tabs (with sound or muted, per
 * Settings › Playback). Tap it to go back to full screen.
 */
@Composable
private fun MiniPlayer(vm: AppViewModel) {
    val channel = vm.backgroundChannel ?: return
    if (vm.backgroundVideo == "off" || !vm.hasMainStream) return
    val stream = vm.mainStream
    if (stream.currentKey != channel.id) return
    LaunchedEffect(channel.id) { vm.requestEpg(channel) }
    val program = vm.nowPlaying(channel.id)
    Column {
        HorizontalDivider(color = AppColors.Border)
        Row(
            Modifier
                .fillMaxWidth()
                .background(AppColors.Raised)
                .clickable { vm.watchBackgroundFullScreen() }
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(128.dp).aspectRatio(16f / 9f).background(Color.Black)) {
                CroppedVideoSurface(stream.player, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(program?.title ?: cleanChannelName(channel.name), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(cleanChannelName(channel.name), fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            var muted by remember(channel.id) { mutableStateOf(stream.volume == 0f) }
            IconButton(onClick = { muted = !muted; stream.volume = if (muted) 0f else 1f }) {
                AppIcon(Icons.Volume, if (muted) "Unmute" else "Mute", tint = if (muted) AppColors.TextFaint else AppColors.Text)
            }
            IconButton(onClick = { vm.stopMainStream() }) { AppIcon(Icons.Close, "Close") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sports
// ---------------------------------------------------------------------------------------------

private sealed interface SportsItem {
    val key: String
    val start: Long

    data class G(val game: Game) : SportsItem {
        override val key = "g:" + game.id
        override val start = game.startMillis
    }

    data class T(val t: Tournament) : SportsItem {
        override val key = "t:" + t.id
        override val start = t.startMillis
    }
}

/** Sport chips, then live, multiview, upcoming and final rows, like the TV's Sports tab. Pull down to refresh scores. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SportsTab(vm: AppViewModel) {
    var filter by rememberSaveable { mutableStateOf("all") }
    val pick = Leagues.pick(filter)
    val keys = pick?.leagueKeys
    val pool: List<Game> = when {
        filter == "all" -> vm.games
        filter == "mine" -> vm.favoriteGames
        else -> vm.games.filter { it.league.key in keys.orEmpty() }
    }
    val tours = when {
        filter == "all" -> vm.tournaments
        pick?.sport == "golf" -> vm.tournaments.filter { it.tour.key in keys.orEmpty() }
        else -> emptyList()
    }
    // A tournament is only "Live now" while a round is being played.
    val live = pool.filter { it.state == GameState.LIVE }.map { SportsItem.G(it) } + tours.filter { it.roundInProgress }.map { SportsItem.T(it) }
    val upcoming = (pool.filter { it.state == GameState.PRE }.map { SportsItem.G(it) } +
        tours.filter { it.state == GameState.PRE || (it.state == GameState.LIVE && !it.roundInProgress) }.map { SportsItem.T(it) })
        .sortedBy { it.start }
    val finals = (pool.filter { it.state == GameState.FINAL }.map { SportsItem.G(it) } + tours.filter { it.state == GameState.FINAL }.map { SportsItem.T(it) })
        .sortedByDescending { it.start }
    val liveNfl = pool.count { it.league.key == "nfl" && it.state == GameState.LIVE }
    val redZone = remember(vm.catalog) { vm.redZoneChannels() }

    var teams by remember(filter) { mutableStateOf<List<FavoriteTeam>>(emptyList()) }
    var channels by remember(filter) { mutableStateOf<List<Channel>>(emptyList()) }
    LaunchedEffect(filter, vm.catalog) {
        if (pick != null) {
            if (pick.sport != "golf") teams = runCatching { vm.loadTeams(pick.leagues.first()) }.getOrDefault(emptyList())
            channels = pick.leagues.flatMap { vm.leagueChannels(it) }.distinctBy { it.id }.take(30)
        } else if (filter == "all") {
            channels = vm.catalog?.sportsChannels?.take(30).orEmpty()
        }
    }
    val hasProvider = vm.catalog != null
    val presets = vm.multiviewRow
    LaunchedEffect(vm.games, vm.catalog, vm.favoriteChannelIds.size) { vm.multiviewRow = if (hasProvider) vm.multiviewPresets() else emptyList() }

    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        vm.refreshScoresNow()
        delay(1_200)
        refreshing = false
    }
    val listState = rememberLazyListState()

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "all") { FilterChip(filter == "all", { filter = "all" }, label = { Text("All sports") }) }
                    if (vm.favorites.isNotEmpty()) {
                        item(key = "mine") {
                            FilterChip(filter == "mine", { filter = "mine" }, label = { Text("Your teams") },
                                leadingIcon = { AppIcon(Icons.Star, null, size = 16.dp) })
                        }
                    }
                    items(vm.followedPicks, key = { it.key }) { p -> FilterChip(filter == p.key, { filter = p.key }, label = { Text(p.label) }) }
                }
            }
            if (!hasProvider) item(key = "provider") { ProviderBanner(vm) }
            if (live.isNotEmpty()) {
                rowSection("live", "Live now") {
                    if (liveNfl > 0 && redZone.isNotEmpty()) item(key = "redzone") { RedZoneTile(vm, redZone, liveNfl) }
                    sportsItems(vm, live, "l:")
                }
            }
            if (filter == "all" && presets.isNotEmpty()) {
                rowSection("mv", "Watch in Multiview") {
                    items(presets, key = { it.key }) { PresetTile(vm, it) }
                }
            }
            if (upcoming.isNotEmpty()) rowSection("upcoming", "Upcoming") { sportsItems(vm, upcoming.take(40), "u:") }
            if (filter == "mine") {
                rowSection("teams", "Your teams") { items(vm.favorites.toList(), key = { it.key }) { TeamTile(vm, it) } }
            }
            if (finals.isNotEmpty()) rowSection("finals", if (vm.hideScores) "Earlier" else "Final scores") { sportsItems(vm, finals.take(40), "f:") }
            if (channels.isNotEmpty()) {
                rowSection("channels", if (pick != null) "${pick.label} channels" else "Sports channels") {
                    items(channels, key = { it.id }) { ChannelTile(vm, it, channels) }
                }
            }
            if (teams.isNotEmpty()) {
                rowSection("allteams", "Teams") {
                    items(teams, key = { it.key }) { TeamTile(vm, vm.favoriteTeam(it.leagueKey, it.id) ?: it) }
                }
            }
            if (live.isEmpty() && upcoming.isEmpty() && finals.isEmpty()) {
                item(key = "empty") {
                    MessageBlock(
                        when {
                            vm.scoresLoading -> "Loading games…"
                            vm.scoresError != null -> "Couldn't load scores"
                            else -> "No games scheduled"
                        },
                        vm.scoresError ?: if (filter == "mine") "None of your teams have games on today's scoreboard." else null,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sportsItems(vm: AppViewModel, list: List<SportsItem>, prefix: String) {
    items(list, key = { prefix + it.key }) { item ->
        when (item) {
            is SportsItem.G -> GameTile(vm, item.game)
            is SportsItem.T -> TournamentTile(vm, item.t)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Live
// ---------------------------------------------------------------------------------------------

/** The live guide as a list: what's on each channel now and next. Tap to watch, long-press for more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTab(vm: AppViewModel) {
    val catalog = vm.catalog
    if (catalog == null) {
        MessageBlock(
            when (vm.iptv) {
                IptvStatus.Loading -> "Loading your channels…"
                is IptvStatus.Failed -> "Couldn't load your channels"
                else -> "No TV provider yet"
            },
            (vm.iptv as? IptvStatus.Failed)?.message ?: "Connect your IPTV provider to see the live guide.",
            Modifier.padding(top = 48.dp),
        ) {
            if (vm.iptv is IptvStatus.Failed) androidx.compose.material3.OutlinedButton(onClick = { vm.reloadChannels() }) { Text("Retry") }
            androidx.compose.material3.Button(onClick = { vm.navigate(Screen.Provider()) }) { Text("Set up provider") }
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
    var pickingGroup by remember { mutableStateOf(false) }
    val filter = vm.guideFilter
    val channels = remember(filter, catalog, vm.hiddenGroups, vm.guideSort, vm.favoriteChannelIds.size, vm.recentChannelIds.size) { vm.guideChannels(filter) }
    val listState = rememberLazyListState()
    LaunchedEffect(filter) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val opts = listOf("sports" to "Sports", "all" to "All channels", "favorites" to "Favorites", "recent" to "Recently watched")
            items(opts, key = { it.first }) { (k, label) -> FilterChip(filter == k, { vm.updateGuideFilter(k) }, label = { Text(label) }) }
            item(key = "groups") {
                val isGroup = filter.startsWith("group:")
                FilterChip(isGroup, { pickingGroup = true }, label = { Text(if (isGroup) filter.removePrefix("group:") else "Categories", maxLines = 1) },
                    leadingIcon = { AppIcon(Icons.Guide, null, size = 16.dp) })
            }
        }
        if (channels.isEmpty()) {
            MessageBlock(
                when (filter) {
                    "favorites" -> "No favorite channels yet"
                    "recent" -> "Nothing watched yet"
                    else -> "No channels here"
                },
                if (filter == "favorites") "Long-press any channel and choose \"Add to favorite channels\"." else null,
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
            items(channels, key = { it.id }) { ch -> ChannelListRow(vm, ch, channels, now = now) }
        }
    }

    if (pickingGroup) {
        val state = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { pickingGroup = false }, sheetState = state, containerColor = AppColors.Raised) {
            Text("Categories", fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 24.dp))
            Text("From your provider", fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            val groups = catalog.sportsGroups + catalog.groups.filter { it !in catalog.sportsGroupSet }
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(groups, key = { it }) { g ->
                    SettingItem(g, { vm.updateGuideFilter("group:$g"); pickingGroup = false }, value = "${catalog.byGroup[g]?.size ?: 0}")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------------------------

private enum class LibSection(val label: String) {
    RECORDINGS("Recordings"),
    SCHEDULED("Scheduled"),
    CONTINUE("Continue watching"),
    TEAMS("Teams"),
    CHANNELS("Channels"),
}

@Composable
fun LibraryTab(vm: AppViewModel) {
    var section by rememberSaveable { mutableStateOf(LibSection.RECORDINGS) }
    val recordings = vm.recordings
    val done = recordings.filter { it.status == RecStatus.DONE || it.status == RecStatus.RECORDING || it.status == RecStatus.FAILED }
        .sortedByDescending { it.startMillis }
    val scheduled = recordings.filter { it.status == RecStatus.SCHEDULED }.sortedBy { it.startMillis }
    val continueWatching = vm.continueWatching

    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibSection.entries, key = { it.name }) { s ->
                val count = when (s) {
                    LibSection.RECORDINGS -> done.size
                    LibSection.SCHEDULED -> scheduled.size
                    LibSection.CONTINUE -> continueWatching.size
                    LibSection.TEAMS -> vm.favorites.size
                    LibSection.CHANNELS -> vm.favoriteChannelIds.size
                }
                FilterChip(section == s, { section = s }, label = { Text(if (count > 0) "${s.label}  $count" else s.label) })
            }
        }
        when (section) {
            LibSection.RECORDINGS -> LibGrid(done.isEmpty(), "No recordings yet",
                "Record a game or show from its menu (long-press), or turn on \"Record all games\" for a team.",
                footer = "${bytesText(vm.recordingsBytes())} used · ${bytesText(vm.recordingsFreeBytes())} free on this device") {
                items(done, key = { it.id }) { RecordingTile(vm, it, width = null) }
            }
            LibSection.SCHEDULED -> LibGrid(scheduled.isEmpty(), "Nothing scheduled",
                if (vm.canWakeExactly) null else "Tip: allow GameDay to set alarms (Settings › Recordings) so recordings start on time.") {
                items(scheduled, key = { it.id }) { RecordingTile(vm, it, width = null) }
            }
            LibSection.CONTINUE -> LibGrid(continueWatching.isEmpty(), "Nothing in progress", "Recordings you start show up here.") {
                items(continueWatching, key = { it.key }) { ResumeTile(vm, it, width = null) }
            }
            LibSection.TEAMS -> LibGrid(vm.favorites.isEmpty(), "No teams yet", "Add teams from Settings › Sports, or from any game or team page.", minWidth = 110) {
                items(vm.favorites.toList(), key = { it.key }) { TeamTile(vm, it, width = null) }
            }
            LibSection.CHANNELS -> {
                val channels = vm.favoriteChannels
                if (channels.isEmpty()) {
                    MessageBlock("No favorite channels", "Long-press a channel in Live and choose \"Add to favorite channels\".")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(channels, key = { it.id }) { ChannelListRow(vm, it, channels) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibGrid(
    empty: Boolean,
    emptyTitle: String,
    emptyMessage: String?,
    footer: String? = null,
    minWidth: Int = 220,
    content: LazyGridScope.() -> Unit,
) {
    if (empty) {
        MessageBlock(emptyTitle, emptyMessage)
        if (footer != null) Text(footer, fontSize = 12.sp, color = AppColors.TextFaint, modifier = Modifier.padding(horizontal = GUTTER))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minWidth.dp),
        contentPadding = PaddingValues(GUTTER),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        content()
        if (footer != null) {
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                Text(footer, fontSize = 12.sp, color = AppColors.TextFaint, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
