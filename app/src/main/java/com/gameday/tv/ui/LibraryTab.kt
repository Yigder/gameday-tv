package com.gameday.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.RecStatus
import com.gameday.tv.ui.theme.AppColors

private enum class LibSection(val label: String) {
    RECORDINGS("Recordings"),
    SCHEDULED("Scheduled"),
    CONTINUE("Continue watching"),
    TEAMS("Teams"),
    MOVIES("Movies"),
    SHOWS("Shows"),
    CHANNELS("Favorite channels"),
}

/** Library: recordings, scheduled recordings, teams, saved movies & shows, favorite channels. */
@Composable
fun LibraryTab(vm: AppViewModel) {
    val screenKey = "main:${Tab.LIBRARY}"
    var section by rememberSaveable { mutableStateOf(LibSection.RECORDINGS) }
    val railFocus = remember { FocusRequester() }
    val selectedRail = remember { FocusRequester() }
    if (vm.tabWantsFocus) InitialFocus(vm, screenKey, railFocus)
    val savedMovies = vm.saved.filter { it.isMovie }
    val savedShows = vm.saved.filter { !it.isMovie }
    val continueWatching = vm.continueWatching

    val recordings = vm.recordings
    val done = recordings.filter { it.status == RecStatus.DONE || it.status == RecStatus.RECORDING || it.status == RecStatus.FAILED }
        .sortedByDescending { it.startMillis }
    val scheduled = recordings.filter { it.status == RecStatus.SCHEDULED }.sortedBy { it.startMillis }

    Row(Modifier.fillMaxSize().padding(top = 72.dp)) {
        Column(Modifier.width(250.dp).fillMaxHeight().padding(start = 40.dp, end = 12.dp)) {
            Text("Library", fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
            Column(Modifier.returnFocusTo(selectedRail)) {
                LibSection.entries.forEachIndexed { i, s ->
                    val count = when (s) {
                        LibSection.RECORDINGS -> done.size
                        LibSection.SCHEDULED -> scheduled.size
                        LibSection.CONTINUE -> continueWatching.size
                        LibSection.TEAMS -> vm.favorites.size
                        LibSection.MOVIES -> savedMovies.size
                        LibSection.SHOWS -> savedShows.size
                        LibSection.CHANNELS -> vm.favoriteChannelIds.size
                    }
                    RailItem(
                        label = s.label,
                        count = count,
                        selected = s == section,
                        onSelect = { section = s },
                        modifier = Modifier
                            .then(if (i == 0) Modifier.focusRequester(railFocus) else Modifier)
                            .then(if (s == section) Modifier.focusRequester(selectedRail) else Modifier),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (section) {
                LibSection.RECORDINGS -> Grid(done.isEmpty(), "No recordings yet",
                    "Record a game or show from its menu (long-press), or turn on \"Record all games\" for a team.",
                    footer = "${bytesText(vm.recordingsBytes())} used · ${bytesText(vm.recordingsFreeBytes())} free on this TV") {
                    items(done, key = { it.id }) { RecordingCard(vm, it, screenKey) }
                }
                LibSection.SCHEDULED -> Grid(scheduled.isEmpty(), "Nothing scheduled",
                    if (vm.canWakeExactly) null else "Tip: allow GameDay TV to set alarms (Settings › Recordings) so recordings start on time.") {
                    items(scheduled, key = { it.id }) { RecordingCard(vm, it, screenKey) }
                }
                LibSection.CONTINUE -> Grid(continueWatching.isEmpty(), "Nothing in progress", "Movies, episodes and recordings you start show up here.") {
                    items(continueWatching, key = { it.key }) { ResumeCard(vm, it, screenKey) }
                }
                LibSection.TEAMS -> Grid(vm.favorites.isEmpty(), "No teams yet", "Add teams from Settings › Sports, or from any game or team page.", minWidth = 150) {
                    items(vm.favorites.toList(), key = { it.key }) { TeamCard(vm, it, screenKey) }
                }
                LibSection.MOVIES -> Grid(savedMovies.isEmpty(), "No saved movies", "Choose \"Add to library\" on any movie.", minWidth = POSTER_WIDTH) {
                    items(savedMovies, key = { it.key }) { SavedCard(vm, it, screenKey) }
                }
                LibSection.SHOWS -> Grid(savedShows.isEmpty(), "No saved shows", "Choose \"Add to library\" on any show.", minWidth = POSTER_WIDTH) {
                    items(savedShows, key = { it.key }) { SavedCard(vm, it, screenKey) }
                }
                LibSection.CHANNELS -> {
                    val channels = vm.favoriteChannels
                    Grid(channels.isEmpty(), "No favorite channels", "Long-press a channel in the guide and choose \"Add to favorite channels\".") {
                        items(channels, key = { it.id }) { ChannelCard(vm, it, screenKey, channels) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RailItem(label: String, count: Int, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (focused && !selected) onSelect() }
    SettingRow(
        title = label,
        onClick = onSelect,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        value = if (count > 0) "$count" else null,
        subtitle = null,
        chevron = selected && !focused,
    )
}

@Composable
private fun Grid(
    empty: Boolean,
    emptyTitle: String,
    emptyText: String?,
    minWidth: Int = CARD_WIDTH,
    footer: String? = null,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (empty) {
            EmptyState(emptyTitle, emptyText, Modifier.padding(top = 60.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minWidth.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 40.dp, top = 8.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
        if (footer != null) {
            Spacer(Modifier.height(4.dp))
            Text(footer, fontSize = 12.sp, color = AppColors.TextFaint, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
        }
    }
}
