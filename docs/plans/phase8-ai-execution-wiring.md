# Phase 8 実装計画書ドラフト — 実行画面へのAI実配線（Local AI Planning / Semantic Contextualization）

> 対象仕様: §13・§14・§15・§19・§20・§21・§27・§28・§34・§40・§43・§44・§57・§58・§60・§72
> 前提基盤: Phase 7（`ai/` 単体完結）／phase7-quality-harness（非同期UX・3段検証・retry・5系統フォールバック）
> 種別: **実装済み（C1〜C3・JVM/Robolectricスコープ）**。ダブルレビュー（Opus/Gemini）を経てRed→Greenへ進み、JVM全件Green（既存613維持＋新規26件、回帰0）を確認済み。C4（A54実機）が残る。
> **承認状態**: Fable 5 裁定 B1〜B7 済み＋Gemini G1（`gemini-3.5-flash`）CRITICAL 3件（①overlay複数step置換バグ／②非同期stale-write／④既定モデル`installedEntry`未改修）反映済み（2026-08-10）→ **G1 通過**。C1〜C3完了（2026-08-10、domain-implementer、詳細は§11.1）。**C1でopusレビュー要の実装時逸脱（LinkageError防御、§11.1参照）を発見・報告済み**。次段: opusレビュー → C4 実機（§11・§10）。

---

## §0. 結論ファースト

**Phase 8 は「Basic が生成した ExecutionPlan の各ステップの表示文言（`ExecutionStep.title`）だけを、AI 有効時にバックグラウンド推論の結果で差し替える」薄い文脈化レイヤー（`ai/LocalAiPlanContextualizer`）を新設し、PlanReview 画面に非同期配線する。** 数値・構造（時刻・所要分・優先度・skippable・ステップ種別・件数・順序・StartOfTransition）は一切変えない=Kotlin（`BasicPlanningEngine`）専任のまま（§13）。AI が OFF／未DL／検証失敗／生成失敗／OOM／タイムアウト／端末非対応のいずれ（=`LocalAiGateway` の全 `AiFallbackReason`）でも、`title` は空のまま残り、既存の `resolveStepTitle(semanticId)` フォールバックにより Basic 固定文言が表示され続ける（§19「Enhancement であって SPOF にしない」）。

**核心となる構造的発見（既存コードが Phase 8 を待っていた）**:
`ExecutionScreen`（L133）と `PlanReviewScreen`（L211）は既に
`val title = step.title.ifBlank { resolveStepTitle(step.semanticId) }`
を実装済みである。したがって **AI 文脈化は「`ExecutionStep.title` に AI の `display_text` を書き込む」だけで完了し、画面（Compose）側は 1 行も変更不要**。`title` が空なら自動的に Basic 固定文言へ縮退する=**per-step 縮退が構造的に保証**されている。

**推奨アーキテクチャ（PlanningEngine 抽象の AI 統合方式・§40/§43/§44 の解の1つ）**:
`PlanningEngine.createPlan()`（`suspend fun … : ExecutionPlan`、単一戻り値）を **AI で差し替えない**。代わりに `ai/` 配下へ純粋なオーバーレイ関数を持つ `LocalAiPlanContextualizer`（`suspend fun contextualize(basePlan, context): ExecutionPlan`）を置き、Basic が作った plan の上に **display_text だけを重ねる decorator** とする。理由は §3 で詳述するが、要約すると (a) 非同期 UX「Basic 即時→AI 後差し替え」は単一戻り値の `createPlan` では表現できない（中間の Basic 結果を先に emit する必要がある）、(b) §13 不変・§19 非SPOF は「Basic 構造を唯一の権威とし、AI は純テキスト重ね書きに限定する」ことで**構造的に**保証できる（2つの PlanningEngine 実装が各々 plan を組み立てて構造が乖離する危険を排除）ためである。

**G1 レビュー反映（2026-08-10）**: 初稿に対する Gemini G1（`gemini-3.5-flash`）CRITICAL 指摘3件を全採用済み。(a) §7.2 の `overlay` は「type ごとに文言1件」の連想配列で base 全 step を一括置換する設計（同一 type の step が複数あると全 step が同一文言で上書きされる欠陥）を廃し、`base.steps` を出現順に走査しながら AI 同種 step 文言を1件ずつ消費する1対1マッピングへ変更した。(b) §4 の非同期合成は「Basic→AI 命令的上書き」（`_uiState.value` への直接全置換の連鎖。既存 `applyPlan()` が実際にこの全置換パターンであることを確認済みで、travel と AI の到達順序によって結果が変わる stale-write を内包していた）を廃し、`StateFlow.combine` によるSSOT宣言的合成へ変更した（到達順序に依存しない）。(c) 既定モデル解決（`ModelStorageImpl.installedEntry()`）が `AiPreferences.selectedModelId`（P7-C6で確定済みのGemma4既定）を無視し catalog 順 first-match のまま（Qwen3-0.6B が先頭）だった不整合を、Phase 8 C1 の担当範囲として §6.4 で解消することを確定した。詳細は各節・§13（B1〜B7裁定）参照。

---

## §1. スコープ（含む / 含まない）

### 含む
1. `ai/LocalAiPlanContextualizer`（新設・headless）: `LocalAiGateway.generatePlan` を呼び、Success 時に `AIPlanResponse.steps[].displayText` を `basePlan` の対応ステップの `title` へ overlay。Fallback 時は `basePlan` を無変更で返す。
2. `PlanActionType → ExecutionStepType` の決定的マッパ（overlay のマッチング基盤）。
3. `PlanReviewViewModel` への非同期 AI フェーズ追加。「最新 base（travel 解決含む）× 最新 AI 応答キャッシュ」を `StateFlow.combine` で宣言的合成する SSOT 設計（§4、Gemini G1 CRITICAL②反映。命令的順次上書きは行わない）。
4. `PlanReviewUiState` へ観測用 `aiState`（Idle/InProgress/Applied/FellBack）追加。
5. `AppContainer` の DI 配線（`localAiPlanContextualizer` を `by lazy` で生成し ViewModel Factory へ注入）。
6. C4 で **A54 実機（Gemma4-E2B）最終確認**＋**G4-E（機内モード無通信成立）**（§10）。
7. `ai/model/ModelStorage.kt`（`ModelStorageImpl`）改修: `AiPreferences` を注入し `installedEntry()` が `selectedModelId`（設定済み＋ファイル実在）をcatalog順走査より優先評価するようにする（§6.4、Gemini G1 CRITICAL④／B4確定）。

### 含まない（明示）
- **`BasicPlanningEngine`・`LocalAiGateway`・`ai/schema`・`ai/prompt`・`ai/adapter` の変更**（Phase 7 で確定・凍結。契約に接地して消費するのみ）。**`ai/model` は原則凍結を維持するが、`ModelStorage.kt`（`ModelStorageImpl.installedEntry()`）のみ Gemini G1 CRITICAL④／B4確定（§6.4）により例外的に改修対象とする**。`ai/model` の他ファイル（`ModelCatalog.kt` のエントリ定義・`ModelVerifier.kt`・`ModelDownloader.kt`等）は引き続き凍結。
- **Compose 画面（`ExecutionScreen`/`PlanReviewScreen`）のフォールバック描画ロジック変更**（既存の `title.ifBlank{…}` をそのまま使う。§0）。任意の「文脈化中」ヒント表示のみ PlanReviewScreen への最小追加を検討（§5・Fable 確認）。
- **`ExecutionViewModel` の変更**（PlanReview で確定した plan を `confirmedPlan` 経由で受け取り、既存の `plan.steps[i].title` 描画で AI 文言が透過的に伝播するため。§6 の推奨で Execution 側推論は行わない）。
- **`features/settings`・`ActionStarterNavHost` の変更**（並走中の別サイクル(C6)所有。AI 有効化トグル・モデルDL・`selectedModelId` は settings の責務。Phase 8 は `aiEnabled` を **gateway 経由で透過的に**消費する）。
- **`generateRecovery` の AI 化**（Phase 9・§73）。
- **Analytics 実装・AiMetrics の PSS 化**（Phase 10/12・ADR-0049。§13 で除外を確認）。

---

## §2. 責務分界の維持（§13 厳守）— AI が触れるのは `display_text` のみ

| 関心事 | 担当 | Phase 8 での扱い |
|---|---|---|
| 時刻演算（StartOfTransition/departureTime/estimatedArrival）| Kotlin `BasicPlanningEngine`（§13） | **不変**。overlay は plan の時刻フィールドに一切触れない |
| 移動時間・travel ステップ有無 | Kotlin（`travelEstimate`）| **不変** |
| ステップ種別（TRANSITION/PREPARATION/DEPARTURE/TRAVEL）| Kotlin（`semanticId`/`type`）| **不変**。AI の `action_type` は**マッチングにのみ**使い、種別決定には使わない |
| 優先度・skippable・estimatedDuration・id・順序・件数 | Kotlin | **不変** |
| **各ステップの表示行動文（`display_text`）** | **Local AI（§14 Meaning→Action）** | **AI 有効時のみ** `ExecutionStep.title` を上書き。例: preparation ステップの Basic 固定文言「持ち物を準備する」→ AI 文脈化「保険証を持って行く」（歯科検診）。空/未マッチなら Basic 文言のまま |

