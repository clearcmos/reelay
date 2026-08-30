package com.clearcmos.reelay

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Re-encodes a downloaded reel into the shape TikTok's editor expects: H.264 without
 * B-frames, AAC-LC audio, both tracks starting at zero, no edit lists.
 *
 * Instagram's MP4s carry edit lists (a B-frame reorder offset on video, HE-AAC encoder
 * priming on audio). Players that honor them, Instagram included, stay in sync; any
 * consumer that ignores them puts the tracks 50-115 ms apart (measured 2026-08-29). Media3
 * Transformer applies the edit lists while decoding; the AAC encoder's own priming is
 * trimmed from the input up front ([LeadTrimAudioProcessor]) and the edit list Media3
 * writes for it is then neutralised ([Mp4EditLists]), so the output has no edit lists and
 * plays the same in every player.
 *
 * A [ReelSource.Split] input (Instagram's DASH renditions: VP9 video-only plus a separate
 * audio file) is muxed here into one H.264/AAC file; this is what lets Reelay hand TikTok
 * the reel at its native resolution and frame rate instead of the 720p30 progressive file.
 */
@OptIn(UnstableApi::class)
class VideoNormalizer(private val context: Context) {
    suspend fun normalize(input: ReelSource, output: File, onProgress: (Int) -> Unit = {}): File {
        var trim = EXPECTED_ENCODER_DELAY_FRAMES
        repeat(MAX_PASSES) { pass ->
            transcode(input, output, trim, onProgress)
            val edits = withContext(Dispatchers.IO) { Mp4EditLists.inspect(output) }
            val priming = edits.audioEditMediaTime ?: 0L
            if (priming == trim.toLong() || pass == MAX_PASSES - 1) {
                if (priming != trim.toLong()) {
                    Log.w(
                        TAG,
                        "Encoder priming $priming frames differs from trim $trim; ${priming - trim} frames of offset remain"
                    )
                }
                withContext(Dispatchers.IO) { Mp4EditLists.neutralize(output) }
                return output
            }
            // This encoder primes by a different amount than expected; re-run with the measured value.
            Log.i(TAG, "Encoder priming is $priming frames, re-encoding with a matching trim")
            trim = priming.toInt()
        }
        return output
    }

    private suspend fun transcode(input: ReelSource, output: File, trimFrames: Int, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.Main) {
            val videoFile =
                when (input) {
                    is ReelSource.Single -> input.file
                    is ReelSource.Split -> input.video
                }
            val source = probe(videoFile)
            val bitrate = EncodingBudget.bitrateFor(source.bitrate, source.width, source.height, source.fps)
            val encoderFactory =
                DefaultEncoderFactory
                    .Builder(context)
                    // Non-default encoder settings make Transformer re-encode instead of transmuxing.
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .build()
            // The trim processor also forces the audio to be decoded and re-encoded.
            val audioEffects = Effects(listOf(LeadTrimAudioProcessor(trimFrames)), emptyList())
            val composition =
                when (input) {
                    is ReelSource.Single ->
                        Composition
                            .Builder(
                                EditedMediaItemSequence.withAudioAndVideoFrom(
                                    listOf(
                                        EditedMediaItem.Builder(
                                            MediaItem.fromUri(Uri.fromFile(input.file))
                                        ).setEffects(audioEffects).build()
                                    )
                                )
                            ).build()
                    is ReelSource.Split ->
                        // A video-only sequence from the DASH video rendition plus an audio-only sequence from the audio one.
                        Composition
                            .Builder(
                                EditedMediaItemSequence.withVideoFrom(
                                    listOf(
                                        EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input.video))).build()
                                    )
                                ),
                                EditedMediaItemSequence.withAudioFrom(
                                    listOf(
                                        EditedMediaItem.Builder(
                                            MediaItem.fromUri(Uri.fromFile(input.audio))
                                        ).setEffects(audioEffects).build()
                                    )
                                )
                            ).build()
                }
            output.delete()
            suspendCancellableCoroutine { cont ->
                val transformer =
                    Transformer
                        .Builder(context)
                        .setEncoderFactory(encoderFactory)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        // Encode portrait as portrait; the default encodes landscape plus a rotate-90 tag,
                        // which is one more piece of metadata a consumer must honor. Encoder failures fall
                        // back to Instagram's progressive file in ShareActivity.
                        .setPortraitEncodingEnabled(true)
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (cont.isActive) cont.resume(output)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    if (cont.isActive) {
                                        cont.resumeWithException(
                                            IOException(
                                                "Re-encoding failed: ${exportException.message}",
                                                exportException
                                            )
                                        )
                                    }
                                }
                            }
                        ).build()
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, output.absolutePath)

                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                handler.postDelayed(
                    object : Runnable {
                        override fun run() {
                            if (!cont.isActive) return
                            if (transformer.getProgress(holder) ==
                                Transformer.PROGRESS_STATE_AVAILABLE
                            ) {
                                onProgress(holder.progress)
                            }
                            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
                        }
                    },
                    PROGRESS_INTERVAL_MS
                )
            }
        }

    private data class Source(val bitrate: Int?, val width: Int, val height: Int, val fps: Double?)

    private fun probe(file: File): Source = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
        val frames = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
        val durationMs = int(MediaMetadataRetriever.METADATA_KEY_DURATION)
        Source(
            bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
            width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: DEFAULT_WIDTH,
            height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: DEFAULT_HEIGHT,
            fps = if (frames != null &&
                durationMs != null &&
                durationMs > 0
            ) {
                frames * 1000.0 / durationMs
            } else {
                null
            }
        )
    }

    private companion object {
        const val TAG = "Reelay"

        /** Priming of the AAC-LC encoder Android ships (c2.android.aac.encoder, fdk-aac): 1600 frames. */
        const val EXPECTED_ENCODER_DELAY_FRAMES = 1600
        const val MAX_PASSES = 2
        const val PROGRESS_INTERVAL_MS = 500L
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1280
    }
}
