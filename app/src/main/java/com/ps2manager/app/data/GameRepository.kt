package com.ps2manager.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ps2manager.app.util.GameIdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val GAME_EXTENSIONS = setOf("iso", "bin", "img")

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
