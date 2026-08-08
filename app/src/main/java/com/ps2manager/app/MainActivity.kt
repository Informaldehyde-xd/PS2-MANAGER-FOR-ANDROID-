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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.ps2manager.app.data.ArtType
import com.ps2manager.app.data.GameFile
import com.ps2manager.app.data.GameStatus
import com.ps2manager.app.ui.LibraryViewModel
import com.ps2manager.app.ui.theme.Ps2Accent
import com.ps2manager.app.ui.theme.Ps2Background
import com.ps2manager.app.ui.theme.Ps2Glow
import com.ps2manager.app.ui.theme.Ps2Line
import com.ps2manager.app.ui.theme.Ps2LineActive
import com.ps2manager.app.ui.theme.Ps2ManagerTheme
import com.ps2manager.app.ui.theme.Ps2OnBackground
import com.ps2manager.app.ui.theme.Ps2OnSurfaceMuted
import com.ps2manager.app.ui.theme.Ps2Primary
import com.ps2manager.app.ui.theme.Ps2Success
import com.ps2manager.app.ui.theme.Ps2Surface
import com.ps2manager.app.ui.theme.Ps2SurfaceElevated

/** The three "control deck" workspaces shown on the home menu. */
enum class ControlDeckSection(val label: String, val icon: String) {
    CONVERSION("File Conversion", "🛠️"),
    COVERS("Cover Arts", "🎨"),
    RENAME("Rename", "🏷️")
}

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
            Ps2ManagerTheme {
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
                val artFetchProgress by viewModel.artFetchProgress.collectAsState()
                val activePreview = liveGames.find { it.documentId == previewGame?.documentId }

                if (activePreview != null && activePreview.status == GameStatus.LOOKING_UP) {
                    ArtLoadingDialog(progressLabel = artFetchProgress)
                }

                if (activePreview != null && activePreview.status == GameStatus.PREVIEW) {
                    ArtPreviewDialog(
                        game = activePreview,
                        onReplaceArt = { type ->
                            pendingReplaceType = type
                            imagePicker.launch("image/*")
                        },
                        onSearch = { query -> viewModel.searchTitles(query) },
                        onPickAlternate = { gameId -> viewModel.useArtFromGameId(activePreview, gameId) },
                        onSelectBackground = { bgUrl -> viewModel.selectActiveBackground(activePreview, bgUrl) },
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
    val hasFolder by viewModel.hasFolder.collectAsState()
    var selectedSection by remember { mutableStateOf(ControlDeckSection.CONVERSION) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark()
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "CONSOLE UTILITY",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ps2Accent,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "PS2 MANAGER",
                                style = MaterialTheme.typography.titleLarge,
                                color = Ps2OnSurfaceMuted.copy(alpha = 0.95f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                actions = {
                    SystemStatusPill(scanning = isScanning)
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ps2Surface,
                    titleContentColor = Ps2Accent
                )
            )
        },
        containerColor = Ps2Background
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Ps2Background, Ps2Surface.copy(alpha = 0.35f))
                    )
                )
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    Button(
                        onClick = onPickFolder,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ps2Primary)
                    ) {
                        Text("PICK USB / HDD FOLDER", style = MaterialTheme.typography.labelLarge)
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = Ps2OnSurfaceMuted)

                    if (isScanning) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Ps2Accent,
                            trackColor = Ps2SurfaceElevated
                        )
                    }

                    if (hasFolder) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "MAIN CONTROL DECK",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ps2Accent,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Select a workspace",
                            style = MaterialTheme.typography.titleMedium,
                            color = Ps2OnSurfaceMuted,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))

                        ControlDeckMenu(
                            selected = selectedSection,
                            onSelect = { selectedSection = it }
                        )

                        Spacer(Modifier.height(14.dp))

                        when (selectedSection) {
                            ControlDeckSection.CONVERSION -> ConversionPanel(
                                games = games,
                                onConvertIsoToUl = { viewModel.convertIsoToUl(it) },
                                onConvertUlToIso = { viewModel.convertUlToIso(it) },
                                onConvertBinToVcd = { viewModel.convertBinCueToVcd(it) },
                                onConvertIsoToZso = { viewModel.convertIsoToZso(it) },
                                onConvertZsoToIso = { viewModel.convertZsoToIso(it) }
                            )
                            ControlDeckSection.COVERS -> CoversPanel(
                                games = games,
                                onFetchCoverArt = { game ->
                                    onPreviewReady(game)
                                    onStartArtPreview(game)
                                }
                            )
                            ControlDeckSection.RENAME -> RenamePanel(
                                games = games,
                                isScanning = isScanning,
                                onRename = onRename,
                                onRegenerateUlConfig = { viewModel.regenerateUlConfig() },
                                onGeneratePopsElfs = { viewModel.generatePopsElfs() },
                                onGenerateConfAppsCfg = { viewModel.generateConfAppsCfg() },
                                onApplyAllMatched = { viewModel.applyAllMatched() }
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    if (hasFolder) {
                        Text(
                            "FULL LIBRARY",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ps2Accent,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    if (games.isEmpty()) {
                        EmptyLibraryPlaceholder()
                    }
                }

                items(games) { game ->
                    GameRow(
                        game = game,
                        onRename = { onRename(game) },
                        onCoverArt = {
                            onPreviewReady(game)
                            onStartArtPreview(game)
                        },
                        onTap = { onEditTitle(game) },
                        onConvertIsoToUl = { viewModel.convertIsoToUl(game) },
                        onConvertUlToIso = { viewModel.convertUlToIso(game) },
                        onConvertBinToVcd = { viewModel.convertBinCueToVcd(game) },
                        onConvertIsoToZso = { viewModel.convertIsoToZso(game) },
                        onConvertZsoToIso = { viewModel.convertZsoToIso(game) }
                    )
                    Divider(color = Ps2SurfaceElevated)
                }
            }
        }
    }
}

