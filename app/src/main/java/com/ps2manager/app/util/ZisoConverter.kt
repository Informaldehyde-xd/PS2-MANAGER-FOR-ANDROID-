package com.ps2manager.app.util

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Sequential and random-access (de)compression helpers for the .zso format described
 * in [ZisoFormat]. All work happens against local java.io.File handles (SAF documents
 * are copied in/out by the caller), matching the rest of the app's conversion pattern.
 */
object ZisoConverter {

    /**
     * Compresses [source] (a plain PS2 ISO) into [dest] as a .zso image.
     * Uses a local RandomAccessFile so the index table (whose final contents are only
     * known once every block's compressed size is settled) can be patched in place.
     */
    fun compressIsoToZso(source: File, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val totalBytes = source.length()
        val blockSize = ZisoFormat.WRITE_BLOCK_SIZE
        val align = ZisoFormat.WRITE_ALIGN
        val numBlocks = ((totalBytes + blockSize - 1) / blockSize).toInt().coerceAtLeast(0)
        val alignUnit = 1L shl align

        val compressor = ZisoFormat.newFastCompressor()

        dest.delete()
        RandomAccessFile(dest, "rw").use { raf ->
            val indexTableBytes = 4L * (numBlocks + 1)
            var dataStart = ZisoFormat.HEADER_SIZE + indexTableBytes
            val rem0 = dataStart % alignUnit
            if (rem0 != 0L) dataStart += (alignUnit - rem0)

            raf.setLength(dataStart)
            var currentPos = dataStart

            source.inputStream().buffered(1 shl 20).use { input ->
                val raw = ByteArray(blockSize)
                var bytesCopied = 0L

                for (blockIndex in 0 until numBlocks) {
                    val toRead = minOf(blockSize.toLong(), totalBytes - bytesCopied).toInt()
                    var readSoFar = 0
                    while (readSoFar < toRead) {
                        val read = input.read(raw, readSoFar, toRead - readSoFar)
                        if (read <= 0) break
                        readSoFar += read
                    }

                    val compressed = if (readSoFar > 0) compressor.compress(raw, 0, readSoFar) else ByteArray(0)
                    val useCompressed = compressed.size < readSoFar
                    val bytesToWrite = if (useCompressed) compressed else raw.copyOf(readSoFar)

                    val entry = ZisoFormat.encodeIndexEntry(currentPos, align, useCompressed)
                    raf.seek(ZisoFormat.HEADER_SIZE.toLong() + 4L * blockIndex)
                    raf.write(ZisoFormat.writeIndexEntry(entry))

                    raf.seek(currentPos)
                    raf.write(bytesToWrite)
                    currentPos += bytesToWrite.size

                    val rem = currentPos % alignUnit
                    if (rem != 0L) {
                        val padLen = (alignUnit - rem).toInt()
                        raf.write(ByteArray(padLen))
                        currentPos += padLen
                    }

                    bytesCopied += readSoFar
                    onProgress(bytesCopied, totalBytes)
                }
            }

            // Sentinel final index entry marks the end of block data (used to size the last block).
            val sentinel = ZisoFormat.encodeIndexEntry(currentPos, align, compressed = true)
            raf.seek(ZisoFormat.HEADER_SIZE.toLong() + 4L * numBlocks)
            raf.write(ZisoFormat.writeIndexEntry(sentinel))

            raf.seek(0)
            raf.write(ZisoFormat.buildHeaderBytes(totalBytes, blockSize, version = 1, align = align))

            raf.setLength(currentPos)
        }
    }

    /**
     * Decompresses [source] (a .zso image) into [dest] as a plain PS2 ISO, reading the
     * whole file strictly forward (no seeking) so the same core logic also works
     * directly against a SAF [InputStream] via [decompressStream].
     */
    fun decompressZsoToIso(source: File, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        source.inputStream().buffered(1 shl 20).use { input ->
            dest.outputStream().buffered(1 shl 20).use { output ->
                decompressStream(input, output, onProgress)
            }
        }
    }

    /** Core forward-only .zso decompressor: header -> index table -> blocks (skipping any padding gaps). */
    fun decompressStream(input: InputStream, output: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val headerBytes = input.readNBytesCompat(ZisoFormat.HEADER_SIZE)
        val header = ZisoFormat.parseHeader(headerBytes)
            ?: throw IllegalArgumentException("Not a valid .zso file (bad header).")

        val numBlocks = header.numBlocks
        val indexBytes = input.readNBytesCompat(4 * (numBlocks + 1))
        if (indexBytes.size < 4 * (numBlocks + 1)) {
            throw IllegalArgumentException("Truncated .zso index table.")
        }

        var readerPos = (ZisoFormat.HEADER_SIZE + indexBytes.size).toLong()
        val decompressor = ZisoFormat.newFastDecompressor()

        var bytesWritten = 0L
        for (blockIndex in 0 until numBlocks) {
            val entry = ZisoFormat.readIndexEntry(indexBytes, blockIndex * 4)
            val nextEntry = ZisoFormat.readIndexEntry(indexBytes, (blockIndex + 1) * 4)

            val blockOffset = ZisoFormat.blockOffset(entry, header.align)
            val nextOffset = ZisoFormat.blockOffset(nextEntry, header.align)
            val compressedLen = (nextOffset - blockOffset).toInt()
            val compressed = ZisoFormat.isBlockCompressed(entry)

            if (blockOffset > readerPos) {
                skipFully(input, blockOffset - readerPos)
                readerPos = blockOffset
            }

            val decompressedLen = minOf(header.blockSize.toLong(), header.totalBytes - bytesWritten).toInt()
            if (compressedLen < 0 || decompressedLen < 0) {
                throw IllegalArgumentException("Corrupt .zso index at block $blockIndex.")
            }

            val raw = input.readNBytesCompat(compressedLen)
            readerPos += raw.size
            if (raw.size < compressedLen) {
                throw IllegalArgumentException("Truncated .zso data at block $blockIndex.")
            }

            val outBlock = if (compressed) {
                val dst = ByteArray(decompressedLen)
                decompressor.decompress(raw, 0, dst, 0, decompressedLen)
                dst
            } else {
                if (raw.size >= decompressedLen) raw.copyOf(decompressedLen) else raw
            }

            output.write(outBlock)
            bytesWritten += outBlock.size
            onProgress(bytesWritten, header.totalBytes)
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(minOf(remaining, (1 shl 16).toLong()).toInt().coerceAtLeast(1))
        while (remaining > 0) {
            val toSkip = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, toSkip)
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var total = 0
        while (total < n) {
            val read = this.read(buf, total, n - total)
            if (read <= 0) break
            total += read
        }
        return if (total == n) buf else buf.copyOf(total)
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

    /** Reads and validates just the 24-byte header from an open [channel], for callers that only need it. */
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
}
