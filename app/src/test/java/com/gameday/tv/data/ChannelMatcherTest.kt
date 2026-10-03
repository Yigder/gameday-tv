package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMatcherTest {

    private val nfl = Leagues.all.first { it.key == "nfl" }

    private fun team(id: String, display: String, name: String, location: String, abbr: String, score: String = "0") =
        TeamScore(id, abbr, display, name, name, location, null, null, score, null, false)

    private val game = Game(
        id = "nfl:1",
        league = nfl,
        startMillis = System.currentTimeMillis() - 60_000,
        state = GameState.LIVE,
        shortDetail = "Q3 - 4:12",
        detail = "3rd Quarter - 4:12",
        away = team("23", "Pittsburgh Steelers", "Steelers", "Pittsburgh", "PIT", "24"),
        home = team("5", "Cleveland Browns", "Browns", "Cleveland", "CLE", "27"),
        broadcasts = listOf("Prime Video"),
        venue = null,
        situation = null,
        possessionTeamId = null,
    )

    private fun ch(name: String, group: String) = Channel(id = name, name = name, logo = null, group = group)

    private val catalog = IptvCatalog(
        groups = listOf("USA | NFL Sunday Ticket", "USA | Sports", "USA | News", "USA | Entertainment"),
        channels = listOf(
            ch("US| ESPN FHD", "USA | Sports"),
            ch("NFL 03: Steelers @ Browns", "USA | NFL Sunday Ticket"),
            ch("NFL 04", "USA | NFL Sunday Ticket"),
            ch("USA PRIME VIDEO HD", "USA | Sports"),
            ch("FOX NEWS", "USA | News"),
            ch("US: NFL Network HD", "USA | Sports"),
            ch("Cleveland Browns 24/7", "USA | Sports"),
            ch("HGTV", "USA | Entertainment"),
        ),
    )

    @Test
    fun eventChannelWithBothTeamsRanksFirst() {
        val matches = ChannelMatcher.match(game, catalog)
        assertEquals("NFL 03: Steelers @ Browns", matches.first().channel.name)
        assertTrue(matches.first().exact)
    }

    @Test
    fun broadcastNetworkIsMatched() {
        val matches = ChannelMatcher.match(game, catalog).map { it.channel.name }
        assertTrue("Prime Video should match", "USA PRIME VIDEO HD" in matches)
        val prime = ChannelMatcher.match(game, catalog).first { it.channel.name == "USA PRIME VIDEO HD" }
        assertTrue(prime.reasons.any { it.contains("Prime Video") })
    }

    @Test
    fun unrelatedChannelsAreExcluded() {
        val names = ChannelMatcher.match(game, catalog).map { it.channel.name }
        assertFalse("FOX NEWS" in names)
        assertFalse("HGTV" in names)
        assertFalse("US| ESPN FHD" in names) // not this game's network
    }

    @Test
    fun leaguePackageChannelsAreIncludedBelowTeamMatches() {
        val names = ChannelMatcher.match(game, catalog).map { it.channel.name }
        assertTrue("NFL 04" in names)
        assertTrue(names.indexOf("NFL 04") > names.indexOf("Cleveland Browns 24/7"))
    }

    @Test
    fun scoreBugDetectsGameFromChannelName() {
        assertEquals(game, ChannelMatcher.findGameForChannel("NFL 03: Steelers @ Browns", listOf(game)))
        assertEquals(game, ChannelMatcher.findGameForChannel("US: PRIME VIDEO", listOf(game)))
        assertNull(ChannelMatcher.findGameForChannel("HGTV", listOf(game)))
    }

    @Test
    fun searchFindsByAnyWordOrder() {
        val hits = ChannelMatcher.search(catalog, "browns", null).map { it.name }
        assertEquals(setOf("NFL 03: Steelers @ Browns", "Cleveland Browns 24/7"), hits.toSet())
        assertEquals("US| ESPN FHD", ChannelMatcher.search(catalog, "espn", null).first().name)
    }

    @Test
    fun sportsGroupsDetected() {
        assertEquals(listOf("USA | NFL Sunday Ticket", "USA | Sports"), catalog.sportsGroups)
    }

    @Test
    fun normalizeHandlesPrefixesAndAccents() {
        assertEquals("US ESPN PLUS", ChannelMatcher.norm("US| ESPN+"))
        assertEquals("ATLETICO MADRID", ChannelMatcher.norm("Atlético Madrid"))
        assertTrue(ChannelMatcher.containsWord("NBA TV HD", "NBA TV"))
        assertFalse(ChannelMatcher.containsWord("WNBA LIVE", "NBA"))
    }

    @Test
    fun xtreamServerNormalization() {
        assertEquals("http://host.tv:8080", XtreamSource.normalizeServer("host.tv:8080"))
        assertEquals("https://host.tv", XtreamSource.normalizeServer("https://host.tv/player_api.php?username=a&password=b"))
        assertEquals("http://host.tv:80", XtreamSource.normalizeServer(" http://host.tv:80/get.php "))
    }
}
