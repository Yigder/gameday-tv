package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Http
import com.gameday.tv.data.IptvAccount
import com.gameday.tv.data.ScoreBugMode
import com.gameday.tv.data.StreamFormat
import com.gameday.tv.data.XtreamSource
import com.gameday.tv.ui.theme.AppColors
import java.text.NumberFormat

@Composable
fun AccountScreen(vm: AppViewModel) {
    val account = vm.settings.account
    val info = vm.accountInfo
    var ua by rememberSaveable { mutableStateOf(vm.userAgent) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusSafely(120) }

    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp)) {
        Text("Account & Settings", fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxSize()) {
            // ---- account details ----
            Column(
                Modifier.weight(1f).background(AppColors.Surface, RoundedCornerShape(16.dp)).padding(22.dp),
            ) {
                Text("IPTV Account", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                when (account) {
                    null -> Text("Not connected — the app is in scores-only mode.", color = AppColors.TextDim, fontSize = 14.sp)
                    is IptvAccount.Xtream -> {
                        InfoLine("Type", "Xtream Codes")
                        InfoLine("Server", XtreamSource.normalizeServer(account.server))
                        InfoLine("Username", account.username)
                    }
                    is IptvAccount.M3u -> {
                        InfoLine("Type", "M3U playlist")
                        InfoLine("Playlist", account.url.substringBefore('?') + if ('?' in account.url) "?…" else "")
                    }
                }
                info?.status?.let { InfoLine("Status", it) }
                info?.expiresAtMillis?.let { InfoLine("Expires", formatDate(it)) }
                if (info?.maxConnections != null) {
                    InfoLine("Connections", "${info.activeConnections ?: "?"} active / ${info.maxConnections} max")
                }
                when (val s = vm.iptv) {
                    is IptvStatus.Ready -> {
                        val nf = NumberFormat.getIntegerInstance()
                        InfoLine("Channels", nf.format(s.catalog.channels.size))
                        InfoLine("Categories", "${nf.format(s.catalog.groups.size)} (${s.catalog.sportsGroups.size} sports)")
                    }
                    IptvStatus.Loading -> InfoLine("Channels", "Loading…")
                    is IptvStatus.Failed -> Text(s.message, color = AppColors.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    IptvStatus.NotConfigured -> Unit
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (account != null) {
                        ActionButton("Reload channels", { vm.reloadChannels() }, Modifier.focusRequester(firstFocus), primary = true)
                        ActionButton("Change login", { vm.navigate(Screen.Login) })
                        ActionButton("Sign out", { vm.logout() })
                    } else {
                        ActionButton("Connect IPTV", { vm.navigate(Screen.Login) }, Modifier.focusRequester(firstFocus), primary = true)
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            // ---- playback settings ----
            Column(
                Modifier
                    .weight(1f)
                    .background(AppColors.Surface, RoundedCornerShape(16.dp))
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Playback", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Score bug while watching", fontSize = 13.sp, color = AppColors.TextDim)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScoreBugMode.entries.forEach { m ->
                        Chip(m.label, vm.scoreBugMode == m, { vm.updateScoreBugMode(m) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "\"Show on button press\" hides the score a few seconds after a stream starts. Press OK or Info on the remote to bring it back.",
                    fontSize = 12.sp,
                    color = AppColors.TextDim,
                )
                Spacer(Modifier.height(10.dp))
                Chip(
                    if (vm.scoreAlerts) "✓ Score alerts on" else "Score alerts off",
                    vm.scoreAlerts,
                    { vm.updateScoreAlerts(!vm.scoreAlerts) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pops the score up when it changes, and alerts you when one of your teams scores.",
                    fontSize = 12.sp,
                    color = AppColors.TextDim,
                )
                Spacer(Modifier.height(18.dp))
                Text("Stream format (Xtream)", fontSize = 13.sp, color = AppColors.TextDim)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StreamFormat.entries.forEach { f ->
                        Chip(f.label, vm.streamFormat == f, { vm.updateStreamFormat(f) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "MPEG-TS starts fastest on most providers. If a channel won't play, the app automatically tries the other format.",
                    fontSize = 12.sp,
                    color = AppColors.TextDim,
                )
                Spacer(Modifier.height(18.dp))
                TvTextField(
                    ua, { ua = it },
                    label = "User-Agent",
                    placeholder = Http.DEFAULT_USER_AGENT,
                    onSubmit = { vm.updateUserAgent(ua) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Some providers only allow specific player User-Agents. Change this only if your provider tells you to.",
                    fontSize = 12.sp,
                    color = AppColors.TextDim,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Save User-Agent", { vm.updateUserAgent(ua) })
                    ActionButton("Reset", { ua = Http.DEFAULT_USER_AGENT; vm.updateUserAgent(ua) })
                }
                Spacer(Modifier.height(18.dp))
                Text("Scores data: ESPN public scoreboards, refreshed every 15 seconds while games are live.", fontSize = 12.sp, color = AppColors.TextDim)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 14.sp, color = AppColors.TextDim, modifier = Modifier.width(120.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
    }
}
