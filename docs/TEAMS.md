# Action Starter Android — 開発ハーネス設計書（Teams構成）

最終更新: 2026-08-08
適用範囲: `/home/noritakasawada/project/app_project-0808/`（本プロジェクト専用）

---

## 正仕様書

本プロジェクトの正仕様書は **`Action_Starter_Master_Specification_v2.0_Android.md`**（Android版・Kotlin / Jetpack Compose / CalendarProvider / FusedLocation / Routes API / Room。2573行、§0〜§95、Changelog付き。レビュー反映済み）である。実装・計画立案・レビューはすべて本ファイルを根拠とする。

`Action_Starter_Master_Specification_v1.0.md`（iOS Native / Swift / SwiftUI / EventKit / CoreLocation / MapKit / SwiftData 版）は**iOS原本のアーカイブ**であり、実装の根拠にしない。参照する場合も「v1.0はアーカイブ」であることを明記する。

Phase構成（§64〜§77 ＝ Phase 0〜13）・セクション番号はv1.0と同一構成をv2.0でも維持している。本書§5のPhaseマッピングは、この共通のセクション番号体系に基づく。

### iOS→Android 読み替え対応表（参考: v2.0策定時に適用済みの対応関係）

下表はv1.0（iOS）からv2.0（Android）を策定する際に適用された対応関係であり、現在は仕様書v2.0本文に反映済みである。新規実装の判断はv2.0本文を直接参照して行い、本表はv1.0記述との照合が必要な場合の参考情報として維持する。

| iOS（v1.0仕様書記載） | Android（v2.0仕様書での対応） |
|---|---|
| Swift / SwiftUI | Kotlin / Jetpack Compose |
| EventKit | CalendarProvider |
| CoreLocation | FusedLocationProviderClient（Google Play services Location） |
| MapKit（Routing） | Routes API（Google） |
| SwiftData | Room |
| XCTest / Swift Testing | JUnit / Robolectric / Compose Test / Instrumented Test |
| TestFlight | Google Play Internal Testing（Closed Testing） |

---

## 1. 目的と適用範囲

本書は「Action Starter Android」プロジェクト専用の**マルチモデル開発ハーネス**を定義する。汎用的なプロジェクト管理手法ではなく、このプロジェクトの実装フェーズ（Phase 0〜13）を安全かつ並列に進めるための役割分担・呼び出し方法・品質ゲートを固定する文書である。

- 本書の§2チーム表がこのプロジェクトにおける役割分担の正である。
- 親ディレクトリ `/home/noritakasawada/project/CLAUDE.md` が定義する一般的な「Frontend Team / Backend Team」モデルとは呼称が異なるが、責務は対応している：`ui-implementer` が Frontend Team相当（Features層/Compose）、`domain-implementer` が Backend Team相当（Domain/Services/Engines層）を担う。本プロジェクトでは役割ごとに専用agentを1体ずつ定義する、より細粒度な構成を採用する。
- オーケストレーター（Fable 5）は本書のPDCA（§3）・dev-workflow対応（§4）・品質ゲート（§6）に従って各agentへ委託する。オーケストレーター自身は実装しない。

---

## 2. チーム編成（役割・モデル・呼び出し方法・責務・禁止事項）

