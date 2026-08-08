# Action Starter Android ― Phase 1 実装計画書：UI Skeleton + Domain（Mock Data）

**対象Phase**: Phase 1（仕様書§65 Phase 1、§92 Phase 1開始Prompt）
**正仕様書**: `Action_Starter_Master_Specification_v2.0_Android.md`（全95節＋Changelog。2026-08-08レビュー反映版。§9のRoutingServiceコード例は本計画書提出と同時に§46準拠へ修正済み＝小修正A）
**起点計画メモ**: android-planner（Opus）作成、2026-08-08
**本書作成**: plan-doc-writer（Sonnet）、2026-08-08
**関連ハーネス文書**: `docs/TEAMS.md`（役割分担・PDCA・品質ゲートの正。TDD原則の例外規定は本計画書提出と同時にC1を追加＝小修正B）、`docs/GOAL.md`（リリース判定基準。Phase 1は/goal第1弾スコープ＝Phase0〜6＋11の一部）
**前提計画書**: `docs/plans/phase0-repo-docs.md`（Phase 0のG1判定を前提とする）

本書と正仕様書v2.0に差異が生じた場合は仕様書v2.0が正とする。本書はandroid-plannerの計画メモ§4〜§14をそのまま文書化したものであり、計画メモにない内容を自己判断で追加していない。

---

## 1. 目的

Phase 1は、実カレンダー・位置情報・Routes API・LLM接続を一切使わず、Mockデータのみで5画面（Event Selection → Plan Review → Execution → Departure、割込としてRecovery）のUXをEmulator上で一気通貫に動作させることを目的とする（仕様§65、`docs/TEAMS.md`§5 Phase1行）。あわせてPlanningEngine／RecoveryEngine／RoutingService／LocalLanguageModelのinterface契約をここで確定し（契約scaffold）、Phase 2以降のui-implementer／domain-implementer並列実装の土台とする。

実行はGradle基盤構築＋バージョンprobe（C1）を皮切りに、契約scaffold（C2）→ Red（C3）→ Green並列（C4）→ 統合（C5）→ Refactor（C6）→（KVM解決後の）Instrumented E2E（C7）の7サイクルで進める（§15）。

## 2. スコープ

### 2.1 やること

F1〜F11（詳細は§6）。CalendarProvider・GPS・Routes API・LLM接続は禁止し、すべてMockのみで完結させる。

### 2.2 やらないこと（実装しないもの。仕様§65+§92のPhase 1範囲外）

CalendarProvider読取・位置情報・Routes API・LLM推論・通知/AlarmManager・Room・Settings画面・Basic/AI切替UI・Plan編集実処理。

## 3. ゲート

Phase 1はコード成果物（Gradleプロジェクト成立後）に該当するため、`docs/TEAMS.md`§6「コード（Gradleプロジェクトが成立後）｜G1 + G2 + G3」および「Phase完了｜上記 + G4」に基づきG1〜G4すべてを適用する。**ただしG4はADR-0006（Fable 5裁定U3）により2段階に分割する。**

- **G1（計画承認）**: 本計画書＋エラー＆レスキューマップ（§12）＋Fable 5 Pass1/Pass2レビュー記録＋Geminiクロスレビュー結果（`docs/TEAMS.md`§6 G1節）。Geminiクロスレビューは実施済みであり、指摘事項はFable 5裁定A1〜A7として本書へ反映済みである（§4参照）。
- **G2（Red確認）**: 各PDCAサイクル（主にC3）でtest-writerが作成したfailingテストをquality-runnerが実測する（`docs/TEAMS.md`§6 G2節）。
- **G3（Green確認）**: 各サイクル（C1基盤限定・C4・C5）でのGreen実測、およびRefactor後の再実測（`docs/TEAMS.md`§6 G3節）。
- **G4-JVM（Phase 1完了・JVM側）**: C6完了時点。`./gradlew build`成功・対象範囲のJVM/Robolectric全テストPass・`lintDebug`エラー0を実測する。**Phase 2はG4-JVM通過をもって着手を許可する**（KVM解決を待たない）。
- **G4-E（Phase 1完了・Emulator側）**: C7完了時点。KVM解決後、`connectedDebugAndroidTest`実行・ja/en両ロケールでのスクリーンショット取得・T-E2E-1〜3の実測を行う。**G4-E未達のままPhase 3以降へ進むことを禁止する。** G4-E未達の場合はその旨を`DECISIONS.md`と完了報告へ明記し、`docs/GOAL.md`のカテゴリD（エミュレータE2E実測・20点）は未採点のまま残す。

## 4. 承認状態

本計画書はFable 5（オーケストレーター）によるPass1（CRITICAL）／Pass2（INFORMATIONAL）アーキテクトレビューを経て作成されている。**Gemini 3.5 Flash（`model: "gemini-3.5-flash"`固定）による第三者クロスレビューは実施済みである（2026-08-08）。指摘事項はFable 5裁定A1〜A7として本書へ反映済みである（下記参照）。**

以下のFable 5裁定（2026-08-08）は、上記の計画書レビューサイクルとは別に、計画メモ提出時点で個別に確定した**承認済み判断**である。**いずれもユーザー承認待ちの項目ではない。**

| # | 裁定内容 | 関連ADR／仕様§ | 反映箇所 |
|---|---|---|---|
| U1 | RoutingServiceは§46のシグネチャ（`estimateRoute(origin, destination, mode, departureDate: Instant): RouteEstimate`）を採用する | ADR-0004／仕様§9・§46 | 本書§9.3。仕様書§9のコード例（小修正A、適用済み） |
| U2 | 未定義型7種（PlanningContext／RecoveryPlan／RouteEstimate／Coordinate／CalendarSource／AIPlanResponse／AIRecoveryResponse）は計画メモ§7.2の補完案どおり確定する | ADR-0005 | 本書§9.2 |
| U3 | TDD原則の例外にC1 Gradleブートストラップを追加する（テストランナー不在のためRed不能。C1完了時のSmokeComposeTest Green実測を必須化） | ADR-0006 | 本書§15（C1行）。`docs/TEAMS.md`62行目（小修正B、適用済み） |
| U4 | Recovery画面到達用に`BuildConfig.DEBUG`ガード付き検証トリガーを設置する（releaseには非搭載） | 仕様§29／§31 | 本書§10.4 |
| U5 | 準備stepが0件でもStart可とする（移動のみのPlanも成立させる） | 仕様§26 | 本書§11.2 T-PLAN-4 |
| U6 | `mock/`は`src/main`配置とする（Phase 2で削除。KDocに明記） | 仕様§43 | 本書§8 |

