package com.ddremlin.stitchpad.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ddremlin.stitchpad.core.Backstitch
import com.ddremlin.stitchpad.core.CellRect
import com.ddremlin.stitchpad.core.Clip
import com.ddremlin.stitchpad.core.ConversionTable
import com.ddremlin.stitchpad.core.Equivalents
import com.ddremlin.stitchpad.core.Floss
import com.ddremlin.stitchpad.core.FlossCatalog
import com.ddremlin.stitchpad.core.Knot
import com.ddremlin.stitchpad.core.Pattern
import com.ddremlin.stitchpad.core.PatternIO
import com.ddremlin.stitchpad.core.PhotoConverter
import com.ddremlin.stitchpad.core.StitchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

enum class Tool(val glyph: String, val label: String, val stitching: Boolean = false) {
    PAN("✋", "Рука"),
    FULL("✕", "Крест"),
    HALF_FWD("╱", "½ /"),
    HALF_BACK("╲", "½ \\"),
    QUARTER("◸", "¼"),
    THREE_QUARTER("◩", "¾"),
    BACKSTITCH("⟋", "Бэкстич"),
    KNOT("•", "Узелок"),
    BEAD("◉", "Бисер"),
    ERASER("⌫", "Ластик"),
    FILL("▨", "Заливка"),
    PICKER("◎", "Пипетка"),
    SELECT("⬚", "Выделить"),
    // режим «вышиваю»
    MARK("✓", "Отметить", true),
    UNMARK("↺", "Снять", true),
}

enum class ViewMode(val label: String) { BOTH("Цвет+символ"), COLOR("Цвет"), SYMBOL("Символы") }

