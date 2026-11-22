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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface DialogState {
    data object None : DialogState
    data object New : DialogState
    data object Resize : DialogState
    data object Open : DialogState
    data object Save : DialogState
    data object AddColor : DialogState
    data object Stats : DialogState
    data class Photo(val uri: Uri, val w: Int, val h: Int) : DialogState
    data class ColorInfo(val index: Int) : DialogState
    data class ReplaceColor(val index: Int) : DialogState
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    var dialog by remember { mutableStateOf<DialogState>(DialogState.None) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val p = vm.pattern

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val size = vm.imageSize(uri)
            if (size == null) vm.message = "Не удалось прочитать картинку"
            else dialog = DialogState.Photo(uri, size.first, size.second)
        }
    }
    val openStp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importStp(uri)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.fileName ?: p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
                        Text("${p.width}×${p.height} · цветов ${p.palette.size}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = vm::undo, enabled = vm.undoCount > 0) { Text("↶", fontSize = 22.sp) }
                    TextButton(onClick = vm::redo, enabled = vm.redoCount > 0) { Text("↷", fontSize = 22.sp) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Меню") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val close: (() -> Unit) -> () -> Unit = { action -> { menuOpen = false; action() } }
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
                            HorizontalDivider()
                            MenuItem("Экспорт PDF для печати", close { exportPdf.launch("$baseName.pdf") })
                            MenuItem("Экспорт PNG", close { exportPng.launch("$baseName.png") })
                            MenuItem("Поделиться файлом .stp", close { exportStp.launch("$baseName.stp") })
                            MenuItem("Открыть файл .stp", close { openStp.launch(arrayOf("*/*")) })
                            HorizontalDivider()
                            MenuItem("Отразить ↔", close { vm.mirrorH() })
                            MenuItem("Отразить ↕", close { vm.mirrorV() })
                            MenuItem("Повернуть на 90°", close { vm.rotate() })
                            MenuItem("Размер холста…", close { dialog = DialogState.Resize })
                            MenuItem("Удалить неиспользуемые цвета", close { vm.removeUnusedColors() })
                            HorizontalDivider()
                            MenuItem("Размер и расход ниток", close { dialog = DialogState.Stats })
                            MenuItem("Вид: ${vm.viewMode.label} → сменить", close {
                                vm.viewMode = ViewMode.entries[(vm.viewMode.ordinal + 1) % ViewMode.entries.size]
                            })
                            MenuItem("Вписать в экран", close { vm.requestFit() })
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
                    ToolBar(vm)
                    PaletteBar(
                        vm,
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
            vm.catalog, "Добавить нитку", p.palette.map { it.floss.code }.toSet(),
            onDismiss = { dialog = DialogState.None },
            onPick = { vm.addFloss(it); dialog = DialogState.None },
        )
        DialogState.Stats -> StatsDialog(p) { dialog = DialogState.None }
        is DialogState.Photo -> PhotoImportDialog(d.w, d.h, { dialog = DialogState.None }) { w, opt ->
            vm.importPhoto(d.uri, w, opt); dialog = DialogState.None
        }
        is DialogState.ColorInfo -> {
            val entry = p.palette.getOrNull(d.index)
            if (entry == null) dialog = DialogState.None
            else {
                val counts = remember(d.index, vm.revision) { p.stitchCounts() }
                ColorEntryDialog(
                    entry, counts[d.index][0], counts[d.index][1],
                    onDismiss = { dialog = DialogState.None },
                    onReplace = { dialog = DialogState.ReplaceColor(d.index) },
                    onDelete = { vm.removeColor(d.index); dialog = DialogState.None },
                )
            }
        }
        is DialogState.ReplaceColor -> FlossPickerDialog(
            vm.catalog, "Заменить на…", p.palette.map { it.floss.code }.toSet(),
            onDismiss = { dialog = DialogState.None },
            onPick = { vm.replaceFloss(d.index, it); dialog = DialogState.None },
        )
    }
}

@Composable
private fun ToolBar(vm: EditorViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tool.entries.forEach { t ->
            val selected = vm.tool == t
            TextButton(
                onClick = { vm.tool = t },
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
private fun PaletteBar(vm: EditorViewModel, onAdd: () -> Unit, onLongPress: (Int) -> Unit) {
    val p = vm.pattern
    // revision читается, чтобы обновлять палитру после замены ниток
    val rev = vm.revision
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "add") {
            Box(
                Modifier.size(44.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, "Добавить цвет") }
        }
        itemsIndexed(p.palette, key = { i, e -> "${e.floss.code}-$i-$rev" }) { i, entry ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Swatch(
                    entry, selected = vm.currentColor == i,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            vm.currentColor = i
                            if (vm.tool == Tool.PAN || vm.tool == Tool.PICKER || vm.tool == Tool.ERASER) vm.tool = Tool.FULL
                        },
                        onLongClick = { onLongPress(i) },
                    ),
                )
                Text(entry.floss.code, fontSize = 9.sp)
            }
        }
        if (p.palette.isEmpty()) {
            item(key = "hint") {
                Text("← добавьте нитку DMC", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(200.dp))
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}
