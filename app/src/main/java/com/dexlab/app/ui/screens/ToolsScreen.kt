package com.dexlab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.Project
import com.dexlab.app.core.ProjectKind
import com.dexlab.app.core.Workspace
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Purple
import com.dexlab.app.ui.theme.Teal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Tool(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
    val color: androidx.compose.ui.graphics.Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(nav: NavHostController) {
    val context = LocalContext.current
    var showProjects by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(showProjects) {
        if (showProjects && !loaded) {
            projects = withContext(Dispatchers.IO) { Workspace(context).list() }
                .filter { it.kind == ProjectKind.APK || it.kind == ProjectKind.DEX || it.kind == ProjectKind.ARCHIVE }
            loaded = true
        }
    }

    val tools = listOf(
        Tool("Hex viewer", "Paged hex, ASCII, hashes and magic detection", Icons.Filled.List, "hex", Amber),
        Tool("Strings", "ASCII and UTF-16LE extraction with a minimum length", Icons.Filled.Search, "strings", Teal),
        Tool("Entropy", "Block entropy chart with an honest interpretation", Icons.Filled.Info, "entropy", Purple),
        Tool("Crypto Lab", "Base64, hashes, XOR, AES, DES, 3DES and RC4", Icons.Filled.Build, "crypto", Green),
        Tool("Crypto scanner", "Static DEX scan for modes, weak hashes and key material", Icons.Filled.Search, "scan", Blue),
        Tool("Network scope", "Your addresses, active link, nearby Wi-Fi and honest exposure signals", Icons.Filled.Build, "network", Teal)
    )

    LabScaffold(
        title = "Tools",
        subtitle = "Offline utilities for the data you already have"
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Start with the file analysis tools, then move to the Crypto Lab when you have bytes worth decoding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            tools.forEach { tool ->
                item {
                    SectionCard(modifier = Modifier.clickable {
                        if (tool.route == "scan") showProjects = true else nav.navigate(tool.route)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                tool.icon,
                                contentDescription = null,
                                tint = tool.color,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tool.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    tool.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Pill("open", tool.color)
                        }
                    }
                }
            }
            item {
                Text(
                    "Reference",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                SectionCard(modifier = Modifier.clickable { nav.navigate("lesson/smali-cheatsheet") }) {
                    Text("Smali cheat sheet", style = MaterialTheme.typography.titleMedium)
                    Text("Directives, opcodes and the patterns you read most.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(modifier = Modifier.clickable { nav.navigate("lesson/arm64-cheatsheet") }) {
                    Text("ARM64 cheat sheet", style = MaterialTheme.typography.titleMedium)
                    Text("Prologue, epilogue, branches and argument registers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Text(
                    "All processing is local. Large files are read in pages or chunks; the Crypto Lab holds decoded bytes in memory.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    if (showProjects) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showProjects = false }, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose a project to scan", style = MaterialTheme.typography.titleLarge)
                Text(
                    "The scanner reads DEX strings, class names and method references.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (projects.isEmpty()) {
                    Text(
                        "No APK or DEX projects yet. Open one from Analyze first.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    projects.forEach { project ->
                        SectionCard(modifier = Modifier.clickable {
                            showProjects = false
                            nav.navigate("cryptoScan/${project.id}")
                        }) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${project.kind.label}  ·  ${Fmt.bytes(project.sizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                TextButton(onClick = { showProjects = false }, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}