**構造的保証**: overlay は `step.copy(title = aiText)` と `basePlan.copy(steps = …)` のみを行う。`ExecutionStep.copy` は init（`estimatedDuration` 非負検証）を再実行するが `title` 変更は安全。`ExecutionPlan` コンストラクタは `scheduledStart` 昇順へ再正規化するが、overlay は `scheduledStart` を変えないため**順序も件数も不変**。したがって「AI 有無で数値・構造が変わらない」ことは**テストで機械的に固定できる不変条件**（§8 T-P8-14。同一 `ExecutionStepType` の step が base 内に複数存在する場合も本不変条件は成立し、かつ各 step が正しく異なる AI 文言へ1対1対応することを T-P8-25 で別途検証する。Gemini G1 CRITICAL①反映・§7.2）。

**§15 逸脱の二重防御**: `display_text` に数字・時刻・URL・`@` が混入した場合、`ContentSanityChecker`（gateway 内②内容 sanity）が `SCHEMA_INVALID` で弾き Basic へ落とす。overlay は sanity 通過済みテキストのみを受け取る。

---

## §3. アーキテクチャ — `LocalAiPlanContextualizer`（§40/§43/§44 との関係）

### 3.1 なぜ「PlanningEngine 差し替え」ではなく「overlay decorator」か（推奨1つ）

| 選択肢 | 内容 | 判定 |
|---|---|---|
| **A. `LocalAIPlanningEngine : PlanningEngine` を DI スロットへ差し替え** | `createPlan(context)` 内で `basic.createPlan`→AI→最終 plan を返す | **却下**。単一戻り値のため AI 完了まで画面を出せない=非同期UX（§8.7・phase7 §18.2「AI 結果を待ってから画面を出す設計は採れない」）に反する。また AI が独自に plan を組む余地が残ると §13 構造乖離リスク |
| **B.（推奨）overlay contextualizer** | `BasicPlanningEngine` は不変で唯一の構造権威。`ai/LocalAiPlanContextualizer.contextualize(basePlan, context)` が **display_text のみ**を重ねる。ViewModel が「Basic 即時 emit → AI overlay 後 emit」の二段で使う | **採用**。§13 不変・§19 非SPOF を構造保証。非同期 UX に自然に載る。画面変更不要 |
| C. `PlanningEngine` を `Flow<ExecutionPlan>` 返しへ契約変更 | Basic→AI を1メソッドで段階 emit | 却下。§44 契約と `BasicPlanningEngine`・既存全テストを破壊。過大 |

**§43/§44 との整合の明示**: 仕様 §43 は `Planning/LocalAIPlanningEngine` を、§44 は `PlanningEngine.createPlan(): ExecutionPlan` を掲げる。本推奨はこの「Meaning→Action の表示文生成」という**役割**を `LocalAiPlanContextualizer` が担うが、以下2点で §43 の字義から意図的に逸脱する（Fable 確認 §13-B1）:
- **配置は `planning/` ではなく `ai/`**（phase7 §18.1 R-10 の推奨に従う）。`planning/` 配下に AI 参照クラスを置くと隔離ガード T-BPE-28 が必ず Red になる。`ai/` 配置なら Basic 経路の純粋性を保てる。
- **`PlanningEngine.createPlan` は実装しない**（overlay 専用シグネチャ）。理由は上表 A の非同期・不変性。

### 3.2 配置と依存方向（隔離ガード整合）

- 新設ファイル `app/src/main/java/com/actionstarter/ai/LocalAiPlanContextualizer.kt`。
- 依存: `ai.LocalAiGateway`/`ai.AiResult`/`ai.AIPlanResponse`/`ai.schema.PlanActionType`（同一 `ai/`）＋ `domain.model.ExecutionPlan`/`ExecutionStep`/`ExecutionStepType`/`PlanningContext`（`ai/`→`domain/` は既存 gateway と同方向で許可）。
- **T-AIISO-5（`ai/`→`features/` 禁止）に抵触しない**（features を参照しない headless 部品）。
- **T-AIISO-6（`ai/` のネットワーク禁止・`ModelDownloader` 以外）へ自動的に服従**（新ファイルは `ai/` 再帰走査対象。誤って通信ラッパを import すれば既存ガードが Red=無償の防御）。
- ViewModel（`features/`）→ `ai/` の参照は許可（`features/settings` が既に `ai/` を import 済み=前例確立）。

### 3.3 overlay 契約（§7 に詳細）

```text
LocalAiPlanContextualizer(gateway)
  suspend contextualize(basePlan: ExecutionPlan, context: PlanningContext): ContextualizationResult
    = when (gateway.generatePlan(context)) {
        is Success  -> Applied(overlay(basePlan, value), value, metrics)   // display_text 重ね。valueは生AIPlanResponseも保持(§4 再overlay用)
        is Fallback -> Unchanged(basePlan, reason)                    // Basic のまま（沈黙縮退）
      }
```
`overlay` は純粋関数・§7.2で公開（`PlanReviewViewModel` の `combine` からも直接呼ばれる、§4）。`ContextualizationResult` は `Applied(plan, response, metrics)` / `Unchanged(plan, reason)`。**`reason` は捨てず保持**（観測性＝§9 サイレント障害防止・将来 Analytics）。

---

## §4. 非同期 UX 設計 — `StateFlow.combine` による宣言的合成（Gemini G1 CRITICAL②反映）

**設計変更の理由（Gemini G1 CRITICAL②・2026-08-10 全採用）**: 初稿は「Basic 即時表示 → travel 解決で再構築 → AI 解決で overlay」を三者とも `_uiState.value = PlanReviewUiState(...)`（既存 `PlanReviewViewModel.applyPlan()` と同型の**全置換**。`.copy()` による部分更新ではなく、呼ぶたびに新しい `PlanReviewUiState` を丸ごと構築して代入する実装であることを実コード（`applyPlan()`）で確認済み）で順次上書きする命令的設計だった。この全置換パターンのまま AI 差し替えを追加すると、**travel 解決が AI 適用より後に完了した場合、直前に書き込まれた `aiState=APPLIED`／overlay 済み `plan` を travel 側の全置換が無条件に上書きし、AI 文脈化が消える**（stale-write／lost-update）。逆順（AI が travel より後に解決）でも、AI 側が「travel 解決前の古い base」を閉じ込めて overlay した結果を書き込めば、travel の時刻更新が消える。両者とも到達順序に依存する非決定的バグであり、単体テストでは再現しにくい。

**解決方針**: 「どちらが最後に書いたか」に依存する共有可変状態への直接代入をやめ、**2本の `StateFlow` の最新値を毎回読み直して合成する** `combine` へ置き換える。到達順序に関わらず、両方の最新値から常に同じ結果が再計算されるため、順序依存のバグが構造的に発生しない。

### 4.1 状態の分解

```kotlin
// PlanReviewViewModel 内部状態（公開する uiState はこの2本から導出する。書き込み口はこの2つのみ）
private val latestBase: MutableStateFlow<ExecutionPlan?> = MutableStateFlow(null)
private val latestAiResponse: MutableStateFlow<AiResponseCache?> = MutableStateFlow(null)

/** [eventId] は書き込み時点でstale-writeガード済み（§4.3-1）。 */
private data class AiResponseCache(val eventId: UUID, val result: ContextualizationResult)
```

- `latestBase`: Basic（travel 未解決）→ travel 解決後の Basic、の順で**同じ StateFlow へ書き込む**（値が変わるたびに `combine` が再計算される）。`ExecutionPlan.event`（既存フィールド、§7 付録で確定済み）を持つため、追加のイベントID用ラッパは不要。
- `latestAiResponse`: AI 推論が完了した時点の `ContextualizationResult`（§7.1）を event.id と共に保持。**別イベント選択の瞬間に同期的に `null` へリセットする**（§4.3-2）。
- **AI は latency の長いポーリング**（Galaxy A で TTFT 10–20s 有り得る。phase7 §7）。したがって travel 取得と**並行に早期起動**する（travel 待ちの後に直列起動しない、この方針は初稿から不変）。

### 4.2 合成（SSOT・唯一の書き出し口）

```kotlin
val uiState: StateFlow<PlanReviewUiState> =
    combine(latestBase, latestAiResponse) { base, aiCache ->
        when {
            base == null -> PlanReviewUiState()
            aiPlanContextualizer == null -> PlanReviewUiState(plan = base, aiState = AiContextualizationState.IDLE) // T-P8-13後方互換
            aiCache == null || aiCache.eventId != base.event.id ->
                PlanReviewUiState(plan = base, aiState = AiContextualizationState.IN_PROGRESS)
            else -> when (val result = aiCache.result) {
                is ContextualizationResult.Applied ->
                    PlanReviewUiState(plan = overlay(base, result.response), aiState = AiContextualizationState.APPLIED)
                is ContextualizationResult.Unchanged ->
                    PlanReviewUiState(plan = base, aiState = AiContextualizationState.FELL_BACK(result.reason))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlanReviewUiState())
```

（本宣言は C1 で scaffold・C3 で実装。上記は合成の形を示す設計スケッチであり、細部の exhaustiveness・null 分岐は C1/C3 で確定する。）

```text
[イベント選択]（collectLatest、旧イベントの全作業を構造的にキャンセル。§4.3）
   │
   ├─ latestAiResponse.value = null                         # 別イベント選択でリセット（4.3-2）
   ├─ base0 = planningEngine.createPlan(travel=null)
   ├─ latestBase.value = base0                               # combine 即再計算 → Basic 表示
   │
   ├─(並行)─ launch { AI 推論(長ポール) → event.id一致なら latestAiResponse.value = AiResponseCache(...) }
   │
   └─ travel = fetchTravelEstimate(event)
        └─ travel != null なら base1 = createPlan(travel) → latestBase.value = base1   # combine 即再計算

combine(latestBase, latestAiResponse) { base, aiCache -> … }   # 唯一の書き出し口。到達順序に関わらず
                                                                  # 常に「最新base × 最新aiCache」を合成
```

