package com.ddremlin.stitchpad.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.ddremlin.stitchpad.core.ColorMath
import com.ddremlin.stitchpad.core.FlossUsage
import com.ddremlin.stitchpad.core.Pattern
import com.ddremlin.stitchpad.core.StitchKind
import java.io.OutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Экспорт схемы в PNG (цветная картинка-схема с легендой) и PDF (печатная схема по страницам). */
object Exporter {

    // ---------------- PNG ----------------

    fun writePng(p: Pattern, out: OutputStream) {
        val cell = max(6, min(28, 7000 / max(p.width, p.height)))
        val margin = cell * 2
        val chartW = p.width * cell
        val chartH = p.height * cell
        val rowH = 34
        val legendH = 60 + p.palette.size * rowH
        val bmpW = max(chartW + margin * 2, 900)
        val bmpH = chartH + margin * 2 + legendH
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        drawChart(c, p, margin.toFloat(), margin.toFloat(), cell.toFloat(), 0, 0, p.width, p.height, colored = true)
        drawLegend(c, p, margin.toFloat(), (chartH + margin * 2).toFloat(), rowH.toFloat(), bmpW - margin * 2f)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }

    // ---------------- PDF ----------------

    private const val PAGE_W = 595 // A4, точки
    private const val PAGE_H = 842
    private const val M = 36f
    private const val CELL = 10f

