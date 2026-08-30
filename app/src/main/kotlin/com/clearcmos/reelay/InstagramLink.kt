package com.clearcmos.reelay

import java.net.URI
import java.net.URISyntaxException

/**
 * An Instagram media link found in shared text.
 *
 * Instagram's "Share to" sends `text/plain` carrying the reel URL, normally with an
 * `igsh` tracking query. It sometimes sends a `/share/...` redirect link instead,
 * which only names the shortcode after the redirect; those are returned with a null
 * [shortcode] and resolved from the final page URL.
 */
data class InstagramLink(
    /** https URL with the tracking query removed, always ending in a slash. */
    val url: String,
    /** Media shortcode when the path names one directly; null for /share/ links. */
    val shortcode: String?
) {
    companion object {
        private val URL_IN_TEXT =
            Regex(
                """https?://(?:[a-z0-9-]+\.)*(?:instagram\.com|instagr\.am)/[^\s<>"']*""",
                RegexOption.IGNORE_CASE
            )
        private val MEDIA_PATH = Regex("""^/(?:[A-Za-z0-9_.]+/)?(reel|reels|p|tv)/([A-Za-z0-9_-]+)/?$""")
        private val SHARE_PATH = Regex("""^/share/(?:reel/|reels/|p/)?[A-Za-z0-9_-]+/?$""")
        private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '>', '\'', '"')

        /** Returns the first Instagram media link in [text], or null when there is none. */
        fun parse(text: String?): InstagramLink? {
            if (text.isNullOrBlank()) return null
            for (match in URL_IN_TEXT.findAll(text)) {
                fromUrl(match.value.trimEnd(*TRAILING_PUNCTUATION))?.let { return it }
            }
            return null
        }

        /** Classifies one URL; null when it is not a media or share link. */
        fun fromUrl(raw: String): InstagramLink? {
            val path =
                try {
                    URI(raw).path ?: return null
                } catch (e: URISyntaxException) {
                    return null
                }
            // Share links go first: /share/reel/<token> would otherwise match MEDIA_PATH with
            // "share" taken as the username segment and the token mistaken for a shortcode.
            if (SHARE_PATH.matches(path)) {
                return InstagramLink("https://www.instagram.com${path.trimEnd('/')}/", null)
            }
            MEDIA_PATH.find(path)?.let { match ->
                val kind = if (match.groupValues[1] == "reels") "reel" else match.groupValues[1]
                val code = match.groupValues[2]
                return InstagramLink("https://www.instagram.com/$kind/$code/", code)
            }
            return null
        }
    }
}
