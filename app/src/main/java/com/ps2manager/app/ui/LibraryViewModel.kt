package com.ps2manager.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ps2manager.app.data.CoverArtFetcher
import com.ps2manager.app.data.GameFile
import com.ps2manager.app.data.GameRepository
import com.ps2manager.app.data.GameStatus
import com.ps2manager.app.data.TitleDatabase
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

    private var selectedTreeUri: Uri? = null

    fun onFolderSelected(treeUri: Uri) {
        selectedTreeUri = treeUri
        viewModelScope.launch {
            _isScanning.value = true
            _statusMessage.value = "Scanning drive for game files..."
            val isoGames = repository.scanFolder(treeUri)
            val ulGames = repository.scanUlGames(treeUri)
            val found = isoGames + ulGames
            _games.value = found
            _statusMessage.value = "Found ${found.size} game file(s). Loading title database..."

            titleDb.ensureLoaded()
            _statusMessage.value = "Matching titles..."

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
            _statusMessage.value = "Done. ${updated.count { it.status == GameStatus.MATCHED }} of ${updated.size} matched."
        }
    }

    /** Downloads art (if missing), then renames the file and saves art onto the drive. */
    fun applyGame(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return
        val title = game.matchedTitle ?: return

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.LOOKING_UP) }

            val artPath = artFetcher.fetchCoverArt(gameId)
            if (artPath != null) {
                repository.saveArtToDrive(treeUri, gameId, artPath)
                updateGame(game.documentId) { it.copy(coverArtLocalPath = artPath) }
            }

            val renamed = if (game.isUlGame) {
                repository.renameUlGame(treeUri, gameId, title)
            } else {
                val extension = game.displayName.substringAfterLast('.', "iso")
                repository.renameFile(game.documentId, gameId, title, extension)
            }

            updateGame(game.documentId) {
                it.copy(status = if (renamed) GameStatus.RENAMED else GameStatus.ERROR)
            }
        }
    }

    /** Runs applyGame for every matched-but-not-yet-renamed game. */
    fun applyAllMatched() {
        viewModelScope.launch {
            _games.value.filter { it.status == GameStatus.MATCHED }.forEach { applyGame(it) }
        }
    }

    private fun updateGame(documentId: String, transform: (GameFile) -> GameFile) {
        _games.value = _games.value.map { if (it.documentId == documentId) transform(it) else it }
    }
}
