package com.dexlab.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dexlab.app.core.ToolTarget
import com.dexlab.app.core.Workspace
import com.dexlab.app.learn.LessonRepository
import com.dexlab.app.ui.components.CodeBlock
import com.dexlab.app.ui.components.EmptyState
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LessonScreen(nav: NavHostController, lessonId: String) {
    val context = LocalContext.current
    val repo = remember { LessonRepository(context) }
    val lesson = remember(lessonId) { repo.lesson(lessonId) }
    val scope = rememberCoroutineScope()
    var completed by remember(lessonId) { mutableStateOf(repo.isCompleted(lessonId)) }
    var reveal by remember(lessonId) { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openTry(tool: String) {
        scope.launch {
            importing = true
            error = null
            try {
                when (tool) {
                    "self" -> {
                        val p = withContext(Dispatchers.IO) { Workspace(context).importSelf() }
                        nav.navigate("project/${p.id}")
                    }
                    "sample-dex" -> {
                        val p = withContext(Dispatchers.IO) {
                            Workspace(context).importAsset("lab/sample.dex", "sample.dex")
                        }
                        nav.navigate("project/${p.id}")
                    }
                    "sample-so" -> {
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
                    else -> nav.navigate(tool)
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                importing = false
            }
        }
    }

    LabScaffold(
        title = lesson?.title ?: "Lesson",
        subtitle = lesson?.let { "${it.minutes} min · offline" } ?: "not found",
        onBack = { nav.popBackStack() }
    ) { padding ->
        if (lesson == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                EmptyState("Lesson not found", "The lesson id $lessonId is not in the offline curriculum.")
            }
            return@LabScaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard {
                    Row {
                        Pill("${lesson.minutes} min", Amber)
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        if (completed) Pill("completed", Green)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(lesson.title, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        lesson.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            lesson.sections.forEach { section ->
                item {
                    SectionCard(title = section.heading) {
                        Text(section.body, style = MaterialTheme.typography.bodyMedium)
                        if (section.code != null) {
                            Spacer(Modifier.height(10.dp))
                            CodeBlock(section.code, title = "example")
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Your task") {
                    Text(lesson.task.ifBlank { "Read the sections, then try the tool." }, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    if (!reveal) {
                        OutlinedButton(onClick = { reveal = true }) { Text("Reveal answer") }
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Green.copy(alpha = 0.08f))
                                .padding(12.dp)
                        ) {
                            Text("Answer", style = MaterialTheme.typography.labelSmall, color = Green)
                            Spacer(Modifier.height(4.dp))
                            Text(lesson.answer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        completed = !completed
                        repo.markCompleted(lesson.id, completed)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (completed) "Completed — tap to undo" else "Mark as complete")
                }
                if (lesson.tryTool != null) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { openTry(lesson.tryTool) },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (importing) "Preparing…" else "Try it in DexLab")
                    }
                }
                if (importing) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }

            item {
                Text(
                    "Static lessons only. If a lesson mentions a dynamic technique, it is for a device you own and control.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
