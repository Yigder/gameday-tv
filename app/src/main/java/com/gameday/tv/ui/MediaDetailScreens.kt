package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.gameday.tv.data.Episode
import com.gameday.tv.data.MovieInfo
import com.gameday.tv.data.SavedKind
import com.gameday.tv.data.SeriesInfo
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

// ---------------------------------------------------------------------------------------------
// Movie page
// ---------------------------------------------------------------------------------------------

@Composable
fun MovieScreen(vm: AppViewModel, movieId: String) {
    val screenKey = Screen.MovieDetail(movieId).key
    LaunchedEffect(Unit) { vm.ensureMovies() }
    val movie = vm.movieById(movieId)
    if (movie == null) {
        if (vm.movies is VodState.Loading) LoadingState("Loading…", Modifier.padding(top = 140.dp))
        else EmptyState("Movie not found", modifier = Modifier.padding(top = 120.dp)) { PillButton("Back", { vm.back() }) }
        return
    }
    var info by remember { mutableStateOf<MovieInfo?>(null) }
    LaunchedEffect(movieId) { info = vm.movieInfo(movie) }
    val resume = vm.resume.firstOrNull { it.key.startsWith("movie:${movie.id}:") }
    val saved = vm.isSaved(SavedKind.MOVIE, movie.id)
    val primary = remember { FocusRequester() }
    InitialFocus(vm, screenKey, primary)
    val i = info

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = movie.name,
            meta = listOfNotNull(
                i?.releaseDate?.take(4),
                i?.genre,
                i?.durationSecs?.let { durationText(it * 1000L) },
                (i?.rating ?: movie.rating?.let { "%.1f".format(it) })?.let { "★ $it" },
            ),
            description = i?.plot,
            art = {
                AsyncImage(i?.backdrop ?: movie.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            },
        ) {
            if (resume != null) {
                PillButton("Resume", { vm.playResume(resume) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
                PillButton("Start over", { vm.removeResume(resume.key); vm.playMovie(movie) }, icon = Icons.Restart)
            } else {
                PillButton("Play", { vm.playMovie(movie) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
            }
            PillButton(if (saved) "In library" else "Add to library", { vm.toggleSaved(SavedKind.MOVIE, movie.id, movie.name, movie.poster) },
                icon = if (saved) Icons.Check else Icons.Add)
        }
        Column(Modifier.padding(horizontal = 48.dp)) {
            i?.cast?.let { Text("Cast: $it", fontSize = 13.sp, color = AppColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            i?.director?.let { Text("Director: $it", fontSize = 13.sp, color = AppColors.TextDim, maxLines = 1) }
            if (resume != null) {
                Spacer(Modifier.height(10.dp))
                Text("${durationText(resume.durationMs - resume.positionMs)} left", fontSize = 13.sp, color = AppColors.TextDim)
                ProgressLine(resume.progress, Modifier.width(260.dp).padding(top = 4.dp))
            }
        }
        // More from the same category, like YouTube TV's "More like this".
        val similar = remember(movie.id, vm.movies) {
            (vm.movies as? VodState.Ready)?.library?.items.orEmpty()
                .filter { it.categoryId == movie.categoryId && it.id != movie.id }.take(20)
        }
        if (similar.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("More like this", Modifier.padding(start = 48.dp, bottom = 10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(similar, key = { it.id }) { MovieCard(vm, it, screenKey) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Show page: seasons and episodes
// ---------------------------------------------------------------------------------------------

@Composable
fun SeriesScreen(vm: AppViewModel, seriesId: String) {
    val screenKey = Screen.SeriesDetail(seriesId).key
    LaunchedEffect(Unit) { vm.ensureSeries() }
    val series = vm.seriesById(seriesId)
    if (series == null) {
        if (vm.series is VodState.Loading) LoadingState("Loading…", Modifier.padding(top = 140.dp))
        else EmptyState("Show not found", modifier = Modifier.padding(top = 120.dp)) { PillButton("Back", { vm.back() }) }
        return
    }
    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seriesId) {
        runCatching { vm.seriesInfo(series) }.onSuccess { info = it }.onFailure { error = vm.friendly(it) }
    }
    var season by rememberSaveable { mutableIntStateOf(-1) }
    val inf = info
    val seasons = inf?.seasons.orEmpty()
    val selected = if (season in seasons) season else seasons.firstOrNull() ?: 1
    val saved = vm.isSaved(SavedKind.SERIES, series.id)
    // Resume the most recent episode of this show, if any.
    val resume = vm.resume.firstOrNull { it.key.startsWith("ep:${series.id}:") }
    val primary = remember { FocusRequester() }
    InitialFocus(vm, screenKey, primary)
    val s = inf?.series ?: series

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
        item(key = "header") {
            DetailHeader(
                title = s.name,
                meta = listOfNotNull("Show", s.releaseDate?.take(4), s.genre, if (seasons.isNotEmpty()) "${seasons.size} season${if (seasons.size == 1) "" else "s"}" else null,
                    s.rating?.let { "★ %.1f".format(it) }),
                description = s.plot,
                art = { AsyncImage(s.backdrop ?: s.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) },
            ) {
                val first = inf?.allEpisodes?.firstOrNull()
                when {
                    resume != null -> PillButton("Resume ${resume.subtitle.substringBefore(" ·")}", { vm.playResume(resume) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
                    first != null -> PillButton("Play S${first.season} E${first.number}", { vm.playEpisodes(inf!!, first) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
                    else -> PillButton(if (error != null) "Couldn't load episodes" else "Loading episodes…", {}, Modifier.focusRequester(primary))
                }
                PillButton(if (saved) "In library" else "Add to library", { vm.toggleSaved(SavedKind.SERIES, series.id, series.name, series.poster) },
                    icon = if (saved) Icons.Check else Icons.Add)
            }
        }
        if (seasons.size > 1) {
            item(key = "seasons") {
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons, key = { it }) { n -> Chip("Season $n", n == selected, { season = n }) }
                }
            }
        }
        val episodes = inf?.episodes?.get(selected).orEmpty()
        items(episodes, key = { "e:" + it.id }) { e -> EpisodeRow(vm, inf!!, e, screenKey) }
        error?.let { item(key = "err") { Text(it, color = AppColors.Live, modifier = Modifier.padding(48.dp)) } }
    }
}

@Composable
private fun EpisodeRow(vm: AppViewModel, info: SeriesInfo, e: Episode, screenKey: String) {
    var focused by remember { mutableStateOf(false) }
    val resume = vm.resumeFor("ep:${info.series.id}:${e.id}:${e.ext}")
    FocusSurface(
        onClick = { vm.playEpisodes(info, e) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 4.dp).rememberFocus(vm, screenKey, e.id).onFocusChanged { focused = it.isFocused },
        containerColor = Color.Transparent,
        focusedContainerColor = Color(0x26FFFFFF),
        focusedScale = 1.01f,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(180.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                PosterThumb(e.image ?: info.series.backdrop ?: info.series.poster, e.title)
                resume?.let { ProgressLine(it.progress, Modifier.align(Alignment.BottomCenter)) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("${e.number}. ${e.title}", fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                e.durationSecs?.let { Text(durationText(it * 1000L), fontSize = 12.sp, color = AppColors.TextDim) }
                e.plot?.let { Text(it, fontSize = 13.sp, color = Color(0xFFBBBBBB), maxLines = if (focused) 3 else 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Browse all movies / shows by category
// ---------------------------------------------------------------------------------------------

@Composable
fun BrowseScreen(vm: AppViewModel, kind: BrowseKind, initialCategory: String?) {
    val screenKey = Screen.Browse(kind, initialCategory).key
    LaunchedEffect(kind) { if (kind == BrowseKind.MOVIES) vm.ensureMovies() else vm.ensureSeries() }
    var category by rememberSaveable { mutableStateOf(initialCategory ?: "recent") }
    val chips = remember { FocusRequester() }
    InitialFocus(vm, screenKey, chips)

    val state = if (kind == BrowseKind.MOVIES) vm.movies else vm.series
    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text(kind.label, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
        when (state) {
            is VodState.Failed -> EmptyState("Couldn't load ${kind.label.lowercase()}", state.message)
            is VodState.Ready<*> -> {
                val categories = state.library.categories
                LazyRow(
                    Modifier.focusRequester(chips),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "recent") { Chip("Recently added", category == "recent", { category = "recent" }) }
                    items(categories, key = { it.id }) { c -> Chip(c.name, category == c.id, { category = c.id }) }
                }
                Spacer(Modifier.height(8.dp))
                if (kind == BrowseKind.MOVIES) {
                    val all = (vm.movies as? VodState.Ready)?.library?.items.orEmpty()
                    val shown = remember(all, category) {
                        if (category == "recent") all.sortedByDescending { it.added }.take(200) else all.filter { it.categoryId == category }
                    }
                    PosterGrid(shown.size) { grid ->
                        grid.items(shown, key = { it.id }) { MovieCard(vm, it, screenKey) }
                    }
                } else {
                    val all = (vm.series as? VodState.Ready)?.library?.items.orEmpty()
                    val shown = remember(all, category) {
                        if (category == "recent") all.sortedByDescending { it.lastModified }.take(200) else all.filter { it.categoryId == category }
                    }
                    PosterGrid(shown.size) { grid ->
                        grid.items(shown, key = { it.id }) { SeriesCard(vm, it, screenKey) }
                    }
                }
            }
            else -> LoadingState("Loading ${kind.label.lowercase()}…", Modifier.padding(top = 80.dp))
        }
    }
}

@Composable
private fun PosterGrid(count: Int, content: (androidx.compose.foundation.lazy.grid.LazyGridScope) -> Unit) {
    if (count == 0) {
        EmptyState("Nothing here", modifier = Modifier.padding(top = 40.dp))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(POSTER_WIDTH.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) { content(this) }
}
