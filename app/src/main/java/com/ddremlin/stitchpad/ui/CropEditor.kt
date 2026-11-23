package com.ddremlin.stitchpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Пропорции рамки обрезки (ширина / высота в пикселях) или null — свободно. */
enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Свободно", null),
    SQUARE("1:1", 1f),
    PORTRAIT("3:4", 3f / 4f),
    LANDSCAPE("4:3", 4f / 3f),
}

private enum class DragMode { NONE, NEW, MOVE, TL, TR, BL, BR, LEFT, TOP, RIGHT, BOTTOM }

private const val MIN_SIZE = 0.04f

/** Самая большая рамка нужных пропорций по центру картинки (в долях 0..1). */
fun defaultCrop(aspect: Float?, imgW: Int, imgH: Int): Rect {
    if (aspect == null) return Rect(0f, 0f, 1f, 1f)
    val imgRatio = imgW.toFloat() / imgH
    var w = 1f
    var h = imgRatio / aspect
    if (h > 1f) { h = 1f; w = aspect / imgRatio }
    val l = (1f - w) / 2f; val t = (1f - h) / 2f
    return Rect(l, t, l + w, t + h)
}

/**
 * Окно обрезки. Жесты:
 *  - провести пальцем по фото — выделить новую область;
 *  - тянуть за уголки (и стороны в свободном режиме) — изменить размер;
 *  - тянуть внутри рамки — переместить.
 * crop — в долях картинки (0..1).
 */
@Composable
fun CropEditor(
    image: ImageBitmap,
    crop: Rect,
    aspect: Float?,
    onCropChange: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imgRatio = image.width.toFloat() / image.height
    val currentCrop by rememberUpdatedState(crop)
    val currentAspect by rememberUpdatedState(aspect)
    val onChange by rememberUpdatedState(onCropChange)

    Box(modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(imgRatio, matchHeightConstraintsFirst = imgRatio < 1f)) {
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
            Canvas(
                Modifier
                    .matchParentSize()
                    .pointerInput(imgRatio) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            fun norm(o: Offset) = Offset((o.x / w).coerceIn(0f, 1f), (o.y / h).coerceIn(0f, 1f))
                            val start = norm(down.position)
                            var r = currentCrop
                            val mode = pickMode(r, start, 32.dp.toPx() / w, 32.dp.toPx() / h, currentAspect != null)
                            down.consume()
                            var last = start
                            while (true) {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val p = norm(ch.position)
                                val dx = p.x - last.x
                                val dy = p.y - last.y
                                last = p
                                ch.consume()
                                if (dx == 0f && dy == 0f) continue
                                r = if (mode == DragMode.NEW) newSelection(start, p, currentAspect, imgRatio)
                                else adjust(r, mode, dx, dy, currentAspect, imgRatio)
                                onChange(r)
                            }
                        }
                    }
            ) {
                val l = crop.left * size.width; val t = crop.top * size.height
                val rr = crop.right * size.width; val b = crop.bottom * size.height
                val dim = Color(0x99000000)
                // затемнение вне рамки
                drawRect(dim, Offset.Zero, Size(size.width, t))
                drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                drawRect(dim, Offset(0f, t), Size(l, b - t))
                drawRect(dim, Offset(rr, t), Size(size.width - rr, b - t))
                // сетка третей
                val thin = Color(0x88FFFFFF)
                for (k in 1..2) {
                    val x = l + (rr - l) * k / 3f; val y = t + (b - t) * k / 3f
                    drawLine(thin, Offset(x, t), Offset(x, b), 1f)
                    drawLine(thin, Offset(l, y), Offset(rr, y), 1f)
                }
                drawRect(Color.White, Offset(l, t), Size(rr - l, b - t), style = Stroke(width = 2.dp.toPx()))
                // уголки
                val len = 18.dp.toPx(); val sw = 4.dp.toPx()
                fun corner(x: Float, y: Float, sx: Float, sy: Float) {
                    drawLine(Color.White, Offset(x, y), Offset(x + len * sx, y), sw)
                    drawLine(Color.White, Offset(x, y), Offset(x, y + len * sy), sw)
                }
                corner(l, t, 1f, 1f); corner(rr, t, -1f, 1f); corner(l, b, 1f, -1f); corner(rr, b, -1f, -1f)
            }
        }
    }
}

