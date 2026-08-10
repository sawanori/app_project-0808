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

**契約確定（Fable 5裁定1・2、2026-08-10、ADR-0045・ADR-0046。P7契約確定サイクル）**: 下記は
本節のドラフト時点（G1通過時）の案であり、その後P7-C2完了記録の差し戻し事項1・2・3を経て
Fable 5が確定させた最終契約は**品質ハーネス（`docs/plans/phase7-quality-harness.md`）の
Semantic Contextualization設計原則を採用**し、`estimated_minutes`／`priority`／`skippable`／
`type`を**LLM出力から完全に除去**した。確定後のスキーマ・詳細な裁定内容は
`app/src/main/java/com/actionstarter/ai/schema/PlanJsonSchema.kt`のKDoc、および
DECISIONS.md ADR-0045〜ADR-0047を正とする。以下は当時の設計意図の記録として残す
（本文中の`estimated_minutes`/`priority`/`skippable`/`type`を含むスキーマ案は**もはや現行
契約ではない**）。

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

**確定後の契約（ADR-0045・ADR-0046。現行）**:

```text
event_type   : string, enum固定（PlanEventType、8値: business_meeting/medical/social/meal/
               travel/errand/personal/other）
steps        : array, minItems 1, maxItems 8
  action_type : string, enum固定（PlanActionType、7値: finish_current_task/prepare_items/
                get_ready/gather_belongings/leave/commute/arrive）
  display_text: string, minLength 1, maxLength 60（enum非制約の自由文。Semantic
                Contextualizationの唯一の自由度）
additionalProperties: false（全階層）
```
`type`／`estimated_minutes`／`priority`／`skippable`はLLM出力から除去し、`action_type`から
Kotlin側（Phase 8 `LocalAIPlanningEngine`）が決定的にマップする（§18申し送り参照）。

**第1層（decode時）**: 上記スキーマ文字列を `ResponseFormat.json(...)` として `sendMessage` に渡し、constrained decoding で**構文とenumを生成時点で強制**する。**日本語 MIFEvalJa 0.425 の0.6B級モデルで §20 を成立させる中核**であり、これなしに小型モデルでスキーマ準拠JSONを安定生成することは期待できない。
**第2層（Kotlin側）**: `SchemaValidator`（①形式検証）が**ランタイムの制約を信用せず独立に**再検証し（件数・enum・長さ・`additionalProperties`）、新設`ContentSanityChecker`（②内容sanity、ADR-0047）が捏造検出・titleコピー検出・locale整合・重複`action_type`検出を担う。**「constrained decodingがあるから検証不要」としないことを設計原則として固定する**（信頼境界。§20「Schema validation必須」）。

**トークン予算（decode 一桁 tok/s 前提の必須制約）**: 上記スキーマで steps 8件を出力すると概ね数百トークンとなり、decode 8〜13 tok/s では数十秒に達する。`ConversationConfig.maxOutputToken` に上限を設け、**実運用の既定 steps 件数は 5 程度に抑える**方向で P7-C0 の実測（V-4: thinking無効時の実出力トークン数）を踏まえて確定する。フィールド数削減（ADR-0045）により出力トークン数はさらに下がる見込み。

### 8.5 `LocalAiGateway` の契約（S-3の解決）

```text
sealed interface AiResult<out T> {
  data class Success<T>(val value: T, val metrics: AiMetrics) : AiResult<T>
  data class Fallback(val reason: AiFallbackReason, val detail: String?) : AiResult<Nothing>
}
```

- **例外を外へ出さない**。`Throwable` は全て `Fallback` へ写像する（ただし**必ず** `reason` と `detail` を埋め、Analyticsへ記録する。§95.6「サイレントに握り潰さない」）。
- retryは§20どおり **1回のみ**。~~S-2の定義: 同一プロンプト・greedy・seed固定での再生成~~
  **是正済み（品質ハーネス§0/§4/§6、Fable 5裁定・ADR-0049、2026-08-10）**: 決定的（greedy）な
  1回目が失敗した場合、同一条件での再生成は同一失敗を再現するため無効。retryは**新規
  single-turnセッション（1回目の失敗出力を含む会話履歴を破棄）＋微小摂動（temperature
  0.1〜0.2, topK=5程度）＋静的な簡潔化制約文の追加**（マルチターン自己修正ではない）へ
  是正した。retry発生自体をメトリクスに残す（`AiMetrics.retried`）。
- `AiMetrics` は §57 に対応: `modelLoadMs` / `firstTokenMs` / `totalMs` / `outputTokens` / `tokensPerSecond` / `peakNativeHeapBytes` / `retried` / `schemaValid` / **`sanityPassed`**（品質ハーネスUQ-5・Fable 5裁定・ADR-0049で追加、②内容sanity検証の通過可否）。**カレンダー本文・住所・座標は一切含めない（§60）。**

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

**#5・#12が参照するSHA-256の確定値（P7-C0実測・U-6・2026-08-10）**: `litert-community/Qwen3-0.6B`の`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`について、開発者（domain-implementer）が`build/models/`へダウンロードした個体を自ら`sha256sum`で計算した値を`ModelCatalog`（F87）へ焼き込む正の値とする（U-6の方針どおりHF側ハッシュの無条件信頼はしない）。
- ファイルサイズ: **344,437,808 バイト**（328.5MiB。§0記載の「約328MB」と整合）
- SHA-256: **`e3e290109da4388d65a17510a0c66af91c8039f52d2c465868dbc43c09a776cf`**
- 参考: HFレスポンスヘッダ`x-linked-etag`の値も同一であり配信経路での破損は確認されなかったが、この一致は補助的な傍証にとどめ、上記の自己計算値を正とする。

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

### 14.1 P7-C0実測結果（2026-08-10確定・domain-implementer・**Go/No-Go判定材料＝GO方向**）

**結論**: ④`ResponseFormat.json(schema)`は AVD `actionstarter_test`（x86_64/API35）上で**スキーマ完全準拠のJSONを2回連続実行とも生成**した。日本語プロンプト「会議の準備について、短い提案を1件だけJSON形式で出力してください。個人情報や実在の予定は使用しないでください。」に対し、`{"event_type":"social","steps":[{"action_type":"prepare_item","display_text":"会議の準備","type":"preparation","estimated_minutes":15,"priority":"important","skippable":false}]}`（計画書§8.4のPlanningスキーマ簡易版・steps 1件固定）が生成され、enum・文字数・範囲制約のすべてを満たした。**判定自体はFable 5に委ねるが、④が実機で機能したことを示す一次証拠として報告する。**

**①依存導入・衝突確認**: `com.google.ai.edge.litertlm:litertlm-android:0.15.0`を`app/build.gradle.kts`の`implementation`へ追加。`:app:dependencies --configuration debugRuntimeClasspath`実測: `com.google.code.gson:gson:2.13.2`・`org.jetbrains.kotlin:kotlin-reflect:2.2.21`・`org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`のいずれも**衝突なし**（gson/kotlin-reflectは本プロジェクトに他バージョンが存在せず新規解決、coroutines-androidは既存の`play-services-location`経由の推移依存が既に1.9.0で一致）。`resolutionStrategy.force`は不要だった（§13 #21・R-1の想定より軽微）。`:app:assembleDebug`成功（`liblitertlm_jni.so`は`stripDebugDebugSymbols`でstrip対象外のままAPKへ同梱される旨のログ有り、機能に影響なし）。既存回帰: `:app:testDebugUnitTest --rerun`＝**417件・failures 0・errors 0・skipped 1**（導入前ベースラインと完全一致、JUnit XML集計で確認）。

**モデル**: Hugging Face `litert-community/Qwen3-0.6B`の`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`を`build/models/`へ取得。**サイズ344,437,808B（328.5MiB、計画書記載の「約328MB」と整合）。SHA-256 = `e3e290109da4388d65a17510a0c66af91c8039f52d2c465868dbc43c09a776cf`**（ダウンロード直後に自らsha256sumで計算した値。HFの`x-linked-etag`ヘッダの値と一致したが、U-6の方針どおり開発者計算値を正としてここに焼き込む）。ライセンスApache-2.0（HF API `cardData.license`実測確認）。

**測定表**（ctx128＝小コンテキスト・テストプロファイル下限、ctx256＝上限。いずれも`maxOutputToken=100`・`Backend.CPU(threadCount=4)`・同一プロンプト・2回目実行の値。**プロンプト/出力トークン数が小さいため厳密なベンチマークではない**）:

| 指標 | ctx128 | ctx256 | 備考 |
|---|---|---|---|
| モデルロード時間（`Engine.initialize()`実測） | 5,046 ms | 803 ms | 2回目(ctx256)が速いのはプロセス内で2件目のロードでありOS側のページキャッシュ等が温まっているためと推定。単独プロセスでの絶対値としては参考程度 |
| `BenchmarkInfo.initTimeInSecond`（native計測） | 14.53 s | 4.24 s | **要注意**: 自前のwall-clockロード時間（5,046ms/803ms）や試験全体の経過時間（後述）と桁が一致しない。ネイティブ側が複数スレッド（`threadCount=4`）のCPU時間を合算している可能性など複数の仮説があるが未検証。数値の意味を断定せず生値として報告する |
| TTFT（`BenchmarkInfo.timeToFirstTokenInSecond`） | 1.017 s | 0.810 s | 初めてnative benchmarkから直接取得（後述の追加発見） |
| decode速度（`BenchmarkInfo.lastDecodeTokensPerSecond`） | 32.60 tok/s | 42.72 tok/s | **x86_64エミュレータは実機と無関係に高速**（後述） |
| prefill速度（`BenchmarkInfo.lastPrefillTokensPerSecond`） | 46.64 tok/s | 58.49 tok/s | 同上 |
| 生成トークン数（prefill/decode） | 46 / 56 | 46 / 56 | 2回とも同一（決定的生成に近い挙動） |
| ピークRAM（`ActivityManager.getProcessMemoryInfo().totalPss`） | 774,245 KB（約756MB） | 724,384 KB（約708MB） | **V-8参照。128→256で下がるのは実行順序効果の疑いが濃厚**、詳細はV-8行 |
| ピークネイティブヒープ（`Debug.getNativeHeapAllocatedSize()`） | 347,544,528 B | 273,093,120 B | 同上の傾向 |
| Native Heap summary stat（`Debug.MemoryInfo.getMemoryStat("summary.native-heap")`） | 285,172 KB | 238,312 KB | dumpsys meminfoのNative Heap行相当値。アプリ内からの`dumpsys meminfo`直接exec自体は出力を得たが期待した行フォーマットに一致せず「not found」（ベストエフォート扱いのため未達でも許容、本APIで代替取得済み） |
| スキーマ適合 | ○（`schemaValid=true`） | ○（`schemaValid=true`） | 2回とも完全一致 |
| `<think>`混入 | なし | なし | V-4参照 |

**測定条件についての重要な留保**: **x86_64エミュレータはARM実機のバイナリ変換ではなくホストCPU上でネイティブ実行**（AAR自体がx86_64向け`.so`を同梱）であるため、decode 32〜43 tok/sという値は計画書§0引用のフラッグシップ実機値（Qwen3-0.6B @ Vivo X300 Pro: decode 9 tok/s）を3〜5倍上回っている。**これは「エミュレータが速い」だけであり、Galaxy A実機の性能を一切示唆しない**（計画書§11.2の既存の留保を実測値で追認)。tok/s・ロード時間の絶対値はP7-C8実機プローブ（§11.3）でのみ確定させる方針を変更しない。