- `combine` は `latestBase` と `latestAiResponse` のどちらが更新されても再実行される。**travel 解決が AI 適用より後でも先でも、最終的な `uiState.value` は同じ**（両方揃った時点の最新値だけを見るため。順序非依存＝§8 T-P8-26）。overlay は純粋関数（§7.2）であり、同じ `(base, response)` から常に同じ結果を返すため、`combine` が何度再計算しても結果は安定する（§8 T-P8-23 冪等性がこの前提を保証）。
- **命令的順次上書き禁止**: `_uiState.value = ...` の直接代入は行わない。すべての更新は `latestBase.value = …` または `latestAiResponse.value = …` への代入のみとし、実際に画面へ出す `uiState` は上記 `combine` の1箇所のみが生成する（書き込み口を2つ、読み出し口を1つに強制する構造）。

### 4.3 stale-write の構造的防御（二重）

1. **書き込みゲート**: AI 推論完了時、`sharedPlanViewModel.selectedEvent.value?.id == event.id` を確認してから `latestAiResponse.value = AiResponseCache(event.id, result)` を行う（既存 `fetchTravelEstimate` と同じ防御。不一致なら書き込まない＝§8 T-P8-19）。
2. **別イベント選択時の同期リセット**: 新しいイベントが選択された瞬間（`collectLatest` ブロックの先頭、AI 推論を起動する**前**）に `latestAiResponse.value = null` を同期的に行う。これにより、万一 (1) のゲートをすり抜けた古い応答があっても、`combine` は次の再計算で「最新の `latestBase`（新イベント）」×「`null`（リセット済み）」を見るため、古い AI 文言が新イベントの画面に混入する窓が構造的に閉じる。

**キャンセルとの統合**: `sharedPlanViewModel.selectedEvent.collect { … }` を **`collectLatest { … }`** へ変更する（Kotlin Flow 標準）。新しいイベントが選択されると、直前のイベントの collect ブロック全体（Basic 構築・travel 取得・AI 推論の `launch` 子コルーチンを含む）が構造化並行性により自動キャンセルされる。これにより「別イベント選択で旧イベントの AI 推論が宙に浮いたまま走り続ける」ことがなく、`CancellationException` は握り潰さず再送出する（gateway `invokeModel` の既定動作に準拠。§8.7-3・T-P8-22）。AI 推論の `launch` を `join()` するなどして `collectLatest` イテレーションの寿命を AI 完了まで保つことで、次イベント到達時に確実にキャンセル対象へ含める。

### 4.4 「差し替え中 / 失敗」の状態表現

- `aiState: AiContextualizationState { IDLE, IN_PROGRESS, APPLIED, FELL_BACK }` を `PlanReviewUiState` に持つ。**主目的はテスト観測性**（Basic→AI の遷移をアサート可能にする）と将来 Analytics。`combine` の再計算のたびに導出される値であり、ViewModel 内の別変数として二重管理しない。
- **UI 表現（§5 と一体）**: 失敗（FELL_BACK）は**画面上は無表現**=Basic 文言が残るだけ（§8.7-4・§19「AI が来ないことをエラーとして見せない」）。IN_PROGRESS は PlanReview に**控えめな非ブロッキングヒント**（例: 見出し下の小さな「あなたの予定に合わせて調整中…」キャプション、Applied/FellBack で消える）を**任意**で出す。Execution 画面（§28 ONE ACTION）には AI 由来の chrome を一切出さない。
- ヒントの具体的視覚は INFORMATIONAL（§13 B5 で要否確認）。**正しさに必要なのは `aiState` 遷移と沈黙縮退のみ**で、ヒントは UX 磨きに属す。

---

## §5. 有効化条件と 5 系統フォールバックの UI 表現

### 5.1 有効化条件は「ViewModel では判定しない」（単一責務の集約）

**ViewModel／contextualizer は `aiEnabled`・DL 状態・端末 tier を一切自前判定せず、無条件に `gateway.generatePlan` を呼ぶ。** gateway が Success か Fallback を返す。これにより有効化ロジックを1箇所（gateway、T-GW-1〜20 で既に回帰ロック済み）へ集約し、UI 層での条件重複を排除する（§19 の構造保証）。

**コスト面の安全性（実測接地）**: `LocalAiGateway.generatePlan` は AI OFF を**最初に**判定して即 `Fallback(AI_DISABLED)` を返す（L155-157、モデルロード無し）。未DL も `checkInstalledModel` で推論前に即 `MODEL_NOT_INSTALLED`。**実推論が走るのは ON+DL済+検証通過時のみ**。よって無条件呼び出しに無駄コストは無い。

### 5.2 5 系統（gateway 全 `AiFallbackReason`）→ UI 表現の対応

| gateway Fallback reason | 発生源 | contextualizer | UI 表現 |
|---|---|---|---|
| `AI_DISABLED`(OFF) | §8.6#10 | Unchanged | Basic 固定文言（変化ゼロ・§19） |
| `MODEL_NOT_INSTALLED`(未DL) | §8.6#11 | Unchanged | 同上 |
| `MODEL_CORRUPTED`(検証失敗) | §8.6#12 | Unchanged | 同上 |
| `SCHEMA_INVALID`(生成/検証失敗・retry後) | §8.6#9/§20 | Unchanged | 同上 |
| `TIMEOUT`(生成失敗) | §8.6#8 | Unchanged | 同上 |
| `OUT_OF_MEMORY_PREVENTED`/`OUT_OF_MEMORY`(OOM) | §8.6#7/#13 | Unchanged | 同上 |
| `UNSUPPORTED_DEVICE`/`UNSUPPORTED_ABI`(端末非対応) | §8.6#1/#2 | Unchanged | 同上 |
| `MODEL_LOAD_FAILED`/`UNKNOWN` | §8.6#6/未分類 | Unchanged | 同上 |

**全系統の UI 表現は「Basic 固定文言のまま・エラーバナー無し」で統一**。§95 の劣化バナー（exact alarm・通知権限）は**中核機能喪失**の告知であり、AI 失敗（=Enhancement 不達）とは意味が異なるため、**AI 失敗専用バナーは作らない**（§8.7-4）。差異は `aiState=FELL_BACK(reason)` に**観測値としてのみ**残し、UI へは出さない。

---

## §6. 接続点と DI

### 6.1 PlanReviewViewModel（主・唯一の推論サイト）

- 既存 `init` の `sharedPlanViewModel.selectedEvent.collect { event -> applyPlan(null); fetchTravelEstimate; applyPlan(travel) }` を、**`collectLatest` へ変更した上で** `latestBase`/`latestAiResponse` への書き込みへ置き換える（旧: `_uiState.value` への直接全置換 → 新: 2本の `StateFlow` 経由、§4 の `combine` が唯一の合成点。Gemini G1 CRITICAL②反映）。`buildPlanningContext(event, travelEstimate)` は既存を再利用。
- 新引数 `aiPlanContextualizer: LocalAiPlanContextualizer? = null`（**末尾・既定 null**、ADR-0028 と同型の後方互換パターン=既存 `PlanReviewViewModelTest` を壊さない）。null の間は AI フェーズを skip（Basic のみ・`uiState.aiState` は常に IDLE、§4.2 combine 分岐）。

### 6.2 ExecutionViewModel（レンダラーのまま・無改修を推奨）

- **推奨: Execution 側で AI を呼ばない。** `workingPlan = confirmedPlan.value` の `plan.steps[i].title` が（PlanReview で確定済みなら）AI 文言を保持しており、既存描画で透過表示される。
- 根拠3点: (i) Execution は `PlanningContext` を持たない（gateway 呼び出しに必要な locale/zone/transportMode 等が無い）。(ii) ONE ACTION 画面での実行中テキスト差し替えは体験を乱す。(iii) プロセス死時は `SharedPlanViewModel.confirmedPlan` が失われ T-NAV-4 ガードで eventSelection へ戻る=Execution が AI を組み直す必要が構造的に無い。
- **タスク指示「両 VM が gateway を呼ぶ」との差異を Fable 確認 §13-B2 に明記**。Execution 側も必要なら、`PlanningContext`（または overlay 済み plan）を `SharedPlanViewModel` 経由で運ぶ代替案を併記する。

### 6.3 AppContainer

```kotlin
val localAiPlanContextualizer: LocalAiPlanContextualizer by lazy {
    LocalAiPlanContextualizer(localAiGateway)          // 既存 localAiGateway(by lazy) を再利用
}
// createViewModelFactory 内 PlanReviewViewModel initializer へ aiPlanContextualizer = localAiPlanContextualizer を追加
```
`by lazy` は他 AI プロパティと同じく R-7（起動を重くしない）。`localAiGateway` は既存の単一 lazy インスタンスを共有（`inferenceMutex` による直列化を1本に保つ）。

### 6.4 `ModelStorageImpl` の既定モデル解決改修（C1・Gemini G1 CRITICAL④／B4確定）

**問題（Gemini G1 CRITICAL④、C6完了報告で確定）**: `AiPreferences.DEFAULT_SELECTED_MODEL_ID`（`AiPreferences.kt`）は P7-C6 で `ModelCatalog.GEMMA_4_E2B_IT.id` へ確定済みだが、`ModelStorageImpl.installedEntry()`（`ai/model/ModelStorage.kt`、実装確認済み）は依然として

```kotlin
override fun installedEntry(): ModelCatalogEntry? = catalog.firstOrNull { entry -> finalFile(entry).isFile }
```

