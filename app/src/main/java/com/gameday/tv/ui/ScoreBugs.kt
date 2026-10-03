package com.gameday.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.ScoreBugMode
import com.gameday.tv.data.TeamScore
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

private val BugBackground = Color(0xE60B111B)

/**
 * When the score bug is on screen. In [ScoreBugMode.ON_PRESS] it shows when a stream starts,
 * hides after a few seconds, and comes back on [show] (a remote button) or when the score changes.
 */
@Stable
class BugState {
    var visible by mutableStateOf(true); internal set
    var flash by mutableStateOf(false); internal set
    internal var showNonce by mutableIntStateOf(0)
    internal var flashNonce by mutableIntStateOf(0)

    fun show() {
        showNonce++
    }

    fun toggle() {
        if (visible) visible = false else show()
    }
}

/**
 * @param key resets the state (e.g. the channel id), so every new stream starts with the bug visible.
 * @param scoreKey changes whenever the score (or golf leader) changes.
 */
@Composable
fun rememberBugState(key: Any?, scoreKey: String?, mode: ScoreBugMode, popOnScore: Boolean, showMillis: Long = 8_000): BugState {
    val state = remember(key) { BugState() }
    var lastScore by remember(key) { mutableStateOf(scoreKey) }
    LaunchedEffect(state, scoreKey) {
        if (scoreKey != null && lastScore != null && scoreKey != lastScore) state.flashNonce++
        lastScore = scoreKey
    }
    LaunchedEffect(state, state.showNonce, mode) {
        state.visible = true
        if (mode == ScoreBugMode.ALWAYS) return@LaunchedEffect
        delay(showMillis)
        state.visible = false
    }
    LaunchedEffect(state, state.flashNonce) {
        if (state.flashNonce == 0) return@LaunchedEffect
        state.flash = true
        if (popOnScore) state.show()
        delay(6_000)
        state.flash = false
    }
    return state
}

/** Score bug (game) or leaderboard bug (golf) that animates in and out with [state]. */
@Composable
fun EventBug(game: Game?, tournament: Tournament?, state: BugState, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (game == null && tournament == null) return
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        when {
            game != null -> ScoreBug(game, flash = state.flash, compact = compact)
            tournament != null -> GolfBug(tournament, flash = state.flash, compact = compact)
        }
    }
}

fun scoreKeyOf(game: Game?, tournament: Tournament?): String? = when {
    game != null -> "${game.away.score}-${game.home.score}"
    tournament != null -> tournament.leaders.firstOrNull()?.let { "${it.id}${it.toPar}" }
    else -> null
}

/** Live score overlay for a team game. */
@Composable
fun ScoreBug(game: Game, modifier: Modifier = Modifier, flash: Boolean = false, compact: Boolean = false) {
    val shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
    Row(
        modifier
            .background(BugBackground, shape)
            .border(if (flash) 2.dp else 1.dp, if (flash) AppColors.Accent else AppColors.Border, shape)
            .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BugTeam(game.away, game, compact)
        Spacer(Modifier.width(if (compact) 8.dp else 14.dp))
        BugTeam(game.home, game, compact)
        Spacer(Modifier.width(if (compact) 8.dp else 14.dp))
        Box(Modifier.width(1.dp).height(if (compact) 18.dp else 30.dp).background(AppColors.Border))
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        if (compact) {
            StatusBadge(game, 10.sp)
        } else {
            Column {
                StatusBadge(game, 13.sp)
                Text(
                    if (flash) "SCORE UPDATE" else game.league.label,
                    fontSize = 10.sp,
                    color = if (flash) AppColors.Accent else AppColors.TextDim,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BugTeam(team: TeamScore, game: Game, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team.logo, team.abbreviation, if (compact) 16.dp else 26.dp)
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        Text(team.abbreviation, fontSize = if (compact) 11.sp else 15.sp, fontWeight = FontWeight.Bold)
        if (game.state != GameState.PRE) {
            Spacer(Modifier.width(if (compact) 5.dp else 8.dp))
            Text(team.score, fontSize = if (compact) 13.sp else 20.sp, fontWeight = FontWeight.Black)
        }
    }
}

/** Mini leaderboard overlay for a golf tournament. */
@Composable
fun GolfBug(t: Tournament, modifier: Modifier = Modifier, flash: Boolean = false, compact: Boolean = false) {
    val rows = t.leaders.take(if (compact) 2 else 5)
    val shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)

    Column(
        modifier
            .width(if (compact) 190.dp else 260.dp)
            .background(BugBackground, shape)
            .border(if (flash) 2.dp else 1.dp, if (flash) AppColors.Accent else AppColors.Border, shape)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 4.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.roundInProgress) {
                LiveDot(if (compact) 6.dp else 8.dp)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                if (flash) "NEW LEADER" else t.detail.replace("Round ", "R"),
                fontSize = if (compact) 9.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (flash) AppColors.Accent else if (t.roundInProgress) AppColors.Live else AppColors.TextDim,
                maxLines = 1,
            )
        }
        if (!compact) {
            Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
        }
        rows.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.position, fontSize = if (compact) 10.sp else 12.sp, color = AppColors.TextDim, modifier = Modifier.width(if (compact) 22.dp else 30.dp))
                Text(
                    p.shortName,
                    fontSize = if (compact) 11.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(p.toPar, fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.Black, color = parColor(p.toPar))
                if (!compact && p.thru != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(if (p.thru == "F") "F" else "thru ${p.thru}", fontSize = 10.sp, color = AppColors.TextDim)
                }
            }
        }
    }
}

fun parColor(toPar: String): Color = when {
    toPar.startsWith("-") -> AppColors.Live
    toPar.startsWith("+") -> AppColors.TextDim
    else -> AppColors.Text
}
