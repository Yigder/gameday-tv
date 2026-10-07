package com.gameday.tv.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameday.tv.data.Channel
import com.gameday.tv.data.ChannelMatch
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.GameStats
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.RecStatus
import com.gameday.tv.data.TeamInfo
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.ChannelLogo
import com.gameday.tv.ui.ChannelThumb
import com.gameday.tv.ui.GameArt
import com.gameday.tv.ui.Icons
import com.gameday.tv.ui.SearchResults
import com.gameday.tv.ui.TeamLogo
import com.gameday.tv.ui.channelMenu
import com.gameday.tv.ui.cleanChannelName
import com.gameday.tv.ui.formatDay
import com.gameday.tv.ui.formatStart
import com.gameday.tv.ui.formatTime
import com.gameday.tv.ui.gameMenu
import com.gameday.tv.ui.heroFor
import com.gameday.tv.ui.minutesLeft
import com.gameday.tv.ui.parColor
import com.gameday.tv.ui.programMenu
import com.gameday.tv.ui.recordMenu
import com.gameday.tv.ui.recordingMenu
import com.gameday.tv.ui.statsItems
import com.gameday.tv.ui.statusLine
import com.gameday.tv.ui.teamColor
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

/** A row of action buttons that wraps on narrow phones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        if (icon != null) {
            AppIcon(icon, null, tint = Color.Black, size = 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, icon: ImageVector? = null) {
    FilledTonalButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        if (icon != null) {
            AppIcon(icon, null, size = 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

/** Detail pages draw under the status bar; a floating Back button sits over the artwork. */
@Composable
private fun DetailPage(vm: AppViewModel, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            content()
            item(key = "nav-pad") { Spacer(Modifier.navigationBarsPadding()) }
        }
        IconButton(
            onClick = { vm.back() },
            modifier = Modifier.statusBarsPadding().padding(4.dp).background(Color(0x99000000), androidx.compose.foundation.shape.CircleShape),
        ) { AppIcon(Icons.Back, "Back") }
    }
}

// ---------------------------------------------------------------------------------------------
// Game
// ---------------------------------------------------------------------------------------------

@Composable
fun GameScreen(vm: AppViewModel, gameId: String) {
    val game = vm.gameById(gameId)
    if (game == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar(vm)
            MessageBlock("This game isn't on today's scoreboard")
        }
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
    val rec = vm.recordingForEvent(game.id)
    val channels = matches.orEmpty().map { it.channel }

    DetailPage(vm) {
        item(key = "header") {
            val h = heroFor(game, hide)
            DetailHero(
                title = h.title,
                meta = h.meta,
                live = game.state == GameState.LIVE,
                description = h.description,
                art = { GameArt(game, logoFraction = 0.5f, showScore = !hide, big = true) },
            ) {
                ActionRow {
                    when {
                        game.state == GameState.FINAL -> Unit
                        channels.isNotEmpty() -> PrimaryAction(if (game.state == GameState.LIVE) "Watch" else "Watch channel", { vm.play(channels, 0, game.id) }, Icons.Play)
                        else -> PrimaryAction(if (matches == null) "Finding channels…" else "No channel found", {}, enabled = false)
                    }
                    if (game.state == GameState.LIVE && channels.isNotEmpty()) SecondaryAction("Multiview", { vm.multiviewWith(channels.first()) }, Icons.Multiview)
                    if (game.state != GameState.FINAL && vm.catalog != null) {
                        if (rec == null) SecondaryAction("Record", { vm.recordGame(game, channels.firstOrNull()) }, Icons.Record)
                        else SecondaryAction(if (rec.status == RecStatus.RECORDING) "Recording" else "Scheduled", { recordingMenu(vm, rec) }, Icons.Check)
                    }
                    if (vm.hideScores && game.state != GameState.PRE) SecondaryAction(if (reveal) "Hide score" else "Show score", { reveal = !reveal })
                    SecondaryAction("More", { gameMenu(vm, game) })
                }
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(false, true)) { home ->
                        val t = vm.favoriteFor(game, home)
                        val fav = vm.isFavorite(t.leagueKey, t.id)
                        AssistChip(
                            onClick = { vm.openTeam(t.leagueKey, t.id) },
                            label = { Text(t.name) },
                            leadingIcon = { TeamLogo(t.logo, t.abbreviation, 18.dp) },
                            trailingIcon = if (fav) ({ AppIcon(Icons.Star, null, tint = AppColors.Warn, size = 14.dp) }) else null,
                        )
                    }
                }
            }
        }
        if (channels.isNotEmpty()) {
            rowSection("watch", "Watch on") {
                items(matches.orEmpty(), key = { it.channel.id }) { m -> MatchTile(vm, m, channels, game.id) }
            }
        }
        stats?.takeIf { !hide }?.let { statsItems(it, game, padding = 16) }
    }
}

