package com.ddremlin.stitchpad.core

import kotlin.math.ceil
import kotlin.math.sqrt

/** Расчёт размера вышивки и расхода ниток (приблизительно). */
object FlossUsage {
    /** Длина мотка DMC — 8 м, 6 сложений. */
    private const val SKEIN_MM = 8000.0
    private const val STRANDS_IN_SKEIN = 6

    data class Row(
        val entry: PaletteEntry,
        val full: Int,
        val half: Int,
        val backstitchCells: Double,
        val skeins: Double,
    ) {
        val skeinsToBuy: Int get() = maxOf(1, ceil(skeins).toInt())
    }

    /** Размер вышивки в сантиметрах на канве с данным каунтом (клеток на дюйм). */
    fun sizeCm(cells: Int, count: Int): Double = cells * 2.54 / count

    fun calculate(p: Pattern, count: Int = 14, strands: Int = 2): List<Row> {
        val cell = 25.4 / count
        val waste = 1.2 // запас на закрепки и переходы
        val perFull = (2 * sqrt(2.0) + 2) * cell * waste
        val perHalf = (sqrt(2.0) + 1) * cell * waste
        val usablePerSkein = SKEIN_MM * (STRANDS_IN_SKEIN / strands)
        val counts = p.stitchCounts()
        val bs = p.backstitchLengths()
        return p.palette.indices.map { i ->
            val full = counts[i][0]; val half = counts[i][1]
            // бэкстич обычно в 1 сложение: в пересчёте на "strands" делим пропорционально
            val bsMm = bs[i] * cell * 2 * waste * (1.0 / strands)
            val mm = full * perFull + half * perHalf + bsMm
            Row(p.palette[i], full, half, bs[i], mm / usablePerSkein)
        }
    }
}
