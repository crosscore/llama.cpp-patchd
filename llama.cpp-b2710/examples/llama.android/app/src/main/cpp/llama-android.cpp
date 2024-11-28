// llama.cpp-b2710/examples/llama.android/app/src/main/cpp/llama-android.cpp
/**
 * llama.cppとAndroidアプリケーションのJNI連携実装
 *
 * このファイルは以下の主要な機能を提供します：
 * - モデルのロードと初期化
 * - コンテキスト管理
 * - トークン生成処理
 * - KVキャッシュ制御
 * - メモリ管理の最適化
 *
 * 技術的特徴：
 * - JNI (Java Native Interface)による相互運用
 * - UTF-8文字列処理の最適化
 * - トークン化とバッチ処理
 * - エラーハンドリングの強化
 *
 * リソース管理：
 * - コンテキストサイズの動的調整
 * - メモリリークの防止
 * - バッチ処理の最適化
 * - トークン追跡の実装
 *
 * 安全性考慮：
 * - バッファオーバーフロー対策
 * - メモリ解放の確実な実行
 * - 適切なエラー伝播
 * - 例外処理の実装
 *
 * @note 最大コンテキストサイズは2048トークン
 * @note トークンの結合処理に特別な注意が必要
 *
 * @see Llm.kt
 * @see MainViewModel.kt
 */

#pragma clang diagnostic push
#pragma ide diagnostic ignored "performance-no-int-to-ptr"
#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include "llama.h"
#include "common.h"

#define TAG "llama-android.cpp"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define MAX_CONTEXT_SIZE 2048

#ifdef __cplusplus
extern "C" {
#endif

jclass la_int_var;
jmethodID la_int_var_value;
jmethodID la_int_var_inc;

std::string cached_token_chars;

namespace {
    /**
     * ログメッセージをAndroidのログシステムにリダイレクトする
     *
     * @param level ログレベル (ERROR/INFO/WARN)
     * @param fmt フォーマット文字列
     * @param data 追加データ (未使用)
     *
     * @note ログレベルに応じて適切なAndroidログ関数を使用
     * @note GGML/LLAMAのログ出力をAndroidのlogcatに統合
     */
    extern "C" void log_callback(ggml_log_level level, const char *fmt, void *data) {
        if (level == GGML_LOG_LEVEL_ERROR) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, data);
        } else if (level == GGML_LOG_LEVEL_INFO) {
            __android_log_print(ANDROID_LOG_INFO, TAG, fmt, data);
        } else if (level == GGML_LOG_LEVEL_WARN) {
            __android_log_print(ANDROID_LOG_WARN, TAG, fmt, data);
        } else {
            __android_log_print(ANDROID_LOG_DEFAULT, TAG, fmt, data);
        }
    }
    int g_input_token_count = 0;  // 入力トークン数を保持
    int g_output_token_count = 0; // 出力トークン数を保持
    int g_total_tokens = 0;       // 合計トークン数を保持
    int g_context_size = 0;       // コンテキストサイズを保持

    // トークン追跡のリセット
    void reset_token_tracking(int context_size) {
        g_input_token_count = 0;
        g_output_token_count = 0;
        g_total_tokens = 0;
        g_context_size = context_size;
    }

    // 入力トークン数の設定
    void set_input_tokens(int count) {
        g_input_token_count = count;
        g_total_tokens = g_input_token_count;
    }

    // 出力トークンの追加
    void add_output_token() {
        g_output_token_count++;
        g_total_tokens = g_input_token_count + g_output_token_count;
    }
}

/**
 * 文字列がUTF-8として有効かチェックする
 *
 * @param string 検証する文字列
 * @return true: 有効なUTF-8文字列、false: 無効
 *
 * @note 以下のUTF-8シーケンスを検証:
 *       - 1バイト (U+0000 to U+007F)
 *       - 2バイト (U+0080 to U+07FF)
 *       - 3バイト (U+0800 to U+FFFF)
 *       - 4バイト (U+10000 to U+10FFFF)
 * @note nullポインタは有効として扱う
 */
