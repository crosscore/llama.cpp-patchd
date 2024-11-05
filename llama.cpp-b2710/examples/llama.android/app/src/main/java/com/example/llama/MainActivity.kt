// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/MainActivity.kt
package com.example.llama

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.llama.ui.theme.LlamaAndroidTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val activityManager by lazy { getSystemService<ActivityManager>()!! }
    private val downloadManager by lazy { getSystemService<DownloadManager>()!! }
    private val clipboardManager by lazy { getSystemService<ClipboardManager>()!! }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory()
    }

    // Keep models as a mutable state list
    private val models = mutableStateListOf<Downloadable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        StrictMode.setVmPolicy(
            VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build()
        )

        viewModel.log(
            "Current memory: ${
                Formatter.formatFileSize(
                    this,
                    availableMemory().availMem
                )
            } / ${Formatter.formatFileSize(this, availableMemory().totalMem)}"
        )
        viewModel.log("Downloads directory: ${getExternalFilesDir(null)}")

        // Initial loading of models
        loadModels()

        setContent {
            var showModelDialog by remember { mutableStateOf(false) }

            LlamaAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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

        // Set a listener to reload models when an operation completes
        viewModel.onModelOperationCompleted = {
            loadModels()
        }
    }

    private fun availableMemory(): ActivityManager.MemoryInfo {
        return ActivityManager.MemoryInfo().also { memoryInfo ->
            activityManager.getMemoryInfo(memoryInfo)
        }
    }

    // Function to load or reload the model list
    private fun loadModels() {
        val extFilesDir = getExternalFilesDir(null)

        val downloadedModels = extFilesDir?.listFiles { file ->
            file.isFile && file.extension == "gguf"
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

        // Update the models list
        models.clear()
        models.addAll(initialModels + downloadedModels)
    }
}

// Factory to provide Context to the ViewModel
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

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // メモリ情報とモデルパスの表示をまとめて枠線付きで表示
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

        val messageListState = rememberLazyListState()

        Box(
            modifier = Modifier
                .weight(1f)
                .border(1.dp, Color.Gray)
                .padding(8.dp)
        ) {
            LazyColumn(
                state = messageListState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(viewModel.messages) { (userMessage, llmResponse) ->
                    Text(text = "User: $userMessage")
                    Text(text = "LLM: $llmResponse")
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        OutlinedTextField(
            value = viewModel.message,
            onValueChange = { viewModel.updateMessage(it) },
            label = { Text("Message") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

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
                modifier = Modifier
                    .weight(1f)
            )
            OutlinedTextField(
                value = viewModel.seed.toString(),
                onValueChange = { viewModel.updateSeed(it) },
                label = { Text("Seed") },
                modifier = Modifier
                    .weight(1f)
            )
            OutlinedTextField(
                value = viewModel.numThreads.toString(),
                onValueChange = { viewModel.updateNumThreads(it) },
                label = { Text("Threads") },
                modifier = Modifier
                    .weight(1f)
            )
            OutlinedTextField(
                value = viewModel.contextSize.toString(),
                onValueChange = { viewModel.updateContextSize(it) },
                label = { Text("Context\nSize") },
                modifier = Modifier
                    .weight(1f)
            )
        }

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { viewModel.toggleMemoryInfo() }) { Text("Memory") }
            Button(onClick = { viewModel.toggleModelPath() }) { Text("Model Path") }
            Button(onClick = { onShowModelDialog(true) }) { Text("Load Model") }
        }

        // Model Selection Dialog
        if (showModelDialog) {
            ModelDialog(
                onDismiss = { onShowModelDialog(false) },
                models = models,
                viewModel = viewModel,
                dm = dm
            )
        }
    }
}
