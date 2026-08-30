package com.clearcmos.reelay

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** What was fetched for one reel: either Instagram's muxed 720p file or the split full-quality renditions. */
sealed class ReelSource {
    abstract val files: List<File>

    data class Single(val file: File) : ReelSource() {
        override val files get() = listOf(file)
    }

    data class Split(val video: File, val audio: File) : ReelSource() {
        override val files get() = listOf(video, audio)
    }
}

/**
 * Streams reel media from Instagram's CDN into the clip cache.
 *
 * The CDN is not fingerprint-gated (verified 2026-08-29), so the platform HTTP client is
 * enough. When the page offered DASH renditions the video and audio files are fetched in
 * parallel; a failure there falls back to the progressive file.
 */
class VideoDownloader(private val cache: ClipCache) {
    suspend fun download(media: ReelMedia): ReelSource {
        val dashVideo = media.dashVideo
        val dashAudio = media.dashAudio
        if (dashVideo != null && dashAudio != null) {
            val videoTarget = cache.file("${media.shortcode}-video.mp4")
            val audioTarget = cache.file("${media.shortcode}-audio.mp4")
            val split =
                runCatching {
                    coroutineScope {
                        val video = async { fetch(dashVideo.url, videoTarget) }
                        val audio = async { fetch(dashAudio.url, audioTarget) }
                        ReelSource.Split(video.await(), audio.await())
                    }
                }
            split.getOrNull()?.let { return it }
            // One rendition failed; the other may have completed and must not linger in the cache.
            videoTarget.delete()
            audioTarget.delete()
        }
        return downloadProgressive(media)
    }

    suspend fun downloadProgressive(media: ReelMedia): ReelSource.Single =
        ReelSource.Single(fetch(media.videoUrl, cache.file("${media.shortcode}-source.mp4")))

    private suspend fun fetch(url: String, target: File): File = withContext(Dispatchers.IO) {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty(USER_AGENT_HEADER, InstagramWebFetcher.USER_AGENT)
            }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Instagram CDN returned HTTP $code")
            target.outputStream().use { sink -> connection.inputStream.use { it.copyTo(sink) } }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        target
    }

    companion object {
        /** Sent on every CDN request; the same UA the WebView used to obtain the URLs. */
        const val USER_AGENT_HEADER = "User-Agent"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
