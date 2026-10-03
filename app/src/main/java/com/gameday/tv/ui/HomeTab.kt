package com.gameday.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.GameState
import com.gameday.tv.data.RecStatus
import com.gameday.tv.ui.theme.AppColors

@Composable
fun HomeTab(vm: AppViewModel) {
    val screenKey = "main:${Tab.HOME}"
    var hero by remember { mutableStateOf<HeroInfo?>(null) }
    val content = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    val tracker = remember { FocusTracker() }
    if (vm.tabWantsFocus) InitialFocus(vm, screenKey, content, tracker = tracker)

    val hasProvider = vm.catalog != null
    LaunchedEffect(hasProvider) {
        if (hasProvider) {
            vm.ensureMovies()
            vm.ensureSeries()
        }
    }
    var presets by remember { mutableStateOf<List<MultiviewPreset>>(emptyList()) }
    LaunchedEffect(vm.games, hasProvider) { presets = if (hasProvider) vm.multiviewPresets() else emptyList() }

    val live = vm.liveGames.sortedByDescending { vm.isFavoriteGame(it) }
    val liveTours = vm.tournaments.filter { it.roundInProgress }
    val yourGames = vm.favoriteGames.filter { it.state != GameState.FINAL || System.currentTimeMillis() - it.startMillis < 36 * 3_600_000L }
    val upcoming = vm.upcomingGames(36).take(25)
    val finals = vm.games.filter { it.state == GameState.FINAL }.sortedByDescending { it.startMillis }.take(20)
    val channels = (vm.favoriteChannels + vm.recentChannels).distinctBy { it.id }.take(20)
    val recordings = vm.recordings.filter { it.status == RecStatus.DONE || it.status == RecStatus.RECORDING }.sortedByDescending { it.startMillis }.take(15)
    val movies = (vm.movies as? VodState.Ready)?.library?.items?.let { list -> remember(list) { list.sortedByDescending { it.added }.take(24) } }.orEmpty()
    val shows = (vm.series as? VodState.Ready)?.library?.items?.let { list -> remember(list) { list.sortedByDescending { it.lastModified }.take(24) } }.orEmpty()

    // Something to show before anything is focused.
    val defaultHero = when {
        live.isNotEmpty() -> heroFor(live.first(), vm.hideScores)
        upcoming.isNotEmpty() -> heroFor(upcoming.first(), vm.hideScores)
        else -> HeroInfo("Welcome, ${vm.profile?.name ?: ""}", listOf("GameDay TV"), "Live sports, your channels, movies and shows.")
    }

    // Rows arrive as scores and channels load. Until the viewer presses something, keep the top row in focus.
    var interacted by remember { mutableStateOf(false) }
    val firstRow = when {
        !hasProvider -> "provider"
        vm.resume.isNotEmpty() -> "resume"
        live.isNotEmpty() || liveTours.isNotEmpty() -> "live"
        presets.isNotEmpty() -> "mv"
        yourGames.isNotEmpty() -> "yours"
        channels.isNotEmpty() -> "channels"
        else -> "other"
    }
    LaunchedEffect(firstRow) {
        if (interacted || !vm.tabWantsFocus || !tracker.has) return@LaunchedEffect
        listState.scrollToItem(0)
        content.requestFocusSafely(50)
    }

    Column(Modifier.fillMaxSize().onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) interacted = true; false }) {
        Hero(hero ?: defaultHero, Modifier.fillMaxWidth().height(250.dp).padding(top = 40.dp), vm.hideScores)
        Box(Modifier.weight(1f)) {
            PivotScroll(offset = ROW_TITLE) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().focusRequester(content).trackFocus(tracker),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 260.dp),
                ) {
                    if (!hasProvider) {
                        item(key = "provider") { ProviderBanner(vm) }
                    }
                    if (vm.resume.isNotEmpty()) {
                        cardRow("resume", "Continue watching", nav) {
                            items(vm.resume.toList(), key = { "r:" + it.key }) { ResumeCard(vm, it, screenKey) { h -> hero = h } }
                        }
                    }
                    if (live.isNotEmpty() || liveTours.isNotEmpty()) {
                        cardRow("live", "Live sports", nav) {
                            items(liveTours, key = { it.id }) { TournamentCard(vm, it, screenKey) { h -> hero = h } }
                            items(live, key = { it.id }) { GameCard(vm, it, screenKey, { h -> hero = h }, "live:") }
                        }
                    }
                    if (presets.isNotEmpty()) {
                        cardRow("mv", "Multiview", nav) {
                            items(presets, key = { it.title }) { PresetCard(vm, it, screenKey) { h -> hero = h } }
                        }
                    }
                    if (yourGames.isNotEmpty()) {
                        cardRow("yours", "Your teams", nav) {
                            items(yourGames, key = { "y:" + it.id }) { GameCard(vm, it, screenKey, { h -> hero = h }, "y:") }
                        }
                    }
                    if (channels.isNotEmpty()) {
                        cardRow("channels", "Your channels", nav) {
                            items(channels, key = { it.id }) { ChannelCard(vm, it, screenKey, channels, { h -> hero = h }) }
                        }
                    }
                    if (upcoming.isNotEmpty()) {
                        cardRow("upcoming", "Coming up", nav) {
                            items(upcoming, key = { "u:" + it.id }) { GameCard(vm, it, screenKey, { h -> hero = h }, "u:") }
                        }
                    }
                    if (recordings.isNotEmpty()) {
                        cardRow("recordings", "Recordings", nav) {
                            items(recordings, key = { it.id }) { RecordingCard(vm, it, screenKey) { h -> hero = h } }
                        }
                    }
                    if (movies.isNotEmpty()) {
                        cardRow("movies", "New movies", nav) {
                            items(movies, key = { it.id }) { MovieCard(vm, it, screenKey) { h -> hero = h } }
                            item(key = "more") { MoreCard("All movies", { vm.navigate(Screen.Browse(BrowseKind.MOVIES)) }, POSTER_WIDTH, 2f / 3f) }
                        }
                    }
                    if (shows.isNotEmpty()) {
                        cardRow("shows", "Shows", nav) {
                            items(shows, key = { it.id }) { SeriesCard(vm, it, screenKey) { h -> hero = h } }
                            item(key = "more") { MoreCard("All shows", { vm.navigate(Screen.Browse(BrowseKind.SHOWS)) }, POSTER_WIDTH, 2f / 3f) }
                        }
                    }
                    if (finals.isNotEmpty()) {
                        cardRow("finals", if (vm.hideScores) "Recent games" else "Final scores", nav) {
                            items(finals, key = { "f:" + it.id }) { GameCard(vm, it, screenKey, { h -> hero = h }, "f:") }
                        }
                    }
                    if (vm.scoresLoading && vm.games.isEmpty()) {
                        item(key = "loading") { LoadingState("Loading live sports…") }
                    } else if (vm.scoresError != null && vm.games.isEmpty()) {
                        item(key = "error") { EmptyState("Couldn't load scores", vm.scoresError) { PillButton("Try again", { vm.refreshScoresNow() }) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderBanner(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Connect your TV provider to watch", fontSize = 18.sp)
            Text(
                when (val s = vm.iptv) {
                    is IptvStatus.Failed -> s.message
                    IptvStatus.Loading -> "Connecting to your provider…"
                    else -> "Add your IPTV login to watch games, browse the guide, record, and use Multiview."
                },
                fontSize = 13.sp,
                color = AppColors.TextDim,
            )
        }
        Spacer(Modifier.width(16.dp))
        if (vm.iptv is IptvStatus.Failed) PillButton("Retry", { vm.reloadChannels() })
        Spacer(Modifier.width(10.dp))
        PillButton("Set up provider", { vm.navigate(Screen.Provider) }, primary = true)
    }
}
