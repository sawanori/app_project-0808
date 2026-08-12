# Phase 12 実装計画書 — Basic/AI比較実験

> 対象仕様: `Action_Starter_Master_Specification_v2.0_Android.md`§76「Basic/AI Experiment」（Phase 12本体）・§36「Basic vs Local AI比較」（Developer Settingsという実装手段を規定するが本フェーズでは不採用、§13参照）・§55「MVP KPI」・§56「Basic vs AI KPI」（本フェーズでは明示委譲、§2・§13参照）
> 前提基盤: Phase 9.5 PR-1（`GpuBackendProbeTest`）／M（`PerformanceBaselineProbeTest`）が確立したandroidTestプローブ方式（`@Ignore`既定・`-e class`個別実行・実機依存）・Phase 9の`AiResult`／`AiMetrics`／`ContentSanityChecker`（本フェーズの唯一のデータ源、Room等の永続化を経由しない）・`docs/plans/phase7-quality-harness.md`§8（文脈適合度を主指標とする既定決定）・ADR-0064決定3（`EventCategoryClassifier`＋16 few-shot seed、本フェーズの中核からは除外、§13参照）
> 種別: 計測・実験フェーズ（3系統レビュー〔オーケストレーター＋Gemini 3.5-flash＋独立検証役〕を経た修正版。「AIをより賢くする」実装は行わない）
> 承認状態: **Phase 12完了（実験結果ネガティブ・AI既定OFF維持を結論・2026-08-12）。C1=`6ebfc55`。実機実験・集計・ユーザー盲検採点はオーケストレーターが実施（§14参照）。**

---

## §0. 結論ファースト

Phase 12は当初案（Developer Settings画面・`AnalyticsStore`拡張・Room スキーマ改修を伴う恒久機能化）から、3系統レビューを経て**大幅に簡素化**された。中核は単一のandroidTestプローブ`BasicAiComparisonProbeTest`（Phase 9.5 PR-1の`GpuBackendProbeTest`と同型）であり、`LocalAiGateway.generatePlan()`を直接呼び出し、その場で`AiResult`（`Success`/`Fallback`いずれも`AiMetrics`を保持）から機械副指標を読む——**ViewModel層・`AnalyticsStore`・Room永続化のいずれも経由しない**。この設計により、当初案が必要としていたスキーマ移行・`ContextualizationResult`型拡張・ViewModel変更は一切不要になった（実コードで再確認済み、詳細は§2）。

測定指標はユーザー確定どおり**ハイブリッド**とする: 機械副指標（reject理由内訳・エコー率・差別化率・TTFT/tok/s・Applied率）は固定30イベント全件に対しプローブが自動収集し、主指標である文脈適合度は約10件をオーケストレーターが盲検化しユーザーが3軸で採点する少数の人手評価に委ねる。スコープはユーザー確定どおり**中間**（Developer Settings画面は作らない、恒久機能化しない）。条件はA（Basic）／B（AI・現行の無条件few-shot）の2条件のみとし、ドーマントのカテゴリ分類器（C条件相当）は盲検の単純化のため中核実験から外す。

固定30イベント（L1: seed非依存の日常10件／L2: seedと近縁だが別語10件／L3: AIが苦手な不規則10件）は事前登録し恣意選択を防ぐ。仕様§56の4行動ファネル指標は、プローブ環境では原理的に収集不能な3指標（起点イベント自体が存在しない準備開始率・再利用率、編集機能が未実装のプラン変更率）と、収集はできても人工的に100%になり実利用ログでのみ意味を持つ1指標（Recovery受容率）から成るため、本フェーズでは明示的に将来の実利用フェーズへ委譲する。撤退基準を事前登録し、ネガティブな結果（「現行端末内0.6BはBasic固定文言に文脈適合で勝てない」）もそのまま報告できる設計とする。

---

## §1. 目的・背景

Phase 10でC-18（行動ログ）は解消済みだが、本フェーズは当初その延長線上に「`AnalyticsStore`を使った恒久的な実験基盤」を構想していた。3系統レビュー（オーケストレーター・Gemini 3.5-flash・独立検証役）の結果、この構想は「実験1回の実施に対して過剰な永続化投資である」という指摘（検証役確認済み）を受けて撤回された——`AiResult`が`Success`/`Fallback`いずれも`AiMetrics`（`sanityRejectCount`／`lastSanityRejectReason`／`firstTokenMs`／`tokensPerSecond`等）を保持しており、`LocalAiGateway`を直接叩けばその場で全指標が揃うため、Phase 9.5のPR-1・M（`GpuBackendProbeTest`／`PerformanceBaselineProbeTest`）と全く同じ「使い捨てandroidTestプローブ」で十分と判断した。

