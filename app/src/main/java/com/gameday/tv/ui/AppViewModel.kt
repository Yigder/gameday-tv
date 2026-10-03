package com.gameday.tv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gameday.tv.data.AccountInfo
import com.gameday.tv.data.Channel
import com.gameday.tv.data.ChannelMatch
import com.gameday.tv.data.ChannelMatcher
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.Http
import com.gameday.tv.data.IptvAccount
import com.gameday.tv.data.IptvCatalog
import com.gameday.tv.data.IptvSource
import com.gameday.tv.data.League
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.MultiviewLayout
import com.gameday.tv.data.MultiviewRules
import com.gameday.tv.data.ScoreBugMode
import com.gameday.tv.data.ScoresRepository
import com.gameday.tv.data.SettingsStore
import com.gameday.tv.data.StreamFormat
import com.gameday.tv.data.Tournament
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed class Screen(val key: String) {
    data object Login : Screen("login")
    data object Home : Screen("home")
    data class GameDetail(val gameId: String) : Screen("game:$gameId")
    data class TournamentDetail(val tournamentId: String) : Screen("golf:$tournamentId")
    data class Channels(val query: String = "") : Screen("channels:$query")
    data object Player : Screen("player")
    data object Multiview : Screen("multiview")
    data object MySports : Screen("mysports")
    data object Account : Screen("account")
}

sealed interface IptvStatus {
    data object NotConfigured : IptvStatus
    data object Loading : IptvStatus
    data class Ready(val catalog: IptvCatalog) : IptvStatus
    data class Failed(val message: String) : IptvStatus
}

/** What's playing: a list to zap through (matched channels or a category) and the current position. */
data class Playback(val channels: List<Channel>, val index: Int, val eventId: String?) {
    val current: Channel? get() = channels.getOrNull(index)
}

/** A one-tap channel choice for multiview: the best channel for a live event. */
data class Suggestion(val channel: Channel, val title: String, val subtitle: String)

