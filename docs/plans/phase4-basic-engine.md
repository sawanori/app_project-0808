# Action Starter Android ― Phase 4 実装計画書：Basic Engine（決定的Planning・Departure実値供給）

**対象Phase**: Phase 4（仕様書§68 Phase 4「Basic Engine」、§13 Basic Engine）
**正仕様書**: `Action_Starter_Master_Specification_v2.0_Android.md`
**前提**: Phase 4着手の前提条件はPhase 2のG4-JVM通過（R-1）として維持する。**Phase 2はC5-fixで122/122 Green達成済み（2026-08-09・`p2c5fix-full.log`）。C6/C8のクローズ工程が進行中である**（同計画書§15(d)(e)のarchitectレビュー未解決事項2件の解消状況は未確認。§7.1実測M4-12参照）。本計画書自体のG1裁定は成立済みであり、**Phase 4実装サイクル（P4-C1）のベースライン確認は「着手時点の全スイートGreen実測（現行122件＋Phase 2 C6での増減を反映した件数）の記録」に簡素化する**（R-1）。
**起点計画メモ**: android-planner（Opus）作成、2026-08-09（§0〜§10）
**本書作成**: plan-doc-writer（Sonnet）、2026-08-09（初版）
**関連ハーネス文書**: `docs/TEAMS.md`（役割分担・PDCA・品質ゲートの正）、`docs/GOAL.md`（リリース判定基準）、`DECISIONS.md`（ADR記録先。本書作成時点の最新確定ADRはADR-0014であることを実測確認済み）
**関連計画書**: `docs/plans/phase2-calendar.md`（Phase 2・C5-fixで122/122 Green達成済み・2026-08-09。C6/C8クローズ工程進行中）、`docs/plans/phase1-ui-skeleton-domain.md`（Phase 1・G4-JVM/G4-E達成済み）

**ステータス: Fable 5＋Geminiクロスレビュー済み・CRITICAL 3件反映済み（2026-08-09）→ G1通過。G-1〜G-9裁定済み（2026-08-09）。Phase 4着手の前提条件=Phase 2のG4-JVM通過（R-1。Phase 2はC5-fixで122/122 Green達成済み・2026-08-09、C6/C8クローズ工程進行中）。**

本計画書はandroid-planner（Opus）が2026-08-09に作成したPhase 4計画メモ（§0〜§10）を忠実に文書化したものであり、計画メモにない機能・仕様を自己判断で追加していない。Fable 5はPass1（CRITICAL：データ安全性／信頼境界違反／サイレント障害／論理的整合性）レビューにより、計画メモが提起した論点をG-1〜G-9として整理し、**全項目を計画メモの推奨案どおり承認した（2026-08-09、§4参照）**。Geminiによる第三者クロスレビュー（`model: "gemini-3.5-flash"`固定）はG1として実施済みであり、指摘されたCRITICAL 3件はFable 5裁定（2026-08-09）により本書へ反映済みである（→**G1通過**）。

本書と正仕様書v2.0に差異が生じた場合は仕様書v2.0が正とする。本書作成にあたり、plan-doc-writerは転記対象の計画メモに加え、既存ソースファイルおよび正仕様書該当箇所を直接確認し、メモの記載内容と矛盾がないことを検証した（§7.1・§13参照）。**本書作成作業ではproduction codeを一切変更していない（読み取りのみ）。**

---

## 1. 目的

Phase 4は、仕様§68（Phase 4「Basic Engine」）が定める完成条件「**LLMゼロでExecution Planが成立**」を満たすことを目的とする。実装対象は仕様§68が列挙するTransition／Preparation／Travel／Buffer／deterministic planning／departure calculationであり、仕様§13（Basic Engine）の計算式`StartOfTransition = EventStart − ArrivalBuffer − TravelTime − PreparationTime − TransitionTime`を担当領域とする。

具体的には、Phase 1で暫定実装した`mock/MockPlanFactory.kt`（`docs/plans/phase1-ui-skeleton-domain.md`§8 U6が定めるとおりPhase 4で削除予定のMock実装）の§13計算式実装を、決定的な本番実装`planning/BasicPlanningEngine.kt`へ昇格させ、以下5件の設計欠陥を修正する（§0結論）。

1. **20分捏造既定値の廃止**: `travelEstimate`が`null`のとき`Duration.ofMinutes(20)`で穴埋めする現行実装（`MockPlanFactory.kt:42-46`）を廃止し、移動時間が未取得の場合は捏造せずTRAVELステップを生成しない。
2. **DEPARTURE/TRAVEL順序の修正**: 現行実装は`ExecutionStep`をTRANSITION→PREPARATION→TRAVEL→DEPARTUREの順で構築する。TRAVELとDEPARTUREが同一`scheduledStart`（`departureTime`）を持つため、`ExecutionPlan`の`nullsLast`安定ソート（`ExecutionPlan.kt:40`）を経ても構築順がそのまま保たれ、結果としてTRAVEL→DEPARTUREという**仕様§48のenum順（TRANSITION, PREPARATION, DEPARTURE, TRAVEL）と逆の順序**で確定してしまう（実測M4-8）。これを§48のenum順に一致させる（G-6）。
3. **決定的step idの導入**: 現行実装は`ExecutionStep.id`に`UUID.randomUUID()`を用いており、同一入力で再planningしても`id`が変化する（実測M4-9）。安定した決定的id生成へ置き換える。
4. **title空文字化**: 現行実装は`title`に`"Transition"`等の英語文字列を直接埋め込んでおり（`MockPlanFactory.kt`、仕様§7「UI文字列の直接ハードコード禁止」違反、実測M4-7）、`title`を空文字とし、既に仕様§48で定義済みの`semanticId`フィールドをlocalizationキーとして解決する方式へ置き換える（G-4）。
5. **Departure実値供給**: `DepartureViewModel`（`DepartureViewModel.kt:20`）は初期`DepartureUiState()`から一切更新されず、常に「移動時間未取得」を表示したままである（実測M4-6）。確定済み`ExecutionPlan`の実値を供給する経路を追加する。

あわせて、仕様§25「Planning」・§26「Plan Review」が例示する時刻付き行表示（例：「08:40 Stop working」「08:50 Get dressed」）に対し、現行`PlanReviewScreen.kt:73`は`step.title`のみを描画し時刻を表示していない（実測M4-5）。本Phaseでこれを解消する。

Phase 3（Routing/Geocoding、仕様§67）とは`PlanningContext.travelEstimate: Duration?`のnull許容性で分離する。`PlanningContext`は既にPhase 1契約scaffold時点で`travelEstimate: Duration?`・`profile: PersonalExecutionProfile?`をいずれもnull許容として定義済みである（実測M4-3）。`travelEstimate == null`は「移動時間見積り未取得」を意味し、その場合もTRAVELステップなしでPlanは正常に成立する（**null＝移動なしPlan成立**。§0結論・§9エラーマップ#1）。したがって本Phaseは`services/routing/`パッケージの実装（Phase 3スコープ）に一切依存せず、`services/routing/`をimportしない（§7.4）。

具体的な準備アクションの分解（例：「Get dressed」「Check equipment」といった個別アクションへの展開、eventType推定、優先順位付けを伴う提案生成）は仕様§14（Local AI Engine）の担当であり、Phase 4（Basic Engine、決定的計算のみ）のスコープ外とする。

---

## 2. スコープ

### 2.1 やること

F40〜F48（詳細は§5）。サイクル構成はP4-C1〜C7の7サイクル（§10）。テストケースは全43件（§8）、エラー＆レスキューマップは全22行（§9）。

### 2.2 やらないこと（明示）

- **Phase 3領域**: `services/routing/`の実装・`services/location/`・`MockRoutingService`には一切触れない。`BasicPlanningEngine`は`services/routing/`をimportせず、`travelEstimate`の値のみで判定する（§7.4）。
- **Phase 2領域**: `services/calendar/`・`services/permission/`・`features/eventselection/`・`AndroidManifest.xml`には一切触れない（非重複宣言、§6.5）。
- **具体的な準備アクションの分解**: 「Get dressed」「Check equipment」等の個別アクションへの展開・eventType推定・優先順位判定は仕様§14（Local AI Engine）の担当であり、Phase 4では行わない。
- **調整UI**（Arrival Buffer等をユーザーが画面上で調整するUI）: Phase 4には含めない（G-2）。ただし`PlanningContext`側の受け口（`arrivalBuffer: Duration`・`profile: PersonalExecutionProfile?`等の入力パラメータ）は既に完成しているためそのまま消費し、将来UIが載る前提を壊さない。
- **仕様§29「最新現在地・経路情報からの再計算」（Departure Modeの継続再計算）**: Phase 4はDepartureViewModelへ確定済み`ExecutionPlan`の**初期値**を供給するのみであり、位置情報・経路情報に基づく継続的な再計算ロジック（仕様§29本文）は実装しない。この再計算はPhase 3の実ルーティング機能に依存するため、**Phase 4完了後**（Phase 5以降）に持ち越す（R-2・R-8・§9エラーマップ#22）。「Phase 4完了」を「§29再計算の実装完了」と混同しないこと（R-8）。
- **`DepartureScreen.kt`の変更**: ETA null表示・buffer負値警告は既にPhase 1で実装済み（`DepartureScreen.kt`）であり、本Phaseでは変更しない。本Phaseの範囲は`DepartureViewModel.kt`への実値供給配線のみ。

---

## 3. ゲート

`docs/TEAMS.md`§6に基づきG1〜G4を適用する。G4は**G4-JVM**と**G4-E**の2段階とする（ADR-0006踏襲、Phase 1・Phase 2の先例と同じ）。

