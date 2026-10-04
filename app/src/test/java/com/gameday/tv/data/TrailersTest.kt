package com.gameday.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailersTest {
    @Test
    fun trailerIdsFromCatalogAndMeta() {
        val o = JSONObject(
            """{"id":"tt1","name":"X",
               "trailers":[{"source":"teaser00001","type":"Teaser"},{"source":"trailer0001","type":"Trailer"},{"source":"bad id"}],
               "trailerStreams":[{"title":"Official Trailer","ytId":"trailer0001"},{"title":"Trailer 2","ytId":"trailer0002"}]}""",
        )
        // Real trailers first, duplicates and malformed ids dropped.
        assertEquals(listOf("trailer0001", "trailer0002", "teaser00001"), Addons.parseTrailers(o))
        assertEquals(emptyList<String>(), Addons.parseTrailers(JSONObject("""{"id":"tt2"}""")))
    }

    @Test
    fun previewsAndMetaCarryTrailers() {
        val metas = Addons.parseMetas("""{"metas":[{"id":"tt1","type":"movie","name":"A","trailers":[{"source":"abcdefghijk","type":"Trailer"}]}]}""", "movie")
        assertEquals(listOf("abcdefghijk"), metas.single().trailers)
        val meta = Addons.parseMeta("""{"meta":{"id":"tt1","type":"movie","name":"A","trailerStreams":[{"ytId":"abcdefghijk"}]}}""", "movie")!!
        assertEquals(listOf("abcdefghijk"), meta.preview.trailers)
    }

    @Test
    fun prefersHlsPlaylist() {
        val body = """{"playabilityStatus":{"status":"OK"},"streamingData":{
            "hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/x",
            "formats":[{"itag":18,"mimeType":"video/mp4; codecs=\"avc1\"","height":360,"url":"https://rr.googlevideo.com/18"}]}}"""
        val s = Trailers.parsePlayer(body, "UA")!!
        assertTrue(s.hls)
        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_variant/x", s.url)
        assertEquals("UA", s.userAgent)
    }

    @Test
    fun fallsBackToBestMp4WithSound() {
        val body = """{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[
            {"itag":18,"mimeType":"video/mp4; codecs=\"avc1, mp4a\"","height":360,"url":"https://rr.googlevideo.com/18"},
            {"itag":22,"mimeType":"video/mp4; codecs=\"avc1, mp4a\"","height":720,"url":"https://rr.googlevideo.com/22"},
            {"itag":43,"mimeType":"video/webm","height":1080,"url":"https://rr.googlevideo.com/43"},
            {"itag":99,"mimeType":"video/mp4","height":720,"signatureCipher":"s=abc"}]}}"""
        val s = Trailers.parsePlayer(body, "UA")!!
        assertFalse(s.hls)
        assertEquals("https://rr.googlevideo.com/22", s.url)
    }

    @Test
    fun unplayableGivesNothing() {
        assertNull(Trailers.parsePlayer("""{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""", "UA"))
        assertNull(Trailers.parsePlayer("""{"playabilityStatus":{"status":"OK"}}""", "UA"))
        assertNull(Trailers.parsePlayer("not json", "UA"))
        assertEquals("LOGIN_REQUIRED", Trailers.playabilityStatus("""{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""))
    }
}
