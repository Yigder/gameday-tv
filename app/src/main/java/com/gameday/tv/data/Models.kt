package com.gameday.tv.data

// ---------- IPTV ----------

sealed interface IptvAccount {
    data class Xtream(val server: String, val username: String, val password: String) : IptvAccount
    data class M3u(val url: String, val epgUrl: String? = null) : IptvAccount
}

data class AccountInfo(
    val status: String?,
    val expiresAtMillis: Long?,
    val maxConnections: String?,
    val activeConnections: String?,
    /** Server time zone (Xtream), needed to build catch-up URLs. */
    val timezone: String? = null,
)

enum class StreamFormat(val ext: String, val label: String) {
    TS("ts", "MPEG-TS"),
    HLS("m3u8", "HLS");

    fun other(): StreamFormat = if (this == TS) HLS else TS
}

data class Channel(
    val id: String,
    val name: String,
    val logo: String?,
    val group: String,
    val num: Int = 0,
    /** Xtream Codes stream id; the URL is built at play time so the format (TS/HLS) can change. */
    val streamId: String? = null,
    /** Direct URL for M3U playlists. */
    val url: String? = null,
    /** XMLTV channel id, used to find this channel's program guide. */
    val epgId: String? = null,
    /** Days of catch-up (replay) the provider keeps for this channel; 0 when it has none. */
    val archiveDays: Int = 0,
    /** The [ProviderEntry] this channel comes from. */
    val providerId: String = "",
)

class IptvCatalog(val groups: List<String>, val channels: List<Channel>) {
    val byGroup: Map<String, List<Channel>> = channels.groupBy { it.group }
    val byId: Map<String, Channel> = channels.associateBy { it.id }

    /** Normalized channel names, index-aligned with [channels]. Built once, used for matching/search. */
    val normNames: Array<String> by lazy { Array(channels.size) { ChannelMatcher.norm(channels[it].name) } }
    val normGroups: Map<String, String> by lazy { groups.associateWith { ChannelMatcher.norm(it) } }
    val sportsGroups: List<String> by lazy { groups.filter { SportsFilter.isSportsGroup(normGroups[it].orEmpty()) } }
    val sportsGroupSet: Set<String> by lazy { sportsGroups.toHashSet() }
    val sportsChannels: List<Channel> by lazy { channels.filter { it.group in sportsGroupSet } }
}

// ---------- Program guide ----------

data class Program(
    val channelId: String,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    /** The provider keeps a replay of this airing (catch-up). */
    val hasArchive: Boolean = false,
) {
    val key: String get() = "$channelId@$startMillis"
    fun isOnNow(now: Long) = now in startMillis until endMillis
    fun progress(now: Long): Float =
        if (endMillis <= startMillis) 0f else ((now - startMillis).toFloat() / (endMillis - startMillis)).coerceIn(0f, 1f)
}

// ---------- Movies & shows ----------

data class VodCategory(val id: String, val name: String)

data class Movie(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val rating: Double?,
    /** When the provider added it (epoch seconds), for "Recently added". */
    val added: Long,
    val ext: String,
    /** Direct URL (M3U playlists). */
    val url: String? = null,
)

data class MovieInfo(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationSecs: Int?,
    val backdrop: String?,
    val rating: String?,
    val ext: String?,
)

data class Series(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val plot: String?,
    val genre: String?,
    val rating: Double?,
    val releaseDate: String?,
    val backdrop: String?,
    val lastModified: Long,
)

data class Episode(
    val id: String,
    val seriesId: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Int?,
    val ext: String,
)

data class SeriesInfo(val series: Series, val seasons: List<Int>, val episodes: Map<Int, List<Episode>>) {
    val allEpisodes: List<Episode> get() = seasons.flatMap { episodes[it].orEmpty() }
}

class VodLibrary<T>(val categories: List<VodCategory>, val items: List<T>)

// ---------- Scores ----------

enum class GameState { PRE, LIVE, FINAL }

data class League(
    val key: String,
    val label: String,
    val sport: String,
    val path: String,
    val query: String = "",
    /** Words that identify this league's channels/packages in an IPTV lineup. */
    val keywords: List<String> = emptyList(),
    /** Networks that usually carry this league even when the feed lists no broadcaster (e.g. golf). */
    val defaultNetworks: List<String> = emptyList(),
    /** Typical length of an event, used to size recordings. */
    val typicalMinutes: Int = 180,
)

data class TeamScore(
    val id: String,
    val abbreviation: String,
    val displayName: String,
    val shortName: String,
    val name: String,
    val location: String,
    val logo: String?,
    val color: String?,
    val score: String,
    val record: String?,
    val winner: Boolean,
    val alternateColor: String? = null,
)

data class Game(
    val id: String,
    val league: League,
    val startMillis: Long,
    val state: GameState,
    val shortDetail: String,
    val detail: String,
    val away: TeamScore,
    val home: TeamScore,
    val broadcasts: List<String>,
    val venue: String?,
    val situation: String?,
    val possessionTeamId: String?,
) {
    /** ESPN's event id (without the league prefix). */
    val eventId: String get() = id.substringAfter(':')
    val title: String get() = "${away.shortName} at ${home.shortName}"
}

/** A team the user follows (added to their library). Identified by league + ESPN team id. */
data class FavoriteTeam(
    val leagueKey: String,
    val id: String,
    val name: String,
    val abbreviation: String,
    val logo: String?,
    /** Record every game automatically, like adding a team to a YouTube TV library. */
    val record: Boolean = false,
) {
    val key: String get() = "$leagueKey:$id"
}

