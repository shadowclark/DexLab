package com.dexlab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dexlab.app.core.AppSettings
import com.dexlab.app.security.TamperCheck
import com.dexlab.app.ui.DexLabRoot
import com.dexlab.app.ui.theme.DexLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        if (intent.getBooleanExtra("skip_disclaimer", false)) {
            AppSettings.acceptDisclaimer(this)
        }
        setContent {
            DexLabTheme(AppSettings.themeMode) {
                if (!AppSettings.disclaimerAccepted) {
                    DisclaimerScreen(onAccept = { AppSettings.acceptDisclaimer(this) })
                } else {
                    DexLabRoot()
                }

                val integrity = remember { TamperCheck.inspect(this@MainActivity) }
                var dismissed by remember { mutableStateOf(false) }
                if (AppSettings.disclaimerAccepted && !integrity.official && !dismissed) {
                    AlertDialog(
                        onDismissRequest = { dismissed = true },
                        title = { Text("Modified copy detected") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "This APK is signed with a different certificate than the reference DexLab build. It may have been repackaged or modified. If you built it yourself, this is expected.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Expected: ${integrity.expectedSha256.take(16)}…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Actual: ${integrity.actualSha256?.take(16) ?: "unavailable"}…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { dismissed = true }) { Text("Continue anyway") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclaimerScreen(onAccept: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(20.dp))
            Text("DexLab", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "A pocket reverse-engineering workbench and academy.",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Built for phones, not desks. Read DEX and smali, disassemble ARM and x86 native code, inspect manifests, decode data, and follow a guided course. Everything runs offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text("Before you begin", style = MaterialTheme.typography.titleLarge)
            Bullet("Analyze only apps you own or have explicit permission to test.")
            Bullet("DexLab is static-only. It does not patch, crack, repackage or bypass payments.")
            Bullet("Native code runs inside the app sandbox. System-wide logcat needs root or Shizuku.")
            Bullet("You are responsible for the laws and contracts that apply where you live.")
            Bullet("A string named password is a lead, not proof. Verify before you report.")
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { checked = !checked }
            ) {
                Checkbox(checked = checked, onCheckedChange = { checked = it })
                Text(
                    "I understand and will use DexLab responsibly.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Button(
                onClick = onAccept,
                enabled = checked,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enter the lab")
            }
            Text(
                "This notice is stored locally and can be reviewed in Settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
