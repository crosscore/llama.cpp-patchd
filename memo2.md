**結論：**

モデル自体がシステムプロンプトを別個に受け取る設計になっていないことは、以下のコードとドキュメントの箇所から確認できます。

---

**コードからの根拠：**

1. **`Llm.kt`の`send`関数：**

```kotlin
suspend fun send(message: String, nLen: Int, seed: Int, n_ctx: Int, n_threads: Int): Flow<String> = flow {
    // 省略...

    val context = new_context(state.model, seed, n_ctx, n_threads)
    if (context == 0L) throw IllegalStateException("new_context() failed")

    try {
        val batch = new_batch(
            context,  // コンテキストポインターを渡す
            2048,    // nTokens
            0,       // embd
            1        // nSeqMax
        )
        if (batch == 0L) throw IllegalStateException("new_batch() failed")

        try {
            val ncur = IntVar(completion_init(context, batch, message, nLen))
            // 省略...
        } finally {
            free_batch(batch)
        }
    } finally {
        free_context(context)
    }
}
```

- **ポイント：** この関数では、ユーザからの入力である`message`のみを`completion_init`関数に渡しています。システムプロンプトやその他のプロンプトを別途渡すための仕組みがありません。

2. **`MainViewModel.kt`の`send`関数：**

```kotlin
fun send() {
    val text = message.trim()
    if (text.isEmpty()) return
    message = ""

    val formattedPrompt = "<|user|>$text<|endofuser|>\n<|assistant|>"

    Log.d(tag, "--- Sending prompt ---\n$formattedPrompt")
    messages = messages + Pair(text, "")
    val currentIndex = messages.lastIndex

    sendJob = viewModelScope.launch {
        val responseBuilder = StringBuilder()
        try {
            llm.send(formattedPrompt, maxTokens, seed, contextSize, numThreads)
                // 省略...
        } catch (e: Exception) {
            // 省略...
        }
    }
}
```

- **ポイント：** `formattedPrompt`を作成していますが、これはユーザの入力を特殊トークンで囲んだものです。`systemPrompt`という変数は定義されていますが、この関数内では使用されておらず、システムプロンプトをモデルに渡す処理は行われていません。

3. **`llama-android.cpp`の`completion_init`関数：**

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_example_llama_Llm_completion_1init(
        JNIEnv *env,
        jobject /*unused*/,
        jlong context_pointer,
        jlong batch_pointer,
        jstring jtext,
        jint n_len
) {
    const auto *const text = env->GetStringUTFChars(jtext, nullptr);
    auto *const context = reinterpret_cast<llama_context *>(context_pointer);
    // 省略...

    const auto tokens_list = llama_tokenize(context, text, true);
    // 省略...
}
```

- **ポイント：** `jtext`として渡されるのは、上記の`message`または`formattedPrompt`であり、システムプロンプトを別個に扱う処理は見当たりません。

---

**ドキュメントからの根拠：**

1. **「入力テキスト仕様および特殊トークンについて」のセクション：**

> 本推論ツールではユーザが指定したテキストに対し、自動で特殊トークンを追加しており、ユーザ指定のテキストを`<|user|> ... <|endofuser|>` で囲む仕様となっている。
>
> また、本推論ツールはマルチターンには対応していない。

- **ポイント：** ユーザの入力テキストのみを特殊トークンで囲んでモデルに渡す仕様であり、システムプロンプトを別途設定する仕組みがないことがわかります。

2. **「システムプロンプト」のセクション：**

> 実際にLLMに入力する「インストラクション」の前に、「システムプロンプト」を与え、出力を制御することが可能である。
>
> `prompt_type`の指定により制御を行う。本推論ツールでは、`simple` = 何も指定しない、`detail` = 詳細に出力、`detail_safe` = 詳細かつ安全に出力 が選択可能である。

- **ポイント：** ここで言及されているシステムプロンプトは、推論ツール（CT2版推論ツール）側で内部的に設定されるものであり、モデル自体がシステムプロンプトを別個に受け取る設計ではないことが示唆されています。

---

**まとめ：**

- **コード上の確認：** `send`関数や`completion_init`関数では、ユーザからの入力のみがモデルに渡されており、システムプロンプトを個別に設定・渡すための変数や関数は存在しません。また、`systemPrompt`という変数は存在しますが、モデルへの入力には使用されていません。

- **ドキュメントの確認：** ドキュメントでも、システムプロンプトは推論ツール側で制御されるものであり、モデル自体がシステムプロンプトを別個に受け取る仕様ではないことが記載されています。

---

**補足：**

- **他のファイルの確認について：** これらのコードとドキュメントから、モデルがシステムプロンプトを別個に受け取る設計になっていないことが確認できます。他の`llama.cpp`のファイルを確認する必要はありません。

- **システムプロンプトを使用したい場合：** もしシステムプロンプトをモデルに渡したい場合は、`formattedPrompt`を以下のように変更し、`systemPrompt`を含める方法があります。

  ```kotlin
  val formattedPrompt = "<|system|>$systemPrompt<|endofsystem|>\n<|user|>$text<|endofuser|>\n<|assistant|>"
  ```

  ただし、この変更を行う場合、モデルが`<|system|>`や`<|endofsystem|>`の特殊トークンを正しく理解し、期待通りに動作するかを確認する必要があります。

---

**結論：**

- モデル自体がシステムプロンプトを別個に受け取る設計になっていないことは、コードおよびドキュメントの上記の箇所から確定できます。
