import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object CoverArtFetcher {

    private const val REPO_TREE_API = "https://api.github.com/repos/Luden02/psx-ps2-opl-art-database/git/trees/main?recursive=1"
    private const val RAW_BASE_URL = "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/"

    @Volatile
    private var pathIndex: List<String>? = null

    /**
     * Normalizes Game IDs like "SLUS-20001" or "SLUS20001" into standard OPL format "SLUS_200.01".
     */
    fun normalizeGameId(rawId: String): String {
        val clean = rawId.uppercase().replace("[^A-Z0-9]".toRegex(), "")
        return if (clean.length == 9) {
            "${clean.substring(0, 4)}_${clean.substring(4, 7)}.${clean.substring(7)}"
        } else {
            rawId.uppercase()
        }
    }

    /**
     * Loads the repository tree index into memory and caches it locally.
     */
    suspend fun ensureIndexLoaded(cacheDir: File): Boolean = withContext(Dispatchers.IO) {
        if (pathIndex != null) return@withContext true

        val cacheFile = File(cacheDir, "opl_art_repo_tree.txt")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            pathIndex = cacheFile.readLines()
            return@withContext true
        }

        try {
            val url = URL(REPO_TREE_API)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "OPL-Art-Fetcher-App")
            }

            if (connection.responseCode == 200) {
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val matcher = Pattern.compile("\"path\":\\s*\"([^\"]+)\"").matcher(json)
                val paths = mutableListOf<String>()

                while (matcher.find()) {
                    paths.add(matcher.group(1))
                }

                if (paths.isNotEmpty()) {
                    cacheFile.writeText(paths.joinToString("\n"))
                    pathIndex = paths
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext false
    }

    /**
     * Fetches raw bytes for artwork. Tries the indexed path first, then falls back to direct raw URLs.
     */
    suspend fun fetchArtBytes(
        gameId: String,
        artSuffix: String, // e.g., "_COV", "_ICO", "_BG", "_SCR"
        cacheDir: File
    ): ByteArray? = withContext(Dispatchers.IO) {
        val normalizedId = normalizeGameId(gameId)
        ensureIndexLoaded(cacheDir)

        // 1. Try matching against the indexed Git Tree
        val matchedPath = pathIndex?.firstOrNull { path ->
            val fileName = path.substringAfterLast("/")
            fileName.equals("${normalizedId}${artSuffix}.png", ignoreCase = true) ||
            fileName.equals("${normalizedId}${artSuffix}.jpg", ignoreCase = true)
        }

        if (matchedPath != null) {
            val downloadUrl = RAW_BASE_URL + matchedPath
            val bytes = downloadBytes(downloadUrl)
            if (bytes != null) return@withContext bytes
        }

        // 2. Direct Fallback if GitHub API failed or file was omitted from tree
        val fallbackPaths = listOf(
            "PS2/${normalizedId}${artSuffix}.png",
            "PS1/${normalizedId}${artSuffix}.png",
            "${normalizedId}${artSuffix}.png"
        )

        for (relativePath in fallbackPaths) {
            val bytes = downloadBytes(RAW_BASE_URL + relativePath)
            if (bytes != null) return@withContext bytes
        }

        return@withContext null
    }

    private fun downloadBytes(urlString: String): ByteArray? {
        return try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            if (connection.responseCode == 200) {
                connection.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
