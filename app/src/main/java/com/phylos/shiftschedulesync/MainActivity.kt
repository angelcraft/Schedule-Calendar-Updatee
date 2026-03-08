package com.phylos.shiftschedulesync

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ScheduleApp()
        }
    }
}

@Composable
fun ScheduleApp() {

    val context = androidx.compose.ui.platform.LocalContext.current

    var images by remember { mutableStateOf(listOf<Uri>()) }
    var extractedText by remember { mutableStateOf("") }
    var people by remember { mutableStateOf(listOf<String>()) }
    var selectedPerson by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf(listOf<List<String>>()) }

    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        images = uris
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Text(
            "ShiftScheduleSync",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { launcher.launch("image/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Subir imágenes del horario")
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text("Imágenes seleccionadas: ${images.size}")

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {

                extractedText = ""

                images.forEach { uri ->

                    val image = InputImage.fromFilePath(context, uri)

                    recognizer.process(image)
                        .addOnSuccessListener {

                            extractedText += it.text + "\n"

                            val lines = extractedText.split("\n")

                            val detectedNames = lines.filter {
                                it.length in 3..20 && it.first().isUpperCase()
                            }

                            people = detectedNames.distinct()

                        }

                }

            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Procesar imágenes (OCR)")
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (people.isNotEmpty()) {

            Text("Selecciona persona")

            PersonDropdown(people, selectedPerson) {
                selectedPerson = it

                val fake = listOf(
                    listOf("Nombre","Lun","Mar","Mie","Jue","Vie","Sab","Dom"),
                    listOf(it,"08-16","08-16","Libre","10-18","08-16","Libre","Libre")
                )

                schedule = fake
            }

        }

        Spacer(modifier = Modifier.height(20.dp))

        if (schedule.isNotEmpty()) {

            Text(
                "Horario de $selectedPerson",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(10.dp))

            ScheduleTable(schedule)

        }

    }
}

@Composable
fun PersonDropdown(
    people: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {

    var expanded by remember { mutableStateOf(false) }

    Box {

        Button(onClick = { expanded = true }) {
            Text(if (selected.isEmpty()) "Elegir persona" else selected)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {

            people.forEach {

                DropdownMenuItem(
                    text = { Text(it) },
                    onClick = {
                        onSelect(it)
                        expanded = false
                    }
                )

            }

        }

    }

}

@Composable
fun ScheduleTable(data: List<List<String>>) {

    LazyColumn {

        items(data) { row ->

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
            ) {

                row.forEach {

                    Text(
                        it,
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                    )

                }

            }

        }

    }

}