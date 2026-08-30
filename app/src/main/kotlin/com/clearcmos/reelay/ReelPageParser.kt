package com.clearcmos.reelay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** The playable video Instagram exposes for one reel. */
data class ReelMedia(
    val shortcode: String,
    /** Progressive MP4 with muxed audio; Instagram caps it at 720p30. */
    val videoUrl: String,
    val width: Int?,
    val height: Int?,
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

/**
 * Extracts the reel video from a logged-out Instagram reel page.
 *
 * The server-rendered page embeds Relay preloader JSON in
 * `<script type="application/json" data-sjs>` blocks; one of them carries
 * `xig_polaris_media.if_not_gated_logged_out` with `video_versions` and, for most reels,
 * a `video_dash_manifest` whose renditions go beyond the 720p progressive file. The
 * parser walks every block generically instead of hardcoding the Relay path because the
 * wrapper keys are generated names that change between Instagram deploys.
 */
object ReelPageParser {
    private const val MEDIA_TYPE_VIDEO = 2
    private val SJS_BLOCK = Regex("""<script\b[^>]*\bdata-sjs\b[^>]*>(\{.*?\})</script>""", RegexOption.DOT_MATCHES_ALL)
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Parses [html]; when [expectedShortcode] is given, a media object with that `code`
     * wins over any other embedded media (related reels, carousel children).
     */
    fun parse(html: String, expectedShortcode: String? = null): ReelMedia {
        val candidates = mutableListOf<JsonObject>()
        for (match in SJS_BLOCK.findAll(html)) {
            val body = match.groupValues[1]
            if ("\"video_versions\"" !in body) continue
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: continue
            collectMedia(root, candidates)
        }
        if (candidates.isEmpty()) {
            val reason =
                if ("/accounts/login" in html) {
                    "Instagram asked for a login; the reel is private or gated"
                } else {
                    "no reel media in the Instagram page"
                }
            throw ReelParseException.NoMedia(reason)
        }
        val media =
            candidates.firstOrNull { expectedShortcode != null && it.string("code") == expectedShortcode }
                ?: candidates.first()
        val mediaType = media.int("media_type") ?: MEDIA_TYPE_VIDEO
        if (mediaType != MEDIA_TYPE_VIDEO) throw ReelParseException.NotVideo(mediaType)

        val versions = (media["video_versions"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val best =
            versions
                .filter { !it.string("url").isNullOrBlank() }
                .sortedWith(
                    compareByDescending<JsonObject> { (it.int("width") ?: 0) * (it.int("height") ?: 0) }
                        .thenBy { it.int("type") ?: Int.MAX_VALUE }
                ).firstOrNull()
                ?: throw ReelParseException.NoMedia("reel has no downloadable video version")

        val manifest = media.string("video_dash_manifest")?.let { xml ->
            runCatching { DashManifest.parse(xml) }.getOrNull()
        }
        val dashVideo = manifest?.bestVideo()
        val dashAudio = manifest?.bestAudio()

        return ReelMedia(
            shortcode = media.string("code") ?: expectedShortcode ?: "reel",
            videoUrl = checkNotNull(best.string("url")),
            width = dashVideo?.width ?: best.int("width") ?: media.int("original_width"),
            height = dashVideo?.height ?: best.int("height") ?: media.int("original_height"),
            username =
            (media["user"] as? JsonObject)?.string("username")
                ?: (media["owner"] as? JsonObject)?.string("username"),
            dashVideo = dashVideo,
            dashAudio = dashAudio
        )
    }

    private fun collectMedia(element: JsonElement, out: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> {
                val versions = element["video_versions"]
                if (versions is JsonArray && versions.isNotEmpty() && ("code" in element || "pk" in element)) {
                    out += element
                }
                element.values.forEach { collectMedia(it, out) }
            }
            is JsonArray -> element.forEach { collectMedia(it, out) }
            else -> Unit
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