**新規発見（一次ソース未記載）**: `Conversation.getBenchmarkInfo()`は既定では`LiteRtLmJniException`（"Benchmark is not enabled. Please make sure the BenchmarkParams is set in the EngineSettings."）を送出する。`ExperimentalFlags.enableBenchmark = true`（`@OptIn(ExperimentalApi::class)`が必要）を事前に設定することで有効化できることを実測で確認した。§8.5の`AiMetrics`実装（`modelLoadMs`/`firstTokenMs`/`tokensPerSecond`）はこの`BenchmarkInfo`から直接取得できる可能性が高く、P7-C5（adapter/gateway実装）の設計材料として申し送る。

**V-1〜V-4・V-8の確定値は本節ではなく§17表の追加列「P7-C0実測確定値」に記載する（詳細はそちらを参照）。** V-5〜V-7はP7-C0のスコープ外（未着手）。

**証拠ファイル**: `build/agent-logs/p7c0-deps.log`（依存解決＋assembleDebug）・`p7c0-regression.log`（417/0/1）・`p7c0-download.log`（DL＋SHA-256）・`p7c0-probe-result.xml`（JUnit結果、2回目実行分。1件・failures 0・errors 0・skipped 0・time 17.56s）・`p7c0-logcat.log`（2回目実行のP7C0_PROBEタグ全量、native BenchmarkInfo値を含む）・`p7c0-probe-gradle.log`/`p7c0-probe-gradle-run2.log`（gradle実行ログ、1回目/2回目）。プローブ自体は`app/src/androidTest/java/com/actionstarter/probe/LiteRtLmProbeTest.kt`に実装し、実測完了後に`@Ignore`を付与済み（`AlarmExactAlarmProbeTest`と同じ運用）。

### 14.2 P7-C1完了記録（2026-08-10確定・domain-implementer）

**結論**: F86〜F96の宣言scaffold（本体`TODO()`のみ）を`ai/`配下12ファイルへ新設し、`AppContainer`へ`localAiGateway`を`by lazy`で1本追加、`libs.versions.toml`／`app/build.gradle.kts`へF85依存をバージョンカタログ化、ADR-0043／ADR-0044を`DECISIONS.md`へ起票した。`:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`はいずれも成功（`build/agent-logs/p7c1-compile.log`）、既存回帰は`:app:testDebugUnitTest --rerun`でtests=417・failures=0・errors=0・skipped=1と、P7-C0ベースライン（`build/agent-logs/p7c0-regression.log`実測値と本サイクルで再クロスチェック済み）に完全一致した（`build/agent-logs/p7c1-regression.log`）。

**着手前のai/パッケージ現状把握**: `ai/LocalLanguageModel.kt`・`ai/AIPlanResponse.kt`・`ai/AIRecoveryResponse.kt`の3ファイルが既存（Phase 1契約scaffold）で、いずれも無変更のまま維持した。`ModelManager`／`PromptBuilder`／`SchemaValidator`／`ModelAdapters`（`ARCHITECTURE.md`§1予定表記）は実測どおり本サイクル開始時点で0ファイルだった。

**新設12ファイル（すべて本体`TODO()`。実装ロジックは一切書いていない）**:

| ファイル | 対応F番号 | 契約 |
|---|---|---|
| `ai/AiFallbackReason.kt` | — | enum 13値を確定（§8.6発動条件表・T-GW-*根拠を1:1でKDoc化。`CANCELLED`／DL系2件／`BUSY`は意図的に除外しKDocで理由を明記） |
| `ai/AiPreferences.kt` | F92 | `aiEnabled`（既定`false`）／`selectedModelId`のSharedPreferences永続化契約 |
| `ai/LocalAiGateway.kt` | F96 | `LocalAiGateway`クラス＋`AiResult`（`Success`/`Fallback`）＋`AiMetrics`を同居 |
| `ai/adapter/LiteRtLmLocalLanguageModel.kt` | F86 | `LocalLanguageModel`の初実装。`com.google.ai.edge.litertlm`をimportしてよい唯一のファイル |
| `ai/model/ModelCatalog.kt` | F87 | `ModelCatalogEntry`／`ModelLicense`＋既定エントリ`QWEN3_0_6B_INT4_BLOCK32`（P7-C0実測のSHA-256・サイズを焼き込み済み） |
| `ai/model/ModelDownloader.kt` | F88 | `ModelDownloadResult`／`ModelDownloadFailureReason`。T-AIISO-6が唯一ネットワークAPIを許すファイル |
| `ai/model/ModelVerifier.kt` | F89 | `ModelVerificationResult`／`ModelVerificationFailureReason` |
| `ai/model/ModelStorage.kt` | F90 | `noBackupFilesDir/models/`配置・容量ガード・原子的コミット・削除の6メソッド契約 |
| `ai/model/DeviceCapability.kt` | F91 | `DeviceTier`＋静的判定（`classify`/`isAbiSupported`）と動的判定（`hasAvailableMemory`）を統合 |
| `ai/prompt/PlanPromptBuilder.kt` | F93 | `build(context: PlanningContext): String` |
| `ai/schema/PlanJsonSchema.kt` | F94 | `TEXT`プロパティ（`get() = TODO()`。スキーマ文字列自体は§21のenum語彙未確認のためP7-C3へ委譲） |
| `ai/schema/SchemaValidator.kt` | F95 | `validate(rawJson: String): SchemaValidationResult`（`Valid`/`Invalid`） |

**計画書§8契約との対応**: §8.1のパッケージ構造・依存方向規律（`ai/adapter/`のみlitertlm直接import可）、§8.4の2層検証原則、§8.5の`AiResult`/`AiMetrics`契約、§8.6の発動条件表はすべて命名・KDoc上でトレース可能な形で反映した。§8.2（単一`:app`モジュール継続）はADR-0043で正式記録した。

**AppContainer変更（本指示との矛盾を計画書優先で解消・報告）**: 本タスク冒頭の制約列は「触らない: AppContainer.kt（AI追加時のlazy化はC5統合ウィンドウ）」としていたが、計画書§14 P7-C1行は「`AppContainer`へ`localAiGateway`を`by lazy`で追加（`planningEngine`/`recoveryEngine`は無変更）」と明記しており、これはT-P7DI-2（§12.6「`AppContainer`生成時点で`localAiGateway`がまだ初期化されていない」）がP7-C2で書けるための前提でもある。両者は直接矛盾するため、タスク冒頭の指示「計画書と本指示が矛盾する場合は計画書§14を優先し矛盾を報告」に従い**計画書側を採用**し、`AppContainer.kt`へ`localAiGateway: LocalAiGateway by lazy { ... }`を1プロパティのみ追加した。`planningEngine`／`recoveryEngine`の右辺・既存の他プロパティは無変更。`AppContainerTest.kt`は触っていない（同ファイルは`calendarService`/`planningEngine`/`recoveryEngine`とmockファイル非存在のみを検証しており、`localAiGateway`には触れないため影響なし）。

**build.gradle.kts／libs.versions.toml（F85正式化）**: P7-C0で`app/build.gradle.kts`に直書きしていた`implementation("com.google.ai.edge.litertlm:litertlm-android:0.15.0")`を、`gradle/libs.versions.toml`の`litertlmAndroid = "0.15.0"`＋`google-ai-edge-litertlm-android`ライブラリエントリへ正式化し、`implementation(libs.google.ai.edge.litertlm.android)`へ差し替えた（§7.2フットプリント）。`testImplementation("org.json:json:...")`（U-11）は本サイクルでは追加していない——P7-C1はテストを書かないため必須ではなく、`SchemaValidatorTest`に着手するP7-C2で追加することを申し送る。

**ADR起票**: `DECISIONS.md`へADR-0043（ランタイム=LiteRT-LM 0.15.0・既定モデル=Qwen3-0.6B INT4・単一`:app`モジュール継続）・ADR-0044（T-AIISO-6の許可リストは`ai/model/ModelDownloader.kt`1ファイルの完全一致に限定し肥大を禁止）を起票した。

**計画書との差異・スキャフォールド時点の設計判断（P7-C2への申し送り）**:
1. **Analytics記録用コラボレータは未配線**。T-GW-14（全Fallback経路でAnalytics記録が1回呼ばれる）を満たす注入口が必要だが、本プロジェクトにAnalytics基盤が存在しない（`AnalyticsStore`はPhase 10、Analytics実装はPhase 12）。`LocalAiGateway`のコンストラクタに意図的に含めていない。P7-C2でT-GW-14を書く際に収集用コラボレータ（関数型で足りる見込み）を追加すること。
2. **`AvailableMemoryProvider`のような独立インタフェースは新設せず、`DeviceCapability`（F91）へ`hasAvailableMemory(requiredBytes: Long): Boolean`として統合した**。計画書§12.5 T-GW-5が言う「fakeのメモリ情報プロバイダ」はこのメソッドのfake化で満たす想定。§7.1フットプリントに独立ファイルの記載がないための判断。
3. **`SchemaValidator`の戻り値型が未解決**。T-SCH-1は検証成功時に`AIPlanResponse`へ写像されると明記する一方、T-SCH-2は`action_type`/`type`/`priority`のDomain enum変換を要求しており、`AIPlanResponse`の既存設計（意図的に生Stringを保持）との関係が計画書から一意に確定できなかった。本スキャフォールドは`SchemaValidationResult.Valid(response: AIPlanResponse)`を仮置きし、KDocで論点を明記した。P7-C2でRedテスト（T-SCH-1〜22）を書く際に確定させること。
4. **`PlanJsonSchema.TEXT`のスキーマ文字列本体は未記入**（`get() = TODO()`）。§8.4の構造（フィールド名・件数制約・`additionalProperties:false`）は確定しているが、`event_type`／`action_type`の確定enum語彙は正仕様書§21の確認を要し、P7-C0の`LiteRtLmProbeTest`が使った語彙も暫定値と明記済みのため、本サイクルでは断定しなかった。P7-C3で§21確認のうえ確定させること。
5. **`LiteRtLmLocalLanguageModel`は`AutoCloseable`を実装しない**。`LocalLanguageModel`契約（§16、凍結）にライフサイクルメソッドがないため、Engine/Conversationの生成・close・アンロードはすべてP7-C5でクラス内部状態として実装する設計とした。

**モデル実DL等の重い作業について**: 本サイクルはコンパイルゲートに必要な範囲（宣言・型・定数）に留め、モデルの実行時ダウンロード実装（`ModelDownloader.download`本体）はC4（Green: model管理）へ送った。計画書§14 P7-C1行の記述どおりであり、逸脱ではない。

**証拠ファイル**: `build/agent-logs/p7c1-compile.log`（`:app:compileDebugKotlin`/`:app:compileDebugUnitTestKotlin`成功）・`p7c1-regression.log`（`:app:testDebugUnitTest --rerun`、JUnit XML集計tests=417/failures=0/errors=0/skipped=1、P7-C0ベースラインとの一致を明記）。

### 14.3 P7-C2完了記録（2026-08-10確定・test-writer）

**結論**: §12のE1/E2テストのうち本サイクルの対象範囲（`SchemaValidator`・`PlanPromptBuilder`・`AiFallbackReason`・`AiMetrics`・`ModelCatalog`・`ModelVerifier`・`DeviceCapability`・`LocalAiGateway`の5系統フォールバック）で**66件のテストを新規作成**した。個別実行・全件実行とも**58件が意図どおりRed**（`NotImplementedError`57件＋型不一致による`AssertionError`1件）、**8件は契約が確定済みのためborn-greenとして意図的にGreen**。`:app:testDebugUnitTest --rerun`（全体）は**tests=483／failures=58／errors=0／skipped=1**で、P7-C1ベースライン（tests=417/failures=0）との差分は**新規66件の追加のみ**（483−417＝66）と完全一致し、**既存417件の回帰は0件**。

