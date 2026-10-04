package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    @Test
    fun matchesLanguages() {
        assertTrue(Subtitles.sameLanguage("eng", "en"))
        assertTrue(Subtitles.sameLanguage("fre", "fr"))
        assertTrue(Subtitles.sameLanguage("pob", "pt"))
        assertTrue(Subtitles.sameLanguage("English", "en"))
        assertFalse(Subtitles.sameLanguage("spa", "en"))
        assertEquals("English", Subtitles.languageName("eng"))
        assertEquals("Portuguese (Brazil)", Subtitles.languageName("pob"))
    }

    @Test
    fun styleRoundTrips() {
        val s = SubtitleStyle(size = 3, color = 1, background = 2, edge = 1, position = 2, font = 3, bold = true)
        assertEquals(s, SubtitleStyle.decode(s.encode()))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("garbage"))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("99,99"))
    }
}
