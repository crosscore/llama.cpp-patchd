// llama.cpp-b2710/examples/llama.android/app/src/main/cpp/llama-android.cpp
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
    auto *const batch = reinterpret_cast<llama_batch *>(batch_pointer);
    auto *const model = llama_get_model(context);

    LOGi("=== Input Analysis ===");
    LOGi("Raw input text: %s", text);

    const auto tokens_list = llama_tokenize(context, text, true);

    LOGi("=== Detailed Token Analysis ===");
    LOGi("Total tokens: %zu", tokens_list.size());

    // 特殊トークンの取得
    const llama_token bos_token = llama_token_bos(model);
    const llama_token eos_token = llama_token_eos(model);
    const llama_token nl_token = llama_token_nl(model);
    const llama_token cls_token = llama_token_cls(model);
    const llama_token sep_token = llama_token_sep(model);

    LOGi("Special tokens:");
    LOGi("- BOS token: %d", bos_token);
    LOGi("- EOS token: %d", eos_token);
    LOGi("- Newline token: %d", nl_token);
    LOGi("- CLS token: %d", cls_token);
    LOGi("- SEP token: %d", sep_token);

    // トークンの詳細情報を出力
    char piece_buf[128];
    for (auto id: tokens_list) {
        // トークンをテキストに変換
        int32_t piece_len = llama_token_to_piece(model, id, piece_buf, sizeof(piece_buf), true);
        if (piece_len < 0) {
            LOGe("Failed to convert token to piece");
            continue;
        }
        std::string token_text(piece_buf, piece_len);

        // 制御文字やスペースの可視化
        std::string visible_text = token_text;
        for (size_t i = 0; i < visible_text.length(); i++) {
            char c = visible_text[i];
            if (c == '\n') {
                visible_text.replace(i, 1, "\\n");
                i++;
            } else if (c == '\r') {
                visible_text.replace(i, 1, "\\r");
                i++;
            } else if (c == '\t') {
                visible_text.replace(i, 1, "\\t");
                i++;
            } else if (c == ' ') {
                visible_text.replace(i, 1, "␣");
            }
        }

        // バイト表現の取得
        std::string bytes;
        for (char c : token_text) {
            char hex[8];
            snprintf(hex, sizeof(hex), "\\x%02X", static_cast<unsigned char>(c));
            bytes += hex;
        }

        // トークンタイプの取得
        llama_token_type token_type = llama_token_get_type(model, id);
        const char* type_str;
        switch (token_type) {
            case LLAMA_TOKEN_TYPE_UNDEFINED:    type_str = "UNDEFINED"; break;
            case LLAMA_TOKEN_TYPE_NORMAL:       type_str = "NORMAL"; break;
            case LLAMA_TOKEN_TYPE_UNKNOWN:      type_str = "UNKNOWN"; break;
            case LLAMA_TOKEN_TYPE_CONTROL:      type_str = "CONTROL"; break;
            case LLAMA_TOKEN_TYPE_USER_DEFINED: type_str = "USER_DEFINED"; break;
            case LLAMA_TOKEN_TYPE_UNUSED:       type_str = "UNUSED"; break;
            case LLAMA_TOKEN_TYPE_BYTE:         type_str = "BYTE"; break;
            default:                            type_str = "???"; break;
        }

        // 特殊トークンのチェック
        bool is_eog = llama_token_is_eog(model, id);

        float token_score = llama_token_get_score(model, id);

        // トークン情報の出力
        LOGi("Token[%5d] | Type: %-11s | Score: %8.3f | Raw: '%-20s' | Bytes: %-30s | Visible: '%-20s' %s",
             id,
             type_str,
             token_score,
             token_text.c_str(),
             bytes.c_str(),
             visible_text.c_str(),
             is_eog ? "[EOG]" : "");

        // 特殊なパターンの検出（一般的なパターンを検出）
        if (token_text.find("<|") != std::string::npos ||
            token_text.find("|>") != std::string::npos ||
            token_text.find("<s>") != std::string::npos ||
            token_text.find("</s>") != std::string::npos) {
            LOGi("    ^-- Potential special token pattern detected");
        }
    }

    // モデル情報の出力
    LOGi("=== Model Info ===");
    LOGi("Vocabulary size: %d", llama_n_vocab(model));
    LOGi("Context size: %d", llama_n_ctx(context));
    LOGi("Embedding size: %d", llama_n_embd(model));
    LOGi("Number of layers: %d", llama_n_layer(model));

    // ボキャブラリタイプの出力
    enum llama_vocab_type vocab_type = llama_vocab_type(model);
    const char* vocab_type_str;
    switch (vocab_type) {
        case LLAMA_VOCAB_TYPE_SPM:  vocab_type_str = "SentencePiece"; break;
        case LLAMA_VOCAB_TYPE_BPE:  vocab_type_str = "BPE"; break;
        case LLAMA_VOCAB_TYPE_WPM:  vocab_type_str = "WordPiece"; break;
        case LLAMA_VOCAB_TYPE_NONE: vocab_type_str = "None"; break;
        default:                    vocab_type_str = "Unknown"; break;
    }
    LOGi("Vocabulary type: %s", vocab_type_str);

    auto n_ctx = llama_n_ctx(context);
    auto n_kv_req = tokens_list.size() + (n_len - tokens_list.size());

    LOGi("=== Context Requirements ===");
    LOGi("n_len = %d, n_ctx = %d, n_kv_req = %zu", n_len, n_ctx, n_kv_req);

    if (n_kv_req > n_ctx) {
        LOGe("error: n_kv_req > n_ctx, the required KV cache size is not big enough");
    }

    llama_batch_clear(*batch);

    // evaluate the initial prompt
    for (auto i = 0; i < tokens_list.size(); i++) {
        llama_batch_add(*batch, tokens_list[i], i, {0}, false);
    }

    // llama_decode will output logits only for the last token of the prompt
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
    auto *const context = reinterpret_cast<llama_context *>(context_pointer); // NOLINT(*-no-int-to-ptr)
    auto *const batch = reinterpret_cast<llama_batch *>(batch_pointer); // NOLINT(*-no-int-to-ptr)
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

    // sample the most likely token
    const auto new_token_id = llama_sample_token_greedy(context, &candidates_p);

    const auto n_cur = env->CallIntMethod(intvar_ncur, la_int_var_value);

    // EOSトークンの検出
    if (llama_token_is_eog(model, new_token_id)) {
        LOGi("Detected EOS TOKEN ID: %d", new_token_id);
        return env->NewStringUTF("<EOS_TOKEN_DETECTED>");
    }

    // MaxTokensに達した場合の処理
    if (n_cur >= n_len) {
        LOGi("MAX_TOKENS_REACHED: n_cur(%d) >= n_len(%d)", n_cur, n_len);
        return env->NewStringUTF("<MAX_TOKENS_REACHED>");
    }

    // トークンの処理
    auto new_token_chars = llama_token_to_piece(context, new_token_id);
    cached_token_chars += new_token_chars;

    jstring new_token;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        new_token = env->NewStringUTF(cached_token_chars.c_str());
        LOGi("cached_token: %s, new_token: %s, token_id: %d", cached_token_chars.c_str(), new_token_chars.c_str(), new_token_id);
        cached_token_chars.clear();
    } else {
        new_token = env->NewStringUTF("");
    }

    // バッチの更新
    llama_batch_clear(*batch);
    llama_batch_add(*batch, new_token_id, n_cur, {0}, true);

    env->CallVoidMethod(intvar_ncur, la_int_var_inc);

    if (llama_decode(context, *batch) != 0) {
        LOGe("llama_decode() returned null");
    }

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
