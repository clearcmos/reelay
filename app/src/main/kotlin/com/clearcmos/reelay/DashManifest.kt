package com.clearcmos.reelay

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The parts of Instagram's `video_dash_manifest` that matter here.
 *
 * Instagram's progressive `video_versions` MP4 tops out at 720p30; the app itself plays
 * DASH renditions that go up to the upload's native size and frame rate (1080p60 seen
 * 2026-08-30). Each representation is a single file behind `BaseURL`, downloadable with a
 * plain GET, video and audio separately.
 */
data class DashManifest(val video: List<Representation>, val audio: List<Representation>) {
    data class Representation(
        val url: String,
        val mimeType: String,
        val codecs: String,
        val bandwidth: Long,
        val width: Int?,
        val height: Int?,
        val frameRate: Double?
    )

    /** Largest frame first, then highest bandwidth. */
    fun bestVideo(): Representation? = video.maxWithOrNull(
        compareBy<Representation> {
            (it.width ?: 0) *
                (it.height ?: 0)
        }.thenBy { it.bandwidth }
    )

    fun bestAudio(): Representation? = audio.maxByOrNull { it.bandwidth }

    companion object {
        fun parse(xml: String): DashManifest {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val nodes = document.getElementsByTagName("Representation")
            val representations =
                (0 until nodes.length).mapNotNull { index ->
                    val element = nodes.item(index) as? Element ?: return@mapNotNull null
                    val url = element.getElementsByTagName("BaseURL").item(0)?.textContent?.trim().orEmpty()
                    if (url.isEmpty()) return@mapNotNull null
                    Representation(
                        url = url,
                        mimeType = element.getAttribute("mimeType"),
                        codecs = element.getAttribute("codecs"),
                        bandwidth = element.getAttribute("bandwidth").toLongOrNull() ?: 0L,
                        width = element.getAttribute("width").toIntOrNull(),
                        height = element.getAttribute("height").toIntOrNull(),
                        frameRate = parseFrameRate(element.getAttribute("frameRate"))
                    )
                }
            return DashManifest(
                video = representations.filter { it.mimeType.startsWith("video/") },
                audio = representations.filter { it.mimeType.startsWith("audio/") }
            )
        }

        /** Accepts "60", "29.97", or the MPD fraction form "15360/256". */
        internal fun parseFrameRate(raw: String?): Double? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('/')
            val numerator = parts[0].toDoubleOrNull() ?: return null
            val denominator = if (parts.size > 1) parts[1].toDoubleOrNull() ?: return null else 1.0
            if (denominator == 0.0) return null
            return numerator / denominator
        }
    }
}
