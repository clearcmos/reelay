package com.clearcmos.reelay

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Drops the first [trimFrames] PCM frames of the decoded audio.
 *
 * The AAC encoder prepends that many frames of priming; without an edit list a player
 * hears the audio late by exactly that amount. Removing the same amount from the input
 * makes the priming land where the removed audio was, so the stream needs no edit list.
 */
@OptIn(UnstableApi::class)
class LeadTrimAudioProcessor(private val trimFrames: Int) : BaseAudioProcessor() {
    private var bytesToSkip = 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        bytesToSkip = trimFrames.toLong() * inputAudioFormat.bytesPerFrame
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (bytesToSkip > 0) {
            val skip = minOf(bytesToSkip, inputBuffer.remaining().toLong()).toInt()
            inputBuffer.position(inputBuffer.position() + skip)
            bytesToSkip -= skip
        }
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onFlush() {
        bytesToSkip = trimFrames.toLong() * inputAudioFormat.bytesPerFrame
    }
}
