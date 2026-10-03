package com.gameday.tv.data

// ---------- IPTV ----------

sealed interface IptvAccount {
    data class Xtream(val server: String, val username: String, val password: String) : IptvAccount
    data class M3u(val url: String) : IptvAccount
}

data class AccountInfo(
    val status: String?,
    val expiresAtMillis: Long?,
    val maxConnections: String?,
    val activeConnections: String?,
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
)

class IptvCatalog(val groups: List<String>, val channels: List<Channel>) {
    val byGroup: Map<String, List<Channel>> = channels.groupBy { it.group }

    /** Normalized channel names, index-aligned with [channels]. Built once, used for matching/search. */
    val normNames: Array<String> by lazy { Array(channels.size) { ChannelMatcher.norm(channels[it].name) } }
    val normGroups: Map<String, String> by lazy { groups.associateWith { ChannelMatcher.norm(it) } }
    val sportsGroups: List<String> by lazy { groups.filter { SportsFilter.isSportsGroup(normGroups[it].orEmpty()) } }
    val sportsGroupSet: Set<String> by lazy { sportsGroups.toHashSet() }
}

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
)

/** A team the user starred. Identified by league + ESPN team id. */
data class FavoriteTeam(
    val leagueKey: String,
    val id: String,
    val name: String,
    val abbreviation: String,
    val logo: String?,
) {
    val key: String get() = "$leagueKey:$id"
}

// ---------- Preferences ----------

enum class ScoreBugMode(val label: String) {
    /** Shows when a stream starts, then hides until OK / Info is pressed (ScoreBox style). */
    ON_PRESS("Show on button press"),
    ALWAYS("Always on"),
}

enum class MultiviewLayout(val screens: Int, val label: String) {
    ONE(1, "1"),
    TWO(2, "2"),
    THREE(3, "3"),
    FOUR(4, "4"),
    ONE_PLUS_THREE(4, "1+3");

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
)
