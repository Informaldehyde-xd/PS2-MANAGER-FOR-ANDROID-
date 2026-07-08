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
                val gameIdEscaped = Regex.escape(gameId)
                val partRegex = Regex("^ul\\.[0-9A-Fa-f]{8}\\.$gameIdEscaped\\.(\\d{2})$")
                val existingParts = root.listFiles()
                    .mapNotNull { doc ->
                        val name = doc.name ?: return@mapNotNull null
                        val match = partRegex.find(name) ?: return@mapNotNull null
                        val partNum = match.groupValues[1].toInt()
                        partNum to doc
                    }
                    .sortedBy { it.first }

                if (existingParts.size != oldEntry.parts) {
                    // Physical files don't match what ul.cfg expects — bail out rather
                    // than risk renaming the wrong things.
                    return@withContext false to
                        "Found ${existingParts.size} part file(s) on disk but ul.cfg expects ${oldEntry.parts} — skipping to avoid corrupting this game."
                }

                // Rename every part file to use the new title's checksum.
                for ((partNum, doc) in existingParts) {
                    val newName = UlConfig.partFileName(newTitle, gameId, partNum)
                    if (!doc.renameTo(newName)) {
                        return@withContext false to "Failed renaming part $partNum of ${existingParts.size} on disk."
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

                val renamedDirectly = try {
                    doc.renameTo(newName)
                } catch (e: UnsupportedOperationException) {
                    false
                }
                if (renamedDirectly) return@withContext true to null

                // Direct rename isn't supported by this storage provider — fall back to copy + delete.
                if (parentDocumentId == null) {
                    return@withContext false to "This drive's storage provider doesn't support renaming, and no folder reference was available for a copy-based rename."
                }
                val parent = DocumentFile.fromTreeUri(context, Uri.parse(parentDocumentId))
                    ?: return@withContext false to "Could not access the containing folder (try re-picking the folder)."

                parent.findFile(newName)?.let { existing ->
                    if (existing.uri != doc.uri) existing.delete()
                }
                val mime = doc.type ?: "application/octet-stream"
                val newFile = parent.createFile(mime, newName)
                    ?: return@withContext false to "Could not create the renamed file in the containing folder."

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
     * drive, named the way OPL expects (GameID_COV.jpg). Kept for backward compatibility.
     */
    suspend fun saveArtToDrive(treeUri: Uri, gameId: String, localArtPath: String): Boolean =
        saveArtSetToDrive(treeUri, gameId, ArtSet(cover = localArtPath))
}
