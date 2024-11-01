# プロジェクトの概要

## 目的

Android Studio で開発したプロジェクトを、Jenkins を利用して DashO による難読化を適用しつつビルドを行い、署名済みの APK を生成すること。

## 現在の状況

- **DashO の統合**:
  - `build.gradle.kts` ファイルに DashO の設定を追加しました。
  - DashO のプラグインとリポジトリをルートの `build.gradle.kts` に追加しました。
  - アプリケーションモジュールの `build.gradle.kts` に DashO のプラグインを適用しました。

- **Jenkins 環境でのビルド**:
  - Jenkins のビルドステップで使用するスクリプトを改良しました。
  - スクリプトは Python で記述され、他のプロジェクトのビルドスクリプトを参考にしています。
  - ビルドスクリプトでは、環境変数や `zipalign` のパスを自動的に検出するように改善しました。

- **SDK のバージョン**:
  - プロジェクトの `build.gradle.kts` で `compileSdk` を 34 に設定しました。
  - Build Tools のバージョンを自動的に検出し、`zipalign` のパスを修正しました。

- **ビルドスクリプトの最新版**:
  - `zipalign` のパスやプロジェクトのディレクトリ構成を考慮して、ビルドスクリプトを改良しました。
  - ビルドの成否を確認し、エラー時には適切なメッセージを出力するようにしました。
  - APK の署名については、現状のスクリプトでは行っていませんが、必要に応じて追加することができます。

## 最新のビルドスクリプト

```python
#!/usr/bin/python3
# -- coding: utf-8 --

import os
import shutil
import glob
import subprocess

def build():
    """
    Build the project with DashO obfuscation.
    """
    # Clean the project
    subprocess.call("./gradlew clean", shell=True)

    # Generate DashO config first
    generate_config_command = "./gradlew assembleRelease -DGENERATE_DASHO_CONFIG"
    result = subprocess.run(generate_config_command, shell=True, check=False)

    if result.returncode != 0:
        print("DashO設定ファイルの生成に失敗しました")
        return

    # Build release APK
    build_command = "./gradlew assembleRelease --stacktrace"
    result = subprocess.run(build_command, shell=True, check=False)

    if result.returncode != 0:
        print("Gradleビルドが失敗しました")
        return

    # Paths for APK files
    output_apk = "./app/build/outputs/apk/release/app-release-unsigned.apk"
    final_apk = "../llama-release.apk"

    if not os.path.exists(output_apk):
        print(f"APKファイルが見つかりません: {output_apk}")
        return

    # Try to find zipalign in common locations
    possible_sdk_paths = [
        os.environ.get('ANDROID_HOME', ''),
        '/Users/systena/Library/Android/sdk',
        os.path.expanduser('~/Library/Android/sdk')
    ]

    zipalign_path = None
    for sdk_path in possible_sdk_paths:
        if sdk_path:
            build_tools_dir = os.path.join(sdk_path, 'build-tools')
            if os.path.exists(build_tools_dir):
                # Get the latest version
                versions = sorted(os.listdir(build_tools_dir), reverse=True)
                if versions:
                    zipalign_path = os.path.join(build_tools_dir, versions[0], 'zipalign')
                    if os.path.exists(zipalign_path):
                        break

    if zipalign_path and os.path.exists(zipalign_path):
        # Align the APK
        aligned_apk = "./app/build/outputs/apk/release/app-release-aligned.apk"
        zipalign_command = f"{zipalign_path} -p -f -v 4 {output_apk} {aligned_apk}"
        subprocess.call(zipalign_command, shell=True)

        if os.path.exists(aligned_apk):
            shutil.move(aligned_apk, final_apk)
        else:
            print("Aligned APKの生成に失敗しました")
            # zipalignができない場合は直接移動
            shutil.copy2(output_apk, final_apk)
    else:
        print("zipalignが見つかりませんでした。unsigned APKを直接コピーします。")
        shutil.copy2(output_apk, final_apk)

def setup():
    """
    Prepare for the build.
    """
    # Get the workspace directory
    WORKSPACE = os.environ.get('WORKSPACE', os.getcwd())
    # Set the project root directory
    PROJECT_ROOT = os.path.join(WORKSPACE, "llama.cpp-b2710/examples/llama.android")
    print(f"PROJECT_ROOT: {PROJECT_ROOT}")

    # Change directory to the project root
    os.chdir(PROJECT_ROOT)

    # Remove existing APK files in the parent directory
    for file in glob.glob("../*.apk"):
        os.remove(file)

    # Make gradlew executable
    subprocess.call("chmod +x gradlew", shell=True)

def main():
    setup()
    build()

if __name__ == '__main__':
    main()
```

## ビルド結果と問題点

- **ビルドの成功**:
  - 上記のスクリプトを Jenkins 上で実行し、ビルドは成功しました。

