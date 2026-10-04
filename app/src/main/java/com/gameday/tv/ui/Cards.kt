package com.gameday.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.gameday.tv.data.Channel
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.Program
import com.gameday.tv.data.TeamScore
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.theme.AppColors

const val CARD_WIDTH = 216
const val POSTER_WIDTH = 124

/** Height of a row title, so pivot scrolling keeps the title of the focused row visible. */
val ROW_TITLE = 38.dp

/** A YouTube TV card: thumbnail with focus outline, title and subtitle underneath. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CARD_WIDTH.dp,
    aspect: Float = 16f / 9f,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    thumb: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.width(width)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick.swallowingRelease(),
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus?.invoke()
                },
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = AppColors.Card,
                contentColor = AppColors.Text,
                focusedContainerColor = AppColors.Card,
                focusedContentColor = AppColors.Text,
                pressedContainerColor = AppColors.Card,
                pressedContentColor = AppColors.Text,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, AppColors.Focus), shape = shape),
            ),
        ) {
            Box(Modifier.fillMaxSize().clip(shape)) { thumb() }
        }
        // Fixed spacing: changing it on focus would resize the row on every move (a visible jump).
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (focused) AppColors.Text else Color(0xFFDDDDDD),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(subtitle, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Lets rows of a vertical list move Up to the previous row even when it has scrolled out of view. */
class RowNav(val state: LazyListState, val scope: CoroutineScope)

@Composable
fun rememberRowNav(state: LazyListState): RowNav {
    val scope = rememberCoroutineScope()
    return remember(state) { RowNav(state, scope) }
}

/**
 * Without this, Up from a row whose previous row is off screen would jump to whatever is visible
 * above the list (the top bar, filter chips) instead of the previous row.
 */
@Composable
private fun Modifier.upToPreviousRow(nav: RowNav?, key: String): Modifier {
    if (nav == null) return this
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown || ev.key != Key.DirectionUp) return@onPreviewKeyEvent false
        val visible = nav.state.layoutInfo.visibleItemsInfo
        val idx = visible.firstOrNull { it.key == key }?.index ?: return@onPreviewKeyEvent false
        if (idx == 0) return@onPreviewKeyEvent false
        val prev = visible.firstOrNull { it.index == idx - 1 }
        if (prev != null && prev.offset >= 0) return@onPreviewKeyEvent false
        nav.scope.launch {
            nav.state.scrollToItem(idx - 1)
            focusManager.moveFocus(FocusDirection.Up)
        }
        true
    }
}

/**
 * A titled horizontal row of cards. Coming back to the row (Up/Down) lands on the card last
 * focused in it, not whichever card happens to line up.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun LazyListScope.cardRow(key: String, title: String, nav: RowNav? = null, content: LazyListScope.() -> Unit) {
    item(key = key) {
        Column(Modifier.upToPreviousRow(nav, key).padding(bottom = 18.dp)) {
            SectionTitle(title, Modifier.padding(start = 48.dp, bottom = 10.dp))
            DefaultScroll {
                LazyRow(
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Thumbnails
// ---------------------------------------------------------------------------------------------

fun teamColor(hex: String?, fallback: Color = Color(0xFF3A3A3A)): Color =
    hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: fallback

private fun Color.darken(f: Float) = Color(red * f, green * f, blue * f, alpha)

/** Team-colored split background with both logos, like YouTube TV's sports cards. */
@Composable
fun GameArt(game: Game, modifier: Modifier = Modifier, logoFraction: Float = 0.42f, showScore: Boolean = true, big: Boolean = false) {
    val awayColor = teamColor(game.away.color).darken(0.75f)
    val homeColor = teamColor(game.home.color, Color(0xFF555555)).darken(0.75f)
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(homeColor)
            val left = Path().apply {
                moveTo(0f, 0f); lineTo(w * 0.56f, 0f); lineTo(w * 0.44f, h); lineTo(0f, h); close()
            }
            drawPath(left, awayColor)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)), startY = h * 0.45f, endY = h))
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            TeamSide(game.away, game, Modifier.weight(1f), logoFraction, showScore, big)
            TeamSide(game.home, game, Modifier.weight(1f), logoFraction, showScore, big)
        }
    }
}

