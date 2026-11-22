package com.ddremlin.stitchpad.core

/** Тип стежка в клетке. */
object StitchKind {
    const val FULL: Byte = 0      // полный крест
    const val HALF_FWD: Byte = 1  // полукрест "/"
    const val HALF_BACK: Byte = 2 // полукрест "\"
}

/** Нитка (DMC). rgb — 0xRRGGBB. */
data class Floss(val code: String, val name: String, val rgb: Int) {
    val argb: Int get() = (0xFF shl 24) or rgb
}

/** Цвет в палитре схемы: нитка + символ на схеме. */
data class PaletteEntry(val floss: Floss, val symbol: Char)

/**
 * Бэкстич: отрезок между узлами сетки. Координаты узлов: 0..width, 0..height.
 * color — индекс в палитре.
 */
data class Backstitch(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val color: Int)

/**
 * Схема вышивки. Изменяемая, но копируется целиком для истории (undo).
 * cells[i] = индекс цвета в палитре или -1 (пусто).
 */
class Pattern(
    val width: Int,
    val height: Int,
    val palette: MutableList<PaletteEntry> = mutableListOf(),
    val cells: IntArray = IntArray(width * height) { -1 },
    val kinds: ByteArray = ByteArray(width * height),
    val backstitches: MutableList<Backstitch> = mutableListOf(),
    var title: String = "Без названия",
) {
    init {
        require(width in 1..MAX_SIZE && height in 1..MAX_SIZE) { "Размер схемы 1..$MAX_SIZE" }
        require(cells.size == width * height && kinds.size == width * height)
    }

    fun index(x: Int, y: Int) = y * width + x
    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun colorAt(x: Int, y: Int) = cells[index(x, y)]

    fun copy(): Pattern = Pattern(
        width, height, palette.toMutableList(), cells.copyOf(), kinds.copyOf(),
        backstitches.toMutableList(), title,
    )

    /** Добавить нитку в палитру (если её ещё нет), вернуть индекс. */
    fun addFloss(thread: Floss): Int {
        val existing = palette.indexOfFirst { it.floss.code == thread.code }
        if (existing >= 0) return existing
        palette.add(PaletteEntry(thread, nextSymbol()))
        return palette.lastIndex
    }

    private fun nextSymbol(): Char {
        val used = palette.map { it.symbol }.toSet()
        return SYMBOLS.firstOrNull { it !in used } ?: '?'
    }

    /** Количество стежков каждого цвета: [полные, половинки]. */
    fun stitchCounts(): Array<IntArray> {
        val res = Array(palette.size) { IntArray(2) }
        for (i in cells.indices) {
            val c = cells[i]
            if (c >= 0 && c < res.size) {
                if (kinds[i] == StitchKind.FULL) res[c][0]++ else res[c][1]++
            }
        }
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

    /** Удалить цвета, которые нигде не используются; переиндексировать. */
    fun removeUnusedColors(): Pattern {
        val used = BooleanArray(palette.size)
        cells.forEach { if (it >= 0) used[it] = true }
        backstitches.forEach { if (it.color in used.indices) used[it.color] = true }
        val remap = IntArray(palette.size) { -1 }
        val newPalette = mutableListOf<PaletteEntry>()
        for (i in palette.indices) if (used[i]) { remap[i] = newPalette.size; newPalette.add(palette[i]) }
        val newCells = IntArray(cells.size) { if (cells[it] >= 0) remap[cells[it]] else -1 }
        val newBs = backstitches.filter { it.color in remap.indices && remap[it.color] >= 0 }
            .map { it.copy(color = remap[it.color]) }.toMutableList()
        return Pattern(width, height, newPalette, newCells, kinds.copyOf(), newBs, title)
    }

    /** Удалить цвет палитры вместе со стежками. */
    fun removeColor(colorIndex: Int): Pattern {
        val p = copy()
        for (i in p.cells.indices) {
            if (p.cells[i] == colorIndex) { p.cells[i] = -1; p.kinds[i] = 0 }
        }
        p.backstitches.removeAll { it.color == colorIndex }
        return p.removeUnusedColorsKeeping(colorIndex)
    }

    private fun removeUnusedColorsKeeping(removed: Int): Pattern {
        val remap = IntArray(palette.size) { if (it < removed) it else if (it == removed) -1 else it - 1 }
        val newPalette = palette.filterIndexed { i, _ -> i != removed }.toMutableList()
        val newCells = IntArray(cells.size) { if (cells[it] >= 0) remap[cells[it]] else -1 }
        val newBs = backstitches.filter { remap[it.color] >= 0 }.map { it.copy(color = remap[it.color]) }.toMutableList()
        return Pattern(width, height, newPalette, newCells, kinds.copyOf(), newBs, title)
    }

    // ---------- Преобразования ----------

    fun mirrorHorizontal(): Pattern {
        val p = Pattern(width, height, palette.toMutableList(), title = title)
        for (y in 0 until height) for (x in 0 until width) {
            val src = index(x, y); val dst = p.index(width - 1 - x, y)
            p.cells[dst] = cells[src]; p.kinds[dst] = flipHalf(kinds[src])
        }
        backstitches.forEach { p.backstitches.add(Backstitch(width - it.x1, it.y1, width - it.x2, it.y2, it.color)) }
        return p
    }

    fun mirrorVertical(): Pattern {
        val p = Pattern(width, height, palette.toMutableList(), title = title)
        for (y in 0 until height) for (x in 0 until width) {
            val src = index(x, y); val dst = p.index(x, height - 1 - y)
            p.cells[dst] = cells[src]; p.kinds[dst] = flipHalf(kinds[src])
        }
        backstitches.forEach { p.backstitches.add(Backstitch(it.x1, height - it.y1, it.x2, height - it.y2, it.color)) }
        return p
    }

    /** Поворот на 90° по часовой стрелке. */
    fun rotateClockwise(): Pattern {
        val p = Pattern(height, width, palette.toMutableList(), title = title)
        for (y in 0 until height) for (x in 0 until width) {
            val src = index(x, y); val dst = p.index(height - 1 - y, x)
            p.cells[dst] = cells[src]; p.kinds[dst] = flipHalf(kinds[src])
        }
        backstitches.forEach { p.backstitches.add(Backstitch(height - it.y1, it.x1, height - it.y2, it.x2, it.color)) }
        return p
    }

    /** Изменить размер холста (обрезка/добавление пустых клеток справа и снизу). */
    fun resizeCanvas(newW: Int, newH: Int): Pattern {
        val p = Pattern(newW, newH, palette.toMutableList(), title = title)
        for (y in 0 until minOf(height, newH)) for (x in 0 until minOf(width, newW)) {
            p.cells[p.index(x, y)] = cells[index(x, y)]; p.kinds[p.index(x, y)] = kinds[index(x, y)]
        }
        backstitches.filter { maxOf(it.x1, it.x2) <= newW && maxOf(it.y1, it.y2) <= newH }
            .forEach { p.backstitches.add(it) }
        return p
    }

    /** Заливка связной области одного цвета/пустоты (4-связность). Возвращает число изменённых клеток. */
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
            cells[i] = color; kinds[i] = kind; changed++
            val x = i % width; val y = i / width
            if (x > 0) { val n = i - 1; if (!visited[n] && cells[n] == target) { visited[n] = true; stack[sp++] = n } }
            if (x < width - 1) { val n = i + 1; if (!visited[n] && cells[n] == target) { visited[n] = true; stack[sp++] = n } }
            if (y > 0) { val n = i - width; if (!visited[n] && cells[n] == target) { visited[n] = true; stack[sp++] = n } }
            if (y < height - 1) { val n = i + width; if (!visited[n] && cells[n] == target) { visited[n] = true; stack[sp++] = n } }
        }
        return changed
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

        private fun flipHalf(k: Byte): Byte = when (k) {
            StitchKind.HALF_FWD -> StitchKind.HALF_BACK
            StitchKind.HALF_BACK -> StitchKind.HALF_FWD
            else -> k
        }
    }
}
