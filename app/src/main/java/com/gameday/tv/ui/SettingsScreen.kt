package com.gameday.tv.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.BuildConfig
import com.gameday.tv.data.Http
import com.gameday.tv.data.IptvAccount
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.ScoreBugMode
import com.gameday.tv.data.XtreamSource
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Settings: a section list on the left and its options on the right (Android TV / YouTube TV style). */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val section = vm.settingsSection
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(150) }

    Row(Modifier.fillMaxSize().padding(top = 36.dp)) {
        Column(Modifier.width(280.dp).fillMaxHeight().padding(start = 40.dp, end = 12.dp)) {
            Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, bottom = 14.dp))
            // Left from the options returns to the open section, not the one that lines up.
            Column(Modifier.returnFocusTo(first)) {
                SettingsSection.entries.forEach { s ->
                    var focused by remember { mutableStateOf(false) }
                    LaunchedEffect(focused) { if (focused && vm.settingsSection != s) vm.settingsSection = s }
                    SettingRow(
                        title = s.label,
                        onClick = { vm.settingsSection = s },
                        icon = sectionIcon(s),
                        chevron = s == section && !focused,
                        modifier = Modifier
                            .onFocusChanged { focused = it.isFocused }
                            .then(if (s == section) Modifier.focusRequester(first) else Modifier),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(end = 48.dp)) {
            LazyColumn(contentPadding = PaddingValues(top = 52.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (section) {
                    SettingsSection.ACCOUNT -> accountSection(vm)
                    SettingsSection.PROFILES -> profilesSection(vm)
                    SettingsSection.PROVIDER -> providerSection(vm)
                    SettingsSection.SPORTS -> sportsSection(vm)
                    SettingsSection.GUIDE -> guideSection(vm)
                    SettingsSection.PLAYBACK -> playbackSection(vm)
                    SettingsSection.DVR -> dvrSection(vm)
                    SettingsSection.ABOUT -> aboutSection(vm)
                }
            }
        }
    }
}

private fun sectionIcon(s: SettingsSection) = when (s) {
    SettingsSection.ACCOUNT -> Icons.Person
    SettingsSection.PROFILES -> Icons.Edit
    SettingsSection.PROVIDER -> Icons.Tv
    SettingsSection.SPORTS -> Icons.Trophy
    SettingsSection.GUIDE -> Icons.Guide
    SettingsSection.PLAYBACK -> Icons.Play
    SettingsSection.DVR -> Icons.Record
    SettingsSection.ABOUT -> Icons.Info
}

private fun LazyListScope.header(text: String, sub: String? = null) {
    item(key = "h:$text") {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.TextDim)
            if (sub != null) Text(sub, fontSize = 12.sp, color = AppColors.TextFaint)
        }
    }
}

private fun LazyListScope.info(label: String, value: String) {
    item(key = "i:$label") {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(label, fontSize = 15.sp, color = AppColors.TextDim, modifier = Modifier.width(170.dp))
            Text(value, fontSize = 15.sp, maxLines = 2)
        }
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.accountSection(vm: AppViewModel) {
    val acct = vm.account ?: return
    header("Your account")
    info("Name", acct.name)
    info("Email", acct.email)
    info("Member since", formatDate(acct.createdAt))
    item(key = "rename") { RenameAccount(vm, acct.name) }
    header("Password")
    item(key = "password") { ChangePassword(vm) }
    header("Sign out & delete")
    item(key = "signout") { SettingRow("Sign out", { vm.signOut() }, subtitle = "Your account stays on this TV", icon = Icons.Logout) }
    item(key = "delete") { DeleteAccount(vm) }
}

@Composable
private fun RenameAccount(vm: AppViewModel, current: String) {
    var name by remember { mutableStateOf(current) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvTextField(name, { name = it }, Modifier.width(300.dp), placeholder = "Name", imeAction = ImeAction.Done, onSubmit = { vm.renameAccount(name) })
        PillButton("Save name", { vm.renameAccount(name); vm.showMessage("Name saved") })
    }
}

@Composable
private fun ChangePassword(vm: AppViewModel) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField(current, { current = it }, Modifier.weight(1f), placeholder = "Current password", password = true)
            TvTextField(new, { new = it }, Modifier.weight(1f), placeholder = "New password", password = true)
            TvTextField(confirm, { confirm = it }, Modifier.weight(1f), placeholder = "Confirm new", password = true, imeAction = ImeAction.Done)
        }
        error?.let { Text(it, color = AppColors.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(8.dp))
        PillButton("Change password", {
            scope.launch {
                error = vm.changePassword(current, new, confirm)
                if (error == null) { current = ""; new = ""; confirm = "" }
            }
        })
    }
}

