// llama.cpp-b2710/examples/llama.android/app/src/main/java/com/example/llama/Llm.kt
package com.example.llama

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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

    private val nlen: Int = 64

    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long): Long
    private external fun free_context(context: Long)
    private external fun backend_init()
    private external fun backend_free()
    private external fun free_batch(batch: Long)
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun bench_model(
        context: Long,
        model: Long,
        pp: Int,
        tg: Int,
        pl: Int,
        nr: Int
    ): String

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

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    Log.d(tag, "bench(): $state")
                    // ローカルでバッチを生成
                    val batch = new_batch(512, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")
                    val result = bench_model(state.context, state.model, pp, tg, pl, nr)
                    free_batch(batch)
                    result
                }

                else -> throw IllegalStateException("No model loaded")
            }
        }
    }

    suspend fun load(pathToModel: String) {
        withContext(runLoop) {
            // 現在のモデルをアンロード
            unloadInternal()
            // 新しいモデルをロード
            val model = load_model(pathToModel)
            if (model == 0L)  throw IllegalStateException("load_model() failed")

            val context = new_context(model)
            if (context == 0L) throw IllegalStateException("new_context() failed")

            Log.i(tag, "Loaded model $pathToModel")
            threadLocalState.set(State.Loaded(model, context))
        }
    }

    fun send(message: String): Flow<String> = flow {
        val state = threadLocalState.get()
        when (state) {
            is State.Loaded -> {
                val batch = new_batch(512, 0, 1)
                if (batch == 0L) throw IllegalStateException("new_batch() failed")

                val ncur = IntVar(completion_init(state.context, batch, message, nlen))
                while (ncur.value <= nlen) {
                    // コルーチンのキャンセルが発生した場合即座に中断
                    currentCoroutineContext().ensureActive()

                    val str = completion_loop(state.context, batch, nlen, ncur)
                    if (str == null) {
                        break
                    }
                    emit(str)
                }
                kv_cache_clear(state.context)
                free_batch(batch)
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
                // `batch` は各関数でローカルに解放されるため、ここでの解放は不要
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
            data object Idle: State
            data class Loaded(val model: Long, val context: Long): State
        }

        // Llmのインスタンスは1つだけにします。
        private val _instance: Llm = Llm()
        fun instance(): Llm = _instance
    }
}
