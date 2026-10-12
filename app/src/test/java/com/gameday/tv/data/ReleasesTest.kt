package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    @Test
    fun comparesVersions() {
        assertTrue(Releases.isNewer("2.7.0", "2.6"))
        assertTrue(Releases.isNewer("2.10", "2.9"))
        assertTrue(Releases.isNewer("3.0", "2.6.1"))
        assertFalse(Releases.isNewer("2.6.0", "2.6"))
        assertFalse(Releases.isNewer("2.5.1", "2.6"))
        assertFalse(Releases.isNewer("2.6-beta", "2.6"))
    }

    private val release = """
        {"tag_name": "v2.7.0", "html_url": "https://github.com/Yigder/gameday-tv/releases/tag/v2.7.0",
         "assets": [
           {"name": "GameDayTV.apk", "browser_download_url": "https://example.com/GameDayTV.apk"},
           {"name": "GameDay.apk", "browser_download_url": "https://example.com/GameDay.apk"}
         ]}
    """.trimIndent()

    @Test
    fun picksEachAppsApk() {
        assertEquals(AppUpdate("2.7.0", "https://example.com/GameDayTV.apk"), Releases.parse(release, "GameDayTV.apk"))
        assertEquals("https://example.com/GameDay.apk", Releases.parse(release, "GameDay.apk").url)
    }

    @Test
    fun fallsBackToTheReleasePage() {
        val u = Releases.parse(release, "Other.apk")
        assertFalse(u.isApk)
        assertEquals("https://github.com/Yigder/gameday-tv/releases/tag/v2.7.0", u.url)
    }
}