- **G1（計画承認）**: 本計画書＋エラー＆レスキューマップ（§9）＋Fable 5 Pass1レビュー記録。**Pass1レビューは実施済みでありG-1〜G-9として確定済み（2026-08-09、§4参照）。Geminiクロスレビュー（`model: "gemini-3.5-flash"`固定）はG1として実施済みであり、指摘されたCRITICAL 3件はFable 5裁定（2026-08-09）により本書へ反映済みである（→G1通過）。** また、**Phase 4サイクル（P4-C1）着手そのものの前提条件としてPhase 2のG4-JVM通過（R-1）を要する**（本書の計画内容そのものに対するG1裁定とは別軸の着手条件であることに留意する。Phase 2はC5-fixで122/122 Green達成済み・2026-08-09、C6/C8クローズ工程進行中であり、P4-C1のベースライン確認は着手時点の全スイートGreen実測の記録に簡素化する）。
- **G2（Red確認）**: P4-C2でtest-writerが作成したfailingテスト（§8、全43件のうちJVM系41件＝E1区分31件＋E2区分10件）をquality-runnerが実測する。E2E系2件（T-P4E2E-1〜2）は作成のみでRed実測はG4-Eまで行わない（Phase 1・Phase 2と同じ扱い）。
- **G3（Green確認）**: P4-C3（Domain側Green）・P4-C4（UI側Green、C3と並列）それぞれでのGreen実測、およびP4-C5（統合ウィンドウ）・P4-C6（Refactor）後の再実測。
- **G4-JVM（Phase 4完了・JVM側）**: P4-C6完了時点。`./gradlew build`成功・対象範囲のJVM/Robolectric全テストPass・`lintDebug`エラー0を実測する。
- **G4-E（Phase 4完了・Emulator側、「薄いG4-E」）**: P4-C7完了時点。PlanReview画面の実時刻表示をja/en両ロケールでスクリーンショット取得（T-P4E2E-1）、Departure画面のETA表示確認（T-P4E2E-2）を行う（§0結論・§10 P4-C7）。**G4-E未達のままPhase 5以降へ進むことを禁止する**（`docs/plans/phase2-calendar.md`§3の先例を踏襲）。

Phase 5着手条件は本書の範囲外とする。

---

## 4. 承認状態

**Fable 5＋Geminiクロスレビュー済み・CRITICAL 3件反映済み（2026-08-09）→ G1通過。G-1〜G-9裁定済み（2026-08-09）。Phase 4着手の前提条件=Phase 2のG4-JVM通過（R-1。Phase 2はC5-fixで122/122 Green達成済み・2026-08-09、C6/C8クローズ工程進行中）。**

android-planner計画メモが提起した論点はG-1〜G-9として整理され、Fable 5はPass1（CRITICAL）レビューにより**全項目を計画メモの推奨案どおり承認した（2026-08-09）**。個別の裁定内容は以下のとおり。

| # | 裁定内容 | 反映箇所 |
|---|---|---|
| G-1 | 4種テンプレート（transition/preparation/departure/travel、eventType非依存）と既定分数（transition 5分／preparation 15分）は「仕様未定義プレースホルダ」である旨をKDocに明記することを条件に条件付き承認。Arrival Buffer既定値はNormal（標準）10分とする。Arrival Buffer自体は仕様§4が定める「希望到着余裕」であり、初期値プリセットはTight 5分／Normal 10分／Relaxed 20分と規定されている（仕様§4根拠）ため、既定値そのものは仕様の範囲内の選択だが、「4種テンプレート構造」「transition 5分/preparation 15分の既定分数」は仕様に明記がないプレースホルダである | 本書§1、§5 F41/F43、§7.3、`planning/BasicPlanningDefaults.kt`のKDoc |
| G-2 | Arrival Buffer等の調整UIはPhase 4に含めない。ただし`PlanningContext`側の受け口（入力パラメータ）は完成させる | 本書§2.2、§7.3 |
| G-3 | ETA未取得の表現は`ExecutionPlan`契約を変更せず、`DepartureUiState.estimatedArrival: Instant?`のnullで表現する（Phase 1契約scaffold時点で既に定義済みのnull許容フィールドを流用する） | 本書§7.4 |
| G-4 | `title`は空文字とし、`semanticId`（仕様§48で定義済みだが用途は仕様未規定のフィールド）をlocalizationキーとして`stringResource`解決する方式に統一する | 本書§1、§5 F45、§7.3、§9エラーマップ#11・#12 |
| G-5 | 「希望余裕」（仕様§4の「希望到着余裕」＝ユーザーが設定するArrival Buffer入力値）と「実現余裕」（実際の到着予測`estimatedArrival`と予定開始時刻`eventStart`の差から逆算される値）の概念を区別する | 本書§7.5 |
| G-6 | ステップの並び順は仕様§48のenum順（TRANSITION→PREPARATION→DEPARTURE→TRAVEL）に一致させることで確定する | 本書§1、§5 F45、§9エラーマップ#9 |
| G-7 | `PersonalExecutionProfile`（`profile`）の取得元が本Phaseでは未定義である点について、Phase 4では`profile = null`固定を明示的に確定させる（`BasicPlanningDefaults`の既定定数が使われる）。永続化層からの実供給はPhase 10で行う。`PlanningContext.profile: PersonalExecutionProfile?`は既にnull許容のため、engine側（`PlanningEngine`契約・`BasicPlanningEngine`）は変更不要（2026-08-09補填。元の計画メモ§9推奨内容、Fable 5承認済み） | 本書§7.3、§11 R-7 |
| G-8 | 仕様§68「departure calculation」（Phase 4の担当範囲）と仕様§29「再計算」（Departure Modeが最新現在地・経路情報から行う継続的な再計算）の境界を確定する。Phase 4は計画時点の`departureTime`／`estimatedArrival`算出までを担当し、現在地に基づく再計算はPhase 3（経路）＋Phase 5（実行中の再評価）へ委ねる（2026-08-09補填。元の計画メモ§9推奨内容、Fable 5承認済み） | 本書§2.2、§6.5、§9エラーマップ#22、R-2・R-8 |
| G-9 | 「移動不要」（travelEstimateがZEROまたは対象外）と「移動時間不明」（travelEstimateがnull）を画面上で区別する専用文言は作らず、共通の1種類の文言で扱う | 本書§9エラーマップ#16 |

**上記G-1〜G-9はいずれもFable 5裁定として確定済みであり、ユーザー承認待ちの項目ではない**（本書冒頭の承認状態のとおり）。**GeminiクロスレビューはG1として実施済みであり、指摘されたCRITICAL 3件（Departure層の所有権と直列化、ForegroundGate判定式の拡張はPhase 3側で反映、Phase 2クローズ前提の更新）はFable 5裁定（2026-08-09）により本書へ反映済みである（→G1通過）。** **G-7・G-8は本書初版作成時点では転記元メモに個別の裁定内容が見当たらず「転記元に記載なし」と一時マークしていたが、2026-08-09に元の計画メモ§9にあった推奨内容（Fable 5承認済み）で補填し、上記表へ反映した。これによりG-1〜G-9は本節時点ですべて裁定済みで確定している。**

---

## 5. 機能一覧（F番号はPhase 3予約〔F21〜F39〕の次から連番継続）

Phase 3（Routing/Geocoding、仕様§67）にはF21〜F39が予約されており、Fable 5承認済みである（計画メモ§4）。Phase 4はF40から採番する。

| ID | 機能 | 仕様根拠 | 備考 |
|---|---|---|---|
| F40 | `BasicPlanningEngine`（`PlanningEngine`実装の本番化） | §68 Phase 4、§13 Basic Engine | `MockPlanFactory`の§13式実装を本番昇格。`PlanningEngine`契約（§44、`suspend fun createPlan(context: PlanningContext): ExecutionPlan`）は変更しない（実測M4-2） |
| F41 | テンプレート4種（transition/preparation/departure/travel、eventType非依存） | §48 Step Model | 仕様未定義プレースホルダとして条件付き承認（G-1）。既定分数はKDocで明記 |
| F42 | departure calculation（出発時刻・到着予想の算出） | §13、§68「departure calculation」 | §13式（`StartOfTransition = EventStart − ArrivalBuffer − TravelTime − PreparationTime − TransitionTime`）をそのまま踏襲する |
| F43 | Arrival Buffer既定値（Normal 10分） | 仕様§4「希望到着余裕」（G-1） | `planning/BasicPlanningDefaults.kt`へ隔離 |
| F44 | 移動なしPlan成立・20分捏造既定値の廃止 | §0結論、§9エラーマップ#1 | `travelEstimate == null`でもTRAVELステップなしでPlanが成立する。固定値での穴埋めをしない |
| F45 | 順序修正・決定的step id・title空文字化の規約統一 | §48 enum順、G-4、G-6 | ステップ順序を§48 enum順（TRANSITION→PREPARATION→DEPARTURE→TRAVEL）に一致させ、`id`を決定的生成へ、`title`を空文字＋`semanticId`解決へ統一 |
| F46 | 入力検証（負値・過去イベント・オーバーフロー等の取扱い） | §9エラーマップ#2〜#6 | `travelEstimate`等の負値は`IllegalArgumentException`。過去イベントも生成は継続 |
| F47 | PlanReview画面の時刻表示＋title localization | 仕様§25/§26、G-4 | `PlanReviewScreen.kt`に時刻表示を追加し、`semanticId`→`stringResource`解決関数（`StepTitle.kt`）で表示 |
| F48 | Departure実値供給 | 仕様§29（初期値のみ。継続再計算はPhase 4対象外） | `DepartureViewModel`に確定済み`ExecutionPlan`をマッピングする経路を追加。§29の継続再計算は対象外（R-2・R-8） |

---

## 6. フットプリント

### 6.1 新規作成

