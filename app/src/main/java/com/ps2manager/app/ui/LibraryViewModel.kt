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

    fun renameOnly(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return
        val title = game.matchedTitle ?: return

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.LOOKING_UP) }

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

    fun startPreview(game: GameFile) {
        val gameId = game.gameId ?: return
        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.LOOKING_UP) }
            val artSet = artFetcher.fetchAllArt(gameId)
            updateGame(game.documentId) { it.copy(artSet = artSet, status = GameStatus.PREVIEW) }
        }
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
                    ArtType.COVER -> current.copy(cover = newPath)
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
            val saved = repository.saveArtSetToDrive(treeUri, gameId, artSet)
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

            val artSet = artFetcher.fetchAllArt(gameId)
            repository.saveArtSetToDrive(treeUri, gameId, artSet)
            updateGame(game.documentId) { it.copy(artSet = artSet, coverArtLocalPath = artSet.cover) }

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

    fun applyAllMatched() {
        viewModelScope.launch {
            _games.value.filter { it.status == GameStatus.MATCHED }.forEach { applyGame(it) }
        }
    }

    private fun updateGame(documentId: String, transform: (GameFile) -> GameFile) {
        _games.value = _games.value.map { if (it.documentId == documentId) transform(it) else it }
    }
}
