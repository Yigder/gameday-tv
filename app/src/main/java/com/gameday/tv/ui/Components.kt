package com.gameday.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.gameday.tv.data.Channel
import com.gameday.tv.data.Profile
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Focusable surfaces and buttons
// ---------------------------------------------------------------------------------------------

/** Focusable panel with the YouTube TV treatment: lifts and gets a white outline when focused. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    containerColor: Color = AppColors.Card,
    focusedContainerColor: Color = AppColors.CardFocused,
    focusedScale: Float = 1.04f,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick.swallowingRelease(),
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = AppColors.Text,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = AppColors.Text,
            pressedContainerColor = focusedContainerColor,
            pressedContentColor = AppColors.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(3.dp, AppColors.Focus), shape = shape),
        ),
        content = content,
    )
}

/**
 * A long-press handler that also drops the rest of the held OK press. Long presses open menus while
 * OK is still down; without this the key repeats and release land on the menu's first option
 * ("Watch") and pick it, closing the menu before anything can be chosen.
 */
fun (() -> Unit)?.swallowingRelease(): (() -> Unit)? = this?.let { action ->
    {
        OkKeyGate.swallowRelease()
        action()
    }
}

/** Rounded button: translucent when idle, white with black text when focused (YouTube TV style). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick.swallowingRelease(),
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color(0xE6FFFFFF) else Color(0x29FFFFFF),
            contentColor = if (primary) Color.Black else AppColors.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color(0xFFDDDDDD),
            pressedContentColor = Color.Black,
            disabledContainerColor = Color(0x14FFFFFF),
            disabledContentColor = AppColors.TextFaint,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(
            Modifier.padding(start = if (icon != null) 16.dp else 22.dp, end = 22.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Size of the player's round buttons (Settings › Playback › Player buttons). */
val LocalButtonSize = androidx.compose.runtime.compositionLocalOf { 46.dp }

/** (button, play button) sizes for "small", "medium" or "large". */
fun playerButtonSizes(setting: String): Pair<Dp, Dp> = when (setting) {
    "small" -> 38.dp to 44.dp
    "large" -> 52.dp to 60.dp
    else -> 44.dp to 52.dp
}

/** Round icon button with its label shown underneath while focused (player controls). */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = LocalButtonSize.current,
    active: Boolean = false,
    tint: Color? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // Narrow columns keep the row compact; the focused label may spill past its column.
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size + 22.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(size).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (active) Color(0x55FFFFFF) else Color(0x29FFFFFF),
                contentColor = tint ?: AppColors.Text,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
                pressedContainerColor = Color(0xFFDDDDDD),
                pressedContentColor = Color.Black,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.48f))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            fontSize = if (size < 42.dp) 11.sp else 12.sp,
            color = if (focused) AppColors.Text else Color.Transparent,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.wrapContentWidth(unbounded = true),
        )
    }
}

/** Filter chip (top of Sports, Live guide, Library). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFFF1F1F1) else Color(0x1FFFFFFF),
            contentColor = if (selected) Color.Black else AppColors.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** A settings-style row: title, optional description and value, with an on/off switch when [checked] is set. */
@Composable
fun SettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    checked: Boolean? = null,
    icon: ImageVector? = null,
    chevron: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        focusedScale = 1.02f,
        containerColor = Color.Transparent,
        focusedContainerColor = Color(0xFFF1F1F1),
        shape = RoundedCornerShape(8.dp),
    ) {
        val fg = if (focused) Color.Black else AppColors.Text
        val dim = if (focused) Color(0xFF444444) else AppColors.TextDim
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(22.dp), tint = fg)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (value != null) {
                Spacer(Modifier.width(12.dp))
                Text(value, fontSize = 14.sp, color = dim, maxLines = 1)
            }
            if (checked != null) {
                Spacer(Modifier.width(12.dp))
                Switch(checked, focused)
            }
            if (chevron) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.ChevronRight, null, Modifier.size(20.dp), tint = dim)
            }
        }
    }
}

