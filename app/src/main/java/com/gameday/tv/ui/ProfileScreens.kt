package com.gameday.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gameday.tv.data.AccountStore
import com.gameday.tv.data.Profile
import com.gameday.tv.ui.theme.AppColors

/** "Who's watching?" — or, with [manage], tap a profile to edit it. */
@Composable
fun ProfilesScreen(vm: AppViewModel, manage: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(200) }
    val current = vm.profile

    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Logo(22.dp)
        Spacer(Modifier.height(30.dp))
        Text(if (manage) "Manage profiles" else "Who's watching?", fontSize = 32.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(vm.account?.email.orEmpty(), fontSize = 14.sp, color = AppColors.TextDim)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            val preferred = vm.profiles.firstOrNull { it.id == current?.id } ?: vm.profiles.firstOrNull()
            vm.profiles.forEach { p ->
                ProfileTile(
                    profile = p,
                    editing = manage,
                    onClick = { if (manage) vm.navigate(Screen.ProfileEdit(p.id)) else vm.selectProfile(p) },
                    modifier = if (p == preferred) Modifier.focusRequester(first) else Modifier,
                )
            }
            if (vm.profiles.size < AccountStore.MAX_PROFILES) {
                AddProfileTile { vm.navigate(Screen.ProfileEdit(null)) }
            }
        }
        Spacer(Modifier.height(40.dp))
        if (manage) {
            PillButton("Done", { vm.back() }, primary = true)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Manage profiles", { vm.navigate(Screen.Profiles(manage = true)) }, icon = Icons.Edit)
                PillButton("Sign out", { vm.signOut() }, icon = Icons.Logout)
            }
        }
    }
}

@Composable
private fun ProfileTile(profile: Profile, editing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
        CircleButton(onClick, modifier.onFocusChanged { focused = it.isFocused }, 108.dp) {
            Avatar(profile, 108.dp)
            if (editing) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Edit, "Edit", Modifier.size(36.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            profile.name,
            fontSize = 16.sp,
            color = if (focused) AppColors.Text else AppColors.TextDim,
            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
        CircleButton(onClick, Modifier.onFocusChanged { focused = it.isFocused }, 108.dp) {
            Box(Modifier.fillMaxSize().background(Color(0x26FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Add, "Add profile", Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Add profile", fontSize = 16.sp, color = if (focused) AppColors.Text else AppColors.TextDim)
    }
}

@Composable
private fun CircleButton(onClick: () -> Unit, modifier: Modifier, size: Dp, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, pressedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = CircleShape)),
    ) { Box(Modifier.fillMaxSize()) { content() } }
}

@Composable
fun ProfileEditScreen(vm: AppViewModel, profileId: String?) {
    val existing = vm.profiles.firstOrNull { it.id == profileId }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable { mutableIntStateOf(existing?.color ?: vm.profiles.size) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(200) }

    fun save() {
        error = vm.saveProfile(profileId, name, color)
        if (error == null) vm.back()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 120.dp, vertical = 40.dp), verticalArrangement = Arrangement.Center) {
        Text(if (existing == null) "Add a profile" else "Edit profile", fontSize = 30.sp, fontWeight = FontWeight.Medium)
        Text("Each profile has its own teams, library, history and settings.", fontSize = 14.sp, color = AppColors.TextDim)
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(Profile("", name.ifBlank { "?" }, color), 96.dp)
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                TvTextField(name, { name = it }, Modifier.focusRequester(first), label = "Name", imeAction = ImeAction.Done, onSubmit = ::save)
                Spacer(Modifier.height(16.dp))
                Text("Color", fontSize = 13.sp, color = AppColors.TextDim)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppColors.Avatars.indices.forEach { i ->
                        CircleButton({ color = i }, Modifier, 40.dp) {
                            Box(Modifier.fillMaxSize().background(AppColors.avatar(i), CircleShape), contentAlignment = Alignment.Center) {
                                if (i == Math.floorMod(color, AppColors.Avatars.size)) Icon(Icons.Check, null, Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = AppColors.Live, fontSize = 14.sp)
        }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Save", ::save, primary = true)
            PillButton("Cancel", { vm.back() })
            if (existing != null && vm.profiles.size > 1) {
                PillButton("Delete profile", {
                    vm.confirm("Delete ${existing.name}?", "This removes the profile's teams, library and history from this account.", "Delete") {
                        vm.deleteProfile(existing.id)
                        vm.back()
                    }
                }, icon = Icons.Delete)
            }
        }
    }
}
