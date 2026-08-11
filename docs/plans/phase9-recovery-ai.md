# Phase 9 実装計画書 — Local AI Recovery（Recovery文言のAI化）＋品質防御ハーネス

> 対象仕様: §73「Phase 9・Local AI Recovery」・§32「Recovery Option」・§33「Recoveryの優先原則」・§34「ユーザー最終決定」・§15「LLMに禁止すること」・§19「AI OFF時でも動作すること」・§20「Structured Output」・§21「AI Promptの言語非依存化」・§61「MVPに入れない機能」・§88「Developer UX Principle」
> 前提基盤: Phase 6（`BasicRecoveryEngine`・`RecoveryOption`/`RecoveryContext`/`RecoveryPlan`・S-2/U-3「delay message」除外）・Phase 7（`LocalLanguageModel.generateRecovery`契約scaffold・`AIRecoveryResponse`型・§18申し送り5）・Phase 8（`LocalAiPlanContextualizer`overlay方式・B1裁定）・Phase 8.5（`ModelSelector`自動選択・ADR-0062 modelPath配線是正・§12 Qwenエコー実例）
> 種別: 新機能（起案のみ・コード変更禁止）
> 承認状態: **コミット1 Green検収合格・コミット済み（`dca7150`）。コミット2（L2/L3/L5品質防御ハーネス）Green検収合格・コミット済み（`81eec58`）。コミット3（UI配線: RecoveryOptionText/RecoveryScreen/AppContainer）Green完了・検収待ち（:app:testDebugUnitTest tests=719/skipped=1/failures=0/errors=0〔JUnit XML集計で照合、既存713件無傷〕・:app:lintDebug error=0/warning=23〔--rerun-tasksで再確認・コミット2から不変〕。resolveRecoveryOptionExplanationにaiExplanation非null・非空優先分岐を実装、RecoveryScreenのexplanation Textへminlines=2（レイアウト安定swap・A-4）、AppContainerへlocalAiRecoveryContextualizer（localAiPlanContextualizerと同型のLinkageError防御）をby lazy新設しRecoveryViewModel初期化子へ配線。C1/C2から持ち越しのRefactor候補（LiteRtLmLocalLanguageModelのbuildConversationConfig/buildRecoveryConversationConfig等の重複）は評価のうえ本ターンでは非実施と判断（理由は完了報告参照）。まだコミットしていない（コミット3メッセージは検収時に受領予定）**

---

## §0. 結論ファースト

Phase 9は`recovery/BasicRecoveryEngine`を**変更しない**。`Action_Starter_Master_Specification`の§13「AIはdisplay_textのみ書き換える」不変条件をRecoveryへそのまま拡張し、新設`ai/LocalAiRecoveryContextualizer`（Phase 8の`LocalAiPlanContextualizer`と同型のoverlay decorator、`RecoveryEngine`は実装しない）が、Basic決定済みの`RecoveryOption.semanticAction`集合に対する`explanation`のみをLLMで上書きする。構造・時刻・skippedStepIdsはKotlin専任のまま不変。失敗時は常に無加工のBasic `RecoveryPlan`へ縮退する（新規Fallback様式を増やさない）。

品質面では、Phase 8.5§12で実例化したQwen 0.6Bのfew-shotエコー（「歯科検診」——`PlanPromptBuilder`の実在する模範例タイトルそのものが無関係な予定の出力に混入した）に対し、`ContentSanityChecker`へ2ルール（few-shotエコー検出・最小品質ヒューリスティック）を追加しRetry昇格に接続する多層防御を**本フェーズのスコープとして実装**する。プロンプト再設計（few-shotのevent_type条件選択）とGPU/ウォームアップ等の性能施策は、実機計測ループを要するため**Phase 9.5候補**として分離する。

---

## §1. 目的・背景

§73「Phase 9」原文は「remaining steps / travel / deadline / priority を与えてRecovery候補生成。数値はKotlin側が計算しLLMに渡す」の3行のみで、詳細設計は§32〜§34・§13〜§21・Phase 6/7/8の既存決定へ委ねられている。Phase 6は「Prepare a delay message」（§32 option 3）を§61（自動メール/SMS禁止）・§34（対外連絡は確認必須）との不整合を理由にS-2/U-3でPhase 9へ先送りした。Phase 7は`LocalLanguageModel.generateRecovery`契約・`AIRecoveryResponse`型をscaffoldしたが`LocalAiGateway.generateRecovery`は`NOT_IMPLEMENTED_IN_PHASE7`を返すのみ（U-8）。Phase 8.5§12は、Qwen 0.6Bが実機で不特定の予定名（few-shot例文由来の「歯科検診」）を無関係な予定へ描画する系統的欠陥を実例化し、Phase 9/12への申し送りとした。本計画はこの3つの積み残しを統合し、Recovery文言のAI化と、Plan/Recovery共通の品質防御ハーネスを同時に設計する。

---

## §2. 仕様整合（事前確認結果）

| § | 引用要点 | 本計画での扱い |
|---|---|---|
| §73 | remaining steps/travel/deadline/priorityを与えてRecovery候補生成。数値はKotlin側計算 | `RecoveryContext`（既存・無変更）がこれを既に満たす。AIへは`RecoveryOption.semanticAction`集合と最小限のイベント文脈のみ渡す（§3.3） |
| §32 | 最大3つ。「1. Leave now ETA／2. Change transport ETA／3. Prepare a delay message」 | option 1・2相当は`BasicRecoveryEngine`が既に実装済み（A〜D規則）。option 3は**本計画のデフォルトスコープに含めない**（§12確認事項1で両論併記） |
| §33 | 完璧な準備でなく予定成立を優先。required/important/optionalを区別、必須は勝手に省略しない | `BasicRecoveryEngine`が既に実装済み・無変更。AIはskippedStepIdsに触れないためこの不変条件を破りようがない（構造的保証） |
| §34 | AIは提案のみ。ステップ省略・移動手段変更・対外連絡等はユーザー確認必須 | Recoveryの選択適用（`useThisPlan`）は既存どおりユーザータップ起点のまま無変更。AIが生成した`explanation`は表示文言であり「決定」ではない |
| §15 | GPS/正確な移動時間/時刻演算/到着時刻演算/メール送信/SMS送信/安全上重要な最終判断をLLMに禁止 | `AIRecoveryOptionResponse`に時刻・座標に相当するフィールドを持たせない（Phase 7設計を継承）。`ContentSanityChecker`の既存「捏造検出」ルール（数字・URL・@を含めば不合格）がRecoveryの`explanation`にもそのまま適用される |
| §19 | Local AIはEnhancement、SPOFにしない | `LocalAiRecoveryContextualizer`は失敗時に無加工の`RecoveryPlan`（Basic）を返す。AI呼び出し自体をtry/catchで包み、`CancellationException`以外は`LocalAiGateway`が既に全て`AiResult.Fallback`へ写像する（既存契約を継承、新規例外パスを増やさない） |
| §20 | LLM自由文をDomain Logicへ直接使用禁止。Schema validation必須、失敗はretry1回→Basic Engineへ | `RecoveryJsonSchema`＋`RecoverySchemaValidator`（新設）でPlanと同じ2層検証パイプラインを敷く。Retry契約（Primary→Retry→Fallback）もPlanと同一に揃える |
| §21 | action_type等は英語ID、display_text側のみlocale依存 | `RecoveryActionType`（新設enum、`keep_all_steps`等4値）をJSON契約の単一情報源にする（`PlanActionType`と同型） |
| §61 | 自動メール送信・自動SMS・自動予定変更を明示的に禁止 | 「Prepare a delay message」を含める場合も送信は常にユーザー操作の共有Intentであり自動送信は行わない（§12確認事項1） |
| §88 | 「予定を今やる一つの行動に変えることに直接寄与するか」がMVP採否基準 | エコー検出ルール・delay message双方をこの基準で§12にて要否判断する |
| §30/§31 | Reality Check→Recovery Modeの表示例（ETA・スキップ提案） | 変更なし。AIはこの表示のうち`explanation`文言のみを差し替える |
| §60 | Telemetryは原文イベントタイトル等を送らない方針 | L5メトリクス（§4.5）はreject理由enumと件数のみ、PII非出力（既存`AiMetrics`規約を継承） |

**Phase 6→9引き継ぎ（S-2/U-3）**: 「Prepare a delay message」除外はS-2の暫定判断であり恒久禁止ではない。本計画で再検討し§12へ結論を記す。