| # | 役割 | モデル | 呼び出し方法 | 責務 | 禁止事項 |
|---|------|--------|-------------|------|---------|
| 1 | オーケストレーター 兼 アーキテクトレビュー・品質ゲート | **Fable 5**（メインセッション本人） | なし（agentファイル不要） | 全体オーケストレーション、Step2アーキテクトレビュー（Pass1/Pass2）、G1〜G4品質ゲートの判定、ユーザーとの折衝、エスカレーションの一次受け | 自身で実装（コード記述・ファイル作成編集・テスト実装）をしないこと（プロジェクトCLAUDE.md絶対ルール） |
| 2 | 計画立案・タスク分解・技術判断案・難解バグ根本原因調査 | **Opus 4.8** | `Agent`（`subagent_type: android-planner`, `model: opus`） | Phase／機能単位の計画メモ作成、interface契約設計案、依存関係・技術選定根拠の提示、リスク分析、根本原因調査 | コード実装をしない（Edit/Write権限なし）。独断で機能追加・仕様逸脱をしない。断定できない技術主張を事実として提示しない |
| 3 | 実装計画書作成 | **Sonnet 5** | `Agent`（`subagent_type: plan-doc-writer`） | android-plannerの計画メモを正式な計画書ファイルへ文書化。テストケース表（正常系・異常系・エッジケース）とエラー＆レスキューマップの作成 | 計画内容を自己判断で変更・追加しない。コード実装をしない |
| 4 | UI実装（Features層/Jetpack Compose） | **Sonnet 5** | `Agent`（`subagent_type: ui-implementer`） | Compose画面・ViewModel・Navigationの実装とGreen化 | Domain/Servicesロジックの重複実装。UI文字列のハードコード（strings.xml外出しを必須とする）。テストを通すためだけの実装 |
| 5 | Domain/Services/Engines実装 | **Sonnet 5** | `Agent`（`subagent_type: domain-implementer`） | PlanningEngine / RecoveryEngine / RoutingService / CalendarService / LocationService / LocalLanguageModel Adapter / Room永続化の実装 | 決定的計算（時刻・GPS・到着時刻演算）をLLMに委譲すること。Schema Validationの省略。プライベート情報（カレンダー本文・位置情報・行動履歴）の外部送信 |
| 6 | 失敗テスト記述（Red） | **Sonnet 5** | `Agent`（`subagent_type: test-writer`） | 計画書のテストケース表に基づくfailingテストの作成 | 本番コードの実装。承認なしの既存テスト削除・assertion弱体化（承認済み仕様変更に伴う既存テスト更新は計画書のケースID・変更理由・レビュー記録を伴う場合に許可）。テストを通すための甘いassertion |
| 7 | ビルド・テスト実行・ログ収集（判断なし） | **Haiku 4.5** | `Agent`（`subagent_type: quality-runner`, `model: haiku`） | `./gradlew build` / `./gradlew test` 等の実行と生ログの収集・報告。エミュレータの起動・AVD管理・adb操作・instrumentedテスト/E2E実行・スクリーンショット取得（`adb exec-out screencap`等） | コード修正（Edit/Write権限なし）。失敗原因の分析・解決策の提案。ログの省略・要約による改変 |
| 8 | 大規模リファクタ・新規モジュール骨格生成・レスキュー | **Codex（gpt-5.1-codex-max）** | `Agent`（`subagent_type: codex:codex-rescue`）、または `codex` CLI | 同一エラー2回連続失敗時のレスキュー、大規模横断リファクタ、新規モジュール骨格生成 | 独断でのマージ・不可逆操作（Fable 5承認が必須） |
| 9 | 第三者クロスレビュー（計画書・仕様書・コード差分） | **Gemini 3.5 Flash** | `mcp__gemini__ask-gemini` に **必ず `model: "gemini-3.5-flash"` を明示** | 計画書・コード差分への別視点レビュー | `model` パラメータの省略。`gemini-2.5-pro` / `gemini-2.5-flash` の使用。`gemini-3-pro-preview` の使用（API廃止=404） |

---

## TDD原則（絶対規則）

本プロジェクトはTDD（Red→Green→Refactor）を**絶対規則**とする（ユーザー指示・2026-08-08）。

- **対応する失敗テストが存在しない本番コードを書かない。** 唯一の例外はPhase 1の契約scaffold（型・interface宣言・空の画面Composable/ViewModelスタブを含む。実装は`TODO()`または空実装）**およびC1 Gradleブートストラップ（テストランナー不在のためRed不能。ただしC1完了時にSmokeComposeTest（Robolectric+Compose）のGreen実測を必須とする）**である。
- テスト後付け（実装後にテストを書く逆順）は**G2違反として差し戻し対象**とする。
- Red→Green→Refactorの各段階でquality-runnerによる実測を行い、**Refactor後のGreen再実測**を下記§6 G3の証拠に含める。
- 実装agent（ui-implementer／domain-implementer）は、failingテストのパスが入力に含まれない場合、**着手せず差し戻す**（Phase 1契約scaffoldタスクを除く）。

---

## 3. PDCAサイクル定義

```
Plan  → android-planner (Opus)         … Phase/機能単位の計画メモ
  ↓
Do    → plan-doc-writer (Sonnet)       … 計画書ファイル化
        test-writer (Sonnet)           … Red
        ui-implementer / domain-implementer (Sonnet) … Green→Refactor
  ↓
Check → Fable 5 本人によるアーキテクトレビュー（Pass1 CRITICAL / Pass2 INFORMATIONAL）
        + Gemini 3.5 Flash 第三者クロスレビュー（mcp__gemini__ask-gemini, model固定）
        + 必要時 Codex（codex:codex-rescue）による技術検証
  ↓
Act   → Sonnet（指摘に応じて plan-doc-writer / test-writer / ui-implementer / domain-implementer が修正）
  ↓（次サイクルへ）
```

