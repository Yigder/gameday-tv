package com.gameday.tv

import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.runtime.LaunchedEffect
import com.gameday.tv.ui.AddonBrowseScreen
import com.gameday.tv.ui.AddonDetailScreen
import com.gameday.tv.ui.AddonInstallScreen
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.DisplayModes
import com.gameday.tv.ui.FollowAppLifecycle
import com.gameday.tv.ui.KeyActivity
import com.gameday.tv.ui.TorBoxSetupScreen
import com.gameday.tv.ui.WatchStalls
import com.gameday.tv.ui.BrowseScreen
import com.gameday.tv.ui.ChannelScreen
import com.gameday.tv.ui.CreateAccountScreen
import com.gameday.tv.ui.DialogHost
import com.gameday.tv.ui.GameScreen
import com.gameday.tv.ui.MainShell
import com.gameday.tv.ui.MovieScreen
import com.gameday.tv.ui.MultiviewBuilderScreen
import com.gameday.tv.ui.MultiviewScreen
import com.gameday.tv.ui.OkKeyGate
import com.gameday.tv.ui.OnboardingScreen
import com.gameday.tv.ui.PlayerScreen
import com.gameday.tv.ui.ProfileEditScreen
import com.gameday.tv.ui.ProfilesScreen
import com.gameday.tv.ui.ProviderScreen
import com.gameday.tv.ui.Screen
import com.gameday.tv.ui.SearchScreen
import com.gameday.tv.ui.OnDemandSearchScreen
import com.gameday.tv.ui.SeriesScreen
import com.gameday.tv.ui.SettingsScreen
import com.gameday.tv.ui.SignInScreen
import com.gameday.tv.ui.TeamScreen
import com.gameday.tv.ui.TeamsPickerScreen
import com.gameday.tv.ui.TournamentScreen
import com.gameday.tv.ui.WelcomeScreen
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
        // Smooth motion: the TV's fastest refresh rate (e.g. 120 Hz) when it has one.
        DisplayModes.apply(this)
        setContent {
            GameDayTheme { GameDayApp() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyActivity.onKey(event)
        if (OkKeyGate.shouldConsume(event)) return true
        // Compose turns Back into "move focus out of the current group" (FocusDirection.Exit) and
        // only lets it through when there's no group left to leave, so menus, side panels and rows
        // took two presses to close. Back goes straight to the back handlers instead. (The on-screen
        // keyboard still takes Back first: the IME sees it before the activity does.)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun GameDayApp(vm: AppViewModel = viewModel()) {
    val screen = vm.screen
    // Keeps each screen's scroll positions/selections when navigating back to it.
    val stateHolder = rememberSaveableStateHolder()

    // The shared player (full screen + live video behind the menus).
    val main = vm.mainStream
    FollowAppLifecycle(main)
    WatchStalls(main)
    LaunchedEffect(screen) { vm.onScreenChanged(screen) }

    // (A menu's own Back handler is in DialogHost, so it wins over the screen's handlers.)
    BackHandler(enabled = vm.dialog == null && vm.canGoBack) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        stateHolder.SaveableStateProvider(screen.key) {
            when (screen) {
                Screen.Splash -> Box(Modifier.fillMaxSize())
                Screen.Welcome -> WelcomeScreen(vm)
                is Screen.SignIn -> SignInScreen(vm, screen.email)
                Screen.CreateAccount -> CreateAccountScreen(vm)
                is Screen.Onboarding -> OnboardingScreen(vm, screen.step)
                is Screen.Provider -> ProviderScreen(vm, onboarding = false, editId = screen.editId)
                Screen.AddonInstall -> AddonInstallScreen(vm)
                Screen.TorBoxSetup -> TorBoxSetupScreen(vm)
                is Screen.AddonDetail -> AddonDetailScreen(vm, screen.type, screen.id)
                is Screen.AddonBrowse -> AddonBrowseScreen(vm, screen.rowKey)
                Screen.TeamsPicker -> TeamsPickerScreen(vm)
                is Screen.Profiles -> ProfilesScreen(vm, screen.manage)
                is Screen.ProfileEdit -> ProfileEditScreen(vm, screen.profileId)
                Screen.Main -> MainShell(vm, stateHolder)
                Screen.Search -> SearchScreen(vm)
                Screen.OnDemandSearch -> OnDemandSearchScreen(vm)
                is Screen.Settings -> SettingsScreen(vm)
                is Screen.GameDetail -> GameScreen(vm, screen.gameId)
                is Screen.TournamentDetail -> TournamentScreen(vm, screen.tournamentId)
                is Screen.Team -> TeamScreen(vm, screen.leagueKey, screen.teamId)
                is Screen.ChannelDetail -> ChannelScreen(vm, screen.channelId)
                is Screen.MovieDetail -> MovieScreen(vm, screen.movieId)
                is Screen.SeriesDetail -> SeriesScreen(vm, screen.seriesId)
                is Screen.Browse -> BrowseScreen(vm, screen.kind, screen.categoryId)
                Screen.Player -> PlayerScreen(vm)
                Screen.Multiview -> MultiviewScreen(vm)
                Screen.MultiviewBuilder -> MultiviewBuilderScreen(vm)
            }
        }

        vm.dialog?.let { DialogHost(vm, it) }

        AnimatedVisibility(
            visible = vm.message != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            Text(
                vm.message.orEmpty(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Black,
                modifier = Modifier
                    .background(AppColors.Text, RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
