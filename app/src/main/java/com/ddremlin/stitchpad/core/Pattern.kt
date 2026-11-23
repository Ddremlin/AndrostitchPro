package com.ddremlin.stitchpad.core

/**
 * Тип стежка в клетке.
 * Углы клетки нумеруются по часовой стрелке: 0 — левый верхний, 1 — правый верхний,
 * 2 — правый нижний, 3 — левый нижний.
 */
object StitchKind {
    const val FULL: Byte = 0       // полный крест
    const val HALF_FWD: Byte = 1   // полукрест "/"
    const val HALF_BACK: Byte = 2  // полукрест "\"
    const val QUARTER: Byte = 3    // 3..6 — четверть креста от угла к центру
    const val THREE_Q: Byte = 7    // 7..10 — три четверти: полукрест + четверть к углу

    fun quarter(corner: Int): Byte = (QUARTER + corner).toByte()
    fun threeQuarter(corner: Int): Byte = (THREE_Q + corner).toByte()
    fun isQuarter(k: Byte) = k in QUARTER until THREE_Q
    fun isThreeQuarter(k: Byte) = k in THREE_Q until (THREE_Q + 4)
    fun corner(k: Byte): Int = when {
        isQuarter(k) -> k - QUARTER
        isThreeQuarter(k) -> k - THREE_Q
        else -> -1
    }

    /** Отрезки стежка в координатах клетки 0..1: [x1, y1, x2, y2]. Для FULL — пусто (заливка). */
    fun segments(k: Byte): List<FloatArray> = when {
        k == HALF_FWD -> listOf(floatArrayOf(0f, 1f, 1f, 0f))
        k == HALF_BACK -> listOf(floatArrayOf(0f, 0f, 1f, 1f))
        isQuarter(k) -> listOf(toCenter(corner(k)))
        isThreeQuarter(k) -> {
            val c = corner(k)
            val diag = if (c == 0 || c == 2) floatArrayOf(0f, 1f, 1f, 0f) else floatArrayOf(0f, 0f, 1f, 1f)
            listOf(diag, toCenter(c))
        }
        else -> emptyList()
    }

    private fun toCenter(c: Int): FloatArray {
        val x = if (c == 1 || c == 2) 1f else 0f
        val y = if (c >= 2) 1f else 0f
        return floatArrayOf(x, y, 0.5f, 0.5f)
    }

    /** Преобразование типа стежка при повороте/отражении. cornerMap: старый угол -> новый. */
    fun transform(k: Byte, cornerMap: IntArray, swapHalves: Boolean): Byte = when {
        k == HALF_FWD -> if (swapHalves) HALF_BACK else HALF_FWD
        k == HALF_BACK -> if (swapHalves) HALF_FWD else HALF_BACK
        isQuarter(k) -> quarter(cornerMap[corner(k)])
        isThreeQuarter(k) -> threeQuarter(cornerMap[corner(k)])
        else -> k
    }

    /** Ближайший угол по дробной позиции внутри клетки. */
    fun cornerAt(fx: Float, fy: Float): Int = when {
        fy < 0.5f && fx < 0.5f -> 0
        fy < 0.5f -> 1
        fx >= 0.5f -> 2
        else -> 3
    }
}

/** Нитка. rgb — 0xRRGGBB. brand — фирма (DMC, Anchor, ...). */
data class Floss(val code: String, val name: String, val rgb: Int, val brand: String = "DMC") {
    val argb: Int get() = (0xFF shl 24) or rgb
    val key: String get() = "$brand $code"
    val label: String get() = "$brand $code"
}

/** Цвет в палитре схемы: нитка + символ на схеме. */
data class PaletteEntry(val floss: Floss, val symbol: Char)

/** Бэкстич: отрезок между узлами сетки (0..width, 0..height). color — индекс в палитре. */
data class Backstitch(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val color: Int)

/**
 * Французский узелок или бусина. Координаты в полуклетках (0..2*width, 0..2*height),
 * чтобы ставить и в узлы сетки, и в центры клеток.
 */