| パス（`app/src/`起点） | 内容 | 担当 |
|---|---|---|
| `main/java/com/actionstarter/planning/BasicPlanningEngine.kt` | F40〜F46実装。`PlanningEngine`の本番実装 | domain-implementer |
| `main/java/com/actionstarter/planning/BasicPlanningDefaults.kt` | F43既定値の隔離先。KDocで「仕様未定義プレースホルダ・Phase 10で置換」を明記（G-1・R-7） | domain-implementer |
| `main/java/com/actionstarter/features/common/StepTitle.kt` | F47 `semanticId`→`stringResource`解決関数。未知キーはフォールバック文言を返しクラッシュしない | ui-implementer |
| `test/java/com/actionstarter/planning/BasicPlanningEngineTest.kt` | T-BPE-1〜27・29 | test-writer |
| `test/java/com/actionstarter/planning/PlanningLlmIsolationTest.kt` | T-BPE-28（AI/LLM非参照の構造ガード） | test-writer |
| `test/java/com/actionstarter/features/planreview/PlanReviewStepDisplayTest.kt` | T-P4UI-1〜5 | test-writer |
| `test/java/com/actionstarter/features/departure/DepartureViewModelTest.kt` | T-P4DEP-1〜5 | test-writer |
| `androidTest/java/com/actionstarter/e2e/BasicPlanE2ETest.kt` | T-P4E2E-1〜2 | test-writer |

### 6.2 既存ファイルの変更（Phase 4専有）

| パス | 変更内容 |
|---|---|
| `features/planreview/PlanReviewScreen.kt` | 時刻表示の追加（仕様§25/§26）。`step.title`ではなく`semanticId`を`StepTitle.kt`経由で解決。準備0件判定（現行`plan.steps.isEmpty()`、`PlanReviewScreen.kt:59`）を「TRANSITION/PREPARATION双方が0件」判定へ変更（§9エラーマップ#13） |
| `features/planreview/PlanReviewViewModel.kt` | 既定Arrival Buffer（現状`private companion object`の`DEFAULT_ARRIVAL_BUFFER = Duration.ofMinutes(10)`）の出所を`BasicPlanningDefaults`へ変更 |
| `features/execution/ExecutionScreen.kt` | title解決のみ（`StepTitle.kt`経由）。多段階遷移ロジックには触れない（Phase 5スコープ。`docs/plans/phase2-calendar.md`§15(a)の後送り事例と同種の切り分け） |
| `features/departure/DepartureViewModel.kt` | F48。`sharedPlanViewModel`を注入し、確定済み`ExecutionPlan`から`DepartureUiState`へマッピングする経路を追加（P4-C5統合ウィンドウ）。**本結線がPhase 3のDeparture改修の前提となる（先行実施。Fable 5裁定2026-08-09）** |
| `res/values/strings.xml` / `res/values-ja/strings.xml` | `semanticId`解決用文言（transition/preparation/departure/travelの表示名）を追加 |

### 6.3 削除（P4-C5統合ウィンドウ）

| パス | 理由 |
|---|---|
| `mock/MockPlanFactory.kt` | `BasicPlanningEngine`への完全昇格によりfake化不要（完全決定的処理のためfakeを別途用意する必要がない）。削除せず両者を並存させると仕様§89「No duplicated domain logic」違反となる（§7.2） |
| `test/java/com/actionstarter/mock/MockPlanFactoryTest.kt` | 検証意図はT-BPE-1／11／18へ移設（§7.2） |

**注記**: `mock/`パッケージ自体は`MockRecoveryFactory.kt`（Phase 6まで現役）・`MockRoutingService.kt`（Phase 3以降まで現役）が残るため消滅しない。ディレクトリ消滅を前提にしたテスト実装をしないこと（R-6）。

### 6.4 統合ウィンドウ（P4-C5、直列）

`di/AppContainer.kt`（`planningEngine`の実装差し替え1行〔`MockPlanFactory()` → `BasicPlanningEngine()`〕・`DepartureViewModel`初期化への`sharedPlanViewModel`注入1行〔`initializer { DepartureViewModel(routingService) }`の変更〕）・`navigation/ActionStarterNavHost.kt`（KDoc更新のみ）・`DECISIONS.md`・本計画書。

### 6.5 非重複宣言（他Phase領域への不可侵）

- **Phase 2領域**: `services/calendar/`・`services/permission/`・`features/eventselection/`・`AndroidManifest.xml`には一切触れない。
- **Phase 3領域**: `services/routing/`の実装・`services/location/`・`MockRoutingService`には一切触れない。
- **`features/departure/`の所有権**: 本Phaseに帰属する（R-2）。ただし仕様§29が定める「最新現在地・経路情報からの再計算」ロジック自体はPhase 4完了後に持ち越す（§9エラーマップ#22、R-8）。

---

## 7. 契約・設計

### 7.1 実測（android-planner、2026-08-09。M4-1〜M4-12）

Phase 4着手前の事前調査として、android-plannerは既存コード・依存関係を実測した。

| # | 実測内容 | 実測結果 |
|---|---|---|
| M4-1 | §13式の実装箇所 | `MockPlanFactory.kt:50-61`に実装済み（`transitionStart`／`departureTime`／`estimatedArrival`の算出） |
| M4-2 | `PlanningEngine`契約への影響 | 変更不要。`suspend fun createPlan(context: PlanningContext): ExecutionPlan`単一メソッドのままBasicPlanningEngineへ差し替え可能 |
| M4-3 | `PlanningContext`のnull許容性 | `travelEstimate: Duration?`・`profile: PersonalExecutionProfile?`ともnull許容型として既に定義済み |
| M4-4 | `ExecutionPlan`のソート仕様 | `steps`は`scheduledStart`昇順・`nullsLast()`の安定ソートで正規化される（`ExecutionPlan.kt:40`） |
| M4-5 | `PlanReviewScreen`の描画内容 | `PlanReviewScreen.kt:73`は`step.title`のみを描画し時刻を表示しない（仕様§25/§26相当違反） |
| M4-6 | `DepartureViewModel`の更新経路 | `DepartureViewModel.kt:20`は初期`DepartureUiState()`から一切更新されず、常に「移動時間未取得」を表示する |
| M4-7 | Mock実装のtitle文言 | `title`に`"Transition"`等の英語文字列を直接埋め込んでいる（仕様§7「UI文字列の直接ハードコード禁止」違反） |
| M4-8 | ステップ表示順序 | 構築順はTRANSITION→PREPARATION→TRAVEL→DEPARTUREであり、TRAVELとDEPARTUREが同一`scheduledStart`のため安定ソートでも入替わらず、§48のenum順（TRANSITION→PREPARATION→DEPARTURE→TRAVEL）と逆順で確定する |
| M4-9 | step idの安定性 | `id = UUID.randomUUID()`であり、同一入力での再planningでも`id`が変化する |
| M4-10 | 既存Mockテスト件数 | `MockPlanFactoryTest`は3件（T-MOCK-4／7／10） |
| M4-11 | coroutines-testの導入状況 | 導入済み（`kotlinx.coroutines.test.runTest`を`MockPlanFactoryTest`が使用中）。Phase 4での新規依存追加はゼロ |
| M4-12 | Phase 2ベースラインへの影響 | Phase 2に残存するRed 7件（`docs/plans/phase2-calendar.md`§18実測：`T-DI-1`、`T-NAV2-1`、`NavigationFlowTest`のT-NAV-1〜5）がPhase 4着手時のベースラインに影響する（R-1） |

**本節の留保**: M4-1〜M4-9はplan-doc-writer（本書作成者）が該当ソースファイル（`MockPlanFactory.kt`／`PlanningContext.kt`／`ExecutionPlan.kt`／`ExecutionStepType.kt`／`PlanReviewScreen.kt`／`DepartureViewModel.kt`）を本書作成時に直接読み、記載内容と一致することを確認済みである。M4-10〜M4-12は`MockPlanFactoryTest.kt`（3件を確認）・`docs/plans/phase2-calendar.md`§18（Red 7件の記載を確認）と突き合わせ、いずれも整合していることを確認した。

### 7.2 Mock昇格方針

`BasicPlanningEngine`はfake化せず、`MockPlanFactory`を削除する。BasicPlanningEngineは完全決定的（LLM等の非決定的要素を含まない）であるためfakeを別途用意する必要がなく、MockとBasicPlanningEngineを並存させることは仕様§89「No duplicated domain logic」に違反する。

**移行手順**:
1. **P4-C1**: `planning/BasicPlanningEngine.kt`を`TODO()`本体で新設する契約scaffold。**`AppContainer`には触れない**（`MockPlanFactory`を`planningEngine`実装として維持したまま並行して新クラスを追加するのみ）。
2. **P4-C2〜C3**: Red→Green。`BasicPlanningEngine`単独で完結させる（`AppContainer`未接続のまま、テストは直接`BasicPlanningEngine()`をインスタンス化して検証する）。
3. **P4-C5（統合ウィンドウ）**: `AppContainer`の`planningEngine`実装を`MockPlanFactory()`から`BasicPlanningEngine()`へ切替。`mock/MockPlanFactory.kt`＋`MockPlanFactoryTest.kt`を削除。`ActionStarterNavHost`のKDoc（Mock言及箇所）を更新。

**既存テストの移設**（assertion強度を維持し、弱体化しない）:

| 旧テスト | 新テスト | 検証意図 |
|---|---|---|
| T-MOCK-4（`createPlan_eventWithoutLocation_generatesNoTravelStep`） | T-BPE-11 | 場所情報なしのイベントはTRAVELステップを生成しない |
| T-MOCK-7（`createPlan_transitionStartBeforeNow_keepsAllStepsAndTransitionStartIsBeforeNow`） | T-BPE-18 | `transitionStart < now`でも生成を継続し自動省略しない |
| T-MOCK-10（`createPlan_transitionStart_matchesSection13Formula`） | T-BPE-1 | §13式との一致 |

