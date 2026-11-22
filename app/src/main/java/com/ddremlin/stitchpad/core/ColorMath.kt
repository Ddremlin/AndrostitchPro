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
class FlossCatalog(val flosses: List<Floss>) {
    private val labs: Array<Lab> = Array(flosses.size) { ColorMath.rgbToLab(flosses[it].rgb) }

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
        /** Формат строки: код;название;R;G;B */
        fun parseCsv(text: String): FlossCatalog {
            val list = text.lineSequence().mapNotNull { line ->
                val p = line.trim().split(';')
                if (p.size < 5) null else {
                    val r = p[2].toIntOrNull(); val g = p[3].toIntOrNull(); val b = p[4].toIntOrNull()
                    if (r == null || g == null || b == null) null
                    else Floss(p[0], p[1], (r shl 16) or (g shl 8) or b)
                }
            }.toList()
            return FlossCatalog(list)
        }
    }
}
