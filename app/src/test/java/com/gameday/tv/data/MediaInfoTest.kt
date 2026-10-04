package com.gameday.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaInfoTest {

    @Test
    fun codecs() {
        assertEquals("H.264", MediaInfo.videoCodec("video/avc", "avc1.640028"))
        assertEquals("HEVC", MediaInfo.videoCodec(null, "hvc1.2.4.L153.B0"))
        assertEquals("AV1", MediaInfo.videoCodec("video/av01", null))
        assertEquals("MPEG-2", MediaInfo.videoCodec("video/mpeg2", null))
        assertEquals("AAC", MediaInfo.audioCodec("audio/mp4a-latm", "mp4a.40.2"))
        assertEquals("HE-AAC", MediaInfo.audioCodec(null, "mp4a.40.5"))
        assertEquals("Dolby Digital", MediaInfo.audioCodec("audio/ac3", null))
        assertEquals("Dolby Digital Plus", MediaInfo.audioCodec("audio/eac3", "ec-3"))
        assertEquals("MP2", MediaInfo.audioCodec("audio/mpeg-l2", null))
        assertNull(MediaInfo.videoCodec(null, null))
    }

    @Test
    fun numbers() {
        assertEquals("Stereo", MediaInfo.channels(2))
        assertEquals("5.1", MediaInfo.channels(6))
        assertNull(MediaInfo.channels(0))
        assertEquals("6.2 Mbps", MediaInfo.bitrate(6_200_000))
        assertEquals("128 kbps", MediaInfo.bitrate(128_000))
        assertEquals("30 fps", MediaInfo.frameRate(30f))
        assertEquals("59.94 fps", MediaInfo.frameRate(59.94f))
        assertNull(MediaInfo.frameRate(-1f))
        assertEquals("1080p", MediaInfo.quality(1920, 1080))
        assertEquals("1080p", MediaInfo.quality(1920, 800))
        assertEquals("4K", MediaInfo.quality(3840, 2160))
        assertEquals("480p", MediaInfo.quality(854, 480))
    }

    @Test
    fun containersAndDecoders() {
        assertEquals("HLS", MediaInfo.container("http://host/live/u/p/123.m3u8?token=1"))
        assertEquals("MPEG-TS", MediaInfo.container("http://host/live/u/p/123.ts"))
        assertEquals("MKV", MediaInfo.container("https://cdn/x/movie.mkv"))
        assertNull(MediaInfo.container("http://host/play/abc"))
        assertTrue(MediaInfo.isSoftwareDecoder("c2.android.avc.decoder"))
        assertFalse(MediaInfo.isSoftwareDecoder("c2.mtk.avc.decoder"))
        assertFalse(MediaInfo.isSoftwareDecoder("OMX.amlogic.avc.decoder.awesome"))
    }
}
