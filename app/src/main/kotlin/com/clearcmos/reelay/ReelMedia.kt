package com.clearcmos.reelay

/** The playable video a reel page exposes, whichever service it came from. */
data class ReelMedia(
    /** Instagram shortcode or Facebook video id; also the stem of the cached file names. */
    val id: String,
    /** Progressive MP4 with muxed audio. Instagram caps it at 720p30, Facebook at 720p. */
    val videoUrl: String,
    val width: Int?,
    val height: Int?,
    /** Reel owner when the page names one; Facebook pages keep it out of the video object. */
    val username: String?,
    /** Best DASH video rendition (video only), when the page carries a manifest. */
    val dashVideo: DashManifest.Representation? = null,
    /** DASH audio rendition matching [dashVideo]. */
    val dashAudio: DashManifest.Representation? = null
) {
    /** True when the full-quality split renditions are available. */
    val hasDash: Boolean get() = dashVideo != null && dashAudio != null
}

sealed class ReelParseException(message: String) : Exception(message) {
    /** The page carried no media JSON: private, removed, rate limited, or a login wall. */
    class NoMedia(message: String) : ReelParseException(message)

    /** The media is a photo or carousel; TikTok's share handler needs a single video. */
    class NotVideo(val mediaType: Int) : ReelParseException("Instagram media_type $mediaType is not a single video")
}
