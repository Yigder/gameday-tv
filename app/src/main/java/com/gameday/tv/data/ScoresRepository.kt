package com.gameday.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object Leagues {
    val all = listOf(
        League("nfl", "NFL", "football", "nfl", typicalMinutes = 210,
            keywords = listOf("NFL", "SUNDAY TICKET", "REDZONE", "RED ZONE", "NFL GAME PASS")),
        League("ncaaf", "NCAAF", "football", "college-football", "?groups=80&limit=300", typicalMinutes = 225,
            keywords = listOf("NCAAF", "NCAA", "COLLEGE FOOTBALL", "CFB")),
        League("mlb", "MLB", "baseball", "mlb", typicalMinutes = 195,
            keywords = listOf("MLB", "EXTRA INNINGS")),
        League("nba", "NBA", "basketball", "nba", typicalMinutes = 165,
            keywords = listOf("NBA", "LEAGUE PASS")),
        League("nhl", "NHL", "hockey", "nhl", typicalMinutes = 165,
            keywords = listOf("NHL", "CENTER ICE")),
        League("wnba", "WNBA", "basketball", "wnba", typicalMinutes = 150,
            keywords = listOf("WNBA")),
        League("ncaam", "NCAAM", "basketball", "mens-college-basketball", "?groups=50&limit=300", typicalMinutes = 150,
            keywords = listOf("NCAAB", "NCAA", "COLLEGE BASKETBALL", "MARCH MADNESS")),
        League("mls", "MLS", "soccer", "usa.1", typicalMinutes = 135,
            keywords = listOf("MLS", "MLS SEASON PASS")),
        League("epl", "Premier League", "soccer", "eng.1", typicalMinutes = 135,
            keywords = listOf("EPL", "PREMIER LEAGUE")),
        League("ucl", "Champions League", "soccer", "uefa.champions", typicalMinutes = 135,
            keywords = listOf("UCL", "CHAMPIONS LEAGUE")),
        League("laliga", "LaLiga", "soccer", "esp.1", typicalMinutes = 135,
            keywords = listOf("LALIGA", "LA LIGA")),
    )

    val golf = listOf(
        League("pga", "PGA Tour", "golf", "pga",
            keywords = listOf("PGA", "PGA TOUR", "GOLF"),
            defaultNetworks = listOf("Golf Channel", "PGA Tour Live"), typicalMinutes = 300),
        League("dpwt", "DP World Tour", "golf", "eur",
            keywords = listOf("DP WORLD", "DP WORLD TOUR", "EUROPEAN TOUR", "GOLF"),
            defaultNetworks = listOf("Sky Sports Golf", "Golf Channel", "DP World Tour"), typicalMinutes = 300),
    )

    val everything = all + golf

    fun byKey(key: String): League? = everything.firstOrNull { it.key == key }

    const val GOLF = "golf"

    /** What the viewer picks in the UI: each team league, and one "Golf" covering every tour. */
    val picks: List<SportPick> = all.map { SportPick(it.key, it.label, it.sport, listOf(it)) } +
        SportPick(GOLF, "Golf", "golf", golf)

    fun pick(key: String): SportPick? = picks.firstOrNull { it.key == key }
}

data class SportPick(val key: String, val label: String, val sport: String, val leagues: List<League>) {
    val leagueKeys: Set<String> get() = leagues.mapTo(HashSet()) { it.key }
}

/** Live scores from ESPN's public scoreboard feeds (no API key required). */
class ScoresRepository {