**粒度規則**: 1サイクル＝1つの明確な成果物とする。Phase全体を1サイクルにしない。目安は計画書内の「1機能」または「1interface実装（例: BasicPlanningEngineのみ）」単位。§5のPhase 0〜13はそれぞれ複数のPDCAサイクルで構成される。

---

## 4. dev-workflow 4ステップとの対応表

| Step | 内容 | 実行agent／担当 | Fable 5の品質ゲート |
|------|------|-----------------|---------------------|
| Step1 計画書 | 機能一覧・仕様、変更対象ファイル構成、依存関係・技術選定根拠、テストケース表（正常系・異常系・エッジケース）、エラー＆レスキューマップを含む実装計画書を作成 | android-planner（Opus）が計画メモ作成 → plan-doc-writer（Sonnet）が計画書ファイル化 | **G1**に統合（下記§6） |
| Step2 アーキテクトレビュー | Pass1 CRITICAL（データ安全性／信頼境界／サイレント障害／論理整合）→ Pass2 INFORMATIONAL | **Fable 5本人が実施**（専用agentなし）。加えてGemini 3.5 Flashへ第三者クロスレビューを委託。技術的疑義が残る場合はandroid-plannerへ再質問 | **G1**に統合 |
| Step3 Red | 計画のテストケースに基づき失敗するテストを先に書き、Redを実行確認 | test-writer（Sonnet）がテスト作成 → quality-runner（Haiku）が実行し失敗を実測 | **G2** |
| Step4 Green→Refactor | 段階的に実装し各段階でテスト実行、Green維持。全通過後リファクタし再度テスト通過確認 | ui-implementer／domain-implementer（Sonnet）が実装 → 各段階でquality-runner（Haiku）がテスト実行 | **G3**（各段階のGreen）／**G4**（Phase完了時の総合確認） |

計画書のフォーマット・テンプレートの詳細手順は `dev-workflow` skillを参照する（Fable 5がandroid-planner／plan-doc-writerへプロンプトを渡す際に、必要なテンプレート断片を本文へ明示的に含めること。subagentは新規コンテキストで起動するため、skill内容を暗黙に参照させない）。

---

## 5. 仕様書 Phase 0〜13 へのマッピング

前提: 下記は正仕様書 `Action_Starter_Master_Specification_v2.0_Android.md` のPhase定義（§64〜§77。v1.0と同一のセクション番号体系）に基づくマッピングである。

