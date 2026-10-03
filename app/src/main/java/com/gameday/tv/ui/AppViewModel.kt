package com.gameday.tv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gameday.tv.data.AccountInfo
import com.gameday.tv.data.AccountPrefs
import com.gameday.tv.data.AccountStore
import com.gameday.tv.data.AppAccount
import com.gameday.tv.data.Channel
import com.gameday.tv.data.ChannelMatch
import com.gameday.tv.data.ChannelMatcher
import com.gameday.tv.data.Episode
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.GameStats
import com.gameday.tv.data.Http
import com.gameday.tv.data.IptvAccount
import com.gameday.tv.data.IptvCatalog
import com.gameday.tv.data.IptvSource
import com.gameday.tv.data.League
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.LegacySettings
import com.gameday.tv.data.Movie
import com.gameday.tv.data.MovieInfo
import com.gameday.tv.data.MultiviewLayout
import com.gameday.tv.data.MultiviewRules
import com.gameday.tv.data.Profile
import com.gameday.tv.data.ProfilePrefs
import com.gameday.tv.data.Program
import com.gameday.tv.data.RecStatus
import com.gameday.tv.data.Recording
import com.gameday.tv.data.ResumePoint
import com.gameday.tv.data.SavedItem
import com.gameday.tv.data.SavedKind
import com.gameday.tv.data.ScoreBugMode
import com.gameday.tv.data.ScoresRepository
import com.gameday.tv.data.Series
import com.gameday.tv.data.SeriesInfo
import com.gameday.tv.data.StreamFormat
import com.gameday.tv.data.TeamInfo
import com.gameday.tv.data.Tournament
import com.gameday.tv.data.Xmltv
import com.gameday.tv.dvr.RecordScheduler
import com.gameday.tv.dvr.RecordingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    val accountStore = AccountStore(app)
    val legacy = LegacySettings(app)
    private val scoresRepo = ScoresRepository()

    // =============================================================================================
    // Navigation
    // =============================================================================================

    private val backStack = mutableStateListOf<Screen>(Screen.Splash)
    val screen: Screen get() = backStack.last()
    val canGoBack: Boolean get() = backStack.size > 1
    var tab by mutableStateOf(Tab.HOME); private set

    /** Remembers the focused item per screen, so coming back lands where the viewer left off. */
    val focusMemory = HashMap<String, String>()
    /** Screen key whose remembered focus should be restored (set when navigating back to it). */
    var restoreFocusFor: String? = null

    var message by mutableStateOf<String?>(null); private set
    private var messageJob: Job? = null
    var dialog by mutableStateOf<AppDialog?>(null); private set

    fun navigate(s: Screen) {
        if (screen.key == s.key) return
        restoreFocusFor = null
        backStack.add(s)
    }

    fun back() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            // Main's tabs remember focus per tab.
            restoreFocusFor = if (screen == Screen.Main) "main:$tab" else screen.key
        }
    }

    fun replace(s: Screen) {
        backStack[backStack.lastIndex] = s
    }

    private fun resetTo(s: Screen) {
        backStack.clear()
        backStack.add(s)
        restoreFocusFor = null
    }

    /** When a tab opens, should its content take focus? (Not while the viewer browses the top bar.) */
    var tabWantsFocus = true

    fun selectTab(t: Tab, focusContent: Boolean = true) {
        tabWantsFocus = focusContent
        tab = t
        if (screen != Screen.Main) resetTo(Screen.Main)
    }

    fun openGame(game: Game) = navigate(Screen.GameDetail(game.id))
    fun openTournament(t: Tournament) = navigate(Screen.TournamentDetail(t.id))
    fun openTeam(leagueKey: String, teamId: String) = navigate(Screen.Team(leagueKey, teamId))
    fun openChannel(channel: Channel) = navigate(Screen.ChannelDetail(channel.id))
    fun openMovie(movie: Movie) = navigate(Screen.MovieDetail(movie.id))
    fun openSeries(series: Series) = navigate(Screen.SeriesDetail(series.id))
    fun openSettings(section: SettingsSection = SettingsSection.ACCOUNT) {
        settingsSection = section
        navigate(Screen.Settings(section))
    }

    var settingsSection by mutableStateOf(SettingsSection.ACCOUNT)

    /** From the player's panels: swap the player for the event page instead of stacking. */
    fun openEventFromPlayer(eventId: String) {
        val target = if (tournamentById(eventId) != null) Screen.TournamentDetail(eventId) else Screen.GameDetail(eventId)
        if (screen is Screen.Player) replace(target) else navigate(target)
    }

    fun showMessage(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(3_500)
            message = null
        }
    }

    fun showDialog(d: AppDialog) {
        dialog = d
    }

    fun dismissDialog() {
        dialog = null
    }

    fun confirm(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit) {
        showDialog(
            AppDialog(
                title = title,
                message = message,
                actions = listOf(
                    DialogAction(confirmLabel, onClick = { dismissDialog(); onConfirm() }),
                    DialogAction("Cancel", onClick = { dismissDialog() }),
                ),
            ),
        )
    }

    // =============================================================================================
    // Accounts & profiles
    // =============================================================================================

    var account by mutableStateOf<AppAccount?>(null); private set
    private var accountPrefs: AccountPrefs? = null
    val profiles = mutableStateListOf<Profile>()
    var profile by mutableStateOf<Profile?>(null); private set
    private var profilePrefs: ProfilePrefs? = null
    var askWhoIsWatching by mutableStateOf(true); private set

    val accounts: List<AppAccount> get() = accountStore.accounts()

    /** Legacy (1.x) login that a new account can take over, shown during setup. */
    val legacyProviderLabel: String? get() = legacy.providerLabel

    /** @return an error message, or null on success. */
    suspend fun createAccount(name: String, email: String, password: String, confirm: String): String? {
        if (password != confirm) return "Passwords don't match."
        val firstOnDevice = accountStore.accounts().isEmpty()
        val created = try {
            accountStore.create(name, email, password)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        val prefs = AccountPrefs(context, created.id)
        val first = Profile(UUID.randomUUID().toString(), created.firstName, 0)
        prefs.profiles = listOf(first)
        prefs.lastProfileId = first.id
        // Bring sports picks over from the previous version on this TV.
        if (firstOnDevice) {
            val pp = ProfilePrefs(context, created.id, first.id)
            legacy.enabledLeagues?.let { pp.enabledLeagues = it }
            legacy.favoriteTeams.takeIf { it.isNotEmpty() }?.let { pp.favoriteTeams = it }
            legacy.streamFormat?.let { prefs.streamFormat = it }
            legacy.userAgent?.let { prefs.userAgent = it }
        }
        accountStore.currentId = created.id
        enterAccount(created)
        return null
    }

    /** @return an error message, or null on success. */
    suspend fun signIn(email: String, password: String): String? {
        if (email.isBlank() || password.isEmpty()) return "Enter your email and password."
        val acct = accountStore.verify(email, password)
            ?: return if (accountStore.hasEmail(email)) "Wrong password. Try again." else "No account with that email on this TV. Create one instead."
        accountStore.currentId = acct.id
        enterAccount(acct)
        return null
    }

    private fun enterAccount(acct: AppAccount) {
        account = acct
        tab = Tab.HOME
        tabWantsFocus = true
        val prefs = AccountPrefs(context, acct.id)
        accountPrefs = prefs
        profiles.clear()
        profiles.addAll(prefs.profiles.ifEmpty {
            listOf(Profile(UUID.randomUUID().toString(), acct.firstName, 0)).also { prefs.profiles = it }
        })
        askWhoIsWatching = prefs.askWhoIsWatching
        streamFormat = prefs.streamFormat
        userAgent = prefs.userAgent
        Http.userAgent = userAgent
        hiddenGroups = prefs.hiddenGroups
        guideSort = prefs.guideSort
        dvrCapGb = prefs.dvrCapGb
        dvrKeepDays = prefs.dvrKeepDays
        dvrPaddingMin = prefs.dvrPaddingMin

        disconnectProvider()
        prefs.iptv?.let { connect(it) }

        when {
            !prefs.onboarded -> {
                selectProfileInternal(profiles.first())
                resetTo(Screen.Onboarding(0))
            }
            profiles.size > 1 && askWhoIsWatching -> resetTo(Screen.Profiles())
            else -> {
                selectProfileInternal(profiles.firstOrNull { it.id == prefs.lastProfileId } ?: profiles.first())
                resetTo(Screen.Main)
            }
        }
    }

    fun signOut() {
        accountStore.currentId = null
        leaveAccount()
        resetTo(Screen.Welcome)
    }

    private fun leaveAccount() {
        disconnectProvider()
        account = null
        accountPrefs = null
        profile = null
        profilePrefs = null
        profiles.clear()
        playback = null
        clearMultiview()
        focusMemory.clear()
        tab = Tab.HOME
    }

    /** @return an error message, or null when the account was deleted. */
    suspend fun deleteAccount(password: String): String? {
        val acct = account ?: return null
        accountStore.verify(acct.email, password) ?: return "Wrong password."
        RecordingStore.removeAccount(acct.id)
        RecordScheduler.reschedule(context)
        accountStore.delete(acct.id)
        leaveAccount()
        resetTo(Screen.Welcome)
        showMessage("Account deleted")
        return null
    }

    /** @return an error message, or null on success. */
    suspend fun changePassword(current: String, new: String, confirm: String): String? {
        val acct = account ?: return null
        if (new != confirm) return "New passwords don't match."
        return try {
            accountStore.changePassword(acct.id, current, new)
            showMessage("Password changed")
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }

    fun renameAccount(name: String) {
        val acct = account ?: return
        if (name.isBlank()) return
        accountStore.rename(acct.id, name)
        account = acct.copy(name = name.trim())
    }

    fun finishOnboardingStep(step: Int) {
        if (step < 2) {
            replace(Screen.Onboarding(step + 1))
        } else {
            accountPrefs?.onboarded = true
            tabWantsFocus = true
            tab = Tab.HOME
            resetTo(Screen.Main)
            showMessage("You're all set, ${account?.firstName ?: ""}")
        }
    }

    fun selectProfile(p: Profile) {
        selectProfileInternal(p)
        accountPrefs?.lastProfileId = p.id
        tabWantsFocus = true
        tab = Tab.HOME
        resetTo(Screen.Main)
    }

    fun switchProfile() = navigate(Screen.Profiles())

    /** @return an error message, or null on success. */
    fun saveProfile(id: String?, name: String, color: Int): String? {
        val prefs = accountPrefs ?: return null
        val n = name.trim()
        if (n.isEmpty()) return "Enter a name."
        if (profiles.any { it.name.equals(n, true) && it.id != id }) return "There's already a profile named $n."
        if (id == null) {
            if (profiles.size >= AccountStore.MAX_PROFILES) return "An account can have up to ${AccountStore.MAX_PROFILES} profiles."
            profiles += Profile(UUID.randomUUID().toString(), n, color)
        } else {
            val i = profiles.indexOfFirst { it.id == id }
            if (i >= 0) profiles[i] = profiles[i].copy(name = n, color = color)
            if (profile?.id == id) profile = profiles[i]
        }
        prefs.profiles = profiles.toList()
        return null
    }

    fun deleteProfile(id: String) {
        val prefs = accountPrefs ?: return
        if (profiles.size <= 1) {
            showMessage("An account needs at least one profile")
            return
        }
        profiles.removeAll { it.id == id }
        prefs.profiles = profiles.toList()
        context.deleteSharedPreferences(ProfilePrefs.prefsName(prefs.accountId, id))
        if (profile?.id == id) selectProfileInternal(profiles.first())
    }

    fun updateAskWhoIsWatching(on: Boolean) {
        accountPrefs?.askWhoIsWatching = on
        askWhoIsWatching = on
    }

    private fun selectProfileInternal(p: Profile) {
        val acct = account ?: return
        profile = p
        val pp = ProfilePrefs(context, acct.id, p.id)
        profilePrefs = pp
        enabledLeagues = pp.enabledLeagues
        favorites.replaceWith(pp.favoriteTeams)
        favoriteChannelIds.replaceWith(pp.favoriteChannels)
        recentChannelIds.replaceWith(pp.recentChannels)
        recentSearches.replaceWith(pp.recentSearches)
        saved.replaceWith(pp.saved)
        resume.replaceWith(pp.resume)
        hideScores = pp.hideScores
        scoreBugMode = pp.scoreBugMode
        scoreAlerts = pp.scoreAlerts
        captions = pp.captions
        autoplayNext = pp.autoplayNext
        zapWithDpad = pp.zapWithDpad
        guideFilter = pp.guideFilter
        focusMemory.clear()
        publishScores()
        refreshScoresNow()
        viewModelScope.launch { scheduleTeamRecordings() }
    }

    // =============================================================================================
    // Profile data: sports, teams, library, history
    // =============================================================================================

    var enabledLeagues by mutableStateOf(Leagues.everything.map { it.key }.toSet()); private set
    val favorites = mutableStateListOf<FavoriteTeam>()
    val favoriteChannelIds = mutableStateListOf<String>()
    val recentChannelIds = mutableStateListOf<String>()
    val recentSearches = mutableStateListOf<String>()
    val saved = mutableStateListOf<SavedItem>()
    val resume = mutableStateListOf<ResumePoint>()
    var hideScores by mutableStateOf(false); private set
    var scoreBugMode by mutableStateOf(ScoreBugMode.ON_PRESS); private set
    var scoreAlerts by mutableStateOf(true); private set
    var captions by mutableStateOf(false); private set
    var autoplayNext by mutableStateOf(true); private set
    var zapWithDpad by mutableStateOf(false); private set
    var guideFilter by mutableStateOf("sports"); private set

    private val teamsCache = HashMap<String, List<FavoriteTeam>>()
    private val scheduleCache = HashMap<String, Pair<Long, List<Game>>>()
    private val teamInfoCache = HashMap<String, TeamInfo>()

    val followedLeagues: List<League> get() = Leagues.all.filter { it.key in enabledLeagues }
    val followedTours: List<League> get() = Leagues.golf.filter { it.key in enabledLeagues }

    fun setLeagueEnabled(key: String, enabled: Boolean) {
        val next = if (enabled) enabledLeagues + key else enabledLeagues - key
        if (next.isEmpty()) {
            showMessage("Keep at least one sport")
            return
        }
        enabledLeagues = next
        profilePrefs?.enabledLeagues = next
        publishScores()
        if (enabled) Leagues.byKey(key)?.let { l -> viewModelScope.launch { refreshScores(listOf(l)) } }
    }

    fun isFavorite(leagueKey: String, teamId: String): Boolean = favorites.any { it.leagueKey == leagueKey && it.id == teamId }

    fun favoriteTeam(leagueKey: String, teamId: String): FavoriteTeam? = favorites.firstOrNull { it.leagueKey == leagueKey && it.id == teamId }

    fun isFavoriteGame(game: Game): Boolean =
        isFavorite(game.league.key, game.home.id) || isFavorite(game.league.key, game.away.id)

    fun toggleFavorite(team: FavoriteTeam) {
        val existing = favorites.indexOfFirst { it.key == team.key }
        if (existing >= 0) {
            favorites.removeAt(existing)
            showMessage("Removed ${team.name} from your library")
        } else {
            favorites.add(team)
            showMessage("Added ${team.name} to your library")
        }
        profilePrefs?.favoriteTeams = favorites.toList()
    }

    /** "Record all games" for a team in the library, like YouTube TV's team recordings. */
    fun setTeamRecording(team: FavoriteTeam, on: Boolean) {
        val i = favorites.indexOfFirst { it.key == team.key }
        val updated = team.copy(record = on)
        if (i >= 0) favorites[i] = updated else favorites.add(updated)
        profilePrefs?.favoriteTeams = favorites.toList()
        if (on) {
            showMessage("Recording all ${team.name} games")
            viewModelScope.launch { scheduleTeamRecordings() }
        } else {
            showMessage("Stopped recording ${team.name} games")
            recordingsAll.filter { it.auto && it.status == RecStatus.SCHEDULED && it.eventId?.startsWith(team.leagueKey + ":") == true }
                .filter { r -> gameMentionsTeam(r, team) }
                .forEach { RecordingStore.remove(it.id) }
            RecordScheduler.reschedule(context)
        }
    }

    private fun gameMentionsTeam(r: Recording, team: FavoriteTeam): Boolean =
        r.title.contains(team.name, true) || r.subtitle.contains(team.name, true) ||
            team.name.substringAfterLast(' ').let { r.title.contains(it, true) }

    fun favoriteFor(game: Game, home: Boolean): FavoriteTeam {
        val t = if (home) game.home else game.away
        return FavoriteTeam(game.league.key, t.id, t.displayName, t.abbreviation, t.logo)
    }

    /** Favorite teams' games: live first, then upcoming, then recent finals. */
    val favoriteGames: List<Game>
        get() = games.filter { isFavoriteGame(it) }.sortedWith(compareBy<Game>({ stateOrder(it) }, { it.startMillis }))

    suspend fun loadTeams(league: League): List<FavoriteTeam> {
        teamsCache[league.key]?.let { return it }
        val teams = scoresRepo.fetchTeams(league)
        teamsCache[league.key] = teams
        return teams
    }

    suspend fun teamSchedule(league: League, teamId: String): List<Game> {
        val key = "${league.key}:$teamId"
        scheduleCache[key]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return it.second }
        val list = scoresRepo.fetchTeamSchedule(league, teamId)
        scheduleCache[key] = System.currentTimeMillis() to list
        return list
    }

    suspend fun teamInfo(league: League, teamId: String): TeamInfo? {
        val key = "${league.key}:$teamId"
        teamInfoCache[key]?.let { return it }
        return scoresRepo.fetchTeamInfo(league, teamId)?.also { teamInfoCache[key] = it }
    }

    suspend fun gameStats(game: Game): GameStats = scoresRepo.fetchSummary(game)

    fun isFavoriteChannel(id: String): Boolean = id in favoriteChannelIds

    fun toggleFavoriteChannel(channel: Channel) {
        if (channel.id in favoriteChannelIds) {
            favoriteChannelIds.remove(channel.id)
            showMessage("Removed ${channel.name} from favorite channels")
        } else {
            favoriteChannelIds.add(channel.id)
            showMessage("Added ${channel.name} to favorite channels")
        }
        profilePrefs?.favoriteChannels = favoriteChannelIds.toList()
    }

    val favoriteChannels: List<Channel> get() = favoriteChannelIds.mapNotNull { channelById(it) }
    val recentChannels: List<Channel> get() = recentChannelIds.mapNotNull { channelById(it) }

    fun noteRecent(channel: Channel) {
        recentChannelIds.remove(channel.id)
        recentChannelIds.add(0, channel.id)
        while (recentChannelIds.size > 30) recentChannelIds.removeAt(recentChannelIds.lastIndex)
        profilePrefs?.recentChannels = recentChannelIds.toList()
    }

    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        recentSearches.removeAll { it.equals(t, true) }
        recentSearches.add(0, t)
        while (recentSearches.size > 8) recentSearches.removeAt(recentSearches.lastIndex)
        profilePrefs?.recentSearches = recentSearches.toList()
    }

    fun clearHistory() {
        recentChannelIds.clear()
        recentSearches.clear()
        resume.clear()
        profilePrefs?.apply { recentChannels = emptyList(); recentSearches = emptyList(); resume = emptyList() }
        showMessage("Watch and search history cleared")
    }

    fun isSaved(kind: SavedKind, id: String) = saved.any { it.kind == kind && it.id == id }

    fun toggleSaved(kind: SavedKind, id: String, title: String, image: String?) {
        val i = saved.indexOfFirst { it.kind == kind && it.id == id }
        if (i >= 0) {
            saved.removeAt(i)
            showMessage("Removed from your library")
        } else {
            saved.add(0, SavedItem(kind, id, title, image, System.currentTimeMillis()))
            showMessage("Added to your library")
        }
        profilePrefs?.saved = saved.toList()
    }

    fun resumeFor(key: String): ResumePoint? = resume.firstOrNull { it.key == key }

    fun saveResume(item: VodItem, positionMs: Long, durationMs: Long) {
        resume.removeAll { it.key == item.key }
        // Finished (or barely started) items drop out of "Continue watching".
        if (durationMs > 0 && positionMs > minOf(30_000L, durationMs / 10) && positionMs < durationMs * 0.95) {
            resume.add(0, ResumePoint(item.key, item.title, item.subtitle, item.image, positionMs, durationMs, System.currentTimeMillis()))
        }
        profilePrefs?.resume = resume.toList()
    }

    fun removeResume(key: String) {
        resume.removeAll { it.key == key }
        profilePrefs?.resume = resume.toList()
    }

    // =============================================================================================
    // Viewing preferences
    // =============================================================================================

    var streamFormat by mutableStateOf(StreamFormat.TS); private set
    var userAgent by mutableStateOf(Http.DEFAULT_USER_AGENT); private set
    var hiddenGroups by mutableStateOf<Set<String>>(emptySet()); private set
    var guideSort by mutableStateOf("number"); private set
    var dvrCapGb by mutableIntStateOf(20); private set
    var dvrKeepDays by mutableIntStateOf(30); private set
    var dvrPaddingMin by mutableIntStateOf(2); private set

    fun updateStreamFormat(f: StreamFormat) {
        accountPrefs?.streamFormat = f
        streamFormat = f
    }

    fun updateUserAgent(ua: String) {
        accountPrefs?.userAgent = ua
        userAgent = accountPrefs?.userAgent ?: Http.DEFAULT_USER_AGENT
        Http.userAgent = userAgent
        showMessage("User-Agent saved")
    }

    fun updateScoreBugMode(mode: ScoreBugMode) {
        profilePrefs?.scoreBugMode = mode
        scoreBugMode = mode
    }

    fun updateScoreAlerts(on: Boolean) {
        profilePrefs?.scoreAlerts = on
        scoreAlerts = on
    }

    fun updateHideScores(on: Boolean) {
        profilePrefs?.hideScores = on
        hideScores = on
    }

    fun updateCaptions(on: Boolean) {
        profilePrefs?.captions = on
        captions = on
    }

    fun updateAutoplay(on: Boolean) {
        profilePrefs?.autoplayNext = on
        autoplayNext = on
    }

    fun updateZapWithDpad(on: Boolean) {
        profilePrefs?.zapWithDpad = on
        zapWithDpad = on
    }

    fun updateGuideFilter(f: String) {
        profilePrefs?.guideFilter = f
        guideFilter = f
    }

    fun setGroupHidden(group: String, hidden: Boolean) {
        val next = if (hidden) hiddenGroups + group else hiddenGroups - group
        hiddenGroups = next
        accountPrefs?.hiddenGroups = next
    }

    fun showAllGroups(only: Collection<String>? = null) {
        val next = if (only == null) emptySet() else (catalog?.groups.orEmpty().toSet() - only.toSet())
        hiddenGroups = next
        accountPrefs?.hiddenGroups = next
    }

    fun updateGuideSort(s: String) {
        guideSort = s
        accountPrefs?.guideSort = s
    }

    fun updateDvr(capGb: Int = dvrCapGb, keepDays: Int = dvrKeepDays, paddingMin: Int = dvrPaddingMin) {
        dvrCapGb = capGb
        dvrKeepDays = keepDays
        dvrPaddingMin = paddingMin
        accountPrefs?.apply { dvrCapGb = capGb; dvrKeepDays = keepDays; dvrPaddingMin = paddingMin }
    }

    fun resetDecoderLimit() {
        DecoderBudget.reset()
        showMessage("Video decoder limit reset")
    }

    // =============================================================================================
    // Scores
    // =============================================================================================

    var games by mutableStateOf<List<Game>>(emptyList()); private set
    var tournaments by mutableStateOf<List<Tournament>>(emptyList()); private set
    var scoresLoading by mutableStateOf(true); private set
    var scoresError by mutableStateOf<String?>(null); private set
    var scoresUpdatedAt by mutableLongStateOf(0L); private set
    private val gamesByLeague = HashMap<String, List<Game>>()
    private val tournamentsByTour = HashMap<String, List<Tournament>>()

    fun gameById(id: String): Game? = games.firstOrNull { it.id == id } ?: gamesByLeague.values.firstNotNullOfOrNull { l -> l.firstOrNull { it.id == id } }
        ?: scheduleCache.values.firstNotNullOfOrNull { (_, l) -> l.firstOrNull { it.id == id } }

    fun tournamentById(id: String): Tournament? = tournaments.firstOrNull { it.id == id }

    val liveGames: List<Game> get() = games.filter { it.state == GameState.LIVE }.sortedBy { it.startMillis }

    fun upcomingGames(withinHours: Int = 36): List<Game> {
        val now = System.currentTimeMillis()
        return games.filter { it.state == GameState.PRE && it.startMillis - now < withinHours * 3_600_000L }.sortedBy { it.startMillis }
    }

    private suspend fun pollScores() {
        var cycle = 0
        while (true) {
            if (account == null) {
                delay(2_000)
                continue
            }
            val now = System.currentTimeMillis()
            val followed = Leagues.everything.filter { it.key in enabledLeagues }
            val anyLive = games.any { it.state == GameState.LIVE } || tournaments.any { it.roundInProgress }
            // While events are live, refresh just the active leagues quickly and everything else every ~minute.
            val leagues = if (!anyLive || cycle % 4 == 0) followed else followed.filter { l ->
                gamesByLeague[l.key].orEmpty().any {
                    it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMillis - now < 10 * 60_000L)
                } || tournamentsByTour[l.key].orEmpty().any { it.roundInProgress }
            }
            refreshScores(leagues)
            cycle++
            val stillLive = games.any { it.state == GameState.LIVE } || tournaments.any { it.roundInProgress }
            delay(if (stillLive) 15_000L else 60_000L)
        }
    }

    fun refreshScoresNow() {
        viewModelScope.launch { refreshScores(Leagues.everything.filter { it.key in enabledLeagues }) }
    }

    private suspend fun refreshScores(leagues: List<League>) {
        if (leagues.isEmpty()) return
        val results = coroutineScope {
            leagues.map { l ->
                async { l to runCatching<Any> { if (l.sport == "golf") scoresRepo.fetchGolf(l) else scoresRepo.fetch(l) } }
            }.awaitAll()
        }
        val before = games
        var anyOk = false
        for ((league, result) in results) {
            result.onSuccess { list ->
                @Suppress("UNCHECKED_CAST")
                if (league.sport == "golf") tournamentsByTour[league.key] = list as List<Tournament>
                else gamesByLeague[league.key] = list as List<Game>
                anyOk = true
            }
        }
        if (anyOk) {
            publishScores()
            scoresUpdatedAt = System.currentTimeMillis()
            scoresError = null
            alertFavoriteScores(before, games)
        } else if (games.isEmpty() && tournaments.isEmpty()) {
            scoresError = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }?.let(::friendly) ?: "Couldn't load scores."
        }
        scoresLoading = false
    }

    private fun publishScores() {
        games = followedLeagues.flatMap { gamesByLeague[it.key].orEmpty() }
        tournaments = followedTours.flatMap { tournamentsByTour[it.key].orEmpty() }
    }

    private fun alertFavoriteScores(before: List<Game>, after: List<Game>) {
        if (!scoreAlerts || before.isEmpty() || favorites.isEmpty()) return
        val old = before.associateBy { it.id }
        for (g in after) {
            if (g.state != GameState.LIVE || !isFavoriteGame(g)) continue
            val prev = old[g.id] ?: continue
            if (g.home.score == prev.home.score && g.away.score == prev.away.score) continue
            if (hideScores) {
                showMessage("Score update: ${g.away.shortName} at ${g.home.shortName}")
                continue
            }
            val scorer = when {
                g.home.score != prev.home.score && isFavorite(g.league.key, g.home.id) -> g.home
                g.away.score != prev.away.score && isFavorite(g.league.key, g.away.id) -> g.away
                else -> null
            }
            val line = "${g.away.abbreviation} ${g.away.score} – ${g.home.score} ${g.home.abbreviation} · ${g.shortDetail}"
            showMessage(if (scorer != null) "${scorer.shortName} score!  $line" else "Score update  $line")
        }
    }

    // =============================================================================================
    // IPTV provider
    // =============================================================================================

    var iptv by mutableStateOf<IptvStatus>(IptvStatus.NotConfigured); private set
    var accountInfo by mutableStateOf<AccountInfo?>(null); private set
    private var source: IptvSource? = null
    private var connectJob: Job? = null
    val catalog: IptvCatalog? get() = (iptv as? IptvStatus.Ready)?.catalog
    val hasProvider: Boolean get() = accountPrefs?.iptv != null
    val providerAccount: IptvAccount? get() = accountPrefs?.iptv

    /** Connects to [provider], saves it to the account when it works, then runs [onSuccess]. */
    fun connectProvider(provider: IptvAccount, onSuccess: () -> Unit) {
        connect(provider, onSuccess)
    }

    fun useLegacyProvider(onSuccess: () -> Unit) {
        val p = legacy.provider ?: return
        connect(p) {
            legacy.forgetProvider()
            onSuccess()
        }
    }

    fun skipProvider() {
        accountPrefs?.providerSkipped = true
    }

    fun removeProvider() {
        disconnectProvider()
        accountPrefs?.iptv = null
        showMessage("TV provider removed")
    }

    fun reloadChannels() {
        accountPrefs?.iptv?.let { connect(it) }
    }

    private fun disconnectProvider() {
        connectJob?.cancel()
        source = null
        accountInfo = null
        iptv = IptvStatus.NotConfigured
        movies = VodState.Idle
        series = VodState.Idle
        epg.clear()
        epgFetched.clear()
        movieInfoCache.clear()
        seriesInfoCache.clear()
        xmltvLoaded = false
    }

    private fun connect(provider: IptvAccount, onSuccess: (() -> Unit)? = null) {
        connectJob?.cancel()
        iptv = IptvStatus.Loading
        movies = VodState.Idle
        series = VodState.Idle
        epg.clear()
        epgFetched.clear()
        connectJob = viewModelScope.launch {
            try {
                val src = IptvSource.create(provider)
                val info = src.login()
                val cat = src.loadCatalog()
                source = src
                accountInfo = info
                iptv = IptvStatus.Ready(cat)
                accountPrefs?.iptv = provider
                accountPrefs?.providerSkipped = false
                onSuccess?.invoke()
                withContext(Dispatchers.Default) { cat.normNames; cat.sportsGroups }
                src.xmltvUrl?.let { loadXmltv(src, it, cat) }
                scheduleTeamRecordings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                iptv = IptvStatus.Failed(friendly(e))
            }
        }
    }

    fun channelById(id: String): Channel? = catalog?.byId?.get(id)

    fun streamCandidates(channel: Channel): List<String> = source?.streamUrls(channel, streamFormat) ?: listOfNotNull(channel.url)

    /** Channels for the live guide, honoring the filter, hidden categories and sort. */
    fun guideChannels(filter: String = guideFilter): List<Channel> {
        val cat = catalog ?: return emptyList()
        val list = when {
            filter == "favorites" -> favoriteChannels
            filter == "recent" -> recentChannels
            filter == "sports" -> cat.sportsChannels.filter { it.group !in hiddenGroups }
            filter.startsWith("group:") -> cat.byGroup[filter.removePrefix("group:")].orEmpty()
            else -> cat.channels.filter { it.group !in hiddenGroups }
        }
        return when {
            filter == "favorites" || filter == "recent" -> list
            guideSort == "name" -> list.sortedBy { it.name.lowercase() }
            else -> list
        }
    }

    suspend fun matchChannels(game: Game): List<ChannelMatch> {
        val cat = catalog ?: return emptyList()
        return withContext(Dispatchers.Default) { ChannelMatcher.match(game, cat) }
    }

    suspend fun matchChannels(t: Tournament): List<ChannelMatch> {
        val cat = catalog ?: return emptyList()
        return withContext(Dispatchers.Default) { ChannelMatcher.match(t, cat) }
    }

    suspend fun searchChannels(query: String, groups: Set<String>? = null): List<Channel> {
        val cat = catalog ?: return emptyList()
        return withContext(Dispatchers.Default) { ChannelMatcher.search(cat, query, groups) }
    }

    fun liveGameFor(channel: Channel): Game? = ChannelMatcher.findGameForChannel(channel.name, games)

    fun liveTournamentFor(channel: Channel): Tournament? = ChannelMatcher.findTournamentForChannel(channel.name, tournaments)

    suspend fun leagueChannels(league: League): List<Channel> {
        val cat = catalog ?: return emptyList()
        return withContext(Dispatchers.Default) { ChannelMatcher.leagueChannels(league, cat) }
    }

    // ---- program guide ----

    val epg = mutableStateMapOf<String, List<Program>>()
    private val epgFetched = HashMap<String, Long>()
    private val epgLimit = Semaphore(4)
    private var xmltvLoaded = false

    /** Loads this channel's guide if it isn't cached (Xtream asks per channel). */
    fun requestEpg(channel: Channel) {
        val src = source ?: return
        if (xmltvLoaded) return
        val now = System.currentTimeMillis()
        val last = epgFetched[channel.id]
        val programs = epg[channel.id]
        val stale = programs != null && programs.none { it.endMillis > now + 60 * 60_000L }
        if (last != null && now - last < 30 * 60_000L && !stale) return
        if (last != null && now - last < 60_000L) return
        epgFetched[channel.id] = now
        viewModelScope.launch {
            epgLimit.withPermit {
                runCatching { src.epg(channel) }.onSuccess { list -> if (list.isNotEmpty()) epg[channel.id] = list }
            }
        }
    }

    fun programsFor(channelId: String): List<Program> = epg[channelId].orEmpty()

    fun nowPlaying(channelId: String, now: Long = System.currentTimeMillis()): Program? =
        epg[channelId]?.firstOrNull { it.isOnNow(now) }

    fun nextProgram(channelId: String, now: Long = System.currentTimeMillis()): Program? =
        epg[channelId]?.firstOrNull { it.startMillis >= now }

    fun catchupUrl(channel: Channel, program: Program): String? = source?.catchupUrl(channel, program)

    private suspend fun loadXmltv(src: IptvSource, url: String, cat: IptvCatalog) {
        val byEpg = HashMap<String, MutableList<String>>()
        cat.channels.forEach { c -> c.epgId?.let { byEpg.getOrPut(it) { ArrayList() } += c.id } }
        if (byEpg.isEmpty()) return
        val now = System.currentTimeMillis()
        runCatching {
            Http.withStream(url) { Xmltv.parse(it, byEpg, now - 6 * 3_600_000L, now + 24 * 3_600_000L) }
        }.onSuccess { result ->
            if (source === src) {
                epg.putAll(result)
                xmltvLoaded = true
            }
        }
    }

    // ---- movies & shows ----

    var movies by mutableStateOf<VodState<Movie>>(VodState.Idle); private set
    var series by mutableStateOf<VodState<Series>>(VodState.Idle); private set
    private val movieInfoCache = HashMap<String, MovieInfo?>()
    private val seriesInfoCache = HashMap<String, SeriesInfo>()
    private var moviesById: Map<String, Movie> = emptyMap()
    private var seriesById: Map<String, Series> = emptyMap()

    val hasVod: Boolean get() = source?.hasVod == true

    fun ensureMovies() {
        val src = source ?: return
        if (movies !is VodState.Idle && movies !is VodState.Failed) return
        if (!src.hasVod) return
        movies = VodState.Loading
        viewModelScope.launch {
            movies = try {
                val lib = src.loadMovies()
                moviesById = withContext(Dispatchers.Default) { lib.items.associateBy { it.id } }
                VodState.Ready(lib)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VodState.Failed(friendly(e))
            }
        }
    }

    fun ensureSeries() {
        val src = source ?: return
        if (series !is VodState.Idle && series !is VodState.Failed) return
        if (src !is com.gameday.tv.data.XtreamSource) return
        series = VodState.Loading
        viewModelScope.launch {
            series = try {
                val lib = src.loadSeries()
                seriesById = withContext(Dispatchers.Default) { lib.items.associateBy { it.id } }
                VodState.Ready(lib)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VodState.Failed(friendly(e))
            }
        }
    }

    fun movieById(id: String): Movie? = moviesById[id]
    fun seriesById(id: String): Series? = seriesById[id]

    suspend fun movieInfo(movie: Movie): MovieInfo? {
        if (movieInfoCache.containsKey(movie.id)) return movieInfoCache[movie.id]
        val info = runCatching { source?.movieInfo(movie) }.getOrNull()
        movieInfoCache[movie.id] = info
        return info
    }

    suspend fun seriesInfo(s: Series): SeriesInfo {
        seriesInfoCache[s.id]?.let { return it }
        val src = source ?: return SeriesInfo(s, emptyList(), emptyMap())
        return src.seriesInfo(s).also { seriesInfoCache[s.id] = it }
    }

    // =============================================================================================
    // Playback
    // =============================================================================================

    var playback by mutableStateOf<PlayRequest?>(null); private set

    fun play(channels: List<Channel>, index: Int, eventId: String?) {
        if (channels.isEmpty()) return
        if (iptv !is IptvStatus.Ready) {
            showMessage("Connect a TV provider in Settings to watch")
            return
        }
        playback = PlayRequest.Live(channels, index.coerceIn(channels.indices), eventId)
        if (screen !is Screen.Player) navigate(Screen.Player)
    }

    fun playChannel(channel: Channel, list: List<Channel> = listOf(channel), eventId: String? = null) {
        val idx = list.indexOfFirst { it.id == channel.id }
        if (idx < 0) play(listOf(channel) + list, 0, eventId) else play(list, idx, eventId)
    }

    /** Watch a game on its best-matching channel; the other matches are a CH+/CH− away. */
    fun watchGame(game: Game) {
        viewModelScope.launch {
            val matches = matchChannels(game)
            if (matches.isEmpty()) {
                openGame(game)
                showMessage("No channel found for this game. Pick one below.")
            } else {
                play(matches.map { it.channel }, 0, game.id)
            }
        }
    }

    fun watchTournament(t: Tournament) {
        viewModelScope.launch {
            val matches = matchChannels(t)
            if (matches.isEmpty()) openTournament(t) else play(matches.map { it.channel }, 0, t.id)
        }
    }

    fun playCatchup(channel: Channel, program: Program) {
        val url = catchupUrl(channel, program)
        if (url == null) {
            showMessage("This airing isn't available to replay")
            return
        }
        playback = PlayRequest.Catchup(channel, program, url)
        if (screen !is Screen.Player) navigate(Screen.Player)
    }

    fun playMovie(movie: Movie) {
        val url = source?.movieUrl(movie) ?: movie.url ?: return
        playVod(listOf(VodItem("movie:${movie.id}:${movie.ext}", movie.name, "Movie", movie.poster, url)), 0)
    }

    fun playEpisodes(info: SeriesInfo, episode: Episode) {
        val src = source ?: return
        val all = info.allEpisodes
        val queue = all.mapNotNull { e ->
            src.episodeUrl(e)?.let { url ->
                VodItem("ep:${info.series.id}:${e.id}:${e.ext}", info.series.name, "S${e.season} E${e.number} · ${e.title}", e.image ?: info.series.poster, url)
            }
        }
        val idx = all.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        if (queue.isNotEmpty()) playVod(queue, idx.coerceAtMost(queue.lastIndex))
    }

    /** Resume something from "Continue watching". */
    fun playResume(point: ResumePoint) {
        val parts = point.key.split(':')
        val src = source
        when (parts.firstOrNull()) {
            "movie" -> {
                val movie = movieById(parts.getOrNull(1).orEmpty())
                val url = when {
                    movie != null -> src?.movieUrl(movie) ?: movie.url
                    src != null && parts.size >= 3 -> src.movieUrl(Movie(parts[1], point.title, point.image, null, null, 0, parts[2]))
                    else -> null
                } ?: return showMessage("Connect your TV provider to keep watching")
                playVod(listOf(VodItem(point.key, point.title, point.subtitle, point.image, url)), 0)
            }
            "ep" -> {
                if (src == null || parts.size < 4) return showMessage("Connect your TV provider to keep watching")
                val ep = Episode(parts[2], parts[1], 0, 0, point.subtitle, null, point.image, null, parts[3])
                val url = src.episodeUrl(ep) ?: return
                playVod(listOf(VodItem(point.key, point.title, point.subtitle, point.image, url)), 0)
            }
            "rec" -> recordingsAll.firstOrNull { it.id == parts.getOrNull(1) }?.let { playRecording(it) }
        }
    }

    private fun playVod(queue: List<VodItem>, index: Int) {
        playback = PlayRequest.Vod(queue, index)
        if (screen !is Screen.Player) navigate(Screen.Player)
    }

    fun playRecording(r: Recording) {
        if (r.status == RecStatus.RECORDING) {
            // Still being written: watch the channel live instead.
            channelById(r.channelId)?.let { playChannel(it) } ?: showMessage("This recording is still in progress")
            return
        }
        if (r.file == null || !java.io.File(r.file).exists()) {
            showMessage("This recording's file is missing")
            return
        }
        playback = PlayRequest.Rec(r)
        if (screen !is Screen.Player) navigate(Screen.Player)
    }

    fun nextVod(): Boolean {
        val p = playback as? PlayRequest.Vod ?: return false
        if (p.index + 1 >= p.queue.size) return false
        playback = p.copy(index = p.index + 1)
        return true
    }

    fun jumpVod(index: Int) {
        val p = playback as? PlayRequest.Vod ?: return
        if (index in p.queue.indices) playback = p.copy(index = index)
    }

    fun zap(delta: Int) {
        val p = playback as? PlayRequest.Live ?: return
        if (p.channels.size < 2) return
        playback = p.copy(index = Math.floorMod(p.index + delta, p.channels.size), eventId = null)
    }

    fun zapTo(index: Int) {
        val p = playback as? PlayRequest.Live ?: return
        if (index in p.channels.indices) playback = p.copy(index = index, eventId = null)
    }

    /** From catch-up back to the live channel. */
    fun goLive() {
        val p = playback as? PlayRequest.Catchup ?: return
        playback = PlayRequest.Live(listOf(p.channel), 0, null)
    }

    // =============================================================================================
    // Multiview
    // =============================================================================================

    val multiview = mutableStateListOf<Channel?>(null, null, null, null)
    var multiviewAudio by mutableIntStateOf(0); private set
    var multiviewLayout by mutableStateOf(MultiviewLayout.ONE); private set

    val multiviewCount: Int get() = (0 until multiviewLayout.screens).count { multiview[it] != null }

    /** Most streams the provider allows at once (unknown = 4). */
    val maxStreams: Int get() = accountInfo?.maxConnections?.toIntOrNull()?.coerceIn(1, 4) ?: 4

    private fun clearMultiview() {
        for (i in multiview.indices) multiview[i] = null
        multiviewLayout = MultiviewLayout.ONE
        multiviewAudio = 0
    }

    /** YouTube TV style: pick up to four, then watch them together. */
    fun startMultiview(channels: List<Channel>) {
        val picks = channels.distinctBy { it.id }.take(4)
        if (picks.isEmpty()) return
        clearMultiview()
        picks.forEachIndexed { i, c -> multiview[i] = c }
        multiviewLayout = MultiviewLayout.forCount(picks.size)
        multiviewAudio = 0
        openMultiview()
    }

    /** "Multiview" from a channel: start the builder with it already picked, or add it to the running one. */
    fun multiviewWith(channel: Channel?) {
        if (multiviewCount > 0 && channel != null) {
            addToMultiview(channel)
            openMultiview()
        } else {
            builderSeed = listOfNotNull(channel)
            if (screen is Screen.Player) replace(Screen.MultiviewBuilder) else navigate(Screen.MultiviewBuilder)
        }
    }

    var builderSeed: List<Channel> = emptyList()

    /** Multiview starting from a game: its best channel is pre-selected. */
    fun multiviewWithGame(game: Game) {
        viewModelScope.launch { multiviewWith(matchChannels(game).firstOrNull()?.channel) }
    }

    fun addToMultiview(channel: Channel) {
        if ((0 until multiviewLayout.screens).any { multiview[it]?.id == channel.id }) {
            showMessage("${channel.name} is already in Multiview")
            return
        }
        if (multiviewCount == 0) clearMultiview()
        val plan = MultiviewRules.slotForNew(multiview.toList(), multiviewLayout, multiviewAudio)
        multiviewLayout = plan.layout
        multiview[plan.slot] = channel
        if (multiviewCount == 1) multiviewAudio = plan.slot
    }

    fun firstEmptyMultiviewSlot(): Int? = (0 until multiviewLayout.screens).firstOrNull { multiview[it] == null }

    fun setMultiviewSlot(slot: Int, channel: Channel?) {
        multiview[slot] = channel
        if (channel != null && multiviewCount == 1) multiviewAudio = slot
        if (channel == null && multiviewAudio == slot) {
            multiviewAudio = (0 until multiviewLayout.screens).firstOrNull { multiview[it] != null } ?: 0
        }
    }

    fun changeMultiviewLayout(layout: MultiviewLayout) {
        val (slots, audio) = MultiviewRules.compact(multiview.toList(), multiviewAudio)
        for (i in slots.indices) multiview[i] = slots[i]
        multiviewLayout = layout
        multiviewAudio = if (audio < layout.screens) audio else 0
    }

    fun selectMultiviewAudio(slot: Int) {
        if (multiview[slot] != null) multiviewAudio = slot
    }

    fun openMultiview() {
        if (iptv !is IptvStatus.Ready) {
            showMessage("Connect a TV provider in Settings to watch")
            return
        }
        // Never stack a full-screen player under multiview: it would hold an extra connection.
        when (screen) {
            is Screen.Player, is Screen.MultiviewBuilder -> replace(Screen.Multiview)
            is Screen.Multiview -> Unit
            else -> navigate(Screen.Multiview)
        }
    }

    fun fullscreenFromMultiview(slot: Int) {
        val channels = (0 until multiviewLayout.screens).mapNotNull { multiview[it] }
        val ch = multiview[slot] ?: return
        play(channels, channels.indexOf(ch), null)
    }

    /** Best channel for each live event, for one-tap multiview picks. */
    suspend fun liveSuggestions(): List<Suggestion> {
        val cat = catalog ?: return emptyList()
        val liveTournaments = tournaments.filter { it.roundInProgress }
        val live = liveGames
        return withContext(Dispatchers.Default) {
            val out = ArrayList<Suggestion>()
            val used = HashSet<String>()
            for (t in liveTournaments) {
                val best = ChannelMatcher.match(t, cat, limit = 5).firstOrNull { it.channel.id !in used } ?: continue
                used += best.channel.id
                out += Suggestion(best.channel, t.name, "${t.tour.label} · ${t.detail}", t.id)
            }
            for (g in live) {
                val best = ChannelMatcher.match(g, cat, limit = 5).firstOrNull { it.channel.id !in used } ?: continue
                used += best.channel.id
                val score = if (hideScores) "" else " · ${g.away.abbreviation} ${g.away.score}-${g.home.score} ${g.home.abbreviation}"
                out += Suggestion(best.channel, g.title, "${g.league.label} · ${g.shortDetail}$score", g.id)
            }
            out
        }
    }

    /** Leagues with 2+ live games that each have a channel: ready-made multiviews for Home. */
    suspend fun multiviewPresets(): List<MultiviewPreset> {
        val cat = catalog ?: return emptyList()
        val byLeague = liveGames.groupBy { it.league }
        return withContext(Dispatchers.Default) {
            byLeague.mapNotNull { (league, list) ->
                if (list.size < 2) return@mapNotNull null
                val used = HashSet<String>()
                val pairs = list.mapNotNull { g ->
                    ChannelMatcher.match(g, cat, limit = 5).firstOrNull { it.channel.id !in used }?.let { used += it.channel.id; g to it.channel }
                }.take(maxStreams)
                if (pairs.size < 2) null
                else MultiviewPreset("${league.label} multiview", "${pairs.size} live games", pairs.map { it.first }, pairs.map { it.second })
            }
        }
    }

    /** Live and upcoming events for a sport (or the user's teams when null) with their best channels. */
    suspend fun eventStreams(leagueKey: String?, perEvent: Int = 3, maxEvents: Int = 15): List<EventStreams> {
        val cat = catalog ?: return emptyList()
        val now = System.currentTimeMillis()
        val soon = now + 12 * 60 * 60_000L
        val golf = Leagues.golf.firstOrNull { it.key == leagueKey }
        return withContext(Dispatchers.Default) {
            if (golf != null) {
                tournaments.filter { it.tour.key == golf.key && it.state != GameState.FINAL }.map { t ->
                    EventStreams(t.id, t.name, t.detail, t.roundInProgress, ChannelMatcher.match(t, cat, perEvent))
                }
            } else {
                val pool = if (leagueKey == null) favoriteGames else games.filter { it.league.key == leagueKey }
                pool.filter { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMillis < soon) }
                    .sortedWith(compareBy<Game>({ stateOrder(it) }, { it.startMillis }))
                    .take(maxEvents)
                    .map { g ->
                        val score = if (g.state == GameState.PRE || hideScores) "" else " · ${g.away.score}-${g.home.score}"
                        EventStreams(g.id, "${g.away.shortName} at ${g.home.shortName}", "${g.league.label} · ${statusText(g)}$score",
                            g.state == GameState.LIVE, ChannelMatcher.match(g, cat, perEvent))
                    }
            }
        }
    }

    // =============================================================================================
    // Recordings (DVR)
    // =============================================================================================

    private var recordingsAll by mutableStateOf<List<Recording>>(emptyList())

    /** This account's recordings, newest first; cancelled ones are kept only as markers. */
    val recordings: List<Recording>
        get() = recordingsAll.filter { it.accountId == account?.id && it.status != RecStatus.CANCELLED }

    fun recordingForEvent(eventId: String): Recording? =
        recordings.firstOrNull { it.eventId == eventId && it.status in ACTIVE_REC }

    fun recordingForProgram(channelId: String, startMillis: Long): Recording? =
        recordings.firstOrNull { it.channelId == channelId && it.status in ACTIVE_REC && startMillis in it.startMillis..it.endMillis && it.eventId == null }

    private val ACTIVE_REC = setOf(RecStatus.SCHEDULED, RecStatus.RECORDING, RecStatus.DONE)

    private fun recordingUrls(channel: Channel): List<String> =
        source?.streamUrls(channel, StreamFormat.TS) ?: listOfNotNull(channel.url)

    fun recordGame(game: Game, channel: Channel? = null) {
        viewModelScope.launch {
            val ch = channel ?: matchChannels(game).firstOrNull()?.channel
            if (ch == null) {
                showMessage("No channel found to record this game")
                return@launch
            }
            addRecording(gameRecording(game, ch, auto = false))
            showMessage(if (game.state == GameState.LIVE) "Recording ${game.title}" else "Will record ${game.title} on ${ch.name}")
        }
    }

    private fun gameRecording(game: Game, ch: Channel, auto: Boolean): Recording {
        val acct = account!!
        val start = maxOf(game.startMillis - dvrPaddingMin * 60_000L, System.currentTimeMillis())
        return Recording(
            id = UUID.randomUUID().toString(),
            accountId = acct.id,
            title = game.title,
            subtitle = "${game.league.label} · ${formatStart(game.startMillis)}",
            channelId = ch.id,
            channelName = ch.name,
            channelLogo = ch.logo,
            urls = recordingUrls(ch),
            startMillis = start,
            endMillis = game.startMillis + game.league.typicalMinutes * 60_000L,
            status = RecStatus.SCHEDULED,
            eventId = game.id,
            image = game.home.logo,
            auto = auto,
        )
    }

    fun recordTournament(t: Tournament, channel: Channel) {
        val acct = account ?: return
        val now = System.currentTimeMillis()
        addRecording(
            Recording(
                id = UUID.randomUUID().toString(), accountId = acct.id, title = t.name, subtitle = "${t.tour.label} · ${t.detail}",
                channelId = channel.id, channelName = channel.name, channelLogo = channel.logo, urls = recordingUrls(channel),
                startMillis = now, endMillis = now + t.tour.typicalMinutes * 60_000L, status = RecStatus.SCHEDULED, eventId = t.id,
            ),
        )
        showMessage("Recording ${t.name}")
    }

    fun recordProgram(channel: Channel, program: Program) {
        val acct = account ?: return
        val pad = dvrPaddingMin * 60_000L
        addRecording(
            Recording(
                id = UUID.randomUUID().toString(), accountId = acct.id, title = program.title,
                subtitle = "${cleanChannelName(channel.name)} · ${formatStart(program.startMillis)}",
                channelId = channel.id, channelName = channel.name, channelLogo = channel.logo, urls = recordingUrls(channel),
                startMillis = maxOf(program.startMillis - pad, System.currentTimeMillis()), endMillis = program.endMillis + pad,
                status = RecStatus.SCHEDULED, image = channel.logo,
            ),
        )
        showMessage(if (program.startMillis <= System.currentTimeMillis()) "Recording ${program.title}" else "Will record ${program.title}")
    }

    fun recordNow(channel: Channel, minutes: Int) {
        val acct = account ?: return
        val now = System.currentTimeMillis()
        val title = nowPlaying(channel.id)?.title ?: channel.name
        addRecording(
            Recording(
                id = UUID.randomUUID().toString(), accountId = acct.id, title = title, subtitle = "${cleanChannelName(channel.name)} · ${formatStart(now)}",
                channelId = channel.id, channelName = channel.name, channelLogo = channel.logo, urls = recordingUrls(channel),
                startMillis = now, endMillis = now + minutes * 60_000L, status = RecStatus.SCHEDULED, image = channel.logo,
            ),
        )
        showMessage("Recording ${channel.name} for ${durationText(minutes * 60_000L)}")
    }

    private fun addRecording(r: Recording) {
        RecordingStore.upsert(r)
        RecordScheduler.kick(context)
        if (!RecordScheduler.canWakeExactly(context) && r.startMillis > System.currentTimeMillis() + 60_000) {
            showMessage("Tip: allow alarms for GameDay TV in Settings › Recordings so recordings start on time")
        }
    }

    fun cancelRecording(r: Recording) {
        when (r.status) {
            RecStatus.RECORDING -> RecordScheduler.stop(context, r.id)
            // Auto-scheduled games stay as a marker so they aren't scheduled again.
            RecStatus.SCHEDULED -> if (r.auto) RecordingStore.update(r.id) { it.copy(status = RecStatus.CANCELLED) } else RecordingStore.remove(r.id)
            else -> Unit
        }
        RecordScheduler.reschedule(context)
        showMessage(if (r.status == RecStatus.RECORDING) "Stopped recording" else "Recording cancelled")
    }

    fun deleteRecording(r: Recording) {
        if (r.status == RecStatus.RECORDING) RecordScheduler.stop(context, r.id)
        RecordingStore.remove(r.id)
        removeResume("rec:${r.id}")
        showMessage("Recording deleted")
    }

    fun recordingsBytes(): Long = account?.id?.let { RecordingStore.usedBytes(it) } ?: 0L

    fun recordingsFreeBytes(): Long = RecordingStore.freeBytes()

    val canWakeExactly: Boolean get() = RecordScheduler.canWakeExactly(context)

    /** Schedules upcoming games for library teams with "Record all games" on. */
    private suspend fun scheduleTeamRecordings() {
        val acct = account ?: return
        val cat = catalog ?: return
        val teams = favorites.filter { it.record }
        if (teams.isEmpty()) return
        val now = System.currentTimeMillis()
        val horizon = now + 4 * 86_400_000L
        for (team in teams) {
            val league = Leagues.byKey(team.leagueKey) ?: continue
            if (league.sport == "golf") continue
            val upcoming = runCatching { teamSchedule(league, team.id) }.getOrDefault(emptyList())
                .filter { it.state != GameState.FINAL && it.startMillis in (now - 3 * 3_600_000L)..horizon }
            for (g in upcoming) {
                if (recordingsAll.any { it.accountId == acct.id && it.eventId == g.id }) continue
                val ch = withContext(Dispatchers.Default) { ChannelMatcher.match(g, cat, 1).firstOrNull()?.channel } ?: continue
                RecordingStore.upsert(gameRecording(g, ch, auto = true))
            }
        }
        RecordScheduler.kick(context)
    }

    private suspend fun housekeeping() {
        var cycle = 0
        while (true) {
            delay(60_000)
            val acct = account ?: continue
            RecordScheduler.kick(context)
            if (cycle % 60 == 0) {
                RecordingStore.enforceLimits(acct.id, dvrCapGb, dvrKeepDays)
                scheduleTeamRecordings()
            }
            cycle++
        }
    }

    // =============================================================================================
    // Search
    // =============================================================================================

    private val allTeams = HashMap<String, List<FavoriteTeam>>()

    suspend fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.length < 2) return SearchResults()
        val n = ChannelMatcher.norm(q)
        // Teams of the followed leagues, fetched once.
        coroutineScope {
            followedLeagues.filter { it.key !in allTeams }.map { l ->
                async { runCatching { loadTeams(l) }.getOrNull()?.let { allTeams[l.key] = it } }
            }.awaitAll()
        }
        return withContext(Dispatchers.Default) {
            fun hit(s: String) = ChannelMatcher.norm(s).contains(n)
            val teams = allTeams.values.flatten().filter { hit(it.name) || it.abbreviation.equals(q, true) }.take(20)
            val gamesHit = games.filter { g ->
                hit(g.home.displayName) || hit(g.away.displayName) || g.home.abbreviation.equals(q, true) || g.away.abbreviation.equals(q, true) || hit(g.league.label)
            }.sortedWith(compareBy<Game>({ stateOrder(it) }, { it.startMillis })).take(20)
            val tours = tournaments.filter { hit(it.name) || hit(it.tour.label) || it.leaders.take(10).any { p -> hit(p.name) } }
            val cat = catalog
            val channels = if (cat != null) ChannelMatcher.search(cat, q, null, limit = 60) else emptyList()
            val now = System.currentTimeMillis()
            val programs = epg.values.asSequence().flatten().filter { it.endMillis > now && hit(it.title) }
                .sortedBy { it.startMillis }.take(40).toList()
            val m = (movies as? VodState.Ready)?.library?.items.orEmpty().asSequence().filter { hit(it.name) }.take(40).toList()
            val s = (series as? VodState.Ready)?.library?.items.orEmpty().asSequence().filter { hit(it.name) }.take(40).toList()
            SearchResults(gamesHit, tours, teams, channels, programs, m, s)
        }
    }

    // =============================================================================================

    // Last in the class: everything above must be initialized before an account is entered.
    init {
        RecordingStore.init(app)
        DecoderBudget.init(app)
        val current = accountStore.currentId?.let { accountStore.byId(it) }
        if (current == null) resetTo(Screen.Welcome) else enterAccount(current)
        viewModelScope.launch { pollScores() }
        viewModelScope.launch { housekeeping() }
        viewModelScope.launch { RecordingStore.all.collect { all -> recordingsAll = all } }
    }

    fun friendly(e: Throwable): String = when (e) {
        is UnknownHostException -> "Can't reach the server. Check the address and your internet connection."
        is SocketTimeoutException -> "The server took too long to respond. Try again."
        is ConnectException -> "Connection refused. Check the server address and port."
        is SSLException -> "Secure connection failed. Try http:// instead of https://."
        else -> e.message ?: e.javaClass.simpleName
    }
}

private fun <T> MutableList<T>.replaceWith(items: List<T>) {
    clear()
    addAll(items)
}
