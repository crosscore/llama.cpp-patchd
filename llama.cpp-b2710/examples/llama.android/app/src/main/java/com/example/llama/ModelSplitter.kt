package com.example.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.*

class ModelSplitter {
    companion object {
        private const val BUFFER_SIZE = 8192
    }

    /**
     * モデルファイルを指定したパートサイズで分割する。
     * 暗号化は行わず、単純にファイルを分割する。
     *
     * @param inputFile 分割対象のモデルファイル
     * @param outputDir 分割後のパートファイルを保存するディレクトリ
     * @param partSizeBytes 各パートのサイズ（バイト単位）
     * @return 分割進捗を表す Flow<Float>（0.0f ～ 1.0f）
     */
    fun splitModelFlow(
        inputFile: File,
        outputDir: File,
        partSizeBytes: Long
    ): Flow<Float> = flow {
        try {
            val totalSize = inputFile.length()
            FileInputStream(inputFile).use { inputStream ->
                var bytesProcessed = 0L
                var partIndex = 1

                while (bytesProcessed < totalSize) {
                    val remaining = totalSize - bytesProcessed
                    val currentPartSize = minOf(partSizeBytes, remaining)
                    val outputFile = File(outputDir, "${inputFile.name}.part$partIndex")

                    FileOutputStream(outputFile).use { outputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesReadTotal = 0L

                        while (bytesReadTotal < currentPartSize) {
                            val bytesToRead = minOf(BUFFER_SIZE.toLong(), currentPartSize - bytesReadTotal).toInt()
                            val bytesRead = inputStream.read(buffer, 0, bytesToRead)
                            if (bytesRead == -1) break
                            outputStream.write(buffer, 0, bytesRead)
                            bytesReadTotal += bytesRead
                            bytesProcessed += bytesRead
                            val progress = bytesProcessed.toFloat() / totalSize
                            emit(progress)
                        }
                    }

                    partIndex++
                }
            }
            emit(1f)
        } catch (e: Exception) {
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 分割されたパートファイルを結合して元のモデルファイルを復元する。
     * 結合時に秘密鍵を要求するが、暗号化や復号化は行わない。
     *
     * @param inputFiles 結合対象のパートファイルのリスト
     * @param outputFile 結合後のモデルファイル
     * @return 結合進捗を表す Flow<Float>（0.0f ～ 1.0f）
     */
    fun mergeModelFlow(
        inputFiles: List<File>,
        outputFile: File
    ): Flow<Float> = flow {
        try {
            val totalSize = inputFiles.sumOf { it.length() }
            var bytesProcessed = 0L

            FileOutputStream(outputFile).use { outputStream ->
                inputFiles.sortedBy {
                    val partNumber = it.name.substringAfter(".part").toIntOrNull() ?: 0
                    partNumber
                }.forEach { inputFile ->
                    FileInputStream(inputFile).use { inputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesProcessed += bytesRead
                            val progress = bytesProcessed.toFloat() / totalSize
                            emit(progress)
                        }
                    }
                }
            }
            emit(1f)
        } catch (e: Exception) {
            throw e
        }
    }.flowOn(Dispatchers.IO)
}
