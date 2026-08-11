package com.ps2manager.app.util

/**
 * This is NOT the standard zlib CRC-32. It's OPL/USBExtreme's own custom
 * CRC-32 variant, transcribed exactly from Open-PS2-Loader's own source
 * (pc/opl2iso/src/opl2iso.c) so that renamed UL games produce filenames
 * byte-identical to what real OPL tools would generate.
 */
object Ps2Crc32 {

    private val table = IntArray(256)
    private val seed: Int

    init {
        for (t in 0 until 256) {
            var crc = t shl 24
            repeat(8) {
                crc = if (crc < 0) (crc shl 1) else ((crc shl 1) xor 0x04C11DB7)
            }
            table[255 - t] = crc
        }
        seed = table[0]
    }

    fun crc32OfTitle(title: String): Int {
        val raw = title.toByteArray(Charsets.ISO_8859_1)
        val truncated = if (raw.size > 32) raw.copyOf(32) else raw
        val bytesToHash = truncated + byteArrayOf(0)

        var crc = seed
        for (b in bytesToHash) {
            val byteVal = b.toInt() and 0xFF
            val idx = byteVal xor ((crc ushr 24) and 0xFF)
            crc = table[idx] xor (crc shl 8)
        }
        return crc
    }
}