#### 確定した論点

1. **`SchemaValidator`の戻り値型（T-SCH-2の解釈）**: `type`・`priority`は既存Domain enum（`ExecutionStepType`／`StepPriority`）と§8.4のenum語彙が完全一致するため対応が確定する。`action_type`は対応するDomain enumが存在しないと判断した——根拠は①`AIPlanStepResponse`のKDoc「`type`／`priority`の意味論はDomain enumと対応するが」という記述が`action_type`を明示的に除外していること、②`RecoveryOption.semanticAction: String`・`ExecutionStep.semanticId: String`という既存の「言語非依存の内部ID＝String」設計前例、③§12テーブルに`T-SCH-3〜5`と並ぶ「`action_type`がenum外→不合格」ケースが存在しないこと。**ただし**現行`SchemaValidationResult.Valid(response: AIPlanResponse)`は`AIPlanResponse`自体が意図的に生Stringのみを保持するため、「変換されたDomain enumインスタンス」を型として公開する経路がない。本サイクルのテスト（T-SCH-2）は「確認済みのenum値文字列が検証を通過する」ところまでを検証し、**変換結果を公開する`SchemaValidationResult`の最終形はFable 5確認事項として残す**（P7-C3で確定させること）。
2. **`PlanJsonSchema.TEXT`のenum語彙（§21）**: 正仕様書§21を確認したが、`event_type`／`action_type`の**確定enum語彙（値の列挙）は§21に存在しない**（§21が示すのは`eventType`/`actionType`等の命名規約が英語IDであるべきという方針と、`check_equipment`という単発の例のみ）。§20の公式JSON例も`event_type: "business_meeting"`という1値のみを示す。したがって`event_type`・`action_type`の**閉じた語彙は本サイクルでは確定できない**。テストは①`type`（transition/preparation/departure/travel）・`priority`（required/important/optional）という**既存Domain enumで確定済みの語彙**についてのみenum網羅検証（T-SCH-2・T-RF-1）を行い、②`event_type`のenum外検証（T-SCH-3）は仕様§20の実例値`"business_meeting"`を正例、明らかに語彙外と分かる文字列を負例として使うことで、**閉じた語彙を確定させずに**検証する設計とした。**`PlanJsonSchema.TEXT`のevent_type/action_type enum語彙確定はP7-C3着手前にFable 5確認が必要**。

#### ケースID別作成状況

| 対象 | 作成ID | 件数 | 区分 | 備考 |
|---|---|---|---|---|
| `SchemaValidator`（F95） | T-SCH-1〜22（全件） | 22 | E1 | 全件Red |
| `PlanJsonSchema`（F94） | T-RF-1〜4（全件） | 4 | E1 | 全件Red |
| `PlanPromptBuilder`（F93） | T-PRM-1〜7（全件） | 7 | E1 | 全件Red。T-PRM-2/6は区切りトークン記法等P7-C3未確定の詳細を複数パターン許容で検証（弱体化ではなく自己解釈での断定を避けるため） |
| `AiFallbackReason` | 独自2件（13値の完全一致・意図的除外3種） | 2 | E1 | born-green（P7-C1で確定済み） |
| `AiMetrics`（T-AIMET-1） | T-AIMET-1 | 2 | E1 | born-green。§60の字面一致ではなく実質的意図（自由文/PII不保持）で検証（本文参照） |
| `ModelCatalog`（F87） | 独自4件（P7-C0実測値・findById・ALL） | 4 | E1 | born-green（P7-C0実測値の回帰ロック） |
| `ModelVerifier`（F89） | T-MDL-9〜11相当＋SIZE_MISMATCH順序保証1件 | 4 | E1 | 全件Red。`File`/`ModelCatalogEntry`を直接構築するため`ModelStorage`の内部規約に非依存 |
| `DeviceCapability`（F91） | T-MDL-1〜3相当（境界値2件追加） | 7 | **E2**（§12.1とのラベル不一致、下記参照） | 全件Red |
| `LocalAiGateway`（F96） | T-GW-1〜10・12・13・15・17 | 14 | E2 | 全件Red（うちT-GW-13のみ型不一致による`AssertionError`、他13件は`NotImplementedError`） |

**対象外（本サイクルで作成しなかったID・理由）**:
- **T-GW-11**（容量不足→DL開始しない）: `LocalAiGateway`の公開APIは`generatePlan`/`generateRecovery`のみでDL開始メソッドを持たず、§8.6 #3の判定タイミングも「DL開始前」（Settings/ModelDownloader起点）のため本クラスの責務外と判断。§12.5への配置自体がミスカテゴリの可能性。
- **T-GW-14**（全Fallback経路でAnalytics記録1回）: P7-C1完了記録が「P7-C2でコンストラクタへ収集用コラボレータを追加する形で設計することを申し送る」としているが、コンストラクタへの引数追加は`src/main`変更に当たり本タスクでは禁止されている。テスト側だけでは注入口が存在せず書けない。
- **T-GW-16**（2回目呼び出しでモデル再ロードが起きない）: 「再ロード」は`LiteRtLmLocalLanguageModel`（P7-C5）の内部状態であり、§16凍結`LocalLanguageModel`インタフェースの`generatePlan`呼び出し回数からは観測できない。P7-C5のadapter単体テストのスコープと判断。
- **T-GW-18**（ロード前再検証失敗→MODEL_CORRUPTED）: 「導入済みだが`ModelCatalogEntry.sha256`と不一致」という状態を組み立てるには`ModelStorage`の内部ファイル配置規約（P7-C4で確定）と、`LocalAiGateway`が再検証対象に選ぶ`ModelCatalogEntry`の決定方法（未確定）の両方が要る。自己判断での組み立てを避け、骨格も書かず申し送りとした。
- **T-SET-1〜8・T-P7DI-1〜2**（F92/F97設定関連）・**T-MDL-4〜8・12〜16**（ModelDownloader/ModelStorage）: 本タスクの対象範囲として明示されなかったため対象外（P7-C6/P7-C4のRed着手時に別途作成）。
- **T-AIISO-4〜9・T-AIISO-8・T-BPE-28/T-BRE-32/T-NOTIF-9の改修**（隔離ガード）: 計画書§14自身がP7-C7（統合）に割り当てており、C2のスコープ外。
- **T-P7E2E-1〜5・P7-P1・P7-P2**（E3/E4）: 実機/エミュレータ必須のためC2対象外。P7-C0でプローブ済み（§14.1）、P7-P1はG4-E、P7-P2はP7-C8実機で実行。

#### 5系統フォールバックテストの設計（`LocalAiGateway`）

`model: LocalLanguageModel`は§16の凍結interfaceのため`FakeLocalLanguageModel`（`Respond`/`ThrowError`の2種の応答を順に返す）で完全に差し替え可能——本プロジェクトの他のDI境界（`CalendarService`/`RoutingService`等）と同じ確立されたfakeパターンをそのまま適用した。

一方`modelStorage: ModelStorage`／`modelVerifier: ModelVerifier`／`deviceCapability: DeviceCapability`／`preferences: AiPreferences`はいずれも**具象クラス（非`open`）**であり、本プロジェクトの他のDI境界と異なりinterfaceでfake差し替えできない。モック用ライブラリは本プロジェクトに存在せず本タスクでも追加不可、`open`化・interface抽出は`src/main`変更のため範囲外。したがって：
- `AiPreferences`／`DeviceCapability`は**Robolectricの実Context・実SharedPreferences・実`ActivityManager`shadow**（`Shadows.shadowOf(activityManager).setMemoryInfo(...)`・`ShadowBuild.setSupportedAbis(...)`、いずれもContext7で実在確認済み）で状態を制御する`E2`テストとして設計した。
- `ModelStorage`の「導入済み」状態は、内部ファイル命名規約がP7-C4で未確定のため**意図表明のプレースホルダ**に留めた（`installedModelStorage()`ヘルパーのKDoc参照）。

5系統の対応: ①ロード失敗=T-GW-4（fake `UnsatisfiedLinkError`）、②OOM能動ガード=T-GW-5（`supportedDeviceCapability(availMemBytes=200MB)`でmodel呼び出し0回を検証）、③タイムアウト=T-GW-6（fake遅延`DEFAULT_TIMEOUT_MILLIS+5000ms`、`runTest`仮想時間）、④スキーマ検証失敗=T-GW-7/8（9件steps応答でmaxItems=8超過を意図した不合格相当を作り、retry動作を検証。ただし下記の統合ギャップに留保付き）、⑤端末非対応=T-GW-9/10（`unsupportedRamDeviceCapability`/`unsupportedAbiDeviceCapability`）。

**統合ギャップ（Fable 5確認事項・T-GW-7/8に影響）**: §16の凍結`LocalLanguageModel.generatePlan()`は`AIPlanResponse`（パース済みオブジェクト）を返す契約だが、`SchemaValidator.validate()`は`rawJson: String`を受け取る契約（F95）である。両者を`LocalAiGateway`がどう橋渡しするか（`AIPlanResponse`を再シリアライズするのか、`SchemaValidator`が構造化オブジェクトを受ける別経路を持つのか）は計画書から一意に確定できない。T-GW-7/8はこの橋渡しの実装詳細に依存せず「9件steps＝不合格相当／1件steps＝合格相当」という観測可能な入力の差だけでGateway全体の振る舞いを検証しており、**P7-C5実装時にこの橋渡し方法を確定させる必要がある**。

#### 3分類集計（`:app:testDebugUnitTest --rerun`実測）

| 分類 | 件数 | 内訳 |
|---|---|---|
| (a) 新規Red（意図した失敗、正常） | 58 | `NotImplementedError`57件（`SchemaValidator`26・`PlanPromptBuilder`7・`ModelVerifier`4・`DeviceCapability`7・`LocalAiGateway`13）＋型不一致`AssertionError`1件（`LocalAiGateway`のT-GW-13、キャッチした`NotImplementedError`が`CancellationException`でないと正しく検出） |
| (b) 新規born-green（契約確定済み、意図したGreen） | 8 | `AiFallbackReason`2・`AiMetrics`2・`ModelCatalog`4 |
| (c) 既存417件の回帰 | **0** | `tests=483`＝`417`（P7-C1ベースライン）＋`66`（新規、a+b）と完全一致。`skipped=1`もベースラインと不変。全XML集計をクラス単位で突合し、失敗が出たのは上記5クラス（58件）のみであることを確認済み |

#### 差し戻し事項（Fable 5確認）

1. `SchemaValidationResult`の最終形（`type`/`priority`の変換済みDomain enumをどう公開するか）— 上記「確定した論点」1参照。
2. `event_type`/`action_type`の確定enum語彙 — 正仕様書§21に列挙がなく、P7-C3着手前にユーザー確認が必要（上記「確定した論点」2参照）。
3. **`LocalLanguageModel.generatePlan()`（`AIPlanResponse`を返す）と`SchemaValidator.validate()`（`rawJson: String`を受ける）の橋渡し方法** — 統合ギャップとして新規に発見。T-GW-7/8・T-SCH群双方に影響するため、P7-C5着手前の確定を推奨。
4. `LocalAiGateway`の4つの具象クラス依存（`ModelStorage`/`ModelVerifier`/`DeviceCapability`/`AiPreferences`）が本プロジェクトの他のDI境界（interfaceベース）と設計が異なる件 — P7-C5でfake注入性を高める設計変更（interface抽出等）を検討するか、Robolectric実状態操作を正式な方式として採用するかの判断が必要。
5. T-GW-11の§12.5配置（`LocalAiGateway`ではなくModelDownloader/Settings起点の可能性）。
6. T-GW-14のAnalyticsコラボレータ追加（コンストラクタ変更、`src/main`）の承認要否。
7. T-GW-18のフィクスチャ完成（`ModelStorage`内部規約・検証対象エントリ決定方法の確定待ち）。

