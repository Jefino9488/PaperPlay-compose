package io.max.paperplay.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import java.awt.Cursor
import java.awt.Rectangle

private val HEIGHT = 34.dp

/** The window's own title bar: icon, title, a draggable middle (double click maximises) and minimise, maximise and close. */
@Composable
internal fun WindowScope.AppTitleBar(
    title: String, background: Color, content: Color, maximized: Boolean,
    onMinimize: () -> Unit, onToggleMaximize: () -> Unit, onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(HEIGHT).background(background), verticalAlignment = Alignment.CenterVertically) {
        WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { onToggleMaximize() }) }.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource("icon.png"), null, Modifier.size(16.dp))
                BasicText(title, Modifier.padding(start = 10.dp), style = TextStyle(color = content, fontSize = 12.sp))
            }
        }
        CaptionButton(content, Color.White.copy(alpha = 0.12f), onMinimize) { c ->
            drawLine(c, Offset(size.width * 0.3f, size.height / 2), Offset(size.width * 0.7f, size.height / 2), 1.2.dp.toPx())
        }
        CaptionButton(content, Color.White.copy(alpha = 0.12f), onToggleMaximize) { c ->
            val s = size.minDimension * 0.4f
            val o = Offset((size.width - s) / 2, (size.height - s) / 2)
            if (maximized) {
                drawRect(c, o + Offset(s * 0.2f, 0f), Size(s * 0.8f, s * 0.8f), style = Stroke(1.2.dp.toPx()))
                drawRect(c, o + Offset(0f, s * 0.2f), Size(s * 0.8f, s * 0.8f), style = Stroke(1.2.dp.toPx()))
            } else drawRect(c, o, Size(s, s), style = Stroke(1.2.dp.toPx()))
        }
        CaptionButton(content, Color(0xFFE81123), onClose, hoverContent = Color.White) { c ->
            val s = size.minDimension * 0.2f
            val m = Offset(size.width / 2, size.height / 2)
            drawLine(c, m - Offset(s, s), m + Offset(s, s), 1.2.dp.toPx(), StrokeCap.Round)
            drawLine(c, m + Offset(-s, s), m + Offset(s, -s), 1.2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun CaptionButton(
    content: Color, hover: Color, onClick: () -> Unit, hoverContent: Color = content,
    draw: DrawScope.(Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(Modifier.width(46.dp).fillMaxHeight().background(if (hovered) hover else Color.Transparent).hoverable(source).clickable(onClick = onClick)) {
        Canvas(Modifier.fillMaxSize()) { draw(if (hovered) hoverContent else content) }
    }
}

/**
 * Undecorated windows have no resize border, so thin strips along the edges and corners resize by hand.
 * Does nothing while maximised or full screen.
 */
@Composable
internal fun WindowScope.ResizeEdges(enabled: Boolean) {
    if (!enabled) return
    val density = LocalDensity.current.density
    val t = 5.dp
    @Composable
    fun Edge(mod: Modifier, cursor: Int, left: Int, top: Int, right: Int, bottom: Int) {
        Box(
            mod.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor))).pointerInput(Unit) {
                var start: Rectangle? = null
                var total = Offset.Zero
                detectDragGestures(onDragStart = { start = window.bounds; total = Offset.Zero }) { change, drag ->
                    change.consume()
                    total += drag
                    val r = start ?: return@detectDragGestures
                    val dx = (total.x / density).toInt(); val dy = (total.y / density).toInt()
                    val minW = 480; val minH = 360
                    var x = r.x; var y = r.y; var w = r.width; var h = r.height
                    if (left != 0) { w = (r.width - dx).coerceAtLeast(minW); x = r.x + r.width - w }
                    if (right != 0) w = (r.width + dx).coerceAtLeast(minW)
                    if (top != 0) { h = (r.height - dy).coerceAtLeast(minH); y = r.y + r.height - h }
                    if (bottom != 0) h = (r.height + dy).coerceAtLeast(minH)
                    window.setBounds(x, y, w, h)
                }
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
        Edge(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(t), Cursor.W_RESIZE_CURSOR, 1, 0, 0, 0)
        Edge(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(t), Cursor.E_RESIZE_CURSOR, 0, 0, 1, 0)
        Edge(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(t), Cursor.N_RESIZE_CURSOR, 0, 1, 0, 0)
        Edge(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(t), Cursor.S_RESIZE_CURSOR, 0, 0, 0, 1)
        Edge(Modifier.align(Alignment.TopStart).size(10.dp), Cursor.NW_RESIZE_CURSOR, 1, 1, 0, 0)
        Edge(Modifier.align(Alignment.TopEnd).size(10.dp), Cursor.NE_RESIZE_CURSOR, 0, 1, 1, 0)
        Edge(Modifier.align(Alignment.BottomStart).size(10.dp), Cursor.SW_RESIZE_CURSOR, 1, 0, 0, 1)
        Edge(Modifier.align(Alignment.BottomEnd).size(10.dp), Cursor.SE_RESIZE_CURSOR, 0, 0, 1, 1)
    }
}
