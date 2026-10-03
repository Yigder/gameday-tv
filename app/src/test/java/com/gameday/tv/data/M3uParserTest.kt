package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private val playlist = """
        #EXTM3U url-tvg="http://epg.example/xml"
        #EXTINF:-1 tvg-id="espn.us" tvg-name="ESPN" tvg-logo="http://logos.example/espn.png" group-title="USA, Sports",US: ESPN HD
        http://provider.example/live/u/p/101.ts
        #EXTINF:-1 tvg-chno="7" group-title="Locals",ABC 7, New York
        http://provider.example/live/u/p/102.ts
        #EXTINF:-1 group-title="Movies",Some Movie (2024)
        http://provider.example/movie/u/p/555.mp4
        #EXTINF:-1,No Group Channel
        #EXTGRP:Misc
        http://provider.example/live/u/p/103.m3u8
    """.trimIndent()

    @Test
    fun parsesChannelsGroupsAndSkipsVod() {
        val catalog = M3uParser.parse(playlist.byteInputStream())
        assertEquals(3, catalog.channels.size)
        assertEquals(listOf("USA, Sports", "Locals", "Misc"), catalog.groups)

        val espn = catalog.channels[0]
        assertEquals("US: ESPN HD", espn.name)
        assertEquals("USA, Sports", espn.group) // comma inside quoted attribute
        assertEquals("http://logos.example/espn.png", espn.logo)
        assertEquals("http://provider.example/live/u/p/101.ts", espn.url)

        val abc = catalog.channels[1]
        assertEquals("ABC 7, New York", abc.name) // comma inside the title
        assertEquals(7, abc.num)
        assertNull(abc.logo)

        assertEquals("Misc", catalog.channels[2].group)
    }
}
