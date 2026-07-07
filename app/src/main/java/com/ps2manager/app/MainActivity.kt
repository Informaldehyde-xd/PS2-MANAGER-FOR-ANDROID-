@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ps2manager.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.ps2manager.app.data.ArtType
import com.ps2manager.app.data.GameFile
import com.ps2manager.app.data.GameStatus
import com.ps2manager.app.ui.LibraryViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val folderPicker = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.onFolderSelected(uri)
            }
        }

        setContent {
            MaterialTheme {
                var previewGame by remember { mutableStateOf<GameFile?>(null) }
                var pendingReplaceType by remember { mutableStateOf<ArtType?>(null) }
                var titleEditGame by remember { mutableStateOf<GameFile?>(null) }
                val context = LocalContext.current

                val imagePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    val game = previewGame
                    val type = pendingReplaceType
                    if (uri != null && game != null && type != null) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                        val ext = if (mime.contains("png")) "png" else "jpg"
                        if (bytes != null) {
                            viewModel.replaceArt(game, type, bytes, ext)
                        }
                    }
                    pendingReplaceType = null
                }

                LibraryScreen(
                    viewModel = viewModel,
                    onPickFolder = { folderPicker.launch(null) },
                    onRename = { game -> viewModel.renameOnly(game) },
                    onStartArtPreview = { game -> viewModel.startPreview(game) },
                    onPreviewReady = { game -> previewGame = game },
                    onEditTitle = { game -> titleEditGame = game }
                )

                if (titleEditGame != null) {
                    TitleEditDialog(
                        game = titleEditGame!!,
                        onSearch = { query -> viewModel.searchTitles(query) },
                        onSave = { title ->
                            viewModel.setManualTitle(titleEditGame!!, title)
                            titleEditGame = null
                        },
                        onCancel = { titleEditGame = null }
                    )
                }

                val liveGames by viewModel.games.collectAsState()
                val activePreview = liveGames.find { it.documentId == previewGame?.documentId }
                if (activePreview != null && activePreview.status == GameStatus.PREVIEW) {
                    ArtPreviewDialog(
                        game = activePreview,
                        onReplaceArt = { type ->
                            pendingReplaceType = type
                            imagePicker.launch("image/*")
                        },
                        onSearch = { query -> viewModel.searchTitles(query) },
                        onPickAlternate = { gameId -> viewModel.useArtFromGameId(activePreview, gameId) },
                        onConfirm = {
                            viewModel.confirmApply(activePreview)
                            previewGame = null
                        },
                        onCancel = {
                            viewModel.cancelPreview(activePreview)
                            previewGame = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onPickFolder: () -> Unit,
    onRename: (GameFile) -> Unit,
    onStartArtPreview: (GameFile) -> Unit,
    onPreviewReady: (GameFile) -> Unit,
    onEditTitle: (GameFile) -> Unit
) {
    val games by viewModel.games.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val status by viewModel.statusMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("PS2 Manager") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {

            Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                Text("Pick USB / HDD Folder")
            }

            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)

            if (isScanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (games.any { it.status == GameStatus.MATCHED }) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.applyAllMatched() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Rename + Fetch Art for All Matched")
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn {
                items(games) { game ->
                    GameRow(
                        game = game,
                        onRename = { onRename(game) },
                        onCoverArt = {
                            onPreviewReady(game)
                            onStartArtPreview(game)
                        },
                        onTap = { onEditTitle(game) }
                    )
                    Divider()
                }
            }
        }
    }
}

@Composable
fun GameRow(game: GameFile, onRename: () -> Unit, onCoverArt: () -> Unit, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onTap() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (game.coverArtLocalPath != null) {
            AsyncImage(
                model = game.coverArtLocalPath,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("?", style = MaterialTheme.typography.titleLarge)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                game.matchedTitle ?: game.currentTitle ?: game.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                (game.gameId ?: "Unrecognized filename") + if (game.isUlGame) "  (UL)" else "",
                style = MaterialTheme.typography.bodySmall
            )
            Text(statusLabel(game.status), style = MaterialTheme.typography.labelSmall)
        }

        if (game.status == GameStatus.MATCHED) {
            Column {
                Button(onClick = onRename, modifier = Modifier.padding(bottom = 4.dp)) { Text("Rename") }
                OutlinedButton(onClick = onCoverArt) { Text("Cover Art") }
            }
        }
    }
}

@Composable
fun ArtPreviewDialog(
    game: GameFile,
    onReplaceArt: (ArtType) -> Unit,
    onSearch: (String) -> List<Pair<String, String>>,
    onPickAlternate: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(
                    game.matchedTitle ?: "Preview",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArtThumb("Cover", game.artSet?.cover) { onReplaceArt(ArtType.COVER) }
                    ArtThumb("Background", game.artSet?.background) { onReplaceArt(ArtType.BACKGROUND) }
                    ArtThumb("Icon", game.artSet?.icon) { onReplaceArt(ArtType.ICON) }
                    ArtThumb("Screenshot", game.artSet?.screenshot) { onReplaceArt(ArtType.SCREENSHOT) }
                }

                Spacer(Modifier.height(16.dp))
                Text("Not the right game? Search for a different title's art:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        searchResults = onSearch(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Type a game title...") }
                )
                searchResults.take(5).forEach { (id, title) ->
                    Text(
                        title,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    )
                    TextButton(onClick = { onPickAlternate(id) }) {
                        Text("Use this game's art")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text("Save Cover Art") }
                }
            }
        }
    }
}

@Composable
fun ArtThumb(label: String, path: String?, onTap: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (path != null) {
                AsyncImage(model = path, contentDescription = label, modifier = Modifier.size(64.dp))
            } else {
                Text("—")
            }
        }
        TextButton(onClick = onTap) { Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun TitleEditDialog(
    game: GameFile,
    onSearch: (String) -> List<Pair<String, String>>,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf(game.matchedTitle ?: game.currentTitle ?: "") }
    var results by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Set Title", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(game.gameId ?: "Unrecognized", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        results = onSearch(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Type or search a title...") }
                )

                results.take(6).forEach { (_, title) ->
                    TextButton(
                        onClick = { text = title; results = emptyList() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(title, modifier = Modifier.fillMaxWidth())
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save Title") }
                }
            }
        }
    }
}

private fun statusLabel(status: GameStatus): String = when (status) {
    GameStatus.PENDING -> "Pending"
    GameStatus.LOOKING_UP -> "Fetching title & art…"
    GameStatus.MATCHED -> "Match found — ready to apply"
    GameStatus.PREVIEW -> "Reviewing art…"
    GameStatus.NO_MATCH -> "No match found in database"
    GameStatus.RENAMED -> "Renamed ✓"
    GameStatus.ERROR -> "Error — try again"
}
