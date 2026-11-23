package com.ddremlin.stitchpad.core

import kotlin.math.ceil
import kotlin.math.sqrt

/** Расчёт размера вышивки и расхода ниток (приблизительно). */
object FlossUsage {
    /** Длина мотка мулине — 8 м, 6 сложений. */
    private const val SKEIN_MM = 8000.0
    private const val STRANDS_IN_SKEIN = 6

    data class Row(
        val entry: PaletteEntry,
        /** Счётчики по индексам [Count]. */
        val counts: IntArray,
        val backstitchCells: Double,
        val skeins: Double,
    ) {
        val full get() = counts[Count.FULL]
        val half get() = counts[Count.HALF]
        val quarter get() = counts[Count.QUARTER]
        val threeQuarter get() = counts[Count.THREE_Q]
        val knotsCount get() = counts[Count.KNOT]
        val beads get() = counts[Count.BEAD]
        /** Нужна ли нитка вообще (бисер ниткой не считается). */
        val usesFloss get() = skeins > 0.0
        val skeinsToBuy: Int get() = if (usesFloss) maxOf(1, ceil(skeins).toInt()) else 0

        /** Короткая строка: «крестов 120, ½ 4, ¼ 2, узелков 3». */
        fun summary(): String = buildList {
            if (full > 0) add("крестов $full")
            if (half > 0) add("½ $half")
            if (quarter > 0) add("¼ $quarter")
            if (threeQuarter > 0) add("¾ $threeQuarter")
            if (knotsCount > 0) add("узелков $knotsCount")
            if (beads > 0) add("бисер $beads шт")
            if (backstitchCells > 0) add(String.format(java.util.Locale.US, "бэкстич %.0f кл.", backstitchCells))
        }.joinToString(", ").ifEmpty { "не используется" }
    }

    /** Размер вышивки в сантиметрах на канве с данным каунтом (клеток на дюйм). */
    fun sizeCm(cells: Int, count: Int): Double = cells * 2.54 / count

    fun calculate(p: Pattern, count: Int = 14, strands: Int = 2): List<Row> {
        val cell = 25.4 / count
        val waste = 1.2 // запас на закрепки и переходы
        val perFull = (2 * sqrt(2.0) + 2) * cell * waste
        val perHalf = (sqrt(2.0) + 1) * cell * waste
        val perQuarter = perHalf / 2
        val perKnot = 4 * cell * waste
        val usablePerSkein = SKEIN_MM * (STRANDS_IN_SKEIN / strands)
        val counts = p.stitchCounts()
        val bs = p.backstitchLengths()
        return p.palette.indices.map { i ->
            val c = counts[i]
            // бэкстич обычно в 1 сложение — пересчитываем пропорционально
            val bsMm = bs[i] * cell * 2 * waste * (1.0 / strands)
            val mm = c[Count.FULL] * perFull + c[Count.HALF] * perHalf + c[Count.QUARTER] * perQuarter +
                c[Count.THREE_Q] * (perHalf + perQuarter) + c[Count.KNOT] * perKnot + bsMm
            Row(p.palette[i], c, bs[i], mm / usablePerSkein)
        }
    }
}
