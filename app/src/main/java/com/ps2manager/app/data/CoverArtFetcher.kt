package com.ps2manager.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** The set of art types OPL itself displays per game. */
enum class ArtType(val oplSuffix: String, val defaultExt: String) {
    COVER("_COV", "jpg"),
    BACKGROUND("_BG", "jpg"),
    ICON("_ICO", "png"),
    SCREENSHOT("_SCR", "jpg")
}

/** Local paths to whichever art types were successfully found for one game. */
data class ArtSet(
    val cover: String? = null,
    val background: String? = null,
    val icon: String? = null,
    val screenshot: String? = null
) {
    fun pathFor(type: ArtType): String? = when (type) {
        ArtType.COVER -> cover
        ArtType.BACKGROUND -> background
        ArtType.ICON -> icon
        ArtType.SCREENSHOT -> screenshot
    }
}

/**
 * Cover art comes from the same archived database, organized by Game ID.
 * The exact file naming in the archive can vary, so we try a handful of
 * likely candidates per art type and use the first one that actually exists.
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

    /** Fetches every art type for a game; each field is null if that type wasn't found. */
    suspend fun fetchAllArt(gameId: String): ArtSet = withContext(Dispatchers.IO) {
        ArtSet(
            cover = fetchArt(gameId, ArtType.COVER),
            background = fetchArt(gameId, ArtType.BACKGROUND),
            icon = fetchArt(gameId, ArtType.ICON),
            screenshot = fetchArt(gameId, ArtType.SCREENSHOT)
        )
    }

    /** Kept for backward compatibility: fetches just the front cover. */
    suspend fun fetchCoverArt(gameId: String): String? = fetchArt(gameId, ArtType.COVER)

    suspend fun fetchArt(gameId: String, type: ArtType): String? = withContext(Dispatchers.IO) {
        val cacheKey = "$gameId${type.oplSuffix}"
        val cachedJpg = File(artDir, "$cacheKey.jpg")
        val cachedPng = File(artDir, "$cacheKey.png")
        if (cachedJpg.exists()) return@withContext cachedJpg.absolutePath
        if (cachedPng.exists()) return@withContext cachedPng.absolutePath

        val suffix = type.oplSuffix
        val candidates = if (type == ArtType.COVER) {
            listOf(
                "$gameId.jpg", "${gameId}${suffix}.jpg",
                "$gameId.png", "${gameId}${suffix}.png"
            )
        } else {
            listOf("${gameId}${suffix}.jpg", "${gameId}${suffix}.png")
        }

        for (name in candidates) {
            try {
                val request = Request.Builder().url(BASE_URL + name).build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val cacheFile = if (name.endsWith(".png")) cachedPng else cachedJpg
                            cacheFile.writeBytes(bytes)
                            return@withContext cacheFile.absolutePath
                        }
                    }
                }
            } catch (e: Exception) {
                // try next candidate
            }
        }
        null
    }

    /** Copies a user-picked image in as the cover art, overriding whatever was auto-fetched. */
    suspend fun saveManualArt(gameId: String, type: ArtType, sourceBytes: ByteArray, ext: String): String =
        withContext(Dispatchers.IO) {
            val file = File(artDir, "$gameId${type.oplSuffix}.$ext")
            file.writeBytes(sourceBytes)
            file.absolutePath
        }
}
