package com.ps2manager.app.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Random-access reading helpers for .zso images, used only to peek at specific
 * sectors while scanning (e.g. reading SYSTEM.CNF to determine a game's ID) without
 * decompressing the whole image. Actual ISO<->ZSO conversion is handled by
 * [DiscConverter]; this object is intentionally read-only.
 */
object ZisoConverter {

    /** Reads and validates just the 24-byte header from an open [channel]. */
    fun readHeader(channel: FileChannel): ZisoFormat.Header? {
        val buf = ByteBuffer.allocate(ZisoFormat.HEADER_SIZE)
        channel.position(0)
        var got = 0
        while (got < ZisoFormat.HEADER_SIZE) {
            val read = channel.read(buf)
            if (read < 0) break
            got += read
        }
        if (got < ZisoFormat.HEADER_SIZE) return null
        return ZisoFormat.parseHeader(buf.array())
    }

    /**
     * Random-access read of [length] decompressed bytes starting at logical [position],
     * used to peek at specific sectors (e.g. the PVD or a SYSTEM.CNF extent) without
     * decompressing the whole image. Reads the header + only the index entries it needs
     * directly from [channel], so it stays cheap even on multi-gigabyte images.
     */
    fun readLogicalRange(channel: FileChannel, header: ZisoFormat.Header, position: Long, length: Int): ByteArray? {
        if (length <= 0 || position < 0 || position + length > header.totalBytes) return null
        val blockSize = header.blockSize
        val firstBlock = (position / blockSize).toInt()
        val lastBlock = ((position + length - 1) / blockSize).toInt()

        val result = ByteArray(length)
        var resultOffset = 0
        var remaining = length
        var srcCursor = position

        val decompressor = ZisoFormat.newFastDecompressor()

        for (blockIndex in firstBlock..lastBlock) {
            val entry = readIndexEntryAt(channel, blockIndex) ?: return null
            val nextEntry = readIndexEntryAt(channel, blockIndex + 1) ?: return null

            val blockOffset = ZisoFormat.blockOffset(entry, header.align)
            val nextOffset = ZisoFormat.blockOffset(nextEntry, header.align)
            val compressedLen = (nextOffset - blockOffset).toInt()
            val compressed = ZisoFormat.isBlockCompressed(entry)
            val decompressedLen =
                minOf(blockSize.toLong(), header.totalBytes - blockIndex.toLong() * blockSize).toInt()
            if (compressedLen < 0 || decompressedLen <= 0) return null

            val raw = ByteArray(compressedLen)
            val buf = ByteBuffer.wrap(raw)
            channel.position(blockOffset)
            var got = 0
            while (got < compressedLen) {
                val read = channel.read(buf)
                if (read < 0) break
                got += read
            }
            if (got < compressedLen) return null

            val blockData = if (compressed) {
                val dst = ByteArray(decompressedLen)
                decompressor.decompress(raw, 0, dst, 0, decompressedLen)
                dst
            } else {
                raw.copyOf(decompressedLen)
            }

            val blockStartLogical = blockIndex.toLong() * blockSize
            val copyStart = maxOf(srcCursor, blockStartLogical)
            val copyEnd = minOf(srcCursor + remaining, blockStartLogical + decompressedLen)
            if (copyEnd <= copyStart) continue

            val srcOff = (copyStart - blockStartLogical).toInt()
            val copyLen = (copyEnd - copyStart).toInt()
            System.arraycopy(blockData, srcOff, result, resultOffset, copyLen)
            resultOffset += copyLen
            remaining -= copyLen
            srcCursor = copyEnd
        }

        return if (remaining == 0) result else null
    }

    private fun readIndexEntryAt(channel: FileChannel, blockIndex: Int): Int? {
        val offset = ZisoFormat.HEADER_SIZE.toLong() + 4L * blockIndex
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(offset)
        var got = 0
        while (got < 4) {
            val read = channel.read(buf)
            if (read < 0) return null
            got += read
        }
        buf.flip()
        return buf.int
    }
}
