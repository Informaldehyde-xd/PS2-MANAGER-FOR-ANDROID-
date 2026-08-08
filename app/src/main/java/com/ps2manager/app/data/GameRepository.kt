package com.ps2manager.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ps2manager.app.util.Cue2PopsConverter
import com.ps2manager.app.util.GameIdUtil
import com.ps2manager.app.util.IsoSystemCnfReader
import com.ps2manager.app.util.ZisoConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private val GAME_EXTENSIONS = setOf("iso", "zso")
private val BIN_CUE_EXTENSIONS = setOf("cue")
private val VCD_EXTENSIONS = setOf("vcd")

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
                    val isZso = ext == "zso"
                    val gameId = IsoSystemCnfReader.readGameId(context, child.uri)
                        ?: GameIdUtil.extractGameId(name)
                    out.add(
                        GameFile(
                            documentId = child.uri.toString(),
                            displayName = name,
                            gameId = gameId,
                            currentTitle = null,
                            sizeBytes = child.length(),
                            parentDocumentId = dir.uri.toString(),
                            isZsoGame = isZso
                        )
                    )
                } else if (ext in BIN_CUE_EXTENSIONS) {
                    val gameId = GameIdUtil.extractGameId(name)
                    val title = GameIdUtil.extractExistingTitle(name, gameId)
                    out.add(
                        GameFile(
                            documentId = child.uri.toString(),
                            displayName = name,
                            gameId = gameId,
                            currentTitle = title,
                            sizeBytes = child.length(),
                            parentDocumentId = dir.uri.toString(),
                            isBinCueGame = true
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

    suspend fun scanVcdGames(treeUri: Uri): List<GameFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val popsDir = root.findFile("POPS") ?: return@withContext emptyList()
        val found = mutableListOf<GameFile>()

        for (child in popsDir.listFiles()) {
            if (child.isDirectory) continue
            val name = child.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in VCD_EXTENSIONS) continue

            val gameId = IsoSystemCnfReader.readGameId(context, child.uri)
                ?: GameIdUtil.extractGameId(name)
            val title = GameIdUtil.extractExistingTitle(name, gameId)

            found.add(
                GameFile(
                    documentId = child.uri.toString(),
                    displayName = name,
                    gameId = gameId,
                    currentTitle = title,
                    sizeBytes = child.length(),
                    parentDocumentId = popsDir.uri.toString(),
                    isVcdGame = true
                )
            )
        }
        found
    }

    suspend fun renameUlGame(treeUri: Uri, gameId: String, newTitle: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withContext false to "Lost access to the drive."
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
                    return@withContext false to "Found ${existingParts.size} part file(s) on disk but ul.cfg expects ${oldEntry.parts}."
                }

                for ((partNum, doc) in existingParts) {
                    val newName = UlConfig.partFileName(newTitle, gameId, partNum)
                    if (doc.name == newName) continue

                    root.findFile(newName)?.let { existing ->
                        if (existing.uri != doc.uri) existing.delete()
                    }
                    if (!doc.renameTo(newName)) {
                        return@withContext false to "Failed renaming part $partNum."
                    }
                }

                entries[index] = oldEntry.copy(nameBytes = UlConfig.buildNameBytes(newTitle))
                val newBytes = UlConfig.serialize(entries)
                context.contentResolver.openOutputStream(cfgFile.uri)?.use { out ->
                    out.write(newBytes)
                } ?: return@withContext false to "Could not write updated ul.cfg."

                true to null
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

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
                    ?: return@withContext false to "Could not access the file."
                
                if (!doc.exists()) {
                    return@withContext false to "File no longer exists."
                }
                
                val newName = GameIdUtil.buildOplFilename(gameId, title, extension)
                if (doc.name == newName) {
                    return@withContext true to null
                }

                val oldName = doc.name ?: return@withContext false to "File has no name."
                val renamed = try {
                    withTimeoutOrNull(5000L) { doc.renameTo(newName) } ?: false
                } catch (e: UnsupportedOperationException) {
                    false
                }

                if (renamed && doc.name == newName) {
                    if (extension.equals("VCD", ignoreCase = true)) {
                        renameAssociatedElf(doc.parentFile, oldName, newName)
                    }
                    return@withContext true to null
                }

                val parent = doc.parentFile 
                    ?: return@withContext false to "Could not access parent directory."

                parent.findFile(newName)?.delete()
                val newFile = parent.createFile("application/octet-stream", newName)
                    ?: return@withContext false to "Failed to create destination file."

                val totalBytes = doc.length()
                val inputStream = context.contentResolver.openInputStream(doc.uri)
                    ?: return@withContext false to "Could not open source file."
                val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                    ?: return@withContext false to "Could not open destination file."

                inputStream.use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(1 shl 20)
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
                    if (extension.equals("VCD", ignoreCase = true)) {
                        renameAssociatedElf(parent, oldName, newName)
                    }
                    true to null
                } else {
                    newFile.delete()
                    false to "Copy-and-delete verification failed."
                }
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

        suspend fun regenerateUlConfig(treeUri: Uri): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext false to "Lost access to the drive."

            val allFiles = root.listFiles()
            
            // Regex to match USBExtreme split files: ul.<CRC>.<GameID>.<PartNum>
            // Example: ul.12345678.SLUS_123.45.00
            val partRegex = Regex("^ul\\.([0-9A-Fa-f]{8})\\.([A-Za-z0-9_.-]+)\\.(\\d{1,3})$", RegexOption.IGNORE_CASE)

            // Group parts by Game ID
            val gameParts = mutableMapOf<String, MutableList<DocumentFile>>()

            for (file in allFiles) {
                val name = file.name ?: continue
                val match = partRegex.find(name) ?: continue
                val gameId = match.groupValues[2]
                gameParts.getOrPut(gameId) { mutableListOf() }.add(file)
            }

            if (gameParts.isEmpty()) {
                return@withContext false to "No UL format game parts found on the drive to generate ul.cfg."
            }

            val entries = mutableListOf<UlEntry>()
            for ((gameId, parts) in gameParts) {
                val totalSize = parts.sumOf { it.length() }
                
                // Construct the 15-byte image ID field required by ul.cfg
                val imageField = ByteArray(15)
                val imageBytes = "ul.$gameId".toByteArray(Charsets.ISO_8859_1)
                imageBytes.copyInto(imageField, 0, 0, imageBytes.size.coerceAtMost(15))

                entries.add(
                    UlEntry(
                        nameBytes = UlConfig.buildNameBytes(gameId), // Defaults the title to the Game ID safely
                        imageBytes = imageField,
                        parts = parts.size,
                        media = if (totalSize > 734_003_200L) UlConfig.MEDIA_DVD else UlConfig.MEDIA_CD,
                        padBytes = UlConfig.defaultPadBytes()
                    )
                )
            }

            val newBytes = UlConfig.serialize(entries)
            
            // Overwrite the existing ul.cfg if it's there
            val cfgFile = root.findFile("ul.cfg") 
            cfgFile?.delete() 
            
            val newCfg = root.createFile("application/octet-stream", "ul.cfg")
                ?: return@withContext false to "Could not create a new ul.cfg file."

            context.contentResolver.openOutputStream(newCfg.uri)?.use { out ->
                out.write(newBytes)
            } ?: return@withContext false to "Could not write to ul.cfg."

            true to "Regenerated ul.cfg successfully with ${entries.size} game(s)."
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
        }
        
    /** Single unified implementation of generateConfAppsCfg taking list of games */
    suspend fun generateConfAppsCfg(treeUri: Uri, games: List<GameFile>): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext false to "Lost access to the drive."
            
            val oplDir = root.findFile("OPL") ?: root.createDirectory("OPL") 
                ?: return@withContext false to "Could not access or create the OPL folder."

            val vcdGames = games.filter { it.isVcdGame }
            val stringBuilder = StringBuilder()
            for (game in vcdGames) {
                val vcdName = game.displayName
                val baseName = vcdName.substringBeforeLast('.')
                val elfName = "XX.$baseName.ELF"
                val titleToDisplay = game.matchedTitle?.takeIf { it.isNotBlank() } ?: baseName
                stringBuilder.append("$titleToDisplay=mass:/POPS/$elfName\n")
            }

            val configFileName = "conf_apps.cfg"
            oplDir.findFile(configFileName)?.delete()
            val configFile = oplDir.createFile("text/plain", configFileName)
                ?: return@withContext false to "Could not create conf_apps.cfg."

            context.contentResolver.openOutputStream(configFile.uri, "w")?.use { out ->
                out.write(stringBuilder.toString().toByteArray(Charsets.UTF_8))
            } ?: return@withContext false to "Could not write content."

            true to "Successfully updated conf_apps.cfg with ${vcdGames.size} entries."
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
                ?: return@withContext false to "Lost access to the drive."

            val cfgFile = root.findFile("ul.cfg")
            val existingEntries: MutableList<UlEntry> = if (cfgFile != null) {
                val bytes = context.contentResolver.openInputStream(cfgFile.uri)?.use { it.readBytes() }
                if (bytes != null) UlConfig.parse(bytes).toMutableList() else mutableListOf()
            } else {
                mutableListOf()
            }

            val sourceDoc = DocumentFile.fromSingleUri(context, Uri.parse(isoDocumentId))
                ?: return@withContext false to "Could not access source ISO."
            val totalBytes = sourceDoc.length()
            
            var partsWritten = 0
            val input = context.contentResolver.openInputStream(sourceDoc.uri)
                ?: return@withContext false to "Could not read source ISO."

            input.use { inStream ->
                var bytesCopiedTotal = 0L
                val buffer = ByteArray(1 shl 20)

                while (bytesCopiedTotal < totalBytes) {
                    val partName = UlConfig.partFileName(title, gameId, partsWritten)
                    root.findFile(partName)?.delete()
                    val partFile = root.createFile("application/octet-stream", partName)
                        ?: return@withContext false to "Could not create part file."
                    val partOutput = context.contentResolver.openOutputStream(partFile.uri)
                        ?: return@withContext false to "Could not write part file."

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
                ?: return@withContext false to "Could not create ul.cfg."
            context.contentResolver.openOutputStream(targetCfg.uri)?.use { out -> out.write(newBytes) }

            true to "Split into $partsWritten part(s)."
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
                ?: return@withContext false to "Lost access to the drive."
            val cfgFile = root.findFile("ul.cfg")
                ?: return@withContext false to "ul.cfg not found."

            val bytes = context.contentResolver.openInputStream(cfgFile.uri)?.use { it.readBytes() }
                ?: return@withContext false to "Could not read ul.cfg."
            val entry = UlConfig.parse(bytes).firstOrNull { it.gameId == gameId }
                ?: return@withContext false to "Game entry not found in ul.cfg."

            val gameIdEscaped = Regex.escape(gameId)
            val partRegex = Regex("^ul\\.[0-9A-Fa-f]{8}\\.$gameIdEscaped\\.(\\d{1,3})$", RegexOption.IGNORE_CASE)
            val existingParts = root.listFiles()
                .mapNotNull { doc ->
                    val name = doc.name ?: return@mapNotNull null
                    val match = partRegex.find(name) ?: return@mapNotNull null
                    match.groupValues[1].toInt() to doc
                }
                .sortedBy { it.first }

            val totalBytes = existingParts.sumOf { it.second.length() }
            val outputName = GameIdUtil.buildOplFilename(gameId, entry.title, "iso")
            root.findFile(outputName)?.delete()
            val outputFile = root.createFile("application/octet-stream", outputName)
                ?: return@withContext false to "Could not create output ISO."

            val output = context.contentResolver.openOutputStream(outputFile.uri)
                ?: return@withContext false to "Could not write ISO."

            output.use { out ->
                val buffer = ByteArray(1 shl 20)
                var bytesCopiedTotal = 0L
                for ((_, partDoc) in existingParts) {
                    context.contentResolver.openInputStream(partDoc.uri)?.use { inStream ->
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

    suspend fun convertIsoToZso(
        treeUri: Uri,
        isoDocumentId: String,
        gameId: String,
        title: String,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "iso2zso_${System.currentTimeMillis()}")
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext false to "Lost access to the drive."
            val sourceDoc = DocumentFile.fromSingleUri(context, Uri.parse(isoDocumentId))
                ?: return@withContext false to "Could not access source ISO."

            workDir.mkdirs()
            val localIso = File(workDir, "source.iso")
            val localZso = File(workDir, "output.zso")
            val totalBytes = sourceDoc.length()

            context.contentResolver.openInputStream(sourceDoc.uri)?.use { input ->
                localIso.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied / 2, totalBytes)
                    }
                }
            } ?: return@withContext false to "Could not read source ISO."

            ZisoConverter.compressIsoToZso(localIso, localZso) { done, total ->
                onProgress(total / 2 + done / 2, total)
            }

            val outputName = GameIdUtil.buildOplFilename(gameId, title, "zso")
            root.findFile(outputName)?.delete()
            val destFile = root.createFile("application/octet-stream", outputName)
                ?: return@withContext false to "Could not create destination .zso file."

            context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                localZso.inputStream().use { input -> input.copyTo(out) }
            } ?: return@withContext false to "Could not write .zso file."

            true to "Compressed into '$outputName'. The original ISO was left in place."
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun convertZsoToIso(
        treeUri: Uri,
        zsoDocumentId: String,
        gameId: String,
        title: String,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "zso2iso_${System.currentTimeMillis()}")
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext false to "Lost access to the drive."
            val sourceDoc = DocumentFile.fromSingleUri(context, Uri.parse(zsoDocumentId))
                ?: return@withContext false to "Could not access source .zso."

            workDir.mkdirs()
            val localZso = File(workDir, "source.zso")
            val localIso = File(workDir, "output.iso")
            val totalBytes = sourceDoc.length()

            context.contentResolver.openInputStream(sourceDoc.uri)?.use { input ->
                localZso.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied / 2, totalBytes)
                    }
                }
            } ?: return@withContext false to "Could not read source .zso."

            ZisoConverter.decompressZsoToIso(localZso, localIso) { done, total ->
                val safeTotal = if (total > 0) total else totalBytes
                onProgress(safeTotal / 2 + done / 2, safeTotal)
            }

            val outputName = GameIdUtil.buildOplFilename(gameId, title, "iso")
            root.findFile(outputName)?.delete()
            val destFile = root.createFile("application/octet-stream", outputName)
                ?: return@withContext false to "Could not create destination ISO file."

            context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                localIso.inputStream().use { input -> input.copyTo(out) }
            } ?: return@withContext false to "Could not write ISO file."

            true to "Decompressed into '$outputName'. The original .zso was left in place."
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        } finally {
            workDir.deleteRecursively()
        }
    }

        suspend fun convertBinCueToVcd(
        treeUri: Uri,
        cueDocumentId: String,
        parentDocumentId: String, // Added to fix the SAF parent limitation
        gameId: String?,
        title: String?,
        onProgress: (stage: String) -> Unit = {}
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "bin2vcd_${System.currentTimeMillis()}")
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext false to "Lost access to drive."

            val cueDoc = DocumentFile.fromSingleUri(context, Uri.parse(cueDocumentId))
                ?: return@withContext false to "Could not access .cue file."
            val cueName = cueDoc.name ?: return@withContext false to "Invalid .cue name."
            
            // SAF Fix: Resolve parent safely using the passed parentDocumentId
            val parentUri = Uri.parse(parentDocumentId)
            val parent = try {
                DocumentFile.fromTreeUri(context, parentUri)
            } catch (e: Exception) { null } 
            ?: try {
                DocumentFile.fromSingleUri(context, parentUri)
            } catch (e: Exception) { null }
            ?: cueDoc.parentFile 
            ?: return@withContext false to "Invalid parent directory."

            workDir.mkdirs()
            onProgress("Reading .cue sheet...")

            val cueBytes = context.contentResolver.openInputStream(cueDoc.uri)?.use { it.readBytes() }
                ?: return@withContext false to "Could not read .cue file."
            val cueText = String(cueBytes, Charsets.US_ASCII)

            val binNameMatch = Regex("FILE\\s+\"([^\"]+)\"\\s+BINARY", RegexOption.IGNORE_CASE).find(cueText)
                ?: return@withContext false to "Could not find BIN reference in .cue."
            val binName = binNameMatch.groupValues[1]

            val binDoc = parent.findFile(binName)
                ?: return@withContext false to "Referenced BIN file '$binName' missing."

            onProgress("Copying BIN/CUE locally...")
            val localCue = File(workDir, cueName)
            val localBin = File(workDir, binName)

            context.contentResolver.openInputStream(cueDoc.uri)?.use { input ->
                localCue.outputStream().use { output -> input.copyTo(output) }
            }
            context.contentResolver.openInputStream(binDoc.uri)?.use { input ->
                localBin.outputStream().use { output -> input.copyTo(output) }
            }

            onProgress("Converting to VCD...")
            val converter = Cue2PopsConverter(context)
            val outputDir = File(workDir, "out")
            val result = converter.convert(localCue, outputDir)

            if (!result.success || result.outputVcd == null) {
                return@withContext false to "Conversion failed."
            }

            onProgress("Copying VCD onto drive...")
            val popsDir = root.findFile("POPS") ?: root.createDirectory("POPS")
                ?: return@withContext false to "Could not create POPS folder."

            val vcdBaseName = if (gameId != null && !title.isNullOrBlank()) {
                GameIdUtil.buildOplFilename(gameId, title, "VCD")
            } else {
                result.outputVcd.name
            }

            popsDir.findFile(vcdBaseName)?.delete()
            val destVcd = popsDir.createFile("application/octet-stream", vcdBaseName)
                ?: return@withContext false to "Could not create destination VCD."

            context.contentResolver.openOutputStream(destVcd.uri)?.use { out ->
                result.outputVcd.inputStream().use { input -> input.copyTo(out) }
            }

            true to "Converted to POPS/$vcdBaseName."
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        } finally {
            workDir.deleteRecursively()
        }
     }

            suspend fun saveArtSetToDrive(treeUri: Uri, gameId: String, artSet: ArtSet, game: GameFile? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
                val artDir = root.findFile("ART") ?: root.createDirectory("ART") ?: return@withContext false

                // If it's a VCD game, use the filename base plus .ELF (e.g., Tomba.ELF)
                val basePrefix = if (game?.isVcdGame == true) {
                    val rawBase = game.displayName.substringBeforeLast('.')
                    "XX.$rawBase.ELF"
                } else {
                    gameId
                }

                var savedAny = false
                for (type in ArtType.entries) {
                    val localPath = artSet.pathFor(type) ?: continue
                    val fileName = "$basePrefix${type.oplSuffix}.png"

                    artDir.findFile("$basePrefix${type.oplSuffix}.jpg")?.delete()
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

    suspend fun generatePopsElfs(
        treeUri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext false to "Lost access to drive."
            val popsDir = root.findFile("POPS")
                ?: return@withContext false to "POPS folder not found."

            val baseElf = popsDir.findFile("POPSTARTER.ELF") ?: popsDir.findFile("POPSTARTER.elf")
                ?: root.findFile("POPSTARTER.ELF") ?: root.findFile("POPSTARTER.elf")
                ?: return@withContext false to "Base POPSTARTER.ELF not found."

            val vcds = popsDir.listFiles().filter { it.name?.lowercase()?.endsWith(".vcd") == true }
            var createdCount = 0
            for ((index, vcd) in vcds.withIndex()) {
                val vcdName = vcd.name ?: continue
                val baseName = vcdName.substringBeforeLast('.')
                val targetElfName = "XX.$baseName.ELF"

                if (popsDir.findFile(targetElfName) == null) {
                    val newElf = popsDir.createFile("application/octet-stream", targetElfName) ?: continue
                    context.contentResolver.openInputStream(baseElf.uri)?.use { input ->
                        context.contentResolver.openOutputStream(newElf.uri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    createdCount++
                }
                onProgress(index + 1, vcds.size)
            }

            true to "Created $createdCount missing POPStarter ELFs."
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun renameAssociatedElf(parent: DocumentFile?, oldVcdName: String, newVcdName: String) {
        if (parent == null) return
        val oldBase = oldVcdName.substringBeforeLast('.')
        val newBase = newVcdName.substringBeforeLast('.')
        // ELF file lookup and rename
        val elf = parent.findFile("XX.$oldBase.ELF") 
        ?: parent.findFile("XX.$oldBase.elf") 
        ?: parent.findFile("$oldBase.ELF") 
        ?: parent.findFile("$oldBase.elf") 
        ?: return

        try {
             elf.renameTo("XX.$newBase.ELF")
             } catch (e: Exception) {
             // Ignored
         }
    }
}
