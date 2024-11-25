# llama.cppにおけるシステムプロンプトの扱いに関する分析結果

## 背景と目的

本分析の目的は、**llama.cpp**のコード設計において、LLM（大規模言語モデル）がシステムプロンプト（`systemPrompt`）やユーザープロンプト（`userPrompt`）を個別に入力として受け取る仕様になっているかを確認することです。

具体的には、以下の点を明確にします：

- **llama.cpp**のコードにおいて、システムプロンプトやユーザープロンプトを個別に設定する変数や機能が存在するか。
- モデルへの入力がどのように処理され、推論が行われているか。

## 結論

**llama.cppのコード設計では、`systemPrompt`や`userPrompt`のような入力項目は存在せず、ユーザからの入力テキストを単一の文字列として扱い、それをトークン化して推論を行っています。**

したがって、モデルはシステムプロンプトを別個に受け取る設計にはなっておらず、システムプロンプトを使用したい場合は、ユーザ入力と組み合わせて単一の文字列としてモデルに渡す必要があります。

## 分析のポイント

### 1. コード全体の流れ

- **ユーザ入力**：ユーザが入力したテキスト（例：質問文など）。
- **`message`変数**：ユーザ入力を保持するための変数。
- **モデルへの入力**：`message`をそのまま、または加工してモデルに渡す。

### 2. `Llm.kt`の`send`関数

```kotlin
suspend fun send(message: String, nLen: Int, seed: Int, n_ctx: Int, n_threads: Int): Flow<String> = flow {
    // ...
    val context = new_context(state.model, seed, n_ctx, n_threads)
    // ...
    val batch = new_batch(context, 2048, 0, 1)
    // ...
    val ncur = IntVar(completion_init(context, batch, message, nLen))
    // ...
}
```

- **ポイント**：`send`関数では、`message`（ユーザ入力）を直接`completion_init`関数に渡しています。
- **システムプロンプトを別途渡す処理は存在しません。**

### 3. `MainViewModel.kt`の`send`関数

```kotlin
fun send() {
    val text = message.trim()
    if (text.isEmpty()) return
    message = ""
    val formattedPrompt = "<|user|>$text<|endofuser|>\n<|assistant|>"
    // ...
    llm.send(formattedPrompt, maxTokens, seed, contextSize, numThreads)
    // ...
}
```

- **ポイント**：`formattedPrompt`を作成していますが、これはユーザ入力を特殊トークンで囲んだものです。
- **`systemPrompt`変数は定義されていますが、`send`関数内で使用していません。**
- **システムプロンプトをモデルに渡す処理は行われていません。**

### 4. `llama-android.cpp`の`completion_init`関数

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_example_llama_Llm_completion_1init(JNIEnv *env, jobject, jlong context_pointer, jlong batch_pointer, jstring jtext, jint n_len) {
    const auto *const text = env->GetStringUTFChars(jtext, nullptr);
    auto *const context = reinterpret_cast<llama_context *>(context_pointer);
    // ...
    const auto tokens_list = llama_tokenize(context, text, true);
    // ...
}
```

- **ポイント**：`jtext`として渡されるのは、`message`または`formattedPrompt`です。
- **`text`はユーザ入力の文字列のみであり、システムプロンプトを別途処理する箇所はありません。**

### 5. トークン化とモデルへの入力

#### トークン化 (`llama_tokenize`)

```cpp
int32_t llama_tokenize(const struct llama_model * model, const char * text, int32_t text_len, llama_token * tokens, int32_t n_tokens_max, bool add_special, bool parse_special) {
    auto res = llama_tokenize_internal(model->vocab, std::string(text, text_len), add_special, parse_special);
    // ...
}
```

- **ポイント**：`text`（ユーザ入力）をトークン化します。
- **システムプロンプトを別途追加する処理はありません。**

#### モデルへの入力設定 (`llama_set_inputs`)

```cpp
static void llama_set_inputs(llama_context & lctx, const llama_batch & batch) {
    if (batch.token) {
        ggml_backend_tensor_set(lctx.inp_tokens, batch.token, 0, n_tokens * ggml_element_size(lctx.inp_tokens));
    }
    // ...
}
```

- **ポイント**：`batch`内のトークン情報（ユーザ入力から生成されたもの）をモデルの入力テンソルに設定します。
- **システムプロンプトを別個に設定する処理はありません。**

### 6. 推論処理 (`llama_decode_internal`)

```cpp
static int llama_decode_internal(llama_context & lctx, llama_batch batch_all) {
    // ...
    llama_set_inputs(lctx, u_batch);
    llama_graph_compute(lctx, gf, n_threads);
    // ...
}
```

- **ポイント**：`llama_set_inputs`で設定した入力データを用いて、モデルの推論が行われます。
- **ユーザ入力のトークンのみがモデルに渡されています。**

## ドキュメントの記載

- **特殊トークンの扱い**：ドキュメントでは、ユーザが指定したテキストに対し、自動で特殊トークンが追加される仕様となっています。
- **システムプロンプト**：推論ツール側で内部的に設定されるものであり、モデル自体がシステムプロンプトを別個に受け取る仕様ではないと記載されています。

## まとめ

- **llama.cppのコード設計では、システムプロンプトやユーザープロンプトを個別に設定する変数や機能は存在しません。**
- **モデルへの入力は、ユーザ入力テキスト（場合によっては特殊トークンで囲まれたもの）を単一の文字列として扱い、それをトークン化してモデルに渡しています。**
- **システムプロンプトをモデルに渡したい場合は、ユーザ入力と組み合わせて一つのテキストとしてモデルに渡す必要があります。**

## システムプロンプトを使用する方法

- **方法**：`message`や`formattedPrompt`を構築する際に、システムプロンプトを含めます。
- **例**：

```kotlin
val formattedPrompt = "<|system|>$systemPrompt<|endofsystem|>\n<|user|>$message<|endofuser|>\n<|assistant|>"
```

- **注意点**：モデルが特殊トークン（`<|system|>`など）を理解し、適切に処理できるかを確認する必要があります。

## 最終的な結論

- **モデル自体がシステムプロンプトを別個に受け取る設計にはなっていません。**
- **コードとドキュメントの分析から、モデルへの入力は単一の文字列として処理されていることが確認できました。**
- **システムプロンプトを使用したい場合は、ユーザ入力と組み合わせてモデルに渡す必要があります。**

---

**本分析により、llama.cppのコード設計において、システムプロンプトを個別に入力する仕様になっていないことが明確に確認できました。**
