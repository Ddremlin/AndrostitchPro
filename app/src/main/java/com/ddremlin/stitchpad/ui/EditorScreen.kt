package com.ddremlin.stitchpad.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddremlin.stitchpad.core.FlossUsage

private sealed interface DialogState {
    data object None : DialogState
    data object New : DialogState
    data object Resize : DialogState
    data object Open : DialogState
    data object Save : DialogState
    data object AddColor : DialogState
    data object Stats : DialogState
    data object Palettes : DialogState
    data class PaletteName(val uri: Uri, val suggested: String) : DialogState
    data class Photo(val uri: Uri, val w: Int, val h: Int, val preview: ImageBitmap) : DialogState
    data class ColorInfo(val index: Int) : DialogState
    data class ReplaceColor(val index: Int) : DialogState
}

@Composable
fun EditorScreen(vm: EditorViewModel) {
    var dialog by remember { mutableStateOf<DialogState>(DialogState.None) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val p = vm.pattern
    val rev = vm.revision

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val size = vm.imageSize(uri)
            val preview = vm.loadPreview(uri)
            if (size == null || preview == null) {
                vm.message = "Не удалось прочитать картинку"
            } else {
                dialog = DialogState.Photo(uri, size.first, size.second, preview)
            }
        }
    }
    val openStp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importStp(uri)
    }
    val openPalette = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = vm.displayName(uri)?.substringBeforeLast('.')?.ifBlank { null } ?: "Моя палитра"
            dialog = DialogState.PaletteName(uri, name)
        }
    }
    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.exportPng(uri)
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.exportPdf(uri)
    }
    val exportStp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportStp(uri)
    }
    val baseName = (vm.fileName ?: p.title).ifBlank { "схема" }

    // прогресс вышивания по цветам (только в режиме «вышиваю»)
    val progress = remember(p, rev, vm.stitchMode) { if (vm.stitchMode) p.progress() else null }
    val totalDone = progress?.sumOf { it[0] } ?: 0
    val totalCells = progress?.sumOf { it[1] } ?: 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.fileName ?: p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
                        val sub = if (vm.stitchMode) {
                            "Вышиваю · готово ${percent(totalDone, totalCells)}% ($totalDone из $totalCells)"
                        } else {
                            "${p.width}×${p.height} · цветов ${p.palette.size}"
                        }
                        Text(sub, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = vm::undo, enabled = vm.undoCount > 0) { Text("↶", fontSize = 22.sp) }
                    TextButton(onClick = vm::redo, enabled = vm.redoCount > 0) { Text("↷", fontSize = 22.sp) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Меню") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val close: (() -> Unit) -> () -> Unit = { action -> { menuOpen = false; action() } }
                            MenuItem(if (vm.stitchMode) "✓ Режим «вышиваю» — выключить" else "Режим «вышиваю»", close { vm.toggleStitchMode() })
                            if (vm.stitchMode) MenuItem("Сбросить все отметки", close { vm.resetProgress() })
                            HorizontalDivider()
                            MenuItem("Новая схема", close { dialog = DialogState.New })
                            MenuItem("Мои схемы…", close { dialog = DialogState.Open })
                            MenuItem("Сохранить", close {
                                val n = vm.fileName
                                if (n != null) {
                                    vm.save(n)
                                } else {
                                    dialog = DialogState.Save
                                }
                            })
                            MenuItem("Сохранить как…", close { dialog = DialogState.Save })
                            HorizontalDivider()
                            MenuItem("Фото → схема…", close { pickPhoto.launch("image/*") })
                            MenuItem("Палитры ниток…", close { dialog = DialogState.Palettes })
                            HorizontalDivider()
                            if (!vm.stitchMode) {
                                MenuItem("Выделить всё", close { vm.selectTool(Tool.SELECT); vm.selectAll() })
                                if (vm.clipboard != null) MenuItem("Вставить", close { vm.paste() })
                                MenuItem("Отразить ↔", close { vm.mirrorH() })
                                MenuItem("Отразить ↕", close { vm.mirrorV() })
                                MenuItem("Повернуть на 90°", close { vm.rotate() })
                                MenuItem("Размер холста…", close { dialog = DialogState.Resize })
                                MenuItem("Удалить неиспользуемые цвета", close { vm.removeUnusedColors() })
                                HorizontalDivider()
                            }
                            MenuItem("Экспорт PDF для печати", close { exportPdf.launch("$baseName.pdf") })
                            MenuItem("Экспорт PNG", close { exportPng.launch("$baseName.png") })
                            MenuItem("Поделиться файлом .stp", close { exportStp.launch("$baseName.stp") })
                            MenuItem("Открыть файл .stp", close { openStp.launch(arrayOf("*/*")) })
                            HorizontalDivider()
                            MenuItem("Размер и расход ниток", close { dialog = DialogState.Stats })
                            MenuItem("Вид: ${vm.viewMode.label} → сменить", close {
                                vm.viewMode = ViewMode.entries[(vm.viewMode.ordinal + 1) % ViewMode.entries.size]
                            })
                            MenuItem("Вписать в экран", close { vm.requestFit() })
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("StitchPad ${vm.appVersion}", style = MaterialTheme.typography.labelSmall) },
                                onClick = {}, enabled = false,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PatternCanvas(vm, Modifier.fillMaxSize())
                if (vm.busy) {
                    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    if (vm.stitchMode) StitchBar(vm, totalDone, totalCells)
                    if (vm.tool == Tool.SELECT) SelectionBar(vm)
                    ToolBar(vm)
                    PaletteBar(
                        vm, progress,
                        onAdd = { dialog = DialogState.AddColor },
                        onLongPress = { dialog = DialogState.ColorInfo(it) },
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        DialogState.None -> {}
        DialogState.New -> SizeDialog("Новая схема", 80, 80, "Создать", { dialog = DialogState.None }) { w, h ->
            vm.newPattern(w, h); dialog = DialogState.None
        }
        DialogState.Resize -> SizeDialog("Размер холста", p.width, p.height, "Применить", { dialog = DialogState.None }) { w, h ->
            vm.resize(w, h); dialog = DialogState.None
        }
        DialogState.Open -> OpenDialog(
            vm.listSaved(),
            onDismiss = { dialog = DialogState.None },
            onOpen = { vm.open(it); dialog = DialogState.None },
            onDelete = { vm.delete(it) },
            onImportFile = { dialog = DialogState.None; openStp.launch(arrayOf("*/*")) },
        )
        DialogState.Save -> SaveDialog(vm.fileName ?: p.title, { dialog = DialogState.None }) {
            vm.save(it); dialog = DialogState.None
        }
        DialogState.AddColor -> FlossPickerDialog(
            vm.catalogs, vm.activeBrand, "Добавить нитку", p.palette.map { it.floss.key }.toSet(),
            onDismiss = { dialog = DialogState.None },
            onPick = { vm.addFloss(it); dialog = DialogState.None },
        )
        DialogState.Stats -> StatsDialog(p) { dialog = DialogState.None }
        DialogState.Palettes -> PalettesDialog(
            vm.catalogs, vm.activeBrand,
            onDismiss = { dialog = DialogState.None },
            onSelect = { vm.activeBrand = it },
            onConvert = { vm.convertPalette(it); dialog = DialogState.None },
            onImport = { openPalette.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
            onDelete = { vm.deletePalette(it) },
        )
        is DialogState.PaletteName -> NameDialog("Название палитры", d.suggested, "Загрузить", { dialog = DialogState.Palettes }) {
            vm.importPalette(d.uri, it); dialog = DialogState.Palettes
        }
        is DialogState.Photo -> PhotoImportDialog(d.preview, d.w, d.h, { dialog = DialogState.None }) { w, crop, opt ->
            vm.importPhoto(d.uri, w, crop, opt); dialog = DialogState.None
        }
        is DialogState.ColorInfo -> {
            val entry = p.palette.getOrNull(d.index)
            if (entry == null) {
                dialog = DialogState.None
            } else {
                val row = remember(d.index, rev) { FlossUsage.calculate(p).getOrNull(d.index) }
                val prog = remember(d.index, rev) { p.progress().getOrNull(d.index) }
                val equivalents = remember(d.index, rev, vm.catalogs) {
                    vm.catalogs.filter { it.brand != entry.floss.brand }.map { it.nearest(entry.floss.rgb) }
                }
                ColorEntryDialog(
                    entry, row?.summary() ?: "", prog, equivalents,
                    onDismiss = { dialog = DialogState.None },
                    onReplace = { dialog = DialogState.ReplaceColor(d.index) },
                    onDelete = { vm.removeColor(d.index); dialog = DialogState.None },
                )
            }
        }
        is DialogState.ReplaceColor -> FlossPickerDialog(
            vm.catalogs, vm.activeBrand, "Заменить на…", p.palette.map { it.floss.key }.toSet(),
            onDismiss = { dialog = DialogState.None },
            onPick = { vm.replaceFloss(d.index, it); dialog = DialogState.None },
        )
    }
}

private fun percent(done: Int, total: Int): Int = if (total == 0) 0 else done * 100 / total

@Composable
private fun StitchBar(vm: EditorViewModel, done: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Готово ${percent(done, total)}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Checkbox(vm.highlightColor, { vm.highlightColor = it })
            Text("Подсвечивать выбранный цвет", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SelectionBar(vm: EditorViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val f = vm.floating
        val sel = vm.selection
        when {
            f != null -> {
                Text("Двигайте фрагмент пальцем", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = vm::commitFloating) { Text("Готово") }
                TextButton(onClick = vm::cancelFloating) { Text("Отмена") }
            }
            sel != null -> {
                Text("${sel.width}×${sel.height}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = vm::copySelection) { Text("Копировать") }
                TextButton(onClick = vm::cutSelection) { Text("Вырезать") }
                TextButton(onClick = vm::moveSelection) { Text("Переместить") }
                TextButton(onClick = vm::deleteSelection) { Text("Удалить") }
                if (vm.clipboard != null) TextButton(onClick = vm::paste) { Text("Вставить") }
                TextButton(onClick = vm::clearSelection) { Text("Снять") }
            }
            else -> {
                Text("Проведите пальцем, чтобы выделить", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = vm::selectAll) { Text("Всё") }
                if (vm.clipboard != null) TextButton(onClick = vm::paste) { Text("Вставить") }
            }
        }
    }
}

@Composable
private fun ToolBar(vm: EditorViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tool.entries.filter { it == Tool.PAN || it.stitching == vm.stitchMode }.forEach { t ->
            val selected = vm.tool == t
            TextButton(
                onClick = { vm.selectTool(t) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    RoundedCornerShape(10.dp),
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.glyph, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(t.label, fontSize = 10.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteBar(vm: EditorViewModel, progress: Array<IntArray>?, onAdd: () -> Unit, onLongPress: (Int) -> Unit) {
    val p = vm.pattern
    // revision читается, чтобы обновлять палитру после замены ниток
    val rev = vm.revision
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!vm.stitchMode) {
            item(key = "add") {
                Box(
                    Modifier.size(44.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, "Добавить цвет") }
            }
        }
        itemsIndexed(p.palette, key = { i, e -> "${e.floss.key}-$i-$rev" }) { i, entry ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Swatch(
                    entry, selected = vm.currentColor == i,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            vm.currentColor = i
                            if (!vm.stitchMode && (vm.tool == Tool.PAN || vm.tool == Tool.PICKER || vm.tool == Tool.ERASER || vm.tool == Tool.SELECT)) {
                                vm.selectTool(Tool.FULL)
                            }
                        },
                        onLongClick = { onLongPress(i) },
                    ),
                )
                val pr = progress?.getOrNull(i)
                Text(
                    if (pr != null) "${percent(pr[0], pr[1])}%" else entry.floss.code,
                    fontSize = 9.sp,
                    fontWeight = if (pr != null && pr[1] > 0 && pr[0] == pr[1]) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        if (p.palette.isEmpty()) {
            item(key = "hint") {
                Text("← добавьте нитку", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(200.dp))
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}