### G1クロスレビュー（Gemini）反映事項（2026-08-08。Fable 5裁定A1〜A7）

以下はGeminiによるG1クロスレビューの指摘を受け、Fable 5が裁定・承認した修正である。**いずれも承認済みであり、ユーザー承認待ちの項目ではない。**

| # | 裁定内容（要約） | 反映箇所 |
|---|---|---|
| A1 | C2契約scaffoldのスコープに5画面Composableスタブ・画面ViewModelスタブを追加（コンパイル可能性のためのG2整合） | 本書§9.4、§15 C2/C3行 |
| A2 | Domain modelを`var`から`val`＋`copy()`＋`init`再検証方式へ変更（ADR-0010） | 本書§9.1、§11.2 T-DM-11、§16 R5 |
| A3 | KVM未解決時の救済条項（実機／Windows側エミュレータ代替、時限付きキャリー延長） | 本書§16 R3 |
| A4 | テスト実装手法注記4件（T-E2E-3／T-EXEC-2／T-SEL-5／T-EXEC-8） | 本書§11.2 各該当箇所 |
| A5 | 画面Composableの疎結合規約（ラムダ引数によるNavigation） | 本書§10.6、§15 C4/C5行 |
| A6 | ADR-0010の追加 | `docs/plans/phase0-repo-docs.md`§8 |
| A7 | TDD例外規定の文言微修正（A1整合） | `docs/TEAMS.md`62行目 |

## 5. 前提：初期バージョンピンと要検証事項（C1 probe対象）

C1（§15参照）で確定させるバージョン構成の出発点。**要検証とされる7項目は確定値ではない**（下記のとおり）。

**推奨初期ピン（第1候補）**: Gradle 8.14.5 + AGP 8.13.2 + Kotlin 2.4.10 + Compose BOM 2026.06.01（→ui 1.11.4） + navigation-compose 2.9.8 + lifecycle 2.11.0 + activity-compose 1.13.0 + core-ktx 1.19.0 + Robolectric 4.16.1 + kotlinx-coroutines-test 1.11.0 + compileSdk/targetSdk 35 + minSdk 26（JDK 17前提。追加ダウンロード不要）。

**第2候補**: Gradle 9.7.0 + AGP 9.3.1 + compileSdk 36（`sdkmanager`で`platforms;android-36`追加が必要）。

**降格経路**: Kotlin 2.3.21 + 1世代前のCompose BOM。

**要検証7項目（すべて「要検証（C1 probeで確定）」とし、本計画書では確定値として扱わない）**:

1. AGP 9.xのcompileSdk 35許容
2. AGP 9.xのJDK 17動作
3. AGP 8.13.2の要求Gradle最小版とGradle 9系可否
4. Kotlin 2.4.xのCompose Compiler Plugin適用
5. Robolectric 4.16.1のSDK 35 android-all jar提供有無（未提供時は`@Config(sdk=[34])`固定または版変更）
6. Robolectric+Compose UI Test（`createComposeRule()`をsrc/testで実行、`testOptions.unitTests.isIncludeAndroidResources=true`前提）の成立 ── **本計画全体の前提**。C1 probeにsmoke test（T-BUILD-3）必須
7. LintのHardcodedTextがComposeのKotlinコード内文字列を検出するか（歴史的にXML限定）

決定結果は`DECISIONS.md`へ記録する。仕様§42の「targetSdk最新」方針はPhase 13配布前に再検討する（ADR-0007の再検討トリガー）。

## 6. 機能一覧（仕様§65 Phase 1 + §92 Phase 1開始Promptベース）

| ID | 機能 | 仕様 |
|---|---|---|
| F1 | Domain Model／ValueObjects | 仕様§47-52・§9・§46の型を定義し、不変条件を実装する |
| F2 | 契約interface 4本 | PlanningEngine(§44)／RecoveryEngine(§45)／RoutingService(§46)／LocalLanguageModel(§16)を宣言のみ（実装は`TODO()`）で用意する |
| F3 | Mock供給 | MockEventSource／MockPlanFactory／MockRecoveryFactoryを実装する |
| F4 | Event Selection画面 | 仕様§24・§35 Screen1 |
| F5 | Plan Review画面 | 仕様§26・§35 Screen2。AIが勝手に確定しない |
| F6 | Execution One Action画面 | 仕様§27-28・§35 Screen3。NOW＋1行動＋Done＋5 min later |
| F7 | Departure画面 | 仕様§29・§35 Screen4。Start navigationは無効（未実装） |
| F8 | Recovery画面 | 仕様§31-34・§35 Screen5。最大3案 |
| F9 | Navigation | Selection→Review→Execution→Departure、Recovery割込 |
| F10 | i18n基盤 | `values/`=en、`values-ja/`=ja。全箇所`stringResource()`使用 |
| F11 | ビルド基盤 | Gradle Wrapper／Version Catalog／単一`:app`モジュール |

実装しないものは§2.2のとおり。

## 7. 基盤判断

### 7.1 モジュール構成：単一`:app`

仕様§43はパッケージ構造を明示しており、モジュール分割は不要と判断する。分割はビルド失敗面を増やすのみであり、仕様§89のModular原則はパッケージ境界（`domain/`・`planning/`・`recovery/`・`ai/`等）で満たす。レイヤー越境（`features/`から`planning/`・`recovery/`・`ai/`の具象実装への直接依存）はG4レビュー観点かつチェックリスト化して禁止する。再検討タイミングはPhase 7（ADR-0002）。

### 7.2 依存管理：Version Catalog採用

`gradle/libs.versions.toml`に集約する。共有ファイルの衝突面を最小化し、§5の降格経路を1ファイル差分で適用できるようにするための選定である。

### 7.3 DI：手動DI（AppContainer）

HiltはPhase 2先頭へ延期する（ADR-0003）。条件：全ViewModelはコンストラクタ注入のみとし、生成は`AppContainer`＋単一`ViewModelProvider.Factory`1箇所に集約する。この制約を守ることで、Hilt移行時はアノテーション付与＋`AppContainer`削除のみで完了する設計とする。

