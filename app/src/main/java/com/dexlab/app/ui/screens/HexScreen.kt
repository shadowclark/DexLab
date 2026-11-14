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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.dexlab.app.core.ToolFiles
import com.dexlab.app.core.ToolTarget
import com.dexlab.app.ui.components.CopyTextButton
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.KeyValue
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.TextDim
import com.dexlab.app.tools.HexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HexScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(ToolTarget.file) }
    var offset by remember { mutableLongStateOf(0L) }
    var offsetText by remember { mutableStateOf("0") }
    var page by remember { mutableStateOf(ByteArray(0)) }
    var hashes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var hashing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { runCatching { ToolFiles.import(context, uri) }.getOrNull() }
                if (imported != null) {
                    file = imported
                    ToolTarget.file = imported
                    offset = 0
                    offsetText = "0"
                    hashes = emptyMap()
                }
            }
        }
    }

    LaunchedEffect(file, offset) {
        val f = file ?: return@LaunchedEffect
        page = withContext(Dispatchers.IO) { runCatching { HexFile(f).read(offset, 4096) }.getOrDefault(ByteArray(0)) }
    }

    val magic = remember(file) { file?.let { runCatching { HexFile(it).magic() }.getOrDefault("unknown") } }
    val total = file?.length() ?: 0L

    LabScaffold(
        title = "Hex viewer",
        subtitle = file?.name ?: "no file selected",
        onBack = { nav.popBackStack() }
    ) { padding ->
        val f = file
        if (f == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                EmptyState("Open a file", "Pick any file to inspect its bytes. Nothing is uploaded.") {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Open file") }
                }
            }
        } else {
            val rows = remember(page) {
                val out = ArrayList<Pair<Long, String>>()
                var i = 0
                while (i < page.size) {
                    out.add((offset + i) to hexRow(offset + i, page, i, 8))
                    i += 8
                }
                out
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SectionCard(title = "File") {
                        KeyValue("Name", f.name, mono = true)
                        KeyValue("Size", Fmt.bytes(total))
                        KeyValue("Magic", magic ?: "unknown")
                        KeyValue("Page", "${offset} – ${minOf(total, offset + 4096)}")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Change file") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        hashing = true
                                        hashes = withContext(Dispatchers.IO) {
                                            mapOf(
                                                "MD5" to HexFile(f).hash("MD5"),
                                                "SHA-1" to HexFile(f).hash("SHA-1"),
                                                "SHA-256" to HexFile(f).hash("SHA-256")
                                            )
                                        }
                                        hashing = false
                                    }
                                },
                                enabled = !hashing
                            ) { Text(if (hashing) "Hashing…" else "Hash file") }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                ToolTarget.seed = page.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                                ToolTarget.seedFormat = "Hex"
                                nav.navigate("crypto")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Send this page to Crypto Lab") }
                        if (hashes.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            hashes.forEach { (name, value) ->
                                KeyValue(name, value, mono = true, valueColor = Green)
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = offsetText,
                            onValueChange = { offsetText = it },
                            modifier = Modifier.width(130.dp),
                            label = { Text("Offset (hex)", style = MaterialTheme.typography.labelSmall) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont)
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = {
                            val target = offsetText.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                            if (target != null) {
                                offset = (target - (target % 8)).coerceIn(0, maxOf(0, total - 1))
                            }
                        }) { Text("Go") }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = {
                            offset = (offset - 4096).coerceAtLeast(0)
                            offsetText = offset.toString(16)
                        }, enabled = offset > 0) { Text("Prev") }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = {
                            offset = (offset + 4096).coerceAtMost(maxOf(0, total - 1))
                            offsetText = offset.toString(16)
                        }, enabled = offset + 4096 < total) { Text("Next") }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "offset    bytes                                            ascii",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                            color = TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                        Spacer(Modifier.weight(1f))
                        CopyTextButton(
                            page.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) },
                            "Copy page"
                        )
                    }
                }
                items(rows) { (_, line) ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont, fontSize = 10.sp),
                        color = Amber,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
                item {
                    Text(
                        "ASCII column: printable bytes only. A stored entry with high entropy is often encrypted or compressed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

private fun hexRow(base: Long, bytes: ByteArray, start: Int, perRow: Int): String {
    val sb = StringBuilder(48)
    sb.append(base.toString(16).padStart(6, '0')).append("  ")
    for (i in 0 until perRow) {
        if (start + i < bytes.size) {
            sb.append("%02x ".format(bytes[start + i].toInt() and 0xFF))
        } else {
            sb.append("   ")
        }
    }
    sb.append(" |")
    for (i in 0 until perRow) {
        if (start + i < bytes.size) {
            val v = bytes[start + i].toInt() and 0xFF
            sb.append(if (v in 32..126) v.toChar() else '.')
        } else {
            sb.append(' ')
        }
    }
    sb.append('|')
    return sb.toString()
}