@Composable
private fun TeamSide(team: TeamScore, game: Game, modifier: Modifier, logoFraction: Float, showScore: Boolean, big: Boolean) {
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxHeight(logoFraction).aspectRatio(1f), contentAlignment = Alignment.Center) {
            TeamLogo(team.logo, team.abbreviation, if (big) 120.dp else 48.dp, Modifier.fillMaxSize())
        }
        if (showScore && game.state != GameState.PRE && team.score.isNotEmpty()) {
            Text(
                team.score,
                fontSize = if (big) 40.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (game.state == GameState.FINAL && !team.winner) Color(0xCCFFFFFF) else Color.White,
            )
        }
    }
}

@Composable
fun GameThumb(game: Game, hideScores: Boolean, recording: Boolean = false) {
    GameArt(game, showScore = !hideScores)
    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Tag(game.league.label)
        if (recording) {
            Spacer(Modifier.width(4.dp))
            Tag("REC", color = AppColors.LiveBadge)
        }
    }
    Box(Modifier.fillMaxSize().padding(6.dp)) {
        when (game.state) {
            GameState.LIVE -> LiveBadge(Modifier.align(Alignment.BottomStart), small = true)
            GameState.PRE -> Tag(formatStart(game.startMillis), Modifier.align(Alignment.BottomStart))
            GameState.FINAL -> Tag("Final", Modifier.align(Alignment.BottomStart))
        }
        game.broadcasts.firstOrNull()?.let { Tag(it, Modifier.align(Alignment.BottomEnd)) }
    }
}

@Composable
fun TournamentThumb(t: Tournament, hideScores: Boolean) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B5E20), Color(0xFF0B3D10))))) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 12.dp, end = 12.dp)) {
            Text(t.tour.label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xCCFFFFFF))
            Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!hideScores) {
                t.leaders.take(2).forEach { p ->
                    Text("${p.position}  ${p.shortName}  ${p.toPar}", fontSize = 11.sp, color = Color(0xE6FFFFFF), maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(6.dp)) {
            if (t.roundInProgress) LiveBadge(Modifier.align(Alignment.BottomStart), small = true)
            else Tag(if (t.state == GameState.PRE) formatDay(t.startMillis) else t.detail.replace("Round ", "R"), Modifier.align(Alignment.BottomStart))
            t.broadcasts.firstOrNull()?.let { Tag(it, Modifier.align(Alignment.BottomEnd)) }
        }
    }
}

