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
   - `app/project.dox`: エントリーポイントとCompose対応の設定を含めて更新完了

3. **エントリーポイントの設定**:
   - `MainActivity`をメインエントリーポイントとして設定
   - Jetpack Compose関連のクラスを難読化から除外
   - UIコンポーネントのパブリックコンストラクタを保持するよう設定

4. **難読化設定の詳細**:
   - クラス名とメンバー名のランダム化を有効化
   - 文字列暗号化をレベル1で有効化
   - 制御フロー難読化を有効化
   - デバッグ情報の削除を設定

5. **今後の課題**:
   - ビルド実行とAPKの生成確認
   - 難読化されたアプリケーションの動作確認
   - 必要に応じて難読化設定の調整
```
