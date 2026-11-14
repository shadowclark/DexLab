package com.dexlab.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dexlab.app.core.Fmt
import com.dexlab.app.export.GraphExportFormat
import com.dexlab.app.native.CallDirection
import com.dexlab.app.native.CallTreeNode
import com.dexlab.app.native.FunctionXrefs
import com.dexlab.app.native.NativeAnnotation
import com.dexlab.app.native.NativeFunctionAnalysis
import com.dexlab.app.native.NativeXref
import com.dexlab.app.native.XrefKind
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim

enum class AnalysisTab(val label: String) {
    XREFS("XREFS"),
    GRAPH("CALL GRAPH"),
    TREE("CALL TREE"),
    FLOW("BLOCKS"),
    NOTES("NOTES")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeAnalysisSheet(
    visible: Boolean,
    loading: Boolean,
    error: String?,
    analysis: NativeFunctionAnalysis?,
    initialTab: AnalysisTab = AnalysisTab.XREFS,
    onDismiss: () -> Unit,
    onOpenAddress: (Long) -> Unit,
    onSaveAnnotation: (NativeAnnotation) -> Unit,
    onExport: (() -> Unit)? = null,
    onExportGraph: ((GraphExportFormat) -> Unit)? = null
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember(visible, initialTab) { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab, visible) {
        if (visible) tab = initialTab
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 720.dp)
                .padding(horizontal = 18.dp, vertical = 2.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "FUNCTION DOSSIER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = CodeFont,
                            letterSpacing = 1.2.sp
                        ),
                        color = TextDim
                    )
                    Text(
                        analysis?.displayName ?: "Native workbench",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        analysis?.let {
                            "${it.function.name}  ·  ${Fmt.address(it.function.address)}"
                        } ?: "Direct references and local notes",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (analysis != null && onExport != null) {
                    TextButton(onClick = onExport) { Text("Export") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }

            if (loading) {
                LoadingPane("Mapping callers, blocks and local notes…")
            } else if (error != null) {
                ErrorPane(error)
            } else if (analysis == null) {
                EmptyState("No function data", "Select a function and open the dossier again.")
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    AnalysisTab.entries.forEach { item ->
                        Column(
                            Modifier
                                .clickable { tab = item }
                                .padding(vertical = 5.dp)
                        ) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = CodeFont,
                                    letterSpacing = 0.9.sp,
                                    fontWeight = if (tab == item) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (tab == item) Amber else TextDim
                            )
                            Box(
                                Modifier
                                    .padding(top = 4.dp)
                                    .width(if (tab == item) 26.dp else 0.dp)
                                    .height(2.dp)
                                    .background(Amber, RoundedCornerShape(0.dp))
                            )
                        }
                    }
                }
                ThinDivider()
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 545.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        when (tab) {
                            AnalysisTab.XREFS -> XrefsPane(analysis.xrefs, onOpenAddress)
                            AnalysisTab.GRAPH -> Column {
                                CallGraphView(
                                    graph = analysis.graph,
                                    focusAddress = analysis.function.address,
                                    onOpen = onOpenAddress
                                )
                                Spacer(Modifier.height(6.dp))
                                if (onExportGraph != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        BenchOutlinedButton("DOT", { onExportGraph(GraphExportFormat.DOT) })
                                        BenchOutlinedButton("JSON", { onExportGraph(GraphExportFormat.JSON) })
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                                Text(
                                    analysis.graph.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextDim
                                )
                            }
                            AnalysisTab.TREE -> CallTreePane(
                                analysis = analysis,
                                onOpenAddress = onOpenAddress
                            )
                            AnalysisTab.FLOW -> Column {
                                CfgGraphView(
                                    cfg = analysis.cfg,
                                    onBlock = onOpenAddress
                                )
                                if (onExportGraph != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        BenchOutlinedButton("DOT", { onExportGraph(GraphExportFormat.DOT) })
                                        BenchOutlinedButton("JSON", { onExportGraph(GraphExportFormat.JSON) })
                                    }
                                }
                            }
                            AnalysisTab.NOTES -> AnnotationPane(
                                annotation = analysis.annotation,
                                onSave = onSaveAnnotation
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XrefsPane(xrefs: FunctionXrefs, onOpenAddress: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            xrefs.note,
            style = MaterialTheme.typography.labelSmall,
            color = TextDim
        )
        XrefSection(
            title = "CALLERS",
            subtitle = "${xrefs.callers.size} incoming direct references",
            items = xrefs.callers.take(200),
            addressOf = { it.fromAddress },
            label = { "from ${it.fromFunction}" },
            onOpenAddress = onOpenAddress
        )
        XrefSection(
            title = "CALLEES",
            subtitle = "${xrefs.callees.size} outgoing direct references",
            items = xrefs.callees.take(200),
            addressOf = { it.toAddress },
            label = { "to ${it.toFunction}" },
            onOpenAddress = onOpenAddress
        )
        XrefSection(
            title = "STRING REFERENCES",
            subtitle = "${xrefs.strings.size} obvious literals",
            items = xrefs.strings.take(200),
            addressOf = { null },
            label = { it.text ?: it.toFunction },
            onOpenAddress = onOpenAddress
        )
    }
}

@Composable
private fun XrefSection(
    title: String,
    subtitle: String,
    items: List<NativeXref>,
    addressOf: (NativeXref) -> Long?,
    label: (NativeXref) -> String,
    onOpenAddress: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = CodeFont,
                    letterSpacing = 0.8.sp
                ),
                modifier = Modifier.weight(1f)
            )
            Pill(if (items.isEmpty()) "0" else items.size.toString(), if (items.isEmpty()) TextDim else Blue)
        }
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextDim)
        if (items.isEmpty()) {
            Text(
                "No direct references found.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            items.forEach { xref ->
                val target = addressOf(xref)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (target != null) Modifier.clickable { onOpenAddress(target) } else Modifier)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Pill(
                        xref.kind.name,
                        when (xref.kind) {
                            XrefKind.CALL -> Amber
                            XrefKind.JUMP -> Teal
                            XrefKind.STRING -> Green
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            label(xref),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (xref.kind == XrefKind.STRING) Green else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${Fmt.address(xref.fromAddress)}  ·  ${xref.instruction}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                            color = TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallTreePane(
    analysis: NativeFunctionAnalysis,
    onOpenAddress: (Long) -> Unit
) {
    var direction by remember(analysis.function.address) { mutableStateOf(CallDirection.CALLEES) }
    val root = remember(analysis.graph, analysis.function.address, direction) {
        analysis.graph.tree(
            root = analysis.function.address,
            direction = direction,
            maxDepth = 3,
            maxNodes = 80
        )
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DIRECTION",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                color = TextDim
            )
            Spacer(Modifier.width(10.dp))
            TreeDirectionButton("CALLEES", direction == CallDirection.CALLEES) { direction = CallDirection.CALLEES }
            Spacer(Modifier.width(4.dp))
            TreeDirectionButton("CALLERS", direction == CallDirection.CALLERS) { direction = CallDirection.CALLERS }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Depth 3, up to 80 nodes. Cycles are marked and never expanded.",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim
        )
        Spacer(Modifier.height(4.dp))
        CallTreeRows(root, 0, onOpenAddress)
    }
}

@Composable
private fun TreeDirectionButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) Amber.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(2.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
        color = if (selected) Amber else TextDim
    )
}

