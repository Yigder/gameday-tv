package com.gameday.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.gameday.tv.data.AddonStream
import com.gameday.tv.data.MetaDetail
import com.gameday.tv.data.MetaPreview
import com.gameday.tv.data.MetaVideo
import com.gameday.tv.data.SavedKind
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// On Demand tab: movies and shows from streaming add-ons (Stremio add-on protocol, like Nuvio)
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OnDemandTab(vm: AppViewModel) {
    val screenKey = "main:${Tab.ON_DEMAND}"
    var kind by rememberSaveable { mutableStateOf("all") }
    val chips = remember { FocusRequester() }
    val selectedChip = remember { FocusRequester() }
    if (vm.tabWantsFocus) InitialFocus(vm, screenKey, chips)
    val onHero: (HeroInfo) -> Unit = { vm.heroFocus = it }
    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)

    val addons = vm.addons
    val rows = addons.rows.filter { kind == "all" || it.catalog.type == kind }
    val items = addons.rowItems
    val failed = addons.rowFailed
    LaunchedEffect(rows.map { it.key }) { rows.forEach { addons.ensureRow(it) } }
    val resume by remember { derivedStateOf { vm.resume.filter { it.key.startsWith("addon:") } } }
    val saved by remember { derivedStateOf { vm.saved.filter { it.kind == SavedKind.ADDON_MOVIE || it.kind == SavedKind.ADDON_SERIES } } }

    val defaultHero = HeroInfo("On Demand", listOf("Movies and shows from your add-ons"),
        if (addons.hasStreamAddons) null else "Add a streaming add-on in Settings › Add-ons to play titles.")

    Column(Modifier.fillMaxSize()) {
        TabHero(vm, defaultHero, Modifier.fillMaxWidth().height(270.dp).padding(top = 64.dp), compact = true)
        LazyRow(
            Modifier.focusRequester(chips).focusRestorer(selectedChip),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("all" to "All", "movie" to "Movies", "series" to "Shows").forEach { (k, label) ->
                item(key = k) {
                    Chip(label, kind == k, { kind = k; vm.heroFocus = null }, if (kind == k) Modifier.focusRequester(selectedChip) else Modifier)
                }
            }
            item(key = "search") { Chip("Search", false, { vm.navigate(Screen.OnDemandSearch) }, icon = Icons.Search) }
            item(key = "manage") { Chip("Add-ons", false, { vm.openSettings(SettingsSection.ADDONS) }, icon = Icons.Settings) }
        }
        Box(Modifier.weight(1f)) {
            PivotScroll(offset = ROW_TITLE) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 260.dp)) {
                    if (addons.installed.isEmpty()) {
                        item(key = "none") {
                            EmptyState("No add-ons yet", "Add Stremio-compatible add-ons (catalogs like Cinemeta, sources like Comet) to browse and stream movies and shows.") {
                                PillButton("Add an add-on", { vm.navigate(Screen.AddonInstall) }, primary = true)
                            }
                        }
                    } else if (!addons.hasStreamAddons) {
                        item(key = "nostreams") {
                            Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Add a source to start watching", fontSize = 17.sp)
                                    Text("Catalogs show what's available. A streaming add-on such as Comet (with TorBox) plays it.", fontSize = 13.sp, color = AppColors.TextDim)
                                }
                                PillButton("Add an add-on", { vm.navigate(Screen.AddonInstall) }, primary = true)
                            }
                        }
                    }
                    if (resume.isNotEmpty()) {
                        cardRow("resume", "Continue watching", nav) {
                            items(resume, key = { "r:" + it.key }) { ResumeCard(vm, it, screenKey, onHero) }
                        }
                    }
                    if (saved.isNotEmpty()) {
                        cardRow("saved", "Your list", nav) {
                            items(saved, key = { it.key }) { SavedCard(vm, it, screenKey) }
                        }
                    }
                    rows.forEach { row ->
                        val list = items[row.key]
                        if (!list.isNullOrEmpty()) {
                            cardRow(row.key, row.title, nav) {
                                items(list, key = { "m:" + it.type + it.id }) { AddonCard(vm, it, screenKey, onHero, row.key) }
                                item(key = "more") { MoreCard("See all", { vm.navigate(Screen.AddonBrowse(row.key)) }, POSTER_WIDTH, 2f / 3f) }
                            }
                        }
                    }
                    if (rows.isNotEmpty() && rows.none { items[it.key] != null || failed[it.key] == true }) {
                        item(key = "loading") { LoadingState("Loading catalogs…") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// "See all" for one catalog, with genres and more pages as you scroll
// ---------------------------------------------------------------------------------------------

@Composable
fun AddonBrowseScreen(vm: AppViewModel, rowKey: String) {
    val screenKey = Screen.AddonBrowse(rowKey).key
    val row = vm.addons.rows.firstOrNull { it.key == rowKey }
    if (row == null) {
        EmptyState("This catalog isn't available", "The add-on may have been removed.", Modifier.padding(top = 120.dp)) { PillButton("Back", { vm.back() }) }
        return
    }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    val list = remember(genre) { mutableStateListOf<MetaPreview>() }
    var loading by remember(genre) { mutableStateOf(false) }
    var done by remember(genre) { mutableStateOf(false) }
    var error by remember(genre) { mutableStateOf<String?>(null) }
    val canPage = row.catalog.extras.any { it.name == "skip" }
    val gridState = rememberLazyGridState()
    val chips = remember { FocusRequester() }
    InitialFocus(vm, screenKey, chips)

    suspend fun loadMore() {
        if (loading || done) return
        loading = true
        val extra = buildMap {
            genre?.let { put("genre", it) }
            if (list.isNotEmpty()) put("skip", list.size.toString())
        }
        runCatching { vm.addons.catalog(row, extra) }
            .onSuccess { page ->
                val fresh = page.filter { p -> list.none { it.id == p.id } }
                list.addAll(fresh)
                if (!canPage || fresh.isEmpty()) done = true
            }
            .onFailure { error = vm.friendly(it); done = true }
        loading = false
    }
    LaunchedEffect(genre) { loadMore() }
    // Load the next page when the viewer nears the end.
    LaunchedEffect(gridState, genre) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= list.size - 12) loadMore()
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text(row.title, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 48.dp))
        Text(row.addon.name, fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
        val genres = row.catalog.genres
        LazyRow(
            Modifier.focusRequester(chips),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "all") { Chip("All", genre == null, { genre = null }) }
            items(genres, key = { it }) { g -> Chip(g, genre == g, { genre = g }) }
        }
        Spacer(Modifier.height(8.dp))
        when {
            list.isEmpty() && loading -> LoadingState("Loading…", Modifier.padding(top = 60.dp))
            list.isEmpty() && error != null -> EmptyState("Couldn't load this catalog", error)
            list.isEmpty() && done -> EmptyState("Nothing here")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(POSTER_WIDTH.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(list, key = { it.type + it.id }) { AddonCard(vm, it, screenKey) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Title page: details, seasons and episodes, and the source picker
// ---------------------------------------------------------------------------------------------

@Composable
fun AddonDetailScreen(vm: AppViewModel, type: String, id: String) {
    val screenKey = Screen.AddonDetail(type, id).key
    var meta by remember { mutableStateOf<MetaDetail?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(type, id) {
        meta = runCatching { vm.addons.meta(type, id) }.getOrNull()
        loaded = true
    }
    var picking by remember { mutableStateOf(false) }
    var pickVideo by remember { mutableStateOf<MetaVideo?>(null) }
    fun pick(v: MetaVideo?) { pickVideo = v; picking = true }
    val primary = remember { FocusRequester() }
    InitialFocus(vm, screenKey, primary, key = loaded)

    val m = meta
    if (m == null) {
        if (!loaded) LoadingState("Loading…", Modifier.padding(top = 140.dp))
        else EmptyState("Couldn't load this title", "None of your add-ons have details for it.", Modifier.padding(top = 120.dp)) { PillButton("Back", { vm.back() }, Modifier.focusRequester(primary)) }
        return
    }
    val p = m.preview
    val isSeries = m.videos.isNotEmpty() && type != "movie"
    val savedKind = if (isSeries) SavedKind.ADDON_SERIES else SavedKind.ADDON_MOVIE
    val saved = vm.isSaved(savedKind, p.id)
    val prefix = "addon:$type:${p.id}|"
    val resume = vm.resume.firstOrNull { it.key.startsWith(prefix) }
    val seasons = m.seasons
    var season by rememberSaveable { mutableIntStateOf(-1) }
    val resumeVideo = resume?.let { r -> m.videos.firstOrNull { it.id == r.key.substringAfter('|') } }
    val selected = when {
        season in seasons -> season
        resumeVideo != null -> resumeVideo.season
        else -> seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            item(key = "header") {
                DetailHeader(
                    title = p.name,
                    meta = listOfNotNull(
                        if (isSeries) "Show" else "Movie", p.releaseInfo, m.runtime, p.genres.take(3).joinToString(", ").ifBlank { null },
                        p.imdbRating?.let { "★ $it" },
                        if (isSeries && seasons.isNotEmpty()) "${seasons.count { it > 0 }} season${if (seasons.count { it > 0 } == 1) "" else "s"}" else null,
                    ),
                    description = p.description,
                    art = { AsyncImage(p.background ?: p.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) },
                ) {
                    when {
                        resume != null && (!isSeries || resumeVideo != null) -> PillButton(
                            if (resumeVideo != null) "Resume S${resumeVideo.season} E${resumeVideo.episode}" else "Resume",
                            { pick(resumeVideo) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true,
                        )
                        isSeries -> {
                            val first = m.videos.firstOrNull { it.season == selected } ?: m.videos.first()
                            PillButton("Play S${first.season} E${first.episode}", { pick(first) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
                        }
                        else -> PillButton("Play", { pick(null) }, Modifier.focusRequester(primary), icon = Icons.Play, primary = true)
                    }
                    if (resume != null && !isSeries) PillButton("Start over", { vm.removeResume(resume.key); pick(null) }, icon = Icons.Restart)
                    PillButton(if (saved) "In your list" else "Add to list", { vm.toggleSaved(savedKind, p.id, p.name, p.poster) },
                        icon = if (saved) Icons.Check else Icons.Add)
                }
            }
            if (!isSeries) {
                item(key = "credits") {
                    Column(Modifier.padding(horizontal = 48.dp)) {
                        if (m.cast.isNotEmpty()) Text("Cast: " + m.cast.take(6).joinToString(", "), fontSize = 13.sp, color = AppColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (m.director.isNotEmpty()) Text("Director: " + m.director.joinToString(", "), fontSize = 13.sp, color = AppColors.TextDim, maxLines = 1)
                        resume?.let {
                            Spacer(Modifier.height(10.dp))
                            Text("${durationText(it.durationMs - it.positionMs)} left", fontSize = 13.sp, color = AppColors.TextDim)
                            ProgressLine(it.progress, Modifier.width(260.dp).padding(top = 4.dp))
                        }
                    }
                }
            }
            if (isSeries) {
                if (seasons.size > 1) {
                    item(key = "seasons") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons, key = { it }) { n -> Chip(if (n <= 0) "Specials" else "Season $n", n == selected, { season = n }) }
                        }
                    }
                }
                val episodes = m.videos.filter { it.season == selected }
                items(episodes, key = { "e:" + it.id }) { v ->
                    AddonEpisodeRow(vm, type, p.id, v, screenKey) { pick(v) }
                }
            }
        }
        if (picking) StreamSheet(vm, m, pickVideo, onClose = { picking = false })
    }
}

@Composable
private fun AddonEpisodeRow(vm: AppViewModel, type: String, metaId: String, v: MetaVideo, screenKey: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val resume = vm.resumeFor(AddonsModel.resumeKey(type, metaId, v.id))
    val upcoming = v.released != null && v.released > System.currentTimeMillis()
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 4.dp).rememberFocus(vm, screenKey, v.id).onFocusChanged { focused = it.isFocused },
        containerColor = Color.Transparent,
        focusedContainerColor = Color(0x26FFFFFF),
        focusedScale = 1.01f,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(180.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                PosterThumb(v.thumbnail, v.title)
                resume?.let { ProgressLine(it.progress, Modifier.align(Alignment.BottomCenter)) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("${v.episode}. ${v.title}", fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(v.released?.let { (if (upcoming) "Airs " else "") + formatDate(it) }, resume?.let { "${durationText(it.durationMs - it.positionMs)} left" })
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), fontSize = 12.sp, color = AppColors.TextDim)
                v.overview?.let { Text(it, fontSize = 13.sp, color = Color(0xFFBBBBBB), maxLines = if (focused) 3 else 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Sources for a title or episode from every stream add-on: best first, TorBox-ready ones marked ⚡. */
@Composable
private fun StreamSheet(vm: AppViewModel, meta: MetaDetail, video: MetaVideo?, onClose: () -> Unit) {
    val type = meta.preview.type
    val streamId = video?.id ?: meta.preview.id
    var result by remember(streamId) { mutableStateOf<StreamList?>(null) }
    LaunchedEffect(streamId) { result = runCatching { vm.addons.streams(type, streamId) }.getOrDefault(StreamList(emptyList(), emptySet(), 0)) }
    val first = remember { FocusRequester() }
    val r = result
    LaunchedEffect(r != null) { first.requestFocusSafely(120) }
    BackHandler(onBack = onClose)
    val torbox = vm.addons.torboxConnected

    Box(Modifier.fillMaxSize().background(Color(0xB3000000))) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(520.dp).fillMaxHeight().background(Color(0xFF1B1B1B)).trapFocus()
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
        ) {
            Text(meta.preview.name, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(video?.let { "S${it.season} E${it.episode} · ${it.title}" } ?: "Choose a source", fontSize = 13.sp, color = AppColors.TextDim, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            vm.addons.resolving?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Spinner(20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Getting the stream from TorBox…", fontSize = 13.sp)
                }
            }
            when {
                r == null -> {
                    LoadingState("Asking your add-ons for sources…")
                    // Something focusable so Back and the D-pad stay in the sheet.
                    PillButton("Cancel", onClose, Modifier.focusRequester(first))
                }
                r.streams.isEmpty() -> {
                    Text(
                        if (r.addonsAsked == 0) "None of your add-ons provide sources for this. Add a streaming add-on (like Comet) in Settings › Add-ons."
                        else "No sources found for this right now.",
                        fontSize = 14.sp, color = AppColors.TextDim,
                    )
                    Spacer(Modifier.height(12.dp))
                    PillButton("Back", onClose, Modifier.focusRequester(first))
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(r.streams, key = { i, s -> "$i:${s.url ?: s.infoHash}" }) { i, s ->
                        StreamRow(s, cached = s.infoHash != null && s.infoHash in r.cached, torbox = torbox,
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            if (vm.addons.resolving == null) vm.addons.play(s, meta, video)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRow(s: AddonStream, cached: Boolean, torbox: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        containerColor = Color(0x10FFFFFF),
        focusedContainerColor = Color(0xFFF1F1F1),
        focusedScale = 1.01f,
        shape = RoundedCornerShape(8.dp),
    ) {
        val fg = if (focused) Color.Black else AppColors.Text
        val dim = if (focused) Color(0xFF444444) else AppColors.TextDim
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(54.dp), contentAlignment = Alignment.CenterStart) {
                Text(s.quality ?: "—", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg)
            }
            Column(Modifier.weight(1f)) {
                Text(s.name.replace('\n', ' '), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.description.isNotBlank()) Text(s.description.replace('\n', ' '), fontSize = 12.sp, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(s.addon, fontSize = 11.sp, color = dim, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            val tag = when {
                s.url != null -> null
                cached -> "⚡ TorBox"
                s.isTorrent && torbox -> "TorBox · not cached"
                s.isTorrent -> "Needs TorBox"
                else -> "External"
            }
            if (tag != null) Tag(tag, color = if (cached) Color(0xFF1B5E20) else Color(0x66000000))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Adding an add-on, and TorBox
// ---------------------------------------------------------------------------------------------

@Composable
fun AddonInstallScreen(vm: AppViewModel) {
    var url by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(200) }

    fun install(value: String = url) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = vm.addons.install(value)
            busy = false
            if (error == null) vm.back()
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("Add an add-on", fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text(
                "Paste the manifest link from the add-on's website — for example, the link Comet gives you after you choose TorBox and your " +
                    "options. Any Stremio-compatible add-on works: catalogs add rows to On Demand, and streaming add-ons add sources.\n\n" +
                    "Links can contain your settings, so they're encrypted on this TV.",
                fontSize = 14.sp, color = AppColors.TextDim, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(18.dp))
            FormError(error)
            TvTextField(url, { url = it }, Modifier.focusRequester(first), label = "Add-on link", placeholder = "https://…/manifest.json",
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, onSubmit = { install() })
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (busy) "Adding…" else "Add", { install() }, primary = true)
                PillButton("Cancel", { vm.back() })
                if (busy) Spinner(26.dp)
            }
            Spacer(Modifier.height(18.dp))
            Text("GameDay TV doesn't host or provide content. Add-ons are made by others; only stream what you have the rights to watch.",
                fontSize = 12.sp, color = AppColors.TextFaint)
        }
        Spacer(Modifier.width(48.dp))
        PhoneEntryPanel("Add-on link", secret = false, onValue = { url = it; install(it) }, modifier = Modifier.width(300.dp).align(Alignment.CenterVertically))
    }
}

@Composable
fun TorBoxSetupScreen(vm: AppViewModel) {
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(200) }

    fun save(value: String = key) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = vm.addons.setTorBoxKey(value)
            busy = false
            if (error == null) vm.back()
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("Connect TorBox", fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text(
                "With TorBox, torrent sources from your add-ons play straight from TorBox's servers — cached ones (⚡) start right away.\n\n" +
                    "Find your API key at torbox.app › Settings. It's encrypted on this TV and only sent to TorBox.",
                fontSize = 14.sp, color = AppColors.TextDim, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(18.dp))
            FormError(error)
            TvTextField(key, { key = it }, Modifier.focusRequester(first), label = "TorBox API key", password = true,
                imeAction = ImeAction.Done, onSubmit = { save() })
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (busy) "Checking…" else "Connect", { save() }, primary = true)
                PillButton("Cancel", { vm.back() })
                if (busy) Spinner(26.dp)
            }
        }
        Spacer(Modifier.width(48.dp))
        PhoneEntryPanel("TorBox API key", secret = true, onValue = { key = it; save(it) }, modifier = Modifier.width(300.dp).align(Alignment.CenterVertically))
    }
}
