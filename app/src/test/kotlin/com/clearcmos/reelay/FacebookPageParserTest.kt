package com.clearcmos.reelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookPageParserTest {
    private val realPage = checkNotNull(
        javaClass.getResourceAsStream("/facebook_reel_page.html")
    ).bufferedReader().use {
        it.readText()
    }

    @Test
    fun `parses the media embedded in a real logged out reel page`() {
        val media = FacebookPageParser.parse(realPage, "4028623617267790")
        assertEquals("4028623617267790", media.id)
        assertEquals(1080, media.width)
        assertEquals(1920, media.height)
        assertTrue(media.videoUrl, ".fbcdn.net/" in media.videoUrl && "progressive_h264" in media.videoUrl)
        assertTrue(media.hasDash)
    }

    @Test
    fun `the reel the page is about wins over the up next feed`() {
        // The same block carries the logged-out feed's reels; the expected id picks the shared one.
        assertEquals("1268443268066222", FacebookPageParser.parse(realPage, "1268443268066222").id)
        // A share link resolves to no id at all, and then the page's own reel comes first.
        assertEquals("4028623617267790", FacebookPageParser.parse(realPage, null).id)
        assertEquals("4028623617267790", FacebookPageParser.parse(realPage, "999").id)
    }

    @Test
    fun `the dash renditions beat the progressive file`() {
        val media = FacebookPageParser.parse(realPage, "4028623617267790")
        val video = checkNotNull(media.dashVideo)
        assertEquals(1080, video.width)
        assertEquals(1920, video.height)
        assertTrue(video.codecs, video.codecs.startsWith("av01"))
        assertEquals("mp4a.40.5", checkNotNull(media.dashAudio).codecs)
    }

    @Test
    fun `the hd progressive file is preferred over the sd one`() {
        val html = page(videoJson("11", hd = "https://cdn/hd.mp4", sd = "https://cdn/sd.mp4"))
        assertEquals("https://cdn/hd.mp4", FacebookPageParser.parse(html, "11").videoUrl)
        val sdOnly = page(videoJson("11", hd = null, sd = "https://cdn/sd.mp4"))
        assertEquals("https://cdn/sd.mp4", FacebookPageParser.parse(sdOnly, "11").videoUrl)
    }

    @Test
    fun `a video object with no progressive url is not a candidate`() {
        val html = page(videoJson("11", hd = null, sd = null))
        assertThrows(ReelParseException.NoMedia::class.java) { FacebookPageParser.parse(html, "11") }
    }

    @Test
    fun `a blank progressive url reports no downloadable version`() {
        val html = page(videoJson("11", hd = "", sd = ""))
        val error = assertThrows(ReelParseException.NoMedia::class.java) { FacebookPageParser.parse(html, "11") }
        assertTrue(error.message, "downloadable" in error.message.orEmpty())
    }

    @Test
    fun `page without media json reports no media`() {
        val shell =
            page("""{"require":[["ScheduledServerJS","handle",null,[{"__bbox":{"define":[],"require":[]}}]]]}""")
        val error = assertThrows(ReelParseException.NoMedia::class.java) { FacebookPageParser.parse(shell, "11") }
        assertTrue(error.message, "Facebook" in error.message.orEmpty())
    }

    @Test
    fun `login wall is called out in the error`() {
        val wall = "<html><head><meta content=\"/login/?next=%2Freel%2F1%2F\"></head><body></body></html>"
        val error = assertThrows(ReelParseException.NoMedia::class.java) { FacebookPageParser.parse(wall, "11") }
        assertTrue(error.message, "login" in error.message.orEmpty())
    }

    @Test
    fun `an unparseable manifest leaves the progressive file usable`() {
        val html = page(videoJson("11", hd = "https://cdn/hd.mp4", sd = null, dash = "not xml"))
        val media = FacebookPageParser.parse(html, "11")
        assertEquals("https://cdn/hd.mp4", media.videoUrl)
        assertNull(media.dashVideo)
        assertEquals(false, media.hasDash)
    }

    private fun page(vararg blocks: String): String = blocks.joinToString("") {
        "<script type=\"application/json\" data-sjs=\"\" data-processed=\"1\">$it</script>"
    }.let { "<html><head>$it</head><body></body></html>" }

    private fun videoJson(id: String, hd: String?, sd: String?, dash: String? = null): String {
        val urls =
            listOfNotNull(
                hd?.let { """"browser_native_hd_url":"$it"""" },
                sd?.let { """"browser_native_sd_url":"$it"""" },
                dash?.let { """"dash_manifest_xml_string":"$it"""" }
            ).joinToString(",")
        return """{"data":{"video":{"creation_story":{"short_form_video_context":{"playback_video":""" +
            """{"id":"$id","width":1080,"height":1920,""" +
            """"videoDeliveryLegacyFields":{"id":"$id"${if (urls.isEmpty()) "" else ",$urls"}}}}}}}}"""
    }
}