という**catalog順走査の先頭一致**のみで「導入済みモデル」を決めており、`preferences.selectedModelId` を一切参照しない。`ModelCatalog.ALL`（`ModelCatalog.kt`、実装確認済み）は `listOf(QWEN3_0_6B_INT4_BLOCK32, QWEN3_1_7B_INT4_BLOCK32, GEMMA_4_E2B_IT)` の順で **Qwen3-0.6B が先頭**であり、同ファイルの KDoc は「Qwen3-0.6B を先頭に置く限り本番の既定選択は変わらない」という P7-C8 時点（Settings 未実装で単一モデルしか実質DLできなかった時期）の設計意図を明記している。**P7-C6 で Settings が実装され複数モデルをDL可能になった今、この前提は崩れている**——端末に Qwen（旧DL分の残骸）と Gemma4 の両方の `finalFile` が存在する状態（A54 実機での既存DL履歴、または比較検証で複数DL済みの端末）では、`selectedModelId=gemma-4-e2b-it` であっても `installedEntry()` は catalog 先頭の Qwen を返してしまい、**「既定モデル=Gemma4」というユーザー確定事項がAI実行時に反映されない**。Phase 8 の A54 実機検証（§10）はこの既定モデルが正しくロードされることが前提のため、この不整合は検証結果の有効性そのものを損なう。

**改修（C1 担当・§11）**: `ModelStorageImpl` へ `AiPreferences` を注入し、`installedEntry()` を「`selectedModelId` が設定済みかつそのファイルが実在する場合は catalog 順走査より最優先で採用し、それ以外の場合のみ既存の catalog 順 first-match へフォールバックする」よう改修する。

```kotlin
class ModelStorageImpl(
    private val context: Context,
    private val catalog: List<ModelCatalogEntry> = ModelCatalog.ALL,
    private val preferences: AiPreferences? = null   // 末尾・既定null（既存呼び出しを壊さない後方互換パターン）
) : ModelStorage {
    override fun installedEntry(): ModelCatalogEntry? {
        val selected = preferences?.selectedModelId?.let { id -> catalog.firstOrNull { it.id == id } }
        if (selected != null && finalFile(selected).isFile) return selected
        return catalog.firstOrNull { entry -> finalFile(entry).isFile }   // 既存のcatalog順fallback（変更なし）
    }
    // installedModelPath()等、他メンバは無改修
}
```

- **`AppContainer` 配線**: `private val modelStorage: ModelStorage by lazy { ModelStorageImpl(context) }`（既存実装確認済み）を `ModelStorageImpl(context, preferences = aiPreferences)` へ1行変更する。`aiPreferences`（`AppContainer` 内で既に eager 初期化済み、`AiPreferencesImpl`）を再利用するだけであり新規インスタンスは作らない。`modelStorage` は `by lazy`（初回アクセス時にのみ評価）のため、クラス内の宣言順（`aiPreferences` は `modelStorage` より後方に宣言されている）に関わらず、コンストラクタ完了後に初めて評価される `modelStorage.value` の時点では `aiPreferences` は必ず初期化済みであり安全（Kotlin `by lazy` の遅延評価による。順序入れ替えは不要）。
- **catalog順fallbackが働く条件（承認済み設計・B4）**: `selectedModelId` が未設定（初回起動でも `AiPreferences.DEFAULT_SELECTED_MODEL_ID` が返るため実質的に「未設定」は稀）、または `selectedModelId` に対応する `ModelCatalogEntry` の `finalFile` が未DL、のいずれかの場合のみ、catalog順 first-match（＝現状の全挙動）へフォールバックする。これにより「選択モデル未DLでも、何か導入済みのモデルがあればそれを使う」という既存のグレースフルデグレードは維持される（AI機能を無用に `MODEL_NOT_INSTALLED` へ倒さない）。

**既存 `ModelStorageTest` への影響（承認済み変更・期待値更新を明記）**: `preferences` は末尾・既定 `null` のため、`preferences` を渡さない既存の全ケース（`ModelStorageImpl(context())` 等、`ModelStorageTest.kt` 実装確認済み）は動作不変で green を維持する。ただし C1 は本改修の正しさを証明する新規ケース（§8 T-P8-24: Qwen・Gemma 両方の `finalFile` が存在し `selectedModelId=gemma-4-e2b-it` の状況で `installedEntry()` が Gemma を返すことの確認）を追加する必要があり、これは `ModelCatalog.kt` の `ALL` に付与された既存 KDoc（「Qwen3-0.6B を先頭に置く限り本番の既定選択は変わらない」という P7-C8 時点の設計前提の記述）と矛盾するようになる。**この KDoc コメントの更新も C1 の改修範囲に含める**（実装と乖離した説明コメントを残さない。catalog順fallbackは selectedModel 未設定/未DL時のみに縮小した旨へ書き換える）。

**フットプリントへの反映**: §12「変更」に `ai/model/ModelStorage.kt` を追加（`ai/model` は原則凍結だが本ファイルのみ例外、§1）。

---

## §7. 契約

### 7.1 `LocalAiPlanContextualizer`

```kotlin
class LocalAiPlanContextualizer(private val gateway: LocalAiGateway) {
    suspend fun contextualize(basePlan: ExecutionPlan, context: PlanningContext): ContextualizationResult =
        when (val r = gateway.generatePlan(context)) {
            is AiResult.Success  -> ContextualizationResult.Applied(overlay(basePlan, r.value), r.value, r.metrics)
            is AiResult.Fallback -> ContextualizationResult.Unchanged(basePlan, r.reason)
        }
}
sealed interface ContextualizationResult {
    /**
     * [plan]は呼び出し時点の[basePlan]へのoverlay結果（単発利用向け）。[response]はoverlay前の
     * 生AI応答——呼び出し側（PlanReviewViewModel、§4）がtravel解決等でbaseが後から変わった際に
     * [overlay]（§7.2、公開関数）へ[response]を渡して**再推論なしで**再overlayするために保持する
     * （Gemini G1 CRITICAL②反映・stale-write構造防御の一部）。
     */
    data class Applied(val plan: ExecutionPlan, val response: AIPlanResponse, val metrics: AiMetrics) : ContextualizationResult
    data class Unchanged(val plan: ExecutionPlan, val reason: AiFallbackReason) : ContextualizationResult
}
```
- 例外は投げない（gateway が全 Throwable を Fallback へ写像済み。`CancellationException` のみ再送出＝gateway 準拠）。

### 7.2 `overlay`（純粋関数・§13 不変の実体）— Gemini G1 CRITICAL①反映（2026-08-10）

**旧設計の欠陥（Gemini G1 CRITICAL①）**: `textByType[s.type]` は `ExecutionStepType` 1つにつき文言1件を保持する連想配列であり、`base.steps.map` 適用時に**同一 type の base step 全件へ同じ文言を配ってしまう**。`ExecutionStepType` ごとに base step が高々1件（§7.3）である限り実害はないが、関数自体の契約としては「同一 type の base step が複数存在すると全 step が同一 AI 文言で上書きされる」という誤った振る舞いを内包しており、Basic 側の将来拡張（例: PREPARATION の複数ステップ分割）やテスト用 fixture で容易に露呈する。

**新設計（インデックス/リスト消費型・1対1マッピング）**: type ごとの連想配列（1文言のみ保持）を、type ごとの**キュー（出現順リスト）**へ変更する。`base.steps` を先頭から走査し、各 step の type に対応するキューから**1件だけ**文言を取り出して（消費して）割り当てる。同じ文言が2つ以上の base step に配られることは構造的に起きない。

```text
overlay(base, ai):
  queueByType = {}                                 # ExecutionStepType -> Queue<display_text>（出現順）
  for aiStep in ai.steps:                          # AI 出力順を尊重
      t = MAP[aiStep.actionType]                   # 未対応(ARRIVE/未知)は null → skip
      if t != null: queueByType.getOrPut(t, ::Queue).addLast(aiStep.displayText)
  newSteps = base.steps.map { s ->                 # base 出現順に1件ずつ消費（1対1）
      queueByType[s.type]?.removeFirstOrNull()?.let { s.copy(title = it) } ?: s   # 消費不可=未マッチはtitle=""のまま
  }
  return base.copy(steps = newSteps)               # 時刻/件数/順序すべて不変（変更なし）
```

**B3（複数準備 action_type の集約）との整合**: AI が `PREPARE_ITEMS`/`GET_READY`/`GATHER_BELONGINGS`（いずれも `ExecutionStepType.PREPARATION` へマップ、§7.3）を複数返しても、`queueByType[PREPARATION]` にはAI出力順で全件が積まれる。Basic 側の PREPARATION step は現状高々1件（§7.3 で維持を確認）なので、`base.steps.map` はこのキューから**1回だけ** `removeFirstOrNull()` する——結果として実質的に「先頭一致のみ採用、残りは自然に消費されず捨てられる」という**旧設計と同じ観測結果**になる（§13 B3 承認はこの新設計のもとでも変わらず成立する）。したがって B3 の「first-match-wins」は特別扱いのロジックではなく、**「demand（base側の同一type step数）が supply（AIの同一type文言数）を下回る場合に自然に生じる帰結」**として1つの機構に統一される。

**新規性が効くケース（旧設計では誤動作、新設計では正しい）**: base 側に同一 type の step が複数存在する場合（現状の `BasicPlanningEngine` は生成しないが、`overlay` 関数自身の契約としてテストで固定する。§8 T-P8-25）、`base.steps` の出現順で1件ずつ異なる文言が割り当てられる。AI 側の文言数が base 側の同一 type step 数より少なければ、消費し切った以降の step は `title=""` のまま Basic 固定文言へ縮退する（§13 不変条件と整合）。