| Phase | 仕様書§ | 内容 | Android読み替え | 主担当agent | 並列化 | 完了条件（仕様書ベース） |
|-------|--------|------|-----------------|-------------|--------|--------------------------|
| 0 | §64 | Repo bootstrap・ドキュメント雛形 | README / ARCHITECTURE.md / PRODUCT.md / AI.md / PRIVACY.md / DECISIONS.md | android-planner → plan-doc-writer | 直列 | 雛形ドキュメント一式が揃っている |
| 1 | §65 | UI Skeleton + Domain（Mock Data） | Jetpack Compose、Domain Model、Event Selection／Plan Review／Execution／Departure／Recovery各画面のMock実装。**interface契約（PlanningEngine／RecoveryEngine／RoutingService／LocalLanguageModel）をここで確定** | ①domain-implementer（契約scaffold：型・interfaceのみのコンパイル可能な骨格、実装は`TODO()`可）→ ②test-writer（scaffold参照でRedテスト作成）→ ③domain-implementer／ui-implementer（Green化） | 契約scaffold確定後にui-implementer着手可（詳細は下記「Phase 1 契約scaffold工程」） | Emulator上で一連のUXが動く |
| 2 | §66 | Calendar統合 | CalendarProvider。Permission／Calendar List／Upcoming Events／Location付きイベント抽出／Event Selection | domain-implementer + ui-implementer | **契約確定済のため並列可** | 実カレンダーから予定を選択可能 |
| 3 | §67 | Routing／Location | FusedLocationProviderClient + Routes API。Permission／current location／destination geocoding／route estimation／transport mode／ETA | domain-implementer | UI側（Phase2 UI磨き込み）と並列可 | 現在地→予定先の所要時間が取れる |
| 4 | §68 | Basic Engine | BasicPlanningEngine：Transition／Preparation／Travel／Buffer／deterministic planning／departure calculation | domain-implementer | ui-implementer（Plan Review／Execution UI磨き込み）と並列可 | LLMゼロでExecution Planが成立 |
| 5 | §69 | Notification + Execution | NotificationService（WorkManager／AlarmManagerの使い分け）。Step start／Done／Snooze／next action／departure | domain-implementer（通知基盤）＋ ui-implementer（Execution One Action UI） | 並列可 | 端末上で時間経過型Executionができる |
| 6 | §70 | Recovery Basic | BasicRecoveryEngine：lateness detection／remaining preparation／recalculation／deterministic alternatives | domain-implementer ＋ ui-implementer（Recovery画面） | 並列可 | 遅れをシミュレートするとRecovery画面へ遷移 |
| 7 | §71 | Local LLM Runtime | Model Manager／Download／Load／Inference／Memory Handling／Structured Output／Schema validation | domain-implementer（骨格が大規模な場合はandroid-planner判断でCodexへ委譲検討） | 直列（基盤のため） | オフライン状態でテストPrompt→JSON取得 |
| 8 | §72 | Local AI Planning | LocalAIPlanningEngine：Calendar Event→Semantic Event→Execution Steps | domain-implementer | ui-implementer（Basic／AI切替UI）と並列可 | BasicPlanningEngineと比較可能な状態 |
| 9 | §73 | Local AI Recovery | LocalAIRecoveryEngine：remaining steps／travel／deadline／priorityをKotlin側で計算しLLMへ渡す（数値計算はLLMへ委譲しない） | domain-implementer | 並列可 | Recovery候補がLocal AI経由で生成される |
| 10 | §74 | Personal Profile | PersonalExecutionProfileのRoom永続化。preparation actual／transition actual／departure lag／arrival buffer | domain-implementer | 並列可 | 履歴が次回Planへ反映される |
| 11 | §75 | Localization | strings.xml ja/en。英語環境で一通り動作確認 | ui-implementer（文字列外出し）＋ domain-implementer（Locale／TimeZone対応） | 並列可 | 英語環境で主要UXが成立 |
| 12 | §76 | Basic/AI Experiment | Developer Settingsでの内部切替 + 行動ログ（§53-54準拠のイベントログ） | domain-implementer | 直列 | 同一ユーザーでBasic/AI比較ログが取得できる |
| 13 | §77 | 配布・実予定検証 | Google Play Internal Testing（仕様書v2.0 §77で確定）。15人程度、実予定で使用 | quality-runner（ビルド確認）→ Fable 5（配布判断） | 直列 | 実予定での検証が開始されている |

### Phase 1 契約scaffold工程（循環依存の解消）

interface契約の確定とui-implementer／domain-implementerの並列着手は、以下の3工程を順序どおりに経ることで循環依存を避ける。

1. **契約scaffold作成（domain-implementer）**: 承認済み計画書に基づき、PlanningEngine／RecoveryEngine／RoutingService／LocalLanguageModel等の**型・interface宣言・空の画面Composable/ViewModelスタブを含むコンパイル可能なscaffold（実装は`TODO()`または空実装）**を先に作成する。実装本体は`TODO()`で仮置きしてよい（この段階でのGreen化は不要）。
2. **Redテスト作成（test-writer）**: 上記scaffoldのinterfaceを参照し、Domain側・UI側それぞれのfailingテストを作成する。
3. **Green化（ui-implementer／domain-implementer）**: scaffoldとRedテストが揃った時点で、ui-implementerとdomain-implementerを並列起動しGreen化する。

この順序を経ずにui-implementerを先行着手させない（未確定のinterfaceに依存する実装が発生し、後工程で手戻りになるため）。

### 並列化ポイント（明記）

**Phase 1の契約scaffold工程完了時点（interface契約 PlanningEngine／RecoveryEngine／RoutingService／LocalLanguageModel のscaffoldが確定した時点）以降、`ui-implementer` と `domain-implementer` を並列起動できる。** これがFE/BE分岐に相当する本プロジェクトの同期ゲートである（§2表のBackend Team相当＝domain-implementer、Frontend Team相当＝ui-implementerが、確定したinterfaceをContractとして各自進める）。

並列起動の正しい方法は§8チートシートを参照。同期ポイント（設計レビュー後＝G1通過後、実装完了後＝G3/G4通過時）でFable 5が品質ゲートを実施する。

### 共有ファイル所有権と統合オーナー

