package com.clearcmos.reelay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Extracts the reel video from a logged-out Facebook reel page.
 *
 * One of the page's [ServerJsBlocks] carries the playback video with a
 * `videoDeliveryLegacyFields` object holding two progressive MP4s
 * (`browser_native_hd_url` at 720p, `browser_native_sd_url` at 360p) and
 * `dash_manifest_xml_string`, an inline MPD whose best rendition is the native upload size
 * (1080x1920 AV1 seen 2026-09-12). The same block also carries the "up next" reels of the
 * logged-out feed, so [expectedId] picks the one that was shared; without it the reel the
 * page is about comes first.
 */
object FacebookPageParser {
    private const val MARKER = "\"videoDeliveryLegacyFields\""
    private const val DELIVERY = "videoDeliveryLegacyFields"

    /** Parses [html]; [expectedId] is the Facebook video id when the shared URL named one. */
    fun parse(html: String, expectedId: String? = null): ReelMedia {
        val candidates = mutableListOf<JsonObject>()
        for (root in ServerJsBlocks.containing(html, MARKER)) collectVideos(root, candidates)
        if (candidates.isEmpty()) {
            val reason =
                if ("/login/?next=" in html || "login_form" in html) {
                    "Facebook asked for a login; the reel is private or gated"
                } else {
                    "no reel media in the Facebook page"
                }
            throw ReelParseException.NoMedia(reason)
        }
        val video =
            candidates.firstOrNull { expectedId != null && it.videoId() == expectedId }
                ?: candidates.first()
        val delivery = checkNotNull(video[DELIVERY] as? JsonObject)

        val progressive =
            delivery.string("browser_native_hd_url")?.ifBlank { null }
                ?: delivery.string("browser_native_sd_url")?.ifBlank { null }
                ?: throw ReelParseException.NoMedia("reel has no downloadable video version")

        val manifest = delivery.string("dash_manifest_xml_string")?.let { xml ->
            runCatching { DashManifest.parse(xml) }.getOrNull()
        }
        val dashVideo = manifest?.bestVideo()
        val dashAudio = manifest?.bestAudio()

        return ReelMedia(
            id = video.videoId() ?: expectedId ?: "reel",
            videoUrl = progressive,
            width = dashVideo?.width ?: video.int("width"),
            height = dashVideo?.height ?: video.int("height"),
            // The owner sits beside the video object rather than inside it; nothing downstream uses it.
            username = null,
            dashVideo = dashVideo,
            dashAudio = dashAudio
        )
    }

    /** Video objects are those carrying a delivery object with at least one progressive URL. */
    private fun collectVideos(element: JsonElement, out: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> {
                val delivery = element[DELIVERY] as? JsonObject
                if (delivery != null && delivery.keys.any { it in PROGRESSIVE_KEYS }) out += element
                element.values.forEach { collectVideos(it, out) }
            }
            is JsonArray -> element.forEach { collectVideos(it, out) }
            else -> Unit
        }
    }

    private val PROGRESSIVE_KEYS = setOf("browser_native_hd_url", "browser_native_sd_url")

    private fun JsonObject.videoId(): String? = string("id") ?: (this[DELIVERY] as? JsonObject)?.string("id")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
