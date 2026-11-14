package com.dexlab.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim

enum class CodeLanguage { PLAIN, C, JAVA, SMALI }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .width(3.dp)
                            .height(14.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title.uppercase(),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = CodeFont,
                                letterSpacing = 0.9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun KeyValue(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(116.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont) else MaterialTheme.typography.bodySmall,
            color = valueColor
        )
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            .padding(11.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BenchButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp)
    ) { Text(text) }
}

@Composable
fun BenchOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp)
    ) { Text(text) }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text.uppercase(),
        modifier = Modifier
            .clip(RoundedCornerShape(1.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.34f), RoundedCornerShape(1.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
        color = color,
        maxLines = 1
    )
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        shape = RoundedCornerShape(2.dp),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}

@Composable
fun OptionChips(
    options: List<String>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option, style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, title: String? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF1A160F))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title ?: "code",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.weight(1f)
            )
            CopyTextButton(code)
        }
        Text(
            code,
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .horizontalScroll(rememberScrollState()),
            fontFamily = CodeFont,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Color(0xFFD6E2F0)
        )
    }
}

@Composable
fun CodeViewer(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 12,
    wrap: Boolean = false,
    query: String = "",
    maxLines: Int = 6000,
    language: CodeLanguage = CodeLanguage.PLAIN
) {
    val lines = remember(text, maxLines) {
        text.replace("\t", "    ").lineSequence().take(maxLines).toList()
    }
    val total = remember(text) { text.count { it == '\n' } + 1 }
    val hScroll = rememberScrollState()
    Column(modifier.fillMaxWidth()) {
        if (total > maxLines) {
            Text(
                "Showing the first $maxLines of $total lines.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1A160F))
                .padding(vertical = 8.dp)
        ) {
            Column(Modifier.width(48.dp).padding(end = 8.dp)) {
                lines.indices.forEach { i ->
                    Text(
                        "${i + 1}",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                        fontFamily = CodeFont,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 5).sp,
                        color = Color(0xFF4D5C6E)
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))
            ) {
                lines.forEach { line ->
                    Text(
                        highlight(line, query, language),
                        fontFamily = CodeFont,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 5).sp,
                        softWrap = wrap,
                        maxLines = if (wrap) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Visible,
                        color = Color(0xFFD6E2F0)
                    )
                }
            }
        }
    }
}

private fun highlight(line: String, query: String, language: CodeLanguage): AnnotatedString {
    if (query.isBlank()) return syntaxHighlight(line, language)
    val lower = line.lowercase()
    val q = query.lowercase()
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val idx = lower.indexOf(q, start)
            if (idx < 0) {
                append(line.substring(start))
                break
            }
            append(line.substring(start, idx))
            withStyle(SpanStyle(background = Color(0x66F2A33A), color = Color(0xFFFFF3DC))) {
                append(line.substring(idx, idx + q.length))
            }
            start = idx + q.length
        }
    }
}

private val C_KEYWORDS = setOf(
    "if", "else", "for", "while", "do", "return", "goto", "switch", "case", "break", "continue",
    "default", "sizeof", "struct", "union", "enum", "typedef", "static", "const", "volatile",
    "extern", "inline", "void", "char", "short", "int", "long", "float", "double", "unsigned",
    "signed", "true", "false", "null", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "int8_t",
    "int16_t", "int32_t", "int64_t", "uintptr_t", "size_t"
)

private val JAVA_KEYWORDS = setOf(
    "package", "import", "class", "interface", "extends", "implements", "public", "private",
    "protected", "static", "final", "abstract", "native", "synchronized", "try", "catch",
    "finally", "throw", "throws", "new", "return", "if", "else", "for", "while", "do",
    "switch", "case", "break", "continue", "default", "void", "boolean", "byte", "short",
    "int", "long", "float", "double", "char", "true", "false", "null", "this", "super"
)