### 7.4 SDKバージョン：minSdk 26／compileSdk・targetSdk 35

`java.time`をネイティブ利用できるためdesugaring不要（§5の前提バージョンピン参照）。

### 7.5 i18n：初日から対応

`values/`=en（デフォルト・フォールバック）、`values-ja/`=ja（ADR-0009）。Composable内の文字列直書きを禁止する。LintのHardcodedTextはCompose非対応の可能性があるため、強制手段はT-I18N-1／T-I18N-2とG4レビューで担保する。時刻表示は`DateTimeFormatter.ofLocalizedTime(SHORT)`を用い、`"HH:mm"`等のハードコードを禁止する。

## 8. パッケージ配置（仕様§43準拠）

`com.actionstarter`直下：

```
com.actionstarter/
├── ActionStarterApplication.kt
├── MainActivity.kt
├── di/
│   └── AppContainer.kt              … Phase 1限定（§7.3）
├── domain/
│   ├── model/                       … §47-52
│   └── valueobject/                 … Coordinate, TransportMode, CalendarSource, RouteEstimate
├── planning/
│   └── PlanningEngine.kt
├── recovery/
│   └── RecoveryEngine.kt
├── services/
│   └── routing/
│       └── RoutingService.kt
├── ai/                               … interface + DTOのみ
├── mock/                             … Phase 1限定。src/main配置（U6・Fable 5裁定・承認済み）。
│                                        Phase 2で削除する旨をKDocへ明記すること
├── navigation/
├── features/
│   ├── eventselection/
│   ├── planreview/
│   ├── execution/
│   ├── departure/
│   └── recovery/
└── ui/
    └── theme/
```

**空プレースホルダ禁止（仕様§88）**: `services/calendar`・`services/location`・`services/notification`・`persistence/`はPhase 1では作らない（それぞれPhase 2／3／5／10まで作成しない）。

**U6（Fable 5裁定・承認済み）**: `mock/`パッケージは`src/main`に配置する。Phase 2で実データ実装に置き換わり次第削除する方針を、`mock/`配下の各ファイルのKDocに明記すること。

## 9. 契約scaffold（C2・TDD例外工程）

### 9.1 仕様どおりに実装するもの（Domain型の可変性方針はADR-0010により変更。下記参照）

PlanningEngine(§44) ／ RecoveryEngine(§45) ／ RoutingService(§46。§9.3参照) ／ LocalLanguageModel(§16) ／ ExecutionEvent(§47) ／ ExecutionStepType・StepPriority・ExecutionStep(§48) ／ ExecutionPlan(§49) ／ RecoveryContext(§50) ／ RecoveryOption(§51) ／ PersonalExecutionProfile(§52) ／ TransportMode(§9)。

**Domain modelは全フィールド`val`＋`copy()`＋`init`再検証方式で実装する（ADR-0010。G1クロスレビュー〔Gemini〕を受けたFable 5裁定A2、2026-08-08承認済み）。** 仕様§48／§49／§52はフィールドを`var`と表記しているが、本計画では意図的にこれから逸脱する。

**逸脱理由**: `var`のまま生成後の再代入を許すと、`init`ブロックによる不変条件検証（例: Coordinateの範囲検証、RecoveryPlanの3件上限）を迂回してサイレント障害を生む経路が生まれる。Kotlinの`data class`の`copy()`はコンストラクタを経由するため`init`が再実行され、`val`化して再代入経路を消せば検証が常に効く状態を保証できる。

ComposeへはUiStateへ写像する規約は従来どおり維持する（Composeの再コンポジション最適化上、Domain型を直接渡さない。`ARCHITECTURE.md`に明記する）。

### 9.2 補完7型（U2・ADR-0005・承認済み）

仕様に明示定義のない以下7型を、計画メモ§7.2の案どおり確定する。

| 型 | 定義 |
|---|---|
| `PlanningContext` | `event`, `now: Instant`, `zoneId`, `locale`, `transportMode`, `travelEstimate: Duration?`, `arrivalBuffer: Duration`, `profile: PersonalExecutionProfile?` |
| `RecoveryPlan` | `options: List<RecoveryOption>`。`init { require(size <= 3) }`（仕様§32） |
| `RouteEstimate` | `duration: Duration`, `mode: TransportMode`, `computedAt: Instant` |
| `Coordinate` | `lat`, `lon`。`init`で範囲検証（-90..90／-180..180）とNaN拒否を行う（信頼境界） |
| `CalendarSource` | `data class(id, displayName)`。enum禁止（仕様§6） |
| `AIPlanResponse` | 仕様§20のJSON例・§21から最小定義。完全スキーマはPhase 7 |
| `AIRecoveryResponse` | 同上 |

### 9.3 シグネチャ不一致の解消（U1・ADR-0004・承認済み）

仕様書§9のコード例と§46の定義に不一致があったが、**§46を正として採用する**（小修正Aとして、仕様書§9のコード例は本計画書提出と同時に修正済み。Changelog表§9行にも追記済み）。

```kotlin
interface RoutingService {
    suspend fun estimateRoute(
        origin: Coordinate,
        destination: Coordinate,
        mode: TransportMode,
        departureDate: Instant
    ): RouteEstimate
}
```

採用理由：TRANSITモードは出発時刻に依存し、仕様§29の再計算に`departureDate`が必要なため。

### 9.4 UIスタブ（C2スコープ拡張。A1・G1クロスレビュー〔Gemini〕を受けたFable 5裁定）

C2契約scaffoldのスコープには、Domain／interface契約に加えて以下のUIスタブも含める。

- **5画面のComposableスタブ**: EventSelectionScreen／PlanReviewScreen／ExecutionScreen／DepartureScreen／RecoveryScreenの引数シグネチャとUiState型定義のみを用意する。本文（描画ロジック）は空とする。
- **画面ViewModelスタブ**: 各画面のViewModelをコンストラクタ注入の形（依存interfaceを受け取る）で用意し、初期状態（UiStateの初期値）のみ定義する。ロジックは未実装（`TODO()`または空実装）とする。

