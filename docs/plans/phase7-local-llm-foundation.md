# Action Starter Android ― Phase 7 実装計画書（ドラフト）：Local LLM Runtime 基盤（Local AI Engine 第2弾の土台）

**対象Phase**: Phase 7（仕様書§71 Phase 7「Local LLM Runtime」＝Model Manager / Download / Load / Inference / Memory Handling / Structured Output / Schema validation）
**正仕様書**: `Action_Starter_Master_Specification_v2.0_Android.md`
**前提**: 第1弾（Basic Engine）完了。実測: git HEAD `9e6c718`「最終ラウンド: 実機E2E 19/19 PASS・/goal 97.6点でリリース可判定」、working tree クリーン。Phase 0〜6・11 完了済み。
**本書**: android-planner（Opus）作成ドラフト、2026-08-10。**Fable 5裁定（U-1〜U-14）＋Gemini G1（`gemini-3.5-flash`）CRITICAL 5件反映済み（2026-08-10）→ G1通過**。
**ステータス**: **G1通過。§16のFable 5確認事項（U-1〜U-14）は全項目裁定済み（2026-08-10、U-11のみ推奨から変更）。Gemini G1（`gemini-3.5-flash`）CRITICAL 5件も本書へ反映済みのため着手可。** 本書はsrcを一切変更していない（読み取りのみ・ファイル新規作成は本書のみ）。
**関連文書**: `docs/TEAMS.md`（役割分担・PDCA・品質ゲートの正）、`docs/GOAL.md`、`DECISIONS.md`（実測最新 **ADR-0042**。Phase 7の起票は ADR-0043 から）、`ARCHITECTURE.md`（§1 パッケージ表・§3 契約変更5ステップ）、`AI.md`（§12「実装はPhase 7以降」）、`PRIVACY.md`
**直接の様式参照元**: `docs/plans/phase6-recovery-basic.md`

本書と正仕様書v2.0に差異が生じた場合は仕様書v2.0が正とする。

---

## §0. 結論ファースト

Phase 7は、**Google AI Edge の LiteRT-LM（Maven AAR `com.google.ai.edge.litertlm:litertlm-android:0.15.0`）を採用し、`ai/` パッケージに ModelManager・PromptBuilder・SchemaValidator・LiteRtLmLocalLanguageModel を実装して、「オフラインでテストPromptを投げ、`ResponseFormat.json(schema)` によるconstrained decodingで生成された JSON を Kotlin 側の独立したスキーマ検証に通し、失敗時は `AiResult.Fallback(reason)` を返す」ところまで**を完成させる。主推奨モデルは **`litert-community/Qwen3-0.6B` の `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`（dynamic INT4 block-32・ctx 4096・実測 328MB・Apache-2.0）**。

**推奨の根拠（2行）**: ①本アプリのLLM出力は §20 の固定スキーマJSONのみであり、**decode時にJSON Schemaで構造を強制できるか**が成否を分ける決定軸である。LiteRT-LM は **Kotlin から直接** `ResponseFormat.json(schema)` を渡せる唯一の候補であり（一次ソース確認済み・後述）、しかも**公式Maven AARで提供されるためNDK/CMake/JNI/vendoringが一切不要**で、Phase 7の実装量とビルド脆弱性を桁で下げる。②主対象デバイスがミッドレンジ Galaxy A（CPU推論・NPU非期待）だが LiteRT-LM は **`Backend.CPU()` が既定**、ABIは **arm64-v8a＋x86_64**（Galaxy A実機とプロジェクトのx86_64エミュレータの両方に適合）、`minSdk 24`（本アプリ26以下）で、Qwen3-0.6B は Google公式ベンチ表に掲載された**公式サポート対象**かつ Apache-2.0 である。

**次点（Fallback案）: llama.cpp（GGUF・自前JNI）＋ `Qwen3-0.6B-Q4_K_M.gguf`（実測 397MB）。** GBNF文法制約が成熟しMITで、GGUFはモデル交換性（§17）が最も高い。しかし**公式Maven AARが存在せず**、`examples/llama.android` をvendoringしてCMake/NDK/JNIを自前で組む必要があり、Phase 7の工数とCI脆弱性が大幅に増える。**§14 P7-C0のスパイクで LiteRT-LM の `ResponseFormat` が実機で機能しなかった場合に、この案へ切り替える**（切替判断はU-2）。

> **重要な留保**: `ResponseFormat` / `enableResponseFormat` は **公式Webドキュメントに未掲載**であり、GitHubのKotlinソースとMaven上のAARにしか存在しない（下表の一次確認参照）。**APIが予告なく変わるリスクがある**ため、バージョンを `0.15.0` に固定し、P7-C0で実機動作を確認するまで本推奨を確定としない。

**一次ソースで確認済みの事実（本書作成時にWebFetchで直接取得）**:

| 確認項目 | 確認結果（逐語） | 一次URL |
|---|---|---|
| `ResponseFormat` の存在とAPI | class `ResponseFormat`、Type enum = `REGEX(1)` / `JSON_OBJECT(2)`、ファクトリ `json(schema: String)` / `json(schema: Map<String, Any?>)` / `regex(pattern: String)` | `raw.githubusercontent.com/google-ai-edge/LiteRT-LM/main/kotlin/java/com/google/ai/edge/litertlm/ResponseFormat.kt` |
| 有効化フラグ | `data class ConversationConfig(... , val enableResponseFormat: Boolean = false)`（**既定false**） | 同 `Config.kt` |
| バックエンド既定 | `data class EngineConfig(val modelPath: String, val backend: Backend = Backend.CPU(), ...)` → **CPUが既定** | 同 `Config.kt` |
| 呼び出し方 | `sendMessage(text: String, ..., responseFormat: ResponseFormat? = null): Message`（Message/Contents/String の3オーバーロード）。`sendMessageAsync` はcallback版とFlow版の各3オーバーロードで同様に `responseFormat` を受ける | 同 `Conversation.kt` |
| 未有効化時の挙動 | 「response_format cannot be used unless enableResponseFormat=True was passed to ConversationConfig」で `IllegalArgumentException` を送出 | 同 `Conversation.kt` |
| Maven座標と依存 | `com.google.ai.edge.litertlm:litertlm-android:0.15.0`（packaging `aar`）。依存は `com.google.code.gson:gson:2.13.2` / `org.jetbrains.kotlin:kotlin-reflect:2.2.21` / `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`（すべてcompile） | `dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.15.0/litertlm-android-0.15.0.pom` |
| Qwenモデルの実配布とライセンス | `litert-community/Qwen3-0.6B` は **Apache 2.0**。成果物4種: `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`（dynamic INT4 block-32・ctx4096・**328MB**）／`qwen3_0_6b_mixed_int4.litertlm`（TorchAO mixed INT4・ctx2048・**474.61MiB**）／`Qwen3-0.6B.litertlm`（dynamic INT8・ctx4096・586MB）／`Qwen3-0.6B.mediatek.mt6993.litertlm`（a16w8 NPU向け・992MB） | `huggingface.co/litert-community/Qwen3-0.6B` |
| Qwenが公式サポート対象であること | 公式ベンチ表に `Qwen3-0.6B` / `Qwen2.5-1.5B` / `Qwen2.5-0.5B` が掲載。ページ文言「Run Gemma, Llama, Phi-4, Qwen and more.」。最終更新 2026-08-04 | `developers.google.com/edge/litert-lm/overview` |

**性能に関する最重要の警告（公式ベンチの逐語値）**: 同ページのCPU実測は **Qwen3-0.6B @ Vivo X300 Pro: prefill 165 tok/s / decode 9 tok/s**、**Qwen2.5-0.5B @ Galaxy S24 Ultra: prefill 251 / decode 30**、**Gemma3-1B @ S24 Ultra: prefill 177 / decode 33**。**これらはすべてフラッグシップ機の値であり、主対象のGalaxy Aクラスではさらに大きく下回る前提で扱う。フラッグシップ値からの外挿で性能を約束しない。** decode 一桁 tok/s があり得る以上、**出力トークン数を最小化する設計（§8.4のmaxItems=8・display_text 60字上限）と、§8.7の「Basic即時→AI後差し替え」非同期UXは仕様ではなく必須の生存条件である**。実測は §11.3 のGalaxy A実機プローブで確定する。

**Qwen3固有の必須設定**: Qwen3は dual-mode thinking を持つ。thinkingが有効だと `<think>` ブロックで数百トークンを消費し、decode 一桁 tok/s の端末では**実用不能になる**。`ConversationConfig.thinkingConfig` および Qwen3のチャットテンプレート側の双方で **thinkingを無効化する**こと。P7-C0で「thinking無効時の実出力トークン数」を実測する（V-4）。

**Phase 7の完成条件（§71「オフライン状態でテストPrompt→JSON取得」の具体化）**:

> 機内モード（またはStrictMode `detectNetwork().penaltyDeath()`）下で `LocalAiGateway.generatePlan(testContext)` を1回実行し、`AiResult.Success(AIPlanResponse)` が返ること。かつロード失敗／OOM／タイムアウト／スキーマ検証失敗／端末非対応の5系統すべてで `AiResult.Fallback(reason)` が返り、**例外がUIまで伝播しない**こと。

**Phase 7でやらないこと（明示）**: `planning/LocalAIPlanningEngine`・`recovery/LocalAIRecoveryEngine` の実装、UI文言生成への実配線、`AppContainer` の `planningEngine`/`recoveryEngine` 差し替え。これらは **Phase 8（§72）／Phase 9（§73）** に属する。Phase 7 は「`ai/` が単体で完結して動く」ことのみを完成条件とする。

**実測で確認した着手前の事実（Phase 7の設計を規定する5点）**:

1. **`ai/` パッケージは既に存在する**（3ファイル・契約scaffoldのみ）。`ai/LocalLanguageModel.kt`（§16のinterfaceと完全一致）・`ai/AIPlanResponse.kt`・`ai/AIRecoveryResponse.kt`。`ModelManager`/`PromptBuilder`/`SchemaValidator`/`ModelAdapters` は `ARCHITECTURE.md`§1 に予定として記載されているが**未作成**。`LocalLanguageModel` の実装クラスは0件、DI結線も0件。
2. **`AIPlanStepResponse` の `type`/`priority` は Domain enum ではなく生 `String`、`AIRecoveryOptionResponse.skippedStepIds` は `UUID` ではなく生 `String`** である。これは「Schema Validation 前の未検証外部入力」を型で表現した既存の設計判断であり、Phase 7 はこれを維持したうえで enum/UUID への変換責務を `SchemaValidator` に置く。
3. **DIは手書き `di/AppContainer.kt` で、Hiltは ADR-0014→ADR-0024 で明示的に却下済み**。全プロパティが `val` の**eager初期化**（`by lazy` 不使用）であり、宣言順＝初期化順。**LLMランタイムをここに素で足すとアプリ起動時に重い初期化が走る**ため、`lazy` 化かフラグゲートが必須。
4. **AI隔離ガードは3本すべて「単純部分文字列マッチ・非再帰・許可リストなし」**。禁止語は planning/notification が `"com.actionstarter.ai"` と `"LocalLanguageModel"`、recovery が `"com.actionstarter.ai"` のみ（非対称）。**KDocコメント内に書いただけでも落ちる**。
5. **設定の受け皿がゼロ**。Settings画面・DataStore・設定Repository・AIフラグのいずれも不在（`aiEnabled` 等のgrepヒット0件）。永続化は `SharedPreferences` のみ（`SharedPreferencesExecutionScheduleStore`、キーは `records`/`next_request_code` の2つだけ）。§19「AI OFFが既定」を満たすには**一式を新設**する必要がある。

---

## §1. 仕様原文の根拠（引用箇所）

| § | 引用（要点） | Phase 7での使い方 |
|---|---|---|
| §71 | Phase 7 = Local LLM Runtime。`Model Manager` / `Download` / `Load` / `Inference` / `Memory Handling` / `Structured Output` / `Schema validation`。完成条件「**オフライン状態でテストPrompt→JSON取得**」 | 7項目をF番号へ1:1写像（§6）。完成条件を§0で具体化 |
| §10 | 「Calendar / Location / Behavioral Historyを**外部LLMへ送らない**」 | §10のネットワーク遮断検証（StrictMode＋機内モードE3）の根拠 |
| §13 | 「**数値計算は必ず通常コード**」 | 時刻演算をLLMに触れさせない。`estimated_minutes` はClamp後にKotlin側が全演算 |
| §14 | LLMの仕事は `Meaning → Action` のみ（eventType推定・Preparation生成・優先順位・必須/任意判定・省略可能性） | プロンプト設計とスキーマの範囲を規定（§8.4） |
| §15 | LLMに「GPS位置・正確な移動時間・時刻演算・到着時刻演算・通知発火・…・安全上重要な最終判断」を決めさせない。「決定的処理はKotlin側」 | スキーマに絶対時刻フィールドを持たせない（既存 `AIRecoveryOptionResponse` に `estimatedArrival` が**意図的に不在**な設計を踏襲） |
| §16 | `interface LocalLanguageModel { val modelIdentifier; suspend fun generatePlan(...); suspend fun generateRecovery(...) }`。「特定モデル依存コードをUIやDomain層へ入れない」「モデルは技術検証で**交換可能**にする」 | **既存interfaceを変更しない**（実測一致）。Adapter方式・GGUF採用の根拠 |
| §17 | 最低評価項目（日本語・英語・多言語・Structured Output成功率・…・RAM・model size・thermal・supported devices・**Androidデバイス断片化**・commercial license）。「モデル名を製品仕様として固定しない」「**日本語だけで選定しない**」 | §5 決定マトリクスの比較軸。モデル名は「既定値」であって仕様固定ではない旨を§5.3に明記 |
| §18 | 「巨大モデル直接同梱を必須としない」。推奨フロー「アプリインストール→Local AI有効化→Model Download」。「端末性能によってモデルを選択できる構造」。「ダウンロード開始前にはストレージ空き容量の事前検証を必須」 | F87〜F90（DL/検証/保存/容量ガード）と§5.3の段階推奨の根拠 |
| §19 | 「モデルDL失敗・低スペック端末・Local AI OFF等でも**アプリ自体は正常に成立**」「Local AIはEnhancementであってSingle Point of Failureにしない」 | **AI既定OFF**（F92）とフォールバック発動条件表（§8.6）の根拠 |
| §20 | 「LLM自由文をDomain Logicへ直接使用禁止」「**Schema validation必須**」「Validation失敗→**retry 1回**→failure→**Basic Engine**」 | 2層検証（constrained decoding＋Kotlin検証器）とretry回数=1の固定根拠（§8.4・§8.5） |
| §21 | 内部意味は英語ID（`eventType`/`actionType`/`priority`/`skippable`/`duration`）。`action_type` と `display_text` を分離 | スキーマのenum設計（§8.4）。`AIPlanStepResponse` の既存フィールド構成と一致 |
| §42 | Local AI候補「MediaPipe LLM Inference API / llama.cpp（JNI）/ MLC-LLM / ONNX Runtime Mobile / AICore・Gemini Nano」。「**特定Runtimeを仕様として固定しない**」 | §5 決定マトリクスはこの候補集合を出発点とし、Qwen要件で絞る |
| §43 | `AI` 配下は `LocalLanguageModel` / `ModelManager` / `PromptBuilder` / `SchemaValidator` / `ModelAdapters` | `ai/` のサブ構造をこの5要素に写像（§8.1） |
| §57 | Local AI性能指標（Plan generation latency / JSON validity / Schema validity / RAM / Battery / Thermal / Model download size） | ベンチプローブの測定項目（§11）に1:1で対応させる |
| §58〜§60 | Privacy（必要最小限）／Local Data（原則端末保存）／Telemetry（**カレンダー本文・住所等を送信しない**。送ってよいのは `plan_generation_ms` 等の指標のみ） | §10の検証方法とAnalytics項目の設計 |
| §95.3 | 「目安として **RAM 6GB未満の端末はLocal AI対象外**」「端末性能に応じて配布モデルを出し分け」 | §5.3の段階推奨の基礎。**調査した実測ピークRAM（Qwen3-0.6B int4 で約2.9GB）とGoogleの `minDeviceMemoryInGb=6` 宣言により、この6GB基準を緩和せずそのまま採用する**（S-1） |
| §95.6 | 「ダウンロード開始前に `StatFs` で空き容量を検証（目安: **モデルサイズ×1.5倍以上**）」「推論エラー・タイムアウト・Schema不通過 → retry 1回 → Basicへ。**失敗はAnalyticsへ記録しサイレントに握り潰さない**」 | F90（容量ガード）と§13エラーマップの直接の根拠 |
| ADR-0002 | 単一 `:app` モジュール。「**Phase 7（Local LLM Runtime導入）でネイティブ依存が増大した時点**…で分割要否を再検討する」 | 再検討トリガーには該当するが、**推奨案（LiteRT-LM）はAAR依存のみでNDK/CMakeを持ち込まないため、分割は不要**と結論する（§8.2）。ADR-0043で「単一モジュール継続」を明示的に記録する。次点のllama.cpp案を採る場合のみ `:llm` 分割が必要になる |
| ADR-0024 | Hilt導入は却下、手動DI継続 | `AppContainer` を手書きのまま拡張する（Hiltを持ち込まない） |

