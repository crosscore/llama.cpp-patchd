// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/com.example.llama.EncryptionDialog.kt
package com.example.llama

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

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
                    Button(onClick = { onEncrypt(model) }) {
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