**可視性**: `overlay` は `ai/LocalAiPlanContextualizer.kt` 内の公開関数（`internal` 以上）とする。`LocalAiPlanContextualizer.contextualize` 内部と `PlanReviewViewModel` の `combine` ブロック（§4.2）の両方から呼べるようにするため、`private` にはしない。

### 7.3 `PlanActionType → ExecutionStepType` マッパ（決定的・全7値網羅）

| `PlanActionType` | → `ExecutionStepType` | 備考 |
|---|---|---|
| `FINISH_CURRENT_TASK` | `TRANSITION` | |
| `PREPARE_ITEMS` | `PREPARATION` | 3語が PREPARATION へ集約 |
| `GET_READY` | `PREPARATION` | first-match-wins で単一 preparation step に1文言 |
| `GATHER_BELONGINGS` | `PREPARATION` | |
| `LEAVE` | `DEPARTURE` | |
| `COMMUTE` | `TRAVEL` | travel step 非生成時は捨てる |
| `ARRIVE` | （対応 step 無し=null） | 到着は plan フィールド `estimatedArrival`。step 化しないため捨てる |

- Basic は各 type を高々1件生成（現状の不変事実。変わった場合も §7.2 のキュー消費設計は正しく1対1対応する）。AI が同一 type へ複数 action_type（例: PREPARATION に集約される3種）を返しても、base 側が該当 type 1件のみなら結果的に**先頭一致のみ採用**（残りは消費されず捨てられる）。この「多対1集約」は Phase 8 の意図的単純化であり、§7.2 のキュー消費機構がもたらす自然な帰結として実現する（旧設計のような専用の `putIfAbsent` 特例ではない。§13 B3 で承認確認）。

---

## §8. テストケース表（正常 / 異常 / エッジ / 実機）

> ID 名前空間 `T-P8-*`。JVM 層は `LocalAiGateway` を fake 化し `AIPlanResponse` を注入（実 LLM 不要=決定的）。**例外: T-P8-24** は `ModelStorageImpl`（Robolectric、`ModelStorageTest.kt` と同型）が対象で `LocalAiGateway` は関与しない（§6.4）。

### 正常系
| ID | ケース | 期待 |
|---|---|---|
| T-P8-1 | AI ON+DL済、gateway Success | 各 step の `title` が対応 `display_text` へ差し替わる |
| T-P8-2 | overlay マッピング（base 側は各type1件の基本形） | transition/preparation/departure/travel が正しい action_type の文言を受ける（複数 base step の1対1対応は T-P8-25） |
| T-P8-3 | 非同期状態遷移（`combine` 由来・§4改訂） | event 選択直後の `uiState` 初回値は Basic（`aiState=IN_PROGRESS`）。AI Success 到達後、`latestAiResponse` 更新により `combine` が再計算され `aiState=APPLIED`・`plan`=overlay 済みへ遷移。いずれも `combine` の自動再emitであり、`_uiState.value` への命令的代入はしない（Gemini G1 CRITICAL②反映） |
| T-P8-4 | 確定伝播 | Start 後 Execution が `confirmedPlan.steps[i].title`=AI文言を表示（Execution 無改修で透過） |
| T-P8-5 | locale | ja→日本語文言 / en→英語文言（gateway 保証を overlay が透過） |
| T-P8-24 | **selectedModelId優先解決**（新設・§6.4、Gemini G1 CRITICAL④） | Qwen・Gemma4 双方の `finalFile` が存在し `selectedModelId=gemma-4-e2b-it` の状況で `ModelStorageImpl.installedEntry()` が Gemma4 を返す（catalog順=Qwen先頭を上書き）。`selectedModelId` 未設定/未DLならcatalog順fallback |

### 異常系（フォールバック）
| ID | ケース | 期待 |
|---|---|---|
| T-P8-6 | AI OFF | plan 不変(Basic固定)・`aiState=FELL_BACK(AI_DISABLED)`・UI 変化ゼロ・エラー非表示 |
| T-P8-7 | 未DL | 同上(`MODEL_NOT_INSTALLED`) |
| T-P8-8 | 生成失敗(retry 後 SCHEMA_INVALID) | 同上(`SCHEMA_INVALID`) |
| T-P8-9 | TIMEOUT | 同上(`TIMEOUT`) |
| T-P8-10 | OOM_PREVENTED / OUT_OF_MEMORY | 同上 |
| T-P8-11 | MODEL_CORRUPTED | 同上 |
| T-P8-12 | UNSUPPORTED_DEVICE / UNSUPPORTED_ABI | 同上 |
| T-P8-13 | `aiPlanContextualizer=null`（旧2引数構築） | AI フェーズ skip・Basic（後方互換） |

### エッジ
| ID | ケース | 期待 |
|---|---|---|
| T-P8-14 | **§13 不変条件（最重要）** | 任意 `AIPlanResponse` に対し overlay 後 plan は `title` 以外の全 step フィールド（id/semanticId/type/estimatedDuration/priority/skippable/scheduledStart/completedAt）と全 plan フィールド（event/transitionStart/departureTime/estimatedArrival/arrivalBuffer/件数/順序）が base と一致。**同一 `ExecutionStepType` の step が base 内に複数存在する fixture を含めて検証する**（1対1対応そのものの正しさは T-P8-25） |
| T-P8-15 | AI が同一 type へ複数 action_type を返す（base 側は該当 type 1件） | `queueByType`（§7.2）に複数件積まれるが base 側の消費は1回のみ＝先頭（AI出力順）の文言だけ採用、残りは消費されず捨てられる。step 数不変（旧設計と同じ観測結果。§7.2「B3との整合」参照） |
| T-P8-16 | AI が ARRIVE を返す | 対応 step 無しで捨てる。step 数不変 |
| T-P8-17 | Basic 側で transition/travel 非生成（duration=0 / travel=null）＋AI が該当 action_type | マッチ先無しで捨て、他 step は正常 overlay |
| T-P8-18 | AI が一部 type のみ carve（例 departure 文言なし） | その step は `title=""` のまま=Basic 固定文言へ per-step 縮退 |
| T-P8-19 | stale（推論中に別イベント選択） | 新イベント選択時点で `latestAiResponse` が同期的に `null` リセット（§4.3-2）。旧イベント宛て AI 応答到達時は event.id 不一致で `latestAiResponse` への書き込み自体を行わない（§4.3-1）。二重防御のいずれが効いても `uiState` は新イベントの Basic/travel 状態のまま=旧イベントの文言は混入しない |
| T-P8-20 | **PII 非送信** | contextualize 1回を `StrictMode.detectNetwork().penaltyDeath()` 下で通過=無通信（§10 L2 相当） |
| T-P8-21 | メトリクス非PII | `AiMetrics`/ログにイベント title/場所/座標が現れない（T-AIMET-1 再利用＋overlay 経路確認） |
| T-P8-22 | キャンセル | 画面離脱、または別イベント選択（`collectLatest` による旧イテレーションの自動キャンセル、§4.3）で推論中断・`CancellationException` 再送出でスコープ健全（リーク無し） |
| T-P8-23 | overlay 冪等性 | `overlay(overlay(base,ai),ai) == overlay(base,ai)`。**宣言的合成（§4）の正しさが依存する前提**: `combine` は base/aiCache いずれかの更新のたびに `overlay(latestBase, aiCache.result.response)` を再計算するため、同一入力に対し常に同一結果を返す（副作用なし・非決定性なし）ことがこの冪等性から保証される |
| T-P8-25 | **同一 `ExecutionStepType` の base step が複数存在**（新設・Gemini G1 CRITICAL①） | base に PREPARATION 相当の step を2件含む fixture を用意し、AI が2件分の文言を返すケースで、各 base step が**異なる** AI 文言へ1対1対応する（先頭 step が2件目の文言を奪わない・2件目が先頭の文言を重複して受け取らない）。AI 文言が1件しかない場合は base 側1件目のみ受け取り2件目は `title=""` のまま |
| T-P8-26 | **travel解決とAI解決の到達順序非依存**（新設・Gemini G1 CRITICAL②） | (a)travel が先に解決→AI が後、(b)AI が先に解決→travel が後、の両順序で最終 `uiState`（`plan`・`aiState`）が一致する。片方のみ到達時点では他方の未反映状態（travel未反映のBasic、またはAI未反映のtravel適用済みBasic）が正しく中間表示される |

### 実機（C4・A54 / Gemma4-E2B）
| ID | ケース | 期待 |
|---|---|---|
| T-P8-D1 | A54 で Gemma4-E2B DL→検証→PlanReview | 実機で文脈化行動文が表示される |
| T-P8-D2 | Execution ONE ACTION | 文脈化行動文が1画面ずつ表示される |
| T-P8-D3 | 速度・非同期 | Basic 即時表示で画面が止まらない。TTFT/total latency を記録 |
| T-P8-D4 | **G4-E 無通信成立** | 機内モードで AiResult.Success（=文脈化表示）が成立（§10 L3・§71 完成条件） |
| T-P8-D5 | 安定性 | 連続 N 回でクラッシュ/OOM 無し。5系統フォールバックが実機で Basic へ縮退 |

**合計 = JVM 26 件（正常6・異常8・エッジ12）＋ 実機 5 件 = 31 件。**

---

## §9. エラー & レスキューマップ（サイレント障害欄に空白なし）

