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
import kotlinx.coroutines.delay
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
    var recordingDuration by remember { mutableIntStateOf(0) }
    var hasRecordedData by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf<String?>(null) }

    // 録音時間を更新するための LaunchedEffect
    LaunchedEffect(isRecording) {
        recordingDuration = 0
        if (isRecording) {
            while (true) {
                delay(1000) // 1秒ごとに更新
                recordingDuration++
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isRecording) onDismiss()
        },
        title = { Text("話者の登録") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // 入力フィールド
                OutlinedTextField(
                    value = speakerId,
                    onValueChange = { speakerId = it },
                    label = { Text("話者ID") },
                    enabled = !isRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = speakerName,
                    onValueChange = { speakerName = it },
                    label = { Text("話者名") },
                    enabled = !isRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )

                // 録音状態の表示
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
                            "録音状態",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isRecording) {
                            Text(
                                "録音中... ${recordingDuration}秒",
                                color = MaterialTheme.colorScheme.error
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        } else if (hasRecordedData) {
                            Text("録音完了（${recordingDuration}秒）")
                        } else {
                            Text("録音待機中")
                        }
                    }
                }

                // 録音ボタン
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (isRecording) {
                                onStopRecording()
                                hasRecordedData = true
                            } else {
                                if (speakerId.isBlank() || speakerName.isBlank()) {
                                    showError = "話者IDと話者名を入力してください"
                                    return@Button
                                }
                                viewModel.startRecording(VoskViewModel.Companion.RecordingMode.Registration)
                                onStartRecording()
                                hasRecordedData = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (isRecording) "録音停止" else "録音開始")
                    }
                }

                // エラーメッセージ
                showError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (hasRecordedData) {
                        if (viewModel.registerSpeaker(speakerId, speakerName)) {
                            onDismiss()
                        } else {
                            showError = "話者の登録に失敗しました"
                        }
                    } else {
                        showError = "録音データがありません"
                    }
                },
                enabled = !isRecording && hasRecordedData
            ) {
                Text("登録")
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                enabled = !isRecording
            ) {
                Text("キャンセル")
            }
        }
    )
}
