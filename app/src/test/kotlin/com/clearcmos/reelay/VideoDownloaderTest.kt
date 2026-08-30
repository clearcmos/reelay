package com.clearcmos.reelay

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the downloader against an in-process HTTP server; nothing leaves the machine. */
class VideoDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FakeHttpServer
    private lateinit var cache: ClipCache

    private val videoBytes = ByteArray(5000) { (it % 251).toByte() }
    private val audioBytes = ByteArray(700) { (it % 13).toByte() }
    private val progressiveBytes = ByteArray(1200) { (it % 7).toByte() }

    @Before
    fun startServer() {
        server = FakeHttpServer()
        server.serve("/video.mp4", 200, videoBytes)
        server.serve("/audio.mp4", 200, audioBytes)
        server.serve("/progressive.mp4", 200, progressiveBytes)
        server.serve("/missing.mp4", 404, ByteArray(0))
        server.serve("/broken.mp4", 500, ByteArray(0))
        server.start()
        cache = ClipCache(File(folder.root, "reelay"))
    }

    @After
    fun stopServer() = server.close()

    @Test
    fun `dash renditions are fetched into split files`() = runBlocking {
        val source = VideoDownloader(
            cache
        ).download(media(dashVideo = url("/video.mp4"), dashAudio = url("/audio.mp4")))
        val split = source as ReelSource.Split
        assertArrayEquals(videoBytes, split.video.readBytes())
        assertArrayEquals(audioBytes, split.audio.readBytes())
        assertEquals(setOf("ABC-video.mp4", "ABC-audio.mp4"), cacheNames())
    }

    @Test
    fun `a failed rendition falls back to the progressive file and leaves no partial download`() = runBlocking {
        // Regression: the first version deleted only the failed target, so the rendition that
        // had succeeded stayed in the cache until the hourly prune.
        val source = VideoDownloader(
            cache
        ).download(media(dashVideo = url("/video.mp4"), dashAudio = url("/missing.mp4")))
        val single = source as ReelSource.Single
        assertArrayEquals(progressiveBytes, single.file.readBytes())
        assertEquals(setOf("ABC-source.mp4"), cacheNames())
    }

    @Test
    fun `without a manifest only the progressive file is fetched`() = runBlocking {
        val source = VideoDownloader(cache).download(media())
        assertTrue(source is ReelSource.Single)
        assertEquals(listOf(File(folder.root, "reelay/ABC-source.mp4")), source.files)
    }

    @Test
    fun `a non 2xx progressive response is an IOException with no file left behind`() {
        val error =
            assertThrows(IOException::class.java) {
                runBlocking { VideoDownloader(cache).download(media(progressive = url("/broken.mp4"))) }
            }
        assertTrue(error.message, "HTTP 500" in error.message.orEmpty())
        assertFalse(File(folder.root, "reelay/ABC-source.mp4").exists())
    }

    @Test
    fun `requests carry the browser user agent the page was fetched with`() = runBlocking {
        VideoDownloader(cache).download(media())
        assertEquals(InstagramWebFetcher.USER_AGENT, server.requestHeaders["/progressive.mp4"]?.get("user-agent"))
    }

    private fun media(
        progressive: String = url("/progressive.mp4"),
        dashVideo: String? = null,
        dashAudio: String? = null
    ) = ReelMedia(
        shortcode = "ABC",
        videoUrl = progressive,
        width = 720,
        height = 1280,
        username = "someone",
        dashVideo = dashVideo?.let { rep(it, "video/mp4") },
        dashAudio = dashAudio?.let { rep(it, "audio/mp4") }
    )

    private fun rep(url: String, mime: String) = DashManifest.Representation(url, mime, "codec", 1000, null, null, null)

    private fun url(path: String) = server.url(path)

    private fun cacheNames(): Set<String> =
        File(folder.root, "reelay").listFiles()?.map { it.name }?.toSet() ?: emptySet()
}