| # | 処理 | 想定異常 | ハンドリング | サイレント障害の防止（観測点） | ユーザー影響 |
|---|---|---|---|---|---|
| 1 | AI 推論 | 生成失敗/OOM/TIMEOUT/未DL/OFF/端末非対応 | gateway が `Fallback(reason)`→contextualizer が `Unchanged(base, reason)` | `aiState=FELL_BACK(reason)` に理由保持・将来 Analytics へ（gateway が全 Throwable を Fallback へ写像済み） | 無し（Basic 固定文言のまま） |
| 2 | overlay マッチング | AI step が未対応 action_type/型不一致、**または同一typeのbase step数がAI供給文言数を超過（queue枯渇、Gemini G1 CRITICAL①反映後の新規パス、§7.2）** | 該当文言を捨てる／該当 step は `title=""` のまま | per-step で Basic 固定文言へ縮退（描画 `title.ifBlank`） | 該当行のみ Basic 文言 |
| 3 | 非同期差し替え | 推論中に別イベント選択（stale） | `latestAiResponse` を新イベント選択時に同期的に `null` リセット（§4.3-2）＋event.id 一致確認で古い結果の書き込み自体をゲート（§4.3-1）の二重防御 | `combine`（§4.2）は常に最新2値のみを合成するため、破棄された結果はどのタイミングでも `uiState` に現れない（IN_PROGRESS のまま新イベントの推論へ） | 古い予定の文言が混ざらない |
| 4 | 画面離脱／別イベント選択 | 推論継続でリソースリーク | `viewModelScope` キャンセル、または `collectLatest`（§4.3）による旧イテレーションの自動キャンセル→`CancellationException` 再送出 | CANCELLED は Fallback と区別（§8.7-3・gateway 準拠） | 無し |
| 5 | 確定タイミング | AI 完了前に Start | `confirmedPlan` は Start 時点スナップショット（Basic の可能性） | `aiState` で「未 Applied のまま確定」が観測可能 | 実行画面は Basic 文言（§19 品質下限=許容） |
| 6 | §15 逸脱 | `display_text` に数値/時刻混入 | gateway ②sanity が `SCHEMA_INVALID`→Basic | overlay は sanity 通過済みのみ受領（二重防御） | 無し（Basic 文言） |
| 7 | PII 漏洩 | 推論経路が通信 | `ai/` 隔離ガード T-AIISO-6＋実行時 StrictMode（T-P8-20） | 通信コード存在自体をガードで機械検出 | 無し（端末内完結） |
| 8 | 既定モデル解決（§6.4・新設） | `selectedModelId` に対応するファイルが未DL、または `selectedModelId` 自体が catalog に存在しない | `installedEntry()` が catalog 順 first-match へフォールバック（selectedModelId 分岐が不成立の場合のみ） | フォールバック発生自体はログ非対象（既存 `installedEntry()` の契約通り。`selectedModelId` と実導入モデルの乖離を観測する仕組みは Phase 10/12 の Analytics へ申し送り） | 何らかの導入済みモデルがあればAIが使われる（意図しないモデルへ縮退する可能性はあるが `MODEL_NOT_INSTALLED` への誤爆よりは可用性を優先、既存方針を維持） |

---

## §10. A54 実機での最終確認手順（Gemma4-E2B 込み完成版）

> 目的: 「実行画面に文脈化行動文が出る」「速度・安定性」「無通信成立（G4-E）」を実機で確定する。手順書 `docs/probes/phase8-a54-validation.md` を新規作成（既存 `docs/probes/phase7-device-bench.md` 様式踏襲・使い捨て計測、正式テストではない）。

1. **端末前提記録**: `Build.MODEL`(SM-A546*)・`totalMem`(6/8GB 変種を明記)・`SUPPORTED_ABIS`(arm64-v8a)・空きストレージ。Gemma4-E2B は DL 2.59GB・ピーク約2GiB のため、`StatFs` 事前ガード（`modelBytes×1.5≈3.9GB`）と `availMem` 余裕を確認。
2. **有効化**: Settings（C6 完成前提。C6 は 2026-08-10 完了済み＝commit `128a9c6`「Phase 7 C6: 設定画面 — AI ON/OFF＋Gemma4 DL導線・既定モデルGemma4」）で AI ON→Gemma4-E2B を選択→DL→DL 直後検証（SHA-256）合格を確認。`selectedModelId="gemma-4-e2b-it"`（`AiPreferences.DEFAULT_SELECTED_MODEL_ID` により既定でも同値）。**§6.4 の `installedEntry()` 改修（C1）が本検証の前提**——旧DL分の Qwen ファイルが端末に残存していても Gemma4 が確実にロードされることを、本手順の実行前に C1 の JVM テスト（T-P8-24）で確認しておく。
3. **文脈化表示（T-P8-D1/D2）**: 意味の異なる複数予定（例: 結婚式/歯科検診/出張/打ち合わせ）で PlanReview→Execution を通し、Basic 固定文言と AI 文脈化文言が**予定ごとに具体的に変わる**ことを目視＋スクショ（`docs/evidence/screenshots/phase8/`）。同一 action_type でも予定で文言が変わること（例 prepare_items: 結婚式「ご祝儀を用意する」/ 歯科検診「保険証を持って行く」）を確認。
4. **速度・非同期（T-P8-D3）**: PlanReview 到達時に Basic が即時表示され画面が止まらないこと。AI 完了までの TTFT・total latency をログ記録（§57 latency）。冷機/連続5回で tok/s 低下（サーマル）も所見記録。§8.6 の暫定タイムアウト 20,000ms・OOM マージン 512MB が A54+Gemma4 で妥当かを実測突合（未確定閾値の確定材料。§13 B6）。
5. **G4-E 無通信成立（T-P8-D4）**: 機内モード ON で PlanReview の AI 文脈化が成立（`AiResult.Success` 相当の文言表示）することを確認（§10 L3・§71 完成条件と同一試験）。加えて可能なら L2（推論スレッドに `detectNetwork().penaltyDeath()`）を instrumented で1回通す。
6. **安定性・縮退（T-P8-D5）**: 連続 N 回でクラッシュ/OOM 無し。AI OFF・未DL・（意図的に破損させた）検証失敗の各系統で Basic へ縮退しクラッシュしないことを確認。
7. **後始末**: モデルは `noBackupFilesDir/models/` から `ModelStorage.delete()`。`ai_preferences.xml` の `aiEnabled` を実行前値へ復元（phase7 §14.10 の後始末手順に準拠）。

---

## §11. サイクル分割（PDCA 超細分化・都度モデル切替）

| サイクル | 担当 | 成果物 | ゲート |
|---|---|---|---|
| **C0 レビュー** | opus＋Gemini(3.5-flash) | 本計画のダブルレビュー（Pass1 CRITICAL→即修正／Pass2 INFORMATIONAL） | ユーザー承認 |
| **C1 scaffold** | sonnet | `LocalAiPlanContextualizer`（契約＋`overlay`/`contextualize` は TODO）／マッパ（born-green データ）／`AiContextualizationState`／`PlanReviewUiState.aiState` 追加／`PlanReviewViewModel` へ `latestBase`/`latestAiResponse`（2 StateFlow）と `combine` 配線の骨格追加（合成本体は TODO、§4）／DI・VM 引数配線（AI 呼び出しは未活性）／**`ModelStorageImpl` へ `AiPreferences?` 引数追加（末尾・既定null。`installedEntry()` のselectedModelId優先分岐はTODOのままC3へ、§6.4）**。compile green・既存回帰0 | opus 構造レビュー |
| **C2 Red** | sonnet | §8 の JVM 26 件（T-P8-24〜26 新設分含む）を失敗する形で先に記述（本番ロジック未実装）。Red 実行確認 | Red 確認 |
| **C3 Green→Refactor** | sonnet | `overlay`（キュー消費・§7.2）＋`contextualize`＋`PlanReviewViewModel` の `combine` 合成本体（§4）＋DI 活性化＋**`ModelStorageImpl.installedEntry()` のselectedModelId優先分岐（§6.4）＋`ModelCatalog.ALL` 順序意図KDocの更新**を段階実装。各段でテスト実行し Green 維持。全通過後リファクタ→再 green | opus レビュー |
| **C4 実機** | （ユーザー実機操作＋sonnet 記録） | §10 の A54/Gemma4-E2B 検証＋G4-E。§6.4 改修により旧DLモデル残存下でもGemma4が確実にロードされることを前提に実施。スクショ・latency 記録 | 目視＋記録 |
| **close** | opus | 全回帰確認・申し送り整理・DECISIONS 追記 | 品質ゲート |

---

### 11.1 C1〜C3完了記録（2026-08-10、domain-implementer、JVM/Robolectricスコープ）

**結論**: C1 scaffold→C2 Red→C3 Green→Refactorを完走し、JVM全件Green（既存613維持＋新規26件、回帰0）・lint error 0/MissingTranslation 0を確認した。C4（A54実機）は本サイクル対象外。

**C1（scaffold・compile green・既存回帰0）**: 新規`ai/LocalAiPlanContextualizer.kt`（`ContextualizationResult`確定・`overlay`/`contextualize`はTODO・`PlanActionType.toExecutionStepTypeOrNull()`はborn-green実装済み）／`PlanReviewUiState.aiState`＋`AiContextualizationState`追加／`PlanReviewViewModel`へ`latestBase`/`latestAiResponse`/`AiResponseCache`＋未収集の`combinedUiStateFlow()`骨格（`_uiState`/`applyPlan()`は無変更のまま維持）／`ModelStorageImpl`へ`preferences: AiPreferences? = null`引数追加（`installedEntry()`本体は無変更）／`AppContainer`へ`localAiPlanContextualizer`・`modelStorage`への`preferences`配線を追加。

