// llama.cpp-b2710/examples/llama.android/app/src/main/cpp/llama-android.cpp
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

namespace {
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
}

#ifdef __cplusplus
extern "C" {
#endif

jclass la_int_var;
jmethodID la_int_var_value;
jmethodID la_int_var_inc;

std::string cached_token_chars;

bool is_valid_utf8(const char *string) {
    if (!string) {
        return true;
    }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
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

extern "C"
JNIEXPORT jlong JNICALL
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

extern "C"
JNIEXPORT void JNICALL
Java_com_example_llama_Llm_free_1model(JNIEnv * /*unused*/, jobject /*unused*/, jlong model) {
    llama_free_model(reinterpret_cast<llama_model *>(model)); // NOLINT(*-no-int-to-ptr)
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_llama_Llm_new_1context(JNIEnv *env, jobject /*unused*/, jlong jmodel, jint seed, jint n_ctx, jint n_threads) {
    auto *model = reinterpret_cast<llama_model *>(jmodel); // NOLINT(*-no-int-to-ptr)

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

    llama_context *context = llama_new_context_with_model(model, ctx_params);

    if (!context) {
        LOGe("llama_new_context_with_model() returned null");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "llama_new_context_with_model() returned null");
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_llama_Llm_free_1context(JNIEnv * /*unused*/, jobject /*unused*/, jlong context) {
    llama_free(reinterpret_cast<llama_context *>(context)); // NOLINT(*-no-int-to-ptr)
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_llama_Llm_backend_1free(JNIEnv * /*unused*/, jobject /*unused*/) {
    llama_backend_free();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_llama_Llm_log_1to_1android(JNIEnv * /*unused*/, jobject /*unused*/) {
    llama_log_set(log_callback, nullptr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_llama_Llm_free_1batch(JNIEnv * /*unused*/, jobject /*unused*/,
                                       jlong batch_pointer) {
    auto *batch = reinterpret_cast<llama_batch *>(batch_pointer); // NOLINT
    llama_batch_free(*batch);
    delete batch;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_llama_Llm_new_1batch(JNIEnv * /*unused*/, jobject /*unused*/, jint n_tokens,
                                      jint embd, jint n_seq_max) {

    // Source: Copy of llama.cpp:llama_batch_init but heap-allocated.

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

    if (embd) {
        batch->embd = (float *) malloc(sizeof(float) * n_tokens * embd);
    } else {
        batch->token = (llama_token *) malloc(sizeof(llama_token) * n_tokens);
    }

    batch->pos = (llama_pos *) malloc(sizeof(llama_pos) * n_tokens);
    batch->n_seq_id = (int32_t *) malloc(sizeof(int32_t) * n_tokens);
    batch->seq_id = (llama_seq_id **) malloc(sizeof(llama_seq_id *) * (n_tokens + 1)); // NOTE: n_tokens + 1
    for (int i = 0; i < n_tokens; ++i) {
        batch->seq_id[i] = (llama_seq_id *) malloc(sizeof(llama_seq_id) * n_seq_max);
    }
    batch->seq_id[n_tokens] = nullptr; // NOTE: Add this line
    batch->logits = (int8_t *) malloc(sizeof(int8_t) * n_tokens);

    return reinterpret_cast<jlong>(batch);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_llama_Llm_backend_1init(JNIEnv * /*unused*/, jobject /*unused*/) {
    llama_backend_init();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_llama_Llm_system_1info(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jint JNICALL
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
    auto *const batch = reinterpret_cast<llama_batch *>(batch_pointer); // NOLINT(*-no-int-to-ptr)
    llama_get_model(context);

    LOGi("=== Prompt Analysis ===");
    LOGi("Input text: %s", text);

    const auto tokens_list = llama_tokenize(context, text, true);

    LOGi("Total tokens: %zu", tokens_list.size());

    auto n_ctx = llama_n_ctx(context);
    auto n_kv_req = tokens_list.size() + (n_len - tokens_list.size());

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

extern "C"
JNIEXPORT jstring JNICALL
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

    auto n_vocab = llama_n_vocab(model);
    auto *logits = llama_get_logits_ith(context, batch->n_tokens - 1);

    std::vector<llama_token_data> candidates;
    candidates.reserve(n_vocab);

    for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
        candidates.emplace_back(llama_token_data{token_id, logits[token_id], 0.0f});
    }

    llama_token_data_array candidates_p = {candidates.data(), candidates.size(), false};

    // トークン選択とそのスコアをログ出力
    const auto new_token_id = llama_sample_token_greedy(context, &candidates_p);
    float token_score = candidates[new_token_id].logit;

    const auto n_cur = env->CallIntMethod(intvar_ncur, la_int_var_value);

    // EOS token check
    if (llama_token_is_eog(model, new_token_id)) {
        char piece[64] = {0};
        int length = llama_token_to_piece(model, new_token_id, piece, sizeof(piece), true);

        if (length >= 0) {
            LOGi("Token[%d] '%s' (score: %.4f, position: %d/%d) - EOS token detected",
                 new_token_id,
                 piece,
                 token_score,
                 n_cur + 1,
                 n_len);
        } else {
            LOGi("Token[%d] '' (score: %.4f, position: %d/%d) - EOS token detected",
                 new_token_id,
                 token_score,
                 n_cur + 1,
                 n_len);
        }
        return env->NewStringUTF("");
    }

    // Max tokens check
    if (n_cur >= n_len) {
        LOGi("MAX_TOKENS_REACHED: n_cur(%d) >= n_len(%d) - stopping generation", n_cur, n_len);
        return env->NewStringUTF("<MAX_TOKENS_REACHED>");
    }

    // process tokens
    jstring new_token;
    auto new_token_chars = llama_token_to_piece(context, new_token_id);
    cached_token_chars += new_token_chars;

    if (is_valid_utf8(cached_token_chars.c_str())) {
        // 改行文字を可視化して表示
        std::string display_chars = cached_token_chars;
        if (display_chars == "\n") {
            display_chars = "\\n";

            // バッチの更新とデコードを先に実行
            llama_batch_clear(*batch);
            llama_batch_add(*batch, new_token_id, n_cur, {0}, true);

            if (llama_decode(context, *batch) != 0) {
                LOGe("llama_decode() failed on token [%d]", new_token_id);
            }

            // 次のトークンをプレビューして"User:"のパターンをチェック
            auto *next_logits = llama_get_logits_ith(context, 0);  // 最後のトークンのlogitsを取得
            if (next_logits != nullptr) {
                std::vector<llama_token_data> next_candidates;
                next_candidates.reserve(n_vocab);
                for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
                    next_candidates.emplace_back(
                            llama_token_data{token_id, next_logits[token_id], 0.0f});
                }
                llama_token_data_array next_candidates_p = {next_candidates.data(),
                                                            next_candidates.size(), false};
                const auto next_token_id = llama_sample_token_greedy(context, &next_candidates_p);

                auto next_chars = llama_token_to_piece(context, next_token_id);
                if (next_chars == "User") {
                    LOGi("Token[%d] '%s' (score: %.4f, position: %d/%d)",
                         new_token_id, display_chars.c_str(), token_score, n_cur + 1, n_len);
                    LOGi("Detected potential role switch to User, stopping generation");
                    return env->NewStringUTF("<EOS_TOKEN_DETECTED>");
                }
            }
        }

        new_token = env->NewStringUTF(cached_token_chars.c_str());
        LOGi("Token[%d] '%s' (score: %.4f, position: %d/%d)",
             new_token_id, display_chars.c_str(), token_score, n_cur + 1, n_len);
        cached_token_chars.clear();
    } else {
        LOGi("Invalid UTF-8 sequence detected for token [%d], skipping", new_token_id);
        new_token = env->NewStringUTF("");
    }

    // 改行以外のトークンの場合のバッチ更新
    if (cached_token_chars != "\n") {
        llama_batch_clear(*batch);
        llama_batch_add(*batch, new_token_id, n_cur, {0}, true);

        if (llama_decode(context, *batch) != 0) {
            LOGe("llama_decode() failed on token [%d]", new_token_id);
        }
    }

    env->CallVoidMethod(intvar_ncur, la_int_var_inc);

    return new_token;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_llama_Llm_kv_1cache_1clear(JNIEnv * /*unused*/, jobject /*unused*/,
                                            jlong context) {
    llama_kv_cache_clear(reinterpret_cast<llama_context *>(context)); // NOLINT(*-no-int-to-ptr)
}

#ifdef __cplusplus
}
#endif

#pragma clang diagnostic pop