data class TeamInfo(
    val leagueKey: String,
    val id: String,
    val name: String,
    val abbreviation: String,
    val logo: String?,
    val color: String?,
    val alternateColor: String?,
    val record: String?,
    val standing: String?,
)

data class StatRow(val label: String, val away: String, val home: String)

data class PlayItem(
    val text: String,
    val period: String,
    val clock: String,
    val teamAbbr: String?,
    val teamLogo: String?,
    val awayScore: String?,
    val homeScore: String?,
)

data class LeaderItem(val category: String, val athlete: String, val value: String, val headshot: String?)

data class TeamLeaders(val teamAbbr: String, val teamLogo: String?, val leaders: List<LeaderItem>)

data class GameStats(val teamStats: List<StatRow>, val plays: List<PlayItem>, val leaders: List<TeamLeaders>)

// ---------- Preferences ----------

enum class ScoreBugMode(val label: String) {
    /** Shows when a stream starts, then hides until OK / Info is pressed (ScoreBox style). */
    ON_PRESS("Show on button press"),
    ALWAYS("Always on"),
}

enum class MultiviewLayout(val screens: Int, val label: String, val description: String) {
    ONE(1, "1", "1 screen"),
    TWO(2, "2", "2 side by side"),
    TWO_PIP(2, "PiP", "Picture in picture"),
    THREE(3, "3", "1 large + 2"),
    FOUR(4, "4", "4 grid"),
    ONE_PLUS_THREE(4, "1+3", "1 large + 3");

    companion object {
        /** Smallest standard layout that fits [n] screens. */
        fun forCount(n: Int): MultiviewLayout = when {
            n <= 1 -> ONE
            n == 2 -> TWO
            n == 3 -> THREE
            else -> FOUR
        }
    }
}

// ---------- Golf ----------

data class GolfPlayer(
    val id: String,
    val name: String,
    val shortName: String,
    val flag: String?,
    /** "1", "T3"... */
    val position: String,
    /** Total to par: "-12", "E", "+3". */
    val toPar: String,
    /** Current round to par, when the player has started it. */
    val today: String?,
    /** "F", "12" (holes completed this round), or null if not started. */
    val thru: String?,
)

data class Tournament(
    val id: String,
    val tour: League,
    val name: String,
    val startMillis: Long,
    val endMillis: Long,
    /** Tournament-level state: LIVE for the whole tournament week once round 1 starts. */
    val state: GameState,
    /** A round is being played right now (vs. suspended / between rounds). */
    val roundInProgress: Boolean,
    /** e.g. "Round 2 - Suspended", "Round 3 - In Progress". */
    val detail: String,
    val broadcasts: List<String>,
    val leaders: List<GolfPlayer>,
    val fieldSize: Int,
    /** The tour's logo (PGA TOUR, DP World Tour), for the tournament's card. */
    val logo: String? = null,
)

// ---------- App accounts ----------

data class AppAccount(val id: String, val name: String, val email: String, val createdAt: Long) {
    val firstName: String get() = name.trim().substringBefore(' ').ifBlank { name }
}

/** A family member inside an account ("Who's watching?"). */
data class Profile(val id: String, val name: String, val color: Int) {
    val initial: String get() = name.trim().take(1).uppercase().ifEmpty { "?" }
}

// ---------- Library ----------

/** MOVIE / SERIES are from the IPTV provider; ADDON_* are from streaming add-ons (id = meta id). */
enum class SavedKind { MOVIE, SERIES, ADDON_MOVIE, ADDON_SERIES }

data class SavedItem(val kind: SavedKind, val id: String, val title: String, val image: String?, val addedAt: Long) {
    val key: String get() = "${kind.name}:$id"
    val isMovie: Boolean get() = kind == SavedKind.MOVIE || kind == SavedKind.ADDON_MOVIE
}

/** Where the viewer stopped in something they can resume (movie, episode, recording). */
data class ResumePoint(
    val key: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val progress: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * "Continue watching" shows one card per show: its most recently watched episode. Every episode
 * keeps its own resume point (for the show's episode list); only the row is combined.
 */
object ContinueWatching {
    /**
     * What a resume key belongs to: an add-on title ("addon:series:tt123|tt123:1:2" → "addon:series:tt123"),
     * a provider show ("ep:<seriesId>:<episodeId>:<ext>" → "ep:<seriesId>"), or itself (movies, recordings).
     */
    fun groupKey(key: String): String = when {
        key.startsWith("addon:") -> key.substringBefore('|')
        key.startsWith("ep:") -> "ep:" + key.removePrefix("ep:").substringBefore(':')
        else -> key
    }

    /** The latest point of each show (and every movie), most recent first. */
    fun latestPerShow(points: List<ResumePoint>): List<ResumePoint> =
        points.sortedByDescending { it.updatedAt }.distinctBy { groupKey(it.key) }
}

// ---------- Recordings (DVR) ----------

enum class RecStatus(val label: String) {
    SCHEDULED("Scheduled"),
    RECORDING("Recording"),
    DONE("Recorded"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
}

data class Recording(
    val id: String,
    val accountId: String,
    val title: String,
    val subtitle: String,
    val channelId: String,
    val channelName: String,
    val channelLogo: String?,
    /** Stream addresses to record from, best first. */
    val urls: List<String>,
    val startMillis: Long,
    val endMillis: Long,
    val status: RecStatus,
    val file: String? = null,
    val bytes: Long = 0,
    /** Sports event this records ("nfl:4018..."), so the recording can run long with the game. */
    val eventId: String? = null,
    val image: String? = null,
    val error: String? = null,
    /** Scheduled automatically for a team in the library. */
    val auto: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val durationMs: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}