private fun pickMode(r: Rect, p: Offset, tolX: Float, tolY: Float, locked: Boolean): DragMode {
    fun nearX(x: Float) = abs(p.x - x) < tolX
    fun nearY(y: Float) = abs(p.y - y) < tolY
    val inY = p.y in (r.top - tolY)..(r.bottom + tolY)
    val inX = p.x in (r.left - tolX)..(r.right + tolX)
    return when {
        nearX(r.left) && nearY(r.top) -> DragMode.TL
        nearX(r.right) && nearY(r.top) -> DragMode.TR
        nearX(r.left) && nearY(r.bottom) -> DragMode.BL
        nearX(r.right) && nearY(r.bottom) -> DragMode.BR
        !locked && nearX(r.left) && inY -> DragMode.LEFT
        !locked && nearX(r.right) && inY -> DragMode.RIGHT
        !locked && nearY(r.top) && inX -> DragMode.TOP
        !locked && nearY(r.bottom) && inX -> DragMode.BOTTOM
        // рамка на всё фото двигаться не может — значит, пользователь выделяет новую область
        r.width > 0.97f && r.height > 0.97f -> DragMode.NEW
        p.x in r.left..r.right && p.y in r.top..r.bottom -> DragMode.MOVE
        else -> DragMode.NEW
    }
}

/** Новая рамка от точки s до точки p (с учётом пропорций). */
private fun newSelection(s: Offset, p: Offset, aspect: Float?, imgRatio: Float): Rect {
    if (aspect == null) return Rect(min(s.x, p.x), min(s.y, p.y), max(s.x, p.x), max(s.y, p.y))
    val sx = if (p.x >= s.x) 1f else -1f
    val sy = if (p.y >= s.y) 1f else -1f
    var w = abs(p.x - s.x)
    var h = w * imgRatio / aspect
    val hy = abs(p.y - s.y)
    if (hy > h) { h = hy; w = h * aspect / imgRatio }
    val maxW = if (sx > 0) 1f - s.x else s.x
    val maxH = if (sy > 0) 1f - s.y else s.y
    if (w > maxW) { w = maxW; h = w * imgRatio / aspect }
    if (h > maxH) { h = maxH; w = h * aspect / imgRatio }
    val x2 = s.x + sx * w; val y2 = s.y + sy * h
    return Rect(min(s.x, x2), min(s.y, y2), max(s.x, x2), max(s.y, y2))
}

private fun adjust(r: Rect, mode: DragMode, dx: Float, dy: Float, aspect: Float?, imgRatio: Float): Rect {
    when (mode) {
        DragMode.NONE, DragMode.NEW -> return r
        DragMode.MOVE -> {
            val nx = (r.left + dx).coerceIn(0f, 1f - r.width)
            val ny = (r.top + dy).coerceIn(0f, 1f - r.height)
            return Rect(nx, ny, nx + r.width, ny + r.height)
        }
        DragMode.LEFT -> return Rect((r.left + dx).coerceIn(0f, r.right - MIN_SIZE), r.top, r.right, r.bottom)
        DragMode.RIGHT -> return Rect(r.left, r.top, (r.right + dx).coerceIn(r.left + MIN_SIZE, 1f), r.bottom)
        DragMode.TOP -> return Rect(r.left, (r.top + dy).coerceIn(0f, r.bottom - MIN_SIZE), r.right, r.bottom)
        DragMode.BOTTOM -> return Rect(r.left, r.top, r.right, (r.bottom + dy).coerceIn(r.top + MIN_SIZE, 1f))
        else -> {}
    }
    // углы
    val moveLeft = mode == DragMode.TL || mode == DragMode.BL
    val moveTop = mode == DragMode.TL || mode == DragMode.TR
    var l = if (moveLeft) (r.left + dx).coerceIn(0f, r.right - MIN_SIZE) else r.left
    var rr = if (moveLeft) r.right else (r.right + dx).coerceIn(r.left + MIN_SIZE, 1f)
    var t = if (moveTop) (r.top + dy).coerceIn(0f, r.bottom - MIN_SIZE) else r.top
    var b = if (moveTop) r.bottom else (r.bottom + dy).coerceIn(r.top + MIN_SIZE, 1f)

    if (aspect != null) {
        // высота в долях для заданной ширины: h = w * imgRatio / aspect
        var w = rr - l
        var h = w * imgRatio / aspect
        val maxH = if (moveTop) b else 1f - t
        if (h > maxH) { h = maxH; w = h * aspect / imgRatio }
        if (moveLeft) l = rr - w else rr = l + w
        if (moveTop) t = b - h else b = t + h
    }
    return Rect(l, t, rr, b)
}
