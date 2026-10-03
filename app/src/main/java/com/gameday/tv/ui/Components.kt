package com.gameday.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
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
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.gameday.tv.data.Channel
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

/** Focusable card with the TV "lift + outline" focus treatment. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    containerColor: Color = AppColors.Card,
    focusedContainerColor: Color = AppColors.CardFocused,
    focusedScale: Float = 1.05f,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
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
            focusedBorder = Border(border = BorderStroke(2.dp, AppColors.Accent), shape = shape),
        ),
        content = content,
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppColors.Accent.copy(alpha = 0.18f) else AppColors.Card,
            contentColor = if (selected) AppColors.Accent else AppColors.TextDim,
            focusedContainerColor = AppColors.Text,
            focusedContentColor = Color.Black,
            pressedContainerColor = AppColors.Text,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(1.5.dp, AppColors.Accent), shape = shape) else Border.None,
        ),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) AppColors.Accent else AppColors.Card,
            contentColor = if (primary) Color.Black else AppColors.Text,
            focusedContainerColor = if (primary) Color(0xFFFFA25E) else AppColors.Text,
            focusedContentColor = Color.Black,
            pressedContainerColor = AppColors.Text,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Single-line text input that works with the D-pad and the on-screen TV keyboard. */
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
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        if (label != null) {
            Text(label, fontSize = 13.sp, color = AppColors.TextDim, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = AppColors.Text, fontSize = 17.sp),
            cursorBrush = SolidColor(AppColors.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = if (onSubmit != null) {
                KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }, onSearch = { onSubmit() })
            } else {
                KeyboardActions.Default
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                // Single-line field: Up/Down always leave it, whatever device the keys come from.
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                }
                .clip(shape)
                .background(if (focused) AppColors.CardFocused else AppColors.Card)
                .border(2.dp, if (focused) AppColors.Accent else AppColors.Border, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = AppColors.TextDim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
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
            color = AppColors.Accent,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun TeamLogo(url: String?, fallback: String, size: Dp, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url.isNullOrBlank() || failed) {
        Box(modifier.size(size).background(AppColors.CardFocused, CircleShape), contentAlignment = Alignment.Center) {
            Text(fallback.take(3), fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold, color = AppColors.TextDim)
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
fun ChannelLogo(channel: Channel, size: Dp, modifier: Modifier = Modifier) {
    var failed by remember(channel.logo) { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0D131E)),
        contentAlignment = Alignment.Center,
    ) {
        if (channel.logo.isNullOrBlank() || failed) {
            Text(
                initials(channel.name),
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
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit,
                onError = { failed = true },
            )
        }
    }
}

private fun initials(name: String): String {
    val words = name.replace(Regex("^[A-Za-z]{2,3}\\s*[:|]\\s*"), "").split(' ', '-', '|').filter { it.isNotBlank() }
    return words.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "TV" }
}

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
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                fontSize = 14.sp,
                color = AppColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )
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