/** Плавающий (вставляемый/перемещаемый) фрагмент и его положение в клетках. */
data class Floating(val clip: Clip, val x: Int, val y: Int, val fromMove: Boolean)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    /** Встроенные палитры из assets. */
    private val builtInCatalogs: List<FlossCatalog> by lazy {
        listOf("dmc.csv" to "DMC", "anchor.csv" to "Anchor").mapNotNull { (file, brand) ->
            runCatching { FlossCatalog.parseCsv(app.assets.open(file).bufferedReader().use { it.readText() }, brand) }.getOrNull()
        }
    }
    private val dmcCatalog: FlossCatalog get() = builtInCatalogs.first()

    /** Таблицы соответствия ниток (DMC → Anchor по таблице artpatt.com). */
    val conversionTables: List<ConversionTable> by lazy {
        listOfNotNull(runCatching {
            ConversionTable.parse(app.assets.open("dmc_anchor.csv").bufferedReader().use { it.readText() }, "DMC", "Anchor")
        }.getOrNull())
    }

    /** Аналог нитки в другой палитре и признак «по таблице». */
    fun equivalent(f: Floss, target: FlossCatalog): Pair<Floss, Boolean> = Equivalents.find(f, target, conversionTables)
    val builtInBrands: Set<String> get() = builtInCatalogs.map { it.brand }.toSet()

    private val palettesDir: File get() = File(getApplication<Application>().filesDir, "palettes").apply { mkdirs() }

    /** Все палитры: встроенная DMC + импортированные пользователем (.csv). */
    var catalogs by mutableStateOf<List<FlossCatalog>>(emptyList()); private set
    /** Фирма ниток для подбора цветов из фото и по умолчанию в выборе ниток. */
    var activeBrand by mutableStateOf("DMC")
    val activeCatalog: FlossCatalog get() = catalogs.firstOrNull { it.brand == activeBrand } ?: dmcCatalog

    var pattern by mutableStateOf(Pattern(60, 60)); private set
    /** Увеличивается при любом изменении клеток — холст перерисовывается. */
    var revision by mutableIntStateOf(0); private set
    var tool by mutableStateOf(Tool.FULL); private set
    var currentColor by mutableIntStateOf(-1)
    var viewMode by mutableStateOf(ViewMode.BOTH)
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var fileName by mutableStateOf<String?>(null); private set
    var backstitchPreview by mutableStateOf<Backstitch?>(null); private set
    var fitRequest by mutableIntStateOf(0); private set
    var undoCount by mutableIntStateOf(0); private set
    var redoCount by mutableIntStateOf(0); private set

    /** Режим «вышиваю»: отметки готовых клеток, подсветка выбранного цвета. */
    var stitchMode by mutableStateOf(false); private set
    /** В режиме вышивания приглушать остальные цвета. */
    var highlightColor by mutableStateOf(true)

    var selection by mutableStateOf<CellRect?>(null); private set
    var clipboard by mutableStateOf<Clip?>(null); private set
    var floating by mutableStateOf<Floating?>(null); private set

    val appVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private val undoStack = ArrayDeque<Pattern>()
    private val redoStack = ArrayDeque<Pattern>()

    private val patternsDir: File get() = File(getApplication<Application>().filesDir, "patterns").apply { mkdirs() }
    private val autosaveFile: File get() = File(getApplication<Application>().filesDir, "autosave.stp")

    init {
        reloadCatalogs()
        runCatching {
            if (autosaveFile.exists()) {
                val lines = autosaveFile.readText()
                val nameLine = lines.substringBefore('\n')
                val body = lines.substringAfter('\n')
                pattern = PatternIO.read(body)
                fileName = nameLine.removePrefix("#").ifBlank { null }
                currentColor = if (pattern.palette.isNotEmpty()) 0 else -1
            }
        }
    }

    // ---------------- История ----------------

    private fun pushUndo() {
        undoStack.addLast(pattern.copy())
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        syncCounts()
    }

    private fun syncCounts() { undoCount = undoStack.size; redoCount = redoStack.size }

    fun undo() {
        floating = null
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(pattern)
        setPattern(prev, keepFit = true)
        syncCounts()
    }

    fun redo() {
        floating = null
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(pattern)
        setPattern(next, keepFit = true)
        syncCounts()
    }

    private fun setPattern(p: Pattern, keepFit: Boolean = false) {
        val sizeChanged = p.width != pattern.width || p.height != pattern.height
        pattern = p
        if (currentColor >= p.palette.size) currentColor = p.palette.lastIndex
        if (currentColor < 0 && p.palette.isNotEmpty()) currentColor = 0
        if (sizeChanged) { selection = null; floating = null }
        revision++
        if (!keepFit || sizeChanged) fitRequest++
    }

    private fun replaceWithUndo(p: Pattern) { pushUndo(); setPattern(p, keepFit = true) }

    // ---------------- Инструменты и режимы ----------------

    fun selectTool(t: Tool) {
        if (tool == Tool.SELECT && t != Tool.SELECT) { commitFloating(); selection = null }
        tool = t
    }

    fun toggleStitchMode() {
        commitFloating(); selection = null
        stitchMode = !stitchMode
        tool = if (stitchMode) Tool.MARK else Tool.FULL
        message = if (stitchMode) "Режим «вышиваю»: проводите пальцем по вышитым клеткам" else "Режим редактирования"
    }

    fun resetProgress() {
        pushUndo()
        pattern.done.fill(false)
        revision++
    }

    // ---------------- Рисование ----------------

    private var lastCellX = -1
    private var lastCellY = -1
    private var strokeStartX = 0
    private var strokeStartY = 0
    private var strokeActive = false
    private var strokeCorner = 0
    private var selAnchorX = 0
    private var selAnchorY = 0
    private var grabDX = 0
    private var grabDY = 0

    /** gx, gy — координаты в клетках (дробные). */
    fun beginStroke(gx: Float, gy: Float) {
        val cx = floor(gx).toInt(); val cy = floor(gy).toInt()
        when (tool) {
            Tool.PAN -> return
            Tool.PICKER -> {
                if (pattern.inBounds(cx, cy)) {
                    val c = pattern.colorAt(cx, cy)
                    if (c >= 0) { currentColor = c; tool = Tool.FULL }
                }
                return
            }
            Tool.FILL -> {
                if (!pattern.inBounds(cx, cy)) return
                if (!requireColor()) return
                pushUndo()
                if (pattern.floodFill(cx, cy, currentColor, StitchKind.FULL) > 0) revision++
                return
            }
            Tool.BACKSTITCH -> {
                if (!requireColor()) return
                strokeStartX = gx.roundToInt().coerceIn(0, pattern.width)
                strokeStartY = gy.roundToInt().coerceIn(0, pattern.height)
                backstitchPreview = Backstitch(strokeStartX, strokeStartY, strokeStartX, strokeStartY, currentColor)
                strokeActive = true
                return
            }
            Tool.KNOT, Tool.BEAD -> {
                if (!requireColor()) return
                val hx = (gx * 2).roundToInt().coerceIn(0, pattern.width * 2)
                val hy = (gy * 2).roundToInt().coerceIn(0, pattern.height * 2)
                pushUndo()
                pattern.knots.removeAll { it.hx == hx && it.hy == hy }
                pattern.knots.add(Knot(hx, hy, currentColor, tool == Tool.BEAD))
                revision++
                return
            }
            Tool.SELECT -> {
                val f = floating
                if (f != null) {
                    grabDX = cx - f.x; grabDY = cy - f.y
                } else {
                    selAnchorX = cx.coerceIn(0, pattern.width - 1); selAnchorY = cy.coerceIn(0, pattern.height - 1)
                    selection = CellRect.of(selAnchorX, selAnchorY, selAnchorX, selAnchorY, pattern.width, pattern.height)
                }
                strokeActive = true
                return
            }
            Tool.MARK, Tool.UNMARK -> {
                pushUndo()
                strokeActive = true
                lastCellX = cx; lastCellY = cy
                applyAt(cx, cy)
                revision++
            }
            else -> {
                if (tool != Tool.ERASER && !requireColor()) return
                pushUndo()
                strokeActive = true
                strokeCorner = StitchKind.cornerAt(gx - floor(gx), gy - floor(gy))
                lastCellX = cx; lastCellY = cy
                applyAt(cx, cy)
                revision++
            }
        }
    }

    fun moveStroke(gx: Float, gy: Float) {
        if (!strokeActive) return
        val cx = floor(gx).toInt(); val cy = floor(gy).toInt()
        when (tool) {
            Tool.BACKSTITCH -> {
                val ex = gx.roundToInt().coerceIn(0, pattern.width)
                val ey = gy.roundToInt().coerceIn(0, pattern.height)
                backstitchPreview = Backstitch(strokeStartX, strokeStartY, ex, ey, currentColor)
                return
            }
            Tool.SELECT -> {
                val f = floating
                if (f != null) {
                    val nx = cx - grabDX; val ny = cy - grabDY
                    if (nx != f.x || ny != f.y) floating = f.copy(x = nx, y = ny)
                } else {
                    selection = CellRect.of(selAnchorX, selAnchorY, cx, cy, pattern.width, pattern.height)
                }
                return
            }
            else -> {}
        }
        strokeCorner = StitchKind.cornerAt(gx - floor(gx), gy - floor(gy))
        if (cx == lastCellX && cy == lastCellY) {
            // в той же клетке для ¼ и ¾ может смениться угол
            if (tool == Tool.QUARTER || tool == Tool.THREE_QUARTER) { applyAt(cx, cy); revision++ }
            return
        }
        // линия Брезенхэма между точками, чтобы не было пропусков при быстром движении
        var x0 = lastCellX; var y0 = lastCellY
        val dx = abs(cx - x0); val dy = -abs(cy - y0)
        val sx = if (x0 < cx) 1 else -1; val sy = if (y0 < cy) 1 else -1
        var err = dx + dy
        while (true) {
            applyAt(x0, y0)
            if (x0 == cx && y0 == cy) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
        lastCellX = cx; lastCellY = cy
        revision++
    }

    fun endStroke() {
        if (!strokeActive) return
        strokeActive = false
        if (tool == Tool.BACKSTITCH) {
            val b = backstitchPreview
            backstitchPreview = null
            if (b != null && (b.x1 != b.x2 || b.y1 != b.y2)) {
                pushUndo()
                pattern.backstitches.add(b)
                revision++
            }
        }
    }

    /** Второй палец коснулся экрана — отменяем начатый штрих. */
    fun cancelStroke() {
        if (!strokeActive) return
        strokeActive = false
        when (tool) {
            Tool.BACKSTITCH -> { backstitchPreview = null; return }
            Tool.SELECT -> return
            else -> {}
        }
        undoStack.removeLastOrNull()?.let { pattern = it; revision++ }
        syncCounts()
    }

    private fun applyAt(x: Int, y: Int) {
        val p = pattern
        if (!p.inBounds(x, y)) return
        val i = p.index(x, y)
        fun put(kind: Byte) {
            if (p.cells[i] != currentColor || p.kinds[i] != kind) p.done[i] = false
            p.cells[i] = currentColor; p.kinds[i] = kind
        }
        when (tool) {
            Tool.FULL -> put(StitchKind.FULL)
            Tool.HALF_FWD -> put(StitchKind.HALF_FWD)
            Tool.HALF_BACK -> put(StitchKind.HALF_BACK)
            Tool.QUARTER -> put(StitchKind.quarter(strokeCorner))
            Tool.THREE_QUARTER -> put(StitchKind.threeQuarter(strokeCorner))
            Tool.ERASER -> {
                p.cells[i] = -1; p.kinds[i] = StitchKind.FULL; p.done[i] = false
                p.backstitches.removeAll { b ->
                    val mx = (b.x1 + b.x2) / 2f; val my = (b.y1 + b.y2) / 2f
                    floor(mx).toInt() == x && floor(my).toInt() == y
                }
                p.knots.removeAll { k -> k.hx in 2 * x..2 * x + 2 && k.hy in 2 * y..2 * y + 2 }
            }
            Tool.MARK -> if (p.cells[i] >= 0) p.done[i] = true
            Tool.UNMARK -> p.done[i] = false
            else -> {}
        }
    }

    private fun requireColor(): Boolean {
        if (currentColor in pattern.palette.indices) return true
        message = "Сначала добавьте цвет в палитру (кнопка +)"
        return false
    }

    // ---------------- Выделение, копирование, вставка ----------------

    fun selectAll() {
        commitFloating()
        selection = CellRect(0, 0, pattern.width, pattern.height)
    }

    fun clearSelection() { selection = null }

    fun copySelection() {
        val r = selection ?: return
        clipboard = pattern.extract(r)
        message = "Скопировано ${r.width}×${r.height}"
    }

    fun cutSelection() {
        val r = selection ?: return
        clipboard = pattern.extract(r)
        pushUndo(); pattern.clear(r); revision++
        message = "Вырезано ${r.width}×${r.height}"
    }

    fun deleteSelection() {
        val r = selection ?: return
        pushUndo(); pattern.clear(r); revision++
    }

    /** Поднять выделенный фрагмент, чтобы перетащить его пальцем. */
    fun moveSelection() {
        val r = selection ?: return
        val clip = pattern.extract(r)
        pushUndo(); pattern.clear(r); revision++
        floating = Floating(clip, r.x0, r.y0, fromMove = true)
        selection = null
    }

    fun paste() {
        val clip = clipboard ?: return
        commitFloating()
        val r = selection
        val x = r?.x0 ?: ((pattern.width - clip.width) / 2).coerceAtLeast(0)
        val y = r?.y0 ?: ((pattern.height - clip.height) / 2).coerceAtLeast(0)
        floating = Floating(clip, x, y, fromMove = false)
        selection = null
        tool = Tool.SELECT
        message = "Перетащите фрагмент пальцем и нажмите «Готово»"
    }

    fun commitFloating() {
        val f = floating ?: return
        if (!f.fromMove) pushUndo()
        pattern.stamp(f.clip, f.x, f.y)
        floating = null
        selection = CellRect.of(f.x, f.y, f.x + f.clip.width - 1, f.y + f.clip.height - 1, pattern.width, pattern.height)
            .takeIf { f.x < pattern.width && f.y < pattern.height && f.x + f.clip.width > 0 && f.y + f.clip.height > 0 }
        revision++
    }

    fun cancelFloating() {
        val f = floating ?: return
        floating = null
        if (f.fromMove) undo() // вернуть фрагмент на место
    }

    // ---------------- Палитра ----------------

    fun addFloss(f: Floss) {
        pushUndo()
        currentColor = pattern.addFloss(f)
        revision++
        if (tool == Tool.PAN || tool == Tool.PICKER || tool == Tool.ERASER) tool = Tool.FULL
    }

    fun replaceFloss(index: Int, f: Floss) {
        if (index !in pattern.palette.indices) return
        if (pattern.palette.any { it.floss.key == f.key }) { message = "Эта нитка уже есть в палитре"; return }
        pushUndo()
        pattern.palette[index] = pattern.palette[index].copy(floss = f)
        revision++
    }

    fun removeColor(index: Int) {
        if (index !in pattern.palette.indices) return
        replaceWithUndo(pattern.removeColor(index))
    }

    fun removeUnusedColors() {
        val before = pattern.palette.size
        replaceWithUndo(pattern.removeUnusedColors())
        message = "Удалено цветов: ${before - pattern.palette.size}"
    }

    /** Перевести всю схему на нитки другой фирмы (по таблице соответствия, где она есть, иначе по цвету). */
    fun convertPalette(brand: String) {
        val cat = catalogs.firstOrNull { it.brand == brand } ?: return
        val before = pattern.palette.size
        var byTable = 0
        val converted = pattern.convertPalette(cat) { f ->
            val (g, official) = equivalent(f, cat)
            if (official) byTable++
            g
        }
        replaceWithUndo(converted)
        activeBrand = brand
        val byColor = before - byTable
        message = "Схема переведена на $brand: цветов $before → ${pattern.palette.size}" +
            (if (byTable > 0) ", по таблице $byTable" else "") + (if (byColor > 0) ", по цвету $byColor" else "")
    }

    // ---------------- Палитры ниток ----------------

    private fun reloadCatalogs() {
        val imported = palettesDir.listFiles { f -> f.extension == "csv" }?.sortedBy { it.name }?.mapNotNull { f ->
            runCatching { FlossCatalog.parseCsv(f.readText(), f.nameWithoutExtension) }.getOrNull()?.takeIf { it.flosses.isNotEmpty() }
        } ?: emptyList()
        catalogs = builtInCatalogs + imported.filter { c -> builtInBrands.none { it.equals(c.brand, ignoreCase = true) } }
        if (catalogs.none { it.brand == activeBrand }) activeBrand = "DMC"
    }

    /** Имя файла по Uri (для названия фирмы при импорте палитры). */
    fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Импорт палитры из CSV: код;название;R;G;B (или через запятую). */
    fun importPalette(uri: Uri, brand: String) {
        val clean = brand.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Палитра" }
        if (builtInBrands.any { it.equals(clean, ignoreCase = true) }) { message = "Название $clean занято встроенной палитрой"; return }
        runCatching {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val cat = FlossCatalog.parseCsv(text, clean)
            require(cat.flosses.isNotEmpty()) { "в файле не найдено цветов (нужны столбцы код, название и R, G, B или HEX)" }
            File(palettesDir, "$clean.csv").writeText(text)
            cat.flosses.size
        }.onSuccess { n ->
            reloadCatalogs()
            message = "Палитра $clean: $n цветов"
        }.onFailure { message = "Не удалось импортировать палитру: ${it.message}" }
    }

    fun deletePalette(brand: String) {
        if (brand in builtInBrands) return
        File(palettesDir, "$brand.csv").delete()
        reloadCatalogs()
    }

    // ---------------- Операции над схемой ----------------

    fun newPattern(w: Int, h: Int) {
        pushUndo()
        fileName = null
        currentColor = -1
        setPattern(Pattern(w, h))
    }

    fun mirrorH() = replaceWithUndo(pattern.mirrorHorizontal())
    fun mirrorV() = replaceWithUndo(pattern.mirrorVertical())
    fun rotate() = replaceWithUndo(pattern.rotateClockwise())
    fun resize(w: Int, h: Int) = replaceWithUndo(pattern.resizeCanvas(w, h))
    fun requestFit() { fitRequest++ }

    // ---------------- Файлы ----------------

    fun listSaved(): List<File> =
        patternsDir.listFiles { f -> f.extension == "stp" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun save(name: String) {
        commitFloating()
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Схема" }
        pattern.title = clean
        runCatching { File(patternsDir, "$clean.stp").writeText(PatternIO.write(pattern)) }
            .onSuccess { fileName = clean; message = "Сохранено: $clean"; autosave() }
            .onFailure { message = "Ошибка сохранения: ${it.message}" }
    }

    fun open(file: File) {
        runCatching { PatternIO.read(file.readText()) }
            .onSuccess { pushUndo(); fileName = file.nameWithoutExtension; currentColor = -1; setPattern(it) }
            .onFailure { message = "Не удалось открыть: ${it.message}" }
    }

    fun delete(file: File) { file.delete() }

    fun autosave() {
        runCatching { autosaveFile.writeText("#" + (fileName ?: "") + "\n" + PatternIO.write(pattern)) }
    }

    /** Импорт/экспорт .stp через системный выбор файлов (обмен схемами). */
    fun importStp(uri: Uri) {
        val cr = getApplication<Application>().contentResolver
        runCatching { cr.openInputStream(uri)!!.bufferedReader().use { PatternIO.read(it.readText()) } }
            .onSuccess { pushUndo(); fileName = it.title; currentColor = -1; setPattern(it) }
            .onFailure { message = "Не удалось открыть файл: ${it.message}" }
    }

    fun exportStp(uri: Uri) = writeToUri(uri) { p, out -> out.write(PatternIO.write(p).toByteArray()) }

    fun exportPng(uri: Uri) = writeToUri(uri) { p, out -> Exporter.writePng(p, out) }

    fun exportPdf(uri: Uri) = writeToUri(uri) { p, out -> Exporter.writePdf(p, out) }

    private fun writeToUri(uri: Uri, block: (Pattern, java.io.OutputStream) -> Unit) {
        commitFloating()
        val snapshot = pattern.copy()
        busy = true
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { block(snapshot, it) }
                }
            }
            busy = false
            message = if (res.isSuccess) "Готово: «${snapshot.title}» экспортирована" else "Ошибка экспорта: ${res.exceptionOrNull()?.message}"
        }
    }

    // ---------------- Фото → схема ----------------

    /** Поворот снимка по EXIF (камера телефона часто пишет фото «боком»). */
    private fun orientationDegrees(uri: Uri): Int = runCatching {
        getApplication<Application>().contentResolver.openInputStream(uri)!!.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        }
    }.getOrDefault(0)

    /** Размер картинки (с учётом поворота) без полной загрузки. */
    fun imageSize(uri: Uri): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
        val deg = orientationDegrees(uri)
        if (deg == 90 || deg == 270) opts.outHeight to opts.outWidth else opts.outWidth to opts.outHeight
    }.getOrNull()

    /** Загрузка с уменьшением в sample раз и поворотом по EXIF. */
    private fun decodeOriented(uri: Uri, sample: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = getApplication<Application>().contentResolver.openInputStream(uri)!!
            .use { BitmapFactory.decodeStream(it, null, opts) } ?: error("формат не поддерживается")
        val deg = orientationDegrees(uri)
        if (deg == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }

    /** Превью для окна обрезки (не больше ~1200 px по длинной стороне). */
    fun loadPreview(uri: Uri): ImageBitmap? = runCatching {
        val (iw, ih) = imageSize(uri) ?: return@runCatching null
        var sample = 1
        while (maxOf(iw, ih) / (sample * 2) >= 1200) sample *= 2
        decodeOriented(uri, sample).asImageBitmap()
    }.getOrNull()

    /**
     * crop — область в долях картинки (0..1). Высота схемы считается по пропорциям обрезанной области.
     */
    fun importPhoto(uri: Uri, widthStitches: Int, crop: Rect, opt: PhotoConverter.Options) {
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val (iw, ih) = imageSize(uri) ?: error("не удалось прочитать картинку")
                    val c = normalizeCrop(crop)
                    val cw = c.width * iw; val ch = c.height * ih
                    val w = widthStitches.coerceIn(5, Pattern.MAX_SIZE)
                    val h = (ch.toDouble() * w / cw).roundToInt().coerceIn(1, Pattern.MAX_SIZE)
                    val bmp = decodeScaled(uri, w, h, c, cw, ch)
                    val pixels = IntArray(w * h)
                    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    PhotoConverter.convert(pixels, w, h, activeCatalog, opt)
                }
            }
            busy = false
            result.onSuccess {
                it.title = "Из фото"
                pushUndo(); fileName = null; currentColor = if (it.palette.isEmpty()) -1 else 0
                setPattern(it)
                message = "Схема ${it.width}×${it.height}, цветов: ${it.palette.size}"
            }.onFailure { message = "Ошибка импорта: ${it.message}" }
        }
    }

    private fun normalizeCrop(r: Rect): Rect {
        val l = r.left.coerceIn(0f, 0.99f); val t = r.top.coerceIn(0f, 0.99f)
        return Rect(l, t, r.right.coerceIn(l + 0.01f, 1f), r.bottom.coerceIn(t + 0.01f, 1f))
    }

    /** Загружает, обрезает и плавно уменьшает до w×h (по шагам, чтобы усреднять цвета). */
    private fun decodeScaled(uri: Uri, w: Int, h: Int, crop: Rect, cropW: Float, cropH: Float): Bitmap {
        var sample = 1
        while (cropW / (sample * 2) >= w * 2 && cropH / (sample * 2) >= h * 2) sample *= 2
        var bmp = decodeOriented(uri, sample)
        val isFull = crop.left <= 0f && crop.top <= 0f && crop.right >= 1f && crop.bottom >= 1f
        if (!isFull) {
            val x = (crop.left * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
            val y = (crop.top * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)
            val cw = (crop.width * bmp.width).roundToInt().coerceIn(1, bmp.width - x)
            val ch = (crop.height * bmp.height).roundToInt().coerceIn(1, bmp.height - y)
            val cropped = Bitmap.createBitmap(bmp, x, y, cw, ch)
            if (cropped != bmp) bmp.recycle()
            bmp = cropped
        }
        while (bmp.width / 2 >= w && bmp.height / 2 >= h) {
            val next = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            if (next != bmp) bmp.recycle()
            bmp = next
        }
        val fin = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (fin != bmp) bmp.recycle()
        return if (fin.config == Bitmap.Config.ARGB_8888) fin else fin.copy(Bitmap.Config.ARGB_8888, false)
    }
}
