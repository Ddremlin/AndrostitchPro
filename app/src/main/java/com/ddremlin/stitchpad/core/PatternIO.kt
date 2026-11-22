package com.ddremlin.stitchpad.core

/**
 * Собственный текстовый формат схемы (.stp). Простой и устойчивый к версиям:
 *
 * STITCHPAD 1
 * title Моя схема
 * size 80 60
 * color 310|Black|000000|●
 * cells -1*120 0*5 1 ...      (RLE: значение*повторы)
 * kinds 0*4800
 * bs 0 0 3 3 1
 */
object PatternIO {
    private const val MAGIC = "STITCHPAD"

    fun write(p: Pattern): String = buildString {
        append(MAGIC).append(" 1\n")
        append("title ").append(p.title.replace('\n', ' ')).append('\n')
        append("size ").append(p.width).append(' ').append(p.height).append('\n')
        for (e in p.palette) {
            append("color ").append(e.floss.code).append('|').append(e.floss.name).append('|')
                .append(String.format("%06X", e.floss.rgb)).append('|').append(e.symbol).append('\n')
        }
        append("cells ").append(rle(p.cells)).append('\n')
        append("kinds ").append(rle(IntArray(p.kinds.size) { p.kinds[it].toInt() })).append('\n')
        for (b in p.backstitches) {
            append("bs ").append(b.x1).append(' ').append(b.y1).append(' ')
                .append(b.x2).append(' ').append(b.y2).append(' ').append(b.color).append('\n')
        }
    }

    fun read(text: String): Pattern {
        val lines = text.lines()
        require(lines.isNotEmpty() && lines[0].startsWith(MAGIC)) { "Это не файл StitchPad" }
        var title = "Без названия"
        var w = 0; var h = 0
        val palette = mutableListOf<PaletteEntry>()
        var cells: IntArray? = null
        var kinds: IntArray? = null
        val bs = mutableListOf<Backstitch>()
        for (line in lines.drop(1)) {
            val sp = line.indexOf(' ')
            if (sp < 0) continue
            val key = line.substring(0, sp); val rest = line.substring(sp + 1)
            when (key) {
                "title" -> title = rest
                "size" -> { val p = rest.trim().split(' '); w = p[0].toInt(); h = p[1].toInt() }
                "color" -> {
                    val p = rest.split('|')
                    if (p.size >= 4) palette.add(PaletteEntry(Floss(p[0], p[1], p[2].toInt(16)), p[3].firstOrNull() ?: '?'))
                }
                "cells" -> cells = unrle(rest)
                "kinds" -> kinds = unrle(rest)
                "bs" -> {
                    val p = rest.trim().split(' ').map { it.toInt() }
                    if (p.size == 5) bs.add(Backstitch(p[0], p[1], p[2], p[3], p[4]))
                }
            }
        }
        val n = w * h
        val c = cells ?: IntArray(n) { -1 }
        val k = kinds ?: IntArray(n)
        require(c.size == n && k.size == n) { "Файл повреждён: размер не совпадает" }
        // защита от битых индексов
        for (i in c.indices) if (c[i] >= palette.size) c[i] = -1
        val validBs = bs.filter { it.color in palette.indices }.toMutableList()
        return Pattern(w, h, palette, c, ByteArray(n) { k[it].toByte() }, validBs, title)
    }

    private fun rle(a: IntArray): String {
        if (a.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < a.size) {
            val v = a[i]; var j = i + 1
            while (j < a.size && a[j] == v) j++
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(v); if (j - i > 1) sb.append('*').append(j - i)
            i = j
        }
        return sb.toString()
    }

    private fun unrle(s: String): IntArray {
        val out = ArrayList<Int>()
        for (tok in s.trim().split(' ')) {
            if (tok.isEmpty()) continue
            val star = tok.indexOf('*')
            if (star < 0) out.add(tok.toInt())
            else { val v = tok.substring(0, star).toInt(); repeat(tok.substring(star + 1).toInt()) { out.add(v) } }
        }
        return out.toIntArray()
    }
}