**画面テストへの影響（P4-C2で実測確定）**: `PlanReviewScreenTest`は時刻表示追加に伴い更新される可能性がある。`DepartureScreenTest`は`DepartureScreen.kt`自体を変更しない（ETA null表示・buffer負値警告は既存実装のまま）ため、変更なしと見込む。いずれも確定的な判断はP4-C2の実測結果で行う。

### 7.3 テンプレート仕様（F41。G-1条件付き承認）

4種類のステップテンプレートを、`eventType`に依存しない一律の構造として定義する。

| テンプレート | `ExecutionStepType` | 生成条件 | 所要時間 | `scheduledStart` | 優先度 | `skippable` |
|---|---|---|---|---|---|---|
| transition | `TRANSITION` | `duration > 0`のときのみ生成 | `profile?.averageTransitionDuration ?: BasicPlanningDefaults.TRANSITION`（既定5分） | `transitionStart` | `IMPORTANT` | `false` |
| preparation | `PREPARATION` | `duration > 0`のときのみ生成 | `profile?.averagePreparationDuration ?: BasicPlanningDefaults.PREPARATION`（既定15分） | `transitionStart + transition所要時間` | `IMPORTANT` | `false` |
| departure | `DEPARTURE` | **常に生成**（0分ステップ非生成の原則の唯一の例外） | `Duration.ZERO` | `departureTime` | `REQUIRED` | `false` |
| travel | `TRAVEL` | `travelEstimate != null && travelEstimate > Duration.ZERO`のときのみ生成 | `travelEstimate` | `departureTime` | `REQUIRED` | `false` |

**リスト構築順は仕様§48のenum順（TRANSITION→PREPARATION→DEPARTURE→TRAVEL）に一致させる**（G-6。M4-8が確認した現行の逆順を修正する。§9エラーマップ#9）。

**0分ステップの非生成**: `duration == Duration.ZERO`となるステップ（transition・preparation・travel）は生成しない。**DEPARTUREのみ例外**であり、`estimatedDuration = Duration.ZERO`であっても常に1件生成する（出発という事象そのものを表すマーカーのため。§9エラーマップ#8）。

**既定値の隔離**: transition既定5分・preparation既定15分・Arrival Buffer既定Normal 10分（G-1、仕様§4「希望到着余裕」のプリセットの1つ）は`planning/BasicPlanningDefaults.kt`へ集約し、KDocで「仕様未定義プレースホルダ・Phase 10で置換」と明記する（R-7）。

**具体的な準備アクションの分解は行わない**（仕様§14 Local AI Engineの担当。§1参照）。

**調整UIは対象外だが受け口は完成させる**（G-2）。`PlanningContext`の`arrivalBuffer: Duration`・`profile: PersonalExecutionProfile?`は既にPhase 1時点で入力パラメータとして定義済みであり、Phase 4はこれをそのまま消費する。UIから値を変更する機能はPhase 4では作らない。

### 7.4 `travelEstimate`の判定とエラー処理（F44・F46）

`BasicPlanningEngine`は`services/routing/`パッケージを一切importしない。TRAVELステップの要否・所要時間は`PlanningContext.travelEstimate`の値のみで判定し、`event.locationName`・`event.coordinates`は参照しない（現行`MockPlanFactory`の`hasLocation`判定〔`locationName != null && coordinates != null`〕からの設計変更）。

| `travelEstimate` | 判定 |
|---|---|
| `null` | TRAVELステップを生成しない。`departureTime = eventStart − arrivalBuffer`（TravelTime項を0として算出）。`estimatedArrival = departureTime` |
| `Duration.ZERO` | 同上（TRAVELステップを生成しない） |
| 正の値 | TRAVELステップを生成する。`departureTime = eventStart − arrivalBuffer − travelEstimate`。`estimatedArrival = departureTime + travelEstimate` |
| 負の値 | `IllegalArgumentException`を送出する |

**20分捏造の削除**: `travelEstimate`が`null`のときに固定値（現行`MockPlanFactory.kt:42-46`の`Duration.ofMinutes(20)`）で穴埋めする実装を削除する。回帰ロックはT-BPE-12で行う。

**ETA未取得の表現**: `ExecutionPlan`の契約（`estimatedArrival: Instant`、non-null）は変更しない。ETA未取得の状態は`DepartureUiState.estimatedArrival: Instant?`のnullで表現する（G-3。この型は既にPhase 1契約scaffold時点で定義済みであり、`DepartureUiState`のKDocも「`estimatedArrival`がnullのとき『移動時間未取得』と表示する」とPhase 1時点で明記済みである。§7.5参照）。

### 7.5 表示接続：PlanReviewとDeparture（F47・F48）

**PlanReview（時刻表示・title localization）**:
- 時刻フォーマットは既存`DepartureScreen.kt`のパターン（`DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)` ＋ `ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())`）をそのまま踏襲する。
- `title`表示は`features/common/StepTitle.kt`に新設する`semanticId → stringResource`解決関数を介する。未知の`semanticId`はフォールバック文言を返し、クラッシュしない（§9エラーマップ#12）。この解決関数はPlanReview・Execution双方の画面で共通利用する。
- 「準備ステップ0件」判定（現行`plan.steps.isEmpty()`、`PlanReviewScreen.kt:59`）は、F44により`DEPARTURE`が常に1件生成されるため`plan.steps`が構造的に空にならなくなる。判定条件を「TRANSITION/PREPARATION双方が0件」へ変更する（§9エラーマップ#13）。

**Departure（`DepartureViewModel`。P4-C5統合ウィンドウで実施）**:
- `sharedPlanViewModel`をコンストラクタ注入する（`AppContainer.createViewModelFactory`内の`initializer { DepartureViewModel(routingService) }`を1行変更）。
- マッピング規則:

| `DepartureUiState`フィールド | 導出元 |
|---|---|
| `eventStart` | `plan.event.startDate` |
| `estimatedArrival` | TRAVELステップが存在する場合のみ`plan.estimatedArrival`。存在しない場合は`null` |
| `arrivalBuffer` | `eventStart − estimatedArrival`。`estimatedArrival`が`null`の場合は`null` |
| `isStartNavigationEnabled` | `false`固定（Phase 1からの既存仕様を維持） |

- **「希望余裕」と「実現余裕」の概念区別（G-5）**: `PlanningContext.arrivalBuffer`（仕様§4「希望到着余裕」＝ユーザー希望の余裕時間）と、上表で実際の到着予測から逆算される`arrivalBuffer`（実現余裕）は異なる概念である。両者が一致するとは限らない。
- **既存のPhase 1テストT-DEP-2（`arrivalBuffer`負値警告、`DepartureScreen.kt`に実装済み）は、Phase 4の配線範囲では本番到達不能である**。Phase 4はDepartureViewModelへ確定済みPlanの初期値を供給するのみであり、実際の現在地・経路からの継続的な再計算（仕様§29）は行わないため、初期算出された`arrivalBuffer`が負値になる経路が実運用フローに存在しない。この事実はG4完了報告に明記する（R-8）。

---

## 8. テストケース表

### 8.1 分類定義

区分E1（純粋JVM）／E2（Robolectric＋Compose）／E3（instrumented）の3区分とする。

| 区分 | 内容 | source set | runner | Gradleタスク | 必要端末 |
|---|---|---|---|---|---|
| E1 | 純粋JVM（Android Framework依存最小、または`fun interface`のfakeのみに依存） | `src/test` | JUnit4 | `:app:testDebugUnitTest` | 不要 |
| E2 | Robolectric＋Compose Test（画面・ViewModel挙動、リソース解決） | `src/test` | JUnit4 + Robolectric（＋Compose Test） | `:app:testDebugUnitTest` | 不要 |
| E3 | Compose Test（instrumented） | `src/androidTest` | AndroidJUnitRunner + Compose Test | `:app:connectedDebugAndroidTest` | 必要（エミュレータ） |

全実行は`--console=plain`で行い、ログを`build/agent-logs/`へ保存する（Phase 1・Phase 2の先例を踏襲）。

### 8.2 テストケース一覧（全43件：正常系23／異常系5／エッジケース15。E1区分31件〔T-BPE 29件＋T-P4DI 2件〕／E2区分10件〔T-P4UI 5件＋T-P4DEP 5件〕／E3区分2件〔T-P4E2E 2件〕）