**理由**: これらのスタブがないままC3でUIテストを書くと、参照先クラスが存在せずコンパイル不能になる。単なるコンパイルエラーによるRedは「意図した失敗」とは認められない（G2定義、`docs/TEAMS.md`§6 G2節）。C2でスタブを用意することで、C3のテストはコンパイルが通り、アサーション（期待値との不一致）によって意図どおりRedになる。

## 10. Navigation

### 10.1 使用ライブラリ

`navigation-compose`を使用する。Phase 1では型安全ルートは使用しない（`kotlinx.serialization`追加を避けるため）。`sealed interface`による文字列ルートとする。選択されたイベント・確定済みPlanはactivity-scoped共有ViewModelが保持する。

### 10.2 グラフ構成

`eventSelection` → [Prepare] → `planReview` → [Start] → `execution` → [最終Done] → `departure`
`execution` → [割込] → `recovery` → [Use this plan] → `execution`

### 10.3 One Action制約

`ExecutionScreen`は同時に1ステップのみComposeツリーに存在させる（畳んだリスト・進捗プレビューも禁止。仕様§28）。T-EXEC-2で不変条件として固定する。

### 10.4 Recovery到達トリガー（U4・Fable 5裁定・承認済み）

`BuildConfig.DEBUG`ガード付きの「Simulate delay (debug)」ボタンをexecution画面に設置する。release変種には非搭載とする。

### 10.5 バックスタック（既知の制限）

バックスタックは自然な挙動のままとする。Plan進行中の破棄防止はPhase 5で再検討する（既知の制限として記録）。

### 10.6 画面Composableの疎結合規約（A5・G1クロスレビュー〔Gemini〕を受けたFable 5裁定）

ui-implementerが実装する画面Composableは、画面遷移をすべてラムダ引数（例: `onNavigateToExecution: () -> Unit`）として受け取る疎結合構造とする。画面Composable自身は`NavController`／`NavHost`を直接参照しない。NavHostへのバインド（各ラムダに実際のnavigate呼び出しを結線する処理）はC5でintegration owner（domain-implementer）が行う（§15）。この規約により、C4でのui-implementerとdomain-implementerの並列作業時にNavHost（domain-implementer所有ファイル）への越境が構造的に発生しない。

## 11. テストケース表

### 11.1 分類（下記11.2の全ケースへ「対象／source set／runner／Gradleタスク／必要端末」として適用する分類定義）

| 分類 | source set | 実行Gradleタスク | 必要端末 | 備考 |
|---|---|---|---|---|
| JUnit4純JVM | `src/test` | `:app:testDebugUnitTest` | 不要 | 純粋Kotlinロジックのみ |
| Robolectric（+ Compose Test） | `src/test` | `:app:testDebugUnitTest` | 不要 | 成立可否はC1 probe（T-BUILD-3、§5要検証(6)）で検証する前提 |
| Compose Test（instrumented） | `src/androidTest` | `:app:connectedDebugAndroidTest` | 必要（エミュレータ。AVD: `actionstarter_test`。KVM解決後） | 作成はPhase 1、実行はG4-E |

全実行は`--console=plain`で行い、ログを`build/agent-logs/`へ保存する。

### 11.2 テストケース一覧（全67件：正常系28／異常系17／エッジケース22）

#### F1 — Domain Model／ValueObjects（JUnit4純JVM／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-DM-1 | 正常系 | `Coordinate(35.6, 139.7)`の生成が成功する | Coordinate |
| T-DM-2 | 異常系 | `lat=91`で生成すると`IllegalArgumentException` | Coordinate |
| T-DM-3 | 異常系 | `lon=181`で生成すると`IllegalArgumentException` | Coordinate |
| T-DM-4 | エッジケース | 境界値`±90`／`±180`ちょうどでは生成が成功する | Coordinate |
| T-DM-5 | 異常系 | `lat`または`lon`が`NaN`だと例外 | Coordinate |
| T-DM-6 | 正常系 | `ExecutionPlan.steps`が昇順で保持される | ExecutionPlan(§49) |
| T-DM-7 | 異常系 | `RecoveryPlan`に4件目を渡すと例外（黙って切り捨てない） | RecoveryPlan(§7.2/§32) |
| T-DM-8 | エッジケース | `RecoveryPlan`が0件でも生成は成功し、UI側で「案なし」表示に写像される | RecoveryPlan(§7.2) |
| T-DM-9 | 異常系 | `skippedStepIds`に`StepPriority.REQUIRED`のステップIDを含めると拒否（§33） | ExecutionPlan(§49)／StepPriority(§48) |
| T-DM-10 | エッジケース | `PlanningContext.travelEstimate = null`でも生成成功 | PlanningContext(§7.2) |
| T-DM-11 | 異常系 | `ExecutionStep.estimatedDuration`が負の値だと例外（**構築時およびcopy()時に検証**） | ExecutionStep(§48) |

> **ADR-0010反映（copy()時検証。A2・G1クロスレビュー〔Gemini〕を受けたFable 5裁定）**: T-DM-2／T-DM-3／T-DM-4／T-DM-5／T-DM-7／T-DM-11は、`init`による検証が初回構築時だけでなく`copy()`実行時にも同様に働くことをあわせて確認する（`val`化に伴う検証保証。§9.1参照）。

#### F3 — Mock供給（JUnit4純JVM／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-MOCK-1 | 正常系 | `MockEventSource`が返す次イベントが非null | MockEventSource |
| T-MOCK-2 | エッジケース | 予定が空リストのとき、例外ではなく空として扱われる（UI Empty） | MockEventSource |
| T-MOCK-3 | エッジケース | 全予定が過去日時のとき候補0件になる | MockEventSource |
| T-MOCK-4 | エッジケース | 場所情報なしのイベントはTRAVELステップを生成せず、ETA未取得として扱う | MockPlanFactory |
| T-MOCK-5 | 正常系 | 翌日のイベントが正しく取得でき、日付表示に反映される | MockEventSource |
| T-MOCK-6 | エッジケース | 進行中（開始済み・未終了）のイベントは候補から除外される | MockEventSource |
| T-MOCK-7 | エッジケース | `transitionStart < now`でもPlanは生成され`isBehindSchedule=true`となり、自動省略はしない（§33/34） | MockPlanFactory |
| T-MOCK-8 | 異常系 | `title=""`だとファクトリが`require`例外を送出する | MockEventSourceのイベント生成 |
| T-MOCK-9 | 異常系 | `startDate=Instant.MIN`だと例外 | MockEventSource |
| T-MOCK-10 | 正常系 | 生成される時刻計算が仕様§13の式と一致する | MockPlanFactory(§13) |