@Composable
private fun CallTreeRows(node: CallTreeNode, depth: Int, onOpenAddress: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenAddress(node.address) }
            .padding(start = (depth * 12).dp, top = 3.dp, bottom = 3.dp)
    ) {
        Text(
            if (depth == 0) "•" else "└",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
            color = if (depth == 0) Amber else TextDim,
            modifier = Modifier.width(16.dp)
        )
        Text(
            node.name,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
            color = if (node.cycle) Amber else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "0x${node.address.toString(16)}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
            color = TextDim
        )
        if (node.cycle) {
            Spacer(Modifier.width(4.dp))
            Text("cycle", style = MaterialTheme.typography.labelSmall, color = Amber)
        }
    }
    if (node.more) {
        Text(
            "… more branches not expanded",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim,
            modifier = Modifier.padding(start = ((depth + 1) * 12 + 16).dp, top = 2.dp, bottom = 2.dp)
        )
    }
    node.children.forEach { CallTreeRows(it, depth + 1, onOpenAddress) }
}

@Composable
private fun AnnotationPane(annotation: NativeAnnotation, onSave: (NativeAnnotation) -> Unit) {
    var label by remember(annotation) { mutableStateOf(annotation.label) }
    var comment by remember(annotation) { mutableStateOf(annotation.comment) }
    var bookmarked by remember(annotation) { mutableStateOf(annotation.bookmarked) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Local notes are stored on this device, keyed by binary build ID and function address. They do not modify the analyzed file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Local label", style = MaterialTheme.typography.labelSmall) },
            placeholder = { Text("e.g. decrypt_config", style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont)
        )
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            label = { Text("Analyst note", style = MaterialTheme.typography.labelSmall) },
            placeholder = { Text("What did you verify? What remains uncertain?", style = MaterialTheme.typography.labelSmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = bookmarked, onCheckedChange = { bookmarked = it })
            Spacer(Modifier.width(8.dp))
            Text("Bookmark this function", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                onSave(NativeAnnotation(label = label, comment = comment, bookmarked = bookmarked))
            }) { Text("Save note") }
            TextButton(onClick = {
                label = ""
                comment = ""
                bookmarked = false
                onSave(NativeAnnotation())
            }) { Text("Clear") }
        }
    }
}
