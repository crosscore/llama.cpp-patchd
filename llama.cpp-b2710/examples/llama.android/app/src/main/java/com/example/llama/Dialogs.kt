// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/Dialogs.kt
package com.example.llama

import android.annotation.SuppressLint
import android.app.DownloadManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.text.*

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

@SuppressLint("DefaultLocale")
@Composable
fun SpeakerManagementDialog(
    onDismiss: () -> Unit,
    viewModel: VoskViewModel,
    currentSpeakerId: String?,
    currentConfidence: Float?,
    onRegisterNewSpeaker: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speaker Management") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // 現在認識中の話者情報表示
                if (currentSpeakerId != null && currentConfidence != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                        ) {
                            Text(
                                "Current Speaker",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("ID: $currentSpeakerId")
                            Text("Confidence: ${String.format("%.2f", currentConfidence)}")
                        }
                    }
                }

                // 登録済み話者リスト
                Text(
                    "Registered Speakers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp)
                ) {
                    items(viewModel.registeredSpeakers) { speakerId ->
                        ListItem(
                            headlineContent = { Text(speakerId) },
                            trailingContent = {
                                IconButton(onClick = {
                                    // TODO: 話者の削除機能を実装
                                }) {
                                    // TODO: 削除アイコンを追加
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onRegisterNewSpeaker) {
                Text("Register New Speaker")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun SpeakerRegistrationDialog(
    onDismiss: () -> Unit,
    viewModel: VoskViewModel,
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    var speakerId by remember { mutableStateOf("") }
    var speakerName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register New Speaker") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = speakerId,
                    onValueChange = { speakerId = it },
                    label = { Text("Speaker ID") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = speakerName,
                    onValueChange = { speakerName = it },
                    label = { Text("Speaker Name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = if (isRecording) onStopRecording else onStartRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (isRecording) "Stop Recording" else "Start Recording")
                    }
                }

                if (isRecording) {
                    Text(
                        "Recording in progress...",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // viewModelを使用して話者登録を実行
                    viewModel.registerSpeaker(
                        speakerId,
                        speakerName,
                        // TODO: 録音データの取得方法を実装
                        ShortArray(0)
                    )
                    onDismiss()
                },
                enabled = !isRecording && speakerId.isNotBlank() && speakerName.isNotBlank()
            ) {
                Text("Register")
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                enabled = !isRecording
            ) {
                Text("Cancel")
            }
        }
    )
}
