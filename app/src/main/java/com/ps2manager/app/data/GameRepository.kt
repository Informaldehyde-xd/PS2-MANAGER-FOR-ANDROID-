package com.ps2manager.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ps2manager.app.util.GameIdUtil
import com.ps2manager.app.util.IsoSystemCnfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private val GAME_EXTENSIONS = setOf("iso")

// 1 GB per part, matching the real USBExtreme/OPL split-format convention.
private const val UL_PART_SIZE = 1024L * 1024L * 1024L

class GameRepository(private val context: Context) {

    /** Recursively finds game files under the selected tree URI (DVD/CD/USB folder structure). */
    suspend fun scanFolder(treeUri: Uri): List<GameFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val found = mutableListOf<GameFile>()
        collect(root, found)
        found
    }

    private fun collect(dir: DocumentFile, out: MutableList<GameFile>) {
        val children = dir.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                collect(child, out)
            } else {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in GAME_EXTENSIONS) {
                    val gameId = IsoSystemCnfReader.readGameId(context, child.uri)
                    out.add(
                        GameFile(
                            documentId = child.uri.toString(),
                            displayName = name,
                            gameId = gameId,
                            currentTitle = null,
                            sizeBytes = child.length(),
                            parentDocumentId = dir.uri.toString()
                        )
                    )
                }
            }
        }
    }

    suspend fun scanUlGames(treeUri: Uri): List<GameFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val cfgFile = root.findFile("ul.cfg") ?: return@withContext emptyList()

        val bytes = try {
            context.contentResolver.openInputStream(cfgFile.uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: return@withContext emptyList()

        UlConfig.parse(bytes).mapNotNull { entry ->
            val gameId = entry.gameId ?: return@mapNotNull null
            GameFile(
                documentId = "ul:$gameId",
                displayName = entry.title,
                gameId = gameId,
                currentTitle = entry.title,
                sizeBytes = 0L,
                isUlGame = true,
                ulParts = entry.parts
            )
        }
    }

    suspend fun renameUlGame(treeUri: Uri, gameId: String, newTitle: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withContext false to "Lost access to the drive (try re-picking the folder)."
                val cfgFile = root.findFile("ul.cfg")
                    ?: return@withContext false to "ul.cfg not found at the drive root."

                val bytes = context.contentResolver.openInputStream(cfgFile.uri)?.use { it.readBytes() }
                    ?: return@withContext false to "Could not read ul.cfg."
                val entries = UlConfig.parse(bytes).toMutableList()

                val index = entries.indexOfFirst { it.gameId == gameId }
                if (index == -1) return@withContext false to "This game's entry is no longer in ul.cfg."
                val oldEntry = entries[index]

                val gameIdEscaped = Regex.escape(gameId)
                val partRegex = Regex("^ul\\.[0-9A-Fa-f]{8}\\.$gameIdEscaped\\.(\\d{1,3})$", RegexOption.IGNORE_CASE)
                val allFiles = root.listFiles()
                val existingParts = allFiles
                    .mapNotNull { doc ->
                        val name = doc.name ?: return@mapNotNull null
                        val match = partRegex.find(name) ?: return@mapNotNull null
                        val partNum = match.groupValues[1].toInt()
                        partNum to doc
                    }
                    .sortedBy { it.first }

                if (existingParts.size != oldEntry.parts) {
                    val nearbyUlFiles = allFiles
                        .mapNotNull { it.name }
                        .filter { it.startsWith("ul.", ignoreCase = true) }
                        .take(10)
                    val diagnostic = if (nearbyUlFiles.isNotEmpty()) {
                        " Files on disk starting with 'ul.': ${nearbyUlFiles.joinToString(", ")}"
                    } else {
                        " No files starting with 'ul.' were found at the drive root at all."
                    }
                    return@withContext false to
                        "Found ${existingParts.size} part file(s) on disk but ul.cfg expects ${oldEntry.parts} — skipping to avoid corrupting this game.$diagnostic"
                }

                for ((partNum, doc) in existingParts) {
                    val newName = UlConfig.partFileName(newTitle, gameId, partNum)
                    if (doc.name == newName) continue

                    root.findFile(newName)?.let { existing ->
                        if (existing.uri != doc.uri) existing.delete()
                    }
                    if (!doc.renameTo(newName)) {
                        return@withContext false to "Failed renaming part $partNum of ${existingParts.size} on disk."
                    }
                    if (doc.name != newName) {
                        return@withContext false to
                            "Part $partNum renamed to '${doc.name}' instead of '$newName' — this drive's storage provider renamed around a naming conflict. The conflicting file has now been removed; try again."
                    }
                }

                entries[index] = oldEntry.copy(nameBytes = UlConfig.buildNameBytes(newTitle))
                val newBytes = UlConfig.serialize(entries)
                context.contentResolver.openOutputStream(cfgFile.uri)?.use { out ->
                    out.write(newBytes)
                } ?: return@withContext false to "Could not write the updated ul.cfg back to the drive."

                true to null
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * Renames a file on the drive to OPL's GameID.Title.ext convention. 
     * Tries a fast metadata rename first. If that fails or is unsupported by the provider,
     * it falls back to a safe copy-and-delete procedure.
     */
    suspend fun renameFile(
        documentUriString: String,
        gameId: String,
        title: String,
        extension: String,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                val doc = DocumentFile.fromSingleUri(context, Uri.parse(documentUriString))
                    ?: return@withContext false to "Could not access the file (permission may have been lost — try re-picking the folder)."
                
                if (!doc.exists()) {
                    return@withContext false to "File no longer exists at that location."
                }
                
                val newName = GameIdUtil.buildOplFilename(gameId, title, extension)

                // Skip if already correctly named
                if (doc.name == newName) {
                    return@withContext true to null
                }

                // Attempt 1: Fast metadata rename with a 5-second timeout
                val renamed = try {
                    withTimeoutOrNull(5000L) {
                        doc.renameTo(newName)
                    } ?: false
                } catch (e: UnsupportedOperationException) {
                    false
                }

                if (renamed && doc.name == newName) {
                    return@withContext true to null
                }

                // Attempt 2: Fallback to Copy-and-Delete if the provider doesn't support metadata rename
                val parent = doc.parentFile 
                    ?: return@withContext false to "Could not access parent directory for fallback copy operation."

                parent.findFile(newName)?.delete()

                val newFile = parent.createFile("application/octet-stream", newName)
                    ?: return@withContext false to "Failed to create renamed destination file."

                val totalBytes = doc.length()
                val inputStream = context.contentResolver.openInputStream(doc.uri)
                    ?: return@withContext false to "Could not open source file for reading."
                val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                    ?: return@withContext false to "Could not open destination file for writing."

                inputStream.use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(1 shl 20) // 1 MB buffer
                        var bytesCopiedTotal = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            bytesCopiedTotal += read
                            onProgress(bytesCopiedTotal, totalBytes)
                        }
                    }
                }

                if (newFile.exists() && newFile.length() == totalBytes) {
                    doc.delete()
                    true to null
                } else {
                    newFile.delete()
                    false to "Copy-and-delete fallback failed to verify file integrity."
                }
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

    suspend fun regenerateUlConfig(
        treeUri: Uri,
        resolveTitle: (gameId: String) -> String? = { null }
    ): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withContext false to "Lost access to the drive (try re-picking the folder)."

                val cfgFile = root.findFile("ul.cfg")
                val existingEntries: MutableList<UlEntry> = if (cfgFile != null) {
                    val bytes = context.contentResolver.openInputStream(cfgFile.uri)?.use { it.readBytes() }
                    if (bytes != null) UlConfig.parse(bytes).toMutableList() else mutableListOf()
                } else {
                    mutableListOf()
                }
                val knownGameIds = existingEntries.mapNotNull { it.gameId?.uppercase() }.toSet()

                val partRegex = Regex("^ul\\.[0-9A-Fa-f]{8}\\.(.+)\\.(\\d{1,3})$", RegexOption.IGNORE_CASE)
                data class Group(val displayGameId: String, val parts: MutableList<Pair<Int, DocumentFile>> = mutableListOf())
                val groups = LinkedHashMap<String, Group>()

                for (doc in root.listFiles()) {
                    val name = doc.name ?: continue
                    val match = partRegex.find(name) ?: continue
                    val gameIdRaw = match.groupValues[1]
                    val partNum = match.groupValues[2].toIntOrNull() ?: continue
                    val key = gameIdRaw.uppercase()
                    val group = groups.getOrPut(key) { Group(gameIdRaw) }
                    group.parts.add(partNum to doc)
                }

                val orphanKeys = groups.keys.filterNot { it in knownGameIds }
                if (orphanKeys.isEmpty()) {
                    return@withContext false to if (groups.isEmpty()) {
                        "No 'ul.*' split-format files were found at the drive root."
                    } else {
                        "No orphaned ul.* files found — every part file on the drive already has a matching ul.cfg entry."
                    }
                }

                var titlesResolved = 0
                var renameFailures = 0

                for (key in orphanKeys) {
                    val group = groups.getValue(key)
                    val gameId = group.displayGameId
                    val resolved = resolveTitle(gameId)?.trim()?.takeIf { it.isNotEmpty() }
                    if (resolved != null) titlesResolved++
                    val title = (resolved ?: gameId).take(32)

                    val sortedParts = group.parts.sortedBy { it.first }.map { it.second }
                    for ((partIndex, doc) in sortedParts.withIndex()) {
                        val newName = UlConfig.partFileName(title, gameId, partIndex)
                        if (doc.name == newName) continue
                        try {
                            root.findFile(newName)?.let { existing ->
                                if (existing.uri != doc.uri) existing.delete()
                            }
                            if (!doc.renameTo(newName) || doc.name != newName) {
                                renameFailures++
                            }
                        } catch (e: UnsupportedOperationException) {
                            renameFailures++
                        }
                    }

                    val imageField = ByteArray(15)
                    val imageStr = "ul.$gameId".toByteArray(Charsets.ISO_8859_1)
                    imageStr.copyInto(imageField, 0, 0, imageStr.size.coerceAtMost(15))
                    existingEntries.add(
                        UlEntry(
                            nameBytes = UlConfig.buildNameBytes(title),
                            imageBytes = imageField,
                            parts = sortedParts.size,
                            media = UlConfig.MEDIA_DVD,
                            padBytes = UlConfig.defaultPadBytes()
                        )
                    )
                }

                val newBytes = UlConfig.serialize(existingEntries)
                val targetCfg = cfgFile ?: root.createFile("application/octet-stream", "ul.cfg")
                    ?: return@withContext false to "Could not create ul.cfg on the drive."
                context.contentResolver.openOutputStream(targetCfg.uri)?.use { out -> out.write(newBytes) }
                    ?: return@withContext false to "Could not write ul.cfg to the drive."

                true to "Added ${orphanKeys.size} entry/entries to ul.cfg."
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

    suspend fun convertIsoToUl(
        treeUri: Uri,
        isoDocumentId: String,
        gameId: String,
        title: String,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext false to "Lost access to the drive (try re-picking the folder)."

            val cfgFile = root.findFile("ul.cfg")
            val existingEntries: MutableList<UlEntry> = if (cfgFile != null) {
                val bytes = context.contentResolver.openInputStream(cfgFile.uri)?.use { it.readBytes() }
                if (bytes != null) UlConfig.parse(bytes).toMutableList() else mutableListOf()
            } else {
                mutableListOf()
            }
            if (existingEntries.any { it.gameId == gameId }) {
                return@withContext false to "This game already has a ul.cfg entry — remove it first if you want to re-split it."
            }

            val sourceDoc = DocumentFile.fromSingleUri(context, Uri.parse(isoDocumentId))
                ?: return@withContext false to "Could not access the source ISO."
            val totalBytes = sourceDoc.length()
            if (totalBytes <= 0) return@withContext false to "Source ISO appears to be empty or inaccessible."

            var partsWritten = 0
            val input = context.contentResolver.openInputStream(sourceDoc.uri)
                ?: return@withContext false to "Could not read the source ISO."

            input.use { inStream ->
                var bytesCopiedTotal = 0L
                val buffer = ByteArray(1 shl 20)

                while (bytesCopiedTotal < totalBytes) {
                    val partName = UlConfig.partFileName(title, gameId, partsWritten)
                    root.findFile(partName)?.delete()
                    val partFile = root.createFile("application/octet-stream", partName)
                        ?: return@withContext false to "Could not create part file $partsWritten on the drive."
                    val partOutput = context.contentResolver.openOutputStream(partFile.uri)
                        ?: return@withContext false to "Could not write part file $partsWritten."

                    partOutput.use { out ->
                        var bytesInThisPart = 0L
                        while (bytesInThisPart < UL_PART_SIZE && bytesCopiedTotal < totalBytes) {
                            val toRead = minOf(buffer.size.toLong(), UL_PART_SIZE - bytesInThisPart).toInt()
                            val read = inStream.read(buffer, 0, toRead)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            bytesInThisPart += read
                            bytesCopiedTotal += read
                            onProgress(bytesCopiedTotal, totalBytes)
                        }
                    }
                    partsWritten++
                }
            }

            val imageField = ByteArray(15)
            val imageBytes = "ul.$gameId".toByteArray(Charsets.ISO_8859_1)
            imageBytes.copyInto(imageField, 0, 0, imageBytes.size.coerceAtMost(15))

            existingEntries.add(
                UlEntry(
                    nameBytes = UlConfig.buildNameBytes(title),
                    imageBytes = imageField,
                    parts = partsWritten,
                    media = if (totalBytes > 734_003_200L) UlConfig.MEDIA_DVD else UlConfig.MEDIA_CD,
                    padBytes = UlConfig.defaultPadBytes()
                )
            )

            val newBytes = UlConfig.serialize(existingEntries)
            val targetCfg = cfgFile ?: root.createFile("application/octet-stream", "ul.cfg")
                ?: return@withContext false to "Could not create ul.cfg on the drive."
            context.contentResolver.openOutputStream(targetCfg.uri)?.use { out -> out.write(newBytes) }
                ?: return@withContext false to "Could not write ul.cfg to the drive."

            true to "Split into $partsWritten part(s) and added to ul.cfg."
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun convertUlToIso(
        treeUri: Uri,
        gameId: String,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext false to "Lost access to the drive (try re-picking the folder)."
            val cfgFile = root.findFile("ul.cfg")
                ?: return@withContext false to "ul.cfg not found at the drive root."

            val bytes = context.contentResolver.openInputStream(cfgFile.uri)?.use { it.readBytes() }
                ?: return@withContext false to "Could not read ul.cfg."
            val entry = UlConfig.parse(bytes).firstOrNull { it.gameId == gameId }
                ?: return@withContext false to "This game's entry was not found in ul.cfg."

            val gameIdEscaped = Regex.escape(gameId)
            val partRegex = Regex("^ul\\.[0-9A-Fa-f]{8}\\.$gameIdEscaped\\.(\\d{1,3})$", RegexOption.IGNORE_CASE)
            val existingParts = root.listFiles()
                .mapNotNull { doc ->
                    val name = doc.name ?: return@mapNotNull null
                    val match = partRegex.find(name) ?: return@mapNotNull null
                    match.groupValues[1].toInt() to doc
                }
                .sortedBy { it.first }

            if (existingParts.size != entry.parts) {
                return@withContext false to "Found ${existingParts.size} part file(s) on disk but ul.cfg expects ${entry.parts}."
            }

            val totalBytes = existingParts.sumOf { it.second.length() }
            val outputName = GameIdUtil.buildOplFilename(gameId, entry.title, "iso")
            root.findFile(outputName)?.delete()
            val outputFile = root.createFile("application/octet-stream", outputName)
                ?: return@withContext false to "Could not create the output ISO file on the drive."

            val output = context.contentResolver.openOutputStream(outputFile.uri)
                ?: return@withContext false to "Could not write the output ISO file."

            output.use { out ->
                var bytesCopiedTotal = 0L
                val buffer = ByteArray(1 shl 20)
                for ((_, partDoc) in existingParts) {
                    val partInput = context.contentResolver.openInputStream(partDoc.uri)
                        ?: return@withContext false to "Could not read part file '${partDoc.name}'."
                    partInput.use { inStream ->
                        while (true) {
                            val read = inStream.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            bytesCopiedTotal += read
                            onProgress(bytesCopiedTotal, totalBytes)
                        }
                    }
                }
            }

            true to "Reassembled into '$outputName'."
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun saveArtSetToDrive(treeUri: Uri, gameId: String, artSet: ArtSet): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
                val artDir = root.findFile("ART") ?: root.createDirectory("ART") ?: return@withContext false

                var savedAny = false
                for (type in ArtType.entries) {
                    val localPath = artSet.pathFor(type) ?: continue
                    val fileName = "$gameId${type.oplSuffix}.png"

                    artDir.findFile("$gameId${type.oplSuffix}.jpg")?.delete()
                    artDir.findFile(fileName)?.delete()
                    val newFile = artDir.createFile("image/png", fileName) ?: continue

                    context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                        File(localPath).inputStream().use { input -> input.copyTo(out) }
                    }
                    savedAny = true
                }
                savedAny
            } catch (e: Exception) {
                false
            }
        }

    suspend fun saveArtToDrive(treeUri: Uri, gameId: String, localArtPath: String): Boolean =
        saveArtSetToDrive(treeUri, gameId, ArtSet(cover = localArtPath))
}