**証拠ファイル**: `build/agent-logs/p7c2-compile.log`（`:app:compileDebugUnitTestKotlin`成功）・`p7c2-red.log`（新規8クラス個別実行、66件中58件Red・8件born-green）・`p7c2-regression.log`（`:app:testDebugUnitTest --rerun`全体、tests=483/failures=58/errors=0/skipped=1）。

### 14.4 P7契約確定完了記録（2026-08-10確定・domain-implementer）

**結論**: §14.3が残した7つの差し戻し事項（確定した論点2件の再確認含む）をFable 5裁定1〜8＋retry契約確定として解決し、`src/main`のscaffold契約とP7-C2の66テストを新契約へ整合させた（本体ロジックはTODO()維持）。`:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`成功（`build/agent-logs/p7-contract-compile.log`）、`:app:testDebugUnitTest --rerun`でtests=476／failures=51／errors=0／skipped=1（`build/agent-logs/p7-contract-regression.log`）。**既存417件の回帰は0件**（476−417＝59＝P7-C2の66件から7件削除した残り。51件が意図的Red維持、8件がborn-green維持）。

**差し戻し事項の解決一覧**:

| # | §14.3の差し戻し事項 | 解決（裁定・ADR） |
|---|---|---|
| 1 | `SchemaValidationResult`の最終形（type/priorityの変換済みDomain enumをどう公開するか） | **裁定1・3（ADR-0045）**: type/priority自体をLLM出力から除去。action_type/event_typeは検証後もString保持のまま、Domain enum変換はPhase 8 `LocalAIPlanningEngine`の責務に確定 |
| 2 | event_type/action_typeの確定enum語彙（§21に列挙なし） | **裁定2（ADR-0046）**: event_type 8値・action_type 7値を確定。`PlanEventType`/`PlanActionType`をscaffold新設 |
| 3 | `LocalLanguageModel.generatePlan()`（AIPlanResponse）と`SchemaValidator.validate()`（rawJson: String）の橋渡し方法（統合ギャップ） | **裁定3（ADR-0045・0047）**: `generatePlan()`の戻り値をStringへ変更し統合ギャップを解消。検証パイプライン（SchemaValidator→ContentSanityChecker）を確定 |
| 4 | `LocalAiGateway`の4つの具象クラス依存が他DI境界（interface）と設計が異なる件 | **裁定5（ADR-0048）**: ModelStorage/ModelVerifier/DeviceCapability/AiPreferencesをinterface化、実装をXxxImplへ分離 |
| 5 | T-GW-11の§12.5配置（LocalAiGatewayでなくModelDownloader/Settings起点の可能性） | **裁定6（ADR-0049）**: ModelDownloader/Settings領域と確定（P7-C2の判断を追認） |
| 6 | T-GW-14のAnalyticsコラボレータ追加（コンストラクタ変更）の承認要否 | **裁定7（ADR-0049）**: 追加しない。Phase 10/12のAnalytics基盤と共に実装することを確定 |
| 7 | T-GW-18のフィクスチャ完成（ModelStorage内部規約・検証対象エントリ決定方法の確定待ち） | **裁定8（ADR-0049）**: P7-C4（ModelStorageファイルレイアウト規約確定時）まで据え置くことを確定 |

**品質ハーネス統合**: `docs/plans/phase7-quality-harness.md`のSemantic Contextualization（§0/§2）・3段検証（§6）・retry是正（§0/§4/§6）・`ContentSanityChecker`（§6②）・`PlanPromptBuilder`拡張（§10）を本計画書の正式契約として統合した（品質ハーネス側は§10へ整合確認の注記を追加、下記参照）。

**P7-C2テストの調整内訳（詳細は`SchemaValidatorTest`/`LocalAiGatewayTest`のクラスKDoc参照）**:
- **born-green化**: 0件（P7-C2時点で既にborn-greenだった8件〔AiFallbackReason 2・AiMetrics 2・ModelCatalog 4〕は本サイクルでも継続してborn-green）
- **削除**: 7件（`SchemaValidatorTest`のT-SCH-4・9・10・11・12・20・21。理由: estimated_minutes/priority/skippableフィールド消滅〔6件〕、重複action_type検出のContentSanityCheckerへの責務移管〔1件〕）
- **更新**: 5件（`AiMetricsTest`のフィールド集合更新1件、`SchemaValidatorTest`のT-SCH-2/5/14〔消滅フィールドから存続フィールドへ検証対象を差し替え〕・T-RF-1/3〔enum配列・requiredセットの更新〕）
- **無変更（新契約下でも意図どおりRed維持）**: 44件（`SchemaValidatorTest`の残り15件・`PlanPromptBuilderTest`の7件・`DeviceCapabilityTest`の7件〔Impl名変更のみ〕・`ModelVerifierTest`の4件〔Impl名変更のみ〕・`LocalAiGatewayTest`の14件〔戻り値型変更の影響を受けるが検証意図は不変〕。※LocalAiGatewayTestの14件は「フィクスチャ内部実装（Respond型・生成JSON形式）は変更したがテストメソッド本体は無変更」の意味）

**起票ADR**: ADR-0045（LLM出力責務分界の再定義とgeneratePlan()契約変更）・ADR-0046（event_type/action_type enum語彙確定）・ADR-0047（ContentSanityChecker新設と3段検証パイプライン確定）・ADR-0048（4型のinterface化）・ADR-0049（retry契約是正・AiMetrics.sanityPassed・PlanPromptBuilder拡張・T-GW-11/14/18帰属確定）。

**P7-C3（Green）への申し送り**:
1. `PlanJsonSchema.TEXT`の実際のJSON文字列リテラルをADR-0045・0046確定契約（event_type 8値・action_type 7値・display_text自由文60字以内・additionalProperties:false）で実装すること。
2. `SchemaValidator.validate()`を①形式検証専念で実装すること（`ContentSanityChecker`の責務〔捏造・titleコピー・locale整合・重複action_type〕を混入させない）。
3. `ContentSanityChecker`は本サイクルでscaffold新設のみ（TODO本体）。対応するRedテスト（品質ハーネスQH-4〜7・QH-10〜11・QH-15相当）は本サイクルで作成していないため、P7-C3着手前にRed作成サイクルを挟むか、P7-C3の一部として作成すること。
4. `PlanPromptBuilder.buildSystemInstruction`/`buildFewShot`も同様にscaffold新設のみ。対応するRedテスト（QH-8・QH-9・QH-14相当）は未作成。
5. `LiteRtLmLocalLanguageModel`（P7-C5）実装時、Gateway起点の「これは何回目の呼び出しか」をadapterがどう判断するか（内部カウンタ等）を設計すること（ADR-0049「再検討トリガー」参照）。

**証拠ファイル**: `build/agent-logs/p7-contract-compile.log`（`:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`成功）・`p7-contract-regression.log`（`:app:testDebugUnitTest --rerun`、tests=476/failures=51/errors=0/skipped=1）。

### 14.5 P7-C2c完了記録（2026-08-10確定・test-writer）

**結論**: §14.4が残したP7-C3申し送り3・4（`ContentSanityChecker`／`PlanPromptBuilder.buildSystemInstruction`・`buildFewShot`のRedテスト未作成）を解消し、29件のRedテストを新規作成した（うち25件Red・4件born-green）。あわせてFable 5裁定9（品質ハーネス§4のサンプリング設計をscaffold契約化する裁定）に基づき、`SamplingPolicy` enum（新設）・`LocalLanguageModel.generatePlan`への`samplingPolicy`引数追加（契約変更）を行った。`:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`成功（`build/agent-logs/p7c2c-compile.log`）、`:app:testDebugUnitTest --rerun`でtests=505／failures=76／errors=0／skipped=1（`build/agent-logs/p7c2c-regression.log`）。**既存417件（416 pass＋1 skip）の回帰は0件**（505−417＝88＝P7-C2〜P7契約確定の既存59件＋P7-C2cの新規29件と完全一致）。

**新設・変更したRedテストの内訳（29件）**:

| 対象ファイル | 新設テスト | 件数 | 区分 |
|---|---|---|---|
| `ai/schema/ContentSanityCheckerTest.kt`（新設） | QH-4a〜d（捏造検出：時刻/単位付き数字/裸数字/URL）・QH-10（禁止語5種）・QH-11（決定性）・QH-5a〜b（titleコピー：完全一致/80%占有）・QH-15a〜b（短title免除：自然な言い換え/完全一致でも免除）・QH-6a〜b（locale不整合：ja↔en双方向）・QH-7（重複action_type）・長さ上限再確認・Semantic Contextualization模範例（正常系） | 15 | E1（純JVM）、全件Red |
| `ai/prompt/PlanPromptBuilderTest.kt`（既存へ追加） | QH-8a〜c（`buildSystemInstruction`：役割/ハードルール含有、ja/en言語切替）・QH-14a〜e（`buildFewShot`：ja/en単一言語・数値ゼロ、shotCount 0/1/2/既定のクランプ） | 8 | E1（純JVM）、全件Red |
| `ai/SamplingPolicyTest.kt`（新設） | `SamplingPolicy.Primary`（topK=1/temp=0.0/簡潔化制約なし）・`Retry`（topK=5/temp 0.1〜0.2/簡潔化制約あり）の値契約、全ポリシーのtopK>0制約、Primary/Retryが異なる条件を持つことの回帰ロック | 4 | E1（純JVM）、born-green（enum定数が確定済みのため） |
| `ai/LocalAiGatewayTest.kt`（既存へ追加） | T-GW-19（1回成功時はPrimaryのみ使用）・T-GW-20（1回目不合格→2回目合格の場合、1回目Primary・2回目Retryで呼び分け） | 2 | E2（Robolectric）、全件Red |

**scaffold調整内容（Fable 5裁定9、TEAMS §5契約変更フロー、ADR-0050）**:
1. **`ai/SamplingPolicy.kt`（新設）**: `enum class SamplingPolicy(topK: Int, temperature: Double, appendConcisenessConstraint: Boolean)`。`Primary(topK=1, temperature=0.0, appendConcisenessConstraint=false)`・`Retry(topK=5, temperature=0.15, appendConcisenessConstraint=true)`の2値を確定（品質ハーネス§4）。全プロパティが確定済みのenum定数でありTODO()を含まないためborn-green契約（`AiFallbackReason`・`PlanEventType`・`PlanActionType`と同型）。
2. **`LocalLanguageModel.generatePlan`（契約変更）**: `generatePlan(context: PlanningContext, samplingPolicy: SamplingPolicy = SamplingPolicy.Primary): String`へ引数を追加。**この変更はADR-0049「代替案と却下理由」が一度却下した設計（retry制御パラメータの追加）をFable 5裁定9が明示的に覆したものであり、この経緯をinterfaceのKDocとADR-0050の双方に明記した**（詳細は下記「ADR-0049との関係」参照）。
3. **`LiteRtLmLocalLanguageModel.generatePlan`（オーバーライド）**: シグネチャを`generatePlan(context: PlanningContext, samplingPolicy: SamplingPolicy): String`へ追随（Kotlinの規約によりoverride側は既定値を再宣言しない）。本体は`TODO()`のまま維持（Green実装はP7-C5）。
4. **`LocalAiGateway`（KDocのみ更新、本体は`TODO()`のまま維持）**: 検証パイプラインの記述を「1回目=`model.generatePlan(context, SamplingPolicy.Primary)`、①②不合格時の2回目=`model.generatePlan(context, SamplingPolicy.Retry)`」へ更新し、この呼び分けがGatewayの責務でありadapterは検証の成否を知らないことを明記した。**呼び分けロジック自体の実装はP7-C3以降のGreenで行う**（本サイクルは契約明記とテスト作成のみ）。
5. **`AppContainer.kt`**: 変更なし（確認済み）。`generatePlan`の呼び出し箇所は本コンテナに存在せず、`samplingPolicy`引数はinterface側の既定値（`SamplingPolicy.Primary`）で解決されるため、配線変更は不要と判断した。
6. **テスト側scaffold（`LocalAiGatewayTest.kt`）**: `FakeLocalLanguageModel`／`ConcurrencyTrackingFakeModel`のoverride署名を追随。`FakeLocalLanguageModel`は呼び出しごとの`SamplingPolicy`を`recordedSamplingPolicies`へ記録するよう拡張した。

