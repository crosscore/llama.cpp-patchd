// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/Downloadable.kt
package com.example.llama

import android.app.DownloadManager
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

data class Downloadable(
    val name: String,
    val source: Uri,
    val file: File,
    val sha256: String
) {
    companion object {
        @JvmStatic
        private val tag: String? = this::class.qualifiedName

        sealed interface State
        data object Ready : State
        data class Downloading(val progress: Int, val id: Long) : State
        data class Downloaded(val downloadable: Downloadable) : State
        data class Error(val message: String) : State

        @Composable
        fun Button(
            viewModel: MainViewModel,
            dm: DownloadManager,
            item: Downloadable
        ) {
            var status by remember {
                mutableStateOf(
                    if (item.file.exists() && (item.sha256.isEmpty() || verifyFileSha256(item.file, item.sha256))) {
                        Downloaded(item)
                    } else {
                        if (item.file.exists()) {
                            item.file.delete()
                        }
                        Ready
                    }
                )
            }

            val coroutineScope = rememberCoroutineScope()

            val isLoading = viewModel.isLoading
            val loadingModelName = viewModel.loadingModelName
            val loadingProgress = viewModel.loadingProgress

            suspend fun monitorDownload(downloadId: Long): State {
                while (true) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val bytesDownloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val bytesTotal = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val progress = if (bytesTotal > 0) ((bytesDownloaded * 100) / bytesTotal).toInt() else 0

                            val statusCode = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            when (statusCode) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    if (item.sha256.isEmpty() || verifyFileSha256(item.file, item.sha256)) {
                                        return Downloaded(item)
                                    } else {
                                        item.file.delete()
                                        return Error("SHA256 checksum mismatch")
                                    }
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                    return Error("Download failed: $reason")
                                }
                                else -> {
                                    status = Downloading(progress, downloadId)
                                }
                            }
                        } else {
                            return Error("Download not found")
                        }
                    } ?: return Error("Cursor is null")

                    delay(500L)
                }
            }

            fun initiateDownload() {
                item.file.delete()

                val request = DownloadManager.Request(item.source).apply {
                    setTitle("Downloading model")
                    setDescription("Downloading model: ${item.name}")
                    setAllowedNetworkTypes(
                        DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                    )
                    setDestinationUri(Uri.fromFile(item.file))
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                }

                viewModel.log("Saving ${item.name} to ${item.file.path}")
                Log.i(tag, "Saving ${item.name} to ${item.file.path}")

                val downloadId = dm.enqueue(request)
                status = Downloading(0, downloadId)

                coroutineScope.launch {
                    status = monitorDownload(downloadId)
                }
            }

            fun cancelDownload(downloadId: Long) {
                val removed = dm.remove(downloadId)
                if (removed > 0) {
                    viewModel.log("Download cancelled: $downloadId")
                    Log.i(tag, "Download cancelled: $downloadId")
                    status = Ready
                } else {
                    viewModel.log("Failed to cancel download: $downloadId")
                    Log.e(tag, "Failed to cancel download: $downloadId")
                }
            }

            fun onClick() {
                when (status) {
                    is Downloaded -> {
                        viewModel.load(item.file.path)
                    }
                    is Ready, is Error -> {
                        initiateDownload()
                    }
                    is Downloading -> {
                    }
                }
            }

            Button(
                onClick = { onClick() },
                enabled = (status !is Downloading) && (loadingModelName == null || loadingModelName == item.name)
            ) {
                when {
                    isLoading && loadingModelName == item.name && loadingProgress != null -> {
                        Text("Loading... ${(loadingProgress * 100).toInt()}%")
                    }
                    isLoading && loadingModelName == item.name -> {
                        Text("Loading...")
                    }
                    isLoading && loadingModelName != item.name -> {
                        // 他のモデルがロード中の場合は通常の状態を維持
                        when (val currentStatus = status) {
                            is Downloading -> {
                                Text("Downloading ${currentStatus.progress}%")
                            }
                            is Downloaded -> Text("Load ${item.name}")
                            is Ready -> Text(if (item.source == Uri.EMPTY) "Load ${item.name}" else "Download ${item.name}")
                            is Error -> Text("Retry ${item.name}")
                        }
                    }
                    status is Downloading -> {
                        Text("Downloading ${ (status as Downloading).progress }%")
                    }
                    status is Downloaded -> {
                        Text("Load ${item.name}")
                    }
                    status is Ready -> {
                        Text(if (item.source == Uri.EMPTY) "Load ${item.name}" else "Download ${item.name}")
                    }
                    status is Error -> {
                        Text("Retry ${item.name}")
                    }
                    else -> {
                        Text("Load ${item.name}")
                    }
                }
            }
            if (status is Downloading) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    (status as? Downloading)?.let { cancelDownload(it.id) }
                }) {
                    Text("Cancel")
                }
            }
        }

        // SHA256チェックサム検証
        private fun verifyFileSha256(file: File, expectedSha256: String): Boolean {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                actualSha256.equals(expectedSha256, ignoreCase = true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to compute SHA256: ${e.message}")
                false
            }
        }
    }
}
