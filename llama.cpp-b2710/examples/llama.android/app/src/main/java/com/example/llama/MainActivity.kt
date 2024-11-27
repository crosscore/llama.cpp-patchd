// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/MainActivity.kt
package com.example.llama

import android.Manifest
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.text.format.Formatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.llama.ui.theme.LlamaAndroidTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val tag: String? = this::class.simpleName

    private val activityManager by lazy { getSystemService<ActivityManager>()!! }
    private val downloadManager by lazy { getSystemService<DownloadManager>()!! }
    private val clipboardManager by lazy { getSystemService<ClipboardManager>()!! }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory()
    }

    private val models = mutableStateListOf<Downloadable>()

    // 音声認識のパーミッション要求用
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            initializeVoskViewModel()
        } else {
            setContent {
                var showDialog by remember { mutableStateOf(true) }
                if (showDialog) {
                    AlertDialog(
                        onDismissRequest = { showDialog = false },
                        title = { Text("権限が拒否されました") },
                        text = { Text("音声認識機能を使用するには、設定からマイクの使用を許可してください。") },
                        confirmButton = {
                            Button(onClick = { showDialog = false }) {
                                Text("OK")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        StrictMode.setVmPolicy(
            VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build()
        )

        // 音声認識のパーミッション確認
        checkAudioPermission()

        Log.i(tag, "Current memory: ${
            Formatter.formatFileSize(
                this,
                availableMemory().availMem
            )
        } / ${Formatter.formatFileSize(this, availableMemory().totalMem)}")
        Log.i(tag, "Downloads directory: ${getExternalFilesDir(null)}")

        // Initial loading of models
        loadModels()

        // SpeakerDataの確認
        checkSpeakerData()

        setContent {
            var showModelDialog by remember { mutableStateOf(false) }
            var showPermissionRationale by remember { mutableStateOf(false) }
            var showPermissionDenied by remember { mutableStateOf(false) }

            LlamaAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Permission Rationale Dialog
                    if (showPermissionRationale) {
                        AlertDialog(
                            onDismissRequest = { showPermissionRationale = false },
                            title = { Text("マイク使用の許可が必要です") },
                            text = { Text("音声認識機能を使用するために、マイクの使用許可が必要です。") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showPermissionRationale = false
                                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                ) {
                                    Text("許可する")
                                }
                            },
                            dismissButton = {
                                Button(onClick = { showPermissionRationale = false }) {
                                    Text("キャンセル")
                                }
                            }
                        )
                    }

                    // Permission Denied Dialog
                    if (showPermissionDenied) {
                        AlertDialog(
                            onDismissRequest = { showPermissionDenied = false },
                            title = { Text("権限が拒否されました") },
                            text = { Text("音声認識機能を使用するには、設定からマイクの使用を許可してください。") },
                            confirmButton = {
                                Button(onClick = { showPermissionDenied = false }) {
                                    Text("OK")
                                }
                            }
                        )
                    }

                    MainCompose(
                        viewModel = viewModel,
                        clipboard = clipboardManager,
                        dm = downloadManager,
                        models = models,
                        showModelDialog = showModelDialog,
                        onShowModelDialog = { showModelDialog = it }
                    )
                }
            }
        }

        viewModel.onModelOperationCompleted = {
            loadModels()
        }
    }

    private fun checkSpeakerData() {
        val baseDir = getExternalFilesDir(null)
        val speakerDataDir = File(baseDir, "speaker_data")

        Log.d("SpeakerData", "Base directory: ${baseDir?.absolutePath}")
        Log.d("SpeakerData", "Speaker data exists: ${speakerDataDir.exists()}")

        if (speakerDataDir.exists()) {
            Log.d("SpeakerData", "Speaker data contents:")
            speakerDataDir.walk().forEach { file ->
                Log.d("SpeakerData", "- ${baseDir?.let { file.relativeTo(it).path }} (${file.length()} bytes)")
            }
        }
    }

    private fun checkAudioPermission() {
        when {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                initializeVoskViewModel()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                setContent {
                    var showDialog by remember { mutableStateOf(true) }
                    if (showDialog) {
                        AlertDialog(
                            onDismissRequest = { showDialog = false },
                            title = { Text("マイク使用の許可が必要です") },
                            text = { Text("音声認識機能を使用するために、マイクの使用許可が必要です。") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showDialog = false
                                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                ) {
                                    Text("許可する")
                                }
                            },
                            dismissButton = {
                                Button(onClick = { showDialog = false }) {
                                    Text("キャンセル")
                                }
                            }
                        )
                    }
                }
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun initializeVoskViewModel() {
        viewModel.initializeVosk(
            VoskViewModel.Factory(
                application = application,
                onRecognitionResult = { text ->
                    viewModel.updateMessage(text)
                }
            )
        )
    }

    private fun availableMemory(): ActivityManager.MemoryInfo {
        return ActivityManager.MemoryInfo().also { memoryInfo ->
            activityManager.getMemoryInfo(memoryInfo)
        }
    }

    private fun loadModels() {
        val extFilesDir = getExternalFilesDir(null)

        val downloadedModels = extFilesDir?.listFiles { file ->
            file.isFile && (file.extension == "gguf" || file.name == VoskRecognizer.VOSK_MODEL_NAME)
        }?.map { file ->
            Downloadable(
                file.name,
                Uri.EMPTY,
                file,
                sha256 = ""
            )
        } ?: emptyList()

        val initialModels = listOf(
            Downloadable(
                "Phi 2 DPO (Q3_K_M, 1.48 GiB)",
                Uri.parse("https://huggingface.co/TheBloke/phi-2-dpo-GGUF/resolve/main/phi-2-dpo.Q3_K_M.gguf?download=true"),
                File(extFilesDir, "phi-2-dpo.Q3_K_M.gguf"),
                sha256 = "e7effd3e3a3b6f1c05b914deca7c9646210bad34576d39d3c5c5f2a25cb97ae1"
            ),
        )

        models.clear()
        models.addAll(initialModels + downloadedModels)
    }
}

class MainViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun MainCompose(
    viewModel: MainViewModel,
    clipboard: ClipboardManager,
    dm: DownloadManager,
    models: List<Downloadable>,
    showModelDialog: Boolean,
    onShowModelDialog: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 録音の状態変数
    val isRecording = viewModel.isRecording
    val currentVoiceTranscript = viewModel.currentVoiceTranscript
    val voiceError = viewModel.voiceRecognitionError

    // 話者管理用の状態変数
    var showSpeakerManagementDialog by remember { mutableStateOf(false) }
    var showSpeakerRegistrationDialog by remember { mutableStateOf(false) }

    // 会話履歴の状態変数
    var showConversationHistory by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // 既存のステータスボックス
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Gray)
                .padding(8.dp)
        ) {
            Column {
                if (viewModel.showMemoryInfo) {
                    val activityManager = context.getSystemService<ActivityManager>()
                    val memoryInfo = ActivityManager.MemoryInfo()
                    activityManager?.getMemoryInfo(memoryInfo)
                    val free = Formatter.formatFileSize(context, memoryInfo.availMem)
                    val total = Formatter.formatFileSize(context, memoryInfo.totalMem)
                    Text("Memory: $free / $total")
                }
                if (viewModel.showModelPath && viewModel.currentModelPath != null) {
                    Text("Model Path: ${viewModel.currentModelPath}")
                }
            }
        }

        // チャットメッセージ表示エリア
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .border(1.dp, Color.Gray)
                .padding(8.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(viewModel.messages) { (userMessage, assistantResponse) ->
                    Text(
                        text = "User: $userMessage",
                        color = Color(0xFFF0F0F0),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                    Text(
                        text = "Assistant: $assistantResponse",
                        color = Color(0xFFFFFFFF),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // メッセージ入力欄と音声認識中の表示
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = viewModel.message,
                onValueChange = { viewModel.updateMessage(it) },
                label = { Text("Message") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )

            if (isRecording && currentVoiceTranscript.isNotBlank()) {
                Text(
                    text = "認識中: $currentVoiceTranscript",
                    color = Color(0xFF64B5F6),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        // パラメータコントロール
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = viewModel.maxTokens.toString(),
                onValueChange = { viewModel.updateMaxTokens(it) },
                label = { Text("Max\nTokens") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = viewModel.seed.toString(),
                onValueChange = { viewModel.updateSeed(it) },
                label = { Text("Seed") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = viewModel.numThreads.toString(),
                onValueChange = { viewModel.updateNumThreads(it) },
                label = { Text("Threads") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = viewModel.contextSize.toString(),
                onValueChange = { viewModel.updateContextSize(it) },
                label = { Text("Context\nSize") },
                modifier = Modifier.weight(1f)
            )
        }

        // アクションボタン
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { viewModel.send() }) { Text("Send") }
            Button(onClick = { viewModel.clear() }) { Text("Clear") }
            Button(onClick = {
                viewModel.getAllMessages().let {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", it))
                }
            }) { Text("Copy") }
        }

        // コントロールボタン行1
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { viewModel.toggleMemoryInfo() }) { Text("Memory") }
            Button(onClick = { viewModel.toggleModelPath() }) { Text("Model Path") }
            Button(onClick = { onShowModelDialog(true) }) { Text("Load Model") }
        }

        // コントロールボタン行2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { viewModel.toggleSystemPromptDialog() }) { Text("System Prompt") }
            Button(
                onClick = { viewModel.toggleHistory() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.isHistoryEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(if (viewModel.isHistoryEnabled) "History ON" else "History OFF")
            }
            Button(onClick = { showConversationHistory = true }) { Text("会話履歴") }
        }

        // 音声入力ボタン行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (isRecording) {
                        viewModel.stopVoiceRecording()
                    } else {
                        viewModel.startVoiceRecording()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) { Text(if (isRecording) "Stop" else "Record") }
            Button(onClick = { showSpeakerManagementDialog = true }) { Text("Speakers") }
        }

        // ダイアログ
        if (showModelDialog) {
            ModelDialog(
                onDismiss = { onShowModelDialog(false) },
                models = models,
                viewModel = viewModel,
                dm = dm
            )
        }

        // 会話履歴
        if (showConversationHistory) {
            ConversationHistoryDialog(
                onDismiss = { showConversationHistory = false },
                viewModel = viewModel.voskViewModel ?: return@MainCompose,
                clipboard = clipboard
            )
        }

        if (viewModel.showSystemPromptDialog) {
            SystemPromptDialog(
                onDismiss = { viewModel.toggleSystemPromptDialog() },
                currentPrompt = viewModel.systemPrompt,
                onUpdatePrompt = { viewModel.updateSystemPrompt(it) }
            )
        }

        // 話者管理
        if (showSpeakerManagementDialog) {
            viewModel.voskViewModel?.let { voskViewModel ->
                SpeakerManagementDialog(
                    onDismiss = { showSpeakerManagementDialog = false },
                    viewModel = voskViewModel,
                    currentSpeakerId = voskViewModel.currentSpeakerId,
                    currentConfidence = voskViewModel.currentSpeakerConfidence,
                    onRegisterNewSpeaker = {
                        showSpeakerManagementDialog = false
                        showSpeakerRegistrationDialog = true
                    }
                )
            }
        }

        if (showSpeakerRegistrationDialog) {
            viewModel.voskViewModel?.let { voskViewModel ->
                SpeakerRegistrationDialog(
                    onDismiss = { showSpeakerRegistrationDialog = false },
                    viewModel = voskViewModel,
                    isRecording = isRecording,
                    onStartRecording = { viewModel.startVoiceRecording() },
                    onStopRecording = { viewModel.stopVoiceRecording() }
                )
            }
        }

        // エラーダイアログ
        voiceError?.let { error ->
            AlertDialog(
                onDismissRequest = { viewModel.clearVoiceError() },
                title = { Text("音声認識エラー") },
                text = { Text(error) },
                confirmButton = {
                    Button(onClick = { viewModel.clearVoiceError() }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}