- **APK のインストールエラー**:
  - ビルド後に生成された `llama-release.apk` をデバイスにインストールしようとしたところ、以下のエラーが発生しました。

    ```
    adb: failed to install C:\apk\llama-release.apk: Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: Failed to collect certificates from /data/app/vmdl1165101821.tmp/base.apk: Attempt to get length of null array]
    ```

  - このエラーは、APK が署名されていないために発生しています。Android デバイスにインストールするためには、APK を署名する必要があります。

## 対処方法

### APK の署名を追加する

ビルドプロセスに APK の署名を追加することで、この問題を解決できます。以下の手順で署名の設定を行います。

1. **署名用のキーストアを準備する**:

   - 既にキーストアがある場合は、それを使用します。無い場合は以下のコマンドでキーストアを作成します。

     ```shell
     keytool -genkey -v -keystore my-release-key.jks -alias my_alias_name -keyalg RSA -keysize 2048 -validity 10000
     ```

   - このコマンドで生成された `my-release-key.jks` を安全な場所に保管してください。

2. **`build.gradle.kts` に署名の設定を追加**:

   アプリケーションモジュールの `build.gradle.kts` ファイルに以下の `signingConfigs` を追加します。

   ```kotlin
   android {
       // 既存の設定...

       signingConfigs {
           create("release") {
               storeFile = file("path/to/your/my-release-key.jks")
               storePassword = "your_store_password"
               keyAlias = "my_alias_name"
               keyPassword = "your_key_password"
           }
       }

       buildTypes {
           release {
               isMinifyEnabled = false // for dasho
               proguardFiles(
                   getDefaultProguardFile("proguard-android-optimize.txt"),
                   "proguard-rules.pro"
               )
               signingConfig = signingConfigs.getByName("release")
           }
       }

       // 既存の設定...
   }
   ```

   - `storeFile`, `storePassword`, `keyAlias`, `keyPassword` を実際のキーストア情報に置き換えてください。
   - **セキュリティ上の理由から、パスワードなどの機密情報はコードに直接記述せず、環境変数や Jenkins の Credentials 機能を使用することを推奨します。**

3. **Jenkins の環境変数を使用して機密情報を管理**:

   - Jenkins のビルド設定で、必要な機密情報を環境変数として設定します。
   - `build.gradle.kts` では、これらの環境変数を参照するようにします。

     ```kotlin
     android {
         // 既存の設定...

         signingConfigs {
             create("release") {
                 storeFile = file(System.getenv("KEYSTORE_FILE") ?: "path/to/default/keystore.jks")
                 storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "default_store_password"
                 keyAlias = System.getenv("KEY_ALIAS") ?: "default_alias"
                 keyPassword = System.getenv("KEY_PASSWORD") ?: "default_key_password"
             }
         }

         // 既存の設定...
     }
     ```

4. **ビルドスクリプトを修正する**:

   APK の署名がビルドプロセス内で行われるため、ビルドスクリプト側での追加の処理は不要になります。ただし、出力される APK のファイル名が変わる可能性があるため、スクリプト内の APK のパスを修正します。

   ```python
   # Paths for APK files
   output_apk = "./app/build/outputs/apk/release/app-release.apk"
   final_apk = "../llama-release.apk"

   # 以下の zipalign の処理は、署名済みの APK に対して行う必要があります
   ```

   - `app-release-unsigned.apk` ではなく、署名済みの `app-release.apk` が生成されるようになります。

5. **`zipalign` の処理を調整する**:

   `zipalign` は署名後の APK に対して行う必要があります。ビルドスクリプト内での処理順序を確認し、必要に応じて修正します。

   ```python
   # Align the APK after signing
   ```

6. **再ビルドとインストールの確認**:

   - Jenkins 上で再度ビルドを実行し、生成された `llama-release.apk` をデバイスにインストールします。

     ```shell
     adb install -r llama-release.apk
     ```

   - 正常にインストールできることを確認します。

## 補足

- **セキュリティの考慮**:
  - キーストアやパスワードなどの機密情報は、絶対にソースコードやリポジトリに含めないでください。
  - Jenkins の Credentials 機能や環境変数を使用して、安全に情報を管理してください。

- **APK の最適化**:
  - `isMinifyEnabled` を `true` に設定し、ProGuard や DashO による最適化と難読化を有効にすることを検討してください。
  - 難読化の設定を調整し、必要なクラスやメソッドが削除されないように注意してください。

- **テスト環境での署名**:
  - デバッグビルドでは自動的にデバッグキーで署名されますが、リリースビルドでは明示的に署名を行う必要があります。
  - テスト目的であれば、デバッグキーを使用して署名することも可能です。

- **CI/CD パイプラインの改善**:
  - ビルドプロセス全体を Jenkins のパイプラインとして定義し、ビルド、テスト、デプロイを自動化することを検討してください。