Phase 9.5のF-1（few-shotカテゴリ条件選択）は2回連続の品質退行によりロールバックされたが、その判定は二値Fallback（合格/不合格）のみに基づいていた。本フェーズは、より精緻な指標（reject理由の内訳・エコー率・人手による文脈適合度）でBasicとAIの差を初めて定量的に可視化する試みであり、F-1が有望と判明した場合の追試候補として1行の記載に留める（C条件は中核実験から除外、§3.5）。

---

## §2. 仕様整合（事前確認結果）

- **§76／§36／§55／§56の原文**: 前ラウンドの調査結果を維持する。§76は「内部切替。データログ。同一ユーザーで比較可能。」の3行のみ。§36はDeveloper Settingsの「Planner Mode」トグルを実装手段として規定するが、**本フェーズはこれを採用しない**（§13参照、理由は中間スコープ判断）。§56「Basic vs AI KPI」は4つの行動ファネル指標（Preparation Start Rate／Plan Modification Rate／Reuse／Recovery acceptance、いずれもBasic vs AI）を定めるが、テキスト品質・意味的妥当性への言及は一切ない。
- **`phase7-quality-harness.md`§8「Phase 12接続・主評価指標の変更」**: 既に敵対的レビュー（Gemini G1 CRITICAL）を通過済みの決定として「Phase 12の主評価指標は時刻正確性でなく文脈適合度に置く」ことを明記済み。本フェーズはこれを踏襲する。
- **§3.2削除の前提（本ラウンドの再設計で最重要、実コードで再確認済み）**: `ai/LocalAiGateway.kt:853-868`を再確認した結果、`sealed interface AiResult<out T>`は`Success<T>(val value: T, val metrics: AiMetrics)`（`metrics`は非null必須）と`Fallback(val reason: AiFallbackReason, val detail: String?, val metrics: AiMetrics? = null)`の2variantのみで構成され、**両方とも`AiMetrics`（`sanityRejectCount`／`lastSanityRejectReason`／`firstTokenMs`／`tokensPerSecond`を含む12フィールド）を保持する**ことを確認した。さらに、**既存の`PerformanceBaselineProbeTest.kt`（Phase 9.5 M実測、`app/src/androidTest/java/com/actionstarter/probe/`）が実際にこのパターンを稼働中のコードとして実証している**——`LocalAiGateway(model=LiteRtLmLocalLanguageModel(), modelStorage=ModelStorageImpl(...), modelVerifier=ModelVerifierImpl(), deviceCapability=DeviceCapabilityImpl(context), preferences=AiPreferencesImpl(...))`を直接構築し、`runBlocking { gateway.generatePlan(planningContext) }`の戻り値`AiResult<AIPlanResponse>`から`is AiResult.Success -> result.metrics.sanityRejectCount`等をその場で読み取っている（同ファイル188-223行）。Room・`AnalyticsStore`・`ContextualizationResult`・ViewModelのいずれも一切介在しない。**当初案（§3.2でのRoom/Contextualizer/AnalyticsStore配線改修）の削除は妥当と結論する。**
- **`AIPlanStepResponse`から「準備ステップ」を特定する方法**: `ai/AIPlanResponse.kt`の`AIPlanStepResponse(actionType: String, displayText: String)`と、`ai/LocalAiPlanContextualizer.kt`の`PlanActionType.toExecutionStepTypeOrNull()`（`internal`関数、7値の決定的マップ）を確認した。`PREPARE_ITEMS`／`GET_READY`／`GATHER_BELONGINGS`の3つの`actionType`が`ExecutionStepType.PREPARATION`へ写像される。本フェーズのプローブは`internal`関数への依存を避け、schema検証済みの`actionType`文字列（`"prepare_items"`／`"get_ready"`／`"gather_belongings"`）との単純な集合一致で同じ判定を独自に行う（本番の`overlay()`と意味的に同一、依存を増やさない設計）。
- **Basic側の静的文言の特定**: `features/common/StepTitle.kt`の`resolveStepTitle("preparation")`が`R.string.step_title_preparation`を返すことを確認した（`BasicPlanningEngine.kt:81-83`が`semanticId="preparation"`のステップを構築）。プローブは`InstrumentationRegistry`経由の`Context.getString(R.string.step_title_preparation)`で同じ値を取得する。
- **few-shot seedとの重複確認（Gemini H-4「事前登録・恣意選択防止」の前提）**: `ai/prompt/PlanPromptBuilder.kt`の`JAPANESE_FEW_SHOT_SEEDS`（8件: 結婚式・歯科検診・出張・打ち合わせ・誕生日会・健康診断・旅行・商談）・`ENGLISH_FEW_SHOT_SEEDS`（8件、英語版同型）を実コードで確認した。**重要な発見**: 全16件のseedのうち`finish_current_task`（TRANSITIONステップ）の`displayText`は、ja版全8件が一言一句「今の作業を切り上げる」、en版全8件が一言一句"Wrap up what you are doing"で完全に同一——つまりこれはAI応答が**ステップ本文をそのまま逐語コピーしうる最も検出されやすい実例**であり、R1b（`checkFewShotEcho`はタイトルのみを検査対象とし、`modelSteps`の`displayText`自体のエコーは検査しない、未実装）が捕捉できない具体的な穴であることを実コードで裏付けた（§3.3・§13で詳述）。

