package com.dexlab.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.dexlab.app.analysis.DeepFinding
import com.dexlab.app.analysis.DeepScanner
import com.dexlab.app.apk.ApkArchive
import com.dexlab.app.apk.ManifestComponent
import com.dexlab.app.apk.PackerDetector
import com.dexlab.app.apk.PackerFinding
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.ProjectKind
import com.dexlab.app.core.ProjectSession
import com.dexlab.app.core.SessionCache
import com.dexlab.app.core.Workspace
import com.dexlab.app.crypto.CryptoFinding
import com.dexlab.app.crypto.CryptoScanner
import com.dexlab.app.export.NativeExport
import com.dexlab.app.export.GraphExportFormat
import com.dexlab.app.native.ElfArch
import com.dexlab.app.native.ElfFunction
import com.dexlab.app.native.ElfInfo
import com.dexlab.app.native.ElfParser
import com.dexlab.app.native.NativeAnnotation
import com.dexlab.app.native.NativeAnnotations
import com.dexlab.app.native.NativeCallGraph
import com.dexlab.app.native.NativeCallGraphBuilder
import com.dexlab.app.native.NativeCfgBuilder
import com.dexlab.app.native.NativeFunctionAnalysis
import com.dexlab.app.native.NativeXrefs
import com.dexlab.app.native.PseudoDecompiler
import com.dexlab.app.native.PseudoResult
import com.dexlab.app.ui.components.AnalysisTab
import com.dexlab.app.ui.components.BenchOutlinedButton
import com.dexlab.app.ui.components.CodeLanguage
import com.dexlab.app.ui.components.CodeViewer
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.ErrorPane
import com.dexlab.app.ui.components.KeyValue
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.LoadingPane
import com.dexlab.app.ui.components.NativeAnalysisSheet
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SearchField
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.components.ThinDivider
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Purple
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class DeepReport(
    val title: String,
    val subtitle: String,
    val apk: Boolean,
    val stats: List<Pair<String, String>>,
    val packers: List<PackerFinding>,
    val crypto: List<CryptoFinding>,
    val secrets: List<DeepFinding>,
    val permissions: List<String>,
    val components: List<ManifestComponent>,
    val nativeLibs: List<LibEntry>,
    val standaloneElf: ElfInfo?,
    val notes: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepScreen(nav: NavHostController, projectId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var session by remember { mutableStateOf<ProjectSession?>(null) }
    var report by remember { mutableStateOf<DeepReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var selectedLib by remember { mutableStateOf<LibEntry?>(null) }
    var elf by remember { mutableStateOf<ElfInfo?>(null) }
    var libLoading by remember { mutableStateOf(false) }
    var libError by remember { mutableStateOf<String?>(null) }

    var functionQuery by remember { mutableStateOf("") }
    var pseudo by remember { mutableStateOf<PseudoResult?>(null) }
    var pseudoLoading by remember { mutableStateOf(false) }
    var activeAnnotation by remember { mutableStateOf(NativeAnnotation()) }
    var analysis by remember { mutableStateOf<NativeFunctionAnalysis?>(null) }
    var callGraph by remember { mutableStateOf<NativeCallGraph?>(null) }
    var analysisLoading by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var showAnalysis by remember { mutableStateOf(false) }
    var analysisTab by remember { mutableStateOf(AnalysisTab.XREFS) }
    var pendingPseudoExport by remember { mutableStateOf<Pair<String, String>?>(null) }

    val pseudoExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingPseudoExport
        pendingPseudoExport = null
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
            if (s == null) {
                error = "Project not found"
            } else {
                report = withContext(Dispatchers.IO) { buildDeepReport(s, context) }
            }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    fun openLib(lib: LibEntry) {
        val s = session ?: return
        scope.launch {
            libLoading = true
            libError = null
            pseudo = null
            analysis = null
            callGraph = null
            showAnalysis = false
            functionQuery = ""
            try {
                val file = withContext(Dispatchers.IO) {
                    if (lib.standalone) {
                        s.elfFile ?: error("Missing native file")
                    } else {
                        val archive = s.archive ?: error("Missing APK archive")
                        val dest = Workspace(context).cacheDir(projectId)
                            .resolve("deep/" + lib.path.replace('/', '_'))
                        archive.extract(lib.path, dest) ?: error("Missing ${lib.path}")
                    }
                }
                val parsed = withContext(Dispatchers.IO) { ElfParser().parse(file) }
                elf = parsed
                selectedLib = lib
            } catch (t: Throwable) {
                libError = t.message ?: t.javaClass.simpleName
            } finally {
                libLoading = false
            }
        }
    }

    fun openFunction(fn: ElfFunction) {
        val info = elf ?: return
        activeAnnotation = NativeAnnotations.get(context, info, fn)
        analysis = null
        showAnalysis = false
        scope.launch {
            pseudoLoading = true
            try {
                pseudo = withContext(Dispatchers.IO) {
                    PseudoDecompiler.generate(info, fn, fn.thumb)
                }
            } catch (t: Throwable) {
                pseudo = PseudoResult(
                    function = fn,
                    code = "// Pseudo-code failed.\n// ${t.message ?: t.javaClass.simpleName}\n",
                    instructionCount = 0,
                    note = "Pseudo-code failed to generate."
                )
            } finally {
                pseudoLoading = false
            }
        }
    }

    fun loadAnalysis(fn: ElfFunction, tab: AnalysisTab) {
        val info = elf ?: return
        showAnalysis = true
        analysisTab = tab
        analysisLoading = true
        analysisError = null
        analysis = null
        scope.launch {
            try {
                val graph = callGraph ?: withContext(Dispatchers.IO) {
                    NativeCallGraphBuilder.build(info)
                }.also { callGraph = it }
                val xrefs = withContext(Dispatchers.IO) {
                    NativeXrefs.scan(info, fn, fn.thumb)
                }
                val cfg = withContext(Dispatchers.IO) {
                    NativeCfgBuilder.build(info, fn, fn.thumb)
                }
                val annotation = NativeAnnotations.get(context, info, fn)
                activeAnnotation = annotation
                analysis = NativeFunctionAnalysis(
                    binaryLabel = info.soname ?: info.buildId ?: "${info.arch.label} ELF (${info.bytes.size} bytes)",
                    function = fn,
                    xrefs = xrefs,
                    graph = graph,
                    cfg = cfg,
                    annotation = annotation
                )
            } catch (t: Throwable) {
                analysisError = t.message ?: t.javaClass.simpleName
            } finally {
                analysisLoading = false
            }
        }
    }

    fun saveAnnotation(annotation: NativeAnnotation) {
        val info = elf ?: return
        val fn = pseudo?.function ?: return
        NativeAnnotations.save(context, info, fn, annotation)
        activeAnnotation = annotation
        analysis = analysis?.copy(annotation = annotation)
        Toast.makeText(
            context,
            if (annotation.isEmpty) "Local note cleared" else "Local note saved",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun exportPseudo() {
        val result = pseudo ?: return
        val name = NativeExport.suggestedName(result.function.name, "c")
        pendingPseudoExport = name to NativeExport.pseudo(result, activeAnnotation)
        pseudoExportLauncher.launch(name)
    }

    fun exportGraph(format: GraphExportFormat) {
        val current = analysis ?: return
        val name = NativeExport.suggestedName("${current.function.name}-graph", format.extension)
        pendingPseudoExport = name to NativeExport.graph(current, format)
        pseudoExportLauncher.launch(name)
    }

    BackHandler(enabled = pseudo != null || selectedLib != null) {
        if (pseudo != null) {
            pseudo = null
            analysis = null
            showAnalysis = false
        } else {
            selectedLib = null
            elf = null
            callGraph = null
            analysis = null
            libError = null
            showAnalysis = false
        }
    }

    val annotations = remember(elf, activeAnnotation) {
        elf?.let { NativeAnnotations.all(context, it, it.functions) } ?: emptyMap()
    }

    val title = when {
        pseudo != null -> activeAnnotation.displayName(pseudo!!.function.name)
        selectedLib != null -> selectedLib!!.label
        else -> "Deep analysis"
    }
    val subtitle = when {
        pseudo != null -> "Heuristic pseudo-code · ${pseudo!!.instructionCount} instructions"
        selectedLib != null -> "${selectedLib!!.abi.ifBlank { "standalone" }} · ${Fmt.bytes(selectedLib!!.size)}"
        else -> report?.subtitle ?: "Static report and pseudo-code"
    }

    LabScaffold(
        title = title,
        subtitle = subtitle,
        onBack = {
            when {
                pseudo != null -> {
                    pseudo = null
                    analysis = null
                    showAnalysis = false
                }
                selectedLib != null -> {
                    selectedLib = null
                    elf = null
                    callGraph = null
                    analysis = null
                    showAnalysis = false
                }
                else -> nav.popBackStack()
            }
        }
    ) { padding ->
        when {
            loading -> LoadingPane("Building deep report…")
            error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                ErrorPane(error!!) { error = null }
            }
            pseudoLoading -> LoadingPane("Turning instructions into pseudo-code…")
            pseudo != null -> PseudoView(
                modifier = Modifier.fillMaxSize().padding(padding),
                result = pseudo!!,
                annotation = activeAnnotation,
                onCopy = { clipboard.setText(AnnotatedString(pseudo!!.code)) },
                onXrefs = { loadAnalysis(pseudo!!.function, AnalysisTab.XREFS) },
                onNotes = { loadAnalysis(pseudo!!.function, AnalysisTab.NOTES) },
                onExport = { exportPseudo() },
                onBack = { pseudo = null }
            )
            libLoading -> LoadingPane("Parsing ELF and symbols…")
            libError != null -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                ErrorPane(libError!!) { libError = null }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { selectedLib = null; elf = null }) { Text("Back to report") }
            }
            selectedLib != null && elf != null -> NativeDeepView(
                modifier = Modifier.fillMaxSize().padding(padding),
                elf = elf!!,
                query = functionQuery,
                annotations = annotations,
                onQuery = { functionQuery = it },
                onFunction = { openFunction(it) }
            )
            report != null -> ReportView(
                modifier = Modifier.fillMaxSize().padding(padding),
                report = report!!,
                onLib = { openLib(it) },
                onOpenNative = { openLib(it) }
            )
            session == null -> EmptyState("Nothing to analyze", "This project has no readable files.")
        }
    }

    NativeAnalysisSheet(
        visible = showAnalysis,
        loading = analysisLoading,
        error = analysisError,
        analysis = analysis,
        initialTab = analysisTab,
        onDismiss = { showAnalysis = false },
        onOpenAddress = { address ->
            val target = elf?.functions?.firstOrNull { it.address == address }
            if (target != null) {
                showAnalysis = false
                openFunction(target)
            } else {
                Toast.makeText(
                    context,
                    "No function entry at 0x${address.toString(16)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        onSaveAnnotation = { saveAnnotation(it) },
        onExport = {
            val current = analysis
            if (current != null) {
                val name = NativeExport.suggestedName("${current.function.name}-analysis", "txt")
                pendingPseudoExport = name to NativeExport.report(current)
                pseudoExportLauncher.launch(name)
            }
        },
        onExportGraph = { format -> exportGraph(format) }
    )
}

private fun buildDeepReport(session: ProjectSession, context: android.content.Context): DeepReport {
    val project = session.project
    val notes = ArrayList<String>()
    notes.add("Static, offline analysis. No code is executed.")
    notes.add("Pseudo-code is a heuristic reconstruction, not a true decompiler.")
    notes.addAll(session.errors)

    if (project.kind == ProjectKind.ELF || (session.elfFile != null && session.archive == null)) {
        val file = session.elfFile
        if (file == null) {
            return DeepReport(
                title = project.name,
                subtitle = "Native file",
                apk = false,
                stats = emptyList(),
                packers = emptyList(),
                crypto = emptyList(),
                secrets = emptyList(),
                permissions = emptyList(),
                components = emptyList(),
                nativeLibs = emptyList(),
                standaloneElf = null,
                notes = notes
            )
        }
        val parsed = ElfParser().parse(file)
        val stats = listOf(
            "Architecture" to parsed.machineName,
            "Type" to parsed.typeName,
            "Size" to Fmt.bytes(file.length()),
            "Functions" to parsed.functions.size.toString(),
            "Symbols" to parsed.symbols.size.toString(),
            "Imports" to parsed.imports.size.toString(),
            "Suspicious imports" to parsed.suspiciousImports.size.toString(),
            "PIE" to yesNo(parsed.pie),
            "NX" to yesNo(parsed.nx),
            "Stripped" to if (parsed.stripped) "yes" else "no"
        )
        return DeepReport(
            title = file.name,
            subtitle = "${parsed.machineName} · ${Fmt.bytes(file.length())}",
            apk = false,
            stats = stats,
            packers = emptyList(),
            crypto = emptyList(),
            secrets = emptyList(),
            permissions = emptyList(),
            components = emptyList(),
            nativeLibs = listOf(
                LibEntry(
                    path = file.absolutePath,
                    label = file.name,
                    abi = parsed.machineName,
                    size = file.length(),
                    standalone = true
                )
            ),
            standaloneElf = parsed,
            notes = notes
        )
    }

    val archive = session.archive
    if (archive == null) {
        return DeepReport(
            title = project.name,
            subtitle = project.kind.label,
            apk = false,
            stats = emptyList(),
            packers = emptyList(),
            crypto = emptyList(),
            secrets = emptyList(),
            permissions = emptyList(),
            components = emptyList(),
            nativeLibs = emptyList(),
            standaloneElf = null,
            notes = notes
        )
    }

    val strings = ArrayList<String>()
    val dexName = archive.dexNames.firstOrNull()
    if (dexName != null) {
        runCatching { session.dex?.strings(dexName, 20_000) }.getOrNull()?.let(strings::addAll)
    }
    archive.entries
        .filter { it.name.startsWith("assets/") && it.size in 1..1_000_000 }
        .take(12)
        .forEach { entry ->
            runCatching { archive.read(entry.name)?.toString(Charsets.UTF_8) }.getOrNull()?.let(strings::add)
        }

    val classNames = runCatching {
        if (dexName != null) session.dex?.classes(dexName)?.take(4_000)?.map { it.descriptor }.orEmpty()
        else emptyList()
    }.getOrDefault(emptyList())
    val methodRefs = runCatching {
        if (dexName != null) session.dex?.methodRefs(dexName, 4_000).orEmpty() else emptyList()
    }.getOrDefault(emptyList())

    val crypto = runCatching { CryptoScanner.scan(strings, classNames, methodRefs).first }.getOrDefault(emptyList())
    val secrets = runCatching { DeepScanner.scan(strings, 100) }.getOrDefault(emptyList())
    val packers = runCatching {
        val dexSize = dexName?.let { name -> archive.entries.firstOrNull { it.name == name }?.size ?: 0L } ?: 0L
        PackerDetector.detect(
            entries = archive.entries.map { it.name },
            strings = strings,
            apkSize = archive.totalSize.coerceAtLeast(project.sizeBytes),
            dexSize = dexSize,
            classNames = classNames
        )
    }.getOrDefault(emptyList())

    val nativeLibs = archive.soNames.map { path ->
        val entry = archive.entries.firstOrNull { it.name == path }
        LibEntry(
            path = path,
            label = path.substringAfterLast('/'),
            abi = path.split('/').getOrNull(1).orEmpty(),
            size = entry?.size ?: 0L,
            standalone = false
        )
    }

    val manifest = session.manifest
    val stats = listOf(
        "Package" to (session.summary?.packageName ?: manifest?.packageName ?: "unknown"),
        "Version" to (session.summary?.versionName ?: manifest?.versionName ?: "unknown"),
        "Size" to Fmt.bytes(project.sizeBytes),
        "DEX files" to archive.dexNames.size.toString(),
        "Native libs" to nativeLibs.size.toString(),
        "Native ABIs" to nativeLibs.map { it.abi }.filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { "none" },
        "Assets" to archive.assetCount.toString(),
        "Permissions" to (manifest?.permissions?.size ?: 0).toString(),
        "Exported components" to (manifest?.exportedCount ?: 0).toString(),
        "Packer hits" to packers.size.toString(),
        "Crypto findings" to crypto.size.toString(),
        "String findings" to secrets.size.toString()
    )

    return DeepReport(
        title = session.summary?.label ?: project.name,
        subtitle = listOfNotNull(
            session.summary?.packageName ?: manifest?.packageName,
            session.summary?.versionName?.let { "v$it" },
            if (nativeLibs.isNotEmpty()) "${nativeLibs.size} native libs" else null
        ).joinToString(" · "),
        apk = true,
        stats = stats,
        packers = packers,
        crypto = crypto,
        secrets = secrets,
        permissions = manifest?.permissions.orEmpty(),
        components = manifest?.components.orEmpty(),
        nativeLibs = nativeLibs,
        standaloneElf = null,
        notes = notes
    )
}

@Composable
private fun ReportView(
    modifier: Modifier,
    report: DeepReport,
    onLib: (LibEntry) -> Unit,
    onOpenNative: (LibEntry) -> Unit
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(title = "Deep report", subtitle = "Read-only static analysis") {
                Text(
                    "This combines manifest checks, packer heuristics, crypto/string scanning and ELF metadata. " +
                        "It is a map of likely areas, not a verdict.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard(title = "Overview") {
                report.stats.forEach { (label, value) ->
                    KeyValue(label, value, mono = label == "Package")
                }
                if (report.standaloneElf != null) {
                    ThinDivider()
                    KeyValue("Suspicious imports", report.standaloneElf.suspiciousImports.joinToString(", ").ifBlank { "none" })
                }
            }
        }

        if (report.packers.isNotEmpty()) {
            item {
                SectionCard(title = "Packer / protector", subtitle = "Static heuristics") {
                    report.packers.forEach { finding ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
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

        if (report.crypto.isNotEmpty()) {
            item {
                SectionCard(title = "Crypto usage", subtitle = "Strings and symbol references, not proof of misuse") {
                    report.crypto.take(40).forEach { finding ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Pill(
                                finding.severity,
                                when (finding.severity) {
                                    "high" -> Red
                                    "medium" -> Amber
                                    "low" -> Blue
                                    else -> Teal
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(finding.category, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    finding.evidence,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    finding.explanation,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextDim
                                )
                            }
                        }
                    }
                }
            }
        }

        if (report.secrets.isNotEmpty()) {
            item {
                SectionCard(title = "URLs, endpoints and secret-shaped strings", subtitle = "Check before trusting; many are false positives") {
                    report.secrets.take(60).forEach { finding ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Pill(finding.kind, when (finding.kind) {
                                "AWS access key", "Google API key", "JWT", "Private key header" -> Red
                                "Secret-shaped assignment" -> Amber
                                else -> Blue
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(
                                finding.value,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (report.permissions.isNotEmpty()) {
            item {
                SectionCard(title = "Permissions", subtitle = "${report.permissions.size} requested") {
                    report.permissions.take(80).forEach { permission ->
                        Text(
                            permission,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = if (permission.contains("SMS") || permission.contains("CONTACTS") ||
                                permission.contains("LOCATION") || permission.contains("RECORD_AUDIO")
                            ) Amber else TextDim
                        )
                    }
                }
            }
        }

        if (report.components.isNotEmpty()) {
            item {
                SectionCard(title = "Components", subtitle = "${report.components.size} declared") {
                    report.components.take(80).forEach { component ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Pill(component.type, Purple)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    component.name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    buildString {
                                        append("exported: ").append(component.exported)
                                        if (component.permission != null) append(" · permission: ").append(component.permission)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextDim
                                )
                            }
                        }
                    }
                }
            }
        }

        if (report.nativeLibs.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Native libraries",
                    subtitle = "Tap a library, then a function, to build pseudo-C"
                ) {
                    report.nativeLibs.forEach { lib ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLib(lib) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    lib.label,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${lib.abi.ifBlank { "standalone" }} · ${Fmt.bytes(lib.size)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Pill("ELF", Blue)
                        }
                    }
                }
            }
        }

        if (report.standaloneElf != null) {
            item {
                SectionCard(title = "Open this ELF") {
                    Button(
                        onClick = { onOpenNative(report.nativeLibs.first()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Browse functions and pseudo-code") }
                }
            }
        }

        if (report.notes.isNotEmpty()) {
            item {
                SectionCard(title = "Notes") {
                    report.notes.forEach {
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
            Text(
                "Pseudo-code is generated from Capstone output. It omits exact types, structs and some control flow; " +
                    "treat it as a reading aid and verify against the disassembly.",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim
            )
        }
    }
}

@Composable
private fun NativeDeepView(
    modifier: Modifier,
    elf: ElfInfo,
    query: String,
    annotations: Map<Long, NativeAnnotation>,
    onQuery: (String) -> Unit,
    onFunction: (ElfFunction) -> Unit
) {
    val functions = remember(elf, query) {
        if (query.isBlank()) elf.functions
        else elf.functions.filter {
            it.name.contains(query, ignoreCase = true) || Fmt.address(it.address).contains(query, ignoreCase = true)
        }
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "ELF overview") {
                KeyValue("Architecture", elf.machineName)
                KeyValue("Type", elf.typeName)
                KeyValue("Functions", elf.functions.size.toString())
                KeyValue("Symbols", elf.symbols.size.toString())
                KeyValue("Stripped", if (elf.stripped) "yes (.symtab absent)" else "no")
                KeyValue("PIE", if (elf.pie) "yes" else "no")
                KeyValue("NX", if (elf.nx) "yes" else "no")
                KeyValue("RELRO", if (elf.relro) "yes" else "no")
                KeyValue("Needed", elf.needed.joinToString(", ").ifBlank { "none" })
                ThinDivider()
                KeyValue(
                    "Suspicious imports",
                    elf.suspiciousImports.joinToString(", ").ifBlank { "none" },
                    valueColor = if (elf.suspiciousImports.isEmpty()) Green else Amber
                )
            }
        }
        item {
            SearchField(query, onQuery, "Search ${elf.functions.size} functions")
        }
        item {
            Text(
                if (functions.size > 600) "Showing 600 of ${functions.size}. Refine the search."
                else "${functions.size} functions. Tap one for pseudo-code.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (functions.isEmpty()) {
            item { EmptyState("No functions", "No symbols or prologue candidates matched.") }
        } else {
            items(functions.take(600), key = { "${it.address}-${it.name}" }) { fn ->
                val annotation = annotations[fn.address]
                SectionCard(modifier = Modifier.clickable { onFunction(fn) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                annotation?.displayName(fn.name) ?: fn.name,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${Fmt.address(fn.address)} · ${fn.source}" +
                                    if (fn.size > 0) " · ${Fmt.bytes(fn.size)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!annotation?.comment.isNullOrBlank()) {
                                Text(
                                    annotation.comment,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (annotation?.bookmarked == true) Pill("marked", Amber)
                        if (fn.thumb) Pill("thumb", Teal)
                    }
                }
            }
        }
    }
}

@Composable
private fun PseudoView(
    modifier: Modifier,
    result: PseudoResult,
    annotation: NativeAnnotation,
    onCopy: () -> Unit,
    onXrefs: () -> Unit,
    onNotes: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    annotation.displayName(result.function.name),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${Fmt.address(result.function.address)} · ${result.instructionCount} instructions · heuristic",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (annotation.comment.isNotBlank()) {
                    Text(
                        annotation.comment,
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (annotation.bookmarked) Pill("marked", Amber)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BenchOutlinedButton("Copy", onCopy)
            Spacer(Modifier.width(6.dp))
            BenchOutlinedButton("Export .c", onExport)
            Spacer(Modifier.width(6.dp))
            BenchOutlinedButton("Xrefs", onXrefs)
            Spacer(Modifier.width(6.dp))
            BenchOutlinedButton("Notes", onNotes)
            Spacer(Modifier.width(6.dp))
            BenchOutlinedButton("Functions", onBack)
        }
        Text(
            result.note,
            style = MaterialTheme.typography.labelSmall,
            color = Amber,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(6.dp))
        CodeViewer(
            text = result.code,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 11,
            language = CodeLanguage.C,
            maxLines = 4000
        )
    }
}

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
