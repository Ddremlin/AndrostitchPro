package com.ddremlin.stitchpad.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ddremlin.stitchpad.core.Clip
import com.ddremlin.stitchpad.core.ColorMath
import com.ddremlin.stitchpad.core.Pattern
import com.ddremlin.stitchpad.core.StitchKind
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Fabric = Color(0xFFFDFBF6)
private val Outside = Color(0xFFDAD6CC)
private val GridThin = Color(0x33000000)
private val GridBold = Color(0x99000000)
private val DoneOverlay = Color(0xB0808080)
private val SelectionColor = Color(0xFF1E88E5)

/** Масштаб, начиная с которого клетки рисуются по одной (с символами и частичными стежками). */
private const val DETAIL_SCALE = 10f
private const val SYMBOL_SCALE = 14f

@Composable
fun PatternCanvas(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val p = vm.pattern
    val rev = vm.revision
    var scale by remember { mutableFloatStateOf(10f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Вписать схему в экран при открытии/смене размера/по кнопке
    LaunchedEffect(vm.fitRequest, viewSize) {
        if (viewSize.width > 0 && viewSize.height > 0) {
            val s = min(viewSize.width * 0.95f / p.width, viewSize.height * 0.95f / p.height).coerceIn(1f, 80f)
            scale = s
            offset = Offset((viewSize.width - p.width * s) / 2f, (viewSize.height - p.height * s) / 2f)
        }
    }

    val highlight = if (vm.stitchMode && vm.highlightColor) vm.currentColor else -1
    val image: ImageBitmap = remember(p, rev, vm.stitchMode, highlight) { buildOverview(p, vm.stitchMode, highlight) }
    val symbolPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER } }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val panOnly = vm.tool == Tool.PAN
                    var multi = false
                    var drawing = !panOnly
                    if (drawing) {
                        vm.beginStroke((down.position.x - offset.x) / scale, (down.position.y - offset.y) / scale)
                    }
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            if (!multi) {
                                multi = true
                                if (drawing) { vm.cancelStroke(); drawing = false }
                            }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * zoom).coerceIn(1f, 120f)
                            val k = newScale / scale
                            offset = Offset(
                                centroid.x - (centroid.x - offset.x) * k + pan.x,
                                centroid.y - (centroid.y - offset.y) * k + pan.y,
                            )
                            scale = newScale
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            if (drawing) {
                                if (ch.pressed) vm.moveStroke((ch.position.x - offset.x) / scale, (ch.position.y - offset.y) / scale)
                            } else {
                                offset += ch.positionChange()
                            }
                            ch.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (drawing) vm.endStroke()
                }
            }
    ) {
        if (rev < 0) return@Canvas
        drawRect(Outside)
        val pw = p.width * scale
        val ph = p.height * scale
        drawRect(Fabric, topLeft = offset, size = Size(pw, ph))

        val x0 = floor(-offset.x / scale).toInt().coerceIn(0, p.width)
        val x1 = ceil((size.width - offset.x) / scale).toInt().coerceIn(0, p.width)
        val y0 = floor(-offset.y / scale).toInt().coerceIn(0, p.height)
        val y1 = ceil((size.height - offset.y) / scale).toInt().coerceIn(0, p.height)

        if (scale < DETAIL_SCALE) {
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(p.width, p.height),
                dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                dstSize = IntSize(pw.roundToInt(), ph.roundToInt()),
                filterQuality = FilterQuality.None,
            )
        } else {
            drawCells(p, vm.viewMode, x0, x1, y0, y1, scale, offset, symbolPaint, vm.stitchMode, highlight)
        }

        if (scale >= 5f) drawGrid(x0, x1, y0, y1, scale, offset)

        // бэкстич
        val bsWidth = max(2f, scale * 0.16f)
        for (b in p.backstitches) {
            if (b.color !in p.palette.indices) continue
            val faded = highlight >= 0 && b.color != highlight
            drawLine(
                Color(p.palette[b.color].floss.argb).copy(alpha = if (faded) 0.25f else 1f),
                Offset(offset.x + b.x1 * scale, offset.y + b.y1 * scale),
                Offset(offset.x + b.x2 * scale, offset.y + b.y2 * scale),
                strokeWidth = bsWidth, cap = StrokeCap.Round,
            )
        }
        // узелки и бисер
        val knotR = max(2.5f, scale * 0.22f)
        for (k in p.knots) {
            if (k.color !in p.palette.indices) continue
            val faded = highlight >= 0 && k.color != highlight
            val c = Color(p.palette[k.color].floss.argb).copy(alpha = if (faded) 0.25f else 1f)
            val center = Offset(offset.x + k.hx * scale / 2f, offset.y + k.hy * scale / 2f)
            if (k.bead) {
                drawCircle(c, radius = knotR * 1.15f, center = center)
                drawCircle(Color.White.copy(alpha = 0.8f), radius = knotR * 0.4f, center = center)
                drawCircle(Color.Black.copy(alpha = 0.6f), radius = knotR * 1.15f, center = center, style = Stroke(1.5f))
            } else {
                drawCircle(c, radius = knotR, center = center)
                drawCircle(Color.Black.copy(alpha = 0.7f), radius = knotR, center = center, style = Stroke(1.5f))
            }
        }
        vm.backstitchPreview?.let { b ->
            val col = if (b.color in p.palette.indices) Color(p.palette[b.color].floss.argb) else Color.Red
            val a = Offset(offset.x + b.x1 * scale, offset.y + b.y1 * scale)
            val e = Offset(offset.x + b.x2 * scale, offset.y + b.y2 * scale)
            drawLine(col.copy(alpha = 0.7f), a, e, strokeWidth = bsWidth, cap = StrokeCap.Round)
            drawCircle(Color.Black, radius = max(3f, scale * 0.12f), center = a)
            drawCircle(Color.Black, radius = max(3f, scale * 0.12f), center = e)
        }

        drawRect(Color.Black, topLeft = offset, size = Size(pw, ph), style = Stroke(width = 2f))

        // выделение
        vm.selection?.let { r ->
            val tl = Offset(offset.x + r.x0 * scale, offset.y + r.y0 * scale)
            val sz = Size(r.width * scale, r.height * scale)
            drawRect(SelectionColor.copy(alpha = 0.12f), tl, sz)
            drawRect(SelectionColor, tl, sz, style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        }
        // плавающий фрагмент
        vm.floating?.let { f -> drawFloating(f.clip, f.x, f.y, scale, offset) }
    }
}