/** A channel's live card: logo on a dark field, with the current program's progress. */
@Composable
fun ChannelThumb(channel: Channel, program: Program?, now: Long = System.currentTimeMillis()) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B2B2B), Color(0xFF151515)))),
        contentAlignment = Alignment.Center,
    ) {
        ChannelLogo(channel, 64.dp, background = Color.Transparent)
        Box(Modifier.fillMaxSize().padding(6.dp)) {
            if (program != null && program.isOnNow(now)) LiveBadge(Modifier.align(Alignment.TopStart), small = true)
        }
        if (program != null && program.isOnNow(now)) {
            ProgressLine(program.progress(now), Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Poster art (movies, shows) with a text fallback. */
@Composable
fun PosterThumb(image: String?, title: String) {
    var failed by remember(image) { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF333333), Color(0xFF1A1A1A)))),
        contentAlignment = Alignment.Center,
    ) {
        if (image.isNullOrBlank() || failed) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 4,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
        } else {
            AsyncImage(image, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Hero ("ambient") header: details of the focused item, like the top of YouTube TV's Home.
// ---------------------------------------------------------------------------------------------

data class HeroInfo(
    val title: String,
    val meta: List<String> = emptyList(),
    val description: String? = null,
    val live: Boolean = false,
    val image: String? = null,
    val game: Game? = null,
    val channel: Channel? = null,
    val progress: Float? = null,
    val tournament: Tournament? = null,
)

/**
 * The header of a main tab: the focused card ([AppViewModel.heroFocus]) or [default]. Reading the
 * focus here (not in the tab) means moving between cards only redraws the header, and a short
 * settle delay skips the headers of cards the viewer just scrolls past.
 */
@Composable
fun TabHero(vm: AppViewModel, default: HeroInfo, modifier: Modifier = Modifier, compact: Boolean = false, videoBehind: Boolean = false) {
    val target = vm.heroFocus ?: default
    var shown by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (shown != target) {
            delay(110)
            shown = target
        }
    }
    Hero(shown, modifier, vm.hideScores, compact = compact, artBehind = !videoBehind)
}

@Composable
fun Hero(info: HeroInfo?, modifier: Modifier = Modifier, hideScores: Boolean = false, compact: Boolean = false, artBehind: Boolean = true) {
    AnimatedContent(
        targetState = info,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        contentKey = { it?.title + it?.meta?.joinToString() },
        modifier = modifier,
        label = "hero",
    ) { h ->
        Box(Modifier.fillMaxSize()) {
            if (h == null) return@Box
            // Art on the right, fading into the background on the left and bottom (unless live video plays there).
            if (artBehind) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.58f)) {
                when {
                    h.game != null -> GameArt(h.game, logoFraction = 0.5f, showScore = !hideScores, big = true)
                    h.image != null -> AsyncImage(h.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    h.channel != null -> Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
                        ChannelLogo(h.channel, 140.dp, background = Color.Transparent)
                    }
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(0f to AppColors.Background, 0.45f to Color(0x990F0F0F), 1f to Color.Transparent),
                    ),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to AppColors.Background)))
            }
            // Text sits at the bottom so long titles never run into the top bar.
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.52f).padding(start = 48.dp, bottom = 10.dp)) {
                Text(h.title, fontSize = if (compact) 26.sp else 30.sp, fontWeight = FontWeight.Medium, maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 34.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (h.live) {
                        LiveBadge()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(h.meta.joinToString("  •  "), fontSize = 14.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (h.progress != null) {
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(h.progress, Modifier.width(220.dp))
                }
                if (!h.description.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(h.description, fontSize = 14.sp, color = Color(0xFFCCCCCC), maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                }
            }
        }
    }
}

fun heroFor(game: Game, hideScores: Boolean): HeroInfo {
    val status = when (game.state) {
        GameState.LIVE -> if (hideScores) "Live now" else game.shortDetail
        GameState.PRE -> formatStart(game.startMillis)
        GameState.FINAL -> "Final"
    }
    val score = if (!hideScores && game.state != GameState.PRE) "${game.away.abbreviation} ${game.away.score} – ${game.home.score} ${game.home.abbreviation}" else null
    val desc = listOfNotNull(
        score,
        game.situation.takeIf { !hideScores },
        listOfNotNull(game.away.record?.let { "${game.away.shortName} ($it)" }, game.home.record?.let { "${game.home.shortName} ($it)" })
            .takeIf { it.isNotEmpty() }?.joinToString(" vs "),
        game.venue,
    ).joinToString("\n")
    return HeroInfo(
        title = "${game.away.displayName} at ${game.home.displayName}",
        meta = listOfNotNull(game.league.label, game.broadcasts.firstOrNull(), status),
        description = desc,
        live = game.state == GameState.LIVE,
        game = game,
    )
}

fun heroFor(t: Tournament, hideScores: Boolean): HeroInfo = HeroInfo(
    title = t.name,
    meta = listOfNotNull(t.tour.label, t.broadcasts.firstOrNull(), t.detail),
    description = if (hideScores) null else t.leaders.take(3).joinToString("\n") { "${it.position}. ${it.name}  ${it.toPar}" },
    live = t.roundInProgress,
    tournament = t,
)

fun heroFor(channel: Channel, program: Program?, now: Long = System.currentTimeMillis()): HeroInfo = HeroInfo(
    title = program?.title ?: cleanChannelName(channel.name),
    meta = listOfNotNull(
        cleanChannelName(channel.name),
        program?.let { if (it.isOnNow(now)) minutesLeft(it.endMillis, now) else formatRange(it.startMillis, it.endMillis) },
    ),
    description = program?.description,
    live = program?.isOnNow(now) == true,
    channel = channel,
    progress = program?.takeIf { it.isOnNow(now) }?.progress(now),
)