bool is_valid_utf8(const char *string) {
    if (!string) {
        return true;
    }
    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {        // 0xxxxxxx: 1バイト文字（ASCII）
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) { // 110xxxxx: 2バイト文字
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) { // 1110xxxx: 3バイト文字
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) { // 11110xxx: 4バイト文字
            num = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

/**
 * モデルをファイルからロードする
 *
 * @param env JNI環境
 * @param filename モデルファイルのパス (.gguf形式)
 * @return ロードされたモデルのポインタ (失敗時は0)
 * @throws IllegalStateException モデルのロードに失敗した場合
 *
 * @note メモリ使用量はモデルサイズに依存
 * @note モデル使用後は必ずfree_model()で解放すること
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_llama_Llm_load_1model(JNIEnv *env, jobject /*unused*/, jstring filename) {
    llama_model_params model_params = llama_model_default_params();

    const auto *path_to_model = env->GetStringUTFChars(filename, nullptr);
    LOGi("Loading model from %s", path_to_model);

    auto *model = llama_load_model_from_file(path_to_model, model_params);
    env->ReleaseStringUTFChars(filename, path_to_model);

    if (!model) {
        LOGe("load_model() failed");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "load_model() failed");
        return 0;
    }

    return reinterpret_cast<jlong>(model);
}

/**
 * モデルのメモリを解放する
 *
 * @param env JNI環境 (未使用)
 * @param model 解放するモデルのポインタ
 *
 * @note モデルを使用しなくなった時点で必ず呼び出すこと
 * @note 解放済みのモデルに対する操作は未定義動作
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_Llm_free_1model(JNIEnv * /*unused*/, jobject /*unused*/, jlong model) {
    llama_free_model(reinterpret_cast<llama_model *>(model)); // NOLINT(*-no-int-to-ptr)
}

/**
 * 推論用のコンテキストを新規作成する
 *
 * @param env JNI環境
 * @param jmodel モデルのポインタ
 * @param seed 乱数生成用シード値
 * @param n_ctx コンテキストサイズ (最大2048トークン)
 * @param n_threads 使用するスレッド数
 * @return 生成されたコンテキストのポインタ (失敗時は0)
 * @throws IllegalArgumentException モデルが無効な場合
 * @throws IllegalStateException コンテキスト生成に失敗した場合
 *
 * @note メモリ使用量はコンテキストサイズに比例
 * @note スレッド数は利用可能なCPUコア数を考慮して設定
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_llama_Llm_new_1context(JNIEnv *env, jobject /*unused*/, jlong jmodel, jint seed, jint n_ctx, jint n_threads) {
    auto *model = reinterpret_cast<llama_model *>(jmodel);

    if (!model) {
        LOGe("new_context(): model cannot be null");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Model cannot be null");
        return 0;
    }

    LOGi("Using %d threads", n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.seed  = seed;
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    // トークン追跡をリセット
    reset_token_tracking(n_ctx);

    llama_context *context = llama_new_context_with_model(model, ctx_params);

    if (!context) {
        LOGe("llama_new_context_with_model() returned null");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "llama_new_context_with_model() returned null");
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

/**
 * コンテキストのメモリを解放する
 *
 * @param env JNI環境 (未使用)
 * @param context 解放するコンテキストのポインタ
 *
 * @note コンテキストを使用しなくなった時点で必ず呼び出すこと
 * @note 解放済みのコンテキストに対する操作は未定義動作
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_Llm_free_1context(JNIEnv * /*unused*/, jobject /*unused*/, jlong context) {
    llama_free(reinterpret_cast<llama_context *>(context)); // NOLINT(*-no-int-to-ptr)
}

/**
 * バックエンドのリソースを解放する
 *
 * @param env JNI環境 (未使用)
 *
 * @note アプリケーション終了時に呼び出し
 * @note GGML/LLAMAバックエンドが確保したリソースを解放
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_Llm_backend_1free(JNIEnv * /*unused*/, jobject /*unused*/) {
    llama_backend_free();
}

