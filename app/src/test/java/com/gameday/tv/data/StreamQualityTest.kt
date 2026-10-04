package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityTest {

    @Test
    fun parsesResolutionAndFrameRateFromNames() {
        assertEquals(StreamQuality(1080, 60f), StreamQuality.fromName("US: ESPN FHD 60FPS"))
        assertEquals(StreamQuality(720, 60f), StreamQuality.fromName("NFL 03 720p60"))
        assertEquals(StreamQuality(2160, 0f), StreamQuality.fromName("FOX 4K"))
        assertEquals(StreamQuality(1080, 50f), StreamQuality.fromName("Sky Sports | FHD 50 FPS"))
        assertEquals(StreamQuality(720, 0f), StreamQuality.fromName("CBS HD"))
        assertEquals(StreamQuality(1080, 0f), StreamQuality.fromName("CBS FHD HD"))
        assertEquals(StreamQuality.UNKNOWN, StreamQuality.fromName("NFL RedZone"))
    }

    @Test
    fun labels() {
        assertEquals("1080p · 60 fps", StreamQuality(1080, 60f).label)
        assertEquals("4K", StreamQuality(2160, 0f).label)
        assertEquals("59.94 fps", StreamQuality(0, 59.94f).label)
        assertEquals("", StreamQuality.UNKNOWN.label)
    }

    @Test
    fun bestFirstPrefersFrameRateThenResolution() {
        val sorted = listOf(
            StreamQuality(2160, 30f),
            StreamQuality(720, 60f),
            StreamQuality.UNKNOWN,
            StreamQuality(1080, 60f),
            StreamQuality(1080, 0f),
        ).sortedWith(StreamQuality.BEST_FIRST)
        assertEquals(
            listOf(StreamQuality(1080, 60f), StreamQuality(720, 60f), StreamQuality(2160, 30f), StreamQuality(1080, 0f), StreamQuality.UNKNOWN),
            sorted,
        )
    }

    @Test
    fun redZoneNames() {
        assertTrue(ChannelMatcher.isRedZone("US: NFL RedZone FHD"))
        assertTrue(ChannelMatcher.isRedZone("NFL RED ZONE"))
        assertFalse(ChannelMatcher.isRedZone("NFL Network"))
    }
}
