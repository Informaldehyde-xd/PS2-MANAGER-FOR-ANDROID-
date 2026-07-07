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
                    var gameId = GameIdUtil.extractGameId(name)
                    if (gameId == null) {
                        gameId = IsoSystemCnfReader.readGameId(context, child.uri)
                    }
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
                    return@withContext false
                }

                for ((partNum, doc) in existingParts) {
                    val newName = UlConfig.partFileName(newTitle, gameId, partNum)
                    if (!doc.renameTo(newName)) return@withContext false
                }

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

    suspend fun saveArtToDrive(treeUri: Uri, gameId: String, localArtPath: String): Boolean =
        saveArtSetToDrive(treeUri, gameId, ArtSet(cover = localArtPath))
}