**Phase 7→9引き継ぎ（§18申し送り5）**: 「基盤はPhase 7で完成しているため、`RecoveryJsonSchema`と`RecoveryPromptBuilder`を追加し`ai/adapter/`を1メソッド実装するだけでよい設計にしてある。`AIRecoveryOptionResponse`に`estimatedArrival`を追加しないこと」。本計画はこの前提を踏襲するが、後述する重要な発見（§3.1）により`AIRecoveryOptionResponse`のフィールド構成自体は見直す。

**重要な発見1（アーキテクチャ精査で判明）**: `RecoveryEngine`のクラスKDoc（Phase 6 scaffold時点の記述）は「Basic EngineとLocal AI Engineの両方がこのinterfaceを実装し交換可能に扱う」と書かれているが、これはPhase 8で実際に採用された設計と矛盾する。Phase 8は`LocalAIPlanningEngine`（`PlanningEngine`実装）を採らず、`LocalAiPlanContextualizer`という**overlay decorator**（`gateway`のみを持ち、`PlanningEngine`を実装しない）を採用した。理由はKDoc原文で明記されている：「`createPlan`は単一戻り値のため『Basic即時表示→AI後差し替え』という非同期UXを表現できず、2つの`PlanningEngine`実装が各々planを組み立てると§13構造不変が構造的に保証できなくなるため」。この理由はRecoveryにもそのまま当てはまる。本計画は`RecoveryEngine`を実装する新クラスを作らず、`LocalAiPlanContextualizer`と同型の`LocalAiRecoveryContextualizer`を採用する（§3.1）。`RecoveryEngine`の該当KDoc記述はPhase 9実装時に是正する（軽微な追従修正、独立のADR起票対象ではない）。

**重要な発見2（Qwenエコーの発生源特定）**: Phase 8.5§12.5の「映画館プランに『歯科検診』」は偶然の文字列ではない。`PlanPromptBuilder.JAPANESE_FEW_SHOT_SEEDS`の2番目の模範例が`title="歯科検診" category=medical`であり、そのuserTurn文字列がQwen 0.6Bの出力へ literal に漏出したものである。この模範例プール（ja: 結婚式/歯科検診/出張/打ち合わせ、en: Wedding/Dental checkup/Business trip/Team meeting）はRecovery用に新設する`RecoveryPromptBuilder`が持つ模範例プールにも同型のリスクを生む。§4のL2 R1はこの発生源を直接の入力として設計する。

---

## §3. 機能一覧と仕様

### 3.1 F-A: `LocalAiRecoveryContextualizer`新設（overlay decorator）

`ai/LocalAiRecoveryContextualizer.kt`を新設する。`LocalAiPlanContextualizer`と同型:
- `class LocalAiRecoveryContextualizer(private val gateway: LocalAiGateway)`
- `suspend fun contextualize(basePlan: RecoveryPlan, context: RecoveryContext): RecoveryContextualizationResult`
- 内部`internal fun overlay(base: RecoveryPlan, ai: AIRecoveryResponse): RecoveryPlan`: `ai.options`を`semanticAction`をキーとした`Map`へ変換し、`base.options`を走査して一致する`semanticAction`があれば`option.copy(explanation = aiExplanation)`。**Planの`overlay`はExecutionStepType別キュー消費（同一typeのstepが複数あり得るため）だが、Recoveryは1`RecoveryPlan`内で`semanticAction`が重複しない（`BasicRecoveryEngine`の生成規則上、A/B/C/Dは各々高々1件）ため、単純な`Map`引き当てで足り、Planより単純になる**。マッチ不能（未知semanticAction／supply不足）の候補は`explanation=""`のまま＝既存の`RecoveryOptionText`静的解決へper-option縮退する（L4、§4.4）。
- `RecoveryContextualizationResult`: `Applied(plan: RecoveryPlan, response: AIRecoveryResponse, metrics: AiMetrics)` / `Unchanged(plan: RecoveryPlan, reason: AiFallbackReason)`（`ContextualizationResult`と同型）。

`RecoveryEngine`は実装しない（重要な発見1）。`RecoveryViewModel`は`recoveryEngine: RecoveryEngine`（既存、`BasicRecoveryEngine`固定・無変更）に加え、`PlanReviewViewModel`の`aiPlanContextualizer`と同型の`aiRecoveryContextualizer: LocalAiRecoveryContextualizer? = null`（既定`null`、後方互換）を追加する。`buildRecoveryContext`→`recoveryEngine.createRecoveryPlan`でBasic結果を即時`RecoveryUiState`へ反映した後、`aiRecoveryContextualizer`が非nullなら`viewModelScope.launch`で非同期に`contextualize`を呼び、`Applied`なら該当optionsのみ`explanation`を差し替える（「Basic即時表示→AI後差し替え」という、`PlanReviewViewModel`が`aiPlanContextualizer`で既に確立している非同期UXパターンをそのまま踏襲する。マスター仕様書側の具体的な節番号は未確認のため断定を避け、既存実装コード〔`PlanReviewViewModel`・`LocalAiPlanContextualizer`〕を直接の根拠として引用する）。

**stale-write防御（オーケストレーター指摘A9、`PlanReviewViewModel`が確立済みのパターンの踏襲）**: `PlanReviewViewModel`は`latestBase`/`latestAiResponse`の2つの`MutableStateFlow`を`combine`し、`AiResponseCache(eventId, result)`で結果をタグ付けして`aiCache.eventId != base.event.id`のときは「AI推論中」として無視する（イベント再選択によるstale-write防止）。**アーキテクチャ差異の確認**: 現行`RecoveryViewModel`は`init`ブロックで`createRecoveryPlan`を**一度だけ**呼ぶ設計であり（`sharedPlanViewModel.selectedEvent`のような再選択可能なFlowを購読していない）、Planのような「別イベント選択で新しいbaseが来る」シナリオは現状存在しない。したがって本フェーズでは、`init`の単発呼び出しを`private suspend fun refresh()`（`SettingsViewModel.refresh()`と同じ命名規約）へ抽出し複数回呼び出し可能な形にしたうえで、`refresh()`実行のたびにインクリメントする`private var computationGeneration: Int`（世代トークン方式、A9本文が許容する代替案）を導入する。AI応答書き込み時は`if (generation == computationGeneration)`のときのみ`_uiState`へ反映する。**現状Phase 9のUIには`refresh()`を2回呼ぶ導線がない**ため本ガードは「常に満たされる」no-op相当だが、(a)将来Reality Checkの再評価等でRecoveryが再トリガーされる拡張点を安全にし、(b)`viewModelScope`のキャンセル漏れ（`onCleared`後の遅延コールバック）に対する構造的な保険になる、という2点の価値がある（この位置づけを完了報告で明記する）。

### 3.2 F-B: `generateRecovery`のmodelPath配線是正（ADR-0062同型）

`LocalLanguageModel.generateRecovery`のシグネチャを次のとおり変更する:
```kotlin
suspend fun generateRecovery(
    context: RecoveryContext,
    options: List<RecoveryOption>,
    modelPath: String,
    samplingPolicy: SamplingPolicy = SamplingPolicy.Primary
): String
```
`generatePlan`と同じ理由（ADR-0062決定5「Gatewayが検証・選択したモデルとEngineが実ロードするモデルを構造的に一致させる」）でmodelPathを明示伝搬する。戻り値も`generatePlan`（ADR-0045）と揃え`AIRecoveryResponse`ではなく**生JSON文字列**にする——Phase 7時点の`AIRecoveryResponse`直接返却契約は、Plan生成が既に廃止した「誰が生JSONへ再シリアライズするのか」という同じ往復問題をRecoveryにも残していた。`options`引数を追加する理由は§3.3参照。

`LocalAiGateway`は`checkInstalledModel()`→`resolveInstalledEntry()`（Phase 8.5 F-A既存、`ModelSelector`/auto既定を含む）を**そのまま再利用**する。generateRecovery専用のモデル解決経路は作らない（オーケストレーター確定事項）。`generatePlan`と同じ`invokeModel`ヘルパーを拡張し`modelPath`計算・Engine再ロード判定（`EngineLoadPolicy`）はPlanと共通化する。

### 3.3 F-C: `RecoveryJsonSchema`・`RecoveryPromptBuilder`・`RecoverySchemaValidator`新設

