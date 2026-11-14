package com.dexlab.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dexlab.app.native.CallEdge
import com.dexlab.app.native.CfgBlock
import com.dexlab.app.native.NativeCallGraph
import com.dexlab.app.native.NativeCfg
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CallGraphView(
    graph: NativeCallGraph,
    focusAddress: Long,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = graph.node(focusAddress)
    val callers = remember(graph, focusAddress) {
        graph.callers(focusAddress).distinctBy { it.fromFunctionAddress }.take(4)
    }
    val callees = remember(graph, focusAddress) {
        graph.callees(focusAddress).distinctBy { it.toAddress }.take(4)
    }
    val rows = maxOf(1, maxOf(callers.size, callees.size))
    val graphHeight = (rows * 54 + 14).dp
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GraphColumnLabel("CALLERS", Modifier.weight(1f), TextAlign.Start)
            GraphColumnLabel("FOCUS", Modifier.weight(1f), TextAlign.Center)
            GraphColumnLabel("CALLEES", Modifier.weight(1f), TextAlign.End)
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(graphHeight)) {
            val nodeWidth = ((maxWidth - 24.dp) / 3).coerceAtMost(112.dp)
            val gap = 7.dp
            val leftX = 0.dp
            val centerX = (maxWidth - nodeWidth) / 2
            val rightX = maxWidth - nodeWidth
            val focusY = (((rows - 1) * 54) / 2 + 7).dp

            val leftCenters = callers.mapIndexed { index, _ ->
                Offset(
                    with(density) { (leftX + nodeWidth).toPx() },
                    with(density) { (index * 54 + 7 + 23).dp.toPx() }
                )
            }
            val focusLeft = Offset(
                with(density) { centerX.toPx() },
                with(density) { focusY.toPx() + 23.dp.toPx() }
            )
            val focusRight = Offset(
                with(density) { (centerX + nodeWidth).toPx() },
                with(density) { focusY.toPx() + 23.dp.toPx() }
            )
            val rightCenters = callees.mapIndexed { index, _ ->
                Offset(
                    with(density) { rightX.toPx() },
                    with(density) { (index * 54 + 7 + 23).dp.toPx() }
                )
            }

            Canvas(Modifier.matchParentSize()) {
                callers.forEachIndexed { index, _ ->
                    drawArrow(leftCenters[index], focusLeft, Amber.copy(alpha = 0.70f))
                }
                callees.forEachIndexed { index, _ ->
                    drawArrow(focusRight, rightCenters[index], Teal.copy(alpha = 0.70f))
                }
            }

            callers.forEachIndexed { index, edge ->
                GraphNode(
                    name = edge.fromName,
                    address = edge.fromFunctionAddress,
                    color = Blue,
                    modifier = Modifier
                        .offset(x = leftX, y = (index * 54 + 7).dp)
                        .width(nodeWidth)
                        .height(46.dp)
                        .clickable { onOpen(edge.fromFunctionAddress) }
                )
            }
            GraphNode(
                name = focus.name,
                address = focusAddress,
                color = Amber,
                selected = true,
                modifier = Modifier
                    .offset(x = centerX, y = focusY)
                    .width(nodeWidth)
                    .height(46.dp)
            )
            callees.forEachIndexed { index, edge ->
                GraphNode(
                    name = edge.toName,
                    address = edge.toAddress,
                    color = Teal,
                    modifier = Modifier
                        .offset(x = rightX, y = (index * 54 + 7).dp)
                        .width(nodeWidth)
                        .height(46.dp)
                        .clickable { onOpen(edge.toAddress) }
                )
            }
        }
        val extra = (graph.callers(focusAddress).size - callers.size) +
            (graph.callees(focusAddress).size - callees.size)
        if (extra > 0) {
            Text(
                "$extra more direct reference${if (extra == 1) "" else "s"} not drawn.",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            "Tap a caller or callee to open it. Solid arrows are direct calls or tail jumps.",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun GraphColumnLabel(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = CodeFont,
            letterSpacing = 1.sp,
            fontSize = 9.sp
        ),
        color = TextDim,
        textAlign = align
    )
}

@Composable
private fun GraphNode(
    name: String,
    address: Long,
    color: Color,
    modifier: Modifier,
    selected: Boolean = false
) {
    Column(
        modifier
            .background(color.copy(alpha = if (selected) 0.16f else 0.08f), RoundedCornerShape(2.dp))
            .border(1.dp, color.copy(alpha = if (selected) 0.9f else 0.45f), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            name,
            fontFamily = CodeFont,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) color else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "0x${address.toString(16)}",
            fontFamily = CodeFont,
            fontSize = 8.sp,
            color = TextDim,
            maxLines = 1
        )
    }
}