---

## §2. スコープ

### 2.1 やること

F85〜F97（§6）。サイクルは P7-C0〜P7-C8（§14）。

### 2.2 やらないこと（明示）

- **`planning/` と `recovery/` の全ファイルを変更しない**。`LocalAIPlanningEngine` / `LocalAIRecoveryEngine` は**作らない**（Phase 8/§72・Phase 9/§73）。これは§9のAI隔離ガード（3本とも単純文字列マッチ）を**無改修のままGreenで通す**ためでもある。
- **`AppContainer` の `planningEngine` / `recoveryEngine` の右辺を変更しない**。`AppContainerTest` の T-P4DI-1（`is BasicPlanningEngine`）／T-P6DI-1（`is BasicRecoveryEngine`）を**触らずにGreen維持**する。Phase 7 が AppContainer に足すのは `localAiGateway` という**新規プロパティ1本（`by lazy`）だけ**。
- **UI文言生成への配線**（Plan Review画面・Recovery画面へのAI提案表示）。Phase 8。
- **`LocalLanguageModel.generateRecovery` の本実装**。Phase 7 は `generatePlan` 経路のみを通し、`generateRecovery` は同一基盤の上に**スキーマとプロンプトだけ差し替えれば動く形**で `TODO()` ではなく「未サポート理由付き `AiResult.Fallback`」を返す（サイレント障害の回避。§13 #18）。**U-8で裁定**。
- **Personal Execution Profile を用いた個人化**（Phase 10）／**Basic vs AI の実験切替UI**（Phase 12・§76 Developer Settings）。
- **モデルのアプリ本体同梱**（§18「必須としない」）。Play Asset Delivery / Play Feature Delivery も Phase 7 では採らない（U-5で裁定）。
- **GPU/NPU バックエンド（OpenCL/Vulkan/NNAPI/QNN）の有効化**。主対象デバイスで安定性・可搬性が読めないため Phase 7 は **CPUのみ**。GPU は Phase 8以降の最適化課題として申し送る（§18）。

---

## §3. ゲート

`docs/TEAMS.md`§6 に基づき G1〜G4 を適用する。Phase 7 は**実機必須項目が初めて本質的に発生する**Phaseであるため、G4 を **G4-JVM / G4-E（エミュレータ）/ G4-D（実機）** の3段階とする。

- **G1（計画承認）**: 本書＋§13エラー＆レスキューマップ＋Fable 5 Pass1/Pass2レビュー＋Geminiクロスレビュー（`model: "gemini-3.5-flash"` 固定）。**§16のU-1〜U-14がすべて裁定されるまでG1は通過しない。** → **2026-08-10、U-1〜U-14の全項目裁定（§16）とGemini G1 CRITICAL 5件の反映が完了し、G1は通過した。**
- **G2（Red確認）**: P7-C2 で作成した failing テスト（§12のE1/E2区分）を `:app:testDebugUnitTest` で実測。E3/E4区分は作成のみでRed実測はG4-E/G4-Dまで行わない（Phase 1/2/4/6の先例踏襲）。
- **G3（Green確認）**: P7-C3〜C6 各サイクル末とP7-C7（統合）後の再実測。
- **G4-JVM**: `./gradlew build` 成功・対象範囲のJVM/Robolectric全テストPass・`lintDebug` エラー0。**AI隔離ガード3本＋新設ガード（§9）が全Green**であること。
- **G4-E（エミュレータ）**: AVD `actionstarter_test`（**実測: x86_64 / API 35 / hw.ramSize=4096**）上で、E3区分（実ロード＋実推論＋機内モードJSON取得＋StrictModeネットワーク遮断）がPass。**実推論は小コンテキスト・テストプロファイル（`maxNumTokens`128〜256・ピークネイティブRAM1GB級。§11.2・§12.8）で行う**（Gemini G1 CRITICAL #4反映。フルコンテキストのピーク実測はG4-Dへ移管）。**この実行のためにdebugビルドは `x86_64` ABI を含める必要がある**（§8.2・R-2）。
- **G4-D（実機）**: ユーザー保有の **Galaxy A系実機**（2世代前ミッドレンジ）へAPKサイドロードし、§11のベンチプローブ P7-P2 を実行して測定値を記録する。**測定値が§8.6のタイムアウト閾値の確定根拠になるため、G4-D未達のままPhase 8へ進むことを禁止する。**

---

## §4. 承認状態

**Fable 5裁定U-1〜U-14済み（U-11のみ変更裁定）＋Gemini G1（gemini-3.5-flash）CRITICAL 5件反映済み（2026-08-10）→ G1通過。** 本書は android-planner が作成した初版に対し、Fable 5 Pass1/Pass2 レビューと Gemini クロスレビュー（G1・`model: "gemini-3.5-flash"`）を実施し、指摘事項をすべて反映した。§16のU-1〜U-14は**全項目裁定済み**（詳細は§16表の「裁定（Fable 5・2026-08-10）」列）。

### 4.1 仕様の矛盾・未定義（自己補完していない論点）

| ID | 内容 | 提案（裁定を仰ぐ） |
|---|---|---|
| **S-1** | **§95.3「RAM 6GB未満はLocal AI対象外」と、Fable 5指示の主対象デバイス（Galaxy A系・RAM 4〜8GB。A15/A25級は4〜6GB）が一見衝突する。** 6GB厳格運用だとGalaxy Aの一部構成が対象外になる | **調査の結果、緩和しない結論に至った**（§5.3）。`litert-community` 公式ベンチで **Qwen3-0.6B int4 のピークRAMが約2.9GB**、かつ Google自身が同クラスに `minDeviceMemoryInGb = 6` を宣言しているため、4GB機での安定動作は見込めない。**§95.3をそのまま採用する。** ただし `EngineConfig.maxNumTokens` によるコンテキスト短縮でピークRAMが十分下がれば境界の再検討余地があるため、P7-C0で実測する（V-8。**Gemini G1 CRITICAL #4によりP7-C0の必須測定項目へ格上げ**）。**U-3で追認済み**（承認・6GB基準維持＋V-8実測での再検討条項付き） |
| **S-2** | **§20は「Validation失敗→retry 1回→Basic」と定めるが、retryの単位（同一プロンプト再生成か／温度を下げた再生成か／プロンプト修正付きか）が未定義**。また文法制約を使う場合「構文エラーによるValidation失敗」は原理的にほぼ起きないため、retryの実効対象が変わる | retryは「**同一プロンプト・temperature=0.0・seed固定での1回再生成**」と定義する案。文法制約下で残る失敗は「意味的スキーマ違反（enum外・件数超過・矛盾）」であり、これがretry対象になる |
| **S-3** | **§16の `LocalLanguageModel` は失敗を表現できない**。戻り型が `AIPlanResponse` の非null固定であり、ロード失敗/OOM/タイムアウト/検証失敗を**例外でしか表現できない**（＝呼び出し側が握り潰すとサイレント障害になる） | **§16のinterfaceは変更せず**（契約変更5ステップを避ける）、その上位に `ai/LocalAiGateway` を置き `AiResult.Success/Fallback(reason)` の**封じ込み型**で返す。`LocalLanguageModel` は「成功時のみ返す・失敗は型付き例外」の内部契約とする |
| **S-4** | **§14は「必要Preparation生成」をLLM担当とするが、§13/§15は数値計算をLLMに禁止する。`estimated_minutes` はどちらか** | §20の公式JSON例に `"estimated_minutes": 10` が含まれるため、**「1ステップの所要見積もり」はLLM可・「時刻演算（StartOfTransition/ETA）」はKotlin専任**と切り分ける。加えてハルシネーション上限として `1..120` にClampし、Clamp発生をAnalyticsへ記録する |
| **S-5** | **モデル配布元が未定義**。§18は「アプリ内ダウンロード」を基本とするだけで、ホスティング先・URL安定性・帯域コスト・地域到達性に言及がない | Hugging Face の `resolve` URL 直参照を既定案とし、**SHA-256固定＋バージョン付きmanifest**で改竄・差し替えに備える。恒久運用は U-5 で裁定 |
| **S-6** | **§19「AI OFFが既定」と§18「Local AI有効化→Model Download」から、Settings画面が必要になるが、仕様にSettings画面の定義がない**（§35「最初の5画面」にも含まれない） | Phase 7 は**最小のSettings画面**（AI ON/OFFトグル＋モデル状態表示＋DL/削除ボタンのみ）を新設する。フル機能のSettingsは対象外 |

### 4.2 Geminiクロスレビュー

**実施済み（`model: "gemini-3.5-flash"` 固定・2026-08-10、G1）。** CRITICAL指摘5件を本書へ反映済み: **#1** 隔離ガード（T-AIISO-6）の自プロジェクト通信クラス経由の迂回穴（§9本文・T-AIISO-6）。**#2** ローカル既存モデルのロード前検証欠如（§8.6 #12・§13 #25）。**#3** ネイティブOOM（LMKによるSIGKILL）はJava `catch` で捕捉不能であり事前回避が必要（§8.6 #7・§12.5 T-GW-5・§13 #8）。**#4** エミュレータ（AVD RAM 4096MB）ではフルコンテキストのピークRAM（約2.9GB級）が成立しない（§11.2・§12.8・§15 R-11）。**#5** `SchemaValidator` のJSONパーサをAndroid同梱`org.json`のみに依存させるとE1（純JVM）実行時に`Stub!`例外を招く（§12.1・§16 U-11）。

---

## §5. 決定マトリクス

### 5.0 情報源の区別（本章の全数値に適用）

| 記号 | 意味 |
|---|---|
| **【一次確認済】** | 本書作成時に android-planner が WebFetch / Context7 で**公式ソース・公式Maven・公式ドキュメントから直接取得**した |
| **【調査実測】** | 調査サブエージェントが AAR/APK を実際に展開する等して実測した。一次に準じるが android-planner 自身は再現していない |
| **【第三者報告】** | 別セッション等からの伝聞。**裏取りできていない** |
| **【未確認】** | 調べたが確定できなかった。推測で埋めていない |

### 5.1 ランタイム決定マトリクス（Qwen小型モデルをAndroidで動かす前提）

**決定軸の重み付け**: 本アプリのLLM用途は §14「Meaning → Action」＝**固定スキーマの短いJSONを返すだけ**である。したがって **①構造化出力（JSON強制）の実現手段** を最優先軸とし、次に **②Qwen対応の成熟度**、**③ミッドレンジCPUでの実行可能性**、**④統合工数**、**⑤ライセンス**、**⑥メンテ活性** の順で評価する。

| 軸 | **LiteRT-LM**（推奨） | **llama.cpp**（次点） | MLC LLM | ONNX Runtime GenAI | MediaPipe LLM Inference | MNN / MNN-LLM |
|---|---|---|---|---|---|---|
| **① 構造化出力（JSON強制）** | **◎ Kotlinから直接可**。`ResponseFormat.json(schema)`＋`ConversationConfig(enableResponseFormat=true)`【一次確認済】。AAR 0.15.0 内に `ResponseFormat.class` 実在【調査実測】。C++側はLLGuidance | ○ **GBNF文法＋JSON Schema→GBNF変換**が公式機能【一次確認済: `grammars/README.md`・`--json-schema`】。C API関数名は【未確認】。**JNIを自前で書く前提** | ○ XGrammar統合済。Kotlin `ResponseFormat(type, schema)` が `OpenAIProtocol.kt` に存在。ただし**Android実機での動作は未検証**、公式サンプルも未使用 | **× Androidでは不可**。C APIに `OgaGeneratorParamsSetGuidance` はあるがJavaバインディング未実装、かつ**公式AARが `--use_guidance` なしでビルドされている** | **× 不可**。`constraintHandle` は生ポインタで公開APIに生成手段なし。FC SDKの制約デコードは**Gemma限定**かつSDK非推奨 | **× 皆無**。docs/全ソース/配布APKの `libMNN.so` の strings まで4重に確認して grammar/gbnf/json_schema ゼロ。サンプラは `logit_bias`/`banned_tokens` のみで状態依存文法を表現不可 |
| **② Qwen対応の成熟度** | ◎ 公式ベンチ表に Qwen3-0.6B / Qwen2.5-0.5B / 1.5B が掲載。「Run Gemma, Llama, Phi-4, **Qwen** and more.」【一次確認済】。`litert-community` に Qwen系 **21リポジトリ**【調査実測】 | ◎ GGUF変換が即日出る。Qwen3全サイズのGGUFが複数org（Qwen公式/unsloth/bartowski）から入手可【一次確認済】 | ◎ `mlc-ai` orgに Qwen3-0.6B/1.7B/4B・Qwen2.5-0.5B/1.5B/3B の q4f16_1 を公式配布。MLCChat既定にQwen3が2つ | △ READMEに Qwen 記載・`model_type.h` に `qwen2`/`qwen3` あり。ただし **Microsoft公式のQwen配布は見当たらず**、自前変換前提 | **× 公式サポート表にQwenなし**（Gemma-3n/Gemma-3 1B/Gemma-2 2B/Phi-2等のみ）【一次確認済】 | ◎ **最厚**。`taobao-mnn` の217モデル中 **95がQwen系**、Qwen3.5まで追随 |
| **③ ミッドレンジCPU実行** | ◎ `EngineConfig.backend` の**既定が `Backend.CPU()`**【一次確認済】 | ◎ CPUが本命。KleidiAIがdotprod/i8mm/SVEを**実行時検出で自動選択**【一次確認済】 | **× 致命的**。Androidは **OpenCLのみ**でCPU/Vulkanバックエンド未提供。加えてAdreno＋`_1`レイアウトで **prefill時20〜50秒のUIフリーズ**の既知不具合 | △ 事実上CPUのみ（providerにNNAPI/XNNPACKなし、QNNはWindows on Snapdragon限定） | ○ CPU可 | ◎ CPU最適化が本領（論文で prefill 8.6x vs llama.cpp を主張） |
| **④ 統合工数** | **◎ 最小**。Gradle依存1行。**NDK/CMake/JNI/vendoringが一切不要** | △ 大。`examples/llama.android` をvendoringしCMake/NDK/JNIを自前構築。公式Maven AARなし | × 大。**Rust＋NDK 27＋TVM＋JDK17** が全部揃わないと `mlc_llm package` が通らない。ビルド失敗issue多数 | ○ 中。GitHub ReleasesのAAR（21.6MB）を `libs/` に置く。**Maven未公開**でバージョン更新が手作業 | ○ 小（Maven 1行）だが①が不可のため選外 | × 大。**公式Maven/AARなし＝ソースビルド必須**（NDK 27.2、`build_64.sh`） |
| **⑤ ライセンス** | Apache-2.0 | MIT | Apache-2.0 | MIT | Apache-2.0 | Apache-2.0 |
| **⑥ メンテ活性** | ◎ **v0.15.0（2026-08-01公開）/ ドキュメント最終更新 2026-08-04**【一次確認済】 | ◎ 極めて活発 | **× 正式リリースが存在しない**（`v0.1.dev0` のみ・nightly運用） | ○ v0.15.2（2026-08-07）とリリース頻度は高い | **× maintenance-only モード・Javaクラスに `@Deprecated`・`tasks-genai` は 0.10.35（2026-04-27）で更新停止**。公式が LiteRT-LM への移行を推奨 | ◎ v3.6.1（2026-07-23）・4〜8週間隔 |
| **ネイティブサイズ** | `liblitertlm_jni.so` arm64 **20.2MB** / AAR 18.9MB。**ABIは arm64-v8a と x86_64 のみ**【調査実測】 | 自前ビルドのため可変【未確認】 | `tvm4j_core.jar` ~60kb、`.so` サイズ【未確認】。**arm64-v8a固定** | AAR 21.6MB（arm64+x86_64）＋ ORT本体が別途必要 | `libllm_inference_engine_jni.so` arm64 **25.4MB**（4ABI同梱でAAR 40.4MB）【調査実測】 | `libMNN.so` **7.16MB** ＋ `libmnnllmapp.so` 1.33MB ＝ 最小約9.7MB【調査実測】。**arm64-v8aのみ** |
| **判定** | **採用** | **次点（P7-C0で①が実機NGなら切替）** | 除外（③が致命的・⑥が不安定） | 除外（①がAndroidで不可） | **除外（①不可・②Qwen非対応・⑥非推奨化）** | 除外（①が皆無。JSON強制を後段retryで代替する設計は§20と両立しない） |