`AIRecoveryOptionResponse`を次のとおり再設計する（契約変更、ADR-0063決定対象）:
```kotlin
data class AIRecoveryResponse(val options: List<AIRecoveryOptionResponse>)
data class AIRecoveryOptionResponse(val semanticAction: String, val explanation: String)
```
`skippedStepIds`・`displayText`（旧`actionType`はJSON上`semantic_action`のenum echoに統合）を**削除する**。理由:
1. コーディネーター確定事項「AIが触れるのは説明文言のみ」の直接実装。`skippedStepIds`はUUID文字列のLLM生成を要し、`SchemaValidator`のKDocが既に指摘する「skippedStepIdsのUUID変換を要する」検証コストと、UUIDが不透明ゆえ誤り検出が難しいという固有リスクを完全に除去する。
2. `displayText`（title相当）は`RecoveryOptionText.resolveRecoveryOptionTitle`の既存静的解決（semanticActionキー4値、ADR-0018拡張）を維持し変更しない。AIが触れるのは`explanation`一本のみとする。
3. `RecoveryOptionResponse`が返す`semanticAction`は**Kotlin側が入力として渡した集合のecho**であり、LLMが新規に発案するものではない（後述のpairing検証）。

`ai/schema/RecoveryJsonSchema.kt`（新設、`PlanJsonSchema`と同型）: `RecoveryActionType`enum（`KEEP_ALL_STEPS`/`SKIP_OPTIONAL_STEPS`/`SKIP_OPTIONAL_AND_IMPORTANT_STEPS`/`CHANGE_TRANSPORT_MODE`、`BasicRecoveryEngine`の`SEMANTIC_*`定数と1:1）を単一情報源にJSON Schema文字列を組み立てる。`options`配列は`minItems=1・maxItems=3`（§32上限）、各要素`semantic_action`（enum制約）・`explanation`（`minLength=1・maxLength=60`、Planと同じ上限）、`additionalProperties: false`全階層。

`ai/prompt/RecoveryPromptBuilder.kt`（新設、`PlanPromptBuilder`と同型の3メソッド構成）:
- `build(context: RecoveryContext, options: List<RecoveryOption>): String` — `[EVENT]title="…" location="…"[/EVENT]`の区切りトークンに加え、`[OPTIONS]`ブロックで`options`の`semantic_action`一覧のみを列挙する（skippedStepIds・estimatedArrival・座標は**渡さない**——§15・PII最小化の両方の理由。事前確認事項「RecoveryPromptBuilderが参照できる情報」への回答=**title・locationName・semanticAction一覧のみ**、`PlanPromptBuilder`の`title`/`locationName`/`startTime`のうち`startTime`は不要のため渡さない（Recoveryのexplanationは「今から何をすべきか」の説明であり出発予定時刻の文脈は不要と判断、必要であれば§12で再検討）。**（Gemini G6対応）** `[OPTIONS]`ブロックは「以下の`semantic_action`それぞれについて1件ずつexplanationを返すこと。1件も欠かさず・新しい値を作らず」という**echo必須集合であることを明文で強調**する（RecoverySchemaValidatorのpairing検証と対になる指示、§3.3後段参照）。
- `buildSystemInstruction(locale): String` — Planのルール1〜5を踏襲しつつ、ルール1を「semantic_actionは与えられたOPTIONSの値をそのまま echo する（新しい値を作らない）」へ差し替える。**（Gemini G2対応・プロンプト側整合）** ルール3（Planの「時刻・数値等を出力しない」相当）を「NEVER output clock times, dates, minutes, numbers, or URLs — those are computed/shown elsewhere (this app displays ETA on a separate, dedicated label)」へ明文化し、explanationが時刻表示と重複・矛盾しないことをプロンプト側からも二重に指示する（既存`ContentSanityChecker`の捏造検出＝コード側の強制と対になる、プロンプト側の予防）。
- `buildFewShot(locale, shotCount): List<PromptExample>` — 新規の模範例プールを持つ（§4.2でL2 R1の入力として再利用するため`RecoveryPromptBuilder`のfew-shot seedsは`internal`公開のアクセサ経由でReadできるようにする、詳細§4.2）。

`ai/schema/RecoverySchemaValidator.kt`（新設、`SchemaValidator`と対になる、KDoc既に「Phase 9でgenerateRecoveryを実装する際に対となる検証メソッドを追加する」と明記済み）: 形式検証（enum・件数・長さ・additionalProperties）に加え、**pairing検証**を行う——返却された`semanticAction`の集合が、入力で渡した`options`の`semanticAction`集合と完全一致（順不同・重複禁止）しなければ`Invalid`。この検証はSchemaValidator層（①形式）に置く（重複action_type検出がContentSanityChecker（②内容）に属するPlanの先例（ADR-0047 Fable5裁定4）とは異なり、pairing不一致は「入力と出力の対応が壊れている」という**形式契約違反**であり内容sanityではないため）。

### 3.4 F-D: `LocalAiGateway.generateRecovery`本実装

Phase 7スタブ（`NOT_IMPLEMENTED_IN_PHASE7`固定）を置き換える。パイプラインは`generatePlan`と同一（§8.6踏襲）:
```text
resolveInstalledEntry() → hasAvailableMemory事前ガード → invokeModel(Primary)
  → RecoverySchemaValidator.validate（①形式＋pairing） → ContentSanityChecker.checkRecovery（②内容、L2）
  → 不合格ならinvokeModel(Retry) → 再検証（①②） → なお不合格 → AiResult.Fallback(SCHEMA_INVALID)
  → 合格 → AiResult.Success(AIRecoveryResponse, AiMetrics)
```
`AiFallbackReason`に新規値は追加しない（Plan同様`SCHEMA_INVALID`が①②双方の検証失敗を束ねる既存パターンを踏襲、非SPOF方針に沿い新規Fallback様式を増やさない）。`NOT_IMPLEMENTED_IN_PHASE7`は削除する（`enum`から値を削除する契約変更はADR-0063で記録）。

**（Gemini G9対応・JSONクレンジング方針の訂正）**: オーケストレーター指示A-8は「前置き文/コードフェンス除去→JSON抽出は既存`SchemaValidator`実装の共通処理を再利用」を求めたが、実装調査の結果、**そのような『剥がして抽出する』共通処理は`SchemaValidator`に存在しないことを確認した**（`SchemaValidator.validate`は`JSONObject(rawJson)`を直接呼びtry/catchで例外をInvalidへ写像するのみ。`SchemaValidatorTest.tSch19_markdownFencedJson_isInvalidNotSilentlyStripped`——テスト名が示すとおり「フェンス付きJSONは黙って剥がさず不合格にする」という**逆の設計思想**を回帰ロックしている）。したがって`RecoverySchemaValidator`が再利用すべきは「JSON抽出コード」ではなく**この非寛容パース方針そのもの**（生JSONを直接パースし、コードフェンス等が混入していれば剥がさず`Invalid`として扱う）である。指示の前提誤りをここに記録し、`RecoverySchemaValidator`はA-8の意図（既存パターンとの整合）を「同一の非寛容方針を独立に採用する」形で満たす。

### 3.5 F-E: `RecoveryOptionText`のAI差し替え対応

`resolveRecoveryOptionExplanation(semanticAction, eta, aiExplanation: String? = null)`（既定`null`で後方互換）に変更し、`aiExplanation`が非null・非空なら静的`stringResource`解決をスキップしそのまま返す。`title`側（`resolveRecoveryOptionTitle`）は変更しない（AIは`explanation`のみ）。`RecoveryScreen`の呼び出し側を`resolveRecoveryOptionExplanation(option.semanticAction, option.estimatedArrival, option.explanation.ifEmpty { null })`へ更新する。

**ETA検証結果（オーケストレーター検証A1）**: `RecoveryOptionText.kt:55-62`実測により、`resolveRecoveryOptionExplanation`の`eta`引数は**現状も未使用のまま**（クラスKDocが明記する意図的な設計、§7.7参照）であり、ETA自体は`RecoveryScreen`側の独立した`recovery_option_eta_label`表示（専用のETA行、testTag`"recovery_option_eta_<id>"`）で描画されることを確認した。したがってexplanation文字列は純粋に静的な説明文であり、AI差し替えは時刻情報を一切消さない・上書きしない（explanation側に時刻表現が混入すること自体をContentSanityChecker／システム指示の両方で防止する設計、§3.3・既存捏造検出ルールと整合）。

**（Gemini G1対応・レイアウト安定swap）**: `RecoveryScreen`のexplanation用`Text`コンポーザブルへ`minLines = 2`（2行分の高さを予約）を指定し、Basic静的文言→AI生成文言への差し替え時に行高が変化してタップターゲット（title・ラジオ選択・ETA表示等の周辺要素）の位置がガタつくことを防ぐ。この配線は**コミット3（UI配線）**のスコープに含める（§11参照）。

---

## §4. 品質防御ハーネス（多層設計）

Phase 8.5§12.5で実例化したQwen 0.6Bのfew-shotエコー（§2「重要な発見2」）への多層防御。**L2/L3/L5は本フェーズのスコープに含める**（Plan/Recovery共通基盤のため片方だけ守っても意味がない）。L1・性能施策（P1〜P5）は実機計測ループを要するためPhase 9.5候補として分離する（§12確認事項2）。