---

## §3. 機能一覧と仕様

### 3.1 中核: `BasicAiComparisonProbeTest`（androidTest、`@Ignore`既定）

Phase 9.5 PR-1（`GpuBackendProbeTest`）・M（`PerformanceBaselineProbeTest`）と同型の使い捨てプローブ。`app/src/androidTest/java/com/actionstarter/probe/BasicAiComparisonProbeTest.kt`に新設する。`@RunWith(AndroidJUnit4::class)`＋`@org.junit.Ignore`（`-e class`個別実行、`connectedDebugAndroidTest`一括実行の対象外）。

固定30イベント（§3.2）それぞれについて、単一の`@Test`メソッド内で順に: (a) Basic側の準備ステップ文言（`resolveStepTitle("preparation")`相当の固定文字列、全イベント共通）、(b) `LocalAiGateway.generatePlan(planningContext)`を直接呼び出しAI側の準備ステップ`displayText`（`actionType`が`prepare_items`／`get_ready`／`gather_belongings`のいずれかの`AIPlanStepResponse.displayText`）を取得し、per-item構造化ログ（Logcat、TAG固定）へ記録する: タイトル・層（L1/L2/L3）・カテゴリラベル・Basic文言・AI文言（Fallback時は`null`）・`Applied`/`Fallback`区分・`sanityRejectCount`・`lastSanityRejectReason`・`firstTokenMs`・`tokensPerSecond`・エコーflag（`lastSanityRejectReason == FEW_SHOT_ECHO`）・差別化flag（AI文言がBasic文言と非一致か）。

### 3.2 固定30イベントデータセット（事前登録、恣意選択防止）

L1（seed非依存の日常、10件）・L2（seedと近縁だが別語、seed16件のいずれとも逐語重複なし、10件）・L3（AIが苦手な不規則、10件）の3層×10件、計30件。全件を下表に明記する（時刻は`Asia/Tokyo`、プローブ実行時刻からの相対日数＋現地時刻）。

| No | 層 | タイトル | カテゴリラベル | オフセット |
|---|---|---|---|---|
| 1 | L1 | 美容院 | 日常 | +1日 10:00 |
| 2 | L1 | ジムでの筋トレ | 日常 | +1日 18:00 |
| 3 | L1 | 車検の予約 | 日常 | +2日 9:00 |
| 4 | L1 | PTA役員会 | 日常 | +2日 14:00 |
| 5 | L1 | 確定申告の相談 | 日常 | +3日 10:00 |
| 6 | L1 | 銀行での住宅ローン相談 | 日常 | +3日 15:00 |
| 7 | L1 | 図書館での本の返却 | 日常 | +4日 11:00 |
| 8 | L1 | 粗大ごみの回収申込 | 日常 | +4日 9:00 |
| 9 | L1 | 期日前投票 | 日常 | +5日 12:00 |
| 10 | L1 | インフルエンザ予防接種 | 日常 | +5日 16:00 |
| 11 | L2 | 眼科検診 | 医療（歯科検診近縁） | +6日 10:00 |
| 12 | L2 | 送別会 | 社交（結婚式近縁） | +6日 19:00 |
| 13 | L2 | 工場見学 | 出張（出張近縁） | +7日 9:00 |
| 14 | L2 | 社内勉強会 | 会議（打ち合わせ近縁） | +7日 13:00 |
| 15 | L2 | 還暦祝い | 社交（誕生日会近縁） | +8日 18:00 |
| 16 | L2 | 人間ドック | 医療（健康診断近縁） | +8日 9:00 |
| 17 | L2 | 帰省 | 旅行（旅行近縁） | +9日 8:00 |
| 18 | L2 | 取引先との会食 | 会議（商談近縁） | +9日 19:00 |
| 19 | L2 | 忘年会 | 社交（結婚式／誕生日会近縁） | +10日 19:00 |
| 20 | L2 | 株主総会 | 会議（打ち合わせ近縁） | +10日 10:00 |
| 21 | L3 | 深夜のオンラインゲーム大会 | 不規則 | +11日 23:30 |
| 22 | L3 | 推し活（ライブ参戦） | 不規則 | +11日 17:00 |
| 23 | L3 | 断捨離（クローゼット整理） | 不規則 | +12日 10:00 |
| 24 | L3 | 早朝の釣り | 不規則 | +12日 5:00 |
| 25 | L3 | 自宅での瞑想会 | 不規則 | +13日 7:00 |
| 26 | L3 | 深夜のコンビニスイーツ食べ比べ | 不規則 | +13日 23:00 |
| 27 | L3 | 推しの誕生日カフェ巡り | 不規則 | +14日 11:00 |
| 28 | L3 | 早朝ランニングの計測会 | 不規則 | +14日 6:00 |
| 29 | L3 | 家庭菜園の収穫 | 不規則 | +15日 8:00 |
| 30 | L3 | 深夜のコードレビュー会 | 不規則 | +15日 1:00 |

