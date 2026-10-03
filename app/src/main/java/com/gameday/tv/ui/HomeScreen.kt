package com.gameday.tv.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.League
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import java.text.NumberFormat

private data class HomeRow(
    val title: String,
    val games: List<Game> = emptyList(),
    val tournaments: List<Tournament> = emptyList(),
) {
    val size get() = games.size + tournaments.size
    val live get() = games.any { it.state == GameState.LIVE } || tournaments.any { it.roundInProgress }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    var chosenFilter by rememberSaveable { mutableStateOf("live") }
    val games = vm.games
    val tournaments = vm.tournaments
    val followed = vm.followedLeagues + vm.followedTours
    // Fall back to "Live Now" if the chosen sport was unfollowed or the last favorite removed.
    val filter = when {
        chosenFilter == "teams" && vm.favorites.isEmpty() -> "live"
        chosenFilter in setOf("live", "all", "teams") || followed.any { it.key == chosenFilter } -> chosenFilter
        else -> "live"
    }
    val favoriteGames = vm.favoriteGames
    val rows = remember(filter, games, tournaments, followed, favoriteGames) {
        buildRows(filter, games, tournaments, followed, favoriteGames)
    }
    val chipFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    val restoreId = vm.lastFocusedEventId

    fun cardModifier(id: String) = Modifier
        .then(if (id == restoreId) Modifier.focusRequester(restoreFocus) else Modifier)
        .onFocusChanged { if (it.isFocused) vm.lastFocusedEventId = id }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 24.dp)) {
        Header(vm)
        Spacer(Modifier.height(14.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
        ) {
            val liveCount = games.count { it.state == GameState.LIVE } + tournaments.count { it.roundInProgress }
            item(key = "live") {
                Chip(
                    if (liveCount > 0) "● Live Now ($liveCount)" else "Live Now",
                    filter == "live",
                    { chosenFilter = "live" },
                    Modifier.focusRequester(chipFocus),
                )
            }
            if (vm.favorites.isNotEmpty()) {
                item(key = "teams") {
                    val n = favoriteGames.count { it.state != GameState.FINAL }
                    Chip(if (n > 0) "★ My Teams · $n" else "★ My Teams", filter == "teams", { chosenFilter = "teams" })
                }
            }
            item(key = "all") { Chip("All Sports", filter == "all", { chosenFilter = "all" }) }
            items(followed, key = { it.key }) { league ->
                val count = if (league.sport == "golf") tournaments.count { it.tour.key == league.key } else games.count { it.league.key == league.key }
                Chip(if (count > 0) "${league.label} · $count" else league.label, filter == league.key, { chosenFilter = league.key })
            }
        }
        Spacer(Modifier.height(6.dp))

        when {
            vm.scoresLoading && games.isEmpty() && tournaments.isEmpty() ->
                LoadingState("Loading live scores…", Modifier.padding(top = 60.dp))
            games.isEmpty() && tournaments.isEmpty() && vm.scoresError != null -> EmptyState(
                "Couldn't load scores",
                vm.scoresError,
                Modifier.padding(top = 40.dp),
            ) { ActionButton("Retry", { vm.refreshScoresNow() }, primary = true) }
            rows.isEmpty() -> EmptyState(
                if (filter == "live") "Nothing live right now" else "Nothing on the schedule",
                "Try another league, or check back closer to game time.",
                Modifier.padding(top = 40.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(rows, key = { it.title }) { row ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (row.live && row.title.startsWith("Live")) {
                                LiveDot(10.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(row.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text("${row.size}", fontSize = 14.sp, color = AppColors.TextDim)
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp),
                        ) {
                            items(row.tournaments, key = { it.id }) { t ->
                                TournamentCard(t, onClick = { vm.openTournament(t) }, modifier = cardModifier(t.id))
                            }
                            items(row.games, key = { it.id }) { game ->
                                GameCard(
                                    game,
                                    onClick = { vm.openGame(game) },
                                    modifier = cardModifier(game.id),
                                    favoriteTeamIds = vm.favorites.filter { it.leagueKey == game.league.key }.map { it.id }.toSet(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (restoreId == null || !restoreFocus.requestFocusSafely(120)) chipFocus.requestFocusSafely()
    }
}

@Composable
private fun Header(vm: AppViewModel) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("GameDay", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(" TV", fontSize = 28.sp, fontWeight = FontWeight.Black, color = AppColors.Accent)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(formatTime(now), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val status = when (val s = vm.iptv) {
                is IptvStatus.Ready -> "● " + NumberFormat.getIntegerInstance().format(s.catalog.channels.size) + " channels"
                IptvStatus.Loading -> "Loading channels…"
                is IptvStatus.Failed -> "IPTV error — open Account"
                IptvStatus.NotConfigured -> "Scores only — IPTV not connected"
            }
            val updated = if (vm.scoresUpdatedAt > 0) "  ·  Scores ${formatTime(vm.scoresUpdatedAt)}" else ""
            Text(
                status + updated,
                fontSize = 12.sp,
                color = when (vm.iptv) {
                    is IptvStatus.Ready -> AppColors.Good
                    is IptvStatus.Failed -> AppColors.Live
                    else -> AppColors.TextDim
                },
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(if (vm.multiviewCount > 0) "⊞ Multiview (${vm.multiviewCount})" else "⊞ Multiview", { vm.openMultiview() })
            ActionButton("Channels", { vm.openChannels() })
            ActionButton("★ My Sports", { vm.navigate(Screen.MySports) })
            ActionButton("Account", { vm.navigate(Screen.Account) })
        }
    }
}

private fun buildRows(
    filter: String,
    games: List<Game>,
    tournaments: List<Tournament>,
    followed: List<League>,
    favoriteGames: List<Game>,
): List<HomeRow> {
    val leagueOrder = Leagues.all.withIndex().associate { it.value.key to it.index }
    val byLeagueThenTime = compareBy<Game>({ leagueOrder[it.league.key] ?: 99 }, { it.startMillis })
    val golfOrder = compareBy<Tournament>({ if (it.state == GameState.LIVE) 0 else if (it.state == GameState.PRE) 1 else 2 }, { it.startMillis })
    val rows = when (filter) {
        "live" -> listOf(
            HomeRow("★ My Teams", favoriteGames),
            HomeRow("Live Now", games.filter { it.state == GameState.LIVE }.sortedWith(byLeagueThenTime)),
            HomeRow("Golf", tournaments = tournaments.filter { it.state == GameState.LIVE }.sortedWith(golfOrder)),
            HomeRow("Up Next", games.filter { it.state == GameState.PRE }.sortedBy { it.startMillis }.take(40)),
            HomeRow("Final", games.filter { it.state == GameState.FINAL }.sortedByDescending { it.startMillis }.take(40)),
        )
        "teams" -> listOf(
            HomeRow("Live", favoriteGames.filter { it.state == GameState.LIVE }),
            HomeRow("Upcoming", favoriteGames.filter { it.state == GameState.PRE }),
            HomeRow("Final", favoriteGames.filter { it.state == GameState.FINAL }.sortedByDescending { it.startMillis }),
        )
        "all" -> followed.map { league ->
            if (league.sport == "golf") {
                HomeRow(league.label, tournaments = tournaments.filter { it.tour.key == league.key }.sortedWith(golfOrder))
            } else {
                HomeRow(
                    league.label,
                    games.filter { it.league.key == league.key }.sortedWith(compareBy<Game>({ stateOrder(it) }, { it.startMillis })),
                )
            }
        }
        else -> {
            if (Leagues.golf.any { it.key == filter }) {
                val tour = tournaments.filter { it.tour.key == filter }
                listOf(
                    HomeRow("This Week", tournaments = tour.filter { it.state != GameState.FINAL }.sortedWith(golfOrder)),
                    HomeRow("Completed", tournaments = tour.filter { it.state == GameState.FINAL }),
                )
            } else {
                val lg = games.filter { it.league.key == filter }
                listOf(
                    HomeRow("Live", lg.filter { it.state == GameState.LIVE }.sortedBy { it.startMillis }),
                    HomeRow("Upcoming", lg.filter { it.state == GameState.PRE }.sortedBy { it.startMillis }),
                    HomeRow("Final", lg.filter { it.state == GameState.FINAL }.sortedByDescending { it.startMillis }),
                )
            }
        }
    }
    return rows.filter { it.size > 0 }
}
