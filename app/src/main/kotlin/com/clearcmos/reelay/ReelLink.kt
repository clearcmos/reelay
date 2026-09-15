package com.clearcmos.reelay

import java.net.URI
import java.net.URISyntaxException

/** Which service a link points at. Each one needs its own page parser. */
enum class ReelPlatform { INSTAGRAM, FACEBOOK }

/**
 * A reel link found in shared text.
 *
 * Instagram's "Share to" sends `text/plain` carrying the reel URL, normally with an
 * `igsh` tracking query; it sometimes sends a `/share/...` redirect link instead.
 * Facebook's share sheet copies a `/share/r/<token>` link, and its reel permalinks are
 * `/reel/<video id>` or `/<page>/videos/<slug>/<video id>`. A redirect link only names
 * the media after it resolves, so those are returned with a null [id] and identified
 * from the final page URL.
 */
data class ReelLink(
    val platform: ReelPlatform,
    /** https URL with the tracking query removed, always ending in a slash. */
    val url: String,
    /** Instagram shortcode or Facebook video id when the path names one; null for redirect links. */
    val id: String?
) {
    companion object {
        private val URL_IN_TEXT =
            Regex(
                """https?://(?:[a-z0-9-]+\.)*""" +
                    """(?:instagram\.com|instagr\.am|facebook\.com|fb\.watch)/[^\s<>"']*""",
                RegexOption.IGNORE_CASE
            )
        private val INSTAGRAM_MEDIA_PATH = Regex("""^/(?:[A-Za-z0-9_.]+/)?(reel|reels|p|tv)/([A-Za-z0-9_-]+)/?$""")
        private val INSTAGRAM_SHARE_PATH = Regex("""^/share/(?:reel/|reels/|p/)?[A-Za-z0-9_-]+/?$""")

        // Facebook numbers its videos, so a reel id is always digits; the slug segment in a
        // permalink is decorative and dropped.
        private val FACEBOOK_REEL_PATH = Regex("""^/reels?/(\d+)/?$""")
        private val FACEBOOK_VIDEO_PATH = Regex("""^/(?:[^/]+/)?videos/(?:[^/]+/)?(\d+)/?$""")
        private val FACEBOOK_SHARE_PATH = Regex("""^/share/[rv]/[A-Za-z0-9_-]+/?$""")
        private val FB_WATCH_PATH = Regex("""^/[A-Za-z0-9_-]+/?$""")

        private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '>', '\'', '"')

        /** Returns the first Instagram or Facebook reel link in [text], or null when there is none. */
        fun parse(text: String?): ReelLink? {
            if (text.isNullOrBlank()) return null
            for (match in URL_IN_TEXT.findAll(text)) {
                fromUrl(match.value.trimEnd(*TRAILING_PUNCTUATION))?.let { return it }
            }
            return null
        }

        /** Classifies one URL; null when it is not a media or redirect link on either service. */
        fun fromUrl(raw: String): ReelLink? {
            val uri =
                try {
                    URI(raw)
                } catch (e: URISyntaxException) {
                    return null
                }
            val host = uri.host?.lowercase() ?: return null
            val path = uri.path ?: return null
            return when {
                host.isOn("instagram.com") || host.isOn("instagr.am") -> instagram(path)
                host.isOn("fb.watch") -> facebookWatch(path)
                host.isOn("facebook.com") -> facebook(path)
                else -> null
            }
        }

        private fun instagram(path: String): ReelLink? {
            // Share links go first: /share/reel/<token> would otherwise match the media path with
            // "share" taken as the username segment and the token mistaken for a shortcode.
            if (INSTAGRAM_SHARE_PATH.matches(path)) {
                return ReelLink(ReelPlatform.INSTAGRAM, "https://www.instagram.com${path.trimEnd('/')}/", null)
            }
            val match = INSTAGRAM_MEDIA_PATH.find(path) ?: return null
            val kind = if (match.groupValues[1] == "reels") "reel" else match.groupValues[1]
            val code = match.groupValues[2]
            return ReelLink(ReelPlatform.INSTAGRAM, "https://www.instagram.com/$kind/$code/", code)
        }

        private fun facebook(path: String): ReelLink? {
            if (FACEBOOK_SHARE_PATH.matches(path)) {
                return ReelLink(ReelPlatform.FACEBOOK, "https://www.facebook.com${path.trimEnd('/')}/", null)
            }
            val id =
                FACEBOOK_REEL_PATH.find(path)?.groupValues?.get(1)
                    ?: FACEBOOK_VIDEO_PATH.find(path)?.groupValues?.get(1)
                    ?: return null
            // /reel/<id>/ is the permalink Facebook itself reports for a reel, whatever form the
            // shared link took; m., web., and mbasic. hosts all serve it.
            return ReelLink(ReelPlatform.FACEBOOK, "https://www.facebook.com/reel/$id/", id)
        }

        private fun facebookWatch(path: String): ReelLink? {
            if (!FB_WATCH_PATH.matches(path)) return null
            return ReelLink(ReelPlatform.FACEBOOK, "https://fb.watch${path.trimEnd('/')}/", null)
        }

        private fun String.isOn(domain: String) = this == domain || endsWith(".$domain")
    }
}
