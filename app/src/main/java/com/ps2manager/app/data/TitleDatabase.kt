package com.ps2manager.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class TitleDatabase(private val context: Context) {

    companion object {
        private val CSV_URL_CANDIDATES = listOf(
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/PS2_LIST.csv",
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/list.csv",
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/PS2/list.csv",
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/ps2_list.csv",
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/database.csv"
        )
        private const val CACHE_FILENAME = "ps2_list_cache.csv"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var idToTitle: Map<String, String> = emptyMap()
    private var loaded = false

    val entryCount: Int get() = idToTitle.size

    var lastError: String? = null
        private set

    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.filesDir, CACHE_FILENAME)
            var csvText = ""
            var succeeded = false

            for (url in CSV_URL_CANDIDATES) {
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                csvText = body
                                succeeded = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message
                }
                if (succeeded) break
            }

            if (succeeded) {
                cacheFile.writeText(csvText)
            } else if (cacheFile.exists()) {
                csvText = cacheFile.readText()
                succeeded = csvText.isNotBlank()
            } else {
                lastError = lastError ?: "Could not reach the online title database (no internet, or the archive moved)."
            }

            idToTitle = parseCsv(csvText)
            if (succeeded && idToTitle.isEmpty()) {
                lastError = "Connected, but couldn't read any entries (database format may differ from what's expected)."
            }
            loaded = true
        }
    }

    fun lookupTitle(gameId: String): String? = idToTitle[gameId.uppercase()]

    fun searchTitles(query: String, limit: Int = 20): List<Pair<String, String>> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        return idToTitle.entries
            .asSequence()
            .filter { it.value.lowercase().contains(q) }
            .take(limit)
            .map { it.key to it.value }
            .toList()
    }

    private fun parseCsv(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            val delim = listOf(',', ';', '\t').firstOrNull { line.contains(it) } ?: return@forEach
            val splitIdx = line.indexOf(delim)
            if (splitIdx <= 0) return@forEach

            val id = line.substring(0, splitIdx).trim().trim('"').uppercase()
            var title = line.substring(splitIdx + 1).trim()
            if (title.startsWith("\"") && title.endsWith("\"") && title.length >= 2) {
                title = title.substring(1, title.length - 1)
            }
            if (id.equals("GAMEID", ignoreCase = true) || id.equals("ID", ignoreCase = true)) return@forEach
            if (id.isNotEmpty() && title.isNotEmpty()) {
                map[id] = title
            }
        }
        return map
    }
}