#### F4 — Event Selection画面（Robolectric + Compose Test／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-SEL-1 | 正常系 | Screen1（Next Event）の表示要素が揃っている | EventSelectionScreen |
| T-SEL-2 | 正常系 | イベントをタップすると`planReview`へ遷移する | EventSelectionScreen/ViewModel |
| T-SEL-3 | エッジケース | 空リストのとき空状態文言が表示され、クラッシュしない | EventSelectionScreen |
| T-SEL-4 | エッジケース | 場所情報がないイベントは場所行が非表示になる | EventSelectionScreen |
| T-SEL-5 | エッジケース | 100字を超えるタイトルは省略表示される | EventSelectionScreen |
| T-SEL-6 | 正常系 | ja/en双方でタイトル等の文言が非空かつ内容が異なる | EventSelectionScreen(i18n) |
| T-SEL-7 | 正常系 | 時刻表示はen-USでAM/PM表記を含み、jaでは含まない（性質検証。厳密な文字列一致は不可） | EventSelectionScreen(i18n) |

> **T-SEL-5実装注記（A4・G1クロスレビュー〔Gemini〕を受けたFable 5裁定）**: 省略表示の検証はセマンティクスツリー上の`maxLines`／`TextOverflow.Ellipsis`プロパティで行う。表示文字列内の「...」の検出による判定は不可とする。

#### F5 — Plan Review画面（Robolectric + Compose Test／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-PLAN-1 | 正常系 | ステップが昇順で表示され、Start／Editボタンが揃っている | PlanReviewScreen |
| T-PLAN-2 | 異常系 | 画面表示だけでは自動的にexecutionへ遷移しない（§26） | PlanReviewScreen/ViewModel |
| T-PLAN-3 | 正常系 | Startタップで`execution`へ遷移する | PlanReviewScreen/ViewModel |
| T-PLAN-4 | エッジケース | 準備ステップが0件でも「準備ステップなし」を表示しStart可能（U5） | PlanReviewScreen |
| T-PLAN-5 | エッジケース | `isBehindSchedule`時は警告を色とテキストの両方で表示する（§63） | PlanReviewScreen |
| T-PLAN-6 | 正常系 | Editボタンは無効化され、理由文言が表示される（Phase 1では未実装） | PlanReviewScreen |

#### F6 — Execution One Action画面（Robolectric + Compose Test／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-EXEC-1 | 正常系 | NOW表示＋現在ステップ＋Doneボタン＋「5 min later」導線が揃っている | ExecutionScreen |
| T-EXEC-2 | 異常系 | 現在ステップ以外のステップタイトルは`assertDoesNotExist`（§28 One Action） | ExecutionScreen |
| T-EXEC-3 | 正常系 | Doneタップで次ステップへ進み、`completedAt`が記録される | ExecutionScreen/ViewModel |
| T-EXEC-4 | エッジケース | 最終ステップのDoneで`departure`へ遷移し、範囲外アクセス（IndexOutOfBounds相当）が発生しない | ExecutionScreen/ViewModel |
| T-EXEC-5 | エッジケース | 準備ステップ0件で入場した場合、`departure`へ直行する | ExecutionScreen/ViewModel |
| T-EXEC-6 | 正常系 | 「5 min later」タップで`scheduledStart`が後倒しされる（ステップを省略しない） | ExecutionScreen/ViewModel |
| T-EXEC-7 | エッジケース | 画面回転後も`currentStepIndex`が保持される | ExecutionScreen/ViewModel |
| T-EXEC-8 | エッジケース | プロセス再生成後、`SavedStateHandle`から状態が復元される | ExecutionScreen/ViewModel |
| T-EXEC-9 | 異常系 | 復元不能な場合は`eventSelection`へ遷移しSnackbarで通知する（空画面を出さない） | ExecutionScreen/ViewModel |

> **T-EXEC-2実装注記（A4・G1クロスレビュー〔Gemini〕を受けたFable 5裁定）**: 各ステップComposableに`step_item_<id>`形式の`testTag`を付与し、非アクティブな全ステップの`testTag`を`assertDoesNotExist()`で検証する規約とする。
> **T-EXEC-8実装注記（A4・G1クロスレビュー〔Gemini〕を受けたFable 5裁定）**: プロセス再生成の検証はRobolectric単体テストとして分離し、ViewModelへ`SavedStateHandle`を手動注入して復元状態をアサートする方式で行う（Compose UI経由の実プロセスKillは模擬しない）。

#### F7 — Departure画面（Robolectric + Compose Test／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-DEP-1 | 正常系 | Screen4（Leave）の表示要素が揃っている | DepartureScreen |
| T-DEP-2 | エッジケース | バッファが負値のとき、色とテキストの両方で明示する | DepartureScreen |
| T-DEP-3 | エッジケース | ETAが取得できていない場合「移動時間未取得」と表示する | DepartureScreen |
| T-DEP-4 | 正常系 | 「Start navigation」ボタンは無効化され、理由が表示される（Phase 1では未実装） | DepartureScreen |

#### F8 — Recovery画面（Robolectric + Compose Test／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-REC-1 | 正常系 | Screen5（Recovery）の表示要素が揃っている | RecoveryScreen |
| T-REC-2 | 正常系 | Recovery候補が3件のとき全件表示される | RecoveryScreen |
| T-REC-3 | エッジケース | 候補0件のとき案内文言と手動導線が表示される | RecoveryScreen |
| T-REC-4 | 異常系 | `StepPriority.REQUIRED`のステップは省略候補として提示されない（§33） | RecoveryScreen/RecoveryOption |
| T-REC-5 | 異常系 | 候補選択だけでは自動適用されず、確認操作後にのみ更新される（§34） | RecoveryScreen/ViewModel |
| T-REC-6 | 正常系 | ja/en双方で文言が非空 | RecoveryScreen(i18n) |

