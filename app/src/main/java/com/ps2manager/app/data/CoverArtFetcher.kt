package com.ps2manager.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** The set of art types OPL itself displays per game. */
enum class ArtType(val oplSuffix: String, val defaultExt: String) {
    COVER("_COV", "png"),
    BACKGROUND("_BG", "png"),
    ICON("_ICO", "png"),
    SCREENSHOT("_SCR", "png")
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
 * Art comes primarily from Luden02/psx-ps2-opl-art-database — a complete,
 * verified dump of OPL Manager's own GameArt Database (confirmed via the
 * same author's own OrbitOPL Toolbox app, which uses this exact repo and
 * documents its GAMEID_COV / _ICO / _SCR / _BG naming convention, already
 * stored as PNG). This gives us ALL four art types from one source, not
 * just the cover.
 *
 * IMPORTANT — real OPL (rev 2159+, Oct 2024 onward) dropped JPG/BMP support
 * entirely and only accepts PNG, lowercase extension. Every image this class
 * produces is guaranteed to be a real PNG regardless of what format the
 * source actually returned: after downloading (or receiving a manually
 * user-picked image), it's decoded and re-encoded through Android's Bitmap
 * PNG encoder before ever being cached or written to the drive. This is a
 * safety net, not just an assumption — if the primary source or a future
 * fallback ever serves a JPG, it still comes out the other end as a real,
 * OPL-compatible PNG.
 */
class CoverArtFetcher(private val context: Context) {

    companion object {
        // OPL's required COV list-icon dimensions; BG reuses this same image.
        private const val COVER_WIDTH = 120
        private const val COVER_HEIGHT = 400

        private const val ART_DB_TREE_API =
            "https://api.github.com/repos/Luden02/psx-ps2-opl-art-database/git/trees/main?recursive=1"
        private const val ART_DB_RAW_BASE =
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/"
        // Backup only, used if the primary database has no entry for a given
        // game's cover — still gets converted to PNG before being saved.
        private const val BACKUP_COVER_BASE =
            "https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/"
        private const val INDEX_CACHE_FILENAME = "ps2_art_index_cache.txt"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val artDir = File(context.filesDir, "cover_art").apply { mkdirs() }

    private var pathIndex: List<String> = emptyList()
    private var indexLoaded = false

    var lastError: String? = null
        private set

    /** Converts our normalized "SLUS_212.42" form into the "SLUS-21242" serial form other sites use. */
    /** Converts our normalized "SLUS_212.42" form into the "SLUS-21242" serial form other sites use. */
    private fun toSerialFormat(gameId: String): String {
        val prefix = gameId.takeWhile { it.isLetter() }
        val digits = gameId.dropWhile { it.isLetter() }.filter { it.isDigit() }
        return "$prefix-$digits"
    }

    /**
     * Fast manual scan for "path":"..." entries in the raw JSON — much faster
     * and more predictable than a regex findAll() over a potentially huge
     * (multi-megabyte) response, which was the real bottleneck causing
     * indefinite-feeling hangs on some devices/connections.
     */
    private fun extractPathsFast(json: String): List<String> {
        val paths = mutableListOf<String>()
        val marker = "\"path\":\""
        var idx = json.indexOf(marker)
        while (idx != -1) {
            val start = idx + marker.length
            val end = json.indexOf('"', start)
            if (end == -1) break
            paths.add(json.substring(start, end))
            idx = json.indexOf(marker, end)
        }
        return paths
    }

    private suspend fun ensureIndexLoaded() {
        if (indexLoaded) return
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.filesDir, INDEX_CACHE_FILENAME)
            var paths: List<String> = emptyList()

            // Hard cap on the ENTIRE operation — both the network fetch and the
            // text parsing that follows it. A previous version only bounded the
            // network call itself, but this repo's file listing can be a very
            // large response, and parsing that huge string with regex afterward
            // had no timeout at all — that's what caused indefinite hangs on
            // slower connections/devices, not just a slow download.
            val completed = withTimeoutOrNull(30_000) {
                var text = ""
                var succeeded = false
                try {
                    val request = Request.Builder()
                        .url(ART_DB_TREE_API)
                        .addHeader("User-Agent", "PS2Manager-Android-App")
                        .addHeader("Accept", "application/vnd.github+json")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                text = body
                                succeeded = true
                            }
                        } else {
                            lastError = "Art database API returned ${resp.code}"
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message
                }

                if (succeeded) {
                    paths = extractPathsFast(text)
                }
            }

            if (completed == null) {
                lastError = "Timed out reaching/parsing the art database (connection may be too slow)."
            }

