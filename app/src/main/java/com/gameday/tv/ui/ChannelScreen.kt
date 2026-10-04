package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.ui.theme.AppColors

// ---------------------------------------------------------------------------------------------
// Channel page: what's on now and the schedule
// ---------------------------------------------------------------------------------------------

@Composable
fun ChannelScreen(vm: AppViewModel, channelId: String) {
    val screenKey = Screen.ChannelDetail(channelId).key
    val channel = vm.channelById(channelId)
    if (channel == null) {
        EmptyState("Channel not found", "It may have been removed from your provider's lineup.", Modifier.padding(top = 120.dp)) { PillButton("Back", { vm.back() }) }
        return
    }
    LaunchedEffect(channelId) { vm.requestEpg(channel) }
    val now = System.currentTimeMillis()
    val programs = vm.programsFor(channel.id).filter { it.endMillis > now - channel.archiveDays * 86_400_000L }
    val current = programs.firstOrNull { it.isOnNow(now) }
    val primary = remember { FocusRequester() }
    InitialFocus(vm, screenKey, primary)
    val fav = vm.isFavoriteChannel(channel.id)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
        item(key = "header") {
            DetailHeader(
                title = current?.title ?: cleanChannelName(channel.name),
                meta = listOfNotNull(cleanChannelName(channel.name), channel.group, current?.let { minutesLeft(it.endMillis, now) },
                    if (channel.archiveDays > 0) "${channel.archiveDays}-day replay" else null),
                live = current != null,
                description = current?.description,
                art = {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
                        ChannelLogo(channel, 150.dp, background = Color.Transparent)
                    }
                },
            ) {
                PillButton("Watch live", { vm.playChannel(channel) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
                if (current != null && vm.catchupUrl(channel, current) != null) PillButton("Start over", { vm.playCatchup(channel, current) }, icon = Icons.Restart)
                PillButton("Record", { recordMenu(vm, channel) }, icon = Icons.Record)
                PillButton(if (fav) "Favorite" else "Add to favorites", { vm.toggleFavoriteChannel(channel) }, icon = if (fav) Icons.Check else Icons.Star)
                PillButton("Multiview", { vm.multiviewWith(channel) }, icon = Icons.Multiview)
            }
        }
        item(key = "sched-h") { SectionTitle("Schedule", Modifier.padding(start = 48.dp, bottom = 8.dp)) }
        if (programs.isEmpty()) {
            item(key = "none") { Text("No guide information for this channel.", color = AppColors.TextDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 48.dp)) }
        }
        items(programs, key = { it.key }) { p ->
            val onNow = p.isOnNow(now)
            val replay = p.endMillis <= now && vm.catchupUrl(channel, p) != null
            val rec = vm.recordingForProgram(channel.id, p.startMillis) != null
            SettingRow(
                title = p.title,
                onClick = { if (onNow) vm.playChannel(channel) else programMenu(vm, channel, p, listOf(channel)) },
                subtitle = p.description.takeIf { it.isNotBlank() },
                value = listOfNotNull(
                    if (rec) "● Rec" else null,
                    if (replay) "Replay" else null,
                    if (onNow) "On now" else "${formatDay(p.startMillis)} ${formatTime(p.startMillis)}",
                ).joinToString("  "),
                modifier = Modifier.padding(horizontal = 32.dp).rememberFocus(vm, screenKey, p.key),
            )
        }
    }
}