### 4.1 L0（既存・維持）— 構造的保証

§13「AIはdisplay_text/explanationのみ」。Phase 8.5§12.5の実例でも、誤ったexplanation（「歯科検診」）が描画された一方で時刻・ステップ順序・構造は無傷だった（§12.5「§13不変条件成立」）。本フェーズの`LocalAiRecoveryContextualizer`（§3.1）が同じ保証をRecoveryへ継承する直接の実績根拠として記載する。追加実装は不要（既存overlay方式が最初から満たす）。

### 4.2 L2（Phase 9スコープ）— ContentSanityChecker拡張2ルール

`ContentSanityChecker.check`（既存6ルールの末尾に追加）へ、Plan・Recovery両方の`displayText`/`explanation`を検査する共通メソッド`checkFewShotEcho(text: String, knownSeedStrings: Set<String>): Boolean`を追加する。**（Gemini G7は誤読だったが明確化の価値あり——checker疎結合の明記）**: `checkFewShotEcho`は`knownSeedStrings: Set<String>`を**引数として受け取るのみ**であり、`ContentSanityChecker`自身は`ai/prompt/`（`PlanPromptBuilder`・`RecoveryPromptBuilder`）を一切importしない。模範例集合は`LocalAiGateway`が両Builderの`internal`公開アクセサから`Set`として取得し、`checkFewShotEcho`の呼び出し時に渡す（依存の向きは`ai/schema/` ← `ai/`のGateway層 → `ai/prompt/`であり、`ai/schema/`から`ai/prompt/`への直接依存は発生しない）。

**R1分割（両レビュー一致・最重要）**: 単一のR1では「本物の歯科検診の予定に対し、AIが正当に『保険証を確認する』と出力したケース」まで模範例のmodelSteps出力と近似一致して誤って弾いてしまう（両レビューが一致して指摘）。したがってR1を2つに分割する。

- **R1a（模範例タイトルのecho検出、Phase 9スコープ・常時reject）**: 判定対象は`PlanPromptBuilder`／`RecoveryPromptBuilder`のfew-shot模範例`userTurn`に埋め込まれた**イベントタイトル文字列**（ja: 結婚式/歯科検診/出張/打ち合わせ、en: Wedding/Dental checkup/Business trip/Team meeting）のみ。既存`isTitleCopy`と同じ正規化＋完全一致／80%以上占有ロジックを再利用し、いずれかのタイトル文字列と一致すれば不合格。Phase 8.5§12.5の実例（「映画館」プランに無関係な「歯科検診」というタイトルそのものが出力された）はこの型であり、**どのカテゴリの実イベントであっても、他の模範例のタイトルがそのまま出力されることは絶対に正当でない**（自分自身の予定タイトルの言い換えではなく、他の予定の名前が裸で出ることは常に誤り）ため、カテゴリ一致判定なしに常時rejectしてよい。実装コストは小さい（模範例集はアプリ側静的データ、`internal val`アクセサ経由）。
- **R1b（模範例display_textのecho検出、Phase 9.5へ送る）**: 判定対象はmodelStepsのdisplay_text（例:「保険証を持って出る」）。こちらは**カテゴリ一致判定**（実イベントのカテゴリがその模範例と同一カテゴリかどうか、§4.6 L1-aのevent_type推定基盤を要する）がないと、正当な同カテゴリ出力まで誤爆する。L1-aの実装（Phase 9.5候補）を前提とするため、R1bはPhase 9のContentSanityCheckerには実装せず、Phase 9.5でL1-aとセットで導入する。

過剰reject（正当な文言を誤って弾く）のトレードオフはL5（§4.5）で監視する。

- **R2（最小品質ヒューリスティック）**: (a) 最小長（`MIN_TITLE_LENGTH_FOR_COPY_CHECK`と同じ6文字を流用、極端に短い文言=情報量ゼロを弾く）。(b) イベントタイトル単独との完全一致禁止（既存`isTitleCopy`の完全一致分岐を流用、新規実装ではなく既存ルールの適用範囲確認）。(c) **動詞相当の存在チェック（両レビュー指摘反映・修正版）**: 軽量ヒューリスティック・NLP不使用。**ja/enとも`contains`判定に統一する**（en側の`startsWith`は「Please check」等の丁寧形・従属節先頭の言い回しを不当に弾くため廃止）。語彙リストを拡充する: ja側は`する`/`しよう`/`ください`/`持って`/`確認`/`用意`/`準備`/`連絡`/`向か`/`急`/`出発`/`出る`のいずれかを含むか、en側は小文字化したうえで`bring`/`check`/`confirm`/`prepare`/`take`/`leave`/`head`/`pack`/`call`のいずれかを含むかを確認する。いずれも**最終判定ではなく「らしさ」の軽量フィルタ**であり、doubt caseは通す設計（誤検出でBasicへ落ちる方が誤情報を見せるより安全という非対称性を採用）。過剰rejectのトレードオフはL5（§4.5）で監視し、Retry（§4.3）による脱出で実害を抑える。

### 4.3 L3（Phase 9スコープ）— Retry昇格の接続

`LocalAiGateway`の既存retry機構（Primary=topK1/temp0 → 検証不合格 → Retry=topK5/temp0.15 → 再検証 → Fallback）にL2のR1/R2を**そのまま接続する**（新規のretry制御は作らない）。決定的サンプリング（topK1/temp0）はPhase 8.5§12.5が実証したとおり同一入力に対し同一エコーを再現するため、温度0のPrimaryがエコーを起こした場合は温度0.15のRetryで異なる出力に「脱出」できる可能性がある（既存`SamplingPolicy.Retry`の設計意図そのもの）。Retryも不合格なら`AiResult.Fallback(SCHEMA_INVALID)`となり、`LocalAiRecoveryContextualizer`は`Unchanged`（無加工Basic）を返す。**これは「誤ったAI文言の表示」を「Basicの静的文言表示」へ変換する安全側の縮退であり、ユーザーには「今回はAI差し替えが効かなかった」以上の実害がない**（明記事項）。

### 4.4 L4（既存・明記のみ）— per-option部分適用

§3.1の`overlay`が持つマッチベース設計（Planは型別キュー消費、Recoveryは`semanticAction`別Map引き当て）は、**一部の候補だけがL2で個別にreject／pairing不一致になっても、他の候補はAI文言のまま活かせる構造を既に持つ**——ただし本フェーズでは`RecoverySchemaValidator`のpairing検証がAll-or-Nothing（集合完全一致）であるため、1件でもR1/R2でrejectされれば①レスポンス全体を`Invalid`として扱いRetry対象にする設計とする（Recoveryは最大3件のみで独立レスポンス分割の複雑化に見合わないため、Planのようなper-step部分採用は**本フェーズでは採用しない**。既存構造がそれを可能にする土台を持つことのみ確認・記載し、実装はしない）。

### 4.5 L5（Phase 9スコープ）— AiMetricsへのreject理由・件数追加

```kotlin
enum class SanityRejectReason { FEW_SHOT_ECHO, MIN_QUALITY, TITLE_COPY, FABRICATED_CONTENT, LOCALE_MISMATCH, DUPLICATE_ACTION_TYPE, LENGTH_OUT_OF_RANGE, BANNED_WORD }
```
`AiMetrics`へ`sanityRejectCount: Int`（0〜2、Primary/Retryそれぞれでrejectされた回数）・`lastSanityRejectReason: SanityRejectReason?`（既定`null`、追加フィールドとも後方互換な追加のみ・削除なし）を追加する。**発見事項**: 現状`AiResult.Fallback`は`reason: AiFallbackReason`と`detail: String?`のみを持ち`AiMetrics`を含まない構造のため、両attemptともrejectされ完全にBasicへ縮退したケースはメトリクスが一切残らない。「fallback率」を定量化するというコーディネーター要求（§4冒頭・Phase 12定量化の土台）を満たすには`AiResult.Fallback`へ`metrics: AiMetrics? = null`（既定値付き加算、後方互換）を追加する必要がある。この契約拡張もADR-0063へ含める。T-AIMET-1許可リストテストの更新を伴う（既存の「新フィールドは許可リストで明示更新」規約を継承）。**現状`AiMetrics`を消費する画面側コードは存在しない**（`features/`配下に`.metrics`参照0件を実測確認済み）ため、本フェーズはデータ構造の整備のみでPhase 12（Analytics消費）を待つ。

### 4.6 Phase 9.5候補（実機計測ループ要）

品質・性能の両面で、机上検証では確定できず実機プローブを要する施策。本フェーズには含めない。