- 共有ファイル（`build.gradle(.kts)` / `settings.gradle(.kts)` / `AndroidManifest.xml` / DIモジュール / Applicationクラス / Navigation配線）の既定所有者は **domain-implementer** とする。ui-implementerがこれらの変更を要する場合は自己判断で編集せず、作業を中断してFable 5へ報告する。
- 並列実装後の統合・配線は、domain-implementerを **integration owner** として直列の統合サイクルで実施し、G3で締める。

### interface契約のバージョン付き変更経路

interface契約はPhase 1の契約scaffoldで確定した後も凍結ではなく、**version付きの承認済み契約**として扱う。変更が必要になった場合の手順は以下に限定する。

1. 変更提案（起案agentが影響範囲メモを添付）
2. android-plannerが影響分析
3. Fable 5が承認
4. `DECISIONS.md` へ変更内容を記録
5. 両側（UI/Domain）のテストを更新

この経路を通らない契約変更は禁止する。ui-implementer／domain-implementer／test-writerがinterface契約の変更が必要と判断した場合は、即座に作業を中断し、上記フローへ提起する（§7エスカレーション規則も参照）。

---

## 6. 品質ゲート定義

G1〜G3はPDCA 1サイクル（§3）ごとに、G4はPhase（§5）完了ごとに通過判定する。

### ゲート適用表（成果物種別ごと）

| 成果物種別 | 適用ゲート |
|---|---|
| 文書のみ（Phase 0の雛形ドキュメント等） | G1のみ（G2〜G4は適用しない） |
| コード（Gradleプロジェクト成立後） | G1 + G2 + G3 |
| Phase完了（実行可能なGradleプロジェクトが存在する場合） | 上記 + G4 |

**注記**: `./gradlew build` 等のG4証拠はGradleプロジェクトが成立するPhase 1以降にのみ要求する。Phase 0のG4は「ドキュメント一式が揃いFable 5が承認」で代替する。

### G1: 計画承認

**通過に必要な証拠**:
- 計画書ファイル（機能一覧と仕様／変更対象ファイル構成／依存関係・技術選定根拠／テストケース表〔正常系・異常系・エッジケース〕）
- エラー＆レスキューマップ（`処理｜想定される異常｜ハンドリング方法｜ユーザーへの影響` の4列。空欄セルはCRITICAL扱いのため通過不可）
- Fable 5によるPass1（CRITICAL: データ安全性／信頼境界違反／サイレント障害／論理的整合性）とPass2（INFORMATIONAL）のレビュー記録
- Geminiクロスレビュー結果（`model: "gemini-3.5-flash"` 指定での実行ログ。利用不可時は代替レビュー記録と不可理由）
- Fable 5のPass1/Pass2レビューとクロスレビュー（Gemini、利用不可時は代替）で問題なしと判定された場合、**ユーザー承認を待たず実装へ自動進行する**（ユーザー指示 2026-08-08）。ただし次の事項は引き続きユーザー確認必須: 仕様の矛盾・スコープ変更、不可逆・対外操作（ストア配布実行・課金・外部への送信）、/goal未達状態でのリリース判断

### G2: Red確認

**通過に必要な証拠**:
- テストスイートがコンパイル・実行できること。単なるコンパイルエラーによるRedは「意図した失敗」と認めない
- **新規要求（計画書のテストケース表の新規項目）ごとに、少なくとも1つのテストが意図した理由（未実装による期待値差分）で失敗**していること。失敗テスト名・期待値・実測値を証拠として記録する
- 既存の回帰テストはpassを維持していること
- quality-runnerが実行したテストコマンドの生ログ（ログファイル絶対パス＋抜粋）
- 「失敗するはず」という推測ではなく、実行結果に基づく報告であること

### G3: Green確認

**通過に必要な証拠**:
- quality-runnerが実行したテストコマンドの生ログ（ログファイル絶対パス＋抜粋。省略・要約による改変なし）
- 対象範囲の全テストがpassしていることの実測
- 各実装段階（Step4の逐次実装）ごとに再実行された記録
- Refactor後のGreen再実測ログ（リファクタリング後に全対象テストを再実行し、pass維持を実測した記録。TDD原則参照）

### G4: Phase完了

**通過に必要な証拠**:
- `./gradlew build` 成功ログ
- 対象Phaseに関わる全テストのpassログ
- Emulatorまたは実機での動作確認結果（quality-runnerによる `connectedAndroidTest` 実行ログ、またはFable 5／ユーザーによる目視確認記録のいずれか。**目視確認の場合も端末/API level・build SHA・操作手順・期待結果・実測結果を記録する**）
- エミュレータ実測（スクリーンショットまたはE2E実行ログ）をFable 5が確認した記録
- Fable 5によるコード差分レビュー記録（計画との対応・interface逸脱・レイヤー越境・Manifest/権限変更・個人情報フロー・エラー処理・依存追加の確認）。**権限・プライバシー・外部API・DB migrationに関わる高リスク変更はGeminiまたはCodexのクロスレビューも必須**
- 未実装事項の明示（「なし」であればその旨も明記）

