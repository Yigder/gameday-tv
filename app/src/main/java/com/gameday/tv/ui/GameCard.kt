package com.gameday.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.TeamScore
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.theme.AppColors

@Composable
fun GameCard(game: Game, onClick: () -> Unit, modifier: Modifier = Modifier, favoriteTeamIds: Set<String> = emptySet()) {
    FocusSurface(onClick = onClick, modifier = modifier.width(272.dp).height(138.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(game.league.label, game.broadcasts.firstOrNull()).joinToString("  •  "),
                    fontSize = 11.sp,
                    color = AppColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(game)
            }
            Spacer(Modifier.height(10.dp))
            TeamLine(game.away, game, game.away.id in favoriteTeamIds)
            Spacer(Modifier.height(6.dp))
            TeamLine(game.home, game, game.home.id in favoriteTeamIds)
            Spacer(Modifier.weight(1f))
            if (game.situation != null) {
                Text(game.situation, fontSize = 11.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TeamLine(team: TeamScore, game: Game, favorite: Boolean) {
    val lost = game.state == GameState.FINAL && !team.winner && (game.home.winner || game.away.winner)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team.logo, team.abbreviation, 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            (if (favorite) "★ " else "") + team.shortName,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                favorite -> AppColors.Accent
                lost -> AppColors.TextDim
                else -> AppColors.Text
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (game.state == GameState.LIVE && game.possessionTeamId == team.id) {
            Text("◀", color = AppColors.Accent, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
        }
        if (game.state == GameState.PRE) {
            team.record?.let { Text(it, fontSize = 12.sp, color = AppColors.TextDim) }
        } else {
            Text(
                team.score,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = if (lost) AppColors.TextDim else AppColors.Text,
            )
        }
    }
}

@Composable
fun TournamentCard(t: Tournament, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick = onClick, modifier = modifier.width(272.dp).height(138.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(t.tour.label, t.broadcasts.firstOrNull()).joinToString("  •  "),
                    fontSize = 11.sp,
                    color = AppColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TournamentStatus(t)
            }
            Spacer(Modifier.height(6.dp))
            Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            if (t.state == GameState.PRE) {
                Text("Starts ${formatStart(t.startMillis)}", fontSize = 13.sp, color = AppColors.TextDim)
                Text("${t.fieldSize} players", fontSize = 12.sp, color = AppColors.TextDim)
            } else {
                t.leaders.take(3).forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(p.position, fontSize = 12.sp, color = AppColors.TextDim, modifier = Modifier.width(28.dp))
                        Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(p.toPar, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = parColor(p.toPar))
                    }
                }
            }
        }
    }
}

@Composable
fun TournamentStatus(t: Tournament, fontSize: TextUnit = 11.sp) {
    when {
        t.roundInProgress -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            LiveDot()
            Text(t.detail.replace("Round ", "R").replace(" - In Progress", ""), color = AppColors.Live, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        t.state == GameState.LIVE -> Text(t.detail.replace("Round ", "R"), color = AppColors.Text, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        t.state == GameState.FINAL -> Text("FINAL", color = AppColors.TextDim, fontSize = fontSize, fontWeight = FontWeight.Bold)
        else -> Text(formatStart(t.startMillis), color = AppColors.Text, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun StatusBadge(game: Game, fontSize: TextUnit = 11.sp) {
    when (game.state) {
        GameState.LIVE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            LiveDot()
            Text(game.shortDetail, color = AppColors.Live, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        GameState.FINAL -> Text(game.shortDetail.uppercase(), color = AppColors.TextDim, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        GameState.PRE -> Text(formatStart(game.startMillis), color = AppColors.Text, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