**ADR-0049との関係（重要・透明性のため明記）**: ADR-0049「代替案と却下理由」表の1行目は、`LocalLanguageModel`への retry制御パラメータ追加案（例:`generatePlan(context, isRetry: Boolean)`）を「§16の`LocalLanguageModel`は凍結interfaceであり、裁定1〜8はいずれもパラメータ追加を指示していない」ことを理由に**却下**していた。本サイクルのFable 5裁定9は、この却下判断を**明示的に覆し**、`samplingPolicy`パラメータの追加を正式に承認した。これはP7-C2c指示自体に「Fable 5追加裁定9」として明記された新規裁定であり、test-writer（本サイクル担当）が自己判断で決めたものではない。ADR-0050の背景欄にこの経緯を明記し、ADR-0049は無効化ではなく「裁定9により一部上書きされた」形で参照可能なまま維持する。

**3分類集計（`:app:testDebugUnitTest --rerun`実測、`build/agent-logs/p7c2c-regression.log`）**:

| 分類 | 件数 | 内訳 |
|---|---|---|
| (a) 新規Red（意図した失敗、正常） | 25 | `ContentSanityCheckerTest`15・`PlanPromptBuilderTest`新規8・`LocalAiGatewayTest`新規2。全件`kotlin.NotImplementedError`（対応する本体`TODO()`起因） |
| (b) 新規born-green（契約確定済み、意図したGreen） | 4 | `SamplingPolicyTest`4件（`SamplingPolicy`がenum定数として確定済みのため） |
| (c) 既存476件（P7契約確定時点のベースライン）の回帰 | **0** | `tests=505`＝`476`（ベースライン）＋`29`（新規、a+b）と完全一致。`skipped=1`もベースラインと不変。クラス単位の失敗内訳は`SchemaValidatorTest`19・`LocalAiGatewayTest`16（既存14＋新規2）・`PlanPromptBuilderTest`15（既存7＋新規8）・`ContentSanityCheckerTest`15（新規）・`DeviceCapabilityTest`7・`ModelVerifierTest`4＝合計76件で、`76 failed`の実測と完全一致。既存6クラス以外（domain/features/services/di等の417件相当）に失敗は一切現れていないことを確認済み |

個別実行によるRed確認（`build/agent-logs/p7c2c-red.log`）: 新設・更新4クラス（`ContentSanityCheckerTest`・`PlanPromptBuilderTest`・`SamplingPolicyTest`・`LocalAiGatewayTest`）を個別実行し、50 tests completed, 46 failed（うち45件`NotImplementedError`・1件は既存T-GW-13由来の`AssertionError`で本サイクルの変更とは無関係）。SamplingPolicyTestの4件はJUnit XML（`TEST-com.actionstarter.ai.SamplingPolicyTest.xml`）でfailures=0・errors=0を個別確認した。

**P7-C3（Green）への申し送り**:
1. §14.4申し送り1〜3（`PlanJsonSchema.TEXT`実装・`SchemaValidator.validate`実装・`ContentSanityChecker`本体実装）は変更なくそのまま有効。`ContentSanityChecker`は本サイクルで対応するRedテスト（15件）が揃ったため、Green化の受け入れ基準が明確になった。
2. `PlanPromptBuilder.buildSystemInstruction`・`buildFewShot`も対応するRedテスト（8件）が揃った。Green化はP7-C5（adapter実装と同時期）のまま。
3. `LocalAiGateway.generatePlan`のGreen実装時、検証パイプライン不合格時に`model.generatePlan(context, SamplingPolicy.Retry)`を呼ぶこと（T-GW-20が回帰ロックする）。1回で成功する場合は`SamplingPolicy.Retry`を一度も使わないこと（T-GW-19が回帰ロックする）。
4. `LiteRtLmLocalLanguageModel.generatePlan`のGreen実装時（P7-C5）、`samplingPolicy.topK`／`samplingPolicy.temperature`を実際の`SamplerConfig(topK, topP, temperature, seed)`へマップすること（`topP`・`seed`の具体値はP7-C5の実装詳細、`SamplingPolicy`のKDoc参照）。`samplingPolicy.appendConcisenessConstraint`が`true`のときのみdata message末尾に固定簡潔化制約文を追記すること。
5. `ContentSanityChecker`の実際の`LocalAiGateway`への配線（コンストラクタ注入）は本サイクルでも未実施のまま（ADR-0047が指定するとおりP7-C5で実施）。

**証拠ファイル**: `build/agent-logs/p7c2c-compile.log`（`:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`成功）・`p7c2c-red.log`（新設・更新4クラス個別実行、50件中46件Red・4件born-green）・`p7c2c-regression.log`（`:app:testDebugUnitTest --rerun`全体、tests=505/failures=76/errors=0/skipped=1）。

### 14.6 P7-C3完了記録（2026-08-10確定・domain-implementer）

**結論**: P7-C2/C2c時点で意図的Redだった76件のうち**75件をGreen化**し、既存417件（416 pass＋1 skip）の回帰は0件。`PlanJsonSchema.TEXT`（F94）・`SchemaValidator`（F95）・`ContentSanityChecker`（ADR-0047新設）・`PlanPromptBuilder`の`build`/`buildSystemInstruction`/`buildFewShot`（F93）・`DeviceCapabilityImpl`（F91）・`ModelVerifierImpl`（F89）・`LocalAiGateway`（F96、5系統フォールバック・3段検証パイプライン・retry呼び分け）を実装した。**唯一Red維持となったT-GW-3（モデル未導入→`MODEL_NOT_INSTALLED`）は実機依存ではなくP7-C4（`ModelStorage`ファイル配置規約未確定）依存であり、ADR-0051として判断・記録した。**

#### クラス別Green化記録（個別実行、`build/agent-logs/p7c3-green-<class>.log`）

| クラス | 対象F番号 | 結果 | ログ |
|---|---|---|---|
| `SchemaValidatorTest` | F94・F95 | **19/19 Green**（T-SCH-1〜3・5〜8・13〜19・22＝15、T-RF-1〜4＝4） | `p7c3-green-SchemaValidator.log` |
| `ContentSanityCheckerTest` | ADR-0047新設 | **15/15 Green**（QH-4a〜d・QH-5a〜b・QH-6a〜b・QH-7・QH-10・QH-11・QH-15a〜b・長さ上限再確認・Semantic Contextualization模範例） | `p7c3-green-ContentSanityChecker.log` |
| `PlanPromptBuilderTest` | F93 | **15/15 Green**（T-PRM-1〜7＝7、QH-8a〜c・QH-14a〜e＝8） | `p7c3-green-PlanPromptBuilder.log` |
| `DeviceCapabilityTest` | F91 | **7/7 Green**（classify境界値3・isAbiSupported2・hasAvailableMemory2） | `p7c3-green-DeviceCapability-ModelVerifier.log` |
| `ModelVerifierTest` | F89 | **4/4 Green**（T-MDL-9〜11相当＋SIZE_MISMATCH順序保証） | `p7c3-green-DeviceCapability-ModelVerifier.log`（同一実行） |
| `LocalAiGatewayTest` | F96 | **15/16 Green**（T-GW-1・4〜10・12・13・15・17・19・20）。**T-GW-3のみRed継続**（ADR-0051、下記参照） | `p7c3-green-LocalAiGateway.log` |

`ModelCatalogTest`（born-green・回帰ガード）・`AiFallbackReasonTest`（born-green）・`AiMetricsTest`（born-green）・`SamplingPolicyTest`（born-green）は無変更のまま全件Green維持（`p7c3-green-ai-package.log`でパッケージ全体88件中87件Greenを再確認）。

#### P7-C3で生じた2つの判断事項（ADR起票済み・詳細はADR本文参照）

1. **ADR-0051（`LocalAiGateway`のGreen実装スコープ）**: `ContentSanityChecker`・`SchemaValidator`は状態レスなためコンストラクタ注入ではなく`LocalAiGateway`のprivateフィールドとして直接インスタンス化した（**`AppContainer.kt`は無変更**、凍結ファイルへの波及なし）。一方、`modelStorage.installedModelPath()`（§8.6 #11）・`modelVerifier`によるロード前再検証（§8.6 #12）は、`LocalAiGatewayTest`の`installedModelStorage()`ヘルパーが`notInstalledModelStorage()`と同一の未初期化`ModelStorageImpl`を返す設計（同テストのKDocが既に明記）であるため、これを呼び出すと「導入済み」を意図した14件のケースも含め全T-GW-*が無条件に`NotImplementedError`となり、5系統フォールバックが1件もGreen化できなくなることが判明した。**したがって本サイクルではこの2ステップを`generatePlan()`の実行パスから意図的に除外し、P7-C4（`ModelStorage`ファイル配置規約確定）まで延期した。** 結果としてT-GW-3のみが対象外（Red継続。失敗の性質は`NotImplementedError`→`AssertionError`〔`Fallback`期待に対し`Success`が返る〕へ変化）。T-GW-18は元々ADR-0049裁定8によりP7-C4据え置き確定済みで対象外（テストメソッド自体が未作成）。
2. **ADR-0052（`AiPreferencesImpl`最小実装の前倒し）**: F92はP7-C6（Green: settings）担当だが、`LocalAiGateway`の最初のガード（§8.6 #10「AI OFF判定」）が`preferences.aiEnabled`を読むため、これがTODO()のままでは`LocalAiGatewayTest`の**全16ケース**（AI ON/OFFいずれの期待値でも）が最初のガードで例外化し、本タスクの主目的（5系統フォールバック等のGreen化）が一切達成できなくなることが判明した。設計上の曖昧さを一切伴わない（TODOコメント自身が実装内容を一字一句指定済み）ため、`aiEnabled`／`selectedModelId`の2プロパティのみP7-C3の範囲でGreen化した。

#### 実機依存で意図的にRedのまま残すもの（計画どおり・変更なし）

P7-C3のスコープはJVM検証可能な部品のみであり、以下は計画書の指定どおり実機/エミュレータ依存のためP7-C3では対象外（Red/未実行のまま）:

