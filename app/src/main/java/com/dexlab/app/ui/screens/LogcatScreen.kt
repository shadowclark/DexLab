package com.dexlab.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dexlab.app.logcat.LogEntry
import com.dexlab.app.logcat.LogFilterState
import com.dexlab.app.logcat.LogcatEngine
import com.dexlab.app.logcat.LogcatFilter
import com.dexlab.app.logcat.Privilege
import com.dexlab.app.shizuku.ShizukuManager
import com.dexlab.app.shizuku.ShizukuState
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.LevelBadge
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.OutlineDark
import com.dexlab.app.ui.theme.Purple
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LogSource(val label: String, val hint: String) {
    APP("This app", "own process"),
    SHIZUKU("Shizuku", "system-wide"),
    ROOT("Root", "su shell")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { LogcatEngine() }
    val entries by engine.entries.collectAsState()
    val running by engine.running.collectAsState()
    val engineError by engine.error.collectAsState()
    val shizukuState by ShizukuManager.state.collectAsState()
    val shizukuRunning by ShizukuManager.logRunning.collectAsState()
    val shizukuError by ShizukuManager.logError.collectAsState()
    val listState = rememberLazyListState()

    var source by remember { mutableStateOf(LogSource.APP) }
    var level by remember { mutableStateOf('V') }
    var preset by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf("") }
    var pidFilter by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var showFilters by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<LogEntry?>(null) }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }

    val filter = LogFilterState(level, preset, query, tagFilter, pidFilter)
    val filtered = remember(entries, filter) { LogcatFilter.apply(entries, filter, 2000) }
    val errorCount = remember(entries) { entries.count { it.level in "EFA" } }
    val warnCount = remember(entries) { entries.count { it.level == 'W' } }
    val crashCount = remember(entries) { entries.count { LogcatFilter.matches(it, LogFilterState(preset = "Crashes")) } }

    LaunchedEffect(Unit) { ShizukuManager.init(context) }

    DisposableEffect(Unit) {
        onDispose {
            engine.stop()
            ShizukuManager.stopLogcat()
        }
    }

    LaunchedEffect(source) {
        engine.clear()
        if (source != LogSource.SHIZUKU) ShizukuManager.stopLogcat()
        when (source) {
            LogSource.APP -> engine.start(scope, root = false, pid = Process.myPid())
            LogSource.ROOT -> {
                rootAvailable = withContext(Dispatchers.IO) { Privilege.hasRoot() }
                if (rootAvailable == true) {
                    engine.start(scope, root = true, pid = null)
                } else {
                    engine.fail("Root was not found. Start su or use Shizuku / this app's logs.")
                }
            }
            LogSource.SHIZUKU -> Unit
        }
    }

    LaunchedEffect(source, shizukuState) {
        if (source != LogSource.SHIZUKU) return@LaunchedEffect
        if (shizukuState is ShizukuState.Ready) {
            engine.beginExternal()
            val ok = ShizukuManager.startLogcat()
            if (!ok) engine.fail(ShizukuManager.logError.value ?: "Shizuku did not start")
        } else {
            engine.finishExternal()
        }
    }

    LaunchedEffect(source) {
        if (source == LogSource.SHIZUKU) {
            ShizukuManager.batches.collect { engine.ingest(it) }
        }
    }

    LaunchedEffect(source, shizukuRunning) {
        if (source == LogSource.SHIZUKU && !shizukuRunning && running) {
            engine.finishExternal()
        }
    }

    LaunchedEffect(shizukuError) {
        val error = shizukuError
        if (source == LogSource.SHIZUKU && error != null) engine.fail(error)
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.lastIndex)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    val text = filtered.joinToString("\n") {
                        "${it.time} ${it.level}/${it.tag} ${it.pid}/${it.tid}: ${it.message}"
                    }
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    }
                }
            }
        }
    }

    fun share() {
        val text = (if (filtered.isEmpty()) entries.takeLast(500) else filtered).joinToString("\n") {
            "${it.time} ${it.level}/${it.tag} ${it.pid}/${it.tid}: ${it.message}"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DexLab logcat")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share logcat"))
    }

    fun resume() {
        when (source) {
            LogSource.APP -> engine.start(scope, root = false, pid = Process.myPid())
            LogSource.ROOT -> {
                if (rootAvailable == true) engine.start(scope, root = true, pid = null)
                else engine.fail("Root was not found.")
            }
            LogSource.SHIZUKU -> {
                if (shizukuState is ShizukuState.Ready) {
                    engine.beginExternal()
                    scope.launch {
                        if (!ShizukuManager.startLogcat()) {
                            engine.fail(ShizukuManager.logError.value ?: "Shizuku did not start")
                        }
                    }
                } else {
                    engine.fail("Start Shizuku and grant DexLab access first.")
                }
            }
        }
    }

    LabScaffold(
        title = "Logcat",
        subtitle = "${source.label} · ${if (running) "live" else "paused"} · ${filtered.size} shown",
        actions = {
            IconButton(onClick = {
                if (running) {
                    engine.stop()
                    if (source == LogSource.SHIZUKU) ShizukuManager.stopLogcat()
                } else {
                    resume()
                }
            }) {
                Icon(
                    if (running) Icons.Filled.Close else Icons.Filled.PlayArrow,
                    contentDescription = if (running) "Pause" else "Start"
                )
            }
            IconButton(onClick = { engine.clear() }) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear")
            }
            IconButton(onClick = { share() }) {
                Icon(Icons.Filled.Share, contentDescription = "Share")
            }
            TextButton(onClick = { saveLauncher.launch("dexlab-logcat.txt") }) { Text("Save") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SourceStrip(
                source = source,
                shizukuState = shizukuState,
                rootAvailable = rootAvailable,
                onSource = { source = it }
            )

            if (source == LogSource.SHIZUKU && shizukuState !is ShizukuState.Ready) {
                ShizukuCard(
                    state = shizukuState,
                    error = shizukuError,
                    onGrant = { ShizukuManager.requestPermission() },
                    onOpen = { ShizukuManager.openShizuku(context) },
                    onRefresh = { ShizukuManager.refresh() },
                    onHelp = { showHelp = true }
                )
            }

            if (source == LogSource.ROOT && rootAvailable != true) {
                SectionCard(
                    title = "Root source",
                    subtitle = if (rootAvailable == null) "checking su…" else "su was not found"
                ) {
                    Text(
                        "System-wide logcat can use a root shell. DexLab will run su -c logcat, which requires a rooted device and an su prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButtonCompat("Recheck") {
                            scope.launch {
                                rootAvailable = withContext(Dispatchers.IO) { Privilege.hasRoot() }
                                if (rootAvailable == true) engine.start(scope, root = true, pid = null)
                            }
                        }
                        OutlinedButtonCompat("Use app logs") { source = LogSource.APP }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search tag, message, pid…", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont)
                )
                TextButton(onClick = { showFilters = true }) {
                    Text("Filters" + if (filter.activeCount > 0) " (${filter.activeCount})" else "")
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${entries.size} buffered · $warnCount warn · $errorCount err",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                    modifier = Modifier.weight(1f)
                )
                if (autoScroll) {
                    Text("live tail", style = MaterialTheme.typography.labelSmall, color = Green)
                } else {
                    Text("scroll locked", style = MaterialTheme.typography.labelSmall, color = Amber)
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LogcatFilter.LEVELS.forEach { candidate ->
                    FilterChip(
                        selected = level == candidate,
                        onClick = { level = candidate },
                        label = { Text(candidate.toString(), style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LogcatFilter.PRESETS.forEach { candidate ->
                    FilterChip(
                        selected = preset == candidate,
                        onClick = { preset = candidate },
                        label = { Text(candidate, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (crashCount > 0 && preset != "Crashes") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { preset = "Crashes" },
                    color = Red.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Red.copy(alpha = 0.3f))
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$crashCount crash-level entries", style = MaterialTheme.typography.labelMedium, color = Red)
                        Spacer(Modifier.weight(1f))
                        Text("Show crashes", style = MaterialTheme.typography.labelSmall, color = Red)
                    }
                }
            }

            val error = engineError
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (filtered.isEmpty()) {
                    EmptyState(
                        when {
                            source == LogSource.SHIZUKU && shizukuState !is ShizukuState.Ready -> "Shizuku is not ready"
                            running -> "Listening for logs"
                            else -> "Logcat is paused"
                        },
                        when {
                            source == LogSource.SHIZUKU && shizukuState !is ShizukuState.Ready ->
                                "Grant Shizuku access above to stream system-wide logs. This app's own logs are always available."
                            running -> "Entries will appear here. On Android 13+, a normal app only sees its own process logs."
                            else -> "Press play to resume the selected source."
                        }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        items(filtered) { entry ->
                            LogRow(entry = entry, query = query, onClick = { detail = entry })
                        }
                    }
                    if (!autoScroll) {
                        FilledTonalButton(
                            onClick = {
                                autoScroll = true
                                scope.launch { if (filtered.isNotEmpty()) listState.animateScrollToItem(filtered.lastIndex) }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                        ) { Text("Jump to live") }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            filter = filter,
            entries = entries,
            autoScroll = autoScroll,
            onLevel = { level = it },
            onPreset = { preset = it },
            onQuery = { query = it },
            onTag = { tagFilter = it },
            onPid = { pidFilter = it },
            onAutoScroll = { autoScroll = it },
            onReset = {
                level = 'V'
                preset = "All"
                query = ""
                tagFilter = ""
                pidFilter = ""
            },
            onDismiss = { showFilters = false }
        )
    }

    detail?.let { entry ->
        DetailSheet(
            entry = entry,
            onDismiss = { detail = null },
            onTag = {
                tagFilter = entry.tag
                preset = "All"
                detail = null
            },
            onPid = {
                pidFilter = entry.pid.toString()
                preset = "All"
                detail = null
            }
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Start Shizuku without a PC") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Install Shizuku from shizuku.rikka.app/download.", style = MaterialTheme.typography.bodySmall)
                    Text("2. On Android 11+, enable Developer options → Wireless debugging.", style = MaterialTheme.typography.bodySmall)
                    Text("3. Open Shizuku and follow its on-device pairing/start flow. A PC is not required on Android 11+.", style = MaterialTheme.typography.bodySmall)
                    Text("4. Return to DexLab → Logcat → Shizuku and grant access.", style = MaterialTheme.typography.bodySmall)
                    Text("Shizuku must be started again after a reboot on a non-rooted device.", style = MaterialTheme.typography.labelSmall, color = TextDim)
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun SourceStrip(
    source: LogSource,
    shizukuState: ShizukuState,
    rootAvailable: Boolean?,
    onSource: (LogSource) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SourceChip(
            label = "This app",
            hint = "own process",
            accent = Amber,
            selected = source == LogSource.APP,
            modifier = Modifier.weight(1f)
        ) { onSource(LogSource.APP) }
        SourceChip(
            label = "Shizuku",
            hint = when (shizukuState) {
                is ShizukuState.Ready -> "UID ${shizukuState.uid}"
                is ShizukuState.PermissionRequired -> "grant needed"
                is ShizukuState.NotRunning -> "not running"
                is ShizukuState.NotInstalled -> "not installed"
                is ShizukuState.Unavailable -> "unavailable"
            },
            accent = Teal,
            selected = source == LogSource.SHIZUKU,
            modifier = Modifier.weight(1f)
        ) { onSource(LogSource.SHIZUKU) }
        SourceChip(
            label = "Root",
            hint = if (rootAvailable == true) "available" else "su",
            accent = Purple,
            selected = source == LogSource.ROOT,
            modifier = Modifier.weight(1f)
        ) { onSource(LogSource.ROOT) }
    }
}

@Composable
private fun SourceChip(
    label: String,
    hint: String,
    accent: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.65f) else OutlineDark)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = if (selected) accent.copy(alpha = 0.8f) else TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ShizukuCard(
    state: ShizukuState,
    error: String?,
    onGrant: () -> Unit,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onHelp: () -> Unit
) {
    val title = when (state) {
        is ShizukuState.Ready -> "Shell access ready"
        is ShizukuState.PermissionRequired -> "Shizuku permission required"
        is ShizukuState.NotRunning -> "Shizuku is installed, but not running"
        is ShizukuState.NotInstalled -> "Shizuku is not installed"
        is ShizukuState.Unavailable -> "Shizuku is unavailable"
    }
    SectionCard(title = "Shizuku bridge", subtitle = title) {
        Text(
            when (state) {
                is ShizukuState.Ready ->
                    "System-wide logcat runs as shell (UID ${state.uid}, Shizuku ${state.version}). This is the no-root path for Android 13+."
                is ShizukuState.PermissionRequired ->
                    "Shizuku is running. Grant DexLab permission to start the shell UserService."
                is ShizukuState.NotRunning ->
                    "Shizuku must be started first. On Android 11+ it can be started entirely on the device with wireless debugging."
                is ShizukuState.NotInstalled ->
                    "Install Shizuku to read other apps' logs and copy APKs that Android normally blocks. This app's own logs still work."
                is ShizukuState.Unavailable -> state.reason
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.labelSmall, color = Red)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is ShizukuState.PermissionRequired -> {
                    Button(onClick = onGrant) { Text("Grant access") }
                    OutlinedButtonCompat("Open Shizuku", onOpen)
                }
                is ShizukuState.NotRunning -> {
                    Button(onClick = onOpen) { Text("Open Shizuku") }
                    OutlinedButtonCompat("Recheck", onRefresh)
                }
                is ShizukuState.NotInstalled -> {
                    Button(onClick = onOpen) { Text("Get Shizuku") }
                    OutlinedButtonCompat("How to start", onHelp)
                }
                else -> {
                    OutlinedButtonCompat("Recheck", onRefresh)
                    OutlinedButtonCompat("Setup help", onHelp)
                }
            }
        }
        if (state is ShizukuState.Ready) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("UID ${state.uid}", Green)
                Spacer(Modifier.width(8.dp))
                Text("v${state.version}", style = MaterialTheme.typography.labelSmall, color = TextDim)
            }
        }
    }
}

@Composable
private fun OutlinedButtonCompat(text: String, onClick: () -> Unit = {}) {
    androidx.compose.material3.OutlinedButton(onClick = onClick) { Text(text) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    filter: LogFilterState,
    entries: List<LogEntry>,
    autoScroll: Boolean,
    onLevel: (Char) -> Unit,
    onPreset: (String) -> Unit,
    onQuery: (String) -> Unit,
    onTag: (String) -> Unit,
    onPid: (String) -> Unit,
    onAutoScroll: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tags = remember(entries) {
        entries.groupingBy { it.tag }.eachCount().entries
            .sortedByDescending { it.value }.take(10).map { it.key }
    }
    val pids = remember(entries) {
        entries.groupingBy { it.pid }.eachCount().entries
            .sortedByDescending { it.value }.take(8).map { it.key }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter logs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("Reset") }
            }
            Text("Level", style = MaterialTheme.typography.labelMedium, color = TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LogcatFilter.LEVELS.forEach { candidate ->
                    FilterChip(
                        selected = filter.minLevel == candidate,
                        onClick = { onLevel(candidate) },
                        label = { Text(candidate.toString()) }
                    )
                }
            }
            Text("Preset", style = MaterialTheme.typography.labelMedium, color = TextDim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LogcatFilter.PRESETS.forEach { candidate ->
                    FilterChip(
                        selected = filter.preset == candidate,
                        onClick = { onPreset(candidate) },
                        label = { Text(candidate) }
                    )
                }
            }
            OutlinedTextField(
                value = filter.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search message, tag or pid") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filter.tag,
                    onValueChange = onTag,
                    modifier = Modifier.weight(1f),
                    label = { Text("Tag") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont)
                )
                OutlinedTextField(
                    value = filter.pid,
                    onValueChange = onPid,
                    modifier = Modifier.weight(1f),
                    label = { Text("PID / TID") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont)
                )
            }
            if (tags.isNotEmpty()) {
                Text("Tags in buffer", style = MaterialTheme.typography.labelMedium, color = TextDim)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = filter.tag == tag,
                            onClick = { onTag(if (filter.tag == tag) "" else tag) },
                            label = { Text(tag.take(24), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
            if (pids.isNotEmpty()) {
                Text("PIDs in buffer", style = MaterialTheme.typography.labelMedium, color = TextDim)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pids.forEach { pid ->
                        FilterChip(
                            selected = filter.pid == pid.toString(),
                            onClick = { onPid(if (filter.pid == pid.toString()) "" else pid.toString()) },
                            label = { Text(pid.toString(), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
            HorizontalDivider(color = OutlineDark)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Live tail", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Scroll to the newest entry automatically.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim
                    )
                }
                Switch(checked = autoScroll, onCheckedChange = onAutoScroll)
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(
    entry: LogEntry,
    onDismiss: () -> Unit,
    onTag: () -> Unit,
    onPid: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelBadge(entry.level)
                Spacer(Modifier.width(8.dp))
                Text(entry.tag, style = MaterialTheme.typography.titleMedium, color = levelColor(entry.level))
                Spacer(Modifier.weight(1f))
                Text(entry.time.ifBlank { "--:--:--" }, style = MaterialTheme.typography.labelSmall, color = TextDim)
            }
            Text(
                "PID ${entry.pid} · TID ${entry.tid} · ${entry.level}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                color = TextDim
            )
            HorizontalDivider(color = OutlineDark)
            SelectionContainer {
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont, fontSize = 12.sp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButtonCompat("Copy") {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("logcat", entry.message))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
                OutlinedButtonCompat("Tag") { onTag() }
                OutlinedButtonCompat("PID") { onPid() }
                OutlinedButtonCompat("Share") {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "${entry.time} ${entry.level}/${entry.tag}: ${entry.message}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share log entry"))
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, query: String, onClick: () -> Unit) {
    val accent = levelColor(entry.level)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .background(if (entry.level in "EFA") accent.copy(alpha = 0.045f) else Color.Transparent)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent.copy(alpha = 0.75f)))
        Spacer(Modifier.width(7.dp))
        Text(
            entry.time.ifBlank { "--:--:--" },
            modifier = Modifier.width(66.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont, fontSize = 8.5.sp),
            color = TextDim,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        LevelBadge(entry.level)
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.tag,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${entry.pid}/${entry.tid}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont, fontSize = 8.sp),
                    color = TextDim,
                    maxLines = 1
                )
            }
            Text(
                highlight(entry.message, query),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont, fontSize = 10.5.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}

private fun levelColor(level: Char): Color = when (level) {
    'V' -> TextDim
    'D' -> Blue
    'I' -> Green
    'W' -> Amber
    'E', 'F', 'A' -> Red
    else -> TextDim
}

private fun highlight(text: String, query: String): AnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(needle, start, ignoreCase = true)
            if (index < 0) break
            append(text.substring(start, index))
            withStyle(SpanStyle(background = Amber.copy(alpha = 0.25f), color = Amber, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(index, index + needle.length))
            }
            start = index + needle.length
        }
        append(text.substring(start))
    }
}
