package com.gameday.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.gameday.tv.data.MetaPreview
import com.gameday.tv.data.Movie
import com.gameday.tv.data.ResumePoint
import com.gameday.tv.data.SavedItem
import com.gameday.tv.data.SavedKind
import com.gameday.tv.data.Series
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// On Demand tab, laid out like Nuvio's home: the focused title fills the top of the screen (its
// backdrop, logo, details and description), with rows of posters underneath. Resting on a poster
// widens it into the title's backdrop and plays its trailer there.
// ---------------------------------------------------------------------------------------------

private const val OD_POSTER_WIDTH = 118
private val OD_POSTER_HEIGHT: Dp = (OD_POSTER_WIDTH * 1.5f).dp
/** How long focus rests on a poster before it widens (and its trailer starts loading). */
private const val EXPAND_AFTER_MS = 900L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OnDemandTab(vm: AppViewModel) {
    val screenKey = "main:${Tab.ON_DEMAND}"
    var kind by rememberSaveable { mutableStateOf("all") }
    val chips = remember { FocusRequester() }
    val selectedChip = remember { FocusRequester() }
    if (vm.tabWantsFocus) InitialFocus(vm, screenKey, chips)
    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)

    val addons = vm.addons
    val rows = addons.rows.filter { kind == "all" || it.catalog.type == kind }
    val items = addons.rowItems
    val failed = addons.rowFailed
    LaunchedEffect(rows.map { it.key }) { rows.forEach { addons.ensureRow(it) } }
    val resume by remember { derivedStateOf { vm.resume.filter { it.key.startsWith("addon:") || it.key.startsWith("movie:") || it.key.startsWith("ep:") } } }
    val saved by remember { derivedStateOf { vm.saved.toList() } }

    // Movies and shows from the TV provider sit after the add-on rows.
    val hasProvider = vm.catalog != null
    LaunchedEffect(vm.catalog) {
        if (hasProvider) {
            vm.ensureMovies()
            vm.ensureSeries()
        }
    }
    val movies = (vm.movies as? VodState.Ready)?.library?.items?.let { list -> remember(list) { list.sortedByDescending { it.added }.take(24) } }.orEmpty()
    val shows = (vm.series as? VodState.Ready)?.library?.items?.let { list -> remember(list) { list.sortedByDescending { it.lastModified }.take(24) } }.orEmpty()

    // Until something is focused, the header shows the first title of the first row.
    val firstTitle = rows.firstNotNullOfOrNull { items[it.key]?.firstOrNull() }
    val defaultHero = firstTitle?.let { odHero(it) } ?: HeroInfo(
        "On Demand", listOf("Movies and shows from your add-ons"),
        if (addons.hasStreamAddons) null else "Add a streaming add-on in Settings › Add-ons to play titles.",
    )

    fun choose(k: String) {
        kind = k
        vm.heroFocus = null
    }

    Box(Modifier.fillMaxSize()) {
        OnDemandBackdrop(vm, defaultHero)
        Column(Modifier.fillMaxSize()) {
            OnDemandHeroText(vm, defaultHero, Modifier.fillMaxWidth().height(262.dp).padding(top = 64.dp))
            Box(Modifier.weight(1f)) {
                PivotScroll(offset = ROW_TITLE) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 260.dp)) {
                        item(key = "chips") {
                            DefaultScroll {
                                LazyRow(
                                    // Up from the rows comes back to the chosen filter, not the chip that lines up.
                                    Modifier.focusRequester(chips).focusRestorer(selectedChip),
                                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf("all" to "All", "movie" to "Movies", "series" to "Shows").forEach { (k, label) ->
                                        item(key = k) {
                                            Chip(label, kind == k, { choose(k) }, if (kind == k) Modifier.focusRequester(selectedChip) else Modifier)
                                        }
                                    }
                                    item(key = "search") { Chip("Search", false, { vm.navigate(Screen.OnDemandSearch) }, icon = Icons.Search) }
                                    item(key = "manage") { Chip("Add-ons", false, { vm.openSettings(SettingsSection.ADDONS) }, icon = Icons.Settings) }
                                }
                            }
                        }
                        if (addons.installed.isEmpty() && !hasProvider) {
                            item(key = "none") {
                                EmptyState("No add-ons yet", "Add Stremio-compatible add-ons (catalogs like Cinemeta, sources like Comet) to browse and stream movies and shows.") {
                                    PillButton("Add an add-on", { vm.navigate(Screen.AddonInstall) }, primary = true)
                                }
                            }
                        } else if (addons.installed.isNotEmpty() && !addons.hasStreamAddons) {
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
                        val resumeShown = resume.filter { kindMatches(kind, it) }
                        if (resumeShown.isNotEmpty()) {
                            cardRow("resume", "Continue watching", nav) {
                                items(resumeShown, key = { "r:" + it.key }) { ContinueCard(vm, it, screenKey) }
                            }
                        }
                        val savedShown = saved.filter { kind == "all" || (kind == "movie") == it.isMovie }
                        if (savedShown.isNotEmpty()) {
                            cardRow("saved", "Your list", nav) {
                                items(savedShown, key = { it.key }) { SavedPosterCard(vm, it, screenKey) }
                            }
                        }
                        rows.forEach { row ->
                            val list = items[row.key]
                            if (!list.isNullOrEmpty()) {
                                cardRow(row.key, row.title, nav) {
                                    items(list, key = { "m:" + it.type + it.id }) { AddonPosterCard(vm, it, screenKey, row.key) }
                                    item(key = "more") { MoreCard("See all", { vm.navigate(Screen.AddonBrowse(row.key)) }, OD_POSTER_WIDTH, 2f / 3f) }
                                }
                            }
                        }
                        if (movies.isNotEmpty() && kind != "series") {
                            cardRow("movies", "Movies from your provider", nav) {
                                items(movies, key = { it.id }) { ProviderMovieCard(vm, it, screenKey) }
                                item(key = "more") { MoreCard("All movies", { vm.navigate(Screen.Browse(BrowseKind.MOVIES)) }, OD_POSTER_WIDTH, 2f / 3f) }
                            }
                        }
                        if (shows.isNotEmpty() && kind != "movie") {
                            cardRow("shows", "Shows from your provider", nav) {
                                items(shows, key = { it.id }) { ProviderSeriesCard(vm, it, screenKey) }
                                item(key = "more") { MoreCard("All shows", { vm.navigate(Screen.Browse(BrowseKind.SHOWS)) }, OD_POSTER_WIDTH, 2f / 3f) }
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
}

private fun kindMatches(kind: String, p: ResumePoint): Boolean = when (kind) {
    "movie" -> p.key.startsWith("movie:") || p.key.startsWith("addon:movie:")
    "series" -> p.key.startsWith("ep:") || (p.key.startsWith("addon:") && !p.key.startsWith("addon:movie:"))
    else -> true
}

/** The header for an add-on title. */
fun odHero(meta: MetaPreview): HeroInfo {
    val kind = when (meta.type) {
        "series" -> "Show"
        "movie" -> "Movie"
        else -> meta.type.replaceFirstChar { it.uppercase() }
    }
    return HeroInfo(
        title = meta.name,
        meta = listOfNotNull(kind, meta.releaseInfo, meta.genres.take(3).joinToString(", ").ifBlank { null }),
        description = meta.description,
        image = meta.background ?: meta.poster,
        logo = meta.logo,
        rating = meta.imdbRating,
    )
}

// ---------------------------------------------------------------------------------------------
// Header: backdrop across the top, title (or its logo), details and description on the left
// ---------------------------------------------------------------------------------------------

/** The focused title, after a short settle so scrolling past cards doesn't flicker the header. */
@Composable
private fun settledHero(vm: AppViewModel, default: HeroInfo): HeroInfo {
    val target = vm.heroFocus ?: default
    var shown by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (shown != target) {
            delay(140)
            shown = target
        }
    }
    return shown
}

@Composable
private fun OnDemandBackdrop(vm: AppViewModel, default: HeroInfo) {
    val h = settledHero(vm, default)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        Box(Modifier.fillMaxWidth(0.78f).height(430.dp)) {
            Crossfade(targetState = h.image, animationSpec = tween(450), label = "odBackdrop") { image ->
                if (image != null) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to AppColors.Background, 0.3f to Color(0xD90F0F0F), 0.62f to Color(0x330F0F0F), 1f to Color.Transparent),
                ),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x800F0F0F), 0.2f to Color.Transparent, 0.55f to Color(0x660F0F0F), 1f to AppColors.Background)))
        }
    }
}

