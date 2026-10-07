package com.gameday.tv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameday.tv.data.AccountStore
import com.gameday.tv.data.Profile
import com.gameday.tv.ui.AppDialog
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.Avatar
import com.gameday.tv.ui.DialogAction
import com.gameday.tv.ui.Icons
import com.gameday.tv.ui.Logo
import com.gameday.tv.ui.Screen
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

/** A text box for forms, with the right keyboard and an Enter action. */
@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = AppColors.TextFaint) } },
        singleLine = true,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) ({
            IconButton(onClick = { visible = !visible }) { AppIcon(if (visible) Icons.Close else Icons.Info, if (visible) "Hide password" else "Show password", tint = AppColors.TextDim) }
        }) else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }, onSearch = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun FormErrorText(error: String?) {
    if (error == null) return
    Text(
        error,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(Color(0xFF5F2120), RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

/** Scrolling single-column page for the sign-in and setup screens, centered on tablets. */
@Composable
fun FormPage(underBar: Boolean = false, content: @Composable () -> Unit) {
    // Under a top bar, the bar already keeps clear of the status bar.
    val insets = if (underBar) Modifier.navigationBarsPadding() else Modifier.safeDrawingPadding()
    Box(Modifier.fillMaxSize().then(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) { content() }
    }
}

@Composable
private fun AuthHeader(title: String, subtitle: String) {
    Logo(22.dp)
    Spacer(Modifier.height(28.dp))
    Text(title, fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp)
    Spacer(Modifier.height(10.dp))
    Text(subtitle, fontSize = 15.sp, color = AppColors.TextDim, lineHeight = 21.sp)
    Spacer(Modifier.height(28.dp))
}

@Composable
fun WelcomeScreen(vm: AppViewModel) {
    val accounts = remember { vm.accounts }
    FormPage {
        AuthHeader(
            title = "Live sports and TV,\nthe way you like it",
            subtitle = "Live scores for every game, your IPTV channels, a program guide, recordings and Multiview, together in one place. " +
                "Create a free GameDay account to save your teams, library and profiles.",
        )
        if (accounts.isNotEmpty()) {
            Text("Choose an account", fontSize = 15.sp, color = AppColors.TextDim)
            Spacer(Modifier.height(10.dp))
            accounts.forEach { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x14FFFFFF))
                        .clickable { vm.navigate(Screen.SignIn(a.email)) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).background(AppColors.avatar(a.email.hashCode()), CircleShape), contentAlignment = Alignment.Center) {
                        Text(a.name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(a.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(a.email, fontSize = 13.sp, color = AppColors.TextDim)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { vm.navigate(Screen.SignIn()) }, Modifier.fillMaxWidth()) { Text("Use another account") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.navigate(Screen.CreateAccount) }, Modifier.fillMaxWidth()) { Text("Create account") }
        } else {
            Button(onClick = { vm.navigate(Screen.CreateAccount) }, Modifier.fillMaxWidth()) { Text("Create account") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { vm.navigate(Screen.SignIn()) }, Modifier.fillMaxWidth()) { Text("Sign in") }
            Spacer(Modifier.height(24.dp))
            Text(
                "Your account lives on this device. Passwords are stored as secure hashes, and your IPTV login is encrypted.",
                fontSize = 12.sp,
                color = AppColors.TextFaint,
            )
        }
    }
}

@Composable
fun SignInScreen(vm: AppViewModel, presetEmail: String) {
    var email by rememberSaveable { mutableStateOf(presetEmail) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = vm.signIn(email, password)
            busy = false
        }
    }

    FormPage {
        AuthHeader("Sign in", "Sign in to your GameDay account to get your profiles, teams, library and TV provider.")
        FormErrorText(error)
        FormField(email, { email = it }, "Email", keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        FormField(password, { password = it }, "Password", password = true, imeAction = ImeAction.Done, onDone = ::submit)
        Spacer(Modifier.height(20.dp))
        Button(onClick = ::submit, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Signing in…" else "Sign in") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            vm.showDialog(
                AppDialog(
                    title = "Forgot your password?",
                    message = "GameDay accounts are stored on this device, so there's no email reset. " +
                        "You can create a new account, and set up your provider and teams again.",
                    actions = listOf(
                        DialogAction("Create a new account", onClick = { vm.dismissDialog(); vm.replace(Screen.CreateAccount) }),
                        DialogAction("Back", onClick = { vm.dismissDialog() }),
                    ),
                ),
            )
        }, Modifier.fillMaxWidth()) { Text("Forgot password?", color = AppColors.TextDim) }
    }
}