| 対象 | 区分 | 理由 |
|---|---|---|
| `LiteRtLmLocalLanguageModel.generatePlan`／`generateRecovery`本体 | — | `com.google.ai.edge.litertlm`の実推論本体はP7-C5スコープ。本サイクルは`LocalLanguageModel`型を介したfake（`FakeLocalLanguageModel`）でのみGatewayを検証した |
| `ModelDownloader.download`本体 | — | 実HTTPダウンロードはP7-C4スコープ。本サイクルは対象外（`ai/model/ModelDownloader.kt`は無変更） |
| `ModelStorageImpl`の7メソッド本体（`installedModelPath`等） | — | 実ファイルI/O・配置規約確定はP7-C4スコープ。本サイクルは無変更（TODO()のまま） |
| T-P7E2E-1〜5 | E3 | 実`.so`ロード・実推論・機内モード・StrictMode・画面回転耐性。エミュレータ必須（P7-C7） |
| QH-12 | E3 | 実機/エミュ小コンテキストでのfew-shot付き実推論。エミュレータ必須 |
| P7-P1 | E3(probe) | エミュレータ実推論ベンチ（G4-E） |
| QH-13・QH-16・P7-P2 | E4(probe) | 実機Galaxy Aベンチ（P7-C8） |
| T-AIISO-4〜9・T-AIISO-8・T-BPE-28/T-BRE-32/T-NOTIF-9改修 | E1 | 計画書§14が明示的にP7-C7（統合）へ割り当て済み。P7-C3の対象外（着手していない） |
| T-SET-1〜8・T-P7DI-1〜2 | E2 | F92/F97（Settings）はP7-C6スコープ。`AiPreferencesImpl.aiEnabled`／`selectedModelId`のみADR-0052でP7-C3にて前倒し実装したが、Settings画面自体・`T-P7DI-*`は未着手 |
| T-MDL-4〜8・12〜16 | E1/E2 | `ModelDownloader`／`ModelStorage`本体はP7-C4スコープ。未着手 |

#### 3分類集計（`:app:testDebugUnitTest --rerun`実測、`build/agent-logs/p7c3-full.log`、JUnit XML集計で裏取り済み）

| 分類 | 件数 | 内訳 |
|---|---|---|
| (a) 実機依存等で意図的に残すRed（計画どおり） | **1**（T-GW-3。ただし実機依存ではなくP7-C4依存、ADR-0051） | `LocalAiGatewayTest.tGw3_modelNotInstalled_returnsFallbackModelNotInstalled_modelNeverInvoked`のみ。`AssertionError`（`NotImplementedError`から性質変化） |
| (b) 既存417件（416 pass＋1 skip）の回帰 | **0** | `tests=505`＝`417`（既存ベースライン）＋`88`（P7-C2〜C2cで追加された新規テスト、うち87がGreen・1がRed）と完全一致。JUnit XML集計で失敗が出たのは`LocalAiGatewayTest`1クラス1件のみであることを確認済み（`com.actionstarter.ai`パッケージ以外に失敗なし） |
| (c) 想定外の失敗 | **0** | 上記(a)以外に予期しない失敗は発生しなかった |

**Green化件数**: P7-C2c時点の76件Redのうち**75件をGreen化**（76−1＝75）。`:app:testDebugUnitTest --rerun`実測: tests=505／**failures=1**／errors=0／skipped=1（P7-C2cベースラインのfailures=76から75件減）。

#### lint結果

`:app:lintDebug`＝**BUILD SUCCESSFUL・error 0**（`build/agent-logs/p7c3-lint.log`）。warning 22件（既存分含む）のうち本サイクルの新規コード由来は`AiPreferences.kt:60`の`UseKtx`提案1件のみ（`SharedPreferences.edit()`を拡張関数化する提案。既存`SharedPreferencesExecutionScheduleStore`と同型の実装のため許容、エラーではない）。

#### ContentSanityChecker配線の判断

ADR-0051のとおり、**`LocalAiGateway`のprivateフィールドとして直接インスタンス化する形で配線を完了した**（コンストラクタ注入ではない。理由: 両クラスとも状態レスでテストからのfake差し替え要求がないため）。この設計により**`AppContainer.kt`は一切変更不要**であり、「凍結ファイルへの波及」は発生しなかった。

#### P7-C4／P7-C5への申し送り

1. **`ModelStorage`のファイル配置規約確定後**、`LocalAiGateway.generatePlan()`へ§8.6 #11（`modelStorage.installedModelPath()`チェック）・#12（`modelVerifier`ロード前再検証）を配線し、`LocalAiGatewayTest`の`installedModelStorage()`ヘルパーを実際にファイルを配置する形へ更新してT-GW-3をGreen化すること（ADR-0051再検討トリガー）。
2. T-GW-18（ADR-0049裁定8）のフィクスチャ完成は上記1と同時に検討すること。
3. `LiteRtLmLocalLanguageModel`（P7-C5）実装時、`AiMetrics`の`modelLoadMs`／`firstTokenMs`／`outputTokens`／`tokensPerSecond`／`peakNativeHeapBytes`を`BenchmarkInfo`から実測値へ差し替えること（本サイクルはGateway境界で計測不能なため`0`のプレースホルダとし、`totalMs`のみGateway側で実測した）。
4. `LocalAiGateway`のOOM事前ガード（§8.6 #7）が参照する安全マージン（`MEMORY_SAFETY_MARGIN_BYTES=512MB`）は仮値。§8.6冒頭の他の閾値（タイムアウト20,000ms等）と同様、G4-D実機実測（§11.3）で確定すること。
5. `AiPreferencesImpl`（ADR-0052）はP7-C3で最小実装済みだが、Settings画面（F97・T-SET-*・T-P7DI-*）自体はP7-C6で改めて対応すること。

**証拠ファイル**: `build/agent-logs/p7c3-green-SchemaValidator.log`・`p7c3-green-ContentSanityChecker.log`・`p7c3-green-PlanPromptBuilder.log`・`p7c3-green-DeviceCapability-ModelVerifier.log`・`p7c3-green-LocalAiGateway.log`・`p7c3-green-ai-package.log`（`com.actionstarter.ai.*`全体88件中87件Green再確認）・`p7c3-full.log`（`:app:testDebugUnitTest --rerun`全体、tests=505/failures=1/errors=0/skipped=1）・`p7c3-lint.log`（`:app:lintDebug`、BUILD SUCCESSFUL・error 0）。

### 14.7 P7-C4完了記録（2026-08-10確定・domain-implementer）

**結論**: P7-C3が唯一Red残置していたT-GW-3を含め、`ModelStorage`（F90）・`ModelDownloader`（F88）を実装し、`LocalAiGateway`へ§8.6 #11（モデル未導入判定）・#12（ロード前SHA-256再検証）を配線した。**T-GW-3をGreen化し、加えてADR-0049裁定8がP7-C4まで据え置いていたT-GW-18（ロード前検証失敗）も本サイクルでGreen化した（当初「Green化できれば行う」というbest-effort項目だったが、`ModelStorage`の`catalog`注入設計〔ADR-0053〕により実現できた）。** `:app:testDebugUnitTest --rerun`実測でtests=528／failures=0／errors=0／skipped=1。**既存417件の回帰は0件、P7-C2〜C2c追加88件（うちT-GW-3の1件Red含む）もすべてGreenを維持し、本サイクルの新規23件もすべてGreen——JVMスコープで唯一残っていたRed（T-GW-3）が解消され、現時点で`:app:testDebugUnitTest`のRedは0件になった。**

#### `ModelStorage`ファイル配置規約の確定内容（ADR-0053）

- **保存先**: `context.noBackupFilesDir/models/`固定（T-MDL-14）。ファイル名は`<ModelCatalogEntry.id>.litertlm`（正式配置）／`<id>.litertlm.part`（DL中一時ファイル）。
- **「導入済み」の解決方法**: `ModelStorageImpl`はコンストラクタで`catalog: List<ModelCatalogEntry>`（既定`ModelCatalog.ALL`）を受け取り、新設`installedEntry(): ModelCatalogEntry?`が`catalog`を順に走査して`finalFile(entry)`が実在する最初のエントリを返す。`installedModelPath()`は内部でこれを再利用する。テストはこの`catalog`引数へ小さなfixtureエントリを差し替えることで、実モデル328MB・SHA-256実測値`e3e290...`を用意せずに、本物の`ModelVerifierImpl`によるSHA-256照合を伴う「導入済み」状態を高速に作れる（`LocalAiGatewayTest`の`installedModelStorage()`参照）。
- **原子的コミット**: `commit()`は`java.nio.file.Files.move`の`ATOMIC_MOVE`で`.part`→正式名へリネームする（同一ディレクトリ内のため`AtomicMoveNotSupportedException`は発生しない）。
- **容量ガード**: `hasSufficientSpace(requiredBytes)`は`StatFs(noBackupFilesDir).availableBytes >= requiredBytes × 1.5`で判定する。

#### クラス別Green化記録（個別実行、`build/agent-logs/p7c4-green-<class>.log`）

| クラス | 対象F番号 | 結果 | ログ |
|---|---|---|---|
| `ModelStorageTest`（新設） | F90 | **11/11 Green**（T-MDL-4〜5相当3件・未導入ベースライン2件・T-MDL-12相当3件・T-MDL-13相当1件・T-MDL-14相当1件・T-MDL-15相当2件、内訳は多重計上あり） | `p7c4-green-ModelStorage.log` |
| `ModelDownloaderTest`（新設） | F88 | **10/10 Green**（T-MDL-16・容量ガード・T-MDL-6・T-MDL-7・T-MDL-8・HTTPエラー・ネットワークエラー・DL後検証成功・検証失敗・commit失敗） | `p7c4-green-ModelDownloader.log` |
| `LocalAiGatewayTest` | F96 | **18/18 Green**（既存16件を維持しつつ**T-GW-3を新規Green化**、加えて**T-GW-18a／T-GW-18bを新設しGreen化**） | `p7c4-green-LocalAiGateway.log` |

#### ModelDownloaderの設計訂正（ADR-0054・重要）

本タスク指示は「`ModelDownloader`...`DeviceCapability`で容量ガード」と記述していたが、`DeviceCapability`（F91）はRAM／ABI判定専用でストレージ容量の概念を持たず、容量ガード（`StatFs`ベース、§95.6）は既存scaffold（P7-C1）の時点で`ModelStorage.hasSufficientSpace(requiredBytes)`として確定済みだった。本タスク自身が「計画書§8・§14 P7-C4が正」と明記しているため、既存`ModelStorage`interface契約を優先し、`ModelDownloader.download()`は`modelStorage.hasSufficientSpace()`で容量ガードする実装とした（詳細な経緯・却下した代替案はADR-0054）。加えて、DL完了後の検証（§8.6 #5）→合格ならコミット／不合格なら削除、までを`download()`自体が一体パイプラインとして行う設計とした（タスク指示が「破損/検証失敗時は削除」までを`ModelDownloader`の記述に含めていたことに基づく）。

#### T-GW-3／T-GW-18のGreen化内容（`LocalAiGateway`配線、ADR-0053）

`generatePlan()`の`isAbiSupported()`チェックとOOM事前ガードの間（`inferenceMutex`内）へ、新設private関数`checkInstalledModel()`を配線した。`modelStorage.installedEntry()`が`null`なら`Fallback(MODEL_NOT_INSTALLED)`（T-GW-3）。毎回ファイルサイズを照合し、プロセス内（`LocalAiGateway`インスタンス単位）で当該エントリが未検証のときのみ`modelVerifier.verify()`でSHA-256を再検証してキャッシュする（T-GW-18a: 不一致→削除＋`Fallback(MODEL_CORRUPTED)`、T-GW-18b: 2回目以降は`ModelVerifier.verify`が再呼び出しされないことをカウンタ付きラッパーで確認）。`LocalAiGatewayTest`の`installedModelStorage()`／`notInstalledModelStorage()`ヘルパーを実配置形へ更新した（ADR-0051の再検討トリガーへの回答。この2ヘルパーの更新とT-GW-18a/b新設のみがテスト側の変更範囲、それ以外の既存テストメソッドは無変更）。

