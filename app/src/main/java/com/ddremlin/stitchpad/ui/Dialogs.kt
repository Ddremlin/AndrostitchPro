package com.ddremlin.stitchpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import com.ddremlin.stitchpad.core.ColorMath
import com.ddremlin.stitchpad.core.Floss
import com.ddremlin.stitchpad.core.FlossCatalog
import com.ddremlin.stitchpad.core.FlossUsage
import com.ddremlin.stitchpad.core.PaletteEntry
import com.ddremlin.stitchpad.core.Pattern
import com.ddremlin.stitchpad.core.PhotoConverter
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SizeDialog(
    title: String, initialW: Int, initialH: Int, confirm: String,
    onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit,
) {
    var w by remember { mutableStateOf(initialW.toString()) }
    var h by remember { mutableStateOf(initialH.toString()) }
    val wi = w.toIntOrNull(); val hi = h.toIntOrNull()
    val valid = wi != null && hi != null && wi in 1..Pattern.MAX_SIZE && hi in 1..Pattern.MAX_SIZE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, label = { Text("Ширина, крестиков") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, label = { Text("Высота, крестиков") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                if (wi != null && hi != null) {
                    Text(
                        String.format(Locale.US, "На Aida 14: %.1f × %.1f см", FlossUsage.sizeCm(wi, 14), FlossUsage.sizeCm(hi, 14)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(wi!!, hi!!) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun SaveDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить схему") },
        text = { OutlinedTextField(name, { name = it.take(60) }, label = { Text("Название") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun OpenDialog(
    files: List<File>, onDismiss: () -> Unit, onOpen: (File) -> Unit,
    onDelete: (File) -> Unit, onImportFile: () -> Unit,
) {
    var list by remember { mutableStateOf(files) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Мои схемы") },
        text = {
            if (list.isEmpty()) Text("Сохранённых схем пока нет.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { it.absolutePath }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(f) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.nameWithoutExtension, fontWeight = FontWeight.Medium)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.lastModified())),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { confirmDelete = f }) { Icon(Icons.Default.Delete, "Удалить") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onImportFile) { Text("Открыть файл…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить «${f.nameWithoutExtension}»?") },
            text = { Text("Схема будет удалена с устройства.") },
            confirmButton = {
                TextButton(onClick = { onDelete(f); list = list - f; confirmDelete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
fun PhotoImportDialog(
    preview: ImageBitmap, imageW: Int, imageH: Int, onDismiss: () -> Unit,
    onConfirm: (widthStitches: Int, crop: Rect, PhotoConverter.Options) -> Unit,
) {
    var aspect by remember { mutableStateOf(CropAspect.FREE) }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var width by remember { mutableFloatStateOf(100f) }
    var colors by remember { mutableFloatStateOf(20f) }
    var dither by remember { mutableStateOf(false) }
    var skipWhite by remember { mutableStateOf(false) }

    val cropW = (crop.width * imageW).coerceAtLeast(1f)
    val cropH = (crop.height * imageH).coerceAtLeast(1f)
    val maxW = cropW.roundToInt().coerceIn(21, 400)
    val w = width.roundToInt().coerceIn(20, maxW)
    val h = (cropH.toDouble() * w / cropW).roundToInt().coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.95f),
        title = { Text("Фото → схема") },
        text = {
            Column {
                // Окно обрезки вне прокрутки, чтобы жесты не перехватывались
                CropEditor(preview, crop, aspect.ratio, onCropChange = { crop = it })
                Text(
                    "Проведите по фото пальцем, чтобы выделить область. Тяните за уголки или стороны, внутри рамки — перемещение.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.size(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CropAspect.entries.forEachIndexed { i, a ->
                        SegmentedButton(
                            selected = aspect == a,
                            onClick = { aspect = a; crop = defaultCrop(a.ratio, imageW, imageH) },
                            shape = SegmentedButtonDefaults.itemShape(i, CropAspect.entries.size),
                        ) { Text(a.label, fontSize = 12.sp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Обрезка: ${cropW.roundToInt()}×${cropH.roundToInt()} px",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { aspect = CropAspect.FREE; crop = Rect(0f, 0f, 1f, 1f) }) { Text("Сбросить") }
                }
                Text("Ширина: $w крестиков (высота $h)")
                Slider(w.toFloat(), { width = it }, valueRange = 20f..maxW.toFloat())
                Text(
                    String.format(Locale.US, "Размер на Aida 14: %.1f × %.1f см", FlossUsage.sizeCm(w, 14), FlossUsage.sizeCm(h, 14)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(12.dp))
                Text("Максимум цветов: ${colors.roundToInt()}")
                Slider(colors, { colors = it }, valueRange = 2f..80f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dither, { dither = it }); Text("Сглаживание переходов (дизеринг)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(skipWhite, { skipWhite = it }); Text("Белый фон не вышивать")
                }
                Text(
                    "Совет: для портретов 30–50 цветов, для мультяшных картинок 8–15 без сглаживания.",
                    style = MaterialTheme.typography.bodySmall,
                )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(w, crop, PhotoConverter.Options(maxColors = colors.roundToInt(), dither = dither, skipWhiteBackground = skipWhite))
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun FlossPickerDialog(
    catalog: FlossCatalog, title: String, used: Set<String>,
    onDismiss: () -> Unit, onPick: (Floss) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { catalog.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Номер DMC или название (англ.)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(results, key = { it.code }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(32.dp).background(Color(f.argb), RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0x33000000), RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("DMC ${f.code}", fontWeight = FontWeight.Medium)
                                Text(f.name, style = MaterialTheme.typography.bodySmall)
                            }
                            if (f.code in used) Text("в палитре", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
fun ColorEntryDialog(
    entry: PaletteEntry, full: Int, half: Int,
    onDismiss: () -> Unit, onReplace: () -> Unit, onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DMC ${entry.floss.code}") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(entry, selected = false, size = 56)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(entry.floss.name)
                    Text("Символ: ${entry.symbol}")
                    Text("Крестов: $full, полукрестов: $half", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onReplace) { Text("Заменить нитку") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Удалить цвет") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}

@Composable
fun StatsDialog(p: Pattern, onDismiss: () -> Unit) {
    var count by remember { mutableIntStateOf(14) }
    val counts = listOf(11, 14, 16, 18)
    val rows = remember(p, count) { FlossUsage.calculate(p, count, 2) }
    val totalFull = rows.sumOf { it.full }
    val totalHalf = rows.sumOf { it.half }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Размер и расход ниток") },
        text = {
            Column {
                Text("${p.width} × ${p.height} клеток, крестов $totalFull, полукрестов $totalHalf")
                Spacer(Modifier.size(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    counts.forEachIndexed { i, c ->
                        SegmentedButton(
                            selected = count == c, onClick = { count = c },
                            shape = SegmentedButtonDefaults.itemShape(i, counts.size),
                        ) { Text("Aida $c") }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    String.format(
                        Locale.US, "Вышивка: %.1f × %.1f см. Канва с запасом 5 см с каждой стороны: %.0f × %.0f см",
                        FlossUsage.sizeCm(p.width, count), FlossUsage.sizeCm(p.height, count),
                        FlossUsage.sizeCm(p.width, count) + 10, FlossUsage.sizeCm(p.height, count) + 10,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(rows) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Swatch(r.entry, selected = false, size = 30)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("DMC ${r.entry.floss.code} · ${r.entry.floss.name}", style = MaterialTheme.typography.bodyMedium)
                                Text("крестов ${r.full}, полукрестов ${r.half}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(String.format(Locale.US, "%.2f", r.skeins), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Text("${r.skeinsToBuy} мот.", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("Расчёт примерный: 2 сложения, моток 8 м, запас 20%.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun Swatch(entry: PaletteEntry, selected: Boolean, size: Int = 44, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .size(size.dp)
            .background(Color(entry.floss.argb), shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            entry.symbol.toString(),
            color = Color((0xFF shl 24) or ColorMath.contrastOn(entry.floss.rgb)),
            fontSize = (size * 0.42f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