data class Knot(val hx: Int, val hy: Int, val color: Int, val bead: Boolean)

/** Индексы в массиве счётчиков стежков одного цвета. */
object Count {
    const val FULL = 0
    const val HALF = 1
    const val QUARTER = 2
    const val THREE_Q = 3
    const val KNOT = 4
    const val BEAD = 5
    const val SIZE = 6
}

/** Фрагмент схемы для копирования/вставки. Цвета хранятся нитками, чтобы вставлять в любую схему. */
class Clip(
    val width: Int,
    val height: Int,
    val flosses: List<Floss>,
    val symbols: List<Char>,
    val cells: IntArray,
    val kinds: ByteArray,
    val backstitches: List<Backstitch>,
    val knots: List<Knot>,
)

/** Прямоугольник клеток [x0, x1) × [y0, y1). */
data class CellRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    val isEmpty get() = width <= 0 || height <= 0

    companion object {
        fun of(ax: Int, ay: Int, bx: Int, by: Int, w: Int, h: Int): CellRect {
            val x0 = minOf(ax, bx).coerceIn(0, w - 1); val x1 = (maxOf(ax, bx) + 1).coerceIn(1, w)
            val y0 = minOf(ay, by).coerceIn(0, h - 1); val y1 = (maxOf(ay, by) + 1).coerceIn(1, h)
            return CellRect(x0, y0, x1, y1)
        }
    }
}

/**
 * Схема вышивки. Изменяемая, копируется целиком для истории (undo).
 * cells[i] — индекс цвета в палитре или -1 (пусто). done[i] — клетка уже вышита.
 */
