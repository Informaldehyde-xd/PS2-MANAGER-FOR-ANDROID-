package com.ps2manager.app.util

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

private const val SECTOR_SIZE = 2048L

object IsoSystemCnfReader {

    fun readGameId(context: Context, isoUri: Uri): String? {
        return try {
            context.contentResolver.openFileDescriptor(isoUri, "r")?.use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                    val channel = stream.channel
                    readGameIdFromChannel(channel)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readGameIdFromChannel(channel: FileChannel): String? {
        val pvd = readSector(channel, 16) ?: return null
        if (pvd[1].toInt() != 'C'.code || pvd[2].toInt() != 'D'.code) {
        }

        val rootRecord = pvd.copyOfRange(156, 156 + 34)
        val rootExtentLba = readUInt32LE(rootRecord, 2)
        val rootDataLength = readUInt32LE(rootRecord, 10)

        if (rootDataLength <= 0 || rootDataLength > 10_000_000L) return null

        val rootDirBytes = readBytesAt(channel, rootExtentLba * SECTOR_SIZE, rootDataLength.toInt()) ?: return null

        val systemCnfRecord = findDirectoryEntry(rootDirBytes, "SYSTEM.CNF") ?: return null
        val fileExtentLba = readUInt32LE(systemCnfRecord, 2)
        val fileDataLength = readUInt32LE(systemCnfRecord, 10)
        if (fileDataLength <= 0 || fileDataLength > 65536L) return null

        val fileBytes = readBytesAt(channel, fileExtentLba * SECTOR_SIZE, fileDataLength.toInt()) ?: return null
        val text = fileBytes.toString(Charsets.US_ASCII)

        return GameIdUtil.extractGameId(text)
    }

    private fun findDirectoryEntry(dirBytes: ByteArray, namePrefix: String): ByteArray? {
        var offset = 0
        while (offset < dirBytes.size) {
            val recordLen = dirBytes[offset].toInt() and 0xFF
            if (recordLen == 0) {
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
        val buffer = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN)
        return buffer.int.toLong() and 0xFFFFFFFFL
    }
}