**C1で発見した設計逸脱（要opusレビュー・§6.3からの実装時逸脱）**: `AppContainer`で`aiPlanContextualizer = localAiPlanContextualizer`を`PlanReviewViewModel`初期化子へ素朴に配線したところ、既存の`NavigationFlowTest`/`CalendarNavigationFlowTest`/`NotificationPermissionRequestTest`（計10件）が`NoClassDefFoundError`で新規に失敗した。実測した根本原因は`com.google.ai.edge.litertlm`（litertlm-android 0.15.0 AAR）のクラスファイルバージョンが65（Java 21相当）であるのに対し、本機の実行JDKが17.0.19（認識上限61）であること（`UnsupportedClassVersionError`）。JDK21は本機に未導入（`/usr/lib/jvm/`にjava-17-openjdk-amd64のみ確認）で、新規JDK導入は本サイクルの権限・スコープ外と判断した。Phase 7時点は`AppContainer.localAiGateway`を実際に消費するコードが皆無だったため潜在していたバグで、Phase 8が初めてこの経路を踏んだ。**対処**: `AppContainer.localAiPlanContextualizer`の型を`LocalAiPlanContextualizer?`へ変更し、構築を`try { … } catch (e: LinkageError) { null }`で囲んだ（`LocalAiGateway.invokeModel`が既に`UnsatisfiedLinkError`＝同じ`LinkageError`系列を「ネイティブ資源ロード不可」の既知縮退経路として捕捉しているのと同一原則をDI構築の1つ手前の層へ適用）。実機（ART、本チェック非該当）では通常どおり非nullを返し、AI実配線は生きたまま。**この対処は計画書§6.3の例示スニペット（無条件構築）からの逸脱であり、opusレビューでの確認を要する**。本質的な解決策の候補はJDK21環境の用意、またはlitertlm-android側の対応JDKバージョン確認（詳細根拠は`app/src/main/java/com/actionstarter/di/AppContainer.kt`の`localAiPlanContextualizer`KDoc参照）。

**C2（Red・26件）**: `ai/LocalAiPlanContextualizerTest.kt`（新規、T-P8-1・2・5〜12・14〜18・20・21・23・25＝19件）／`ai/model/ModelStorageTest.kt`（追加、T-P8-24＝1件、3シナリオ）／`features/PlanReviewViewModelTest.kt`（追加、T-P8-3・4・13・19・22・26＝6件）。実行結果：25/26が`NotImplementedError`（TODO本体）または`Idle`期待値ズレ（combine未配線）で意図どおりRed。**1件（T-P8-13、`aiPlanContextualizer=null`後方互換）はC1時点で偶然Green**——`uiState`がまだ旧`_uiState`経由（既定`aiState=Idle`固定）のため、アサーション自体は正しいが実質的な分岐をまだ検証していない状態だった。C3で`combine`実配線後は同じアサーションが実際の`aiPlanContextualizer==null`分岐を通る形になり、意味のある回帰ガードへ移行したことを確認済み。共有テストfixture`ai/AiGatewayTestFixtures.kt`（新規）を追加し、`LocalAiGatewayTest`が確立した「fakeは`LocalLanguageModel`境界のみ・`LocalAiGateway`自体は実物をRobolectricで駆動」規約を`ai`/`features`両パッケージから再利用可能にした。

**C3（Green→Refactor）**: `overlay`（キュー消費・§7.2）／`contextualize`（§7.1）／`ModelStorageImpl.installedEntry()`のselectedModelId優先分岐（§6.4）／`ModelCatalog.ALL`のKDoc更新／`PlanReviewViewModel`の`combine`合成本体・`init`の`collectLatest`化（§4.2〜4.3）を段階実装し、各段でテスト実行してGreenを確認した。全通過後、C1由来の陳腐化コメント（「C1時点では…TODO」等）を除去するリファクタを行い、再度JVM全件Green・lint再確認を行った。

**Green化の過程で見つかったテスト側の不具合（3件、いずれもテストの作成ミスでありproduction側の欠陥ではない）**:
1. `event_type`に無効な文字列`"medical_appointment"`を使用（正しい`PlanEventType`列挙値は`"medical"`）→`SchemaValidator`が正しく`SCHEMA_INVALID`を返していた。
2. `LocalAiPlanContextualizerTest`の`PlanningContext.locale`が`Locale.US`のまま日本語`display_text`フィクスチャを使用→`ContentSanityChecker.isLocaleConsistent`（§15逸脱の二重防御）が正しく`SCHEMA_INVALID`を返していた。`Locale.JAPAN`へ修正。
3. `PlanReviewViewModelTest`の新規AIテストは`PlanReviewViewModel.buildPlanningContext`内部の`Locale.getDefault()`（実行環境依存）を経由するため、同じ理由で`SCHEMA_INVALID`になった→`@Before`/`@After`でJVM既定localeを`Locale.JAPAN`へ一時固定（`BasicPlanningEngine`はlocaleを一切参照しないため既存`tP4c8_*`5件への影響なし、実測確認済み）。
4. （production側の実装バグ）`ModelStorageTest`のT-P8-24が当初3シナリオで同一catalog id（`qwen3-0.6b-int4-block32`/`gemma-4-e2b-it`）を再利用しており、共有Robolectric `noBackupFilesDir`上でfinalFileパスが衝突しシナリオ間でファイルが残留していた。各シナリオのid接尾辞を`-s1`/`-s2`/`-s3`で分離して解消（これはテストの分離漏れであり、`installedEntry()`実装自体は当初から正しかった）。

**§13不変条件（AI有無で数値・構造一致）の保証テスト**: T-P8-14（`overlay`適用後、`title`以外の全step/planフィールドが不変であることを直接アサート。同一`ExecutionStepType`のstepがbase内に複数存在するfixtureを含む）・T-P8-23（`overlay`冪等性）・T-P8-25（重複type1対1対応）がいずれもGreen。

**クラス別Green化**：
| クラス | tests | failures |
|---|---|---|
| `ai.LocalAiPlanContextualizerTest`（新規） | 19 | 0 |
| `ai.model.ModelStorageTest`（13既存＋1新規） | 14 | 0 |
| `features.PlanReviewViewModelTest`（5既存＋6新規） | 11 | 0 |

**全JVM件数**: `:app:testDebugUnitTest --rerun`でtests=639／failures=0／errors=0／skipped=1（Phase 8着手前ベースラインtests=613／failures=0／skipped=1との差分は新規26件の追加のみ＝完全一致、既存613件の回帰0件）。

**lint**: `:app:lintDebug --rerun-tasks`でBUILD SUCCESSFUL・error 0・MissingTranslation 0・warning 22（既存分と完全一致、新規warning 0）。

**フットプリント実績**: 計画書§12予定どおり——新規1本（`ai/LocalAiPlanContextualizer.kt`）／変更4本（`PlanReviewViewModel.kt`／`PlanReviewUiState.kt`／`AppContainer.kt`／`ai/model/ModelStorage.kt`）。加えてテスト専用の新規共有fixture（`ai/AiGatewayTestFixtures.kt`）を1本追加（計画書は明示していないが、C2の複数テストファイルが同一のRobolectric fixtureパターンを再利用するための土台であり、production側フットプリントには影響しない）。UXヒント（§12「任意」）は計画書B5裁定どおり見送り（未着手）。

**§6.4 vs §11の軽微な文言不一致（報告）**: §6.4本文は`ModelCatalog.ALL`のKDoc更新を「C1の改修範囲に含める」と記すが、§11のサイクル表はC3の成果物として明記する。実装はC3を採用した（KDocが記述する新挙動＝selectedModelId優先分岐は同じくC3で実装されるため、実装より先にKDocだけ新挙動を記述すると逆に実装と乖離した記述になってしまうため）。挙動・テストへの影響はない。

**C4（A54実機確認）への申し送り**: 完成版APKで以下を確認すること。
1. **§6.4改修の実機効果**: 旧DL分のQwenファイルが端末に残存していても、`selectedModelId=gemma-4-e2b-it`（既定）であれば`ModelStorageImpl.installedEntry()`がGemma4-E2Bを正しくロードすること（T-P8-24はJVM/Robolectricのfixtureエントリで検証済みだが、実カタログ`ModelCatalog.ALL`・実際のファイル配置での確認は未実施）。
2. **LinkageError防御が実機で発火しないこと**: `AppContainer.localAiPlanContextualizer`が実機（ART）では例外なく非nullを返し、AI推論が実際に呼ばれることを確認する（JVM側では`LinkageError`捕捉分岐が存在するが、これは実機用ではなくJVMテスト環境専用の防御であるべきで、実機でこの分岐に入ってしまう場合は別問題として扱う）。
3. **文脈化表示（T-P8-D1/D2）**: 複数の意味の異なる予定（結婚式/歯科検診/出張等）でPlanReview→Executionを通し、AI文脈化文言が予定ごとに具体的に変わることを目視確認（§10手順3）。
4. **速度・非同期（T-P8-D3）**: PlanReview到達時にBasicが即時表示され画面が止まらないこと。TTFT・total latencyの実測記録（§10手順4）。
5. **G4-E無通信成立（T-P8-D4）**: 機内モードでAI文脈化が成立すること（§10手順5）。
6. **安定性・縮退（T-P8-D5）**: 連続実行でクラッシュ/OOM無し、AI OFF・未DL・検証失敗の各系統がBasicへ正しく縮退すること（§10手順6）。
7. Manifest/strings変更は不要だった（計画書想定どおり）。

---

