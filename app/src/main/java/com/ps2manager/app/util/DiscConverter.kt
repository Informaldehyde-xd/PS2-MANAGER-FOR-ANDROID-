package com.ps2manager.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Factory
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ConversionType {
    BIN_TO_ISO,
    ISO_TO_ZSO,
    ZSO_TO_ISO,
    REPAIR_ZSO_HEADER
}

object DiscConverter {

    private const val DEFAULT_BLOCK_SIZE = 2048
    private const val BIN_SECTOR_SIZE = 2352
    private const val HEADER_SIZE = 24
    // Matches ziso.py's COMPRESS_THREHOLD: if compression saves less than 5%, store the block raw
    private const val COMPRESS_THRESHOLD_PERCENT = 95
    private val PAD_BYTE: Byte = 'X'.code.toByte() // matches ziso.py DEFAULT_PADDING
    private val ZSO_MAGIC = byteArrayOf('Z'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte())
    // Pure-Java LZ4 implementation - no native libs to bundle, works out of the box on Android/CI
    private val lz4Factory = LZ4Factory.safeInstance()

    fun getFileName(context: Context, uri: Uri): String {
        var name = "unknown_file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    fun deriveOutputName(inputName: String, mode: ConversionType): String {
        val baseName = inputName.substringBeforeLast('.')
        return when (mode) {
            ConversionType.BIN_TO_ISO -> "$baseName.iso"
            ConversionType.ISO_TO_ZSO -> "$baseName.zso"
            ConversionType.ZSO_TO_ISO -> "$baseName.iso"
            ConversionType.REPAIR_ZSO_HEADER -> "$baseName.zso"
        }
    }

    suspend fun binToIso(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        onProgress: (Float) -> Unit
    ) {
        val contentResolver = context.contentResolver
        val fileDescriptor = contentResolver.openFileDescriptor(inputUri, "r") ?: return
        val totalSize = fileDescriptor.statSize
        fileDescriptor.close()

        val sectors = totalSize / BIN_SECTOR_SIZE
        var processedSectors = 0L

        contentResolver.openInputStream(inputUri)?.buffered(65536)?.use { rawInput ->
            val input = DataInputStream(rawInput)
            contentResolver.openOutputStream(outputUri)?.buffered(65536)?.use { output ->
                val buffer = ByteArray(BIN_SECTOR_SIZE)

                try {
                    while (true) {
                        input.readFully(buffer)

                        val mode = buffer[15].toInt()
                        val offset = if (mode == 2) 24 else 16
                        output.write(buffer, offset, DEFAULT_BLOCK_SIZE)

                        processedSectors++
                        if (sectors > 0 && processedSectors % 1000L == 0L) {
                            onProgress(processedSectors.toFloat() / sectors.toFloat())
                        }
                    }
                } catch (e: EOFException) {
                    // Reached the end of the file safely
                }
            }
        }
        onProgress(1f)
    }