@Composable
private fun MatchTile(vm: AppViewModel, m: ChannelMatch, channels: List<Channel>, eventId: String) {
    LaunchedEffect(m.channel.id) { vm.requestEpg(m.channel) }
    val program = vm.nowPlaying(m.channel.id)
    MediaTile(
        title = cleanChannelName(m.channel.name),
        subtitle = listOfNotNull(
            "Best match".takeIf { m.exact },
            m.quality.label.ifEmpty { null },
            program?.title ?: m.reasons.joinToString(" · ").ifBlank { m.channel.group },
        ).joinToString(" · "),
        onClick = { vm.play(channels, channels.indexOf(m.channel).coerceAtLeast(0), eventId) },
        onLongClick = { channelMenu(vm, m.channel, channels) },
        width = 200.dp,
    ) { ChannelThumb(m.channel, program) }
}

// ---------------------------------------------------------------------------------------------
// Golf
// ---------------------------------------------------------------------------------------------

@Composable
fun TournamentScreen(vm: AppViewModel, tournamentId: String) {
    val t = vm.tournamentById(tournamentId)
    if (t == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar(vm)
            MessageBlock("This tournament isn't on the schedule anymore")
        }
        return
    }
    var matches by remember { mutableStateOf<List<ChannelMatch>?>(null) }
    LaunchedEffect(tournamentId, vm.catalog) { matches = if (vm.catalog != null) vm.matchChannels(t) else emptyList() }
    val channels = matches.orEmpty().map { it.channel }
    var reveal by remember { mutableStateOf(false) }
    val hide = vm.hideScores && !reveal

    DetailPage(vm) {
        item(key = "header") {
            DetailHero(
                title = t.name,
                meta = listOfNotNull(t.tour.label, t.broadcasts.firstOrNull(), t.detail, "${t.fieldSize} players"),
                live = t.roundInProgress,
                art = { Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2E7D32), Color(0xFF0B3D10))))) },
            ) {
                ActionRow {
                    if (channels.isNotEmpty()) PrimaryAction("Watch", { vm.play(channels, 0, t.id) }, Icons.Play)
                    else PrimaryAction(if (matches == null) "Finding channels…" else "No channel found", {}, enabled = false)
                    if (channels.isNotEmpty() && t.roundInProgress) SecondaryAction("Record", { vm.recordTournament(t, channels.first()) }, Icons.Record)
                    if (vm.hideScores) SecondaryAction(if (reveal) "Hide leaderboard" else "Show leaderboard", { reveal = !reveal })
                }
            }
        }
        if (channels.isNotEmpty()) {
            rowSection("watch", "Watch on") {
                items(matches.orEmpty(), key = { it.channel.id }) { m -> MatchTile(vm, m, channels, t.id) }
            }
        }
        if (!hide) {
            item(key = "lb-h") { SectionHeader("Leaderboard") }
            item(key = "lb-cols") {
                Row(Modifier.padding(horizontal = GUTTER, vertical = 4.dp)) {
                    listOf("POS" to 44, "PLAYER" to 0, "TOT" to 48, "TODAY" to 52, "THRU" to 44).forEach { (h, w) ->
                        Text(h, fontSize = 11.sp, color = AppColors.TextFaint, modifier = if (w == 0) Modifier.weight(1f) else Modifier.width(w.dp))
                    }
                }
            }
            items(t.leaders, key = { "g:" + it.id }) { p ->
                Row(Modifier.padding(horizontal = GUTTER, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.position, fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.width(44.dp))
                    Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.toPar, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = parColor(p.toPar), modifier = Modifier.width(48.dp))
                    Text(p.today ?: "-", fontSize = 13.sp, modifier = Modifier.width(52.dp))
                    Text(p.thru ?: "-", fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.width(44.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Team
// ---------------------------------------------------------------------------------------------

@Composable
fun TeamScreen(vm: AppViewModel, leagueKey: String, teamId: String) {
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
    val fav = vm.favoriteTeam(leagueKey, teamId)
    // Prefer the live scoreboard's copy of a game (fresher than the schedule).
    val games = schedule.orEmpty().map { g -> vm.games.firstOrNull { it.id == g.id } ?: g }
    val upcoming = games.filter { it.state != GameState.FINAL }.sortedBy { it.startMillis }
    val results = games.filter { it.state == GameState.FINAL }.sortedByDescending { it.startMillis }

    DetailPage(vm) {
        item(key = "header") {
            val i = info
            DetailHero(
                title = i?.name ?: fav?.name ?: "Team",
                meta = listOfNotNull(league?.label, i?.record?.let { "Record $it" }, i?.standing),
                art = {
                    Box(
                        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(teamColor(i?.color), teamColor(i?.alternateColor, Color(0xFF111111))))),
                        contentAlignment = Alignment.Center,
                    ) { TeamLogo(i?.logo ?: fav?.logo, i?.abbreviation ?: "", 120.dp) }
                },
            ) {
                val team = fav ?: FavoriteTeam(leagueKey, teamId, i?.name ?: "", i?.abbreviation ?: "", i?.logo)
                val live = upcoming.firstOrNull { it.state == GameState.LIVE }
                ActionRow {
                    if (live != null) PrimaryAction("Watch live", { vm.watchGame(live) }, Icons.Play)
                    if (fav != null) SecondaryAction("In your library", { vm.toggleFavorite(team) }, Icons.Check)
                    else if (live == null) PrimaryAction("Add to library", { if (team.name.isNotBlank()) vm.toggleFavorite(team) }, Icons.Add)
                    else SecondaryAction("Add to library", { if (team.name.isNotBlank()) vm.toggleFavorite(team) }, Icons.Add)
                    if (vm.catalog != null && team.name.isNotBlank()) {
                        SecondaryAction(
                            if (fav?.record == true) "Recording all games" else "Record all games",
                            { vm.setTeamRecording(team, fav?.record != true) },
                            if (fav?.record == true) Icons.Check else Icons.Record,
                        )
                    }
                }
            }
        }
        when {
            error != null -> item(key = "err") { Text(error!!, color = AppColors.Live, modifier = Modifier.padding(GUTTER)) }
            schedule == null -> item(key = "load") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
        if (upcoming.isNotEmpty()) {
            rowSection("upcoming", "Upcoming") {
                items(upcoming.take(20), key = { it.id }) { g ->
                    MediaTile(
                        title = g.title,
                        subtitle = "${formatStart(g.startMillis)}${g.broadcasts.firstOrNull()?.let { " · $it" } ?: ""}",
                        onClick = { vm.openGame(g) },
                        onLongClick = { gameMenu(vm, g) },
                    ) { com.gameday.tv.ui.GameThumb(g, vm.hideScores, vm.recordingForEvent(g.id) != null) }
                }
            }
        }
        if (results.isNotEmpty()) {
            rowSection("results", if (vm.hideScores) "Earlier games" else "Results") {
                items(results.take(20), key = { it.id }) { g ->
                    MediaTile(
                        title = g.title,
                        subtitle = "${formatDay(g.startMillis)} · ${statusLine(g, vm.hideScores)}",
                        onClick = { vm.openGame(g) },
                    ) { com.gameday.tv.ui.GameThumb(g, vm.hideScores) }
                }
            }
        }
        if (channels.isNotEmpty()) {
            rowSection("channels", "Team channels") { items(channels, key = { it.id }) { ChannelTile(vm, it, channels) } }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Channel
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelScreen(vm: AppViewModel, channelId: String) {
    val channel = vm.channelById(channelId)
    if (channel == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar(vm)
            MessageBlock("Channel not found", "It may have been removed from your provider's lineup.")
        }
        return
    }
    LaunchedEffect(channelId) { vm.requestEpg(channel) }
    val now = System.currentTimeMillis()
    val programs = vm.programsFor(channel.id).filter { it.endMillis > now - channel.archiveDays * 86_400_000L }
    val current = programs.firstOrNull { it.isOnNow(now) }
    val fav = vm.isFavoriteChannel(channel.id)

    DetailPage(vm) {
        item(key = "header") {
            DetailHero(
                title = current?.title ?: cleanChannelName(channel.name),
                meta = listOfNotNull(cleanChannelName(channel.name), channel.group, current?.let { minutesLeft(it.endMillis, now) },
                    if (channel.archiveDays > 0) "${channel.archiveDays}-day replay" else null),
                live = current != null,
                description = current?.description,
                art = {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
                        ChannelLogo(channel, 110.dp, background = Color.Transparent)
                    }
                },
            ) {
                ActionRow {
                    PrimaryAction("Watch live", { vm.playChannel(channel) }, Icons.Play)
                    if (current != null && vm.catchupUrl(channel, current) != null) SecondaryAction("Start over", { vm.playCatchup(channel, current) }, Icons.Restart)
                    SecondaryAction("Record", { recordMenu(vm, channel) }, Icons.Record)
                    SecondaryAction(if (fav) "Favorite" else "Add to favorites", { vm.toggleFavoriteChannel(channel) }, if (fav) Icons.Check else Icons.Star)
                    SecondaryAction("Multiview", { vm.multiviewWith(channel) }, Icons.Multiview)
                }
            }
        }
        item(key = "sched-h") { SectionHeader("Schedule") }
        if (programs.isEmpty()) {
            item(key = "none") { Text("No guide information for this channel.", color = AppColors.TextDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = GUTTER)) }
        }
        items(programs, key = { it.key }) { p ->
            val onNow = p.isOnNow(now)
            val replay = p.endMillis <= now && vm.catchupUrl(channel, p) != null
            val rec = vm.recordingForProgram(channel.id, p.startMillis) != null
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (onNow) vm.playChannel(channel) else programMenu(vm, channel, p, listOf(channel)) },
                        onLongClick = { programMenu(vm, channel, p, listOf(channel)) },
                    )
                    .padding(horizontal = GUTTER, vertical = 10.dp),
            ) {
                Column(Modifier.width(76.dp)) {
                    Text(if (onNow) "On now" else formatTime(p.startMillis), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (onNow) AppColors.Live else AppColors.Text)
                    Text(formatDay(p.startMillis), fontSize = 11.sp, color = AppColors.TextFaint)
                }
                Column(Modifier.weight(1f)) {
                    Text(p.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (p.endMillis <= now && !replay) AppColors.TextDim else AppColors.Text)
                    if (p.description.isNotBlank()) Text(p.description, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val tags = listOfNotNull(if (rec) "● Recording" else null, if (replay) "Replay available" else null)
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), fontSize = 11.sp, color = if (rec) AppColors.Live else AppColors.TextDim)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------------------------

@Composable
fun SearchScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
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
    LaunchedEffect(results) {
        // Remember searches that found something once the viewer pauses.
        if (results?.isEmpty == false) {
            delay(2_500)
            vm.addRecentSearch(query)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = GUTTER, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { AppIcon(Icons.Back, "Back") }
            TextField(
                value = query,
                onValueChange = { if (it.length <= 40) query = it },
                placeholder = { Text("Teams, channels, leagues, shows") },
                singleLine = true,
                leadingIcon = { AppIcon(Icons.Search, null, tint = AppColors.TextDim) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { AppIcon(Icons.Close, "Clear") } }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    if (query.trim().length >= 2) vm.addRecentSearch(query)
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppColors.Raised,
                    unfocusedContainerColor = AppColors.Raised,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        val r = results
        when {
            query.trim().length < 2 -> SearchSuggestions(vm) { query = it }
            r == null || (searching && r.isEmpty) -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            r.isEmpty -> MessageBlock("No results for \"$query\"", "Try a team, a channel, a league, or a show on TV.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (r.games.isNotEmpty() || r.tournaments.isNotEmpty()) {
                    rowSection("games", "Games") {
                        items(r.tournaments, key = { it.id }) { TournamentTile(vm, it) }
                        items(r.games, key = { it.id }) { GameTile(vm, it) }
                    }
                }
                if (r.teams.isNotEmpty()) {
                    rowSection("teams", "Teams") { items(r.teams, key = { it.key }) { TeamTile(vm, vm.favoriteTeam(it.leagueKey, it.id) ?: it) } }
                }
                if (r.channels.isNotEmpty()) {
                    item(key = "ch-h") { SectionHeader("Channels") }
                    items(r.channels.take(30), key = { "c:" + it.id }) { ChannelListRow(vm, it, r.channels) }
                }
                if (r.programs.isNotEmpty()) {
                    rowSection("programs", "On TV") {
                        items(r.programs, key = { it.key }) { p ->
                            val ch = vm.channelById(p.channelId)
                            if (ch != null) {
                                val now = System.currentTimeMillis()
                                MediaTile(
                                    title = p.title,
                                    subtitle = "${cleanChannelName(ch.name)} · ${if (p.isOnNow(now)) minutesLeft(p.endMillis, now) else formatStart(p.startMillis)}",
                                    onClick = { programMenu(vm, ch, p, listOf(ch)) },
                                ) { ChannelThumb(ch, p, now) }
                            }
                        }
                    }
                }
                item(key = "nav-pad") { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

/** Before typing: recent searches, quick picks for the leagues and teams followed, and what's live. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchSuggestions(vm: AppViewModel, onPick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (vm.recentSearches.isNotEmpty()) {
            item(key = "recent-h") { SectionHeader("Recent searches") }
            items(vm.recentSearches.take(6), key = { "r:$it" }) { s -> SettingItem(s, { onPick(s) }, icon = Icons.History) }
        }
        item(key = "try") {
            SectionHeader("Try searching for")
            val picks = (vm.favorites.map { it.name } + Leagues.everything.filter { it.key in vm.enabledLeagues }.map { it.label } +
                listOf("ESPN", "Fox Sports", "News")).distinct().take(14)
            FlowRow(Modifier.padding(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                picks.forEach { p -> AssistChip(onClick = { onPick(p) }, label = { Text(p) }) }
            }
        }
        val live = vm.liveGames
        if (live.isNotEmpty()) {
            rowSection("live", "Live now") { items(live, key = { it.id }) { GameTile(vm, it) } }
        }
    }
}
