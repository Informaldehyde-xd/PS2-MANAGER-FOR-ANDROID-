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
 * CoverArtFetcher fetches game artwork directly from Luden02/psx-ps2-opl-art-database.
 */
class CoverArtFetcher(private val context: Context) {

    companion object {
        private const val COVER_WIDTH = 140
        private const val COVER_HEIGHT = 200

        private const val ART_DB_TREE_API =
            "https://api.github.com/repos/Luden02/psx-ps2-opl-art-database/git/trees/main?recursive=1"
        private const val ART_DB_RAW_BASE =
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/"

        private const val BACKUP_COVER_BASE =
            "https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/"
            
        private const val INDEX_CACHE_FILENAME = "opl_art_luden02_cache_v9.txt"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val artDir = File(context.filesDir, "cover_art").apply { mkdirs() }

    private var pathIndex: List<String> = emptyList()
    private var indexLoaded = false

    var lastError: String? = null
        private set

    /** Generates all common Game ID serial variations. */
    private fun getGameIdVariations(gameId: String): List<String> {
        val clean = gameId.uppercase().replace("[^A-Z0-9]".toRegex(), "")
        if (clean.length < 4) return listOf(gameId.uppercase())

        val prefix = clean.takeWhile { it.isLetter() }
        val digits = clean.dropWhile { it.isLetter() }

        if (prefix.isEmpty() || digits.isEmpty()) return listOf(gameId.uppercase())

        val formattedDigits = if (digits.length >= 5) {
            "${digits.substring(0, digits.length - 2)}.${digits.substring(digits.length - 2)}"
        } else digits

        return listOf(
            "${prefix}_$formattedDigits",    // OPL Standard: SCUS_974.81
            "${prefix}_$digits",             // No-dot: SCUS_97481
            "$prefix-$digits",               // Serial: SCUS-97481
            gameId.uppercase(),
            "$prefix$digits"                 // Flat: SCUS97481
        ).distinct()
    }

    /** Fast manual scan for "path":"..." entries in the raw JSON. */
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

    /** Safely loads the index into memory, caching to disk. */
    private suspend fun ensureIndexLoaded() {
        if (indexLoaded && pathIndex.isNotEmpty()) return
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.filesDir, INDEX_CACHE_FILENAME)

            if (cacheFile.exists() && cacheFile.length() > 0) {
                pathIndex = cacheFile.readText().lineSequence().filter { it.isNotBlank() }.toList()
                if (pathIndex.isNotEmpty()) {
                    indexLoaded = true
                    return@withContext
                }
            }

            var paths: List<String> = emptyList()
            val completed = withTimeoutOrNull(20_000) {
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

            if (paths.isNotEmpty()) {
                cacheFile.writeText(paths.joinToString("\n"))
                pathIndex = paths
            } else {
                pathIndex = emptyList()
            }
            indexLoaded = true
        }
    }

    /** Matches game ID variations and correct OPL suffixes against the index. */
    private fun findExactPath(gameId: String, type: ArtType): String? {
        val idVariations = getGameIdVariations(gameId)

        val suffixes = when (type) {
            ArtType.BACKGROUND -> listOf("_BG", "_BG_00", "_BG_01", "_BG_02", "_BG.1")
            ArtType.ICON -> listOf("_ICO", "_ICO.1")
            ArtType.COVER -> listOf("_COV", "_COV2", "_COV.1")
            ArtType.SCREENSHOT -> listOf("_SCR", "_SCR_00", "_SCR_01", "_SCR.1")
        }

        val extensions = listOf(".PNG", ".JPG", ".JPEG", ".BMP")

        val targetFilenames = HashSet<String>()
        for (id in idVariations) {
            for (suffix in suffixes) {
                for (ext in extensions) {
                    targetFilenames.add("$id$suffix$ext".uppercase())
                }
            }
        }

        return pathIndex.firstOrNull { path ->
            val filename = path.substringAfterLast('/').uppercase()
            targetFilenames.contains(filename)
        }
    }

    /** Fetches all four art types for a game. */
    suspend fun fetchAllArt(
        gameId: String,
        onProgress: (label: String, fileName: String, step: Int, total: Int) -> Unit = { _, _, _, _ -> }
    ): ArtSet = withContext(Dispatchers.IO) {
        ensureIndexLoaded()

        onProgress("Cover", "$gameId${ArtType.COVER.oplSuffix}.png", 1, 4)
        val cover = fetchArt(gameId, ArtType.COVER)

        onProgress("Icon", "$gameId${ArtType.ICON.oplSuffix}.png", 2, 4)
        val icon = fetchArt(gameId, ArtType.ICON)

        onProgress("Screenshot", "$gameId${ArtType.SCREENSHOT.oplSuffix}.png", 3, 4)
        val screenshot = fetchArt(gameId, ArtType.SCREENSHOT)

        onProgress("Background", "$gameId${ArtType.BACKGROUND.oplSuffix}.png", 4, 4)
        val background = fetchArt(gameId, ArtType.BACKGROUND)

        ArtSet(cover = cover, background = background, icon = icon, screenshot = screenshot)
    }

    suspend fun fetchCoverArt(gameId: String): String? = fetchArt(gameId, ArtType.COVER)

    suspend fun fetchArt(gameId: String, type: ArtType): String? = withContext(Dispatchers.IO) {
        val cached = File(artDir, "$gameId${type.oplSuffix}.png")
        if (cached.exists()) return@withContext cached.absolutePath

        ensureIndexLoaded()

        // 1. Fast in-memory lookup against the GitHub database tree index
        val matchedPath = findExactPath(gameId, type)
        if (matchedPath != null) {
            val url = (ART_DB_RAW_BASE + matchedPath).replace(" ", "%20")
            val bytes = downloadBytes(url)
            if (bytes != null) {
                return@withContext normalizeAndSave(bytes, cached, resizeForCover = type == ArtType.COVER)
            }
        }

        // 2. Direct Fallback matching both plain and numbered OPL suffixes
        val idVariations = getGameIdVariations(gameId)
        val suffixes = when (type) {
            ArtType.BACKGROUND -> listOf("_BG", "_BG_00", "_BG_01", "_BG.1")
            ArtType.ICON -> listOf("_ICO", "_ICO.1")
            ArtType.COVER -> listOf("_COV", "_COV2", "_COV.1")
            ArtType.SCREENSHOT -> listOf("_SCR", "_SCR_00", "_SCR")
        }
        val exts = listOf("png", "jpg", "jpeg")

        for (folderId in idVariations) {
            for (fileId in idVariations) {
                for (suffix in suffixes) {
                    for (ext in exts) {
                        val directUrl = "${ART_DB_RAW_BASE}PS2/${folderId}/${fileId}$suffix.$ext"
                        val bytes = downloadBytes(directUrl)
                        if (bytes != null) {
                            return@withContext normalizeAndSave(bytes, cached, resizeForCover = type == ArtType.COVER)
                        }
                    }
                }
            }
        }

        // 3. Fallback for front cover only from backup repo
        if (type == ArtType.COVER) {
            val serial = idVariations.firstOrNull { it.contains('-') } ?: gameId
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

    /** Decodes whatever format was downloaded and re-encodes it as a PNG file. */
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

    /** Copies a user-picked image in as art, normalized to PNG regardless of format. */
    suspend fun saveManualArt(gameId: String, type: ArtType, sourceBytes: ByteArray, ext: String): String? =
        withContext(Dispatchers.IO) {
            val file = File(artDir, "$gameId${type.oplSuffix}.png")
            normalizeAndSave(sourceBytes, file, resizeForCover = type == ArtType.COVER)
        }
}
