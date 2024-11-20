// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/Llm.kt
package com.example.llama

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    // 追加: KVキャッシュをクリアする関数
    fun clearKVCache() {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                kv_cache_clear(state.context)
            }
            else -> {
                Log.d(tag, "No model loaded, skipping KV cache clear")
            }
        }
    }

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

    external fun kv_cache_clear(context: Long)

    private external fun new_context(model: Long, seed: Int, n_ctx: Int, n_threads: Int): Long

    fun load(pathToModel: String, seed: Int, n_ctx: Int, n_threads: Int): Flow<Float> = flow {
        unloadInternal()
        emit(0f)

        val model = load_model(pathToModel)
        if (model == 0L) {
            throw IllegalStateException("load_model() failed")
        }
        emit(0.5f)

        val context = new_context(model, seed, n_ctx, n_threads)
        if (context == 0L) {
            free_model(model)
            throw IllegalStateException("new_context() failed")
        }

        emit(1f)
        Log.i(tag, "Loaded model $pathToModel")
        threadLocalState.set(State.Loaded(model, context))
    }.flowOn(runLoop)

    class MaxTokensReachedException : Exception("Maximum output tokens limit reached")

    // Helper function to count tokens
    private external fun llama_tokenize(model: Long, text: String): IntArray

    suspend fun send(message: String, nLen: Int, seed: Int, n_ctx: Int, n_threads: Int): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                // メッセージのトークン数を取得
                val tokens = llama_tokenize(state.model, message)
                val inputTokenCount = tokens.size

                // 利用可能なトークン数をチェック
                if (inputTokenCount + nLen > n_ctx) {
                    throw IllegalArgumentException(
                        "Total tokens would exceed context size ($n_ctx). " +
                            "Input tokens: $inputTokenCount, " +
                            "Requested output tokens: $nLen, " +
                            "Available tokens for output: ${n_ctx - inputTokenCount}"
                    )
                }

                val context = new_context(state.model, seed, n_ctx, n_threads)
                if (context == 0L) throw IllegalStateException("new_context() failed")

                try {
                    val batch = new_batch(2048, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    try {
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
                    } finally {
                        free_batch(batch)
                    }
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
            data class Loaded(val model: Long, val context: Long) : State
        }

        private val _instance: Llm = Llm()
        fun instance(): Llm = _instance
    }
}