@Composable
private fun Switch(on: Boolean, focused: Boolean) {
    val track = when {
        on -> AppColors.Live
        focused -> Color(0xFF9E9E9E)
        else -> Color(0xFF5F5F5F)
    }
    Box(Modifier.width(40.dp).height(22.dp).background(track, RoundedCornerShape(50)).padding(3.dp)) {
        Box(Modifier.size(16.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).background(Color.White, CircleShape))
    }
}

// ---------------------------------------------------------------------------------------------
// Text input & on-screen keyboard
// ---------------------------------------------------------------------------------------------

/**
 * Single-line text input for a TV. It shows as a button until OK is pressed, so moving the focus
 * over a form never pops up the on-screen keyboard; OK starts editing, and Done / Back ends it.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onSubmit: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val display = remember { FocusRequester() }
    val editor = remember { FocusRequester() }
    var returnFocus by remember { mutableStateOf(false) }
    var editorHadFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val shown = if (password) "•".repeat(value.length) else value

    fun finish(next: Boolean) {
        editing = false
        keyboard?.hide()
        if (next) focusManager.moveFocus(FocusDirection.Down) else returnFocus = true
    }
    LaunchedEffect(editing) {
        if (editing) {
            editor.requestFocusSafely(30)
            keyboard?.show()
        }
    }
    LaunchedEffect(returnFocus) {
        if (returnFocus) {
            display.requestFocusSafely(30)
            returnFocus = false
        }
    }

    Column(modifier) {
        if (label != null) {
            Text(label, fontSize = 13.sp, color = AppColors.TextDim, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
        }
        if (!editing) {
            Surface(
                onClick = { editing = true },
                modifier = Modifier.fillMaxWidth().focusRequester(display),
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0x1FFFFFFF),
                    contentColor = AppColors.Text,
                    focusedContainerColor = Color(0xFFF1F1F1),
                    focusedContentColor = Color.Black,
                    pressedContainerColor = Color.White,
                    pressedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)),
            ) {
                Text(
                    shown.ifEmpty { placeholder },
                    fontSize = 17.sp,
                    color = if (shown.isEmpty()) androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.6f) else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.Black, fontSize = 17.sp),
                cursorBrush = SolidColor(Color.Black),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { finish(false); onSubmit?.invoke() },
                    onGo = { finish(false); onSubmit?.invoke() },
                    onSearch = { finish(false); onSubmit?.invoke() },
                    onNext = { if (onSubmit != null) { finish(false); onSubmit() } else finish(true) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(editor)
                    .onFocusChanged {
                        // Leaving the field (e.g. touch, or focus moved by code) ends editing.
                        if (it.isFocused) editorHadFocus = true
                        else if (editorHadFocus) { editorHadFocus = false; editing = false }
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Back, Key.Escape -> { finish(false); true }
                            Key.DirectionDown -> { finish(false); focusManager.moveFocus(FocusDirection.Down) }
                            Key.DirectionUp -> { finish(false); focusManager.moveFocus(FocusDirection.Up) }
                            else -> false
                        }
                    }
                    .clip(shape)
                    .background(Color(0xFFF1F1F1))
                    .border(2.dp, Color.White, shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, color = Color(0xFF666666), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                },
            )
        }
    }
}
/** YouTube TV's search keyboard: a letter grid that never needs the system keyboard. */
@Composable
fun OnScreenKeyboard(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    firstKey: FocusRequester? = null,
    /** Shows a full-width Search (Enter) key. */
    onEnter: (() -> Unit)? = null,
) {
    val rows = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { c, ch ->
                    KeyCap(
                        ch.toString(),
                        { onKey(ch.toString()) },
                        if (r == 0 && c == 0 && firstKey != null) Modifier.focusRequester(firstKey) else Modifier,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyCap("", { onKey(" ") }, Modifier.width(118.dp), icon = Icons.Space)
            KeyCap("", onBackspace, Modifier.width(76.dp), icon = Icons.Backspace)
            KeyCap("Clear", onClear, Modifier.width(76.dp))
        }
        if (onEnter != null) KeyCap("Search", onEnter, Modifier.width(282.dp), icon = Icons.Search, primary = true)
    }
}

