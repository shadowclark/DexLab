package com.dexlab.app.ui.screens

import android.content.Context
import android.os.Build
import android.os.Process
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.apk.AndroidAttrNames
import com.dexlab.app.apk.AxmlParser
import com.dexlab.app.apk.ManifestReader
import com.dexlab.app.core.Fmt
import com.dexlab.app.core.Workspace
import com.dexlab.app.crypto.CryptoKit
import com.dexlab.app.dex.DexStrings
import com.dexlab.app.lab.Checker
import com.dexlab.app.logcat.LogcatParser
import com.dexlab.app.native.CapstoneBridge
import com.dexlab.app.native.ElfParser
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.LoadingPane
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

data class Check(val name: String, val ok: Boolean, val detail: String)

@Composable
fun DiagnosticsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var nonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(nonce) {
        running = true
        checks = withContext(Dispatchers.IO) { runChecks(context) }
        running = false
    }

    LabScaffold(
        title = "Diagnostics",
        subtitle = "Real checks on this device",
        onBack = { nav.popBackStack() }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SectionCard(title = "Self-check") {
                    Text(
                        "These checks exercise the same code paths the analyzer uses. A red result is a real failure, not a cosmetic warning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { nonce++ }, enabled = !running) {
                        Text(if (running) "Running…" else "Run again")
                    }
                }
            }
            if (running) {
                item { LoadingPane("Running native, DEX, manifest and crypto checks…") }
            } else {
                item {
                    val passed = checks.count { it.ok }
                    SectionCard(title = "Result", subtitle = "$passed of ${checks.size} passed") {
                        checks.forEach { check ->
                            CheckRow(check)
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Device") {
                    Text("${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${if (Process.is64Bit()) "64-bit" else "32-bit"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Workspace: ${Fmt.bytes(runCatching { Workspace(context).size() }.getOrDefault(0L))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim
                    )
                }
            }
            item {
                Text(
                    "Root and Shizuku paths are code-complete but cannot be verified here without a rooted device or a Shizuku service.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CheckRow(check: Check) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            if (check.ok) "✓" else "✕",
            modifier = Modifier.width(24.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (check.ok) Green else Red
        )
        Column(Modifier.weight(1f)) {
            Text(check.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                check.detail,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun runChecks(context: Context): List<Check> {
    val out = ArrayList<Check>()

    val capstone = CapstoneBridge.version()
    out.add(Check("Capstone native library", CapstoneBridge.available, capstone))
    val insn = runCatching {
        CapstoneBridge.disassemble(
            CapstoneBridge.ARCH_ARM64,
            CapstoneBridge.MODE_ARM,
            byteArrayOf(0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte()),
            0x1000
        )
    }.getOrNull()
    out.add(
        Check(
            "Capstone disassembles ARM64",
            insn?.firstOrNull()?.mnemonic == "ret",
            insn?.firstOrNull()?.let { "${it.mnemonic} ${it.operands}" } ?: "no instruction decoded"
        )
    )

    val so = File(context.applicationInfo.nativeLibraryDir, "libsample.so")
    val secret = runCatching { Checker.nativeSecret() }.getOrNull()
    out.add(
        Check(
            "Sample JNI library",
            so.exists() && secret?.contains("arm64") == true,
            "exists=${so.exists()} size=${Fmt.bytes(so.length())} secret=${secret ?: "error"}"
        )
    )

    val elf = runCatching { ElfParser().parse(so) }.getOrNull()
    out.add(
        Check(
            "ELF parser",
            elf != null && elf.functions.isNotEmpty(),
            elf?.let { "${it.arch.label} · ${it.functions.size} functions · ${it.imports.size} imports · NX=${it.nx} RELRO=${it.relro}" }
                ?: "parse failed"
        )
    )

    val dex = runCatching {
        context.assets.open("lab/sample.dex").use { it.readBytes() }
    }.getOrNull()
    val strings = dex?.let { DexStrings.readBytes(it, 5000) }.orEmpty()
    out.add(
        Check(
            "DEX string table",
            strings.any { it.contains("s3cr3t-lab") },
            "${strings.size} strings · password present=${strings.any { it.contains("s3cr3t-lab") }}"
        )
    )

    val manifest = runCatching {
        ZipFile(File(context.applicationInfo.sourceDir)).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return@use null
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val root = AxmlParser(AndroidAttrNames.resolver).parse(bytes)
            ManifestReader.summarize(root)
        }
    }.getOrNull()
    out.add(
        Check(
            "AXML manifest parser",
            manifest?.packageName == context.packageName,
            "package=${manifest?.packageName ?: "unavailable"} · components=${manifest?.components?.size ?: 0}"
        )
    )

    val sha = CryptoKit.hash("SHA-256", "abc".toByteArray())
    out.add(
        Check(
            "Crypto vector (SHA-256)",
            sha == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha
        )
    )

    val parsed = LogcatParser.parse("01-01 12:00:00.000  123  456 I DexLab: hello")
    out.add(
        Check(
            "Logcat threadtime parser",
            parsed?.tag == "DexLab" && parsed?.message == "hello",
            parsed?.let { "tag=${it.tag} pid=${it.pid} message=${it.message}" } ?: "no match"
        )
    )

    val entropy = CryptoKit.entropy("aaaaaaaa".toByteArray())
    out.add(Check("Entropy calculation", entropy < 0.01, "entropy(aaaaaaaa)=%.4f".format(entropy)))

    return out
}
