package com.clearcmos.reelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramLinkTest {
    @Test
    fun `reel url with tracking query yields shortcode and canonical url`() {
        val link = InstagramLink.parse("https://www.instagram.com/reel/DNabc123XyZ/?igsh=MzRlODBiNWFlZA==")
        assertEquals("DNabc123XyZ", link?.shortcode)
        assertEquals("https://www.instagram.com/reel/DNabc123XyZ/", link?.url)
    }

    @Test
    fun `url embedded in share text is found and trailing punctuation dropped`() {
        val link = InstagramLink.parse("Look at this https://www.instagram.com/reel/C1a-b_2/?igsh=x. So good")
        assertEquals("C1a-b_2", link?.shortcode)
    }

    @Test
    fun `reels path is normalised to reel`() {
        assertEquals(
            "https://www.instagram.com/reel/Cop84x6u7CP/",
            InstagramLink.parse("https://www.instagram.com/reels/Cop84x6u7CP")?.url
        )
    }

    @Test
    fun `post and tv paths keep their kind`() {
        assertEquals(
            "https://www.instagram.com/p/BQ0eAlwhDrw/",
            InstagramLink.parse("https://www.instagram.com/p/BQ0eAlwhDrw/")?.url
        )
        assertEquals(
            "https://www.instagram.com/tv/BkfuX9UB-eK/",
            InstagramLink.parse("https://instagram.com/tv/BkfuX9UB-eK")?.url
        )
    }

    @Test
    fun `profile scoped reel path yields the shortcode`() {
        assertEquals(
            "CDUMkliABpa",
            InstagramLink.parse("https://www.instagram.com/some.user_1/reel/CDUMkliABpa/")?.shortcode
        )
    }

    @Test
    fun `share redirect links are kept without a shortcode`() {
        val reel = InstagramLink.parse("https://www.instagram.com/share/reel/_abc-XYZ9/?igsh=1")
        assertNull(reel?.shortcode)
        assertEquals("https://www.instagram.com/share/reel/_abc-XYZ9/", reel?.url)
        val bare = InstagramLink.parse("https://www.instagram.com/share/BAxyz123")
        assertNull(bare?.shortcode)
        assertEquals("https://www.instagram.com/share/BAxyz123/", bare?.url)
    }

    @Test
    fun `short domain and mobile host are accepted`() {
        assertEquals("BQ0eAlwhDrw", InstagramLink.parse("https://instagr.am/p/BQ0eAlwhDrw/")?.shortcode)
        assertEquals("CDUMkliABpa", InstagramLink.parse("https://m.instagram.com/reel/CDUMkliABpa/")?.shortcode)
    }

    @Test
    fun `non media instagram urls and other text are rejected`() {
        assertNull(InstagramLink.parse("https://www.instagram.com/someuser/"))
        assertNull(InstagramLink.parse("https://www.instagram.com/stories/someuser/123456/"))
        assertNull(InstagramLink.parse("https://help.instagram.com/123"))
        assertNull(InstagramLink.parse("https://www.tiktok.com/@x/video/1"))
        assertNull(InstagramLink.parse("no link here"))
        assertNull(InstagramLink.parse(""))
        assertNull(InstagramLink.parse(null))
    }

    @Test
    fun `fromUrl resolves the final url of a share redirect`() {
        assertEquals(
            "DNabc123XyZ",
            InstagramLink.fromUrl("https://www.instagram.com/reel/DNabc123XyZ/?utm_source=ig_web_copy_link")?.shortcode
        )
        assertNull(InstagramLink.fromUrl("https://www.instagram.com/accounts/login/?next=%2Freel%2FX%2F"))
        assertNull(InstagramLink.fromUrl("not a url at all"))
    }
}
