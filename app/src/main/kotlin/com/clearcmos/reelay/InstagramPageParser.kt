package com.clearcmos.reelay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Extracts the reel video from a logged-out Instagram reel page.
 *
 * One of the page's [ServerJsBlocks] carries `xig_polaris_media.if_not_gated_logged_out`
 * with `video_versions` and, for most reels, a `video_dash_manifest` whose renditions go
 * beyond the 720p progressive file. The parser walks every block generically instead of
 * following the Relay path, because the wrapper keys are generated names.
 */
object InstagramPageParser {
    private const val MEDIA_TYPE_VIDEO = 2
    private const val MARKER = "\"video_versions\""

    /**
     * Parses [html]; when [expectedId] is given, a media object with that `code` wins over
     * any other embedded media (related reels, carousel children).
     */
    fun parse(html: String, expectedId: String? = null): ReelMedia {
        val candidates = mutableListOf<JsonObject>()
        for (root in ServerJsBlocks.containing(html, MARKER)) collectMedia(root, candidates)
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
            candidates.firstOrNull { expectedId != null && it.string("code") == expectedId }
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
            id = media.string("code") ?: expectedId ?: "reel",
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
