package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingTest {

    private fun point(key: String, updatedAt: Long) = ResumePoint(key, "T", "S", null, 60_000, 600_000, updatedAt)

    @Test
    fun groupKeys() {
        assertEquals("addon:series:tt0944947", ContinueWatching.groupKey("addon:series:tt0944947|tt0944947:1:2"))
        assertEquals("addon:series:kitsu:42", ContinueWatching.groupKey("addon:series:kitsu:42|kitsu:42:7"))
        assertEquals("addon:movie:tt1375666", ContinueWatching.groupKey("addon:movie:tt1375666|tt1375666"))
        assertEquals("ep:p3k9~x1234", ContinueWatching.groupKey("ep:p3k9~x1234:5678:mkv"))
        assertEquals("movie:99:mp4", ContinueWatching.groupKey("movie:99:mp4"))
        assertEquals("rec:abc", ContinueWatching.groupKey("rec:abc"))
    }

    @Test
    fun oneCardPerShowWithTheLatestEpisode() {
        val list = listOf(
            point("addon:series:tt1|tt1:1:1", 100),
            point("movie:7:mp4", 250),
            point("addon:series:tt1|tt1:1:3", 300),
            point("ep:55:901:mkv", 150),
            point("addon:series:tt1|tt1:1:2", 200),
            point("ep:55:902:mkv", 175),
            point("addon:series:tt2|tt2:2:1", 50),
        )
        val shown = ContinueWatching.latestPerShow(list).map { it.key }
        assertEquals(
            listOf("addon:series:tt1|tt1:1:3", "movie:7:mp4", "ep:55:902:mkv", "addon:series:tt2|tt2:2:1"),
            shown,
        )
    }
}
