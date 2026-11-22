package com.ddremlin.stitchpad.core

/**
 * Превращает картинку (уже уменьшенную до размера схемы, по пикселю на клетку)
 * в схему: квантование median cut -> подбор ближайших ниток -> раскладка клеток.
 */
object PhotoConverter {

    data class Options(
        val maxColors: Int = 20,
        /** Ошибочное диффузное размытие Флойда–Стейнберга: плавнее переходы, но больше «шума». */
        val dither: Boolean = false,
        /** Пиксели с альфой ниже порога остаются пустыми (фон без вышивки). */
        val alphaThreshold: Int = 128,
        /** Считать почти белый фон пустым. */
        val skipWhiteBackground: Boolean = false,
    )

    /** pixels — ARGB, размер w*h. */
    fun convert(pixels: IntArray, w: Int, h: Int, catalog: FlossCatalog, opt: Options): Pattern {
        require(pixels.size == w * h)
        val opaque = BooleanArray(pixels.size) { i ->
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val isWhite = opt.skipWhiteBackground && ((p shr 16) and 0xFF) > 245 && ((p shr 8) and 0xFF) > 245 && (p and 0xFF) > 245
            a >= opt.alphaThreshold && !isWhite
        }

        // 1. Квантование до maxColors цветов
        val sample = ArrayList<Int>()
        val step = maxOf(1, pixels.size / 60000)
        var i = 0
        while (i < pixels.size) { if (opaque[i]) sample.add(pixels[i] and 0xFFFFFF); i += step }
        val pattern = Pattern(w, h)
        if (sample.isEmpty()) return pattern

        val centers = medianCut(sample.toIntArray(), opt.maxColors.coerceIn(1, 120))

        // 2. Ближайшие нитки (без повторов)
        val flosses = LinkedHashMap<String, Floss>()
        for (c in centers) {
            val t = catalog.nearest(c)
            flosses[t.code] = t
        }
        flosses.values.forEach { pattern.addFloss(it) }
        val palLabs = pattern.palette.map { ColorMath.rgbToLab(it.floss.rgb) }

        // 3. Раскладка пикселей по палитре
        if (!opt.dither) {
            val cache = HashMap<Int, Int>()
            for (idx in pixels.indices) {
                if (!opaque[idx]) continue
                val rgb = pixels[idx] and 0xFFFFFF
                pattern.cells[idx] = cache.getOrPut(rgb) { nearestIndex(ColorMath.rgbToLab(rgb), palLabs) }
            }
        } else {
            val r = FloatArray(pixels.size); val g = FloatArray(pixels.size); val b = FloatArray(pixels.size)
            for (idx in pixels.indices) {
                r[idx] = ((pixels[idx] shr 16) and 0xFF).toFloat()
                g[idx] = ((pixels[idx] shr 8) and 0xFF).toFloat()
                b[idx] = (pixels[idx] and 0xFF).toFloat()
            }
            for (y in 0 until h) for (x in 0 until w) {
                val idx = y * w + x
                if (!opaque[idx]) continue
                val cr = r[idx].coerceIn(0f, 255f).toInt(); val cg = g[idx].coerceIn(0f, 255f).toInt(); val cb = b[idx].coerceIn(0f, 255f).toInt()
                val ci = nearestIndex(ColorMath.rgbToLab((cr shl 16) or (cg shl 8) or cb), palLabs)
                pattern.cells[idx] = ci
                val prgb = pattern.palette[ci].floss.rgb
                val er = cr - ((prgb shr 16) and 0xFF); val eg = cg - ((prgb shr 8) and 0xFF); val eb = cb - (prgb and 0xFF)
                fun spread(nx: Int, ny: Int, k: Float) {
                    if (nx in 0 until w && ny in 0 until h) {
                        val n = ny * w + nx
                        r[n] += er * k; g[n] += eg * k; b[n] += eb * k
                    }
                }
                spread(x + 1, y, 7f / 16); spread(x - 1, y + 1, 3f / 16); spread(x, y + 1, 5f / 16); spread(x + 1, y + 1, 1f / 16)
            }
        }
        return pattern.removeUnusedColors()
    }

    private fun nearestIndex(lab: Lab, pal: List<Lab>): Int {
        var best = 0; var bestD = Double.MAX_VALUE
        for (k in pal.indices) { val d = pal[k].dist2(lab); if (d < bestD) { bestD = d; best = k } }
        return best
    }

    /** Классический median cut. Возвращает до n средних цветов 0xRRGGBB. */
    fun medianCut(colors: IntArray, n: Int): List<Int> {
        val boxes = ArrayList<IntArray>()
        boxes.add(colors)
        while (boxes.size < n) {
            // выбираем коробку с наибольшим диапазоном по какому-либо каналу
            var bestBox = -1; var bestRange = 0; var bestCh = 0
            for ((bi, box) in boxes.withIndex()) {
                if (box.size < 2) continue
                for (ch in 0..2) {
                    val shift = 16 - ch * 8
                    var mn = 255; var mx = 0
                    for (c in box) { val v = (c shr shift) and 0xFF; if (v < mn) mn = v; if (v > mx) mx = v }
                    if (mx - mn > bestRange) { bestRange = mx - mn; bestBox = bi; bestCh = ch }
                }
            }
            if (bestBox < 0 || bestRange == 0) break
            val box = boxes.removeAt(bestBox)
            val shift = 16 - bestCh * 8
            val sorted = box.sortedBy { (it shr shift) and 0xFF }.toIntArray()
            val mid = sorted.size / 2
            boxes.add(sorted.copyOfRange(0, mid))
            boxes.add(sorted.copyOfRange(mid, sorted.size))
        }
        return boxes.filter { it.isNotEmpty() }.map { box ->
            var r = 0L; var g = 0L; var b = 0L
            for (c in box) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
            val s = box.size
            (((r / s).toInt()) shl 16) or (((g / s).toInt()) shl 8) or (b / s).toInt()
        }
    }
}
