# プロジェクト状況まとめ

## 概要

- **目的**: 有料版のDashOがインストールされているJenkins環境を利用して、Android Studioで作成したプロジェクトの難読化済みAPKを作成する。
- **開発環境**: Android Studioで`llama.cpp-b2710/examples/llama.android`フォルダを開いて開発を行った。
- **llama.androidフォルダ以下の構成**:
```
llama.android % pwd
llama.cpp-b2710/examples/llama.android
llama.android % ls -F
README.md            gradle/              local.properties
app/                 gradle.properties    settings.gradle.kts
build.gradle.kts     gradlew*
```
- **要件**: `CMakeLists.txt`の設計上、`llama.cpp-b2710`フォルダ以下のファイルもGitに含める必要がある。

## 現在の状況

1. **DashOを利用した難読化**:
   - DashOは有料の難読化ツールであり、Jenkins環境にインストールされている
   - Gradleプラグインバージョン: `com.preemptive.dasho:dasho-android:1.4.0`
   - インストールパス: `/Applications/PreEmptiveProtection-DashO8.5/Contents/Java`

2. **設定ファイルの状態**:
   - `local.properties`: SDKパスを`${HOME}/Library/Android/sdk`に設定完了
   - `build.gradle.kts`: DashOプラグインの追加とビルド設定を実施
   - `app/build.gradle.kts`: DashO設定の追加と難読化有効化を実施
   - `app/project.dox`: 基本設定を実装済み、エントリーポイント設定は未実施

3. **今後の課題**:
   - プロジェクト固有のエントリーポイントの特定と`project.dox`への追加
   - AndroidManifest.xmlの確認とエントリーポイントの抽出
   - カスタムViewやライブラリインターフェースの確認
   - 難読化のテストビルドと動作確認
```
