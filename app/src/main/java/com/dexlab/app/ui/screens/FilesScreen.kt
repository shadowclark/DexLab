package com.dexlab.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.apk.ApkArchive
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.ProjectSession
import com.dexlab.app.core.SessionCache
import com.dexlab.app.core.ToolTarget
import com.dexlab.app.core.Workspace
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.KeyValue
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.LoadingPane
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SearchField
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.components.ThinDivider
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Purple
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FilesScreen(nav: NavHostController, projectId: String) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var session by remember { mutableStateOf<ProjectSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ApkArchive.Entry?>(null) }
    var pendingExport by remember { mutableStateOf<ApkArchive.Entry?>(null) }
    var pendingAbi by remember { mutableStateOf<ApkArchive.AbiGroup?>(null) }

    LaunchedEffect(projectId) {
        loading = true
        session = runCatching { SessionCache.get(context, projectId) }.getOrNull()
        loading = false
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = pendingExport
        pendingExport = null
        if (uri != null && entry != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = session?.archive?.read(entry.name) ?: return@runCatching
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                }
            }
        }
    }

    val abiExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val abi = pendingAbi
        pendingAbi = null
        val archive = session?.archive
        if (uri != null && abi != null && archive != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: error("Cannot open the selected destination")
                        out.use { archive.writeAbiZip(abi.abi, it, session?.project?.name) }
                    }
                }
                Toast.makeText(
                    context,
                    result.fold(
                        onSuccess = { "Exported $it native files + manifest from lib/${abi.abi}" },
                        onFailure = { "ABI export failed: ${it.message}" }
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LabScaffold(
        title = "Files, ABIs and entries",
        subtitle = session?.project?.name ?: "archive contents",
        onBack = { nav.popBackStack() }
    ) { padding ->
        val s = session
        val entries = remember(s, query) {
            val all = s?.archive?.entries.orEmpty()
            val filtered = if (query.isBlank()) all else all.filter { it.name.contains(query, true) }
            filtered.sortedWith(
                compareBy(
                    {
                        when {
                            it.name == "AndroidManifest.xml" -> 0
                            it.name == "classes.dex" -> 1
                            it.name == "resources.arsc" -> 2
                            it.name.startsWith("classes") -> 3
                            it.name.startsWith("lib/") -> 4
                            it.name.startsWith("assets/") -> 5
                            else -> 6
                        }
                    },
                    { it.name }
                )
            )
        }
        val abiGroups = remember(s) { s?.archive?.abiGroups.orEmpty() }

        when {
            loading -> LoadingPane("Listing entries…")
            s?.archive == null -> EmptyState("No archive", "This project is not a ZIP-based container.")
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SearchField(query, { query = it }, "Search ${s.archive.entries.size} entries")
                }
                item {
                    Text(
                        if (entries.size > 1500) "Showing 1500 of ${entries.size} entries." else "${entries.size} entries · ${Fmt.bytes(s.archive.totalSize)} uncompressed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (abiGroups.isNotEmpty()) {
                    item {
                        NativeAbiCard(abiGroups) { abi ->
                            pendingAbi = abi
                            abiExportLauncher.launch(abiExportName(session?.project?.name, abi.abi))
                        }
                    }
                }
                items(entries.take(1500), key = { it.name + it.container.name }) { entry ->
                    EntryRow(entry) { selected = entry }
                }
                item {
                    Text(
                        "Stored entries are uncompressible payloads. High entropy plus a .so or .dex name is often an encrypted asset.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }

    val entry = selected
    if (entry != null && session != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.name.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                        color = Amber
                    )
                    ThinDivider()
                    KeyValue("Size", Fmt.bytes(entry.size))
                    KeyValue("Compressed", Fmt.bytes(entry.compressedSize))
                    KeyValue(
                        "Ratio",
                        if (entry.size > 0) "${((1.0 - entry.compressedSize.toDouble() / entry.size) * 100).toInt()}% saved" else "n/a"
                    )
                    KeyValue("Method", if (entry.stored) "stored (0)" else "deflated (8)")
                    KeyValue("Container", entry.container.name)
                    ThinDivider()
                    Text(
                        "Open this entry in a tool. The file is extracted to the project cache first.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            selected = null
                            scope.launch { openTool(nav, projectId, entry, "hex") }
                        }) { Text("Hex") }
                        TextButton(onClick = {
                            selected = null
                            scope.launch { openTool(nav, projectId, entry, "strings") }
                        }) { Text("Strings") }
                        TextButton(onClick = {
                            selected = null
                            scope.launch { openTool(nav, projectId, entry, "entropy") }
                        }) { Text("Entropy") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            pendingExport = entry
                            selected = null
                            exportLauncher.launch(entry.name.substringAfterLast('/'))
                        }) { Text("Export") }
                        TextButton(onClick = { selected = null }) { Text("Close") }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun NativeAbiCard(
    groups: List<ApkArchive.AbiGroup>,
    onExport: (ApkArchive.AbiGroup) -> Unit
) {
    SectionCard(
        title = "Native ABIs",
        subtitle = "Extract one architecture as a ZIP"
    ) {
        Text(
            "These are the lib/<abi>/ folders shipped inside the APK. Export keeps that directory layout, so arm64, arm32 and x86 variants stay separate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "The ZIP includes ABI-MANIFEST.txt with each library's architecture, size, SHA-256 and ELF build ID (when present).",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim
        )
        ThinDivider()
        groups.forEach { group ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        group.abi,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont),
                        color = Blue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${abiDescription(group.abi)} · ${group.soCount} .so · ${Fmt.bytes(group.bytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onExport(group) }) { Text("Export ZIP") }
            }
        }
        Text(
            "Each .so can also be exported individually below. Search for lib/ plus an ABI name to narrow the entries to one variant.",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim
        )
    }
}

private fun abiDescription(abi: String): String = when (abi) {
    "arm64-v8a" -> "64-bit ARM"
    "armeabi-v7a" -> "32-bit ARM"
    "armeabi" -> "legacy ARM"
    "x86_64" -> "64-bit x86"
    "x86" -> "32-bit x86"
    "riscv64" -> "64-bit RISC-V"
    else -> "native"
}

private fun abiExportName(projectName: String?, abi: String): String {
    val base = projectName.orEmpty().substringBeforeLast('.').trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .ifBlank { "apk" }
        .take(64)
    val safeAbi = abi.replace(Regex("""[\\/:*?"<>|]"""), "_")
    return "$base-$safeAbi.zip"
}

@Composable
private fun EntryRow(entry: ApkArchive.Entry, onClick: () -> Unit) {
    val color = when {
        entry.name == "AndroidManifest.xml" -> Amber
        entry.name.endsWith(".dex") -> Teal
        entry.name.endsWith(".so") -> Blue
        entry.name == "resources.arsc" -> Purple
        entry.name.startsWith("assets/") -> Green
        else -> TextDim
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(Fmt.bytes(entry.size))
                    if (entry.size > 0) {
                        append("  ·  ")
                        append(((1.0 - entry.compressedSize.toDouble() / entry.size) * 100).toInt())
                        append("% saved")
                    }
                    if (entry.stored) append("  ·  stored")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(6.dp))
        Pill(
            when {
                entry.name.endsWith(".dex") -> "dex"
                entry.name.endsWith(".so") -> "so"
                entry.stored -> "stored"
                else -> "zip"
            },
            color
        )
    }
}

private suspend fun openTool(nav: NavHostController, projectId: String, entry: ApkArchive.Entry, tool: String) {
    val context = nav.context
    val session = SessionCache.get(context, projectId) ?: return
    val archive = session.archive ?: return
    val dest = Workspace(context).cacheDir(projectId)
        .resolve("tool/${entry.name.replace('/', '_').replace('\\', '_')}")
    withContext(Dispatchers.IO) { archive.extract(entry.name, dest) } ?: return
    ToolTarget.file = dest
    ToolTarget.title = entry.name
    nav.navigate(tool)
}