private val SMALI_WORDS = setOf(
    "invoke-virtual", "invoke-super", "invoke-direct", "invoke-static", "invoke-interface",
    "invoke-virtual/range", "invoke-super/range", "invoke-direct/range", "invoke-static/range",
    "invoke-interface/range", "const-string", "const-string/jumbo", "const-class", "const/4",
    "const/16", "const", "const-wide", "move-result", "move-result-object", "move-result-wide",
    "move-exception", "move-object", "move", "return-void", "return-object", "return-wide",
    "return", "new-instance", "new-array", "filled-new-array", "check-cast", "instance-of",
    "array-length", "iget", "iget-object", "iget-wide", "iput", "iput-object", "iput-wide",
    "sget", "sget-object", "sput", "sput-object", "if-eq", "if-ne", "if-lt", "if-ge", "if-gt",
    "if-le", "if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez", "goto", "goto/16",
    "goto/32", "packed-switch", "sparse-switch", "throw", "monitor-enter", "monitor-exit"
)

private fun syntaxHighlight(line: String, language: CodeLanguage): AnnotatedString {
    if (language == CodeLanguage.PLAIN) return AnnotatedString(line)
    val commentAt = if (language == CodeLanguage.SMALI) line.indexOf('#') else line.indexOf("//")
    val code = if (commentAt >= 0) line.substring(0, commentAt) else line
    val comment = if (commentAt >= 0) line.substring(commentAt) else ""
    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            if (ch == '"') {
                var end = i + 1
                while (end < code.length) {
                    if (code[end] == '\\' && end + 1 < code.length) {
                        end += 2
                        continue
                    }
                    if (code[end] == '"') {
                        end++
                        break
                    }
                    end++
                }
                withStyle(SpanStyle(color = Color(0xFFD8B47A))) { append(code.substring(i, end)) }
                i = end
                continue
            }
            if (ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '$' || ch == '-') {
                var end = i + 1
                while (end < code.length) {
                    val n = code[end]
                    if (n.isLetterOrDigit() || n == '_' || n == '.' || n == '$' || n == '-') end++ else break
                }
                val value = code.substring(i, end)
                val next = code.getOrNull(end)
                val style = when {
                    value.startsWith("0x") || value.firstOrNull()?.isDigit() == true -> SpanStyle(color = Color(0xFFC7A9C7))
                    language == CodeLanguage.SMALI && value.startsWith(".") -> SpanStyle(color = Green)
                    language == CodeLanguage.SMALI && value.matches(Regex("[vp]\\d+")) -> SpanStyle(color = Teal)
                    language == CodeLanguage.SMALI && value in SMALI_WORDS -> SpanStyle(color = Blue)
                    language == CodeLanguage.C && value in C_KEYWORDS -> SpanStyle(color = Amber)
                    language == CodeLanguage.JAVA && value in JAVA_KEYWORDS -> SpanStyle(color = Amber)
                    language == CodeLanguage.C && value in setOf("uint8_t", "uint16_t", "uint32_t", "uint64_t", "uintptr_t", "void") -> SpanStyle(color = Blue)
                    language == CodeLanguage.JAVA && value in setOf("String", "Object", "Integer", "Long", "Boolean") -> SpanStyle(color = Blue)
                    next == '(' -> SpanStyle(color = Teal)
                    else -> null
                }
                if (style != null) withStyle(style) { append(value) } else append(value)
                i = end
                continue
            }
            append(ch)
            i++
        }
        if (comment.isNotEmpty()) {
            withStyle(
                SpanStyle(color = Color(0xFF7F8C75), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            ) { append(comment) }
        }
    }
}

@Composable
fun CopyTextButton(text: String, label: String = "Copy") {
    val context = LocalContext.current
    TextButton(onClick = { copy(context, text) }) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DexLab", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
fun EmptyState(title: String, body: String, action: @Composable (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
fun LoadingPane(label: String) {
    Column(
        Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorPane(message: String, onRetry: (() -> Unit)? = null) {
    SectionCard {
        Text("Something went wrong", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(3.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
fun LevelBadge(level: Char) {
    val color = when (level) {
        'V' -> TextDim
        'D' -> Blue
        'I' -> Green
        'W' -> Amber
        'E', 'F', 'A' -> Red
        else -> TextDim
    }
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(level.toString(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = color)
    }
}

@Composable
fun ThinDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
}
