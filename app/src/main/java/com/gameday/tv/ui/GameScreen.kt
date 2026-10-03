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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.ChannelMatch
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.TeamScore
import com.gameday.tv.ui.theme.AppColors

@Composable
fun GameScreen(vm: AppViewModel, gameId: String) {
    val game = vm.gameById(gameId)
    if (game == null) {
        EmptyState("Game not found", "This game is no longer on the scoreboard.", Modifier.padding(top = 120.dp)) {
            ActionButton("Back", { vm.back() }, primary = true)
        }
        return
    }

    val iptv = vm.iptv
    var matches by remember(gameId) { mutableStateOf<List<ChannelMatch>?>(null) }
    LaunchedEffect(gameId, iptv) {
        matches = null
        if (iptv is IptvStatus.Ready) matches = vm.matchChannels(game, iptv.catalog)
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Scoreboard(vm, game, Modifier.width(360.dp).fillMaxHeight())
        Spacer(Modifier.width(32.dp))
        WatchPanel(
            vm = vm,
            eventId = game.id,
            matches = matches,
            searchQuery = game.home.name.ifBlank { game.home.shortName },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/** "Watch Live" column shared by game and tournament pages: IPTV status + matched channels. */
@Composable
fun WatchPanel(
    vm: AppViewModel,
    eventId: String,
    matches: List<ChannelMatch>?,
    searchQuery: String,
    modifier: Modifier = Modifier,
) {
    val iptv = vm.iptv
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Watch Live", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (iptv is IptvStatus.Ready && vm.multiviewCount > 0) {
                ActionButton("⊞ Multiview (${vm.multiviewCount})", { vm.openMultiview() })
            }
        }
        Spacer(Modifier.height(4.dp))
        val sub = when {
            iptv !is IptvStatus.Ready -> "Connect your IPTV service to watch live."
            matches == null -> "Searching your channels…"
            matches.isEmpty() -> "No channel matched automatically."
            else -> "${matches.size} matching channels · best first · hold OK to add one to Multiview"
        }
        Text(sub, fontSize = 14.sp, color = AppColors.TextDim)
        Spacer(Modifier.height(16.dp))

        when (iptv) {
            IptvStatus.NotConfigured -> EmptyState(
                "IPTV not connected",
                "Sign in with your Xtream Codes login or M3U playlist to watch live.",
            ) { ActionButton("Connect IPTV", { vm.navigate(Screen.Login) }, primary = true) }
            IptvStatus.Loading -> LoadingState("Loading your channels…")
            is IptvStatus.Failed -> EmptyState("Couldn't load channels", iptv.message) {
                ActionButton("Retry", { vm.reloadChannels() }, primary = true)
                ActionButton("Account", { vm.navigate(Screen.Account) })
            }
            is IptvStatus.Ready -> MatchList(vm, eventId, matches, searchQuery)
        }
    }
}

@Composable
private fun MatchList(vm: AppViewModel, eventId: String, matches: List<ChannelMatch>?, searchQuery: String) {
    val firstFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }

    when {
        matches == null -> LoadingState("Matching channels…")
        matches.isEmpty() -> {
            EmptyState(
                "No matching channels",
                "Your provider may name this channel differently. Try searching your lineup by name or network.",
            ) {
                ActionButton("Search \"$searchQuery\"", { vm.openChannels(searchQuery) }, Modifier.focusRequester(searchFocus), primary = true)
                ActionButton("Browse channels", { vm.openChannels() })
            }
            LaunchedEffect(Unit) { searchFocus.requestFocusSafely() }
        }
        else -> {
            val channels = remember(matches) { matches.map { it.channel } }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
            ) {
                itemsIndexed(matches, key = { _, m -> m.channel.id }) { i, m ->
                    ChannelRow(
                        channel = m.channel,
                        subtitle = (listOf(m.channel.group) + m.reasons).joinToString("  •  "),
                        badge = when {
                            i == 0 && m.exact -> "Best match"
                            m.exact -> "Event channel"
                            else -> null
                        },
                        onClick = { vm.play(channels, i, eventId) },
                        onLongClick = { vm.addToMultiview(m.channel) },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                item(key = "search") {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("Search channels", { vm.openChannels(searchQuery) })
                        ActionButton("Browse all", { vm.openChannels() })
                    }
                }
            }
            LaunchedEffect(matches) { firstFocus.requestFocusSafely() }
        }
    }
}

@Composable
fun ChannelRow(
    channel: Channel,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    playing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(64.dp),
        focusedScale = 1.02f,
        containerColor = if (playing) AppColors.Accent.copy(alpha = 0.16f) else AppColors.Card,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (playing) {
                Text("▶ Playing", fontSize = 12.sp, color = AppColors.Accent, fontWeight = FontWeight.Bold)
            } else if (badge != null) {
                Box(
                    Modifier.background(AppColors.Accent.copy(alpha = 0.2f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(badge, fontSize = 11.sp, color = AppColors.Accent, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun Scoreboard(vm: AppViewModel, game: Game, modifier: Modifier) {
    Column(
        modifier.background(AppColors.Surface, RoundedCornerShape(18.dp)).padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(game.league.label, fontSize = 13.sp, color = AppColors.TextDim, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusBadge(game, 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        BigTeam(game.away, game, "Away")
        Spacer(Modifier.height(16.dp))
        BigTeam(game.home, game, "Home")
        Spacer(Modifier.height(20.dp))

        Text(
            when (game.state) {
                GameState.PRE -> "Starts ${formatStart(game.startMillis)}"
                else -> game.detail
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (game.state == GameState.LIVE) AppColors.Live else AppColors.Text,
        )
        game.situation?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 14.sp, color = AppColors.Text)
        }
        Spacer(Modifier.weight(1f))
        if (game.broadcasts.isNotEmpty()) {
            Text("TV: " + game.broadcasts.joinToString(", "), fontSize = 13.sp, color = AppColors.TextDim, maxLines = 2)
        }
        game.venue?.let { Text(it, fontSize = 13.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (game.state == GameState.LIVE) {
            Spacer(Modifier.height(6.dp))
            Text("Score updates automatically", fontSize = 11.sp, color = AppColors.TextDim)
        }
        Spacer(Modifier.height(10.dp))
        // Star either team to follow it (My Teams row, score alerts).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (home in listOf(false, true)) {
                val team = if (home) game.home else game.away
                val fav = vm.isFavorite(game.league.key, team.id)
                Chip((if (fav) "★ " else "☆ ") + team.abbreviation, fav, { vm.toggleFavorite(vm.favoriteFor(game, home)) })
            }
        }
    }
}

@Composable
private fun BigTeam(team: TeamScore, game: Game, side: String) {
    val lost = game.state == GameState.FINAL && !team.winner && (game.home.winner || game.away.winner)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team.logo, team.abbreviation, 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                team.displayName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (lost) AppColors.TextDim else AppColors.Text,
            )
            Text(listOfNotNull(side, team.record).joinToString(" · "), fontSize = 12.sp, color = AppColors.TextDim)
        }
        if (game.state != GameState.PRE) {
            Text(
                team.score,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.End,
                color = if (lost) AppColors.TextDim else AppColors.Text,
            )
        }
    }
}
