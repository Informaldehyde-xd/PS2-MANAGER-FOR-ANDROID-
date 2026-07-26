package com.ps2mc.manager

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ps2mc.manager.data.McCardRepository
import com.ps2mc.manager.ui.theme.PS2MCManagerTheme
import kotlinx.coroutines.launch

class TestViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val repo = McCardRepository(application)
    var resultText by mutableStateOf("Pick a memory card image (.ps2 / .bin / .mcd) to test the parser.")
        private set

    fun open(uri: Uri) {
        viewModelScope.launch {
            resultText = "Parsing..."
            val (image, error) = repo.openCard(uri)
            resultText = if (image != null) {
                buildString {
                    appendLine("Parsed OK ✓")
                    appendLine()
                    appendLine("magic: ${image.superblock.magic}")
                    appendLine("version: ${image.superblock.version}")
                    appendLine("pageSize: ${image.superblock.pageSize}")
                    appendLine("pagesPerCluster: ${image.superblock.pagesPerCluster}")
                    appendLine("clustersPerCard: ${image.superblock.clustersPerCard}")
                    appendLine("allocOffset: ${image.superblock.allocOffset}")
                    appendLine("rootDirCluster: ${image.superblock.rootDirCluster}")
                    appendLine("hasEcc: ${image.hasEcc}")
                    appendLine()
                    try {
                        val root = image.listRoot()
                        appendLine("Root directory: ${root.size} entr${if (root.size == 1) "y" else "ies"}")
                        root.forEach { e ->
                            appendLine("  ${if (e.isDirectory) "[DIR]" else "     "} ${e.name}  (${e.length} bytes, mode=0x${e.mode.toString(16)})")
                        }

                        if (root.isEmpty()) {
                            appendLine()
                            appendLine("Diagnostic — raw hex of first 3 directory slots")
                            appendLine("(mode·unused·length·created·cluster·dirEntry·modified·attr·pad·name = 96 bytes each):")
                            for (i in 0 until 3) {
                                appendLine("slot $i: ${image.dumpRawEntryHex(image.superblock.rootDirCluster, i)}")
                            }
                        }
                    } catch (e: Exception) {
                        appendLine("Directory listing failed: ${e.message}")
                    }
                }
            } else {
                "Failed to parse: $error"
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PS2MCManagerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TestScreen()
                }
            }
        }
    }
}

@Composable
fun TestScreen(viewModel: TestViewModel = viewModel()) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.open(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("PS2 MC Manager — Engine Test", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { launcher.launch(arrayOf("*/*")) }) {
            Text("Pick memory card image")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            viewModel.resultText,
            modifier = Modifier.verticalScroll(rememberScrollState())
        )
    }
}
