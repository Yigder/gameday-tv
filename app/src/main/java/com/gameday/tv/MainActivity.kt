package com.gameday.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.gameday.tv.ui.AccountScreen
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.ChannelsScreen
import com.gameday.tv.ui.GameScreen
import com.gameday.tv.ui.HomeScreen
import com.gameday.tv.ui.LoginScreen
import com.gameday.tv.ui.MultiviewScreen
import com.gameday.tv.ui.MySportsScreen
import com.gameday.tv.ui.PlayerScreen
import com.gameday.tv.ui.Screen
import com.gameday.tv.ui.TournamentScreen
import com.gameday.tv.ui.theme.AppColors
import com.gameday.tv.ui.theme.GameDayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-screen on phones/tablets too (TVs have no system bars).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            GameDayTheme { GameDayApp() }
        }
    }
}

@Composable
private fun GameDayApp(vm: AppViewModel = viewModel()) {
    val screen = vm.screen
    // Keeps each screen's scroll positions/selections when navigating back to it.
    val stateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = vm.canGoBack) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        stateHolder.SaveableStateProvider(screen.key) {
            when (screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Home -> HomeScreen(vm)
                is Screen.GameDetail -> GameScreen(vm, screen.gameId)
                is Screen.TournamentDetail -> TournamentScreen(vm, screen.tournamentId)
                is Screen.Channels -> ChannelsScreen(vm, screen.query)
                Screen.Player -> PlayerScreen(vm)
                Screen.Multiview -> MultiviewScreen(vm)
                Screen.MySports -> MySportsScreen(vm)
                Screen.Account -> AccountScreen(vm)
            }
        }

        AnimatedVisibility(
            visible = vm.message != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            Text(
                vm.message.orEmpty(),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black,
                modifier = Modifier
                    .background(AppColors.Text, RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
