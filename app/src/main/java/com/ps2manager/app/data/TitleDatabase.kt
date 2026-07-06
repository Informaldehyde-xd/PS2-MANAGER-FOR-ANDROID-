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
        private const val CSV_URL =
            "https://raw.githubusercontent.com/Luden02/psx-ps2-opl-art-database/main/PS2_LIST.csv"
        private const val CACHE_FILENAME = "ps2_list_cache.csv"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var idToTitle: Map<String, String> = emptyMap()
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.filesDir, CACHE_FILENAME)
            val csvText = try {
                val request = Request.Builder().url(CSV_URL).build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        cacheFile.writeText(body)
                        body
                    } else if (cacheFile.exists()) {
                        cacheFile.readText()
                    } else ""
                }
            } catch (e: Exception) {
                if (cacheFile.exists()) cacheFile.readText() else ""
            }
            idToTitle = parseCsv(csvText)
            loaded = true
        }
    }

    fun lookupTitle(gameId: String): String? = idToTitle[gameId.uppercase()]

    /** Simple substring search over the loaded database, for manually picking an alternate match. */
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
        text.lineSequence().drop(0).forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val commaIdx = line.indexOf(',')
            if (commaIdx <= 0) return@forEach
            val id = line.substring(0, commaIdx).trim().trim('"').uppercase()
            var title = line.substring(commaIdx + 1).trim()
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