### G4補遺: Phase 13（配布）の追加証拠

`./gradlew build` の成功だけでは配布可否を判定できないため、Phase 13完了のG4証拠には上記に加え以下を必須とする。

- 署名済みrelease AAB（`:app:bundleRelease`）のビルド成功ログ
- versionCode・versionNameの確認記録
- release variantでのsmoke test記録
- AABファイルの絶対パスとSHA-256
- upload keyや署名情報（keystore・パスワード等）をリポジトリに置いていないことの確認
- Google Play Consoleクローズドテストトラックへのアップロード結果
- ロールバック手順

### （提案・未承認）軽量G1オプション

低リスク変更（UI文言・レイアウト微調整等）向けに、G1の簡略版（テンプレート簡略計画＋Phase単位承認）を導入する案がある。ただしCLAUDE.mdの4ステップ厳守との兼ね合いがあるため、**ユーザー承認があるまでは既定を常にフルG1とする**。この軽量オプションを実装agent・Fable 5が自己判断で適用することを禁止する。

いずれのゲートも証拠が揃わない場合は不合格とし、次工程（次サイクル／次Phase）へ進めない。

---

## /goalと改善ループ

リリース判定基準は `docs/GOAL.md` を正本とする（目標: 90/100点）。

- **ループ**: 実装完了 → quality-runner実測＋エミュレータE2E → Fable 5採点 → 90点未満なら改善点洗い出し（android-planner）→ 修正（実装agent）→ 再テスト → 再採点、を90点到達まで反復する。
- 採点者はFable 5。採点は必ず実測証拠（テストログ・エミュレータ実行結果・スクリーンショット）に接地させる。
- 2周連続で+5点未満の改善に留まった場合は**停滞**と判定し、原因分析と方針をユーザーへ報告する。

---

## 7. エスカレーション規則

| トリガー | 対応 | 判断者 |
|---------|------|--------|
| 同一エラーで2回連続失敗 | incident packet（error fingerprint・再現コマンド・環境・関連diff・2回の試行内容）を作成してFable 5へ提出する。Fable 5が振り分ける: 原因不明→android-planner（根本原因調査）／原因が明確で修正規模が大きい→Codexレスキュー（`Agent` `subagent_type: codex:codex-rescue`、または `codex` CLI） | 実装agentがincident packetを提起 → Fable 5が振り分け判断 |
| interface契約の変更が必要になった（設計逸脱・レイヤー越境・仕様書§15禁止事項への抵触を含む） | 実装agentは**即座に作業を中断**しFable 5へ報告。作業を継続せず、§5「interface契約のバージョン付き変更経路」の手順（変更提案→android-planner影響分析→Fable 5承認→DECISIONS.md記録→両側テスト更新）へ提起する | Fable 5 |
| 仕様の矛盾・未定義箇所を発見 | 自己判断で進めずユーザーに確認 | ユーザー |
| Gemini呼び出し時に `model` パラメータを省略しそうになった／`gemini-2.5-pro`系を使おうとした | 呼び出し前に停止し `gemini-3.5-flash` へ訂正 | 呼び出し元agent／Fable 5 |
| Gemini呼び出しが失敗・利用不可（レート制限・障害等） | 1回リトライ→なお不可なら「Gemini利用不可」を記録し、Codexクロスレビューまたは Fable 5単独レビューで代替する。代替した事実を成果物に記録しユーザーへ報告する | Fable 5 |
| quality-runnerが環境要因（SDK未インストール等）でビルド・テストを実行できない | 推測で「おそらくGreen」と報告せず、実行不能である旨をそのまま報告 | quality-runner → Fable 5 |

---

## 8. 呼び出しチートシート

> **重要（並列起動の正しい方法）**: `Agent` ツールに `run_in_background` パラメータは存在しない（これは `Bash` ツール専用のパラメータであり、`codex` CLIをBash経由で長時間実行する場合などに使うものである）。複数のAgentを**並列**で動かしたい場合は、**同一メッセージ内に複数の `Agent` 呼び出しを同時に発行する**（Agentツール自身の仕様: "send them in a single message with multiple tool uses so they run concurrently"）。1件ずつ送って完了を待ってから次を送ると並列にならない。

