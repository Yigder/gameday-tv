package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.ChannelMatch
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.GameStats
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.RecStatus
import com.gameday.tv.data.TeamInfo
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Shared detail-page header: art on the right fading into the page, title, details and actions on
 * the left (the layout of YouTube TV's game and show pages).
 */
@Composable
fun DetailHeader(
    title: String,
    meta: List<String>,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    description: String? = null,
    art: @Composable BoxScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().height(300.dp)) {
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.6f)) {
            art()
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to AppColors.Background, 0.5f to Color(0x990F0F0F), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to AppColors.Background)))
        }
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.58f).padding(start = 48.dp, top = 24.dp)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 35.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    LiveBadge()
                    Spacer(Modifier.width(8.dp))
                }
                Text(meta.joinToString("  •  "), fontSize = 14.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(description, fontSize = 14.sp, color = Color(0xFFCCCCCC), maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Game page
// ---------------------------------------------------------------------------------------------

@Composable
fun GameScreen(vm: AppViewModel, gameId: String) {
    val screenKey = Screen.GameDetail(gameId).key
    val game = vm.gameById(gameId)
    if (game == null) {
        EmptyState("This game isn't on today's scoreboard", modifier = Modifier.padding(top = 120.dp)) { PillButton("Back", { vm.back() }) }
        return
    }
    var matches by remember { mutableStateOf<List<ChannelMatch>?>(null) }
    LaunchedEffect(gameId, vm.catalog) { matches = if (vm.catalog != null) vm.matchChannels(game) else emptyList() }
    var stats by remember { mutableStateOf<GameStats?>(null) }
    LaunchedEffect(gameId, game.state) {
        if (game.state == GameState.PRE) return@LaunchedEffect
        while (true) {
            runCatching { vm.gameStats(game) }.onSuccess { stats = it }
            if (game.state != GameState.LIVE) break
            delay(30_000)
        }
    }
    var reveal by remember { mutableStateOf(false) }
    val hide = vm.hideScores && !reveal
    val primary = remember { FocusRequester() }
    InitialFocus(vm, screenKey, primary)
    val rec = vm.recordingForEvent(game.id)
    val channels = matches.orEmpty().map { it.channel }

    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
        item(key = "header") {
            val h = heroFor(game, hide)
            DetailHeader(
                title = h.title,
                meta = h.meta,
                live = game.state == GameState.LIVE,
                description = h.description,
                art = { GameArt(game, logoFraction = 0.5f, showScore = !hide, big = true) },
            ) {
                when {
                    game.state == GameState.FINAL -> PillButton("Back", { vm.back() }, Modifier.focusRequester(primary))
                    channels.isNotEmpty() -> PillButton(if (game.state == GameState.LIVE) "Watch" else "Watch channel", { vm.play(channels, 0, game.id) },
                        Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
                    else -> PillButton(if (matches == null) "Finding channels…" else "No channel found", {}, Modifier.focusRequester(primary))
                }
                if (game.state == GameState.LIVE && channels.isNotEmpty()) PillButton("Multiview", { vm.multiviewWith(channels.first()) }, icon = Icons.Multiview)
                if (game.state != GameState.FINAL && vm.catalog != null) {
                    if (rec == null) PillButton("Record", { vm.recordGame(game, channels.firstOrNull()) }, icon = Icons.Record)
                    else PillButton(if (rec.status == RecStatus.RECORDING) "Recording" else "Scheduled", { recordingMenu(vm, rec) }, icon = Icons.Check)
                }
                if (vm.hideScores && game.state != GameState.PRE) PillButton(if (reveal) "Hide score" else "Show score", { reveal = !reveal })
            }
        }
        item(key = "teams") {
            Row(Modifier.padding(start = 48.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(false, true).forEach { home ->
                    val t = vm.favoriteFor(game, home)
                    val fav = vm.isFavorite(t.leagueKey, t.id)
                    PillButton(t.name, { vm.openTeam(t.leagueKey, t.id) }, icon = if (fav) Icons.Star else Icons.Trophy)
                }
            }
        }
        if (channels.isNotEmpty()) {
            cardRow("watch", "Watch on", nav) {
                items(matches.orEmpty(), key = { it.channel.id }) { m ->
                    MatchCard(vm, m, channels, game.id, screenKey)
                }
            }
        }
        if (stats != null && !hide) statsItems(stats!!, game)
    }
}

@Composable
private fun MatchCard(vm: AppViewModel, m: ChannelMatch, channels: List<Channel>, eventId: String, screenKey: String) {
    LaunchedEffect(m.channel.id) { vm.requestEpg(m.channel) }
    val program = vm.nowPlaying(m.channel.id)
    MediaCard(
        title = cleanChannelName(m.channel.name),
        subtitle = listOfNotNull(
            "Best match".takeIf { m.exact },
            m.quality.label.ifEmpty { null },
            program?.title ?: m.reasons.joinToString(" · ").ifBlank { m.channel.group },
        ).joinToString(" · "),
        onClick = { vm.play(channels, channels.indexOf(m.channel).coerceAtLeast(0), eventId) },
        onLongClick = { channelMenu(vm, m.channel, channels) },
        modifier = Modifier.rememberFocus(vm, screenKey, m.channel.id),
    ) { ChannelThumb(m.channel, program) }
}

/** Box score, scoring plays and leaders (also used by the player's Stats panel). */
fun LazyListScope.statsItems(stats: GameStats, game: Game, padding: Int = 48) {
    if (stats.teamStats.isNotEmpty()) {
        item(key = "stats-h") { StatsHeader("Team stats", game, padding) }
        items(stats.teamStats.take(16), key = { "s:" + it.label }) { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = padding.dp, vertical = 3.dp)) {
                Text(r.away, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(70.dp))
                Text(r.label, fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.weight(1f), maxLines = 1)
                Text(r.home, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
    if (stats.plays.isNotEmpty()) {
        item(key = "plays-h") { SectionTitle("Scoring plays", Modifier.padding(start = padding.dp, top = 18.dp, bottom = 8.dp)) }
        items(stats.plays.reversed().take(30), key = { "p:" + it.period + it.clock + it.text.hashCode() }) { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = padding.dp, vertical = 5.dp), verticalAlignment = Alignment.Top) {
                TeamLogo(p.teamLogo, p.teamAbbr ?: "", 26.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(p.period, p.clock).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = AppColors.TextDim)
                }
                if (p.awayScore != null && p.homeScore != null) {
                    Text("${p.awayScore}-${p.homeScore}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
    if (stats.leaders.any { it.leaders.isNotEmpty() }) {
        item(key = "leaders-h") { SectionTitle("Leaders", Modifier.padding(start = padding.dp, top = 18.dp, bottom = 8.dp)) }
        stats.leaders.forEach { t ->
            items(t.leaders, key = { "l:" + t.teamAbbr + it.category }) { l ->
                Row(Modifier.fillMaxWidth().padding(horizontal = padding.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(t.teamLogo, t.teamAbbr, 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(l.category, fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.width(150.dp), maxLines = 1)
                    Text(l.athlete, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(150.dp), maxLines = 1)
                    Text(l.value, fontSize = 13.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StatsHeader(title: String, game: Game, padding: Int) {
    Row(Modifier.fillMaxWidth().padding(start = padding.dp, end = padding.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(70.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(game.away.logo, game.away.abbreviation, 22.dp)
            Spacer(Modifier.width(4.dp))
            Text(game.away.abbreviation, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Row(Modifier.width(70.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(game.home.abbreviation, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            TeamLogo(game.home.logo, game.home.abbreviation, 22.dp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Golf tournament page
// ---------------------------------------------------------------------------------------------

@Composable
fun TournamentScreen(vm: AppViewModel, tournamentId: String) {
    val screenKey = Screen.TournamentDetail(tournamentId).key
    val t = vm.tournamentById(tournamentId)
    if (t == null) {
        EmptyState("This tournament isn't on the schedule anymore", modifier = Modifier.padding(top = 120.dp)) { PillButton("Back", { vm.back() }) }
        return
    }
    var matches by remember { mutableStateOf<List<ChannelMatch>?>(null) }
    LaunchedEffect(tournamentId, vm.catalog) { matches = if (vm.catalog != null) vm.matchChannels(t) else emptyList() }
    val channels = matches.orEmpty().map { it.channel }
    var reveal by remember { mutableStateOf(false) }
    val hide = vm.hideScores && !reveal
    val primary = remember { FocusRequester() }
    InitialFocus(vm, screenKey, primary)

    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
        item(key = "header") {
            DetailHeader(
                title = t.name,
                meta = listOfNotNull(t.tour.label, t.broadcasts.firstOrNull(), t.detail, "${t.fieldSize} players"),
                live = t.roundInProgress,
                art = {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2E7D32), Color(0xFF0B3D10)))))
                },
            ) {
                if (channels.isNotEmpty()) PillButton("Watch", { vm.play(channels, 0, t.id) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
                else PillButton(if (matches == null) "Finding channels…" else "No channel found", {}, Modifier.focusRequester(primary))
                if (channels.isNotEmpty() && t.roundInProgress) PillButton("Record", { vm.recordTournament(t, channels.first()) }, icon = Icons.Record)
                if (vm.hideScores) PillButton(if (reveal) "Hide leaderboard" else "Show leaderboard", { reveal = !reveal })
            }
        }
        if (channels.isNotEmpty()) {
            cardRow("watch", "Watch on", nav) {
                items(matches.orEmpty(), key = { it.channel.id }) { m -> MatchCard(vm, m, channels, t.id, screenKey) }
            }
        }
        if (!hide) {
            item(key = "lb-h") { SectionTitle("Leaderboard", Modifier.padding(start = 48.dp, bottom = 8.dp)) }
            item(key = "lb-cols") {
                Row(Modifier.padding(horizontal = 48.dp, vertical = 4.dp)) {
                    listOf("POS" to 60, "PLAYER" to 0, "TOTAL" to 70, "TODAY" to 70, "THRU" to 60).forEach { (h, w) ->
                        Text(h, fontSize = 12.sp, color = AppColors.TextFaint, modifier = if (w == 0) Modifier.weight(1f) else Modifier.width(w.dp))
                    }
                }
            }
            items(t.leaders, key = { "g:" + it.id }) { p ->
                Row(Modifier.padding(horizontal = 48.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.position, fontSize = 14.sp, color = AppColors.TextDim, modifier = Modifier.width(60.dp))
                    Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(p.toPar, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = parColor(p.toPar), modifier = Modifier.width(70.dp))
                    Text(p.today ?: "-", fontSize = 14.sp, modifier = Modifier.width(70.dp))
                    Text(p.thru ?: "-", fontSize = 14.sp, color = AppColors.TextDim, modifier = Modifier.width(60.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Team page
// ---------------------------------------------------------------------------------------------

@Composable
fun TeamScreen(vm: AppViewModel, leagueKey: String, teamId: String) {
    val screenKey = Screen.Team(leagueKey, teamId).key
    val league = Leagues.byKey(leagueKey)
    var info by remember { mutableStateOf<TeamInfo?>(null) }
    var schedule by remember { mutableStateOf<List<Game>?>(null) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(leagueKey, teamId) {
        if (league == null) return@LaunchedEffect
        runCatching { vm.teamInfo(league, teamId) }.onSuccess { info = it }
        runCatching { vm.teamSchedule(league, teamId) }.onSuccess { schedule = it }.onFailure { error = vm.friendly(it) }
    }
    LaunchedEffect(info, vm.catalog) {
        val name = info?.name ?: return@LaunchedEffect
        channels = vm.searchChannels(name.substringAfterLast(' ')).take(20)
    }
    val primary = remember { FocusRequester() }
    InitialFocus(vm, screenKey, primary)
    val fav = vm.favoriteTeam(leagueKey, teamId)
    val now = System.currentTimeMillis()
    // Prefer the live scoreboard's copy of a game (fresher than the schedule).
    val games = schedule.orEmpty().map { g -> vm.games.firstOrNull { it.id == g.id } ?: g }
    val upcoming = games.filter { it.state != GameState.FINAL }.sortedBy { it.startMillis }
    val results = games.filter { it.state == GameState.FINAL }.sortedByDescending { it.startMillis }

    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
        item(key = "header") {
            val i = info
            DetailHeader(
                title = i?.name ?: fav?.name ?: "Team",
                meta = listOfNotNull(league?.label, i?.record?.let { "Record $it" }, i?.standing),
                art = {
                    Box(
                        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(teamColor(i?.color), teamColor(i?.alternateColor, Color(0xFF111111))))),
                        contentAlignment = Alignment.Center,
                    ) { TeamLogo(i?.logo ?: fav?.logo, i?.abbreviation ?: "", 170.dp) }
                },
            ) {
                val team = fav ?: com.gameday.tv.data.FavoriteTeam(leagueKey, teamId, i?.name ?: "", i?.abbreviation ?: "", i?.logo)
                val live = upcoming.firstOrNull { it.state == GameState.LIVE }
                if (live != null) PillButton("Watch live", { vm.watchGame(live) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
                PillButton(
                    if (fav != null) "In your library" else "Add to library",
                    { if (team.name.isNotBlank()) vm.toggleFavorite(team) },
                    if (live == null) Modifier.focusRequester(primary) else Modifier,
                    icon = if (fav != null) Icons.Check else Icons.Add,
                    primary = live == null,
                )
                if (vm.catalog != null && team.name.isNotBlank()) {
                    PillButton(
                        if (fav?.record == true) "Recording all games" else "Record all games",
                        { vm.setTeamRecording(team, fav?.record != true) },
                        icon = if (fav?.record == true) Icons.Check else Icons.Record,
                    )
                }
            }
        }
        when {
            error != null -> item(key = "err") { Text(error!!, color = AppColors.Live, modifier = Modifier.padding(48.dp)) }
            schedule == null -> item(key = "load") { LoadingState("Loading schedule…") }
        }
        if (upcoming.isNotEmpty()) {
            cardRow("upcoming", "Upcoming", nav) {
                items(upcoming.take(20), key = { it.id }) { g ->
                    MediaCard(
                        title = g.title,
                        subtitle = "${formatStart(g.startMillis)}${g.broadcasts.firstOrNull()?.let { " · $it" } ?: ""}",
                        onClick = { vm.openGame(g) },
                        onLongClick = { gameMenu(vm, g) },
                        modifier = Modifier.rememberFocus(vm, screenKey, "u:" + g.id),
                    ) { GameThumb(g, vm.hideScores, vm.recordingForEvent(g.id) != null) }
                }
            }
        }
        if (results.isNotEmpty()) {
            cardRow("results", if (vm.hideScores) "Earlier games" else "Results", nav) {
                items(results.take(20), key = { it.id }) { g ->
                    MediaCard(
                        title = g.title,
                        subtitle = "${formatDay(g.startMillis)} · ${statusLine(g, vm.hideScores)}",
                        onClick = { vm.openGame(g) },
                        modifier = Modifier.rememberFocus(vm, screenKey, "f:" + g.id),
                    ) { GameThumb(g, vm.hideScores) }
                }
            }
        }
        if (channels.isNotEmpty()) {
            cardRow("channels", "Team channels", nav) {
                items(channels, key = { it.id }) { ChannelCard(vm, it, screenKey, channels) }
            }
        }
        if (results.isNotEmpty() || upcoming.isNotEmpty()) {
            item(key = "pad") { Spacer(Modifier.height(20.dp)) }
        }
    }
}

