package com.gameday.tv.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gameday.tv.BuildConfig
import com.gameday.tv.data.IptvAccount
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.ProviderEntry
import com.gameday.tv.data.ScoreBugMode
import com.gameday.tv.data.XtreamSource
import com.gameday.tv.ui.AppDialog
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.DecoderSlot
import com.gameday.tv.ui.DialogAction
import com.gameday.tv.ui.Icons
import com.gameday.tv.ui.IptvStatus
import com.gameday.tv.ui.SCORE_DELAY_RANGE
import com.gameday.tv.ui.SCORE_DELAY_STEP
import com.gameday.tv.ui.Screen
import com.gameday.tv.ui.bytesText
import com.gameday.tv.ui.delayLabel
import com.gameday.tv.ui.formatDate
import com.gameday.tv.ui.sportName
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Every setting on one scrolling page, grouped like the TV's Settings sections. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        BackBar(vm, "Settings")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            accountSection(vm)
            profilesSection(vm)
            providerSection(vm)
            sportsSection(vm)
            guideSection(vm)
            playbackSection(vm)
            dvrSection(vm)
            aboutSection(vm)
            item(key = "nav-pad") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

private fun LazyListScope.header(text: String, sub: String? = null) {
    item(key = "h:$text") {
        Column(Modifier.padding(start = GUTTER, end = GUTTER, top = 24.dp, bottom = 4.dp)) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.Live)
            if (sub != null) Text(sub, fontSize = 12.sp, color = AppColors.TextFaint)
        }
    }
}

private fun LazyListScope.note(key: String, text: String) {
    item(key = "n:$key") { Text(text, fontSize = 12.sp, color = AppColors.TextFaint, modifier = Modifier.padding(horizontal = GUTTER, vertical = 6.dp)) }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.accountSection(vm: AppViewModel) {
    val acct = vm.account ?: return
    header("Account")
    item(key = "acct") { SettingItem(acct.name, {}, subtitle = "${acct.email} · Member since ${formatDate(acct.createdAt)}", icon = Icons.Person) }
    item(key = "password") { ChangePassword(vm) }
    item(key = "signout") { SettingItem("Sign out", { vm.signOut() }, subtitle = "Your account stays on this device", icon = Icons.Logout) }
    item(key = "delete") { DeleteAccount(vm) }
}

@Composable
private fun ChangePassword(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(vm.account?.name.orEmpty()) }
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (!open) {
        SettingItem("Name and password", { open = true }, subtitle = "Change your name or password", icon = Icons.Edit, chevron = true)
        return
    }
    Column(Modifier.padding(horizontal = GUTTER, vertical = 8.dp)) {
        FormField(name, { name = it }, "Name", imeAction = ImeAction.Done, onDone = { vm.renameAccount(name); vm.showMessage("Name saved") })
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.renameAccount(name); vm.showMessage("Name saved") }) { Text("Save name") }
        Spacer(Modifier.height(16.dp))
        FormErrorText(error)
        FormField(current, { current = it }, "Current password", password = true)
        Spacer(Modifier.height(8.dp))
        FormField(new, { new = it }, "New password", password = true)
        Spacer(Modifier.height(8.dp))
        FormField(confirm, { confirm = it }, "Confirm new password", password = true, imeAction = ImeAction.Done)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                scope.launch {
                    error = vm.changePassword(current, new, confirm)
                    if (error == null) { current = ""; new = ""; confirm = ""; open = false }
                }
            }) { Text("Change password") }
            OutlinedButton(onClick = { open = false }) { Text("Close") }
        }
    }
}

