# Android Studio: `llama.cpp-b2710/examples/llama.android/`からBenchボタンおよびその機能を削除する

このガイドでは、Android Studioの`llama.cpp-b2710/examples/llama.android/`プロジェクトから**Bench**ボタンとそれに関連するすべての機能を削除するためのステップバイステップの手順を提供します。変更内容はMarkdown形式で文書化されており、必要に応じて簡単に変更を元に戻すことができます。

> **注意:** 重要な変更を行う前に、必ずバックアップを取るか、バージョン管理（例：Git）を使用してください。

---

## 目次

1. [概要](#概要)
2. [ステップ1: UIからBenchボタンを削除する](#ステップ1-uiからbenchボタンを削除する)
3. [ステップ2: `MainViewModel.kt`からBench機能を削除する](#ステップ2-mainviewmodelktからbench機能を削除する)
4. [ステップ3: `Llm.kt`からBench関連のメソッドを削除する](#ステップ3-llmktからbench関連のメソッドを削除する)
5. [ステップ4: ネイティブコード(`llama-android.cpp`)からBench機能を削除する](#ステップ4-ネイティブコードllama-androidcppからbench機能を削除する)
6. [ステップ5: クリーンアップと確認](#ステップ5-クリーンアップと確認)
7. [バックアップとリバージョン](#バックアップとリバージョン)

---

## 概要

**Bench**ボタンおよびその機能は複数のファイルに実装されています：

- **UIコンポーネント:** `MainActivity.kt`
- **ViewModelロジック:** `MainViewModel.kt`
- **バックエンドロジック:** `Llm.kt`
- **ネイティブコード (C++):** `llama-android.cpp`

クリーンに削除するためには、これらのファイル全体で関連するコードセグメントを特定し、削除またはコメントアウトする必要があります。

---

## ステップ1: UIからBenchボタンを削除する

**ファイル:** `app/src/main/java/com/example/llama/MainActivity.kt`

### 手順:

1. **`MainCompose` Composable関数を探す:**
   - この関数はボタンを含むUIコンポーネントを定義しています。

2. **Benchボタンを特定する:**
   - BenchボタンはSend、Clear、Copyなどの他のボタンと一緒に`Row`内にあります。

3. **Benchボタンを削除またはコメントアウトする:**

   ### 削除前:
   ```kotlin
   Row(
       modifier = Modifier
           .fillMaxWidth()
           .padding(vertical = 4.dp),
       horizontalArrangement = Arrangement.spacedBy(8.dp)
   ) {
       Button(onClick = { viewModel.send() }) { Text("Send") }
       Button(onClick = { viewModel.bench(8, 4, 1) }) { Text("Bench") }
       Button(onClick = { viewModel.clear() }) { Text("Clear") }
       Button(onClick = {
           viewModel.messages.joinToString("\n").let {
               clipboard.setPrimaryClip(ClipData.newPlainText("", it))
           }
       }) { Text("Copy") }
   }
   ```

   ### 削除後:
   ```kotlin
   Row(
       modifier = Modifier
           .fillMaxWidth()
           .padding(vertical = 4.dp),
       horizontalArrangement = Arrangement.spacedBy(8.dp)
   ) {
       Button(onClick = { viewModel.send() }) { Text("Send") }
       // Button(onClick = { viewModel.bench(8, 4, 1) }) { Text("Bench") }
       Button(onClick = { viewModel.clear() }) { Text("Clear") }
       Button(onClick = {
           viewModel.messages.joinToString("\n").let {
               clipboard.setPrimaryClip(ClipData.newPlainText("", it))
           }
       }) { Text("Copy") }
   }
   ```

   > **代替案:** 変更を簡単に元に戻せるように、Benchボタンを削除する代わりにコメントアウトすることもできます：
   >
   > ```kotlin
   > /*
   > Button(onClick = { viewModel.bench(8, 4, 1) }) { Text("Bench") }
   > */
   > ```

---

## ステップ2: `MainViewModel.kt`からBench機能を削除する

**ファイル:** `app/src/main/java/com/example/llama/MainViewModel.kt`

### 手順:

1. **`bench`関数を探す:**
   - この関数はBenchボタンが押されたときに実行されるロジックを処理します。

2. **`bench`関数を削除またはコメントアウトする:**

   ### 削除前:
   ```kotlin
   fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
       viewModelScope.launch {
           try {
               val start = System.nanoTime()
               val warmupResult = llm.bench(pp, tg, pl, nr)
               val end = System.nanoTime()

               messages += warmupResult

               val warmup = (end - start).toDouble() / NanosPerSecond
               messages += "Warm up time: $warmup seconds, please wait..."

               if (warmup > 5.0) {
                   messages += "Warm up took too long, aborting benchmark"
                   return@launch
               }

               messages += llm.bench(512, 128, 1, 3)
           } catch (exc: IllegalStateException) {
               Log.e(tag, "bench() failed", exc)
               messages += exc.message!!
           }
       }
   }
   ```

   ### 削除後:
   ```kotlin
   // fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
   //     viewModelScope.launch {
   //         try {
   //             val start = System.nanoTime()
   //             val warmupResult = llm.bench(pp, tg, pl, nr)
   //             val end = System.nanoTime()

   //             messages += warmupResult

   //             val warmup = (end - start).toDouble() / NanosPerSecond
   //             messages += "Warm up time: $warmup seconds, please wait..."

   //             if (warmup > 5.0) {
   //                 messages += "Warm up took too long, aborting benchmark"
   //                 return@launch
   //             }

   //             messages += llm.bench(512, 128, 1, 3)
   //         } catch (exc: IllegalStateException) {
   //             Log.e(tag, "bench() failed", exc)
   //             messages += exc.message!!
   //         }
   //     }
   // }
   ```

3. **他に参照がないことを確認する:**
   - `MainViewModel.kt`ファイル全体を検索し、`bench`関数への他の参照がないことを確認します。

---

## ステップ3: `Llm.kt`からBench関連のメソッドを削除する

**ファイル:** `app/src/main/java/com/example/llama/Llm.kt`

### 手順:

1. **`bench`関数を探す:**
   - このsuspend関数はネイティブのBench機能とやり取りします。

2. **`bench`関数を削除またはコメントアウトする:**

   ### 削除前:
   ```kotlin
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
   ```

   ### 削除後:
   ```kotlin
   // suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
   //     return withContext(runLoop) {
   //         when (val state = threadLocalState.get()) {
   //             is State.Loaded -> {
   //                 Log.d(tag, "bench(): $state")
   //                 // ローカルでバッチを生成
   //                 val batch = new_batch(512, 0, 1)
   //                 if (batch == 0L) throw IllegalStateException("new_batch() failed")
   //                 val result = bench_model(state.context, state.model, pp, tg, pl, nr)
   //                 free_batch(batch)
   //                 result
   //             }

   //             else -> throw IllegalStateException("No model loaded")
   //         }
   //     }
   // }
   ```

3. **`bench_model`の外部関数宣言を削除する:**

   ### 削除前:
   ```kotlin
   private external fun bench_model(
       context: Long,
       model: Long,
       pp: Int,
       tg: Int,
       pl: Int,
       nr: Int
   ): String
   ```

   ### 削除後:
   ```kotlin
   // private external fun bench_model(
   //     context: Long,
   //     model: Long,
   //     pp: Int,
   //     tg: Int,
   //     pl: Int,
   //     nr: Int
   // ): String
   ```

4. **他に参照がないことを確認する:**
   - `Llm.kt`ファイル全体を検索し、Bench機能への他の参照がないことを確認します。

---

## ステップ4: ネイティブコード(`llama-android.cpp`)からBench機能を削除する

**ファイル:** `app/src/main/cpp/llama-android.cpp`

### 手順:

1. **Bench用のJNI関数を探す:**
   - Bench機能はJNI関数を介してKotlinに公開されています。

2. **`Java_com_example_llama_Llm_bench_1model`関数を削除またはコメントアウトする:**

   ### 削除前:
   ```cpp
   extern "C"
   JNIEXPORT jstring JNICALL
   Java_com_example_llama_Llm_bench_1model(
           JNIEnv *env,
           jobject /*unused*/,
           jlong context_pointer,
           jlong model_pointer,
           jlong batch_pointer,
           jint pp,
           jint tg,
           jint pl,
           jint nr
   ) {
       // 関数の実装...
   }
   ```

   ### 削除後:
   ```cpp
   /*
   extern "C"
   JNIEXPORT jstring JNICALL
   Java_com_example_llama_Llm_bench_1model(
           JNIEnv *env,
           jobject /*unused*/,
           jlong context_pointer,
           jlong model_pointer,
           jlong batch_pointer,
           jint pp,
           jint tg,
           jint pl,
           jint nr
   ) {
       // 関数の実装...
   }
   */
   ```

3. **Bench関連のヘルパー関数を確認する:**
   - Bench機能専用に使用されているヘルパー関数や変数があれば、それらも削除またはコメントアウトします。
   - `bench_model`が主なBench関連の関数です。

4. **残留するBenchコードがないことを確認する:**
   - `llama-android.cpp`内で`bench`などのキーワードを検索し、関連するコードが残っていないことを確認します。

---

## ステップ5: クリーンアップと確認

### 手順:

1. **プロジェクトをクリーンする:**
   - Android Studioで`Build` > `Clean Project`に移動し、古いビルドアーティファクトを削除します。

2. **プロジェクトを再ビルドする:**
   - `Build` > `Rebuild Project`に移動し、プロジェクトがエラーなくコンパイルされることを確認します。

3. **アプリケーションを実行する:**
   - デバイスまたはエミュレーターにアプリをデプロイして、以下を確認します：
     - BenchボタンがUIに表示されなくなっていること。
     - 他のすべての機能（Send、Clear、Copy、Memory Info、Model Path）が期待通りに動作すること。
     - 削除したBench機能に関連するランタイムエラーが発生しないこと。

4. **徹底的にテストする:**
   - アプリのさまざまなフローを試して、削除がアプリケーションの他の部分に予期せぬ影響を与えていないことを確認します。

---

## バックアップとリバージョン

将来的にBench機能を復元できるように、以下のバックアップ戦略を検討してください：

### 1. **バージョン管理を使用する（推奨）:**

- **Git:**
  - まだ行っていない場合は、Gitリポジトリを初期化します：
    ```bash
    git init
    ```
  - 変更を行う前に現在の状態をステージングしてコミットします：
    ```bash
    git add .
    git commit -m "Bench機能削除前のバックアップ"
    ```
  - 変更後、必要に応じてこのコミットに戻ることができます：
    ```bash
    git checkout <commit-hash>
    ```

### 2. **削除する代わりにコメントアウトする:**

- 上記の手順で示したように、Bench関連のコードを削除する代わりにコメントアウトします。これにより、必要に応じて簡単にコードのコメントを解除して機能を復元できます。

### 3. **別のブランチを作成する:**

- 変更を行う前に新しいGitブランチを作成します：
  ```bash
  git checkout -b remove-bench-button
  ```
- このブランチで変更を行います。必要に応じて、メインブランチに戻ることができます：
  ```bash
  git checkout main
  ```

### 4. **手動バックアップ:**

- **ファイルをコピーする:**
  - 変更を加える前に、各ファイルのコピーを作成します。例えば：
    - `MainActivity.kt` → `MainActivity.kt.bak`
    - `MainViewModel.kt` → `MainViewModel.kt.bak`
    - その他のファイルも同様に。

- **必要に応じて復元する:**
  - Bench機能を復元するには、変更されたファイルをバックアップ版に置き換えます。

---

## 結論

上記の手順に従うことで、`llama.cpp-b2710/examples/llama.android/`プロジェクトからBenchボタンおよび関連機能を効果的に削除することができます。これらの変更を行った後は、アプリケーションの安定性を維持するためにテストを行ってください。さらに、堅牢なバックアップおよびバージョン管理戦略を採用することで、将来的にBench機能を再統合する際に容易にリバートできるようになります。

---