#### F9 — Navigation（Robolectric + Compose Test／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-NAV-1 | 正常系 | Selection→Review→Execution→Departureの一連の遷移が通しで成立する | NavHost |
| T-NAV-2 | 正常系 | 戻る操作（back）が各画面で妥当に動作する | NavHost |
| T-NAV-3 | 正常系 | recoveryから「Use this plan」でexecutionへ戻る | NavHost |
| T-NAV-4 | 異常系 | Planが未確定のままexecutionへ到達しようとした場合、`popUpTo`で`eventSelection`へ戻しSnackbarで通知する | NavHost（前提検証） |
| T-NAV-5 | エッジケース | プロセス再生成後もdestinationが復元される | NavHost |

#### F10 — i18n基盤（JUnit4純JVM／`src/test`／`:app:testDebugUnitTest`／端末不要。strings.xmlを直接パース）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-I18N-1 | 異常系 | en/jaのstring resourceキー集合が完全一致しない場合、差分キー名を出力してテストが失敗する | res/values, res/values-ja |
| T-I18N-2 | 異常系 | フォーマット引数の個数が一致しない場合テストが失敗する | res/values, res/values-ja |
| T-I18N-3 | 異常系 | 空文字列のリソースがあるとテストが失敗する | res/values, res/values-ja |

#### F11 — C1 probe（ビルド基盤の成立確認）

| ID | 区分 | 内容・期待値 | 対象 | source set | runner | Gradleタスク | 必要端末 |
|---|---|---|---|---|---|---|---|
| T-BUILD-1 | 正常系 | `assembleDebug`が成功する | `:app`モジュール全体 | ― | Gradleビルドタスク | `:app:assembleDebug` | 不要 |
| T-BUILD-2 | 正常系 | `lintDebug`のエラーが0件 | `:app`モジュール全体 | ― | Android Lint | `:app:lintDebug` | 不要 |
| T-BUILD-3 | 正常系 | Robolectric+Compose smoke testがpassする（§5要検証(6)の前提検証） | SmokeComposeTest | `src/test` | Robolectric + Compose Test | `:app:testDebugUnitTest` | 不要 |

#### E2E（`src/androidTest`／`:app:connectedDebugAndroidTest`／エミュレータ必要・KVM解決後。**G4-Eでのみ実行**）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-E2E-1 | 正常系 | 主要UXが一気通貫で動作する（Selection→Review→Execution→Departure） | 全画面（E2Eフロー） |
| T-E2E-2 | エッジケース | Recovery割込からの復帰が成立する | Recovery割込フロー |
| T-E2E-3 | 正常系 | ja/en両ロケールで全画面のスクリーンショットが取得できる | 全画面（i18n） |

> **T-E2E-3実装注記（A4・G1クロスレビュー〔Gemini〕を受けたFable 5裁定）**: 1回のテストランでシステムロケールを動的切替することは困難なため、Instrumentation Runner引数、またはper-app locale（`AppCompatDelegate.setApplicationLocales()`）を用いてen/jaそれぞれで実行し、2回に分けてスクリーンショットを取得する。

E2E群は実行するまでpassとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ）。

## 12. エラー＆レスキューマップ（全17行。ハンドリング方法列に空欄なし）

| # | 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|---|
| 1 | Gradle Wrapperブートストラップ（初回`./gradlew`解決前のSHA検証） | ダウンロードしたwrapper jar／distributionのSHA-256が公式値と不一致（改ざん・破損・ミラー障害） | SHA-256照合を実施し、不一致ならブートストラップを中断する（§14手順） | セットアップが完了せず後続作業に進めない（安全側に倒すための意図的な停止。中断理由をログに残す） |
| 2 | 初回のGradle依存解決（ライブラリDL） | ライブラリバージョン間の非互換（AGP/Kotlin/Compose等の相性問題。§5要検証7項目） | 生ログをそのまま報告し、版不整合が判明した場合は§5の降格経路（Kotlin 2.3.21＋旧世代Compose BOM等）へ切替え、結果を`DECISIONS.md`へ記録する | 初回ビルドが失敗する場合があるが、降格経路により復旧できる。ユーザーはビルド遅延として体感する可能性がある |
| 3 | Mock供給（MockEventSource／MockPlanFactory／MockRecoveryFactory）でのデータ生成 | 不正なMockデータ（例: `title=""`、`startDate=Instant.MIN`等）が生成されようとする | `require()`による即時失敗とし、握り潰し（catchして無視等）を禁止する | Mockデータ不正時は開発・テストビルドがクラッシュして開発者に即座に露見する（Phase 1ではMockは本番配布物に含めないためエンドユーザー影響なし） |
| 4 | MockEventSourceからのイベント一覧取得 | 次イベント候補が0件（全予定が過去／イベント登録なし等） | 例外にせずUiState.Emptyへ明示的に写像する | Event Selection画面に空状態文言が表示され、クラッシュしない |
| 5 | Plan生成時のTRAVELステップ算出 | イベントに場所情報がない | TRAVELステップを生成せず、ETA未取得の状態として表示する | 移動時間・出発時刻の目安が表示されないが、その他の準備ステップは通常どおり利用できる |
| 6 | Plan生成時のスケジュール計算 | transitionStart（準備開始時刻）が現在時刻より過去（予定に対してすでに遅れている） | `isBehindSchedule=true`を立てて生成し、ステップを自動省略しない | Plan Review画面に遅延警告が表示されるが、全ステップは維持されユーザー自身が判断できる |
| 7 | Execution画面での端末回転（Configuration変更） | 回転によりComposeツリー・Activityが再生成される | 状態をViewModel＋SavedStateHandleに保持し、`currentStepIndex`等を復元する | 回転してもExecution中のステップ進行状況が失われない |
| 8 | OSによるプロセス再生成（メモリ回収後の復帰等） | プロセスKill後の再起動でExecution状態が失われる、または保存済み状態が破損し復元不能 | 必要最小限のキー（現在ステップID等）のみSavedStateHandleへ保存し、復元不能な場合はeventSelection画面へ遷移しSnackbarで通知する | 通常は元のステップから再開できる。復元不能時のみ選択画面へ戻され、Snackbarで状況が説明される（空白画面は発生しない） |
| 9 | 画面遷移（Navigation） | 前提条件を満たさない状態での画面到達（例: Plan未確定のままexecutionへ到達） | 遷移前提を検証し、不整合時は`popUpTo`でeventSelectionへ戻しSnackbarで通知する | 不整合な画面には留まらず、選択画面へ戻され理由が説明される |
| 10 | Execution最終ステップのDone操作 | 最終ステップDone後にインデックス参照が範囲外になりうる（IndexOutOfBounds想定箇所） | 範囲外アクセスをさせず、Departure画面への遷移として明示的に写像する | 最終ステップ完了後はクラッシュせずDeparture画面へ正常に進む |
| 11 | Recovery Option生成 | Recovery候補が0件（有効な代替案が計算できない） | NoOptions状態として明示し、手動導線を提示する（T-REC-3） | 「候補なし」の案内が表示され、ユーザーは手動で対処判断ができる |
| 12 | RecoveryPlan生成（init） | Recovery候補が仕様上限の3件を超えて4件以上生成されようとする | `init { require(size <= 3) }`で即時失敗させ、黙って切り捨てることを禁止する（§32） | 開発時に検出されるため、ユーザーには4件以上の不正なRecovery画面は到達しない |
| 13 | Recovery Option／Execution Stepの省略判定 | `StepPriority.REQUIRED`のステップがskip／省略候補として扱われようとする | 生成時点で拒否する（§33） | 必須ステップが誤って省略候補として提示されることがない |
| 14 | 未実装導線（Edit／Start navigation等のボタン） | Phase 1未実装機能をユーザーが操作しようとする | ボタンをdisabled状態にし、理由説明文を添える | 押せないことが明示され、未実装であることが理解できる（誤操作やクラッシュを防止） |
| 15 | strings.xml（values／values-ja）のキー管理 | en/ja間でstring resourceキーが欠落・不一致 | T-I18N-1〜3で事前に検出し、CIレベルで失敗させる | ビルド前に検出されるため、エンドユーザーには文言欠落状態が到達しない |
| 16 | Robolectricによる`src/test`でのAndroid Framework／Compose検証 | Robolectric 4.16.1がSDK 35のandroid-all jarを提供していない（§5要検証(5)） | `@Config(sdk=[34])`等での固定、またはRobolectricバージョン変更で対応し、結果を`DECISIONS.md`へ記録する（要検証・C1 probeで確定） | テスト実行方式の内部調整のみで、エンドユーザー向け挙動への影響はない |
| 17 | `connectedDebugAndroidTest`等instrumentedテストの実行 | エミュレータが未起動（KVM未解決等） | 実行不能である旨をそのまま報告する。G3はJVM側テストのみで判定し、instrumented分は「未実行」と明記する（G4-E待ち） | 開発プロセス上の制約でありユーザー向け直接影響はないが、Phase 3以降への進行はG4-E通過まで保留される |

