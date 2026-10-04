package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonsTest {
    private val cinemeta = """
        {"id":"com.linvo.cinemeta","version":"3.0.13","name":"Cinemeta","types":["movie","series"],
         "resources":["catalog",{"name":"meta","types":["movie","series"],"idPrefixes":["tt"]}],
         "catalogs":[
           {"type":"movie","id":"top","name":"Popular","extra":[{"name":"genre","options":["Action","Drama"]},{"name":"search"},{"name":"skip"}]},
           {"type":"movie","id":"year","name":"New","extra":[{"name":"genre","isRequired":true,"options":["2026"]}]},
           {"type":"series","id":"top","name":"Popular","extraSupported":["search","genre","skip"]}
         ]}
    """.trimIndent()

    @Test
    fun parsesManifestResourcesAndCatalogs() {
        val m = Addons.parseManifest(cinemeta)!!
        assertEquals("Cinemeta", m.name)
        assertEquals(3, m.catalogs.size)
        assertTrue(m.supports("meta", "movie", "tt0111161"))
        assertFalse(m.supports("meta", "movie", "kitsu:1"))
        assertFalse(m.supports("stream", "movie", "tt0111161"))
        assertTrue(m.catalogs[1].needsExtra)
        assertFalse(m.catalogs[0].needsExtra)
        assertTrue(m.catalogs[2].searchable)
        assertEquals(listOf("Action", "Drama"), m.catalogs[0].genres)
    }

    @Test
    fun streamResourceWithManifestLevelPrefixes() {
        val m = Addons.parseManifest(
            """{"id":"comet","name":"Comet","types":["movie","series"],"idPrefixes":["tt","kitsu"],"resources":["stream"],"catalogs":[]}""",
        )!!
        assertTrue(m.supports("stream", "series", "tt0944947:1:2"))
        assertTrue(m.supports("stream", "series", "kitsu:12:3"))
        assertFalse(m.supports("stream", "series", "tmdb:1"))
        assertFalse(m.supports("stream", "channel", "tt1"))
    }

    @Test
    fun configurationRequiredIsDetected() {
        val m = Addons.parseManifest("""{"id":"x","name":"X","resources":["stream"],"types":["movie"],"behaviorHints":{"configurable":true,"configurationRequired":true}}""")!!
        assertTrue(m.configurationRequired)
    }

    @Test
    fun normalizesAddonLinks() {
        assertEquals("https://comet.example/abc/manifest.json", Addons.normalizeUrl("stremio://comet.example/abc/manifest.json"))
        assertEquals("https://host/x/manifest.json", Addons.normalizeUrl("https://host/x/configure"))
        assertEquals("https://host/x/manifest.json", Addons.normalizeUrl("host/x/"))
        assertEquals("https://host/x", Addons.baseOf("https://host/x/manifest.json"))
    }

    @Test
    fun encodesIdsLikeStremio() {
        assertEquals("tt0944947%3A1%3A2", Addons.encode("tt0944947:1:2"))
        assertEquals("genre%3DSci-Fi", Addons.encode("genre=Sci-Fi"))
        assertEquals("a%20b", Addons.encode("a b"))
    }

    @Test
    fun catalogUrlWithExtras() {
        val m = Addons.parseManifest(cinemeta)!!
        val a = InstalledAddon("https://v3-cinemeta.strem.io/manifest.json", m, cinemeta)
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/top.json", Addons.catalogUrl(a, m.catalogs[0]))
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Action&skip=100.json",
            Addons.catalogUrl(a, m.catalogs[0], linkedMapOf("genre" to "Action", "skip" to "100")),
        )
    }

    @Test
    fun parsesMetaWithEpisodesInOrder() {
        val body = """{"meta":{"id":"tt1","type":"series","name":"Show","poster":"https://p/x.jpg","releaseInfo":"2020–",
            "videos":[{"id":"tt1:2:1","name":"S2E1","season":2,"episode":1},{"id":"tt1:1:2","name":"Two","season":1,"episode":2,"released":"2020-01-08T00:00:00.000Z"},
            {"id":"tt1:1:1","title":"One","season":1,"episode":1},{"id":"tt1:0:1","name":"Special","season":0,"episode":1}]}}"""
        val m = Addons.parseMeta(body, "series")!!
        assertEquals(listOf("tt1:1:1", "tt1:1:2", "tt1:2:1", "tt1:0:1"), m.videos.map { it.id })
        assertEquals(listOf(1, 2, 0), m.seasons)
        assertEquals("One", m.videos[0].title)
        assertNotNull(m.videos[1].released)
    }

    @Test
    fun parsesStreamsAndSortsBestFirst() {
        val body = """{"streams":[
            {"name":"Torrentio\n720p","title":"Show.S01E02.720p\n👤 5 💾 900 MB","infoHash":"ABCDEF0123456789ABCDEF0123456789ABCDEF01","fileIdx":1,"sources":["tracker:udp://t.example:80","dht:x"]},
            {"name":"Comet 4K","description":"Show.S01E02.2160p","url":"https://comet.example/play/1","behaviorHints":{"bingeGroup":"comet|4k","proxyHeaders":{"request":{"User-Agent":"X"}}}},
            {"name":"Torrentio\n1080p","title":"Show.S01E02.1080p 💾 2.1 GB","infoHash":"1111111111111111111111111111111111111111"},
            {"name":"YouTube","ytId":"abc"}
        ]}"""
        val s = Addons.parseStreams(body, "Mixed")
        assertEquals(3, s.size) // the YouTube-only entry can't be played
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", s[0].infoHash)
        assertEquals(listOf("udp://t.example:80"), s[0].trackers)
        assertEquals("720p", s[0].quality)
        assertEquals("4K", s[1].quality)
        assertEquals(mapOf("User-Agent" to "X"), s[1].headers)
        assertEquals(2_100_000_000L, s[2].sizeBytes)

        // Links first, then torrents TorBox has, then the rest; better quality first within a group.
        val sorted = Addons.sortStreams(s, cached = setOf("1111111111111111111111111111111111111111"))
        assertEquals(listOf("4K", "1080p", "720p"), sorted.map { it.quality })
    }

    @Test
    fun resumeKeysRoundTrip() {
        val k = com.gameday.tv.ui.AddonsModel.resumeKey("series", "tt1", "tt1:1:2")
        assertEquals(Triple("series", "tt1", "tt1:1:2"), com.gameday.tv.ui.AddonsModel.parseResumeKey(k))
        assertNull(com.gameday.tv.ui.AddonsModel.parseResumeKey("movie:1:mp4"))
    }
}