/** A live/upcoming event with its best channels, for the multiview sidebar. */
data class EventStreams(val eventId: String, val title: String, val subtitle: String, val live: Boolean, val matches: List<ChannelMatch>)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = SettingsStore(app)
    private val scoresRepo = ScoresRepository()

    // ---- navigation ----
    private val backStack = mutableStateListOf<Screen>()
    val screen: Screen get() = backStack.last()
    val canGoBack: Boolean get() = backStack.size > 1

    /** Remembered so Home can restore focus to the card the user came from. */
    var lastFocusedEventId: String? = null

    /** Short confirmation / alert shown at the bottom of the screen. */
    var message by mutableStateOf<String?>(null); private set
    private var messageJob: Job? = null

    // ---- my sports & teams ----
    var enabledLeagues by mutableStateOf(settings.enabledLeagues); private set
    val favorites = mutableStateListOf<FavoriteTeam>().apply { addAll(settings.favoriteTeams) }
    private val teamsCache = HashMap<String, List<FavoriteTeam>>()

    /** Leagues the user follows, in display order. */
    val followedLeagues: List<League> get() = Leagues.all.filter { it.key in enabledLeagues }
    val followedTours: List<League> get() = Leagues.golf.filter { it.key in enabledLeagues }

    // ---- scores ----
    var games by mutableStateOf<List<Game>>(emptyList()); private set
    var tournaments by mutableStateOf<List<Tournament>>(emptyList()); private set
    var scoresLoading by mutableStateOf(true); private set
    var scoresError by mutableStateOf<String?>(null); private set
    var scoresUpdatedAt by mutableLongStateOf(0L); private set
    private val gamesByLeague = HashMap<String, List<Game>>()
    private val tournamentsByTour = HashMap<String, List<Tournament>>()

    // ---- IPTV ----
    var iptv by mutableStateOf<IptvStatus>(IptvStatus.NotConfigured); private set
    var accountInfo by mutableStateOf<AccountInfo?>(null); private set
    var streamFormat by mutableStateOf(settings.streamFormat); private set
    var userAgent by mutableStateOf(settings.userAgent); private set
    private var source: IptvSource? = null
    private var connectJob: Job? = null

    // ---- playback ----
    var playback by mutableStateOf<Playback?>(null); private set
    val recentChannels = mutableStateListOf<Channel>()
    var scoreBugMode by mutableStateOf(settings.scoreBugMode); private set
    var scoreAlerts by mutableStateOf(settings.scoreAlerts); private set

    // ---- multiview: 4 slots; the layout decides how many are on screen; one has the audio ----
    val multiview = mutableStateListOf<Channel?>(null, null, null, null)
    var multiviewAudio by mutableIntStateOf(0); private set
    var multiviewLayout by mutableStateOf(MultiviewLayout.ONE); private set

    init {
        Http.userAgent = settings.userAgent
        val account = settings.account
        backStack.add(if (account != null || settings.scoresOnly) Screen.Home else Screen.Login)
        if (account != null) connect(account, navigateHome = false)
        viewModelScope.launch { pollScores() }
    }

    // ---------------- navigation ----------------

    fun navigate(s: Screen) {
        backStack.add(s)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    private fun resetTo(s: Screen) {
        backStack.clear()
        backStack.add(s)
    }

    fun openGame(game: Game) = navigate(Screen.GameDetail(game.id))

    fun openTournament(t: Tournament) = navigate(Screen.TournamentDetail(t.id))

    /** From the player's scores panel: swap the player for the event page instead of stacking. */
    fun openEventFromPlayer(eventId: String) {
        val target = if (tournamentById(eventId) != null) Screen.TournamentDetail(eventId) else Screen.GameDetail(eventId)
        if (screen is Screen.Player) backStack[backStack.lastIndex] = target else navigate(target)
    }

    fun openChannels(query: String = "") {
        if (iptv is IptvStatus.NotConfigured) navigate(Screen.Login) else navigate(Screen.Channels(query))
    }

    fun showMessage(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(3_500)
            message = null
        }
    }

    // ---------------- my sports & teams ----------------

    fun setLeagueEnabled(key: String, enabled: Boolean) {
        val next = if (enabled) enabledLeagues + key else enabledLeagues - key
        if (next.isEmpty()) {
            showMessage("Keep at least one sport")
            return
        }
        enabledLeagues = next
        settings.enabledLeagues = next
        publishScores()
        if (enabled) Leagues.everything.firstOrNull { it.key == key }?.let { l -> viewModelScope.launch { refreshScores(listOf(l)) } }
    }

    fun isFavorite(leagueKey: String, teamId: String): Boolean = favorites.any { it.leagueKey == leagueKey && it.id == teamId }

    fun isFavoriteGame(game: Game): Boolean =
        isFavorite(game.league.key, game.home.id) || isFavorite(game.league.key, game.away.id)

    fun toggleFavorite(team: FavoriteTeam) {
        val existing = favorites.indexOfFirst { it.key == team.key }
        if (existing >= 0) {
            favorites.removeAt(existing)
            showMessage("Removed ${team.name} from My Teams")
        } else {
            favorites.add(team)
            showMessage("★ Added ${team.name} to My Teams")
        }
        settings.favoriteTeams = favorites.toList()
    }

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

    // ---------------- scores ----------------

    fun gameById(id: String): Game? = games.firstOrNull { it.id == id }

    fun tournamentById(id: String): Tournament? = tournaments.firstOrNull { it.id == id }

    private suspend fun pollScores() {
        var cycle = 0
        while (true) {
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
                async {
                    l to runCatching<Any> { if (l.sport == "golf") scoresRepo.fetchGolf(l) else scoresRepo.fetch(l) }
                }
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
            scoresError = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }?.let(::friendly)
                ?: "Couldn't load scores."
        }
        scoresLoading = false
    }

    /** Rebuilds the visible lists from the per-league caches, honoring the followed sports. */
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
            val scorer = when {
                g.home.score != prev.home.score && isFavorite(g.league.key, g.home.id) -> g.home
                g.away.score != prev.away.score && isFavorite(g.league.key, g.away.id) -> g.away
                g.home.score != prev.home.score || g.away.score != prev.away.score -> null
                else -> continue
            }
            val line = "${g.away.abbreviation} ${g.away.score} – ${g.home.score} ${g.home.abbreviation} · ${g.shortDetail}"
            showMessage(if (scorer != null) "★ ${scorer.shortName} score!  $line" else "★ Score update  $line")
        }
    }

    // ---------------- IPTV ----------------

    fun connect(account: IptvAccount, navigateHome: Boolean) {
        connectJob?.cancel()
        iptv = IptvStatus.Loading
        connectJob = viewModelScope.launch {
            try {
                val src = IptvSource.create(account)
                val info = src.login()
                val catalog = src.loadCatalog()
                source = src
                accountInfo = info
                iptv = IptvStatus.Ready(catalog)
                settings.account = account
                settings.scoresOnly = false
                if (navigateHome) resetTo(Screen.Home)
                // Warm the normalized-name caches so the first event lookup is instant.
                withContext(Dispatchers.Default) { catalog.normNames; catalog.sportsGroups }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                iptv = IptvStatus.Failed(friendly(e))
            }
        }
    }

    fun reloadChannels() {
        settings.account?.let { connect(it, navigateHome = false) }
    }

    fun useScoresOnly() {
        settings.scoresOnly = true
        resetTo(Screen.Home)
    }

    fun logout() {
        connectJob?.cancel()
        settings.account = null
        settings.scoresOnly = false
        source = null
        accountInfo = null
        playback = null
        recentChannels.clear()
        for (i in multiview.indices) multiview[i] = null
        multiviewLayout = MultiviewLayout.ONE
        iptv = IptvStatus.NotConfigured
        resetTo(Screen.Login)
    }

    fun updateStreamFormat(f: StreamFormat) {
        settings.streamFormat = f
        streamFormat = f
    }

    fun updateUserAgent(ua: String) {
        settings.userAgent = ua
        userAgent = settings.userAgent
        Http.userAgent = userAgent
    }

    fun updateScoreBugMode(mode: ScoreBugMode) {
        settings.scoreBugMode = mode
        scoreBugMode = mode
    }

    fun updateScoreAlerts(on: Boolean) {
        settings.scoreAlerts = on
        scoreAlerts = on
    }

    suspend fun matchChannels(game: Game, catalog: IptvCatalog): List<ChannelMatch> =
        withContext(Dispatchers.Default) { ChannelMatcher.match(game, catalog) }

    suspend fun matchChannels(t: Tournament, catalog: IptvCatalog): List<ChannelMatch> =
        withContext(Dispatchers.Default) { ChannelMatcher.match(t, catalog) }

    suspend fun searchChannels(catalog: IptvCatalog, query: String, groups: Set<String>?): List<Channel> =
        withContext(Dispatchers.Default) { ChannelMatcher.search(catalog, query, groups) }

    fun liveGameFor(channel: Channel): Game? = ChannelMatcher.findGameForChannel(channel.name, games)

    fun liveTournamentFor(channel: Channel): Tournament? = ChannelMatcher.findTournamentForChannel(channel.name, tournaments)

    fun streamCandidates(channel: Channel): List<String> =
        source?.streamUrls(channel, streamFormat) ?: listOfNotNull(channel.url)

    /** Best channel for each live event, for one-tap multiview picks. */
    suspend fun liveSuggestions(): List<Suggestion> {
        val catalog = (iptv as? IptvStatus.Ready)?.catalog ?: return emptyList()
        val liveTournaments = tournaments.filter { it.state == GameState.LIVE }
        val liveGames = games.filter { it.state == GameState.LIVE }.sortedBy { it.startMillis }
        return withContext(Dispatchers.Default) {
            val out = ArrayList<Suggestion>()
            val used = HashSet<String>()
            for (t in liveTournaments) {
                val best = ChannelMatcher.match(t, catalog, limit = 5).firstOrNull { it.channel.id !in used } ?: continue
                used += best.channel.id
                val leader = t.leaders.firstOrNull()?.let { " · ${it.shortName} ${it.toPar}" }.orEmpty()
                out += Suggestion(best.channel, t.name, "${t.tour.label} · ${t.detail}$leader")
            }
            for (g in liveGames) {
                val best = ChannelMatcher.match(g, catalog, limit = 5).firstOrNull { it.channel.id !in used } ?: continue
                used += best.channel.id
                out += Suggestion(
                    best.channel,
                    "${g.away.shortName} ${g.away.score} @ ${g.home.shortName} ${g.home.score}",
                    "${g.league.label} · ${g.shortDetail}",
                )
            }
            out
        }
    }

    /**
     * Live and upcoming events for a sport (or the user's teams) with their best channels.
     * [leagueKey] null means "My Teams".
     */
    suspend fun eventStreams(leagueKey: String?, perEvent: Int = 3, maxEvents: Int = 15): List<EventStreams> {
        val catalog = (iptv as? IptvStatus.Ready)?.catalog ?: return emptyList()
        val now = System.currentTimeMillis()
        val soon = now + 12 * 60 * 60_000L
        val golf = Leagues.golf.firstOrNull { it.key == leagueKey }
        return withContext(Dispatchers.Default) {
            if (golf != null) {
                tournaments.filter { it.tour.key == golf.key && it.state != GameState.FINAL }.map { t ->
                    val leader = t.leaders.firstOrNull()?.let { " · ${it.shortName} ${it.toPar}" }.orEmpty()
                    EventStreams(t.id, t.name, "${t.detail}$leader", t.roundInProgress, ChannelMatcher.match(t, catalog, perEvent))
                }
            } else {
                val pool = if (leagueKey == null) favoriteGames else games.filter { it.league.key == leagueKey }
                pool.filter { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMillis < soon) }
                    .sortedWith(compareBy<Game>({ stateOrder(it) }, { it.startMillis }))
                    .take(maxEvents)
                    .map { g ->
                        val score = if (g.state == GameState.PRE) "" else " · ${g.away.score}-${g.home.score}"
                        EventStreams(
                            g.id,
                            "${g.away.shortName} @ ${g.home.shortName}",
                            "${g.league.label} · ${statusText(g)}$score",
                            g.state == GameState.LIVE,
                            ChannelMatcher.match(g, catalog, perEvent),
                        )
                    }
            }
        }
    }

    suspend fun leagueChannels(league: League): List<Channel> {
        val catalog = (iptv as? IptvStatus.Ready)?.catalog ?: return emptyList()
        return withContext(Dispatchers.Default) { ChannelMatcher.leagueChannels(league, catalog) }
    }

    // ---------------- playback ----------------

    fun play(channels: List<Channel>, index: Int, eventId: String?) {
        if (channels.isEmpty()) return
        playback = Playback(channels, index.coerceIn(channels.indices), eventId)
        if (screen !is Screen.Player) navigate(Screen.Player)
    }

    fun zap(delta: Int) {
        val p = playback ?: return
        if (p.channels.size < 2) return
        playback = p.copy(index = Math.floorMod(p.index + delta, p.channels.size))
    }

    fun zapTo(index: Int) {
        val p = playback ?: return
        if (index in p.channels.indices) playback = p.copy(index = index)
    }

    fun noteRecent(channel: Channel) {
        recentChannels.removeAll { it.id == channel.id }
        recentChannels.add(0, channel)
        while (recentChannels.size > 20) recentChannels.removeAt(recentChannels.lastIndex)
    }

    // ---------------- multiview ----------------

    /** Channels currently on screen (the layout may hide some slots). */
    val multiviewCount: Int get() = (0 until multiviewLayout.screens).count { multiview[it] != null }

    /** Puts a channel on a free screen, growing the layout (1 → 2 → 3 → 4) as needed. */
    fun addToMultiview(channel: Channel, open: Boolean = false) {
        val existing = (0 until multiviewLayout.screens).firstOrNull { multiview[it]?.id == channel.id }
        if (existing != null) {
            showMessage("${channel.name} is already in Multiview")
        } else {
            if (multiviewCount == 0) {
                // Starting fresh: one full screen.
                for (i in multiview.indices) multiview[i] = null
                multiviewLayout = MultiviewLayout.ONE
            }
            val plan = MultiviewRules.slotForNew(multiview.toList(), multiviewLayout, multiviewAudio)
            multiviewLayout = plan.layout
            multiview[plan.slot] = channel
            if (multiviewCount == 1) multiviewAudio = plan.slot
            showMessage("Added ${channel.name} to Multiview (${multiviewCount}/${multiviewLayout.screens})")
        }
        if (open) openMultiview()
    }

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
        if (iptv is IptvStatus.NotConfigured) {
            navigate(Screen.Login)
            return
        }
        // Never stack a full-screen player under multiview: it would hold an extra connection.
        if (screen is Screen.Player) backStack[backStack.lastIndex] = Screen.Multiview
        else if (screen !is Screen.Multiview) navigate(Screen.Multiview)
    }

    /** Full-screen one multiview slot; zapping goes through the other multiview channels. */
    fun fullscreenFromMultiview(slot: Int) {
        val channels = (0 until multiviewLayout.screens).mapNotNull { multiview[it] }
        val ch = multiview[slot] ?: return
        play(channels, channels.indexOf(ch), null)
    }

    private fun friendly(e: Throwable): String = when (e) {
        is UnknownHostException -> "Can't reach the server. Check the address and your internet connection."
        is SocketTimeoutException -> "The server took too long to respond. Try again."
        is ConnectException -> "Connection refused. Check the server address and port."
        is SSLException -> "Secure connection failed. Try http:// instead of https://."
        else -> e.message ?: e.javaClass.simpleName
    }
}