/**
 * Androidログシステムへのリダイレクトを設定する
 *
 * @param env JNI環境 (未使用)
 *
 * @note アプリケーション起動時に呼び出し
 * @note log_callback関数をGGMLのログハンドラとして登録
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_Llm_log_1to_1android(JNIEnv * /*unused*/, jobject /*unused*/) {
    llama_log_set(log_callback, nullptr);
}

/**
 * バッチ処理用のリソースを解放する
 *
 * @param env JNI環境 (未使用)
 * @param batch_pointer 解放するバッチのポインタ
 *
 * @note 以下のメモリを順次解放:
 *       - embd (埋め込みデータ)
 *       - token (トークンデータ)
 *       - pos (位置情報)
 *       - n_seq_id (シーケンスID数)
 *       - seq_id (シーケンスIDリスト)
 *       - logits (ロジット)
 * @note nullポインタは安全に無視される
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_Llm_free_1batch(JNIEnv * /*unused*/, jobject /*unused*/,
                                       jlong batch_pointer) {
    auto *batch = reinterpret_cast<llama_batch *>(batch_pointer);
    if (!batch) {
        return;
    }

    // Free embd or token
    if (batch->embd) {
        free(batch->embd);
        batch->embd = nullptr;
    }
    if (batch->token) {
        free(batch->token);
        batch->token = nullptr;
    }

    // Free pos
    if (batch->pos) {
        free(batch->pos);
        batch->pos = nullptr;
    }

    // Free n_seq_id
    if (batch->n_seq_id) {
        free(batch->n_seq_id);
        batch->n_seq_id = nullptr;
    }

    // Free seq_id
    if (batch->seq_id) {
        size_t i = 0;
        while (batch->seq_id[i] != nullptr) {
            free(batch->seq_id[i]);
            i++;
        }
        free(reinterpret_cast<void*>(batch->seq_id)); // 正しいポインタ型で解放

        batch->seq_id = nullptr;
    }

    // Free logits
    if (batch->logits) {
        free(batch->logits);
        batch->logits = nullptr;
    }

    // Free the batch itself
    delete batch;
}