#### F40/F42/F43/F44/F45/F46 — `BasicPlanningEngine`（E1・純粋JVM／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-BPE-1 | 正常系 | `transitionStart`が仕様§13式（`EventStart − ArrivalBuffer − TravelTime − PreparationTime − TransitionTime`）と一致する（旧T-MOCK-10） | BasicPlanningEngine |
| T-BPE-2 | 正常系 | `departureTime = eventStart − arrivalBuffer − travelEstimate`と一致する | BasicPlanningEngine |
| T-BPE-3 | 正常系 | `estimatedArrival = departureTime + travelEstimate`と一致する | BasicPlanningEngine |
| T-BPE-4 | 正常系 | `arrivalBuffer`は`ExecutionPlan.arrivalBuffer`へ入力値がそのまま反映される | BasicPlanningEngine |
| T-BPE-5 | 正常系 | 生成された`steps`の並び順が§48 enum順（TRANSITION→PREPARATION→DEPARTURE→TRAVEL）と一致する（G-6。M4-8の逆順を修正したことの回帰ロック） | BasicPlanningEngine |
| T-BPE-6 | 正常系 | preparationステップの`scheduledStart == transitionStart + transition所要時間` | BasicPlanningEngine |
| T-BPE-7 | 正常系 | 生成された各`ExecutionStep.semanticId`が`"transition"`/`"preparation"`/`"departure"`/`"travel"`のいずれかで不変（title解決のキーとして安定） | BasicPlanningEngine |
| T-BPE-8 | 正常系 | 生成された各`ExecutionStep.title`が空文字である（G-4。localization解決はUI層の責務） | BasicPlanningEngine |
| T-BPE-9 | エッジケース | `travelEstimate == null` → TRAVELステップを生成せず、固定値で穴埋めしない（20分捏造なし） | BasicPlanningEngine |
| T-BPE-10 | エッジケース | `travelEstimate == Duration.ZERO` → TRAVELステップを生成しない | BasicPlanningEngine |
| T-BPE-11 | エッジケース | 場所情報なしのイベント（`locationName == null`）でも`travelEstimate`が非nullなら判定に影響しない（BasicPlanningEngineは`locationName`/`coordinates`を参照しない設計の回帰ロック。旧T-MOCK-4） | BasicPlanningEngine |
| T-BPE-12 | エッジケース | `travelEstimate == null`のとき`Duration.ofMinutes(20)`等の固定値が一切使われない（20分捏造の削除を直接検証する回帰ロック） | BasicPlanningEngine |
| T-BPE-13 | 正常系 | `profile == null`のとき`BasicPlanningDefaults`の既定値（transition 5分/preparation 15分/Arrival Buffer 10分）が適用される | BasicPlanningEngine |
| T-BPE-14 | 正常系 | `profile`が非nullのとき`profile.averageTransitionDuration`/`averagePreparationDuration`が既定値より優先される | BasicPlanningEngine |
| T-BPE-15 | エッジケース | transition所要時間が0分に確定する場合（`profile.averageTransitionDuration == Duration.ZERO`）→ transitionステップが生成されない（0分ステップ非生成の原則） | BasicPlanningEngine |
| T-BPE-16 | エッジケース | preparation所要時間が0分に確定する場合（`profile.averagePreparationDuration == Duration.ZERO`）→ preparationステップが生成されない | BasicPlanningEngine |
| T-BPE-17 | エッジケース | transition・preparation・travelがすべて0分/null条件に該当する場合、生成される`steps`はDEPARTURE1件のみになる | BasicPlanningEngine |
| T-BPE-18 | 正常系 | `transitionStart < now`（スケジュールが逼迫している）でもPlan生成は継続し、ステップを自動省略しない（旧T-MOCK-7） | BasicPlanningEngine |
| T-BPE-19 | エッジケース | イベントの`startDate`が過去でも、例外を送出せずPlanを生成する | BasicPlanningEngine |
| T-BPE-20 | 異常系 | `travelEstimate`が負の値 → `IllegalArgumentException`を送出する | BasicPlanningEngine |
| T-BPE-21 | 異常系 | `arrivalBuffer`が負の値 → `IllegalArgumentException`を送出する | BasicPlanningEngine |
| T-BPE-22 | 異常系 | `profile`内の所要時間（`averageTransitionDuration`等）が負の値 → `IllegalArgumentException`を送出する | BasicPlanningEngine |
| T-BPE-23 | 異常系 | `Duration`/`Instant`演算がオーバーフローする極端な入力 → 例外が握り潰されず呼び出し元へ伝播する（**例外の型は要検証。§12未確認事項**） | BasicPlanningEngine |
| T-BPE-24 | エッジケース | DST切替を跨ぐ`eventStart`でも`Instant`基準の演算が1時間ずれない | BasicPlanningEngine |
| T-BPE-25 | エッジケース | `PlanningContext.zoneId`/`locale`を変えても算出される`Instant`値（`transitionStart`等）が変化しない（時刻演算はゾーン・ロケール非依存） | BasicPlanningEngine |
| T-BPE-26 | 正常系 | `ExecutionStep.id`が`UUID.nameUUIDFromBytes("${event.id}:$semanticId".toByteArray())`相当の決定的値で生成され、同一入力での再planningでも同一`id`になる | BasicPlanningEngine |
| T-BPE-27 | 正常系 | 生成された全ステップの`scheduledStart`が非null（`ExecutionStep.scheduledStart`はnull許容型だがBasicPlanningEngineの出力は必ず値を持つ） | BasicPlanningEngine |
| T-BPE-28 | 正常系 | `BasicPlanningEngine`が`ai/`パッケージ・`LocalLanguageModel`のいずれも参照しない構造ガード（決定的処理のみで構成。仕様§15） | PlanningLlmIsolationTest |
| T-BPE-29 | 正常系 | 生成された`ExecutionPlan`の`transitionStart`/`departureTime`/`estimatedArrival`/各ステップ`scheduledStart`が相互に矛盾しない（内部整合性の最終確認） | BasicPlanningEngine |

#### F47 — PlanReview画面（E2・Robolectric＋Compose／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P4UI-1 | 正常系 | 各ステップ行に時刻（`DateTimeFormatter.ofLocalizedTime(SHORT)`書式）が表示される（仕様§25/§26） | PlanReviewScreen |
| T-P4UI-2 | 正常系 | ja/en両ロケールでtitle表示（`semanticId`解決結果）が非空かつ言語ごとに異なる | PlanReviewScreen(i18n) |
| T-P4UI-3 | エッジケース | 未知の`semanticId`が渡ってもフォールバック文言が表示されクラッシュしない | StepTitle |
| T-P4UI-4 | エッジケース | DEPARTUREステップ1件のみのPlan（transition/preparation/travelすべて非生成）でも「準備ステップなし」相当の案内が表示され、Startボタンが有効なまま | PlanReviewScreen |
| T-P4UI-5 | 正常系 | 新規追加文言（`semanticId`解決文言・時刻ラベル等）がja/en間で文言パリティを保つ（既存`StringResourceParityTest`パターンを踏襲） | PlanReviewScreen(i18n) |

#### F48 — `DepartureViewModel`（E2・Robolectric＋Compose／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P4DEP-1 | 正常系 | 確定済み`ExecutionPlan`のマッピング（`eventStart`/`estimatedArrival`/`arrivalBuffer`）が§7.5の規則どおりに`DepartureUiState`へ反映される | DepartureViewModel |
| T-P4DEP-2 | 正常系 | 「実現余裕」（`eventStart − estimatedArrival`から算出される`arrivalBuffer`）が期待値と一致する（G-5。既存Phase 1テストT-DEP-2の負値警告表示自体はPhase 4の配線範囲では本番到達不能であり、この点はG4報告に明記する。R-8） | DepartureViewModel |
| T-P4DEP-3 | エッジケース | TRAVELステップが存在しないPlan（`travelEstimate`がnull/ZERO） → `estimatedArrival == null` | DepartureViewModel |
| T-P4DEP-4 | エッジケース | `estimatedArrival == null`のとき`arrivalBuffer`も`null`になる | DepartureViewModel |
| T-P4DEP-5 | エッジケース | Planが未確定（`sharedPlanViewModel`の確定済みPlanが未設定）の状態でもクラッシュせず初期`DepartureUiState()`のまま留まる | DepartureViewModel |

#### F40（構成差し替え） — DI（E1・純粋JVM／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P4DI-1 | 正常系 | `AppContainer.planningEngine`の型が`BasicPlanningEngine`である（`MockPlanFactory`ではない） | AppContainer |
| T-P4DI-2 | 異常系 | `com.actionstarter.mock.MockPlanFactory`がsrc/mainに存在しないこと（削除の走査確認。ディレクトリ非存在ではなくクラス非存在で検証する安全な実装とする。R-6） | パッケージ構成 |

#### F40/F47/F48 — instrumented E2E（E3／`src/androidTest`／`:app:connectedDebugAndroidTest`／エミュレータ必要。**作成のみ・実行はG4-E**）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P4E2E-1 | 正常系 | PlanReview画面の実時刻表示をja/en両ロケールでスクリーンショット取得する | E2Eフロー（i18n） |
| T-P4E2E-2 | 正常系 | Departure画面でTRAVELステップありのPlanのETA表示を確認する | E2Eフロー |

E2E群は実行するまでpassとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ。Phase 1・Phase 2の先例踏襲）。

---

## 9. エラー＆レスキューマップ（全22行。ハンドリング方法列に空欄なし）

