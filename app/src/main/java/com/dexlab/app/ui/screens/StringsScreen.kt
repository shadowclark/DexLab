package com.dexlab.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.ToolFiles
import com.dexlab.app.core.ToolTarget
import com.dexlab.app.ui.components.CopyTextButton
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SearchField
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import com.dexlab.app.tools.StringHit
import com.dexlab.app.tools.StringsExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun StringsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(ToolTarget.file) }
    var minLength by remember { mutableIntStateOf(4) }
    var utf16 by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<StringHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun extract(f: File) {
        scope.launch {
            loading = true
            hits = withContext(Dispatchers.IO) {
                runCatching { StringsExtractor.extract(f, minLength, utf16, 30_000) }.getOrDefault(emptyList())
            }
            loading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { runCatching { ToolFiles.import(context, uri) }.getOrNull() }
                if (imported != null) {
                    file = imported
                    ToolTarget.file = imported
                    hits = emptyList()
                    extract(imported)
                }
            }
        }
    }

    val filtered = remember(hits, query) {
        if (query.isBlank()) hits else hits.filter { it.value.contains(query, ignoreCase = true) }
    }

    LabScaffold(
        title = "Strings",
        subtitle = file?.name ?: "ASCII and UTF-16LE",
        onBack = { nav.popBackStack() }
    ) { padding ->
        val f = file
        if (f == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                EmptyState("Open a file", "Extract printable ASCII or UTF-16LE strings from any file.") {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Open file") }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SectionCard(title = f.name, subtitle = Fmt.bytes(f.length())) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Minimum length: $minLength", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Text("$minLength", style = MaterialTheme.typography.titleMedium, color = Amber)
                        }
                        Slider(
                            value = minLength.toFloat(),
                            onValueChange = { minLength = it.toInt().coerceIn(4, 16) },
                            valueRange = 4f..16f,
                            steps = 11
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = utf16, onCheckedChange = { utf16 = it })
                            Text(
                                "Also scan UTF-16LE",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { extract(f) }, enabled = !loading) {
                                Text(if (loading) "Scanning…" else "Extract")
                            }
                            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Change file") }
                        }
                        if (loading) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Reading in 1 MB chunks. Large files are capped at 256 MB.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item {
                    SearchField(query, { query = it }, "Filter ${hits.size} strings")
                }
                item {
                    Text(
                        if (filtered.size > 1200) "Showing 1200 of ${filtered.size} matches."
                        else "${filtered.size} strings",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (filtered.isEmpty() && !loading) {
                    item {
                        EmptyState(
                            "No strings",
                            if (hits.isEmpty()) "Try a lower minimum length, or enable UTF-16LE." else "No matches for this filter."
                        )
                    }
                } else {
                    items(filtered.take(1200)) { hit ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(Modifier.width(64.dp)) {
                                Text(
                                    hit.offset.toString(16).padStart(6, '0'),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                    color = TextDim
                                )
                                if (hit.encoding != "ASCII") {
                                    Pill("UTF-16", Teal)
                                }
                            }
                            Text(
                                hit.value,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            CopyTextButton(hit.value, "Copy")
                        }
                    }
                }
                item {
                    Text(
                        "Offsets are absolute file offsets. UTF-16LE strings are decoded from pairs of bytes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}
