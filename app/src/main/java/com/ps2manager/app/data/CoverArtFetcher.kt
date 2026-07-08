package com.ps2manager.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Cover art comes from two sources:
 *  1. Front covers: xlenore/ps2-covers on GitHub — a direct, verified, single-file-per-game
 *     URL pattern (this is the exact source PCSX2's own built-in Cover Downloader uses).
 *  2. Background/Icon/Screenshot: the archived OPL Manager GameArt Database, searched via
 *     GitHub's real file listing (best-effort — coverage is less complete for these types).
 */
class CoverArtFetcher(private val context: Context) {

    companion object {
        private const val PS2_COVERS_BASE =
            "https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/"
        private const val REPO_TREE_API =
            "https://api.github.com/repos/Luden02/psx-ps2-opl-art-database/git/trees/main?recursive=1"
        private const val RAW_BASE =
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/"
        private const val INDEX_CACHE_FILENAME = "art_index_cache.txt"
        private val PATH_REGEX = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val artDir = File(context.filesDir, "cover_art").apply { mkdirs() }

    private var pathIndex: List<String> = emptyList()
    private var indexLoaded = false

    var lastError: String? = null
        private set

    /** Number of files found in the archive's listing — 0 means it couldn't be reached. */
    val indexedFileCount: Int get() = pathIndex.size

    /** Converts our normalized "SLUS_212.42" form into the "SLUS-21242" serial form other sites use. */
    private fun toSerialFormat(gameId: String): String {
        val prefix = gameId.takeWhile { it.isLetter() }
        val digits = gameId.dropWhile { it.isLetter() }.filter { it.isDigit() }
        return "$prefix-$digits"
    }

    private suspend fun ensureIndexLoaded() {
        if (indexLoaded) return
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.filesDir, INDEX_CACHE_FILENAME)
            var text = ""
            var succeeded = false

            val completed = withTimeoutOrNull(30_000) {
                try {
                    val request = Request.Builder()
                        .url(REPO_TREE_API)
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
                            lastError = "GitHub API returned ${resp.code}"
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message
                }
            }
            if (completed == null) {
                lastError = "Timed out reaching the art database (connection may be too slow)."
            }

            val paths = if (succeeded) {
                PATH_REGEX.findAll(text).map { it.groupValues[1] }.toList()
            } else emptyList()

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

    /** Fetches every art type for a game; each field is null if that type wasn't found. */
    suspend fun fetchAllArt(
        gameId: String,
        onProgress: (label: String, fileName: String, step: Int, total: Int) -> Unit = { _, _, _, _ -> }
    ): ArtSet = withContext(Dispatchers.IO) {
        val types = ArtType.entries.toList()
        val results = mutableMapOf<ArtType, String?>()
        types.forEachIndexed { index, type ->
            val plannedFile = if (type == ArtType.COVER) {
                "${toSerialFormat(gameId)}.jpg"
            } else {
                "$gameId${type.oplSuffix}.${type.defaultExt}"
            }
            onProgress(type.name.lowercase().replaceFirstChar { it.uppercase() }, plannedFile, index + 1, types.size)
            results[type] = fetchArt(gameId, type)
        }
        ArtSet(
            cover = results[ArtType.COVER],
            background = results[ArtType.BACKGROUND],
            icon = results[ArtType.ICON],
            screenshot = results[ArtType.SCREENSHOT]
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

        if (type == ArtType.COVER) {
            // Primary, verified source: direct per-serial URL, no lookup needed.
            val serial = toSerialFormat(gameId)
            val direct = downloadDirect("$PS2_COVERS_BASE$serial.jpg", cachedJpg)
            if (direct != null) return@withContext direct
        }

        // Fallback / other art types: search the archived database's real file listing.
        ensureIndexLoaded()
        val matchedPath = findMatchingPath(gameId, type) ?: return@withContext null
        val isPng = matchedPath.endsWith(".png", ignoreCase = true)
        val cacheFile = if (isPng) cachedPng else cachedJpg
        downloadDirect(RAW_BASE + matchedPath, cacheFile)
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

    /** Searches the real file listing for the best match to this Game ID + art type. */
    private fun findMatchingPath(gameId: String, type: ArtType): String? {
        val idNorm = gameId.uppercase()
        val idNoDot = idNorm.replace(".", "")
        val idNoUnderscoreDot = idNorm.replace("_", "").replace(".", "")

        fun filenameOf(path: String) = path.substringAfterLast('/').uppercase()

        val candidates = pathIndex.filter { path ->
            val fname = filenameOf(path)
            fname.contains(idNorm) || fname.contains(idNoDot) || fname.contains(idNoUnderscoreDot)
        }
        if (candidates.isEmpty()) return null

        val suffixUpper = type.oplSuffix.uppercase()
        candidates.firstOrNull { filenameOf(it).contains(suffixUpper) }?.let { return it }

        if (type == ArtType.COVER) {
            candidates.firstOrNull { path ->
                val f = filenameOf(path)
                ArtType.entries.none { f.contains(it.oplSuffix.uppercase()) }
            }?.let { return it }
        }
        return null
    }

    /** Copies a user-picked image in as the cover art, overriding whatever was auto-fetched. */
    suspend fun saveManualArt(gameId: String, type: ArtType, sourceBytes: ByteArray, ext: String): String =
        withContext(Dispatchers.IO) {
            val file = File(artDir, "$gameId${type.oplSuffix}.$ext")
            file.writeBytes(sourceBytes)
            file.absolutePath
        }
}