            if (paths.isNotEmpty()) {
                cacheFile.writeText(paths.joinToString("\n"))
                pathIndex = paths
            } else if (cacheFile.exists()) {
                pathIndex = cacheFile.readText().lineSequence().filter { it.isNotBlank() }.toList()
            } else {
                pathIndex = emptyList()
                lastError = lastError ?: "Could not reach the art database."
            }
            indexLoaded = true
        }
    }

    /** Exact (case-insensitive) match against the real file listing: "{gameId}_{SUFFIX}.png". */
    private fun findExactPath(gameId: String, type: ArtType): String? {
        val expectedName = "$gameId${type.oplSuffix}.png".uppercase()
        return pathIndex.firstOrNull { it.substringAfterLast('/').uppercase() == expectedName }
    }

    /** Fetches all four art types for a game from the comprehensive database. */
    suspend fun fetchAllArt(
        gameId: String,
        onProgress: (label: String, fileName: String, step: Int, total: Int) -> Unit = { _, _, _, _ -> }
    ): ArtSet = withContext(Dispatchers.IO) {
        ensureIndexLoaded()

        onProgress("Cover", "$gameId${ArtType.COVER.oplSuffix}.png", 1, 3)
        val cover = fetchArt(gameId, ArtType.COVER)

        onProgress("Icon", "$gameId${ArtType.ICON.oplSuffix}.png", 2, 3)
        val icon = fetchArt(gameId, ArtType.ICON)

        onProgress("Screenshot", "$gameId${ArtType.SCREENSHOT.oplSuffix}.png", 3, 3)
        val screenshot = fetchArt(gameId, ArtType.SCREENSHOT)

        // Background is intentionally the same 120x400 cover image, not a
        // separate fetch — copy the cover file to the background slot.
        val background = cover?.let { copyAsBackground(gameId, it) }

        ArtSet(cover = cover, background = background, icon = icon, screenshot = screenshot)
    }

    /** Copies the already-normalized cover image into the background cache slot too. */
    private fun copyAsBackground(gameId: String, coverPath: String): String? {
        return try {
            val dest = File(artDir, "$gameId${ArtType.BACKGROUND.oplSuffix}.png")
            File(coverPath).copyTo(dest, overwrite = true)
            dest.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Kept for backward compatibility: fetches just the front cover. */
    suspend fun fetchCoverArt(gameId: String): String? = fetchArt(gameId, ArtType.COVER)

    suspend fun fetchArt(gameId: String, type: ArtType): String? = withContext(Dispatchers.IO) {
        val cached = File(artDir, "$gameId${type.oplSuffix}.png")
        if (cached.exists()) return@withContext cached.absolutePath

        ensureIndexLoaded()
        val matchedPath = findExactPath(gameId, type)
        if (matchedPath != null) {
            val bytes = downloadBytes(ART_DB_RAW_BASE + matchedPath)
            if (bytes != null) {
                return@withContext normalizeAndSave(bytes, cached, resizeForCover = type == ArtType.COVER)
            }
        }

        // Fallback: only for the front cover, from a different (JPG) source —
        // still normalized to PNG (and resized) before saving.
        if (type == ArtType.COVER) {
            val serial = toSerialFormat(gameId)
            val bytes = downloadBytes("$BACKUP_COVER_BASE$serial.jpg")
            if (bytes != null) {
                return@withContext normalizeAndSave(bytes, cached, resizeForCover = true)
            }
        }

        null
    }

    private fun downloadBytes(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) bytes else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes whatever format was downloaded (or manually picked) and
     * re-encodes it as a real PNG before saving — guarantees OPL
     * compatibility regardless of the source format. Cover art is resized to
     * exactly 120x400, OPL's required list-icon dimensions (stretched to
     * fit, since cover art is rarely authored at that narrow aspect ratio
     * natively).
     */
    private fun normalizeAndSave(sourceBytes: ByteArray, destFile: File, resizeForCover: Boolean = false): String? {
        var bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size) ?: return null

        if (resizeForCover && (bitmap.width != COVER_WIDTH || bitmap.height != COVER_HEIGHT)) {
            val resized = Bitmap.createScaledBitmap(bitmap, COVER_WIDTH, COVER_HEIGHT, true)
            if (resized !== bitmap) bitmap.recycle()
            bitmap = resized
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        destFile.writeBytes(out.toByteArray())
        return destFile.absolutePath
    }

    /** Copies a user-picked image in as art, normalized to PNG regardless of the picked format. */
    suspend fun saveManualArt(gameId: String, type: ArtType, sourceBytes: ByteArray, ext: String): String? =
        withContext(Dispatchers.IO) {
            val file = File(artDir, "$gameId${type.oplSuffix}.png")
            val path = normalizeAndSave(sourceBytes, file, resizeForCover = type == ArtType.COVER)
            // Keep the background in sync if the user manually replaces the cover.
            if (type == ArtType.COVER && path != null) copyAsBackground(gameId, path)
            path
        }
}
