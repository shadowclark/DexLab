package com.dexlab.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.ToolFiles
import com.dexlab.app.core.ToolTarget
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.KeyValue
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.TextDim
import com.dexlab.app.tools.Entropy
import com.dexlab.app.tools.EntropyBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun EntropyScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(ToolTarget.file) }
    var blocks by remember { mutableStateOf<List<EntropyBlock>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var scannedBytes by remember { mutableStateOf(0L) }

    fun analyze(f: File) {
        scope.launch {
            loading = true
            blocks = withContext(Dispatchers.IO) {
                runCatching { Entropy.analyzeFile(f, 4096, 8 * 1024 * 1024) }.getOrDefault(emptyList())
            }
            scannedBytes = minOf(f.length(), 8L * 1024 * 1024)
            loading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { runCatching { ToolFiles.import(context, uri) }.getOrNull() }
                if (imported != null) {
                    file = imported
                    ToolTarget.file = imported
                    blocks = emptyList()
                    analyze(imported)
                }
            }
        }
    }

    val average = if (blocks.isEmpty()) 0.0 else blocks.sumOf { it.entropy } / blocks.size
    val max = blocks.maxOfOrNull { it.entropy } ?: 0.0
    val min = blocks.minOfOrNull { it.entropy } ?: 0.0

    LabScaffold(
        title = "Entropy",
        subtitle = file?.name ?: "block randomness",
        onBack = { nav.popBackStack() }
    ) { padding ->
        val f = file
        if (f == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                EmptyState("Open a file", "Measure Shannon entropy in 4 KB blocks. Up to 8 MB is scanned.") {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Open file") }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SectionCard(title = f.name, subtitle = "${Fmt.bytes(f.length())} on disk") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { analyze(f) }, enabled = !loading) {
                                Text(if (loading) "Analyzing…" else "Analyze")
                            }
                            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Change file") }
                        }
                        if (scannedBytes > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Scanned ${Fmt.bytes(scannedBytes)} in 4 KB blocks.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (blocks.isNotEmpty()) {
                    item {
                        SectionCard(title = "Block entropy", subtitle = "0 = predictable, 8 = uniform random") {
                            EntropyChart(blocks)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                KeyValue("Average", "%.2f".format(average), mono = true, valueColor = colorFor(average))
                                KeyValue("Min", "%.2f".format(min), mono = true)
                                KeyValue("Max", "%.2f".format(max), mono = true)
                                KeyValue("Blocks", blocks.size.toString())
                            }
                        }
                    }
                    item {
                        SectionCard(title = "Interpretation") {
                            Text(
                                Entropy.interpretation(average),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            KeyValue("0 – 3.2", "text, structured headers, code with repetition", valueColor = Green)
                            KeyValue("3.2 – 5.2", "mixed content, resources", valueColor = Amber)
                            KeyValue("5.2 – 6.8", "compressed or compiled", valueColor = Amber)
                            KeyValue("6.8 – 8", "encrypted, packed or already compressed", valueColor = Red)
                        }
                    }
                    item {
                        Text(
                            "High entropy is not proof of encryption: ZIP, DEX compact dex, images and OAT are all high-entropy by design. Compare with the file magic and strings.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntropyChart(blocks: List<EntropyBlock>) {
    val sampled = remember(blocks) {
        if (blocks.size <= 600) blocks
        else {
            val step = blocks.size / 600.0
            List(600) { i -> blocks[minOf(blocks.size - 1, (i * step).toInt())] }
        }
    }
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val barWidth = size.width / sampled.size
        for (level in 0..4) {
            val y = size.height * level / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        sampled.forEachIndexed { index, block ->
            val h = (block.entropy / 8.0 * size.height).toFloat().coerceIn(0f, size.height)
            drawRect(
                color = colorFor(block.entropy),
                topLeft = Offset(index * barWidth, size.height - h),
                size = Size(barWidth.coerceAtLeast(0.8f), h)
            )
        }
        drawLine(surface, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
    }
}

private fun colorFor(entropy: Double): Color = when {
    entropy < 3.2 -> Green
    entropy < 5.2 -> Color(0xFFB9D36A)
    entropy < 6.8 -> Amber
    else -> Red
}