/**
 * 新しいバッチ処理用のリソースを確保する
 *
 * @param env JNI環境
 * @param context_pointer コンテキストのポインタ
 * @param n_tokens トークン数 (未使用)
 * @param embd 埋め込みフラグ (0: トークンモード, 1: 埋め込みモード)
 * @param n_seq_max 最大シーケンス数
 * @return 生成されたバッチのポインタ (失敗時は0)
 * @throws OutOfMemoryError メモリ確保に失敗した場合
 *
 * @note バッチサイズはコンテキストサイズと最大コンテキストサイズの小さい方
 * @note 確保したメモリは必ずfree_batch()で解放すること
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_llama_Llm_new_1batch(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong context_pointer,  // コンテキストポインターを追加
        jint /*n_tokens*/,
        jint embd,
        jint n_seq_max          // n_seq_maxパラメータを追加
) {
    // コンテキストポインターから現在のコンテキストサイズを取得
    auto *const context = reinterpret_cast<llama_context *>(context_pointer);
    const size_t current_context_size = llama_n_ctx(context);

    // 現在のコンテキストサイズに基づいてバッチサイズを設定
    // ただし最大値を超えないようにする
    const size_t batch_size = std::min(current_context_size, static_cast<size_t>(MAX_CONTEXT_SIZE));

    LOGi("Creating batch with size: %zu (context size: %zu)", batch_size, current_context_size);

    auto *batch = new llama_batch{
            0,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            0,
            0,
            0,
    };

    try {
        if (embd) {
            batch->embd = static_cast<float *>(malloc(sizeof(float) * batch_size * embd));
            if (!batch->embd) { throw std::bad_alloc(); }
        } else {
            batch->token = static_cast<llama_token *>(malloc(sizeof(llama_token) * batch_size));
            if (!batch->token) { throw std::bad_alloc(); }
        }

        batch->pos = static_cast<llama_pos *>(malloc(sizeof(llama_pos) * batch_size));
        if (!batch->pos) { throw std::bad_alloc(); }

        batch->n_seq_id = static_cast<int32_t *>(malloc(sizeof(int32_t) * batch_size));
        if (!batch->n_seq_id) { throw std::bad_alloc(); }

        batch->seq_id = static_cast<llama_seq_id **>(malloc(sizeof(llama_seq_id *) * (batch_size + 1)));
        if (!batch->seq_id) { throw std::bad_alloc(); }

        for (size_t i = 0; i < batch_size; ++i) {
            batch->seq_id[i] = static_cast<llama_seq_id *>(malloc(sizeof(llama_seq_id) * n_seq_max));
            if (!batch->seq_id[i]) { throw std::bad_alloc(); }
        }
        batch->seq_id[batch_size] = nullptr;

        batch->logits = static_cast<int8_t *>(malloc(sizeof(int8_t) * batch_size));
        if (!batch->logits) { throw std::bad_alloc(); }
    } catch (const std::bad_alloc &) {
        // エラー時は既に確保したメモリを解放
        if (batch->embd) { free(batch->embd); }
        if (batch->token) { free(batch->token); }
        if (batch->pos) { free(batch->pos); }
        if (batch->n_seq_id) { free(batch->n_seq_id); }
        if (batch->seq_id) {
            for (size_t i = 0; i < batch_size; ++i) {
                if (batch->seq_id[i]) {
                    free(batch->seq_id[i]);
                }
            }
            free(reinterpret_cast<void*>(batch->seq_id)); // 正しいポインタ型で解放
        }
        if (batch->logits) { free(batch->logits); }
        delete batch;
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Failed to allocate memory for batch");
        return 0;
    }

    return reinterpret_cast<jlong>(batch);
}

/**
 * バックエンドを初期化する
 *
 * @param env JNI環境 (未使用)
 *
 * @note アプリケーション起動時に一度だけ呼び出す
 * @note GGML/LLAMAバックエンドの初期化を実行
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_Llm_backend_1init(JNIEnv * /*unused*/, jobject /*unused*/) {
    llama_backend_init();
}

/**
 * システム情報を取得する
 *
 * @param env JNI環境
 * @return システム情報を含む文字列
 *
 * @note 以下の情報を含む:
 *       - CPUアーキテクチャ
 *       - CPUコア数
 *       - BLAS/CUBLAS/METAL等のバックエンド情報
 *       - コンパイル時の最適化オプション
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_llama_Llm_system_1info(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

/**
 * 文章生成の初期化を行う
 *
 * @param env JNI環境
 * @param context_pointer LLAMAコンテキストのポインタ
 * @param batch_pointer バッチ処理のポインタ
 * @param jtext 入力プロンプト文字列
 * @param n_len 生成する最大トークン数
 * @return 初期化後のトークン数
 *
 * @note プロンプトを解析してトークン化し、初期状態を設定
 * @note キャッシュされたトークン文字列をクリア
 * @note KVキャッシュのサイズチェックを実行
 * @note g_input_token_countを更新
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_llama_Llm_completion_1init(
        JNIEnv *env,
        jobject /*unused*/,
        jlong context_pointer,
        jlong batch_pointer,
        jstring jtext,
        jint n_len
) {
    cached_token_chars.clear();

    const auto *const text = env->GetStringUTFChars(jtext, nullptr);
    auto *const context = reinterpret_cast<llama_context *>(context_pointer);
    auto *const batch = reinterpret_cast<llama_batch *>(batch_pointer);
    // auto *const model = llama_get_model(context);

    LOGi("=== Prompt Analysis ===");
    std::string display_text = text;
    if (display_text.length() > 300) {
        display_text = display_text.substr(0, 297) + "...";
    }
    LOGi("Input text: %s", display_text.c_str());

    const auto tokens_list = llama_tokenize(context, text, true);
    set_input_tokens(static_cast<int>(tokens_list.size()));  // 入力トークン数を設定

    LOGi("Input tokens: %zu, Total tokens: %d/%d (%.1f%% used)",
         g_input_token_count, g_total_tokens, g_context_size,
         (g_total_tokens * 100.0f) / g_context_size);

    auto n_ctx = llama_n_ctx(context);
    auto n_kv_req = g_input_token_count + n_len;

    if (n_kv_req > n_ctx) {
        LOGe("Error: Required KV cache size (%zu) exceeds context size (%d)", n_kv_req, n_ctx);
    }

    llama_batch_clear(*batch);

    for (auto i = 0; i < tokens_list.size(); i++) {
        llama_batch_add(*batch, tokens_list[i], i, {0}, false);
    }

    batch->logits[batch->n_tokens - 1] = true;

    if (llama_decode(context, *batch) != 0) {
        LOGe("llama_decode() failed");
    }

    env->ReleaseStringUTFChars(jtext, text);

    return batch->n_tokens;
}

