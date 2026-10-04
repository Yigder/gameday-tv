package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayAndProvidersTest {
    @Test
    fun delayedHistoryReturnsWhatWasCurrentThen() {
        val h = DelayedHistory<String>()
        h.record("g", "0-0", at = 1_000, same = { a, b -> a == b })
        h.record("g", "0-0", at = 16_000, same = { a, b -> a == b }) // unchanged: not stored again
        h.record("g", "7-0", at = 31_000, same = { a, b -> a == b })
        h.record("g", "7-3", at = 61_000, same = { a, b -> a == b })
        assertEquals("0-0", h.at("g", 30_000))
        assertEquals("7-0", h.at("g", 31_000))
        assertEquals("7-0", h.at("g", 60_999))
        assertEquals("7-3", h.at("g", 90_000))
        // Before anything was seen: the oldest known version.
        assertEquals("0-0", h.at("g", 0))
        assertNull(h.at("other", 50_000))
    }

    @Test
    fun delayedHistoryKeepsTheVersionStillOnScreen() {
        val h = DelayedHistory<String>(keepMs = 10_000)
        h.record("g", "a", at = 0, same = { x, y -> x == y })
        h.record("g", "b", at = 5_000, same = { x, y -> x == y })
        h.record("g", "c", at = 100_000, same = { x, y -> x == y })
        // "a" is pruned, "b" (the newest older than the window) stays.
        assertEquals("b", h.at("g", 50_000))
        assertEquals("c", h.at("g", 100_000))
    }

    @Test
    fun providerIdsAndStorage() {
        assertEquals("x12", Providers.scoped("", "x12"))
        assertEquals("pab~x12", Providers.scoped("pab", "x12"))
        assertEquals("pab", Providers.prefixOf("pab~x12"))
        assertEquals("", Providers.prefixOf("x12"))
        assertEquals("x12", Providers.unscoped("pab~x12"))
        val list = listOf(
            ProviderEntry("1", "Main", IptvAccount.Xtream("http://h:8080", "u", "p"), ""),
            ProviderEntry("2", "Backup", IptvAccount.M3u("http://h/list.m3u", "http://h/epg.xml"), "pzz2", enabled = false),
        )
        assertEquals(list, Providers.decode(Providers.encode(list)))
        assertTrue(Providers.newPrefix(listOf("pabc")).startsWith("p"))
    }

    @Test
    fun mergesCatalogsKeepingGroupOrder() {
        val a = IptvCatalog(listOf("Sports", "News"), listOf(Channel("x1", "ESPN", null, "Sports"), Channel("x2", "CNN", null, "News")))
        val b = IptvCatalog(listOf("Movies", "Sports"), listOf(Channel("p~x1", "HBO", null, "Movies"), Channel("p~x2", "ESPN 2", null, "Sports")))
        val m = Providers.merge(listOf(a, b))!!
        assertEquals(listOf("Sports", "News", "Movies"), m.groups)
        assertEquals(4, m.channels.size)
        assertEquals(2, m.byGroup["Sports"]?.size)
    }

    @Test
    fun golfIsOneSport() {
        val golf = Leagues.pick(Leagues.GOLF)!!
        assertEquals(setOf("pga", "dpwt"), golf.leagueKeys)
        assertEquals(1, Leagues.picks.count { it.sport == "golf" })
    }
}