**MediaPipe LLM Inference API の除外を明記する（タスク指示による）**: MediaPipe LLM Inference API の**公式サポートモデル表にQwenは含まれない**（掲載は Gemma-3n E2B/E4B・Gemma-3 1B・Gemma-2 2B、LoRA節に Gemma-2 2B / Gemma 2B / Phi-2）。加えて同APIは公式に maintenance-only モードへ移行し Java クラスに `@Deprecated` が付与され、Google自身が **LiteRT-LM Kotlin API への移行を推奨**している。よって**新規採用しない**。ただし後継の LiteRT-LM では Qwen が公式サポート対象に入るため、「Google純正Androidツールチェーン」という利点は LiteRT-LM を選ぶことで**そのまま得られる**。

### 5.2 モデルファミリー比較（Qwen vs Gemma vs Llama 3.2）

**比較の前提**: 用途は「日本語/英語の予定文 → 短い構造化JSON」。長文生成・対話品質は評価対象外。ランタイムは §5.1 の推奨（LiteRT-LM）との組み合わせで評価する。

**日本語品質の一次データ**: Swallow LLM Leaderboard の生データ（`raw.githubusercontent.com/swallow-llm/leaderboard/main/_data/model.yml`）を直接取得して確認した【調査実測】。本用途に最も近い指標は **MIFEvalJa（日本語の検証可能な指示追従）** である。

| モデル | params | ja_post avg | **MIFEvalJa** | ja_mtb |
|---|---|---|---|---|
| Gemma 4 E2B IT | 5.1 | 0.431 | **0.646（小型帯で最高）** | 0.726 |
| Qwen3-4B | 3.1 | 0.448 | 0.509 | 0.628 |
| **Qwen3-1.7B** | 1.5 | 0.364 | **0.491（1〜2B帯で最高）** | 0.478 |
| **Qwen3-0.6B** | 0.5 | 0.216 | **0.425** | 0.336 |
| Gemma 3 1B IT | 1.0 | 0.147 | 0.323 | 0.352 |
| Qwen3.5-0.8B | 0.8 | 0.128 | **0.261（Qwen3-0.6Bから退行）** | 0.248 |
| Llama 3.2 1B / 3B | 1.2 / 3.2 | base版のみ登録 | **Instruct版の登録なし** | — |

| 軸 | **Qwen3（0.6B / 1.7B）** | Gemma 4 E2B / Gemma 3 1B | Llama 3.2 1B / 3B |
|---|---|---|---|
| **① 日本語品質** | ◎ **Qwen3-1.7B の MIFEvalJa 0.491 は1〜2B帯で最高**、0.6B も 0.425 で Gemma 3 1B（0.323）を上回る。公式は119言語対応で**日本語を明示**（`qwenlm.github.io/blog/qwen3/` の言語テーブル） | Gemma 4 E2B は **0.646 で全小型帯の最高**だがサイズ・RAM要件が重い。**Gemma 3 1B は 0.323 で実用域に届かない**。Gemma 3/4 とも公式の対応言語一覧は非公開【未確認】 | **× 公式サポート8言語（英独仏伊葡ヒンディー西タイ）に日本語が含まれない**（`huggingface.co/meta-llama/Llama-3.2-1B-Instruct` に明記）。Swallow に Instruct版の登録もない。**本アプリの主用途が日本語のため除外** |
| **② ミッドレンジ適合** | **△ 要注意**。`litert-community` 実測で **Qwen3-0.6B mixed_int4 CPU: TECNO LJ9（Dimensity 8350・準ミッドレンジ）で prefill 231.2 / decode 8.33 tok/s・ピーク 2,890MB**、Galaxy S25 Edge で 576.6 / 12.9・**2,895MB**。**0.6BなのにピークRAMが約2.9GBに達する**（KVキャッシュ支配とみられる） | Gemma 3 1B int4 は S24 Ultra CPU で **prefill 138 / decode 50 tok/s・ピーク 982MB** と、速度・メモリの両面で Qwen3-0.6B を大きく上回る。Gemma 4 E2B は S26 Ultra CPU 557/46.9・1,733MB | ExecuTorch経由で Llama 3.2 1B が OnePlus 12 CPU で decode 50.2 tok/s・1,921MiB |
| **③ ライセンス** | ◎ **Apache-2.0**（`litert-community/Qwen3-0.6B` ページでも Apache 2.0【一次確認済】）。再配布・商用に追加義務なし | **Gemma 4 は2026-04にApache-2.0へ変更**（`opensource.googleblog.com/2026/03/gemma-4-…`）→ 義務ほぼゼロ。**Gemma 3 / 3n / FunctionGemma は従来のGemma Terms のまま**で、規約コピー同梱・Prohibited Use Policyの自EULAへの執行可能な組み込み・Notice文言同梱・**HFリポジトリがゲート付き（トークン必要）** という実作業が発生する | Llama 3.2 Community License。Notice文言＋**「Built with Llama」の表示義務**＋派生モデル名の接頭辞義務＋MAU 7億条項 |
| **④ 構造化出力の強制しやすさ** | ◎ LiteRT-LM `ResponseFormat` で**モデル非依存に強制**。Qwen3は tool calling / structured output を0.6Bのモデルカードでも謳う。**ただし dual-mode thinking を無効化しないと `<think>` で数百トークンを浪費する**（§0の警告） | ◎ 同上。Gemma 4 は「Native support for function-calling, structured JSON output」を明記。**Gemma 3 / 3n のモデルカードには structured output / function calling の記載なし** | モデルカードに tool calling の記載なし |
| **⑤ 配布サイズ（`.litertlm` int4）** | 0.6B = **328MB**（block-32・ctx4096）／ mixed_int4 = 474.61MiB（ctx2048）、**1.7B = 932MB**、4B = 2535.88MiB【一次確認済＋調査実測】 | **Gemma3-1B-IT int4 = 584,417,280 B（557MiB）**、Gemma-4-E2B-it = 2,583,085,056 B（2.41GiB）、Gemma-3n-E2B = 3.40GiB（Google AI Edge Gallery の allowlist JSON 実バイト数） | GGUF Q4_K_M で 1B=0.81GB / 3B=2.02GB |
| **Google公式の最低RAM宣言** | Qwen2.5-1.5B = **6GB**（Qwen3系の宣言値は allowlist に不在【未確認】） | **Gemma3-1B / FunctionGemma 270M = 6GB、Gemma-4-E2B / 3n-E2B = 8GB、E4B = 12GB** | — |
| **判定** | **主推奨（Qwen3系）** | **Gemma 3 1Bは日本語で不採用。Gemma 4 E2Bは品質最高だが8GB要件でGalaxy Aクラスを外れる** | **除外（日本語非サポート）** |

**結論（主推奨の組み合わせ1案）**:

> **LiteRT-LM 0.15.0 ＋ `litert-community/Qwen3-0.6B` の `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`（int4 block-32・328MB・Apache-2.0）、`Backend.CPU()`、`EngineConfig.maxNumTokens` でコンテキストを絞ってKVキャッシュを抑制、`ResponseFormat.json(PlanJsonSchema)` で構造強制、thinking無効化。**

**Gemmaへ切り替える場合の差分**:

| 観点 | 内容 |
|---|---|
| **利点** | ①**速度とメモリが桁違いに良い**。Gemma 3 1B int4 は S24 Ultra CPU で decode 50 tok/s・ピーク982MB に対し、Qwen3-0.6B は decode 8〜13 tok/s・ピーク約2.9GB。**本アプリの体感と4GB機での成立可否を最も左右する数値**。②Gemma 4 は日本語指示追従 0.646 で最高、かつ **Apache-2.0 に変更済み**で義務なし。③Google純正モデル×純正ランタイムで検証事例が最厚 |
| **欠点** | ①**Gemma 3 1B は日本語 MIFEvalJa 0.323 と実用域に届かない**（速いが賢くない）。②**Gemma 4 E2B は Google自身が最低8GB RAMを要求**しており、主対象のGalaxy A（4〜8GB）の大半を切り捨てる。サイズも2.41GiB。③Gemma 3 / 3n を選ぶ場合のみライセンス義務が重い（規約同梱・EULA組み込み・HFゲート）。④ユーザー指示（「Qwen辺りのローカルLLM」）から外れる |
| **追加作業（Gemma 3/3n の場合のみ）** | ①Gemma Terms of Use の全文コピーをアプリ内で提供。②Prohibited Use Policy を自アプリEULAに**執行可能な条項として**組み込む。③Noticeファイルに指定文言を同梱。④HFゲート対応のためDLパイプラインにトークン管理を追加（または自前CDNへ再配布し、その場合は再配布義務が全て自分にかかる）。**Gemma 4（Apache-2.0）ならこれらは不要** |

**最終選択の建付け**: 本書は**推奨1案を提示するのみ**であり確定しない。**§11.3のGalaxy A実機プローブで Qwen3-0.6B / Qwen3-1.7B / Gemma3-1B を同一条件で実測**し、結果を Fable 5 → ユーザーへ報告して確定する（U-4）。**Galaxy A系（A54/A55/A56・Snapdragon 6/7系・Exynosミッドレンジ）の実測値は公式・非公式を問わず1件も存在せず**、最も近い代替が TECNO LJ9（Dimensity 8350）の行である。したがって**本プローブは選定の必須条件であり、省略できない**。

### 5.3 端末RAM別の段階推奨（Fable 5指示: 基準線＝ミッドレンジGalaxy A）

**主対象デバイスクラス = ミッドレンジ Galaxy A系（2023〜24年、A54/A34/A25/A15級。Exynos 1280/1380・Dimensity 1080/6100+級、RAM 4〜8GB、NPU活用は期待できずCPU推論前提）。段階推奨の基準線をこのクラスに置く。**

| 段 | 端末RAM（`totalMem`） | 推奨モデル | モデルサイズ | 実測ピークRAM（近縁機） | 扱い |
|---|---|---|---|---|---|
| **段0** | **6GB未満** | なし | — | — | **Local AI 対象外**（§95.3どおり）。トグルを無効化しBasicのみ（§19） |
| **段1（既定）** | **6GB以上** | **Qwen3-0.6B int4（block-32）** | **328MB** | **約2,890MB**（TECNO LJ9 CPU・mixed_int4）【調査実測】 | **本クラスの既定** |
| **段2（オプトイン）** | 8GB以上 ＋ **実機プローブで実用速度を確認できた場合のみ** | Qwen3-1.7B int4 | 932MB | 【未確認】 | 既定にしない。ユーザーが明示的に選ぶ |
| **段3** | — | 4B級 | 2.5GB前後 | — | **本クラスでは対象外**（RAM余裕とCPU速度の両面。Fable 5指示） |

**§95.3との整合（S-1の再検討・結論が変わった）**: 本書の初期案では段1の下限を4GBへ緩和することを検討したが、**調査で得た実測値がこれを否定した**。`litert-community` の公式ベンチで **Qwen3-0.6B（int4）のピークメモリは約2.9GB**であり、これは総RAM 4GBの端末でアプリに割り当てられる実効メモリを超える可能性が高い。加えて Google自身が同クラス（Gemma3-1B・Qwen2.5-1.5B）に **`minDeviceMemoryInGb = 6`** を宣言している。**したがって仕様§95.3の「RAM 6GB未満はLocal AI対象外」を緩和せず、そのまま採用する。** これにより Galaxy A54/A34（6〜8GB）は対象、A15/A25の4GB構成は対象外となる。**この判断はU-3で追認済み（承認・6GB基準維持＋V-8実測での再検討条項付き）。**

**ピークRAMを下げる設計上の手段（段1を成立させるために必須）**: 上記2.9GBはKVキャッシュ支配とみられる。本アプリのプロンプトも出力も短いため、**`EngineConfig.maxNumTokens` でコンテキスト長を業務上の最小値（例 1024）に絞る**ことでピークRAMを大きく下げられる可能性がある。**P7-C0でコンテキスト長を変えたときのピークRAM変化を実測し（V-8）、段1の成立可否と段0/段1の境界値を確定する。**

**モデル名を製品仕様として固定しない（§17）**: 上表は `ModelCatalog` の**既定値**であり、エントリを足すだけで他モデルへ差し替えられる構造にする。

**モデル名を製品仕様として固定しない（§17）**: 上表は `ModelCatalog` の**既定値**であり、`ModelCatalog` にエントリを足すだけで他モデルへ差し替えられる構造にする。

### 5.4 配布方式（ミッドレンジのストレージ事情を前提条件に含める）

主対象クラスのストレージは **64〜128GB**（Galaxy A15/A25/A34/A54級）で、実使用では空きが数GBまで減っている個体が珍しくない。したがって:

- **アプリ本体同梱は採らない**（§18「必須としない」）。328MBをAPKに載せるとインストール障壁とPlayのサイズ制約に直結する。
- **アプリ内ダウンロード方式を基本とする**（§18の推奨フローそのまま）。
- **`StatFs` による事前容量ガードを必須**とし、閾値は §95.6 の `モデルサイズ × 1.5`（段1なら約 **492MB** の空きを要求）。不足時はDLを開始せず、必要量と現在の空き容量を数値で明示する。
- Play Asset Delivery / Play Feature Delivery は §18 で選択肢とされているが、**Phase 7では採らない**（オンデマンド配信の追加複雑性に見合う利点が現時点でない。U-5で裁定）。
- 配布元は Hugging Face の `litert-community/Qwen3-0.6B` を既定とし、**SHA-256固定＋バージョン付き `ModelCatalog`** で改竄・差し替えに備える（S-5）。恒久運用先はU-5で裁定。

---

## §6. 機能一覧（F番号）

> **採番根拠**: `docs/plans/*.md` 全体の実測最大F番号は **F84**（Phase 11で使用）。Phase 7 は **F85から**採番する（U-1で裁定）。