| ID | 施策 | 設計 | 検証方法 |
|---|---|---|---|
| L1-a | few-shotのevent_type条件選択 | `RecoveryContext.event`のカテゴリ推定に応じて模範例プールから同カテゴリの1〜2件のみを選択し、異種カテゴリの例文（＝エコー素材）自体を送らない。プロンプト短縮によりTTFT短縮とも同一レバー | AVD/実機で条件選択あり/なしのエコー再現率をA/B比較する`androidTest`プローブ |
| L1-b | マスク形式の模範例 | few-shotの`userTurn`から実タイトル文字列自体を`[EVENT_TITLE]`等のプレースホルダへ置換し、模範例自体にエコー可能な固有名詞を含めない設計へ変更 | 同上、かつSemantic Contextualization（具体的行動の質）が劣化しないかを目視評価 |
| P1 | Engine事前ウォームアップ | Settings/Recovery画面入場時に`ModelSelector.select()`結果へ`EngineLoadPolicy`を先行適用しEngineを温めておく（Phase 8.5のauto既定化で対象モデルが安定した前提が必要） | 画面入場からTTFTまでの実機計測比較 |
| P2 | few-shot条件選択（=P1-a）によるプレフィル短縮 | L1-aと同一施策（品質とTTFTが同じレバーで改善する点を明記） | 同上 |
| P3 | GPUバックエンド | A54のMali-G68でLiteRT-LMのGPUバックエンドが利用可能か`androidTest`1本で検証（Phase 7時点はCPU固定、§18申し送り4の継続課題） | 実機プローブ1本（decode tok/s・ピークRAム比較） |
| P4 | KV/プレフィックスキャッシュAPI | LiteRT-LMのリリースノートを定点観測し、対応APIが追加されたらTTFT桁改善が見込めるかを評価する（現時点では未提供） | リリースノート監視（実装タスクではない） |
| P5 | recovery系プロファイルのmaxNumTokens縮小 | Recoveryの出力は短文（explanation最大3件×60字）のためPlanより小さい`maxNumTokens`プロファイルで足りる可能性がある | `estimateMaxNumTokens`相当の値をRecovery用に別途算出し実機でメモリ/速度差を計測 |

---

## §5. 変更対象ファイル構成

### 新設
- `app/src/main/java/com/actionstarter/ai/LocalAiRecoveryContextualizer.kt`
- `app/src/main/java/com/actionstarter/ai/prompt/RecoveryPromptBuilder.kt`
- `app/src/main/java/com/actionstarter/ai/schema/RecoveryJsonSchema.kt`（`RecoveryActionType`含む）
- `app/src/main/java/com/actionstarter/ai/schema/RecoverySchemaValidator.kt`
- 対応する`Test.kt`各4本

### 変更
- `app/src/main/java/com/actionstarter/ai/AIRecoveryResponse.kt`（フィールド構成見直し、§3.3）
- `app/src/main/java/com/actionstarter/ai/LocalLanguageModel.kt`（`generateRecovery`シグネチャ変更）
- `app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（`generateRecovery`本実装、`AiResult.Fallback.metrics`追加）
- `app/src/main/java/com/actionstarter/ai/LocalAiMetrics`相当（`AiMetrics`定義箇所＝`LocalAiGateway.kt`内、L5フィールド追加）
- `app/src/main/java/com/actionstarter/ai/schema/ContentSanityChecker.kt`（L2 R1/R2追加、`checkRecovery`メソッド追加または`check`の汎用化）
- `app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（`generateRecovery`実装、1メソッド）
- `app/src/main/java/com/actionstarter/ai/prompt/PlanPromptBuilder.kt`（few-shot seedsをL2 R1が参照できる`internal`アクセサ追加、既存メソッドは無変更）
- `app/src/main/java/com/actionstarter/features/recovery/RecoveryOptionText.kt`（`aiExplanation`引数追加）
- `app/src/main/java/com/actionstarter/features/recovery/RecoveryViewModel.kt`（`aiRecoveryContextualizer`注入・非同期overlay配線）
- `app/src/main/java/com/actionstarter/features/recovery/RecoveryScreen.kt`（呼び出し引数追加のみ）
- `app/src/main/java/com/actionstarter/recovery/RecoveryEngine.kt`（KDoc是正のみ、§2「重要な発見1」）
- `app/src/main/java/com/actionstarter/di/AppContainer.kt`（`LocalAiRecoveryContextualizer`配線・`RecoveryViewModel`初期化子更新）
- 対応する既存`Test.kt`群（`AiMetricsTest`・`ContentSanityCheckerTest`・`RecoveryViewModelTest`・`RecoveryOptionDisplayTest`等）

### 非変更（明示）
- `recovery/BasicRecoveryEngine.kt`・`RecoveryPlanApplier.kt`・`LatenessDetector.kt`・`BasicRecoveryDefaults.kt`（構造・時刻計算は完全無変更、L0の直接の根拠）
- `domain/model/RecoveryOption.kt`・`RecoveryContext.kt`・`RecoveryPlan.kt`（フィールド構成無変更）
- `ai/model/`配下全体（Phase 8.5で確定済み、ModelSelector等をそのまま再利用）
- T-AIISO隔離ガード本体（新規ファイルは既存`walkTopDown`が自動網羅、§6）

---

## §6. 依存関係・技術選定の根拠

新規外部依存なし。**`RecoveryEngine`を実装する新クラスを作らずoverlay decoratorを採る理由**は§2「重要な発見1」に記載のとおりPhase 8のB1裁定をそのまま踏襲（同一問題への同一解、独自設計を持ち込まない）。**`AIRecoveryOptionResponse`から`skippedStepIds`/`displayText`を削除する理由**は§3.3のとおり。**L2をContentSanityChecker（内容sanity）へ置く理由**（SchemaValidatorではない）: few-shotエコーも最小品質も「形式は正しいが内容が不適切」という既存5ルールと同じ性質の欠陥であり、既存の責務分界（ADR-0047）と整合する。**T-AIISO隔離ガードへの影響**: 新設4ファイルはいずれも`ai/`配下のため既存ガード（`resolveMainPackageDir`ベースの`walkTopDown`）が自動的に走査対象へ含める。`recovery/`・`features/recovery/`側からの`ai/`参照は`LocalAiRecoveryContextualizer`経由のみに閉じる設計とし、`recovery/`パッケージ自体（`BasicRecoveryEngine`等）は一切`ai/`をimportしない（T-BRE-32の維持、§2で確認済み）。

---

## §7. テストケースリスト

`T-P9-*`で採番。分類ラベルはPhase 8.5と同じ規約（[Red]=新規ロジック、[born-green]=既存コードの挙動がそのまま成立、[既存書換]=既存テストの構造書換）。

### overlay/Contextualizer（`LocalAiRecoveryContextualizerTest`、Robolectric不要）

| ID | 分類 | 内容 | 種別 |
|---|---|---|---|
| T-P9-1 | 正常 | 全option一致→全explanation差し替え | [Red] |
| T-P9-2 | エッジ | AI側semanticActionが一部欠落→pairing不一致でRecoverySchemaValidatorがInvalid→Retry→なお不一致→Unchanged(無加工Basic) | [Red] |
| T-P9-3 | 異常 | gateway.generateRecoveryがFallback即返却→Unchanged、explanationは""のまま | [Red] |
| T-P9-4 | 不変条件 | overlay後もsemanticAction・skippedStepIds・estimatedArrival・options件数・順序が完全不変（§13相当） | [Red] |

### RecoveryJsonSchema/RecoveryPromptBuilder/RecoverySchemaValidator

| ID | 分類 | 内容 | 種別 |
|---|---|---|---|
| T-P9-5 | 正常 | RecoveryJsonSchema.TEXTがRecoveryActionType4値・options 1〜3件・additionalProperties:false全階層を含む | [Red] |
| T-P9-6 | 正常 | RecoveryPromptBuilder.buildが座標・skippedStepIds・estimatedArrivalを一切含まない（PII/§15チェック） | [Red] |
| T-P9-7 | 正常 | buildSystemInstructionが「semantic_actionは与えられた値のecho」指示を含む | [Red] |
| T-P9-8 | 正常 | pairing検証: 返却semanticAction集合=入力options集合→Valid | [Red] |
| T-P9-9 | 異常 | pairing検証: 返却semanticActionに重複あり→Invalid | [Red] |
| T-P9-10 | 異常 | pairing検証: 返却semanticActionが入力集合の部分集合のみ（1件欠落）→Invalid | [Red] |
| T-P9-11 | 異常 | pairing検証: 返却semanticActionに未知の値→Invalid（enum制約と二重防御） | [Red] |

### L2: ContentSanityChecker拡張（`ContentSanityCheckerTest`）

