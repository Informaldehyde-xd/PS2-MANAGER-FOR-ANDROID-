package com.ps2manager.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ps2manager.app.util.GameIdUtil
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
                    val gameId = GameIdUtil.extractGameId(name)
                    val existingTitle = GameIdUtil.extractExistingTitle(name, gameId)
                    out.add(
                        GameFile(
                            documentId = child.uri.toString(),
                            displayName = name,
                            gameId = gameId,
                            currentTitle = existingTitle,
                            sizeBytes = child.length()
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
    suspend fun renameUlGame(treeUri: Uri, gameId: String, newTitle: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
                val cfgFile = root.findFile("ul.cfg") ?: return@withContext false

                val bytes = context.contentResolver.openInputStream(cfgFile.uri)?.use { it.readBytes() }
                    ?: return@withContext false
                val entries = UlConfig.parse(bytes).toMutableList()

                val index = entries.indexOfFirst { it.gameId == gameId }
                if (index == -1) return@withContext false
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
                    return@withContext false
                }

                // Rename every part file to use the new title's checksum.
                for ((partNum, doc) in existingParts) {
                    val newName = UlConfig.partFileName(newTitle, gameId, partNum)
                    if (!doc.renameTo(newName)) return@withContext false
                }

                // Update the ul.cfg entry's title and write the whole file back.
                entries[index] = oldEntry.copy(nameBytes = UlConfig.buildNameBytes(newTitle))
                val newBytes = UlConfig.serialize(entries)
                context.contentResolver.openOutputStream(cfgFile.uri)?.use { out ->
                    out.write(newBytes)
                } ?: return@withContext false

                true
            } catch (e: Exception) {
                false
            }
        }

    /** Renames a file on the drive to OPL's GameID.Title.ext convention. Returns true on success. */
    suspend fun renameFile(documentUriString: String, gameId: String, title: String, extension: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val doc = DocumentFile.fromSingleUri(context, Uri.parse(documentUriString)) ?: return@withContext false
                val newName = GameIdUtil.buildOplFilename(gameId, title, extension)
                doc.renameTo(newName)
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Saves a downloaded cover art file into an "ART" folder at the root of the selected
     * drive, named the way OPL expects (GameID_COV.jpg).
     */
    suspend fun saveArtToDrive(treeUri: Uri, gameId: String, localArtPath: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
                val artDir = root.findFile("ART") ?: root.createDirectory("ART") ?: return@withContext false

                val fileName = "${gameId}_COV.jpg"
                artDir.findFile(fileName)?.delete()
                val newFile = artDir.createFile("image/jpeg", fileName) ?: return@withContext false

                context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                    File(localArtPath).inputStream().use { input -> input.copyTo(out) }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
}
