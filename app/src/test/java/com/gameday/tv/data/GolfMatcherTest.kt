package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GolfMatcherTest {

    private val pga = Leagues.golf.first { it.key == "pga" }
    private val dp = Leagues.golf.first { it.key == "dpwt" }

    private fun tournament(tour: League, name: String, broadcasts: List<String>) = Tournament(
        id = "${tour.key}:1",
        tour = tour,
        name = name,
        startMillis = 0,
        endMillis = 0,
        state = GameState.LIVE,
        roundInProgress = true,
        detail = "Round 3 - In Progress",
        broadcasts = broadcasts,
        leaders = emptyList(),
        fieldSize = 120,
    )

    private val utah = tournament(pga, "Bank of Utah Championship", listOf("ESPN+", "Golf Chnl"))
    private val dunhill = tournament(dp, "Alfred Dunhill Links Championship", emptyList())

    private fun ch(name: String, group: String) = Channel(id = name, name = name, logo = null, group = group)

    private val catalog = IptvCatalog(
        groups = listOf("US Sports", "UK Sports", "PGA Tour Live", "US News"),
        channels = listOf(
            ch("US: Golf Channel HD", "US Sports"),
            ch("PGA TOUR LIVE 1: Bank of Utah - Featured Groups", "PGA Tour Live"),
            ch("UK: Sky Sports Golf FHD", "UK Sports"),
            ch("DP World Tour: Dunhill Links", "UK Sports"),
            ch("US: ESPN+ 03", "US Sports"),
            ch("US: ESPN", "US Sports"),
            ch("Golf Digest News", "US News"),
        ),
    )

    @Test
    fun pgaEventChannelThenGolfChannel() {
        val names = ChannelMatcher.match(utah, catalog).map { it.channel.name }
        assertEquals("PGA TOUR LIVE 1: Bank of Utah - Featured Groups", names[0])
        assertTrue(names.indexOf("US: Golf Channel HD") in 1..2)
        assertTrue("US: ESPN+ 03" in names)
        assertFalse("US: ESPN" in names)
        assertFalse("Golf Digest News" in names)
    }

    @Test
    fun dpWorldTourUsesUsualNetworksWhenNoneListed() {
        val matches = ChannelMatcher.match(dunhill, catalog)
        assertEquals("DP World Tour: Dunhill Links", matches.first().channel.name)
        assertTrue(matches.first().exact)
        // Sky Sports Golf is the DP World Tour's main carrier, so it outranks Golf Channel.
        val names = matches.map { it.channel.name }
        assertTrue(names.indexOf("UK: Sky Sports Golf FHD") < names.indexOf("US: Golf Channel HD"))
    }

    @Test
    fun golfBugFindsTournamentFromChannel() {
        val live = listOf(utah, dunhill)
        assertEquals(utah, ChannelMatcher.findTournamentForChannel("US: Golf Channel HD", live))
        assertEquals(dunhill, ChannelMatcher.findTournamentForChannel("DP World Tour: Dunhill Links", live))
        assertEquals(dunhill, ChannelMatcher.findTournamentForChannel("UK: Sky Sports Golf HD", live))
        assertNull(ChannelMatcher.findTournamentForChannel("US: ESPN", live))
    }
}