| ID | 分類 | 内容 | 種別 |
|---|---|---|---|
| T-P9-12 | 異常・実例接地 | display_text/explanationが"歯科検診"（`PlanPromptBuilder`の実在few-shotタイトルと完全一致）→R1aでInvalid（Phase 8.5§12.5実例をそのままテストデータに使用） | [Red] |
| T-P9-13 | 異常・実例接地（差し替え） | display_text/explanationが"出張"（`JAPANESE_FEW_SHOT_SEEDS`の3番目の模範例タイトルと完全一致、無関係な予定に裸で出る）→R1aでInvalid（両レビュー指摘反映、旧T-P9-13のmodelSteps display_text一致ケースはR1b＝Phase 9.5送りのため本フェーズのRedから除外） | [Red] |
| T-P9-14 | 正常 | 本物の歯科検診の予定に対しexplanationが"保険証を確認する"（R2語彙拡充後の許容表現）→R1a・R2いずれにもかからず合格する（両レビューが指摘した誤爆シナリオが、R1a限定化とR2(c)緩和の両方によって解消されていることの回帰確認） | [Red] |
| T-P9-15 | 異常 | explanationが5文字以下→R2(a)でInvalid | [Red] |
| T-P9-16 | 異常 | explanationがイベントタイトルの完全一致（既存isTitleCopyの適用確認）→R2(b)でInvalid | [Red] |
| T-P9-17 | 異常 | explanationに動詞相当の語尾/先頭語を一切含まない（例:「歯科検診について」）→R2(c)でInvalid | [Red] |
| T-P9-18 | 正常 | 動詞相当を含む自然な文言（例:「保険証を確認する」）→R1〜R2すべて通過 | [Red] |

### L3: Retry昇格（`LocalAiGatewayTest`拡張）

| ID | 分類 | 内容 | 種別 |
|---|---|---|---|
| T-P9-19 | 正常・実例接地 | Primary出力が"歯科検診"エコー→L2 reject→Retry(topK5/temp0.15)呼び出し→Retry出力が正常→Success | [Red] |
| T-P9-20 | 異常・実例接地 | Primary/Retryとも"歯科検診"エコーを再現（決定的サンプリングの再現性、§12.5「2回目生成でも同一出力」を模した二重fakeを使用）→Fallback(SCHEMA_INVALID)、無加工Basicへ縮退 | [Red] |

### L5: AiMetrics拡張（`AiMetricsTest`）

| ID | 分類 | 内容 | 種別 |
|---|---|---|---|
| T-P9-21 | 正常 | Primary成功（reject無し）→sanityRejectCount=0・lastSanityRejectReason=null | [Red] |
| T-P9-22 | 正常 | PrimaryがR1でreject後Retry成功→sanityRejectCount=1・lastSanityRejectReason=FEW_SHOT_ECHO | [Red] |
| T-P9-23 | 異常 | 両attemptともreject→Fallback.metricsにsanityRejectCount=2が記録される（AiResult.Fallback.metrics新設フィールドの検証） | [Red] |
| T-P9-24 | 回帰 | T-AIMET-1許可リストがsanityRejectCount/lastSanityRejectReason/AiResult.Fallback.metricsを含めて更新されている | [既存書換] |
| T-P9-25 | 回帰 | LocalAiPlanContextualizerTest tP8_21相当（PII非出力ガード）がRecoveryの新フィールドに対しても成立する | [born-green] |

### modelPath配線（ADR-0062同型、F-B）

| ID | 分類 | 内容 | 種別 |
|---|---|---|---|
| T-P9-26 | 正常 | generateRecoveryがLocalAiGatewayの選択したmodelPathをそのままadapterへ渡す | [Red] |
| T-P9-27 | 正常 | generateRecoveryもModelSelector/auto既定を経由する（Recovery専用解決経路が存在しないことの回帰確認） | [born-green] |
| T-P9-28 | 正常 | Engine再ロード判定（EngineLoadPolicy）がgeneratePlan直後のgenerateRecovery呼び出しで同一パスならEngine再利用される | [born-green] |

### 全体回帰

| ID | 内容 |
|---|---|
| T-P9-29 | `:app:testDebugUnitTest`既存全件Green維持。`:app:lintDebug` error 0維持。**ベースライン記録（Step 3実測、2026-08-11）**: Phase 9着手時点（Phase 8.5コミット2 `2fcb8ae`時点）はtests=681/skipped=1/failures=0/errors=0。コミット1 Step 3（Red）完了時点はtests=701/skipped=1/failures=18/errors=0。コミット1 Green（`dca7150`）後・コミット2 Step 3（Red）完了時点はtests=713/skipped=1/failures=11/errors=0。コミット2 Green（`81eec58`）後・**コミット3 Step 3（Red、UI配線スコープ）完了時点はtests=719/skipped=1/failures=5/errors=0**（新規6件のうちRed5件・born-green1件〔T-P9-33〕、既存713件は無傷、JUnit XML集計で照合）、`lintDebug` error=0・warning=23（既存分と同数、新規発生なし） |
| T-P9-30 | T-BRE-32（recovery/がai/を参照しない構造ガード）が新設ファイル追加後も引き続きGreen（recovery/自体は無変更のため自動的にborn-green） |

### stale-write防御（オーケストレーター指摘A9、`RecoveryViewModelTest`拡張）

論理的には「overlay/Contextualizer」節（§3.1）に属するが、既存T-P9-1〜30の番号を保持するため本節末尾へ追記する。

| ID | 分類 | 内容 | 種別 |
|---|---|---|---|
| T-P9-31 | エッジ・ゲート | `refresh()`を2回呼び出し（fake `recoveryEngine`が呼び出しごとに異なる`RecoveryPlan`を返す）、1回目の`refresh()`が起動したAI応答（fake `aiRecoveryContextualizer`を遅延応答させる）が2回目の`refresh()`完了**後**に到着しても、2回目のbaseへ古いexplanationが適用されない（世代トークン不一致により無視される）ことを検証する | [Red] |

### UI配線（コミット3、`RecoveryOptionDisplayTest`／`RecoveryScreenTest`／`AppContainerTest`拡張）

| ID | 分類 | 内容 | 種別 |
|---|---|---|---|
| T-P9-32 | 正常 | `resolveRecoveryOptionExplanation`に`aiExplanation`（非null・非空）を渡すとそのまま返し、静的`stringResource`解決をスキップする | [Red] |
| T-P9-33 | 正常・回帰 | `aiExplanation`がnull／空文字のときは2引数版と同一の静的`stringResource`解決結果へフォールバックする | [born-green] |
| T-P9-34 | 正常 | `option.explanation`が非空（AI差し替え済みを模す）のとき、`RecoveryScreen`は静的`stringResource`ではなくその文字列をそのまま表示する | [Red] |
| T-P9-35 | 正常・構造ガード | `RecoveryScreen.kt`のexplanation用`Text`コンポーザブルが`minLines = 2`を指定している（ソーステキスト走査、Gemini G1対応・敵対レビューA-4。ピクセル単位の高さ比較はフォントメトリクス依存で脆いため不採用とし、§10実機受け入れ手順6の目視確認を最終検証手段とする設計判断） | [Red] |
| T-P9-36 | 正常・構造ガード | `AppContainer.localAiRecoveryContextualizer`が`by lazy`プロパティとして存在し、構築時点では未初期化である（`localAiGateway`のT-P7DI-2と同型） | [Red] |
| T-P9-37 | 正常・構造ガード | `AppContainer.createViewModelFactory`の`RecoveryViewModel`初期化子が`aiRecoveryContextualizer = localAiRecoveryContextualizer`を配線している（ソーステキスト走査。`localAiPlanContextualizer`自体にも専用のランタイム検証テストが存在しない既存の踏襲） | [Red] |

---

## §8. エラー＆レスキューマップ