| ID | 機能 | §71項目 | 仕様根拠 |
|---|---|---|---|
| F85 | LiteRT-LM 依存導入（`com.google.ai.edge.litertlm:litertlm-android:0.15.0`）＋**推移依存のバージョン衝突解消**（gson 2.13.2 新規／kotlin-reflect 2.2.21 対 本プロジェクトKotlin 2.4.10／coroutines-android 1.9.0 対 既存 coroutines-test 1.11.0） | Inference基盤 | §16・§42 |
| F86 | `ai/adapter/LiteRtLmLocalLanguageModel`（`LocalLanguageModel` の初の実装。`Engine`/`Conversation` のライフサイクル管理・thinking無効化を含む） | Load / Inference | §16 |
| F87 | `ai/model/ModelCatalog`（モデル定義＝id・URL・SHA-256・バイト数・必要RAM・量子化） | Model Manager | §17・§18 |
| F88 | `ai/model/ModelDownloader`（HTTP Range再開付きDL・進捗・キャンセル） | Download | §18 |
| F89 | `ai/model/ModelVerifier`（SHA-256照合・サイズ照合・**検証通過前は絶対にロードしない**） | Download | §18・信頼境界 |
| F90 | `ai/model/ModelStorage`（`noBackupFilesDir/models/` 配置・`StatFs` 容量ガード×1.5・原子的リネーム・削除） | Model Manager | §95.6 |
| F91 | `ai/model/DeviceCapability`（`totalMem`／`SUPPORTED_ABIS`／CPU機能から対応可否とモデル段を判定） | Memory Handling | §17・§95.3 |
| F92 | `ai/AiPreferences`（AI ON/OFF・選択モデルID の永続化。**既定OFF**） | — | §19 |
| F93 | `ai/prompt/PlanPromptBuilder`（§21準拠・英語ID指示・locale別 `display_text` 指示） | Structured Output | §20・§21 |
| F94 | `ai/schema/PlanJsonSchema`（JSON Schema文字列を定義し、`ResponseFormat.json(schema)` へ渡して**decode時に構造を強制**する） | Structured Output | §20 |
| F95 | `ai/schema/SchemaValidator`（**Kotlin側の独立した第2層検証**。enum/範囲/件数/UUID変換・Clamp） | Schema validation | §20・§15 |
| F96 | `ai/LocalAiGateway`（`AiResult.Success/Fallback(reason)`・retry1回・タイムアウト・**OOM事前ガード＋捕捉**（Gemini G1 CRITICAL #3）・**モデルのロード前再検証**（Gemini G1 CRITICAL #2）・全失敗のAnalytics記録） | Memory Handling / Schema validation | §19・§20・§95.6 |
| F97 | `features/settings/`（最小Settings画面: AIトグル・モデル状態・DL/削除・容量表示） | — | §18・§19・S-6 |

---

## §7. フットプリント

### 7.1 新設（Phase 7が作る。既存ファイルではない）

| パス | 種別 | 備考 |
|---|---|---|
| （新規モジュールなし） | — | 推奨案はAAR依存のみのため `:llm` モジュールを作らない（§8.2）。次点案を採る場合のみ発生 |
| `app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt` | Kotlin | F96。`AiResult` sealed interface を同居 |
| `app/src/main/java/com/actionstarter/ai/AiFallbackReason.kt` | Kotlin | §8.6の発動条件をenum化（Analyticsキーと1:1） |
| `app/src/main/java/com/actionstarter/ai/AiPreferences.kt` | Kotlin | F92。SharedPreferences実装 |
| `app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt` | Kotlin | F86 |
| `app/src/main/java/com/actionstarter/ai/model/{ModelCatalog,ModelDownloader,ModelVerifier,ModelStorage,DeviceCapability}.kt` | Kotlin | F87〜F91 |
| `app/src/main/java/com/actionstarter/ai/prompt/PlanPromptBuilder.kt` | Kotlin | F93 |
| `app/src/main/java/com/actionstarter/ai/schema/{PlanJsonSchema,SchemaValidator}.kt` | Kotlin | F94・F95 |
| `app/src/main/java/com/actionstarter/features/settings/{SettingsScreen,SettingsViewModel,SettingsUiState}.kt` | Kotlin | F97 |
| `app/src/test/java/com/actionstarter/ai/**` | test | E1/E2（§12） |
| `app/src/androidTest/java/com/actionstarter/e2e/LocalLlmOfflineE2ETest.kt` | test | E3。§0完成条件の実証 |
| `app/src/androidTest/java/com/actionstarter/probe/LlmBenchProbeTest.kt` | probe | §11。既存 `probe/AlarmExactAlarmProbeTest.kt` と同型（`@Ignore` 既定・Log出力） |
| `docs/probes/phase7-device-bench.md` | doc | 実機ベンチ手順書（§11.3） |

### 7.2 変更（既存ファイルに手を入れる。**すべてFable 5承認対象**）

| パス | 変更内容 | 破壊リスク |
|---|---|---|
| `app/build.gradle.kts` | `implementation(libs.litertlm.android)` 追加。必要なら `resolutionStrategy.force` で推移依存の版を統一（§13 #21）。**`testImplementation("org.json:json:<最新安定版>")` 追加**（U-11。`SchemaValidator` のテストをE1（純JVM）で実行するため） | **中（R-1）** |
| `gradle/libs.versions.toml` | `litertlm = "0.15.0"` と library エントリを追加（**バージョン固定**） | 低 |
| `app/src/main/AndroidManifest.xml` | `android:allowBackup` 方針の明確化（モデルをバックアップ対象外に） | 低 |
| `app/src/main/java/com/actionstarter/di/AppContainer.kt` | **`val localAiGateway: LocalAiGateway by lazy { ... }` を1本追加するのみ**。`planningEngine`/`recoveryEngine` の右辺は**無変更** | 中（§8.2・R-1） |
| `app/src/main/java/com/actionstarter/navigation/ActionStarterNavHost.kt` | Settings route 1本追加 | 中（既存 T-NAV-* への回帰） |
| `app/src/main/res/values/strings.xml` ＋ `values-ja/strings.xml` | Settings/AI関連文言（**ja/en同時追加。`StringResourceParityTest` が両者の差分を検出する**） | 中 |
| `app/src/test/.../PlanningLlmIsolationTest.kt` ／ `RecoveryLlmIsolationTest.kt` ／ `NotificationLlmIsolationTest.kt` | **禁止語リストへ `com.actionstarter.llm` を追加**し、走査を**再帰化**する（§9.2） | 中（強化方向のみ。assertion弱体化なし） |

### 7.3 触らない

`planning/`（3ファイル）・`recovery/`（5ファイル）・`domain/`（14ファイル）・`services/`（40ファイル）・`persistence/`・`features/{departure,eventselection,execution,planreview,recovery,common}`・`app/src/test/java/com/actionstarter/di/AppContainerTest.kt`。

---

## §8. 契約・設計

### 8.1 `ai/` パッケージ構造（§43の5要素への写像）

```text
com.actionstarter.ai
├── LocalLanguageModel.kt        （既存・無変更。§16）
├── AIPlanResponse.kt            （既存・無変更）
├── AIRecoveryResponse.kt        （既存・無変更）
├── LocalAiGateway.kt            （新規・唯一の外部公開点。AiResultで封じ込め）
├── AiFallbackReason.kt          （新規）
├── AiPreferences.kt             （新規）
├── adapter/  LiteRtLmLocalLanguageModel.kt      → §43 "ModelAdapters"
├── model/    ModelCatalog / ModelDownloader / ModelVerifier
│             ModelStorage / DeviceCapability   → §43 "ModelManager"
├── prompt/   PlanPromptBuilder                 → §43 "PromptBuilder"
└── schema/   PlanJsonSchema / SchemaValidator  → §43 "SchemaValidator"
```

**依存方向の規律**: `features/settings` → `ai/` → LiteRT-LM AAR の一方向のみ。**`com.google.ai.edge.litertlm` を直接importしてよいのは `ai/adapter/` 配下のみ**とし、`ai/` の他のサブパッケージ（model/prompt/schema）や `ai/LocalAiGateway.kt` からは参照しない。これにより §16「特定モデル依存コードをUIやDomain層へ入れない」「モデルは技術検証で交換可能にする」を構造で担保し、**次点のllama.cpp案へ切り替える場合も `ai/adapter/` の差し替えだけで済む**（§9.3 T-AIISO-9で機械検証）。

### 8.2 モジュール構成とABI（ADR-0002の明示トリガーへの回答）

ADR-0002 は「Phase 7でネイティブ依存が増大した時点で分割要否を再検討する」と定めており、Phase 7 は再検討トリガーに該当する。**再検討の結論は「単一 `:app` モジュールを継続する」**。理由は、推奨案（LiteRT-LM）が**Maven AAR依存のみでNDK・CMake・JNI・vendoringをプロジェクトに一切持ち込まない**ため、ADR-0002が想定した「ネイティブビルドによるビルド時間増大」が発生しないからである。ADR-0043 にこの判断を明示的に記録する。**次点のllama.cpp案へ切り替える場合のみ `:llm` モジュール分離が必要になる**（その場合はADR-0043を改訂する）。

**ABI（重要・G4-Eの成立条件）**: 実測で AVD `actionstarter_test` は **x86_64**（`hw.cpu.arch=x86_64` / `image.sysdir.1=…/x86_64/`、`hw.ramSize=4096`）である。調査エージェントが **AAR 0.15.0 を実際に展開して確認**したところ、`litertlm-android` は **arm64-v8a と x86_64 の2 ABI のみを同梱**（`liblitertlm_jni.so` は arm64 で 20.2MB、AAR全体 18.9MB）し、**armeabi-v7a を含まない**【調査実測】。したがって:

- **x86_64が含まれるため、G4-EのE3テストがエミュレータでそのまま成立する**（llama.cpp案なら自前ビルドでx86_64を追加する必要があった）。これは推奨案の実務上の大きな利点である。
- armeabi-v7a非対応 = **32bit端末はLocal AI対象外**。§5.3 段0の判定に `Build.SUPPORTED_ABIS` チェックを含める根拠（§8.6 #2）。
- **P7-C0でAARを展開し、ABI構成とminSdkを自プロジェクトで再確認する（V-1）。**

- `abiFilters` は**明示指定しない**方針を既定とする（AARが持つABIをそのまま使う）。release で `arm64-v8a` に絞るかは配布サイズ実測後にU-7で裁定する。
- AVD の RAM は 4096MB。**0.6B級（モデルファイル328MB）はエミュレータで検証可能だが、これは「小コンテキスト・テストプロファイル（`maxNumTokens`128〜256）でピークネイティブRAMを1GB級に抑えた場合に限る」という条件付きである**（Gemini G1 CRITICAL #4反映。フルコンテキストでのピーク実測値は約2.9GB級であり、AVDの総RAM4096MBでは安定確保できない前提に立つ。§11.2）。フルコンテキストでのピークRAM実測、および0.6Bを超える段はエミュレータでの安定検証を前提にせず、G4-D（実機）側に置く。

### 8.3 推論経路（LiteRT-LM Kotlin APIの使用範囲）

**すべて一次ソース（GitHubのKotlinソース／Maven POM）で存在を確認した要素のみを使う。**

| 用途 | 使用API | 確認状況 |
|---|---|---|
| エンジン生成（1回・プロセス常駐） | `EngineConfig(modelPath = <.litertlm の絶対パス>, backend = Backend.CPU())` | **確認済**（`Config.kt`。`backend` の既定が `Backend.CPU()`） |
| 会話生成（プロンプトごと） | `ConversationConfig(enableResponseFormat = true, thinkingConfig = <無効化>, maxOutputToken = <上限>)` | **確認済**（`Config.kt`。`enableResponseFormat` 既定 `false`、`thinkingConfig` / `maxOutputToken` の存在も確認） |
| 構造化出力の強制 | `sendMessage(text, responseFormat = ResponseFormat.json(PlanJsonSchema.TEXT))` | **確認済**（`Conversation.kt` / `ResponseFormat.kt`） |
| 非同期・キャンセル | `sendMessageAsync(...): Flow<Message>`（Flow版が存在する＝コルーチンのキャンセルに素直に乗る） | **確認済**（`Conversation.kt`） |
| 誤用時の防御 | `enableResponseFormat=false` のまま `responseFormat` を渡すと `IllegalArgumentException`（「response_format cannot be used unless enableResponseFormat=True was passed to ConversationConfig」） | **確認済**（`Conversation.kt`）。§13 #22で扱う |
| エンジン/会話の解放 | `close()` 相当のライフサイクルAPI | **未確認 → V-2**。P7-C0で `Engine`/`Conversation` の解放手段を確認し、§13 #8のアンロード設計を確定する |
| 実行スレッド数・メモリ上限の調整 | CPUスレッド数の指定手段 | **未確認 → V-3**。`Backend.CPU()` が引数を取るかを P7-C0 で確認する |
| AARのABI構成 | arm64-v8a / x86_64 の2ABIのみ（armeabi-v7aなし） | 【調査実測】。P7-C0で自プロジェクトで再確認 → V-1 |
| コンテキスト長の制御 | `EngineConfig.maxNumTokens` | **確認済**（`Config.kt`）。ピークRAM抑制の主手段（V-8） |
| 出力トークン上限 | `ConversationConfig.maxOutputToken` / `sendMessage(maxOutputToken=)` | **確認済**（`Config.kt` / `Conversation.kt`） |
| thinking無効化 | `ConversationConfig.thinkingConfig` / `sendMessage(thinkingConfig=)` | 存在は**確認済**。**無効化の具体的な指定方法は未確認 → V-4** |

**V-1〜V-3はP7-C0（スパイク）で実測して確定させる。確定するまで §8.2・§8.6・§13の該当記述を最終と見なさない。**

### 8.4 スキーマ（§20・§21準拠）と2層強制

`AIPlanResponse` / `AIPlanStepResponse` の既存フィールドに1:1対応させる。**絶対時刻フィールドは持たせない（§15）。**

```text
event_type        : string, enum固定（例 business_meeting / medical / social / travel / other）
steps             : array, minItems 1, maxItems 8
  action_type     : string, enum固定（英語ID。§21）
  display_text    : string, minLength 1, maxLength 60（表示文のみ。Domain判断に使わない）
  type            : string, enum [transition, preparation, departure, travel]
  estimated_minutes : integer, 1..120（S-4のClamp範囲）
  priority        : string, enum [required, important, optional]
  skippable       : boolean
additionalProperties : false（全階層）
```

**第1層（decode時）**: 上記スキーマ文字列を `ResponseFormat.json(...)` として `sendMessage` に渡し、constrained decoding で**構文とenumを生成時点で強制**する。**日本語 MIFEvalJa 0.425 の0.6B級モデルで §20 を成立させる中核**であり、これなしに小型モデルでスキーマ準拠JSONを安定生成することは期待できない。
**第2層（Kotlin側）**: `SchemaValidator` が**ランタイムの制約を信用せず独立に**再検証する（件数・enum・範囲・重複ID・`required` の `skippable=true` 矛盾・UUID変換）。**「constrained decodingがあるから検証不要」としないことを設計原則として固定する**（信頼境界。§20「Schema validation必須」）。

**トークン予算（decode 一桁 tok/s 前提の必須制約）**: 上記スキーマで steps 8件を出力すると概ね数百トークンとなり、decode 8〜13 tok/s では数十秒に達する。`ConversationConfig.maxOutputToken` に上限を設け、**実運用の既定 steps 件数は 5 程度に抑える**方向で P7-C0 の実測（V-4: thinking無効時の実出力トークン数）を踏まえて確定する。

### 8.5 `LocalAiGateway` の契約（S-3の解決）

```text
sealed interface AiResult<out T> {
  data class Success<T>(val value: T, val metrics: AiMetrics) : AiResult<T>
  data class Fallback(val reason: AiFallbackReason, val detail: String?) : AiResult<Nothing>
}
```

- **例外を外へ出さない**。`Throwable` は全て `Fallback` へ写像する（ただし**必ず** `reason` と `detail` を埋め、Analyticsへ記録する。§95.6「サイレントに握り潰さない」）。
- retryは§20どおり **1回のみ**（S-2の定義: 同一プロンプト・greedy・seed固定での再生成）。retry発生自体をメトリクスに残す。
- `AiMetrics` は §57 に対応: `modelLoadMs` / `firstTokenMs` / `totalMs` / `outputTokens` / `tokensPerSecond` / `peakNativeHeapBytes` / `retried` / `schemaValid`。**カレンダー本文・住所・座標は一切含めない（§60）。**

### 8.6 Basicフォールバック発動条件表

**タイムアウト閾値の数値は「ミッドレンジGalaxy A基準の仮置き」であり、G4-D（§11 P7-P2）の実測で確定する。現時点では未確定である。**

