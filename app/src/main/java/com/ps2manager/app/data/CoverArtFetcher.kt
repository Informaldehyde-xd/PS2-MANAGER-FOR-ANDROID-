package com.ps2manager.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cover art comes from the same archived database, organized by Game ID.
 * The exact file naming in the archive can vary, so we try a handful of
 * likely candidates and use the first one that actually exists.
 */
class CoverArtFetcher(private val context: Context) {

    companion object {
        private const val BASE_URL =
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/PS2/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val artDir = File(context.filesDir, "cover_art").apply { mkdirs() }

    /** Returns a local file path to the downloaded cover, or null if none could be found. */
    suspend fun fetchCoverArt(gameId: String): String? = withContext(Dispatchers.IO) {
        val cached = File(artDir, "$gameId.jpg")
        if (cached.exists()) return@withContext cached.absolutePath

        val candidates = listOf(
            "$gameId.jpg",
            "${gameId}_COV.jpg",
            "$gameId.png",
            "${gameId}_COV.png",
            "${gameId.replace(".", "")}.jpg",
            "${gameId.replace("_", "-")}.jpg"
        )

        for (name in candidates) {
            try {
                val request = Request.Builder().url(BASE_URL + name).build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            cached.writeBytes(bytes)
                            return@withContext cached.absolutePath
                        }
                    }
                }
            } catch (e: Exception) {
                // try next candidate
            }
        }
        null
    }
}
