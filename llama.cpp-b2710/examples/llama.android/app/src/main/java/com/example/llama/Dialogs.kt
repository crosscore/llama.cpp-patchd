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
fun ModelActionDialog(
    onDismiss: () -> Unit,
    title: String,
    models: List<Downloadable>,
    progressMap: Map<String, Float>,
    onAction: (Downloadable) -> Unit,
    filter: (Downloadable) -> Boolean = { true },
    additionalContent: @Composable ColumnScope.() -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                additionalContent()
                Spacer(modifier = Modifier.height(8.dp))
                models.filter(filter).forEach { model ->
                    val progress = progressMap[model.name] ?: 0f
                    val buttonText = if (progress > 0f && progress < 1f) {
                        "${model.name} (${(progress * 100).toInt()}%)"
                    } else {
                        model.name
                    }
                    Button(
                        onClick = { onAction(model) },
                        enabled = progress == 0f || progress >= 1f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(buttonText)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun SplitDialog(
    onDismiss: () -> Unit,
    models: List<Downloadable>,
    onSplit: (Downloadable, Long) -> Unit,
    viewModel: MainViewModel
) {
    var partSize by remember { mutableStateOf("524288000") } // Default 500MB

    ModelActionDialog(
        onDismiss = onDismiss,
        title = "Select a model to split",
        models = models,
        progressMap = viewModel.splitProgress,
        onAction = { model ->
            onSplit(model, partSize.toLongOrNull() ?: 52428800L)
        },
        additionalContent = {
            OutlinedTextField(
                value = partSize,
                onValueChange = { partSize = it },
                label = { Text("Part Size (bytes)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
fun MergeDialog(
    onDismiss: () -> Unit,
    models: List<Downloadable>,
    onMerge: (List<Downloadable>, String) -> Unit,
    viewModel: MainViewModel
) {
    var secretKey by remember { mutableStateOf("") }

    ModelActionDialog(
        onDismiss = onDismiss,
        title = "Select model parts to merge",
        models = models,
        progressMap = viewModel.mergeProgress,
        onAction = { model ->
            val parts = models.filter { it.file.name.startsWith(model.file.name.substringBefore(".part")) && it.file.name.contains(".part") }
            onMerge(parts, secretKey)
        },
        filter = { it.file.name.contains(".part") },
        additionalContent = {
            OutlinedTextField(
                value = secretKey,
                onValueChange = { secretKey = it },
                label = { Text("Secret Key") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
fun EncryptionDialog(
    onDismiss: () -> Unit,
    models: List<Downloadable>,
    onEncrypt: (Downloadable) -> Unit,
    viewModel: MainViewModel
) {
    ModelActionDialog(
        onDismiss = onDismiss,
        title = "Select a model to encrypt",
        models = models,
        progressMap = viewModel.encryptionProgress,
        onAction = onEncrypt
    )
}

@Composable
fun DecryptionDialog(
    onDismiss: () -> Unit,
    models: List<Downloadable>,
    onDecrypt: (Downloadable) -> Unit,
    viewModel: MainViewModel
) {
    ModelActionDialog(
        onDismiss = onDismiss,
        title = "Select a model to decrypt",
        models = models,
        progressMap = viewModel.decryptionProgress,
        onAction = onDecrypt,
        filter = { it.file.extension == "enc" }
    )
}

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