/**
 * 文章生成のメインループを実行する
 *
 * @param env JNI環境
 * @param context_pointer LLAMAコンテキストのポインタ
 * @param batch_pointer バッチ処理のポインタ
 * @param n_len 生成する最大トークン数
 * @param intvar_ncur 現在のトークン位置を管理するオブジェクト
 * @return 生成されたトークンの文字列 (特殊トークンの場合は制御文字列)
 *
 * @note 以下の特殊トークンを返す可能性あり:
 *       - "<EOS_TOKEN_DETECTED>": 終了トークン検出
 *       - "<MAX_TOKENS_REACHED>": 最大トークン数到達
 *       - "<CONVERSATION_END>": 会話終了(改行2回)
 *       - "": UTF-8変換失敗や中間トークン
 *
 * @note トークンのUTF-8検証と結合処理を実行
 * @note 生成状況のログ出力を含む
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_llama_Llm_completion_1loop(
        JNIEnv *env,
        jobject /*unused*/,
        jlong context_pointer,
        jlong batch_pointer,
        jint n_len,
        jobject intvar_ncur
) {
    auto *const context = reinterpret_cast<llama_context *>(context_pointer);
    auto *const batch = reinterpret_cast<llama_batch *>(batch_pointer);
    const auto *const model = llama_get_model(context);

    if (!la_int_var) { la_int_var = env->GetObjectClass(intvar_ncur); }
    if (!la_int_var_value) { la_int_var_value = env->GetMethodID(la_int_var, "getValue", "()I"); }
    if (!la_int_var_inc) { la_int_var_inc = env->GetMethodID(la_int_var, "inc", "()V"); }

    static bool skip_next_token = false;

    // 先にトークン生成のロジックを実行
    auto n_vocab = llama_n_vocab(model);
    auto *logits = llama_get_logits_ith(context, batch->n_tokens - 1);
    std::vector<llama_token_data> candidates;
    candidates.reserve(n_vocab);

    for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
        candidates.emplace_back(llama_token_data{token_id, logits[token_id], 0.0f});
    }

    llama_token_data_array candidates_p = {candidates.data(), candidates.size(), false};
    const auto new_token_id = llama_sample_token_greedy(context, &candidates_p);
    float token_score = candidates[new_token_id].logit;
    const auto n_cur = env->CallIntMethod(intvar_ncur, la_int_var_value);
    const auto start_pos = n_cur - g_input_token_count;

    // skip_next_tokenの処理
    if (skip_next_token) {
        skip_next_token = false;
        LOGi("Skipping token as it was used in combination");

        llama_batch_clear(*batch);
        llama_batch_add(*batch, new_token_id, n_cur, {0}, true);
        llama_decode(context, *batch);
        env->CallVoidMethod(intvar_ncur, la_int_var_inc);
        return env->NewStringUTF("");
    }

    // トークンの生成と位置のログ出力
    add_output_token();  // 出力トークンをカウント
    const auto output_position = g_output_token_count;  // 現在の出力位置

    LOGi("Token[%d] at position:%d/%d totalTokens:%d/%d (%.1f%% used) (score: %.4f)",
         new_token_id, output_position, n_len,
         g_total_tokens, g_context_size,
         (g_total_tokens * 100.0f) / g_context_size,
         token_score);

    // 基本的なチェック
    if (new_token_id == 0 || llama_token_is_eog(model, new_token_id)) {
        LOGi("Token[%d]: EOS token detected at position: %d/%d",
             new_token_id, start_pos + 1, n_len);
        return env->NewStringUTF("<EOS_TOKEN_DETECTED>");
    }

    // トークンからピースへの変換
    char piece[64] = {0};
    int length = llama_token_to_piece(model, new_token_id, piece, sizeof(piece), true);
    if (length < 0) {
        LOGi("Token[%d]: Conversion failed", new_token_id);
        llama_batch_clear(*batch);
        llama_batch_add(*batch, new_token_id, n_cur, {0}, true);
        llama_decode(context, *batch);
        env->CallVoidMethod(intvar_ncur, la_int_var_inc);
        return env->NewStringUTF("");
    }

    // 特殊なトークンIDの処理
    if (new_token_id == 32766) {
        LOGi("Token[%d] -> '\\n\\n' (double newline detected)", new_token_id);
        return env->NewStringUTF("<CONVERSATION_END>");
    }
    if (new_token_id == 212) {
        LOGi("Token[%d] -> '\\n'", new_token_id);
    }

    if (start_pos >= n_len) {  // 相対位置でチェック
        LOGi("Max tokens reached: %d/%d", start_pos, n_len);
        return env->NewStringUTF("<MAX_TOKENS_REACHED>");
    }

    // ログ出力：Bytes情報
    std::string bytes_log = "Bytes: ";
    for (int i = 0; i < length; i++) {
        char hex[8];
        snprintf(hex, sizeof(hex), "0x%02X ", (unsigned char)piece[i]);
        bytes_log += hex;
    }

    // UTF-8シーケンスの処理
    cached_token_chars += std::string(piece, length);
    bool is_valid = is_valid_utf8(cached_token_chars.c_str());
    bool needs_next_token = false;

    if (!cached_token_chars.empty()) {
        unsigned char first_byte = cached_token_chars[0];
        size_t expected_length = 0;
        if ((first_byte & 0x80) == 0) { expected_length = 1;
        } else if ((first_byte & 0xE0) == 0xC0) { expected_length = 2;
        } else if ((first_byte & 0xF0) == 0xE0) { expected_length = 3;
        } else if ((first_byte & 0xF8) == 0xF0) { expected_length = 4;
        }
        needs_next_token = cached_token_chars.length() < expected_length;
    }

    jstring new_token = nullptr;
    if (is_valid && !needs_next_token) {
        new_token = env->NewStringUTF(cached_token_chars.c_str());
        if (new_token_id != 212) {  // 改行トークンの場合は既にログ出力済み
            LOGi("Token[%d] -> '%s'", new_token_id, cached_token_chars.c_str());
        }
        cached_token_chars.clear();
    } else if (needs_next_token) {
        // 次のトークンの準備
        llama_batch_clear(*batch);
        llama_batch_add(*batch, new_token_id, n_cur, {0}, true);
        if (llama_decode(context, *batch) == 0) {
            auto *next_logits = llama_get_logits_ith(context, 0);
            if (next_logits != nullptr) {
                std::vector<llama_token_data> next_candidates;
                next_candidates.reserve(n_vocab);
                for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
                    next_candidates.emplace_back(llama_token_data{token_id, next_logits[token_id], 0.0f});
                }
                llama_token_data_array next_candidates_p = {next_candidates.data(), next_candidates.size(), false};
                const auto next_token_id = llama_sample_token_greedy(context, &next_candidates_p);

                char next_piece[64] = {0};
                int next_length = llama_token_to_piece(model, next_token_id, next_piece, sizeof(next_piece), true);
                if (next_length > 0) {
                    LOGi("Found continuation token [%d] with length %d", next_token_id, next_length);

                    // Debug: Print continuation token bytes
                    std::string next_bytes_log = "Continuation bytes: ";
                    for (int i = 0; i < next_length; i++) {
                        char hex[8];
                        snprintf(hex, sizeof(hex), "0x%02X ", (unsigned char)next_piece[i]);
                        next_bytes_log += hex;
                    }
                    LOGi("%s", next_bytes_log.c_str());

                    std::string combined = cached_token_chars + std::string(next_piece, next_length);
                    LOGi("Attempting to combine tokens (total bytes: %zu)", combined.length());
                    if (is_valid_utf8(combined.c_str())) {
                        cached_token_chars = combined;
                        new_token = env->NewStringUTF(cached_token_chars.c_str());
                        LOGi("Tokens[%d,%d] combined -> '%s'",
                             new_token_id, next_token_id, cached_token_chars.c_str());
                        cached_token_chars.clear();
                        skip_next_token = true;  // 次のトークンをスキップするフラグを設定
                    } else {
                        LOGi("Invalid combination of tokens [%d,%d]", new_token_id, next_token_id);
                        new_token = env->NewStringUTF("");
                    }
                } else {
                    LOGi("Next token[%d] conversion failed", next_token_id);
                    new_token = env->NewStringUTF("");
                }
            }
        } else {
            LOGi("Decode failed for token[%d]", new_token_id);
            new_token = env->NewStringUTF("");
        }
    } else {
        LOGi("Invalid UTF-8 sequence for token[%d], attempting to continue", new_token_id);
        new_token = env->NewStringUTF("");
    }

    // バッチの処理
    llama_batch_clear(*batch);
    llama_batch_add(*batch, new_token_id, n_cur, {0}, true);
    if (llama_decode(context, *batch) != 0) {
        LOGi("Warning: Decode failed for token[%d], but continuing", new_token_id);
    }
    env->CallVoidMethod(intvar_ncur, la_int_var_inc);

    return new_token;
}

