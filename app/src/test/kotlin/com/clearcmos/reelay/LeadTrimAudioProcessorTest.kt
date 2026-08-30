package com.clearcmos.reelay

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LeadTrimAudioProcessorTest {
    private val stereo16 = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    @Test
    fun `drops exactly the configured number of leading frames`() {
        val processor = LeadTrimAudioProcessor(trimFrames = 3).apply {
            configure(stereo16)
            flush()
        }
        val input = pcm(0 until 10) // 10 frames, 4 bytes each
        processor.queueInput(input)
        assertArrayEquals(pcm(3 until 10).array(), drain(processor))
        assertFalse(input.hasRemaining())
    }

    @Test
    fun `trim spans buffers and the first buffer can yield nothing`() {
        val processor = LeadTrimAudioProcessor(trimFrames = 5).apply {
            configure(stereo16)
            flush()
        }
        processor.queueInput(pcm(0 until 4))
        assertEquals(0, drain(processor).size)
        processor.queueInput(pcm(4 until 8))
        assertArrayEquals(pcm(5 until 8).array(), drain(processor))
    }

    @Test
    fun `flush re-arms the trim for the next stream`() {
        val processor = LeadTrimAudioProcessor(trimFrames = 2).apply {
            configure(stereo16)
            flush()
        }
        processor.queueInput(pcm(0 until 4))
        drain(processor)
        processor.flush()
        processor.queueInput(pcm(0 until 4))
        assertArrayEquals(pcm(2 until 4).array(), drain(processor))
    }

    @Test
    fun `zero trim passes audio through unchanged`() {
        val processor = LeadTrimAudioProcessor(trimFrames = 0).apply {
            configure(stereo16)
            flush()
        }
        processor.queueInput(pcm(0 until 6))
        assertArrayEquals(pcm(0 until 6).array(), drain(processor))
    }

    /** One 16-bit stereo frame per index: left = index, right = -index. */
    private fun pcm(frames: IntRange): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames.count() * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in frames) {
            buffer.putShort(i.toShort())
            buffer.putShort((-i).toShort())
        }
        buffer.flip()
        return buffer
    }

    private fun drain(processor: AudioProcessor): ByteArray {
        val output = processor.output
        return ByteArray(output.remaining()).also { output.get(it) }
    }
}