全30件、16件のfew-shot seedタイトル（結婚式／歯科検診／出張／打ち合わせ／誕生日会／健康診断／旅行／商談／Wedding／Dental checkup／Business trip／Team meeting／Birthday party／Health checkup／Vacation trip／Client negotiation）のいずれとも逐語一致しないことを確認済み（機械的な構造ガードとしてC1のJVMテストで固定する、§7）。**具体30件はオーケストレーターの点検を受ける前提の起案**。

### 3.3 測定指標（ハイブリッド、ユーザー確定）

**主指標（人手評価、約10件）**: オーケストレーターが層別に3〜4件を選び盲検シート（甲/乙シャッフル、Basic/AI秘匿）を作成し、ユーザーが3軸（①自然さ・行動可能性 ②捏造なし ③文脈適合）で採点する。採点単位は準備ステップの`displayText`単体（プラン全体ではない）。**実装者（本計画書の起案者）はシャッフル・マッピングに一切関与しない**（盲検の完全性）。n=10・単一評価者であり、**統計的有意性ではなく方向性を示す評価として扱う**ことを明記する。

**機械副指標（自動、30件全数）**:
- reject理由内訳: `lastSanityRejectReason`（8値）の発生件数分布。
- エコー率: `lastSanityRejectReason == FEW_SHOT_ECHO`の発生率。**R1b盲点を正直に明記する**——seedのstep動作文言の逐語コピー（例: 「今の作業を切り上げる」、§2で確認した全16 seed共通のTRANSITIONステップ文言）は`checkFewShotEcho`（R1a、タイトルのみ検査）の対象外であり機械的に検出されない。この種のエコーは人手評価（③文脈適合）が捕捉する前提とする。
- 差別化率: AI文言≠Basic文言の発生率。**品質指標ではないと明記する**——エコーや捏造でも差別化率は上がるため、水増しされうる参考値としてのみ報告する。
- TTFT（`firstTokenMs`）・tok/s（`tokensPerSecond`）: `AiMetrics`から直接。
- Applied率: `AiResult.Success`（かつ`sanityPassed`）の発生率／30件。

### 3.4 計測統制

出力テキストは`LocalAiGateway`のPrimary attemptが決定的サンプリング（topK=1／temperature=0／seed=0、`GpuBackendProbeTest`が使う`PRIMARY_TOP_K`等と同値の既存既定）を使うため、**テキスト系指標（displayText・reject理由・エコー）は再実行しても安定する**。統制が必要なのは時間系指標（TTFT・tok/s）のみであり、実機受け入れ（C2）実行時の運用手順として給電統一・item間クールダウン・実行前force-stop/GCをオーケストレーターが徹底する（コード上の対策ではなく実行手順、§10）。

### 3.5 条件はA（Basic）／B（AI）のみ

前ラウンド案のC条件（`EventCategoryClassifier`によるカテゴリ条件付きfew-shot選択）は盲検評価の単純化のため中核実験から外す。Bが有望と判明した場合、C条件（F-1の精緻な指標下での再測定）は追試候補として本計画書に1行のみ記載し、実装は行わない（§3.6）。

### 3.6 スコープ外（見送り・理由を明記）

- **Developer Settings画面（§36が規定する実装手段）**: 中間スコープ判断により作らない。プローブは`LocalAiGateway`を直接呼ぶため、AI/Basic切替UIは実験の実施に不要。
- **`AnalyticsStore`／Room／`ContextualizationResult`の拡張**: §2で確認したとおり、プローブが`AiResult`を直接読めるため不要（低リスク化、スキーマ移行ゼロ・ViewModel変更ゼロ）。
- **リアルタイムのランダム化A/B**: §36が「将来的に可能」と位置づけるオプションであり不採用。
- **C条件（カテゴリ分類器）の実装・実行**: §3.5参照、有望なら追試候補としてのみ記載。
- **仕様§56の4行動ファネル指標の収集・分析**: §13で明示的に将来の実利用フェーズへ委譲する（理由は§13参照）。
- **「AIをより賢くする」施策全般**（F-1本番再投入・LoRA蒸留等）: Phase 12は測定・可視化フェーズであり改善実装フェーズではない。

---

## §4. 計測方法論（N・統制・指標定義）