| # | 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|---|
| 1 | TRAVEL/departure計算 | `travelEstimate`がnull（移動時間見積り未取得） | 固定値（20分等）で穴埋めせず、TRAVELステップを生成しない。`departureTime`/`estimatedArrival`はTravelTime項を0として算出する | 実際には把握していない移動時間を「わかっているかのように」見せない。移動時間欄は「移動時間未取得」表示（`DepartureUiState.estimatedArrival == null`経由）になる |
| 2 | `travelEstimate`の検証 | `travelEstimate`が負の値で渡される（呼び出し側の不正な入力） | `IllegalArgumentException`を送出し、握り潰さない | 不正な入力に基づく誤ったPlan（過去時刻への出発指示等）が画面に出ない |
| 3 | `arrivalBuffer`の検証 | `arrivalBuffer`が負の値 | `IllegalArgumentException`を送出する | 不正な余裕時間による誤ったPlanが生成されない |
| 4 | `profile`由来所要時間の検証 | `profile.averageTransitionDuration`等が負の値 | `IllegalArgumentException`を送出する | 学習データ由来の不正値が原因で不可解な時刻のPlanが生成されない |
| 5 | `Duration`/`Instant`の加減算 | 極端な入力によりオーバーフローする | 例外を握り潰さずそのまま呼び出し元へ伝播させる（例外型は要検証・T-BPE-23） | 誤ったPlan（不正なタイムスタンプ）が表示されるより、エラーとして扱われる方が安全 |
| 6 | Plan生成 | イベントの`startDate`が既に過去 | 例外を投げず、Planは通常どおり生成する（`transitionStart`等の値が過去日時になるだけ）。逼迫警告は既存の`PlanReviewViewModel.isBehindSchedule`ロジックを踏襲する | 過去のイベントでも操作が止まらず、遅れて出発する状況でも案内が継続する |
| 7 | Plan生成（`transitionStart < now`） | 残り時間が既に足りない（逼迫） | ステップを自動的に間引かない（T-BPE-18）。省略の要否はユーザー判断に委ねる（仕様§33/34と同じ思想） | システムが勝手に「これは飛ばしていい」と判断しない。ユーザー自身が何を省略するか選べる |
| 8 | テンプレート生成判定 | transition/preparation/travelの所要時間が0分（意味のないステップ） | 0分のステップは生成しない。ただしDEPARTUREは所要時間が常にZEROでも例外的に必ず1件生成する（出発という事象そのもののマーカーのため） | 空虚な「0分の準備」行が表示されずUIが簡潔に保たれる。出発マーカーは常に存在するためStartの基準点を見失わない |
| 9 | steps一覧の並び順 | 実装変更により順序が仕様§48のenum順から再度ズレる（M4-8で発見された既存の逆順バグの再発） | T-BPE-5で順序を回帰ロックし、DEPARTURE→TRAVELの順（§48 enum順）で固定する | PlanReview/Executionでの表示順が仕様どおり安定し、出発の後に移動という直感的でない逆転表示が発生しない |
| 10 | `ExecutionStep.id`生成 | 同一イベントを再planningするたびに`id`が変わり、Execution画面での完了状態追跡等が不安定になる（実測M4-9） | `UUID.nameUUIDFromBytes("${event.id}:$semanticId")`による決定的生成へ置き換える（T-BPE-26で回帰ロック） | 同じイベントに対する操作の同一性が保たれ、状態管理が安定する |
| 11 | `ExecutionStep.title`生成 | 英語文字列が直接埋め込まれ、日本語ロケールでも英語のまま表示される（実測M4-7、仕様§7違反） | `title`を空文字とし、`semanticId`をlocalizationキーとしてUI層の`StepTitle.kt`で解決する（G-4） | 日本語ユーザーにも日本語で表示される |
| 12 | `semanticId`→`stringResource`解決（`StepTitle.kt`） | 想定外の`semanticId`文字列が渡る（将来のテンプレート追加漏れ等） | 例外を投げずフォールバック文言を返す | 未知の種別のステップがあってもアプリがクラッシュせず、表示のまま操作を継続できる |
| 13 | `PlanReviewScreen`の「準備ステップなし」表示判定 | F44によりDEPARTUREが常に1件生成されるため`plan.steps.isEmpty()`判定が構造的に常にfalseになり、「準備ステップなし」メッセージが表示されなくなる（サイレントな表示ロジック崩壊） | 判定条件を「TRANSITION/PREPARATION双方が0件」へ変更する | 準備が不要なイベントで引き続き適切な案内が表示される |
| 14 | `DepartureViewModel`の初期状態 | `sharedPlanViewModel`に確定済みPlanがまだ存在しない状態で参照される | クラッシュせず初期`DepartureUiState()`のまま留まる（T-P4DEP-5） | 画面遷移の順序が万一崩れても例外で落ちない |
| 15 | `DepartureUiState`マッピング | `estimatedArrival`がnull（TRAVELなし）のとき`arrivalBuffer`の算出式が例外を起こす | `estimatedArrival`が`null`の場合は`arrivalBuffer`も`null`とする（減算を実行しない） | 移動時間が不明な予定でも例外にならず、「移動時間未取得」の一貫した表示になる |
| 16 | Departure/PlanReview表示文言 | 「そもそも移動が不要（`travelEstimate`がZERO）」と「移動時間が不明（`travelEstimate`がnull）」を画面上で書き分けようとして文言が破綻・矛盾する | 両者を区別する専用文言は作らず、共通の1種類の文言（既存の「移動時間未取得」相当）で扱う（G-9） | 文言の一貫性が保たれる。「不要」と「不明」の違いをユーザーに詳細説明しない設計上の割り切り |
| 17 | `BasicPlanningEngine`の実装構成 | 将来の変更で`ai/`パッケージや`LocalLanguageModel`を誤って参照してしまい、決定的処理のみという仕様§15の原則が崩れる | `PlanningLlmIsolationTest`（T-BPE-28）で構造的に参照がないことをテストし回帰を防ぐ | Local AIが停止・非対応の端末でもBasic Engineが独立して動作し続ける（仕様§19原則） |
| 18 | 例外送出・ログ出力 | `IllegalArgumentException`等の例外メッセージにイベントの`title`/`notes`/`locationName`が混入し、ログへ記録される | 例外メッセージはフィールド名とDuration値のみとする（既存`ExecutionStep.init`の`require`メッセージパターン「`ExecutionStep.estimatedDuration must not be negative, was $estimatedDuration`」を踏襲。イベント本文を含めない） | 予定の中身が端末外・ログへ出ない（仕様§58 Privacy） |
| 19 | `mock/MockPlanFactory.kt`削除（P4-C5） | 削除により検証意図（T-MOCK-4/7/10）が失われる | 削除ではなく検証意図の移設とし、対応表（§7.2）を計画書に明記する。移設後の件数をG3証拠に含める | 開発プロセス上の担保 |
| 20 | `AppContainer.planningEngine`差し替え（P4-C5） | `MockPlanFactory`と`BasicPlanningEngine`の出力差異（既定値の違い等）に気づかないまま切り替わる | 切替前後で`:app:testDebugUnitTest`の実測件数・build成功を比較し、意図しない回帰がないことを確認する | 切替後にPlan内容が意図せず変化しないことを保証する |
| 21 | P4-C3（Domain Green）とP4-C4（UI Green）の並列実行 | 両サイクルが同一ファイルへ同時に触れて競合する | 共有ファイル所有権規則（§10）を適用し、domain-implementerとui-implementerの担当ファイルを明確に分離する | 開発プロセス上の担保（マージ競合・相互破壊を防ぐ） |
| 22 | `features/departure/`の所有権 | Phase 3が仕様§29の再計算ロジックを`DepartureViewModel`へ実装しようとし、Phase 4が供給した初期値配線と衝突する | `features/departure/`の所有権をPhase 4に帰属させ、§29再計算はPhase 4完了後（Phase 5以降）に着手することを明記する（R-2・R-8） | 開発プロセス上の担保。Phase間の実装衝突・手戻りを防ぐ |

---

## 10. サイクル分解（P4-C1〜C8）

