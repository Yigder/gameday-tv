package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.gameday.tv.data.ChannelMatcher
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.IptvAccount
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.XtreamSource
import com.gameday.tv.ui.theme.AppColors

@Composable
fun OnboardingScreen(vm: AppViewModel, step: Int) {
    when (step) {
        0 -> ProviderScreen(vm, onboarding = true)
        1 -> StepFrame(
            step = 2,
            title = "What do you like to watch?",
            subtitle = "Pick your sports. They'll fill your Sports tab, and you can change them anytime in Settings.",
            next = { vm.finishOnboardingStep(1) },
        ) { SportsPicker(vm, Modifier.fillMaxSize()) }
        else -> StepFrame(
            step = 3,
            title = "Add your teams",
            subtitle = "Teams you add get their own filter in Sports, score alerts, and can be recorded automatically.",
            next = { vm.finishOnboardingStep(2) },
            nextLabel = "Done",
        ) { TeamsPicker(vm, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun StepFrame(
    step: Int,
    title: String,
    subtitle: String,
    next: () -> Unit,
    nextLabel: String = "Next",
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(20.dp)
            Spacer(Modifier.weight(1f))
            Text("Step $step of 3", fontSize = 13.sp, color = AppColors.TextDim)
            Spacer(Modifier.width(16.dp))
            PillButton(nextLabel, next, primary = true)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, fontSize = 14.sp, color = AppColors.TextDim)
        Spacer(Modifier.height(16.dp))
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
    // Offer the 1.x login only when setting up the first provider.
    val legacyLabel = vm.legacyProviderLabel.takeIf { editId == null && vm.providers.isEmpty() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.resetProviderTest()
        first.requestFocusSafely(200)
    }

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

    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Logo(22.dp)
            Spacer(Modifier.height(24.dp))
            if (onboarding) Text("Step 1 of 3", fontSize = 13.sp, color = AppColors.TextDim)
            Text(
                when {
                    entry != null -> "Edit ${entry.name}"
                    vm.providers.isNotEmpty() -> "Add another provider"
                    else -> "Connect your TV provider"
                },
                fontSize = 30.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Sign in with the details your IPTV provider gave you. GameDay TV uses them to show your channels, guide, " +
                    "movies and shows, and to find the channel for every game.\n\nYour login is encrypted on this TV and only sent to your provider.",
                fontSize = 14.sp,
                color = AppColors.TextDim,
                lineHeight = 20.sp,
            )
            if (legacyLabel != null) {
                Spacer(Modifier.height(18.dp))
                FocusSurface(onClick = { submitted = true; vm.useLegacyProvider(done) }, modifier = Modifier.fillMaxWidth(), containerColor = Color(0x1FFFFFFF)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Check, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Use the provider already on this TV", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("$legacyLabel · saved by the previous version", fontSize = 12.sp, color = AppColors.TextDim)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            FormError(formError ?: failure)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Xtream Codes", mode == "xtream", { mode = "xtream" }, Modifier.focusRequester(first))
                Chip("M3U playlist", mode == "m3u", { mode = "m3u" })
            }
            Spacer(Modifier.height(16.dp))
            if (!onboarding) {
                TvTextField(name, { name = it }, label = "Name (optional)", placeholder = "Shown in Settings and the guide")
                Spacer(Modifier.height(10.dp))
            }
            if (mode == "xtream") {
                TvTextField(server, { server = it }, label = "Server URL", placeholder = "http://provider.example:8080", keyboardType = KeyboardType.Uri)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvTextField(user, { user = it }, Modifier.weight(1f), label = "Username")
                    TvTextField(pass, { pass = it }, Modifier.weight(1f), label = "Password", password = true, imeAction = ImeAction.Done, onSubmit = ::connect)
                }
            } else {
                TvTextField(m3u, { m3u = it }, label = "Playlist URL (M3U)", placeholder = "http://provider.example/get.php?…", keyboardType = KeyboardType.Uri)
                Spacer(Modifier.height(10.dp))
                TvTextField(epgUrl, { epgUrl = it }, label = "Guide URL (XMLTV, optional)", placeholder = "Uses the playlist's url-tvg if empty",
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, onSubmit = ::connect)
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (connecting) "Connecting…" else "Connect", ::connect, primary = true)
                if (onboarding) {
                    PillButton("Skip for now", { vm.skipProvider(); vm.finishOnboardingStep(0) })
                } else {
                    PillButton("Cancel", { vm.back() })
                }
                if (connecting) Spinner(28.dp)
            }
            if (onboarding) {
                Spacer(Modifier.height(10.dp))
                Text("Skip to use GameDay TV for live scores only. You can add a provider later in Settings.", fontSize = 12.sp, color = AppColors.TextFaint)
            }
        }
    }
}

