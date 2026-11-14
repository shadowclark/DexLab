package com.dexlab.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.dexlab.app.core.ToolTarget
import com.dexlab.app.core.Workspace
import com.dexlab.app.learn.LessonModule
import com.dexlab.app.learn.LessonRepository
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SearchField
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.components.ThinDivider
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LearnScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repo = remember { LessonRepository(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openLab(kind: String) {
        scope.launch {
            importing = true
            error = null
            try {
                when (kind) {
                    "dex" -> {
                        val p = withContext(Dispatchers.IO) {
                            Workspace(context).importAsset("lab/sample.dex", "sample.dex")
                        }
                        nav.navigate("project/${p.id}")
                    }
                    "so" -> {
                        val p = withContext(Dispatchers.IO) {
                            Workspace(context).importNativeLib("libsample.so")
                        }
                        nav.navigate("project/${p.id}")
                    }
                    "secret" -> {
                        val p = withContext(Dispatchers.IO) {
                            Workspace(context).importAsset("lab/secret.bin", "secret.bin")
                        }
                        ToolTarget.file = File(p.files.first())
                        ToolTarget.title = "secret.bin"
                        nav.navigate("hex")
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                importing = false
            }
        }
    }

    val modules = remember(query, refresh) {
        val all = repo.modules
        if (query.isBlank()) all
        else all.mapNotNull { module ->
            val lessons = module.lessons.filter {
                it.title.contains(query, true) || it.summary.contains(query, true)
            }
            if (lessons.isEmpty() && !module.title.contains(query, true)) null
            else module.copy(lessons = lessons)
        }
    }
    val total = remember(refresh) { repo.allLessons.size }
    val done = remember(refresh) { repo.completedCount() }
    val progress = if (total == 0) 0f else done.toFloat() / total

    LabScaffold(
        title = "Academy",
        subtitle = "Offline modules, real tasks, bundled practice files"
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard(title = "Your progress") {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$done of $total lessons", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Pill("${(progress * 100).toInt()}%", Teal)
                    }
                    Text(
                        "Progress is stored locally. No account, no sync.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard(
                    title = "Practice lab",
                    subtitle = "Three files built into the APK"
                ) {
                    Text(
                        "Start with sample.dex, then the native library, then the XOR secret. Each lesson tells you what to find.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { openLab("dex") },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open sample.dex") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { openLab("so") },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open libsample.so") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { openLab("secret") },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open secret.bin") }
                    if (importing) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (error != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(error!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            item {
                SearchField(query, { query = it }, "Search lessons")
            }

            modules.forEach { module ->
                item {
                    ModuleCard(module, repo) { lessonId ->
                        nav.navigate("lesson/$lessonId")
                    }
                }
            }

            if (modules.isEmpty()) {
                item {
                    Text(
                        "No lessons matched. Try a different search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Text(
                    "Lessons are aligned to OWASP MASTG topics and are intentionally conservative: detection and understanding before any change.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ModuleCard(module: LessonModule, repo: LessonRepository, onLesson: (String) -> Unit) {
    SectionCard(title = module.title, subtitle = module.subtitle) {
        module.lessons.forEachIndexed { index, lesson ->
            if (index > 0) ThinDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onLesson(lesson.id) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (repo.isCompleted(lesson.id)) "✓" else "○",
                    modifier = Modifier.width(24.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (repo.isCompleted(lesson.id)) Green else TextDim
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        lesson.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        lesson.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Pill("${lesson.minutes} min", Amber)
            }
        }
    }
}