### handoffマニフェスト（全agent起動プロンプト共通の記載項目）

サブエージェントは新規コンテキストで起動するため、呼び出し元（Fable 5）は起動プロンプトに以下の項目を可能な限り含める。欠落があると誤った前提での作業・手戻りに繋がるため、該当なしの項目も「なし」と明記する。

| # | 項目 | 備考 |
|---|------|------|
| 1 | repo root絶対パス | `/home/noritakasawada/project/app_project-0808/` |
| 2 | 仕様書v2.0絶対パス | `Action_Starter_Master_Specification_v2.0_Android.md` のフルパス |
| 3 | 承認済み計画書絶対パス | `docs/plans/xxxx.md` のフルパス |
| 4 | interface契約ファイル絶対パス | 対象interfaceの定義ファイルのフルパス |
| 5 | 対象テストファイル絶対パス | test-writerが作成／参照するテストファイルのフルパス |
| 6 | 対象モジュール・variant | 例: `:app`、`debug`/`release` |
| 7 | 実行コマンド | quality-runner等に渡す正確なGradleコマンド（module修飾込み） |
| 8 | baseline commit SHA | 作業開始時点のコミットSHA |
| 9 | 編集許可ファイル・編集禁止ファイル | 担当領域外への越境を防止 |
| 10 | 未解決事項 | 既知の懸念・保留中の判断があれば明記。なければ「なし」 |

以下のチートシート例は上記マニフェストの主要項目を反映し、パスをすべて絶対パスへ統一した形へ更新済み。

### 計画立案（Opus）
```
Agent({
  subagent_type: "android-planner",
  model: "opus",
  description: "Phase4 Basic Engine 計画立案",
  prompt: "repo root: /home/noritakasawada/project/app_project-0808/
           仕様書: /home/noritakasawada/project/app_project-0808/Action_Starter_Master_Specification_v2.0_Android.md
           Action Starter AndroidのPhase4（仕様書§68 Basic Engine）を計画する。
           対象: BasicPlanningEngine。前提: interface契約(PlanningEngine, §44)は
           Phase1で確定済み(パスは /home/noritakasawada/project/app_project-0808/app/src/main/.../PlanningEngine.kt)。
           決定的計算はLLMに委譲禁止(仕様§15)。baseline commit: <SHA>。
           変更対象ファイル案・テスト観点・エラー&レスキューマップ下書きを返答すること。未解決事項があれば明記。"
})
```

### 実装計画書作成（Sonnet）
```
Agent({
  subagent_type: "plan-doc-writer",
  description: "Phase4 実装計画書作成",
  prompt: "repo root: /home/noritakasawada/project/app_project-0808/
           <android-plannerの計画メモ全文を貼り付け> を正式な実装計画書ファイルとして
           /home/noritakasawada/project/app_project-0808/docs/plans/ 配下へ作成する。
           テストケース表(正常系/異常系/エッジケース。対象/source set/runner/Gradleタスク/必要端末の列を含む)と
           エラー&レスキューマップを含めること。"
})
```

### UI実装とDomain実装の並列起動（Phase1契約scaffold確定後、Sonnet×2）
同一メッセージ内で以下2件を同時発行する。
```
Agent({
  subagent_type: "ui-implementer",
  description: "Execution One Action画面 Compose実装",
  prompt: "repo root: /home/noritakasawada/project/app_project-0808/
           承認済み計画書: /home/noritakasawada/project/app_project-0808/docs/plans/xxxx.md。
           test-writerが作成したfailingテスト:
           /home/noritakasawada/project/app_project-0808/app/src/test/.../ExecutionScreenTest.kt。
           PlanningEngine interfaceは
           /home/noritakasawada/project/app_project-0808/app/src/main/.../domain/planning/PlanningEngine.kt
           を参照しfakeで実装すること。対象module: :app（debug variant）。
           編集許可: app/src/main/.../features/execution/ 配下のみ。編集禁止: domain/ 配下。"
})
Agent({
  subagent_type: "domain-implementer",
  description: "BasicPlanningEngine実装",
  prompt: "repo root: /home/noritakasawada/project/app_project-0808/
           承認済み計画書: /home/noritakasawada/project/app_project-0808/docs/plans/xxxx.md。
           test-writerが作成したfailingテスト:
           /home/noritakasawada/project/app_project-0808/app/src/test/.../BasicPlanningEngineTest.kt。
           PlanningEngine interfaceのシグネチャは変更しないこと。決定的計算のみで実装(仕様§13/§15準拠)。
           対象module: :app（debug variant）。編集許可: app/src/main/.../domain/planning/ 配下のみ。"
})
```

