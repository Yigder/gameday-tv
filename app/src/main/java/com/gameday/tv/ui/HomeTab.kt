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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.MetaPreview
import com.gameday.tv.data.RecStatus
import com.gameday.tv.ui.theme.AppColors

/** One card in "Top picks": whatever is most worth watching right now, of any kind. */
private sealed interface Pick {
    val key: String

    data class LiveGame(val game: com.gameday.tv.data.Game) : Pick { override val key = "g:" + game.id }
    data class Golf(val t: com.gameday.tv.data.Tournament) : Pick { override val key = "t:" + t.id }
    data class OnNow(val channel: Channel) : Pick { override val key = "c:" + channel.id }
    data class Resume(val point: com.gameday.tv.data.ResumePoint) : Pick { override val key = "r:" + point.key }
    data class Rec(val r: com.gameday.tv.data.Recording) : Pick { override val key = "rec:" + r.id }
}

/**
 * Home, laid out like YouTube TV's: top picks, continue watching, what's on now, live sports,
 * multiviews, recordings, then movies and shows. Team schedules and finals live in Sports.
 */
@Composable
fun HomeTab(vm: AppViewModel) {
    val screenKey = "main:${Tab.HOME}"
    val content = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    val tracker = remember { FocusTracker() }
    if (vm.tabWantsFocus) InitialFocus(vm, screenKey, content, tracker = tracker)
    val onHero: (HeroInfo) -> Unit = { vm.heroFocus = it }

    val hasProvider = vm.catalog != null
    LaunchedEffect(vm.catalog, vm.movies is VodState.Idle) {
        if (hasProvider) {
            vm.ensureMovies()
            vm.ensureSeries()
        }
    }
    // Kept in the view model so the row is there at once when coming back (and focus returns to it).
    val presets = vm.homePresets
    LaunchedEffect(vm.games, vm.catalog, vm.favoriteChannelIds.size) { vm.homePresets = if (hasProvider) vm.multiviewPresets() else emptyList() }

    // Rows are computed only when their inputs change, not on every focus move.
    val live by remember { derivedStateOf { vm.liveGames.sortedByDescending { vm.isFavoriteGame(it) } } }
    val liveTours by remember { derivedStateOf { vm.tournaments.filter { it.roundInProgress } } }
    val onNow by remember {
        derivedStateOf {
            val cat = vm.catalog
            (vm.favoriteChannels + vm.recentChannels + cat?.sportsChannels.orEmpty().take(12)).distinctBy { it.id }.take(24)
        }
    }
    val recordings by remember {
        derivedStateOf { vm.recordings.filter { it.status == RecStatus.DONE || it.status == RecStatus.RECORDING }.sortedByDescending { it.startMillis }.take(15) }
    }
    val picks by remember {
        derivedStateOf {
            val out = ArrayList<Pick>()
            live.filter { vm.isFavoriteGame(it) }.forEach { out += Pick.LiveGame(it) }
            vm.resume.take(2).forEach { out += Pick.Resume(it) }
            vm.favoriteChannels.take(3).forEach { out += Pick.OnNow(it) }
            recordings.filter { it.status == RecStatus.DONE }.take(2).forEach { out += Pick.Rec(it) }
            live.filter { !vm.isFavoriteGame(it) }.take(4).forEach { out += Pick.LiveGame(it) }
            liveTours.take(1).forEach { out += Pick.Golf(it) }
            vm.recentChannels.take(3).forEach { out += Pick.OnNow(it) }
            out.distinctBy { it.key }.take(14)
        }
    }
    val movies = (vm.movies as? VodState.Ready)?.library?.items?.let { list -> remember(list) { list.sortedByDescending { it.added }.take(24) } }.orEmpty()
    val shows = (vm.series as? VodState.Ready)?.library?.items?.let { list -> remember(list) { list.sortedByDescending { it.lastModified }.take(24) } }.orEmpty()

    // Two add-on rows on Home (popular movies and shows); the rest are in On Demand.
    val addonRows = vm.addons.rows.take(2)
    val addonItems = vm.addons.rowItems
    LaunchedEffect(addonRows.map { it.key }) { addonRows.forEach { vm.addons.ensureRow(it) } }

    val defaultHero = when {
        live.isNotEmpty() -> heroFor(live.first(), vm.hideScores)
        else -> HeroInfo("Welcome, ${vm.profile?.name ?: ""}", listOf("GameDay TV"), "Live TV, sports, recordings, movies and shows.")
    }

    // Rows arrive as scores and channels load (a big lineup can take a while). Until the viewer
    // presses a button, keep focus on the top row as it changes — even if focus was parked on the
    // top bar while nothing was loaded yet.
    val shownAt = remember { android.os.SystemClock.uptimeMillis() }
    val firstRow = when {
        !hasProvider -> "provider"
        picks.isNotEmpty() -> "picks"
        vm.resume.isNotEmpty() -> "resume"
        onNow.isNotEmpty() -> "onnow"
        else -> "other"
    }
    LaunchedEffect(firstRow) {
        if (KeyActivity.lastKeyAt > shownAt || !vm.tabWantsFocus) return@LaunchedEffect
        if (vm.restoreFocusFor == screenKey) return@LaunchedEffect
        listState.scrollToItem(0)
        content.requestFocusSafely(80)
    }

    Column(Modifier.fillMaxSize()) {
        TabHero(vm, defaultHero, Modifier.fillMaxWidth().height(260.dp).padding(top = 64.dp), videoBehind = vm.backgroundShowing)
        Box(Modifier.weight(1f)) {
            PivotScroll(offset = ROW_TITLE) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().focusRequester(content).trackFocus(tracker),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 260.dp),
                ) {
                    if (!hasProvider) {
                        item(key = "provider") { ProviderBanner(vm) }
                    }
                    if (picks.isNotEmpty()) {
                        cardRow("picks", "Top picks for you", nav) {
                            items(picks, key = { "p:" + it.key }) { p ->
                                when (p) {
                                    is Pick.LiveGame -> GameCard(vm, p.game, screenKey, onHero, "p:")
                                    is Pick.Golf -> TournamentCard(vm, p.t, screenKey, onHero)
                                    is Pick.OnNow -> ChannelCard(vm, p.channel, screenKey, onNow, onHero, "p:")
                                    is Pick.Resume -> ResumeCard(vm, p.point, screenKey, onHero)
                                    is Pick.Rec -> RecordingCard(vm, p.r, screenKey, onHero)
                                }
                            }
                        }
                    }
                    if (vm.resume.isNotEmpty()) {
                        cardRow("resume", "Continue watching", nav) {
                            items(vm.resume.toList(), key = { "r:" + it.key }) { ResumeCard(vm, it, screenKey, onHero) }
                        }
                    }
                    if (onNow.isNotEmpty()) {
                        cardRow("onnow", "On now", nav) {
                            items(onNow, key = { "n:" + it.id }) { ChannelCard(vm, it, screenKey, onNow, onHero, "n:") }
                            item(key = "guide") { MoreCard("Live guide", { vm.selectTab(Tab.LIVE) }) }
                        }
                    }
                    if (live.isNotEmpty() || liveTours.isNotEmpty()) {
                        cardRow("live", "Live sports", nav) {
                            items(liveTours, key = { it.id }) { TournamentCard(vm, it, screenKey, onHero) }
                            items(live, key = { it.id }) { GameCard(vm, it, screenKey, onHero, "live:") }
                            item(key = "sports") { MoreCard("All sports", { vm.selectTab(Tab.SPORTS) }) }
                        }
                    }
                    if (presets.isNotEmpty()) {
                        cardRow("mv", "Watch in Multiview", nav) {
                            items(presets, key = { it.key }) { PresetCard(vm, it, screenKey, onHero) }
                            item(key = "build") { MoreCard("Build your own", { vm.multiviewWith(null) }) }
                        }
                    }
                    if (recordings.isNotEmpty()) {
                        cardRow("recordings", "Recordings", nav) {
                            items(recordings, key = { it.id }) { RecordingCard(vm, it, screenKey, onHero) }
                        }
                    }
                    addonRows.forEach { row ->
                        val list = addonItems[row.key].orEmpty()
                        if (list.isNotEmpty()) {
                            cardRow("a:" + row.key, row.title, nav) {
                                items(list, key = { "a:" + it.type + it.id }) { AddonCard(vm, it, screenKey, onHero, "h:") }
                                item(key = "more") { MoreCard("See all", { vm.navigate(Screen.AddonBrowse(row.key)) }, POSTER_WIDTH, 2f / 3f) }
                            }
                        }
                    }
                    if (movies.isNotEmpty()) {
                        cardRow("movies", "Movies from your provider", nav) {
                            items(movies, key = { it.id }) { MovieCard(vm, it, screenKey, onHero) }
                            item(key = "more") { MoreCard("All movies", { vm.navigate(Screen.Browse(BrowseKind.MOVIES)) }, POSTER_WIDTH, 2f / 3f) }
                        }
                    }
                    if (shows.isNotEmpty()) {
                        cardRow("shows", "Shows from your provider", nav) {
                            items(shows, key = { it.id }) { SeriesCard(vm, it, screenKey, onHero) }
                            item(key = "more") { MoreCard("All shows", { vm.navigate(Screen.Browse(BrowseKind.SHOWS)) }, POSTER_WIDTH, 2f / 3f) }
                        }
                    }
                    if (vm.scoresLoading && vm.games.isEmpty() && picks.isEmpty()) {
                        item(key = "loading") { LoadingState("Loading…") }
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
        PillButton("Set up provider", { vm.navigate(Screen.Provider()) }, primary = true)
    }
}
