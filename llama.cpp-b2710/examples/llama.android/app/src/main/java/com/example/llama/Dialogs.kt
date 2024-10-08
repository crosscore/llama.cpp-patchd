// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/Dialogs.kt
package com.example.llama

import android.app.DownloadManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EncryptionDialog(
    onDismiss: () -> Unit,
    models: List<Downloadable>,
    onEncrypt: (Downloadable) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select a model to encrypt") },
        text = {
            Column {
                models.forEach { model ->
                    Button(
                        onClick = { onEncrypt(model) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(model.name)
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