@Composable
private fun KeyCap(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp).then(if (label.length == 1) Modifier.width(36.dp) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color(0x40FFFFFF) else Color(0x1AFFFFFF),
            contentColor = AppColors.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color(0xFFDDDDDD),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (primary) 1.04f else 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                icon != null && primary -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                icon != null -> Icon(icon, label, Modifier.size(20.dp))
                else -> Text(label, fontSize = if (label.length == 1) 17.sp else 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Small visual pieces
// ---------------------------------------------------------------------------------------------

@Composable
fun LiveBadge(modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        "LIVE",
        fontSize = if (small) 10.sp else 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .background(AppColors.LiveBadge, RoundedCornerShape(3.dp))
            .padding(horizontal = if (small) 4.dp else 6.dp, vertical = 1.dp),
    )
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = Color(0xCC000000), textColor: Color = Color.White) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = textColor,
        maxLines = 1,
        modifier = modifier.background(color, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Thin red progress line (live programs, resume points). */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = AppColors.Live, track: Color = Color(0x55FFFFFF)) {
    Box(modifier.fillMaxWidth().height(3.dp).background(track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
fun LiveDot(size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "live")
    val alpha by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    Box(Modifier.size(size).alpha(alpha).background(AppColors.Live, CircleShape))
}

@Composable
fun Spinner(size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(modifier.size(size)) {
        drawArc(
            color = AppColors.Live,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun Avatar(profile: Profile?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(AppColors.avatar(profile?.color ?: 0), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(profile?.initial ?: "?", fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

@Composable
fun TeamLogo(url: String?, fallback: String, size: Dp, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url.isNullOrBlank() || failed) {
        Box(modifier.size(size).background(Color(0x33FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
            Text(fallback.take(3), fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold, color = AppColors.Text)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = fallback,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
            onError = { failed = true },
        )
    }
}

@Composable
fun ChannelLogo(channel: Channel, size: Dp, modifier: Modifier = Modifier, background: Color = Color(0xFF1C1C1C)) {
    var failed by remember(channel.logo) { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(6.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (channel.logo.isNullOrBlank() || failed) {
            Text(
                channelInitials(channel.name),
                fontSize = (size.value * 0.28f).sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextDim,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        } else {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(size * 0.08f),
                contentScale = ContentScale.Fit,
                onError = { failed = true },
            )
        }
    }
}

/** Logo stand-in: short names as-is ("ESPN", "FS1"), otherwise initials ("NFL NETWORK" → "NN"). */
fun channelInitials(name: String): String {
    val clean = cleanChannelName(name).uppercase()
    val words = clean.split(' ', '-', '|', ':').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "TV"
        words.size == 1 || words[0].length <= 5 && words.size <= 2 && clean.length <= 6 -> words[0].take(5)
        words[0].length in 2..4 && words[0].all { it.isLetterOrDigit() } -> words[0]
        else -> words.take(3).joinToString("") { it.take(1) }
    }
}

/** Channel names without the provider's "US|" prefixes and quality tags, for display. */
fun cleanChannelName(name: String): String =
    name.replace(Regex("^\\|?[A-Za-z]{2,3}\\|?\\s*[:|]\\s*"), "")
        .replace(Regex("\\s+(FHD|UHD|HD|SD|4K|HEVC|RAW)\\b", RegexOption.IGNORE_CASE), "")
        .trim().ifEmpty { name }

@Composable
fun EmptyState(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 14.sp, color = AppColors.TextDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
    }
}

@Composable
fun LoadingState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spinner()
        Spacer(Modifier.height(14.dp))
        Text(text, color = AppColors.TextDim, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = modifier, maxLines = 1)
}

// ---------------------------------------------------------------------------------------------
// Focus helpers
// ---------------------------------------------------------------------------------------------

/** Requests focus once the target is laid out; silently ignores requesters that aren't attached. */
suspend fun FocusRequester.requestFocusSafely(delayMs: Long = 60): Boolean {
    delay(delayMs)
    return try {
        requestFocus()
        true
    } catch (_: IllegalStateException) {
        false
    }
}

/**
 * Remembers this item as the focused one on [screenKey], and takes focus back when the viewer
 * returns to the screen (vm.back()).
 */
@Composable
fun Modifier.rememberFocus(vm: AppViewModel, screenKey: String, itemKey: String): Modifier {
    val requester = remember { FocusRequester() }
    val restore = vm.restoreFocusFor == screenKey && vm.focusMemory[screenKey] == itemKey
    LaunchedEffect(restore) {
        if (restore && requester.requestFocusSafely(80)) vm.restoreFocusFor = null
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) vm.focusMemory[screenKey] = itemKey }
}

/** Whether focus is somewhere inside a container (see [trackFocus]). */
class FocusTracker {
    var has by mutableStateOf(false)
}

fun Modifier.trackFocus(tracker: FocusTracker): Modifier = onFocusChanged { tracker.has = it.hasFocus }

/**
 * Puts focus on [default] when a screen opens, unless [rememberFocus] is restoring a previous spot.
 * With a [tracker], it keeps trying while the content is still loading (nothing focusable yet).
 */
@Composable
fun InitialFocus(vm: AppViewModel, screenKey: String, default: FocusRequester, key: Any? = Unit, tracker: FocusTracker? = null) {
    LaunchedEffect(screenKey, key) {
        if (vm.restoreFocusFor == screenKey && vm.focusMemory[screenKey] != null) {
            delay(500)
            if (vm.restoreFocusFor != screenKey) return@LaunchedEffect
            vm.restoreFocusFor = null
        }
        repeat(if (tracker == null) 1 else 20) { attempt ->
            default.requestFocusSafely(if (attempt == 0) 100 else 300)
            if (tracker == null || tracker.has) return@LaunchedEffect
        }
    }
}

/**
 * Scrolls a vertical list so the focused row sits near the top (TV "pivot" scrolling), instead of
 * the minimum scroll. Rows inside get the default behavior back via [DefaultScroll].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScroll(offset: Dp, content: @Composable () -> Unit) {
    val default = LocalBringIntoViewSpec.current
    val px = with(LocalDensity.current) { offset.toPx() }
    val spec = remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - px
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, LocalDefaultScroll provides default) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
private val LocalDefaultScroll = androidx.compose.runtime.staticCompositionLocalOf<BringIntoViewSpec?> { null }

/** Restores normal scrolling for horizontal rows inside [PivotScroll]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DefaultScroll(content: @Composable () -> Unit) {
    val d = LocalDefaultScroll.current
    if (d == null) content() else CompositionLocalProvider(LocalBringIntoViewSpec provides d) { content() }
}

/**
 * Keeps D-pad focus inside this container (side sheets drawn over a page). Only arrow moves are
 * stopped: a dialog opened on top can still take focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.trapFocus(): Modifier = this
    .focusProperties {
        onExit = {
            when (requestedFocusDirection) {
                FocusDirection.Left, FocusDirection.Right, FocusDirection.Up, FocusDirection.Down,
                FocusDirection.Next, FocusDirection.Previous -> cancelFocusChange()
                else -> Unit
            }
        }
    }
    .focusGroup()

/**
 * A column of choices (settings sections, library shelves) that, when focus comes back from the
 * page next to it, returns to the chosen item instead of whichever item lines up.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.returnFocusTo(selected: FocusRequester): Modifier = this.focusRestorer(selected).focusGroup()

/** 16:9 box for thumbnails. */
fun Modifier.thumb(width: Dp): Modifier = this.width(width).aspectRatio(16f / 9f)
