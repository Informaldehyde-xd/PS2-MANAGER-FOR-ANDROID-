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
 * Front covers come from xlenore/ps2-covers on GitHub — a direct, verified,
 * single-file-per-game URL pattern (the exact source PCSX2's own built-in
 * Cover Downloader uses). This is fast: one direct request, short timeout,
 * no crawling or lookup needed.
 *
 * Background/Icon/Screenshot have no equally fast, verified source, so they
 * are manual-upload only (via the photo picker in the Cover Art preview) —
 * they are never auto-fetched, so the app never waits on them.
 */
class CoverArtFetcher(private val context: Context) {

    companion object {
        private const val PS2_COVERS_BASE =
            "https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val artDir = File(context.filesDir, "cover_art").apply { mkdirs() }

    /** Converts our normalized "SLUS_212.42" form into the "SLUS-21242" serial form other sites use. */
    private fun toSerialFormat(gameId: String): String {
        val prefix = gameId.takeWhile { it.isLetter() }
        val digits = gameId.dropWhile { it.isLetter() }.filter { it.isDigit() }
        return "$prefix-$digits"
    }

    /** Fetches art for a game. Only the cover auto-downloads; other types stay null (manual only). */
    suspend fun fetchAllArt(
        gameId: String,
        onProgress: (label: String, fileName: String, step: Int, total: Int) -> Unit = { _, _, _, _ -> }
    ): ArtSet = withContext(Dispatchers.IO) {
        onProgress("Cover", "${toSerialFormat(gameId)}.jpg", 1, 1)
        val cover = fetchArt(gameId, ArtType.COVER)
        ArtSet(cover = cover, background = null, icon = null, screenshot = null)
    }

    /** Kept for backward compatibility: fetches just the front cover. */
    suspend fun fetchCoverArt(gameId: String): String? = fetchArt(gameId, ArtType.COVER)

    suspend fun fetchArt(gameId: String, type: ArtType): String? = withContext(Dispatchers.IO) {
        val cacheKey = "$gameId${type.oplSuffix}"
        val cachedJpg = File(artDir, "$cacheKey.jpg")
        val cachedPng = File(artDir, "$cacheKey.png")
        if (cachedJpg.exists()) return@withContext cachedJpg.absolutePath
        if (cachedPng.exists()) return@withContext cachedPng.absolutePath

        if (type != ArtType.COVER) {
            // No fast, verified source for these — manual upload only.
            return@withContext null
        }

        val serial = toSerialFormat(gameId)
        downloadDirect("$PS2_COVERS_BASE$serial.jpg", cachedJpg)
    }

    private fun downloadDirect(url: String, cacheFile: File): String? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        cacheFile.writeBytes(bytes)
                        return cacheFile.absolutePath
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Copies a user-picked image in as the cover art, overriding whatever was auto-fetched. */
    suspend fun saveManualArt(gameId: String, type: ArtType, sourceBytes: ByteArray, ext: String): String =
        withContext(Dispatchers.IO) {
            val file = File(artDir, "$gameId${type.oplSuffix}.$ext")
            file.writeBytes(sourceBytes)
            file.absolutePath
        }
}
