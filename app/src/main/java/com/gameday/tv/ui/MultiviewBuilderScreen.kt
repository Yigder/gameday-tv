package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.ui.theme.AppColors

/**
 * YouTube TV's "Build a multiview": tick up to four games or channels, then watch them together.
 */
@Composable
fun MultiviewBuilderScreen(vm: AppViewModel) {
    val screenKey = Screen.MultiviewBuilder.key
    val max = vm.maxStreams.coerceAtMost(4)
    val picked = remember { mutableStateListOf<Channel>().apply { addAll(vm.builderSeed.take(max)) } }
    var suggestions by remember { mutableStateOf<List<Suggestion>?>(null) }
    LaunchedEffect(Unit) { suggestions = vm.liveSuggestions() }
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    InitialFocus(vm, screenKey, first)

    val yours = (vm.favoriteChannels + vm.recentChannels).distinctBy { it.id }.take(20)
    val sports = vm.catalog?.sportsChannels?.take(40).orEmpty()

    fun toggle(c: Channel) {
        val i = picked.indexOfFirst { it.id == c.id }
        when {
            i >= 0 -> picked.removeAt(i)
            picked.size >= max -> vm.showMessage(if (max < 4) "Your provider allows $max streams at once" else "Multiview shows up to 4")
            else -> picked.add(c)
        }
    }

    Row(Modifier.fillMaxSize().padding(top = 36.dp)) {
        // ---- left: what's picked ----
        Column(Modifier.width(300.dp).padding(start = 48.dp, end = 16.dp)) {
            Text("Build a multiview", fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text("Choose up to $max to watch at once. The screen you highlight plays the sound.", fontSize = 13.sp, color = AppColors.TextDim)
            Spacer(Modifier.height(18.dp))
            // Layout preview.
            Box(Modifier.fillMaxWidth().height(134.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)).padding(4.dp)) {
                val cells = picked.size.coerceAtLeast(1)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val rows = if (cells <= 2) listOf(picked.take(2)) else listOf(picked.take(2), picked.drop(2))
                    rows.forEach { row ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            (0 until maxOf(row.size, 1)).forEach { i ->
                                val c = row.getOrNull(i)
                                Box(
                                    Modifier.weight(1f).fillMaxSize().background(if (c != null) Color(0xFF333333) else Color(0xFF222222), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (c != null) ChannelLogo(c, 40.dp, background = Color.Transparent)
                                    else Text("Pick a game", fontSize = 12.sp, color = AppColors.TextFaint)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            picked.forEachIndexed { i, c ->
                Text("${i + 1}. ${cleanChannelName(c.name)}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            PillButton(
                if (picked.size >= 2) "Watch ${picked.size} in multiview" else "Pick at least 2",
                { if (picked.size >= 2) vm.startMultiview(picked.toList()) else vm.showMessage("Pick at least 2 to build a multiview") },
                icon = Icons.Multiview,
                primary = picked.size >= 2,
            )
            if (picked.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                PillButton("Clear", { picked.clear() })
            }
        }

        // ---- right: choices ----
        Box(Modifier.weight(1f)) {
            PivotScroll(offset = ROW_TITLE) {
                LazyColumn(Modifier.fillMaxSize().focusRequester(first), state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 240.dp)) {
                    val sugg = suggestions
                    if (sugg == null) {
                        item(key = "loading") { LoadingState("Finding live games…") }
                    } else if (sugg.isNotEmpty()) {
                        cardRow("live", "Live sports", nav) {
                            items(sugg, key = { "s:" + it.eventId }) { s ->
                                val game = vm.gameById(s.eventId)
                                PickCard(vm, s.title, "${cleanChannelName(s.channel.name)} · ${s.subtitle}", picked.any { it.id == s.channel.id },
                                    { toggle(s.channel) }, screenKey, "s:" + s.eventId) {
                                    if (game != null) GameArt(game, showScore = !vm.hideScores) else ChannelThumb(s.channel, null)
                                }
                            }
                        }
                    }
                    if (yours.isNotEmpty()) {
                        cardRow("yours", "Your channels", nav) {
                            items(yours, key = { "y:" + it.id }) { c -> ChannelPick(vm, c, picked.any { it.id == c.id }, { toggle(c) }, screenKey, "y:") }
                        }
                    }
                    if (sports.isNotEmpty()) {
                        cardRow("sports", "Sports channels", nav) {
                            items(sports, key = { "c:" + it.id }) { c -> ChannelPick(vm, c, picked.any { it.id == c.id }, { toggle(c) }, screenKey, "c:") }
                        }
                    }
                    if (sugg != null && sugg.isEmpty() && yours.isEmpty() && sports.isEmpty()) {
                        item(key = "empty") { EmptyState("Nothing to add yet", "When games are live, they'll show up here with the best channel for each.") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelPick(vm: AppViewModel, c: Channel, selected: Boolean, onToggle: () -> Unit, screenKey: String, prefix: String) {
    LaunchedEffect(c.id) { vm.requestEpg(c) }
    val program = vm.nowPlaying(c.id)
    PickCard(vm, program?.title ?: cleanChannelName(c.name), cleanChannelName(c.name), selected, onToggle, screenKey, prefix + c.id) {
        ChannelThumb(c, program)
    }
}

@Composable
private fun PickCard(
    vm: AppViewModel,
    title: String,
    subtitle: String,
    selected: Boolean,
    onToggle: () -> Unit,
    screenKey: String,
    focusKey: String,
    thumb: @Composable () -> Unit,
) {
    MediaCard(
        title = title,
        subtitle = subtitle,
        onClick = onToggle,
        modifier = Modifier.rememberFocus(vm, screenKey, focusKey),
    ) {
        thumb()
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopEnd) {
            Box(Modifier.size(26.dp)) { if (selected) CheckCircle(true) else Box(Modifier.fillMaxSize().background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape)) }
        }
    }
}
