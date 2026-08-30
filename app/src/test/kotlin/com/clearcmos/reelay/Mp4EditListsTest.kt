package com.clearcmos.reelay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4EditListsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `reads the audio edit offset and timescale`() {
        val file = write(mp4(audioElst = elst(mediaTime = 1600), videoElst = null))
        val summary = Mp4EditLists.inspect(file)
        assertEquals(1600L, summary.audioEditMediaTime)
        assertEquals(44100L, summary.audioTimescale)
        assertEquals(1, summary.editListCount)
    }

    @Test
    fun `skips a leading empty edit and reports the real offset`() {
        val file =
            write(mp4(audioElst = elst(mediaTime = -1, secondMediaTime = 512), videoElst = elst(mediaTime = 1024)))
        val summary = Mp4EditLists.inspect(file)
        assertEquals(512L, summary.audioEditMediaTime)
        assertEquals(2, summary.editListCount)
    }

    @Test
    fun `neutralize renames every edts box and leaves the file length unchanged`() {
        val file = write(mp4(audioElst = elst(mediaTime = 1600), videoElst = elst(mediaTime = 1024)))
        val before = file.length()
        val original = file.readBytes()
        Mp4EditLists.neutralize(file)
        assertEquals(before, file.length())
        val after = Mp4EditLists.inspect(file)
        assertEquals(0, after.editListCount)
        assertNull(after.audioEditMediaTime)
        // Only the two four-byte type fields changed.
        val changed = original.indices.count { original[it] != file.readBytes()[it] }
        assertEquals(8, changed)
    }

    @Test
    fun `file without edit lists is reported as such`() {
        val summary = Mp4EditLists.inspect(write(mp4(audioElst = null, videoElst = null)))
        assertEquals(0, summary.editListCount)
        assertNull(summary.audioEditMediaTime)
    }

    private fun write(bytes: ByteArray): File = folder.newFile("clip.mp4").also { it.writeBytes(bytes) }

    private fun mp4(audioElst: ByteArray?, videoElst: ByteArray?): ByteArray =
        box("ftyp", "isom".toByteArray() + int(0) + "isom".toByteArray()) +
            box(
                "moov",
                box("mvhd", fullBox(int(0) + int(0) + int(1000) + int(0))) +
                    trak(handler = "soun", timescale = 44100, edts = audioElst) +
                    trak(handler = "vide", timescale = 15360, edts = videoElst)
            )

    private fun trak(handler: String, timescale: Int, edts: ByteArray?): ByteArray {
        val mdhd = box("mdhd", fullBox(int(0) + int(0) + int(timescale) + int(0) + short(0) + short(0)))
        val hdlr = box("hdlr", fullBox(int(0) + handler.toByteArray() + int(0) + int(0) + int(0) + byteArrayOf(0)))
        val mdia = box("mdia", mdhd + hdlr)
        val edtsBox = edts?.let { box("edts", box("elst", it)) } ?: ByteArray(0)
        return box("trak", box("tkhd", fullBox(ByteArray(80))) + edtsBox + mdia)
    }

    private fun elst(mediaTime: Int, secondMediaTime: Int? = null): ByteArray {
        val entries = listOfNotNull(mediaTime, secondMediaTime)
        var body = int(entries.size)
        entries.forEach { body += int(1000) + int(it) + short(1) + short(0) }
        return fullBox(body)
    }

    private fun fullBox(payload: ByteArray): ByteArray = int(0) + payload

    private fun box(type: String, payload: ByteArray): ByteArray =
        int(8 + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun int(value: Int): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).writeInt(value) }.toByteArray()

    private fun short(value: Int): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).writeShort(value) }.toByteArray()
}
