# プロジェクト状況まとめ

## 概要

- **目的**: 有料版のDashOがインストールされているJenkins環境を利用して、Android Studioで作成したプロジェクトの難読化済みAPKを作成する。
- **開発環境**: Android Studioで`llama.cpp-b2710/examples/llama.android`フォルダを開いて開発を行った。
- **ソースコード**: gitリポジトリのブランチ"scrap/tsuzumi_dasho"にコミット済み
- **プロジェクトパス**: `scrap/tsuzumi_dasho/stbdag_android/llama.cpp-b2710`

## ファイル構成と役割

1. **Androidプロジェクト基本ファイル**:
   ```
   llama.cpp-b2710/examples/llama.android/
   ├── README.md
   ├── gradle/
   ├── local.properties      # SDKパス設定: ${HOME}/Library/Android/sdk
   ├── app/
   │   ├── build.gradle.kts  # アプリケーションビルド設定
   │   ├── project.dox       # DashO詳細設定
   │   └── dasho.gradle      # アプリケーションレベルのDashO設定
   ├── build.gradle.kts      # プロジェクトビルド設定
   ├── dasho.gradle          # プロジェクトレベルのDashO設定
   ├── dasho.xml            # DashOのAntタスク設定
   ├── gradle.properties
   ├── gradlew*
   └── settings.gradle.kts
   ```

2. **DashO関連ファイル**:
   - `project.dox`:
     - パッケージ名、SDKバージョン等の基本設定
     - 難読化除外設定（androidx, compose等）
     - エントリーポイント設定（MainActivity, UI Components）
     - 文字列暗号化、制御フロー難読化の設定
   - `dasho.gradle`（プロジェクトルート）:
     - DashOプラグイン設定
     - バージョン: 1.4.0
   - `dasho.gradle`（app/）:
     - DashOタスク設定
     - マッピングファイル出力設定
   - `dasho.xml`:
     - Antタスクの定義
     - メモリ設定
     - ビルドターゲット設定

3. **Jenkinsビルド設定**:
   - Build Steps用Pythonスクリプト:
     - プロジェクトパス: `llama.cpp-b2710/examples/llama.android`
     - 出力APK名: `llama-release.apk`
     - DashO結果: `dasho-result.tgz`
   - 環境変数:
     - `ANDROID_HOME`: Android SDKパス（Jenkins System設定で定義）
     - Build Tools: 34.0.0を使用

## 設定詳細

1. **難読化設定**:
   - クラス名とメンバー名のランダム化
   - 文字列暗号化（レベル1）
   - 制御フロー難読化
   - デバッグ情報の削除

2. **エントリーポイント**:
   - `com.example.llama.MainActivity`
   - Compose UI関連のパブリックコンストラクタ

3. **除外設定**:
   - `android.**`
   - `androidx.**`
   - `com.google.**`
   - `androidx.compose.**`

## ビルドプロセス

1. **準備**:
   - gitリポジトリから最新コードをチェックアウト
   - gradlewに実行権限付与
   - 既存のAPKファイルを削除

2. **ビルド実行**:
   - クリーンビルド: `./gradlew clean`
   - リリースビルド: `./gradlew assembleRelease`
   - zipalign処理
   - 成果物の移動

3. **成果物**:
   - 難読化済みAPK: `llama-release.apk`
   - DashO処理結果: `dasho-result.tgz`

## 今後の課題

1. ビルド実行による動作確認
2. 難読化の効果確認
3. 必要に応じた設定の調整
