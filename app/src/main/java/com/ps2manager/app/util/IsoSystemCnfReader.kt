package com.ps2manager.app.util

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

private const val SECTOR_SIZE = 2048L

/**
 * Most real-world PS2 ISO dumps are named after the game title, not the Game ID
 * (unlike files already renamed by OPL itself). To find the true ID, we read it
 * straight from SYSTEM.CNF inside the disc image, e.g.:
 *   BOOT2 = cdrom0:\SLUS_212.42;1
 * This mirrors what OPL/PCSX2 themselves do to identify a disc.
 */
object IsoSystemCnfReader {

    /** Returns the normalized Game ID found inside the ISO's SYSTEM.CNF, or null. */
    fun readGameId(context: Context, isoUri: Uri): String? {
        return try {
            context.contentResolver.openFileDescriptor(isoUri, "r")?.use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                    val channel = stream.channel
                    readGameIdFromChannel(channel) ?: readGameIdByBruteForce(channel)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fallback for discs whose filesystem layout doesn't match the strict ISO9660
     * assumptions above (hybrid/UDF discs, unusual authoring tools, etc). SYSTEM.CNF
     * always sits very early on a real PS2 disc, so this just scans the first few MB
     * of raw bytes for its distinctive "BOOT2 = cdrom0:\..." text directly.
     */
    private fun readGameIdByBruteForce(channel: FileChannel): String? {
        val scanSize = 4 * 1024 * 1024 // first 4MB is always enough on a real PS2 disc
        val bytes = readBytesAt(channel, 0L, scanSize) ?: return null
        val text = bytes.toString(Charsets.US_ASCII)
        return GameIdUtil.extractGameId(text)
    }

    private fun readGameIdFromChannel(channel: FileChannel): String? {
        // Primary Volume Descriptor lives at sector 16.
        val pvd = readSector(channel, 16) ?: return null
        if (pvd[1].toInt() != 'C'.code || pvd[2].toInt() != 'D'.code) {
            return null // not a standard ISO9660 image at this sector — let the brute-force fallback handle it
        }

        // Root directory record starts at byte 156 within the PVD, 34 bytes long.
        val rootRecord = pvd.copyOfRange(156, 156 + 34)
        val rootExtentLba = readUInt32LE(rootRecord, 2)
        val rootDataLength = readUInt32LE(rootRecord, 10)

        if (rootDataLength <= 0 || rootDataLength > 10_000_000L) return null // sanity check

        val rootDirBytes = readBytesAt(channel, rootExtentLba * SECTOR_SIZE, rootDataLength.toInt()) ?: return null

        val systemCnfRecord = findDirectoryEntry(rootDirBytes, "SYSTEM.CNF") ?: return null
        val fileExtentLba = readUInt32LE(systemCnfRecord, 2)
        val fileDataLength = readUInt32LE(systemCnfRecord, 10)
        if (fileDataLength <= 0 || fileDataLength > 65536L) return null

        val fileBytes = readBytesAt(channel, fileExtentLba * SECTOR_SIZE, fileDataLength.toInt()) ?: return null
        val text = fileBytes.toString(Charsets.US_ASCII)

        return GameIdUtil.extractGameId(text)
    }

    /** Scans a directory extent's raw bytes for an entry whose name starts with the given prefix. */
    private fun findDirectoryEntry(dirBytes: ByteArray, namePrefix: String): ByteArray? {
        var offset = 0
        while (offset < dirBytes.size) {
            val recordLen = dirBytes[offset].toInt() and 0xFF
            if (recordLen == 0) {
                // Padding to next sector boundary — advance there.
                val nextSectorOffset = ((offset / SECTOR_SIZE.toInt()) + 1) * SECTOR_SIZE.toInt()
                if (nextSectorOffset <= offset || nextSectorOffset >= dirBytes.size) break
                offset = nextSectorOffset
                continue
            }
            if (offset + recordLen > dirBytes.size) break

            val nameLen = dirBytes[offset + 32].toInt() and 0xFF
            if (offset + 33 + nameLen <= dirBytes.size) {
                val name = String(dirBytes, offset + 33, nameLen, Charsets.US_ASCII)
                if (name.uppercase().startsWith(namePrefix.uppercase())) {
                    return dirBytes.copyOfRange(offset, offset + recordLen)
                }
            }
            offset += recordLen
        }
        return null
    }

    private fun readSector(channel: FileChannel, sectorIndex: Long): ByteArray? =
        readBytesAt(channel, sectorIndex * SECTOR_SIZE, SECTOR_SIZE.toInt())

    private fun readBytesAt(channel: FileChannel, position: Long, length: Int): ByteArray? {
        return try {
            val buffer = ByteBuffer.allocate(length)
            channel.position(position)
            var totalRead = 0
            while (totalRead < length) {
                val read = channel.read(buffer)
                if (read < 0) break
                totalRead += read
            }
            if (totalRead <= 0) null else buffer.array()
        } catch (e: Exception) {
            null
        }
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        // ISO9660 stores both-endian values; we only need the little-endian half (first 4 bytes).
        val buffer = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN)
        return buffer.int.toLong() and 0xFFFFFFFFL
    }
}