**N**: 機械副指標＝30件全数（固定データセット、§3.2）。人手評価＝約10件（層別3〜4件、単一評価者）。

**統制**: (a) 同一イベントに対しBasic・AI両方の文言を同一プロンプト構成（`PlanPromptBuilder`の本番と同一のsystem instruction・few-shot）で取得し、条件間の交絡を避ける。(b) 出力テキストはPrimary attemptの決定的サンプリング（topK=1/temp=0/seed=0）により再実行安定——時間系指標のみ環境要因（給電・クールダウン・force-stop/GC）の統制を要する（§3.4）。(c) `selected_model_id=auto`（実機ではQwen0.6B解決を想定）固定、実験中のモデル切替は発生させない。(d) L2はseedと近縁だが逐語重複なしという設計により、「seedをそのまま暗記して返しているだけ」という交絡を排除する。

**指標定義**: §3.3の全指標を採用。文脈適合度スコアは3軸（自然さ・捏造なし・文脈適合）それぞれ合格/不合格の二値評価とし、層別・条件別に集計する。

---

## §5. 変更対象ファイル構成

- **新設（C1）**: `app/src/androidTest/java/com/actionstarter/probe/BasicAiComparisonProbeTest.kt`（§3.1・§3.2、30イベントデータセットを内包）。`app/src/test/java/com/actionstarter/probe/BasicAiComparisonDatasetTest.kt`（固定データセットの構造検証、§7）。
- **変更**: なし。既存の`ai/`・`analytics/`・`persistence/`・`features/`配下は一切変更しない（§2・§3.6）。

---

## §6. 依存関係・技術選定の根拠

- **新規外部依存なし**。既存の`LocalAiGateway`／`AiResult`／`AiMetrics`／`ModelStorageImpl`／`AiPreferencesImpl`／`DeviceCapabilityImpl`（いずれもPhase 9で実装済み、`PerformanceBaselineProbeTest`が既に実運用）をそのまま再利用する。
- **androidTestプローブ方式の採用根拠**: Phase 9.5のPR-1・Mで確立済みの手法であり、JVMでは検証できないLocal AI実推論（litertlm、class file version不一致でJVM実行不可）を実機上で決定的に検証できる唯一の経路として実績がある。
- **人手評価の採用根拠（LLM-as-judge不採用）**: 端末内Qwen0.6B自身に自己の出力を採点させることは循環評価であり、`phase7-quality-harness.md`が既に指摘した「代理指標だけでは賢さを測れない」問題の別形態にすぎない。

---

## §7. テストケースリスト

| ID | 分類 | 内容 |
|---|---|---|
| T-P12-1 | 正常（回帰ガード・ソーススキャン型pinning） | `BasicAiComparisonProbeTest.kt`のデータセットが正確に30件（`ProbeEvent(`の出現数）である |
| T-P12-2 | 正常（同上） | データセットの`title`が16件のfew-shot seedタイトル（ja8件・en8件）のいずれとも逐語一致しない |
| T-P12-3 | 正常（同上） | L1/L2/L3各層が正確に10件ずつである |
| T-P12-4 | 異常（回帰ガード） | データセット内で`title`が重複しない（30件すべて異なるタイトル） |

C1はこの4件のJVMテスト（`BasicAiComparisonDatasetTest.kt`、Robolectric不要の純粋ソーススキャン）のみで「Red→Green」を構成する。`BasicAiComparisonProbeTest`自体はandroidTest・`@Ignore`既定のため、JVMテストスイート（`:app:testDebugUnitTest`）には一切現れず既存件数へ影響しない——コンパイル可否（`:app:compileDebugAndroidTestKotlin`等）のみがC1の検証対象であり、実機実行そのもの（Green相当の実証）はC2で行う。

---

## §8. エラー＆レスキューマップ

| 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|
| プローブ内`generatePlan`呼び出し | 個別イベントでFallback（AI生成失敗） | 当該イベントを`Fallback`区分としてそのままログ記録し、プローブ全体は継続する（30件中1件の失敗で実験全体を止めない） | 実験データの一部欠損のみ（想定内、機械副指標の一部） |
| プローブ実行環境 | モデル未導入・AI未有効化 | `AiFallbackReason.AI_DISABLED`／`MODEL_NOT_INSTALLED`をログへ明示し前提条件警告を出す（`PerformanceBaselineProbeTest`と同型） | 実験自体が成立しない旨を明示（サイレント化しない） |
| 人手評価（盲検シート） | オーケストレーターの盲検作業ミス（Basic/AIの割当誤り） | 実装者（本計画書起案者）はシャッフル・マッピングに一切関与しないため実装側の混入リスクなし。プロセス上の統制はオーケストレーターの責務 | 評価結果の信頼性に影響しうるため運用手順として明記 |

---

## §9. ADR起票方針