    /**
     * ISO -> ZSO, ported to match ps2homebrew/Open-PS2-Loader's pc/ziso.py exactly:
     * LZ4 block compression, floor-divided block count, and OPL's align/plain-flag scheme.
     * compressionLevel: 1 = fast LZ4, >1 = LZ4 HC (mirrors ziso.py's -c flag).
     */
    suspend fun isoToZso(
    context: Context,
    inputUri: Uri,
    outputUri: Uri,
    compressionLevel: Int = 17,
    onProgress: (Float) -> Unit
) {
    val contentResolver = context.contentResolver
    val fileDescriptor = contentResolver.openFileDescriptor(inputUri, "r") ?: return
    val totalBytes = fileDescriptor.statSize
    fileDescriptor.close()

    val indexShift = (totalBytes / 0x80000000L).toInt()
    val align = 1 shl indexShift
    val totalBlocks = (totalBytes / DEFAULT_BLOCK_SIZE).toInt()
    val indexTable = IntArray(totalBlocks + 1)

    val compressor: LZ4Compressor =
        if (compressionLevel > 1) lz4Factory.highCompressor(compressionLevel.coerceIn(1, 17))
        else lz4Factory.fastCompressor()

    // Compress in parallel across all available cores, write sequentially after each batch
    val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val batchSize = parallelism * 32

    var currentOffset = HEADER_SIZE.toLong() + (totalBlocks + 1) * 4L

    val outFd = contentResolver.openFileDescriptor(outputUri, "rw")
        ?: throw IllegalStateException("Cannot open output stream in read-write mode")

    outFd.use { pfd ->
        FileOutputStream(pfd.fileDescriptor).use { output ->
            output.write(ByteArray(currentOffset.toInt()))

            contentResolver.openInputStream(inputUri)?.buffered(65536)?.use { input ->
                var blocksProcessed = 0

                while (blocksProcessed < totalBlocks) {
                    val currentBatchSize = minOf(batchSize, totalBlocks - blocksProcessed)

                    // Sequential read
                    val rawBlocks = Array(currentBatchSize) { ByteArray(DEFAULT_BLOCK_SIZE) }
                    for (b in 0 until currentBatchSize) {
                        var bytesRead = 0
                        while (bytesRead < DEFAULT_BLOCK_SIZE) {
                            val r = input.read(rawBlocks[b], bytesRead, DEFAULT_BLOCK_SIZE - bytesRead)
                            if (r == -1) break
                            bytesRead += r
                        }
                        if (bytesRead < DEFAULT_BLOCK_SIZE) {
                            rawBlocks[b].fill(0, bytesRead, DEFAULT_BLOCK_SIZE)
                        }
                    }

                    // Parallel LZ4 HC compression - this is the step that actually benefits from cores
                    val compressedBlocks: List<ByteArray> = coroutineScope {
                        rawBlocks.map { raw ->
                            async(Dispatchers.Default) {
                                val destBuf = ByteArray(compressor.maxCompressedLength(DEFAULT_BLOCK_SIZE))
                                val size = compressor.compress(raw, 0, DEFAULT_BLOCK_SIZE, destBuf, 0, destBuf.size)
                                if (size * 100 / DEFAULT_BLOCK_SIZE >= COMPRESS_THRESHOLD_PERCENT) raw
                                else destBuf.copyOf(size)
                            }
                        }.awaitAll()
                    }

                    // Sequential write - preserves order so offsets/index table stay correct
                    for (b in 0 until currentBatchSize) {
                        val i = blocksProcessed + b
                        val rem = (currentOffset % align).toInt()
                        if (rem != 0) {
                            val pad = align - rem
                            output.write(ByteArray(pad) { PAD_BYTE })
                            currentOffset += pad
                        }
                        indexTable[i] = (currentOffset shr indexShift).toInt()

                        val data = compressedBlocks[b]
                        val storedRaw = data === rawBlocks[b]
                        output.write(data)
                        currentOffset += data.size
                        if (storedRaw) {
                            indexTable[i] = indexTable[i] or 0x80000000.toInt()
                        }
                    }

                    blocksProcessed += currentBatchSize
                    onProgress(blocksProcessed.toFloat() / totalBlocks.toFloat())
                }
            }

            indexTable[totalBlocks] = (currentOffset shr indexShift).toInt()

            val channel = output.channel
            channel.position(0)

            val header = ByteBuffer.allocate(HEADER_SIZE).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put(ZSO_MAGIC)
                putInt(HEADER_SIZE)
                putLong(totalBytes)
                putInt(DEFAULT_BLOCK_SIZE)
                put(1.toByte())
                put(indexShift.toByte())
                putShort(0)
            }.array()
            channel.write(ByteBuffer.wrap(header))

            val indexBytes = ByteBuffer.allocate((totalBlocks + 1) * 4).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                indexTable.forEach { putInt(it) }
            }.array()
            channel.write(ByteBuffer.wrap(indexBytes))
        }
    }
    onProgress(1f)
    }
    /**
     * One-time fix for .zso files produced before the magic-number bug was fixed.
     * Only the first 4 header bytes were wrong - the LZ4 payload is untouched.
     */
    suspend fun repairZsoHeader(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        onProgress: (Float) -> Unit
    ) {
        val contentResolver = context.contentResolver
        val fileDescriptor = contentResolver.openFileDescriptor(inputUri, "r") ?: return
        val totalBytes = fileDescriptor.statSize
        fileDescriptor.close()

        val oldBuggyMagic = byteArrayOf('Z'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 0x01)

        contentResolver.openInputStream(inputUri)?.buffered(65536)?.use { input ->
            val header = ByteArray(4)
            if (input.read(header) < 4) {
                throw IllegalArgumentException("File is too small to be a ZSO image.")
            }

            if (!header.contentEquals(oldBuggyMagic) && !header.contentEquals(ZSO_MAGIC)) {
                throw IllegalArgumentException("This doesn't look like a ZSO file from this app's earlier build.")
            }

            contentResolver.openOutputStream(outputUri)?.buffered(65536)?.use { output ->
                output.write(ZSO_MAGIC) // always write the corrected magic
                var copied = 4L
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (totalBytes > 0) {
                        onProgress((copied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
        }
        onProgress(1f)
    }

    /**
     * ZSO -> ISO, ported to match ziso.py's decompress_zso() exactly.
     */
    suspend fun zsoToIso(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        onProgress: (Float) -> Unit
    ) {
        val contentResolver = context.contentResolver
        val decompressor = lz4Factory.safeDecompressor()

        contentResolver.openInputStream(inputUri)?.buffered(65536)?.use { rawInput ->
            val input = DataInputStream(rawInput)
            val headerBuffer = ByteArray(HEADER_SIZE)
            input.readFully(headerBuffer)

            val header = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            header.get(magic)
            if (!magic.contentEquals(ZSO_MAGIC)) {
                throw IllegalArgumentException("Invalid ZSO file header.")
            }

            val headerSize = header.getInt()
            val totalBytes = header.getLong()
            val blockSize = header.getInt()
            val version = header.get()
            val indexShift = header.get().toInt()
            header.getShort()

            if (headerSize != HEADER_SIZE || version.toInt() > 1) {
                throw IllegalArgumentException("Unsupported ZSO version or header size.")
            }

            val totalBlocks = (totalBytes / blockSize).toInt()

            val indexBytes = ByteArray((totalBlocks + 1) * 4)
            input.readFully(indexBytes)
            val indexTable = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).run {
                IntArray(totalBlocks + 1) { getInt() }
            }

            var currentReadPos = HEADER_SIZE.toLong() + indexBytes.size

            contentResolver.openOutputStream(outputUri)?.buffered(65536)?.use { output ->
                val decompressed = ByteArray(blockSize)

                for (i in 0 until totalBlocks) {
                    val rawIndex = indexTable[i]
                    val isUncompressed = (rawIndex and 0x80000000.toInt()) != 0
                    val index = rawIndex and 0x7FFFFFFF
                    val offset = index.toLong() shl indexShift

                    // Plain blocks always read exactly blockSize; compressed blocks use the index diff
                    val readSize: Int = if (isUncompressed) {
                        blockSize
                    } else {
                        val index2 = indexTable[i + 1] and 0x7FFFFFFF
                        if (i == totalBlocks - 1) {
                            (totalBytes - offset).toInt()
                        } else {
                            ((index2 - index).toLong() shl indexShift).toInt()
                        }
                    }

                    if (offset > currentReadPos) {
                        var skipAmt = (offset - currentReadPos).toInt()
                        while (skipAmt > 0) {
                            val s = input.skipBytes(skipAmt)
                            if (s <= 0) break
                            skipAmt -= s
                        }
                        currentReadPos = offset
                    }

                    val blockData = ByteArray(readSize)
                    input.readFully(blockData)
                    currentReadPos += readSize

                    if (isUncompressed) {
                        output.write(blockData)
                    } else {
                        val decompressedSize = try {
                            decompressor.decompress(blockData, 0, blockData.size, decompressed, 0, blockSize)
                        } catch (e: Exception) {
                            throw RuntimeException("LZ4 decompression failed at block $i: ${e.message}", e)
                        }
                        output.write(decompressed, 0, decompressedSize)
                    }

                    if (i % 1000 == 0 || i == totalBlocks - 1) {
                        onProgress((i + 1).toFloat() / totalBlocks.toFloat())
                    }
                }
            }
        }
        onProgress(1f)
    }
}