| # | 発動条件 | 検知方法（具体API） | 判定タイミング | 閾値（仮） | 動作 |
|---|---|---|---|---|---|
| 1 | 端末非対応（RAM） | `ActivityManager.MemoryInfo.totalMem` | AI有効化前 | §5.3の段別下限 | AIトグルを無効化し理由を表示。DLさせない |
| 2 | 端末非対応（ABI） | `Build.SUPPORTED_ABIS` に `arm64-v8a` を含まない | 同上 | — | 同上 |
| 3 | ストレージ不足 | `StatFs(noBackupFilesDir).availableBytes` | DL開始前 | `modelBytes × 1.5`（§95.6） | DL開始せず、必要量と空き容量を明示 |
| 4 | DL失敗 | IOException / HTTPステータス / 中断 | DL中 | retry導線 | Basic継続。「Private AIは現在利用できません」表示 |
| 5 | 検証失敗（改竄・破損。DL完了直後の1回目検証） | SHA-256不一致 / サイズ不一致 | DL完了直後（`.part`→正式名リネーム前） | — | **ファイルを削除**しロードしない。再DL導線 |
| 6 | ロード失敗 | `UnsatisfiedLinkError` / JNIがnullハンドルを返す | 初回推論時 | — | `Fallback(MODEL_LOAD_FAILED)`。当該セッションはAI無効 |
| 7 | OOM回避（能動的メモリガード・主防御。Gemini G1 CRITICAL #3反映） | モデルロード直前・推論開始直前に `ActivityManager.getMemoryInfo().availMem` を確認 | ロード直前／推論開始直前（毎回） | 必要ピークRAM＋安全マージンを下回る場合 | **ロード／推論を実行せず**即時 `Fallback(OUT_OF_MEMORY_PREVENTED)`。次段の小さいモデルを提案 |
| 8 | タイムアウト | `withTimeout` ＋ ネイティブ側中断（V-2に依存） | 推論中 | **仮 20,000ms**（プローブで確定） | `Fallback(TIMEOUT)` |
| 9 | スキーマ検証失敗 | `SchemaValidator` が不合格 | 生成直後 | retry 1回（§20） | 2回目も失敗なら `Fallback(SCHEMA_INVALID)` |
| 10 | AI OFF（既定） | `AiPreferences.aiEnabled == false` | 呼び出し入口 | — | `Fallback(AI_DISABLED)`。**推論を一切開始しない** |
| 11 | モデル未取得 | `ModelStorage` にファイルなし | 呼び出し入口 | — | `Fallback(MODEL_NOT_INSTALLED)` |
| 12 | 既存モデルの破損・改竄（ロード前検証。Gemini G1 CRITICAL #2反映） | 毎回: ファイルサイズ照合。プロセス初回ロード前: SHA-256再検証（結果はプロセス内キャッシュ。以後の呼び出しでは再計算しない） | モデルロード直前（サイズは毎回、SHA-256はプロセス内初回のみ） | サイズ完全一致／SHA-256完全一致 | **当該ファイルを削除**し `Fallback(MODEL_CORRUPTED)`。再DL導線を提示 |
| 13 | OOM（二次防御。事前ガードをすり抜けた残余ケース） | `OutOfMemoryError` 捕捉 / ネイティブalloc失敗 | ロード・推論中 | — | モデルをアンロードし `Fallback(OUT_OF_MEMORY)`。次段の小さいモデルを提案 |

**#7と#13・#5と#12の関係（Gemini G1 CRITICAL #2/#3反映・確定）**: LinuxのLow Memory Killer（LMK）によるプロセスSIGKILLは、Javaの `try/catch` では**原理的に捕捉できない**（プロセスが即座に強制終了するため例外送出の余地がない）。したがって「`OutOfMemoryError` を捕捉して処理する」設計だけではLMKによる強制終了を防げない。**#7（能動的メモリガード）を主防御とし、ロード・推論を試みる前に空きメモリを確認して危険な場合は実行しないことでLMKに至る状況そのものを回避する。** #13（Javaの`catch(OutOfMemoryError)`）は、#7をすり抜けた軽微なJavaヒープ枯渇等に対する**二次防御**として残置する。同様に、**#5はDL完了直後の1回限りの検証であり、時間経過後の破損・改竄（ストレージ異常・手動改変等）は捕捉できない**。**#12（ロード前検証）は毎回のロード直前に再検証することでこの穴を塞ぐ**主防御であり、#5と#12は独立して両方とも必要とする。

### 8.7 UX設計原則（Fable 5指示・非同期前提）

本アプリのLLM出力は §14 のとおり短い構造化JSONのみであり、ミッドレンジCPU推論では**秒オーダーの待ちが避けられない**。したがって Phase 8 以降の配線を見据え、Phase 7 の契約段階で次を固定する。

1. **Basic即時 → AI後差し替え**。画面表示は常に Basic Engine の決定的結果で即座に成立させ、AI結果は完了後に**差分として置き換える**（AI待ちでUIをブロックしない）。`LocalAiGateway` は `suspend` 関数として、**呼び出し側がバックグラウンドで待てる**契約にする。
2. **AI推論はフォアグラウンド限定**（Phase 7時点）。バックグラウンド／アラーム起点での推論は行わない（電池・Doze・§95.1と整合）。
3. **キャンセル可能**。画面離脱時に `CoroutineScope` のキャンセルで推論を中断できること（V-2の確定に依存）。中断は失敗ではないため Analytics上 `CANCELLED` として `Fallback` と区別する。
4. **AI結果が来ないことは正常系**。「AIが間に合わなかった」ことを**エラーとして見せない**（§19: EnhancementでありSPOFにしない）。

---

## §9. AI隔離ガードの拡張

**本節を貫く原則（Gemini G1 CRITICAL #1反映）**: `ai/` 配下が外部と通信してよい経路は、モデルDL専用として明示された**単一の許可クラス（`ai/model/ModelDownloader.kt`。許可リスト1件・U-9裁定）経由のみ**である。「生のネットワークAPI（`java.net.`/`HttpURLConnection`/`URL(`）を直接使わない」だけでは不十分で、**自プロジェクトが `ai/` の外に持つ通信ラッパークラス（例: `com.actionstarter.services.routing` 配下の `UrlConnectionHttpPostClient` 等の既存HTTP手段）を `ai/` から呼び出して迂回する経路**も同じ強度で塞ぐ。T-AIISO-6（§9.3）はこの原則を機械検証する。

### 9.1 既存3本の実測仕様（変更前）

| ファイル | テストID | 走査対象 | 禁止語 |
|---|---|---|---|
| `app/src/test/java/com/actionstarter/planning/PlanningLlmIsolationTest.kt` | T-BPE-28 | `src/main/java/com/actionstarter/planning` 直下の `.kt` | `com.actionstarter.ai` ／ `LocalLanguageModel` |
| `app/src/test/java/com/actionstarter/recovery/RecoveryLlmIsolationTest.kt` | T-BRE-32 | `…/recovery` 直下の `.kt` | `com.actionstarter.ai` **のみ** |
| `app/src/test/java/com/actionstarter/services/notification/NotificationLlmIsolationTest.kt` | T-NOTIF-9 | `…/services/notification` 直下の `.kt` | `com.actionstarter.ai` ／ `LocalLanguageModel` |

共通仕様（3本とも同一）: 相対パスを3段fallbackで解決し、解決不能なら `error()` で **hard fail**（偽陽性Green防止）。`listFiles { isFile && extension == "kt" }` で **非再帰**列挙。`readText().contains(...)` の**単純部分文字列マッチ**。ファイル0件なら失敗。**許可リストなし**。

### 9.2 Phase 7で必要な拡張（3つの穴を塞ぐ）

既存ガードには、Phase 7 が導入する構造に対して**3つの取りこぼし**がある。いずれも「強化方向のみ」でありassertion強度を下げない（TEAMS §2の既存テスト変更条件を満たす）。

| 穴 | 具体的な抜け道 | 対処 |
|---|---|---|
| **穴A: ランタイムのパッケージ名を知らない** | `planning/BasicPlanningEngine.kt` が `import com.google.ai.edge.litertlm.Engine` と直接書いても**3本とも素通りする**（禁止語にランタイムのパッケージ名がない）。次点のllama.cpp案なら `com.actionstarter.llm` が同じ穴になる | 3本の禁止語リストへ **`com.google.ai.edge.litertlm`**（および将来の代替ランタイムのルートパッケージ）を追加 |
| **穴B: 非再帰** | `planning/internal/Foo.kt` のようにサブディレクトリを作れば走査対象外になる | `walkTopDown()` へ変更し再帰化。あわせて「サブディレクトリが存在しても検出できる」ことを保証するテストを追加 |
| **穴C: recoveryだけ `LocalLanguageModel` を見ていない** | `recovery/` が `LocalLanguageModel` 型だけを参照する形（同名importなし）を素通りさせうる | 3本の禁止語リストを**同一**に揃える（非対称の解消） |

### 9.3 新設ガード（Phase 7が追加する4本）

| ID | 内容 | 区分 |
|---|---|---|
| T-AIISO-4 | `domain/` 配下（再帰）が `com.actionstarter.ai` / `com.actionstarter.llm` / `LocalLanguageModel` を参照しない（§16「Domain層へ入れない」の機械検証） | E1 |
| T-AIISO-5 | `ai/` 配下（再帰）が `com.actionstarter.features` を参照しない（AI層がUIに依存しない＝headless維持） | E1 |
| T-AIISO-6 | `ai/` 配下（再帰）で、①ネットワークAPI（`java.net.` / `HttpURLConnection` / `URL(`）、②**`com.actionstarter.services.routing` 配下（`UrlConnectionHttpPostClient` 等の自プロジェクトHTTP手段）のimport/参照**、のいずれかを参照してよいのは **`ai/model/ModelDownloader.kt` の1ファイルのみ**。推論経路のファイルが1つでも参照したら失敗（**§10の外部送信禁止を構造で担保。Gemini G1 CRITICAL #1: 自プロジェクトの通信ラッパー経由の迂回穴も追加で塞ぐ**） | E1 |
| T-AIISO-7 | `domain/` および `services/` 配下（再帰）が `com.google.ai.edge.litertlm` を参照しない（ランタイムがDomain/Serviceへ漏れない） | E1 |
| T-AIISO-9 | **`com.google.ai.edge.litertlm` をimportしてよいのは `ai/adapter/` 配下のみ**。`ai/` の他のサブパッケージ・`LocalAiGateway.kt`・`features/` から参照したら失敗（§16「モデルは技術検証で交換可能にする」の構造担保。次点案への切替を `ai/adapter/` 差し替えだけで可能にする） | E1 |

**注**: T-AIISO-6 は「許可リストを持つ初のガード」になる。既存3本が許可リストを持たない設計思想と異なるため、**許可対象は単一ファイル名の完全一致に限定**し、許可リストの肥大を禁止する旨をADR-0044に記録する（U-9）。

---

## §10. カレンダー本文等を端末外へ送らない検証方法（§10・§58〜§60）

「送っていない」ことは**通信が起きないことの機械的証明**でしか示せない。3層で検証する。

| 層 | 手段 | 何を証明するか | 区分 |
|---|---|---|---|
| **L1 構造** | §9のT-AIISO-6（推論経路にネットワークAPIが存在しないことをソース走査で証明） | そもそも送信するコードが存在しない | E1 |
| **L2 実行時（強制）** | 推論を専用スレッドで実行し、そのスレッドに `StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath()` を適用したうえで `LocalAiGateway.generatePlan()` を1回通す。ソケットに触れた瞬間にプロセスが落ちるため、**Passすること自体が無通信の証明**になる | 推論中に一切のネットワークI/Oが発生しない | E3 |
| **L3 実環境** | 機内モードON（`UiAutomator` またはG4-Dの手動手順）で推論を実行し、`AiResult.Success` が返ることを確認 | ネットワークが物理的に無い状態でも機能が成立する（§71完成条件と同一の試験） | E3／E4 |

**加えて**: `AiMetrics`（§8.5）にカレンダー本文・イベントタイトル・住所・座標を**含めない**ことを、フィールド名の許可リスト方式でユニットテスト化する（T-AIMET-1）。§60の「送ってよい」列挙（`event_category_hash` / `plan_generation_ms` / `step_count` / `delay_seconds` / `AI_enabled`）に対応するフィールドのみを許可する。

---

## §11. 推論ベンチプローブ（2段構え）

`docs/GOAL.md`／§57 の性能指標を実測で埋めるための使い捨て計測。**正式テストではない**（既存 `app/src/androidTest/java/com/actionstarter/probe/AlarmExactAlarmProbeTest.kt` と同型: `@Ignore` 既定・`Log` 出力・目的と測定対象をKDocに明記）。

### 11.1 測定項目（§57に1:1対応）

| 項目 | 取得方法 | §57対応 |
|---|---|---|
| モデルロード時間（ms） | `Engine` 初期化の前後で `SystemClock.elapsedRealtime()` | （新規・実運用に直結） |
| 初回トークンまでの時間 TTFT（ms） | `sendMessageAsync(...): Flow<Message>` の最初のemitまで | Plan generation latency |
| 生成速度（tok/s） | 出力トークン数 ÷ 生成時間 | Plan generation latency |
| 総レイテンシ（ms） | 呼び出し〜`AiResult` 返却 | Plan generation latency |
| ピークRAM | `Debug.getNativeHeapAllocatedSize()` ＋ `ActivityManager.getProcessMemoryInfo()` の `totalPss` を推論中に定期サンプリング | RAM |
| 実行時のコンテキスト長（`maxNumTokens`） | 各回のログに記録。**P7-P1は小コンテキスト・テストプロファイル（128〜256）に固定、P7-P2はフルコンテキスト（ctx4096）を基本に複数条件を記録可**（Gemini G1 CRITICAL #4。測定条件の透明性確保） | （新規・§11.2・§11.3） |
| JSON妥当性／Schema妥当性 | 同一プロンプト×N回（例 N=20）での成功率 | JSON validity / Schema validity |
| 発熱・バッテリー所見 | `BatteryManager` の `BATTERY_PROPERTY_CAPACITY` 差分と、`ACTION_BATTERY_CHANGED` の `EXTRA_TEMPERATURE` を推論前後で記録。**加えて連続N回実行での tok/s の低下率**（サーマルスロットリングの間接指標） | Battery / Thermal |
| モデルDLサイズ | `ModelCatalog` の定義値と実DLバイト数の照合 | Model download size |

### 11.2 P7-P1（エミュレータ）

AVD `actionstarter_test`（**実測: x86_64 / API 35 / RAM 4096MB**）。目的は「配線が通っていること」と「回帰の常時監視」であり、**速度の絶対値には意味がない**（x86_64エミュレータのため）。測定するのはロード成否・JSON妥当性・スキーマ妥当性・ピークRAMのみとし、**tok/s は参考値としても記録しない**（誤解を招くため）。

**実行構成（Gemini G1 CRITICAL #4反映・確定）**: フルコンテキスト（ctx4096）でのQwen3-0.6Bのピークネイティブメモリは実測約2.9GB（§5.2・R-4）であり、AVDの`hw.ramSize=4096`（総RAM。OS・エミュレータ自体のオーバーヘッドを差し引くとアプリに残る実効メモリはさらに少ない）ではフルピークでの実推論は成立しない可能性が高い。したがって**E3の標準実行構成を「小コンテキスト・テストプロファイル」と定義する**: `EngineConfig.maxNumTokens`（コンテキスト長）を**128〜256に制限**し、ピークネイティブRAMを**1GB級へ抑制**した状態でP7-P1・T-P7E2E-1〜5（§12.8）を実行する。フルコンテキスト（ctx4096・ピーク2.9GB級）での実測は**P7-C8実機プローブの測定項目に移す**（§11.3・§12.8のP7-P2）。**AVDの`hw.ramSize`を6144MBへ引き上げることは、小コンテキストプロファイルでも起動・推論が成立しない場合の代替手段として記録するに留め、既定の実行構成は`hw.ramSize=4096`のままとする。**V-8（`maxNumTokens`のピークRAM低減幅）はこの成立可否を左右するため、P7-C0の必須測定項目へ格上げする（§14）。

