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
