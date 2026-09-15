package com.clearcmos.reelay

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The parts of Meta's inline DASH manifest that matter here.
 *
 * The progressive MP4 each service exposes tops out at 720p (Instagram 720p30 in
 * `video_versions`, Facebook 720p in `browser_native_hd_url`); the apps themselves play
 * DASH renditions that go up to the upload's native size and frame rate (Instagram 1080p60
 * VP9 seen 2026-08-30, Facebook 1080p30 AV1 seen 2026-09-12). Each representation is a
 * single file behind `BaseURL`, downloadable with a plain GET, video and audio separately;
 * the `SegmentBase` byte ranges Facebook adds describe that same file and can be ignored.
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
                        // Instagram tags the representation, Facebook only the enclosing AdaptationSet.
                        frameRate =
                        parseFrameRate(element.getAttribute("frameRate"))
                            ?: parseFrameRate((element.parentNode as? Element)?.getAttribute("frameRate"))
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