@Composable
private fun OnDemandHeroText(vm: AppViewModel, default: HeroInfo, modifier: Modifier) {
    val h = settledHero(vm, default)
    AnimatedContent(
        targetState = h,
        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
        contentKey = { it.title + it.meta.joinToString() },
        modifier = modifier,
        label = "odHero",
    ) { info ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.48f).padding(start = 48.dp, bottom = 12.dp)) {
                var logoFailed by remember(info.logo) { mutableStateOf(false) }
                if (info.logo != null && !logoFailed) {
                    AsyncImage(
                        info.logo, info.title,
                        Modifier.height(76.dp).widthIn(max = 300.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        onError = { logoFailed = true },
                    )
                } else {
                    Text(info.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    info.rating?.let {
                        ImdbBadge(it)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(info.meta.joinToString("  •  "), fontSize = 14.sp, color = Color(0xFFDDDDDD), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (info.progress != null) {
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(info.progress, Modifier.width(220.dp))
                }
                if (!info.description.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(info.description, fontSize = 14.sp, color = Color(0xFFC8C8C8), maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                }
            }
        }
    }
}

@Composable
private fun ImdbBadge(rating: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "IMDb", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.Black,
            modifier = Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(rating, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

@Composable
private fun AddonPosterCard(vm: AppViewModel, meta: MetaPreview, screenKey: String, keyPrefix: String) {
    // Catalogs don't always carry trailers or logos; the title's details do.
    val load: (suspend () -> MetaPreview?)? =
        if (meta.trailers.isEmpty() || meta.logo == null) suspend { vm.addons.meta(meta.type, meta.id)?.preview } else null
    PosterCard(
        vm, screenKey,
        key = keyPrefix + meta.type + ":" + meta.id,
        title = meta.name,
        poster = meta.poster,
        preview = meta,
        load = load,
        onClick = { vm.navigate(Screen.AddonDetail(meta.type, meta.id)) },
        onLongClick = { addonTitleMenu(vm, meta.type, meta.id, meta.name, meta.poster) },
    )
}

@Composable
private fun SavedPosterCard(vm: AppViewModel, item: SavedItem, screenKey: String) {
    val addonType = when (item.kind) {
        SavedKind.ADDON_MOVIE -> "movie"
        SavedKind.ADDON_SERIES -> "series"
        else -> null
    }
    val load: (suspend () -> MetaPreview?)? = addonType?.let { t -> suspend { vm.addons.meta(t, item.id)?.preview } }
    PosterCard(
        vm, screenKey,
        key = item.key,
        title = item.title,
        poster = item.image,
        preview = null,
        load = load,
        fallbackHero = HeroInfo(item.title, listOf(if (item.isMovie) "Movie" else "Show", "In your list"), image = item.image),
        onClick = {
            when (item.kind) {
                SavedKind.MOVIE -> vm.navigate(Screen.MovieDetail(item.id))
                SavedKind.SERIES -> vm.navigate(Screen.SeriesDetail(item.id))
                SavedKind.ADDON_MOVIE -> vm.navigate(Screen.AddonDetail("movie", item.id))
                SavedKind.ADDON_SERIES -> vm.navigate(Screen.AddonDetail("series", item.id))
            }
        },
        onLongClick = {
            vm.showDialog(AppDialog(item.title, actions = listOf(DialogAction("Remove from your list", Icons.Delete) {
                vm.dismissDialog(); vm.toggleSaved(item.kind, item.id, item.title, item.image)
            })))
        },
    )
}

@Composable
private fun ProviderMovieCard(vm: AppViewModel, movie: Movie, screenKey: String) {
    PosterCard(
        vm, screenKey,
        key = "pm:" + movie.id,
        title = movie.name,
        poster = movie.poster,
        preview = null,
        fallbackHero = HeroInfo(movie.name, listOf("Movie", "From your provider"), image = movie.poster, rating = movie.rating?.let { "%.1f".format(it) }),
        onClick = { vm.openMovie(movie) },
    )
}

@Composable
private fun ProviderSeriesCard(vm: AppViewModel, s: Series, screenKey: String) {
    val preview = remember(s) {
        MetaPreview(
            id = s.id, type = "series", name = s.name, poster = s.poster, background = s.backdrop, logo = null,
            description = s.plot, releaseInfo = s.releaseDate?.take(4), imdbRating = s.rating?.let { "%.1f".format(it) },
            genres = s.genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        )
    }
    PosterCard(
        vm, screenKey,
        key = "ps:" + s.id,
        title = s.name,
        poster = s.poster,
        preview = preview,
        onClick = { vm.openSeries(s) },
    )
}

/** Continue watching: the frame where it was left, with progress, like Nuvio. */
@Composable
private fun ContinueCard(vm: AppViewModel, point: ResumePoint, screenKey: String) {
    val left = "${durationText(point.durationMs - point.positionMs)} left"
    val basic = HeroInfo(point.title, listOf(point.subtitle, left), image = point.image, progress = point.progress)
    var focused by remember { mutableStateOf(false) }
    // Add-on titles: fill the header with the title's details (logo, description) while focused.
    val ids = remember(point.key) { AddonsModel.parseResumeKey(point.key) }
    LaunchedEffect(focused) {
        if (!focused || ids == null) return@LaunchedEffect
        delay(250)
        val meta = loadOrNull { vm.addons.meta(ids.first, ids.second)?.preview } ?: return@LaunchedEffect
        vm.heroFocus = odHero(meta).copy(meta = listOf(point.subtitle, left), image = meta.background ?: point.image, progress = point.progress)
    }
    MediaCard(
        title = point.title,
        subtitle = "${point.subtitle} · $left",
        onClick = { vm.playResume(point) },
        onLongClick = {
            vm.showDialog(AppDialog(point.title, point.subtitle, actions = listOf(
                DialogAction("Resume", Icons.Play) { vm.dismissDialog(); vm.playResume(point) },
                DialogAction("Remove from Continue watching", Icons.Close) { vm.dismissDialog(); vm.removeResume(point.key) },
            )))
        },
        onFocus = { vm.heroFocus = basic },
        modifier = Modifier.rememberFocus(vm, screenKey, "r:" + point.key).onFocusChanged { focused = it.isFocused },
    ) {
        PosterThumb(point.image, point.title)
        ProgressLine(point.progress, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * A poster that, after focus rests on it, widens into the title's backdrop (its logo on top) and
 * plays the trailer there — Nuvio's "expanded" card. [preview] is what's known up front; [load]
 * fetches fuller details (backdrop, logo, trailers) while the card is focused.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    vm: AppViewModel,
    screenKey: String,
    key: String,
    title: String,
    poster: String?,
    preview: MetaPreview?,
    onClick: () -> Unit,
    load: (suspend () -> MetaPreview?)? = null,
    fallbackHero: HeroInfo = HeroInfo(title, image = poster),
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var rested by remember { mutableStateOf(false) }
    var details by remember(key) { mutableStateOf(preview) }
    LaunchedEffect(focused) {
        rested = false
        if (!focused) return@LaunchedEffect
        vm.heroFocus = details?.let(::odHero) ?: fallbackHero
        if (load != null) {
            delay(250)
            loadOrNull { load() }?.let { more ->
                val known = details
                val merged = more.copy(
                    background = more.background ?: known?.background,
                    logo = more.logo ?: known?.logo,
                    trailers = more.trailers.ifEmpty { known?.trailers.orEmpty() },
                )
                details = merged
                vm.heroFocus = odHero(merged)
            }
        }
        delay(EXPAND_AFTER_MS)
        rested = true
    }
    val backdrop = details?.background
    val expanded = focused && rested && backdrop != null
    val width by animateDpAsState(if (expanded) OD_POSTER_HEIGHT * 16f / 9f else OD_POSTER_WIDTH.dp, tween(280), label = "posterWidth")
    // Keep the whole widened card on screen.
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(300)
            bring.bringIntoView()
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick.swallowingRelease(),
        modifier = Modifier
            .width(width)
            .height(OD_POSTER_HEIGHT)
            .bringIntoViewRequester(bring)
            .rememberFocus(vm, screenKey, key)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Card,
            contentColor = AppColors.Text,
            focusedContainerColor = AppColors.Card,
            focusedContentColor = AppColors.Text,
            pressedContainerColor = AppColors.Card,
            pressedContentColor = AppColors.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Focus), shape = shape)),
    ) {
        Box(Modifier.fillMaxSize().clip(shape)) {
            if (expanded) {
                AsyncImage(backdrop, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                TrailerPreview(vm, "card:$key", details?.trailers.orEmpty(), active = true, modifier = Modifier.fillMaxSize(), delayMs = 300)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))))
                CardTitle(details?.logo, title, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp, end = 12.dp))
            } else {
                PosterThumb(poster, title)
            }
        }
    }
}

@Composable
private fun CardTitle(logo: String?, title: String, modifier: Modifier) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !failed) {
        AsyncImage(logo, title, modifier.height(40.dp).widthIn(max = 170.dp),
            contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { failed = true })
    } else {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

/** Long press on an add-on title: open it, or add it to / remove it from the list. */
private fun addonTitleMenu(vm: AppViewModel, type: String, id: String, name: String, poster: String?) {
    val kind = if (type == "series") SavedKind.ADDON_SERIES else SavedKind.ADDON_MOVIE
    val saved = vm.isSaved(kind, id)
    vm.showDialog(
        AppDialog(
            name,
            actions = listOf(
                DialogAction("Details", Icons.Info) { vm.dismissDialog(); vm.navigate(Screen.AddonDetail(type, id)) },
                DialogAction(if (saved) "Remove from your list" else "Add to your list", if (saved) Icons.Check else Icons.Add) {
                    vm.dismissDialog(); vm.toggleSaved(kind, id, name, poster)
                },
            ),
        ),
    )
}

/** [block]'s result, or null if it fails (cancellation still cancels). */
private suspend fun <T> loadOrNull(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