@Composable
private fun DeleteAccount(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(open) { if (open) passwordFocus.requestFocusSafely(80) }
    if (!open) {
        SettingRow("Delete account", { open = true }, subtitle = "Removes the account, its profiles, library and recordings from this TV", icon = Icons.Delete)
        return
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Enter your password to permanently delete this account.", fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField(password, { password = it }, Modifier.width(280.dp).focusRequester(passwordFocus), placeholder = "Password", password = true, imeAction = ImeAction.Done)
            PillButton("Delete forever", { scope.launch { error = vm.deleteAccount(password) } })
            PillButton("Cancel", { open = false; password = "" })
        }
        error?.let { Text(it, color = AppColors.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.profilesSection(vm: AppViewModel) {
    header("Profiles on this account", "Up to 6. Each has its own teams, library, history and viewing settings.")
    items(vm.profiles.toList(), key = { "p:" + it.id }) { p ->
        SettingRow(
            title = p.name + if (p.id == vm.profile?.id) "  (you)" else "",
            onClick = { vm.navigate(Screen.ProfileEdit(p.id)) },
            subtitle = "Edit name and color",
            icon = Icons.Person,
            chevron = true,
        )
    }
    item(key = "add") { SettingRow("Add profile", { vm.navigate(Screen.ProfileEdit(null)) }, icon = Icons.Add) }
    item(key = "switch") { SettingRow("Switch profile", { vm.switchProfile() }, icon = Icons.Refresh) }
    header("When the app opens")
    item(key = "ask") {
        SettingRow("Ask who's watching", { vm.updateAskWhoIsWatching(!vm.askWhoIsWatching) },
            subtitle = "Show profile choice at start when there's more than one profile", checked = vm.askWhoIsWatching)
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.providerSection(vm: AppViewModel) {
    header("TV providers", "Add more than one login or playlist: their channels and guides are combined.")
    if (vm.providers.isEmpty()) {
        item(key = "none") {
            Text("No provider connected. GameDay TV shows live scores, but you'll need a provider to watch.", fontSize = 14.sp,
                color = AppColors.TextDim, modifier = Modifier.padding(16.dp))
        }
    }
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
        SettingRow(
            title = p.name,
            onClick = { providerMenu(vm, p) },
            subtitle = detail,
            icon = if (status is IptvStatus.Failed) Icons.Info else Icons.Tv,
            chevron = true,
        )
    }
    header("Manage")
    item(key = "add") { SettingRow(if (vm.hasProvider) "Add another provider" else "Connect a provider", { vm.navigate(Screen.Provider()) }, icon = Icons.Add, chevron = true) }
    if (vm.hasProvider) {
        item(key = "reload") { SettingRow("Reload channels", { vm.reloadChannels() }, subtitle = "Get the latest lineups and guides", icon = Icons.Refresh) }
    }
}

private fun providerMenu(vm: AppViewModel, p: com.gameday.tv.data.ProviderEntry) {
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
    header("Spoilers")
    item(key = "hide") {
        SettingRow("Hide scores", { vm.updateHideScores(!vm.hideScores) },
            subtitle = "No scores on cards, game pages or alerts. Press Info while watching to peek.", checked = vm.hideScores)
    }
    header("While watching")
    item(key = "bug") {
        SettingRow("Score bug", {
            vm.updateScoreBugMode(if (vm.scoreBugMode == ScoreBugMode.ON_PRESS) ScoreBugMode.ALWAYS else ScoreBugMode.ON_PRESS)
        }, subtitle = "Show on button press hides it after a few seconds; OK or Info brings it back", value = vm.scoreBugMode.label)
    }
    item(key = "alerts") {
        SettingRow("Score alerts", { vm.updateScoreAlerts(!vm.scoreAlerts) }, subtitle = "Pop up the score when it changes, and when your teams score", checked = vm.scoreAlerts)
    }
    item(key = "delay") {
        SliderRow("Score delay", vm.scoreDelaySec, SCORE_DELAY_RANGE, SCORE_DELAY_STEP, vm::updateScoreDelay,
            subtitle = "IPTV runs behind the live broadcast. The score bug and alerts wait this long so they don't spoil plays.",
            label = ::delayLabel)
    }
    header("Your teams", "Teams you add get a \"Your teams\" filter in Sports, alerts, and optional automatic recording.")
    items(vm.favorites.toList(), key = { "t:" + it.key }) { t ->
        SettingRow(
            title = t.name,
            onClick = { vm.setTeamRecording(t, !t.record) },
            subtitle = "${Leagues.byKey(t.leagueKey)?.label ?: ""} · Record all games",
            checked = t.record,
            icon = Icons.Trophy,
        )
    }
    item(key = "teams") { SettingRow("Add or remove teams", { vm.navigate(Screen.TeamsPicker) }, icon = Icons.Add, chevron = true) }
    header("Sports you follow")
    items(Leagues.picks, key = { "l:" + it.key }) { p ->
        val on = vm.isSportEnabled(p)
        SettingRow(p.label, { vm.setSportEnabled(p, !on) },
            subtitle = if (p.leagues.size > 1) p.leagues.joinToString(" & ") { it.label } else sportName(p.sport), checked = on)
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.guideSection(vm: AppViewModel) {
    header("Order")
    item(key = "sort") {
        SettingRow("Sort channels", { vm.updateGuideSort(if (vm.guideSort == "name") "number" else "name") },
            value = if (vm.guideSort == "name") "By name" else "By channel number")
    }
    val cat = vm.catalog ?: return
    header("Categories in \"All channels\"", "Turn off categories you never watch (e.g. other countries).")
    item(key = "showall") {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Show all", { vm.showAllGroups() })
            PillButton("Sports only", { vm.showAllGroups(cat.sportsGroups) })
        }
    }
    items(cat.groups, key = { "g:$it" }) { g ->
        val shown = g !in vm.hiddenGroups
        SettingRow(g, { vm.setGroupHidden(g, shown) }, value = "${cat.byGroup[g]?.size ?: 0}", checked = shown)
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.playbackSection(vm: AppViewModel) {
    header("Video")
    item(key = "format") {
        SettingRow("Live stream format", { vm.updateStreamFormat(vm.streamFormat.other()) },
            subtitle = "MPEG-TS starts fastest on most providers. If a channel won't play, the other format is tried automatically.",
            value = vm.streamFormat.label)
    }
    header("Video decoding")
    item(key = "dec-player") {
        val mode = vm.decoderMode(DecoderSlot.PLAYER)
        SettingRow("Full-screen player", { vm.setDecoderMode(DecoderSlot.PLAYER, mode.next()) },
            subtitle = "Hardware is smoothest. Software runs on the CPU and suits smaller or lower-resolution streams. Nothing switches on its own.",
            value = mode.label)
    }
    for (slot in 0 until 4) {
        item(key = "dec-mv$slot") {
            val key = DecoderSlot.multiview(slot)
            val mode = vm.decoderMode(key)
            SettingRow("Multiview screen ${slot + 1}", { vm.setDecoderMode(key, mode.next()) },
                subtitle = if (slot == 0) "If a screen says the TV has no hardware decoder free, set the small screens to Software" else null,
                value = mode.label)
        }
    }
    header("Menus")
    item(key = "bg") {
        val modes = listOf("sound", "muted", "off")
        val next = modes[(modes.indexOf(vm.backgroundVideo).coerceAtLeast(0) + 1) % modes.size]
        SettingRow("Live TV behind the menus", { vm.updateBackgroundVideo(next) },
            subtitle = "Like YouTube TV: what you were watching keeps playing at the top of Sports and Live, and previews the channel you rest on.",
            value = when (vm.backgroundVideo) {
                "sound" -> "With sound"
                "muted" -> "Muted"
                else -> "Off"
            })
    }
    item(key = "smooth") { SmoothMotionRow() }
    header("Remote")
    item(key = "keys") {
        Text(
            "While watching: Up shows or hides the score bug · Down or OK shows the controls · CH+/CH− change channel · hold OK for Multiview.",
            fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    header("Player")
    item(key = "buttons") {
        val sizes = listOf("small", "medium", "large")
        SettingRow("Player buttons", { vm.updatePlayerButtons(sizes[(sizes.indexOf(vm.playerButtons) + 1) % sizes.size]) },
            subtitle = "Size of the round buttons under live TV and recordings",
            value = vm.playerButtons.replaceFirstChar { it.uppercase() })
    }
    header("Subtitles", "Captions from the channel or recording itself. Pick one in the player's settings (Menu, or the gear).")
    item(key = "cc") { SettingRow("Subtitles", { vm.updateCaptions(!vm.captions) }, subtitle = "Turn on when there are any", checked = vm.captions) }
    item(key = "cc-preview") { SubtitlePreview(vm.subtitleStyle, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
    subtitleStyleItems(vm, "s")
    header("Advanced")
    item(key = "ua") { UserAgentEditor(vm) }
}

@Composable
private fun SmoothMotionRow() {
    val activity = LocalContext.current as? android.app.Activity ?: return
    var on by remember { mutableStateOf(DisplayModes.enabled(activity)) }
    var detail by remember { mutableStateOf(DisplayModes.describe(activity)) }
    SettingRow(
        "Smooth motion (up to 120 Hz)",
        {
            on = !on
            DisplayModes.setEnabled(activity, on)
            detail = DisplayModes.describe(activity)
        },
        subtitle = "Uses the TV's fastest refresh rate for the menus when it has one. $detail",
        checked = on,
    )
}

@Composable
private fun UserAgentEditor(vm: AppViewModel) {
    var ua by remember { mutableStateOf(vm.userAgent) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        TvTextField(ua, { ua = it }, label = "User-Agent", placeholder = Http.DEFAULT_USER_AGENT, onSubmit = { vm.updateUserAgent(ua) })
        Spacer(Modifier.height(4.dp))
        Text("Some providers only allow certain players. Change this only if your provider tells you to.", fontSize = 12.sp, color = AppColors.TextFaint)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Save", { vm.updateUserAgent(ua) })
            PillButton("Reset", { ua = Http.DEFAULT_USER_AGENT; vm.updateUserAgent(ua) })
        }
    }
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.dvrSection(vm: AppViewModel) {
    header("Storage", "Recordings are saved on this TV.")
    info("Used", bytesText(vm.recordingsBytes()))
    info("Free on TV", bytesText(vm.recordingsFreeBytes()))
    item(key = "cap") {
        val caps = listOf(5, 10, 20, 50, 100, 0)
        SettingRow("Space for recordings", { vm.updateDvr(capGb = caps[(caps.indexOf(vm.dvrCapGb) + 1) % caps.size]) },
            subtitle = "Oldest recordings are deleted to stay under this", value = if (vm.dvrCapGb == 0) "Until the TV is full" else "${vm.dvrCapGb} GB")
    }
    item(key = "keep") {
        val days = listOf(7, 14, 30, 90, 0)
        SettingRow("Keep recordings", { vm.updateDvr(keepDays = days[(days.indexOf(vm.dvrKeepDays) + 1) % days.size]) },
            value = if (vm.dvrKeepDays == 0) "Until deleted" else "${vm.dvrKeepDays} days")
    }
    item(key = "pad") {
        val pads = listOf(0, 1, 2, 5, 10)
        SettingRow("Start early", { vm.updateDvr(paddingMin = pads[(pads.indexOf(vm.dvrPaddingMin) + 1) % pads.size]) },
            subtitle = "Also added to the end of shows from the guide. Games keep recording while they're live.",
            value = if (vm.dvrPaddingMin == 0) "On time" else "${vm.dvrPaddingMin} min")
    }
    header("Reliability")
    item(key = "alarms") { ExactAlarmRow(vm) }
    item(key = "note") {
        Text(
            "Recordings need the TV to be on or in a standby that keeps the network on. Each recording uses one of your provider's streams.",
            fontSize = 12.sp, color = AppColors.TextFaint, modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ExactAlarmRow(vm: AppViewModel) {
    val context = LocalContext.current
    val allowed = vm.canWakeExactly
    SettingRow(
        "Start recordings on time",
        {
            if (!allowed && Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }.onFailure { vm.showMessage("Open Android Settings › Apps › GameDay TV › Alarms & reminders") }
            }
        },
        subtitle = if (allowed) "Allowed: GameDay TV can wake up for scheduled recordings" else "Allow \"Alarms & reminders\" so scheduled recordings start exactly on time",
        value = if (allowed) "On" else "Off",
    )
}

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.aboutSection(vm: AppViewModel) {
    header("GameDay TV")
    info("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    info("Scores", "ESPN public scoreboards, refreshed every 15 s while games are live")
    info("Video", "Your IPTV provider, played with Media3 / ExoPlayer")
    header("Privacy")
    item(key = "hist") { SettingRow("Clear watch & search history", { vm.clearHistory() }, subtitle = "For ${vm.profile?.name ?: "this profile"}", icon = Icons.Delete) }
    item(key = "priv") {
        Text(
            "Your account, profiles and library are stored only on this TV. Your provider login is encrypted with the Android Keystore and only sent to your provider.",
            fontSize = 12.sp, color = AppColors.TextFaint, modifier = Modifier.padding(16.dp),
        )
    }
}

