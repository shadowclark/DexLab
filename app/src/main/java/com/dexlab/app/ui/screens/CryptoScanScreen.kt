package com.dexlab.app.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.core.ProjectSession
import com.dexlab.app.core.SessionCache
import com.dexlab.app.crypto.CryptoFinding
import com.dexlab.app.crypto.CryptoScanner
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.LoadingPane
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SearchField
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.components.StatTile
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CryptoScanScreen(nav: NavHostController, projectId: String) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<ProjectSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var findings by remember { mutableStateOf<List<CryptoFinding>>(emptyList()) }
    var keys by remember { mutableStateOf<List<String>>(emptyList()) }
    var stringCount by remember { mutableStateOf(0) }
    var classCount by remember { mutableStateOf(0) }
    var methodCount by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(projectId) {
        loading = true
        try {
            val s = SessionCache.get(context, projectId)
            session = s
            val repo = s?.dex ?: error("This project has no DEX to scan")
            val name = repo.dexNames.firstOrNull() ?: error("No DEX files")
            val strings = withContext(Dispatchers.IO) { repo.strings(name, 150_000) }
            val classes = withContext(Dispatchers.IO) { repo.classes(name) }
            val methods = withContext(Dispatchers.IO) {
                runCatching { repo.methodRefs(name, 40_000) }.getOrDefault(emptyList())
            }
            stringCount = strings.size
            classCount = classes.size
            methodCount = methods.size
            val (f, k) = withContext(Dispatchers.Default) {
                CryptoScanner.scan(strings, classes.map { it.displayName }, methods)
            }
            findings = f
            keys = k
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        }
        loading = false
    }

    LabScaffold(
        title = "Crypto scanner",
        subtitle = session?.project?.name ?: "static DEX hints",
        onBack = { nav.popBackStack() }
    ) { padding ->
        when {
            loading -> LoadingPane("Reading strings, classes and method references…")
            error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                EmptyState("Scan failed", error!!)
            }
            else -> {
                val filtered = remember(findings, query) {
                    if (query.isBlank()) findings
                    else findings.filter {
                        it.category.contains(query, true) ||
                            it.evidence.contains(query, true) ||
                            it.explanation.contains(query, true)
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Strings", stringCount.toString(), Modifier.weight(1f), Teal)
                            StatTile("Classes", classCount.toString(), Modifier.weight(1f), Amber)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Methods", methodCount.toString(), Modifier.weight(1f), Green)
                            StatTile("Findings", findings.size.toString(), Modifier.weight(1f), Red)
                        }
                    }
                    item {
                        SearchField(query, { query = it }, "Filter findings")
                    }
                    if (keys.isNotEmpty()) {
                        item {
                            SectionCard(
                                title = "Key and IV candidates",
                                subtitle = "Length and shape only — verify in the code"
                            ) {
                                keys.forEach { key ->
                                    Text(
                                        key,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                        modifier = Modifier.padding(vertical = 3.dp),
                                        color = Amber
                                    )
                                }
                            }
                        }
                    }
                    if (findings.isEmpty()) {
                        item {
                            EmptyState(
                                "No obvious crypto hints",
                                "The scanner looks for mode names, weak hashes, key-like strings and native crypto references. Clean results are not proof of safe crypto."
                            )
                        }
                    } else {
                        item {
                            Text(
                                "${filtered.size} matching findings, grouped by severity. Every hit is a lead, not a verdict.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(filtered) { finding ->
                            SectionCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Pill(
                                        finding.severity,
                                        when (finding.severity) {
                                            "high" -> Red
                                            "medium" -> Amber
                                            "low" -> Teal
                                            else -> TextDim
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        finding.category,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Pill(finding.location, TextDim)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    finding.evidence,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                    color = com.dexlab.app.ui.theme.TextMain,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    finding.explanation,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            "The scanner reads DEX strings, class names and method references. It does not execute code and does not decrypt anything.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
