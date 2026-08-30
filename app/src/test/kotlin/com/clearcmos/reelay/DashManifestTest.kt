package com.clearcmos.reelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashManifestTest {
    private val mpd = checkNotNull(javaClass.getResourceAsStream("/dash_manifest.mpd")).bufferedReader().use {
        it.readText()
    }

    @Test
    fun `picks the largest video rendition and unescapes its url`() {
        val best = checkNotNull(DashManifest.parse(mpd).bestVideo())
        assertEquals(1080, best.width)
        assertEquals(1920, best.height)
        assertEquals(2_935_254L, best.bandwidth)
        assertEquals(60.0, best.frameRate ?: 0.0, 0.001)
        assertTrue(best.codecs, best.codecs.startsWith("vp09"))
        assertTrue(best.url, best.url.startsWith("https://") && "&amp;" !in best.url && "&_nc_" in best.url)
    }

    @Test
    fun `picks the audio rendition`() {
        val audio = checkNotNull(DashManifest.parse(mpd).bestAudio())
        assertEquals("audio/mp4", audio.mimeType)
        assertEquals("mp4a.40.5", audio.codecs)
        assertTrue(audio.url.startsWith("https://"))
    }

    @Test
    fun `frame rate accepts fractions and plain numbers`() {
        assertEquals(60.0, DashManifest.parseFrameRate("15360/256") ?: 0.0, 0.001)
        assertEquals(29.97, DashManifest.parseFrameRate("29.97") ?: 0.0, 0.001)
        assertNull(DashManifest.parseFrameRate(""))
        assertNull(DashManifest.parseFrameRate("x/y"))
        assertNull(DashManifest.parseFrameRate("30/0"))
    }

    @Test
    fun `manifest without representations yields no renditions`() {
        val empty = DashManifest.parse("""<?xml version="1.0"?><MPD><Period><AdaptationSet/></Period></MPD>""")
        assertNull(empty.bestVideo())
        assertNull(empty.bestAudio())
    }
}
