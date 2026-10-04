package com.gameday.tv.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.gameday.tv.data.Channel
import com.gameday.tv.data.ChannelMatch
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.IptvCatalog
import com.gameday.tv.data.Movie
import com.gameday.tv.data.Program
import com.gameday.tv.data.Recording
import com.gameday.tv.data.Series
import com.gameday.tv.data.Tournament
import com.gameday.tv.data.VodLibrary

sealed class Screen(val key: String) {
    data object Splash : Screen("splash")
    data object Welcome : Screen("welcome")
    data class SignIn(val email: String = "") : Screen("signin:$email")
    data object CreateAccount : Screen("create")
    /** First-run setup: 0 = provider, 1 = sports, 2 = teams. */
    data class Onboarding(val step: Int) : Screen("onboarding:$step")
    /** Add a provider, or edit [editId] (from Settings; not part of onboarding). */
    data class Provider(val editId: String? = null) : Screen("provider:$editId")
    /** Add a streaming add-on by address (typed, or sent from a phone). */
    data object AddonInstall : Screen("addon-install")
    data object TorBoxSetup : Screen("torbox")
    data class AddonDetail(val type: String, val id: String) : Screen("addon:$type:$id")
    /** "See all" for an add-on catalog row ([AddonRow.key]). */
    data class AddonBrowse(val rowKey: String) : Screen("addon-browse:$rowKey")
    data object TeamsPicker : Screen("teams-picker")
    data class Profiles(val manage: Boolean = false) : Screen("profiles:$manage")
    data class ProfileEdit(val profileId: String?) : Screen("profile-edit:$profileId")
    data object Main : Screen("main")
    data object Search : Screen("search")
    /** Search for On Demand (add-on) titles only. */
    data object OnDemandSearch : Screen("od-search")
    data class Settings(val section: SettingsSection = SettingsSection.ACCOUNT) : Screen("settings")
    data class GameDetail(val gameId: String) : Screen("game:$gameId")
    data class TournamentDetail(val tournamentId: String) : Screen("golf:$tournamentId")
    data class Team(val leagueKey: String, val teamId: String) : Screen("team:$leagueKey:$teamId")
    data class ChannelDetail(val channelId: String) : Screen("channel:$channelId")
    data class MovieDetail(val movieId: String) : Screen("movie:$movieId")
    data class SeriesDetail(val seriesId: String) : Screen("series:$seriesId")
    data class Browse(val kind: BrowseKind, val categoryId: String? = null) : Screen("browse:$kind:$categoryId")
    data object Player : Screen("player")
    data object Multiview : Screen("multiview")
    data object MultiviewBuilder : Screen("mv-builder")
}

enum class Tab(val label: String) { HOME("Home"), SPORTS("Sports"), LIVE("Live"), ON_DEMAND("On Demand"), LIBRARY("Library") }

enum class BrowseKind(val label: String) { MOVIES("Movies"), SHOWS("Shows") }

enum class SettingsSection(val label: String) {
    ACCOUNT("Account"),
    PROFILES("Profiles"),
    PROVIDER("TV providers"),
    ADDONS("Add-ons & TorBox"),
    SPORTS("Sports & scores"),
    GUIDE("Live guide"),
    PLAYBACK("Playback"),
    DVR("Recordings"),
    ABOUT("About"),
}

sealed interface IptvStatus {
    data object NotConfigured : IptvStatus
    data object Loading : IptvStatus
    data class Ready(val catalog: IptvCatalog) : IptvStatus
    data class Failed(val message: String) : IptvStatus
}

sealed interface VodState<out T> {
    data object Idle : VodState<Nothing>
    data object Loading : VodState<Nothing>
    data class Ready<T>(val library: VodLibrary<T>) : VodState<T>
    data class Failed(val message: String) : VodState<Nothing>
}

/** Something the full-screen player is showing. */
sealed interface PlayRequest {
    /** A live channel, with a list to zap through (matched channels, a guide filter, a category). */
    data class Live(val channels: List<Channel>, val index: Int, val eventId: String?) : PlayRequest {
        val current: Channel? get() = channels.getOrNull(index)
    }

    /** A past airing, replayed from the provider's catch-up archive. */
    data class Catchup(val channel: Channel, val program: Program, val url: String) : PlayRequest

    /** Movies and episodes; the queue lets the next episode play automatically. */
    data class Vod(val queue: List<VodItem>, val index: Int) : PlayRequest {
        val current: VodItem get() = queue[index]
    }

    data class Rec(val recording: Recording) : PlayRequest
}

data class VodItem(
    /** Resume key: "movie:<id>:<ext>", "ep:<seriesId>:<episodeId>:<ext>", or an add-on key ([AddonsModel.resumeKey]). */
    val key: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val url: String,
    /** HTTP headers some add-on sources need. */
    val headers: Map<String, String> = emptyMap(),
    /** For add-on episodes: what autoplay plays next. */
    val next: AddonNext? = null,
    /** For add-on titles: what to ask subtitle add-ons for. */
    val subtitles: com.gameday.tv.data.SubtitleRequest? = null,
)

data class DialogAction(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/** A YouTube TV-style modal: a title and a column of actions. */
data class AppDialog(
    val title: String,
    val subtitle: String? = null,
    val message: String? = null,
    val actions: List<DialogAction>,
)

/** A one-tap channel choice for multiview: the best channel for a live event. */
data class Suggestion(val channel: Channel, val title: String, val subtitle: String, val eventId: String)

/** A live/upcoming event with its best channels, for the multiview sidebar. */
data class EventStreams(val eventId: String, val title: String, val subtitle: String, val live: Boolean, val matches: List<ChannelMatch>)

/** A ready-made multiview (Home "Multiview" row): games, or channels when [games] is empty. */
data class MultiviewPreset(val key: String, val title: String, val subtitle: String, val games: List<Game>, val channels: List<Channel>)

data class SearchResults(
    val games: List<Game> = emptyList(),
    val tournaments: List<Tournament> = emptyList(),
    val teams: List<FavoriteTeam> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val programs: List<Program> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    /** From streaming add-ons. */
    val onDemand: List<com.gameday.tv.data.MetaPreview> = emptyList(),
) {
    val isEmpty: Boolean
        get() = games.isEmpty() && tournaments.isEmpty() && teams.isEmpty() && channels.isEmpty() &&
            programs.isEmpty() && movies.isEmpty() && series.isEmpty() && onDemand.isEmpty()
}
