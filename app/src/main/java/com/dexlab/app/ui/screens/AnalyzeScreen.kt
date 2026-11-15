package com.dexlab.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.dexlab.app.core.AppScanner
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.InstalledApp
import com.dexlab.app.core.Project
import com.dexlab.app.core.ProjectKind
import com.dexlab.app.core.SessionCache
import com.dexlab.app.core.Workspace
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.LoadingPane
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SearchField
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.Purple
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnalyzeViewModel(app: Application) : AndroidViewModel(app) {
    private val workspace = Workspace(app)

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects = _projects.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _created = MutableSharedFlow<Project>(extraBufferCapacity = 1)
    val created = _created.asSharedFlow()

    private var loaded = false

    fun refresh(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        viewModelScope.launch {
            _projects.value = workspace.list()
        }
    }

    fun importUris(uris: List<Uri>) = runImport("Copying ${uris.size} file(s)…") {
        workspace.importUris(uris)
    }

    fun importInstalled(packageName: String, label: String) = runImport("Copying $label…") {
        workspace.importInstalled(packageName)
    }

    fun importSelf() = runImport("Copying DexLab…") {
        workspace.importSelf()
    }

    fun importLab(kind: String) = runImport("Preparing the lab…") {
        when (kind) {
            "dex" -> workspace.importAsset("lab/sample.dex", "sample.dex")
            "secret" -> workspace.importAsset("lab/secret.bin", "secret.bin")
            "so" -> workspace.importNativeLib("libsample.so")
            else -> error("Unknown lab asset")
        }
    }

    private fun runImport(message: String, block: suspend () -> Project) {
        viewModelScope.launch {
            _busy.value = message
            try {
                val project = block()
                _projects.value = workspace.list()
                _created.emit(project)
            } catch (t: Throwable) {
                _error.value = friendly(t)
            } finally {
                _busy.value = null
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            SessionCache.invalidate(id)
            workspace.delete(id)
            _projects.value = workspace.list()
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun friendly(t: Throwable): String {
        val message = t.message ?: t.javaClass.simpleName
        return if (t is SecurityException || message.contains("EACCES") || message.contains("Permission denied")) {
            "Android blocked reading this app's APK ($message). Start Shizuku and retry, or use Open file and pick the APK from storage instead."
        } else {
            message
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeScreen(nav: NavHostController) {
    val vm: AnalyzeViewModel = viewModel()
    val context = LocalContext.current
    val projects by vm.projects.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showApps by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }

    LaunchedEffect(Unit) { vm.refresh(true) }
    LaunchedEffect(Unit) {
        vm.created.collect { project -> nav.navigate("project/${project.id}") }
    }
    LaunchedEffect(error) {
        val e = error
        if (e != null) {
            snackbar.showSnackbar(e)
            vm.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "OFFLINE  /  STATIC  /  READ-ONLY",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 1.1.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("DexLab", style = MaterialTheme.typography.displaySmall)
                            Text(
                                "Android reverse-engineering workbench",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { nav.navigate("settings") }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }

            val b = busy
            if (b != null) {
                item {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            b,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            item {
                SectionCard(
                    title = "Open a target",
                    subtitle = "APK, APKM/APKS bundle, DEX, native library or any file"
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(2.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open file")
                        }
                        OutlinedButton(
                            onClick = { showApps = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(2.dp)
                        ) {
                            Text("Installed apps")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.importSelf() }) { Text("Analyze DexLab") }
                        TextButton(onClick = { vm.importLab("dex") }) { Text("Sample DEX") }
                        TextButton(onClick = { vm.importLab("so") }) { Text("Sample SO") }
                    }
                    Text(
                        "Files are copied into a private workspace. Nothing is uploaded.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Workspace",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        Fmt.plural(projects.size, "project"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (projects.isEmpty()) {
                item {
                    EmptyState(
                        "No projects yet",
                        "Open an APK or APKM/APKS bundle, or analyze DexLab itself. The sample DEX and native library are built in."
                    )
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { nav.navigate("project/${project.id}") },
                        onDelete = { pendingDelete = project }
                    )
                }
            }

            item {
                Text(
                    "Static analysis only. Dynamic instrumentation, patching and repackaging are out of scope.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }

    if (showApps) {
        InstalledAppsSheet(
            onDismiss = { showApps = false },
            onPick = { app ->
                showApps = false
                vm.importInstalled(app.packageName, app.label)
            }
        )
    }

    val p = pendingDelete
    if (p != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("The copied files and analysis cache for this project will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    vm.delete(p.id)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProjectCard(project: Project, onOpen: () -> Unit, onDelete: () -> Unit) {
    val color = when (project.kind) {
        ProjectKind.APK -> Amber
        ProjectKind.DEX -> Teal
        ProjectKind.ELF -> Blue
        ProjectKind.ARCHIVE -> Purple
        ProjectKind.FILE -> TextDim
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.10f))
                    .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    project.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = color
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(project.packageName ?: project.kind.label, Fmt.bytes(project.sizeBytes))
                        .joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = TextDim)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledAppsSheet(onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(includeSystem) {
        loading = true
        apps = withContext(Dispatchers.IO) { AppScanner.list(context, includeSystem) }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = 640.dp)
        ) {
            Text("Installed apps", style = MaterialTheme.typography.titleLarge)
            Text(
                "Copies the base APK and all splits into a workspace.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            SearchField(query, { query = it }, "Filter by name or package")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = includeSystem, onCheckedChange = { includeSystem = it })
                Text(
                    "Include system apps",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (loading) {
                LoadingPane("Reading package manager…")
            } else {
                val filtered = apps.filter {
                    query.isBlank() ||
                        it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        ListItem(
                            headlineContent = {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    app.packageName + (app.versionName?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingContent = { if (app.system) Pill("system", TextDim) },
                            modifier = Modifier.clickable { onPick(app) }
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close")
            }
        }
    }
}
