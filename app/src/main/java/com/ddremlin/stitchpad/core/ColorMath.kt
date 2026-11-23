package com.ddremlin.stitchpad.core

import kotlin.math.cbrt
import kotlin.math.pow

/** Цвет в пространстве CIE L*a*b* — в нём расстояние близко к восприятию глазом. */
data class Lab(val l: Double, val a: Double, val b: Double) {
    fun dist2(o: Lab): Double {
        val dl = l - o.l; val da = a - o.a; val db = b - o.b
        return dl * dl + da * da + db * db
    }
}

object ColorMath {
    private fun srgbToLinear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun f(t: Double): Double = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0

    fun rgbToLab(rgb: Int): Lab {
        val r = srgbToLinear((rgb shr 16) and 0xFF)
        val g = srgbToLinear((rgb shr 8) and 0xFF)
        val b = srgbToLinear(rgb and 0xFF)
        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = (r * 0.2126 + g * 0.7152 + b * 0.0722)
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        val fx = f(x); val fy = f(y); val fz = f(z)
        return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** Чёрный или белый — что лучше читается поверх цвета. */
    fun contrastOn(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return if (lum > 140) 0x000000 else 0xFFFFFF
    }
}

/** Каталог ниток с поиском ближайшего цвета. */
class FlossCatalog(val brand: String, val flosses: List<Floss>) {
    private val labs: Array<Lab> = Array(flosses.size) { ColorMath.rgbToLab(flosses[it].rgb) }
    private val byCodeMap: Map<String, Floss> = flosses.associateBy { it.code.lowercase() }

    fun byCode(code: String): Floss? = byCodeMap[code.lowercase()]

    fun nearest(rgb: Int, exclude: Set<String> = emptySet()): Floss {
        val lab = ColorMath.rgbToLab(rgb)
        var best = -1; var bestD = Double.MAX_VALUE
        for (i in flosses.indices) {
            if (flosses[i].code in exclude) continue
            val d = labs[i].dist2(lab)
            if (d < bestD) { bestD = d; best = i }
        }
        return flosses[if (best >= 0) best else 0]
    }

    fun search(query: String): List<Floss> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return flosses
        return flosses.filter { it.code.lowercase().startsWith(q) || it.name.lowercase().contains(q) }
    }

    companion object {
        /**
         * Чтение палитры из CSV. Понимает:
         *  - строки без заголовка: код;название;R;G;B (разделитель ; или ,);
         *  - таблицы с заголовком, где есть столбцы кода, названия и R/G/B или HEX
         *    (например «Anchor Code,Color Name,HEX,R,G,B,...» или «Floss#,Description,Red,Green,Blue»).
         */
        fun parseCsv(text: String, brand: String = "DMC"): FlossCatalog {
            val lines = text.lineSequence().map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() }.toList()
            if (lines.isEmpty()) return FlossCatalog(brand, emptyList())
            val sep = if (lines.first().contains(';')) ';' else ','
            val first = splitCsv(lines.first(), sep).map { it.lowercase() }

            fun col(vararg names: String) = first.indexOfFirst { h -> names.any { n -> h == n } }
            fun colContains(vararg parts: String) = first.indexOfFirst { h -> parts.any { p -> h.contains(p) } }

            var iCode = 0; var iName = 1; var iR = 2; var iG = 3; var iB = 4; var iHex = -1
            val hasHeader = first.size >= 3 && first.take(5).count { it.toIntOrNull() != null } < 2 &&
                (col("r", "red") >= 0 || colContains("hex") >= 0)
            if (hasHeader) {
                iR = col("r", "red"); iG = col("g", "green"); iB = col("b", "blue")
                iHex = colContains("hex")
                iCode = colContains("code", "floss", "#", "номер", "код", "number").let { if (it < 0) 0 else it }
                iName = colContains("name", "desc", "назв", "цвет").let { if (it < 0 || it == iCode) 1 else it }
            }
            val list = lines.drop(if (hasHeader) 1 else 0).mapNotNull { line ->
                val p = splitCsv(line, sep)
                val code = p.getOrNull(iCode)?.trim().orEmpty()
                if (code.isEmpty()) return@mapNotNull null
                val name = p.getOrNull(iName)?.trim().orEmpty()
                var rgb: Int? = null
                val r = p.getOrNull(iR)?.trim()?.toIntOrNull()
                val g = p.getOrNull(iG)?.trim()?.toIntOrNull()
                val b = p.getOrNull(iB)?.trim()?.toIntOrNull()
                if (iR >= 0 && r != null && g != null && b != null && r in 0..255 && g in 0..255 && b in 0..255) {
                    rgb = (r shl 16) or (g shl 8) or b
                } else if (iHex >= 0) {
                    val h = p.getOrNull(iHex)?.trim()?.removePrefix("#")
                    if (h != null && h.length == 6) rgb = h.toIntOrNull(16)
                }
                if (rgb == null) null else Floss(code, name, rgb, brand)
            }
            return FlossCatalog(brand, list.distinctBy { it.code })
        }

        /** Разбор строки CSV с учётом кавычек. */
        private fun splitCsv(line: String, sep: Char): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> quoted = !quoted
                    ch == sep && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                    else -> sb.append(ch)
                }
                i++
            }
            out.add(sb.toString())
            return out
        }
    }
}

/**
 * Официальная (табличная) таблица соответствия ниток двух фирм, например DMC → Anchor.
 * Формат файла: код_from;код_to в каждой строке.
 */
class ConversionTable(val from: String, val to: String, val map: Map<String, String>) {
    companion object {
        fun parse(text: String, from: String, to: String): ConversionTable {
            val m = LinkedHashMap<String, String>()
            text.lineSequence().forEach { line ->
                val p = line.trim().split(';', ',')
                if (p.size >= 2 && p[0].isNotBlank() && p[1].isNotBlank()) m.putIfAbsent(p[0].trim(), p[1].trim())
            }
            return ConversionTable(from, to, m)
        }
    }
}

/** Подбор аналога нитки в другой палитре: сначала по таблицам соответствия, иначе по цвету. */
object Equivalents {
    /** Аналог по таблице или null, если таблицы для этой пары фирм нет или в ней нет этой нитки. */
    fun official(f: Floss, target: FlossCatalog, tables: List<ConversionTable>): Floss? {
        for (t in tables) {
            if (f.brand.equals(t.from, true) && target.brand.equals(t.to, true)) {
                val code = t.map[f.code] ?: t.map.entries.firstOrNull { it.key.equals(f.code, true) }?.value
                code?.let { target.byCode(it) }?.let { return it }
            }
            if (f.brand.equals(t.to, true) && target.brand.equals(t.from, true)) {
                // обратное направление: несколько ниток могут вести к одной — берём ближайшую по цвету
                val lab = ColorMath.rgbToLab(f.rgb)
                val cands = t.map.filterValues { it.equals(f.code, true) }.keys.mapNotNull { target.byCode(it) }
                cands.minByOrNull { ColorMath.rgbToLab(it.rgb).dist2(lab) }?.let { return it }
            }
        }
        return null
    }

    /** Аналог и признак «по таблице» (true) или «по цвету» (false). */
    fun find(f: Floss, target: FlossCatalog, tables: List<ConversionTable>): Pair<Floss, Boolean> {
        official(f, target, tables)?.let { return it to true }
        return target.nearest(f.rgb) to false
    }
}
