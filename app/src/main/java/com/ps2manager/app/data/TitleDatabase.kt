package com.ps2manager.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TitleDatabase(private val context: Context) {

    private var idToTitle: Map<String, String> = emptyMap()
    private var loaded = false

    val entryCount: Int get() = idToTitle.size

    var lastError: String? = null
        private set

    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            try {
                val map = HashMap<String, String>()

                // 1. Load PS2 List from Assets
                try {
                    context.assets.open("PS2_LIST.csv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty() && !trimmed.startsWith("sep=")) {
                                val parts = parseCsvLine(trimmed)
                                if (parts.size >= 3) {
                                    val id1 = parts[0].uppercase().replace("-", "_")
                                    val id2 = parts[1].uppercase().replace("-", "_")
                                    val title = parts[2]
                                    if (id1 != "ID" && title.isNotEmpty()) {
                                        map[id1] = title
                                        map[id2] = title
                                        map[id1.replace("_", "-")] = title
                                        map[id2.replace("_", "-")] = title
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    lastError = "PS2 asset load error: ${e.message}"
                }

                // 2. Load PS1 TSV List from Assets
                try {
                    context.assets.open("PSX.data.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                        val iterator = lines.iterator()
                        if (iterator.hasNext()) {
                            val headerLine = iterator.next()
                            val headers = headerLine.split('\t').map { it.trim() }
                            val serialIdx = headers.indexOf("serial")
                            val titleIdx = headers.indexOf("title")

                            if (serialIdx >= 0 && titleIdx >= 0) {
                                while (iterator.hasNext()) {
                                    val line = iterator.next()
                                    val parts = line.split('\t')
                                    if (parts.size > maxOf(serialIdx, titleIdx)) {
                                        val serial = parts[serialIdx].trim().uppercase()
                                        val title = parts[titleIdx].trim().trim('"')
                                        if (serial.isNotEmpty() && serial != "N/A" && title.isNotEmpty() && title != "N/A") {
                                            map[serial] = title
                                            map[serial.replace("-", "_")] = title
                                            map[serial.replace("_", "-")] = title
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (lastError == null) lastError = "PSX asset load error: ${e.message}"
                }

                idToTitle = map
                if (idToTitle.isEmpty()) {
                    lastError = lastError ?: "Could not load title entries from local assets."
                }
                loaded = true
            } catch (e: Exception) {
                lastError = e.message
                loaded = true
            }
        }
    }

    fun lookupTitle(gameId: String): String? {
        val normalized = gameId.uppercase().trim()
        return idToTitle[normalized] 
            ?: idToTitle[normalized.replace("-", "_")] 
            ?: idToTitle[normalized.replace("_", "-")]
    }

    fun searchTitles(query: String, limit: Int = 20): List<Pair<String, String>> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        return idToTitle.entries
            .asSequence()
            .filter { it.key.lowercase().contains(q) || it.value.lowercase().contains(q) }
            .take(limit)
            .map { it.key to it.value }
            .toList()
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(sb.toString().trim().trim('"'))
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        result.add(sb.toString().trim().trim('"'))
        return result
    }
}
