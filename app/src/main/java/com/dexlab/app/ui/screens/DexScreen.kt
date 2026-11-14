package com.dexlab.app.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.dexlab.app.core.AppSettings
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.ProjectSession
import com.dexlab.app.core.SessionCache
import com.dexlab.app.dex.DexClassInfo
import com.dexlab.app.export.NativeExport
import com.dexlab.app.ui.components.CodeLanguage
import com.dexlab.app.ui.components.CodeViewer
import com.dexlab.app.ui.components.CopyTextButton
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.LoadingPane
import com.dexlab.app.ui.components.OptionChips
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SearchField
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.components.ThinDivider
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DexScreen(nav: NavHostController, projectId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<ProjectSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var dexName by remember { mutableStateOf<String?>(null) }
    var classes by remember { mutableStateOf<List<DexClassInfo>>(emptyList()) }
    var classesLoading by remember { mutableStateOf(false) }
    var classQuery by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Classes") }
    var strings by remember { mutableStateOf<List<String>>(emptyList()) }
    var stringsLoading by remember { mutableStateOf(false) }
    var stringQuery by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<DexClassInfo?>(null) }
    var selectedMethod by remember { mutableStateOf<String?>(null) }
    var smali by remember { mutableStateOf<String?>(null) }
    var smaliError by remember { mutableStateOf<String?>(null) }
    var java by remember { mutableStateOf<String?>(null) }
    var javaError by remember { mutableStateOf<String?>(null) }
    var codeMode by remember { mutableStateOf("Java") }
    var smaliLoading by remember { mutableStateOf(false) }
    var smaliQuery by remember { mutableStateOf("") }
    var callers by remember { mutableStateOf<List<String>>(emptyList()) }
    var callersFor by remember { mutableStateOf<String?>(null) }
    var scanningCallers by remember { mutableStateOf(false) }
    var showCallers by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) {
            runCatching { NativeExport.writeText(context, uri, pending.second) }
                .onSuccess { Toast.makeText(context, "Exported ${pending.first}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(projectId) {
        loading = true
        try {
            val s = SessionCache.get(context, projectId)
            session = s
            dexName = s?.dex?.dexNames?.firstOrNull()
            if (s?.dex == null) error = "No DEX files in this project"
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        }
        loading = false
    }

    LaunchedEffect(dexName) {
        val repo = session?.dex ?: return@LaunchedEffect
        val name = dexName ?: return@LaunchedEffect
        classesLoading = true
        classes = withContext(Dispatchers.IO) {
            runCatching { repo.classes(name) }.getOrElse {
                error = "Could not read DEX: ${it.message}"
                emptyList()
            }
        }
        classesLoading = false
    }

    LaunchedEffect(mode, dexName) {
        if (mode != "Strings" || strings.isNotEmpty()) return@LaunchedEffect
        val repo = session?.dex ?: return@LaunchedEffect
        val name = dexName ?: return@LaunchedEffect
        stringsLoading = true
        strings = withContext(Dispatchers.IO) {
            runCatching { repo.strings(name, 150_000) }.getOrDefault(emptyList())
        }
        stringsLoading = false
    }

    LaunchedEffect(selected, dexName) {
        val c = selected ?: return@LaunchedEffect
        val repo = session?.dex ?: return@LaunchedEffect
        val name = dexName ?: return@LaunchedEffect
        smaliLoading = true
        smali = null
        java = null
        smaliError = null
        javaError = null
        codeMode = "Java"
        smaliQuery = ""
        selectedMethod = c.methods.firstOrNull()
        callers = emptyList()
        val smaliResult = withContext(Dispatchers.IO) {
            runCatching { repo.smali(name, c.descriptor) }
        }
        smali = smaliResult.getOrNull()
        smaliError = smaliResult.exceptionOrNull()?.let {
            "baksmali failed: ${it.message ?: it.javaClass.simpleName}"
        }
        val javaResult = withContext(Dispatchers.IO) {
            runCatching { repo.java(name, c.descriptor) }
        }
        java = javaResult.getOrNull()
        javaError = javaResult.exceptionOrNull()?.let {
            "Java view failed: ${it.message ?: it.javaClass.simpleName}"
        }
        smaliLoading = false
    }

    BackHandler(enabled = selected != null) {
        selected = null
    }

    val title = selected?.displayName?.substringAfterLast('.') ?: "DEX code"
    LabScaffold(
        title = title,
        subtitle = when {
            selected != null -> selected?.packageName?.ifBlank { "default package" }
            else -> dexName ?: "Dalvik bytecode"
        },
        onBack = {
            if (selected != null) selected = null else nav.popBackStack()
        },
        actions = {
            if (selected != null && (smali != null || java != null)) {
                val text = if (codeMode == "Smali") smali else java
                if (text != null) {
                    TextButton(onClick = {
                        val extension = if (codeMode == "Smali") "smali" else "java"
                        val fileName = NativeExport.suggestedName(
                            selected!!.displayName.substringAfterLast('.') + "_$codeMode", extension
                        )
                        pendingExport = fileName to text
                        exportLauncher.launch(fileName)
                    }) { Text("Export") }
                    TextButton(onClick = { share(context, text, selected!!.displayName) }) {
                        Text("Share")
                    }
                }
            }
        }
    ) { padding ->
        when {
            loading -> LoadingPane("Opening project…")
            error != null -> EmptyState("Cannot open DEX", error!!)
            selected != null -> ClassDetail(
                modifier = Modifier.fillMaxSize().padding(padding),
                info = selected!!,
                smali = smali,
                smaliError = smaliError,
                java = java,
                javaError = javaError,
                codeMode = codeMode,
                onCodeMode = { codeMode = it },
                smaliLoading = smaliLoading,
                smaliQuery = smaliQuery,
                onSmaliQuery = { smaliQuery = it },
                selectedMethod = selectedMethod,
                onMethod = { selectedMethod = it },
                scanningCallers = scanningCallers,
                onScanCallers = {
                    val method = selectedMethod ?: return@ClassDetail
                    val repo = session?.dex ?: return@ClassDetail
                    val name = dexName ?: return@ClassDetail
                    scope.launch {
                        scanningCallers = true
                        callers = withContext(Dispatchers.IO) {
                            runCatching {
                                repo.callers(name, selected!!.descriptor, method, 500)
                            }.getOrElse { emptyList() }
                        }
                        callersFor = method
                        scanningCallers = false
                        showCallers = true
                    }
                }
            )
            else -> ClassList(
                modifier = Modifier.fillMaxSize().padding(padding),
                dexNames = session?.dex?.dexNames.orEmpty(),
                dexName = dexName,
                onDex = { dexName = it },
                mode = mode,
                onMode = { mode = it },
                classQuery = classQuery,
                onClassQuery = { classQuery = it },
                classes = classes,
                classesLoading = classesLoading,
                stringQuery = stringQuery,
                onStringQuery = { stringQuery = it },
                strings = strings,
                stringsLoading = stringsLoading,
                onClass = { selected = it }
            )
        }
    }

    if (showCallers) {
        AlertDialog(
            onDismissRequest = { showCallers = false },
            title = { Text("Callers of ${callersFor ?: "method"}") },
            text = {
                Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (callers.isEmpty()) {
                        Text(
                            "No callers found in this DEX. The method may be called from another dex, from native code, or only through reflection.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        callers.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                color = Teal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCallers = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ClassList(
    modifier: Modifier,
    dexNames: List<String>,
    dexName: String?,
    onDex: (String) -> Unit,
    mode: String,
    onMode: (String) -> Unit,
    classQuery: String,
    onClassQuery: (String) -> Unit,
    classes: List<DexClassInfo>,
    classesLoading: Boolean,
    stringQuery: String,
    onStringQuery: (String) -> Unit,
    strings: List<String>,
    stringsLoading: Boolean,
    onClass: (DexClassInfo) -> Unit
) {
    val filtered = remember(classes, classQuery) {
        if (classQuery.isBlank()) classes
        else classes.filter {
            it.displayName.contains(classQuery, ignoreCase = true) ||
                it.descriptor.contains(classQuery, ignoreCase = true)
        }
    }
    val filteredStrings = remember(strings, stringQuery) {
        if (stringQuery.isBlank()) strings
        else strings.filter { it.contains(stringQuery, ignoreCase = true) }
    }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (dexNames.size > 1) {
            item {
                OptionChips(dexNames, dexName ?: dexNames.first(), onSelect = onDex)
            }
        }
        item {
            OptionChips(listOf("Classes", "Strings"), mode, onSelect = onMode)
        }
        item {
            SearchField(
                if (mode == "Classes") classQuery else stringQuery,
                if (mode == "Classes") onClassQuery else onStringQuery,
                if (mode == "Classes") "Search ${classes.size} classes" else "Search strings"
            )
        }
        if (mode == "Classes") {
            if (classesLoading) {
                item { LoadingPane("Reading DEX classes…") }
            } else if (classes.isEmpty()) {
                item { EmptyState("No classes", "This DEX contains no class definitions.") }
            } else {
                item {
                    Text(
                        if (filtered.size > 1200) "Showing 1200 of ${filtered.size} matches. Refine the search."
                        else "${filtered.size} matching classes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(filtered.take(1200), key = { it.descriptor }) { info ->
                    SectionCard(
                        modifier = Modifier.clickable { onClass(info) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    info.displayName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    info.packageName.ifBlank { "default package" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Pill("${info.methodCount} m", Teal)
                            Spacer(Modifier.width(4.dp))
                            Pill("${info.fieldCount} f", TextDim)
                        }
                    }
                }
            }
        } else {
            if (stringsLoading) {
                item { LoadingPane("Reading the DEX string table…") }
            } else if (filteredStrings.isEmpty()) {
                item { EmptyState("No strings", "Try a shorter search, or switch back to classes.") }
            } else {
                item {
                    Text(
                        if (filteredStrings.size > 800) "Showing 800 of ${filteredStrings.size} matches." else "${filteredStrings.size} strings",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(filteredStrings.take(800)) { value ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            value.ifBlank { "(empty)" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont, fontSize = 11.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        CopyTextButton(value, "Copy")
                    }
                }
            }
        }
        item {
            Text(
                "baksmali runs on the device with a per-class filter. Large classes can take a moment.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ClassDetail(
    modifier: Modifier,
    info: DexClassInfo,
    smali: String?,
    smaliError: String?,
    java: String?,
    javaError: String?,
    codeMode: String,
    onCodeMode: (String) -> Unit,
    smaliLoading: Boolean,
    smaliQuery: String,
    onSmaliQuery: (String) -> Unit,
    selectedMethod: String?,
    onMethod: (String) -> Unit,
    scanningCallers: Boolean,
    onScanCallers: () -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                info.descriptor,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                color = Amber,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${info.methodCount} methods · ${info.fieldCount} fields",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OptionChips(listOf("Java", "Smali"), codeMode, onSelect = onCodeMode)
            Text(
                if (codeMode == "Java") {
                    "Heuristic Java-shaped view. Register names are raw and control flow is flat; smali is the ground truth."
                } else {
                    "baksmali output. This is the exact decoded bytecode."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (info.methods.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                info.methods.take(40).forEach { method ->
                    FilterChip(
                        selected = method == selectedMethod,
                        onClick = { onMethod(method) },
                        label = { Text(method, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onScanCallers,
                    enabled = !scanningCallers && selectedMethod != null,
                    modifier = Modifier.weight(1f)
                ) {
                    if (scanningCallers) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (scanningCallers) "Scanning…" else "Find callers of ${selectedMethod ?: "method"}")
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(smaliQuery, onSmaliQuery, "Find in ${codeMode.lowercase()}", Modifier.weight(1f))
        }

        when {
            smaliLoading -> LoadingPane("Disassembling ${info.displayName.substringAfterLast('.')}…")
            (if (codeMode == "Smali") smaliError else javaError) != null -> Column(Modifier.padding(16.dp)) {
                Text("Could not build this view", style = MaterialTheme.typography.titleMedium, color = Red)
                Spacer(Modifier.height(6.dp))
                Text(
                    (if (codeMode == "Smali") smaliError else javaError) ?: "Unknown error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            (if (codeMode == "Smali") smali else java) != null -> Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
                val code = if (codeMode == "Smali") smali!! else java!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${code.lineSequence().count()} lines · $codeMode",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    CopyTextButton(code, "Copy all")
                }
                CodeViewer(
                    text = code,
                    modifier = Modifier.weight(1f),
                    fontSize = AppSettings.fontSize,
                    wrap = AppSettings.wrapLines,
                    query = smaliQuery,
                    language = if (codeMode == "Smali") CodeLanguage.SMALI else CodeLanguage.JAVA
                )
            }
            else -> EmptyState("No $codeMode view", "Switch to the other tab or pick another class.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun share(context: Context, text: String, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share code"))
}
