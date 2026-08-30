package com.clearcmos.reelay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share-sheet entry point: turns a shared Instagram link (or an already shared video)
 * into a TikTok share, so the editor opens with the clip loaded and "Your Story" is
 * one tap away.
 */
class ShareActivity : AppCompatActivity() {
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var close: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        close = findViewById(R.id.close)
        close.setOnClickListener { finish() }
        if (savedInstanceState == null) {
            lifecycleScope.launch { relay(intent) }
        }
    }

    private suspend fun relay(intent: Intent) {
        try {
            val video = resolveVideo(intent)
            setStatus(R.string.status_handoff)
            val tiktok =
                TikTokHandoff.buildIntent(packageManager, video)
                    ?: throw RelayException(getString(R.string.error_no_tiktok))
            startActivity(tiktok)
            finish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e)
        }
    }

    private suspend fun resolveVideo(intent: Intent): Uri {
        val link = InstagramLink.parse(intent.getStringExtra(Intent.EXTRA_TEXT))
        if (link == null) {
            val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (stream != null && intent.type?.startsWith("video/") == true) return stream
            throw RelayException(getString(R.string.error_no_link))
        }
        setStatus(R.string.status_fetching)
        val page = InstagramWebFetcher(this).fetch(link.url)
        val shortcode = link.shortcode ?: InstagramLink.fromUrl(page.finalUrl)?.shortcode
        val media = ReelPageParser.parse(page.html, shortcode)

        setStatus(R.string.status_downloading)
        val cache = ClipCache.of(this)
        withContext(Dispatchers.IO) { cache.prune() }
        val downloader = VideoDownloader(cache)
        val source = downloader.download(media)

        setStatus(R.string.status_normalizing)
        val clip =
            normalizeOrNull(source, cache.file("reelay-${media.shortcode}.mp4"))
                ?: (source as? ReelSource.Single)?.file
                ?: downloader.downloadProgressive(media).file
        source.files.filter { it != clip }.forEach { it.delete() }

        // TikTok copies the clip when its editor opens; the cache keeps it for an hour in case it re-reads.
        CleanupJobService.schedule(this)
        return FileProvider.getUriForFile(this, FILE_AUTHORITY, clip)
    }

    /** Re-encodes for TikTok; null when the device encoder fails, so the caller can fall back to Instagram's own file. */
    private suspend fun normalizeOrNull(source: ReelSource, output: File): File? = try {
        VideoNormalizer(this).normalize(source, output) { percent ->
            status.text = getString(R.string.status_normalizing_progress, percent)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Re-encoding failed; falling back to Instagram's progressive file", e)
        output.delete()
        null
    }

    private fun setStatus(@StringRes id: Int) {
        status.setText(id)
    }

    private fun showError(e: Exception) {
        progress.visibility = View.GONE
        close.visibility = View.VISIBLE
        status.text =
            when (e) {
                is RelayException -> e.message
                is ReelParseException.NotVideo -> getString(R.string.error_not_video)
                is ReelParseException.NoMedia -> getString(R.string.error_no_media, e.message)
                else -> getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
            }
    }

    private companion object {
        const val TAG = "Reelay"
        const val FILE_AUTHORITY = "com.clearcmos.reelay.files"
    }
}

/** A failure whose message is already written for the user. */
class RelayException(message: String) : Exception(message)
