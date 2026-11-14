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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.core.ToolTarget
import com.dexlab.app.crypto.CryptoKit
import com.dexlab.app.crypto.DataFormat
import com.dexlab.app.crypto.XorCandidate
import com.dexlab.app.ui.components.CodeBlock
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.OptionChips
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.components.ThinDivider
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim

@Composable
fun CryptoLabScreen(nav: NavHostController) {
    val seeded = remember { ToolTarget.seed }
    var mode by remember { mutableStateOf(if (seeded != null) "XOR" else "Encode") }
    var input by remember { mutableStateOf(seeded ?: "DexLab{xor_is_not_security}") }
    var inputFormat by remember { mutableStateOf(if (seeded != null) (ToolTarget.seedFormat ?: "Hex") else DataFormat.TEXT.label) }
    var outputFormat by remember { mutableStateOf(DataFormat.HEX.label) }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    var algorithm by remember { mutableStateOf("AES") }
    var cipherMode by remember { mutableStateOf("CBC") }
    var paddingMode by remember { mutableStateOf("PKCS5Padding") }
    var keyText by remember { mutableStateOf("000102030405060708090a0b0c0d0e0f") }
    var keyFormat by remember { mutableStateOf(DataFormat.HEX.label) }
    var ivText by remember { mutableStateOf("101112131415161718191a1b1c1d1e1f") }
    var hashAlgorithm by remember { mutableStateOf("SHA-256") }
    var candidates by remember { mutableStateOf<List<XorCandidate>>(emptyList()) }

    LaunchedEffect(Unit) {
        ToolTarget.seed = null
        ToolTarget.seedFormat = null
    }

    LaunchedEffect(cipherMode, algorithm) {
        if (cipherMode == "GCM" || algorithm == "RC4") paddingMode = "NoPadding"
        if (algorithm == "RC4") cipherMode = "CBC"
    }

    fun fail(t: Throwable) {
        error = t.message ?: t.javaClass.simpleName
        output = ""
    }

    fun convert() {
        try {
            val data = CryptoKit.decode(input, formatOf(inputFormat))
            output = CryptoKit.encode(data, formatOf(outputFormat))
            error = null
            note = CryptoKit.identifyHash(CryptoKit.encode(data, DataFormat.HEX))
        } catch (t: Throwable) {
            fail(t)
        }
    }

    fun hash() {
        try {
            val data = CryptoKit.decode(input, formatOf(inputFormat))
            output = CryptoKit.hash(hashAlgorithm, data)
            error = null
            note = "Fixed-length digest. A hash is not reversible; compare guesses instead."
        } catch (t: Throwable) {
            fail(t)
        }
    }

    fun applyXor() {
        try {
            val data = CryptoKit.decode(input, formatOf(inputFormat))
            val key = CryptoKit.decode(keyText, formatOf(keyFormat))
            require(key.isNotEmpty()) { "Key is empty" }
            output = CryptoKit.encode(CryptoKit.xor(data, key), formatOf(outputFormat))
            error = null
            note = "XOR is symmetric: applying the same key again recovers the input."
        } catch (t: Throwable) {
            fail(t)
        }
    }

    fun bruteXor() {
        try {
            val data = CryptoKit.decode(input, formatOf(inputFormat))
            candidates = CryptoKit.bruteXor(data, 16)
            error = null
            note = if (candidates.isEmpty()) "No candidate scored as text. The input may not be single-byte XOR." else null
        } catch (t: Throwable) {
            fail(t)
        }
    }

    fun runCipher(encrypt: Boolean) {
        try {
            val data = CryptoKit.decode(input, formatOf(inputFormat))
            val key = CryptoKit.decode(keyText, formatOf(keyFormat))
            val iv = if (cipherMode == "ECB" || algorithm == "RC4") null else CryptoKit.decode(ivText, formatOf(keyFormat))
            val result = when (algorithm) {
                "AES" -> CryptoKit.aes(cipherMode, paddingMode, key, iv, data, encrypt)
                "DES" -> CryptoKit.des(false, cipherMode, paddingMode, key, iv, data, encrypt)
                "3DES" -> CryptoKit.des(true, cipherMode, paddingMode, key, iv, data, encrypt)
                "RC4" -> CryptoKit.rc4(key, data)
                else -> error("Unknown algorithm")
            }
            output = CryptoKit.encode(result, formatOf(outputFormat))
            error = null
            note = when {
                algorithm == "RC4" -> "RC4 has known biases. Do not use it for new designs."
                cipherMode == "ECB" -> "ECB leaks block patterns. This is a demonstration, not a recommendation."
                cipherMode == "GCM" -> "GCM is authenticated; never reuse a nonce with the same key."
                else -> "CBC alone has no integrity check. A real design needs a MAC or an AEAD mode."
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    fun fillExample() {
        when (algorithm) {
            "AES" -> {
                keyText = "000102030405060708090a0b0c0d0e0f"
                ivText = "101112131415161718191a1b1c1d1e1f"
                keyFormat = DataFormat.HEX.label
            }
            "DES" -> {
                keyText = "0123456789abcdef"
                ivText = "1234567890abcdef"
                keyFormat = DataFormat.HEX.label
            }
            "3DES" -> {
                keyText = "0123456789abcdef0123456789abcdef0123456789abcdef"
                ivText = "1234567890abcdef"
                keyFormat = DataFormat.HEX.label
            }
            "RC4" -> {
                keyText = "dexlab"
                keyFormat = DataFormat.TEXT.label
            }
        }
    }

    LabScaffold(
        title = "Crypto Lab",
        subtitle = "Encoding, hashing and classical ciphers",
        onBack = { nav.popBackStack() }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OptionChips(listOf("Encode", "Hash", "XOR", "AES / DES"), mode) {
                    mode = it
                    output = ""
                    error = null
                    note = null
                }
            }
            item {
                SectionCard(title = "Input") {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                        label = { Text("Data", style = MaterialTheme.typography.labelSmall) }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Input format", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    OptionChips(DataFormat.entries.map { it.label }, inputFormat) { inputFormat = it }
                    Spacer(Modifier.height(6.dp))
                    Text("Output format", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    OptionChips(DataFormat.entries.map { it.label }, outputFormat) { outputFormat = it }
                }
            }

            when (mode) {
                "Encode" -> item {
                    SectionCard(title = "Convert") {
                        Text(
                            "Decode the input, then re-encode the bytes. Base64 and hex are reversible encodings, not encryption.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { convert() }, modifier = Modifier.fillMaxWidth()) { Text("Convert") }
                    }
                }
                "Hash" -> item {
                    SectionCard(title = "Hash") {
                        OptionChips(listOf("MD5", "SHA-1", "SHA-256", "SHA-512"), hashAlgorithm) { hashAlgorithm = it }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The output is always hex. MD5 and SHA-1 are shown because you will meet them in the wild, not because they are safe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { hash() }, modifier = Modifier.fillMaxWidth()) { Text("Hash input") }
                    }
                }
                "XOR" -> item {
                    SectionCard(title = "XOR") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = keyText,
                                onValueChange = { keyText = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Key", style = MaterialTheme.typography.labelSmall) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Key format", style = MaterialTheme.typography.labelSmall, color = TextDim)
                                OptionChips(DataFormat.entries.map { it.label }, keyFormat) { keyFormat = it }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { applyXor() }, modifier = Modifier.weight(1f)) { Text("Apply key") }
                            OutlinedButton(onClick = { bruteXor() }, modifier = Modifier.weight(1f)) { Text("Brute force") }
                        }
                        if (candidates.isNotEmpty()) {
                            ThinDivider()
                            Text("Top single-byte candidates", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            candidates.forEach { candidate ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Pill("0x%02x".format(candidate.key), Teal)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            candidate.preview,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "score %.2f".format(candidate.score),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextDim
                                        )
                                    }
                                    TextButton(onClick = {
                                        keyText = "%02x".format(candidate.key)
                                        keyFormat = DataFormat.HEX.label
                                    }) { Text("Use") }
                                }
                            }
                        }
                    }
                }
                else -> item {
                    SectionCard(title = "Block cipher") {
                        OptionChips(listOf("AES", "DES", "3DES", "RC4"), algorithm) {
                            algorithm = it
                            fillExample()
                        }
                        if (algorithm != "RC4") {
                            Spacer(Modifier.height(8.dp))
                            Text("Mode", style = MaterialTheme.typography.labelSmall, color = TextDim)
                            OptionChips(listOf("CBC", "ECB", "GCM"), cipherMode) { cipherMode = it }
                            Spacer(Modifier.height(8.dp))
                            Text("Padding", style = MaterialTheme.typography.labelSmall, color = TextDim)
                            OptionChips(listOf("PKCS5Padding", "PKCS7Padding", "NoPadding"), paddingMode) { paddingMode = it }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keyText,
                            onValueChange = { keyText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Key", style = MaterialTheme.typography.labelSmall) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                            maxLines = 4
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Key / IV format", style = MaterialTheme.typography.labelSmall, color = TextDim)
                        OptionChips(DataFormat.entries.map { it.label }, keyFormat) { keyFormat = it }
                        if (algorithm != "RC4" && cipherMode != "ECB") {
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = ivText,
                                onValueChange = { ivText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("IV / nonce", style = MaterialTheme.typography.labelSmall) },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                maxLines = 4
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { runCipher(true) }, modifier = Modifier.weight(1f)) { Text("Encrypt") }
                            OutlinedButton(onClick = { runCipher(false) }, modifier = Modifier.weight(1f)) { Text("Decrypt") }
                        }
                        TextButton(onClick = { fillExample() }) { Text("Fill example key and IV") }
                    }
                }
            }

            if (error != null) {
                item {
                    SectionCard(title = "Error") {
                        Text(error!!, style = MaterialTheme.typography.bodySmall, color = Red)
                        Text(
                            "AES keys are 16, 24 or 32 bytes. DES is 8 bytes, 3DES is 24 bytes, CBC IVs are 16 bytes (8 for DES).",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextDim
                        )
                    }
                }
            }
            if (output.isNotBlank()) {
                item {
                    SectionCard(title = "Output") {
                        CodeBlock(output)
                        if (note != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                note!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = Amber
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "The Crypto Lab is a learning tool. It uses the platform JCE providers and keeps every value in memory; nothing is logged or transmitted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatOf(label: String): DataFormat =
    DataFormat.entries.firstOrNull { it.label == label } ?: DataFormat.TEXT
