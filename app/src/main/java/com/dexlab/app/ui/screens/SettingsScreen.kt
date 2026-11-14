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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.BuildConfig
import com.dexlab.app.core.AppSettings
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.Workspace
import com.dexlab.app.learn.LessonRepository
import com.dexlab.app.notice.StaticNotice
import com.dexlab.app.security.TamperCheck
import com.dexlab.app.ui.components.KeyValue
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.OptionChips
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.components.ThinDivider
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableLongStateOf(0L) }
    var cacheBusy by remember { mutableStateOf(false) }
    var showResetProgress by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { runCatching { Workspace(context).size() }.getOrDefault(0L) }
    }

    val themeLabel = when (AppSettings.themeMode) {
        "light" -> "Light"
        "system" -> "System"
        else -> "Dark"
    }

    LabScaffold(
        title = "Settings",
        subtitle = "Appearance, storage and about",
        onBack = { nav.popBackStack() }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard(title = "Appearance") {
                    Text("Theme", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    OptionChips(listOf("Dark", "Light", "System"), themeLabel) {
                        AppSettings.setThemeMode(context, it.lowercase())
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Code font size", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        Text("${AppSettings.fontSize} sp", style = MaterialTheme.typography.titleMedium, color = Amber)
                    }
                    Slider(
                        value = AppSettings.fontSize.toFloat(),
                        onValueChange = { AppSettings.setFontSize(context, it.toInt()) },
                        valueRange = 9f..20f,
                        steps = 10
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = AppSettings.wrapLines,
                            onCheckedChange = { AppSettings.setWrapLines(context, it) }
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("Wrap long code lines", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Line numbers stay aligned when wrapping is off.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Storage") {
                    KeyValue("Workspace size", Fmt.bytes(cacheSize))
                    KeyValue("Location", "app-private files/workspaces", mono = true)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    cacheBusy = true
                                    withContext(Dispatchers.IO) { runCatching { Workspace(context).clearCache() } }
                                    cacheSize = withContext(Dispatchers.IO) { runCatching { Workspace(context).size() }.getOrDefault(0L) }
                                    cacheBusy = false
                                }
                            },
                            enabled = !cacheBusy
                        ) { Text(if (cacheBusy) "Clearing…" else "Clear analysis cache") }
                        Button(onClick = {
                            scope.launch {
                                cacheSize = withContext(Dispatchers.IO) { runCatching { Workspace(context).size() }.getOrDefault(0L) }
                            }
                        }) { Text("Refresh") }
                    }
                    Text(
                        "Clearing the cache removes extracted DEX and native copies. Your imported projects stay.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard(title = "Academy") {
                    val repo = remember { LessonRepository(context) }
                    KeyValue("Completed", "${repo.completedCount()} of ${repo.allLessons.size}")
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { showResetProgress = true }) {
                        Text("Reset lesson progress")
                    }
                }
            }

            item {
                SectionCard(title = "Diagnostics") {
                    Text(
                        "Check the native bridge, ELF parser, DEX reader, manifest decoder, crypto vectors and logcat parser on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { nav.navigate("diagnostics") }) { Text("Run self-check") }
                }
            }

            item {
                SectionCard(title = "Authorization notice") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill("accepted", Green)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showNotice = true }) { Text("Read") }
                    }
                    Text(
                        "Use DexLab only on apps you own or have permission to test. Static analysis is not authorization.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { AppSettings.resetDisclaimer(context) }) {
                        Text("Show the gate again")
                    }
                }
            }

            item {
                val integrity = remember { TamperCheck.inspect(context) }
                SectionCard(
                    title = "Integrity",
                    subtitle = if (integrity.official) "signer matches the reference build" else "modified or re-signed copy"
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (integrity.official) "verified" else "modified", if (integrity.official) Green else Red)
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Signer", integrity.signer ?: "unavailable", mono = true)
                    KeyValue("Actual SHA-256", integrity.actualSha256 ?: "unavailable", mono = true)
                    if (!integrity.official) {
                        KeyValue("Expected SHA-256", integrity.expectedSha256, mono = true)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        integrity.detail ?: "The APK signing certificate matches the embedded reference fingerprint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Advisory check. A patcher can modify the expected fingerprint too; use it as a warning, not proof.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim
                    )
                }
            }

            item {
                SectionCard(title = "For static analysts") {
                    Text(
                        StaticNotice.MESSAGE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This note is also embedded in the manifest, string resources and assets/${StaticNotice.ASSET}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim
                    )
                }
            }

            item {
                SectionCard(title = "About") {
                    KeyValue("App", "DexLab")
                    KeyValue("Version", BuildConfig.VERSION_NAME)
                    KeyValue("Package", BuildConfig.APPLICATION_ID, mono = true)
                    KeyValue("Build", BuildConfig.BUILD_TYPE)
                    ThinDivider()
                    Text(
                        "DexLab is a local, static Android reverse-engineering workbench and academy. It has no account, no telemetry and no network permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard(title = "Licenses and attribution") {
                    Text("Capstone 5.0.7", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Disassembly framework. BSD 3-Clause. Copyright the Capstone project contributors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("baksmali and dexlib2 2.5.2", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Dalvik disassembler and DEX library. BSD 3-Clause. Copyright JesusFreke and contributors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("AndroidX, Jetpack Compose, Kotlin", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Apache License 2.0. Copyright Google LLC and JetBrains s.r.o.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The bundled sample.dex, libsample.so and secret.bin are original teaching material generated for DexLab.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim
                    )
                }
            }

            item {
                Text(
                    "DexLab is a sideloaded tool, not a Play Store app. Static analysis only; dynamic instrumentation is out of scope.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showResetProgress) {
        AlertDialog(
            onDismissRequest = { showResetProgress = false },
            title = { Text("Reset lesson progress?") },
            text = { Text("This clears the completed checkmarks. The lessons themselves stay offline and available.") },
            confirmButton = {
                TextButton(onClick = {
                    LessonRepository(context).resetProgress()
                    showResetProgress = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetProgress = false }) { Text("Cancel") }
            }
        )
    }

    if (showNotice) {
        AlertDialog(
            onDismissRequest = { showNotice = false },
            title = { Text("Before you begin") },
            text = {
                Text(
                    "Analyze only apps you own or have explicit permission to test. DexLab is static-only: it does not patch, crack, repackage or bypass payments. Native code runs in the app sandbox, and system-wide logcat needs root or Shizuku. You are responsible for the laws and contracts where you live.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showNotice = false }) { Text("Close") }
            }
        )
    }
}