### 11.3 P7-P2（実機・Galaxy A系）

**手順書 `docs/probes/phase7-device-bench.md` を新規作成する。** APKサイドロード方式は確立済みのため、手順書は次を含む。

1. 端末情報の記録: `Build.MODEL` / `Build.SOC_MODEL`（API 31+）/ `totalMem` / `SUPPORTED_ABIS` / `/proc/cpuinfo` の `Features` 行（**dotprod・i8mm・sve の有無を実測**。KleidiAIがどのカーネルを選ぶかを左右する）。
2. debug APK（`arm64-v8a` 含む）をサイドロードし、Settings画面から 0.6B級モデルをDL。
3. `LlmBenchProbeTest` を `@Ignore` 解除して `connectedDebugAndroidTest` で実行、または Settings のデバッグボタンから起動（U-10で方式を裁定）。
4. §11.1の全項目を **Qwen3-0.6B int4** について記録。比較のため **Qwen3-1.7B int4** と **Gemma3-1B int4** も同一条件で記録（§5.2の最終選択の根拠にする）。**フルコンテキスト（ctx4096）でのピークRAM実測はここP7-P2が担う（エミュレータは小コンテキスト・テストプロファイルに限定するため。§11.2・§12.8）。**
5. 端末を冷やした状態と、連続5回実行後の状態で2回測定し、スロットリング影響を見る。
6. 記録結果を本書§8.6のタイムアウト閾値（仮20,000ms）とV-3の確定に反映する。

**この実測が完了するまで、§8.6の閾値・§5.3の段階推奨の境界値・§12のE4テスト期待値はいずれも暫定である。**

---

## §12. テストケース表

### 12.1 区分定義（JVMでモック可能な層と実機必須層の区分）

| 区分 | 内容 | source set | Gradleタスク | 必要環境 | Phase 7での意味 |
|---|---|---|---|---|---|
| **E1** | 純Kotlin/JVM。Android Framework非依存。**ネイティブライブラリも実モデルも不要** | `src/test` | `:app:testDebugUnitTest` | 不要 | スキーマ・プロンプト・フォールバック判定・容量計算・再開オフセット計算・ガード類。**Phase 7のロジックの大半をここに寄せる** |
| **E2** | Robolectric（`Context`・`SharedPreferences`・`StatFs`・`org.json`・Compose Test）。**`LocalLanguageModel` はfake実装に差し替える** | `src/test` | `:app:testDebugUnitTest` | 不要 | ModelStorage・AiPreferences・Settings画面・AppContainer結線 |
| **E3** | instrumented。**実 `.so` ロード＋実モデル＋実推論が必要**（AVD上は小コンテキスト・テストプロファイル。§11.2・§12.8） | `src/androidTest` | `:app:connectedDebugAndroidTest` | エミュレータ（x86_64 ABI必須） | JNI疎通・機内モードJSON取得・StrictModeネットワーク遮断 |
| **E4** | 実機のみ。**性能・発熱・実RAMは実機でしか測れない** | `src/androidTest`（probe） | 手動 | Galaxy A系実機 | §11.3のベンチ。閾値確定の根拠 |

> **`org.json` の可用性に注意（U-11裁定済み）**: 実測で `ExecutionScheduleStoreTest` は `@RunWith(RobolectricTestRunner::class)` を使っており、`org.json` はRobolectric経由で供給されている。Android SDK同梱の `org.json`（`android.jar`）は**純JVM実行時にはスタブであり呼び出すと `Stub!` 例外を送出する**（Gemini G1 CRITICAL #5で指摘）ため、Robolectricなしの純JVMテストでは実クラスとして機能しない。**U-11の裁定により**、本番コードは引き続きAndroid同梱の `org.json` を使用し、テスト側のみ `testImplementation("org.json:json:<最新安定版>")`（pure Java実装）を追加することで、`SchemaValidator` のテストを**Robolectric不要のE1（純JVM）として実行する**。本表（§12.2）のT-SCH-*/T-RF-*は**E1として確定**する（暫定E2ではない）。

### 12.2 F94/F95 — スキーマとバリデータ（E1／`ai/schema/`）

