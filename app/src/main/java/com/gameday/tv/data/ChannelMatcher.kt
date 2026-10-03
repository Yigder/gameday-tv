package com.gameday.tv.data

import java.text.Normalizer
import java.util.Locale

data class ChannelMatch(
    val channel: Channel,
    val score: Int,
    val reasons: List<String>,
    /** Channel is specific to this event (both teams, or the tournament name, appear in it). */
    val exact: Boolean,
)

/**
 * Finds the IPTV channels most likely to be showing a game.
 *
 * IPTV channel names are messy ("US| ESPN FHD", "NFL 03: Chiefs @ Bills", "USA NBC SPORTS CHICAGO"),
 * so names are normalized to uppercase words and scored on:
 *  - team names (full name > nickname > short name > city), with a big bonus when both teams appear,
 *  - the national broadcast network ESPN reports for the game (ESPN, FOX, TNT, Prime Video...),
 *  - league packages/categories (NFL Sunday Ticket, NBA League Pass, "NHL" groups...).
 */
object ChannelMatcher {

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^A-Z0-9]+")

    fun norm(s: String): String {
        val plain = Normalizer.normalize(s.replace("+", " PLUS "), Normalizer.Form.NFD).replace(DIACRITICS, "")
        return plain.uppercase(Locale.US).replace(NON_ALNUM, " ").trim()
    }

    /** True if [needle] appears in [hay] as whole words. Both must be normalized. */
    fun containsWord(hay: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        var i = hay.indexOf(needle)
        while (i >= 0) {
            val end = i + needle.length
            if ((i == 0 || hay[i - 1] == ' ') && (end == hay.length || hay[end] == ' ')) return true
            i = hay.indexOf(needle, i + 1)
        }
        return false
    }

    // ---- name cleanup ----

    private val QUALITY = setOf(
        "HD", "FHD", "UHD", "SD", "4K", "8K", "HEVC", "H265", "H264", "RAW", "VIP", "HQ", "LQ",
        "1080P", "1080", "720P", "720", "60FPS", "50FPS", "FPS", "BACKUP", "ALT", "MULTI", "LIVE",
    )
    private val COUNTRY = setOf("US", "USA", "UK", "CA", "CAN", "EN", "ENG", "AM", "NA")

    /** Name variants with quality tags removed, and optionally a leading country tag ("US ESPN HD" -> "ESPN"). */
    private fun variants(n: String): Set<String> {
        val words = n.split(' ').filter { it.isNotEmpty() && it !in QUALITY }
        val v1 = words.joinToString(" ")
        val v2 = if (words.size > 1 && words[0] in COUNTRY) words.drop(1).joinToString(" ") else v1
        return setOf(v1, v2)
    }

    private val NON_SPORTS = listOf("NEWS", "BUSINESS", "WEATHER", "KIDS", "MOVIE", "MOVIES", "CINEMA", "FAMILY", "DRAMA", "COMEDY", "RADIO", "MUSIC")

    private fun isNonSports(n: String) = NON_SPORTS.any { containsWord(n, it) }

    // ---- team phrases ----

    private data class Phrase(val text: String, val weight: Int, val label: String)

    private val GENERIC = setOf("UNITED", "CITY", "CLUB", "TEAM", "SPORT", "SPORTS", "STATE", "REAL", "ATHLETIC", "FC", "SC", "CF")

    private fun teamPhrases(t: TeamScore): List<Phrase> {
        val out = ArrayList<Phrase>(4)
        val seen = HashSet<String>()
        fun add(raw: String, weight: Int) {
            val n = norm(raw)
            if (n.length >= 4 && n !in GENERIC && seen.add(n)) out += Phrase(n, weight, raw)
        }
        add(t.displayName, 12)
        add(t.name, 10)
        add(t.shortName, 8)
        add(t.location, 5)
        return out
    }

    private fun bestTeamHit(n: String, phrases: List<Phrase>, abbr: String, leagueContext: Boolean): Phrase? {
        var best: Phrase? = null
        for (p in phrases) if ((best == null || p.weight > best.weight) && containsWord(n, p.text)) best = p
        if (best == null && leagueContext && abbr.length >= 2 && containsWord(n, abbr)) best = Phrase(abbr, 3, abbr)
        return best
    }

    // ---- networks ----

    private val NETWORK_ALIASES: Map<String, List<String>> = mapOf(
        "FS1" to listOf("FS1", "FOX SPORTS 1"),
        "FS2" to listOf("FS2", "FOX SPORTS 2"),
        "NFL NET" to listOf("NFL NETWORK", "NFL NET"),
        "NFLN" to listOf("NFL NETWORK"),
        "NFL NETWORK" to listOf("NFL NETWORK"),
        "MLB NET" to listOf("MLB NETWORK"),
        "MLBN" to listOf("MLB NETWORK"),
        "MLB NETWORK" to listOf("MLB NETWORK"),
        "NHL NET" to listOf("NHL NETWORK"),
        "NHL NETWORK" to listOf("NHL NETWORK"),
        "NBA TV" to listOf("NBA TV", "NBATV"),
        "USA NET" to listOf("USA NETWORK"),
        "USA NETWORK" to listOf("USA NETWORK"),
        "SECN" to listOf("SEC NETWORK", "SECN"),
        "SEC NETWORK" to listOf("SEC NETWORK", "SECN"),
        "ACCN" to listOf("ACC NETWORK", "ACCN"),
        "ACC NETWORK" to listOf("ACC NETWORK", "ACCN"),
        "BTN" to listOf("BIG TEN NETWORK", "BTN", "BIG 10 NETWORK"),
        "BIG TEN NETWORK" to listOf("BIG TEN NETWORK", "BTN", "BIG 10 NETWORK"),
        "CBSSN" to listOf("CBS SPORTS NETWORK", "CBSSN"),
        "CBS SPORTS NET" to listOf("CBS SPORTS NETWORK", "CBSSN"),
        "TRUTV" to listOf("TRUTV", "TRU TV"),
        "PRIME VIDEO" to listOf("PRIME VIDEO", "AMAZON PRIME", "THURSDAY NIGHT FOOTBALL", "TNF"),
        "APPLE TV" to listOf("APPLE TV", "MLS SEASON PASS"),
        "PARAMOUNT PLUS" to listOf("PARAMOUNT PLUS", "PARAMOUNT"),
        "ESPN PLUS" to listOf("ESPN PLUS"),
        "GOLF CHNL" to listOf("GOLF CHANNEL", "GOLF"),
        "GOLF CHANNEL" to listOf("GOLF CHANNEL", "GOLF"),
        "GOLF" to listOf("GOLF CHANNEL", "GOLF"),
        "SKY SPORTS GOLF" to listOf("SKY SPORTS GOLF", "SKY GOLF"),
        "PGA TOUR LIVE" to listOf("PGA TOUR LIVE", "ESPN PLUS PGA"),
    )

    private fun networkPhrases(broadcast: String): List<String> {
        val n = norm(broadcast)
        return NETWORK_ALIASES[n] ?: listOf(n)
    }

    // ---- event "sides": the two teams of a game, or the name of a golf tournament ----

    private class Side(
        val phrases: List<Phrase>,
        val abbr: String = "",
        /** Distinctive words of an event name; a channel containing enough of them is a hit. */
        val coreWords: List<String> = emptyList(),
        val coreLabel: String = "",
    )

    private val EVENT_FILLER = setOf(
        "THE", "OF", "AT", "AND", "BY", "PRESENTED", "CHAMPIONSHIP", "CHAMPIONSHIPS", "OPEN", "CLASSIC",
        "INVITATIONAL", "TOURNAMENT", "CUP", "GOLF", "PRO", "AM",
    )

    private fun teamSide(t: TeamScore) = Side(teamPhrases(t), norm(t.abbreviation))

    private fun tournamentSide(t: Tournament): Side {
        val full = norm(t.name)
        val core = full.split(' ').filter { it.length >= 3 && it !in EVENT_FILLER }.distinct()
        return Side(listOf(Phrase(full, 14, t.name)), coreWords = core, coreLabel = t.name)
    }

    private fun sideHit(n: String, side: Side, leagueContext: Boolean): Phrase? {
        bestTeamHit(n, side.phrases, side.abbr, leagueContext)?.let { return it }
        val core = side.coreWords
        if (core.isNotEmpty()) {
            val hits = core.count { containsWord(n, it) }
            if (hits == core.size || hits >= 2) return Phrase(core.joinToString(" "), 10, side.coreLabel)
        }
        return null
    }

    // ---- public API ----

    fun match(game: Game, catalog: IptvCatalog, limit: Int = 80): List<ChannelMatch> =
        matchEvent(listOf(teamSide(game.away), teamSide(game.home)), game.broadcasts, game.league, catalog, limit)

    fun match(tournament: Tournament, catalog: IptvCatalog, limit: Int = 80): List<ChannelMatch> =
        matchEvent(listOf(tournamentSide(tournament)), tournament.broadcasts, tournament.tour, catalog, limit)

    private fun matchEvent(
        sides: List<Side>,
        broadcasts: List<String>,
        league: League,
        catalog: IptvCatalog,
        limit: Int,
    ): List<ChannelMatch> {
        // Broadcasters listed for the event score higher than a league's usual networks,
        // and the first usual network (the main carrier) edges out the others.
        val networks = LinkedHashMap<String, Pair<List<String>, Int>>()
        league.defaultNetworks.forEachIndexed { i, n -> networks[n] = networkPhrases(n) to if (i == 0) 7 else 6 }
        broadcasts.forEach { networks[it] = networkPhrases(it) to 9 }
        val leagueKeys = league.keywords.map(::norm)
        val names = catalog.normNames
        val groupNorm = catalog.normGroups
        val sports = catalog.sportsGroupSet

        val out = ArrayList<ChannelMatch>()
        for (i in catalog.channels.indices) {
            val ch = catalog.channels[i]
            val n = names[i]
            val g = groupNorm[ch.group].orEmpty()
            val nonSports = isNonSports(n)
            val leagueInName = !nonSports && leagueKeys.any { containsWord(n, it) }
            val leagueInGroup = !nonSports && leagueKeys.any { containsWord(g, it) }
            val leagueContext = leagueInName || leagueInGroup

            val reasons = ArrayList<String>(3)
            var score = 0

            val hits = sides.map { sideHit(n, it, leagueContext) }
            hits.forEach { if (it != null) score += it.weight }
            val anySide = hits.any { it != null }
            val exact = if (sides.size >= 2) hits.all { it != null } else hits.firstOrNull()?.let { it.weight >= 10 } == true
            when {
                exact && sides.size >= 2 -> { score += 15; reasons += "Both teams" }
                exact -> { score += 6; reasons += hits.first()!!.label }
                else -> hits.firstNotNullOfOrNull { it }?.let { reasons += it.label }
            }

            var netScore = 0
            var netLabel: String? = null
            if (networks.isNotEmpty() && !nonSports) {
                val vs = variants(n)
                for ((label, entry) in networks) {
                    val (phrases, exactScore) = entry
                    for (p in phrases) {
                        val s = when {
                            p in vs -> exactScore
                            p.length >= 3 && containsWord(n, p) -> if (exactScore == 9) 5 else 3
                            else -> 0
                        }
                        if (s > netScore) { netScore = s; netLabel = label }
                    }
                }
            }
            if (netScore > 0) { score += netScore; reasons += "On $netLabel" }

            var leagueScore = 0
            if (leagueInName) leagueScore += 3
            if (leagueInGroup) leagueScore += 3
            if (leagueScore > 0) {
                score += leagueScore
                if (!anySide && netScore == 0) reasons += "${league.label} channel"
            }
            if (ch.group in sports) score += 1

            if ((anySide || netScore > 0 || leagueScore > 0) && score >= 3) {
                out += ChannelMatch(ch, score, reasons, exact)
            }
        }
        out.sortWith(compareByDescending<ChannelMatch> { it.score }.thenBy { it.channel.name.length })
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /** Best guess of which live tournament a channel is showing (event channel or listed broadcaster). */
    fun findTournamentForChannel(channelName: String, tournaments: List<Tournament>): Tournament? {
        val n = norm(channelName)
        val live = tournaments.filter { it.state == GameState.LIVE }
        live.firstOrNull { sideHit(n, tournamentSide(it), false) != null }?.let { return it }
        if (isNonSports(n)) return null
        val vs = variants(n)
        fun onNetwork(networks: (Tournament) -> List<String>) =
            live.filter { t -> networks(t).any { b -> networkPhrases(b).any { it in vs } } }.singleOrNull()
        // Listed broadcasters first; then the tour's usual networks (ESPN lists none for DP World Tour).
        return onNetwork { it.broadcasts } ?: onNetwork { it.tour.defaultNetworks }
    }

    /** Team-name score of a single channel name against a game (used for the in-player score bug). */
    private fun teamScore(n: String, game: Game): Int {
        val a = bestTeamHit(n, teamPhrases(game.away), "", false)
        val h = bestTeamHit(n, teamPhrases(game.home), "", false)
        return (a?.weight ?: 0) + (h?.weight ?: 0) + if (a != null && h != null) 15 else 0
    }

    /** Best guess of which live game a channel is currently showing, or null. */
    fun findGameForChannel(channelName: String, games: List<Game>, now: Long = System.currentTimeMillis()): Game? {
        val n = norm(channelName)
        val candidates = games.filter {
            it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMillis - now in 0..60 * 60_000L)
        }
        var best: Game? = null
        var bestScore = 0
        for (g in candidates) {
            val s = teamScore(n, g)
            if (s > bestScore || (s == bestScore && s > 0 && g.state == GameState.LIVE && best?.state != GameState.LIVE)) {
                best = g; bestScore = s
            }
        }
        if (bestScore >= 10) return best

        // Fall back to the broadcast network, but only if it's unambiguous.
        if (isNonSports(n)) return null
        val vs = variants(n)
        return candidates
            .filter { g -> g.state == GameState.LIVE && g.broadcasts.any { b -> networkPhrases(b).any { it in vs } } }
            .singleOrNull()
    }

    /**
     * Channels that belong to a sport in general: the league named in the channel or its category
     * (e.g. "NFL Sunday Ticket" groups, "NBA League Pass 03"), or the league's usual networks.
     */
    fun leagueChannels(league: League, catalog: IptvCatalog, limit: Int = 200): List<Channel> {
        val keys = league.keywords.map(::norm)
        val networks = league.defaultNetworks.flatMap { networkPhrases(it) }.toSet()
        val names = catalog.normNames
        val groupNorm = catalog.normGroups
        val hits = ArrayList<Pair<Int, Channel>>()
        for (i in catalog.channels.indices) {
            val ch = catalog.channels[i]
            val n = names[i]
            if (isNonSports(n)) continue
            val rank = when {
                keys.any { containsWord(n, it) } -> 0
                variants(n).any { it in networks } -> 1
                keys.any { containsWord(groupNorm[ch.group].orEmpty(), it) } -> 2
                else -> continue
            }
            hits += rank to ch
        }
        return hits.sortedWith(compareBy<Pair<Int, Channel>> { it.first }.thenBy { it.second.num })
            .take(limit).map { it.second }
    }

    fun search(catalog: IptvCatalog, query: String, groups: Set<String>?, limit: Int = 800): List<Channel> {
        val q = norm(query)
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val names = catalog.normNames
        val hits = ArrayList<Pair<Int, Channel>>()
        for (i in catalog.channels.indices) {
            val ch = catalog.channels[i]
            if (groups != null && ch.group !in groups) continue
            val n = names[i]
            if (tokens.all { n.contains(it) }) {
                val rank = when {
                    variants(n).contains(q) -> 0
                    containsWord(n, q) -> 1
                    else -> 2
                }
                hits += rank to ch
                if (hits.size >= limit * 3) break
            }
        }
        return hits.sortedWith(compareBy<Pair<Int, Channel>> { it.first }.thenBy { it.second.name.length })
            .take(limit).map { it.second }
    }
}

object SportsFilter {
    private val WORDS = listOf(
        "NFL", "NBA", "MLB", "NHL", "NCAA", "NCAAF", "NCAAB", "WNBA", "MLS", "EPL", "UFC", "PPV", "WWE", "AEW", "F1",
        "ESPN", "DAZN", "BEIN", "TSN", "FUBO", "GOLF", "TENNIS", "BOXING", "NASCAR", "INDYCAR", "CRICKET", "RUGBY",
        "SOCCER", "FOOTBALL", "FUTBOL", "LALIGA", "BUNDESLIGA", "EVENTS", "MATCHDAY", "REDZONE",
    )
    private val SUBSTRINGS = listOf(
        "SPORT", "DEPORTE", "LEAGUE PASS", "SUNDAY TICKET", "CENTER ICE", "EXTRA INNINGS", "PREMIER LEAGUE",
        "CHAMPIONS LEAGUE", "SERIE A", "LIGUE 1", "LA LIGA", "SEASON PASS",
    )

    fun isSportsGroup(normalizedName: String): Boolean =
        WORDS.any { ChannelMatcher.containsWord(normalizedName, it) } || SUBSTRINGS.any { normalizedName.contains(it) }
}
