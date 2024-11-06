// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/Dialogs.kt
package com.example.llama

import android.app.DownloadManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ModelDialog(
    onDismiss: () -> Unit,
    models: List<Downloadable>,
    viewModel: MainViewModel,
    dm: DownloadManager
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select a model to load") },
        text = {
            Column {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(models) { model ->
                        Downloadable.Button(viewModel, dm, model)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SystemPromptDialog(
    onDismiss: () -> Unit,
    currentPrompt: String,
    onUpdatePrompt: (String) -> Unit
) {
    var editingPrompt by remember { mutableStateOf(currentPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("System Prompt Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = editingPrompt,
                    onValueChange = { editingPrompt = it },
                    label = { Text("System Prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    minLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUpdatePrompt(editingPrompt)
                    onDismiss()
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
