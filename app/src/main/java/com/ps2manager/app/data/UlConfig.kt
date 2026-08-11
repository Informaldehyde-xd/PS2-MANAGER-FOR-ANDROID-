package com.ps2manager.app.data

import com.ps2manager.app.util.Ps2Crc32

data class UlEntry(
    var nameBytes: ByteArray,
    val imageBytes: ByteArray,
    val parts: Int,
    val media: Int,
    val padBytes: ByteArray
) {
    val title: String
        get() = nameBytes.toString(Charsets.ISO_8859_1).substringBefore('\u0000')

    val gameId: String?
        get() {
            val imageStr = imageBytes.toString(Charsets.ISO_8859_1).substringBefore('\u0000')
            return if (imageStr.startsWith("ul.")) imageStr.substring(3) else null
        }
}

object UlConfig {

    private const val RECORD_SIZE = 64

    // Real on-disk media type codes, confirmed against tools that actually write ul.cfg
    // (e.g. ISO2UL's USBExtreme_game_entry_t) — 0 is NOT a valid media type.
    const val MEDIA_DVD = 0x14
    const val MEDIA_CD = 0x12

    /**
     * The trailing 15 bytes of each 64-byte record aren't pure padding: real USBExtreme/OPL
     * tools always write a fixed marker byte of 0x08 at offset 4 of this region (absolute
     * byte 0x35 in the record), with every other byte zero. Entries built with an all-zero
     * "pad" region are malformed and may be skipped or misread by OPL. Always use this (or
     * an existing entry's original padBytes) rather than a raw ByteArray(15).
     */
    fun defaultPadBytes(): ByteArray {
        val pad = ByteArray(15)
        pad[4] = 0x08
        return pad
    }

    fun parse(bytes: ByteArray): List<UlEntry> {
        val count = bytes.size / RECORD_SIZE
        val entries = ArrayList<UlEntry>(count)
        for (i in 0 until count) {
            val offset = i * RECORD_SIZE
            val name = bytes.copyOfRange(offset, offset + 32)
            val image = bytes.copyOfRange(offset + 32, offset + 47)
            val parts = bytes[offset + 47].toInt() and 0xFF
            val media = bytes[offset + 48].toInt() and 0xFF
            val pad = bytes.copyOfRange(offset + 49, offset + 64)
            entries.add(UlEntry(name, image, parts, media, pad))
        }
        return entries
    }

    fun serialize(entries: List<UlEntry>): ByteArray {
        val out = ByteArray(entries.size * RECORD_SIZE)
        entries.forEachIndexed { i, entry ->
            val offset = i * RECORD_SIZE
            entry.nameBytes.copyInto(out, offset, 0, 32.coerceAtMost(entry.nameBytes.size))
            entry.imageBytes.copyInto(out, offset + 32, 0, 15.coerceAtMost(entry.imageBytes.size))
            out[offset + 47] = entry.parts.toByte()
            out[offset + 48] = entry.media.toByte()
            entry.padBytes.copyInto(out, offset + 49, 0, 15.coerceAtMost(entry.padBytes.size))
        }
        return out
    }

    fun buildNameBytes(title: String): ByteArray {
        val raw = title.toByteArray(Charsets.ISO_8859_1)
        val field = ByteArray(32)
        raw.copyInto(field, 0, 0, raw.size.coerceAtMost(32))
        return field
    }

    fun partFileName(title: String, gameId: String, partIndex: Int): String {
        val crc = Ps2Crc32.crc32OfTitle(title)
        return "ul.%08X.%s.%02d".format(crc, gameId, partIndex)
    }
}
