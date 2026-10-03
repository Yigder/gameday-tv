package com.gameday.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object Leagues {
    val all = listOf(
        League("nfl", "NFL", "football", "nfl",
            keywords = listOf("NFL", "SUNDAY TICKET", "REDZONE", "RED ZONE", "NFL GAME PASS")),
        League("ncaaf", "NCAAF", "football", "college-football", "?groups=80&limit=300",
            keywords = listOf("NCAAF", "NCAA", "COLLEGE FOOTBALL", "CFB")),
        League("mlb", "MLB", "baseball", "mlb",
            keywords = listOf("MLB", "EXTRA INNINGS")),
        League("nba", "NBA", "basketball", "nba",
            keywords = listOf("NBA", "LEAGUE PASS")),
        League("nhl", "NHL", "hockey", "nhl",
            keywords = listOf("NHL", "CENTER ICE")),
        League("wnba", "WNBA", "basketball", "wnba",
            keywords = listOf("WNBA")),
        League("ncaam", "NCAAM", "basketball", "mens-college-basketball", "?groups=50&limit=300",
            keywords = listOf("NCAAB", "NCAA", "COLLEGE BASKETBALL", "MARCH MADNESS")),
        League("mls", "MLS", "soccer", "usa.1",
            keywords = listOf("MLS", "MLS SEASON PASS")),
        League("epl", "Premier League", "soccer", "eng.1",
            keywords = listOf("EPL", "PREMIER LEAGUE")),
        League("ucl", "Champions League", "soccer", "uefa.champions",
            keywords = listOf("UCL", "CHAMPIONS LEAGUE")),
        League("laliga", "LaLiga", "soccer", "esp.1",
            keywords = listOf("LALIGA", "LA LIGA")),
    )

    val golf = listOf(
        League("pga", "PGA Tour", "golf", "pga",
            keywords = listOf("PGA", "PGA TOUR", "GOLF"),
            defaultNetworks = listOf("Golf Channel", "PGA Tour Live")),
        League("dpwt", "DP World Tour", "golf", "eur",
            keywords = listOf("DP WORLD", "DP WORLD TOUR", "EUROPEAN TOUR", "GOLF"),
            defaultNetworks = listOf("Sky Sports Golf", "Golf Channel", "DP World Tour")),
    )

    val everything = all + golf
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
            score = c.optString("score").clean().orEmpty(),
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
