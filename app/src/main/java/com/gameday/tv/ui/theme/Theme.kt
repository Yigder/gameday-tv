package com.gameday.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** YouTube TV-style dark palette: near-black canvas, white focus, red for live. */
object AppColors {
    val Background = Color(0xFF0F0F0F)
    val Surface = Color(0xFF181818)
    val Raised = Color(0xFF212121)
    val Card = Color(0xFF272727)
    val CardFocused = Color(0xFF3A3A3A)
    val Border = Color(0xFF3D3D3D)
    val Focus = Color(0xFFFFFFFF)
    val Live = Color(0xFFFF0033)
    val LiveBadge = Color(0xFFCC0000)
    val Accent = Color(0xFFFF0033)
    val Good = Color(0xFF2BA640)
    val Warn = Color(0xFFFFB300)
    val Text = Color(0xFFF1F1F1)
    val TextDim = Color(0xFFAAAAAA)
    val TextFaint = Color(0xFF717171)
    val Scrim = Color(0xE6000000)

    /** Profile avatar colors. */
    val Avatars = listOf(
        Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
        Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFFD81B60), Color(0xFF6D4C41),
    )

    fun avatar(index: Int): Color = Avatars[Math.floorMod(index, Avatars.size)]
}

@Composable
fun GameDayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.Text,
            onPrimary = Color.Black,
            background = AppColors.Background,
            onBackground = AppColors.Text,
            surface = AppColors.Surface,
            onSurface = AppColors.Text,
            surfaceVariant = AppColors.Card,
            onSurfaceVariant = AppColors.TextDim,
            border = AppColors.Focus,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides AppColors.Text) {
            Box(Modifier.fillMaxSize().background(AppColors.Background)) { content() }
        }
    }
}
