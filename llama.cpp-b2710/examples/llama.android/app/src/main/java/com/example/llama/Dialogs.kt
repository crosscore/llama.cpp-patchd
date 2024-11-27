// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/Dialogs.kt
package com.example.llama

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    var selectedSpeakerId by remember { mutableStateOf<String?>(null) }
    val speakerMetadata = remember { mutableStateOf(listOf<SpeakerStorage.SpeakerMetadata>()) }
    var showDeleteConfirmation by remember { mutableStateOf<String?>(null) }

    // メタデータの読み込み
    LaunchedEffect(Unit) {
        val storage = SpeakerStorage.getInstance(viewModel.getApplication())
        speakerMetadata.value = storage.getAllSpeakerMetadata()
    }

    // 削除確認ダイアログ
    showDeleteConfirmation?.let { speakerId ->
        val speaker = speakerMetadata.value.find { it.id == speakerId }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = null },
            title = { Text("話者の削除") },
            text = { Text("「${speaker?.name ?: speakerId}」を削除してもよろしいですか？") },
            confirmButton = {
                Button(
                    onClick = {
                        val storage = SpeakerStorage.getInstance(viewModel.getApplication())
                        if (storage.deleteSpeaker(speakerId)) {
                            // 削除成功時、リストを更新
                            speakerMetadata.value = storage.getAllSpeakerMetadata()
                        }
                        showDeleteConfirmation = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteConfirmation = null }) {
                    Text("キャンセル")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speaker Management") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 現在の認識状態
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
                            "Current Recognition Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Speaker ID: ${currentSpeakerId ?: "Not detected"}")
                        if (currentConfidence != null) {
                            Text("Confidence: ${String.format("%.2f%%", currentConfidence * 100)}")
                        }
                    }
                }

                // 登録済み話者一覧
                Text(
                    "Registered Speakers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // 登録済み話者の詳細表示
                speakerMetadata.value.forEach { metadata ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                selectedSpeakerId = if (selectedSpeakerId == metadata.id) null else metadata.id
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        metadata.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "ID: ${metadata.id}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        showDeleteConfirmation = metadata.id
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete speaker"
                                    )
                                }
                            }

                            // 選択された話者の詳細情報
                            if (selectedSpeakerId == metadata.id) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Registration Date: ${
                                        SimpleDateFormat(
                                            "yyyy-MM-dd HH:mm:ss",
                                            Locale.getDefault()
                                        ).format(metadata.registrationDate)
                                    }",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Sample File: ${metadata.samplePath}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Embedding File: ${metadata.embeddingPath}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // デバッグ情報
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            "Debug Information",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Base Directory: ${viewModel.getApplication().getExternalFilesDir(null)?.absolutePath}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Speaker Data Directory: ${
                                viewModel.getApplication().getExternalFilesDir(null)?.let {
                                    File(it, "speaker_data").absolutePath
                                }
                            }",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Recording State: ${if (viewModel.isRecording) "Recording" else "Stopped"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Registration State: ${viewModel.registrationState}",
                            style = MaterialTheme.typography.bodySmall
                        )
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
    var finalRecordingDuration by remember { mutableIntStateOf(0) }
    var showError by remember { mutableStateOf<String?>(null) }
    val recordingPath by remember { mutableStateOf<String?>(null) }

    // 録音時間を更新
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0
            while (true) {
                delay(1000)
                recordingDuration++
            }
        } else {
            // 録音停止時に最終時間を保存
            if (recordingDuration > 0) {
                finalRecordingDuration = recordingDuration
            }
        }
    }

    // 登録状態の監視
    val registrationState = viewModel.registrationState
    LaunchedEffect(registrationState) {
        when (registrationState) {
            is VoskViewModel.RegistrationState.Success -> {
                onDismiss()
            }
            is VoskViewModel.RegistrationState.Error -> {
                showError = registrationState.message
            }
            else -> {}
        }
    }

    val canRegister = !isRecording && recordingDuration > 0 && speakerId.isNotBlank() && speakerName.isNotBlank()

    AlertDialog(
        onDismissRequest = {
            if (!isRecording) onDismiss()
        },
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
                    enabled = !isRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = speakerName,
                    onValueChange = { speakerName = it },
                    label = { Text("Speaker Name") },
                    enabled = !isRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )

                // 録音状態表示
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
                            "Recording Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        when {
                            isRecording -> {
                                Text(
                                    "Recording in progress... ${recordingDuration}s",
                                    color = MaterialTheme.colorScheme.error
                                )
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                )
                            }
                            registrationState is VoskViewModel.RegistrationState.Processing -> {
                                Text("Processing...")
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                )
                            }
                            else -> {
                                Column {
                                    Text(
                                        if (recordingDuration > 0)
                                            "Recording completed (${recordingDuration}s)"
                                        else
                                            "Waiting to start"
                                    )

                                    // デバッグ情報
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Debug Info:",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "- Recording Status: Stopped",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "- Duration: ${recordingDuration}s",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "- Speaker ID: ${speakerId.ifBlank { "Empty" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "- Speaker Name: ${speakerName.ifBlank { "Empty" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "- Registration State: $registrationState",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // デバッグボタン
                        if (recordingDuration > 0 && !isRecording) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    viewModel.debugSaveAudioData(speakerId)
                                },
                                enabled = speakerId.isNotBlank()
                            ) {
                                Text("Save Debug Recording")
                            }
                        }

                        // 録音パスの表示（デバッグ用）
                        recordingPath?.let { path ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Recording Path: $path",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // エラーメッセージ
                showError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            // ボタン活性化条件のデバッグ出力
            LaunchedEffect(isRecording, recordingDuration, finalRecordingDuration, speakerId, speakerName) {
                Log.d("SpeakerRegistration", """
                    Register button state:
                    - isRecording: $isRecording
                    - recordingDuration: $recordingDuration
                    - finalRecordingDuration: $finalRecordingDuration
                    - speakerId: ${speakerId.isNotBlank()}
                    - speakerName: ${speakerName.isNotBlank()}
                    - canRegister: $canRegister
                    """.trimIndent())
            }

            Button(
                onClick = {
                    if (speakerId.isBlank() || speakerName.isBlank()) {
                        showError = "Please enter both Speaker ID and Name"
                        return@Button
                    }
                    if (recordingDuration == 0) {
                        showError = "Please record a voice sample first"
                        return@Button
                    }
                    viewModel.registerSpeaker(speakerId, speakerName)
                },
                enabled = canRegister, // 単純化した条件
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Register")
            }
        },
        dismissButton = {
            Row {
                Button(
                    onClick = {
                        if (isRecording) {
                            onStopRecording()
                        } else {
                            viewModel.startRegistrationRecording()
                            onStartRecording()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isRecording) "Stop" else "Record")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onDismiss,
                    enabled = !isRecording
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun ConversationHistoryDialog(
    onDismiss: () -> Unit,
    viewModel: VoskViewModel,
    clipboard: ClipboardManager
) {
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf<String?>(null) }
    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())

    LaunchedEffect(Unit) {
        viewModel.loadAllSessions()
    }

    // 削除確認ダイアログ
    showDeleteConfirmation?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = null },
            title = { Text("セッションの削除") },
            text = { Text("このセッションを削除してもよろしいですか？") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSession(sessionId)
                        showDeleteConfirmation = null
                        if (selectedSessionId == sessionId) {
                            selectedSessionId = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteConfirmation = null }) {
                    Text("キャンセル")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会話履歴") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                if (selectedSessionId == null) {
                    // セッション一覧表示
                    Text(
                        text = "セッション一覧",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        items(viewModel.allSessions) { session ->
                            val sessionTime = dateFormat.format(Date(session.timestamp))
                            val isCurrentSession = viewModel.isCurrentSession(session.sessionId)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedSessionId = session.sessionId }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "セッション: $sessionTime",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = "会話数: ${session.entries.size}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (isCurrentSession) {
                                            Text(
                                                text = "現在のセッション",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    if (!isCurrentSession) {
                                        IconButton(
                                            onClick = { showDeleteConfirmation = session.sessionId }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "セッションを削除"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 選択されたセッションの会話履歴表示
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedSessionId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                        }
                        Text(
                            text = "セッション詳細",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val session = viewModel.allSessions.find { it.sessionId == selectedSessionId }
                    session?.let { currentSession ->
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            items(currentSession.entries) { entry ->
                                val messageTime = dateFormat.format(Date(entry.timestamp))
                                Text(
                                    text = "${entry.speakerName}: ${entry.message}\n$messageTime",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }

                        // コピーボタン
                        if (currentSession.entries.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    val text = currentSession.entries.joinToString("\n") { entry ->
                                        "${entry.speakerName}: ${entry.message} (${dateFormat.format(Date(entry.timestamp))})"
                                    }
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("Conversation History", text)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Text("このセッションをコピー")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}
