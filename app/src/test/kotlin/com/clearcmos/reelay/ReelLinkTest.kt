package com.clearcmos.reelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReelLinkTest {
    @Test
    fun `reel url with tracking query yields shortcode and canonical url`() {
        val link = ReelLink.parse("https://www.instagram.com/reel/DNabc123XyZ/?igsh=MzRlODBiNWFlZA==")
        assertEquals(ReelPlatform.INSTAGRAM, link?.platform)
        assertEquals("DNabc123XyZ", link?.id)
        assertEquals("https://www.instagram.com/reel/DNabc123XyZ/", link?.url)
    }

    @Test
    fun `url embedded in share text is found and trailing punctuation dropped`() {
        val link = ReelLink.parse("Look at this https://www.instagram.com/reel/C1a-b_2/?igsh=x. So good")
        assertEquals("C1a-b_2", link?.id)
    }

    @Test
    fun `reels path is normalised to reel`() {
        assertEquals(
            "https://www.instagram.com/reel/Cop84x6u7CP/",
            ReelLink.parse("https://www.instagram.com/reels/Cop84x6u7CP")?.url
        )
    }

    @Test
    fun `post and tv paths keep their kind`() {
        assertEquals(
            "https://www.instagram.com/p/BQ0eAlwhDrw/",
            ReelLink.parse("https://www.instagram.com/p/BQ0eAlwhDrw/")?.url
        )
        assertEquals(
            "https://www.instagram.com/tv/BkfuX9UB-eK/",
            ReelLink.parse("https://instagram.com/tv/BkfuX9UB-eK")?.url
        )
    }

    @Test
    fun `profile scoped reel path yields the shortcode`() {
        assertEquals(
            "CDUMkliABpa",
            ReelLink.parse("https://www.instagram.com/some.user_1/reel/CDUMkliABpa/")?.id
        )
    }

    @Test
    fun `share redirect links are kept without a shortcode`() {
        val reel = ReelLink.parse("https://www.instagram.com/share/reel/_abc-XYZ9/?igsh=1")
        assertNull(reel?.id)
        assertEquals("https://www.instagram.com/share/reel/_abc-XYZ9/", reel?.url)
        val bare = ReelLink.parse("https://www.instagram.com/share/BAxyz123")
        assertNull(bare?.id)
        assertEquals("https://www.instagram.com/share/BAxyz123/", bare?.url)
    }

    @Test
    fun `short domain and mobile host are accepted`() {
        assertEquals("BQ0eAlwhDrw", ReelLink.parse("https://instagr.am/p/BQ0eAlwhDrw/")?.id)
        assertEquals("CDUMkliABpa", ReelLink.parse("https://m.instagram.com/reel/CDUMkliABpa/")?.id)
    }

    @Test
    fun `non media instagram urls and other text are rejected`() {
        assertNull(ReelLink.parse("https://www.instagram.com/someuser/"))
        assertNull(ReelLink.parse("https://www.instagram.com/stories/someuser/123456/"))
        assertNull(ReelLink.parse("https://help.instagram.com/123"))
        assertNull(ReelLink.parse("https://www.tiktok.com/@x/video/1"))
        assertNull(ReelLink.parse("no link here"))
        assertNull(ReelLink.parse(""))
        assertNull(ReelLink.parse(null))
    }

    @Test
    fun `fromUrl resolves the final url of a share redirect`() {
        assertEquals(
            "DNabc123XyZ",
            ReelLink.fromUrl("https://www.instagram.com/reel/DNabc123XyZ/?utm_source=ig_web_copy_link")?.id
        )
        assertNull(ReelLink.fromUrl("https://www.instagram.com/accounts/login/?next=%2Freel%2FX%2F"))
        assertNull(ReelLink.fromUrl("not a url at all"))
    }

    @Test
    fun `facebook reel permalink yields the video id`() {
        val link = ReelLink.parse("https://www.facebook.com/reel/4028623617267790/?mibextid=abc")
        assertEquals(ReelPlatform.FACEBOOK, link?.platform)
        assertEquals("4028623617267790", link?.id)
        assertEquals("https://www.facebook.com/reel/4028623617267790/", link?.url)
    }

    @Test
    fun `facebook video permalink with a slug is normalised to the reel path`() {
        // This is the canonical form Facebook redirects a share link to.
        val link = ReelLink.fromUrl("https://www.facebook.com/somepage/videos/sharing-is-caring/4028623617267790/")
        assertEquals("4028623617267790", link?.id)
        assertEquals("https://www.facebook.com/reel/4028623617267790/", link?.url)
        assertEquals("55", ReelLink.fromUrl("https://www.facebook.com/videos/55")?.id)
    }

    @Test
    fun `facebook share and fb watch links are kept without an id`() {
        val share = ReelLink.parse("Check this out https://www.facebook.com/share/r/19LD8TJ5tr/")
        assertEquals(ReelPlatform.FACEBOOK, share?.platform)
        assertNull(share?.id)
        assertEquals("https://www.facebook.com/share/r/19LD8TJ5tr/", share?.url)
        val watch = ReelLink.parse("https://fb.watch/AbC-1_xyz/")
        assertEquals(ReelPlatform.FACEBOOK, watch?.platform)
        assertNull(watch?.id)
        assertEquals("https://fb.watch/AbC-1_xyz/", watch?.url)
    }

    @Test
    fun `mobile facebook hosts resolve to the www permalink`() {
        assertEquals(
            "https://www.facebook.com/reel/123456/",
            ReelLink.parse("https://m.facebook.com/reel/123456")?.url
        )
        assertEquals(
            "https://www.facebook.com/reel/123456/",
            ReelLink.parse("https://web.facebook.com/reel/123456/")?.url
        )
    }

    @Test
    fun `non media facebook urls are rejected`() {
        assertNull(ReelLink.parse("https://www.facebook.com/somepage/"))
        assertNull(ReelLink.parse("https://www.facebook.com/share/p/19LD8TJ5tr/"))
        assertNull(ReelLink.parse("https://www.facebook.com/reel/not-a-number/"))
        assertNull(ReelLink.parse("https://www.facebook.com/groups/123456/"))
        assertNull(ReelLink.parse("https://developers.facebook.com/docs/"))
    }
}