### テスト作成（Red、Sonnet）
```
Agent({
  subagent_type: "test-writer",
  description: "BasicPlanningEngine Redテスト作成",
  prompt: "repo root: /home/noritakasawada/project/app_project-0808/
           計画書: /home/noritakasawada/project/app_project-0808/docs/plans/xxxx.md のテストケース表に基づき、
           BasicPlanningEngineのfailingテストをJUnitで作成する。
           interface契約: /home/noritakasawada/project/app_project-0808/app/src/main/.../domain/planning/PlanningEngine.kt。
           本番コードは書かない。"
})
```

### ビルド・テスト実行（Haiku、判断なし）
```
Agent({
  subagent_type: "quality-runner",
  model: "haiku",
  description: "BasicPlanningEngineテスト実行",
  prompt: "repo root: /home/noritakasawada/project/app_project-0808/
           `./gradlew :app:testDebugUnitTest --tests '*BasicPlanningEngineTest'` を実行し、
           ログをbuild/agent-logs/へ保存した上で、終了コード・pass/fail件数・
           失敗テスト名一覧とスタックトレース抜粋・ログファイル絶対パス・ログ末尾50行を報告する。
           原因分析はしない。"
})
```

### Codexレスキュー（2回連続失敗時）
```
Agent({
  subagent_type: "codex:codex-rescue",
  description: "Room migration失敗の根本原因調査",
  prompt: "repo root: /home/noritakasawada/project/app_project-0808/
           PersonalExecutionProfileのRoom migrationが2回連続で失敗した。
           1回目/2回目のエラーログ: <貼付>。根本原因調査と修正案を求める。"
})
```
または `codex` CLIを直接使用する場合は、フラグ・オプションを事前に `codex --help` 等で確認してから実行する（未確認のオプションを断定的な手順として案内しない）。

### Gemini第三者クロスレビュー（modelパラメータ必須）
```
mcp__gemini__ask-gemini({
  model: "gemini-3.5-flash",
  prompt: "以下の実装計画書をアーキテクトの立場でレビューしてください。
           データ安全性・信頼境界・サイレント障害・論理的整合性の観点を優先し、
           次にテスト網羅性・設計妥当性を確認してください。<計画書全文>"
})
```
本ハーネス環境では `mcp__gemini__ask-gemini` はセッション開始時点で未ロードのdeferred toolになっている場合がある。その場合は先に `ToolSearch({query: "select:mcp__gemini__ask-gemini", max_results: 1})` でスキーマを解決してから呼び出す。

---

## 9. 既存の汎用agent（親ディレクトリ）について

`/home/noritakasawada/project/.claude/agents/` には、本プロジェクト専用ではない汎用agentが既に定義されている（`requirement-analyzer`, `work-planner`, `code-reviewer`, `document-reviewer`, `task-decomposer`, `technical-designer`, `technical-designer-frontend`, `task-executor`, `task-executor-frontend`, `quality-fixer`, `quality-fixer-frontend`, `prd-creator`, `design-sync`, `code-verifier`, `verifier`, `investigator`, `solver`, `scope-discoverer`, `rule-advisor`, `integration-test-reviewer`, `acceptance-test-generator` 等）。これらは本プロジェクトでも**補助的に利用可能**であり、特に以下は§2の専用agentと役割が重なるため状況に応じて使い分ける。

- `acceptance-test-generator`: Design DocからのE2E/統合テスト骨格生成が必要な場合、test-writerの前段として利用できる
- `document-reviewer` / `design-sync`: 複数ドキュメント間の整合性チェックが必要な場合、Fable 5のStep2レビューを補強する目的で利用できる

**検証結果**: 本セッションのagent一覧に親ディレクトリの汎用agent（`task-executor`, `work-planner` 等）が含まれることを確認済みである。ただし本プロジェクトで新規作成したagent（`android-planner` 等）は、作成後に開始したセッションから確実に解決される。現セッション内で解決しない場合は、定義ファイルをReadしてプロンプトへ内容を埋め込み、`general-purpose` + 該当modelで代替すること。

---

*本書はFable 5からの委託によりテクニカルライター役のsubagentが作成した。仕様書はv2.0_Android版（レビュー反映済み）が正として確定しており、旧オープン項目（仕様書バージョン不一致）は解消済みである。*