起票直前に`grep -n "^### ADR-" DECISIONS.md | tail -3`を再実行し最新確定ADRを確認したうえで、本フェーズの決定（当初案からの大幅簡素化・ハイブリッド指標設計・A/B2条件限定・§56の委譲判断・撤退基準）をC2完了後に正式起票する。

---

## §10. 実機受け入れ手順（A54、C2）

C2の内容そのものが実機受け入れを兼ねる。(a) A54実機で`BasicAiComparisonProbeTest`を`-e class`個別実行し、30件全件が完走しログが出力される（給電統一・item間クールダウン・実行前force-stop/GCを運用手順として徹底、§3.4）。(b) オーケストレーターがログから約10件を層別に抽出し盲検シートを作成する。(c) ユーザーが3軸でスコアリングする。(d) 機械副指標（30件）と人手評価（約10件）を集計し§14レポートへ記録する。(e) 既定基準（§9事前登録の結論基準）に基づき「AI明確に価値あり」／「0.6Bでは価値不明」のいずれかを判定し、ネガティブな結果であってもそのまま報告する。

**事前登録の結論基準**: 「AI明確に価値あり」＝盲検の文脈適合（AI）がBasicを明確に上回り、かつエコー/捏造率が低く、かつTTFTが許容範囲内。「0.6Bでは価値不明」＝適合度（AI）がBasicと同等以下、またはエコー/reject率が高い——この場合、現行端末内Qwen0.6BはBasic固定文言に文脈適合度で明確に勝てないと平明に報告し、AI既定OFF維持・より強いモデル（capable端末のGemma4・将来モデル）まで本格投入を見送ることを提言する。

---

## §11. コミット粒度（簡素化）

- **C1**: 固定30データセット＋`BasicAiComparisonProbeTest`（Red→Green＝§7の4件のJVMテスト・既存JVM件数無傷。プローブ自体は`@Ignore`androidTestのためJVMスイート非依存）
- **C2**: A54実行→オーケストレーターが盲検シート作成→ユーザー採点→集計→計画書§14レポート→ADR起票→クローズ

---

## §12. ユーザー確認事項（残存分のみ）

主指標（ハイブリッド）・スコープ（中間）は確定済み。残る確認事項:

1. **固定30イベントの内容点検**: §3.2の具体30件（タイトル・カテゴリラベル・時刻オフセット）がオーケストレーターの点検基準を満たすか。
2. **盲検評価対象10件の層別配分**: 層別3〜4件（合計約10件）の具体的な抽出基準（各層から均等に選ぶか、機械副指標で「差が出た/出なかった」イベントを優先的に選ぶか等）はオーケストレーターの盲検シート作成時に確定する運用でよいか。

---

## §13. 3系統レビュー記録（オーケストレーター＋Gemini 3.5-flash＋独立検証役、採用/棄却全件）

前ラウンド案（Developer Settings画面新設・`AnalyticsStore`/Room/`ContextualizationResult`拡張を伴う恒久実験基盤）に対する3系統レビューの指摘・採否を全件記録する。

### 採用（計画書全面改訂）

