package com.gameday.tv.mobile

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gameday.tv.ui.theme.AppColors
import com.gameday.tv.ui.theme.GameDayTheme

/**
 * The TV app's dark palette for Material 3 touch components. Shared pieces (cards' artwork, score
 * bugs, logos) are drawn with Compose for TV, so its theme wraps this one.
 */
@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    GameDayTheme {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = AppColors.Text,
                onPrimary = Color.Black,
                primaryContainer = Color(0xFF3A3A3A),
                onPrimaryContainer = AppColors.Text,
                secondary = AppColors.Accent,
                onSecondary = Color.White,
                secondaryContainer = Color(0xFF333333),
                onSecondaryContainer = AppColors.Text,
                tertiary = AppColors.Good,
                background = AppColors.Background,
                onBackground = AppColors.Text,
                surface = AppColors.Background,
                onSurface = AppColors.Text,
                surfaceVariant = AppColors.Card,
                onSurfaceVariant = AppColors.TextDim,
                surfaceContainerLowest = AppColors.Background,
                surfaceContainerLow = AppColors.Surface,
                surfaceContainer = AppColors.Surface,
                surfaceContainerHigh = AppColors.Raised,
                surfaceContainerHighest = AppColors.Card,
                outline = AppColors.Border,
                outlineVariant = Color(0xFF2A2A2A),
                error = AppColors.Live,
                onError = Color.White,
            ),
        ) {
            // Text outside a Material surface otherwise defaults to black (unreadable on this dark canvas).
            CompositionLocalProvider(LocalContentColor provides AppColors.Text, content = content)
        }
    }
}