class Pattern(
    val width: Int,
    val height: Int,
    val palette: MutableList<PaletteEntry> = mutableListOf(),
    val cells: IntArray = IntArray(width * height) { -1 },
    val kinds: ByteArray = ByteArray(width * height),
    val backstitches: MutableList<Backstitch> = mutableListOf(),
    var title: String = "Без названия",
    val knots: MutableList<Knot> = mutableListOf(),
    val done: BooleanArray = BooleanArray(width * height),
) {
    init {
        require(width in 1..MAX_SIZE && height in 1..MAX_SIZE) { "Размер схемы 1..$MAX_SIZE" }
        require(cells.size == width * height && kinds.size == width * height && done.size == width * height)
    }

    fun index(x: Int, y: Int) = y * width + x
    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun colorAt(x: Int, y: Int) = cells[index(x, y)]

    fun copy(): Pattern = Pattern(
        width, height, palette.toMutableList(), cells.copyOf(), kinds.copyOf(),
        backstitches.toMutableList(), title, knots.toMutableList(), done.copyOf(),
    )

    /** Добавить нитку в палитру (если её ещё нет), вернуть индекс. */
    fun addFloss(floss: Floss, preferredSymbol: Char? = null): Int {
        val existing = palette.indexOfFirst { it.floss.key == floss.key }
        if (existing >= 0) return existing
        val used = palette.map { it.symbol }.toSet()
        val sym = if (preferredSymbol != null && preferredSymbol !in used) preferredSymbol
        else SYMBOLS.firstOrNull { it !in used } ?: '?'
        palette.add(PaletteEntry(floss, sym))
        return palette.lastIndex
    }

    /** Счётчики стежков по цветам: [Count.FULL..Count.BEAD]. */
    fun stitchCounts(): Array<IntArray> {
        val res = Array(palette.size) { IntArray(Count.SIZE) }
        for (i in cells.indices) {
            val c = cells[i]
            if (c < 0 || c >= res.size) continue
            val k = kinds[i]
            val slot = when {
                k == StitchKind.FULL -> Count.FULL
                k == StitchKind.HALF_FWD || k == StitchKind.HALF_BACK -> Count.HALF
                StitchKind.isQuarter(k) -> Count.QUARTER
                else -> Count.THREE_Q
            }
            res[c][slot]++
        }
        for (k in knots) if (k.color in res.indices) res[k.color][if (k.bead) Count.BEAD else Count.KNOT]++
        return res
    }

    /** Длина бэкстича каждого цвета в клетках. */
    fun backstitchLengths(): DoubleArray {
        val res = DoubleArray(palette.size)
        for (b in backstitches) {
            if (b.color in res.indices) {
                val dx = (b.x2 - b.x1).toDouble(); val dy = (b.y2 - b.y1).toDouble()
                res[b.color] += Math.sqrt(dx * dx + dy * dy)
            }
        }
        return res
    }

    /** Прогресс вышивания по цветам: [вышито клеток, всего клеток]. */
    fun progress(): Array<IntArray> {
        val res = Array(palette.size) { IntArray(2) }
        for (i in cells.indices) {
            val c = cells[i]
            if (c < 0 || c >= res.size) continue
            res[c][1]++
            if (done[i]) res[c][0]++
        }
        return res
    }

    // ---------- Палитра ----------

    /** Переиндексация цветов: remap[старый] = новый или -1 (удалить стежки). */
    private fun remapped(newPalette: MutableList<PaletteEntry>, remap: IntArray): Pattern {
        val n = cells.size
        val newCells = IntArray(n); val newKinds = kinds.copyOf(); val newDone = done.copyOf()
        for (i in 0 until n) {
            val c = cells[i]
            newCells[i] = if (c >= 0 && c < remap.size) remap[c] else -1
            if (newCells[i] < 0) { newKinds[i] = 0; newDone[i] = false }
        }
        val newBs = backstitches.filter { it.color in remap.indices && remap[it.color] >= 0 }
            .map { it.copy(color = remap[it.color]) }.toMutableList()
        val newKnots = knots.filter { it.color in remap.indices && remap[it.color] >= 0 }
            .map { it.copy(color = remap[it.color]) }.toMutableList()
        return Pattern(width, height, newPalette, newCells, newKinds, newBs, title, newKnots, newDone)
    }

    /** Удалить цвета, которые нигде не используются. */
    fun removeUnusedColors(): Pattern {
        val used = BooleanArray(palette.size)
        cells.forEach { if (it >= 0 && it < used.size) used[it] = true }
        backstitches.forEach { if (it.color in used.indices) used[it.color] = true }
        knots.forEach { if (it.color in used.indices) used[it.color] = true }
        val remap = IntArray(palette.size) { -1 }
        val np = mutableListOf<PaletteEntry>()
        for (i in palette.indices) if (used[i]) { remap[i] = np.size; np.add(palette[i]) }
        return remapped(np, remap)
    }

    /** Удалить цвет вместе со всеми его стежками. */
    fun removeColor(colorIndex: Int): Pattern {
        val remap = IntArray(palette.size) { if (it < colorIndex) it else if (it == colorIndex) -1 else it - 1 }
        val np = palette.filterIndexed { i, _ -> i != colorIndex }.toMutableList()
        return remapped(np, remap)
    }

    /** Перевести схему на нитки другой фирмы (ближайшие по цвету, совпадения сливаются). */
    fun convertPalette(catalog: FlossCatalog): Pattern {
        val np = mutableListOf<PaletteEntry>()
        val remap = IntArray(palette.size)
        for (i in palette.indices) {
            val f = catalog.nearest(palette[i].floss.rgb)
            val existing = np.indexOfFirst { it.floss.key == f.key }
            if (existing >= 0) remap[i] = existing
            else { remap[i] = np.size; np.add(PaletteEntry(f, palette[i].symbol)) }
        }
        return remapped(np, remap)
    }

    // ---------- Преобразования ----------

    private fun transformed(
        newW: Int, newH: Int,
        cellMap: (Int, Int) -> Int,          // (x, y) -> новый индекс
        nodeMap: (Int, Int) -> Pair<Int, Int>, // узел сетки -> новый узел
        halfMap: (Int, Int) -> Pair<Int, Int>, // полуклетки -> новые полуклетки
        cornerMap: IntArray, swapHalves: Boolean,
    ): Pattern {
        val p = Pattern(newW, newH, palette.toMutableList(), title = title)
        for (y in 0 until height) for (x in 0 until width) {
            val src = index(x, y); val dst = cellMap(x, y)
            p.cells[dst] = cells[src]
            p.kinds[dst] = StitchKind.transform(kinds[src], cornerMap, swapHalves)
            p.done[dst] = done[src]
        }
        backstitches.forEach {
            val (a, b) = nodeMap(it.x1, it.y1); val (c, d) = nodeMap(it.x2, it.y2)
            p.backstitches.add(Backstitch(a, b, c, d, it.color))
        }
        knots.forEach { val (a, b) = halfMap(it.hx, it.hy); p.knots.add(it.copy(hx = a, hy = b)) }
        return p
    }

    fun mirrorHorizontal(): Pattern = transformed(
        width, height,
        { x, y -> y * width + (width - 1 - x) },
        { x, y -> (width - x) to y },
        { x, y -> (2 * width - x) to y },
        intArrayOf(1, 0, 3, 2), true,
    )

    fun mirrorVertical(): Pattern = transformed(
        width, height,
        { x, y -> (height - 1 - y) * width + x },
        { x, y -> x to (height - y) },
        { x, y -> x to (2 * height - y) },
        intArrayOf(3, 2, 1, 0), true,
    )

    /** Поворот на 90° по часовой стрелке. */
    fun rotateClockwise(): Pattern = transformed(
        height, width,
        { x, y -> x * height + (height - 1 - y) },
        { x, y -> (height - y) to x },
        { x, y -> (2 * height - y) to x },
        intArrayOf(1, 2, 3, 0), true,
    )

    /** Изменить размер холста (обрезка/добавление пустых клеток справа и снизу). */
    fun resizeCanvas(newW: Int, newH: Int): Pattern {
        val p = Pattern(newW, newH, palette.toMutableList(), title = title)
        for (y in 0 until minOf(height, newH)) for (x in 0 until minOf(width, newW)) {
            val s = index(x, y); val d = p.index(x, y)
            p.cells[d] = cells[s]; p.kinds[d] = kinds[s]; p.done[d] = done[s]
        }
        backstitches.filter { maxOf(it.x1, it.x2) <= newW && maxOf(it.y1, it.y2) <= newH }.forEach { p.backstitches.add(it) }
        knots.filter { it.hx <= 2 * newW && it.hy <= 2 * newH }.forEach { p.knots.add(it) }
        return p
    }

    /** Заливка связной области одного цвета (4-связность). Возвращает число изменённых клеток. */
    fun floodFill(sx: Int, sy: Int, color: Int, kind: Byte): Int {
        if (!inBounds(sx, sy)) return 0
        val target = colorAt(sx, sy)
        val start = index(sx, sy)
        if (target == color && kinds[start] == kind) return 0
        val stack = IntArray(cells.size)
        val visited = BooleanArray(cells.size)
        var sp = 0
        stack[sp++] = start; visited[start] = true
        var changed = 0
        while (sp > 0) {
            val i = stack[--sp]
            cells[i] = color; kinds[i] = kind; done[i] = false; changed++
            val x = i % width; val y = i / width
            if (x > 0) { val n = i - 1; if (!visited[n] && cells[n] == target) { visited[n] = true; stack[sp++] = n } }
            if (x < width - 1) { val n = i + 1; if (!visited[n] && cells[n] == target) { visited[n] = true; stack[sp++] = n } }
            if (y > 0) { val n = i - width; if (!visited[n] && cells[n] == target) { visited[n] = true; stack[sp++] = n } }
            if (y < height - 1) { val n = i + width; if (!visited[n] && cells[n] == target) { visited[n] = true; stack[sp++] = n } }
        }
        return changed
    }

    // ---------- Выделение ----------

    private fun bsInside(b: Backstitch, r: CellRect) =
        minOf(b.x1, b.x2) >= r.x0 && maxOf(b.x1, b.x2) <= r.x1 && minOf(b.y1, b.y2) >= r.y0 && maxOf(b.y1, b.y2) <= r.y1

    private fun knotInside(k: Knot, r: CellRect) =
        k.hx in 2 * r.x0..2 * r.x1 && k.hy in 2 * r.y0..2 * r.y1

    /** Скопировать фрагмент. */
    fun extract(r: CellRect): Clip {
        val used = LinkedHashMap<Int, Int>() // индекс палитры -> индекс в clip
        fun map(c: Int): Int = used.getOrPut(c) { used.size }
        val w = r.width; val h = r.height
        val cl = IntArray(w * h) { -1 }; val kd = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val src = index(r.x0 + x, r.y0 + y)
            val c = cells[src]
            if (c >= 0) { cl[y * w + x] = map(c); kd[y * w + x] = kinds[src] }
        }
        val bs = backstitches.filter { bsInside(it, r) }
            .map { Backstitch(it.x1 - r.x0, it.y1 - r.y0, it.x2 - r.x0, it.y2 - r.y0, map(it.color)) }
        val kn = knots.filter { knotInside(it, r) }
            .map { it.copy(hx = it.hx - 2 * r.x0, hy = it.hy - 2 * r.y0, color = map(it.color)) }
        val order = used.entries.sortedBy { it.value }.map { it.key }
        return Clip(w, h, order.map { palette[it].floss }, order.map { palette[it].symbol }, cl, kd, bs, kn)
    }

    /** Очистить фрагмент (клетки, бэкстич и узелки внутри). */
    fun clear(r: CellRect) {
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) {
            val i = index(x, y); cells[i] = -1; kinds[i] = 0; done[i] = false
        }
        backstitches.removeAll { bsInside(it, r) }
        knots.removeAll { knotInside(it, r) }
    }

    /** Вставить фрагмент левым верхним углом в (ox, oy). Пустые клетки фрагмента не затирают схему. */
    fun stamp(clip: Clip, ox: Int, oy: Int) {
        val remap = IntArray(clip.flosses.size) { addFloss(clip.flosses[it], clip.symbols.getOrNull(it)) }
        for (y in 0 until clip.height) for (x in 0 until clip.width) {
            val c = clip.cells[y * clip.width + x]
            val tx = ox + x; val ty = oy + y
            if (c < 0 || !inBounds(tx, ty)) continue
            val i = index(tx, ty)
            cells[i] = remap[c]; kinds[i] = clip.kinds[y * clip.width + x]; done[i] = false
        }
        for (b in clip.backstitches) {
            val nb = Backstitch(b.x1 + ox, b.y1 + oy, b.x2 + ox, b.y2 + oy, remap[b.color])
            if (minOf(nb.x1, nb.x2) >= 0 && maxOf(nb.x1, nb.x2) <= width && minOf(nb.y1, nb.y2) >= 0 && maxOf(nb.y1, nb.y2) <= height)
                backstitches.add(nb)
        }
        for (k in clip.knots) {
            val nk = k.copy(hx = k.hx + 2 * ox, hy = k.hy + 2 * oy, color = remap[k.color])
            if (nk.hx in 0..2 * width && nk.hy in 0..2 * height) {
                knots.removeAll { it.hx == nk.hx && it.hy == nk.hy }
                knots.add(nk)
            }
        }
    }

    companion object {
        const val MAX_SIZE = 1000

        /** Символы для схемы: сначала хорошо различимые фигуры, потом буквы и цифры. */
        val SYMBOLS: List<Char> = (
            "●■▲◆★✚✖○□△◇☆♥♠♣♦▼◀▶◐◑" +
                "ABCDEFGHJKLMNPRSTUVWXYZ" +
                "abdefghkmnpqrtuy" +
                "23456789" +
                "@#%&=+?!$§¤Ω∑∆Ψφλµ"
            ).toList()
    }
}
