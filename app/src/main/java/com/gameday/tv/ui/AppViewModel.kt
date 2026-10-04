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
import com.gameday.tv.data.DelayedHistory
import com.gameday.tv.data.ProviderEntry
import com.gameday.tv.data.Providers
import com.gameday.tv.data.ScopedSource
import com.gameday.tv.data.ScoreSnapshots
import com.gameday.tv.data.SportPick
import com.gameday.tv.data.SubtitleStyle
import com.gameday.tv.data.VodLibrary
import com.gameday.tv.data.AccountPrefs
import com.gameday.tv.data.AccountStore
import com.gameday.tv.data.AppAccount
import com.gameday.tv.data.Channel
import com.gameday.tv.data.ChannelMatch
import com.gameday.tv.data.ChannelMatcher
import com.gameday.tv.data.ContinueWatching
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

    /** Streaming add-ons and TorBox (On Demand). */
    val addons = AddonsModel(this)

    // =============================================================================================
    // Navigation
    // =============================================================================================

    private val backStack = mutableStateListOf<Screen>(Screen.Splash)
    val screen: Screen get() = backStack.last()
    val canGoBack: Boolean get() = backStack.size > 1
    var tab by mutableStateOf(Tab.FIRST); private set

    /** Remembers the focused item per screen, so coming back lands where the viewer left off. */
    val focusMemory = HashMap<String, String>()

    /**
     * Screen key whose remembered focus should be restored (set when navigating back to it, or when
     * a menu closes). State, so cards already on screen see it; it expires after a moment so a card
     * that only scrolls into view later can't pull focus to itself.
     */
    var restoreFocusFor by mutableStateOf<String?>(null)
    private var restoreJob: Job? = null

    private fun restoreFocusTo(key: String?) {
        restoreJob?.cancel()
        restoreFocusFor = key
        if (key != null) restoreJob = viewModelScope.launch {
            delay(2_000)
            if (restoreFocusFor == key) restoreFocusFor = null
        }
    }

    /** The key [rememberFocus] uses for what's on screen (Main's tabs remember focus per tab). */
    private val focusScope: String get() = if (screen == Screen.Main) "main:$tab" else screen.key

    var message by mutableStateOf<String?>(null); private set
    private var messageJob: Job? = null
    var dialog by mutableStateOf<AppDialog?>(null); private set

    fun navigate(s: Screen) {
        if (screen.key == s.key) return
        restoreFocusTo(null)
        backStack.add(s)
    }

    fun back() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            restoreFocusTo(focusScope)
        }
    }

    fun replace(s: Screen) {
        backStack[backStack.lastIndex] = s
    }

    private fun resetTo(s: Screen) {
        backStack.clear()
        backStack.add(s)
        restoreFocusTo(null)
    }

    /** When a tab opens, should its content take focus? (Not while the viewer browses the top bar.) */
    var tabWantsFocus = true

    fun selectTab(t: Tab, focusContent: Boolean = true) {
        tabWantsFocus = focusContent
        if (t != tab) stopTrailer()
        tab = t
        heroFocus = null
        if (screen != Screen.Main) resetTo(Screen.Main)
        // The library and On Demand have no live video header.
        if (t == Tab.LIBRARY || t == Tab.ON_DEMAND) stopMainStream()
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
        if (dialog == null) return
        dialog = null
        // The focused menu row is gone; put focus back on the card the menu was opened from instead
        // of letting the next button press land on whatever Android picks. (An action that opens
        // another screen calls navigate() right after, which cancels this.)
        if (focusMemory[focusScope] != null) restoreFocusTo(focusScope)
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
        tab = Tab.FIRST
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

        disconnectProviders()
        providers.addAll(prefs.providers)
        providers.filter { it.enabled }.forEach { connectOne(it) }
        addons.enter(prefs)

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
        stopMainStream()
        stopTrailer()
        disconnectProviders()
        addons.leave()
        account = null
        accountPrefs = null
        profile = null
        profilePrefs = null
        profiles.clear()
        playback = null
        clearMultiview()
        focusMemory.clear()
        tab = Tab.FIRST
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
            tab = Tab.FIRST
            resetTo(Screen.Main)
            showMessage("You're all set, ${account?.firstName ?: ""}")
        }
    }

    fun selectProfile(p: Profile) {
        selectProfileInternal(p)
        accountPrefs?.lastProfileId = p.id
        tabWantsFocus = true
        tab = Tab.FIRST
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
        scoreDelaySec = pp.scoreDelaySec
        backgroundVideo = pp.backgroundVideo
        trailerPreviews = pp.trailerPreviews
        guideFilter = pp.guideFilter
        subtitleStyle = pp.subtitleStyle
        subtitleLanguage = pp.subtitleLanguage
        playerButtons = pp.playerButtons
        recentOnDemandSearches.replaceWith(pp.recentOnDemandSearches)
        focusMemory.clear()
        alertedScores.clear()
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
    var scoreDelaySec by mutableIntStateOf(60); private set
    /** "sound", "muted" or "off". */
    var backgroundVideo by mutableStateOf("sound"); private set
    /** On Demand trailers on the focused card: "muted", "sound" or "off". */
    var trailerPreviews by mutableStateOf("muted"); private set
    var guideFilter by mutableStateOf("sports"); private set
    var subtitleStyle by mutableStateOf(SubtitleStyle()); private set
    /** ISO 639-1 code of the subtitle language picked automatically. */
    var subtitleLanguage by mutableStateOf("en"); private set
    /** "small", "medium" or "large". */
    var playerButtons by mutableStateOf("medium"); private set
    val recentOnDemandSearches = mutableStateListOf<String>()

    private val teamsCache = HashMap<String, List<FavoriteTeam>>()
    private val scheduleCache = HashMap<String, Pair<Long, List<Game>>>()
    private val teamInfoCache = HashMap<String, TeamInfo>()

    val followedLeagues: List<League> get() = Leagues.all.filter { it.key in enabledLeagues }
    val followedTours: List<League> get() = Leagues.golf.filter { it.key in enabledLeagues }
    val followedPicks: List<SportPick> get() = Leagues.picks.filter { isSportEnabled(it) }

    fun isSportEnabled(pick: SportPick): Boolean = pick.leagueKeys.any { it in enabledLeagues }

    /** Follows or unfollows a sport (Golf covers every tour). */
    fun setSportEnabled(pick: SportPick, enabled: Boolean) {
        val next = if (enabled) enabledLeagues + pick.leagueKeys else enabledLeagues - pick.leagueKeys
        if (next.isEmpty()) {
            showMessage("Keep at least one sport")
            return
        }
        enabledLeagues = next
        profilePrefs?.enabledLeagues = next
        publishScores()
        if (enabled) viewModelScope.launch { refreshScores(pick.leagues) }
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
        recentOnDemandSearches.clear()
        resume.clear()
        profilePrefs?.apply { recentChannels = emptyList(); recentSearches = emptyList(); recentOnDemandSearches = emptyList(); resume = emptyList() }
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

    /** Continue watching: one card per show (its latest episode), plus movies and recordings. */
    val continueWatching: List<ResumePoint> get() = ContinueWatching.latestPerShow(resume)

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

    /** Takes a card out of Continue watching: for a show, every episode's point goes with it. */
    fun removeFromContinueWatching(key: String) {
        val group = ContinueWatching.groupKey(key)
        resume.removeAll { ContinueWatching.groupKey(it.key) == group }
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

    fun updateSubtitleStyle(style: SubtitleStyle) {
        profilePrefs?.subtitleStyle = style
        subtitleStyle = style
    }

    fun updateSubtitleLanguage(lang: String) {
        profilePrefs?.subtitleLanguage = lang
        subtitleLanguage = lang
    }

    fun updatePlayerButtons(size: String) {
        profilePrefs?.playerButtons = size
        playerButtons = size
    }

    fun addRecentOnDemandSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        recentOnDemandSearches.removeAll { it.equals(t, true) }
        recentOnDemandSearches.add(0, t)
        while (recentOnDemandSearches.size > 8) recentOnDemandSearches.removeAt(recentOnDemandSearches.lastIndex)
        profilePrefs?.recentOnDemandSearches = recentOnDemandSearches.toList()
    }

    fun updateAutoplay(on: Boolean) {
        profilePrefs?.autoplayNext = on
        autoplayNext = on
    }

    fun updateScoreDelay(seconds: Int) {
        profilePrefs?.scoreDelaySec = seconds
        scoreDelaySec = seconds
        alertedScores.clear()
    }

    fun updateTrailerPreviews(mode: String) {
        profilePrefs?.trailerPreviews = mode
        trailerPreviews = mode
        if (mode == "off") stopTrailer() else trailerOrNull?.volume = if (mode == "sound") 1f else 0f
    }

    fun updateBackgroundVideo(mode: String) {
        profilePrefs?.backgroundVideo = mode
        backgroundVideo = mode
        if (mode == "off") stopMainStream() else if (screen != Screen.Player) mainStreamOrNull?.volume = if (mode == "sound") 1f else 0f
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

    /** Per-stream decoding choices, keyed by [DecoderSlot]. Saved for the device, not the account. */
    private val decoderModes = mutableStateMapOf<String, DecoderMode>()

    /** Which Multiview screens are decoding in software right now, for the screen menu. */
    val multiviewSoftware = mutableStateMapOf<Int, Boolean>()

    fun decoderMode(slot: String): DecoderMode = decoderModes[slot] ?: DecoderBudget.mode(slot)

    fun setDecoderMode(slot: String, mode: DecoderMode) {
        DecoderBudget.setMode(slot, mode)
        decoderModes[slot] = mode
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
            val now = System.currentTimeMillis()
            scoresUpdatedAt = now
            scoresError = null
            games.forEach { if (it.state != GameState.PRE) gameHistory.record(it.id, it, now, ScoreSnapshots::sameGame) }
            tournaments.forEach { if (it.state == GameState.LIVE) tourHistory.record(it.id, it, now, ScoreSnapshots::sameTournament) }
            checkScoreAlerts()
        } else if (games.isEmpty() && tournaments.isEmpty()) {
            scoresError = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }?.let(::friendly) ?: "Couldn't load scores."
        }
        scoresLoading = false
    }

    private fun publishScores() {
        games = followedLeagues.flatMap { gamesByLeague[it.key].orEmpty() }
        tournaments = followedTours.flatMap { tournamentsByTour[it.key].orEmpty() }
    }

    // ---- delayed scores: the bug and alerts trail the scoreboard to match the stream ----

    private val gameHistory = DelayedHistory<Game>()
    private val tourHistory = DelayedHistory<Tournament>()
    /** Ticks every few seconds so delayed scores move on even between scoreboard refreshes. */
    var scoreClock by mutableLongStateOf(System.currentTimeMillis()); private set
    private val alertedScores = HashMap<String, Pair<String, String>>()

    /** [game] as it was [scoreDelaySec] ago (what the stream is showing now). */
    fun delayedGame(game: Game?): Game? {
        game ?: return null
        if (scoreDelaySec <= 0) return game
        return gameHistory.at(game.id, scoreClock - scoreDelaySec * 1000L) ?: game
    }

    fun delayedTournament(t: Tournament?): Tournament? {
        t ?: return null
        if (scoreDelaySec <= 0) return t
        return tourHistory.at(t.id, scoreClock - scoreDelaySec * 1000L) ?: t
    }

    private suspend fun runScoreClock() {
        while (true) {
            delay(5_000)
            scoreClock = System.currentTimeMillis()
            if (account != null) checkScoreAlerts()
        }
    }

    /** Alerts for library teams' games, from the delayed scores so the stream isn't spoiled. */
    private fun checkScoreAlerts() {
        if (favorites.isEmpty()) return
        for (live in games) {
            if (live.state == GameState.PRE || !isFavoriteGame(live)) continue
            val g = delayedGame(live) ?: continue
            if (g.state != GameState.LIVE) continue
            val prev = alertedScores.put(g.id, g.away.score to g.home.score)
            if (prev == null || !scoreAlerts) continue
            val (awayBefore, homeBefore) = prev
            if (g.away.score == awayBefore && g.home.score == homeBefore) continue
            if (hideScores) {
                showMessage("Score update: ${g.away.shortName} at ${g.home.shortName}")
                continue
            }
            val scorer = when {
                g.home.score != homeBefore && isFavorite(g.league.key, g.home.id) -> g.home
                g.away.score != awayBefore && isFavorite(g.league.key, g.away.id) -> g.away
                else -> null
            }
            val line = "${g.away.abbreviation} ${g.away.score} – ${g.home.score} ${g.home.abbreviation} · ${g.shortDetail}"
            showMessage(if (scorer != null) "${scorer.shortName} score!  $line" else "Score update  $line")
        }
    }

    // =============================================================================================
    // IPTV provider
    // =============================================================================================

    /** All providers together: Ready once any of them has loaded. */
    var iptv by mutableStateOf<IptvStatus>(IptvStatus.NotConfigured); private set
    val catalog: IptvCatalog? get() = (iptv as? IptvStatus.Ready)?.catalog

    /** The account's IPTV logins and playlists, in the order they were added. */
    val providers = mutableStateListOf<ProviderEntry>()
    /** Each provider's own status (keyed by [ProviderEntry.id]). */
    val providerStatus = mutableStateMapOf<String, IptvStatus>()
    val providerInfo = mutableStateMapOf<String, AccountInfo>()
    private val sources = LinkedHashMap<String, ScopedSource>()
    private val catalogs = LinkedHashMap<String, IptvCatalog>()
    private val connectJobs = HashMap<String, Job>()

    val hasProvider: Boolean get() = providers.isNotEmpty()

    /** Status of the login being tried on the provider screen. */
    var providerTest by mutableStateOf<IptvStatus>(IptvStatus.NotConfigured); private set
    private var testJob: Job? = null

    private fun sourceFor(channel: Channel): ScopedSource? =
        sources[channel.providerId] ?: sources.values.firstOrNull { it.prefix == Providers.prefixOf(channel.id) }

    /** The provider of a scoped movie / show / episode id. */
    private fun sourceForId(id: String): ScopedSource? {
        val prefix = Providers.prefixOf(id)
        return sources.values.firstOrNull { it.prefix == prefix }
    }

    fun providerName(channel: Channel): String? =
        if (providers.size < 2) null else providers.firstOrNull { it.id == channel.providerId }?.name

    /**
     * Tries [account] and, when it works, saves it (as a new provider, or replacing [editId]) and
     * loads its channels alongside the others. Then runs [onSuccess].
     */
    fun saveProvider(editId: String?, name: String, account: IptvAccount, onSuccess: () -> Unit) {
        val prefs = accountPrefs ?: return
        testJob?.cancel()
        providerTest = IptvStatus.Loading
        testJob = viewModelScope.launch {
            val existing = providers.firstOrNull { it.id == editId }
            val entry = ProviderEntry(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.trim().ifBlank { ProviderEntry.defaultName(account) },
                account = account,
                // The first provider keeps unprefixed ids; later ones get their own prefix.
                prefix = existing?.prefix ?: if (providers.isEmpty()) "" else Providers.newPrefix(providers.map { it.prefix }),
            )
            try {
                val src = ScopedSource(entry, IptvSource.create(account))
                val info = src.login()
                val cat = src.loadCatalog()
                connectJobs.remove(entry.id)?.cancel()
                val i = providers.indexOfFirst { it.id == entry.id }
                if (i >= 0) providers[i] = entry else providers += entry
                prefs.providers = providers.toList()
                prefs.providerSkipped = false
                providerTest = IptvStatus.Ready(cat)
                onProviderLoaded(src, info, cat)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                providerTest = IptvStatus.Failed(friendly(e))
            }
        }
    }

    fun resetProviderTest() {
        testJob?.cancel()
        providerTest = IptvStatus.NotConfigured
    }

    fun useLegacyProvider(onSuccess: () -> Unit) {
        val p = legacy.provider ?: return
        saveProvider(null, "", p) {
            legacy.forgetProvider()
            onSuccess()
        }
    }

    fun skipProvider() {
        accountPrefs?.providerSkipped = true
    }

    fun removeProvider(id: String) {
        val entry = providers.firstOrNull { it.id == id } ?: return
        providers.removeAll { it.id == id }
        accountPrefs?.providers = providers.toList()
        dropProvider(id)
        rebuildCatalogAsync()
        showMessage("Removed ${entry.name}")
    }

    fun setProviderEnabled(id: String, enabled: Boolean) {
        val i = providers.indexOfFirst { it.id == id }
        if (i < 0) return
        providers[i] = providers[i].copy(enabled = enabled)
        accountPrefs?.providers = providers.toList()
        if (enabled) connectOne(providers[i]) else {
            dropProvider(id)
            rebuildCatalogAsync()
        }
    }

    fun reloadChannels() {
        movies = VodState.Idle
        series = VodState.Idle
        providers.filter { it.enabled }.forEach { connectOne(it) }
    }

    private fun dropProvider(id: String) {
        connectJobs.remove(id)?.cancel()
        sources.remove(id)
        catalogs.remove(id)
        providerStatus.remove(id)
        providerInfo.remove(id)
        xmltvLoaded.remove(id)
        movies = VodState.Idle
        series = VodState.Idle
    }

    private fun disconnectProviders() {
        connectJobs.values.forEach { it.cancel() }
        connectJobs.clear()
        testJob?.cancel()
        sources.clear()
        catalogs.clear()
        providers.clear()
        providerStatus.clear()
        providerInfo.clear()
        iptv = IptvStatus.NotConfigured
        movies = VodState.Idle
        series = VodState.Idle
        epg.clear()
        epgFetched.clear()
        movieInfoCache.clear()
        seriesInfoCache.clear()
        xmltvLoaded.clear()
    }

    private fun connectOne(entry: ProviderEntry) {
        connectJobs.remove(entry.id)?.cancel()
        providerStatus[entry.id] = IptvStatus.Loading
        if (catalogs.isEmpty()) iptv = IptvStatus.Loading
        connectJobs[entry.id] = viewModelScope.launch {
            try {
                val src = ScopedSource(entry, IptvSource.create(entry.account))
                val info = src.login()
                val cat = src.loadCatalog()
                onProviderLoaded(src, info, cat)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                providerStatus[entry.id] = IptvStatus.Failed(friendly(e))
                rebuildCatalog()
            }
        }
    }

    private suspend fun onProviderLoaded(src: ScopedSource, info: AccountInfo?, cat: IptvCatalog) {
        val id = src.entry.id
        sources[id] = src
        catalogs[id] = cat
        providerStatus[id] = IptvStatus.Ready(cat)
        if (info != null) providerInfo[id] = info else providerInfo.remove(id)
        // New channels from this provider: movies and shows are combined again on next use.
        movies = VodState.Idle
        series = VodState.Idle
        rebuildCatalog()
        src.xmltvUrl?.let { loadXmltv(src, it, cat) }
        scheduleTeamRecordings()
    }

    /** Combines the loaded providers, in the account's order. */
    private suspend fun rebuildCatalog() {
        val ordered = providers.mapNotNull { catalogs[it.id] }
        val merged = Providers.merge(ordered)
        if (merged != null) withContext(Dispatchers.Default) { merged.normNames; merged.sportsGroups }
        iptv = when {
            merged != null -> IptvStatus.Ready(merged)
            providers.none { it.enabled } -> IptvStatus.NotConfigured
            providers.any { providerStatus[it.id] == IptvStatus.Loading } -> IptvStatus.Loading
            else -> providers.firstNotNullOfOrNull { providerStatus[it.id] as? IptvStatus.Failed } ?: IptvStatus.NotConfigured
        }
    }

    private fun rebuildCatalogAsync() {
        viewModelScope.launch { rebuildCatalog() }
    }

    fun channelById(id: String): Channel? = catalog?.byId?.get(id)

    fun streamCandidates(channel: Channel): List<String> = sourceFor(channel)?.streamUrls(channel, streamFormat) ?: listOfNotNull(channel.url)

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
    /** Providers whose whole guide came from XMLTV (no per-channel requests needed). */
    private val xmltvLoaded = HashSet<String>()

    /** Loads this channel's guide if it isn't cached (Xtream asks per channel). */
    fun requestEpg(channel: Channel) {
        val src = sourceFor(channel) ?: return
        if (src.entry.id in xmltvLoaded) return
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

    fun catchupUrl(channel: Channel, program: Program): String? = sourceFor(channel)?.catchupUrl(channel, program)

    private suspend fun loadXmltv(src: ScopedSource, url: String, cat: IptvCatalog) {
        val byEpg = HashMap<String, MutableList<String>>()
        cat.channels.forEach { c -> c.epgId?.let { byEpg.getOrPut(it) { ArrayList() } += c.id } }
        if (byEpg.isEmpty()) return
        val now = System.currentTimeMillis()
        runCatching {
            Http.withStream(url) { Xmltv.parse(it, byEpg, now - 6 * 3_600_000L, now + 24 * 3_600_000L) }
        }.onSuccess { result ->
            if (sources[src.entry.id] === src) {
                epg.putAll(result)
                xmltvLoaded += src.entry.id
            }
        }
    }

    // ---- movies & shows (all providers combined) ----

    var movies by mutableStateOf<VodState<Movie>>(VodState.Idle); private set
    var series by mutableStateOf<VodState<Series>>(VodState.Idle); private set
    private val movieInfoCache = HashMap<String, MovieInfo?>()
    private val seriesInfoCache = HashMap<String, SeriesInfo>()
    private var moviesById: Map<String, Movie> = emptyMap()
    private var seriesById: Map<String, Series> = emptyMap()

    val hasVod: Boolean get() = sources.values.any { it.hasVod }

    fun ensureMovies() {
        if (movies !is VodState.Idle && movies !is VodState.Failed) return
        val vod = providers.mapNotNull { sources[it.id] }.filter { it.hasVod }
        if (vod.isEmpty()) return
        movies = VodState.Loading
        viewModelScope.launch {
            val libs = coroutineScope { vod.map { s -> async { runCatching { s.loadMovies() } } }.awaitAll() }
            val ok = libs.mapNotNull { it.getOrNull() }
            movies = if (ok.isEmpty()) {
                VodState.Failed(libs.firstNotNullOfOrNull { it.exceptionOrNull() }?.let(::friendly) ?: "Couldn't load movies.")
            } else {
                val lib = VodLibrary(ok.flatMap { it.categories }, ok.flatMap { it.items })
                moviesById = withContext(Dispatchers.Default) { lib.items.associateBy { it.id } }
                VodState.Ready(lib)
            }
        }
    }

    fun ensureSeries() {
        if (series !is VodState.Idle && series !is VodState.Failed) return
        val vod = providers.mapNotNull { sources[it.id] }.filter { it.hasSeries }
        if (vod.isEmpty()) return
        series = VodState.Loading
        viewModelScope.launch {
            val libs = coroutineScope { vod.map { s -> async { runCatching { s.loadSeries() } } }.awaitAll() }
            val ok = libs.mapNotNull { it.getOrNull() }
            series = if (ok.isEmpty()) {
                VodState.Failed(libs.firstNotNullOfOrNull { it.exceptionOrNull() }?.let(::friendly) ?: "Couldn't load shows.")
            } else {
                val lib = VodLibrary(ok.flatMap { it.categories }, ok.flatMap { it.items })
                seriesById = withContext(Dispatchers.Default) { lib.items.associateBy { it.id } }
                VodState.Ready(lib)
            }
        }
    }

    fun movieById(id: String): Movie? = moviesById[id]
    fun seriesById(id: String): Series? = seriesById[id]

    suspend fun movieInfo(movie: Movie): MovieInfo? {
        if (movieInfoCache.containsKey(movie.id)) return movieInfoCache[movie.id]
        val info = runCatching { sourceForId(movie.id)?.movieInfo(movie) }.getOrNull()
        movieInfoCache[movie.id] = info
        return info
    }

    suspend fun seriesInfo(s: Series): SeriesInfo {
        seriesInfoCache[s.id]?.let { return it }
        val src = sourceForId(s.id) ?: return SeriesInfo(s, emptyList(), emptyMap())
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
        (playback as? PlayRequest.Live)?.let { liveBeforeCatchup = it }
        playback = PlayRequest.Catchup(channel, program, url)
        if (screen !is Screen.Player) navigate(Screen.Player)
    }

    /** The live channel list catch-up was started from, so "Live" returns to it (and CH+/CH− still work). */
    private var liveBeforeCatchup: PlayRequest.Live? = null

    fun playMovie(movie: Movie) {
        val url = sourceForId(movie.id)?.movieUrl(movie) ?: movie.url ?: return
        playVod(listOf(VodItem("movie:${movie.id}:${movie.ext}", movie.name, "Movie", movie.poster, url)), 0)
    }

    fun playEpisodes(info: SeriesInfo, episode: Episode) {
        val src = sourceForId(info.series.id) ?: return
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
        if (point.key.startsWith("addon:")) {
            // Add-on links expire: open the title and pick a source; playback resumes from here.
            val (type, metaId, _) = AddonsModel.parseResumeKey(point.key) ?: return
            navigate(Screen.AddonDetail(type, metaId))
            return
        }
        val parts = point.key.split(':')
        val src = when (parts.firstOrNull()) {
            "movie" -> sourceForId(parts.getOrNull(1).orEmpty())
            "ep" -> sourceForId(parts.getOrNull(2).orEmpty())
            else -> null
        }
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

    private fun playVod(queue: List<VodItem>, index: Int) = playVodItems(queue, index)

    fun playVodItems(queue: List<VodItem>, index: Int) {
        if (queue.isEmpty()) return
        playback = PlayRequest.Vod(queue, index.coerceIn(queue.indices))
        if (screen !is Screen.Player) navigate(Screen.Player)
    }

    // ---- the shared player: full screen, and live TV behind the menus (like YouTube TV) ----

    private var mainStreamOrNull: StreamController? = null

    /** One player for the full-screen player and the background video, so going in and out is instant. */
    val mainStream: StreamController
        get() = mainStreamOrNull ?: StreamController(context, handleAudioFocus = true).also { mainStreamOrNull = it }

    val hasMainStream: Boolean get() = mainStreamOrNull != null

    // ---- On Demand trailers (their own small player) ----

    private var trailerOrNull: TrailerController? = null

    /** Plays the focused title's trailer in On Demand. */
    val trailer: TrailerController
        get() = trailerOrNull ?: TrailerController(context).also { trailerOrNull = it }

    fun stopTrailer() {
        trailerOrNull?.stop()
    }

    /** The live channel playing (or last played) in the shared player, shown behind the menus. */
    var backgroundChannel by mutableStateOf<Channel?>(null); private set

    /** Live video is on screen behind the menus (connected and playing). */
    val backgroundShowing: Boolean
        get() {
            val s = mainStreamOrNull ?: return false
            val ch = backgroundChannel ?: return false
            return backgroundVideo != "off" && s.currentKey == ch.id && !s.buffering && s.error == null
        }

    /** The card focused on the main tabs: drives the header and which channel previews. */
    var heroFocus by mutableStateOf<HeroInfo?>(null)

    /** Plays [channel] behind the menus (muted or with sound, per Settings). */
    fun previewChannel(channel: Channel) {
        if (backgroundVideo == "off" || iptv !is IptvStatus.Ready) return
        val s = mainStream
        s.applyDecoderMode(decoderMode(DecoderSlot.PLAYER))
        s.volume = if (backgroundVideo == "sound") 1f else 0f
        s.load(channel.id, streamCandidates(channel))
        backgroundChannel = channel
    }

    /** The live video behind Sports / Live, full screen (it carries on without restarting). */
    fun watchBackgroundFullScreen() {
        val channel = backgroundChannel ?: return
        playChannel(channel, eventId = liveGameFor(channel)?.id ?: liveTournamentFor(channel)?.id)
    }

    /** The full-screen player is showing [channel]; it keeps playing behind the menus afterwards. */
    fun noteWatching(channel: Channel?) {
        backgroundChannel = channel
    }

    fun stopMainStream() {
        mainStreamOrNull?.stop()
        backgroundChannel = null
    }

    /** Until then, focus landing back on a card doesn't replace what was just being watched. */
    var keepBackgroundUntil = 0L; private set
    private var lastScreen: Screen? = null

    /** Called when the screen changes: the shared player only runs on the player and the main tabs. */
    fun onScreenChanged(s: Screen) {
        val from = lastScreen
        lastScreen = s
        stopTrailer()
        val main = mainStreamOrNull ?: return
        when {
            s == Screen.Player -> main.volume = 1f
            s == Screen.Main && backgroundVideo != "off" && backgroundChannel != null && tab != Tab.LIBRARY && tab != Tab.ON_DEMAND -> {
                main.volume = if (backgroundVideo == "sound") 1f else 0f
                if (from == Screen.Player) keepBackgroundUntil = System.currentTimeMillis() + 4_000
            }
            else -> stopMainStream()
        }
    }

    override fun onCleared() {
        mainStreamOrNull?.release()
        mainStreamOrNull = null
        trailerOrNull?.release()
        trailerOrNull = null
        super.onCleared()
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

    fun previousVod(): Boolean {
        val p = playback as? PlayRequest.Vod ?: return false
        if (p.index <= 0) return false
        playback = p.copy(index = p.index - 1)
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
        val before = liveBeforeCatchup?.takeIf { it.current?.id == p.channel.id }
        playback = before ?: PlayRequest.Live(listOf(p.channel), 0, null)
    }

    // =============================================================================================
    // Multiview
    // =============================================================================================

    val multiview = mutableStateListOf<Channel?>(null, null, null, null)
    var multiviewAudio by mutableIntStateOf(0); private set
    var multiviewLayout by mutableStateOf(MultiviewLayout.ONE); private set

    val multiviewCount: Int get() = (0 until multiviewLayout.screens).count { multiview[it] != null }

    /** Most streams the providers allow at once, together (unknown = 4). */
    val maxStreams: Int
        get() {
            val ready = providers.filter { it.enabled && sources.containsKey(it.id) }
            if (ready.isEmpty()) return 4
            return ready.sumOf { providerInfo[it.id]?.maxConnections?.toIntOrNull()?.coerceAtLeast(1) ?: 4 }.coerceIn(1, 4)
        }

    /** Connection limit of the provider a channel comes from, when it says. */
    fun connectionLimit(channel: Channel): Int? = providerInfo[channel.providerId]?.maxConnections?.toIntOrNull()

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
        // Free the shared player's connection and decoder before the screens start.
        stopMainStream()
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

    /** Sports' "Watch in Multiview" row (refreshed by Sports as games change). */
    var multiviewRow by mutableStateOf<List<MultiviewPreset>>(emptyList())

    /**
     * Ready-made multiviews for Sports, like YouTube TV's: your teams, the top live games, close
     * games, each league with several games on, the main sports networks, and your channels.
     */
    suspend fun multiviewPresets(): List<MultiviewPreset> {
        val cat = catalog ?: return emptyList()
        val live = liveGames
        val favs = live.filter { isFavoriteGame(it) }
        val max = maxStreams
        val hide = hideScores
        val favChannels = favoriteChannels
        return withContext(Dispatchers.Default) {
            val best = HashMap<String, Channel?>()
            fun channelFor(g: Game): Channel? = best.getOrPut(g.id) { ChannelMatcher.match(g, cat, limit = 3).firstOrNull()?.channel }

            fun preset(key: String, title: String, subtitle: (Int) -> String, games: List<Game>): MultiviewPreset? {
                val used = HashSet<String>()
                val pairs = games.mapNotNull { g -> channelFor(g)?.takeIf { used.add(it.id) }?.let { g to it } }.take(max)
                return if (pairs.size < 2) null else MultiviewPreset(key, title, subtitle(pairs.size), pairs.map { it.first }, pairs.map { it.second })
            }

            val out = ArrayList<MultiviewPreset>()
            preset("mine", "Your teams", { "$it of your games" }, favs)?.let(out::add)
            // Top games: your teams first, then the closest and latest in the game.
            val ranked = live.sortedWith(compareBy<Game>({ !isFavoriteGame(it) }, { closeness(it) }))
            preset("top", "Top live games", { "$it games across sports" }, ranked)?.let(out::add)
            if (!hide) {
                val close = live.filter { closeness(it) <= 1 }
                preset("close", "Close games", { "$it games within one score" }, close)?.let(out::add)
            }
            live.groupBy { it.league }.forEach { (league, list) ->
                preset("l:${league.key}", "${league.label} multiview", { "$it live ${league.label} games" }, list)?.let(out::add)
            }
            // Channel presets, for when no games line up.
            val networks = listOf("ESPN", "ESPN2", "FS1", "NFL NETWORK", "NBA TV", "MLB NETWORK", "NHL NETWORK", "GOLF CHANNEL", "CBS SPORTS NETWORK")
            val nets = networks.mapNotNull { n -> ChannelMatcher.search(cat, n, cat.sportsGroupSet.ifEmpty { null }, limit = 3).firstOrNull() }
                .distinctBy { it.id }.take(max)
            if (nets.size >= 2) out += MultiviewPreset("nets", "Sports networks", "${nets.size} channels", emptyList(), nets)
            val mine = favChannels.take(max)
            if (mine.size >= 2) out += MultiviewPreset("favch", "Your favorite channels", "${mine.size} channels", emptyList(), mine)
            // Drop presets that show exactly the same channels as an earlier one.
            out.distinctBy { p -> p.channels.map { it.id }.sorted() }
        }
    }

    /** 0 = tied or one score apart late in the game … higher = less close (team sports). */
    private fun closeness(g: Game): Int {
        val diff = kotlin.math.abs((g.away.score.toIntOrNull() ?: 0) - (g.home.score.toIntOrNull() ?: 0))
        val unit = when (g.league.sport) {
            "football" -> 8
            "basketball" -> 6
            else -> 1
        }
        return (diff + unit - 1) / unit
    }

    /** Live and upcoming events for a sport (or the user's teams when null) with their best channels. */
    suspend fun eventStreams(leagueKey: String?, perEvent: Int = 3, maxEvents: Int = 15): List<EventStreams> {
        val cat = catalog ?: return emptyList()
        val now = System.currentTimeMillis()
        val soon = now + 12 * 60 * 60_000L
        val golf = leagueKey == Leagues.GOLF || Leagues.golf.any { it.key == leagueKey }
        return withContext(Dispatchers.Default) {
            if (golf) {
                tournaments.filter { (leagueKey == Leagues.GOLF || it.tour.key == leagueKey) && it.state != GameState.FINAL }.map { t ->
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
        sourceFor(channel)?.streamUrls(channel, StreamFormat.TS) ?: listOfNotNull(channel.url)

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
        val onDemand = viewModelScope.async { rankResults(runCatching { addons.search(q) }.getOrDefault(emptyList()), q) }
        coroutineScope {
            followedLeagues.filter { it.key !in allTeams }.map { l ->
                async { runCatching { loadTeams(l) }.getOrNull()?.let { allTeams[l.key] = it } }
            }.awaitAll()
        }
        val addonHits = onDemand.await()
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
            SearchResults(gamesHit, tours, teams, channels, programs, m, s, addonHits)
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
        viewModelScope.launch { runScoreClock() }
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
