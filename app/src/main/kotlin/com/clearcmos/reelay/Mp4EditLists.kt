package com.clearcmos.reelay

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal MP4 box walker for what the normalizer needs after Media3 has written the file:
 * read the audio track's edit-list offset (the encoder priming Media3 recorded) and
 * neutralise every edit list by renaming `edts` boxes to `free`, which parsers skip.
 * Renaming keeps every byte offset in the file valid, so no other box has to change.
 */
object Mp4EditLists {
    data class Summary(
        /** First non-empty `elst` media_time on the audio track, in [audioTimescale] units. */
        val audioEditMediaTime: Long?,
        val audioTimescale: Long?,
        val editListCount: Int
    )

    fun inspect(file: File): Summary = RandomAccessFile(file, "r").use { walk(it, neutralize = false) }

    fun neutralize(file: File): Summary = RandomAccessFile(file, "rw").use { walk(it, neutralize = true) }

    private fun walk(raf: RandomAccessFile, neutralize: Boolean): Summary {
        var audioMediaTime: Long? = null
        var audioTimescale: Long? = null
        var editLists = 0
        forEachBox(raf, 0, raf.length()) { type, _, payload, end ->
            if (type != "moov") return@forEachBox
            forEachBox(raf, payload, end) { trakType, _, trakPayload, trakEnd ->
                if (trakType != "trak") return@forEachBox
                var handler: String? = null
                var timescale: Long? = null
                var mediaTime: Long? = null
                val edtsStarts = mutableListOf<Long>()
                forEachBox(raf, trakPayload, trakEnd) { child, childStart, childPayload, childEnd ->
                    when (child) {
                        "mdia" ->
                            forEachBox(raf, childPayload, childEnd) { leaf, _, leafPayload, _ ->
                                when (leaf) {
                                    "hdlr" -> handler = readHandlerType(raf, leafPayload)
                                    "mdhd" -> timescale = readTimescale(raf, leafPayload)
                                }
                            }
                        "edts" -> {
                            edtsStarts += childStart
                            forEachBox(raf, childPayload, childEnd) { leaf, _, leafPayload, _ ->
                                if (leaf == "elst") mediaTime = readFirstMediaTime(raf, leafPayload)
                            }
                        }
                    }
                }
                editLists += edtsStarts.size
                if (handler == "soun" && mediaTime != null) {
                    audioMediaTime = mediaTime
                    audioTimescale = timescale
                }
                if (neutralize) {
                    edtsStarts.forEach { start ->
                        raf.seek(start + 4)
                        raf.write("free".toByteArray(Charsets.ISO_8859_1))
                    }
                }
            }
        }
        return Summary(audioMediaTime, audioTimescale, editLists)
    }

    /** Calls [action] with (type, boxStart, payloadStart, boxEnd) for each box between [from] and [to]. */
    private fun forEachBox(raf: RandomAccessFile, from: Long, to: Long, action: (String, Long, Long, Long) -> Unit) {
        var pos = from
        while (pos + BOX_HEADER <= to) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { raf.readFully(it) }, Charsets.ISO_8859_1)
            var payload = pos + BOX_HEADER
            when (size) {
                1L -> {
                    size = raf.readLong()
                    payload = pos + BOX_HEADER + 8
                }
                0L -> size = to - pos
            }
            val end = pos + size
            if (size < BOX_HEADER || end > to) return
            action(type, pos, payload, end)
            pos = end
        }
    }

    private fun readHandlerType(raf: RandomAccessFile, payload: Long): String {
        // FullBox header (4) + pre_defined (4), then handler_type.
        raf.seek(payload + 8)
        return String(ByteArray(4).also { raf.readFully(it) }, Charsets.ISO_8859_1)
    }

    private fun readTimescale(raf: RandomAccessFile, payload: Long): Long {
        raf.seek(payload)
        val version = raf.readByte().toInt()
        // FullBox header (4), then creation and modification times (4+4 or 8+8), then timescale.
        raf.seek(payload + 4 + if (version == 1) 16 else 8)
        return raf.readInt().toLong() and 0xFFFFFFFFL
    }

    private fun readFirstMediaTime(raf: RandomAccessFile, payload: Long): Long? {
        raf.seek(payload)
        val version = raf.readByte().toInt()
        raf.seek(payload + 4)
        val entries = raf.readInt()
        repeat(entries.coerceAtMost(MAX_ELST_ENTRIES)) {
            val mediaTime =
                if (version == 1) {
                    raf.readLong()
                    raf.readLong()
                } else {
                    raf.readInt()
                    raf.readInt().toLong()
                }
            raf.readInt() // media_rate integer + fraction
            // -1 marks an empty edit (a delay); the first real edit carries the offset.
            if (mediaTime >= 0) return mediaTime
        }
        return null
    }

    private const val BOX_HEADER = 8L
    private const val MAX_ELST_ENTRIES = 16
}
