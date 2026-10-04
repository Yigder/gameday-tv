package com.gameday.tv.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.gameday.tv.data.Channel
import com.gameday.tv.data.ChannelMatch
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.IptvCatalog
import com.gameday.tv.data.Program
import com.gameday.tv.data.Recording
import com.gameday.tv.data.Tournament

sealed class Screen(val key: String) {
    data object Splash : Screen("splash")
    data object Welcome : Screen("welcome")
    data class SignIn(val email: String = "") : Screen("signin:$email")
    data object CreateAccount : Screen("create")
    /** First-run setup: 0 = provider, 1 = sports, 2 = teams. */
    data class Onboarding(val step: Int) : Screen("onboarding:$step")
    /** Add a provider, or edit [editId] (from Settings; not part of onboarding). */
    data class Provider(val editId: String? = null) : Screen("provider:$editId")
    data object TeamsPicker : Screen("teams-picker")
    data class Profiles(val manage: Boolean = false) : Screen("profiles:$manage")
    data class ProfileEdit(val profileId: String?) : Screen("profile-edit:$profileId")
    data object Main : Screen("main")
    data object Search : Screen("search")
    data class Settings(val section: SettingsSection = SettingsSection.ACCOUNT) : Screen("settings")
    data class GameDetail(val gameId: String) : Screen("game:$gameId")
    data class TournamentDetail(val tournamentId: String) : Screen("golf:$tournamentId")
    data class Team(val leagueKey: String, val teamId: String) : Screen("team:$leagueKey:$teamId")
    data class ChannelDetail(val channelId: String) : Screen("channel:$channelId")
    data object Player : Screen("player")
    data object Multiview : Screen("multiview")
    data object MultiviewBuilder : Screen("mv-builder")
}

enum class Tab(val label: String) { SPORTS("Sports"), LIVE("Live"), LIBRARY("Library");

    companion object {
        /** Where the app opens, and where Back from another tab goes. */
        val FIRST = SPORTS
    }
}

enum class SettingsSection(val label: String) {
    ACCOUNT("Account"),
    PROFILES("Profiles"),
    PROVIDER("TV providers"),
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

/** Something the full-screen player is showing. */
sealed interface PlayRequest {
    /** A live channel, with a list to zap through (matched channels, a guide filter, a category). */
    data class Live(val channels: List<Channel>, val index: Int, val eventId: String?) : PlayRequest {
        val current: Channel? get() = channels.getOrNull(index)
    }

    /** A past airing, replayed from the provider's catch-up archive. */
    data class Catchup(val channel: Channel, val program: Program, val url: String) : PlayRequest

    data class Rec(val recording: Recording) : PlayRequest
}

/** A recording being played, for "Continue watching". */
data class VodItem(
    /** Resume key: "rec:<recordingId>". */
    val key: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val url: String,
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

/** A ready-made multiview (Sports "Watch in Multiview" row): games, or channels when [games] is empty. */
data class MultiviewPreset(val key: String, val title: String, val subtitle: String, val games: List<Game>, val channels: List<Channel>)

data class SearchResults(
    val games: List<Game> = emptyList(),
    val tournaments: List<Tournament> = emptyList(),
    val teams: List<FavoriteTeam> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val programs: List<Program> = emptyList(),
) {
    val isEmpty: Boolean
        get() = games.isEmpty() && tournaments.isEmpty() && teams.isEmpty() && channels.isEmpty() && programs.isEmpty()
}