    fun writePdf(p: Pattern, out: OutputStream) {
        val doc = PdfDocument()
        var pageNo = 1
        fun newPage(): PdfDocument.Page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo++).create())

        // Страница 1: обложка — превью, размеры, легенда
        var page = newPage()
        var c = page.canvas
        val title = Paint().apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val text = Paint().apply { textSize = 10f; isAntiAlias = true }
        c.drawText(p.title, M, M + 14, title)
        val info = String.format(
            Locale.US, "%d × %d крестиков · цветов: %d · Aida 14: %.1f × %.1f см · Aida 16: %.1f × %.1f см · Aida 18: %.1f × %.1f см",
            p.width, p.height, p.palette.size,
            FlossUsage.sizeCm(p.width, 14), FlossUsage.sizeCm(p.height, 14),
            FlossUsage.sizeCm(p.width, 16), FlossUsage.sizeCm(p.height, 16),
            FlossUsage.sizeCm(p.width, 18), FlossUsage.sizeCm(p.height, 18),
        )
        c.drawText(info, M, M + 34, text)
        val prevMaxW = PAGE_W - 2 * M
        val prevMaxH = 300f
        val pc = min(prevMaxW / p.width, prevMaxH / p.height)
        drawChart(c, p, M, M + 50, pc, 0, 0, p.width, p.height, colored = true, symbols = false, grid = pc >= 3f)

        var y = M + 50 + p.height * pc + 24
        val rowH = 16f
        drawLegendHeader(c, M, y)
        y += rowH
        val usage = FlossUsage.calculate(p, 14, 2)
        for (row in usage) {
            if (y > PAGE_H - M) {
                doc.finishPage(page); page = newPage(); c = page.canvas; y = M + 10
                drawLegendHeader(c, M, y); y += rowH
            }
            drawLegendRow(c, row, M, y, rowH)
            y += rowH
        }
        doc.finishPage(page)

        // Страницы схемы
        val cols = ((PAGE_W - 2 * M - 16) / CELL).toInt()
        val rows = ((PAGE_H - 2 * M - 30) / CELL).toInt()
        val tilesX = (p.width + cols - 1) / cols
        val tilesY = (p.height + rows - 1) / rows
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            page = newPage(); c = page.canvas
            val x0 = tx * cols; val y0 = ty * rows
            val x1 = min(p.width, x0 + cols); val y1 = min(p.height, y0 + rows)
            c.drawText("${p.title} — лист ${ty * tilesX + tx + 1} из ${tilesX * tilesY} (столбцы ${x0 + 1}–$x1, ряды ${y0 + 1}–$y1)", M, M, text)
            drawChart(c, p, M + 16, M + 24, CELL, x0, y0, x1, y1, colored = false, labels = true)
            doc.finishPage(page)
        }

        doc.writeTo(out)
        doc.close()
    }

    // ---------------- Общая отрисовка ----------------

    /**
     * Рисует фрагмент схемы [x0,x1)×[y0,y1) с левым верхним углом (ox, oy).
     * colored=true — заливка цветом ниток, иначе бледная заливка и чёрные символы (для печати).
     */
    private fun drawChart(
        c: Canvas, p: Pattern, ox: Float, oy: Float, cell: Float,
        x0: Int, y0: Int, x1: Int, y1: Int,
        colored: Boolean, symbols: Boolean = true, grid: Boolean = true, labels: Boolean = false,
    ) {
        val fill = Paint().apply { style = Paint.Style.FILL }
        val sym = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; textSize = cell * 0.72f }
        val half = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeWidth = cell * 0.3f }
        val rect = RectF()
        for (y in y0 until y1) for (x in x0 until x1) {
            val ci = p.colorAt(x, y)
            if (ci < 0) continue
            val rgb = p.palette[ci].floss.rgb
            val l = ox + (x - x0) * cell; val t = oy + (y - y0) * cell
            rect.set(l, t, l + cell, t + cell)
            val kind = p.kinds[p.index(x, y)]
            val argb = if (colored) (0xFF shl 24) or rgb else (0x55 shl 24) or rgb
            if (kind == StitchKind.FULL) {
                fill.color = argb; c.drawRect(rect, fill)
            } else {
                half.color = argb
                for (sg in StitchKind.segments(kind)) {
                    c.drawLine(l + sg[0] * cell, t + sg[1] * cell, l + sg[2] * cell, t + sg[3] * cell, half)
                }
            }
            if (symbols && cell >= 6f) {
                sym.color = if (colored) (0xFF shl 24) or ColorMath.contrastOn(rgb) else Color.BLACK
                sym.textSize = if (kind == StitchKind.FULL) cell * 0.72f else cell * 0.5f
                val cy = rect.centerY() - (sym.ascent() + sym.descent()) / 2
                c.drawText(p.palette[ci].symbol.toString(), rect.centerX(), cy, sym)
            }
        }
        val w = (x1 - x0) * cell; val h = (y1 - y0) * cell
        if (grid) {
            val thin = Paint().apply { color = Color.rgb(190, 190, 190); strokeWidth = max(0.3f, cell * 0.04f) }
            val bold = Paint().apply { color = Color.rgb(60, 60, 60); strokeWidth = max(0.8f, cell * 0.1f) }
            val num = Paint().apply { isAntiAlias = true; textSize = 7f; color = Color.DKGRAY; textAlign = Paint.Align.CENTER }
            for (x in x0..x1) {
                val px = ox + (x - x0) * cell
                c.drawLine(px, oy, px, oy + h, if (x % 10 == 0 || x == x1) bold else thin)
                if (labels && x % 10 == 0 && x != x0) c.drawText(x.toString(), px, oy - 3, num)
            }
            num.textAlign = Paint.Align.RIGHT
            for (y in y0..y1) {
                val py = oy + (y - y0) * cell
                c.drawLine(ox, py, ox + w, py, if (y % 10 == 0 || y == y1) bold else thin)
                if (labels && y % 10 == 0 && y != y0) c.drawText(y.toString(), ox - 2, py + 3, num)
            }
        }
        // бэкстич
        if (p.backstitches.isNotEmpty()) {
            c.save()
            c.clipRect(ox, oy, ox + w, oy + h)
            val bs = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeWidth = max(1.2f, cell * 0.18f) }
            for (b in p.backstitches) {
                if (b.color !in p.palette.indices) continue
                bs.color = (0xFF shl 24) or p.palette[b.color].floss.rgb
                c.drawLine(ox + (b.x1 - x0) * cell, oy + (b.y1 - y0) * cell, ox + (b.x2 - x0) * cell, oy + (b.y2 - y0) * cell, bs)
            }
            c.restore()
        }
        // узелки и бисер
        if (p.knots.isNotEmpty()) {
            c.save()
            c.clipRect(ox - cell, oy - cell, ox + w + cell, oy + h + cell)
            val kp = Paint().apply { isAntiAlias = true }
            val ring = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = max(0.6f, cell * 0.06f); color = Color.BLACK }
            for (k in p.knots) {
                if (k.color !in p.palette.indices) continue
                if (k.hx < 2 * x0 || k.hx > 2 * x1 || k.hy < 2 * y0 || k.hy > 2 * y1) continue
                kp.color = (0xFF shl 24) or p.palette[k.color].floss.rgb
                val cx = ox + (k.hx / 2f - x0) * cell; val cy = oy + (k.hy / 2f - y0) * cell
                val r = if (k.bead) cell * 0.3f else cell * 0.24f
                c.drawCircle(cx, cy, r, kp)
                c.drawCircle(cx, cy, r, ring)
                if (k.bead) { kp.color = Color.WHITE; c.drawCircle(cx, cy, r * 0.35f, kp) }
            }
            c.restore()
        }
    }

    private fun drawLegend(c: Canvas, p: Pattern, ox: Float, oy: Float, rowH: Float, width: Float) {
        val head = Paint().apply { textSize = rowH * 0.6f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        c.drawText("${p.title}: ${p.width}×${p.height} крестиков, цветов ${p.palette.size}", ox, oy + rowH, head)
        val text = Paint().apply { textSize = rowH * 0.5f; isAntiAlias = true }
        val sym = Paint().apply { textSize = rowH * 0.55f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val fill = Paint()
        val usage = FlossUsage.calculate(p)
        var y = oy + rowH * 1.6f
        for (row in usage) {
            val rgb = row.entry.floss.rgb
            fill.color = (0xFF shl 24) or rgb
            c.drawRect(ox, y, ox + rowH, y + rowH * 0.85f, fill)
            sym.color = (0xFF shl 24) or ColorMath.contrastOn(rgb)
            c.drawText(row.entry.symbol.toString(), ox + rowH / 2, y + rowH * 0.62f, sym)
            val line = "${row.entry.floss.label}  ${row.entry.floss.name} — ${row.summary()}" +
                if (row.skeinsToBuy > 0) ", мотков: ${row.skeinsToBuy}" else ""
            c.drawText(line, ox + rowH * 1.4f, y + rowH * 0.62f, text)
            y += rowH
        }
    }

    private fun drawLegendHeader(c: Canvas, x: Float, y: Float) {
        val b = Paint().apply { textSize = 9f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        c.drawText("Симв.", x, y, b)
        c.drawText("Нитка", x + 40, y, b)
        c.drawText("Название", x + 110, y, b)
        c.drawText("Стежки", x + 260, y, b)
        c.drawText("Мотков (Aida 14, 2 сл.)", x + 430, y, b)
    }

    private fun drawLegendRow(c: Canvas, row: FlossUsage.Row, x: Float, y: Float, h: Float) {
        val t = Paint().apply { textSize = 9f; isAntiAlias = true }
        val fill = Paint().apply { color = (0xFF shl 24) or row.entry.floss.rgb }
        c.drawRect(x, y - h + 4, x + h - 2, y + 2, fill)
        val s = Paint().apply {
            textSize = 9f; isAntiAlias = true; textAlign = Paint.Align.CENTER
            color = (0xFF shl 24) or ColorMath.contrastOn(row.entry.floss.rgb)
        }
        c.drawText(row.entry.symbol.toString(), x + (h - 2) / 2, y - 1, s)
        c.drawText(row.entry.symbol.toString(), x + 24, y, t)
        c.drawText(row.entry.floss.label, x + 40, y, t)
        c.drawText(row.entry.floss.name.take(28), x + 110, y, t)
        c.drawText(row.summary().take(48), x + 260, y, t)
        if (row.skeinsToBuy > 0) c.drawText(String.format(Locale.US, "%.2f → %d", row.skeins, row.skeinsToBuy), x + 430, y, t)
    }
}