/** Small glowing square logo mark, echoing the reference theme's brand-mark. */
@Composable
fun BrandMark() {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Ps2SurfaceElevated, Ps2Background)
                )
            )
            .border(BorderStroke(1.dp, Ps2LineActive), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ps2Accent)
        )
    }
}

/** "SYSTEM READY" / "SCANNING" status chip shown in the header. */
@Composable
fun SystemStatusPill(scanning: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Ps2SurfaceElevated.copy(alpha = 0.7f))
            .border(BorderStroke(1.dp, Ps2Line), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (scanning) Ps2Primary else Ps2Success)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (scanning) "SCANNING" else "SYSTEM READY",
            style = MaterialTheme.typography.labelSmall,
            color = Ps2Accent,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * The three home-menu cards: File Conversion, Cover Arts, Rename.
 * Tapping one selects the workspace and shows its detail panel below.
 */
@Composable
fun ControlDeckMenu(
    selected: ControlDeckSection,
    onSelect: (ControlDeckSection) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ControlDeckSection.values().forEachIndexed { index, section ->
            val isActive = section == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            colors = if (isActive) {
                                listOf(Ps2Glow, Ps2SurfaceElevated)
                            } else {
                                listOf(Ps2Surface, Ps2Background)
                            }
                        )
                    )
                    .border(
                        BorderStroke(1.dp, if (isActive) Ps2LineActive else Ps2Line),
                        RoundedCornerShape(16.dp)
                    )
                    .clickable { onSelect(section) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "0${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) Ps2Accent else Ps2OnSurfaceMuted,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ps2SurfaceElevated)
                        .border(BorderStroke(1.dp, Ps2Line), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(section.icon, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        section.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = Ps2OnBackground,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when (section) {
                            ControlDeckSection.CONVERSION -> "ISO ⇄ UL, ISO ⇄ ZSO, BIN/CUE → VCD"
                            ControlDeckSection.COVERS -> "Fetch and apply artwork"
                            ControlDeckSection.RENAME -> "ul.cfg, conf_apps.cfg, POPStarter, titles"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Ps2OnSurfaceMuted
                    )
                }
                if (isActive) {
                    Text("›", color = Ps2Accent, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Bordered, slightly glowing panel used to host each workspace's detail view. */
@Composable
fun ConsoleFrame(
    kicker: String,
    title: String,
    description: String,
    icon: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(colors = listOf(Ps2Surface, Ps2Background))
            )
            .border(BorderStroke(1.dp, Ps2Line), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text(
            kicker,
            style = MaterialTheme.typography.labelSmall,
            color = Ps2Accent,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ps2OnBackground,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ps2OnSurfaceMuted
                )
            }
            Text(icon, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** File Conversion workspace: ISO → UL, UL → ISO, ISO → ZSO, ZSO → ISO, and BIN/CUE → VCD. */
@Composable
fun ConversionPanel(
    games: List<GameFile>,
    onConvertIsoToUl: (GameFile) -> Unit,
    onConvertUlToIso: (GameFile) -> Unit,
    onConvertBinToVcd: (GameFile) -> Unit,
    onConvertIsoToZso: (GameFile) -> Unit,
    onConvertZsoToIso: (GameFile) -> Unit
) {
    val isoToUlEligible = games.filter {
        it.status == GameStatus.MATCHED && !it.isUlGame && !it.isVcdGame && !it.isBinCueGame && !it.isZsoGame
    }
    val ulToIsoEligible = games.filter { it.status == GameStatus.MATCHED && it.isUlGame }
    val binToVcdEligible = games.filter { it.isBinCueGame && it.status != GameStatus.LOOKING_UP }
    val isoToZsoEligible = games.filter {
        it.status == GameStatus.MATCHED && !it.isUlGame && !it.isVcdGame && !it.isBinCueGame && !it.isZsoGame
    }
    val zsoToIsoEligible = games.filter {
        it.status == GameStatus.MATCHED && it.isZsoGame && it.status != GameStatus.CONVERTING
    }

    ConsoleFrame(
        kicker = "01 / Workspace active",
        title = "File Conversion",
        description = "Convert and prepare your game archive with a focused, streamlined workflow.",
        icon = "🛠️"
    ) {
        ConversionGroup(
            label = "ISO → UL",
            hint = "Split a plain ISO into USBExtreme/UL parts",
            games = isoToUlEligible,
            buttonLabel = "Split to UL",
            onConvert = onConvertIsoToUl
        )
        Spacer(Modifier.height(14.dp))
        ConversionGroup(
            label = "UL → ISO",
            hint = "Reassemble split UL parts back into one ISO",
            games = ulToIsoEligible,
            buttonLabel = "Reassemble to ISO",
            onConvert = onConvertUlToIso
        )
        Spacer(Modifier.height(14.dp))
        ConversionGroup(
            label = "ISO → ZSO",
            hint = "Compress a plain ISO into a smaller .zso image",
            games = isoToZsoEligible,
            buttonLabel = "Compress to ZSO",
            onConvert = onConvertIsoToZso
        )
        Spacer(Modifier.height(14.dp))
        ConversionGroup(
            label = "ZSO → ISO",
            hint = "Decompress a .zso image back into a plain ISO",
            games = zsoToIsoEligible,
            buttonLabel = "Decompress to ISO",
            onConvert = onConvertZsoToIso
        )
        Spacer(Modifier.height(14.dp))
        ConversionGroup(
            label = "BIN/CUE → VCD",
            hint = "Convert a PS1 BIN/CUE dump into a POPS-ready VCD",
            games = binToVcdEligible,
            buttonLabel = "Convert to VCD",
            onConvert = onConvertBinToVcd
        )
    }
}

@Composable
fun ConversionGroup(
    label: String,
    hint: String,
    games: List<GameFile>,
    buttonLabel: String,
    onConvert: (GameFile) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ps2Background.copy(alpha = 0.4f))
            .border(BorderStroke(1.dp, Ps2Line), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Ps2Accent, fontWeight = FontWeight.Bold)
            Text(
                "${games.size} eligible",
                style = MaterialTheme.typography.labelSmall,
                color = Ps2OnSurfaceMuted
            )
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Ps2OnSurfaceMuted)
        if (games.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No games currently need this conversion.",
                style = MaterialTheme.typography.bodySmall,
                color = Ps2OnSurfaceMuted
            )
        } else {
            Spacer(Modifier.height(8.dp))
            games.forEach { game ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        game.matchedTitle ?: game.currentTitle ?: game.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ps2OnBackground,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { onConvert(game) },
                        enabled = game.status != GameStatus.CONVERTING
                    ) {
                        Text(if (game.status == GameStatus.CONVERTING) "Converting..." else buttonLabel)
                    }
                }
            }
        }
    }
}