| No | レビュー元 | 指摘要約 | 反映箇所 |
|---|---|---|---|
| 1 | 統合判断（オーケストレーター） | Developer Settings画面（§36準拠）は中間スコープでは不要——実験にトグルUIは要らない | §3.1・§3.6（全削除） |
| 2 | 独立検証役（確認済み） | プローブは`LocalAiGateway`を直接呼び`AiResult`から機械副指標をその場で読める。ViewModel→Room経由（3段喪失ギャップ）はアプリ内常時集計〔フル〕用であり実験には不要——スキーマ移行ゼロ・ViewModel変更ゼロで低リスク | §2（実コードで再確認・`PerformanceBaselineProbeTest`の既存稼働で実証）・§3.6（全削除） |
| 3 | 統合判断（オーケストレーター） | 中核をPhase 9.5 PR-1型のandroidTestプローブ1本に集約する | §3.1 |
| 4 | Gemini H-4 | 恣意選択防止のため固定30イベントセットを事前登録し計画書に全件明記する。3層（L1: seed非依存の日常／L2: seed近縁で別語／L3: AIが苦手な不規則）×10件、seedタイトルの逐語再利用禁止 | §3.2（全30件表） |
| 5 (前段) | 統合判断（オーケストレーター） | 機械副指標として reject理由内訳・エコー率・差別化率・TTFT/tok/s・Applied率を自動収集する | §3.3 |
| 5 (後段) | 独立検証役 #4 | R1b（modelStepsのstep動作文言の逐語コピー検出）は未実装であり機械検出されない盲点である。正直に明記し人手評価が担う前提を明示する。実コード確認（本ラウンド）でこの懸念を裏付ける具体的事実（全16 seedのTRANSITIONステップ文言「今の作業を切り上げる」/"Wrap up what you are doing"が完全同一）を発見した | §2・§3.3（R1b盲点の明記） |
| 6 | Gemini H-5（オーケストレーターが訂正のうえ採用） | 計測統制（給電統一・item間クールダウン・force-stop/GC）を実施する。ただし出力テキストはtopK1/temp0/seed0で決定的なためテキスト系指標は再実行で安定し、統制が必要なのは時間系指標のみ、と訂正のうえ明記する | §3.4・§4 |
| 7 | Gemini H-3＋独立検証役 #1 | 主指標をハイブリッド（機械副指標の全件自動収集＋人手による約10件の盲検評価）とする。盲検シートはオーケストレーターが作成し、実装者はシャッフル・マッピングに一切関与しない（盲検の完全性）。採点単位は準備ステップのdisplayText単体。n=10単一評価者は統計的有意性でなく方向性として扱う | §3.3・§10（ユーザー確定によりPhase 12の主方針として確定） |
| 8 | 統合判断（オーケストレーター） | 条件はA（Basic）/B（AI）の2条件のみとし、C条件（カテゴリ分類器）は盲検の単純化のため中核から外す。Bが有望なら追試候補として1行のみ記載する | §3.5 |
| 9 | Gemini M-6（撤退基準） | 事前登録の結論基準を定める。「AI明確に価値あり」/「0.6Bでは価値不明」の2分岐を明記し、後者の場合は既定OFF維持・より強いモデルまでの見送りを提言する設計とする。ネガティブな結果もそのまま出せる設計であることを明記する | §10 |
| 10 | 独立検証役（精密所見） | 仕様§56の4行動ファネル指標のうち3つ（準備開始率・再利用率は起点となる実イベントが存在しないプローブ環境では収集不能、プラン変更率は編集機能自体が未実装のため収集不能）、4つ目のRecovery受容率もプローブでは常に100%になる人工物であり実利用ログでのみ意味を持つ。必要なイベント・機能を備えた将来の実利用フェーズへの委譲を提言する | §2・§3.6・本節（§56を明示委譲） |
| 11 | 統合判断（オーケストレーター） | overlay decorator構造（`LocalAiPlanContextualizer.overlay`）がBasic/AI間の入力同一性を構造的に保証していることを実験の土台となる好材料として正直に記録する。R1a（タイトルエコー検出）は本番稼働済み、R1b（未実装）は人手評価が担う、という現状を正直に記録する | §2（正直な記録として明記） |

### 棄却

| No | 内容 | 棄却理由 |
|---|---|---|
| 1 | 前ラウンド案§3.1「Developer Settings画面新設」 | 中間スコープ確定（ユーザー決定）により不要。プローブが`LocalAiGateway`を直接呼ぶため実験の実施に切替UIは不要 |
| 2 | 前ラウンド案§3.2「`ContextualizationResult`拡張・`AnalyticsStore.recordAiWordingOutcome`シグネチャ拡張・`BehaviorEventEntity`新規カラム」 | 独立検証役の確認により、プローブが`AiResult`を直接読めるため不要と判明。スキーマ移行・ViewModel変更を伴う変更は実験1回に対して過剰投資 |
| 3 | 前ラウンド案§3.5「C条件（カテゴリ分類器）を中核実験に含める」 | 盲検評価の単純化のため中核から除外（統合判断）。有望なら追試候補として記載のみ |
| 4 | 前ラウンド案「候補③（少数正解ラベル一致率）を主指標の一部とする」 | ハイブリッド確定（人手評価＋機械副指標）により、キーワード一致による自動ラベリングは採用しない。循環評価リスクを避ける |

---

## §14. 実験結果（2026-08-12実施・A54実機）

オーケストレーターがA54（SCG21）実機で`BasicAiComparisonProbeTest`を実行し、機械副指標を全30イベントから収集したうえで、AI成功14件を対象に盲検シートを作成しユーザーが3軸評価を行った。以下は実測事実である（脚色禁止・観測されたとおりに記載）。logcat全文は`build/agent-logs/phase12-comparison-logcat.log`（gitignore対象）に保存されており、本節には集計数値のみを記録する。

**実施環境**: A54（SCG21）・Qwen0.6B（`selected_model_id=auto`）・`-e class`個別実行。Basic側の準備ステップ共通文言は`step_title_preparation`の値「出かける準備をする」。

### 1. 機械副指標（30イベント全数）

