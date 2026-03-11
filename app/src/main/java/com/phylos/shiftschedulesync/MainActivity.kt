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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

private enum class UiStatus { IDLE, SAVING, PROCESSING, DONE, ERROR }
enum class DayKey { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

data class PersonSchedule(
    val displayName: String,
    val days: Map<DayKey, String>
)

@Composable
private fun ShiftScheduleApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(UiStatus.IDLE) }
    var errorText by remember { mutableStateOf("") }
    var selectedImagesCount by remember { mutableStateOf(0) }

    val schedulesMap = remember { mutableStateMapOf<String, PersonSchedule>() }
    val detectedNames = remember { mutableStateListOf<String>() }
    var selectedName by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                try {
                    status = UiStatus.SAVING
                    errorText = ""
                    withContext(Dispatchers.IO) {
                        uris.forEach { saveOneToDownloads(context.contentResolver, it) }
                    }
                    selectedImagesCount = uris.size
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
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ShiftScheduleSync", style = MaterialTheme.typography.headlineMedium)

        Button(
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.fillMaxWidth().height(72.dp)
        ) {
            Text(stringResource(R.string.upload))
        }

        Text(stringResource(R.string.images_selected, selectedImagesCount))

        Button(
            onClick = {
                scope.launch {
                    try {
                        status = UiStatus.PROCESSING
                        errorText = ""
                        val result = withContext(Dispatchers.IO) { processAllSchedules(context) }
                        schedulesMap.clear()
                        detectedNames.clear()
                        result.forEach {
                            schedulesMap[it.displayName] = it
                            detectedNames += it.displayName
                        }
                        selectedName = detectedNames.firstOrNull()
                        status = UiStatus.DONE
                    } catch (t: Throwable) {
                        errorText = t.message ?: "Unknown process error"
                        status = UiStatus.ERROR
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(72.dp)
        ) {
            Text(stringResource(R.string.process))
        }

        Card(Modifier.fillMaxWidth()) {
            Box(Modifier.padding(12.dp)) {
                Text(
                    when (status) {
                        UiStatus.IDLE -> stringResource(R.string.status_opencv_ready)
                        UiStatus.SAVING -> stringResource(R.string.status_saving)
                        UiStatus.PROCESSING -> stringResource(R.string.status_processing)
                        UiStatus.DONE -> stringResource(R.string.status_done)
                        UiStatus.ERROR -> stringResource(R.string.status_error, errorText)
                    }
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = selectedName?.let { stringResource(R.string.selected_name, it) }
                        ?: stringResource(R.string.select_name),
                    style = MaterialTheme.typography.titleMedium
                )

                if (detectedNames.isEmpty()) {
                    Text(stringResource(R.string.empty_state))
                } else {
                    PersonDropdown(detectedNames, selectedName ?: "") { selectedName = it }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.schedule_preview), style = MaterialTheme.typography.titleMedium)
                if (selectedSchedule == null) {
                    Text(stringResource(R.string.no_schedule_for_name))
                } else {
                    SchedulePreviewTable(selectedSchedule)
                }
            }
        }
    }
}

@Composable
private fun PersonDropdown(people: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) {
            Text(if (selected.isBlank()) stringResource(R.string.select_name) else selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
    val scroll = rememberScrollState()

    Column(Modifier.horizontalScroll(scroll)) {
        Row { headers.forEach { Cell(it, true) } }
        Row { values.forEach { Cell(if (it.isBlank()) "-" else it, false) } }
    }
}

@Composable
private fun Cell(text: String, isHeader: Boolean) {
    Box(
        modifier = Modifier
            .width(120.dp)
            .padding(3.dp)
            .background(
                if (isHeader) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .padding(10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text)
    }
}

private suspend fun processAllSchedules(context: Context): List<PersonSchedule> {
    OpenCvScheduleEngine.ensureInitialized()

    val resolver = context.contentResolver
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val uris = queryDownloadsFolderUris(resolver, "Download/Schedules/toSync")
    val all = linkedMapOf<String, PersonSchedule>()

    for (uri in uris) {
        val rawBitmap = loadBitmap(resolver, uri) ?: continue
        val tableBitmap = OpenCvScheduleEngine.preprocessSchedule(rawBitmap)

        val cellMatrix = OpenCvScheduleEngine.extractGrid(tableBitmap)
        if (cellMatrix.rows.size > 1) {
            for (rowIndex in 1 until cellMatrix.rows.size) {
                val row = cellMatrix.rows[rowIndex]
                if (row.isEmpty()) continue

                val nameText = recognizeCell(recognizer, row.first())
                val personName = normalizeMainName(nameText) ?: continue

                val dayMap = mutableMapOf<DayKey, String>()
                val dayCells = row.drop(1).take(7)

                DayKey.entries.forEachIndexed { index, day ->
                    val cellBmp = dayCells.getOrNull(index)
                    val text = if (cellBmp != null) recognizeCell(recognizer, cellBmp) else ""
                    dayMap[day] = normalizeShiftText(text)
                }

                val schedule = PersonSchedule(personName, dayMap)
                if (schedule.days.values.any { it.isNotBlank() }) {
                    all[personName] = schedule
                }
            }
        }

        moveDownloadItem(resolver, uri, "Download/Schedules/Synced")
    }

    val result = all.values.sortedBy { it.displayName }
    exportCsv(resolver, result)
    return result
}

private suspend fun recognizeCell(
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    bitmap: Bitmap
): String {
    val image = InputImage.fromBitmap(bitmap, 0)
    return recognizer.process(image).await().text
}

private fun normalizeMainName(raw: String): String? {
    val cleaned = raw
        .replace("•", "")
        .replace("·", "")
        .replace(Regex("[()\\[\\],.]"), " ")
        .trim()

    val first = cleaned.split(Regex("\\s+")).firstOrNull().orEmpty()
    if (first.length < 3) return null
    if (!first.first().isLetter()) return null

    val banned = setOf("LIB", "LIBRE", "VAC", "VACACIONES", "HSIN", "OFF", "L")
    val upper = first.uppercase(Locale.getDefault())
    if (upper in banned) return null
    if (Regex("^\\d+$").matches(first)) return null
    if (Regex("^\\d{1,2}[:-]\\d{1,2}$").matches(first)) return null

    return first.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun normalizeShiftText(raw: String): String {
    val text = raw
        .uppercase(Locale.getDefault())
        .replace("—", "-")
        .replace("–", "-")
        .replace("O", "0")
        .replace(Regex("\\bSN\\b"), "")
        .trim()

    if (text.contains("LIB") || text == "L" || text.contains("OFF")) return "Libre"
    if (text.contains("VAC")) return "Vac"
    if (text.contains("HSIN")) return "HSIN"

    val ranges = Regex("(\\d{1,2})(?::|\\.)?(\\d{2})?\\s*-\\s*(\\d{1,2})(?::|\\.)?(\\d{2})?")
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

private fun moveDownloadItem(resolver: ContentResolver, uri: Uri, destinationRelativePath: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, destinationRelativePath)
        }
        resolver.update(uri, values, null, null)
    }
}

private fun exportCsv(resolver: ContentResolver, items: List<PersonSchedule>) {
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
    if (existing != null) resolver.delete(existing, null, null)

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

private fun queryDownloadByName(resolver: ContentResolver, relativePath: String, fileName: String): Uri? {
    val projection = arrayOf(MediaStore.Downloads._ID)
    val relA = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
    val selection =
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
    val args = arrayOf(relA, fileName)

    resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
        if (cursor.moveToFirst()) {
            return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol).toString())
        }
    }
    return null
}

private fun queryDownloadsFolderUris(resolver: ContentResolver, relativePath: String): List<Uri> {
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
            out += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol).toString())
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
