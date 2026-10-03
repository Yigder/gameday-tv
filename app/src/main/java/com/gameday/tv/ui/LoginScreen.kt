package com.gameday.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.IptvAccount
import com.gameday.tv.data.XtreamSource
import com.gameday.tv.ui.theme.AppColors

@Composable
fun LoginScreen(vm: AppViewModel) {
    val existing = remember { vm.settings.account }
    var mode by rememberSaveable { mutableStateOf(if (existing is IptvAccount.M3u) "m3u" else "xtream") }
    var server by rememberSaveable { mutableStateOf((existing as? IptvAccount.Xtream)?.server.orEmpty()) }
    var username by rememberSaveable { mutableStateOf((existing as? IptvAccount.Xtream)?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf((existing as? IptvAccount.Xtream)?.password.orEmpty()) }
    var m3uUrl by rememberSaveable { mutableStateOf((existing as? IptvAccount.M3u)?.url.orEmpty()) }
    var submitted by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val status = vm.iptv
    val loading = submitted && status is IptvStatus.Loading
    val error = localError ?: (status as? IptvStatus.Failed)?.message?.takeIf { submitted }

    fun submit() {
        localError = null
        val account: IptvAccount = if (mode == "xtream") {
            if (server.isBlank() || username.isBlank() || password.isBlank()) {
                localError = "Enter the server address, username and password."
                return
            }
            IptvAccount.Xtream(server.trim(), username.trim(), password)
        } else {
            if (m3uUrl.isBlank()) {
                localError = "Enter your M3U playlist URL."
                return
            }
            XtreamSource.fromPlaylistUrl(m3uUrl) ?: IptvAccount.M3u(m3uUrl.trim())
        }
        submitted = true
        vm.connect(account, navigateHome = true)
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusSafely(120) }

    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        // ---- branding ----
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Row {
                Text("GameDay", fontSize = 44.sp, fontWeight = FontWeight.Black)
                Text(" TV", fontSize = 44.sp, fontWeight = FontWeight.Black, color = AppColors.Accent)
            }
            Spacer(Modifier.height(10.dp))
            Text("Live scores and your IPTV, together.", fontSize = 18.sp, color = AppColors.TextDim)
            Spacer(Modifier.height(28.dp))
            Feature("Live scores: NFL, college football, MLB, NBA, NHL, WNBA, soccer, PGA Tour and DP World Tour golf")
            Feature("Pick a game and jump straight to the channel airing it")
            Feature("Multiview: watch up to 4 channels at once")
            Feature("Works with Xtream Codes logins and M3U playlists")
            Spacer(Modifier.height(20.dp))
            Text(
                "Your login is stored only on this TV and is sent only to your IPTV provider.",
                fontSize = 12.sp,
                color = AppColors.TextDim,
            )
        }

        Spacer(Modifier.width(40.dp))

        // ---- form ----
        Column(
            Modifier.width(440.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Connect your IPTV", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Xtream Codes", mode == "xtream", { mode = "xtream"; localError = null }, Modifier.focusRequester(firstFocus))
                Chip("M3U Playlist URL", mode == "m3u", { mode = "m3u"; localError = null })
            }
            Spacer(Modifier.height(18.dp))

            if (mode == "xtream") {
                TvTextField(server, { server = it }, label = "Server URL", placeholder = "http://example.com:8080", keyboardType = KeyboardType.Uri)
                Spacer(Modifier.height(12.dp))
                TvTextField(username, { username = it }, label = "Username")
                Spacer(Modifier.height(12.dp))
                TvTextField(
                    password, { password = it },
                    label = "Password",
                    password = true,
                    imeAction = ImeAction.Done,
                    onSubmit = { submit() },
                )
            } else {
                TvTextField(
                    m3uUrl, { m3uUrl = it },
                    label = "Playlist URL",
                    placeholder = "http://provider.com/get.php?username=…",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onSubmit = { submit() },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Xtream-style get.php links are detected automatically for faster loading.",
                    fontSize = 12.sp,
                    color = AppColors.TextDim,
                )
            }

            Spacer(Modifier.height(18.dp))
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(24.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Signing in and loading channels… big lineups can take a minute.", fontSize = 13.sp, color = AppColors.TextDim)
                }
                error != null -> Text(error, fontSize = 14.sp, color = AppColors.Live)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ActionButton(if (loading) "Connecting…" else "Connect", { if (!loading) submit() }, primary = true)
                if (vm.canGoBack) {
                    ActionButton("Cancel", { vm.back() })
                } else {
                    ActionButton("Scores only", { vm.useScoresOnly() })
                }
            }
        }
    }
}

@Composable
private fun Feature(text: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = AppColors.Accent, fontSize = 10.sp)
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp)
    }
}
