package com.clearcmos.reelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelPageParserTest {
    private val realPage = checkNotNull(javaClass.getResourceAsStream("/reel_page.html")).bufferedReader().use {
        it.readText()
    }

    @Test
    fun `parses the media embedded in a real logged out reel page`() {
        val media = ReelPageParser.parse(realPage, "CDUMkliABpa")
        assertEquals("CDUMkliABpa", media.shortcode)
        assertEquals("clippedinandfree", media.username)
        assertEquals(720, media.width)
        assertEquals(1280, media.height)
        assertTrue(media.videoUrl, media.videoUrl.startsWith("https://instagram.") && ".fbcdn.net/" in media.videoUrl)
    }

    @Test
    fun `falls back to the only media when the expected shortcode is absent`() {
        assertEquals("CDUMkliABpa", ReelPageParser.parse(realPage, "SOMETHINGELSE").shortcode)
    }

    @Test
    fun `carousel or photo media is rejected as not a video`() {
        val carousel = realPage.replace("\"media_type\":2", "\"media_type\":8")
        val error =
            assertThrows(ReelParseException.NotVideo::class.java) { ReelPageParser.parse(carousel, "CDUMkliABpa") }
        assertEquals(8, error.mediaType)
    }

    @Test
    fun `page without media json reports no media`() {
        val shell =
            page("""{"require":[["ScheduledServerJS","handle",null,[{"__bbox":{"define":[],"require":[]}}]]]}""")
        assertThrows(ReelParseException.NoMedia::class.java) { ReelPageParser.parse(shell, "X") }
    }

    @Test
    fun `login wall is called out in the error`() {
        val wall = "<html><head><link href=\"/accounts/login/\"></head><body></body></html>"
        val error = assertThrows(ReelParseException.NoMedia::class.java) { ReelPageParser.parse(wall, "X") }
        assertTrue(error.message, "login" in error.message.orEmpty())
    }

    @Test
    fun `highest resolution version wins and lower type breaks ties`() {
        val versions =
            """[{"type":103,"width":480,"height":854,"url":"https://cdn/low"},""" +
                """{"type":101,"width":1080,"height":1920,"url":"https://cdn/high"},""" +
                """{"type":102,"width":1080,"height":1920,"url":"https://cdn/high-dup"}]"""
        val media = ReelPageParser.parse(page(mediaJson("AAA", versions = versions)), "AAA")
        assertEquals("https://cdn/high", media.videoUrl)
        assertEquals(1080, media.width)
    }

    @Test
    fun `expected shortcode selects among several embedded media`() {
        val html =
            page(
                mediaJson("OTHER", versions = """[{"type":101,"url":"https://cdn/other"}]"""),
                mediaJson("WANT", versions = """[{"type":101,"url":"https://cdn/want"}]""")
            )
        assertEquals("https://cdn/want", ReelPageParser.parse(html, "WANT").videoUrl)
        assertEquals("https://cdn/other", ReelPageParser.parse(html, null).videoUrl)
    }

    @Test
    fun `webview outerHTML attribute form with empty value is parsed`() {
        // Regression guard: WebView serialises the boolean attribute as data-sjs="" while curl output has bare data-sjs.
        val html =
            "<html><head><script type=\"application/json\" data-content-len=\"5\" data-sjs=\"\">" +
                mediaJson("BBB", versions = """[{"type":101,"url":"https://cdn/b"}]""") +
                "</script></head></html>"
        assertEquals("https://cdn/b", ReelPageParser.parse(html, "BBB").videoUrl)
    }

    @Test
    fun `dash manifest in the page yields split renditions`() {
        val rep1080 =
            """<Representation bandwidth="2000000" mimeType="video/mp4" codecs="vp09" """ +
                """width="1080" height="1920" frameRate="15360/256">""" +
                """<BaseURL>https://cdn/v1080.mp4?a=1&amp;b=2</BaseURL></Representation>"""
        val rep720 =
            """<Representation bandwidth="900000" mimeType="video/mp4" codecs="vp09" width="720" height="1280">""" +
                """<BaseURL>https://cdn/v720.mp4</BaseURL></Representation>"""
        val repAudio =
            """<Representation bandwidth="54608" mimeType="audio/mp4" codecs="mp4a.40.5">""" +
                """<BaseURL>https://cdn/a.mp4</BaseURL></Representation>"""
        val mpd =
            """<?xml version="1.0"?><MPD><Period><AdaptationSet>$rep1080$rep720</AdaptationSet>""" +
                """<AdaptationSet>$repAudio</AdaptationSet></Period></MPD>"""
        val escaped = mpd.replace("\\", "\\\\").replace("\"", "\\\"")
        val json =
            """{"data":{"media":{"code":"DDD","pk":"1","media_type":2,""" +
                """"original_width":1080,"original_height":1920,""" +
                """"video_versions":[{"type":101,"url":"https://cdn/progressive.mp4"}],""" +
                """"video_dash_manifest":"$escaped","user":{"username":"u"}}}}"""
        val media = ReelPageParser.parse(page(json), "DDD")
        assertEquals("https://cdn/progressive.mp4", media.videoUrl)
        assertEquals("https://cdn/v1080.mp4?a=1&b=2", media.dashVideo?.url)
        assertEquals("https://cdn/a.mp4", media.dashAudio?.url)
        assertEquals(1080, media.width)
        assertTrue(media.hasDash)
    }

    @Test
    fun `page without a dash manifest still yields the progressive url`() {
        val media = ReelPageParser.parse(realPage, "CDUMkliABpa")
        assertNull(media.dashVideo)
        assertEquals(false, media.hasDash)
    }

    @Test
    fun `versions without a url are skipped and none left is no media`() {
        val html = page(mediaJson("CCC", versions = """[{"type":101},{"type":102,"url":""}]"""))
        assertThrows(ReelParseException.NoMedia::class.java) { ReelPageParser.parse(html, "CCC") }
    }

    private fun page(vararg blocks: String): String = blocks.joinToString("") {
        "<script type=\"application/json\" data-sjs>$it</script>"
    }.let { "<html><head>$it</head><body></body></html>" }

    private fun mediaJson(code: String, type: Int = 2, versions: String): String =
        """{"data":{"media":{"code":"$code","pk":"1","media_type":$type,""" +
            """"original_width":720,"original_height":1280,""" +
            """"video_versions":$versions,"user":{"username":"someone"}}}}"""
}