#### AppContainer配線（最小）

`modelStorage`をローカル変数からprivateプロパティ（`by lazy`）へ昇格し、`localAiGateway`と新設`modelDownloader`（`by lazy`）が同一インスタンスを共有する構成にした。`modelDownloader`の**呼び出し元は未配線**（Settings画面はF97・P7-C6のスコープであり本サイクルでは作らない）。`planningEngine`／`recoveryEngine`の右辺・その他の既存プロパティは無変更。

#### 3分類集計（`:app:testDebugUnitTest --rerun`実測、`build/agent-logs/p7c4-full.log`、JUnit XML集計で裏取り済み）

| 分類 | 件数 | 内訳 |
|---|---|---|
| (a) 実機依存等で意図的に残すRed（P7-C5/C6/C7/C8スコープ） | **0（JVMスイート上）** | 現時点で`:app:testDebugUnitTest`にRedは0件。P7-C5（`LiteRtLmLocalLanguageModel`本体・T-P7E2E-*）・P7-C6（Settings・T-SET-*・T-P7DI-*）・P7-C7（T-AIISO-4〜9等の隔離ガード拡張）・P7-C8（P7-P1/P7-P2・QH-12/13/16）に属するテストは**まだテストファイル自体が存在しない**（Red化する対象コードがまだscaffold段階のため。§12.1のE3/E4区分・§14サイクル表どおり、実機/エミュレータ必須のため各担当サイクルで新規作成する） |
| (b) 既存417件（P7-C1ベースライン）＋P7-C2〜C2c追加88件の回帰 | **0** | `tests=528`＝`417`（既存）＋`87`（P7-C2〜C2cで既にGreenだった分）＋`1`（T-GW-3、Red→Green反転）＋`23`（P7-C4新規、全件Green）で完全一致。JUnit XML集計（68クラス）で失敗クラスは0件 |
| (c) 想定外の失敗 | **0** | 上記以外に予期しない失敗は発生しなかった |

**Green化件数**: P7-C3終了時点の1件Red（T-GW-3）が解消し、加えてT-GW-18a・T-GW-18bおよびModelStorageTest 11件・ModelDownloaderTest 10件の新規23件が全件born-green相当でGreen化した。`:app:testDebugUnitTest --rerun`実測: tests=528／failures=0／errors=0／skipped=1（P7-C3のfailures=1から1件減、新規23件はfailuresに現れず即Green）。

#### lint結果

`:app:lintDebug --rerun-tasks`＝**BUILD SUCCESSFUL・error 0**（`build/agent-logs/p7c4-lint.log`、キャッシュ汚染を避けるため強制再実行で確認）。warning 22件（既存分と完全一致）のうち本サイクルの新規・変更コード（`ModelStorage.kt`・`ModelDownloader.kt`・`LocalAiGateway.kt`・`AppContainer.kt`）由来のものは**0件**（全22件は`AiPreferences.kt`2件・`SharedPreferencesExecutionScheduleStore.kt`3件のUseKtx提案、および既存のGradle/Manifest系警告であり、いずれもP7-C3以前から存在する既知分）。

#### P7-C5への申し送り

1. `LiteRtLmLocalLanguageModel`（P7-C5）実装時、`AiMetrics`の`modelLoadMs`／`firstTokenMs`／`outputTokens`／`tokensPerSecond`／`peakNativeHeapBytes`を`BenchmarkInfo`から実測値へ差し替えること（P7-C3からの申し送り、変更なし）。
2. `LocalAiGateway`のOOM事前ガード（§8.6 #7）が参照する安全マージン（`MEMORY_SAFETY_MARGIN_BYTES=512MB`）は仮値のまま。G4-D実機実測（§11.3）で確定すること（P7-C3からの申し送り、変更なし）。
3. `ModelDownloader.download()`は実装済みだが**呼び出し元が存在しない**（`AppContainer.modelDownloader`はwiring済みだが未使用）。P7-C5で`LiteRtLmLocalLanguageModel`を実装する際、モデル未導入時のフォールバック導線（DL誘導UI）を設計する場合はF97・P7-C6と調整すること。
4. `AiPreferencesImpl`（ADR-0052）・Settings画面（F97・T-SET-*・T-P7DI-*）自体はP7-C6で改めて対応すること（P7-C3からの申し送り、変更なし）。
5. `ModelStorage.installedEntry()`は現在`catalog`（既定`ModelCatalog.ALL`、単一エントリ）の先頭一致で「導入済み」を決める。P7-C6で`AiPreferences.selectedModelId`による複数モデル選択が入る場合、この解決方法を`selectedModelId`ベースへ切り替えることを検討すること（ADR-0053再検討トリガー）。

**証拠ファイル**: `build/agent-logs/p7c4-compile.log`（`:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`成功）・`p7c4-green-ModelStorage.log`（11/11 Green）・`p7c4-green-ModelDownloader.log`（10/10 Green）・`p7c4-green-LocalAiGateway.log`（18/18 Green）・`p7c4-full.log`（`:app:testDebugUnitTest --rerun`全体、tests=528/failures=0/errors=0/skipped=1）・`p7c4-lint.log`（`:app:lintDebug --rerun-tasks`、BUILD SUCCESSFUL・error 0）。

### 14.8 P7-C5完了記録（2026-08-10確定・domain-implementer）

**結論**: `LiteRtLmLocalLanguageModel.generatePlan`（F86本体）を実装し、実機（AVD `actionstarter_test` x86_64/API35）で**実推論が成功することを確認した**（`AiResult.Success`・`schemaValid=true`・`sanityPassed=true`）。`AiMetrics`実測配線（新設`BenchmarkMetricsSource`、ADR-0055）・`AppContainer`統合配線（P7-C4で先行配線済みを確認・無変更）も完了。**ただし、P7-C1が定めた`DEFAULT_MAX_NUM_TOKENS=256`のままでは本番プロンプト一式（system instruction＋既定2-shot few-shot＋data message）が実機でネイティブ`FAILED_PRECONDITION`により失敗することを実測し、`maxNumTokens=1024`への引き上げで解消することを確認した**（ADR-0056）。この値自体はP7-C8実機プローブの確定事項として据え置き、本サイクルでは変更していない。`:app:testDebugUnitTest --rerun`でtests=528/failures=0/errors=0/skipped=1（P7-C4ベースラインと完全一致、既存528件の回帰0件）、`:app:lintDebug --rerun-tasks`でBUILD SUCCESSFUL・error 0（warning 22件は全て既存分）。

#### `LiteRtLmLocalLanguageModel`実装内容

- **Engine**: プロセス内で高々1個。`engineLifecycleMutex`（Mutex）配下で遅延生成し（初回`generatePlan`呼び出し時）、以後は再利用する（R-7・T-GW-16）。`ExperimentalFlags.enableBenchmark = true`をEngine生成直前に設定する（P7-C0発見の一次ソース未記載の挙動、`getBenchmarkInfo()`の前提）。
- **Conversation**: `generatePlan`呼び出しごとに新規生成し`finally`で`close()`する。毎回`systemInstruction`（`PlanPromptBuilder.buildSystemInstruction(locale)`）＋`initialMessages`（`PlanPromptBuilder.buildFewShot(locale)`を`Message.user`/`Message.model`へ変換）のprefaceを`prefillPrefaceOnInit=true`で再prefillする。この設計により「Engineの重みは保持しつつ会話状態は都度リセットする」（KVキャッシュクリア相当）と「retryは新規single-turnセッション」（S-2是正・Gemini G1 CRITICAL #1）の両要求を単一の仕組みで満たした（ADR-0056決定1）。
- **`SamplingPolicy`→`SamplerConfig`のマッピング**: `topK`／`temperature`は`SamplingPolicy`の値をそのまま転記。`topP`／`seed`（`SamplingPolicy`が持たない品質ハーネス§4の値）はPrimary=`topP=1.0,seed=0`、Retry=`topP=0.95,seed=1`で確定（ADR-0056決定2）。`SamplingPolicy.appendConcisenessConstraint=true`のときのみdata message末尾へ固定簡潔化制約文（品質ハーネス§6の逐語文言）を追記する。
- **`AiMetrics`実測配線（ADR-0055）**: 新設`ai/BenchmarkMetricsSource.kt`（`BenchmarkMetricsSource`interface・`InferenceBenchmarkSnapshot`）を`LiteRtLmLocalLanguageModel`が追加実装し、`LocalAiGateway`が`(model as? BenchmarkMetricsSource)?.lastInferenceMetrics()`で任意に読み出す設計とした。§16の凍結`LocalLanguageModel`interfaceは無変更。`modelLoadMs`は`SystemClock.elapsedRealtime()`のwall-clock差分（P7-C0が「`BenchmarkInfo.initTimeInSecond`は数値の意味を断定できない」と指摘済みのため不採用、ADR-0056決定3）、`firstTokenMs`／`outputTokens`／`tokensPerSecond`は`BenchmarkInfo`の対応フィールドから、`peakNativeHeapBytes`はP7-C0 `RamSampler`と同一方式のバックグラウンドサンプラから取得する。
- **`AppContainer`統合配線**: P7-C4時点で`localAiGateway`が`LiteRtLmLocalLanguageModel`を実装として使う配線が**既に完了していた**（`di/AppContainer.kt`の`localAiGateway`プロパティ）ため、本サイクルでの追加変更は不要だった（確認のみ）。AI既定OFF（`AiPreferencesImpl.aiEnabled`既定`false`）は無変更であり、通常フローに影響しない。

#### 実機E2E実測（`app/src/androidTest/java/com/actionstarter/probe/LiteRtLmAdapterE2EProbeTest.kt`、`@Ignore`既定・3メソッド）

正式なT-P7E2E-1〜5（JNI疎通・機内モード・StrictMode・破損モデル・画面回転耐性）は計画書§14.6の記録どおりP7-C7スコープであり、本プローブはこれを代替しない。P7-C5自身の検証として、実装した`generatePlan`本体とGateway統合配線（3段検証パイプライン込み）が実機で動くかを実測した。日本語の合成予定3件（歯科検診／友人の結婚式／チームMTG、PIIなし）で試行。

| 実行 | 設定 | 結果 |
|---|---|---|
| `probeAdapterThroughGateway_defaultCatalog` | `maxNumTokens=256`（P7-C1既定）・本番`ModelCatalogEntry.peakRamBytes`=2,890MB（フルコンテキスト実測値）そのまま | 3件とも`Fallback(OUT_OF_MEMORY_PREVENTED)`。§8.6 #7の主防御がAVDの実際の空きメモリ不足を検知し、**推論を一度も開始せず**安全側に停止した（正しい防御動作。ただし`peakRamBytes`がプロファイル非依存の単一値であるため過大判定というギャップも判明、下記「発見」参照） |
| `probeAdapterThroughGateway_smallContextProfile` | `maxNumTokens=256`のまま・`peakRamBytes`をfixtureで1GiBへ下げOOMガードのみ迂回 | OOMガードは通過したが3件とも`Fallback(UNKNOWN)`、detail=`LiteRtLmJniException: FAILED_PRECONDITION: Chosen prefill work group size exceeds available state entries (73).`（本番プロンプト一式がコンテキスト予算を超過、新規発見） |
| `probeAdapterThroughGateway_widerContextDiagnostic` | `maxNumTokens=1024`・`peakRamBytes`fixture=1.25GiB | **3件とも`AiResult.Success`**（詳細は下記実測値・生成テキスト参照） |

