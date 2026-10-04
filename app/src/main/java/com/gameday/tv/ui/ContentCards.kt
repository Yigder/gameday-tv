package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.RecStatus
import com.gameday.tv.data.Recording
import com.gameday.tv.data.ResumePoint
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.theme.AppColors

/*
 * Ready-made cards for the content types that appear across Sports, Live, Library and Search.
 * Each one remembers focus for its screen, feeds the hero header, and opens a menu on long press.
 */

@Composable
fun GameCard(vm: AppViewModel, game: Game, screenKey: String, onHero: (HeroInfo) -> Unit = {}, keyPrefix: String = "") {
    val rec = vm.recordingForEvent(game.id) != null
    MediaCard(
        title = game.title,
        subtitle = "${game.league.label} · ${statusLine(game, vm.hideScores)}",
        onClick = { if (game.state == GameState.LIVE) vm.watchGame(game) else vm.openGame(game) },
        onLongClick = { gameMenu(vm, game) },
        onFocus = { onHero(heroFor(game, vm.hideScores)) },
        modifier = Modifier.rememberFocus(vm, screenKey, keyPrefix + game.id),
    ) { GameThumb(game, vm.hideScores, rec) }
}

@Composable
fun TournamentCard(vm: AppViewModel, t: Tournament, screenKey: String, onHero: (HeroInfo) -> Unit = {}) {
    MediaCard(
        title = t.name,
        subtitle = "${t.tour.label} · ${t.detail}",
        onClick = { if (t.roundInProgress) vm.watchTournament(t) else vm.openTournament(t) },
        onLongClick = { tournamentMenu(vm, t) },
        onFocus = { onHero(heroFor(t, vm.hideScores)) },
        modifier = Modifier.rememberFocus(vm, screenKey, t.id),
    ) { TournamentThumb(t, vm.hideScores) }
}

@Composable
fun ChannelCard(
    vm: AppViewModel,
    channel: Channel,
    screenKey: String,
    list: List<Channel> = listOf(channel),
    onHero: (HeroInfo) -> Unit = {},
    keyPrefix: String = "",
) {
    LaunchedEffect(channel.id) { vm.requestEpg(channel) }
    val now = System.currentTimeMillis()
    val program = vm.nowPlaying(channel.id, now)
    MediaCard(
        title = program?.title ?: cleanChannelName(channel.name),
        subtitle = if (program != null) "${cleanChannelName(channel.name)} · ${minutesLeft(program.endMillis, now)}" else channel.group,
        onClick = { vm.playChannel(channel, list) },
        onLongClick = { channelMenu(vm, channel, list) },
        onFocus = { onHero(heroFor(channel, program, now)) },
        modifier = Modifier.rememberFocus(vm, screenKey, keyPrefix + channel.id),
    ) { ChannelThumb(channel, program, now) }
}

/** NFL RedZone in "Live now": plays the best RedZone channel (the others are a CH+/CH− away). */
@Composable
fun RedZoneCard(vm: AppViewModel, channels: List<Channel>, liveGames: Int, screenKey: String, onHero: (HeroInfo) -> Unit = {}) {
    val channel = channels.first()
    val subtitle = "NFL · $liveGames ${if (liveGames == 1) "game" else "games"} live"
    MediaCard(
        title = "NFL RedZone",
        subtitle = subtitle,
        onClick = { vm.playChannel(channel, channels) },
        onLongClick = { channelMenu(vm, channel, channels) },
        onFocus = { onHero(HeroInfo("NFL RedZone", listOf(subtitle, cleanChannelName(channel.name)), "Every touchdown from every Sunday game, with a score bug for each live game.", live = true, channel = channel)) },
        modifier = Modifier.rememberFocus(vm, screenKey, "redzone"),
    ) {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFB3001B), Color(0xFF4A0010)))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("RED ZONE", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 1.sp)
                Text("$liveGames LIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xCCFFFFFF))
            }
        }
    }
}