private fun DrawScope.drawStitch(kind: Byte, color: Color, l: Float, t: Float, scale: Float) {
    if (kind == StitchKind.FULL) {
        drawRect(color, Offset(l, t), Size(scale, scale))
        return
    }
    val w = scale * 0.3f
    for (s in StitchKind.segments(kind)) {
        drawLine(color, Offset(l + s[0] * scale, t + s[1] * scale), Offset(l + s[2] * scale, t + s[3] * scale), w, StrokeCap.Round)
    }
}

private fun DrawScope.drawCells(
    p: Pattern, mode: ViewMode, x0: Int, x1: Int, y0: Int, y1: Int,
    scale: Float, offset: Offset, paint: Paint, stitchMode: Boolean, highlight: Int,
) {
    val showSymbols = mode != ViewMode.COLOR && scale >= SYMBOL_SCALE
    val showColor = mode != ViewMode.SYMBOL || scale < SYMBOL_SCALE
    val canvas = drawContext.canvas.nativeCanvas
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val i = p.index(x, y)
            val ci = p.cells[i]
            if (ci < 0) continue
            val entry = p.palette[ci]
            val l = offset.x + x * scale
            val t = offset.y + y * scale
            val kind = p.kinds[i]
            val faded = highlight >= 0 && ci != highlight
            val alpha = if (faded) 0.2f else 1f
            val color = Color(entry.floss.argb).copy(alpha = alpha)
            if (showColor) {
                drawStitch(kind, color, l, t, scale)
            } else if (kind != StitchKind.FULL) {
                drawStitch(kind, color.copy(alpha = 0.35f * alpha), l, t, scale)
            }
            val isDone = stitchMode && p.done[i]
            if (showSymbols && !isDone) {
                val onColor = showColor && kind == StitchKind.FULL
                val base = if (onColor) ColorMath.contrastOn(entry.floss.rgb) else 0x000000
                paint.color = ((255 * alpha).toInt() shl 24) or base
                paint.textSize = if (kind == StitchKind.FULL) scale * 0.7f else scale * 0.42f
                val cx = l + scale / 2
                val cy = t + scale / 2 - (paint.ascent() + paint.descent()) / 2
                canvas.drawText(entry.symbol.toString(), cx, cy, paint)
            }
            if (isDone) {
                drawRect(DoneOverlay, Offset(l, t), Size(scale, scale))
                drawLine(Color.White, Offset(l + scale * 0.22f, t + scale * 0.52f), Offset(l + scale * 0.42f, t + scale * 0.72f), scale * 0.1f, StrokeCap.Round)
                drawLine(Color.White, Offset(l + scale * 0.42f, t + scale * 0.72f), Offset(l + scale * 0.78f, t + scale * 0.3f), scale * 0.1f, StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.drawFloating(clip: Clip, fx: Int, fy: Int, scale: Float, offset: Offset) {
    for (y in 0 until clip.height) for (x in 0 until clip.width) {
        val c = clip.cells[y * clip.width + x]
        if (c < 0) continue
        val l = offset.x + (fx + x) * scale
        val t = offset.y + (fy + y) * scale
        drawStitch(clip.kinds[y * clip.width + x], Color(clip.flosses[c].argb).copy(alpha = 0.85f), l, t, scale)
    }
    val bw = max(2f, scale * 0.16f)
    for (b in clip.backstitches) {
        drawLine(
            Color(clip.flosses[b.color].argb),
            Offset(offset.x + (fx + b.x1) * scale, offset.y + (fy + b.y1) * scale),
            Offset(offset.x + (fx + b.x2) * scale, offset.y + (fy + b.y2) * scale),
            bw, StrokeCap.Round,
        )
    }
    for (k in clip.knots) {
        drawCircle(
            Color(clip.flosses[k.color].argb), radius = max(2.5f, scale * 0.22f),
            center = Offset(offset.x + (2 * fx + k.hx) * scale / 2f, offset.y + (2 * fy + k.hy) * scale / 2f),
        )
    }
    val tl = Offset(offset.x + fx * scale, offset.y + fy * scale)
    val sz = Size(clip.width * scale, clip.height * scale)
    drawRect(Color(0xFFE53935), tl, sz, style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
}

private fun DrawScope.drawGrid(x0: Int, x1: Int, y0: Int, y1: Int, scale: Float, offset: Offset) {
    val top = offset.y + y0 * scale
    val bottom = offset.y + y1 * scale
    val left = offset.x + x0 * scale
    val right = offset.x + x1 * scale
    val thinVisible = scale >= 8f
    for (x in x0..x1) {
        val bold = x % 10 == 0
        if (!bold && !thinVisible) continue
        val px = offset.x + x * scale
        drawLine(if (bold) GridBold else GridThin, Offset(px, top), Offset(px, bottom), if (bold) 2f else 1f)
    }
    for (y in y0..y1) {
        val bold = y % 10 == 0
        if (!bold && !thinVisible) continue
        val py = offset.y + y * scale
        drawLine(if (bold) GridBold else GridThin, Offset(left, py), Offset(right, py), if (bold) 2f else 1f)
    }
}

/** Картинка «пиксель = клетка» для быстрого показа при мелком масштабе. */
private fun buildOverview(p: Pattern, stitchMode: Boolean, highlight: Int): ImageBitmap {
    val px = IntArray(p.width * p.height)
    for (i in px.indices) {
        val ci = p.cells[i]
        if (ci < 0) { px[i] = 0; continue }
        var rgb = p.palette[ci].floss.rgb
        var a = if (p.kinds[i] == StitchKind.FULL) 0xFF else 0x90
        if (highlight >= 0 && ci != highlight) a = 0x30
        if (stitchMode && p.done[i]) { rgb = 0x808080; a = 0xFF }
        px[i] = (a shl 24) or rgb
    }
    val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, p.width, 0, 0, p.width, p.height)
    return bmp.asImageBitmap()
}