    suspend fun fetch(league: League): List<Game> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/${league.sport}/${league.path}/scoreboard${league.query}"
        val events = JSONObject(Http.getString(url)).optJSONArray("events") ?: return emptyList()
        return events.objects().mapNotNull { runCatching { parseEvent(league, it) }.getOrNull() }.toList()
    }

    /** Every team in a league, for the favorites picker. */
    suspend fun fetchTeams(league: League): List<FavoriteTeam> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/${league.sport}/${league.path}/teams?limit=1000"
        val teams = JSONObject(Http.getString(url))
            .optJSONArray("sports")?.optJSONObject(0)
            ?.optJSONArray("leagues")?.optJSONObject(0)
            ?.optJSONArray("teams") ?: return emptyList()
        return teams.objects().mapNotNull { it.optJSONObject("team") }.mapNotNull { t ->
            val id = t.optString("id").clean() ?: return@mapNotNull null
            FavoriteTeam(
                leagueKey = league.key,
                id = id,
                name = t.optString("displayName").clean() ?: return@mapNotNull null,
                abbreviation = t.optString("abbreviation").clean().orEmpty(),
                logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href").clean(),
            )
        }.sortedBy { it.name }.toList()
    }

    /** A team's season schedule: past results and upcoming games. */
    suspend fun fetchTeamSchedule(league: League, teamId: String): List<Game> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/${league.sport}/${league.path}/teams/$teamId/schedule"
        val events = JSONObject(Http.getString(url)).optJSONArray("events") ?: return emptyList()
        return events.objects().mapNotNull { runCatching { parseEvent(league, it) }.getOrNull() }.sortedBy { it.startMillis }.toList()
    }

    suspend fun fetchTeamInfo(league: League, teamId: String): TeamInfo? {
        val url = "https://site.api.espn.com/apis/site/v2/sports/${league.sport}/${league.path}/teams/$teamId"
        val t = JSONObject(Http.getString(url)).optJSONObject("team") ?: return null
        return TeamInfo(
            leagueKey = league.key,
            id = teamId,
            name = t.optString("displayName").clean() ?: return null,
            abbreviation = t.optString("abbreviation").clean().orEmpty(),
            logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href").clean(),
            color = t.optString("color").clean(),
            alternateColor = t.optString("alternateColor").clean(),
            record = t.optJSONObject("record")?.optJSONArray("items")?.optJSONObject(0)?.optString("summary").clean(),
            standing = t.optString("standingSummary").clean(),
        )
    }

    /** Box score, scoring plays and leaders for the in-player Stats panel and the game page. */
    suspend fun fetchSummary(game: Game): GameStats {
        val l = game.league
        val url = "https://site.api.espn.com/apis/site/v2/sports/${l.sport}/${l.path}/summary?event=${game.eventId}"
        val root = JSONObject(Http.getString(url))

        // Team stats: line up the two teams' rows by stat name, in the away team's order.
        val teams = root.optJSONObject("boxscore")?.optJSONArray("teams")?.objects()?.toList().orEmpty()
        fun teamFor(id: String) = teams.firstOrNull { it.optJSONObject("team")?.optString("id") == id }
        val awayStats = teamFor(game.away.id)?.optJSONArray("statistics")?.objects()?.toList().orEmpty()
        val homeStats = teamFor(game.home.id)?.optJSONArray("statistics")?.objects()?.associateBy { it.optString("name") }.orEmpty()
        val rows = awayStats.mapNotNull { s ->
            val label = s.optString("label").clean() ?: s.optString("abbreviation").clean() ?: return@mapNotNull null
            val home = homeStats[s.optString("name")] ?: return@mapNotNull null
            StatRow(label, s.optString("displayValue").clean() ?: "-", home.optString("displayValue").clean() ?: "-")
        }

        val playsSource = root.optJSONArray("scoringPlays")?.takeIf { it.length() > 0 }
            ?: root.optJSONArray("keyEvents")
        val plays = playsSource?.objects()?.mapNotNull { p ->
            val text = p.optString("text").clean() ?: p.optJSONObject("type")?.optString("text").clean() ?: return@mapNotNull null
            val team = p.optJSONObject("team")
            val period = p.optJSONObject("period")?.let { periodLabel(l, it.optInt("number")) }.orEmpty()
            PlayItem(
                text = text,
                period = period,
                clock = p.optJSONObject("clock")?.optString("displayValue").clean().orEmpty(),
                teamAbbr = team?.optString("abbreviation").clean(),
                teamLogo = team?.optString("logo").clean(),
                awayScore = p.optString("awayScore").clean(),
                homeScore = p.optString("homeScore").clean(),
            )
        }?.toList().orEmpty()

        val leaders = root.optJSONArray("leaders")?.objects()?.mapNotNull { t ->
            val team = t.optJSONObject("team") ?: return@mapNotNull null
            val items = t.optJSONArray("leaders")?.objects()?.mapNotNull { cat ->
                val top = cat.optJSONArray("leaders")?.optJSONObject(0) ?: return@mapNotNull null
                val athlete = top.optJSONObject("athlete") ?: return@mapNotNull null
                LeaderItem(
                    category = cat.optString("displayName").clean() ?: cat.optString("name"),
                    athlete = athlete.optString("shortName").clean() ?: athlete.optString("displayName"),
                    value = top.optString("displayValue").clean().orEmpty(),
                    headshot = athlete.optJSONObject("headshot")?.optString("href").clean(),
                )
            }?.toList().orEmpty()
            TeamLeaders(team.optString("abbreviation"), team.optString("logo").clean(), items)
        }?.toList().orEmpty()

        return GameStats(rows, plays, leaders)
    }

    private fun periodLabel(league: League, n: Int): String = when {
        n <= 0 -> ""
        league.sport == "baseball" -> "Inning $n"
        league.sport == "soccer" -> if (n == 1) "1st half" else if (n == 2) "2nd half" else "ET"
        league.sport == "hockey" -> if (n <= 3) "P$n" else "OT"
        else -> if (n <= 4) "Q$n" else "OT"
    }

    // ---------------- golf ----------------

    suspend fun fetchGolf(tour: League): List<Tournament> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/golf/${tour.path}/scoreboard"
        val events = JSONObject(Http.getString(url)).optJSONArray("events") ?: return emptyList()
        return events.objects().mapNotNull { runCatching { parseTournament(tour, it) }.getOrNull() }.toList()
    }

    private fun parseTournament(tour: League, ev: JSONObject): Tournament? {
        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        // The event status tracks the tournament week; the competition status tracks the current round.
        val eventType = ev.optJSONObject("status")?.optJSONObject("type") ?: JSONObject()
        val roundType = comp.optJSONObject("status")?.optJSONObject("type") ?: eventType
        val state = when (eventType.optString("state")) {
            "in" -> GameState.LIVE
            "post" -> GameState.FINAL
            else -> GameState.PRE
        }

        val broadcasts = LinkedHashSet<String>()
        comp.optJSONArray("broadcasts")?.objects()?.forEach { b ->
            b.optJSONArray("names")?.let { names -> for (i in 0 until names.length()) names.optString(i).clean()?.let(broadcasts::add) }
        }
        comp.optJSONArray("geoBroadcasts")?.objects()?.forEach { g ->
            g.optJSONObject("media")?.optString("shortName").clean()?.let(broadcasts::add)
        }

        val competitors = comp.optJSONArray("competitors")?.objects()?.toList().orEmpty()
            .sortedBy { it.optInt("order", Int.MAX_VALUE) }
        val totals = competitors.map { parToInt(it.optString("score")) }
        val leaders = competitors.take(MAX_LEADERS).mapIndexed { i, c ->
            val total = totals[i]
            val firstWithScore = totals.indexOf(total)
            val tied = total != null && totals.count { it == total } > 1
            val athlete = c.optJSONObject("athlete") ?: JSONObject()
            val (today, thru) = currentRound(c)
            GolfPlayer(
                id = c.optString("id"),
                name = athlete.optString("displayName").clean() ?: athlete.optString("fullName"),
                shortName = athlete.optString("shortName").clean() ?: athlete.optString("displayName"),
                flag = athlete.optJSONObject("flag")?.optString("href").clean(),
                position = when {
                    total == null -> "-"
                    tied -> "T${firstWithScore + 1}"
                    else -> "${firstWithScore + 1}"
                },
                toPar = c.optString("score").clean() ?: "-",
                today = today,
                thru = thru,
            )
        }

        return Tournament(
            id = "${tour.key}:${ev.optString("id")}",
            tour = tour,
            name = ev.optString("name").clean() ?: ev.optString("shortName"),
            startMillis = parseDate(ev.optString("date")),
            endMillis = parseDate(ev.optString("endDate")),
            state = state,
            roundInProgress = roundType.optString("state") == "in",
            detail = roundType.optString("shortDetail").clean() ?: roundType.optString("description"),
            broadcasts = broadcasts.toList(),
            leaders = leaders,
            fieldSize = competitors.size,
        )
    }

    /** Today's score and holes completed, from the latest round the player has started. */
    private fun currentRound(c: JSONObject): Pair<String?, String?> {
        val rounds = c.optJSONArray("linescores")?.objects()?.toList().orEmpty()
        val round = rounds.lastOrNull { r -> r.has("value") || (r.optJSONArray("linescores")?.length() ?: 0) > 0 }
            ?: return null to null
        val holes = round.optJSONArray("linescores")?.objects()?.count { it.has("value") } ?: 0
        val thru = when {
            holes >= 18 -> "F"
            holes > 0 -> holes.toString()
            round.has("value") -> "F" // some tours only report round totals, no hole-by-hole
            else -> null
        }
        return round.optString("displayValue").clean() to thru
    }

    private fun parToInt(s: String): Int? = when (val t = s.trim()) {
        "E" -> 0
        else -> t.removePrefix("+").toIntOrNull()
    }

    // ---------------- team sports ----------------

    private fun parseEvent(league: League, ev: JSONObject): Game? {
        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val status = ev.optJSONObject("status") ?: comp.optJSONObject("status") ?: return null
        val type = status.optJSONObject("type") ?: JSONObject()
        val state = when (type.optString("state")) {
            "in" -> GameState.LIVE
            "post" -> GameState.FINAL
            else -> GameState.PRE
        }

        var home: TeamScore? = null
        var away: TeamScore? = null
        comp.optJSONArray("competitors")?.objects()?.forEach { c ->
            val team = parseTeam(c)
            if (c.optString("homeAway") == "home") home = team else away = team
        }
        val h = home ?: return null
        val a = away ?: return null

        val broadcasts = LinkedHashSet<String>()
        comp.optJSONArray("broadcasts")?.objects()?.forEach { b ->
            b.optJSONArray("names")?.let { names -> for (i in 0 until names.length()) names.optString(i).clean()?.let(broadcasts::add) }
            // Team schedules list broadcasters as media objects instead of names.
            b.optJSONObject("media")?.optString("shortName").clean()?.let(broadcasts::add)
        }
        comp.optJSONArray("geoBroadcasts")?.objects()?.forEach { g ->
            g.optJSONObject("media")?.optString("shortName").clean()?.let(broadcasts::add)
        }

        val sit = comp.optJSONObject("situation")
        val situation = if (state == GameState.LIVE && sit != null) describeSituation(sit) else null

        return Game(
            id = "${league.key}:${ev.optString("id")}",
            league = league,
            startMillis = parseDate(ev.optString("date")),
            state = state,
            shortDetail = type.optString("shortDetail").clean() ?: type.optString("description"),
            detail = type.optString("detail").clean() ?: type.optString("description"),
            away = a,
            home = h,
            broadcasts = broadcasts.toList(),
            venue = comp.optJSONObject("venue")?.optString("fullName").clean(),
            situation = situation,
            possessionTeamId = sit?.optString("possession").clean(),
        )
    }

    private fun parseTeam(c: JSONObject): TeamScore {
        val t = c.optJSONObject("team") ?: JSONObject()
        val displayName = t.optString("displayName").clean() ?: t.optString("name")
        return TeamScore(
            id = t.optString("id"),
            abbreviation = t.optString("abbreviation").clean() ?: displayName.take(3).uppercase(),
            displayName = displayName,
            shortName = t.optString("shortDisplayName").clean() ?: displayName,
            name = t.optString("name").clean().orEmpty(),
            location = t.optString("location").clean().orEmpty(),
            logo = t.optString("logo").clean() ?: t.optJSONArray("logos")?.optJSONObject(0)?.optString("href").clean(),
            color = t.optString("color").clean(),
            alternateColor = t.optString("alternateColor").clean(),
            // Scoreboards send the score as text; team schedules send {value, displayValue}.
            score = when (val s = c.opt("score")) {
                is JSONObject -> s.optString("displayValue").clean().orEmpty()
                else -> s?.toString().clean().orEmpty()
            },
            record = c.optJSONArray("records")?.optJSONObject(0)?.optString("summary").clean(),
            winner = c.optBoolean("winner", false),
        )
    }

    private fun describeSituation(sit: JSONObject): String? {
        sit.optString("downDistanceText").clean()?.let { return it }
        sit.optString("shortDownDistanceText").clean()?.let { return it }
        if (sit.has("outs")) {
            val outs = sit.optInt("outs")
            val bases = listOfNotNull(
                "1st".takeIf { sit.optBoolean("onFirst") },
                "2nd".takeIf { sit.optBoolean("onSecond") },
                "3rd".takeIf { sit.optBoolean("onThird") },
            )
            val count = "${sit.optInt("balls")}-${sit.optInt("strikes")}, $outs out"
            return if (bases.isEmpty()) count else "$count • On ${bases.joinToString(", ")}"
        }
        return null
    }

    private fun parseDate(s: String): Long =
        runCatching { Instant.parse(s).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")).toInstant().toEpochMilli() }
            .getOrDefault(0L)

    private companion object {
        const val MAX_LEADERS = 60
    }
}

internal fun JSONArray.objects(): Sequence<JSONObject> =
    (0 until length()).asSequence().mapNotNull { optJSONObject(it) }