| 指標 | 値 |
|---|---|
| Applied（AI成功） | 14/30（47%） |
| Fallback | 16/30（53%） |
| エコー検出数（`FEW_SHOT_ECHO`） | 6件 |
| 差別化数（AI文言≠Basic文言、Applied内） | 14件（Applied全件が差別化——§3.3「差別化率は品質指標ではない」という事前の警告どおり、差別化していても人手評価では有害と判定された例が多数を占めた。§2参照） |
| reject理由内訳（`lastSanityRejectReason`、Applied内のRetry経由・Fallback双方を含む延べ件数） | `MIN_QUALITY`8・`FEW_SHOT_ECHO`6・`TITLE_COPY`2・`DUPLICATE_ACTION_TYPE`2・`LOCALE_MISMATCH`1（延べ19、Fallback16件との差はApplied内でPrimary失敗→Retry成功したケースの残存reject理由を含むため） |
| TTFT中央値 | 約2.4秒 |
| decode速度 | 約9 tok/s |

### 2. 人手評価（AI成功14件、Basic共通文言「出かける準備をする」との比較）

| No | 層 | イベント | 判定 |
|---|---|---|---|
| 2 | L1 | ジムでの筋トレ | 良い |
| 5 | L1 | 確定申告の相談 | おかしい |
| 10 | L1 | インフルエンザ予防接種 | おかしい |
| 11 | L2 | 眼科検診 | おかしい |
| 12 | L2 | 送別会 | 良い |
| 13 | L2 | 工場見学 | おかしい |
| 17 | L2 | 帰省 | 良い |
| 18 | L2 | 取引先との会食 | 良い |
| 19 | L2 | 忘年会 | 微妙 |
| 20 | L2 | 株主総会 | おかしい |
| 25 | L3 | 自宅での瞑想会 | おかしい |
| 27 | L3 | 推しの誕生日カフェ巡り | 良い |
| 29 | L3 | 家庭菜園の収穫 | おかしい |
| 30 | L3 | 深夜のコードレビュー会 | おかしい |

「おかしい」8件の大半は「歯科検診」（few-shot seedのタイトル）への鸚鵡返しだった（例: 「歯科検診を受ける場所を確認する」に類する出力）。この文言はseedタイトル「歯科検診」そのものの完全コピーではなく、seedの`modelSteps`（ステップ本文）を意味的に踏襲した言い換えであるため、R1a（`checkFewShotEcho`、`knownFewShotTitles`＝タイトル集合との一致判定）をすり抜け"Applied"として通過した。**これは計画書§3.3で事前に警告していたR1b盲点（「seedのstep動作文言の逐語コピーは機械検出されず人手評価が担う」）が実データで確定した結果である**（独立検証役#4・Gemini C-2の指摘の実証）。

### 3. 全30件集計

| 判定 | 件数 | 割合 |
|---|---|---|
| 良い | 5 | 17% |
| 中立（Fallback16件＋微妙1件） | 17 | 57% |
| **有害** | **8** | **27%** |

**核心所見**: AIが助ける（17%）よりAIが壊す（27%）方が多く、Basic固定文言に対してnet-negativeという結果になった。

### 4. 事前登録の結論基準への当てはめ（§10）

計画書§10が事前登録した2分岐——「AI明確に価値あり」（盲検の文脈適合がBasicを明確に上回り、かつエコー/捏造率が低く、かつTTFTが許容範囲内）／「0.6Bでは価値不明」（適合度がBasicと同等以下、またはエコー/reject率が高い）——のうち、**後者に該当する**。適合度は良い17%<有害27%でBasicを下回り（同等以下どころか下回っている）、エコー率も20%（6/30）と無視できない水準であるため、撤退基準を満たす。実測結果はより明確に「現行端末内0.6BはBasic固定文言に対しnet-negative」であり、単なる「価値不明」より踏み込んだ結論として報告する。

### 5. 推奨

1. **AI既定OFFを維持する**（現状どおり）。既定ONへの昇格は行わない——有害率27%という実測結果のもとでの既定ON化は正当化できない。
2. **価値の可能性が残るのは高RAM端末のGemma4のみ**。ただし日常使用のA54はメモリ不足でGemma4を利用できないことがPhase 8.5§12で実測済みであり、Gemma4での同種実験は将来課題として持ち越す。
3. **抜本策の候補**: (a) モデル自体の強化（より大きい／新しいモデルへの置き換え）、(b) プロンプト側のマスク（Phase 9.5でドーマント化したL1-b、few-shotの`userTurn`をプレースホルダへ置換する設計）。今回の実データ（R1a→R1b拡張の必要性が具体的に確定）はこれらの検討を後押しする根拠として記録する。

### 6. 仕様§56ファネルKPIの委譲（§2・§13の再掲）

§56「Basic vs AI KPI」の4行動ファネル指標（Preparation Start Rate／Plan Modification Rate／Reuse／Recovery acceptance）は、本フェーズのプローブ環境（起点イベント自体が存在しない・編集機能が未実装・Recovery受容率は常に100%になる人工物）では引き続き測定不能であり、必要なイベント・機能を備えた将来の実利用フェーズへ委譲する（§2・§13で既述の判断を実験完了後も維持する）。
