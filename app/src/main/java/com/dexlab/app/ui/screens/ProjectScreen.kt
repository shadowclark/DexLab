package com.dexlab.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.apk.PackerDetector
import com.dexlab.app.export.NativeExport
import com.dexlab.app.apk.PackerFinding
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.ProjectKind
import com.dexlab.app.core.ProjectSession
import com.dexlab.app.core.SessionCache
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
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Purple
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(nav: NavHostController, projectId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<ProjectSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var packers by remember { mutableStateOf<List<PackerFinding>>(emptyList()) }
    var pendingExport by remember { mutableStateOf<Pair<String, File>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { NativeExport.copyFile(context, uri, pending.second) }
                }
                Toast.makeText(
                    context,
                    result.fold(
                        onSuccess = { "Exported ${pending.first}" },
                        onFailure = { "Export failed: ${it.message}" }
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    LaunchedEffect(projectId) {
        loading = true
        try {
            val s = SessionCache.get(context, projectId)
            session = s
            if (s == null) error = "Project not found"
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        }
        loading = false
    }

    val s = session
    LaunchedEffect(s) {
        val archive = s?.archive ?: return@LaunchedEffect
        packers = withContext(Dispatchers.IO) {
            runCatching {
                val dexName = archive.dexNames.firstOrNull()
                val strings = if (dexName != null) {
                    runCatching { s.dex?.strings(dexName, 20_000) }.getOrNull().orEmpty()
                } else emptyList()
                val dexSize = dexName?.let { name ->
                    archive.entries.firstOrNull { it.name == name }?.size ?: 0L
                } ?: 0L
                val classNames = if (dexName != null) {
                    runCatching {
                        s.dex?.classes(dexName)?.take(4_000)?.map { it.descriptor }.orEmpty()
                    }.getOrDefault(emptyList())
                } else emptyList()
                PackerDetector.detect(
                    entries = archive.entries.map { it.name },
                    strings = strings,
                    apkSize = archive.totalSize.coerceAtLeast(s.project.sizeBytes),
                    dexSize = dexSize,
                    classNames = classNames
                )
            }.getOrDefault(emptyList())
        }
    }

    LabScaffold(
        title = s?.summary?.label ?: s?.project?.name ?: "Project",
        subtitle = s?.summary?.packageName ?: s?.project?.kind?.label,
        onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { nav.navigate("settings") }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    ) { padding ->
        when {
            loading -> LoadingPane("Preparing project…")
            error != null -> Box(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                ErrorPane(error!!) {
                    nav.popBackStack()
                }
            }
            s == null -> Box(Modifier.fillMaxSize().padding(padding)) {
                Text("Project not found", modifier = Modifier.padding(16.dp))
            }
            else -> {
                val project = s.project
                val dexCount = s.archive?.dexNames?.size ?: if (s.dex != null) 1 else 0
                val soCount = s.archive?.soNames?.size ?: if (s.elfFile != null) 1 else 0
                val entries = s.archive?.entries?.size ?: project.files.size
                val accent = when (project.kind) {
                    ProjectKind.APK -> Amber
                    ProjectKind.DEX -> Teal
                    ProjectKind.ELF -> Blue
                    ProjectKind.ARCHIVE -> Purple
                    ProjectKind.FILE -> TextDim
                }

                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                                        .size(52.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = 0.10f))
                    .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(2.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    project.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = accent
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.summary?.label ?: project.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    listOfNotNull(
                                        s.summary?.packageName ?: project.packageName,
                                        s.summary?.versionName?.let { "v$it" },
                                        project.kind.label
                                    ).joinToString("  ·  "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Pill(project.kind.label, accent)
                        }
                    }

                    item {
                        SectionCard(title = "At a glance") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatTile("DEX files", dexCount.toString(), Modifier.weight(1f), Teal)
                                StatTile("Native libs", soCount.toString(), Modifier.weight(1f), Blue)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatTile("Entries / files", entries.toString(), Modifier.weight(1f), Amber)
                                StatTile("Size", Fmt.bytes(project.sizeBytes), Modifier.weight(1f), Purple)
                            }
                            ThinDivider()
                            KeyValue("Created", Fmt.date(project.createdAt))
                            KeyValue("Min SDK", (s.summary?.minSdk ?: project.minSdk)?.toString() ?: "unknown")
                            KeyValue("Target SDK", (s.summary?.targetSdk ?: project.targetSdk)?.toString() ?: "unknown")
                            KeyValue("Version code", (s.summary?.versionCode ?: project.versionCode)?.toString() ?: "unknown")
                        }
                    }

                    if (s.summary != null) {
                        item {
                            SectionCard(title = "Signing certificate", subtitle = "The identity Android checks at install time") {
                                KeyValue("SHA-256", s.summary.signatureSha256 ?: "no certificate found", mono = true, valueColor = if (s.summary.signatureSha256 != null) Green else Red)
                                KeyValue("Subject", s.summary.signatureSubject ?: "unknown")
                                KeyValue(
                                    "Splits",
                                    when {
                                        s.summary.splitNames.isNotEmpty() -> s.summary.splitNames.joinToString(", ")
                                        s.project.files.size > 1 -> "${s.project.files.size} APK files (bundle)"
                                        else -> "none (single APK)"
                                    }
                                )
                                KeyValue("Debuggable", if (s.summary.debuggable) "true" else "false", valueColor = if (s.summary.debuggable) Red else Green)
                            }
                        }
                    }

                    item {
                        SectionCard(
                            title = "Packer / protector",
                            subtitle = "Static heuristics, not a verdict"
                        ) {
                            if (packers.isEmpty()) {
                                Text(
                                    "No known packer signature found. A clean result does not prove the app is unpacked; runtime loaders can hide below static markers.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                packers.forEach { finding ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Pill(
                                            finding.confidence,
                                            when (finding.confidence) {
                                                "high" -> Red
                                                "medium" -> Amber
                                                else -> TextDim
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(finding.name, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                finding.evidence,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (s.manifest != null) {
                        item {
                            SectionCard(title = "Manifest surface") {
                                val m = s.manifest
                                KeyValue("Permissions", m.permissions.size.toString())
                                KeyValue(
                                    "Dangerous",
                                    if (m.dangerousPermissions.isEmpty()) "none" else m.dangerousPermissions.joinToString(", "),
                                    valueColor = if (m.dangerousPermissions.isEmpty()) Green else Amber
                                )
                                KeyValue("Components", m.components.size.toString())
                                KeyValue("Exported", m.exportedCount.toString(), valueColor = if (m.exportedCount > 0) Amber else Green)
                                KeyValue("Deep links", if (m.deepLinks.isEmpty()) "none" else m.deepLinks.joinToString(", "))
                                ThinDivider()
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (m.debuggable) Pill("debuggable", Red)
                                    if (m.allowBackup == true) Pill("backup on", Amber)
                                    if (m.cleartext == true) Pill("cleartext", Red)
                                    if (m.extractNativeLibs == true) Pill("extract libs", TextDim)
                                }
                            }
                        }
                    }

                    if (s.errors.isNotEmpty()) {
                        item {
                            SectionCard(title = "Notes") {
                                s.errors.forEach {
                                    Text(
                                        "• $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    item {
                        SectionCard(title = "Open") {
                            val original = project.mainFile?.let(::File)
                            if (original != null && original.isFile) {
                                BenchOutlinedButton(
                                    text = if (project.files.size > 1) {
                                        "Export base APK"
                                    } else {
                                        "Export original ${original.name.substringAfterLast('.', "file").uppercase()}"
                                    },
                                    onClick = {
                                        pendingExport = original.name to original
                                        exportLauncher.launch(original.name)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            BenchOutlinedButton(
                                text = "Compare with another binary",
                                onClick = { nav.navigate("diff/${project.id}") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            if (s.archive != null || s.elfFile != null) {
                                BenchButton(
                                    text = "Deep analysis + pseudo-code",
                                    onClick = { nav.navigate("deep/${project.id}") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (s.dex != null) {
                                BenchButton(
                                    text = "DEX, Java and smali",
                                    onClick = { nav.navigate("dex/${project.id}") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (s.manifestXml != null) {
                                BenchOutlinedButton(
                                    text = "AndroidManifest",
                                    onClick = { nav.navigate("manifest/${project.id}") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (s.elfFile != null || !s.archive?.soNames.isNullOrEmpty()) {
                                BenchOutlinedButton(
                                    text = "Native disassembly",
                                    onClick = { nav.navigate("native/${project.id}") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (s.archive != null) {
                                BenchOutlinedButton(
                                    text = "Files, ABIs and export",
                                    onClick = { nav.navigate("files/${project.id}") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (s.dex != null) {
                                BenchOutlinedButton(
                                    text = "Scanner: crypto usage",
                                    onClick = { nav.navigate("cryptoScan/${project.id}") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            "Everything here is read-only. DexLab does not modify the analyzed files.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
