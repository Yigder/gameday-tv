package com.gameday.tv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

/** Home / Sports / Live / Library with the YouTube TV-style top bar. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MainShell(vm: AppViewModel, stateHolder: SaveableStateHolder) {
    val tab = vm.tab
    val context = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }
    val tabFocus = remember { Tab.entries.associateWith { FocusRequester() } }
    // If nothing in the content claims focus (e.g. back from a screen opened from the top bar), use the tab.
    val shellFocus = remember { FocusTracker() }
    LaunchedEffect(Unit) {
        delay(900)
        if (!shellFocus.has) tabFocus.getValue(vm.tab).requestFocusSafely(0)
    }

    BackHandler(enabled = vm.dialog == null) {
        when {
            tab != Tab.HOME -> {
                vm.selectTab(Tab.HOME)
            }
            System.currentTimeMillis() - lastBack < 2_500 -> (context as? Activity)?.finish()
            else -> {
                lastBack = System.currentTimeMillis()
                vm.showMessage("Press back again to exit")
            }
        }
    }

    Box(Modifier.fillMaxSize().trackFocus(shellFocus)) {
        stateHolder.SaveableStateProvider("main:$tab") {
            when (tab) {
                Tab.HOME -> HomeTab(vm)
                Tab.SPORTS -> SportsTab(vm)
                Tab.LIVE -> LiveTab(vm)
                Tab.LIBRARY -> LibraryTab(vm)
            }
        }

        // ---- top bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xF00F0F0F), Color(0x000F0F0F))))
                .padding(start = 48.dp, end = 40.dp, top = 14.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(18.dp)
            Spacer(Modifier.width(28.dp))
            Row(
                Modifier.focusRestorer(tabFocus.getValue(tab)),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tab.entries.forEach { t ->
                    TabButton(
                        label = t.label,
                        selected = t == tab,
                        onFocused = { if (t != vm.tab) vm.selectTab(t, focusContent = false) },
                        onClick = { vm.selectTab(t) },
                        modifier = Modifier.focusRequester(tabFocus.getValue(t)),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TopIconButton(Icons.Search, "Search") { vm.navigate(Screen.Search) }
            Spacer(Modifier.width(10.dp))
            TopIconButton(Icons.Settings, "Settings") { vm.openSettings() }
            Spacer(Modifier.width(10.dp))
            ProfileButton(vm)
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    // Like YouTube TV, resting on a tab opens it.
    LaunchedEffect(focused) {
        if (focused && !selected) {
            delay(350)
            onFocused()
        }
    }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) AppColors.Text else AppColors.TextDim,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (selected && !focused) {
                Box(Modifier.align(Alignment.BottomCenter).padding(top = 26.dp).width(18.dp).height(2.dp).background(AppColors.Text))
            }
        }
    }
}

@Composable
private fun TopIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = AppColors.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(22.dp)) }
    }
}

@Composable
private fun ProfileButton(vm: AppViewModel) {
    Surface(
        onClick = { profileMenu(vm) },
        modifier = Modifier.size(38.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, pressedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape)),
    ) {
        Avatar(vm.profile, 38.dp)
    }
}

private fun profileMenu(vm: AppViewModel) {
    val p = vm.profile
    vm.showDialog(
        AppDialog(
            title = p?.name ?: "Profile",
            subtitle = vm.account?.email,
            actions = listOfNotNull(
                DialogAction("Switch profile", Icons.Person) { vm.dismissDialog(); vm.switchProfile() },
                DialogAction("Your library", Icons.Library) { vm.dismissDialog(); vm.selectTab(Tab.LIBRARY) },
                DialogAction("Settings", Icons.Settings) { vm.dismissDialog(); vm.openSettings() },
                DialogAction("Sign out", Icons.Logout) { vm.dismissDialog(); vm.signOut() },
            ),
        ),
    )
}