**AiMetrics実測値**（`probeAdapterThroughGateway_widerContextDiagnostic`、3件連続実行）:

| 予定 | modelLoadMs | firstTokenMs | tokensPerSecond | outputTokens | peakNativeHeapBytes | totalMs |
|---|---|---|---|---|---|---|
| 歯科検診（1件目） | 4,073 | 1,878 | 25.73 | 38 | 約536MB | 12,264 |
| 友人の結婚式（2件目） | **0**（Engine再利用） | 1,556 | 27.53 | 31 | 約545MB | 6,358 |
| チームMTG（3件目） | **0**（Engine再利用） | 1,534 | 35.22 | 31 | 約542MB | 5,326 |

2・3件目の`modelLoadMs=0`はEngine再利用（R-7・T-GW-16）が実機で機能している直接証拠。**x86_64エミュレータの数値であり、P7-C0の留保どおりGalaxy A実機の性能を一切示唆しない**（絶対値の確定はP7-C8）。

**実際に生成された`display_text`（Semantic Contextualizationの実測。Basic版`step_title_preparation`="出かける準備をする"〔`features/common/StepTitle.kt`、予定種別に関わらず常に同一固定文〕との比較）**:

| 予定 | `action_type` | `display_text`（実生成） | Basic版との対比 |
|---|---|---|---|
| 歯科検診 | `prepare_items` | 「歯科検診に手順を計らる」 | Basicの汎用文とは異なり予定名に反応しているが、文法がやや不自然（0.6Bモデルの限界が観測された） |
| 友人の結婚式 | `commute` | 「結婚式に参加する」 | 自然な日本語で予定固有（Basicの「出かける準備をする」より具体的）。ただしfew-shot模範の「ご祝儀を準備する」のような文化的踏み込みまでは至っていない |
| チームMTG | `prepare_items` | 「チームMTGの準備」 | 予定名を含むが、`ContentSanityChecker`のコピー閾値（占有率80%）を僅かに下回り（6/8=75%）合格。タイトルとほぼ同一の文言になるリスクの実例 |

3件とも`steps`は1件のみ（few-shot模範が示す3ステップ構成より少ない。既定`SamplingPolicy.Primary`＝実質greedyの保守性か0.6Bモデルの限界かは本実測（n=3）では断定できない）。**総括**: Semantic Contextualizationは表層レベル（予定名を認識した反応）では確認できたが、few-shotが示す「予定の意味を理解した個別具体的な行動」（ご祝儀・切符確認等）の深さには、この少数サンプルでは届いていない。P7-C8の人手評価（品質ハーネス§8）で母数を増やして再評価する必要がある。

#### 発見・申し送り事項（P7-C6/P7-C8向け、ADR-0055・0056に記録済み）

1. **`DEFAULT_MAX_NUM_TOKENS=256`は本番プロンプト一式では実機で機能しない**。`maxNumTokens=1024`への引き上げ、または`shotCount`削減（品質ハーネス§7の0-shot候補）の少なくとも一方が必要。最終値はP7-C8のGalaxy A実測で確定すること。
2. **`ModelCatalogEntry.peakRamBytes`がコンテキストプロファイル非依存の単一値**であるため、§8.6 #7のOOM事前ガードが小コンテキスト・テストプロファイルの実要求量より過大な閾値で判定してしまう（AVDで実測確認）。プロファイル別`peakRamBytes`の要否をP7-C8で検討すること。
3. `MEMORY_SAFETY_MARGIN_BYTES=512MB`・タイムアウト仮20,000ms（§8.6冒頭）はいずれもP7-C3からの申し送りのまま未確定（G4-D実機実測待ち、変更なし）。

#### cleanup

`adb push`したモデルは`app/src/androidTest`の`LiteRtLmAdapterE2EProbeTest`が各テストメソッドの`finally`でアプリ内部ストレージ（`noBackupFilesDir/models/`）から`ModelStorage.delete()`により削除する。実測後に`/data/user/0/com.actionstarter/no_backup/`・`shared_prefs/ai_preferences.xml`をroot adb shellで確認し、**残存なし**を確認済み（`connectedDebugAndroidTest`のテストランナー自体がテスト間でアプリデータをクリアするため、二重に保護されている）。ホスト側`/data/local/tmp/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`（328MB）はアプリのストレージではないため放置してもアプリ動作に影響しないが、`adb shell rm /data/local/tmp/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`で手動削除可能（未実施のまま申し送り）。`AiPreferencesImpl.aiEnabled`はテスト内で`true`へ一時変更後、`finally`で実行前の値（`false`）へ復元済み。

**証拠ファイル**: `build/agent-logs/p7c5-jvm.log`（`:app:testDebugUnitTest --rerun`、tests=528/failures=0/errors=0/skipped=1）・`p7c5-lint.log`（`:app:lintDebug --rerun-tasks`、BUILD SUCCESSFUL・error 0）・`p7c5-e2e.log`（3実行分のLogcat全量＋JUnit結果XML統合）・`p7c5-e2e-result.xml`（最終実行分のJUnit結果、1件・failures=0）・`p7c5-e2e-logcat.log`／`p7c5-e2e-logcat-run2.log`／`p7c5-e2e-logcat-run3.log`（実行別Logcat）。

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

**本表はG1時点（2026-08-10午前）の確認事項でクローズ済み。P7-C2完了後の契約確定ラウンド
（品質ハーネス統合、同日）でのFable 5裁定1〜8＋retry契約確定は§14.4に記録し、
DECISIONS.md ADR-0045〜ADR-0049へ起票した。番号体系を分けたのは、U-番号が基盤計画単独の
G1レビュー起源であるのに対し、裁定1〜8は基盤計画＋品質ハーネスの統合確定という異なる
文脈のラウンドであるため（新規U-番号を追加すると2つの独立したレビューラウンドが
同一連番に混在し追跡性が下がる）。**

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

| ID | 内容 | 影響範囲 | P7-C0実測確定値（2026-08-10・domain-implementer・AVD actionstarter_test x86_64/API35） |
|---|---|---|---|
| **V-1** | AAR 0.15.0 の ABI構成とminSdkの自プロジェクトでの再確認（調査エージェントはarm64-v8a＋x86_64・minSdk 24と実測したが、android-planner自身は未再現） | §8.2・G4-Eの成立可否 | **確定・調査エージェントの実測と完全一致**。AAR展開で再確認: `AndroidManifest.xml`の`minSdkVersion=24`。`jni/arm64-v8a/liblitertlm_jni.so`（21,199,264B）・`jni/x86_64/liblitertlm_jni.so`（25,222,024B）の2ABIのみ同梱、armeabi-v7aなし。実機ログでも`sdkInt=35 supportedAbis=x86_64,arm64-v8a`を確認、AVD上でJNI疎通・実推論とも成功 |
| **V-2** | `Engine` / `Conversation` の**解放API**（`close()` 相当）の有無と正しいライフサイクル | §13 #8のアンロード設計・§8.6 #7 | **確定**。両クラスとも`java.lang.AutoCloseable`実装で`close()`を持つ（バイトコード確認）。実機実測: `close()`後に`engine.createConversation()`を呼ぶと`IllegalStateException: "Engine is not initialized."`を送出（2回とも再現）。`close()`自体は`checkInitialized()`を先頭で呼ぶため**冪等ではない**（未初期化/close済みEngineへの再`close()`も同例外を送出する設計。バイトコード確認。§13 #8のアンロード設計は「1回だけ呼ぶ」規律が必須という追加知見） |
| **V-3** | `Backend.CPU()` がスレッド数等の引数を取るか。ミッドレンジでのbig/LITTLEコア割り当ての制御可否 | §11の測定条件・性能 | **確定（存在・使用可否のみ。性能差比較はP7-C0スコープ外）**。`Backend.CPU(threadCount: Int?, numOfThreads: Int?)`の2引数コンストラクタが存在（`numOfThreads`は`@Deprecated`マーカー付きの旧名、`threadCount`が優先されnull時のみ`numOfThreads`にフォールバックする実装をバイトコードで確認）。`Backend.CPU(threadCount=4)`で実機実行しエラーなく完走。big/LITTLE個別割当のAPIは確認できず（単純なスレッド総数指定のみ） |
| **V-4** | **Qwen3のthinking無効化の具体的な指定方法**と、無効化時の実出力トークン数 | §13 #23・§8.4のトークン予算・R-3 | **確定**。`ConversationConfig.thinkingConfig = ThinkingConfig(enableThinking = false)`の**API設定のみで十分**（`/no_think`等プロンプト側の追加操作は不要と実測で確認）。2回の独立実行とも生出力に`<think`混入なし。実出力トークン数は`BenchmarkInfo.lastDecodeTokenCount=56`（2回とも同一。簡易スキーマ1件・`maxOutputToken=100`条件下） |
| **V-5** | Qwen3の「119言語」およびGemmaの「140言語」主張の**公式一次ソースでの日本語の明示**（Qwen3はブログの言語テーブルで確認できたとの調査報告があるが、android-planner自身は未確認） | §5.2 ①の根拠強度 | **未着手（P7-C0スコープ外）**。§14 P7-C0の①〜⑦（Kotlin API実測）に含まれない別系統の一次ソース文献確認事項のため本サイクルでは対応していない |
| **V-6** | **Gemma 4 が Apache-2.0 へ変更された事実**の一次確認（調査報告は `opensource.googleblog.com` を出典としているが、android-planner自身は未取得）。Gemma 3が遡及的にApache化されていないことも含む | §5.2 ③・Gemma切替時の義務 | **未着手（P7-C0スコープ外）**。同上、別系統の一次ソース文献確認事項のため本サイクルでは対応していない |
| **V-7** | `litert-community` の Qwen3-1.7B のピークRAMとAndroid実測tok/s（**モデルカードにベンチ表がなく未公開**） | §5.3 段2の成立可否 | **未着手（P7-C0スコープ外）**。本サイクルはQwen3-0.6Bのみ実測。Qwen3-1.7Bの実測はP7-C8実機プローブ（§11.3）側の範囲 |
| **V-8** | **`EngineConfig.maxNumTokens` を変えたときのピークRAMの変化**。2.9GBをどこまで下げられるか。**128〜256トークンまで絞った小コンテキスト・テストプロファイルでピークが1GB級まで下がるかを含む**（Gemini G1 CRITICAL #4により**P7-C0の必須測定項目へ格上げ**） | §5.3の段境界・S-1・R-4・**R-11・§11.2・§12.8（E3小コンテキストプロファイルの成立可否）** | **「1GB級まで下がるか」はYESで確定・「128→256の増分」は本実測だけでは確定不可（正直な未決着）**。ctx128/ctx256とも`ActivityManager.getProcessMemoryInfo().totalPss`ピークは約700〜775MB（2回の独立実行: 774,245KB/724,384KB）で、§11.2が想定した「1GB級」を下回り小コンテキスト・テストプロファイルがAVD RAM4096MBで安定成立することを確認。ただし**2回とも`ctx256`の方が`ctx128`よりピークRAMが低い**という直感に反する結果が再現し、同一プロセス内で128→256の順に逐次実行する本プローブの設計では実行順序効果（2回目はOSページキャッシュ/メモリアロケータが温まっている）との交絡を排除できていない。**128→256の真の増分を定量化するには実行順序を入れ替えた追加実測が必要**（P7-C1以降への申し送り事項） |

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
