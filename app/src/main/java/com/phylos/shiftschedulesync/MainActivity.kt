package com.phylos.shiftschedulesync

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShiftScheduleApp()
                }
            }
        }
    }
}

private enum class UiStatus {
    IDLE, SAVING, PROCESSING, DONE, ERROR
}

private enum class DayKey {
    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY
}

private data class PersonSchedule(
    val displayName: String,
    val days: Map<DayKey, String>
)

private data class OcrWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

@Composable
private fun ShiftScheduleApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(UiStatus.IDLE) }
    var errorText by remember { mutableStateOf("") }

    val detectedNames = remember { mutableStateListOf<String>() }
    val schedulesMap = remember { mutableStateMapOf<String, PersonSchedule>() }

    var selectedName by remember { mutableStateOf<String?>(null) }
    var selectedImagesCount by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                status = UiStatus.SAVING
                errorText = ""
                try {
                    val saved = withContext(Dispatchers.IO) {
                        uris.forEach { saveOneToDownloads(context.contentResolver, it) }
                        uris.size
                    }
                    selectedImagesCount = saved
                    status = UiStatus.DONE
                } catch (t: Throwable) {
                    errorText = t.message ?: "Unknown save error"
                    status = UiStatus.ERROR
                }
            }
        }
    }

    val selectedSchedule = selectedName?.let { schedulesMap[it] }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ShiftScheduleSync",
            style = MaterialTheme.typography.headlineMedium
        )

        Button(
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            Text(text = stringResource(R.string.upload))
        }

        Text(text = "Imágenes seleccionadas: $selectedImagesCount")

        Button(
            onClick = {
                scope.launch {
                    status = UiStatus.PROCESSING
                    errorText = ""
                    try {
                        val result = withContext(Dispatchers.IO) {
                            processAllSchedules(context)
                        }

                        detectedNames.clear()
                        detectedNames.addAll(result.map { it.displayName }.sorted())

                        schedulesMap.clear()
                        result.forEach { schedulesMap[it.displayName] = it }

                        if (detectedNames.isNotEmpty()) {
                            selectedName = detectedNames.first()
                        }

                        status = UiStatus.DONE
                    } catch (t: Throwable) {
                        errorText = t.message ?: "Unknown processing error"
                        status = UiStatus.ERROR
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            Text(text = stringResource(R.string.process_now))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = when (status) {
                        UiStatus.IDLE -> stringResource(R.string.status_idle)
                        UiStatus.SAVING -> stringResource(R.string.status_saving)
                        UiStatus.PROCESSING -> stringResource(R.string.status_processing)
                        UiStatus.DONE -> stringResource(R.string.status_done)
                        UiStatus.ERROR -> stringResource(R.string.status_error, errorText)
                    }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = selectedName?.let { stringResource(R.string.selected_name, it) }
                        ?: stringResource(R.string.select_name),
                    style = MaterialTheme.typography.titleMedium
                )

                if (detectedNames.isEmpty()) {
                    Text(text = stringResource(R.string.empty_state))
                } else {
                    PersonDropdown(
                        people = detectedNames,
                        selected = selectedName ?: "",
                        onSelect = { selectedName = it }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.schedule_preview),
                    style = MaterialTheme.typography.titleMedium
                )

                if (selectedSchedule == null) {
                    Text(text = stringResource(R.string.no_schedule_for_name))
                } else {
                    SchedulePreviewTable(selectedSchedule)
                }
            }
        }
    }
}

