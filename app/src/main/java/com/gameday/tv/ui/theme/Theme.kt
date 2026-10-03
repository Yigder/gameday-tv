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

object AppColors {
    val Background = Color(0xFF0A0E16)
    val Surface = Color(0xFF111824)
    val Card = Color(0xFF172030)
    val CardFocused = Color(0xFF223049)
    val Border = Color(0xFF2A3548)
    val Accent = Color(0xFFFF7A1A)
    val Live = Color(0xFFFF3B3B)
    val Good = Color(0xFF2ED47A)
    val Text = Color(0xFFF2F5FA)
    val TextDim = Color(0xFF9AA6B8)
}

@Composable
fun GameDayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.Accent,
            onPrimary = Color.Black,
            background = AppColors.Background,
            onBackground = AppColors.Text,
            surface = AppColors.Surface,
            onSurface = AppColors.Text,
            surfaceVariant = AppColors.Card,
            onSurfaceVariant = AppColors.TextDim,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides AppColors.Text) {
            Box(Modifier.fillMaxSize().background(AppColors.Background)) { content() }
        }
    }
}
