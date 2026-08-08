package com.ps2manager.app.util

import net.jpountz.lz4.LZ4Factory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level (de)serialization for the .zso container format: a compressed PS2 ISO
 * image, block-split and LZ4-compressed, as produced/read by common OPL-compatible
 * tools (ziso/CISO-family format, LZ4 variant).
 *
 * Layout on disk:
 *   [0..24)                header (24 bytes, see [Header])
 *   [24 .. 24+4*(n+1))      index table: (numBlocks + 1) little-endian uint32 entries
 *   [dataStart .. EOF)      block data, one entry per block, each either a raw
 *                           [blockSize]-byte sector or an LZ4-compressed block
 *
 * Each index entry packs an (offset >> align) value in its low 31 bits; the top bit
 * (0x80000000) is set when that block is stored **uncompressed** (raw passthrough),
 * and clear when it holds an LZ4 block. A block's on-disk length is derived from the
 * difference between its offset and the next entry's offset.
 */
object ZisoFormat {

    const val MAGIC = "ZISO"
    const val HEADER_SIZE = 24

    // Every block/file offset in the index is stored as (realOffset shr WRITE_ALIGN).
    // Shifting by 3 (i.e. offsets always land on 8-byte boundaries) gives ~17GB of
    // addressable range, comfortably covering the largest realistic PS2 disc image
    // even in the worst case where compression buys nothing.
    const val WRITE_ALIGN = 3
    const val WRITE_BLOCK_SIZE = 2048 // one ISO9660 sector — keeps 1 block == 1 sector

    private const val UNCOMPRESSED_FLAG = 0x80000000.toInt()
    private const val OFFSET_MASK = 0x7FFFFFFF

    private val lz4Factory = LZ4Factory.fastestInstance()

    data class Header(
        val totalBytes: Long,
        val blockSize: Int,
        val version: Int,
        val align: Int
    ) {
        val numBlocks: Int get() = ((totalBytes + blockSize - 1) / blockSize).toInt()
    }

    /** Parses a 24-byte header. Returns null if [bytes] isn't a recognizable .zso header. */
    fun parseHeader(bytes: ByteArray): Header? {
        if (bytes.size < HEADER_SIZE) return null
        if (bytes[0] != 'Z'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'S'.code.toByte() || bytes[3] != 'O'.code.toByte()
        ) return null

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        buf.int // headerSize, unused beyond validation
        val totalBytes = buf.long
        val blockSize = buf.int
        if (blockSize <= 0 || totalBytes < 0) return null
        val ver = buf.get().toInt() and 0xFF
        val align = buf.get().toInt() and 0xFF
        return Header(totalBytes, blockSize, ver, align)
    }

    /** Quick magic-only check, useful before committing to a full header read. */
    fun looksLikeZiso(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'Z'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'S'.code.toByte() && bytes[3] == 'O'.code.toByte()

    fun buildHeaderBytes(totalBytes: Long, blockSize: Int, version: Int, align: Int): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buf.putInt(HEADER_SIZE)
        buf.putLong(totalBytes)
        buf.putInt(blockSize)
        buf.put(version.toByte())
        buf.put(align.toByte())
        buf.put(0) // reserved
        buf.put(0) // reserved
        return buf.array()
    }

    fun isBlockCompressed(indexEntry: Int): Boolean = (indexEntry and UNCOMPRESSED_FLAG) == 0

    fun blockOffset(indexEntry: Int, align: Int): Long =
        (indexEntry.toLong() and OFFSET_MASK.toLong()) shl align

    fun encodeIndexEntry(offset: Long, align: Int, compressed: Boolean): Int {
        val shifted = (offset shr align).toInt() and OFFSET_MASK
        return if (compressed) shifted else (shifted or UNCOMPRESSED_FLAG)
    }

    fun newFastCompressor() = lz4Factory.fastCompressor()
    fun newFastDecompressor() = lz4Factory.fastDecompressor()

    /** Reads a little-endian uint32 from [bytes] at [offset] as an Int (bit pattern preserved). */
    fun readIndexEntry(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    fun writeIndexEntry(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
