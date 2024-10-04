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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.example.llama.ui.theme.LlamaAndroidTheme
import java.io.File

class MainActivity(
    activityManager: ActivityManager? = null,
    downloadManager: DownloadManager? = null,
    clipboardManager: ClipboardManager? = null,
) : ComponentActivity() {

    private val activityManager by lazy { activityManager ?: getSystemService<ActivityManager>()!! }
    private val downloadManager by lazy { downloadManager ?: getSystemService<DownloadManager>()!! }
    private val clipboardManager by lazy { clipboardManager ?: getSystemService<ClipboardManager>()!! }

    private val viewModel: MainViewModel by viewModels()

    private fun availableMemory(): ActivityManager.MemoryInfo {
        return ActivityManager.MemoryInfo().also { memoryInfo ->
            activityManager.getMemoryInfo(memoryInfo)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        StrictMode.setVmPolicy(
            VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build()
        )

        val free = Formatter.formatFileSize(this, availableMemory().availMem)
        val total = Formatter.formatFileSize(this, availableMemory().totalMem)

        viewModel.log("Current memory: $free / $total")
        viewModel.log("Downloads directory: ${getExternalFilesDir(null)}")

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

        val models = listOf(
            Downloadable(
                "Phi 2 DPO (Q3_K_M, 1.48 GiB)",
                Uri.parse("https://huggingface.co/TheBloke/phi-2-dpo-GGUF/resolve/main/phi-2-dpo.Q3_K_M.gguf?download=true"),
                File(extFilesDir, "phi-2-dpo.Q3_K_M.gguf"),
                sha256 = "e7effd3e3a3b6f1c05b914deca7c9646210bad34576d39d3c5c5f2a25cb97ae1"
            ),
        ) + downloadedModels

        setContent {
            LlamaAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainCompose(
                        viewModel,
                        clipboardManager,
                        downloadManager,
                        models,
                    )
                }
            }
        }
    }
}

@Composable
fun MainCompose(
    viewModel: MainViewModel,
    clipboard: ClipboardManager,
    dm: DownloadManager,
    models: List<Downloadable>
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
                itemsIndexed(viewModel.messages) { index, messagePair ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .padding(8.dp)
                    ) {
                        if (messagePair.first == "[System]") {
                            Text(
                                text = messagePair.second,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        } else {
                            Text(
                                text = "User: ${messagePair.first}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "LLM: ${messagePair.second}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { viewModel.toggleMemoryInfo() }) { Text("Memory") }
            Button(onClick = { viewModel.toggleModelPath() }) { Text("Model Path") }
        }

        Column(modifier = Modifier.padding(top = 8.dp)) {
            for (model in models) {
                Downloadable.Button(viewModel, dm, model)
            }
        }
    }
}
