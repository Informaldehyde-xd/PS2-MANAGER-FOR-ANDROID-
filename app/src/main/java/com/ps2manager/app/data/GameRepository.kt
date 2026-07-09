package com.ps2manager.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ps2manager.app.util.GameIdUtil
import com.ps2manager.app.util.IsoSystemCnfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val GAME_EXTENSIONS = setOf("iso")

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
                    // Always read the real Game ID from inside the disc itself (SYSTEM.CNF) —
                    // never infer it from the filename, which may have been renamed to anything
                    // and could produce a false match.
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

    /** Finds ul.cfg at the root of the drive and lists the split-format (UL) games in it. */
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

    /**
     * Renames a UL (split-format) game: updates its title in ul.cfg AND renames every
     * physical part file to match, since OPL derives part filenames from a checksum
     * of the title. Both must change together or OPL will no longer find the game.
     */
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

                // Find the existing physical part files for this game, sorted by part number.
                // Match case-insensitively and allow 1-3 digit part numbers: different UL
                // conversion tools (USBUtil, USBExtreme, OPLUtil, etc.) don't all zero-pad or
                // case the Game ID the same way, so being strict here causes false "0 found".
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
                    // Physical files don't match what ul.cfg expects — bail out rather
                    // than risk renaming the wrong things. Show what ul.* files actually
                    // exist at the root so a naming-convention mismatch is easy to spot.
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

                // Rename every part file to use the new title's checksum.
                // Some storage providers don't fail a rename when the target name is already
                // taken — they silently disambiguate by appending " (1)" and still report
                // success. Guard against that: clear any pre-existing file at the target name
                // first (likely a stray leftover from an earlier attempt), then verify the
                // resulting name is exactly what we asked for rather than trusting a bare `true`.
                for ((partNum, doc) in existingParts) {
                    val newName = UlConfig.partFileName(newTitle, gameId, partNum)
                    if (doc.name == newName) continue // already correctly named

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

                // Update the ul.cfg entry's title and write the whole file back.
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
     * Renames a file on the drive to OPL's GameID.Title.ext convention. Returns (success, errorMessage).
     *
     * Some USB/HDD storage providers (notably many FAT32/exFAT SAF providers) don't support the
     * rename operation at all and throw UnsupportedOperationException from DocumentsContract.
     * When that happens, fall back to copying the file to a new name in the same folder and
     * deleting the original, which works everywhere since it only needs create+write+delete.
     * Since ISOs can be multiple GB, onCopyProgress reports bytes copied so the UI isn't silent.
     */
    suspend fun renameFile(
        documentUriString: String,
        gameId: String,
        title: String,
        extension: String,
        parentDocumentId: String? = null,
        onCopyProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                val doc = DocumentFile.fromSingleUri(context, Uri.parse(documentUriString))
                    ?: return@withContext false to "Could not access the file (permission may have been lost — try re-picking the folder)."
                if (!doc.exists()) {
                    return@withContext false to "File no longer exists at that location."
                }
                val newName = GameIdUtil.buildOplFilename(gameId, title, extension)

                // Resolve the parent up front (if we have it) so we can clear any pre-existing
                // file at the target name before renaming. Some storage providers silently
                // disambiguate a taken name by appending " (1)" instead of failing the rename,
                // which would otherwise go unnoticed since renameTo() still returns true.
                val parent = parentDocumentId?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
                parent?.findFile(newName)?.let { existing ->
                    if (existing.uri != doc.uri) existing.delete()
                }

                val renamedDirectly = try {
                    doc.renameTo(newName)
                } catch (e: UnsupportedOperationException) {
                    false
                }
                if (renamedDirectly) {
                    return@withContext if (doc.name == newName) {
                        true to null
                    } else {
                        false to "Renamed to '${doc.name}' instead of '$newName' — this drive's storage provider renamed around a naming conflict. The conflicting file has now been removed; try again."
                    }
                }

                // Direct rename isn't supported by this storage provider — fall back to copy + delete.
                if (parentDocumentId == null) {
                    return@withContext false to "This drive's storage provider doesn't support renaming, and no folder reference was available for a copy-based rename."
                }
                if (parent == null) {
                    return@withContext false to "Could not access the containing folder (try re-picking the folder)."
                }

                parent.findFile(newName)?.let { existing ->
                    if (existing.uri != doc.uri) existing.delete()
                }
                val mime = doc.type ?: "application/octet-stream"
                val newFile = parent.createFile(mime, newName)
                    ?: return@withContext false to "Could not create the renamed file in the containing folder."
                if (newFile.name != newName) {
                    return@withContext false to "Created file as '${newFile.name}' instead of '$newName' — a naming conflict remains on this drive."
                }

                val totalBytes = doc.length()
                var copiedBytes = 0L

                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                        val buffer = ByteArray(1 shl 20) // 1 MB buffer — the default 8 KB is very slow over USB/SAF
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            copiedBytes += read
                            onCopyProgress(copiedBytes, totalBytes)
                        }
                    } ?: return@withContext false to "Could not write the new file."
                } ?: return@withContext false to "Could not read the original file to copy it."

                if (!doc.delete()) {
                    return@withContext false to "Copied to the new name, but couldn't delete the original — you may have a duplicate now."
                }
                true to null
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * Recovery tool for when ul.cfg is missing, corrupted, or out of sync with the drive:
     * scans the root folder for "ul.<crc>.<gameId>.<partNum>" split-format files, groups
     * them by Game ID, and adds a placeholder entry to ul.cfg for any group that doesn't
     * already have a matching entry. Existing entries (and their titles) are left untouched.
     *
     * The placeholder title is just the Game ID, since the original title can't be recovered
     * from the files alone (the part filenames encode a checksum of whatever title was used
     * when they were created, not the title itself). After regenerating, use "Match Title &
     * Rename" on the new entries as normal — that recomputes the checksum for the matched
     * title and renames the physical part files to match, making everything consistent again.
     */
    suspend fun regenerateUlConfig(treeUri: Uri): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withContext false to "Lost access to the drive (try re-picking the folder)."

                // Load whatever's already in ul.cfg, if anything, so we only add what's missing.
                val cfgFile = root.findFile("ul.cfg")
                val existingEntries: MutableList<UlEntry> = if (cfgFile != null) {
                    val bytes = context.contentResolver.openInputStream(cfgFile.uri)?.use { it.readBytes() }
                    if (bytes != null) UlConfig.parse(bytes).toMutableList() else mutableListOf()
                } else {
                    mutableListOf()
                }
                val knownGameIds = existingEntries.mapNotNull { it.gameId?.uppercase() }.toSet()

                // Group every "ul.<8-hex-crc>.<gameId>.<partNum>" file at the root by Game ID.
                // Case-insensitive and 1-3 digit part numbers, same tolerance as the rename path,
                // since different conversion tools format these slightly differently.
                val partRegex = Regex("^ul\\.[0-9A-Fa-f]{8}\\.(.+)\\.(\\d{1,3})$", RegexOption.IGNORE_CASE)
                data class Group(val displayGameId: String, val parts: MutableList<DocumentFile> = mutableListOf())
                val groups = LinkedHashMap<String, Group>() // key = uppercased gameId

                for (doc in root.listFiles()) {
                    val name = doc.name ?: continue
                    val match = partRegex.find(name) ?: continue
                    val gameIdRaw = match.groupValues[1]
                    val key = gameIdRaw.uppercase()
                    val group = groups.getOrPut(key) { Group(gameIdRaw) }
                    group.parts.add(doc)
                }

                val orphanKeys = groups.keys.filterNot { it in knownGameIds }
                if (orphanKeys.isEmpty()) {
                    return@withContext false to if (groups.isEmpty()) {
                        "No 'ul.*' split-format files were found at the drive root."
                    } else {
                        "No orphaned ul.* files found — every part file on the drive already has a matching ul.cfg entry."
                    }
                }

                for (key in orphanKeys) {
                    val group = groups.getValue(key)
                    val gameId = group.displayGameId
                    val imageField = ByteArray(15)
                    val imageStr = "ul.$gameId".toByteArray(Charsets.ISO_8859_1)
                    imageStr.copyInto(imageField, 0, 0, imageStr.size.coerceAtMost(15))
                    existingEntries.add(
                        UlEntry(
                            nameBytes = UlConfig.buildNameBytes(gameId), // placeholder — real title unrecoverable from files alone
                            imageBytes = imageField,
                            parts = group.parts.size,
                            media = 0, // DVD
                            padBytes = ByteArray(15)
                        )
                    )
                }

                val newBytes = UlConfig.serialize(existingEntries)
                val targetCfg = cfgFile ?: root.createFile("application/octet-stream", "ul.cfg")
                    ?: return@withContext false to "Could not create ul.cfg on the drive."
                context.contentResolver.openOutputStream(targetCfg.uri)?.use { out -> out.write(newBytes) }
                    ?: return@withContext false to "Could not write ul.cfg to the drive."

                true to "Added ${orphanKeys.size} missing entr${if (orphanKeys.size == 1) "y" else "ies"} to ul.cfg with placeholder titles. Use \"Match Title & Rename\" on ${if (orphanKeys.size == 1) "it" else "them"} to set the real title (this also fixes the file checksums)."
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * Saves whichever art types were found into an "ART" folder at the root of the
     * selected drive, named the way OPL expects (GameID_COV.jpg, GameID_BG.jpg, etc).
     */
    suspend fun saveArtSetToDrive(treeUri: Uri, gameId: String, artSet: ArtSet): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
                val artDir = root.findFile("ART") ?: root.createDirectory("ART") ?: return@withContext false

                var savedAny = false
                for (type in ArtType.entries) {
                    val localPath = artSet.pathFor(type) ?: continue
                    val ext = localPath.substringAfterLast('.', type.defaultExt)
                    val mime = if (ext == "png") "image/png" else "image/jpeg"
                    val fileName = "$gameId${type.oplSuffix}.$ext"

                    artDir.findFile(fileName)?.delete()
                    val newFile = artDir.createFile(mime, fileName) ?: continue

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

    /**
     * Saves a downloaded cover art file into an "ART" folder at the root of the selected
     * drive, named the way OPL expects (GameID_COV.jpg). Kept for
     */
    suspend fun saveArtToDrive(treeUri: Uri, gameId: String, localArtPath: String): Boolean =
        saveArtSetToDrive(treeUri, gameId, ArtSet(cover = localArtPath))
}
