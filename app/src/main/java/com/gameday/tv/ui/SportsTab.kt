package com.gameday.tv.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.theme.AppColors

/** A card in a Sports row: team games and golf tournaments share the rows. */
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

/**
 * Sports, the first tab: sport chips across the top, then live / upcoming / final rows, ready-made
 * multiviews, teams and channels.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SportsTab(vm: AppViewModel) {
    val screenKey = "main:${Tab.SPORTS}"
    var filter by rememberSaveable { mutableStateOf("all") }
    val chips = remember { FocusRequester() }
    val selectedChip = remember { FocusRequester() }
    if (vm.tabWantsFocus) InitialFocus(vm, screenKey, chips)
    val onHero: (HeroInfo) -> Unit = { vm.heroFocus = it }

    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    val picks = vm.followedPicks
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
    val live = pool.filter { it.state == GameState.LIVE }.map { SportsItem.G(it) } + tours.filter { it.roundInProgress || it.state == GameState.LIVE }.map { SportsItem.T(it) }
    val upcoming = (pool.filter { it.state == GameState.PRE }.map { SportsItem.G(it) } + tours.filter { it.state == GameState.PRE }.map { SportsItem.T(it) })
        .sortedBy { it.start }
    val finals = (pool.filter { it.state == GameState.FINAL }.map { SportsItem.G(it) } + tours.filter { it.state == GameState.FINAL }.map { SportsItem.T(it) })
        .sortedByDescending { it.start }

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

    // Kept in the view model so the row is there at once when coming back (and focus returns to it).
    val hasProvider = vm.catalog != null
    val presets = vm.multiviewRow
    LaunchedEffect(vm.games, vm.catalog, vm.favoriteChannelIds.size) { vm.multiviewRow = if (hasProvider) vm.multiviewPresets() else emptyList() }

    val first = live.firstOrNull() ?: upcoming.firstOrNull()
    val defaultHero = when (first) {
        is SportsItem.G -> heroFor(first.game, vm.hideScores)
        is SportsItem.T -> heroFor(first.t, vm.hideScores)
        null -> HeroInfo(pick?.label ?: "Sports", listOf("No games right now"))
    }

    fun choose(f: String) {
        filter = f
        vm.heroFocus = null
    }

    Column(Modifier.fillMaxSize()) {
        TabHero(vm, defaultHero, Modifier.fillMaxWidth().height(250.dp).padding(top = 72.dp), compact = true, videoBehind = vm.backgroundShowing)
        LazyRow(
            // Up from the rows comes back to the chosen sport, not the chip that lines up.
            Modifier.focusRequester(chips).focusRestorer(selectedChip),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "all") { Chip("All sports", filter == "all", { choose("all") }, if (filter == "all") Modifier.focusRequester(selectedChip) else Modifier) }
            if (vm.favorites.isNotEmpty()) {
                item(key = "mine") { Chip("Your teams", filter == "mine", { choose("mine") }, if (filter == "mine") Modifier.focusRequester(selectedChip) else Modifier, icon = Icons.Star) }
            }
            items(picks, key = { it.key }) { p ->
                Chip(p.label, filter == p.key, { choose(p.key) }, if (filter == p.key) Modifier.focusRequester(selectedChip) else Modifier)
            }
        }
        Box(Modifier.weight(1f)) {
            PivotScroll(offset = ROW_TITLE) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 260.dp)) {
                    if (!hasProvider) {
                        item(key = "provider") { ProviderBanner(vm) }
                    }
                    if (live.isNotEmpty()) {
                        cardRow("live", "Live now", nav) { sportsItems(vm, live, screenKey, onHero, "l:") }
                    }
                    if (filter == "all" && presets.isNotEmpty()) {
                        cardRow("mv", "Watch in Multiview", nav) {
                            items(presets, key = { it.key }) { PresetCard(vm, it, screenKey, onHero) }
                            item(key = "build") { MoreCard("Build your own", { vm.multiviewWith(null) }) }
                        }
                    }
                    if (upcoming.isNotEmpty()) {
                        cardRow("upcoming", "Upcoming", nav) { sportsItems(vm, upcoming.take(40), screenKey, onHero, "u:") }
                    }
                    if (filter == "mine") {
                        cardRow("teams", "Your teams", nav) {
                            items(vm.favorites.toList(), key = { it.key }) { TeamCard(vm, it, screenKey) }
                        }
                    }
                    if (finals.isNotEmpty()) {
                        cardRow("finals", if (vm.hideScores) "Earlier" else "Final scores", nav) { sportsItems(vm, finals.take(40), screenKey, onHero, "f:") }
                    }
                    if (channels.isNotEmpty()) {
                        cardRow("channels", if (pick != null) "${pick.label} channels" else "Sports channels", nav) {
                            items(channels, key = { it.id }) { ChannelCard(vm, it, screenKey, channels, onHero) }
                        }
                    }
                    if (teams.isNotEmpty()) {
                        cardRow("allteams", "Teams", nav) {
                            items(teams, key = { it.key }) { TeamCard(vm, vm.favoriteTeam(it.leagueKey, it.id) ?: it, screenKey) }
                        }
                    }
                    if (live.isEmpty() && upcoming.isEmpty() && finals.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                if (vm.scoresLoading) "Loading games…" else "No games scheduled",
                                if (filter == "mine") "None of your teams have games on today's scoreboard." else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sportsItems(
    vm: AppViewModel,
    list: List<SportsItem>,
    screenKey: String,
    onHero: (HeroInfo) -> Unit,
    prefix: String,
) {
    items(list, key = { prefix + it.key }) { item ->
        when (item) {
            is SportsItem.G -> GameCard(vm, item.game, screenKey, onHero, prefix)
            is SportsItem.T -> TournamentCard(vm, item.t, screenKey, onHero)
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
