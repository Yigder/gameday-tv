package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.gameday.tv.data.Leagues
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

/** Search with YouTube TV's on-screen keyboard on the left and results on the right. */
@Composable
fun SearchScreen(vm: AppViewModel) {
    val screenKey = Screen.Search.key
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    InitialFocus(vm, screenKey, firstKey)

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = null
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        results = vm.search(query)
        searching = false
    }
    // Search (Enter) on the keyboard: remember the search and jump to the results once they're in.
    var submitted by remember { mutableIntStateOf(0) }
    val resultsFocus = remember { FocusRequester() }
    LaunchedEffect(submitted, results, searching) {
        if (submitted == 0 || searching || results == null) return@LaunchedEffect
        if (results?.isEmpty == false) resultsFocus.requestFocusSafely(60)
        submitted = 0
    }
    LaunchedEffect(results) {
        // Remember searches that found something once the viewer pauses.
        if (results?.isEmpty == false) {
            delay(2_500)
            vm.addRecentSearch(query)
        }
    }

    Row(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Column(Modifier.width(340.dp).fillMaxHeight().padding(start = 48.dp, end = 10.dp).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().height(46.dp).background(Color(0x1FFFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, null, Modifier.size(22.dp), tint = AppColors.TextDim)
                Spacer(Modifier.width(10.dp))
                Text(
                    query.ifEmpty { "Search" },
                    fontSize = 18.sp,
                    color = if (query.isEmpty()) AppColors.TextDim else AppColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            OnScreenKeyboard(
                onKey = { if (query.length < 40) query += it },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
                firstKey = firstKey,
                onEnter = {
                    if (query.trim().length < 2) vm.showMessage("Type at least 2 letters")
                    else {
                        vm.addRecentSearch(query)
                        submitted++
                    }
                },
            )
            Spacer(Modifier.height(18.dp))
            if (vm.recentSearches.isNotEmpty() && query.isEmpty()) {
                Text("Recent searches", fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                vm.recentSearches.take(4).forEach { s ->
                    SettingRow(s, { query = s }, icon = Icons.Clock)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val r = results
            when {
                query.trim().length < 2 -> SearchSuggestions(vm, screenKey) { query = it }
                r == null || (searching && r.isEmpty) -> LoadingState("Searching…", Modifier.padding(top = 80.dp))
                r.isEmpty -> EmptyState("No results for \"$query\"", "Try a team, a channel, a league, or a show on TV.", Modifier.padding(top = 80.dp))
                // New rows for each search, so they start at the first result.
                else -> key(r) { PivotScroll(offset = ROW_TITLE) {
                    LazyColumn(Modifier.focusRequester(resultsFocus), state = listState, contentPadding = PaddingValues(bottom = 200.dp)) {
                        if (r.games.isNotEmpty() || r.tournaments.isNotEmpty()) {
                            cardRow("games", "Games", nav) {
                                items(r.tournaments, key = { it.id }) { TournamentCard(vm, it, screenKey) }
                                items(r.games, key = { it.id }) { GameCard(vm, it, screenKey) }
                            }
                        }
                        if (r.teams.isNotEmpty()) {
                            cardRow("teams", "Teams", nav) {
                                items(r.teams, key = { it.key }) { TeamCard(vm, vm.favoriteTeam(it.leagueKey, it.id) ?: it, screenKey) }
                            }
                        }
                        if (r.channels.isNotEmpty()) {
                            cardRow("channels", "Channels", nav) {
                                items(r.channels, key = { it.id }) { ChannelCard(vm, it, screenKey, r.channels) }
                            }
                        }
                        if (r.programs.isNotEmpty()) {
                            cardRow("programs", "On TV", nav) {
                                items(r.programs, key = { it.key }) { p ->
                                    val ch = vm.channelById(p.channelId)
                                    if (ch != null) {
                                        val now = System.currentTimeMillis()
                                        MediaCard(
                                            title = p.title,
                                            subtitle = "${cleanChannelName(ch.name)} · ${if (p.isOnNow(now)) minutesLeft(p.endMillis, now) else formatStart(p.startMillis)}",
                                            onClick = { programMenu(vm, ch, p, listOf(ch)) },
                                            modifier = androidx.compose.ui.Modifier.rememberFocus(vm, screenKey, "p:" + p.key),
                                        ) { ChannelThumb(ch, p, now) }
                                    }
                                }
                            }
                        }
                    }
                } }
            }
        }
    }
}

/** Before typing: quick picks for the leagues followed and the teams in the library. */
@Composable
private fun SearchSuggestions(vm: AppViewModel, screenKey: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, top = 8.dp, end = 40.dp)) {
        Text("Try searching for", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        val picks = (vm.favorites.map { it.name } + Leagues.everything.filter { it.key in vm.enabledLeagues }.map { it.label } +
            listOf("ESPN", "Fox Sports", "News")).distinct().take(14)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(picks.take(7), key = { it }) { Chip(it, false, { onPick(it) }) }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(picks.drop(7), key = { it }) { Chip(it, false, { onPick(it) }) }
        }
        val live = vm.liveGames
        if (live.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Live now", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(live, key = { it.id }) { GameCard(vm, it, screenKey) }
            }
        }
    }
}