| サイクル | 内容 | 担当agent（Do） | 到達ゲート |
|---|---|---|---|
| P4-C1 | Scaffold: `planning/BasicPlanningEngine.kt`（本体`TODO()`）／`BasicPlanningDefaults.kt`／`features/common/StepTitle.kt`の宣言。ADR起票（G-4のsemanticId解決方式・G-1の既定値プレースホルダ扱いを記録）。ベースライン実測記録（Phase 2残存状態を含む現状の`:app:testDebugUnitTest`件数を記録し、Phase 4着手時点の基準とする） | domain-implementer | scaffoldコンパイル成功・ベースライン実測ログ |
| P4-C2 | Red: §8の全43テストケースのうちJVM系41件（E1区分31件＋E2区分10件）をfailing化し実測でRedを確認する。E2E系2件（T-P4E2E-1〜2）は作成のみ | test-writer → quality-runner | **G2** |
| P4-C3 | Green（Domain側）: `BasicPlanningEngine`／`BasicPlanningDefaults`の実装（T-BPE-1〜29）。**完了（実測2026-08-09）**: `BasicPlanningEngineTest`（T-BPE-1〜27・29）・`PlanningLlmIsolationTest`（T-BPE-28）をGreen化した。同時点の`:app:testDebugUnitTest`全体実測は164件中8件Red（`build/agent-logs/p4c3-full.log`）だが、内訳は`AppContainerTest`のT-P4DI-1/2、`DepartureViewModelTest`のT-P4DEP-1/2、`PlanReviewStepDisplayTest`のT-P4UI-1/2/4/5の8件のみで、いずれもP4-C4（UI）／P4-C5（統合）が担当する範囲（domain側T-BPE系はRed 0件） | domain-implementer（**P4-C4と並列起動**） | **G3** |
| P4-C4 | Green（UI側）: `StepTitle.kt`実装、`PlanReviewScreen.kt`への時刻表示・title解決の結線（T-P4UI-1〜5）。**完了（実測2026-08-09）**: `PlanReviewStepDisplayTest`（T-P4UI-1〜5）をGreen化した。実測（`build/agent-logs/p4c4-ui.log`）は71件中2件Red（`DepartureViewModelTest`のT-P4DEP-1/2）のみで、これはP4-C5未着手（`sharedPlanViewModel`注入前）による想定内Red（T-P4UI系はRed 0件） | ui-implementer（**P4-C3と同一メッセージで並列起動**。共有ファイル所有権規則を適用） | **G3** |
| P4-C5 | 統合ウィンドウ（直列）: `AppContainer`の`planningEngine`差し替え（1行）、`DepartureViewModel`への`sharedPlanViewModel`注入（1行）とマッピング実装（T-P4DEP-1〜5）、`mock/MockPlanFactory.kt`＋テスト削除、`ActionStarterNavHost`のKDoc更新。**本結線がPhase 3のDeparture改修の前提となる（先行実施。Fable 5裁定2026-08-09）**: 完了後にPhase 3のP3-C5（`features/departure/`のUI・統合サイクル）が着手可能になる。`di/AppContainer.kt`の編集順序もP4統合→P3統合とする。**完了（実測2026-08-09）**: `AppContainer.planningEngine`を`BasicPlanningEngine()`へ、`DepartureViewModel`初期化を`sharedPlanViewModel`注入へ切替（`AppContainer.kt`）。`mock/MockPlanFactory.kt`／`MockPlanFactoryTest.kt`を削除。コンパイル成功実測（`build/agent-logs/p4c5-compile.log`）、`:app:testDebugUnitTest`161/161 Green実測（`build/agent-logs/p4c5-full.log`、JUnit XML集計`tests=161 failures=0 errors=0`）、`:app:assembleDebug`もBUILD SUCCESSFUL実測（`build/agent-logs/p4c5-assembledebug.log`） | domain-implementer（integration owner） | **G3** |
| P4-C6 | Refactor＋`./gradlew build`/`lintDebug`エラー0の再実測。**完了（実測2026-08-09）**: (1)`:app:lintDebug`エラー0実測（BUILD SUCCESSFUL・exit 0、`build/agent-logs/p4c6-lint.log`）。warning総数24件（既知9件から+15）、内訳は`GradleDependency`+1（`androidxTestUiautomator`、既存カテゴリの版数警告）・`UnusedResources`+14。後者のうち13件（`location_permission_*`／`travel_time_manual_*`／`transport_mode_*`／`departure_eta_stale_notice`／`departure_geocode_no_match_message`）はPhase 3所有の未配線文字列リソースでPhase 4スコープ外（Phase 3進行中のため不可侵）、残り1件`execution_placeholder_step_title`はC4の`ExecutionScreen.kt`title解決統一（`resolveStepTitle`経由化）に伴い不使用化したものだが、`strings.xml`／`values-ja/strings.xml`は複数Phaseが同時編集中の共有ファイルのため本サイクルの明示的な作業範囲外と判断し未修正（フォローアップ事項として申し送る）。いずれもerrorではなくwarning（許容）。(2)軽リファクタ：`BasicPlanningEngine.kt`は既存private分割（`validateNonNegativeDurations`／`buildStep`）済みで`createPlan`本体（約75実効行・分岐3、§13式・§7.3表と1:1対応する単一責務の線形処理）は変更不要と判断し変更なし。`PlanReviewScreen.kt`はC4追加箇所（ステップ行の時刻表示＋title解決）を`PlanReviewStepRow`private Composableへ抽出（`EventSelectionScreen.kt`の`EventRow`、P2-C6先例踏襲。testTag・文字列リソース・挙動は不変）。変更を伴ったため全スイート再実測を実施し161/161 Green維持を確認（`build/agent-logs/p4c6-postrefactor.log`）。(3)`MockRecoveryFactory.kt`のダングリングKDocリンク（`[com.actionstarter.mock.MockPlanFactory]`、削除済みクラスへの未解決参照）を`[com.actionstarter.planning.BasicPlanningEngine]`へKDocのみ修正（C5申し送り解消）。(4)`:app:build`（テスト込みフルビルド）実測（`build/agent-logs/p4c6-build.log`）はBUILD FAILED（32件Red）だが、**32件全てPhase 3が本サイクル中に並行追加した新規テスト**（`DepartureRoutingScreenTest`8／`DepartureRoutingViewModelTest`9／`GeoDistanceTest`3／`RoutesApiRequestBuilderTest`3／`RoutesApiResponseParserTest`5／`LocationNameNormalizerTest`4＝計35件中32件Red、3件Green）であり、Phase 4所有テスト（`BasicPlanningEngineTest`28・`PlanningLlmIsolationTest`1・`PlanReviewStepDisplayTest`5・`DepartureViewModelTest`5・`AppContainerTest`4の計43件）はRed 0件で全Green実測（同ログのJUnit XML集計）。したがってG4-JVM（Phase 4関連クラスの全Green）はPhase 4スコープにおいて達成、`:app:build`タスク自体のBUILD SUCCESSFULはPhase 3のクローズ待ちで本サイクル時点では未達（詳細は完了報告参照） | ui-implementer/domain-implementer → quality-runner | **G4-JVM** |
| P4-C7 | 薄いG4-E: PlanReview実時刻のja/enスクリーンショット取得（T-P4E2E-1）＋Departure ETA表示確認（T-P4E2E-2） | quality-runner | **G4-E** |
| P4-C8 | **追加サイクル（Fable 5指示、G4-E後の統合漏れ発見に伴う追補。当初計画§10のP4-C1〜C7には存在しなかった）**: Plan構築時の実移動時間統合（仕様§13の式の完全実装）。**背景**: `planning/BasicPlanningEngine.kt`は仕様§13の式（`StartOfTransition = EventStart − ArrivalBuffer − TravelTime − PreparationTime − TransitionTime`）とF44（`travelEstimate == null`でもTRAVELステップなしでPlanが成立するフォールバック）を正しく実装済みだったが、`features/planreview/PlanReviewViewModel.kt`の`buildPlanningContext`は`travelEstimate = null`を無条件にハードコードしており、TravelTime項を取得しにいく主経路（Phase 3実装済みの`GeocodingService`／`LocationService`／`RoutingService`。`features/departure/DepartureViewModel.kt`で実績あり）がPlan構築側に一切配線されていなかった（計画の谷間の統合漏れ。F44の「取得できなかった場合のフォールバック」自体は正しいが、「常に取得を試みずフォールバックのみ返す」状態で運用されていた）。**設計裁定（ADR-0038）**: `PlanReviewViewModel`へ`geocodingService`／`locationService`／`routingService`／`permissionGate`の4引数をいずれもデフォルト値`null`で追加（`ExecutionViewModel`のADR-0028と同型の後方互換パターン、既存2引数構築を破壊しない）。イベント選択時にまず`travelEstimate=null`でPlanを即時表示し（ユーザーを待たせない）、`DepartureViewModel.recalculate`と同型の「geocode→currentLocation→estimateRoute」パイプラインを同一suspendチェーン内で実行し、成功時のみPlanを再構築して差し替える（差し替えは`StartOfTransition`が移動時間ぶん早まる方向にのみ変化）。いかなる失敗（`GeocodeResult.NoMatch`/`Failure`、`LocationResult.PermissionDenied`/`Failure`、`RoutingException`の任意のサブクラス、位置権限なし、`locationName`空/null）でも例外を握り潰さず`null`へフォールバックしF44の3ステップPlanへ回帰する。`transportMode`既定値は`DepartureUiState`の既定（`TransportMode.TRANSIT`）と同一値を使用。`di/AppContainer.kt`の`createViewModelFactory`は`DepartureViewModel`と同一の共有サービスインスタンス（`routingService`は`CachingRoutingService`または`UnconfiguredRoutingService`）を`PlanReviewViewModel`へも供給し、Departure画面遷移時のキャッシュ共有（§8、二重フェッチ回避）を実現する。詳細な代替案比較はADR-0038参照。**実測結果（domain-implementer、2026-08-10）**: TDD Red→Green完遂。Red実測は`PlanReviewViewModelTest.kt`（新規、T-P4C8-1〜5）が新規4引数を参照するため`:app:compileDebugUnitTestKotlin`がコンパイル不能（`No parameter with name 'geocodingService'/'locationService'/'routingService'/'permissionGate' found`）で失敗することを確認（`build/agent-logs/p4c8-red.log`）。Green実装後、対象5件個別実行で5/5 Green（`build/agent-logs/p4c8-green-targeted.log`）。全JVMスイート`:app:testDebugUnitTest --rerun`で**373 tests・failures 0・errors 0・skipped 1**（`build/agent-logs/p4c8-full.log`。P4-C6完了時点の368件から新規5件の純増のみで、既存368件は無改造のままGreen維持）。`NavigationFlowTest`5/5・`PlanReviewScreenTest`6/6・`PlanReviewStepDisplayTest`5/5・`ExecutionOneActionTest`8/8・`DepartureRoutingViewModelTest`10/10・`DepartureViewModelTest`5/5・`CalendarNavigationFlowTest`1/1を個別確認済み（いずれもRobolectricのカレンダーfixtureが`EVENT_LOCATION=null`で構成されているため、新規フェッチ経路が構造的に起動せず既存の3ステップPlan挙動・タップ数と一致する）。T-P4C8-1はサービス未配線のベースラインPlanとの`transitionStart`差分が実測した移動時間（25分）と厳密一致することを直接アサーションし、「StartOfTransitionが移動時間ぶん早まる」ことを回帰ロックした。`:app:lintDebug`は**error 0**・warning **22件**（P6-C5時点から不変）・UnusedResources**3件**（不変、`strings.xml`無変更の裏付け）を実測（`build/agent-logs/p4c8-lint.log`）。**制約遵守の確認**: `androidTest/e2e/`・`docs/plans/phase5-notification-execution.md`／`phase6-recovery-basic.md`／`phase11-i18n-a11y.md`・`res/values*/strings.xml`・`AndroidManifest.xml`・`features/departure/DepartureViewModel.kt`・`services/`配下はいずれも変更していない（読み取り・呼び出しのみ）。`navigation/ActionStarterNavHost.kt`も変更不要だった（`AppContainer`のfactoryが引数を供給する既存設計のまま吸収）。エミュレータ・adbは使用していない。git commitは行っていない。Gradleロック競合は発生しなかった（全実行が初回試行で成功、60秒リトライの発動は不要だった） | domain-implementer | **G4-JVM再検証** |

**P4-C3/P4-C4並列時の所有権規則**: `planning/`配下・`di/AppContainer.kt`・`navigation/ActionStarterNavHost.kt`の既定所有者はdomain-implementerのみ。ui-implementerはP4-C4の間これらに一切触れず、必要が生じたら中断してFable 5へ報告する（Phase 1/2の先例踏襲）。

**着手前提**: P4-C1の着手前提条件は**Phase 2のG4-JVM通過**とする（R-1）。**Phase 2はC5-fixで122/122 Green達成済み（2026-08-09・`p2c5fix-full.log`）。C6/C8のクローズ工程が進行中である**（§7.1実測M4-12、`docs/plans/phase2-calendar.md`§18）。P4-C1のベースライン確認は「着手時点の全スイートGreen実測（現行122件＋Phase 2 C6での増減を反映した件数）の記録」に簡素化する。

---

## 11. リスク

