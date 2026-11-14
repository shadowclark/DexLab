package com.dexlab.app.ui.screens

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.apk.ManifestComponent
import com.dexlab.app.core.ProjectSession
import com.dexlab.app.core.SessionCache
import com.dexlab.app.ui.components.CodeViewer
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.KeyValue
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.LoadingPane
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SearchField
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.components.ThinDivider
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.TextDim

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManifestScreen(nav: NavHostController, projectId: String) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<ProjectSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(projectId) {
        loading = true
        session = runCatching { SessionCache.get(context, projectId) }.getOrNull()
        loading = false
    }

    val s = session
    LabScaffold(
        title = "AndroidManifest",
        subtitle = s?.summary?.packageName ?: "binary XML decoded on device",
        onBack = { nav.popBackStack() }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Summary") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("XML") })
            }
            when {
                loading -> LoadingPane("Decoding manifest…")
                s == null || s.manifestXml == null -> EmptyState(
                    "Manifest unavailable",
                    s?.errors?.joinToString("\n") ?: "The project could not be opened."
                )
                tab == 0 -> ManifestSummaryTab(s, query) { query = it }
                else -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                    SearchField(query, { query = it }, "Find in the manifest")
                    Spacer(Modifier.height(8.dp))
                    CodeViewer(
                        text = s.manifestXml,
                        modifier = Modifier.weight(1f),
                        fontSize = 11,
                        wrap = false,
                        query = query,
                        maxLines = 20_000
                    )
                }
            }
        }
    }
}

@Composable
private fun ManifestSummaryTab(session: ProjectSession, query: String, onQuery: (String) -> Unit) {
    val manifest = session.manifest ?: return
    val q = query.trim()
    val permissions = manifest.permissions.filter { q.isBlank() || it.contains(q, true) }
    val components = manifest.components.filter {
        q.isBlank() || it.name.contains(q, true) || it.type.contains(q, true)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SearchField(query, onQuery, "Filter permissions and components")
        }
        item {
            SectionCard(title = "Identity") {
                KeyValue("Package", manifest.packageName ?: "unknown", mono = true)
                KeyValue("Version", manifest.versionName ?: "unknown")
                KeyValue("Version code", manifest.versionCode?.toString() ?: "unknown")
                KeyValue("Min SDK", manifest.minSdk?.toString() ?: "not declared")
                KeyValue("Target SDK", manifest.targetSdk?.toString() ?: "not declared")
                KeyValue("Label", manifest.label ?: "not declared")
            }
        }
        item {
            SectionCard(title = "Application flags") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (manifest.debuggable) Pill("debuggable", Red)
                    if (manifest.allowBackup == true) Pill("allowBackup", Amber)
                    if (manifest.cleartext == true) Pill("cleartext", Red)
                    if (manifest.extractNativeLibs == true) Pill("extractNativeLibs", TextDim)
                }
                if (manifest.networkSecurityConfig != null) {
                    ThinDivider()
                    KeyValue("Network config", manifest.networkSecurityConfig, mono = true)
                }
            }
        }
        item {
            SectionCard(
                title = "Permissions",
                subtitle = "${manifest.permissions.size} declared, ${manifest.dangerousPermissions.size} dangerous"
            ) {
                if (permissions.isEmpty()) {
                    Text("No matching permissions.", style = MaterialTheme.typography.bodySmall, color = TextDim)
                } else {
                    permissions.forEach { permission ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                permission,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                color = if (permission in manifest.dangerousPermissions) Amber else MaterialTheme.colorScheme.onSurface
                            )
                            if (permission in manifest.dangerousPermissions) Pill("dangerous", Amber)
                        }
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "Components",
                subtitle = "${manifest.components.size} declared, ${manifest.exportedCount} exported"
            ) {
                if (components.isEmpty()) {
                    Text("No matching components.", style = MaterialTheme.typography.bodySmall, color = TextDim)
                } else {
                    components.forEachIndexed { index, component ->
                        if (index > 0) ThinDivider()
                        ComponentRow(component)
                    }
                }
            }
        }
        item {
            SectionCard(title = "Deep links", subtitle = "Data elements found in intent filters") {
                if (manifest.deepLinks.isEmpty()) {
                    Text(
                        "None declared. Exported components without data filters are still reachable by explicit intent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim
                    )
                } else {
                    manifest.deepLinks.forEach { link ->
                        Text(
                            link,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                            color = Green,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                }
            }
        }
        if (session.errors.isNotEmpty()) {
            item {
                SectionCard(title = "Notes") {
                    session.errors.forEach {
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
    }
}

@Composable
private fun ComponentRow(component: ManifestComponent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(component.type, TextDim)
            Spacer(Modifier.width(8.dp))
            Text(
                component.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val color = when {
                component.exported == "true" -> Amber
                component.exported.startsWith("default (true") -> Amber
                component.exported.startsWith("default") -> TextDim
                else -> Green
            }
            Pill(component.exported, color)
        }
        if (component.permission != null) {
            Text(
                "permission: ${component.permission}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        component.filters.forEach { filter ->
            Text(
                filter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
