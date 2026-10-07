package com.gameday.tv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameday.tv.data.ChannelMatcher
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.IptvAccount
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.XtreamSource
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.Icons
import com.gameday.tv.ui.IptvStatus
import com.gameday.tv.ui.Logo
import com.gameday.tv.ui.TeamLogo
import com.gameday.tv.ui.sportName
import com.gameday.tv.ui.theme.AppColors

@Composable
fun OnboardingScreen(vm: AppViewModel, step: Int) {
    when (step) {
        0 -> ProviderScreen(vm, onboarding = true)
        1 -> StepFrame(
            step = 2,
            title = "What do you like to watch?",
            subtitle = "Pick your sports. They fill your Sports tab, and you can change them anytime in Settings.",
            next = { vm.finishOnboardingStep(1) },
        ) { SportsPicker(vm) }
        else -> StepFrame(
            step = 3,
            title = "Add your teams",
            subtitle = "Your teams get their own filter in Sports, score alerts, and can be recorded automatically.",
            next = { vm.finishOnboardingStep(2) },
            nextLabel = "Done",
        ) { TeamsPicker(vm) }
    }
}

@Composable
private fun StepFrame(step: Int, title: String, subtitle: String, next: () -> Unit, nextLabel: String = "Next", content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = GUTTER, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(18.dp)
            Spacer(Modifier.weight(1f))
            Text("Step $step of 3", fontSize = 13.sp, color = AppColors.TextDim)
            Spacer(Modifier.width(12.dp))
            Button(onClick = next) { Text(nextLabel) }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 14.sp, color = AppColors.TextDim)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** IPTV login: Xtream Codes or an M3U playlist. Used in setup and from Settings (add or edit one). */
@Composable
fun ProviderScreen(vm: AppViewModel, onboarding: Boolean, editId: String? = null) {
    val entry = vm.providers.firstOrNull { it.id == editId }
    val existing = entry?.account
    // Plain remember: the form starts fresh each time it opens (it holds logins).
    var name by remember { mutableStateOf(entry?.name.orEmpty()) }
    var mode by remember { mutableStateOf(if (existing is IptvAccount.M3u) "m3u" else "xtream") }
    var server by remember { mutableStateOf((existing as? IptvAccount.Xtream)?.server.orEmpty()) }
    var user by remember { mutableStateOf((existing as? IptvAccount.Xtream)?.username.orEmpty()) }
    var pass by remember { mutableStateOf((existing as? IptvAccount.Xtream)?.password.orEmpty()) }
    var m3u by remember { mutableStateOf((existing as? IptvAccount.M3u)?.url.orEmpty()) }
    var epgUrl by remember { mutableStateOf((existing as? IptvAccount.M3u)?.epgUrl.orEmpty()) }
    var submitted by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val legacyLabel = vm.legacyProviderLabel.takeIf { editId == null && vm.providers.isEmpty() }
    LaunchedEffect(Unit) { vm.resetProviderTest() }

    val done: () -> Unit = { if (onboarding) vm.finishOnboardingStep(0) else vm.back() }

    fun connect() {
        formError = null
        val account: IptvAccount = if (mode == "xtream") {
            XtreamSource.fromPlaylistUrl(server) ?: run {
                if (server.isBlank() || user.isBlank() || pass.isBlank()) {
                    formError = "Enter the server address, username and password from your provider."
                    return
                }
                IptvAccount.Xtream(server.trim(), user.trim(), pass)
            }
        } else {
            if (m3u.isBlank()) {
                formError = "Enter your playlist URL."
                return
            }
            XtreamSource.fromPlaylistUrl(m3u) ?: IptvAccount.M3u(m3u.trim(), epgUrl.trim().ifBlank { null })
        }
        submitted = true
        vm.saveProvider(editId, name, account, done)
    }

    val status = vm.providerTest
    val connecting = submitted && status is IptvStatus.Loading
    val failure = (status as? IptvStatus.Failed)?.message?.takeIf { submitted }

    Column(Modifier.fillMaxSize()) {
        if (!onboarding) BackBar(vm)
        FormPage(underBar = !onboarding) {
            if (onboarding) {
                Logo(20.dp)
                Spacer(Modifier.height(20.dp))
                Text("Step 1 of 3", fontSize = 13.sp, color = AppColors.TextDim)
            }
            Text(
                when {
                    entry != null -> "Edit ${entry.name}"
                    vm.providers.isNotEmpty() -> "Add another provider"
                    else -> "Connect your TV provider"
                },
                fontSize = 26.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign in with the details your IPTV provider gave you. GameDay uses them to show your channels and guide, " +
                    "and to find the channel for every game. Your login is encrypted on this device and only sent to your provider.",
                fontSize = 14.sp, color = AppColors.TextDim, lineHeight = 20.sp,
            )
            if (legacyLabel != null) {
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { submitted = true; vm.useLegacyProvider(done) }, Modifier.fillMaxWidth()) {
                    Text("Use the provider already on this device ($legacyLabel)")
                }
            }
            Spacer(Modifier.height(18.dp))
            FormErrorText(formError ?: failure)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "xtream", onClick = { mode = "xtream" }, label = { Text("Xtream Codes") })
                FilterChip(selected = mode == "m3u", onClick = { mode = "m3u" }, label = { Text("M3U playlist") })
            }
            Spacer(Modifier.height(10.dp))
            if (!onboarding) {
                FormField(name, { name = it }, "Name (optional)", placeholder = "Shown in Settings and the guide")
                Spacer(Modifier.height(10.dp))
            }
            if (mode == "xtream") {
                FormField(server, { server = it }, "Server URL", placeholder = "http://provider.example:8080", keyboardType = KeyboardType.Uri)
                Spacer(Modifier.height(10.dp))
                FormField(user, { user = it }, "Username")
                Spacer(Modifier.height(10.dp))
                FormField(pass, { pass = it }, "Password", password = true, imeAction = ImeAction.Done, onDone = ::connect)
            } else {
                FormField(m3u, { m3u = it }, "Playlist URL (M3U)", placeholder = "http://provider.example/get.php?…", keyboardType = KeyboardType.Uri)
                Spacer(Modifier.height(10.dp))
                FormField(epgUrl, { epgUrl = it }, "Guide URL (XMLTV, optional)", placeholder = "Uses the playlist's url-tvg if empty",
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, onDone = ::connect)
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = ::connect, Modifier.fillMaxWidth(), enabled = !connecting) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (connecting) "Connecting…" else "Connect")
            }
            if (onboarding) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.skipProvider(); vm.finishOnboardingStep(0) }, Modifier.fillMaxWidth()) { Text("Skip for now", color = AppColors.TextDim) }
                Text("Skip to use GameDay for live scores only. You can add a provider later in Settings.", fontSize = 12.sp, color = AppColors.TextFaint)
            }
        }
    }
}

