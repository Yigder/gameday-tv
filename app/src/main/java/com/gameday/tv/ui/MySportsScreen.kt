package com.gameday.tv.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.ChannelMatcher
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Leagues
import com.gameday.tv.ui.theme.AppColors

/** Choose which sports to follow and star favorite teams. */
@Composable
fun MySportsScreen(vm: AppViewModel) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusSafely(120) }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 24.dp)) {
        Text("My Sports & Teams", fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text(
            "Only the sports you follow appear on Home and in Multiview. Your teams get their own row and score alerts.",
            fontSize = 13.sp,
            color = AppColors.TextDim,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxSize()) {
            // ---- sports ----
            Column(Modifier.width(250.dp).fillMaxHeight()) {
                Text("Sports", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 32.dp)) {
                    items(Leagues.everything, key = { it.key }) { league ->
                        val on = league.key in vm.enabledLeagues
                        FocusSurface(
                            onClick = { vm.setLeagueEnabled(league.key, !on) },
                            modifier = Modifier.fillMaxWidth().then(if (league == Leagues.everything.first()) Modifier.focusRequester(firstFocus) else Modifier),
                            focusedScale = 1.03f,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CheckBox(on)
                                Spacer(Modifier.width(12.dp))
                                Text(league.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) AppColors.Text else AppColors.TextDim)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(28.dp))
            TeamsPanel(vm, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun CheckBox(checked: Boolean) {
    Box(
        Modifier
            .size(20.dp)
            .background(if (checked) AppColors.Accent else AppColors.Background, RoundedCornerShape(5.dp))
            .padding(1.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Black)
        else Box(Modifier.fillMaxSize().background(AppColors.CardFocused, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun TeamsPanel(vm: AppViewModel, modifier: Modifier) {
    val leagues = vm.followedLeagues
    var leagueKey by rememberSaveable { mutableStateOf(leagues.firstOrNull()?.key) }
    val league = leagues.firstOrNull { it.key == leagueKey } ?: leagues.firstOrNull()
    var teams by remember(league) { mutableStateOf<List<FavoriteTeam>?>(null) }
    var error by remember(league) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(league?.key) { mutableStateOf("") }
    LaunchedEffect(league) {
        if (league == null) return@LaunchedEffect
        runCatching { vm.loadTeams(league) }
            .onSuccess { teams = it }
            .onFailure { error = "Couldn't load teams. Check your connection." }
    }
    val shown = remember(teams, query) {
        val q = ChannelMatcher.norm(query)
        teams.orEmpty().filter { q.isEmpty() || ChannelMatcher.norm(it.name + " " + it.abbreviation).contains(q) }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My Teams", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent)
            Spacer(Modifier.width(10.dp))
            Text(if (vm.favorites.isEmpty()) "none yet" else "${vm.favorites.size} starred", fontSize = 13.sp, color = AppColors.TextDim)
        }
        if (vm.favorites.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                items(vm.favorites.toList(), key = { it.key }) { t ->
                    FocusSurface(onClick = { vm.toggleFavorite(t) }, shape = RoundedCornerShape(50), focusedScale = 1.06f) {
                        Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TeamLogo(t.logo, t.abbreviation, 22.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("${t.name}  ✕", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (leagues.isEmpty()) {
            Text("Follow a team sport to pick favorite teams.", fontSize = 14.sp, color = AppColors.TextDim)
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
            items(leagues, key = { it.key }) { l -> Chip(l.label, l == league, { leagueKey = l.key }) }
        }
        Spacer(Modifier.height(6.dp))
        TvTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search ${league?.label ?: ""} teams",
            imeAction = ImeAction.Search,
            onSubmit = {},
            modifier = Modifier.width(360.dp),
        )
        Spacer(Modifier.height(10.dp))
        when {
            error != null -> Text(error!!, fontSize = 14.sp, color = AppColors.Live)
            teams == null -> LoadingState("Loading teams…")
            shown.isEmpty() -> Text("No teams match.", fontSize = 14.sp, color = AppColors.TextDim)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 32.dp),
            ) {
                items(shown, key = { it.key }) { t ->
                    val fav = vm.isFavorite(t.leagueKey, t.id)
                    FocusSurface(
                        onClick = { vm.toggleFavorite(t) },
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        focusedScale = 1.06f,
                        containerColor = if (fav) AppColors.Accent.copy(alpha = 0.18f) else AppColors.Card,
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            TeamLogo(t.logo, t.abbreviation, 36.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                (if (fav) "★ " else "") + t.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (fav) AppColors.Accent else AppColors.Text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