@Composable
fun CreateAccountScreen(vm: AppViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = vm.createAccount(name, email, password, confirm)
            busy = false
        }
    }

    FormPage {
        AuthHeader(
            "Create your account",
            "One account for the whole household, with up to 6 profiles. Next, you'll connect your IPTV provider and pick your sports.",
        )
        FormErrorText(error)
        FormField(name, { name = it }, "Name", placeholder = "First and last name")
        Spacer(Modifier.height(10.dp))
        FormField(email, { email = it }, "Email", placeholder = "you@example.com", keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(10.dp))
        FormField(password, { password = it }, "Password", placeholder = "8+ characters", password = true)
        Spacer(Modifier.height(10.dp))
        FormField(confirm, { confirm = it }, "Confirm password", password = true, imeAction = ImeAction.Done, onDone = ::submit)
        Spacer(Modifier.height(20.dp))
        Button(onClick = ::submit, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Creating account…" else "Create account") }
        Spacer(Modifier.height(14.dp))
        Text("Stored only on this device. GameDay doesn't send your account anywhere.", fontSize = 12.sp, color = AppColors.TextFaint)
    }
}

// ---------------------------------------------------------------------------------------------
// Profiles
// ---------------------------------------------------------------------------------------------

/** "Who's watching?" Tap a profile to use it, or its pencil to edit it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(vm: AppViewModel) {
    FormPage {
        Logo(22.dp)
        Spacer(Modifier.height(28.dp))
        Text("Who's watching?", fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(vm.account?.email.orEmpty(), fontSize = 14.sp, color = AppColors.TextDim, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            vm.profiles.forEach { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                    Box {
                        Avatar(p, 88.dp, Modifier.clip(CircleShape).clickable { vm.selectProfile(p) })
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(30.dp).clip(CircleShape).background(AppColors.Raised)
                                .clickable { vm.navigate(Screen.ProfileEdit(p.id)) },
                            contentAlignment = Alignment.Center,
                        ) { AppIcon(Icons.Edit, "Edit ${p.name}", size = 16.dp) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(p.name, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
            if (vm.profiles.size < AccountStore.MAX_PROFILES) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                    Box(
                        Modifier.size(88.dp).clip(CircleShape).background(Color(0x26FFFFFF)).clickable { vm.navigate(Screen.ProfileEdit(null)) },
                        contentAlignment = Alignment.Center,
                    ) { AppIcon(Icons.Add, "Add profile", size = 36.dp) }
                    Spacer(Modifier.height(8.dp))
                    Text("Add profile", fontSize = 15.sp, color = AppColors.TextDim)
                }
            }
        }
        Spacer(Modifier.height(36.dp))
        OutlinedButton(onClick = { vm.signOut() }, Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
fun ProfileEditScreen(vm: AppViewModel, profileId: String?) {
    val existing = vm.profiles.firstOrNull { it.id == profileId }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable { mutableIntStateOf(existing?.color ?: vm.profiles.size) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        error = vm.saveProfile(profileId, name, color)
        if (error == null) vm.back()
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(vm, if (existing == null) "Add a profile" else "Edit profile")
        FormPage(underBar = true) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Avatar(Profile("", name.ifBlank { "?" }, color), 96.dp) }
            Spacer(Modifier.height(20.dp))
            Text("Each profile has its own teams, library, history and settings.", fontSize = 14.sp, color = AppColors.TextDim)
            Spacer(Modifier.height(16.dp))
            FormErrorText(error)
            FormField(name, { name = it }, "Name", imeAction = ImeAction.Done, onDone = ::save)
            Spacer(Modifier.height(16.dp))
            Text("Color", fontSize = 13.sp, color = AppColors.TextDim)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppColors.Avatars.indices.forEach { i ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(AppColors.avatar(i)).clickable { color = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (i == Math.floorMod(color, AppColors.Avatars.size)) AppIcon(Icons.Check, null, size = 20.dp)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = ::save, Modifier.fillMaxWidth()) { Text("Save") }
            if (existing != null && vm.profiles.size > 1) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    vm.confirm("Delete ${existing.name}?", "This removes the profile's teams, library and history from this account.", "Delete") {
                        vm.deleteProfile(existing.id)
                        vm.back()
                    }
                }, Modifier.fillMaxWidth()) { Text("Delete profile") }
            }
        }
    }
}
