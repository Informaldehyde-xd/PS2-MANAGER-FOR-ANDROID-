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

    private val _artFetchProgress = MutableStateFlow<String?>(null)
    val artFetchProgress: StateFlow<String?> = _artFetchProgress.asStateFlow()

    private var selectedTreeUri: Uri? = null
    private var previewJob: Job? = null

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

    /** Manually sets a game's title (typed or picked from search), enabling Rename/Cover Art regardless of auto-match. */
    fun setManualTitle(game: GameFile, title: String) {
        if (title.isBlank()) return
        updateGame(game.documentId) { it.copy(matchedTitle = title, status = GameStatus.MATCHED) }
    }

    /** Renames the game using its matched title, without touching cover art at all. */
    fun renameOnly(game: GameFile) {
        val treeUri = selectedTreeUri ?: return
        val gameId = game.gameId ?: return
        val title = game.matchedTitle ?: return

        viewModelScope.launch {
            updateGame(game.documentId) { it.copy(status = GameStatus.LOOKING_UP) }

            val (renamed, error) = if (game.isUlGame) {
                repository.renameUlGame(treeUri, gameId, title)
            } else {
                val extension = game.displayName.substringAfterLast('.', "iso")
                repository.renameFile(game.documentId, gameId, title, extension, game.parentDocumentId)
            }

            updateGame(game.documentId) {
                it.copy(status = if (renamed) GameStatus.RENAMED else GameStatus.ERROR, lastError = error)
            }
        }
    }

    /** Step 1 of applying a single game: fetch all art types and show them for review. */
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

    /** Cancels an in-progress art fetch (e.g. stuck on a slow connection) and returns to MATCHED. */
    fun cancelArtFetch(game: GameFile) {
        previewJob?.cancel()
        _artFetchProgress.value = null
        updateGame(game.documentId) { it.copy(status = GameStatus.MATCHED) }
    }

    /** Cancels a preview without writing anything to the drive. */
    fun cancelPreview(game: GameFile) {
        updateGame(game.documentId) { it.copy(status = GameStatus.MATCHED) }
    }

    /** Replaces one art type with a user-picked image while still in preview. */
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

    /** Searches the loaded title database, e.g. to find a different game's art to borrow. */
    fun searchTitles(query: String): List<Pair<String, String>> = titleDb.searchTitles(query)

    /** Loads a different Game ID's art into the current preview (e.g. after a manual search). */
    fun useArtFromGameId(game: GameFile, otherGameId: String) {
        viewModelScope.launch {
            val artSet = artFetcher.fetchAllArt(otherGameId)
            updateGame(game.documentId) { it.copy(artSet = artSet) }
        }
    }

    /** Step 2: writes the (possibly edited) art set to the drive. Renaming is a separate action now. */
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

    /** Downloads art (if missing), then renames the file and saves art onto the drive — no preview, used for batch apply. */
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
            repository.saveArtSetToDrive(treeUri, gameId, artSet)
            updateGame(game.documentId) { it.copy(artSet = artSet, coverArtLocalPath = artSet.cover) }

            val (renamed, error) = if (game.isUlGame) {
                repository.renameUlGame(treeUri, gameId, title)
            } else {
                val extension = game.displayName.substringAfterLast('.', "iso")
                repository.renameFile(game.documentId, gameId, title, extension, game.parentDocumentId)
            }

            updateGame(game.documentId) {
                it.copy(status = if (renamed) GameStatus.RENAMED else GameStatus.ERROR, lastError = error)
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
