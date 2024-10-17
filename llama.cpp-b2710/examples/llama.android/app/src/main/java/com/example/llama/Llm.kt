// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/Llm.kt
package com.example.llama

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class Llm {
    private val tag: String? = this::class.simpleName

    private val threadLocalState: ThreadLocal<State> = ThreadLocal.withInitial { State.Idle }

    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            Log.d(tag, "Dedicated thread for native code: ${Thread.currentThread().name}")

            // No-op if called more than once.
            System.loadLibrary("llama-android")

            // Set llama log handler to Android
            log_to_android()
            backend_init()

            Log.d(tag, system_info())

            it.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, exception: Throwable ->
                Log.e(tag, "Unhandled exception", exception)
            }
        }
    }.asCoroutineDispatcher()

    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun free_context(context: Long)
    private external fun backend_init()
    private external fun backend_free()
    private external fun free_batch(batch: Long)
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long

    private external fun system_info(): String

    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        nLen: Int
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        nLen: Int,
        ncur: IntVar
    ): String?

    private external fun kv_cache_clear(context: Long)

    private external fun new_context(model: Long, seed: Int, n_ctx: Int, n_threads: Int): Long

    fun load(pathToModel: String, seed: Int, n_ctx: Int, n_threads: Int): Flow<Float> = flow {
        unloadInternal()
        var progress = 0f
        emit(progress)

        val actualPath: String
        val tempFile: File?

        if (pathToModel.endsWith(".enc")) {
            tempFile = File.createTempFile("model", ".gguf")
            try {
                val totalSize = File(pathToModel).length()
                val decryptionFlow = ModelCrypto().decryptModelFlow(
                    inputStream = FileInputStream(pathToModel),
                    outputStream = FileOutputStream(tempFile),
                    totalSize = totalSize
                )

                decryptionFlow.collect { decryptionProgress ->
                    progress = decryptionProgress * 0.5f // Let's assume decryption is first 50%
                    emit(progress)
                }

                actualPath = tempFile.absolutePath
            } catch (e: Exception) {
                tempFile.delete()
                throw IllegalStateException("Failed to decrypt model: ${e.message}")
            }
        } else {
            actualPath = pathToModel
            tempFile = null
        }

        // Now loading model
        progress = 0.5f
        emit(progress)
        val model = load_model(actualPath)
        if (model == 0L) {
            tempFile?.delete()
            throw IllegalStateException("load_model() failed")
        }
        progress = 0.75f
        emit(progress)

        val context = new_context(model, seed, n_ctx, n_threads)
        if (context == 0L) {
            free_model(model)
            tempFile?.delete()
            throw IllegalStateException("new_context() failed")
        }

        progress = 1f
        emit(progress)
        Log.i(tag, "Loaded model $actualPath")
        threadLocalState.set(State.Loaded(model, context, tempFile))
    }.flowOn(runLoop) // Flow全体の実行コンテキストを変更

    class MaxTokensReachedException : Exception("Max tokens limit reached")

    suspend fun send(message: String, nLen: Int, seed: Int, n_ctx: Int, n_threads: Int): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                val context = new_context(state.model, seed, n_ctx, n_threads)
                if (context == 0L) throw IllegalStateException("new_context() failed")

                try {
                    val batch = new_batch(512, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    val ncur = IntVar(completion_init(context, batch, message, nLen))
                    while (true) {
                        currentCoroutineContext().ensureActive()

                        val str = completion_loop(context, batch, nLen, ncur)
                        if (str.isNullOrEmpty() || str == "<EOS_TOKEN_DETECTED>") {
                            break
                        } else if (str == "<MAX_TOKENS_REACHED>") {
                            throw MaxTokensReachedException()
                        } else {
                            emit(str)
                        }
                    }
                    kv_cache_clear(context)
                    free_batch(batch)
                } finally {
                    free_context(context)
                }
            }
            else -> {}
        }
    }.flowOn(runLoop)

    suspend fun unload() {
        withContext(runLoop) {
            unloadInternal()
        }
    }

    private fun unloadInternal() {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                free_context(state.context)
                free_model(state.model)
                state.tempFile?.delete()
                threadLocalState.set(State.Idle)
            }
            else -> {}
        }
    }

    companion object {
        private class IntVar(value: Int) {
            @Volatile
            var value: Int = value
                private set

            fun inc() {
                synchronized(this) {
                    value += 1
                }
            }
        }

        private sealed interface State {
            data object Idle : State
            data class Loaded(val model: Long, val context: Long, val tempFile: File?) : State
        }

        private val _instance: Llm = Llm()
        fun instance(): Llm = _instance
    }
}
