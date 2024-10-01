# Android Studio: Removing the Bench Button and Its Functionality from `llama.cpp-b2710/examples/llama.android/`

This guide provides step-by-step instructions to remove the **Bench** button and all related functionalities from the `llama.cpp-b2710/examples/llama.android/` project in Android Studio. The modifications are documented in Markdown format, ensuring that you can easily revert the changes if needed.

> **Note:** Always ensure you have a backup or use version control (e.g., Git) before making significant changes to your codebase.

---

## Table of Contents

1. [Overview](#overview)
2. [Step 1: Remove the Bench Button from the UI](#step-1-remove-the-bench-button-from-the-ui)
3. [Step 2: Remove Bench Functionality in `MainViewModel.kt`](#step-2-remove-bench-functionality-in-mainviewmodelkt)
4. [Step 3: Remove Bench-Related Methods in `Llm.kt`](#step-3-remove-bench-related-methods-in-llmkt)
5. [Step 4: Remove Bench Functionality in Native Code (`llama-android.cpp`)](#step-4-remove-bench-functionality-in-native-codellama-androidcpp)
6. [Step 5: Clean Up and Verify](#step-5-clean-up-and-verify)
7. [Backup and Reversion](#backup-and-reversion)

---

## Overview

The **Bench** button and its functionalities are implemented across multiple files:

- **UI Component:** `MainActivity.kt`
- **ViewModel Logic:** `MainViewModel.kt`
- **Backend Logic:** `Llm.kt`
- **Native Code (C++):** `llama-android.cpp`

To ensure a clean removal, all related code segments across these files need to be identified and deleted or commented out.

---

## Step 1: Remove the Bench Button from the UI

**File:** `app/src/main/java/com/example/llama/MainActivity.kt`

### Actions:

1. **Locate the `MainCompose` Composable Function:**
   - This function defines the UI components, including the buttons.

2. **Identify the Bench Button:**
   - The Bench button is within a `Row` alongside other buttons like Send, Clear, and Copy.

3. **Remove or Comment Out the Bench Button:**

   ### Before Removal:
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

   ### After Removal:
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

   > **Alternative:** To facilitate easy reversion, you can comment out the Bench button instead of deleting it:
   >
   > ```kotlin
   > /*
   > Button(onClick = { viewModel.bench(8, 4, 1) }) { Text("Bench") }
   > */
   > ```

---

## Step 2: Remove Bench Functionality in `MainViewModel.kt`

**File:** `app/src/main/java/com/example/llama/MainViewModel.kt`

### Actions:

1. **Locate the `bench` Function:**
   - This function handles the logic executed when the Bench button is pressed.

2. **Delete or Comment Out the `bench` Function:**

   ### Before Removal:
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

   ### After Removal:
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

3. **Ensure No Other References Exist:**
   - Search the entire `MainViewModel.kt` file to confirm there are no other references to the `bench` function.

---

## Step 3: Remove Bench-Related Methods in `Llm.kt`

**File:** `app/src/main/java/com/example/llama/Llm.kt`

### Actions:

1. **Locate the `bench` Function:**
   - This suspend function interacts with the native Bench functionality.

2. **Delete or Comment Out the `bench` Function:**

   ### Before Removal:
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

   ### After Removal:
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

3. **Remove the `bench_model` External Function Declaration:**

   ### Before Removal:
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

   ### After Removal:
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

4. **Ensure No Other References Exist:**
   - Search the entire `Llm.kt` file to confirm there are no other references to the Bench functionality.

---

## Step 4: Remove Bench Functionality in Native Code (`llama-android.cpp`)

**File:** `app/src/main/cpp/llama-android.cpp`

### Actions:

1. **Locate the JNI Function for Bench:**
   - The Bench functionality is exposed to Kotlin via JNI functions.

2. **Delete or Comment Out the `Java_com_example_llama_Llm_bench_1model` Function:**

   ### Before Removal:
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
       // Function implementation...
   }
   ```

   ### After Removal:
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
       // Function implementation...
   }
   */
   ```

3. **Check for Bench-Related Helper Functions:**
   - Ensure that any helper functions or variables exclusively used by the Bench functionality are also removed or commented out.
   - From the provided code, it appears that `bench_model` is the primary Bench-related function.

4. **Verify No Residual Bench Code Exists:**
   - Perform a search within `llama-android.cpp` for keywords like `bench` to ensure no related code remains.

---

## Step 5: Clean Up and Verify

### Actions:

1. **Clean the Project:**
   - In Android Studio, navigate to `Build` > `Clean Project` to remove any old build artifacts.

2. **Rebuild the Project:**
   - Navigate to `Build` > `Rebuild Project` to ensure that the project compiles without errors.

3. **Run the Application:**
   - Deploy the app to a device or emulator to verify that:
     - The Bench button is no longer visible in the UI.
     - All other functionalities (Send, Clear, Copy, Memory Info, Model Path) operate as expected.
     - No runtime errors related to the removed Bench functionality occur.

4. **Test Thoroughly:**
   - Engage in different app flows to ensure stability and that the removal hasn't inadvertently affected other parts of the application.

---

## Backup and Reversion

To ensure that you can restore the Bench functionality in the future, consider the following backup strategies:

### 1. **Use Version Control (Recommended):**

- **Git:**
  - Initialize a Git repository if not already done:
    ```bash
    git init
    ```
  - Stage and commit the current state before making changes:
    ```bash
    git add .
    git commit -m "Backup before removing Bench functionality"
    ```
  - After making changes, you can always revert to this commit if needed:
    ```bash
    git checkout <commit-hash>
    ```

### 2. **Comment Out Instead of Deleting:**

- As demonstrated in the steps above, comment out the Bench-related code instead of deleting it. This allows you to easily uncomment the code if you wish to restore the functionality.

### 3. **Create a Separate Branch:**

- Create a new Git branch before making changes:
  ```bash
  git checkout -b remove-bench-button
  ```
- Make all modifications in this branch. If you need to revert, you can switch back to the main branch:
  ```bash
  git checkout main
  ```

### 4. **Manual Backup:**

- **Copy Files:**
  - Before modifying any file, create a copy of it. For example:
    - `MainActivity.kt` → `MainActivity.kt.bak`
    - `MainViewModel.kt` → `MainViewModel.kt.bak`
    - And so on for other files.

- **Restore When Needed:**
  - Replace the modified file with its backup version to restore the Bench functionality.

---

## Conclusion

By following the steps outlined above, you can effectively remove the Bench button and its associated functionalities from the `llama.cpp-b2710/examples/llama.android/` project. Ensure thorough testing after making these changes to maintain application stability. Additionally, adopting a robust backup and version control strategy will facilitate easy reversion if you decide to reintegrate the Bench functionality in the future.

---
