package com.ps2manager.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ps2manager.app.data.ArtSet
import com.ps2manager.app.data.ArtType
import com.ps2manager.app.data.CoverArtFetcher
import com.ps2manager.app.data.GameFile
import com.ps2manager.app.data.GameRepository
import com.ps2manager.app.data.GameStatus
import com.ps2manager.app.data.TitleDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GameRepository(application)
    private val titleDb = TitleDatabase(application)
    private val artFetcher = CoverArtFetcher(application)

    private val _games = MutableStateFlow<List<GameFile>>(emptyList())
    val games: StateFlow<List<GameFile>> = _games.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow("Pick your USB/HDD folder to get started.")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _hasFolder = MutableStateFlow(false)
    val hasFolder: StateFlow<Boolean> = _hasFolder.asStateFlow()

    private val _artFetchProgress = MutableStateFlow<String?>(null)
    val artFetchProgress: StateFlow<String?> = _artFetchProgress.asStateFlow()

    private var selectedTreeUri: Uri? = null
    private var previewJob: Job? = null

    fun onFolderSelected(treeUri: Uri) {
        selectedTreeUri = treeUri
        _hasFolder.value = true
        viewModelScope.launch {
            _isScanning.value = true
            _statusMessage.value = "Scanning drive for game files..."
            val isoGames = repository.scanFolder(treeUri)
            val ulGames = repository.scanUlGames(treeUri)
            val vcdGames = repository.scanVcdGames(treeUri)
            val found = isoGames + ulGames + vcdGames
            _games.value = found
            _statusMessage.value = "Found ${found.size} game file(s). Loading title database..."

            titleDb.ensureLoaded()

            if (titleDb.entryCount == 0) {
                _statusMessage.value = "Couldn't load the online title database: " +
                    (titleDb.lastError ?: "unknown error") +
                    " — you can still set titles manually below."
            } else {
                _statusMessage.value = "Matching titles..."
            }

            val updated = found.map { game ->
                if (game.gameId == null) {
                    game.copy(status = GameStatus.NO_MATCH)
                } else {
                    val title = titleDb.lookupTitle(game.gameId)
                    if (title != null) {
                        game.copy(matchedTitle = title, status = GameStatus.MATCHED)
                    } else {
                        game.copy(status = GameStatus.NO_MATCH)
                    }
                }
            }
            _games.value = updated
            _isScanning.value = false
            _statusMessage.value = if (titleDb.entryCount == 0) {
                "Online database unreachable (${titleDb.lastError ?: "unknown error"}). Tap any game below to set its title manually."
            } else {
                "Done. ${updated.count { it.status == GameStatus.MATCHED }} of ${updated.size} matched."
            }
        }
    }

    fun setManualTitle(game: GameFile, title: String) {
        if (title.isBlank()) return
        updateGame(game.documentId) { it.copy(matchedTitle = title, status = GameStatus.MATCHED) }
    }

    fun renameOnly(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return
        val title = game.matchedTitle ?: return

        // Short-circuit physical file rename for VCD files
        if (game.isVcdGame) {
            updateGame(game.documentId) { it.copy(status = GameStatus.RENAMED) }
            _statusMessage.value = "Custom title saved! Click 'Generate conf_apps.cfg for OPL' to apply."
            return
        }

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.LOOKING_UP) }

            val (renamed, error) = when {
                game.isUlGame -> repository.renameUlGame(treeUri, gameId, title)
                else -> {
                    val extension = game.displayName.substringAfterLast('.', "iso")
                    repository.renameFile(game.documentId, gameId, title, extension)
                }
            }
            _artFetchProgress.value = null

            updateGame(game.documentId) {
                it.copy(status = if (renamed) GameStatus.RENAMED else GameStatus.ERROR, lastError = error)
            }
        }
    }

    fun selectActiveBackground(game: GameFile, bgUrl: String) {
        val gameId = game.gameId ?: return
        viewModelScope.launch {
            val localPath = artFetcher.downloadSpecificBackground(gameId, bgUrl)
            
            if (localPath != null) {
                updateGame(game.documentId) { g ->
                    val currentArt = g.artSet ?: return@updateGame g
                    g.copy(artSet = currentArt.copy(background = localPath))
                }
            }
        }
    }

    fun generateConfAppsCfg() {
        val treeUri = selectedTreeUri ?: return
        viewModelScope.launch {
            _isScanning.value = true
            _statusMessage.value = "Generating conf_apps.cfg..."
            val (ok, message) = repository.generateConfAppsCfg(treeUri, _games.value)
            _isScanning.value = false
            _statusMessage.value = message ?: if (ok) "conf_apps.cfg created." else "Failed to create conf_apps.cfg."
        }
    }

    fun startPreview(game: GameFile) {
        val gameId = game.gameId ?: return
        previewJob = viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.LOOKING_UP) }
            val artSet = artFetcher.fetchAllArt(gameId) { label, fileName, step, total ->
                _artFetchProgress.value = "($step of $total) $label: $fileName"
            }
            _artFetchProgress.value = null
            updateGame(game.documentId) { it.copy(artSet = artSet, status = GameStatus.PREVIEW) }
        }
    }

    fun cancelArtFetch(game: GameFile) {
        previewJob?.cancel()
        _artFetchProgress.value = null
        updateGame(game.documentId) { it.copy(status = GameStatus.MATCHED) }
    }

    fun cancelPreview(game: GameFile) {
        updateGame(game.documentId) { it.copy(status = GameStatus.MATCHED) }
    }

    fun replaceArt(game: GameFile, type: ArtType, bytes: ByteArray, ext: String) {
        val gameId = game.gameId ?: return
        viewModelScope.launch {
            val newPath = artFetcher.saveManualArt(gameId, type, bytes, ext)
            updateGame(game.documentId) { g ->
                val current = g.artSet ?: ArtSet()
                val updated = when (type) {
                    ArtType.COVER -> current.copy(cover = newPath, background = newPath)
                    ArtType.BACKGROUND -> current.copy(background = newPath)
                    ArtType.ICON -> current.copy(icon = newPath)
                    ArtType.SCREENSHOT -> current.copy(screenshot = newPath)
                }
                g.copy(artSet = updated)
            }
        }
    }

    fun searchTitles(query: String): List<Pair<String, String>> = titleDb.searchTitles(query)

    fun useArtFromGameId(game: GameFile, otherGameId: String) {
        viewModelScope.launch {
            val artSet = artFetcher.fetchAllArt(otherGameId)
            updateGame(game.documentId) { it.copy(artSet = artSet) }
        }
    }

    fun confirmApply(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return
        val artSet = game.artSet ?: ArtSet()

        viewModelScope.launch {
            val saved = repository.saveArtSetToDrive(treeUri, gameId, artSet, game)
            updateGame(game.documentId) {
                it.copy(
                    coverArtLocalPath = artSet.cover,
                    status = if (saved) GameStatus.MATCHED else GameStatus.ERROR
                )
            }
        }
    }

    fun applyGame(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return
        val title = game.matchedTitle ?: return

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.LOOKING_UP) }

            val artSet = artFetcher.fetchAllArt(gameId) { label, fileName, step, total ->
                _artFetchProgress.value = "${game.matchedTitle ?: gameId}: ($step of $total) $label: $fileName"
            }
            _artFetchProgress.value = null
            
            repository.saveArtSetToDrive(treeUri, gameId, artSet, game)
            updateGame(game.documentId) { it.copy(artSet = artSet, coverArtLocalPath = artSet.cover) }

            val (renamed, error) = when {
                game.isUlGame -> repository.renameUlGame(treeUri, gameId, title)
                game.isVcdGame -> true to null // Skip physical file rename for VCD files here too
                else -> {
                    val extension = game.displayName.substringAfterLast('.', "iso")
                    repository.renameFile(game.documentId, gameId, title, extension)
                }
            }
            _artFetchProgress.value = null

            updateGame(game.documentId) {
                it.copy(status = if (renamed) GameStatus.RENAMED else GameStatus.ERROR, lastError = error)
            }
        }
    }

    fun applyAllMatched() {
        viewModelScope.launch {
            _games.value.filter { it.status == GameStatus.MATCHED }.forEach { applyGame(it) }
        }
    }

    fun convertIsoToUl(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return
        val title = game.matchedTitle ?: return

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.LOOKING_UP) }
            val (ok, message) = repository.convertIsoToUl(treeUri, game.documentId, gameId, title) { copied, total ->
                val pct = if (total > 0) (copied * 100 / total) else 0
                _artFetchProgress.value = "Splitting to UL format: $pct% (${copied / 1_000_000}MB / ${total / 1_000_000}MB)"
            }
            _artFetchProgress.value = null
            updateGame(game.documentId) {
                it.copy(status = if (ok) GameStatus.RENAMED else GameStatus.ERROR, lastError = message)
            }
            if (ok) onFolderSelected(treeUri)
        }
    }
    
    fun regenerateUlConfig() {
        val treeUri = selectedTreeUri ?: return
        viewModelScope.launch {
            _isScanning.value = true
            _statusMessage.value = "Regenerating ul.cfg..."
            val (ok, message) = repository.regenerateUlConfig(treeUri)
            _isScanning.value = false
            _statusMessage.value = message ?: if (ok) "ul.cfg updated." else "Failed to update ul.cfg."
            if (ok) onFolderSelected(treeUri)
        }
    }

    fun convertUlToIso(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.LOOKING_UP) }
            val (ok, message) = repository.convertUlToIso(treeUri, gameId) { copied, total ->
                val pct = if (total > 0) (copied * 100 / total) else 0
                _artFetchProgress.value = "Reassembling to ISO: $pct% (${copied / 1_000_000}MB / ${total / 1_000_000}MB)"
            }
            _artFetchProgress.value = null
            updateGame(game.documentId) {
                it.copy(status = if (ok) GameStatus.RENAMED else GameStatus.ERROR, lastError = message)
            }
            if (ok) onFolderSelected(treeUri)
        }
    }

    fun convertIsoToZso(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return
        val title = game.matchedTitle ?: return

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.CONVERTING) }
            val (ok, message) = repository.convertIsoToZso(treeUri, game.documentId, gameId, title) { copied, total ->
                val pct = if (total > 0) (copied * 100 / total) else 0
                _artFetchProgress.value = "Compressing to ZSO: $pct% (${copied / 1_000_000}MB / ${total / 1_000_000}MB)"
            }
            _artFetchProgress.value = null
            updateGame(game.documentId) {
                it.copy(status = if (ok) GameStatus.CONVERTED else GameStatus.ERROR, lastError = message)
            }
            if (ok) onFolderSelected(treeUri)
        }
    }

    fun convertZsoToIso(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return
        val title = game.matchedTitle ?: return

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.CONVERTING) }
            val (ok, message) = repository.convertZsoToIso(treeUri, game.documentId, gameId, title) { copied, total ->
                val pct = if (total > 0) (copied * 100 / total) else 0
                _artFetchProgress.value = "Decompressing to ISO: $pct% (${copied / 1_000_000}MB / ${total / 1_000_000}MB)"
            }
            _artFetchProgress.value = null
            updateGame(game.documentId) {
                it.copy(status = if (ok) GameStatus.CONVERTED else GameStatus.ERROR, lastError = message)
            }
            if (ok) onFolderSelected(treeUri)
        }
    }

    fun convertBinCueToVcd(game: GameFile) {
        val treeUri = selectedTreeUri ?: return

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.CONVERTING) }
            val (ok, message) = repository.convertBinCueToVcd(
                treeUri, 
                game.documentId, 
                game.parentDocumentId!!, 
                game.gameId, 
                game.matchedTitle ?: game.currentTitle
            ) { stage ->
                _artFetchProgress.value = stage
            }
            _artFetchProgress.value = null
            updateGame(game.documentId) {
                it.copy(status = if (ok) GameStatus.CONVERTED else GameStatus.ERROR, lastError = message)
            }
        }
    }     

    fun generatePopsElfs() {
        val treeUri = selectedTreeUri ?: return
        viewModelScope.launch {
            _isScanning.value = true
            _statusMessage.value = "Generating POPStarter ELFs..."
            val (ok, message) = repository.generatePopsElfs(treeUri) { current, total ->
                _statusMessage.value = "Generating ELFs: $current / $total"
            }
            _isScanning.value = false
            _statusMessage.value = message ?: if (ok) "Finished generating ELFs." else "Failed to generate ELFs."
            if (ok) onFolderSelected(treeUri)
        }
    }

    private fun updateGame(documentId: String, transform: (GameFile) -> GameFile) {
        _games.value = _games.value.map { if (it.documentId == documentId) transform(it) else it }
    }
}
