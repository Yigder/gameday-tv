package com.gameday.tv.mobile

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.FollowAppLifecycle
import com.gameday.tv.ui.Screen
import com.gameday.tv.ui.Tab
import com.gameday.tv.ui.WatchStalls
import com.gameday.tv.ui.theme.AppColors

/**
 * The phone and tablet app. Same account, provider, scores and recordings logic as the TV app
 * ([AppViewModel]); only the screens differ: touch controls, a bottom tab bar, and a player that
 * turns to landscape and keeps playing in picture-in-picture.
 */
class MobileActivity : ComponentActivity() {
    /** The activity is showing as a picture-in-picture window. */
    val inPip = mutableStateOf(false)

    /** Leaving the app (Home) shrinks the video into a picture-in-picture window. */
    private var pipEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalInPip provides inPip.value) {
                MobileTheme { MobileApp(this) }
            }
        }
    }

    /** Video screens: landscape, no system bars, picture-in-picture on Home. */
    fun setVideoMode(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        pipEnabled = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { setPictureInPictureParams(pipParams(autoEnter = on)) }
        }
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        return b.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters on its own (auto-enter); older versions need asking.
        if (pipEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(pipParams(autoEnter = false)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }
}

/** True while the app is a picture-in-picture window: the player shows only the video and score. */
val LocalInPip = compositionLocalOf { false }

@Composable
private fun MobileApp(activity: MobileActivity, vm: AppViewModel = viewModel()) {
    val screen = vm.screen
    // Keeps each screen's scroll positions when navigating back to it.
    val stateHolder = rememberSaveableStateHolder()

    // The shared player: full screen, and the mini player above the tab bar.
    val main = vm.mainStream
    FollowAppLifecycle(main)
    WatchStalls(main)
    LaunchedEffect(screen) { vm.onScreenChanged(screen) }

    val video = screen == Screen.Player || screen == Screen.Multiview
    DisposableEffect(video) {
        activity.setVideoMode(video)
        onDispose { }
    }

    BackHandler(enabled = vm.dialog == null && vm.canGoBack) { vm.back() }
    // Back from another tab goes to Sports first, then leaves the app.
    BackHandler(enabled = vm.dialog == null && !vm.canGoBack && screen == Screen.Main && vm.tab != Tab.FIRST) { vm.selectTab(Tab.FIRST) }

    Box(Modifier.fillMaxSize().background(if (video) Color.Black else AppColors.Background)) {
        stateHolder.SaveableStateProvider(screen.key) {
            when (screen) {
                Screen.Splash -> Box(Modifier.fillMaxSize())
                Screen.Welcome -> WelcomeScreen(vm)
                is Screen.SignIn -> SignInScreen(vm, screen.email)
                Screen.CreateAccount -> CreateAccountScreen(vm)
                is Screen.Onboarding -> OnboardingScreen(vm, screen.step)
                is Screen.Provider -> ProviderScreen(vm, onboarding = false, editId = screen.editId)
                Screen.TeamsPicker -> TeamsPickerScreen(vm)
                is Screen.Profiles -> ProfilesScreen(vm)
                is Screen.ProfileEdit -> ProfileEditScreen(vm, screen.profileId)
                Screen.Main -> MainScreen(vm, stateHolder)
                Screen.Search -> SearchScreen(vm)
                is Screen.Settings -> SettingsScreen(vm)
                is Screen.GameDetail -> GameScreen(vm, screen.gameId)
                is Screen.TournamentDetail -> TournamentScreen(vm, screen.tournamentId)
                is Screen.Team -> TeamScreen(vm, screen.leagueKey, screen.teamId)
                is Screen.ChannelDetail -> ChannelScreen(vm, screen.channelId)
                Screen.Player -> PlayerScreen(vm)
                Screen.Multiview -> MultiviewScreen(vm)
                Screen.MultiviewBuilder -> MultiviewBuilderScreen(vm)
            }
        }

        vm.dialog?.let { ActionSheet(vm, it) }

        AnimatedVisibility(
            visible = vm.message != null && !LocalInPip.current,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (screen == Screen.Main) 150.dp else 24.dp, start = 24.dp, end = 24.dp),
        ) {
            Text(
                vm.message.orEmpty(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .background(AppColors.Text, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