## 13. ファイル構成

```
（リポジトリルート）
├── README.md                        … Phase 0新規
├── ARCHITECTURE.md                  … Phase 0新規
├── PRODUCT.md                       … Phase 0新規
├── AI.md                            … Phase 0新規
├── PRIVACY.md                       … Phase 0新規
├── DECISIONS.md                     … Phase 0新規
├── .gitignore                       … Phase 1追記: /build, /app/build, .gradle/, local.properties, *.iml, .idea/, build/agent-logs/
├── docs/
│   ├── plans/                       … 本書を含む2ファイル
│   └── evidence/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── local.properties                 … .gitignore対象
├── gradlew / gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/
        │   │   ├── values/strings.xml         … en
        │   │   ├── values-ja/strings.xml
        │   │   └── (themes)
        │   └── java/com/actionstarter/…       … §8のパッケージ配置
        ├── test/
        │   ├── SmokeComposeTest.kt
        │   ├── domain/
        │   ├── mock/
        │   ├── features/                      … ViewModel(純JVM) + Screen(Robolectric)
        │   ├── navigation/
        │   └── i18n/StringResourceParityTest.kt
        └── androidTest/
            └── e2e/MainUxFlowTest.kt           … 作成のみ（実行はG4-E）
```

## 14. Gradle Wrapperブートストラップ手順

1. `mkdir /tmp/gradle-boot`
2. `services.gradle.org/versions/current`で最新版を確認する
3. `VER=8.14.5`のbin.zipとSHA-256を取得する
4. `sha256sum -c`で照合する（不一致なら中断する）
5. `unzip`で展開する
6. `local.properties`を先に配置する（`sdk.dir=/home/noritakasawada/Android/Sdk`）
7. `/tmp/gradle-boot/gradle-$VER/bin/gradle -p <project> wrapper --gradle-version $VER --distribution-type bin`を実行する（`cd`せず`-p`で指定する）
8. 生成された`wrapper.jar`のSHA-256を公式値と照合する（コミットするバイナリのため必須）
9. `./gradlew --version`で確認する
10. `/tmp/gradle-boot`を削除する

**`gradle.properties`初期値**:

```
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
kotlin.daemon.jvmargs=-Xmx1g
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
```

**quality-runner注意事項**: 初回`assembleDebug`は数百MBのダウンロードを伴い、Bash既定の120秒タイムアウトでは不足する。`run_in_background`または`timeout 600000`（10分）を使用すること。実行は`--console=plain`とし、ログは`build/agent-logs/`へ保存する。

## 15. PDCAサイクル分解（C1〜C7）

各Cは`docs/TEAMS.md`§3の一般PDCAループ（Plan=android-planner → Do=該当agent → Check=Fable 5＋Gemini → Act=該当agent）の1インスタンスである。下表はDoフェーズの担当agentと到達ゲートを示す。