| 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|
| Recovery AI呼び出し全体 | モデル未導入／AI OFF／端末非対応 | 既存`resolveInstalledEntry`/`checkInstalledModel`ガードがそのまま働きFallbackへ。`LocalAiRecoveryContextualizer`はaiRecoveryContextualizerがnullまたはFallback時にUnchanged | 常にBasic文言で成立（§19非SPOF） |
| pairing不一致 | LLMが一部semanticActionを欠落／重複／捏造 | RecoverySchemaValidatorが①形式層でInvalid判定→Retry→なお不一致→Fallback(SCHEMA_INVALID) | Basic静的文言へ縮退。クラッシュなし |
| L2 few-shotエコー(R1) | LLMが模範例文字列を無関係な予定へ出力（Phase 8.5§12.5実例） | ContentSanityCheckerがInvalid→Retry→なお不合格→Fallback | Basic静的文言へ縮退。誤情報は表示されない |
| L2 過剰reject | 正当な文言が偶然R1/R2条件に合致し誤って弾かれる | Retryで温度を上げて再生成を試行。再rejectでもBasicへ縮退するだけでサイレントではない（L5がreject理由・件数を記録） | 稀にAI差し替えが効かずBasic表示になるのみ。誤情報表示ではない |
| RecoverySchemaValidator/JSON解析失敗 | 壊れたJSON・前置き文混入等 | 既存SchemaValidatorと同型のtry/catch、例外を外へ投げず`Invalid`へ写像 | Basic静的文言へ縮退 |
| Engine例外（OOM等） | 推論中のOutOfMemoryError等 | 既存`LocalAiGateway`の二次防御（catch OutOfMemoryError）がそのまま適用、generateRecoveryも同一invokeModelヘルパーを通る | Basic静的文言へ縮退。クラッシュなし |
| `AiResult.Fallback.metrics`欠落 | 旧呼び出し元が`metrics`未設定のまま構築 | 既定値`null`により既存コンパイル済みコードは無改修で動作。L5メトリクスが取れないだけで機能は縮退しない | 影響なし（観測性の低下のみ、機能面はBasicで成立） |
| RecoveryViewModelの非同期AI差し替え中に画面遷移 | ユーザーがAI応答を待たず「Use this plan」をタップ | 既存`useThisPlan`はUiState時点のoptionを使うため、Basic文言のまま適用されるだけで不整合は生じない（Planと同型の既存挙動） | 説明文言がBasicのままになるだけ。適用される候補・時刻は不変 |
| §32 option 3「Prepare a delay message」を含める場合 | 自動送信の誤操作リスク（§61） | 共有Intentのみ（送信はOS標準アプリ側でユーザーが最終操作）、アプリ側は文面生成とIntent起動のみ行い送信ボタンを持たない | §12確認事項1で要否・設計を確定してから着手（本表は含める場合の設計方針の事前記録） |
| Plan/Recovery AI呼び出しの同時発生（Gemini G3対応・オーケストレーター指示A-6） | `PlanReview`→`Execution`遷移が`popUpTo`を伴わないため（`ActionStarterNavHost.kt`実測: `Destinations.Execution.route`への`navigate()`にpopUpTo指定なし）、`PlanReviewViewModel`の`viewModelScope`は`Execution`到達後も生存し続ける。その状態でRecoveryが発火すると、`generatePlan`と`generateRecovery`が同一の`LocalAiGateway`インスタンスへ同時に到達しうる。**実装調査で確認**: `LocalAiGateway`は既に`private val inferenceMutex = Mutex()`（T-GW-15、`LocalAiGatewayTest.tGw15_concurrentCalls_neverOverlapInsideModel`が回帰ロック済み）を持ち、`generatePlan`は`checkInstalledModel()`から`invokeModel()`（実際の`model.generatePlan(...)`呼び出しを含む）までの**全パイプラインを`inferenceMutex.withLock { }`で包んでいる**（`LocalAiGateway.kt:185-206`実測）。したがって`generateRecovery`の実装（§3.4）が同じ`inferenceMutex.withLock { }`パターンをそのまま踏襲しさえすれば、Gatewayレベルで既にPlan/Recovery呼び出し（実推論呼び出しを含む）が完全に直列化される。 | `generateRecovery`の実装で`inferenceMutex.withLock { }`（既存`generatePlan`と同一パターン）を必ず使う、という実装規律として明記する（新規ロック機構は導入しない・既存Mutexの保護範囲変更も不要）。Basic即時表示は本ロックの外側（`RecoveryEngine.createRecoveryPlan`は`ai/`を参照しない）のため非ブロックのまま。 | 同時発生時はRecovery（またはPlan）のAI差し替えが数秒〜数十秒遅延するのみ。Basic文言は両画面とも即時表示のままで機能上のブロックはない |

---

## §9. ADR起票方針（ADR-0063想定）

起票直前の再確認（既存慣行）: 本計画書起案時点で`grep -n "^### ADR-" DECISIONS.md | tail -3`を実行し、最新確定ADRは**ADR-0062**（Phase 8.5 F-B）であることを確認済み。したがって本計画の決定は**ADR-0063**（暫定）として、Step 4実装完了後に同じ手順で再確認のうえ正式起票する。記録予定の決定:
1. `RecoveryEngine`を実装する新クラスを作らず、`LocalAiPlanContextualizer`と同型の`LocalAiRecoveryContextualizer`（overlay decorator）を採用する（Phase 8 B1裁定の踏襲）
2. `AIRecoveryOptionResponse`から`skippedStepIds`/`displayText`を削除し`semanticAction`＋`explanation`のみに縮小する。`generateRecovery`の戻り値を`AIRecoveryResponse`から生JSON文字列（`String`）へ変更する（ADR-0045同型）
3. `generateRecovery`へ`modelPath`引数を追加する（ADR-0062決定5の同型是正）
4. `ContentSanityChecker`へL2 R1（few-shotエコー検出）・R2（最小品質ヒューリスティック）を追加する
5. `AiMetrics`へ`sanityRejectCount`/`lastSanityRejectReason`を、`AiResult.Fallback`へ`metrics: AiMetrics?`を追加する（いずれも既定値付き追加、既存呼び出し元は無改修）
6. §32 option 3「Prepare a delay message」の扱い（§12確認事項1の結論をここへ反映）
7. `generateRecovery`の実装は`LocalAiGateway`の既存`inferenceMutex.withLock { }`（T-GW-15、`generatePlan`と同一パターン）を必ず経由する。既存Mutexの保護範囲は変更しない（§8「Plan/Recovery AI呼び出しの同時発生」参照、実装調査により追加のロック機構・範囲変更は不要と判明）

**再検討トリガー（Gemini G6対応）**: L5実測（§4.5）で、pairing不一致（`RecoverySchemaValidator`のAll-or-Nothing全体reject）に起因するFallback率が高いことが判明した場合、All-or-Nothing方式をBest-Effort部分適用（§4.4 L4が既に持つ構造——一致した候補のみexplanationを適用し、不一致だった候補のみBasic静的文言のまま残す設計）へ緩和することを検討する。Plan/Recovery同時発火時の`inferenceMutex`直列化による遅延がPhase 9.5のP1（Engine事前ウォームアップ）等で実機体感上問題になった場合は、直列化方式自体（Gateway単位での1推論ずつ）を再評価する。

---

## §10. 実機受け入れ手順（A54）

1. Phase 9実装後のアプリをA54実機（Phase 8.5§12と同じ日常使用状態）へインストールする。
2. **実予定を使わない安全な遅延シナリオを作る**: カレンダーに新規テスト予定（例:「テスト用_Recovery確認」）を、現在時刻から準備時間より短い間隔で作成する（既存Phase 6 G4-Eが使ってきたDEBUG「Simulate delay」ボタン（`ExecutionScreen`、T-NAV-3/T-E2E-2で維持確認済み）を優先利用し、実カレンダーの操作を最小化する）。
3. Recovery画面へ遷移し、A〜D案の`explanation`がAI生成文言（Basic静的文言と異なる自然文）で表示されることを確認する。
4. Settings画面で見た推奨モデル（Phase 8.5§12のQwen 0.6B自動選択）がそのままRecoveryでも使われることをログ/`selectedModelId`で確認する（generateRecovery専用の解決経路がないことの実機確認）。
5. 意図的に模範例と同名のテスト予定（例:「歯科検診」）でRecoveryを発生させ、L2 R1aが実機でも機能する（模範例タイトルの裸のechoがBasic文言へ縮退する、またはRetryで正常文言になる）ことを確認する。
6. **（Gemini G1対応・レイアウト目視確認）**: Basic静的文言からAI生成文言への差し替え瞬間を目視し、explanation行の高さ（`minLines=2`予約分）が変化せず、title・ラジオ選択・ETA表示等の周辺タップターゲットの位置がガタつかないことを確認する。
7. スクリーンショットを取得し、本計画書または完了記録へ証拠として残す。

---

## §11. コミット粒度

3コミット構成を提案する:
- **コミット1（F-A/F-B/F-C基盤）**: `LocalAiRecoveryContextualizer`・`RecoveryJsonSchema`・`RecoveryPromptBuilder`・`RecoverySchemaValidator`新設＋`generateRecovery`のmodelPath/戻り値契約変更＋`LocalAiGateway.generateRecovery`本実装（既存`inferenceMutex`パターンを踏襲、§8・§9決定7）＋対応テスト（T-P9-1〜11・26〜28）。
- **コミット2（L2/L3/L5品質防御ハーネス）**: `ContentSanityChecker`拡張・Retry接続・`AiMetrics`/`AiResult.Fallback`拡張＋対応テスト（T-P9-12〜25）。基盤（コミット1）に依存するため分離してレビュー・リバートを容易にする。
- **コミット3（UI配線）**: `RecoveryOptionText`・`RecoveryViewModel`（stale-write防御の`refresh()`抽出＋世代トークン含む）・`RecoveryScreen`（`minLines=2`のレイアウト安定swap含む、Gemini G1対応）・`AppContainer`配線＋全体回帰（T-P9-29〜30）。

