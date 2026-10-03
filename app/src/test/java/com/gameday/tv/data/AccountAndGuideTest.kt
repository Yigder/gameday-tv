package com.gameday.tv.data

import com.gameday.tv.dvr.StreamRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PasswordHasherTest {
    @Test
    fun verifiesOnlyTheRightPassword() {
        val stored = PasswordHasher.hash("correct horse", iterations = 1_000)
        assertTrue(PasswordHasher.verify("correct horse", stored))
        assertFalse(PasswordHasher.verify("correct hors", stored))
        assertFalse(PasswordHasher.verify("", stored))
    }

    @Test
    fun saltsEveryHash() {
        assertNotEquals(PasswordHasher.hash("same", 1_000), PasswordHasher.hash("same", 1_000))
    }

    @Test
    fun rejectsMalformedHashes() {
        assertFalse(PasswordHasher.verify("x", "not-a-hash"))
        assertFalse(PasswordHasher.verify("x", "pbkdf2-sha256\$abc\$\$"))
    }
}

class XtreamEpgTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun parsesShortEpgWithBase64Titles() {
        val body = """
            {"epg_listings":[
              {"title":"${b64("NFL Football: Chiefs at Bills")}","description":"${b64("Week 5.")}",
               "start_timestamp":"1790000000","stop_timestamp":"1790010800","has_archive":1},
              {"title":"${b64("SportsCenter")}","description":"","start_timestamp":"1789996400","stop_timestamp":"1790000000"}
            ]}
        """.trimIndent()
        val programs = XtreamSource.parseEpgListings(body, "x1")
        assertEquals(2, programs.size)
        assertEquals("SportsCenter", programs[0].title) // sorted by start
        assertEquals("NFL Football: Chiefs at Bills", programs[1].title)
        assertEquals("Week 5.", programs[1].description)
        assertTrue(programs[1].hasArchive)
        assertEquals(1_790_000_000_000L, programs[1].startMillis)
    }

    @Test
    fun keepsPlainTitles() {
        assertEquals("Live: Game", XtreamSource.decodeMaybeBase64("Live: Game"))
        assertEquals("NBA", XtreamSource.decodeMaybeBase64("NBA")) // not valid base64 length
        assertEquals("", XtreamSource.decodeMaybeBase64(null))
    }

    @Test
    fun ignoresBrokenEntries() {
        assertTrue(XtreamSource.parseEpgListings("not json", "x").isEmpty())
        val body = """{"epg_listings":[{"title":"A","start_timestamp":"20","stop_timestamp":"10"}]}"""
        assertTrue(XtreamSource.parseEpgListings(body, "x").isEmpty())
    }
}

class XmltvTimeTest {
    @Test
    fun parsesOffsets() {
        assertEquals(1_790_000_000_000L, Xmltv.parseTime("20260921141320 +0000"))
        assertEquals(1_790_000_000_000L, Xmltv.parseTime("20260921101320 -0400"))
        assertEquals(1_790_000_000_000L, Xmltv.parseTime("20260921141320"))
        assertNull(Xmltv.parseTime("garbage"))
        assertNull(Xmltv.parseTime(null))
    }
}

class HlsPlaylistTest {
    @Test
    fun picksVariantsAndResolvesUrls() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            http://cdn.example/hi/index.m3u8
        """.trimIndent()
        val variants = StreamRecorder.parseMaster("http://host.example/live/ch/master.m3u8", master)
        assertEquals(2, variants.size)
        assertEquals("http://host.example/live/ch/low/index.m3u8", variants[0].url)
        assertEquals("http://cdn.example/hi/index.m3u8", variants.maxBy { it.bandwidth }.url)
    }

    @Test
    fun numbersSegmentsFromMediaSequence() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:120
            #EXTINF:4.0,
            seg120.ts
            #EXTINF:4.0,
            /abs/seg121.ts
        """.trimIndent()
        val pl = StreamRecorder.parseMedia("http://h.example/a/b/index.m3u8", media)
        assertEquals(4, pl.targetDurationSec)
        assertEquals(listOf(120L to "http://h.example/a/b/seg120.ts", 121L to "http://h.example/abs/seg121.ts"), pl.segments)
        assertFalse(pl.encrypted)
        assertFalse(pl.endList)
    }

    @Test
    fun detectsEncryptionAndEnd() {
        val media = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST\n"
        val pl = StreamRecorder.parseMedia("http://h/x.m3u8", media)
        assertTrue(pl.encrypted)
        assertTrue(pl.endList)
    }
}