/**
 * KVキャッシュをクリアする
 *
 * @param env JNI環境 (未使用)
 * @param context クリア対象のコンテキストポインタ
 *
 * @note 会話履歴をリセットする際に使用
 * @note キャッシュクリアにより文脈が失われる
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_Llm_kv_1cache_1clear(JNIEnv * /*unused*/, jobject /*unused*/,
                                            jlong context) {
    llama_kv_cache_clear(reinterpret_cast<llama_context *>(context)); // NOLINT(*-no-int-to-ptr)
}

/**
 * 文字列をトークンに分割する
 *
 * @param env JNI環境
 * @param model モデルのポインタ
 * @param text トークン化する文字列
 * @return トークンIDの配列
 *
 * @note BPE (Byte Pair Encoding) アルゴリズムを使用
 * @note 結果は必要に応じてJavaのint配列に変換
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_llama_Llm_llama_1tokenize(JNIEnv *env, jobject /*unused*/, jlong model, jstring text) {
    const char *input = env->GetStringUTFChars(text, nullptr);
    auto *model_ptr = reinterpret_cast<llama_model *>(model); // NOLINT(*-no-int-to-ptr)

    std::vector<llama_token> tokens = llama_tokenize(model_ptr, input, true);

    env->ReleaseStringUTFChars(text, input);

    jintArray result = env->NewIntArray(static_cast<int>(tokens.size()));
    if (result) {
        env->SetIntArrayRegion(result, 0, static_cast<int>(tokens.size()), reinterpret_cast<const jint*>(tokens.data()));
    }

    return result;
}

#ifdef __cplusplus
}
#endif

#pragma clang diagnostic pop
