package com.dexlab.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.ProjectSession
import com.dexlab.app.core.SessionCache
import com.dexlab.app.core.Workspace
import com.dexlab.app.diff.BinaryDiffEngine
import com.dexlab.app.diff.BinaryDiffReport
import com.dexlab.app.diff.DiffChange
import com.dexlab.app.diff.DiffRow
import com.dexlab.app.export.NativeExport
import com.dexlab.app.ui.components.BenchButton
import com.dexlab.app.ui.components.BenchOutlinedButton
import com.dexlab.app.ui.components.ErrorPane
import com.dexlab.app.ui.components.KeyValue
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.LoadingPane
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.components.StatTile
import com.dexlab.app.ui.components.ThinDivider
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DiffScreen(nav: NavHostController, projectId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<ProjectSession?>(null) }
    var aFile by remember { mutableStateOf<File?>(null) }
    var bFile by remember { mutableStateOf<File?>(null) }
    var report by remember { mutableStateOf<BinaryDiffReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingReport by remember { mutableStateOf<Pair<String, String>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingReport
        pendingReport = null
        if (uri != null && pending != null) {
            runCatching { NativeExport.writeText(context, uri, pending.second) }
                .onSuccess { Toast.makeText(context, "Exported ${pending.first}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                loading = true
                error = null
                try {
                    val target = withContext(Dispatchers.IO) { copyToCache(context, projectId, uri) }
                    bFile = target
                    val a = aFile ?: error("The source file is missing")
                    report = withContext(Dispatchers.IO) { BinaryDiffEngine.compare(a, target) }
                } catch (t: Throwable) {
                    error = t.message ?: t.javaClass.simpleName
                } finally {
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(projectId) {
        loading = true
        try {
            val s = SessionCache.get(context, projectId)
            session = s
            val source = s?.project?.mainFile?.let(::File)
            if (source == null || !source.isFile) error = "The source file is missing"
            aFile = source
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    fun chooseComparison() {
        picker.launch(arrayOf("*/*"))
    }

    LabScaffold(
        title = "Binary diff",
        subtitle = session?.project?.name ?: "Compare two builds",
        onBack = { nav.popBackStack() }
    ) { padding ->
        when {
            loading -> LoadingPane("Comparing files…")
            aFile == null -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                ErrorPane(error ?: "The source file is missing")
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SectionCard(title = "Source · A", subtitle = "The open project file") {
                        FileSummary(aFile!!)
                    }
                }
                item {
                    SectionCard(
                        title = "Comparison · B",
                        subtitle = if (bFile == null) "Pick another APK, SO, DEX or arbitrary binary" else bFile!!.name
                    ) {
                        FileSummary(bFile)
                        Spacer(Modifier.height(10.dp))
                        BenchButton(
                            text = if (bFile == null) "Choose comparison file" else "Choose another file",
                            onClick = ::chooseComparison,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (bFile != null && report != null) {
                            Spacer(Modifier.height(8.dp))
                            BenchOutlinedButton(
                                text = "Compare again",
                                onClick = {
                                    val a = aFile ?: return@BenchOutlinedButton
                                    val b = bFile ?: return@BenchOutlinedButton
                                    scope.launch {
                                        loading = true
                                        error = null
                                        report = withContext(Dispatchers.IO) {
                                            runCatching { BinaryDiffEngine.compare(a, b) }
                                                .onFailure { error = it.message ?: it.javaClass.simpleName }
                                                .getOrNull()
                                        }
                                        loading = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                if (error != null) {
                    item { SectionCard { Text(error!!, color = MaterialTheme.colorScheme.error) } }
                }
                report?.let { result ->
                    item {
                        SectionCard(
                            title = "Verdict",
                            subtitle = if (result.bytes.sameHash) "Identical bytes" else "Different builds"
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatTile(
                                    "Byte match",
                                    "${"%.1f".format(result.bytes.similarity * 100)}%",
                                    Modifier.weight(1f),
                                    if (result.bytes.sameHash) Green else Amber
                                )
                                StatTile("Added", result.addedCount.toString(), Modifier.weight(1f), Teal)
                                StatTile("Removed", result.removedCount.toString(), Modifier.weight(1f), Red)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatTile("Changed", result.changedCount.toString(), Modifier.weight(1f), Amber)
                                StatTile(
                                    "Byte delta",
                                    signedBytes(result.b.size - result.a.size),
                                    Modifier.weight(1f),
                                    Blue
                                )
                            }
                            ThinDivider()
                            KeyValue("SHA-256 A", result.a.sha256.take(32) + "…", mono = true)
                            KeyValue("SHA-256 B", result.b.sha256.take(32) + "…", mono = true)
                            KeyValue(
                                "First difference",
                                result.bytes.firstDifference?.let { "0x${it.toString(16)}" } ?: "none",
                                mono = true
                            )
                            KeyValue("Compared bytes", Fmt.bytes(result.bytes.comparedBytes), mono = true)
                            if (result.bytes.note.isNotBlank()) {
                                Text(
                                    result.bytes.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextDim
                                )
                            }
                        }
                    }
                    if (result.entries.isNotEmpty()) {
                        item { DiffSection("ARCHIVE ENTRIES", "${result.entries.size} changes", result.entries) }
                    }
                    if (result.classes.isNotEmpty()) {
                        item { DiffSection("DEX CLASSES", "${result.classes.size} changes", result.classes) }
                    }
                    if (result.functions.isNotEmpty()) {
                        item { DiffSection("NATIVE FUNCTIONS", "${result.functions.size} changes", result.functions) }
                    }
                    if (result.sections.isNotEmpty()) {
                        item { DiffSection("ELF SECTIONS", "${result.sections.size} changes", result.sections) }
                    }
                    if (result.symbols.isNotEmpty()) {
                        item { DiffSection("IMPORTED SYMBOLS", "${result.symbols.size} changes", result.symbols) }
                    }
                    if (result.entries.isEmpty() && result.classes.isEmpty() &&
                        result.functions.isEmpty() && result.sections.isEmpty() && result.symbols.isEmpty()
                    ) {
                        item {
                            SectionCard(title = "Structure") {
                                Text(
                                    "No structured changes were recognized. The byte comparison above is the useful part.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (result.notes.isNotEmpty()) {
                        item {
                            SectionCard(title = "Limits", subtitle = "Read this before drawing conclusions") {
                                result.notes.forEach {
                                    Text(
                                        "• $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    item {
                        BenchOutlinedButton(
                            text = "Export diff report",
                            onClick = {
                                val text = BinaryDiffEngine.render(result)
                                val name = NativeExport.suggestedName(
                                    "${result.a.name}-vs-${result.b.name}-diff",
                                    "txt"
                                )
                                pendingReport = name to text
                                exportLauncher.launch(name)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                item {
                    Text(
                        "Diffing is read-only. Byte matching is exact up to 64 MiB; structural lists are limited to 2,000 rows.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim
                    )
                }
            }
        }
    }
}

@Composable
private fun FileSummary(file: File?) {
    if (file == null) {
        Text(
            "No comparison file selected yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    KeyValue("Name", file.name, mono = true)
    KeyValue("Size", "${Fmt.bytes(file.length())} · ${file.length()} bytes")
    KeyValue("Path", file.absolutePath, mono = true)
}

@Composable
private fun DiffSection(title: String, subtitle: String, rows: List<DiffRow>) {
    SectionCard(title = title, subtitle = subtitle) {
        rows.take(240).forEach { row ->
            val color = when (row.change) {
                DiffChange.ADDED -> Green
                DiffChange.REMOVED -> Red
                DiffChange.CHANGED -> Amber
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Pill(row.change.label, color)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val detail = listOf(row.before, row.after).filter { it.isNotBlank() }.joinToString("  →  ")
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (rows.size > 240) {
            Text(
                "Showing 240 of ${rows.size}. Export the report for the full list.",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun signedBytes(value: Long): String =
    (if (value >= 0) "+" else "-") + Fmt.bytes(kotlin.math.abs(value))

private fun copyToCache(context: Context, projectId: String, uri: Uri): File {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "comparison.bin"
    val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(96)
    val dir = Workspace(context).cacheDir(projectId).resolve("diff").apply { mkdirs() }
    val out = File(dir, "${System.currentTimeMillis()}-$safe")
    resolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    } ?: error("Cannot open the selected file")
    return out
}
