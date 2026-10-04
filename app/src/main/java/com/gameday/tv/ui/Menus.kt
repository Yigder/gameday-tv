package com.gameday.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.Program
import com.gameday.tv.data.RecStatus
import com.gameday.tv.data.Recording
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.theme.AppColors

/** YouTube TV-style side sheet for [AppDialog]s: dims the screen, actions stacked on the right. */
@Composable
fun DialogHost(vm: AppViewModel, dialog: AppDialog) {
    val first = remember(dialog) { FocusRequester() }
    LaunchedEffect(dialog) { first.requestFocusSafely(120) }
    // Registered after the screen's handlers (the menu is composed on top), so Back closes the
    // menu even over the player or a tab.
    BackHandler { vm.dismissDialog() }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0x99000000), Color(0xF2000000))))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(420.dp)
                .fillMaxHeight()
                .background(Color(0xFF1F1F1F))
                // The page is still underneath: without this, Up / Down past the first or last
                // action moved focus onto whatever card was behind the menu.
                .trapFocus()
                .padding(horizontal = 28.dp, vertical = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(dialog.title, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 3)
            dialog.subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 14.sp, color = AppColors.TextDim, maxLines = 2)
            }
            dialog.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 14.sp, color = Color(0xFFCCCCCC), lineHeight = 20.sp)
            }
            Spacer(Modifier.height(22.dp))
            dialog.actions.forEachIndexed { i, a ->
                SettingRow(
                    title = a.label,
                    onClick = a.onClick,
                    icon = a.icon,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Long-press menus ("more options") for cards
// ---------------------------------------------------------------------------------------------

fun gameMenu(vm: AppViewModel, game: Game) {
    val rec = vm.recordingForEvent(game.id)
    val actions = buildList {
        if (game.state != GameState.FINAL) add(DialogAction("Watch", Icons.Play) { vm.dismissDialog(); vm.watchGame(game) })
        if (game.state == GameState.LIVE) {
            add(DialogAction("Watch in Multiview", Icons.Multiview) { vm.dismissDialog(); vm.multiviewWithGame(game) })
        }
        if (game.state != GameState.FINAL && vm.catalog != null) {
            if (rec == null) add(DialogAction(if (game.state == GameState.LIVE) "Record" else "Record this game", Icons.Record) { vm.dismissDialog(); vm.recordGame(game) })
            else add(DialogAction(if (rec.status == RecStatus.RECORDING) "Stop recording" else "Cancel recording", Icons.Close) { vm.dismissDialog(); vm.cancelRecording(rec) })
        }
        add(DialogAction("Game details & channels", Icons.Info) { vm.dismissDialog(); vm.openGame(game) })
        listOf(game.away, game.home).forEach { t ->
            add(DialogAction("${t.displayName} team page", Icons.Trophy) { vm.dismissDialog(); vm.openTeam(game.league.key, t.id) })
        }
    }
    vm.showDialog(AppDialog(game.title, "${game.league.label} · ${statusLine(game, vm.hideScores)}", actions = actions))
}

fun tournamentMenu(vm: AppViewModel, t: Tournament) {
    vm.showDialog(
        AppDialog(
            t.name, "${t.tour.label} · ${t.detail}",
            actions = listOf(
                DialogAction("Watch", Icons.Play) { vm.dismissDialog(); vm.watchTournament(t) },
                DialogAction("Leaderboard & channels", Icons.Info) { vm.dismissDialog(); vm.openTournament(t) },
            ),
        ),
    )
}

fun channelMenu(vm: AppViewModel, channel: Channel, list: List<Channel> = listOf(channel)) {
    val program = vm.nowPlaying(channel.id)
    val fav = vm.isFavoriteChannel(channel.id)
    vm.showDialog(
        AppDialog(
            title = cleanChannelName(channel.name),
            subtitle = program?.let { "${it.title} · ${minutesLeft(it.endMillis)}" } ?: channel.group,
            actions = listOf(
                DialogAction("Watch", Icons.Play) { vm.dismissDialog(); vm.playChannel(channel, list) },
                DialogAction("Add to Multiview", Icons.Multiview) { vm.dismissDialog(); vm.multiviewWith(channel) },
                DialogAction("Record", Icons.Record) { vm.dismissDialog(); recordMenu(vm, channel) },
                DialogAction(if (fav) "Remove from favorites" else "Add to favorite channels", Icons.Star) { vm.dismissDialog(); vm.toggleFavoriteChannel(channel) },
                DialogAction("Channel schedule", Icons.Guide) { vm.dismissDialog(); vm.openChannel(channel) },
            ),
        ),
    )
}

/** How long to record a channel that's on now. */
fun recordMenu(vm: AppViewModel, channel: Channel) {
    val program = vm.nowPlaying(channel.id)
    val actions = buildList {
        if (program != null) add(DialogAction("Until \"${program.title}\" ends (${minutesLeft(program.endMillis)})", Icons.Record) {
            vm.dismissDialog(); vm.recordProgram(channel, program)
        })
        listOf(30, 60, 120, 180, 240).forEach { m ->
            add(DialogAction("For ${durationText(m * 60_000L)}", Icons.Clock) { vm.dismissDialog(); vm.recordNow(channel, m) })
        }
    }
    vm.showDialog(AppDialog("Record ${cleanChannelName(channel.name)}", "Saved to your library on this TV", actions = actions))
}

/** A guide entry: watch it now, replay it, or record it. */
fun programMenu(vm: AppViewModel, channel: Channel, program: Program, list: List<Channel>) {
    val now = System.currentTimeMillis()
    val rec = vm.recordingForProgram(channel.id, program.startMillis)
    val actions = buildList {
        when {
            program.isOnNow(now) -> {
                add(DialogAction("Watch live", Icons.Play) { vm.dismissDialog(); vm.playChannel(channel, list) })
                if (vm.catchupUrl(channel, program) != null) {
                    add(DialogAction("Watch from the start", Icons.Restart) { vm.dismissDialog(); vm.playCatchup(channel, program) })
                }
            }
            program.endMillis <= now && vm.catchupUrl(channel, program) != null ->
                add(DialogAction("Watch", Icons.Play) { vm.dismissDialog(); vm.playCatchup(channel, program) })
        }
        if (program.endMillis > now) {
            if (rec == null) add(DialogAction(if (program.isOnNow(now)) "Record the rest" else "Record", Icons.Record) { vm.dismissDialog(); vm.recordProgram(channel, program) })
            else add(DialogAction("Cancel recording", Icons.Close) { vm.dismissDialog(); vm.cancelRecording(rec) })
        }
        add(DialogAction("Channel schedule", Icons.Guide) { vm.dismissDialog(); vm.openChannel(channel) })
    }
    vm.showDialog(
        AppDialog(
            title = program.title,
            subtitle = "${cleanChannelName(channel.name)} · ${formatDay(program.startMillis)} ${formatRange(program.startMillis, program.endMillis)}",
            message = program.description.takeIf { it.isNotBlank() },
            actions = actions,
        ),
    )
}

fun recordingMenu(vm: AppViewModel, r: Recording) {
    val actions = buildList {
        when (r.status) {
            RecStatus.DONE -> add(DialogAction("Play", Icons.Play) { vm.dismissDialog(); vm.playRecording(r) })
            RecStatus.RECORDING -> {
                add(DialogAction("Watch live", Icons.Play) { vm.dismissDialog(); vm.playRecording(r) })
                add(DialogAction("Stop recording", Icons.Close) { vm.dismissDialog(); vm.cancelRecording(r) })
            }
            RecStatus.SCHEDULED -> add(DialogAction("Cancel recording", Icons.Close) { vm.dismissDialog(); vm.cancelRecording(r) })
            else -> Unit
        }
        if (r.status != RecStatus.SCHEDULED) add(DialogAction("Delete", Icons.Delete) { vm.dismissDialog(); vm.deleteRecording(r) })
    }
    val detail = when (r.status) {
        RecStatus.SCHEDULED -> "Starts ${formatStart(r.startMillis)} on ${cleanChannelName(r.channelName)}"
        RecStatus.RECORDING -> "Recording now · ${bytesText(r.bytes)}"
        RecStatus.DONE -> "${durationText(r.durationMs)} · ${bytesText(r.bytes)}"
        else -> r.error ?: r.status.label
    }
    vm.showDialog(AppDialog(r.title, detail, message = r.error.takeIf { r.status == RecStatus.DONE }, actions = actions))
}

fun statusLine(game: Game, hideScores: Boolean): String = when (game.state) {
    GameState.PRE -> formatStart(game.startMillis)
    GameState.LIVE -> if (hideScores) "Live" else "${game.away.abbreviation} ${game.away.score}-${game.home.score} ${game.home.abbreviation} · ${game.shortDetail}"
    GameState.FINAL -> if (hideScores) "Final" else "Final · ${game.away.abbreviation} ${game.away.score}-${game.home.score} ${game.home.abbreviation}"
}