| ID | 区分 | 内容・期待値 |
|---|---|---|
| T-SCH-1 | 正常系 | §20の公式JSON例（`event_type`＋1ステップ）が検証を通過し `AIPlanResponse` へ写像される |
| T-SCH-2 | 正常系 | `action_type`／`type`／`priority` の生Stringが正しくDomain enumへ変換される |
| T-SCH-3 | 異常系 | `event_type` がenum外 → 不合格。理由に該当フィールド名が含まれる |
| T-SCH-4 | 異常系 | `priority` がenum外（例 `"critical"`）→ 不合格 |
| T-SCH-5 | 異常系 | `type` がenum外 → 不合格 |
| T-SCH-6 | 異常系 | `steps` が空配列（minItems違反）→ 不合格 |
| T-SCH-7 | エッジ | `steps` が9件（maxItems=8超過）→ 不合格 |
| T-SCH-8 | エッジ | `steps` がちょうど8件 → 合格（境界値） |
| T-SCH-9 | エッジ | `estimated_minutes = 0` → 不合格（min=1） |
| T-SCH-10 | エッジ | `estimated_minutes = 1` / `= 120` → 合格（両境界） |
| T-SCH-11 | エッジ | `estimated_minutes = 121` → **Clampされず不合格**（S-4の上限は検証で弾く。黙って丸めない） |
| T-SCH-12 | 異常系 | `estimated_minutes` が文字列 `"10"` → 不合格（型不一致） |
| T-SCH-13 | 異常系 | 未知フィールドが存在（`additionalProperties:false`）→ 不合格 |
| T-SCH-14 | 異常系 | 必須フィールド欠落（`skippable` なし）→ 不合格 |
| T-SCH-15 | 異常系 | `display_text` が空文字 → 不合格（minLength=1） |
| T-SCH-16 | エッジ | `display_text` が61文字 → 不合格 / 60文字 → 合格 |
| T-SCH-17 | 異常系 | JSONとして壊れている（閉じ括弧なし）→ 例外を投げず不合格を返す |
| T-SCH-18 | 異常系 | 空文字列入力 → 不合格 |
| T-SCH-19 | 異常系 | JSON前後にモデルの前置き文が付く（```json フェンス等）→ **不合格**（文法制約下では起きないはずの事象。黙って剥がさず検出する） |
| T-SCH-20 | エッジ | `priority=required` かつ `skippable=true` の矛盾 → 不合格（§33整合） |
| T-SCH-21 | エッジ | 同一 `action_type` の重複ステップ → 不合格 |
| T-SCH-22 | 異常系 | 深いネスト・巨大配列（DoS的入力）で例外を投げず一定時間内に不合格を返す |
| T-RF-1 | 正常系 | `PlanJsonSchema.TEXT` がJSONとして構文的に妥当で、全enum値・`additionalProperties:false`・`required` を含む |
| T-RF-2 | 正常系 | `PlanJsonSchema.TEXT` が定数であり、呼び出しごとに同一文字列を返す（決定性） |
| T-RF-3 | 正常系 | `AIPlanStepResponse` の全フィールドがスキーマの `properties` に1:1で存在する（**スキーマとKotlinデータクラスの乖離を防ぐ回帰ロック**） |
| T-RF-4 | エッジ | スキーマに絶対時刻・ETA・座標に相当するプロパティが存在しない（§15の機械検証） |

### 12.3 F93 — プロンプト（E1／`ai/prompt/`）

| ID | 区分 | 内容・期待値 |
|---|---|---|
| T-PRM-1 | 正常系 | `PlanningContext` から生成したプロンプトにイベントタイトル・開始時刻・場所が含まれる |
| T-PRM-2 | 正常系 | `locale=ja` のとき `display_text` を日本語で出す指示が含まれ、`action_type` は**英語IDのまま**という指示が含まれる（§21） |
| T-PRM-3 | 正常系 | `locale=en` で同上（英語） |
| T-PRM-4 | 異常系 | イベントタイトルが極端に長い（1000字）→ 上限で切り詰め、プロンプト全体が上限トークン内に収まる |
| T-PRM-5 | エッジ | タイトルが空／場所がnull → プロンプト生成が例外にならない |
| T-PRM-6 | 異常系 | タイトルにプロンプトインジェクション文字列（"ignore previous instructions" 等）が含まれても、**指示部とデータ部が構造的に分離**されている（データ部が区切りトークンで囲まれる） |
| T-PRM-7 | 正常系 | プロンプトに絶対時刻の**計算**を要求する文言が含まれない（§15の機械検証） |

### 12.4 F87〜F91 — モデル管理（E1／E2）

| ID | 区分 | 内容・期待値 |
|---|---|---|
| T-MDL-1 | E1・正常系 | `DeviceCapability` が `totalMem` から正しい推奨段を返す（§5.3の境界値ごと） |
| T-MDL-2 | E1・エッジ | 段の境界値ちょうど（例 6GB）で期待どおりの段になる |
| T-MDL-3 | E1・異常系 | `SUPPORTED_ABIS` に `arm64-v8a` がない → `UNSUPPORTED_ABI` |
| T-MDL-4 | E1・正常系 | 容量ガード: 必要量 = `modelBytes × 1.5` の計算が正しい（§95.6） |
| T-MDL-5 | E1・エッジ | 空き容量が必要量ちょうど → 許可 / 1バイト不足 → 拒否 |
| T-MDL-6 | E1・正常系 | 再開DL: 既存部分ファイル長からRangeヘッダのオフセットが正しく決まる |
| T-MDL-7 | E1・異常系 | サーバがRangeを無視して200を返した → **部分ファイルを破棄して先頭から**やり直す（追記して壊さない） |
| T-MDL-8 | E1・異常系 | ダウンロード中の総バイト数がカタログ定義値を超えた → 即中断（無限DL防止） |
| T-MDL-9 | E1・正常系 | SHA-256照合が一致で合格 |
| T-MDL-10 | E1・異常系 | SHA-256不一致 → 不合格かつ**ファイル削除**が呼ばれる |
| T-MDL-11 | E1・異常系 | サイズ一致・ハッシュ不一致（改竄想定）→ 不合格 |
| T-MDL-12 | E2・正常系 | 検証通過後に `.part` → 正式名へ**原子的リネーム**され、検証前の名前ではロードされない |
| T-MDL-13 | E2・異常系 | リネーム前にプロセスが落ちた想定（`.part` だけ残存）→ 次回起動時に**未完了として扱い自動削除**される |
| T-MDL-14 | E2・正常系 | 保存先が `noBackupFilesDir` 配下である（Auto Backup対象外。数百MBのバックアップを起こさない） |
| T-MDL-15 | E2・正常系 | モデル削除で実ファイルが消え、`AiPreferences` の状態も未インストールへ戻る |
| T-MDL-16 | E1・異常系 | DL先URLが `https` でない → 拒否（平文DLの禁止） |

### 12.5 F96 — Gateway とフォールバック（E1／E2）

**§8.6の13条件すべてに1:1でテストを置く。空欄なし。**

| ID | 区分 | 内容・期待値 |
|---|---|---|
| T-GW-1 | E2・正常系 | AI ON＋モデル導入済＋fakeモデルが正しいJSONを返す → `AiResult.Success` |
| T-GW-2 | E2・正常系 | **AI OFF（既定）→ `Fallback(AI_DISABLED)`。fakeモデルの `generatePlan` が1回も呼ばれない**（§19） |
| T-GW-3 | E2・正常系 | モデル未DL → `Fallback(MODEL_NOT_INSTALLED)`、推論を開始しない |
| T-GW-4 | E1・異常系 | fakeモデルが `UnsatisfiedLinkError` を投げる → `Fallback(MODEL_LOAD_FAILED)`、**例外は外へ出ない** |
| T-GW-5 | E1・異常系 | **事前ガード（主防御）**: fakeのメモリ情報プロバイダが「必要ピークRAM＋安全マージンを下回る」を返す → ロード/推論を一切開始せず `Fallback(OUT_OF_MEMORY_PREVENTED)`。**fakeモデルの `generatePlan`／ロードが1回も呼ばれない**ことを検証（Gemini G1 CRITICAL #3。LMKによるSIGKILLは捕捉不能なため実行前の回避を主防御とする） |
| T-GW-6 | E1・異常系 | fakeモデルが閾値を超えて応答しない → `Fallback(TIMEOUT)`（`runTest` の仮想時間で検証） |
| T-GW-7 | E1・異常系 | 1回目がスキーマ不合格・2回目が合格 → **`Success` かつ `metrics.retried == true`**（§20 retry1回） |
| T-GW-8 | E1・異常系 | 1回目も2回目も不合格 → `Fallback(SCHEMA_INVALID)`。**呼び出し回数がちょうど2回**（3回目を呼ばない） |
| T-GW-9 | E1・異常系 | 端末非対応（RAM不足）→ `Fallback(UNSUPPORTED_DEVICE)` |
| T-GW-10 | E1・異常系 | 端末非対応（ABI）→ `Fallback(UNSUPPORTED_ABI)` |
| T-GW-11 | E1・異常系 | 容量不足 → DLを開始しない（`Fallback(INSUFFICIENT_STORAGE)`） |
| T-GW-12 | E1・異常系 | fakeモデルが未定義の `RuntimeException` を投げる → `Fallback(UNKNOWN)` かつ `detail` に例外クラス名が入る（**サイレント握り潰しの禁止**） |
| T-GW-13 | E1・エッジ | 呼び出し側のコルーチンがキャンセルされた → `CancellationException` を**握り潰さず再送出**する（構造化並行性を壊さない）。`Fallback` に化けさせない |
| T-GW-14 | E1・正常系 | すべての `Fallback` 経路で Analytics 記録が1回呼ばれる（§95.6「サイレントに握り潰さない」の機械検証） |
| T-GW-15 | E1・正常系 | 同時に2回呼ばれても推論が直列化される（`Mutex`）。ネイティブコンテキストの同時使用を起こさない |
| T-GW-16 | E1・正常系 | 2回目の呼び出しでモデルの再ロードが起きない（ロードは1回・KVキャッシュのみクリア） |
| T-GW-17 | E1・異常系 | **二次防御**: 事前ガードは通過したが、fakeモデルが `OutOfMemoryError` を投げる（事前ガードをすり抜けた残余ケースを想定）→ `Fallback(OUT_OF_MEMORY)` かつアンロードが呼ばれる（Javaの `catch` は二次防御として残置。Gemini G1 CRITICAL #3） |
| T-GW-18 | E1・異常系 | **ロード前検証**: fakeの検証器が「プロセス初回ロード前のSHA-256再検証で不一致」を返す → 当該ファイルの削除が呼ばれ `Fallback(MODEL_CORRUPTED)`。**同一プロセス内の2回目以降の呼び出しではSHA-256を再計算しない**（結果がキャッシュされる）ことも検証（Gemini G1 CRITICAL #2） |
| T-AIMET-1 | E1・正常系 | `AiMetrics` のフィールド集合が§60の許可リストに一致し、自由文フィールドを持たない |

### 12.6 F92／F97 — 設定（E2）

| ID | 区分 | 内容・期待値 |
|---|---|---|
| T-SET-1 | 正常系 | **初回起動時 `aiEnabled == false`**（§19の既定OFF） |
| T-SET-2 | 正常系 | トグルONで永続化され、プロセス再生成後も保持される |
| T-SET-3 | 正常系 | 非対応端末ではトグルが無効表示され、理由文言が出る |
| T-SET-4 | 正常系 | Settings画面にモデル状態（未DL／DL中％／導入済／検証失敗）が表示される |
| T-SET-5 | 正常系 | DL前に「必要容量」と「現在の空き容量」が表示される（§95.6） |
| T-SET-6 | 異常系 | DL失敗時にretry導線が出て、Basic機能には影響がない |
| T-SET-7 | 正常系 | ja/en 両方で新規文言が解決される（既存 `StringResourceParityTest` を通す） |
| T-SET-8 | 正常系 | フォントスケール1.5xでSettings画面がクリップしない（既存 `FontScaleResilienceTest` の方式踏襲） |
| T-P7DI-1 | 正常系 | `AppContainer.planningEngine is BasicPlanningEngine` が**引き続きtrue**（Phase 7はAI配線しない） |
| T-P7DI-2 | 正常系 | `AppContainer` 生成時点で `localAiGateway` が**まだ初期化されていない**（`lazy` の検証。起動を重くしない） |

### 12.7 隔離ガード（E1）

§9.2の3本改修＋§9.3の新設4本。T-BPE-28／T-BRE-32／T-NOTIF-9（改修後）、T-AIISO-4〜7。加えて:

| ID | 区分 | 内容・期待値 |
|---|---|---|
| T-AIISO-8 | エッジ | **ガード自身のメタテスト**: 一時ディレクトリに禁止語を含むダミー`.kt`をサブディレクトリ付きで作り、検出器が**再帰的に**それを検出することを確認（穴Bが本当に塞がったことの証明） |

### 12.8 実機・エミュレータ必須（E3／E4）

**E3の実行構成（Gemini G1 CRITICAL #4反映・確定）**: 以下のE3区分（T-P7E2E-1〜5・P7-P1）は、AVD `actionstarter_test`（RAM 4096MB）上でピークネイティブRAMを1GB級に抑える**小コンテキスト・テストプロファイル**（`maxNumTokens`／コンテキスト長を**128〜256に制限**。§11.2）で実行する。フルコンテキスト（ctx4096・ピーク2.9GB級）での実推論確認はエミュレータでは行わず、**P7-P2（実機Galaxy A・§11.3）の測定項目とする**。プロファイルでもAVD上でロード・推論が安定しない場合は、AVDの`hw.ramSize`を6144MBへ引き上げることを代替手段として検討する（§11.2）。

| ID | 区分 | 内容・期待値 |
|---|---|---|
| T-P7E2E-1 | E3 | 実 `.so` がロードでき `LlamaBridge` が非nullハンドルを返す（JNI疎通）。**小コンテキスト・テストプロファイル下** |
| T-P7E2E-2 | E3 | **機内モード下でテストPrompt→`AiResult.Success`（§71完成条件そのもの）。小コンテキスト・テストプロファイル（`maxNumTokens`128〜256）下で実証する** |
| T-P7E2E-3 | E3 | StrictMode `detectNetwork().penaltyDeath()` 下で推論が完走する（§10 L2）。**小コンテキスト・テストプロファイル下** |
| T-P7E2E-4 | E3 | 破損モデルファイル（先頭バイト改変）を置くと `Fallback(MODEL_LOAD_FAILED)` になりクラッシュしない |
| T-P7E2E-5 | E3 | 推論中に画面回転／Activity再生成 → クラッシュせず、Gatewayが直列性を保つ。**小コンテキスト・テストプロファイル下** |
| P7-P1 | E3(probe) | エミュレータ: **小コンテキスト・テストプロファイル下**でのロード成否・JSON妥当性・スキーマ妥当性・ピークRAM（**tok/sは記録しない**。§11.2） |
| P7-P2 | E4(probe) | 実機Galaxy A: §11.1の全項目。**フルコンテキスト（ctx4096）でのピークRAM実測を含む（エミュレータから移管）。§8.6の閾値の確定根拠** |

---

## §13. エラー＆レスキューマップ（ハンドリング方法列に空欄なし）

| # | 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|---|
| 1 | ネイティブライブラリのロード | `.so` が該当ABI向けに含まれていない／`UnsatisfiedLinkError` | `System.loadLibrary` を try/catch し、`DeviceCapability` を `UNSUPPORTED_ABI` へ固定。以後AIトグルを無効化。Analyticsへ記録 | AI機能が出ないだけで、Basic Engineの全機能が通常どおり動作する（§19） |
| 2 | モデルDL | 回線断・タイムアウト・HTTP 4xx/5xx | 部分ファイルを保持しRangeで再開。retry導線を提示。3回連続失敗でユーザーに手動再試行を促す。理由をログとAnalyticsに記録 | DL完了までAI提案が出ないが、予定成立支援は継続する |
| 3 | モデルDL | 空き容量不足 | **開始前に** `StatFs` で `modelBytes × 1.5` を検証。不足時は開始せず、必要量と空き容量を数値で明示（§95.6） | 容量確保までAI利用不可。Basicで全機能継続 |
| 4 | モデルDL | サーバがRange無視で全体を返す／Content-Lengthがカタログ値と不一致 | 部分ファイルを破棄して先頭から取得。総バイトがカタログ値を超えた時点で中断 | 再DLで時間はかかるが破損モデルは残らない |
| 5 | モデル検証 | SHA-256不一致（改竄・破損・配布元差し替え） | **ファイルを即削除**しロードしない。`MODEL_VERIFY_FAILED` をAnalyticsへ記録し、ユーザーには再DLを提示 | 不正なモデルが動くことがない。Basicで継続 |
| 6 | モデル保存 | 検証前ファイルが正式名で残りロードされる | `.part` 名でDLし、**検証通過後にのみ**原子的リネーム。起動時に残存 `.part` を掃除 | 中断後の再起動でも壊れたモデルを掴まない |
| 7 | モデル保存 | Auto Backupが数百MBのモデルを吸い上げる | 保存先を `noBackupFilesDir` に固定し、Manifestのバックアップ規則でも除外 | ユーザーのバックアップ容量・通信量を圧迫しない |
| 8 | モデルロード | RAM不足によるロード失敗（mmap失敗／ネイティブalloc失敗）。**LMK（Low Memory Killer）によるプロセスSIGKILLはJava層で捕捉不能なため、発生させないことが唯一の防御**（Gemini G1 CRITICAL #3） | **能動的メモリガード（主防御）**: モデルロード直前・推論開始直前に `ActivityManager.getMemoryInfo().availMem` を確認し、必要ピークRAM＋安全マージンを下回る場合は**実行せず**即時 `Fallback(OUT_OF_MEMORY_PREVENTED)` を返す。Javaの `catch(OutOfMemoryError)` は事前ガードをすり抜けた残余ケースに対する**二次防御**として残置し、捕捉時はモデルをアンロードして `Fallback(OUT_OF_MEMORY)` を返し、**次に小さい段のモデル**を提案 | クラッシュせず、Basicへ落ちる。より軽いモデルの選択肢が示される |
| 9 | 推論 | 応答が閾値を超える（低速端末・サーマルスロットリング） | `withTimeout` で打ち切り `Fallback(TIMEOUT)`。ネイティブ側の中断可否はV-2で確定させ、**中断できない場合はワーカースレッドを孤立させず必ず完了を待って破棄**する（スレッドリーク防止） | AI提案が出ないだけ。Basicの表示は最初から出ているため画面は止まらない（§8.7） |
| 10 | 推論 | ネイティブクラッシュ（SIGSEGV等）でプロセスごと落ちる | **JNI境界で全例外を捕捉し、C++側は例外を跨がせない**。加えて推論前に `DeviceCapability` で足切りする。それでも落ちる端末は次回起動時にAIを自動OFFへ戻す「クラッシュ後セーフモード」フラグを持つ | 万一クラッシュしても次回起動はBasicで安全に立ち上がる |
| 11 | 推論 | 同一プロセスから同時に2回呼ばれネイティブコンテキストが競合 | `Mutex` で直列化（T-GW-15）。2本目は待つか即 `Fallback(BUSY)` | 予測不能な破損が起きない |
| 12 | Structured Output | 文法制約が効かずJSON以外が出る | 第2層の `SchemaValidator` が不合格を返し、retry 1回 → `Fallback(SCHEMA_INVALID)`（§20） | AI提案が出ないだけ。Basicで予定成立は継続 |
| 13 | Structured Output | enum外の値・件数超過・矛盾（`required` かつ `skippable`） | 同上。**黙って丸めたり既定値で穴埋めしない**（不合格として扱う） | 誤った提案がDomainへ入らない |
| 14 | Structured Output | `estimated_minutes` が非現実的（0や1440） | スキーマの `1..120` で不合格。Clampによる暗黙修正はしない（S-4） | 明らかに誤った所要時間が計画へ入らない |
| 15 | プロンプト | イベントタイトルにプロンプトインジェクションが含まれる | 指示部とデータ部を区切りトークンで構造分離し、データ部は必ず引用として囲む。さらに**出力は文法制約でスキーマ外に出られない**ため、注入が成功しても構造は壊れない（二重防御） | 悪意ある予定名でもアプリの挙動が乗っ取られない |
| 16 | プライバシー | 推論経路が誤ってネットワークを叩く | §9.3 T-AIISO-6（構造）＋§10 L2（StrictMode `penaltyDeath`）＋L3（機内モード）の3層で機械検証。CIで常時実行 | カレンダー本文が端末外へ出ないことが継続的に保証される |
| 17 | Analytics | 指標に自由文（イベントタイトル等）が混入する | `AiMetrics` を許可リスト型で定義し T-AIMET-1 で検証（§60） | 送信可能な指標にPIIが混ざらない |
| 18 | `generateRecovery` 呼び出し | Phase 7では未実装なのに呼ばれる | `TODO()` で落とさず `Fallback(NOT_IMPLEMENTED_IN_PHASE7)` を返し、Analyticsへ記録 | Phase 8/9の途中配線でもクラッシュしない |
| 19 | AI設定 | 端末非対応なのにユーザーがONにできてしまう | `DeviceCapability` の判定結果でトグルを無効化し、理由文言を表示。ON状態のまま非対応端末へ復元された場合も入口で `Fallback(UNSUPPORTED_DEVICE)` | 動かない機能をONにできない／ONでも安全に落ちる |
| 20 | AI設定 | ユーザーがDL中にアプリを終了する | 部分ファイルを保持し、次回Settings表示時に「再開」を提示。バックグラウンド継続はPhase 7では行わない | 通信量が無駄にならない |
| 21 | ビルド | LiteRT-LM の推移依存（gson 2.13.2 / kotlin-reflect **2.2.21** / kotlinx-coroutines-android **1.9.0**）が、本プロジェクト（**Kotlin 2.4.10** / coroutines-test **1.11.0**）と衝突し、既存の245件規模のテストが壊れる | P7-C0で依存解決結果を `./gradlew :app:dependencies` で実測し、必要なら `resolutionStrategy.force` で統一する。**`:app:testDebugUnitTest` のベースライン件数と結果を導入前後で突き合わせ、1件でも増減したら原因を特定してから進む** | 第1弾の回帰テストがAI導入で壊れない |
| 22 | 推論 | `enableResponseFormat = false` のまま `responseFormat` を渡し `IllegalArgumentException` になる（実装ミス） | `ai/adapter/` が `ConversationConfig` を生成する箇所を1つに集約し、**`enableResponseFormat = true` を定数で固定**。E2テストで「Conversation生成時に必ずtrueである」ことを検証する | 実装ミスが本番で構造化出力を無効化しない |
| 23 | 推論 | Qwen3のthinkingモードが有効なまま `<think>` に数百トークンを浪費し、decode 一桁 tok/s の端末で実質フリーズする | `ConversationConfig.thinkingConfig` で無効化し、**E3テストで実出力トークン数が上限内であることを検証**（V-4）。加えて `maxOutputToken` を必ず設定する | 推論が現実的な時間で終わる |
| 24 | ライセンス | モデル配布元のライセンス条件を満たさないまま再配布する | `ModelCatalog` の各エントリに**ライセンス種別とNotice要否を必須フィールドとして持たせ**、Apache-2.0以外のモデルを追加する場合はビルドを通さない（Phase 7はApache-2.0のQwen3のみ）。Gemma 3系を将来追加する場合の追加義務は§5.2に明記済み | ライセンス違反配布が構造的に起きない |
| 25 | モデルロード | ロード前検証で破損・改竄を検知（DL完了後、時間経過後の劣化・手動改変等。SHA-256不一致またはサイズ不一致） | 毎回ファイルサイズを照合し、プロセス初回ロード前にSHA-256を再検証（結果はプロセス内キャッシュ）。不一致時は**当該ファイルを削除**し `Fallback(MODEL_CORRUPTED)` をAnalyticsへ記録、再DL導線を提示（Gemini G1 CRITICAL #2） | AI提案が出ないだけ。Basicで継続。再DLで復旧できる |

---

## §14. サイクル分解（P7-C0〜P7-C8）

| サイクル | 内容 | 担当（Do） | 到達ゲート |
|---|---|---|---|
| **P7-C0** **スパイク（Go/No-Go判定）** | **本Phase最大のリスクを最初に潰す使い捨て検証。** ①`litertlm-android:0.15.0` を空プロジェクトへ追加しAARを展開してABI/minSdkを実測（V-1）。②`Engine`/`Conversation` の生成・解放APIを確認（V-2）。③`Backend.CPU()` のスレッド数指定可否（V-3）。④**`ResponseFormat.json(schema)` が実機/エミュレータで実際にスキーマ準拠JSONを返すことを確認**。⑤thinking無効化の指定方法と実出力トークン数（V-4）。**⑥`maxNumTokens` を128〜256まで絞ったときのピークRAM変化を実測し、1GB級まで下がることを確認する（V-8。Gemini G1 CRITICAL #4により必須測定項目へ格上げ。§11.2のE3小コンテキスト・テストプロファイルとG4-Eの成立可否を左右するため④に準ずる重要度で扱う）**。⑦本プロジェクトへ依存を入れたときの推移依存衝突を `:app:dependencies` で実測（§13 #21）。**成果物は破棄し、知見のみを本書へ反映する（production codeを残さない）** | sonnet | **④が失敗したら次点のllama.cpp案へ切り替え、本書§5.1・§7・§8を改訂してG1を取り直す**（U-2） |
| **P7-C1** scaffold | `ai/` 配下の新規ファイルを本体 `TODO()` で新設（F86〜F96の宣言のみ）。`AiFallbackReason` enum は全値を確定させる。`AppContainer` へ `localAiGateway` を `by lazy` で追加（**`planningEngine`/`recoveryEngine` は無変更**）。`libs.versions.toml`＋`app/build.gradle.kts` へ依存追加。**ベースライン実測記録**（着手時点の `:app:testDebugUnitTest` 件数・failures・skipped）。ADR-0043（ランタイム/モデル選定・単一モジュール継続）・ADR-0044（T-AIISO-6の許可リスト方針）起票 | sonnet | 既存テスト**全件が導入前と完全一致**（§13 #21） |
| **P7-C2** Red | §12のE1/E2テストを作成し**失敗を実測**（G2）。E3/E4は作成のみ | sonnet | G2 |
| **P7-C3** Green: schema/prompt | F93・F94・F95 を実装。**E1中心で最も検証密度が高い層** | sonnet | G3（部分） |
| **P7-C4** Green: model管理 | F87〜F91 を実装（Catalog/Downloader/Verifier/Storage/DeviceCapability）。**C3と並列可** | sonnet | G3（部分） |
| **P7-C5** Green: adapter/gateway | F86・F96 を実装。C3・C4の完了後に直列 | sonnet | G3（部分） |
| **P7-C6** Green: settings | F92・F97 を実装。Settings route追加・ja/en文言追加 | sonnet | G3（部分） |
| **P7-C7** 統合 | 全体結線・§9のガード改修と新設・E3テストの実行（G4-E）。既存245件規模の回帰確認 | sonnet | **G4-JVM ＋ G4-E** |
| **P7-C8** 実機プローブ＋Refactor | §11.3 の Galaxy A実機ベンチ（Qwen3-0.6B / Qwen3-1.7B / Gemma3-1B の3者比較）。測定値で §8.6 のタイムアウト閾値と §5.3 の段境界を確定。リファクタ後に再度全テスト通過を確認 | sonnet → opus | **G4-D**。**未達のままPhase 8へ進むことを禁止** |

**並列可否**: P7-C3 と P7-C4 は独立（共有ファイルなし）。P7-C5 は両者に依存するため直列。P7-C6 は C5 の `LocalAiGateway` シグネチャ確定後に開始。

---

## §15. リスク

| ID | リスク | 深刻度 | 対応 |
|---|---|---|---|
| **R-1** | **推移依存の衝突**。LiteRT-LM が要求する kotlin-reflect **2.2.21** / kotlinx-coroutines-android **1.9.0** が、本プロジェクトの Kotlin **2.4.10** / coroutines-test **1.11.0** と競合し、**Phase 1〜11で積み上げた245件規模のテストを壊す** | **高** | P7-C0で先に実測（§13 #21）。`resolutionStrategy.force` で統一。P7-C1完了時点でベースライン件数の完全一致を必須ゲートにする |
| **R-2** | **`ResponseFormat` が公式Webドキュメントに未掲載**であり、マイナーバージョンアップで予告なく変わる／削除される | **高** | バージョンを `0.15.0` に**固定**し、`libs.versions.toml` にピン留め。自動更新しない。P7-C0で実動作を確認してから本採用。ダメなら次点のllama.cpp案（U-2） |
| **R-3** | **decode 8〜13 tok/s（フラッグシップ実測）** であり、Galaxy A ではさらに遅い。数十秒かかると機能として成立しない | **高** | ①§8.7の「Basic即時→AI後差し替え」を契約段階で固定。②`maxOutputToken` と steps 件数でトークン予算を絞る。③thinking無効化（§13 #23）。④**P7-C8の実機実測で成立しないと判明した場合、Phase 8の配線方針そのものを見直す**（AIを「予定作成直後のバックグラウンド先読み」に回す等） |
| **R-4** | **ピークRAM 約2.9GB**（Qwen3-0.6B int4 実測）。6GB機でも他アプリと競合してOOMしうる | **高** | `maxNumTokens` によるKVキャッシュ抑制（V-8。**P7-C0の必須測定項目**）。§8.6 #7 の**能動的メモリガード（事前回避が主防御。Gemini G1 CRITICAL #3）**とJavaの`catch`による二次防御（§8.6 #13）・アンロード。§5.3の段0で6GB未満を構造的に排除。**G4-Eは小コンテキスト・テストプロファイルで実施しフルピークの実測はG4-Dへ移管する（R-11）** |
| **R-5** | **Galaxy Aクラスの公開ベンチが世界に1件も存在しない**ため、本書の性能前提はすべて外挿である | **高** | §11.3の実機プローブを**G4-Dの必須要件**にし、未実測のままPhase 8へ進むことを禁止（§3） |
| **R-6** | 既存3本のAI隔離ガードが**単純文字列マッチ**のため、`ai/` の実装中にKDocへ禁止語を書いただけでRedになる | 中 | P7-C1着手前に開発者へ周知。§9.2の改修時に「KDocでも落ちる」旨をテストのKDocへ明記 |
| **R-7** | `AppContainer` が **eager初期化**のため、`localAiGateway` を素で足すとアプリ起動時にモデルロードが走り起動が数秒遅くなる | 中 | `by lazy` を必須とし、T-P7DI-2 で「生成時点で未初期化」を機械検証。さらに `LocalAiGateway` 内部でもモデルロードを初回推論まで遅延させる |
| **R-8** | Settings画面の新設で `ActionStarterNavHost` を触るため、既存 T-NAV-* / E2E が壊れる | 中 | route追加のみに限定し既存routeを変更しない。P7-C6で既存ナビゲーションテストの再実測を必須にする |
| **R-9** | モデル配布元（Hugging Face）のURL変更・レート制限・地域到達性でDLが失敗する | 中 | `ModelCatalog` にURLを集約しバージョン管理。§13 #2 のretry導線。恒久的な配布先はU-5で裁定 |
| **R-10** | Phase 8以降で `planning/LocalAIPlanningEngine` を作る際、**`planning/` 配下に置くと隔離ガードが必ずRedになる**（設計上の袋小路） | 中 | **Phase 7のうちに配置方針を決めて申し送る**（§18）。`ai/` 側に置いて `planning/` からは参照しない構成か、ガードへ限定的な許可リストを設けるか。**Phase 8の計画で最初に解く論点として明示する** |
| **R-11** | **エミュレータ（AVD RAM 4096MB）ではフルコンテキスト時のピークネイティブRAM（約2.9GB級）を安定して確保できず、E3のフル条件実推論テストが成立しない可能性がある**（Gemini G1 CRITICAL #4） | **高** | E3の標準実行構成を**小コンテキスト・テストプロファイル**（`maxNumTokens`128〜256・ピーク1GB級。§11.2・§12.8）に固定する。V-8を**P7-C0の必須測定項目へ格上げ**し、プロファイルでも成立しない場合はAVDの`hw.ramSize`を6144MBへ引き上げる（代替手段として記録・§11.2）。フルピークの実測責任はP7-C8実機プローブ（P7-P2）へ移す |

---

## §16. Fable 5 確認事項（U-1〜U-14・**裁定済み**）

**Fable 5がPass1/Pass2レビューにより本表「裁定」列のとおり確定した（2026-08-10）。U-1〜U-10・U-12〜U-14は推奨案どおり承認、U-11のみ推奨から変更（Gemini G1 CRITICAL #5反映）。全14項目裁定済みのためG1通過条件を満たす。**

| ID | 確認事項 | 推奨案 | 裁定（Fable 5・2026-08-10） |
|---|---|---|---|
| **U-1** | F番号の採番開始位置 | 実測最大が F84（Phase 11）のため **F85から**採番する | **承認（推奨案どおり）**。F85から採番する |
| **U-2** | **ランタイム選定の確定**: LiteRT-LM 0.15.0 を採用し、P7-C0で `ResponseFormat` が実機で機能しなかった場合のみ llama.cpp（次点）へ切り替える、という段取りでよいか | 承認を推奨。**P7-C0をGo/No-Go判定として明示的に置く** | **承認**。**P7-C0をGo/No-Go判定として明示的に置くことを確定する。**④（`ResponseFormat`実機動作確認）が不成立の場合のみ次点llama.cpp案へ切替え、本書§5.1・§7・§8を改訂してG1を取り直す |
| **U-3** | **§95.3の6GB基準を緩和せずそのまま採用する**（S-1・§5.3）。結果として Galaxy A15/A25 の4GB構成は Local AI 対象外になる | 承認を推奨（実測ピークRAM 2.9GB とGoogleの6GB宣言が根拠）。ただし V-8 の実測次第で再検討の余地を残す | **承認**。**6GB基準を維持する。ただしV-8（`maxNumTokens`によるピークRAM実測）の結果次第で境界を再検討する条項を付す**（再検討はP7-C0実測後、本書改訂として扱う。無条件の緩和ではない） |
| **U-4** | **モデルの最終選択**を P7-C8 の実機プローブ（Qwen3-0.6B / Qwen3-1.7B / Gemma3-1B の3者比較）結果でユーザーに報告して確定する建付けでよいか | 承認を推奨。**日本語品質（MIFEvalJa）と decode速度・ピークRAM のトレードオフをユーザーに数値で示して選んでもらう** | **承認**。**P7-C8実測後、日本語品質（MIFEvalJa）・decode速度・ピークRAMの数値をユーザーへ提示したうえで確定する**建付けで確定 |
| **U-5** | **モデル配布方式**: Hugging Face `litert-community` の直参照を既定とし、Play Asset Delivery / Play Feature Delivery は採らない | 承認を推奨。恒久運用先（自前CDN移行の要否）はPhase 8以降で再検討 | **承認（推奨案どおり）**。恒久運用先の再検討はPhase 8以降 |
| **U-6** | **モデルのSHA-256をどう入手・固定するか**。HF側のファイルハッシュをそのまま信頼するか、開発者が一度DLして自分で計算した値を `ModelCatalog` に焼くか | **後者を推奨**（開発者が検証した1個体を正とする）。前者はHF APIへの信頼を増やす | **承認**。**開発者が一度DLして自ら計算したSHA-256を`ModelCatalog`へ焼き込む方式で確定**（HF側ハッシュの無条件信頼はしない） |
| **U-7** | `abiFilters` を明示指定せずAAR同梱の2ABIをそのまま使うか、releaseで `arm64-v8a` に絞るか | **Phase 7は明示指定しない**を推奨（G4-Eの成立を優先）。release絞り込みは配布サイズ実測後にPhase 8で判断 | **承認（推奨案どおり）**。Phase 7は明示指定しない |
| **U-8** | `generateRecovery` を Phase 7 で `Fallback(NOT_IMPLEMENTED_IN_PHASE7)` として実装する（`TODO()` で落とさない） | 承認を推奨（サイレント障害・クラッシュの回避） | **承認（推奨案どおり）** |
| **U-9** | **T-AIISO-6 が既存ガードの設計思想（許可リストなし）を破って許可リストを1件持つ**ことの承認 | 承認を推奨。許可対象を単一ファイル名の完全一致に限定しADR-0044に記録 | **承認**。**ADR-0044への記録を条件として**許可リスト1件を認める。許可対象は単一ファイル名の完全一致に限定し肥大を禁止 |
| **U-10** | 実機ベンチの起動方式: `@Ignore` を外して `connectedAndroidTest` で回すか、Settings画面にデバッグボタンを置くか | **前者を推奨**（既存 `AlarmExactAlarmProbeTest` の先例と一致し、production codeにデバッグ導線を残さない） | **承認（推奨案どおり）** |
| **U-11** | `SchemaValidator` のJSONパーサ。`org.json`（→テストがE2になる）／`testImplementation` に `org.json:json` を追加（→E1可）／自前パーサ | **`org.json` ＋ E2 で開始**を推奨（新規依存を増やさない。既存 `ExecutionScheduleStore` と同じ流儀） | **推奨から変更（Gemini G1 CRITICAL #5）**。**本番はorg.json（Android同梱）を使いつつ、`testImplementation("org.json:json:<最新安定版>")`（pure Java実装）を追加してE1（純JVM）で実行する**。根拠: Android SDK同梱の`org.json`は純JVM実行時にはスタブであり呼び出すと`Stub!`例外を送出するため、Robolectric（E2）なしのE1テストでは実クラスとして機能しない。テストスコープにpure Java実装の`org.json:json`を追加することでこれを回避し、本番コードはAndroid同梱のものを使い続けたままテストのみE1（純JVM）で実行可能にする |
| **U-12** | 既存3本の隔離ガード改修（禁止語追加＋再帰化）の承認（TEAMS §2の既存テスト変更） | 承認を推奨。**強化方向のみでassertion弱体化なし** | **承認（推奨案どおり）** |
| **U-13** | Settings画面の範囲。AIトグル＋モデル状態＋DL/削除＋容量表示のみに限定してよいか（S-6） | 承認を推奨。フル機能Settingsは対象外 | **承認（推奨案どおり）** |
| **U-14** | **Phase 7の完成条件**を §0 のとおり「`ai/` 単体で完結して1回動き、5系統の失敗でBasicへ落ちる」とし、UI配線をPhase 8へ送る | 承認を推奨（§71の完成条件「オフライン状態でテストPrompt→JSON取得」と一致） | **承認（推奨案どおり）** |

---

## §17. 未確認事項（V-1〜V-8）

**いずれも P7-C0 で実測確定する。確定するまで本書の該当箇所を最終と見なさない。**

| ID | 内容 | 影響範囲 |
|---|---|---|
| **V-1** | AAR 0.15.0 の ABI構成とminSdkの自プロジェクトでの再確認（調査エージェントはarm64-v8a＋x86_64・minSdk 24と実測したが、android-planner自身は未再現） | §8.2・G4-Eの成立可否 |
| **V-2** | `Engine` / `Conversation` の**解放API**（`close()` 相当）の有無と正しいライフサイクル | §13 #8のアンロード設計・§8.6 #7 |
| **V-3** | `Backend.CPU()` がスレッド数等の引数を取るか。ミッドレンジでのbig/LITTLEコア割り当ての制御可否 | §11の測定条件・性能 |
| **V-4** | **Qwen3のthinking無効化の具体的な指定方法**と、無効化時の実出力トークン数 | §13 #23・§8.4のトークン予算・R-3 |
| **V-5** | Qwen3の「119言語」およびGemmaの「140言語」主張の**公式一次ソースでの日本語の明示**（Qwen3はブログの言語テーブルで確認できたとの調査報告があるが、android-planner自身は未確認） | §5.2 ①の根拠強度 |
| **V-6** | **Gemma 4 が Apache-2.0 へ変更された事実**の一次確認（調査報告は `opensource.googleblog.com` を出典としているが、android-planner自身は未取得）。Gemma 3が遡及的にApache化されていないことも含む | §5.2 ③・Gemma切替時の義務 |
| **V-7** | `litert-community` の Qwen3-1.7B のピークRAMとAndroid実測tok/s（**モデルカードにベンチ表がなく未公開**） | §5.3 段2の成立可否 |
| **V-8** | **`EngineConfig.maxNumTokens` を変えたときのピークRAMの変化**。2.9GBをどこまで下げられるか。**128〜256トークンまで絞った小コンテキスト・テストプロファイルでピークが1GB級まで下がるかを含む**（Gemini G1 CRITICAL #4により**P7-C0の必須測定項目へ格上げ**） | §5.3の段境界・S-1・R-4・**R-11・§11.2・§12.8（E3小コンテキストプロファイルの成立可否）** |

**加えて、本書全体に関わる根本的な未確認事項**: **Galaxy Aクラス（Exynos 1280/1380・Dimensity 1080/6100+級）でのオンデバイスLLM実測値は、公式・非公式を問わず1件も存在しない。** 本書の性能に関する記述はすべてフラッグシップ機または準ミッドレンジ機（TECNO LJ9 / Dimensity 8350）からの外挿であり、**§11.3の実機プローブが完了するまで、Phase 7が主対象デバイスで成立するかどうかは確定していない**。これは計画の欠陥ではなく、この領域の情報が世の中に存在しないことによる。**だからこそ P7-C0 と P7-C8 を必須ゲートに置いている。**

---

## §18. 申し送り（Phase 8以降へ）

1. **R-10（最重要）**: `LocalAIPlanningEngine` の**配置場所**を Phase 8 の最初に解くこと。`planning/` 配下に置くと既存の隔離ガードが必ずRedになる。「`ai/` 側に置き `planning/` は参照しない」か「ガードに限定的な許可リストを設ける」かの二択で、**前者を推奨**する（Basic経路の純粋性を保てる）。
2. **UX配線の前提**: §8.7の「Basic即時→AI後差し替え」を Phase 8 のUI設計の出発点にすること。decode 一桁 tok/s の可能性がある以上、**AI結果を待ってから画面を出す設計は採れない**。
3. **性能が成立しなかった場合の退避策**: P7-C8の実測で実用にならないと判明した場合、Phase 8 では「Plan確定直後にバックグラウンドで先読み生成し、次回表示時に使う」等の**時間軸をずらす設計**を検討すること。
4. **GPU バックエンド**: Phase 7 はCPU固定としたが、`litert-community` の実測では Qwen3-0.6B が GPU で decode 33.5 tok/s（TECNO LJ9・CPUの約4倍）と大きく改善する。**Galaxy A の Mali GPU で安定動作するかを Phase 8 の最適化課題として検証すること**（ピークRAMも GPU の方が小さい実測がある: 1,832MB vs 2,890MB）。
5. **`generateRecovery`（Phase 9）**: 基盤は Phase 7 で完成しているため、`RecoveryJsonSchema` と `RecoveryPromptBuilder` を追加し `ai/adapter/` を1メソッド実装するだけでよい設計にしてある。§15「時刻演算をLLMに渡さない」に従い、`AIRecoveryOptionResponse` に `estimatedArrival` を**追加しない**こと。
6. **Analytics（Phase 12）**: `AiMetrics` は §57 の指標に1:1対応させてある。Basic vs AI の比較実験（§76）ではこのメトリクスをそのまま使えるが、**§60の許可リストを超えるフィールドを足さないこと**（T-AIMET-1が回帰ロックしている）。
7. **モデル差し替え**: `ai/adapter/` 以外がランタイムAPIを参照しない構造（T-AIISO-9）にしてあるため、§17「モデルは技術検証で交換可能」の要求は `ModelCatalog` へのエントリ追加と `ai/adapter/` の差し替えで満たせる。**この構造を壊さないこと。**