/** Toggle the sports a profile follows. */
@Composable
fun SportsPicker(vm: AppViewModel, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(Leagues.picks, key = { it.key }) { league ->
            val on = vm.isSportEnabled(league)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color(0x33FFFFFF) else Color(0x14FFFFFF))
                    .clickable { vm.setSportEnabled(league, !on) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckDot(on)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(league.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(sportName(league.sport), fontSize = 12.sp, color = AppColors.TextDim)
                }
            }
        }
    }
}

@Composable
private fun CheckDot(on: Boolean) {
    Box(Modifier.size(22.dp).background(if (on) Color.White else Color(0x33FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
        if (on) AppIcon(Icons.Check, null, tint = Color.Black, size = 16.dp)
    }
}

/** Search and add teams from the followed leagues. */
@Composable
fun TeamsPicker(vm: AppViewModel, modifier: Modifier = Modifier) {
    val leagues = vm.followedLeagues
    var leagueKey by rememberSaveable { mutableStateOf(leagues.firstOrNull()?.key) }
    val league = leagues.firstOrNull { it.key == leagueKey } ?: leagues.firstOrNull()
    var teams by remember(league) { mutableStateOf<List<FavoriteTeam>?>(null) }
    var error by remember(league) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(league?.key) { mutableStateOf("") }
    LaunchedEffect(league) {
        if (league == null) return@LaunchedEffect
        runCatching { vm.loadTeams(league) }
            .onSuccess { teams = it }
            .onFailure { error = "Couldn't load teams. Check your connection." }
    }
    val shown = remember(teams, query) {
        val q = ChannelMatcher.norm(query)
        teams.orEmpty().filter { q.isEmpty() || ChannelMatcher.norm(it.name + " " + it.abbreviation).contains(q) }
    }

    Column(modifier.fillMaxSize()) {
        if (leagues.isEmpty()) {
            Text("Follow a team sport first to add teams.", fontSize = 14.sp, color = AppColors.TextDim)
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(leagues, key = { it.key }) { l ->
                FilterChip(selected = l == league, onClick = { leagueKey = l.key }, label = { Text(l.label) })
            }
        }
        Spacer(Modifier.height(6.dp))
        FormField(query, { query = it }, "Search ${league?.label ?: ""} teams", imeAction = ImeAction.Search)
        if (vm.favorites.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Your teams: " + vm.favorites.joinToString(", ") { it.name }, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        when {
            error != null -> Text(error!!, fontSize = 14.sp, color = AppColors.Live)
            teams == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Text("No teams match.", fontSize = 14.sp, color = AppColors.TextDim)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                items(shown, key = { it.key }) { t ->
                    val fav = vm.isFavorite(t.leagueKey, t.id)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (fav) Color(0x40FFFFFF) else Color(0x14FFFFFF))
                            .clickable { vm.toggleFavorite(t) }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box {
                            TeamLogo(t.logo, t.abbreviation, 38.dp)
                            if (fav) Box(Modifier.align(Alignment.TopEnd).padding(start = 28.dp)) { CheckDot(true) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Add or remove teams (from Settings). */
@Composable
fun TeamsPickerScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        BackBar(vm, "Your teams") { TextButton(onClick = { vm.back() }) { Text("Done") } }
        Text("Tap a team to add it to your library. Tap it again to remove it.", fontSize = 14.sp, color = AppColors.TextDim,
            modifier = Modifier.padding(horizontal = GUTTER))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).padding(horizontal = GUTTER)) { TeamsPicker(vm) }
    }
}