| サイクル | 内容 | 担当agent（Do） | 到達ゲート |
|---|---|---|---|
| C1 | 基盤＋バージョンprobe: Wrapper・Version Catalog・最小Composeアプリ・SmokeComposeTest作成 → `assembleDebug`／`lintDebug`／`testDebugUnitTest` Green確認 | domain-implementer | G3（基盤限定）。Red不要（U3・TDD例外。§4参照）だが**C1完了時のSmokeComposeTest Green実測は必須** |
| C2 | 契約scaffold: interface 4本＋Domain＋ValueObjects＋**5画面のComposableスタブ（シグネチャ・UiState定義のみ）＋画面ViewModelスタブ（コンストラクタ注入の形・初期状態のみ）**（§9.4・A1）を`TODO()`または空実装でコンパイル可能な状態にする | domain-implementer | TDD対象外（唯一の例外＝契約scaffold。Red不要）。次のC3の前提としてコンパイル成功を確認する |
| C3 | Red: §11のテストケースをfailing化し、実測でRedを確認する。**C2のUIスタブによりテストはコンパイル可能であり、アサーション（期待値との不一致）によって意図どおりRedになることを確認する**（単なるコンパイルエラーは意図したRedと認めない。G2定義） | test-writer → quality-runner | G2 |
| C4 | Green並列: UI側＝5画面のComposable＋画面ViewModel（画面遷移はラムダ引数として受け取り、NavHostに触れない。§10.6・A5）／Domain側＝Mockファクトリ＋不変条件。**ui-implementerとdomain-implementerを同一メッセージで並列起動する** | ui-implementer ∥ domain-implementer | G3 |
| C5 | 統合（直列）: NavHost配線（C4で用意したラムダ引数へ実際のnavigate呼び出しを結線。§10.6）・AppContainer結線・AndroidManifest・Applicationクラスの統合 | domain-implementer（integration owner） | G3 |
| C6 | Refactor: 両agentによるリファクタ後、Green再実測 | ui-implementer／domain-implementer → quality-runner | **G4-JVM** |
| C7 | 保留（KVM解決後）: `connectedDebugAndroidTest`実行＋ja/en全画面スクリーンショット取得 | quality-runner | **G4-E** |

### C4並列時の所有権規則（必須遵守）

C4でui-implementerとdomain-implementerを並列起動する際、以下の共有ファイルの既定所有者は**domain-implementerのみ**とする（`docs/TEAMS.md`§5「共有ファイル所有権と統合オーナー」に準拠）。

- `build.gradle(.kts)` ／ `settings.gradle(.kts)`
- `AndroidManifest.xml`
- DIモジュール（`AppContainer`等）
- `Application`クラス
- Navigation配線（`NavHost`本体）

**ui-implementerはC4の間、上記ファイルに一切触れない。** 画面Composable・画面ViewModelの実装に必要な範囲を超えて上記への変更が必要になった場合は、自己判断で編集せず作業を中断し、Fable 5へ報告する。統合作業はC5でdomain-implementerがintegration ownerとして直列に行う。

## 16. リスク

| ID | リスク | 対応 |
|---|---|---|
| R1 | 初回ライブラリダウンロードに時間を要する | C1を独立サイクルとして先行させ、長めのタイムアウトを設定する。C1がGreenになるまで他サイクルを起動しない |
| R2 | AGP/Kotlin/Compose等の相性が未検証（§5の7項目） | Version Catalogへ集約し、C1 probeで実測する。降格経路を明記し、結果を`DECISIONS.md`へ記録する |
| R3 | KVM未解決（ユーザーが`kvm`グループ未所属） | G4を2段化（G4-JVM／G4-E、ADR-0006）。Phase 2着手はG4-JVMで許可し、G4-E未達のままPhase 3以降へ進むことを禁止する。ユーザーへ`sudo usermod -aG kvm noritakasawada`実行と再ログインを依頼する。**【A3・G1クロスレビュー〔Gemini〕を受けたFable 5裁定】** KVMがユーザー環境要因で解消不能と判明した場合は、(a)実機のadb接続 (b)Windows側エミュレータへの`adb connect`等の代替をユーザーと協議する。その間、Robolectric+Compose検証が全通過している場合に限り、Fable 5承認のもとキャリー上限をPhase 4完了まで時限延長できる（無期限キャリーは不可） |
| R4 | WSL2のメモリ制約 | `gradle.properties`のjvmargsを抑制する（§14）。`.wslconfig`の調整はユーザー事項とする |
| R5 | Domain層の可変性とComposeのimmutable原則の衝突（**ADR-0010によりDomain modelを`val`化し解消**） | `val`化により再コンポジション問題は解消。ComposeへはUiState写像を維持する（§9.1） |
| R6 | `java.time.Duration`と`kotlin.time.Duration`の混在 | `java.time.Duration`に統一する（ADR-0008） |

## 17. 変更対象ファイル構成・依存関係の要約

- **変更対象ファイル構成**: §13（ファイル構成）に一覧化。新規作成が中心（Gradleプロジェクト一式・6画面のComposable一式・Domain／Mock一式）であり、既存ファイルの変更は仕様書§9のみ（本書§9.3・小修正Aで対応済み）。
- **依存関係・技術選定の根拠**: §5（バージョンピン）・§7（基盤判断）に集約。Version Catalog採用理由は§7.2、手動DI採用理由は§7.3、SDKバージョン根拠は§7.4を参照。ライブラリ本体のバージョン確定はC1 probe（§15）の実測結果をもって行い、本書時点では推奨ピンとして扱う（§5の要検証7項目は確定値ではない）。

## 18. 未解決事項・申し送り

- Gemini 3.5 Flashクロスレビューは実施済み。指摘事項はFable 5裁定A1〜A7として本書へ反映済み（§4参照）。
- §5の要検証7項目はC1 probeで確定するまで確定値として扱わない（本書内でも「要検証（C1 probeで確定）」表記を維持している）。
- R3（KVM未解決）はユーザー操作（`sudo usermod -aG kvm noritakasawada`＋再ログイン）に依存する。解決時期未定のため、G4-Eの着手時期も未定。
- Phase 1完了後もバックスタック起因のPlan破棄防止は未対応（既知の制限。§10.5、Phase 5で再検討）。
- ADR-0002／0003／0007／0008／0009は本計画メモ内でandroid-plannerが提示した技術判断であり、Fable 5裁定U1〜U3（ADR-0004／0005／0006）とは異なり、本計画書自体のG1レビュー（Pass1/Pass2＋Geminiクロスレビュー）を経て確定する（`docs/plans/phase0-repo-docs.md`§8にも同旨を記載）。
- 計画メモに記載のなかった内容の追加、および転記漏れは確認していない（本書は計画メモ§4〜§14の全項目を転記済み）。
- T-DM-9はファクトリ層契約（MockRecoveryFactoryTest）として再定義済み（Fable 5裁定 2026-08-08）
- T-REC-4は画面レベル検証へ再定義済み（同上）
- T-MOCK-11（upcomingEvents昇順の回帰テスト）を追加（Fable 5裁定 2026-08-09）。テストケース総数は68件→69件
