package com.ps2manager.app.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves a SAF document [Uri] served by the local "external storage" provider
 * (content://com.android.externalstorage.documents/...) back into a real
 * java.io.File path — exactly what most file managers do so they can perform a
 * genuine, instant filesystem rename instead of going through the SAF
 * DocumentsProvider's (often slow, sometimes unreliable) rename call, or a
 * full copy+delete, for a simple rename.
 *
 * Returns null for anything that isn't the local external storage provider, or
 * whose raw path can't be resolved (e.g. a cloud/other DocumentsProvider) —
 * callers should fall back to SAF in that case.
 */
object RawPathResolver {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Resolves a single-document Uri (from DocumentFile.fromSingleUri) to its raw File, if possible. */
    fun resolve(uri: Uri): File? {
        return try {
            if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
            val docId = DocumentsContract.getDocumentId(uri)
            docIdToFile(docId)
        } catch (e: Exception) {
            null
        }
    }

    private fun docIdToFile(docId: String): File? {
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volumeId, relativePath) = parts

        val baseDir = when (volumeId) {
            "primary" -> Environment.getExternalStorageDirectory()
            else -> File("/storage/$volumeId")
        }

        return if (relativePath.isBlank()) baseDir else File(baseDir, relativePath)
    }
}