/** Cover Arts workspace: fetch and apply artwork per game. */
@Composable
fun CoversPanel(
    games: List<GameFile>,
    onFetchCoverArt: (GameFile) -> Unit
) {
    val eligible = games.filter {
        it.status == GameStatus.MATCHED || it.status == GameStatus.RENAMED || it.status == GameStatus.CONVERTED
    }

    ConsoleFrame(
        kicker = "02 / Artwork station",
        title = "Cover Arts",
        description = "Keep every title visually complete with a dedicated artwork workspace.",
        icon = "🎨"
    ) {
        if (eligible.isEmpty()) {
            Text(
                "Once games are matched, they'll appear here so you can fetch cover art, backgrounds, icons, and screenshots.",
                style = MaterialTheme.typography.bodySmall,
                color = Ps2OnSurfaceMuted
            )
        } else {
            eligible.forEach { game ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ps2Background.copy(alpha = 0.4f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (game.coverArtLocalPath != null) {
                        AsyncImage(
                            model = game.coverArtLocalPath,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        game.matchedTitle ?: game.currentTitle ?: game.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ps2OnBackground,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { onFetchCoverArt(game) }) {
                        Text(if (game.coverArtLocalPath != null) "Update Art" else "Fetch Cover Art")
                    }
                }
            }
        }
    }
}

/** Rename workspace: ul.cfg regen, conf_apps.cfg generation, missing POPStarter generation, and rename. */
@Composable
fun RenamePanel(
    games: List<GameFile>,
    isScanning: Boolean,
    onRename: (GameFile) -> Unit,
    onRegenerateUlConfig: () -> Unit,
    onGeneratePopsElfs: () -> Unit,
    onGenerateConfAppsCfg: () -> Unit,
    onApplyAllMatched: () -> Unit
) {
    val hasVcdGames = games.any { it.isVcdGame }
    val matchedGames = games.filter { it.status == GameStatus.MATCHED }

    ConsoleFrame(
        kicker = "03 / Rename & config station",
        title = "Rename",
        description = "Regenerate configs, restore missing POPStarter ELFs, and rename matched titles.",
        icon = "🏷️"
    ) {
        OutlinedButton(
            onClick = onRegenerateUlConfig,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isScanning
        ) {
            Text("Regenerate ul.cfg from Files on Drive")
        }

        if (hasVcdGames) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onGeneratePopsElfs,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isScanning
            ) {
                Text("Generate missing POPStarter ELFs")
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onGenerateConfAppsCfg,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ps2Primary),
                enabled = !isScanning
            ) {
                Text("Generate conf_apps.cfg for OPL")
            }
        }

        if (matchedGames.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onApplyAllMatched,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ps2SurfaceElevated)
            ) {
                Text("Rename + Fetch Art for All Matched", color = Ps2Accent)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "MATCHED TITLES",
            style = MaterialTheme.typography.labelSmall,
            color = Ps2Accent,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))

        if (matchedGames.isEmpty()) {
            Text(
                "No matched titles ready to rename yet.",
                style = MaterialTheme.typography.bodySmall,
                color = Ps2OnSurfaceMuted
            )
        } else {
            matchedGames.forEach { game ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ps2Background.copy(alpha = 0.4f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        game.matchedTitle ?: game.currentTitle ?: game.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ps2OnBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onRename(game) },
                        colors = ButtonDefaults.buttonColors(containerColor = Ps2Primary)
                    ) {
                        Text("Rename")
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyLibraryPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ps2SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Text("💿", style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "No drive connected yet",
            style = MaterialTheme.typography.titleMedium,
            color = Ps2Accent
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Plug in your USB/HDD with an OTG adapter, then tap\n\"Pick USB / HDD Folder\" above to load your library.",
            style = MaterialTheme.typography.bodySmall,
            color = Ps2OnSurfaceMuted,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GameRow(
    game: GameFile,
    onRename: () -> Unit,
    onCoverArt: () -> Unit,
    onTap: () -> Unit,
    onConvertIsoToUl: () -> Unit,
    onConvertUlToIso: () -> Unit,
    onConvertBinToVcd: () -> Unit,
    onConvertIsoToZso: () -> Unit,
    onConvertZsoToIso: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Ps2SurfaceElevated.copy(alpha = 0.5f))
            .clickable { onTap() }
            .padding(10.dp),
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
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ps2SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text("💿", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                game.matchedTitle ?: game.currentTitle ?: game.displayName.substringBeforeLast('.', game.displayName),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                (game.gameId ?: "Unrecognized filename") +
                    when {
                        game.isUlGame -> "  (UL)"
                        game.isVcdGame -> "  (POPS)"
                        game.isZsoGame -> "  (ZSO)"
                        else -> ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = Ps2OnSurfaceMuted
            )
            Text(statusLabel(game.status), style = MaterialTheme.typography.labelSmall, color = Ps2Accent)
            if (game.status == GameStatus.ERROR && game.lastError != null) {
                Text(
                    game.lastError!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (game.isBinCueGame) {
            if (game.status != GameStatus.LOOKING_UP) {
                OutlinedButton(
                    onClick = onConvertBinToVcd,
                    enabled = game.status != GameStatus.CONVERTING
                ) {
                    Text(if (game.status == GameStatus.CONVERTING) "Converting..." else "Convert to VCD (POPS)")
                }
            }
        } else if (game.status == GameStatus.MATCHED) {
            Column {
                Button(
                    onClick = onRename,
                    modifier = Modifier.padding(bottom = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ps2Primary)
                ) { Text("Rename") }
                OutlinedButton(onClick = onCoverArt, modifier = Modifier.padding(bottom = 4.dp)) { Text("Cover Art") }
                // VCD (POPS) games aren't part of the ISO<->UL / ISO<->ZSO split-or-compress
                // world, so they only get Rename + Cover Art, same as everything else by default.
                if (!game.isVcdGame) {
                    when {
                        game.isUlGame -> OutlinedButton(onClick = onConvertUlToIso) { Text("Reassemble to ISO") }
                        game.isZsoGame -> OutlinedButton(onClick = onConvertZsoToIso) { Text("Decompress to ISO") }
                        else -> {
                            OutlinedButton(
                                onClick = onConvertIsoToUl,
                                modifier = Modifier.padding(bottom = 4.dp)
                            ) { Text("Split to UL") }
                            OutlinedButton(onClick = onConvertIsoToZso) { Text("Compress to ZSO") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtLoadingDialog(progressLabel: String?) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(12.dp)) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Fetching Cover Art", style = MaterialTheme.typography.titleMedium, color = Ps2Accent)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Ps2Accent,
                    trackColor = Ps2SurfaceElevated
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    progressLabel ?: "Contacting online database…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ps2OnSurfaceMuted
                )
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
    onSelectBackground: (String) -> Unit,
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

                // --- NEW BACKGROUND SELECTION UI ---
                val availableBgs = game.artSet?.availableBackgroundUrls ?: emptyList()
                if (availableBgs.size > 1) {
                    Spacer(Modifier.height(16.dp))
                    Text("Alternative Backgrounds (Tap to download & select):", style = MaterialTheme.typography.bodySmall, color = Ps2Accent)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableBgs.forEach { bgUrl ->
                            Box(
                                modifier = Modifier
                                    .size(80.dp, 45.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Ps2SurfaceElevated)
                                    .clickable { onSelectBackground(bgUrl) },
                                contentAlignment = Alignment.Center
                            ) {
                               AsyncImage(
                                    model = bgUrl,
                                    contentDescription = "Alt BG",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                }
                // -----------------------------------

                val noArtFound = game.artSet != null &&
                    game.artSet?.cover == null && game.artSet?.background == null &&
                    game.artSet?.icon == null && game.artSet?.screenshot == null
                if (noArtFound) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No art found online for this game. Tap a box above to add your own photo.",
                        style = MaterialTheme.typography.bodySmall
                    )
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
    GameStatus.CONVERTING -> "Converting..."
    GameStatus.CONVERTED -> "Converted ✓"
    GameStatus.ERROR -> "Error — try again"
}
