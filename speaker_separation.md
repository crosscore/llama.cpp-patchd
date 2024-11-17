# Android会議支援システム 実装計画書

## 1. システム概要

### 1.1 目的
- リアルタイム会議支援（ファシリテーション）
- 会議内容の自動議事録化
- 話者の識別と発言の整理

### 1.2 主要機能
1. リアルタイム音声認識（Vosk）
2. 話者分離（WebRTC VAD + カスタム実装）
3. テキスト要約・分析（GGUF）
4. ファシリテーション支援

## 2. 実装方針

### 2.1 話者分離機能の段階的実装

#### Phase 1: 基本的な音声区間検出
```kotlin
class VoiceActivityDetector(context: Context) {
    private val webRtcVad: WebRtcVad = WebRtcVad()

    fun detectVoiceSegments(audioData: ShortArray): List<AudioSegment> {
        // 1. フレーム分割
        // 2. VAD処理
        // 3. セグメント結合
        return segments
    }
}
```

#### Phase 2: 話者特徴量抽出
```kotlin
class SpeakerFeatureExtractor(context: Context) {
    private val tfliteInterpreter: Interpreter

    fun extractFeatures(segment: AudioSegment): FloatArray {
        // 1. MFCC特徴量抽出
        // 2. 話者埋め込み計算
        return features
    }
}
```

#### Phase 3: 話者クラスタリング
```kotlin
class SpeakerClustering {
    fun clusterSpeakers(
        segments: List<AudioSegment>,
        features: List<FloatArray>
    ): List<SpeakerSegment> {
        // 1. 特徴量クラスタリング
        // 2. 話者ID割り当て
        return speakerSegments
    }
}
```

### 2.2 ファシリテーション機能の実装

```kotlin
class MeetingFacilitator(
    private val context: Context,
    private val llm: Llm
) {
    suspend fun analyzeMeetingState(
        recentUtterances: List<SpeakerUtterance>
    ): FacilitationAction {
        // 1. 発言パターン分析
        // 2. 会議進行状況確認
        // 3. 介入必要性判断
        return suggestedAction
    }
}
```

## 3. 処理負荷分析

### 3.1 基準負荷（CPU使用率）
GGUFによる5分間の議事録要約: 100%（基準値）

### 3.2 各機能の相対的負荷

1. **リアルタイム音声認識（Vosk）**
- CPU: 30-40%
- メモリ: 150-200MB
- バッテリー影響: 中
- 特徴: 常時実行が必要だが、最適化されている

2. **話者分離処理**
- Phase 1 (VAD)
  - CPU: 10-15%
  - メモリ: 50-100MB
  - バッテリー影響: 低

- Phase 2 (特徴抽出)
  - CPU: 20-25%
  - メモリ: 100-150MB
  - バッテリー影響: 中

- Phase 3 (クラスタリング)
  - CPU: 15-20%
  - メモリ: 50-100MB
  - バッテリー影響: 中

3. **ファシリテーション分析**
- CPU: 40-50%
- メモリ: 200-300MB
- バッテリー影響: 高
- 特徴: 定期的な実行（30秒〜1分間隔）

### 3.3 総合負荷予測

#### 通常動作時（音声認識 + VAD）
- CPU: 40-55%
- メモリ: 200-300MB
- バッテリー消費: 中程度

#### 最大負荷時（全機能動作）
- CPU: 90-120%
- メモリ: 400-600MB
- バッテリー消費: 高

## 4. 最適化戦略

### 4.1 処理の優先順位付け
1. 音声認識（最優先・常時）
2. VAD処理（最優先・常時）
3. 話者特徴抽出（準優先・定期）
4. クラスタリング（通常・定期）
5. ファシリテーション分析（通常・定期）

### 4.2 リソース管理
```kotlin
class ResourceManager {
    fun adjustProcessingLevel(
        batteryLevel: Int,
        cpuUsage: Float,
        temperature: Float
    ): ProcessingMode {
        return when {
            batteryLevel < 15 -> ProcessingMode.MINIMAL
            temperature > 40f -> ProcessingMode.REDUCED
            cpuUsage > 90f -> ProcessingMode.BALANCED
            else -> ProcessingMode.FULL
        }
    }
}
```

### 4.3 バッチ処理の活用
- 音声データの一時バッファリング
- 特徴抽出の定期実行
- クラスタリングの間隔調整

## 5. 今後の課題

1. **パフォーマンス最適化**
   - JNIオーバーヘッドの削減
   - メモリ使用量の最適化
   - バッテリー消費の抑制

2. **精度向上**
   - 話者識別の精度改善
   - ノイズ耐性の向上
   - ファシリテーション判断の改善

3. **ユーザビリティ**
   - UI/UXの改善
   - 設定の柔軟性向上
   - エラー処理の強化

## 6. 開発スケジュール

1. **Phase 1 (2-3週間)**
   - WebRTC VAD実装
   - 基本的な音声区間検出

2. **Phase 2 (3-4週間)**
   - 特徴抽出実装
   - TFLiteモデル統合

3. **Phase 3 (4-5週間)**
   - クラスタリング実装
   - ファシリテーション基本機能

4. **Phase 4 (2-3週間)**
   - 最適化
   - テスト
   - ドキュメント作成