@Composable
private fun DeleteAccount(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (!open) {
        SettingItem("Delete account", { open = true }, subtitle = "Removes the account, its profiles, library and recordings from this device", icon = Icons.Delete)
        return
    }
    Column(Modifier.padding(horizontal = GUTTER, vertical = 8.dp)) {
        Text("Enter your password to permanently delete this account.", fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        FormErrorText(error)
        FormField(password, { password = it }, "Password", password = true, imeAction = ImeAction.Done)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { scope.launch { error = vm.deleteAccount(password) } }) { Text("Delete forever") }
            OutlinedButton(onClick = { open = false; password = "" }) { Text("Cancel") }
        }
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.profilesSection(vm: AppViewModel) {
    header("Profiles", "Up to 6. Each has its own teams, library, history and viewing settings.")
    items(vm.profiles.toList(), key = { "p:" + it.id }) { p ->
        SettingItem(p.name + if (p.id == vm.profile?.id) "  (you)" else "", { vm.navigate(Screen.ProfileEdit(p.id)) },
            subtitle = "Edit name and color", icon = Icons.Person, chevron = true)
    }
    item(key = "p-add") { SettingItem("Add profile", { vm.navigate(Screen.ProfileEdit(null)) }, icon = Icons.Add) }
    item(key = "p-switch") { SettingItem("Switch profile", { vm.switchProfile() }, icon = Icons.Refresh) }
    item(key = "p-ask") {
        SettingItem("Ask who's watching", { vm.updateAskWhoIsWatching(!vm.askWhoIsWatching) },
            subtitle = "Show the profile choice when the app opens", checked = vm.askWhoIsWatching)
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.providerSection(vm: AppViewModel) {
    header("TV providers", "Add more than one login or playlist: their channels and guides are combined.")
    if (vm.providers.isEmpty()) note("none", "No provider connected. GameDay shows live scores, but you'll need a provider to watch.")
    val nf = NumberFormat.getIntegerInstance()
    items(vm.providers.toList(), key = { "pv:" + it.id }) { p ->
        val status = vm.providerStatus[p.id]
        val info = vm.providerInfo[p.id]
        val detail = listOfNotNull(
            when (val a = p.account) {
                is IptvAccount.Xtream -> "Xtream Codes · ${a.username}"
                is IptvAccount.M3u -> "M3U playlist"
            },
            when {
                !p.enabled -> "Turned off"
                status is IptvStatus.Ready -> "${nf.format(status.catalog.channels.size)} channels"
                status is IptvStatus.Loading -> "Loading…"
                status is IptvStatus.Failed -> status.message
                else -> null
            },
            info?.expiresAtMillis?.let { "Expires ${formatDate(it)}" },
            info?.maxConnections?.let { "${info.activeConnections ?: "?"}/$it streams in use" },
        ).joinToString(" · ")
        SettingItem(p.name, { providerMenu(vm, p) }, subtitle = detail, icon = if (status is IptvStatus.Failed) Icons.Info else Icons.Tv, chevron = true)
    }
    item(key = "pv-add") { SettingItem(if (vm.hasProvider) "Add another provider" else "Connect a provider", { vm.navigate(Screen.Provider()) }, icon = Icons.Add, chevron = true) }
    if (vm.hasProvider) item(key = "pv-reload") { SettingItem("Reload channels", { vm.reloadChannels() }, subtitle = "Get the latest lineups and guides", icon = Icons.Refresh) }
}

private fun providerMenu(vm: AppViewModel, p: ProviderEntry) {
    vm.showDialog(
        AppDialog(
            title = p.name,
            subtitle = when (val a = p.account) {
                is IptvAccount.Xtream -> XtreamSource.normalizeServer(a.server)
                is IptvAccount.M3u -> a.url.substringBefore('?')
            },
            actions = listOf(
                DialogAction("Edit login", Icons.Edit) { vm.dismissDialog(); vm.navigate(Screen.Provider(p.id)) },
                DialogAction(if (p.enabled) "Turn off" else "Turn on", Icons.Tv) { vm.dismissDialog(); vm.setProviderEnabled(p.id, !p.enabled) },
                DialogAction("Remove", Icons.Delete) {
                    vm.dismissDialog()
                    vm.confirm("Remove ${p.name}?", "Its channels leave the guide. Recordings stay in your library.", "Remove") { vm.removeProvider(p.id) }
                },
            ),
        ),
    )
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.sportsSection(vm: AppViewModel) {
    header("Sports & scores")
    item(key = "hide") {
        SettingItem("Hide scores", { vm.updateHideScores(!vm.hideScores) },
            subtitle = "No scores on cards, game pages or alerts. Tap the trophy while watching to peek.", checked = vm.hideScores)
    }
    item(key = "bug") {
        SettingItem("Score bug", {
            vm.updateScoreBugMode(if (vm.scoreBugMode == ScoreBugMode.ON_PRESS) ScoreBugMode.ALWAYS else ScoreBugMode.ON_PRESS)
        }, subtitle = "\"Show on button press\" hides it after a few seconds; the trophy brings it back", value = vm.scoreBugMode.label)
    }
    item(key = "alerts") {
        SettingItem("Score alerts", { vm.updateScoreAlerts(!vm.scoreAlerts) }, subtitle = "Pop up the score when it changes, and when your teams score", checked = vm.scoreAlerts)
    }
    item(key = "delay") { ScoreDelay(vm) }
    header("Your teams", "Teams you add get a \"Your teams\" filter in Sports, alerts, and optional automatic recording.")
    items(vm.favorites.toList(), key = { "t:" + it.key }) { t ->
        SettingItem(t.name, { vm.setTeamRecording(t, !t.record) }, subtitle = "${Leagues.byKey(t.leagueKey)?.label ?: ""} · Record all games",
            checked = t.record, icon = Icons.Trophy)
    }
    item(key = "teams") { SettingItem("Add or remove teams", { vm.navigate(Screen.TeamsPicker) }, icon = Icons.Add, chevron = true) }
    header("Sports you follow")
    items(Leagues.picks, key = { "l:" + it.key }) { p ->
        val on = vm.isSportEnabled(p)
        SettingItem(p.label, { vm.setSportEnabled(p, !on) },
            subtitle = if (p.leagues.size > 1) p.leagues.joinToString(" & ") { it.label } else sportName(p.sport), checked = on)
    }
}

@Composable
private fun ScoreDelay(vm: AppViewModel) {
    var value by remember(vm.scoreDelaySec) { mutableFloatStateOf(vm.scoreDelaySec.toFloat()) }
    val steps = (SCORE_DELAY_RANGE.last - SCORE_DELAY_RANGE.first) / SCORE_DELAY_STEP - 1
    Column(Modifier.padding(horizontal = GUTTER, vertical = 8.dp)) {
        Row {
            Text("Score delay", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(delayLabel(value.roundToInt()), fontSize = 13.sp, color = AppColors.TextDim)
        }
        Text("IPTV runs behind the live broadcast. The score bug and alerts wait this long so they don't spoil plays.",
            fontSize = 12.sp, color = AppColors.TextDim, lineHeight = 16.sp)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { vm.updateScoreDelay((value / SCORE_DELAY_STEP).roundToInt() * SCORE_DELAY_STEP) },
            valueRange = SCORE_DELAY_RANGE.first.toFloat()..SCORE_DELAY_RANGE.last.toFloat(),
            steps = steps,
        )
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.guideSection(vm: AppViewModel) {
    header("Live guide")
    item(key = "sort") {
        SettingItem("Sort channels", { vm.updateGuideSort(if (vm.guideSort == "name") "number" else "name") },
            value = if (vm.guideSort == "name") "By name" else "By channel number")
    }
    val cat = vm.catalog ?: return
    item(key = "groups") { CategoryToggles(vm, cat) }
}

@Composable
private fun CategoryToggles(vm: AppViewModel, cat: com.gameday.tv.data.IptvCatalog) {
    var open by remember { mutableStateOf(false) }
    val shown = cat.groups.count { it !in vm.hiddenGroups }
    SettingItem("Categories in \"All channels\"", { open = !open }, subtitle = "Turn off categories you never watch", value = "$shown of ${cat.groups.size}")
    if (!open) return
    Row(Modifier.padding(horizontal = GUTTER, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.showAllGroups() }) { Text("Show all") }
        OutlinedButton(onClick = { vm.showAllGroups(cat.sportsGroups) }) { Text("Sports only") }
    }
    cat.groups.forEach { g ->
        val on = g !in vm.hiddenGroups
        SettingItem(g, { vm.setGroupHidden(g, on) }, subtitle = "${cat.byGroup[g]?.size ?: 0} channels", checked = on)
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.playbackSection(vm: AppViewModel) {
    header("Playback")
    item(key = "format") {
        SettingItem("Live stream format", { vm.updateStreamFormat(vm.streamFormat.other()) },
            subtitle = "MPEG-TS starts fastest on most providers. If a channel won't play, the other format is tried automatically.",
            value = vm.streamFormat.label)
    }
    item(key = "dec") {
        val mode = vm.decoderMode(DecoderSlot.PLAYER)
        SettingItem("Video decoding", { vm.setDecoderMode(DecoderSlot.PLAYER, mode.next()) },
            subtitle = "Hardware is smoothest and easiest on the battery. Software runs on the CPU.", value = mode.label)
    }
    item(key = "mini") {
        val modes = listOf("sound", "muted", "off")
        val next = modes[(modes.indexOf(vm.backgroundVideo).coerceAtLeast(0) + 1) % modes.size]
        SettingItem("Mini player", { vm.updateBackgroundVideo(next) },
            subtitle = "After you leave the player, what you were watching keeps playing above the tabs.",
            value = when (vm.backgroundVideo) {
                "sound" -> "With sound"
                "muted" -> "Muted"
                else -> "Off"
            })
    }
    item(key = "cc") { SettingItem("Subtitles", { vm.updateCaptions(!vm.captions) }, subtitle = "Turn on when the channel or recording has them", checked = vm.captions) }
    note("gestures", "While watching: tap for controls · swipe across the picture to change channel · double-tap the sides to skip in recordings · press and hold for Multiview.")
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.dvrSection(vm: AppViewModel) {
    header("Recordings", "Recordings are saved on this device.")
    item(key = "used") { SettingItem("Storage", {}, value = "${bytesText(vm.recordingsBytes())} used · ${bytesText(vm.recordingsFreeBytes())} free") }
    item(key = "cap") {
        val caps = listOf(5, 10, 20, 50, 100, 0)
        SettingItem("Space for recordings", { vm.updateDvr(capGb = caps[(caps.indexOf(vm.dvrCapGb) + 1) % caps.size]) },
            subtitle = "Oldest recordings are deleted to stay under this", value = if (vm.dvrCapGb == 0) "Until storage is full" else "${vm.dvrCapGb} GB")
    }
    item(key = "keep") {
        val days = listOf(7, 14, 30, 90, 0)
        SettingItem("Keep recordings", { vm.updateDvr(keepDays = days[(days.indexOf(vm.dvrKeepDays) + 1) % days.size]) },
            value = if (vm.dvrKeepDays == 0) "Until deleted" else "${vm.dvrKeepDays} days")
    }
    item(key = "pad") {
        val pads = listOf(0, 1, 2, 5, 10)
        SettingItem("Start early", { vm.updateDvr(paddingMin = pads[(pads.indexOf(vm.dvrPaddingMin) + 1) % pads.size]) },
            subtitle = "Also added to the end of shows from the guide. Games keep recording while they're live.",
            value = if (vm.dvrPaddingMin == 0) "On time" else "${vm.dvrPaddingMin} min")
    }
    item(key = "alarms") { ExactAlarmRow(vm) }
    item(key = "notif") { NotificationRow() }
    note("dvr", "Each recording uses one of your provider's streams, and needs the device to stay online.")
}

@Composable
private fun ExactAlarmRow(vm: AppViewModel) {
    val context = LocalContext.current
    val allowed = vm.canWakeExactly
    SettingItem(
        "Start recordings on time",
        {
            if (!allowed && Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }.onFailure { vm.showMessage("Open Android Settings › Apps › GameDay › Alarms & reminders") }
            }
        },
        subtitle = if (allowed) "Allowed: GameDay can wake up for scheduled recordings" else "Allow \"Alarms & reminders\" so scheduled recordings start exactly on time",
        value = if (allowed) "On" else "Off",
    )
}

/** Android 13+ asks before an app shows notifications; recordings show one while they run. */
@Composable
private fun NotificationRow() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    SettingItem(
        "Recording notifications",
        { if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        subtitle = "Shows when a recording is running, so you can stop it",
        value = if (granted) "On" else "Off",
    )
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.aboutSection(vm: AppViewModel) {
    header("About")
    item(key = "ver") { SettingItem("GameDay", {}, subtitle = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", icon = Icons.Info) }
    item(key = "src") { SettingItem("Scores and video", {}, subtitle = "ESPN public scoreboards, refreshed every 15 s while games are live. Video from your IPTV provider.") }
    item(key = "hist") { SettingItem("Clear watch & search history", { vm.clearHistory() }, subtitle = "For ${vm.profile?.name ?: "this profile"}", icon = Icons.Delete) }
    note("priv", "Your account, profiles and library are stored only on this device. Your provider login is encrypted with the Android Keystore and only sent to your provider.")
}
