package com.gameday.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.gameday.tv.data.Channel
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.Leagues

/** Sports: league chips across the top, then live / upcoming / final rows, teams and channels. */
@Composable
fun SportsTab(vm: AppViewModel) {
    val screenKey = "main:${Tab.SPORTS}"
    var filter by rememberSaveable { mutableStateOf("all") }
    var hero by remember { mutableStateOf<HeroInfo?>(null) }
    val chips = remember { FocusRequester() }
    if (vm.tabWantsFocus) InitialFocus(vm, screenKey, chips)

    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    val leagues = vm.followedLeagues + vm.followedTours
    val league = Leagues.byKey(filter)
    val pool: List<Game> = when {
        filter == "all" -> vm.games
        filter == "mine" -> vm.favoriteGames
        else -> vm.games.filter { it.league.key == filter }
    }
    val live = pool.filter { it.state == GameState.LIVE }.sortedBy { it.startMillis }
    val upcoming = pool.filter { it.state == GameState.PRE }.sortedBy { it.startMillis }
    val finals = pool.filter { it.state == GameState.FINAL }.sortedByDescending { it.startMillis }
    val tours = when {
        filter == "all" -> vm.tournaments
        league?.sport == "golf" -> vm.tournaments.filter { it.tour.key == filter }
        else -> emptyList()
    }

    var teams by remember(filter) { mutableStateOf<List<FavoriteTeam>>(emptyList()) }
    var channels by remember(filter) { mutableStateOf<List<Channel>>(emptyList()) }
    LaunchedEffect(filter, vm.catalog) {
        if (league != null) {
            if (league.sport != "golf") teams = runCatching { vm.loadTeams(league) }.getOrDefault(emptyList())
            channels = vm.leagueChannels(league).take(30)
        } else if (filter == "all") {
            channels = vm.catalog?.sportsChannels?.take(30).orEmpty()
        }
    }

    val defaultHero = (live.firstOrNull() ?: upcoming.firstOrNull())?.let { heroFor(it, vm.hideScores) }
        ?: tours.firstOrNull()?.let { heroFor(it, vm.hideScores) }
        ?: HeroInfo(league?.label ?: "Sports", listOf("No games right now"))

    Column(Modifier.fillMaxSize()) {
        Hero(hero ?: defaultHero, Modifier.fillMaxWidth().height(210.dp).padding(top = 40.dp), vm.hideScores)
        LazyRow(
            Modifier.focusRequester(chips),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "all") { Chip("All sports", filter == "all", { filter = "all"; hero = null }) }
            if (vm.favorites.isNotEmpty()) item(key = "mine") { Chip("Your teams", filter == "mine", { filter = "mine"; hero = null }, icon = Icons.Star) }
            items(leagues, key = { it.key }) { l -> Chip(l.label, filter == l.key, { filter = l.key; hero = null }) }
        }
        Box(Modifier.weight(1f)) {
            PivotScroll(offset = ROW_TITLE) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 260.dp)) {
                    if (tours.isNotEmpty()) {
                        cardRow("tours", "Golf", nav) {
                            items(tours, key = { it.id }) { TournamentCard(vm, it, screenKey) { h -> hero = h } }
                        }
                    }
                    if (live.isNotEmpty()) {
                        cardRow("live", "Live now", nav) {
                            items(live, key = { it.id }) { GameCard(vm, it, screenKey, { h -> hero = h }, "l:") }
                        }
                    }
                    if (upcoming.isNotEmpty()) {
                        cardRow("upcoming", "Upcoming", nav) {
                            items(upcoming.take(40), key = { it.id }) { GameCard(vm, it, screenKey, { h -> hero = h }, "u:") }
                        }
                    }
                    if (filter == "mine") {
                        cardRow("teams", "Your teams", nav) {
                            items(vm.favorites.toList(), key = { it.key }) { TeamCard(vm, it, screenKey) }
                        }
                    }
                    if (finals.isNotEmpty()) {
                        cardRow("finals", if (vm.hideScores) "Earlier" else "Final scores", nav) {
                            items(finals.take(40), key = { it.id }) { GameCard(vm, it, screenKey, { h -> hero = h }, "f:") }
                        }
                    }
                    if (channels.isNotEmpty()) {
                        cardRow("channels", if (league != null) "${league.label} channels" else "Sports channels", nav) {
                            items(channels, key = { it.id }) { ChannelCard(vm, it, screenKey, channels, { h -> hero = h }) }
                        }
                    }
                    if (teams.isNotEmpty()) {
                        cardRow("allteams", "Teams", nav) {
                            items(teams, key = { it.key }) { TeamCard(vm, vm.favoriteTeam(it.leagueKey, it.id) ?: it, screenKey) }
                        }
                    }
                    if (live.isEmpty() && upcoming.isEmpty() && finals.isEmpty() && tours.isEmpty()) {
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