| ID | リスク | 対応 |
|---|---|---|
| R-1 | Phase 2の残存事項がPhase 4のベースライン実測を汚染する（旧懸念: Red 7件の残存。**C5-fixで解消済み・122/122 Green＝2026-08-09**） | **Phase 2はC5-fixで122/122 Green達成済み（2026-08-09・`p2c5fix-full.log`）。C6/C8のクローズ工程が進行中である。** Phase 4着手（P4-C1）の**着手前提条件はPhase 2のG4-JVM通過として維持する**。P4-C1のベースライン確認は「着手時点の全スイートGreen実測（現行122件＋Phase 2 C6での増減を反映した件数）の記録」に簡素化する |
| R-2 | `features/departure/`の所有権がPhase 3（Routing実装）とPhase 4のいずれに属するか曖昧なまま並行開発が進み、実装が衝突する | 所有権をPhase 4に帰属させることを本書で明記する（§6.5、§9エラーマップ#22）。仕様§29の再計算ロジックはPhase 4完了後に持ち越す |
| R-3 | P4-C3（Domain）とP4-C4（UI）の並列実行で共有ファイルへ同時に触れ競合する | 共有ファイル所有権規則（§10）を適用し、担当外ファイルへの変更が必要になった場合は中断して報告させる |
| R-4 | 0分ステップ非生成の原則と、`ActionStarterNavHost`のKDocが前提とする既存の画面遷移ロジック（`confirmedPlan.steps.firstOrNull()`ベースの構築等）との間に、steps非空を前提にした分岐の陳腐化等の矛盾が生じる | P4-C2の実測で確認する（要検証。§12） |
| R-5 | 決定的id化（`UUID.nameUUIDFromBytes`への変更）が既存テスト（`id`の一意性やランダム性を前提にしたassertion）に予期しない影響を与える | P4-C2の実測で確認する（要検証。§12） |
| R-6 | `mock/`ディレクトリが将来消滅した場合、パッケージ非存在を検証するテストが想定しないパス解決エラーでhard failする | `mock/`ディレクトリ自体の存在ではなく`MockPlanFactory`クラスの非存在を検証する安全なテスト実装（T-P4DI-2）とする。ディレクトリ消滅を前提にしたテストは書かない |
| R-7 | `BasicPlanningDefaults`の既定値（transition 5分/preparation 15分/Arrival Buffer 10分）に仕様上の根拠がなく（Arrival Buffer 10分自体は仕様§4のNormalプリセットに根拠があるが、4種テンプレート構造とtransition/preparationの分数は仕様未定義）、後日変更が必要になったときに変更箇所が分散している | 既定値を`BasicPlanningDefaults.kt`へ隔離し、KDocで「仕様未定義プレースホルダ・Phase 10で置換」と明記する。ADR再検討トリガーとして記録する |
| R-8 | 「Phase 4完了」の報告が、仕様§29が定める「最新現在地・経路情報からの再計算」の実装完了と誤解される | G4完了報告で「Departure実値供給は初期値のみであり、§29の継続再計算はPhase 4のスコープ外（Phase 5以降）」と明記する。既存Phase 1テストT-DEP-2の負値警告表示がPhase 4の配線範囲では本番到達不能であることも同報告に含める（§7.5） |

---

## 12. 未確認事項

- ~~**T-BPE-23の例外型**: `Duration`/`Instant`演算のオーバーフロー時に送出される例外の具体的な型（`ArithmeticException`か`DateTimeException`か等）は未確認。P4-C1/C2で実測確定する。~~ → **確定済み（P4-C6実測、2026-08-09）**: `java.time.DateTimeException`（メッセージ「Instant exceeds minimum or maximum instant」）。T-BPE-23の入力（`event.startDate = Instant.MIN`、`arrivalBuffer`10分／`travelEstimate`20分／`profile`所要時間計30分）では、`BasicPlanningEngine.createPlan`が最初に評価する`transitionStart`算出の1減算目（`event.startDate.minus(context.arrivalBuffer)`）の時点で即座にオーバーフローする。本プロジェクトのGradle実行JVM（`app/build.gradle.kts`の`sourceCompatibility`/`targetCompatibility`/`jvmTarget`=17、実行環境実測`java -version`＝OpenJDK 17.0.19）上で`jshell`により`Instant.MIN.minus(Duration.ofMinutes(10))`を直接実行し実測確認した。`BasicPlanningEngineTest.kt`のT-BPE-23実装は`assertThrows(Exception::class.java)`という上位型判定のままであり、本確認によってテストのアサーションを変更してはいない（変更禁止制約に従う）。
- **R-4（0分省略とNavHost KDoc前提の整合）**: 未確認。P4-C1/C2で実測確定する。
- **R-5（決定的id化の既存テストへの影響）**: 未確認。P4-C1/C2で実測確定する。
- **`kotlinx-coroutines-test`の実動作**: 導入済み（M4-11、`MockPlanFactoryTest`で使用実績あり）ではあるが、BasicPlanningEngineの新規テスト群（T-BPE-1〜29）での実動作そのものはP4-C1/C2で実測確定する。
- **Phase 2の実件数**: **Phase 2はC5-fixで122/122 Green達成済み（2026-08-09・`p2c5fix-full.log`）。C6/C8のクローズ工程が進行中である。** 本書のP4-C1着手前提条件（R-1。Phase 2のG4-JVM通過）の判定材料としては、P4-C1着手時点の全スイートGreen実測（現行122件＋Phase 2 C6での増減を反映した件数）を記録することに簡素化する。C6/C8完了後の最終件数は本書時点では未確定であり、P4-C1着手直前に再実測して確定する。
- **次のADR番号**: `DECISIONS.md`の最新確定ADRはADR-0014（本書作成時にplan-doc-writerが実測確認済み）。ADR-0015は`docs/plans/phase2-calendar.md`のHilt裁定（B17）向けに予約されたが発効せず記録されなかった。したがって次にPhase 4が起票するADR（G-1の既定値プレースホルダ扱い等）がADR-0015を再利用するかADR-0016から採番するかは未確定であり、P4-C1で確定する。
- ~~**G-7・G-8の裁定内容**: 転記元メモに個別の記載がなく未確認~~ → **裁定済み（2026-08-09補填）**。元の計画メモ§9にあった推奨内容（Fable 5承認済み）で§4裁定表を補填した（§4参照）。

いずれも「要検証（P4-C1/C2で確定）」として扱い、確定するまで本書の該当箇所（§8テストケース表・§10サイクル分解・§11リスク）の記述を最終と見なさない。

---

## 13. 申し送り

- 本計画書はandroid-planner作成のPhase 4計画メモ（§0〜§10）を忠実に文書化したものである。計画メモにない機能・仕様を自己判断で追加していない。
- Fable 5はPass1レビューによりG-1〜G-9を計画メモの推奨案どおり承認した（2026-08-09、§4）。**G-7・G-8は本書初版作成時点では転記元メモに個別の裁定内容が見当たらず「転記元に記載なし」と一時マークしていたが、2026-08-09に元の計画メモ§9にあった推奨内容（Fable 5承認済み）で補填し、裁定済みとして§4裁定表へ反映済み**。
- **Geminiクロスレビュー（`model: "gemini-3.5-flash"`固定）はG1として実施済み**であり、指摘されたCRITICAL 3件（Departure層の所有権と直列化、ForegroundGate判定式の拡張はPhase 3側で反映、Phase 2クローズ前提の更新）はFable 5裁定（2026-08-09）により本書へ反映済みである（→G1通過）。
- **Phase 4サイクル（P4-C1）の着手前提条件はPhase 2のG4-JVM通過**とする（R-1）。**Phase 2はC5-fixで122/122 Green達成済み（2026-08-09・`p2c5fix-full.log`）。C6/C8のクローズ工程が進行中であり**、同計画書§15(d)(e)のarchitectレビュー未解決事項2件の解消状況は本書時点で未確認である。P4-C1のベースライン確認は「着手時点の全スイートGreen実測（現行122件＋Phase 2 C6での増減を反映した件数）の記録」に簡素化する。
- テストケース件数は本書内で数え直し、**全43件**（T-BPE-1〜29の29件＋T-P4UI-1〜5の5件＋T-P4DEP-1〜5の5件＋T-P4DI-1〜2の2件＋T-P4E2E-1〜2の2件＝43件。正常系23／異常系5／エッジケース15。E1区分31件・E2区分10件・E3区分2件）で一致していることを確認済み。
- エラー＆レスキューマップは**全22行**（#1〜#22）で一致していることを確認済み。ハンドリング方法列に空欄はない。
- 本書作成にあたり、plan-doc-writerは転記対象のandroid-planner計画メモに加え、根拠付けのため既存ソースファイル（`MockPlanFactory.kt`／`MockPlanFactoryTest.kt`／`PlanningEngine.kt`／`PlanningContext.kt`／`ExecutionPlan.kt`／`ExecutionStep.kt`／`ExecutionStepType.kt`／`StepPriority.kt`／`PersonalExecutionProfile.kt`／`PlanReviewScreen.kt`／`PlanReviewViewModel.kt`／`PlanReviewUiState.kt`／`DepartureScreen.kt`／`DepartureViewModel.kt`／`DepartureUiState.kt`／`AppContainer.kt`）と正仕様書（§4／§13／§14／§25／§26／§29／§48／§58／§68／§88／§89）を直接確認し、メモの記載内容（M4-1〜M4-9、G-1/G-4/G-6の特記事項）と矛盾がないことを検証した。本作業では**production codeを一切変更していない**（読み取りのみ）。
- 転記漏れの確認: 転記元メモ§0〜§10の全項目を本書へ反映した。**G-7・G-8は本書初版作成時に入手したテキストに個別記載がなかったため一時的に「転記元に記載なし」としていたが、2026-08-09に元の計画メモ§9の推奨内容で補填し、転記漏れは解消済みである**（上記および§4参照）。