@Composable
fun ResumeCard(vm: AppViewModel, point: ResumePoint, screenKey: String, onHero: (HeroInfo) -> Unit = {}) {
    MediaCard(
        title = point.title,
        subtitle = "${point.subtitle} · ${durationText(point.durationMs - point.positionMs)} left",
        onClick = { vm.playResume(point) },
        onLongClick = {
            vm.showDialog(AppDialog(point.title, point.subtitle, actions = listOf(
                DialogAction("Resume", Icons.Play) { vm.dismissDialog(); vm.playResume(point) },
                DialogAction("Remove from Continue watching", Icons.Close) { vm.dismissDialog(); vm.removeResume(point.key) },
            )))
        },
        onFocus = { onHero(HeroInfo(point.title, listOf(point.subtitle, "${durationText(point.durationMs - point.positionMs)} left"), image = point.image, progress = point.progress)) },
        modifier = Modifier.rememberFocus(vm, screenKey, "r:" + point.key),
    ) {
        PosterThumb(point.image, point.title)
        ProgressLine(point.progress, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun RecordingCard(vm: AppViewModel, r: Recording, screenKey: String, onHero: (HeroInfo) -> Unit = {}) {
    val status = when (r.status) {
        RecStatus.SCHEDULED -> formatStart(r.startMillis)
        RecStatus.RECORDING -> "Recording now"
        RecStatus.DONE -> "${formatDay(r.startMillis)} · ${durationText(r.durationMs)}"
        else -> r.status.label
    }
    val resumeAt = vm.resumeFor("rec:${r.id}")
    MediaCard(
        title = r.title,
        subtitle = "${cleanChannelName(r.channelName)} · $status",
        onClick = { if (r.status == RecStatus.DONE || r.status == RecStatus.RECORDING) vm.playRecording(r) else recordingMenu(vm, r) },
        onLongClick = { recordingMenu(vm, r) },
        onFocus = { onHero(HeroInfo(r.title, listOf(cleanChannelName(r.channelName), status), r.error ?: r.subtitle, live = r.status == RecStatus.RECORDING)) },
        modifier = Modifier.rememberFocus(vm, screenKey, "rec:" + r.id),
    ) {
        val game = r.eventId?.let { vm.gameById(it) }
        if (game != null) GameArt(game, showScore = false)
        else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B2B2B), Color(0xFF151515)))), contentAlignment = Alignment.Center) {
            Text(r.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.padding(10.dp))
        }
        Box(Modifier.fillMaxSize().padding(6.dp)) {
            when (r.status) {
                RecStatus.RECORDING -> Tag("● REC", Modifier.align(Alignment.TopStart), color = AppColors.LiveBadge)
                RecStatus.SCHEDULED -> Tag("Scheduled", Modifier.align(Alignment.TopStart))
                RecStatus.FAILED -> Tag("Failed", Modifier.align(Alignment.TopStart), color = Color(0xCC5F2120))
                else -> Unit
            }
        }
        resumeAt?.let { ProgressLine(it.progress, Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
fun TeamCard(vm: AppViewModel, team: FavoriteTeam, screenKey: String) {
    MediaCard(
        title = team.name,
        subtitle = if (team.record) "Recording all games" else com.gameday.tv.data.Leagues.byKey(team.leagueKey)?.label,
        onClick = { vm.openTeam(team.leagueKey, team.id) },
        modifier = Modifier.rememberFocus(vm, screenKey, "t:" + team.key),
        width = 150.dp,
        aspect = 1f,
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF242424)), contentAlignment = Alignment.Center) {
            TeamLogo(team.logo, team.abbreviation, 76.dp)
        }
        if (team.record) Box(Modifier.fillMaxSize().padding(6.dp)) { Tag("REC", Modifier.align(Alignment.TopEnd), color = AppColors.LiveBadge) }
    }
}

@Composable
fun PresetCard(vm: AppViewModel, preset: MultiviewPreset, screenKey: String, onHero: (HeroInfo) -> Unit = {}) {
    val lines = if (preset.games.isNotEmpty()) preset.games.map { it.title } else preset.channels.map { cleanChannelName(it.name) }
    MediaCard(
        title = preset.title,
        subtitle = preset.subtitle,
        onClick = { vm.startMultiview(preset.channels) },
        onFocus = { onHero(HeroInfo(preset.title, listOf("Multiview", preset.subtitle), lines.joinToString("\n"), live = true)) },
        modifier = Modifier.rememberFocus(vm, screenKey, "mv:" + preset.key),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val tiles: List<@Composable () -> Unit> = if (preset.games.isNotEmpty()) {
                preset.games.take(4).map { g -> @Composable { GameArt(g, logoFraction = 0.55f, showScore = false) } }
            } else {
                preset.channels.take(4).map { c ->
                    @Composable {
                        Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
                            ChannelLogo(c, 34.dp, background = Color.Transparent)
                        }
                    }
                }
            }
            tiles.chunked(2).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { tile -> Box(Modifier.weight(1f).fillMaxSize()) { tile() } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).background(Color(0xCC000000), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Multiview, null, Modifier.size(22.dp))
            }
        }
        Box(Modifier.fillMaxSize().padding(6.dp)) { LiveBadge(Modifier.align(Alignment.BottomStart), small = true) }
    }
}

/** "See all" tile at the end of a row. */
@Composable
fun MoreCard(label: String, onClick: () -> Unit, width: Int = CARD_WIDTH, aspect: Float = 16f / 9f) {
    MediaCard(title = label, subtitle = null, onClick = onClick, width = width.dp, aspect = aspect) {
        Box(Modifier.fillMaxSize().background(Color(0xFF242424)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.ChevronRight, null, Modifier.size(32.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 13.sp)
            }
        }
    }
}
