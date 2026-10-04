package com.gameday.tv.data

import com.gameday.tv.ui.rankResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    @Test
    fun parsesSubRip() {
        val srt = "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i> there\r\nsecond line\r\n\r\n2\r\n00:01:02,000 --> 00:01:04,250\r\nTom &amp; Jerry\r\n"
        val cues = Subtitles.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        assertEquals("Hello there\nsecond line", cues[0].text)
        assertEquals(62_000L, cues[1].startMs)
        assertEquals("Tom & Jerry", cues[1].text)
    }

    @Test
    fun parsesWebVttWithoutHours() {
        val vtt = "WEBVTT\n\nNOTE comment\n\n00:05.000 --> 00:07.500 line:80%\n{\\an8}Up top\n"
        val cues = Subtitles.parse(vtt)
        assertEquals(1, cues.size)
        assertEquals(5000L, cues[0].startMs)
        assertEquals(7500L, cues[0].endMs)
        assertEquals("Up top", cues[0].text)
    }

    @Test
    fun parsesAss() {
        val ass = """
            [Script Info]
            Title: x

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.00,0:00:04.50,Default,,0,0,0,,{\i1}One, two\Nthree
        """.trimIndent()
        val cues = Subtitles.parse(ass)
        assertEquals(1, cues.size)
        assertEquals(2000L, cues[0].startMs)
        assertEquals(4500L, cues[0].endMs)
        assertEquals("One, two\nthree", cues[0].text)
    }

    @Test
    fun decodesLatin1AndGzip() {
        val latin = "café".toByteArray(charset("windows-1252"))
        assertEquals("café", Subtitles.decodeBytes(latin))
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write("1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray()) }
        assertEquals(1, Subtitles.parse(Subtitles.decodeBytes(bos.toByteArray())).size)
    }

    @Test
    fun findsActiveCues() {
        val cues = listOf(SubtitleCue(0, 1000, "a"), SubtitleCue(2000, 4000, "b"), SubtitleCue(3000, 3500, "c"), SubtitleCue(5000, 6000, "d"))
        assertEquals(listOf("a"), Subtitles.activeAt(cues, 500).map { it.text })
        assertTrue(Subtitles.activeAt(cues, 1500).isEmpty())
        assertEquals(listOf("b", "c"), Subtitles.activeAt(cues, 3200).map { it.text })
        assertTrue(Subtitles.activeAt(cues, 9000).isEmpty())
    }

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
    fun parsesAddonListsAndSorts() {
        val body = """{"subtitles":[{"id":"1","url":"https://s/1.srt","lang":"spa"},{"id":"2","url":"https://s/2.srt","lang":"eng"},{"id":"3","url":"ftp://bad","lang":"eng"}]}"""
        val list = Subtitles.parseList(body, "OpenSubtitles")
        assertEquals(2, list.size)
        assertEquals("eng", Subtitles.sortTracks(list, "en").first().lang)
        val stream = Addons.parseStreams("""{"streams":[{"url":"https://v/a.mp4","subtitles":[{"id":"x","url":"https://s/x.vtt","lang":"en"}]}]}""", "A")
        assertEquals(1, stream.first().subtitles.size)
    }

    @Test
    fun styleRoundTrips() {
        val s = SubtitleStyle(size = 3, color = 1, background = 2, edge = 1, position = 2, font = 3, bold = true)
        assertEquals(s, SubtitleStyle.decode(s.encode()))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("garbage"))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("99,99"))
    }

    @Test
    fun ranksSearchResults() {
        fun m(name: String) = MetaPreview(name, "movie", name, null, null, null, null, null, null, emptyList())
        val ranked = rankResults(listOf(m("The Dark Knight"), m("Dark"), m("Darkman"), m("Into the Darkness")), "dark")
        assertEquals(listOf("Dark", "Darkman", "The Dark Knight", "Into the Darkness"), ranked.map { it.name })
    }
}