@Composable
private fun PersonDropdown(
    people: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Button(onClick = { expanded = true }) {
            Text(if (selected.isBlank()) stringResource(R.string.select_name) else selected)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            people.forEach { person ->
                DropdownMenuItem(
                    text = { Text(person) },
                    onClick = {
                        onSelect(person)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SchedulePreviewTable(schedule: PersonSchedule) {
    val scroll = rememberScrollState()

    val headers = listOf(
        stringResource(R.string.name),
        stringResource(R.string.monday),
        stringResource(R.string.tuesday),
        stringResource(R.string.wednesday),
        stringResource(R.string.thursday),
        stringResource(R.string.friday),
        stringResource(R.string.saturday),
        stringResource(R.string.sunday)
    )

    val values = listOf(
        schedule.displayName,
        schedule.days[DayKey.MONDAY].orEmpty(),
        schedule.days[DayKey.TUESDAY].orEmpty(),
        schedule.days[DayKey.WEDNESDAY].orEmpty(),
        schedule.days[DayKey.THURSDAY].orEmpty(),
        schedule.days[DayKey.FRIDAY].orEmpty(),
        schedule.days[DayKey.SATURDAY].orEmpty(),
        schedule.days[DayKey.SUNDAY].orEmpty()
    )

    Column(modifier = Modifier.horizontalScroll(scroll)) {
        Row {
            headers.forEach { Cell(text = it, isHeader = true) }
        }
        Row {
            values.forEach { Cell(text = if (it.isBlank()) "-" else it, isHeader = false) }
        }
    }
}

@Composable
private fun Cell(text: String, isHeader: Boolean) {
    Box(
        modifier = Modifier
            .width(120.dp)
            .padding(3.dp)
            .background(
                color = if (isHeader) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text = text)
    }
}

private suspend fun processAllSchedules(context: Context): List<PersonSchedule> {
    val resolver = context.contentResolver
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    val toSyncUris = queryDownloadsFolderUris(resolver, "Download/Schedules/toSync")
    val allSchedules = linkedMapOf<String, PersonSchedule>()

    for (uri in toSyncUris) {
        val bitmap = loadBitmap(resolver, uri) ?: continue
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()

        val words = extractWords(result)
        val schedules = buildSchedulesFromWords(words)

        schedules.forEach { schedule ->
            allSchedules[schedule.displayName] = schedule
        }

        moveDownloadItem(resolver, uri, "Download/Schedules/Synced")
    }

    val finalSchedules = allSchedules.values.sortedBy { it.displayName }
    exportCsv(resolver, finalSchedules)

    return finalSchedules
}

private fun extractWords(result: Text): List<OcrWord> {
    val out = mutableListOf<OcrWord>()
    for (block in result.textBlocks) {
        for (line in block.lines) {
            for (element in line.elements) {
                val box = element.boundingBox ?: continue
                val text = element.text.trim()
                if (text.isNotBlank()) {
                    out += OcrWord(
                        text = text,
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom
                    )
                }
            }
        }
    }
    return out
}

private val bannedNameTokens = setOf(
    "LIB", "LIBRE", "VAC", "VACACIONES", "HSIN", "OFF",
    "OY-3", "LIE", "LUB", "SN", "H", "HS"
)

private fun normalizeMainName(raw: String): String? {
    val cleaned = raw
        .replace("•", "")
        .replace("·", "")
        .replace(",", " ")
        .replace(".", " ")
        .replace("(", " ")
        .replace(")", " ")
        .trim()

    val first = cleaned
        .split(Regex("\\s+"))
        .firstOrNull()
        ?.trim()
        .orEmpty()

    if (first.length < 3) return null
    if (!first.first().isLetter()) return null

    val upper = first.uppercase(Locale.getDefault())
    if (upper in bannedNameTokens) return null
    if (Regex("""^\d+$""").matches(first)) return null
    if (Regex("""^\d{1,2}[:-]\d{1,2}$""").matches(first)) return null

    return first.lowercase(Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun detectNamesFromLeftColumn(words: List<OcrWord>): List<Pair<String, Int>> {
    if (words.isEmpty()) return emptyList()

    val maxX = words.maxOf { it.right }
    val leftLimit = (maxX * 0.28f).toInt()

    val candidates = words
        .filter { it.left < leftLimit }
        .sortedBy { it.top }

    val grouped = mutableListOf<MutableList<OcrWord>>()
    val rowTolerance = 26

    for (word in candidates) {
        val existing = grouped.firstOrNull { group ->
            abs(group.first().centerY - word.centerY) <= rowTolerance
        }
        if (existing != null) {
            existing += word
        } else {
            grouped += mutableListOf(word)
        }
    }

    return grouped.mapNotNull { row ->
        val rowText = row.sortedBy { it.left }.joinToString(" ") { it.text }
        val name = normalizeMainName(rowText) ?: return@mapNotNull null
        name to row.first().centerY
    }.distinctBy { it.first }
}

private fun normalizeShiftText(raw: String): String {
    val text = raw
        .uppercase(Locale.getDefault())
        .replace("—", "-")
        .replace("–", "-")
        .replace("O", "0")

    if (text.contains("LIB") || text == "L" || text.contains("OFF")) return "Libre"
    if (text.contains("VAC")) return "Vac"

    val ranges = Regex("""(\d{1,2})(?::|\.|H)?(\d{2})?\s*-\s*(\d{1,2})(?::|\.|H)?(\d{2})?""")
        .findAll(text)
        .map {
            val sh = it.groupValues[1].padStart(2, '0')
            val sm = it.groupValues[2].ifBlank { "00" }
            val eh = it.groupValues[3].padStart(2, '0')
            val em = it.groupValues[4].ifBlank { "00" }
            "${sh}:${sm}-${eh}:${em}"
        }
        .toList()

    if (ranges.isEmpty()) return ""

    val firstStart = ranges.first().substringBefore("-")
    val lastEnd = ranges.last().substringAfter("-")
    return "$firstStart-$lastEnd"
}

private fun buildSchedulesFromWords(words: List<OcrWord>): List<PersonSchedule> {
    if (words.isEmpty()) return emptyList()

    val nameRows = detectNamesFromLeftColumn(words)
    if (nameRows.isEmpty()) return emptyList()

    val maxX = words.maxOf { it.right }
    val nameColWidth = (maxX * 0.28f).toInt()
    val usableWidth = (maxX - nameColWidth).coerceAtLeast(1)
    val dayWidth = usableWidth / 7f
    val yTolerance = 28

    return nameRows.map { (name, rowY) ->
        val rowWords = words.filter {
            abs(it.centerY - rowY) <= yTolerance && it.left > nameColWidth
        }

        val dayMap = mutableMapOf<DayKey, String>()
        DayKey.entries.forEachIndexed { index, day ->
            val startX = nameColWidth + (index * dayWidth)
            val endX = nameColWidth + ((index + 1) * dayWidth)

            val bucketText = rowWords
                .filter { it.centerX in startX.toInt()..endX.toInt() }
                .sortedBy { it.left }
                .joinToString(" ") { it.text }

            dayMap[day] = normalizeShiftText(bucketText)
        }

        PersonSchedule(
            displayName = name,
            days = dayMap
        )
    }.filter { schedule ->
        schedule.days.values.any { it.isNotBlank() }
    }
}

private fun saveOneToDownloads(resolver: ContentResolver, sourceUri: Uri): Boolean {
    val mime = resolver.getType(sourceUri) ?: "image/jpeg"
    val ext = when {
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        else -> "jpg"
    }

    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName = "schedule_${ts}_${UUID.randomUUID().toString().take(8)}.$ext"

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Schedules/toSync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false

    resolver.openInputStream(sourceUri).use { input ->
        resolver.openOutputStream(itemUri).use { output ->
            if (input == null || output == null) return false
            input.copyTo(output)
            output.flush()
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)
    }

    return true
}

private fun moveDownloadItem(
    resolver: ContentResolver,
    uri: Uri,
    destinationRelativePath: String
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, destinationRelativePath)
        }
        resolver.update(uri, values, null, null)
    }
}

private fun exportCsv(
    resolver: ContentResolver,
    items: List<PersonSchedule>
) {
    if (items.isEmpty()) return

    val fileName = "Horario-Semana-Que-viene.csv"
    val csv = buildString {
        appendLine("Nombre,Lunes,Martes,Miercoles,Jueves,Viernes,Sabado,Domingo")
        items.forEach { item ->
            appendCsvCell(item.displayName); append(',')
            appendCsvCell(item.days[DayKey.MONDAY].orEmpty()); append(',')
            appendCsvCell(item.days[DayKey.TUESDAY].orEmpty()); append(',')
            appendCsvCell(item.days[DayKey.WEDNESDAY].orEmpty()); append(',')
            appendCsvCell(item.days[DayKey.THURSDAY].orEmpty()); append(',')
            appendCsvCell(item.days[DayKey.FRIDAY].orEmpty()); append(',')
            appendCsvCell(item.days[DayKey.SATURDAY].orEmpty()); append(',')
            appendCsvCell(item.days[DayKey.SUNDAY].orEmpty())
            appendLine()
        }
    }

    val existing = queryDownloadByName(resolver, "Download/Schedules", fileName)
    if (existing != null) {
        resolver.delete(existing, null, null)
    }

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Schedules")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
    resolver.openOutputStream(uri).use { out ->
        out?.write(csv.toByteArray())
        out?.flush()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}

private fun StringBuilder.appendCsvCell(value: String) {
    append('"')
    append(value.replace("\"", "\"\""))
    append('"')
}

private fun queryDownloadByName(
    resolver: ContentResolver,
    relativePath: String,
    fileName: String
): Uri? {
    val projection = arrayOf(MediaStore.Downloads._ID)
    val relA = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
    val selection =
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
    val args = arrayOf(relA, fileName)

    resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        null
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
        if (cursor.moveToFirst()) {
            return Uri.withAppendedPath(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cursor.getLong(idCol).toString()
            )
        }
    }
    return null
}

private fun queryDownloadsFolderUris(
    resolver: ContentResolver,
    relativePath: String
): List<Uri> {
    val relA = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
    val relB = relativePath.removeSuffix("/")

    val projection = arrayOf(MediaStore.Downloads._ID)
    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    } else null
    val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(relA, relB) else null

    val out = mutableListOf<Uri>()
    resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        "${MediaStore.MediaColumns.DATE_ADDED} ASC"
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
        while (cursor.moveToNext()) {
            out += Uri.withAppendedPath(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cursor.getLong(idCol).toString()
            )
        }
    }
    return out
}

private fun loadBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(resolver, uri)
        }
    } catch (_: Throwable) {
        null
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