理由: コミット1は「AIが生成できる」という基盤、コミット2は「生成された内容の安全性」、コミット3は「実際に画面へ出す」という段階的な検証境界に対応し、各コミット時点でテスト全件Green・lint 0を維持できる。

---

## §12. ユーザー確認事項（Pass 2）— 全件【確定】（ユーザー委任・オーケストレーター推奨確定、2026-08-11）

1. **§32 option 3「Prepare a delay message」を本フェーズに含めるか**: 【確定】**Phase 10以降へ再送り**（推奨どおり）。
   - 参考（起案時点の両論併記）: 含める案＝§61/§34整合の共有Intent限定設計（コミット1本相当の工数）。再送る案＝§88基準（「予定を今やる一つの行動に変えることに直接寄与するか」）への適合度が低く「対外連絡」という質的に異なるリスク領域を本計画の主目的と混在させない。
2. **L1（few-shot再設計）・P1〜P5（性能施策）をPhase 9に同梱するかPhase 9.5へ分離するか**: 【確定】**Phase 9.5へ分離**。ただしPhase 9のG4-E（実機受け入れ）実施時に、L1-a/P3等の実機挙動を「ついでに」観察しログへ残すことは許可する（実装は伴わない観察のみ、§10手順への追加実装はしない）。
3. **エコー検出ルール（L2）のスコープ**: 【確定】**含める**（ユーザーが要望したハーネスそのもの）。R1a・R2はPhase 9スコープ、R1bはL1-a基盤を要するためPhase 9.5（§4.2・A-1）。
4. **AiResult.Fallbackへのmetrics追加（§4.5・§9決定5）**: 【確定】**承認**。現状消費者がいない先行実装だが、Phase 12のAnalytics基盤が来た時点で過去のFallbackケースのデータが欠落したまま設計されるのを避けるための先行対応として実施する。
5. **RecoveryPromptBuilderの入力からstart_time（出発予定時刻の文脈）を除く判断（§3.3）**: 【確定】**現行案どおり維持**（渡さない）。Phase 9.5で「間に合わせる」文脈の要否を再検討する余地は残す。

---

## §13. 敵対的レビュー記録（オーケストレーター＋Gemini 3.5-flashクロス、2026-08-11）

初稿ドラフトに対する2系統レビューの指摘・採否を全件記録する。

### 採用（設計変更）

| No | レビュー元 | 指摘要約 | 反映箇所 |
|---|---|---|---|
| A-1 | 両レビュー一致（最重要） | 単一R1では本物の同カテゴリ予定への正当な出力（例:「保険証を確認する」）まで模範例と近似一致して誤爆する | §4.2をR1a（模範例タイトルecho・Phase 9常時reject）／R1b（模範例display_textのecho・カテゴリ判定要・Phase 9.5送り）へ分割。T-P9-13をR1a実例へ差し替え、T-P9-14をR1a/R2すり抜け確認の[Red]へ変更 |
| A-2 | 両レビュー一致 | R2(c)のen側`startsWith`が丁寧形（"Please check"等）を誤って弾く。語彙が手薄 | §4.2 R2(c)をja/enとも`contains`判定に統一、語彙をja12語・en9語相当へ拡充 |
| A-3 | Gemini G2 | プロンプト側にも「数字・時刻・URLを含めない」を明文化すべき | §3.3 `buildSystemInstruction`ルール3へ追記（ETAはアプリが別途表示する旨を明記） |
| A-4 | Gemini G1 | Basic→AI差し替え時のレイアウトジャンプ（タップターゲットのガタつき）リスク | §3.5へ`minLines=2`予約を追記、§10受け入れ手順・§11コミット3スコープへ反映 |
| A-5 | オーケストレーター指摘A9 | `RecoveryViewModel`にPlanReviewViewModel同型のstale-write防御がない | §3.1へ`refresh()`抽出＋世代トークン設計を追記、T-P9-31（stale-write防御テスト）を追加 |
| A-6 | Gemini G3 | Plan/Recovery推論の同時発生時の挙動が未検討 | §8へ行追加。**実装調査により指摘内容を精緻化**（後述「実装調査による追加発見」参照） |
| A-7 | Gemini G6 | pairing All-or-Nothingの再検討条件が未定義 | §9へ「再検討トリガー」を新設、§3.3の`[OPTIONS]`ブロックのecho必須集合の明記を強調 |
| A-8 | Gemini G9 | JSONクレンジング処理をSchemaValidatorと共通化すべき | §3.4へ反映。**実装調査の結果、指摘の前提（共通処理の存在）が誤りと判明**（後述） |
| A-9 | オーケストレーター検証A1 | explanation差し替えがETA表示を消さないことの根拠確認 | §3.5へ`RecoveryOptionText.kt:55-62`実測根拠を追記 |
| A-10 | Gemini G7（誤読だが明確化に価値あり） | `ContentSanityChecker`と`ai/prompt/`の依存方向の明確化 | §4.2冒頭へchecker疎結合の設計（`Set<String>`引数受け取りのみ、import禁止）を明記 |

### 棄却（理由を記録）

| No | レビュー元 | 指摘要約 | 棄却理由 |
|---|---|---|---|
| R-1 | Gemini G2 | `ContentSanityChecker`の既存digit-ban（捏造検出）ルールを緩和すべき | §15「時刻演算をLLMに渡さない」の直接実装であり正しい既存ルール。緩和すると数字・時刻の捏造をexplanationが通過し得るため不採用 |
| R-2 | Gemini G8 | バイナリ互換性への懸念（契約変更の影響範囲） | 本アプリは単一モジュール・単一APKで配布され、`AIRecoveryResponse`等の型は外部公開APIではないため該当しない（外部SDK等での懸念であり本プロジェクトの構成には非該当） |
| R-3 | Gemini G1 | AI応答完了までRecovery画面の描画自体を待機すべき（先に見せない） | §19「Local AIはEnhancement、SPOFにしない」に違反する。Basic即時表示は本計画の全編を通じた不変の前提であり、これを崩す提案は不採用 |

### 実装調査による追加発見（レビュー起票時には未確認だった事実）

計画書修正の過程で、A-6・A-8について**指摘そのものの前提を実装コードで検証した結果、両方とも前提の一部が事実と異なることが判明した**。捏造せず、実装調査の結果を優先してそれぞれ以下のとおり訂正した。

1. **A-8（JSONクレンジング共通化）の前提誤り**: `SchemaValidator`に「前置き文/コードフェンスを剥がしてJSONを抽出する」処理は実在しない。`grep`実測と`SchemaValidatorTest.tSch19_markdownFencedJson_isInvalidNotSilentlyStripped`（テスト名が「黙って剥がさず不合格にする」という逆方針を明示）で確認済み。`RecoverySchemaValidator`は「共通処理の再利用」ではなく「同一の非寛容パース方針の独立採用」として設計した（§3.4）。
2. **A-6（Mutex直列化）の調査過程の自己訂正**: 当初の調査で`ai/adapter/LiteRtLmLocalLanguageModel.kt`の`engineLifecycleMutex`のみを確認し、これが`obtainEngine`（Engine取得）のみを保護し実際の推論呼び出し（`Conversation.sendMessage`）はmutexの外側で実行されることを発見したため、「Plan/Recovery推論は直列化されていない未対策の隙間」と一度結論した（この時点で計画書へも誤った結論を反映していた）。その後、`AiFallbackReasonTest.kt`のコメント（「T-GW-15のMutex直列化採用」）を手がかりに`LocalAiGateway.kt`を再確認したところ、**`LocalAiGateway`自身が別途`inferenceMutex`（T-GW-15、既存テスト`tGw15_concurrentCalls_neverOverlapInsideModel`が回帰ロック済み）を持ち、`generatePlan`の`checkInstalledModel`〜`invokeModel`（実推論呼び出しを含む）の全区間を`inferenceMutex.withLock { }`で包んでいる**ことを確認した。当初の結論は調査範囲が不足しており誤りだったため訂正し、正しくは「`generateRecovery`が既存`inferenceMutex`パターンを踏襲すれば、Gatewayレベルで既に実推論を含めた完全な直列化が成立する」である（§8・§9決定7を訂正済みの内容へ更新）。
