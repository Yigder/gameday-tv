package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.ChannelMatch
import com.gameday.tv.data.GameState
import com.gameday.tv.data.GolfPlayer
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.theme.AppColors

@Composable
fun TournamentScreen(vm: AppViewModel, tournamentId: String) {
    val t = vm.tournamentById(tournamentId)
    if (t == null) {
        EmptyState("Tournament not found", "This event is no longer on the scoreboard.", Modifier.padding(top = 120.dp)) {
            ActionButton("Back", { vm.back() }, primary = true)
        }
        return
    }

    val iptv = vm.iptv
    var matches by remember(tournamentId) { mutableStateOf<List<ChannelMatch>?>(null) }
    LaunchedEffect(tournamentId, iptv) {
        matches = null
        if (iptv is IptvStatus.Ready) matches = vm.matchChannels(t, iptv.catalog)
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Leaderboard(t, Modifier.width(360.dp).fillMaxHeight())
        Spacer(Modifier.width(32.dp))
        WatchPanel(
            vm = vm,
            eventId = t.id,
            matches = matches,
            searchQuery = "Golf",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun Leaderboard(t: Tournament, modifier: Modifier) {
    Column(modifier.background(AppColors.Surface, RoundedCornerShape(18.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.tour.label, fontSize = 13.sp, color = AppColors.TextDim, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TournamentStatus(t, 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(t.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(
            if (t.state == GameState.PRE) "Starts ${formatStart(t.startMillis)}" else t.detail,
            t.broadcasts.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "TV: "),
        ).joinToString("  ·  ")
        Text(sub, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))

        if (t.leaders.isEmpty()) {
            Text("Leaderboard will appear once play begins.", fontSize = 14.sp, color = AppColors.TextDim)
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            HeaderCell("POS", Modifier.width(38.dp))
            HeaderCell("PLAYER", Modifier.weight(1f))
            HeaderCell("TOT", Modifier.width(42.dp))
            HeaderCell("TODAY", Modifier.width(48.dp))
            HeaderCell("THRU", Modifier.width(38.dp))
        }
        // Show as many rows as fit; the list isn't focusable so it must not need scrolling.
        FittingColumn(Modifier.weight(1f)) {
            t.leaders.forEach { LeaderRow(it) }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, fontSize = 10.sp, color = AppColors.TextDim, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
private fun LeaderRow(p: GolfPlayer) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.position, fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.width(38.dp))
        Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(p.toPar, fontSize = 14.sp, fontWeight = FontWeight.Black, color = parColor(p.toPar), modifier = Modifier.width(42.dp))
        Text(p.today ?: "–", fontSize = 13.sp, color = p.today?.let(::parColor) ?: AppColors.TextDim, modifier = Modifier.width(48.dp))
        Text(p.thru ?: "–", fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.width(38.dp))
    }
}

/** Lays children out vertically and simply drops the ones that don't fit. */
@Composable
private fun FittingColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        var used = 0
        val placeables = buildList {
            for (m in measurables) {
                val p = m.measure(loose)
                if (used + p.height > constraints.maxHeight) break
                used += p.height
                add(p)
            }
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = 0
            placeables.forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}