## §12. フットプリント

**新規（1本）**: `app/src/main/java/com/actionstarter/ai/LocalAiPlanContextualizer.kt`（`ContextualizationResult`・`overlay`（公開・キュー消費版、§7.2）・`PlanActionType→ExecutionStepType` マッパを同居 or `ai/PlanActionTypeMapping.kt` へ分離）。

**変更（4本）**:
- `features/planreview/PlanReviewViewModel.kt`（`latestBase`/`latestAiResponse` の2 StateFlow＋`combine`合成・`collectLatest`化・引数 `aiPlanContextualizer?=null` 追加。§4）。
- `features/planreview/PlanReviewUiState.kt`（`aiState` フィールド追加・末尾・既定 IDLE）。
- `di/AppContainer.kt`（`localAiPlanContextualizer by lazy`＋Factory 注入。加えて既存 `modelStorage by lazy { ModelStorageImpl(context) }` を `ModelStorageImpl(context, preferences = aiPreferences)` へ1行変更、§6.4）。
- `ai/model/ModelStorage.kt`（`ModelStorageImpl` のみ改修: `AiPreferences?` 注入・`installedEntry()` のselectedModelId優先解決。§6.4、Gemini G1 CRITICAL④／B4確定。`ModelStorage` interfaceのシグネチャは無変更）。

**任意（UX ヒント採用時のみ・§5/§13 B5）**: `features/planreview/PlanReviewScreen.kt`（IN_PROGRESS 中の控えめキャプション）＋ `res/values*/strings.xml`（1 文言）。

**不変（触れない）**: `planning/BasicPlanningEngine.kt`／`ai/LocalAiGateway.kt`／`ai/schema/*`／`ai/prompt/*`／`ai/adapter/*`／`ai/model/*` のうち `ModelStorage.kt` を除く全ファイル（`ModelCatalog.kt` は §6.4 の KDoc コメント更新のみ許容し、エントリ定義・`ALL` の並び自体は不変）／`features/common/StepTitle.kt`／`features/execution/*`（ExecutionScreen/VM/UiState）／`navigation/*`（NavHost・SharedPlanViewModel）／`features/settings/*`（C6 所有）／全 domain モデル。

**回帰の期待**: 既存テスト（隔離ガード T-AIISO/T-BPE-28・PlanReview/Execution 単体・`ModelStorageTest`）は無変更で green のまま（後方互換 null 引数・overlay の `ai/` 配置・`ModelStorageImpl` の `preferences` 既定null による）。`ModelCatalog.ALL` のKDocコメント更新はコンパイル・実行に無影響。

---

## §13. Fable 5 確認事項 → 裁定（2026-08-10、全件確定）

> B1〜B7 は初稿時点の「自己確定せず判断を仰ぐ」オープン項目。Fable 5 裁定によりB1〜B7全件へ裁定が下り、本計画は **G1 通過**（承認状態は文書冒頭参照）。論点の原文は保持し、裁定を右列に記す。

| ID | 論点 | 裁定 |
|---|---|---|
| **B1**（設計） | `LocalAiPlanContextualizer` を仕様 §43 の `planning/LocalAIPlanningEngine` ではなく **`ai/` 配置＋overlay 専用（`PlanningEngine.createPlan` 非実装）**とする逸脱を承認するか（根拠: phase7 §18.1 R-10・非同期 UX・§13 構造保証）。命名を `LocalAIPlanningEngine` に寄せるかも含めて確認。 | **承認**（隔離ガード維持・§13保証・非同期UX）。命名は `LocalAiPlanContextualizer` のまま=「overlay専用であり `PlanningEngine` 実装ではない」ことをクラス名自体が示す方が、§43からの意図的逸脱を隠さず誠実 |
| **B2**（接続点） | タスクは「PlanReview/Execution 両 VM が gateway を呼ぶ」だが、本推奨は **PlanReview のみで推論、Execution はレンダラー**とする（Execution は `PlanningContext` を持たず、実行中差し替えが体験を乱し、プロセス死で eventSelection へ戻るため）。この絞り込みを承認するか、Execution 側推論（`PlanningContext` を `SharedPlanViewModel` 経由で運ぶ代替案）を要求するか。 | **承認**（Executionは既存title表示・二重推論回避）。`plan.steps[i].title` の透過描画で足りるため Execution 側の gateway 呼び出しは追加しない |
| **B3**（UX/仕様トレードオフ） | AI が複数の準備系 step（prepare_items/get_ready/gather_belongings）を返しても、Basic の単一 PREPARATION step には **first-match-wins で1文言のみ**反映（残りは捨てる）。この情報欠落を Phase 8 で許容するか、将来 Basic 側で複数準備 step を出す拡張を別 Phase で検討するか。 | **承認**（Basic構造=1 PREPARATION維持）。ただし Gemini G1 CRITICAL①（§7.2）の1対1マッピング設計と整合させ、集約後のstep群に対して正しく対応付ける——§7.2「B3との整合」参照。first-match-winsは専用ロジックではなく、キュー消費（demand1・supply複数）の自然な帰結として実現する |
| **B4**（既定モデル・CRITICAL） | タスクは「既定モデル=Gemma4-E2B 確定」だが、現行 `ModelCatalog.ALL` は **Qwen3-0.6B を先頭**とし `ModelStorage.installedEntry()` は catalog 順の先頭一致で解決、`AiPreferences.selectedModelId` を **gateway は参照していない**。Gemma4-E2B を実際の既定にする実現方式（(a) `installedEntry` が `selectedModelId` を優先する小改修＝`ai/model` 内、(b) `ModelCatalog.ALL` の並べ替え、(c) settings/C6 が Gemma のみ DL 可能にする）の**選定と担当（Phase 8 か C6 か）**を確定してほしい。Phase 8 の配線自体はモデル非依存だが、A54 検証（§10）の期待値がこれに依存する。 | **確定（Gemini G1 CRITICAL④と統合）**: 方式(a)を採用。`ModelStorageImpl` へ `AiPreferences` を注入し `installedEntry()` で selectedModelId優先解決（設定済み＋ファイル実在の場合のみ catalog順走査より優先、それ以外は既存fallback）。**担当= Phase 8 C1**（§6.4・§11）。C6完了報告で本問題が実際に顕在化することが確定済みのため据え置き不可と判断 |
| **B5**（UX ヒント・INFORMATIONAL） | IN_PROGRESS 中の「調整中…」控えめヒントを PlanReview に出すか（＝`PlanReviewScreen`＋strings への最小追加）。出さない場合フットプリントは §12 の3変更に収まる。 | **まず無しで開始**（Basic即時表示で認知負荷減・A54実機で差し替えの自然さを見て再判断）。§12の「任意」フットプリントは温存し、C4実機所見次第でC3後半かPhase 8.1で追加検討 |
| **B6**（スコープ） | (i) **AiMetrics の PSS 化**（phase7 §11.1/申し送り）は Analytics sink 不在（Phase 10/12）ゆえ **Phase 8 スコープ外**を推奨。(ii) **G4-E（無通信実機試験）** は AI が初めてユーザー到達する本 Phase の A54 検証（§10-5）に**含める**ことを推奨。この2点の scope 判断を承認するか。 | **承認**。(i) AiMetrics PSS化はPhase 8外のまま。(ii) G4-EはA54検証（§10手順5）に含める |
| **B7**（依存） | 本 Phase は C6（settings の AI 有効化トグル＋Gemma4-E2B DL 導線）の完成に**実機検証(C4)で依存**する。C1〜C3 の JVM 実装は C6 と独立に進行可能。C4 着手前提として C6 完了状況を確認してほしい。 | **承認（C6完了済みのため制約解除）**。C6は2026-08-10完了（commit `128a9c6`「Phase 7 C6: 設定画面 — AI ON/OFF＋Gemma4 DL導線・既定モデルGemma4」）。C4はブロッカー無しで着手可能。ただしC6完了報告がB4（既定モデル解決の不整合）を新たに提起したため、**C1が§6.4の改修を先に完了させることをC4着手の実質的な前提とする** |

---

### 付録: 接地した既存契約（推測ではなく Phase 7 確定物）

- `LocalAiGateway.generatePlan(context): AiResult<AIPlanResponse>`（例外を出さない・全 Throwable→Fallback・`CancellationException` 再送出）。`AiResult.Success(value, metrics)` / `Fallback(reason, detail)`。
- `AIPlanResponse(eventType: String, steps: List<AIPlanStepResponse(actionType, displayText)>)`。`displayText` は sanity 通過済み（≤60字・非捏造・locale 整合・非コピー・action_type 重複なし）。
- `AiFallbackReason` 12 値（AI_DISABLED/MODEL_NOT_INSTALLED/UNSUPPORTED_DEVICE/UNSUPPORTED_ABI/INSUFFICIENT_STORAGE/MODEL_LOAD_FAILED/MODEL_CORRUPTED/OUT_OF_MEMORY_PREVENTED/OUT_OF_MEMORY/TIMEOUT/SCHEMA_INVALID/NOT_IMPLEMENTED_IN_PHASE7/UNKNOWN）。
- `PlanActionType` 7 値・`ExecutionStepType` 4 値（§7.3 マッパ）。
- 描画フォールバック: `ExecutionScreen` L133／`PlanReviewScreen` L211 が `step.title.ifBlank { resolveStepTitle(step.semanticId) }`。
- 隔離ガード: `ai/`→`features/` 禁止(T-AIISO-5)・`ai/` 通信は `ModelDownloader` のみ(T-AIISO-6)・`features/`→`ai/` は許可（`features/settings` が既に import）。
