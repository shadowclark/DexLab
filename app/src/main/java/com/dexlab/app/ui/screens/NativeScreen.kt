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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.ProjectSession
import com.dexlab.app.core.SessionCache
import com.dexlab.app.core.Workspace
import com.dexlab.app.export.NativeExport
import com.dexlab.app.export.GraphExportFormat
import com.dexlab.app.native.CapstoneBridge
import com.dexlab.app.native.ElfArch
import com.dexlab.app.native.ElfFunction
import com.dexlab.app.native.ElfInfo
import com.dexlab.app.native.ElfParser
import com.dexlab.app.native.InstructionExplainer
import com.dexlab.app.native.NativeAnnotation
import com.dexlab.app.native.NativeAnnotations
import com.dexlab.app.native.NativeCallGraph
import com.dexlab.app.native.NativeCallGraphBuilder
import com.dexlab.app.native.NativeCfgBuilder
import com.dexlab.app.native.NativeFunctionAnalysis
import com.dexlab.app.native.NativeXrefs
import com.dexlab.app.ui.components.AnalysisTab
import com.dexlab.app.ui.components.BenchOutlinedButton
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
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class LibEntry(
    val path: String,
    val label: String,
    val abi: String,
    val size: Long,
    val standalone: Boolean
)

@Composable
fun NativeScreen(nav: NavHostController, projectId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<ProjectSession?>(null) }
    var libs by remember { mutableStateOf<List<LibEntry>>(emptyList()) }
    var abiFilter by remember { mutableStateOf<String?>(null) }
    var selectedLib by remember { mutableStateOf<LibEntry?>(null) }
    var elf by remember { mutableStateOf<ElfInfo?>(null) }
    var elfFile by remember { mutableStateOf<File?>(null) }
    var libSha256 by remember { mutableStateOf<String?>(null) }
    var loadingLib by remember { mutableStateOf(false) }
    var libError by remember { mutableStateOf<String?>(null) }
    var functionQuery by remember { mutableStateOf("") }
    var selectedFunction by remember { mutableStateOf<ElfFunction?>(null) }
    var instructions by remember { mutableStateOf<List<CapstoneBridge.Insn>>(emptyList()) }
    var disasmLoading by remember { mutableStateOf(false) }
    var disasmError by remember { mutableStateOf<String?>(null) }
    var thumb by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf<CapstoneBridge.Insn?>(null) }
    var jump by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf<NativeFunctionAnalysis?>(null) }
    var callGraph by remember { mutableStateOf<NativeCallGraph?>(null) }
    var analysisLoading by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var showAnalysis by remember { mutableStateOf(false) }
    var analysisTab by remember { mutableStateOf(AnalysisTab.XREFS) }
    var activeAnnotation by remember { mutableStateOf(NativeAnnotation()) }
    var annotationVersion by remember { mutableStateOf(0) }
    var pendingTextExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingFileExport by remember { mutableStateOf<Pair<String, File>?>(null) }
    val listState = rememberLazyListState()

    val textExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingTextExport
        pendingTextExport = null
        if (uri != null && pending != null) {
            runCatching { NativeExport.writeText(context, uri, pending.second) }
                .onSuccess { Toast.makeText(context, "Exported ${pending.first}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val fileExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingFileExport
        pendingFileExport = null
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
        abiFilter = null
        val s = runCatching { SessionCache.get(context, projectId) }.getOrNull()
        session = s
        libs = when {
            s == null -> emptyList()
            s.elfFile != null -> listOf(
                LibEntry(
                    path = s.elfFile.absolutePath,
                    label = s.elfFile.name,
                    abi = archHint(s.elfFile.name),
                    size = s.elfFile.length(),
                    standalone = true
                )
            )
            s.archive != null -> s.archive.soNames.map { path ->
                val entry = s.archive.entries.firstOrNull { it.name == path }
                LibEntry(
                    path = path,
                    label = path.substringAfterLast('/'),
                    abi = path.split('/').getOrNull(1).orEmpty(),
                    size = entry?.size ?: 0L,
                    standalone = false
                )
            }
            else -> emptyList()
        }
    }

    fun loadLib(entry: LibEntry) {
        scope.launch {
            loadingLib = true
            libError = null
            libSha256 = null
            selectedFunction = null
            instructions = emptyList()
            try {
                val file = withContext(Dispatchers.IO) {
                    if (entry.standalone) {
                        session?.elfFile ?: error("Missing native file")
                    } else {
                        val archive = session?.archive ?: error("Missing archive")
                        val dest = Workspace(context).cacheDir(projectId)
                            .resolve("native/${entry.path.replace('/', '_')}")
                        archive.extract(entry.path, dest) ?: error("Missing ${entry.path}")
                    }
                }
                elfFile = file
                libSha256 = withContext(Dispatchers.IO) {
                    runCatching { sha256Hex(file) }.getOrNull()
                }
                val parsed = withContext(Dispatchers.IO) { ElfParser().parse(file) }
                elf = parsed
                thumb = parsed.arch == ElfArch.ARM
                selectedLib = entry
                selectedFunction = null
            } catch (t: Throwable) {
                libError = t.message ?: t.javaClass.simpleName
            } finally {
                loadingLib = false
            }
        }
    }

    fun runDisasm(function: ElfFunction, useThumb: Boolean) {
        val info = elf ?: return
        scope.launch {
            disasmLoading = true
            disasmError = null
            selectedFunction = function
            instructions = emptyList()
            analysis = null
            showAnalysis = false
            activeAnnotation = NativeAnnotations.get(context, info, function)
            try {
                val code = withContext(Dispatchers.IO) { info.functionBytes(function) }
                if (code.isEmpty()) error("No mapped code bytes at ${Fmt.address(function.address)}")
                val arch = when (info.arch) {
                    ElfArch.ARM -> CapstoneBridge.ARCH_ARM
                    ElfArch.ARM64 -> CapstoneBridge.ARCH_ARM64
                    ElfArch.X86, ElfArch.X86_64 -> CapstoneBridge.ARCH_X86
                    else -> error("Unsupported architecture: ${info.arch.label}")
                }
                val mode = when (info.arch) {
                    ElfArch.ARM64 -> CapstoneBridge.MODE_ARM
                    ElfArch.ARM -> if (useThumb) CapstoneBridge.MODE_THUMB else CapstoneBridge.MODE_ARM
                    ElfArch.X86_64 -> CapstoneBridge.MODE_64
                    ElfArch.X86 -> CapstoneBridge.MODE_32
                    else -> CapstoneBridge.MODE_ARM
                }
                instructions = withContext(Dispatchers.IO) {
                    CapstoneBridge.disassemble(arch, mode, code, function.address, 800)
                }
            } catch (t: Throwable) {
                disasmError = t.message ?: t.javaClass.simpleName
            } finally {
                disasmLoading = false
            }
        }
    }

    fun loadAnalysis(function: ElfFunction, tab: AnalysisTab) {
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
                    NativeXrefs.scan(info, function, function.thumb)
                }
                val cfg = withContext(Dispatchers.IO) {
                    NativeCfgBuilder.build(info, function, function.thumb)
                }
                val annotation = NativeAnnotations.get(context, info, function)
                activeAnnotation = annotation
                analysis = NativeFunctionAnalysis(
                    binaryLabel = info.soname ?: info.buildId ?: "${info.arch.label} ELF (${info.bytes.size} bytes)",
                    function = function,
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
        val function = selectedFunction ?: return
        NativeAnnotations.save(context, info, function, annotation)
        activeAnnotation = annotation
        analysis = analysis?.copy(annotation = annotation)
        annotationVersion++
        Toast.makeText(
            context,
            if (annotation.isEmpty) "Local note cleared" else "Local note saved",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun exportGraph(format: GraphExportFormat) {
        val current = analysis ?: return
        val name = NativeExport.suggestedName("${current.function.name}-graph", format.extension)
        pendingTextExport = name to NativeExport.graph(current, format)
        textExportLauncher.launch(name)
    }

    fun exportDisassembly() {
        val info = elf ?: return
        val function = selectedFunction ?: return
        if (instructions.isEmpty()) {
            Toast.makeText(context, "Nothing decoded to export", Toast.LENGTH_SHORT).show()
            return
        }
        val name = NativeExport.suggestedName("${function.name}-${function.address.toString(16)}", "asm")
        pendingTextExport = name to NativeExport.disassembly(info, function, instructions, activeAnnotation)
        textExportLauncher.launch(name)
    }

    fun exportReport() {
        val current = analysis ?: return
        val name = NativeExport.suggestedName("${current.function.name}-analysis", "txt")
        pendingTextExport = name to NativeExport.report(current)
        textExportLauncher.launch(name)
    }

    fun exportLibrary() {
        val file = elfFile ?: return
        val name = NativeExport.suggestedName(selectedLib?.label ?: file.name, "so")
        pendingFileExport = name to file
        fileExportLauncher.launch(name)
    }

    BackHandler(enabled = selectedLib != null || selectedFunction != null) {
        if (selectedFunction != null) {
            selectedFunction = null
            instructions = emptyList()
            showAnalysis = false
        } else {
            selectedLib = null
            elf = null
        }
    }

    val annotations = remember(elf, annotationVersion) {
        elf?.let { NativeAnnotations.all(context, it, it.functions) } ?: emptyMap()
    }

    val title = when {
        selectedFunction != null -> activeAnnotation.displayName(selectedFunction!!.name)
        selectedLib != null -> selectedLib!!.label
        else -> "Native libraries"
    }
    val subtitle = when {
        selectedFunction != null -> selectedFunction!!.let { "${Fmt.address(it.address)} · ${it.size} bytes · ${it.source}" }
        selectedLib != null -> "${selectedLib!!.abi.ifBlank { "standalone" }} · ${Fmt.bytes(selectedLib!!.size)}"
        else -> "ELF parsing and Capstone disassembly"
    }

    LabScaffold(
        title = title,
        subtitle = subtitle,
        onBack = {
            when {
                selectedFunction != null -> {
                    selectedFunction = null
                    instructions = emptyList()
                    showAnalysis = false
                }
                selectedLib != null -> {
                    selectedLib = null
                    elf = null
                }
                else -> nav.popBackStack()
            }
        }
    ) { padding ->
        when {
            loadingLib -> LoadingPane("Mapping ELF and symbols…")
            libError != null -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                ErrorPane(libError!!) { libError = null }
            }
            selectedFunction != null -> DisassemblyView(
                modifier = Modifier.fillMaxSize().padding(padding),
                elf = elf,
                function = selectedFunction!!,
                instructions = instructions,
                loading = disasmLoading,
                error = disasmError,
                thumb = thumb,
                onThumb = {
                    thumb = it
                    runDisasm(selectedFunction!!, it)
                },
                jump = jump,
                onJump = { jump = it },
                listState = listState,
                onExplain = { explain = it },
                onXrefs = { selectedFunction?.let { loadAnalysis(it, AnalysisTab.XREFS) } },
                onNotes = { selectedFunction?.let { loadAnalysis(it, AnalysisTab.NOTES) } },
                onExport = { exportDisassembly() },
                annotation = activeAnnotation,
                onBack = {
                    selectedFunction = null
                    instructions = emptyList()
                }
            )
            selectedLib != null && elf != null -> LibDetail(
                modifier = Modifier.fillMaxSize().padding(padding),
                elf = elf!!,
                sha256 = libSha256,
                annotations = annotations,
                functionQuery = functionQuery,
                onFunctionQuery = { functionQuery = it },
                onFunction = { runDisasm(it, thumb) },
                onExportLibrary = { exportLibrary() }
            )
            else -> LibList(
                modifier = Modifier.fillMaxSize().padding(padding),
                libs = libs,
                selectedAbi = abiFilter,
                onAbi = { abiFilter = it },
                onOpen = { loadLib(it) }
            )
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
                runDisasm(target, target.thumb)
            } else {
                Toast.makeText(
                    context,
                    "No function entry at 0x${address.toString(16)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        onSaveAnnotation = { saveAnnotation(it) },
        onExport = { exportReport() },
        onExportGraph = { format -> exportGraph(format) }
    )

    val e = explain
    if (e != null) {
        val arch = elf?.arch ?: ElfArch.UNKNOWN
        val description = InstructionExplainer.explain(arch, e.mnemonic)
        val registerNotes = registerNotes(arch, e.operands)
        AlertDialog(
            onDismissRequest = { explain = null },
            title = {
                Text(
                    "${e.mnemonic} ${e.operands}".trim(),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = CodeFont)
                )
            },
            text = {
                Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        description ?: "No reference entry for this mnemonic yet. Read the operands and follow the registers.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (registerNotes.isNotEmpty()) {
                        ThinDivider()
                        registerNotes.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                color = Teal
                            )
                        }
                    }
                    ThinDivider()
                    Text(
                        "Address ${Fmt.address(e.address)} · ${e.size} bytes · ${e.bytes}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { explain = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun LibList(
    modifier: Modifier,
    libs: List<LibEntry>,
    selectedAbi: String?,
    onAbi: (String?) -> Unit,
    onOpen: (LibEntry) -> Unit
) {
    val counts = libs.groupingBy { it.abiKey() }.eachCount()
    val abis = counts.keys.sortedWith(compareBy({ abiOrder(it) }, { it }))
    val visible = if (selectedAbi == null) libs else libs.filter { it.abiKey() == selectedAbi }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (libs.isEmpty()) {
            item {
                EmptyState(
                    "No native libraries",
                    "This project has no lib/<abi>/*.so entry. Import a standalone .so or an APK that ships native code."
                )
            }
        } else {
            item {
                Text(
                    "Capstone runs on device. ARM, AArch64 and x86 are supported; stripped libraries still expose .dynsym.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedAbi == null,
                        onClick = { onAbi(null) },
                        label = { Text("All ${libs.size}") }
                    )
                    abis.forEach { abi ->
                        FilterChip(
                            selected = selectedAbi == abi,
                            onClick = { onAbi(abi) },
                            label = { Text("$abi ${counts[abi]}") }
                        )
                    }
                }
            }
            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        "No $selectedAbi libraries",
                        "Choose All to see the libraries shipped for every ABI."
                    )
                }
            }
            items(visible) { lib ->
                SectionCard(modifier = Modifier.clickable { onOpen(lib) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                lib.label,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${lib.abiKey()}  ·  ${Fmt.bytes(lib.size)}",
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
}

private fun LibEntry.abiKey(): String = abi.ifBlank { "standalone" }

private fun abiOrder(abi: String): Int = when (abi) {
    "arm64-v8a" -> 0
    "armeabi-v7a" -> 1
    "x86_64" -> 2
    "x86" -> 3
    "armeabi" -> 4
    "riscv64" -> 5
    "standalone" -> 99
    else -> 50
}

@Composable
private fun LibDetail(
    modifier: Modifier,
    elf: ElfInfo,
    sha256: String?,
    annotations: Map<Long, NativeAnnotation>,
    functionQuery: String,
    onFunctionQuery: (String) -> Unit,
    onFunction: (ElfFunction) -> Unit,
    onExportLibrary: () -> Unit
) {
    val functions = remember(elf, functionQuery) {
        if (functionQuery.isBlank()) elf.functions
        else elf.functions.filter { it.name.contains(functionQuery, ignoreCase = true) }
    }
    val jni = remember(elf) {
        elf.functions.filter { it.name.startsWith("Java_") || it.name == "JNI_OnLoad" }
    }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(title = "ELF overview") {
                KeyValue("Architecture", elf.machineName)
                KeyValue("Type", elf.typeName)
                KeyValue("Endianness", if (elf.littleEndian) "little" else "big")
                KeyValue("PIE", yesNo(elf.pie), valueColor = if (elf.pie) Green else Amber)
                KeyValue("Stripped", if (elf.stripped) "yes (.symtab absent)" else "no", valueColor = if (elf.stripped) Amber else Green)
                KeyValue("RELRO", yesNo(elf.relro), valueColor = if (elf.relro) Green else Amber)
                KeyValue("NX", yesNo(elf.nx), valueColor = if (elf.nx) Green else Red)
                KeyValue("Build ID", elf.buildId ?: "not present", mono = true)
                KeyValue("SHA-256", sha256 ?: "unavailable", mono = true)
                KeyValue("SONAME", elf.soname ?: "not set", mono = true)
                KeyValue("Needed", elf.needed.joinToString(", ").ifBlank { "none" })
                ThinDivider()
                KeyValue("Sections", elf.sections.size.toString())
                KeyValue("Segments", elf.segments.size.toString())
                KeyValue("Symbols", elf.symbols.size.toString())
                KeyValue("Functions", elf.functions.size.toString())
            }
        }
        item {
            OutlinedButton(
                onClick = onExportLibrary,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(2.dp)
            ) {
                Text("Export .so")
            }
        }
        item {
            SectionCard(title = "Sensitive imports", subtitle = "Heuristics, not a verdict") {
                if (elf.suspiciousImports.isEmpty()) {
                    Text(
                        "No imports from the built-in sensitive list (ptrace, dlopen, system, getprop, …).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        elf.suspiciousImports.forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill("import", Amber)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                    color = Amber
                                )
                            }
                        }
                    }
                }
            }
        }
        if (jni.isNotEmpty()) {
            item {
                SectionCard(
                    title = "JNI exports",
                    subtitle = "Symbols following the Java_ naming contract"
                ) {
                    jni.take(80).forEach { fn ->
                        FunctionRow(fn, annotation = annotations[fn.address], onClick = { onFunction(fn) })
                    }
                }
            }
        }
        item {
            SectionCard(title = "Imported libraries", subtitle = "DT_NEEDED") {
                if (elf.needed.isEmpty()) {
                    Text("No DT_NEEDED entries.", style = MaterialTheme.typography.bodySmall, color = TextDim)
                } else {
                    elf.needed.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
        if (annotations.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Local notes",
                    subtitle = "${annotations.size} annotated function${if (annotations.size == 1) "" else "s"}"
                ) {
                    annotations.toList()
                        .sortedBy { it.first }
                        .take(12)
                        .forEach { (address, annotation) ->
                            val fn = elf.functions.firstOrNull { it.address == address }
                            if (fn != null) {
                                FunctionRow(fn, annotation = annotation, onClick = { onFunction(fn) })
                            }
                        }
                }
            }
        }
        item {
            SearchField(functionQuery, onFunctionQuery, "Search ${elf.functions.size} functions")
        }
        item {
            Text(
                if (functions.size > 500) "Showing 500 of ${functions.size} functions. Refine the search."
                else "${functions.size} functions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(functions.take(500), key = { "${it.address}-${it.name}" }) { fn ->
            SectionCard(modifier = Modifier.clickable { onFunction(fn) }) {
                FunctionRow(
                    fn,
                    annotation = annotations[fn.address],
                    onClick = { onFunction(fn) },
                    flat = true
                )
            }
        }
        item {
            Text(
                "Function names come from .symtab or .dynsym. Where symbols are stripped, sub_XXXX entries come from prologue heuristics.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FunctionRow(
    fn: ElfFunction,
    annotation: NativeAnnotation? = null,
    onClick: () -> Unit,
    flat: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                annotation?.displayName(fn.name) ?: fn.name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(Fmt.address(fn.address))
                    if (fn.size > 0) append("  ·  ${Fmt.bytes(fn.size)}")
                    append("  ·  ${fn.source}")
                    if (!annotation?.label.isNullOrBlank()) append("  ·  local")
                },
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

@Composable
private fun DisassemblyView(
    modifier: Modifier,
    elf: ElfInfo?,
    function: ElfFunction,
    instructions: List<CapstoneBridge.Insn>,
    loading: Boolean,
    error: String?,
    thumb: Boolean,
    onThumb: (Boolean) -> Unit,
    jump: String,
    onJump: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onExplain: (CapstoneBridge.Insn) -> Unit,
    onXrefs: () -> Unit,
    onNotes: () -> Unit,
    onExport: () -> Unit,
    annotation: NativeAnnotation,
    onBack: () -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    annotation.displayName(function.name),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${Fmt.address(function.address)}  ·  ${instructions.size} decoded" +
                        if (annotation.label.isNotBlank()) "  ·  local label" else "",
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
            if (elf?.arch == ElfArch.ARM) {
                FilterChip(
                    selected = thumb,
                    onClick = { onThumb(!thumb) },
                    label = { Text("Thumb") },
                    shape = RoundedCornerShape(2.dp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BenchOutlinedButton("Xrefs", onXrefs)
            Spacer(Modifier.width(6.dp))
            BenchOutlinedButton("Notes", onNotes)
            Spacer(Modifier.width(6.dp))
            BenchOutlinedButton("Export .asm", onExport)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InlineJump(jump, onJump, listState, instructions)
            Spacer(Modifier.width(6.dp))
            BenchOutlinedButton("Symbols", onBack)
        }
        when {
            loading -> LoadingPane("Disassembling ${function.name}…")
            error != null -> Column(Modifier.padding(16.dp)) {
                ErrorPane(error)
            }
            instructions.isEmpty() -> EmptyState("No instructions", "Capstone decoded nothing from these bytes.")
            else -> {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Tap an instruction for an explanation.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    items(instructions) { insn ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onExplain(insn) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                hex(insn.address),
                                modifier = Modifier.width(64.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                color = Color4D
                            )
                            Text(
                                insn.bytes.padEnd(16),
                                modifier = Modifier.width(112.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                color = Teal,
                                maxLines = 1
                            )
                            Text(
                                insn.mnemonic,
                                modifier = Modifier.width(58.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                color = Amber,
                                maxLines = 1
                            )
                            Text(
                                insn.operands,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineJump(
    jump: String,
    onJump: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    instructions: List<CapstoneBridge.Insn>
) {
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = jump,
            onValueChange = onJump,
            modifier = Modifier.width(150.dp),
            label = { Text("Address", style = MaterialTheme.typography.labelSmall) },
            placeholder = { Text("0x1000", style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont)
        )
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = {
                val target = jump.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return@OutlinedButton
                val index = instructions.indexOfFirst { it.address >= target }
                if (index >= 0) {
                    scope.launch { listState.scrollToItem((index - 3).coerceAtLeast(0)) }
                }
            },
            shape = RoundedCornerShape(2.dp)
        ) { Text("Go") }
    }
}

private val Color4D = TextDim

private fun hex(value: Long): String = value.toString(16).padStart(8, '0')

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return buildString(64) {
        for (byte in digest.digest()) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

private val HEX = "0123456789abcdef".toCharArray()

private fun archHint(name: String): String = when {
    name.contains("arm64") -> "arm64-v8a"
    name.contains("armeabi") -> "armeabi-v7a"
    name.contains("x86_64") -> "x86_64"
    name.contains("x86") -> "x86"
    else -> ""
}

private fun registerNotes(arch: ElfArch, operands: String): List<String> {
    val tokens = operands
        .replace("[", " ")
        .replace("]", " ")
        .replace("!", " ")
        .replace("#", " ")
        .split(",", " ")
        .map { it.trim().trimEnd(',') }
        .filter { it.isNotBlank() }
    val out = LinkedHashSet<String>()
    for (token in tokens) {
        val name = token.substringBefore(' ').trim()
        if (name.isEmpty()) continue
        if (name.toLongOrNull(16) != null) continue
        val note = InstructionExplainer.register(arch, name) ?: continue
        out.add("$name — $note")
    }
    return out.toList()
}