@Composable
fun CfgGraphView(
    cfg: NativeCfg,
    onBlock: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val blocks = remember(cfg) { cfg.blocks.take(18) }
    if (blocks.isEmpty()) {
        EmptyGraph("No basic blocks", cfg.note)
        return
    }
    val levels = remember(blocks) { computeLevels(blocks) }
    val visible = remember(blocks, levels) { blocks.filter { levels[it.startAddress] != null }.take(18) }
    val maxLevel = visible.maxOfOrNull { levels[it.startAddress] ?: 0 } ?: 0
    val graphHeight = ((maxLevel + 1) * 78 + 8).dp
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val visibleStarts = remember(visible) { visible.map { it.startAddress }.toSet() }

    Column(modifier.fillMaxWidth()) {
        Text(
            "BLOCK GRAPH",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = CodeFont,
                letterSpacing = 1.sp,
                fontSize = 9.sp
            ),
            color = TextDim
        )
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(graphHeight)) {
            val gap = 8.dp
            val nodeWidth = ((maxWidth - 20.dp) / 3).coerceAtMost(108.dp)
            val perRow = ((maxWidth + gap) / (nodeWidth + gap)).toInt().coerceAtLeast(1)
            val groups = visible.groupBy { levels[it.startAddress] ?: 0 }
            val positions = HashMap<Long, Pair<Dp, Dp>>()
            groups.forEach { (level, group) ->
                group.take(perRow).forEachIndexed { index, block ->
                    val shown = minOf(group.size, perRow)
                    val rowWidth = nodeWidth * shown + gap * (shown - 1)
                    val startX = (maxWidth - rowWidth) / 2
                    positions[block.startAddress] = (startX + (nodeWidth + gap) * index) to (level * 78 + 7).dp
                }
            }
            val px = positions.mapValues { (_, pos) ->
                Offset(
                    with(density) { pos.first.toPx() + nodeWidth.toPx() / 2 },
                    with(density) { pos.second.toPx() }
                )
            }
            Canvas(Modifier.matchParentSize()) {
                visible.forEach { block ->
                    val from = positions[block.startAddress] ?: return@forEach
                    val fromX = with(density) { from.first.toPx() + nodeWidth.toPx() / 2 }
                    val fromY = with(density) { from.second.toPx() + 48.dp.toPx() }
                    block.successors.filter { it in visibleStarts }.forEach { successor ->
                        val to = positions[successor] ?: return@forEach
                        val toX = with(density) { to.first.toPx() + nodeWidth.toPx() / 2 }
                        val toY = with(density) { to.second.toPx() }
                        drawArrow(Offset(fromX, fromY), Offset(toX, toY), Teal.copy(alpha = 0.60f))
                    }
                }
            }
            visible.forEach { block ->
                val pos = positions[block.startAddress] ?: return@forEach
                val branch = block.successors.size > 1
                val color = when {
                    block.terminator.startsWith("ret") -> TextDim
                    branch -> Amber
                    else -> Blue
                }
                Column(
                    Modifier
                        .offset(x = pos.first, y = pos.second)
                        .width(nodeWidth)
                        .height(48.dp)
                        .background(color.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                        .border(1.dp, color.copy(alpha = 0.50f), RoundedCornerShape(2.dp))
                        .clickable { onBlock(block.startAddress) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        block.label,
                        fontFamily = CodeFont,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1
                    )
                    Text(
                        "${block.instructionCount} insn",
                        fontFamily = CodeFont,
                        fontSize = 8.sp,
                        color = TextDim,
                        maxLines = 1
                    )
                    Text(
                        block.terminator,
                        fontFamily = CodeFont,
                        fontSize = 8.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        val hidden = cfg.blocks.size - visible.size
        if (hidden > 0) {
            Text(
                "$hidden more blocks not drawn.",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            cfg.note,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun EmptyGraph(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun computeLevels(blocks: List<CfgBlock>): Map<Long, Int> {
    if (blocks.isEmpty()) return emptyMap()
    val starts = blocks.map { it.startAddress }.toSet()
    val levels = LinkedHashMap<Long, Int>()
    val queue = ArrayDeque<Long>()
    val first = blocks.first().startAddress
    levels[first] = 0
    queue.add(first)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val level = levels[current] ?: 0
        val block = blocks.firstOrNull { it.startAddress == current } ?: continue
        block.successors.filter { it in starts }.forEach { target ->
            if (!levels.containsKey(target)) {
                levels[target] = level + 1
                queue.add(target)
            }
        }
    }
    blocks.forEach { if (!levels.containsKey(it.startAddress)) levels[it.startAddress] = 0 }
    return levels
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color) {
    drawLine(color, from, to, strokeWidth = 1.4.dp.toPx())
    val angle = atan2(to.y - from.y, to.x - from.x)
    val size = 5.dp.toPx()
    val a = Path().apply {
        moveTo(to.x, to.y)
        lineTo(
            to.x - cos(angle - 0.55f) * size,
            to.y - sin(angle - 0.55f) * size
        )
        lineTo(
            to.x - cos(angle + 0.55f) * size,
            to.y - sin(angle + 0.55f) * size
        )
        close()
    }
    drawPath(a, color)
}

@Suppress("unused")
private fun graphMimeHint(): String = "text/plain"