/** Toggle the leagues a profile follows. */
@Composable
fun SportsPicker(vm: AppViewModel, modifier: Modifier = Modifier, focusFirst: Boolean = true) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusFirst) first.requestFocusSafely(200) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(190.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 32.dp),
    ) {
        items(Leagues.picks, key = { it.key }) { league ->
            val on = vm.isSportEnabled(league)
            FocusSurface(
                onClick = { vm.setSportEnabled(league, !on) },
                modifier = Modifier.fillMaxWidth().height(64.dp).then(if (league == Leagues.picks.first()) Modifier.focusRequester(first) else Modifier),
                containerColor = if (on) Color(0x33FFFFFF) else Color(0x14FFFFFF),
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CheckCircle(on)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(league.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(sportName(league.sport), fontSize = 12.sp, color = AppColors.TextDim)
                    }
                }
            }
        }
    }
}

fun sportName(sport: String): String = when (sport) {
    "football" -> "Football"
    "baseball" -> "Baseball"
    "basketball" -> "Basketball"
    "hockey" -> "Hockey"
    "soccer" -> "Soccer"
    "golf" -> "Golf"
    else -> sport.replaceFirstChar { it.uppercase() }
}

@Composable
fun CheckCircle(on: Boolean) {
    Box(
        Modifier.size(24.dp).background(if (on) Color.White else Color(0x33FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Check, null, Modifier.size(18.dp), tint = Color.Black)
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
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(200) }
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

    Column(modifier) {
        if (leagues.isEmpty()) {
            Text("Follow a team sport first to add teams.", fontSize = 14.sp, color = AppColors.TextDim)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
                items(leagues, key = { it.key }) { l ->
                    Chip(l.label, l == league, { leagueKey = l.key }, if (l == leagues.first()) Modifier.focusRequester(first) else Modifier)
                }
            }
            Spacer(Modifier.width(12.dp))
            TvTextField(query, { query = it }, Modifier.width(260.dp), placeholder = "Search ${league?.label ?: ""} teams", imeAction = ImeAction.Search, onSubmit = {})
        }
        if (vm.favorites.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Your teams: " + vm.favorites.joinToString(", ") { it.name }, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        when {
            error != null -> Text(error!!, fontSize = 14.sp, color = AppColors.Live)
            teams == null -> LoadingState("Loading teams…")
            shown.isEmpty() -> Text("No teams match.", fontSize = 14.sp, color = AppColors.TextDim)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 32.dp),
            ) {
                items(shown, key = { it.key }) { t ->
                    val fav = vm.isFavorite(t.leagueKey, t.id)
                    FocusSurface(
                        onClick = { vm.toggleFavorite(t) },
                        modifier = Modifier.fillMaxWidth().height(104.dp),
                        containerColor = if (fav) Color(0x40FFFFFF) else Color(0x14FFFFFF),
                    ) {
                        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box {
                                TeamLogo(t.logo, t.abbreviation, 40.dp)
                                if (fav) Box(Modifier.align(Alignment.TopEnd).padding(start = 30.dp)) { CheckCircle(true) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** Add or remove teams (from Settings). */
@Composable
fun TeamsPickerScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your teams", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                Text("Select a team to add it to your library. Select it again to remove it.", fontSize = 14.sp, color = AppColors.TextDim)
            }
            PillButton("Done", { vm.back() }, primary = true)
        }
        Spacer(Modifier.height(16.dp))
        TeamsPicker(vm, Modifier.weight(1f))
    }
}