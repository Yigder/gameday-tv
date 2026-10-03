package com.gameday.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.AppAccount
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

/** The app's wordmark: a "GD" scoreboard tile and "GameDay TV". */
@Composable
fun Logo(size: Dp = 26.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(size).background(Brush.linearGradient(listOf(Color(0xFFFF5A1F), AppColors.Live)), RoundedCornerShape(size * 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("GD", fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = (-0.5).sp)
        }
        Spacer(Modifier.width(size * 0.35f))
        Text("GameDay", fontSize = (size.value * 0.85f).sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        Text(" TV", fontSize = (size.value * 0.85f).sp, fontWeight = FontWeight.Normal, color = AppColors.TextDim)
    }
}

/** Big two-column layout used by the account screens: copy on the left, the form on the right. */
@Composable
private fun AuthLayout(title: String, subtitle: String, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF1A0A0D), AppColors.Background, AppColors.Background)))
            .padding(horizontal = 64.dp, vertical = 40.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Logo(30.dp)
            Spacer(Modifier.height(28.dp))
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Medium, lineHeight = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text(subtitle, fontSize = 15.sp, color = AppColors.TextDim, lineHeight = 21.sp)
        }
        Spacer(Modifier.width(56.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) { content() }
    }
}

@Composable
fun WelcomeScreen(vm: AppViewModel) {
    val accounts = remember { vm.accounts }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(200) }

    AuthLayout(
        title = "Live sports and TV,\nthe way you like it",
        subtitle = "Live scores for every game, your IPTV channels, a full program guide, recordings, Multiview and movies — together in one place. " +
            "Create a free GameDay TV account to save your teams, library and profiles.",
    ) {
        if (accounts.isNotEmpty()) {
            Text("Choose an account", fontSize = 16.sp, color = AppColors.TextDim)
            Spacer(Modifier.height(10.dp))
            accounts.forEachIndexed { i, a ->
                AccountRow(a, { vm.navigate(Screen.SignIn(a.email)) }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Use another account", { vm.navigate(Screen.SignIn()) })
                PillButton("Create account", { vm.navigate(Screen.CreateAccount) })
            }
        } else {
            PillButton("Create account", { vm.navigate(Screen.CreateAccount) }, Modifier.focusRequester(first), primary = true)
            Spacer(Modifier.height(12.dp))
            PillButton("Sign in", { vm.navigate(Screen.SignIn()) })
            Spacer(Modifier.height(24.dp))
            Text(
                "Your account lives on this TV. Passwords are stored as secure hashes, and your IPTV login is encrypted.",
                fontSize = 12.sp,
                color = AppColors.TextFaint,
            )
        }
    }
}

@Composable
private fun AccountRow(a: AppAccount, onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), containerColor = Color(0x14FFFFFF)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(AppColors.avatar(a.email.hashCode()), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                Text(a.name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(a.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(a.email, fontSize = 13.sp, color = AppColors.TextDim)
            }
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
    val emailFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (presetEmail.isBlank()) emailFocus else passFocus).requestFocusSafely(200) }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = vm.signIn(email, password)
            busy = false
        }
    }

    AuthLayout(
        title = "Sign in",
        subtitle = "Sign in to your GameDay TV account to get your profiles, teams, library and TV provider.",
    ) {
        FormError(error)
        TvTextField(email, { email = it }, Modifier.focusRequester(emailFocus), label = "Email", keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(14.dp))
        TvTextField(password, { password = it }, Modifier.focusRequester(passFocus), label = "Password", password = true, imeAction = ImeAction.Done, onSubmit = ::submit)

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(if (busy) "Signing in…" else "Sign in", ::submit, primary = true)
            PillButton("Forgot password?", {
                vm.showDialog(
                    AppDialog(
                        title = "Forgot your password?",
                        message = "GameDay TV accounts are stored on this TV, so there's no email reset. " +
                            "You can create a new account, and set up your provider and teams again.",
                        actions = listOf(
                            DialogAction("Create a new account", onClick = { vm.dismissDialog(); vm.replace(Screen.CreateAccount) }),
                            DialogAction("Back", onClick = { vm.dismissDialog() }),
                        ),
                    ),
                )
            })
        }
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
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(200) }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = vm.createAccount(name, email, password, confirm)
            busy = false
        }
    }

    AuthLayout(
        title = "Create your account",
        subtitle = "One account for the whole household. Add up to 6 profiles so everyone gets their own teams, library and recommendations.\n\n" +
            "Next, you'll connect your IPTV provider and pick your sports.",
    ) {
        FormError(error)
        TvTextField(name, { name = it }, Modifier.focusRequester(first), label = "Name", placeholder = "First and last name")
        Spacer(Modifier.height(10.dp))
        TvTextField(email, { email = it }, label = "Email", keyboardType = KeyboardType.Email, placeholder = "you@example.com")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(password, { password = it }, Modifier.weight(1f), label = "Password", password = true, placeholder = "8+ characters")
            TvTextField(confirm, { confirm = it }, Modifier.weight(1f), label = "Confirm password", password = true, imeAction = ImeAction.Done, onSubmit = ::submit)
        }

        Spacer(Modifier.height(18.dp))
        PillButton(if (busy) "Creating account…" else "Create account", ::submit, primary = true)
        Spacer(Modifier.height(14.dp))
        Text(
            "Stored only on this TV. GameDay TV doesn't send your account anywhere.",
            fontSize = 12.sp,
            color = AppColors.TextFaint,
            textAlign = TextAlign.Start,
        )
    }
}

/** Errors sit above the form: the TV keyboard covers the bottom half of the screen. */
@Composable
fun FormError(error: String?) {
    if (error == null) return
    Text(
        error,
        fontSize = 14.sp,
        color = Color.White,
        modifier = Modifier.fillMaxWidth().background(Color(0xCC8E0F1F), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
    )
    Spacer(Modifier.height(12.dp))
}