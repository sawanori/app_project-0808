# Action Starter Android ― Phase 6 実装計画書：Recovery Basic（決定的リカバリー候補生成・lateness detection）

**対象Phase**: Phase 6（仕様書§70 Phase 6「Recovery Basic」、§13 Basic Engine「遅延検知」担当）
**正仕様書**: `Action_Starter_Master_Specification_v2.0_Android.md`
**前提**: P6-C1着手の前提条件はPhase 3クローズとする（下記「承認状態」・§3・§10参照）。Phase 5とはP6-C1〜C4を並列実行可、Phase 5のC6（統合）およびC7（Refactor）完了後に、Phase 6のC5（統合）を実行する（§10・§11.2）。
**起点計画メモ**: android-planner（Opus）作成、2026-08-09（§0〜§12。JSONL出力の最終行`message.content[0].text`より抽出）
**本書作成**: plan-doc-writer（Sonnet）、2026-08-09（初版）
**関連ハーネス文書**: `docs/TEAMS.md`（役割分担・PDCA・品質ゲートの正）、`docs/GOAL.md`（リリース判定基準）、`DECISIONS.md`（ADR記録先。本書作成時点の最新確定ADRはADR-0023であることを実測確認済み。§12 V-5参照）
**関連計画書**: `docs/plans/phase4-basic-engine.md`（本書の章立て・様式の直接の参照元）、`docs/plans/phase3-routing-location.md`（Phase 3・§50 `routingService`/`locationService`等の前提を供給）、Phase 5計画書（本書作成時点で`docs/plans/`に未作成。§12 V-6参照）

---

**承認状態（要約）: Fable 5＋Gemini G1完了・CRITICAL 6件反映済み（2026-08-09）→ G1通過。U-1〜U-9すべて裁定済み（2026-08-09・詳細は§4.2）。着手条件: Phase 3クローズ（Phase 5とC1〜C4並列可・C5統合はPhase 5のC6（統合）およびC7（Refactor）完了後）。**

> **上記ステータス行はメモ§12引き渡し指示からの変更点である。** android-planner作成の計画メモ§12は「§9.3のU-1〜U-9はユーザー承認待ちとして§4『承認状態』に『未裁定』で列挙し、Phase 4のG-1〜G-9のように『裁定済み』と書かないこと」と明示的に指示している。しかし本書作成の時点でFable 5がU-1〜U-9のすべてを裁定済み（2026-08-09・推奨案どおり承認）としたため、本書ではこの上書き指示に従い「裁定済み」として記載する。**この上書きはFable 5の指示によるものであり、plan-doc-writerの自己判断による追加ではない。** 個別の裁定内容は§4.2に記載する。なお、Geminiクロスレビュー（`model: "gemini-3.5-flash"`固定）はCRITICAL指摘6件（G1）を提示し、Fable 5裁定によりすべて推奨案どおり採用し本書へ反映済みである（2026-08-09）。これによりG1（計画承認）は通過した（§3参照）。

本計画書はandroid-planner（Opus）が2026-08-09に作成したPhase 6計画メモ（§0〜§12）を忠実に文書化したものであり、計画メモにない機能・仕様を自己判断で追加していない。メモは「ファイル作成・Gradle実行・エミュレータ操作は一切行っていません（読み取りのみ）」との前置きのもとで作成されている。

本書作成にあたり、plan-doc-writerは転記対象の計画メモに加え、メモが指摘する既存コード実欠陥6件（§0）に対応する実ソースファイル（`RecoveryEngine.kt`／`RecoveryContext.kt`／`RecoveryOption.kt`／`RecoveryPlan.kt`／`RecoveryViewModel.kt`／`MockRecoveryFactory.kt`／`AppContainerTest.kt`の`resolveMockPackageDir()`定義部／`RecoveryScreen.kt`／`strings.xml`・`values-ja/strings.xml`／`mock/`ディレクトリ内容）を直接確認し、メモの記載内容と一致することを検証した。**唯一の軽微な相違点**: 欠陥2（title/explanationの英語ハードコード）の引用行番号「53,64,76」は実測では`explanation`行の行番号であり、対応する`title`行は1行上の52/63/75である。欠陥の実質的内容（両フィールドとも英語文字列がハードコードされている事実）には影響しない。また`DECISIONS.md`の最新ADR番号（ADR-0023、メモV-5の主張と一致）、`docs/plans/`にPhase 5計画書が存在しないこと（メモV-6の主張と一致）もあわせて実測確認した。本書作成作業では**production codeを一切変更していない（読み取りのみ）**。

本書と正仕様書v2.0に差異が生じた場合は仕様書v2.0が正とする。

---

## §0. 結論ファースト

Phase 6は `recovery/BasicRecoveryEngine.kt` を新設して `mock/MockRecoveryFactory.kt` を削除し、**§31〜§33から導出した4規則・カスケード型の完全決定的な候補生成器**（A: そのまま出発／B: OPTIONAL省略／C: OPTIONAL+IMPORTANT省略／D: 移動手段変更）と、**Phase 5の通知・FGS・AlarmManagerに一切依存しないフォアグラウンド限定の lateness detection**（`recovery/LatenessDetector.kt`）を実装する。Phase 5とはフットプリントが**ほぼ素**であり **C1〜C4は並列推奨**だが、**C5統合ウィンドウはPhase 5のC6（統合）およびC7（Refactor）完了後に、Phase 6のC5（統合）を実行することが必須**（理由は§11.2）。

**実測で確認した既存コードの不備（Phase 6が修正すべき対象）**:
1. `features/recovery/RecoveryViewModel.kt:51` — `currentLocation = plan.event.coordinates` は**目的地座標を現在地として渡している**（§50違反・§30のReality Check計算が構造的に誤る）。plan-doc-writerによる実測（`RecoveryViewModel.kt`直接Read）でも、この行が`buildRecoveryContext`関数内の`RecoveryContext`構築式にそのまま存在することを確認済み。
2. `MockRecoveryFactory.kt:53,64,76` — `title`/`explanation` に**英語UI文字列をDomain層でハードコード**（§7 Global-first・ADR-0018の`title`空文字規約に反する）。title="Continue as planned"等（52/63/75行）、explanation="Keep the current plan..."等（53/64/76行）を実測確認。
3. `MockRecoveryFactory.kt:54,65,77` — 3案すべてが `estimatedArrivalWithoutSkip` で**同一ETA**（省略しても到着が早まらない＝§31/§32の価値が成立していない）。実測確認済み（3案すべてが`context.currentTime.plus(context.latestTravelEstimate)`という単一変数を参照）。
4. `RecoveryScreen.kt` — **ETAを表示していない**（§32が要求。`recovery_option_eta_label` は strings.xml:76 に定義済みだが grep実測で**参照0件**＝未配線）。plan-doc-writerによる実測（`app/src/`全体grep）でも、`recovery_option_eta_label`への参照はja/en両strings.xmlの定義行（各76行目）以外に存在しないことを確認済み。
5. `RecoveryViewModel.kt:37-43` — `viewModelScope.launch` に例外処理がなく、engine例外が**戻り値にもUIにも現れない**サイレント障害パス。実測確認済み（`init`ブロックにtry/catchなし）。
6. `di/AppContainerTest.kt` の `resolveMockPackageDir()` は **ディレクトリ非存在時に `error()` でhard fail** する。`MockRecoveryFactory.kt` は `mock/` 配下の**最後の1ファイル**（実測: `ls mock/` = MockRecoveryFactory.kt のみ）であり、削除すると `mock/` が消滅してT-P4DI-2が落ちる（Phase 4のR-6が現実化）。plan-doc-writerによる実測（`resolveMockPackageDir()`定義部Read、`ls mock/`実行）でも、いずれも一致することを確認済み。

---

## §1. 仕様原文の根拠（引用箇所）

| § | 引用（要点） | Phase 6での使い方 |
|---|---|---|
| §70 | Phase 6 = Recovery Basic。`lateness detection` / `remaining preparation` / `recalculation` / `deterministic alternatives`。完成条件「遅れをシミュレートするとRecovery画面へ遷移」 | 4項目をF番号へ1:1写像（§5） |
| §30 | `currentTime / currentLocation / completedSteps / unfinishedSteps / travelTime / eventStart` を比較し `planned state vs actual state` を計算 | `LatenessDetector` の入力集合（§7.2） |
| §31 | 「If you continue preparing, you'll arrive at 10:06 / Skip the detailed equipment check and leave now / Estimated arrival: 09:58」 | 診断行（A案ETA）と提案（B案ETA）の**両方をETA付きで出す**根拠 |
| §32 | 「最大3つまで」「1. Leave now ETA / 2. Change transport ETA / 3. Prepare a delay message」「選択肢過多は禁止」 | 上限3・**各案にETA必須**・D案（移動手段変更）の存在根拠 |
| §33 | 「**完璧な準備**ではなく**予定成立**を優先する。ただし安全・必須物は勝手に省略しない」「required / important / optional を必ず区別する」 | 優先順位規則の主軸（§7.3）。REQUIRED省略禁止 |
| §34 | AIは提案のみ。**ステップ省略・移動手段変更**はユーザー確認必須 | A案（変更なし）を常に残す／自動適用禁止 |
| §13 | 「遅延検知」はBasic Engine担当。「数値計算は必ず通常コード」 | lateness detectionはKotlin側・LLM不使用 |
| §15 | LLMに「正確な移動時間・時刻演算・到着時刻演算・安全上重要な最終判断」を決めさせない | `recovery/` が `ai/` を参照しない構造ガード（T-BRE-32） |
| §45 | `interface RecoveryEngine { suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan }` | **変更しない**（実測: 既存 `recovery/RecoveryEngine.kt` と完全一致） |
| §50 | `RecoveryContext(currentTime, currentLocation, event, unfinishedSteps, latestTravelEstimate, plannedDepartureTime)` | **変更しない**。`transportMode` を足さない解決策を§7.5で示す |
| §51 | `RecoveryOption(id, semanticAction, title, explanation, estimatedArrival, skippedStepIds)` | **変更しない**。`title`/`explanation` 空文字＋`semanticAction`解決（ADR-0018踏襲） |
| §95.2 | 「Reality Check（§30）・Departure Mode等での再計算は、Routes APIを**ポーリングせず、スロットリングとキャッシュを義務**とする」 | D案の再計算は**DI供給の `CachingRoutingService` 経由のみ**（§7.5） |
| §61 | 自動メール送信・自動SMS・自動予定変更を禁止 | §32の「Prepare a delay message」をPhase 6から除外する根拠（§4.2 U-3） |
| §88 | 「その機能は、予定を今やる一つの行動に変えることに直接寄与するか？ NoならMVPへ入れない」 | 通知・履歴学習・案の説明文生成をPhase 6に持ち込まない |

---

## §2. スコープ

### 2.1 やること

F70〜F78（§5）。サイクルはP6-C1〜C7（§10）。テスト全69件（§8）、エラー＆レスキューマップ全21行（§9）。

### 2.2 やらないこと（明示）

- **Phase 5領域**: `services/notification/`（未存在。Phase 5が新設）・AlarmManager／BootReceiver／Foreground Service・`AndroidManifest.xml` の通知/アラーム権限追加に**一切触れない**。
- **`features/execution/` 配下の全ファイル**: `ExecutionScreen.kt` / `ExecutionUiState.kt` / `ExecutionViewModel.kt` を**変更しない**（§6所有権整理）。DEBUG「Simulate delay」ボタンも**現状のまま維持**（T-NAV-3・T-E2E-2の回帰保護）。
- **Recovery通知（§62の3通知のうち#3）**: 通知としてのRecovery発火はPhase 5の通知基盤に依存するため対象外。Phase 6は**アプリがフォアグラウンドにある間の画面内検知のみ**。
- **§32 option 3「Prepare a delay message」**: §61（自動メール/SMS禁止）・§34（対外連絡は確認必須）・§88に照らし、共有Intent導線を含めPhase 6では実装しない（§4.2 U-3でユーザー確認）。
- **Local AI Recovery（§73 Phase 9）**: `LocalAIRecoveryEngine`・`ai/AIRecoveryResponse.kt` は参照も実装もしない。
- **§30の完全実装**: `completedSteps` の実データ供給（実行中の完了記録）はPhase 5のstep progressionに属する。Phase 6は `ExecutionStep.completedAt` を**読むだけ**で、書き込み経路は作らない。
- **PersonalExecutionProfile由来の個人化**: Phase 10。

---

## §3. ゲート

`docs/TEAMS.md`§6に基づきG1〜G4を適用する。G4は**G4-JVM**と**G4-E**の2段階とする（Phase 4と同じ2段階構成）。

- **G1（計画承認）**: 本計画書＋エラー＆レスキューマップ（§9）＋Fable 5 Pass1レビュー記録。**Pass1（CRITICAL）レビューは実施済みであり、U-1〜U-9はFable 5裁定として全項目が推奨案どおり承認済み（2026-08-09、§4.2）。** **Geminiクロスレビュー（`model: "gemini-3.5-flash"`固定）はCRITICAL指摘6件を提示し、Fable 5裁定によりすべて推奨案どおり採用し本書へ反映済みである（2026-08-09、§4.3）。これによりG1は通過した。** また、**P6-C1着手そのものの前提条件としてPhase 3クローズを要する**（本書の計画内容そのものに対するG1裁定とは別軸の着手条件であることに留意する）。加えて、Phase 5とはP6-C1〜C4を並列実行可能だが、Phase 5のC6（統合）およびC7（Refactor）完了後に、Phase 6のC5（統合）を実行する必要がある（§10・§11.2）。
- **G2（Red確認）**: P6-C2でtest-writerが作成したfailingテスト（§8、全69件のうちJVM系67件＝E1区分49件＋E2区分18件）をquality-runnerが実測する。E3系2件（T-P6E2E-1〜2）は作成のみでRed実測はG4-Eまで行わない（Phase 1・Phase 2・Phase 4と同じ扱い）。
- **G3（Green確認）**: P6-C3（Domain側Green）・P6-C4（UI側Green、C3と並列）それぞれでのGreen実測、およびP6-C5（統合ウィンドウ、直列）後の再実測。
- **G4-JVM（Phase 6完了・JVM側）**: P6-C6完了時点。`./gradlew build`成功・対象範囲のJVM/Robolectric全テストPass・`lintDebug`エラー0を実測する。No giant Composable/ViewModel（§89）の確認を含む。
- **G4-E（Phase 6完了・Emulator側、「薄いG4-E」）**: P6-C7完了時点。Simulate delay → Recovery画面到達・候補ETA表示のja/enスクリーンショット取得（T-P6E2E-1）、「Use this plan」復帰の非ループ確認（T-P6E2E-2）を行う。**G4-E未達のままPhase 7以降へ進むことを禁止する**（`docs/plans/phase2-calendar.md`§3・`docs/plans/phase4-basic-engine.md`§3の先例を踏襲）。

Phase 7着手条件は本書の範囲外とする。

---

## §4. 承認状態

**Fable 5レビュー済み・U-1〜U-9すべて裁定済み（2026-08-09・すべて推奨案どおり承認）。Fable 5＋Gemini G1完了・CRITICAL 6件反映済み（2026-08-09）→ G1通過。**

### 4.1 仕様の矛盾・未定義（自己補完していない論点。メモ§9.2）

| ID | 内容 | 提案 |
|---|---|---|
| **S-1** | **§32「Leave now」と§33「必須物は勝手に省略しない」の衝突**。未完了のREQUIREDステップが残っている状態で「今すぐ出発」を提示すると、REQUIREDを暗黙に省略することになる | REQUIREDが残る場合、A案を「残りの準備（REQUIREDを含む）を終えてから出発」として提示し、REQUIREDは常に `skippedStepIds` に入れない。REQUIREDが0件のときのみA案は実質「Leave now」に縮退する。**§7.3のA案定義はこの解釈に基づく** |
| **S-2** | **§32 option 3「Prepare a delay message」が §61（自動メール送信・自動SMS禁止）・§88（予定を今やる一つの行動に変えるか）と整合しない**。また§34は「対外連絡」をユーザー確認必須としており、実装には共有Intent導線が必要 | Phase 6のスコープから除外する。Local AI Recovery（§73 Phase 9）で「自然言語説明生成」の一部として再検討する |
| **S-3** | **§50 `RecoveryContext` に `transportMode` がない**ため、「移動手段変更」案を作るのに現在の移動手段を engine が知る手段が仕様上存在しない | §7.5の方式（コンストラクタ供給・既定 `TRANSIT`）で§50を変更せずに解決する。契約変更（`RecoveryContext` への追加）はTEAMS §5のversion付き変更経路を要するため採らない |
| **S-4** | **§51 `RecoveryOption.title`/`explanation` が仕様上「表示文言」であるのに、§7 Global-first は UI文字列のハードコード禁止を要求する** | ADR-0018（`ExecutionStep.title` 空文字＋`semanticId` 解決）と同じ扱いにし、空文字＋`semanticAction` 解決とする。ADRで逸脱を記録 |
| **S-5** | **§30 の `completedSteps` の供給元が未定義**。実行中のステップ完了記録を持つ層がまだ存在しない（`ExecutionStep.completedAt` はモデル上あるが書き込み経路がない） | Phase 6は `completedAt == null` のステップのみを残準備として扱う設計にして構造的に耐える。書き込み経路の実装はPhase 5（step progression）へ委ねる |
| **S-6** | **§32「最大3つ」の切り詰め時にどれを残すかが仕様未定義** | §33（予定成立優先・過剰省略の禁止）＋§34（ユーザー最終決定）から §7.3 の全順序規則を導出した。ADRで導出根拠を記録 |
| **S-7** | **遅延の閾値（何分遅れたらRecoveryか）が仕様未定義** | 閾値を設けず「予定が成立しない（`ETA > eventStart`）」を唯一のトリガーとする。閾値導入は§88に照らし過剰設計と判断 |

### 4.2 ユーザー（Fable 5）確認事項の裁定（メモ§9.3、U-1〜U-9）

android-planner計画メモが提起した論点はU-1〜U-9として整理され、**Fable 5はすべての項目を推奨案どおり承認した（2026-08-09）**。

| ID | 確認事項 | 推奨案（メモ§9.3） | 裁定（Fable 5・2026-08-09） |
|---|---|---|---|
| **U-1** | **F番号の採番**。Phase 5が未計画のためブロックが未予約 | Phase 5にF49〜F69を予約し、Phase 6はF70から採番する | **承認**。Phase 5にF49〜F69を予約し、Phase 6はF70から採番する |
| **U-2** | S-1の解釈（REQUIREDが残る場合のA案の意味づけ） | §7.3の定義を承認 | **承認**。S-1のA案解釈を承認する（REQUIREDが残存する場合は「必須を終えて出発」という意味で§7.3のA案定義を確定する） |
| **U-3** | S-2（§32 option 3「Prepare a delay message」をPhase 6から除外） | 除外を承認し、Phase 9で再検討 | **承認**。「Prepare a delay message」をPhase 6のスコープから除外し、Phase 9で再検討する |
| **U-4** | S-3（`transportMode` をコンストラクタ供給・既定 `TRANSIT`）／代替手段1つのみ試行 | 承認。既定値は `DepartureUiState` 実測値（TRANSIT）と一致させる | **承認**。`transportMode`はコンストラクタ供給とし既定値`TRANSIT`、代替手段は1つのみ試行する |
| **U-5** | S-4（`title`/`explanation` の空文字化＝§51からの逸脱） | ADR-0018と同型としてADR起票のうえ承認 | **承認**。`title`/`explanation`は空文字とし`semanticAction`で解決する方式とする。ADR-0018の拡張としてADRを起票する |
| **U-6** | 既存テスト変更の承認（TEAMS §2）: `AppContainerTest.kt`（§9 #20 必須）・`RecoveryScreenTest.kt`（シグネチャ追随） | assertion強度維持を条件に承認 | **承認**（条件付き）。`AppContainerTest`のhard fail修正と`RecoveryScreenTest`の追随変更を、assertion強度維持を条件に承認する |
| **U-7** | `RecoveryPlan` への失敗理由フィールド追加（ADR-0005補完型のため§仕様変更ではない） | デフォルト引数付き追加を承認 | **承認**。`RecoveryPlan`への失敗理由フィールド追加は、デフォルト引数付きで行うことを承認する |
| **U-8** | DEBUG「Simulate delay」ボタンの扱い。実検知経路が入った後も残すか | **残す**（T-NAV-3／T-E2E-2の回帰保護、§70完成条件の再現手段）。`ExecutionScreen.kt` は変更しない | **承認**。DEBUG「Simulate delay」ボタンは維持する |
| **U-9** | Phase 5との統合ウィンドウの順序（§11.2の判定） | Phase 5のC6（統合）およびC7（Refactor）完了後に、Phase 6のC5（統合）を実行する直列を承認 | **承認**。Phase 5のC6（統合）およびC7（Refactor）完了後に、Phase 6のC5（統合）を実行するという統合順序（直列）を承認する |

**上記U-1〜U-9はいずれもFable 5裁定として確定済みであり、ユーザー承認待ちの項目ではない**（本書冒頭の承認状態のとおり。※メモ§12は本来「未裁定」として列挙するよう指示していたが、Fable 5裁定完了によりこの指示を上書きしている）。

### 4.3 Geminiクロスレビュー

**実施済み（2026-08-09）**。CRITICAL指摘6件（G1）に対し、Fable 5がすべて推奨案どおり採用する裁定を行い、本書へ反映した（§7.1・§7.9・§10・§11.2・U-9関連の各該当箇所）。これによりG1は通過した（§3参照）。

---

## §5. 機能一覧（F番号）

> **F番号の採番はユーザー確認事項（§4.2 U-1）として裁定済み**。Phase 3=F21〜F39予約、Phase 4=F40〜F48使用済み。Phase 5にF49〜F69を予約し、Phase 6はF70から採番する。

| ID | 機能 | §70項目 | 仕様根拠 |
|---|---|---|---|
| F70 | `BasicRecoveryEngine`（`RecoveryEngine` 実装の本番化） | deterministic alternatives | §45・§70・§13 |
| F71 | `LatenessDetector`（決定的な遅延判定・フォアグラウンド限定） | lateness detection | §30・§13「遅延検知」 |
| F72 | `RemainingPreparation` 算出（TRANSITION/PREPARATIONのみ合計・`completedAt`除外） | remaining preparation | §30・§48 |
| F73 | 候補生成4規則 A/B/C/D とカスケード条件 | deterministic alternatives | §31・§32・§33 |
| F74 | 決定的優先順位・3案上限の切り詰め規則・A案保証 | deterministic alternatives | §32「最大3つ」・§33・§34 |
| F75 | 移動手段変更案の再計算（DI供給 `CachingRoutingService` 経由・1呼び出し上限） | recalculation | §32・§95.2（義務）・§4 |
| F76 | `RecoveryPlanApplier`（選択案の適用＝steps除去＋時刻再計算） | recalculation | §34・§49 |
| F77 | Recovery UI の ETA表示 と `semanticAction`→`stringResource` 解決 | — | §32・§21・§7 |
| F78 | `MockRecoveryFactory` 削除とDI差替・テスト移設 | — | §89「No duplicated domain logic」 |

---

## §6. フットプリント

### 6.1 新規作成（Phase 6専有）

| パス（`app/src/` 起点） | 内容 | 担当 |
|---|---|---|
| `main/java/com/actionstarter/recovery/BasicRecoveryEngine.kt` | F70/F73/F74/F75 | domain-implementer |
| `main/java/com/actionstarter/recovery/BasicRecoveryDefaults.kt` | 代替移動手段テーブル・既定 `TransportMode`。**仕様未定義プレースホルダである旨をKDocへ明記**（`BasicPlanningDefaults` 先例） | domain-implementer |
| `main/java/com/actionstarter/recovery/LatenessDetector.kt` | F71/F72（`LatenessVerdict` 含む） | domain-implementer |
| `main/java/com/actionstarter/recovery/RecoveryPlanApplier.kt` | F76 | domain-implementer |
| `main/java/com/actionstarter/features/recovery/RecoveryOptionText.kt` | F77（`semanticAction`→`stringResource`） | ui-implementer |
| `test/java/com/actionstarter/recovery/BasicRecoveryEngineTest.kt` | T-BRE-1〜31 | test-writer |
| `test/java/com/actionstarter/recovery/RecoveryLlmIsolationTest.kt` | T-BRE-32 | test-writer |
| `test/java/com/actionstarter/recovery/LatenessDetectorTest.kt` | T-LATE-1〜10 | test-writer |
| `test/java/com/actionstarter/recovery/RecoveryPlanApplierTest.kt` | T-APPLY-1〜7 | test-writer |
| `test/java/com/actionstarter/features/RecoveryViewModelTest.kt` | T-RECVM-1〜8 | test-writer |
| `test/java/com/actionstarter/features/RecoveryOptionDisplayTest.kt` | T-RECUI-1〜8 | test-writer |
| `androidTest/java/com/actionstarter/e2e/RecoveryBasicE2ETest.kt` | T-P6E2E-1〜2 | test-writer |

### 6.2 既存ファイルの変更（Phase 6専有）

| パス | 変更内容 |
|---|---|
| `main/.../features/recovery/RecoveryViewModel.kt` | `Clock`／`LocationService` 注入、`currentLocation` バグ修正、`try/catch` 追加、適用ロジック結線 |
| `main/.../features/recovery/RecoveryUiState.kt` | `routingFailureReason: Int?`・適用状態フィールド追加 |
| `main/.../features/recovery/RecoveryScreen.kt` | ETA表示、`semanticAction` 解決、「Use this plan」の適用結線 |
| `test/java/com/actionstarter/features/RecoveryScreenTest.kt` | UiState/Screen シグネチャ変更への追随。**assertion強度は維持し弱体化しない**（TEAMS §2 承認要請対象。§4.2 U-6で承認済み） |
| `test/java/com/actionstarter/di/AppContainerTest.kt` | `resolveMockPackageDir()` の hard fail 修正（§9 #20）。**TEAMS §2 承認要請対象**（§4.2 U-6で承認済み） |

### 6.3 削除（P6-C5統合ウィンドウ）

| パス | 理由 |
|---|---|
| `main/java/com/actionstarter/mock/MockRecoveryFactory.kt` | `BasicRecoveryEngine` への完全昇格。並存は§89「No duplicated domain logic」違反（ADR-0019＝`MockPlanFactory` の先例と同型） |
| `test/java/com/actionstarter/mock/MockRecoveryFactoryTest.kt` | 検証意図をT-BRE-11/12/20へ移設（§7.8） |

**副作用（実測）**: これにより `mock/` パッケージは main/test 双方で**消滅する**。§9 #20 の対処が必須。

### 6.4 共有ファイル（P6-C5統合ウィンドウでのみ直列に編集）

| # | 共有ファイル | 変更内容 |
|---|---|---|
| 1 | `di/AppContainer.kt` | `recoveryEngine` を `BasicRecoveryEngine(routingService)` へ差替。`RecoveryViewModel` initializer に `locationService`／`clock` 追加。`MockRecoveryFactory` import 削除。**単一Factory集約（ADR-0003/0014の保護条件）を維持** |
| 2 | `navigation/ActionStarterNavHost.kt` | execution route に lateness評価の `LaunchedEffect` を1箇所追加（one-shotガード込み）。Mock言及KDoc更新 |
| 3 | `res/values/strings.xml` ／ `res/values-ja/strings.xml` | `recovery_option_title_*` / `recovery_option_explanation_*`（4キー×2）＋フォールバック＋経路失敗注記。**両ファイル同時更新**（`StringResourceParityTest` が検査） |
| 4 | `DECISIONS.md` | ADR起票（§7.9） |
| 5 | `docs/plans/phase6-recovery-basic.md` | 本メモの計画書化（本書） |

### 6.5 非重複宣言（他Phase領域への不可侵）

- **Phase 5領域（最重要）**: `services/notification/`（未存在。Phase 5が新設）・`features/execution/` 配下の**全ファイル**（`ExecutionScreen.kt`／`ExecutionUiState.kt`／`ExecutionViewModel.kt`）・`AndroidManifest.xml`（`POST_NOTIFICATIONS`／`SCHEDULE_EXACT_ALARM`／`RECEIVE_BOOT_COMPLETED`／`FOREGROUND_SERVICE`）・BootReceiver／Foreground Service関連クラス・`gradle/libs.versions.toml`／`app/build.gradle.kts` に**一切触れない**。
- **Phase 3領域**: `services/routing/`・`services/location/` を**読むだけで再利用し、変更しない**（`RoutingService`／`LocationService` は既存契約のまま注入する）。
- **Phase 4領域**: `planning/`（`BasicPlanningEngine.kt` 他）・`features/planreview/`・`features/departure/` を**変更しない**。
- **Phase 2領域**: `services/calendar/`・`services/permission/`・`features/eventselection/` を**変更しない**。
- **Phase 7/9領域**: `ai/`（`AIRecoveryResponse.kt`／`LocalLanguageModel.kt`）を**参照も変更もしない**（T-BRE-32で構造ガード）。
- **Domain契約**: `domain/model/RecoveryContext.kt`（§50）・`RecoveryOption.kt`（§51）・`recovery/RecoveryEngine.kt`（§45）を**変更しない**。`RecoveryPlan.kt`（ADR-0005補完型）へのフィールド追加のみ§7.9 ADR起票で扱う。

---

## §7. 契約・設計

### 7.1 `recovery/BasicRecoveryEngine.kt`

```
class BasicRecoveryEngine(
    private val routingService: RoutingService,          // DI供給＝CachingRoutingService or UnconfiguredRoutingService
    private val defaults: BasicRecoveryDefaults = BasicRecoveryDefaults
) : RecoveryEngine {
    override suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan
}
```

- **§45契約は変更しない**（実測済み: 既存interfaceとシグネチャ完全一致。plan-doc-writerによる`RecoveryEngine.kt`直接Readでも再確認済み）。
- **§50 `RecoveryContext` も変更しない**。`transportMode` は §7.5 の方式で解決する。
- `title`/`explanation` は**常に空文字**、`semanticAction` に言語非依存キーを入れる（ADR-0018 = `ExecutionStep.title` 空文字＋`semanticId`解決の先例をRecoveryへ拡張）。
- `RecoveryOption.id` は `UUID.nameUUIDFromBytes` による**決定的生成**（ADR-0017踏襲。`MockRecoveryFactory` の `UUID.randomUUID()` は非決定的で回帰テストが書けない）。**idシードは `"${event.id}:${semanticAction}:${skippedStepIds.sorted().joinToString(",")}"` とする**（`semanticAction`単独ではB/C案の構成差やコンテキスト差で衝突するため）。`estimatedArrival` はシードに含めない——RoutingService結果で揺れると決定性が壊れるため、構成のみで一意化する。

### 7.2 決定的な導出量（すべて `Instant`/`Duration` 演算・LLM不使用＝§13/§15）

`T = context.currentTime`, `V = context.latestTravelEstimate`, `E = context.event.startDate`, `D = context.plannedDepartureTime`

```
U        = context.unfinishedSteps.filter { it.completedAt == null &&
             (it.type == TRANSITION || it.type == PREPARATION) }      … F72（DEPARTURE/TRAVELは移動側なので除外）
R_all    = U.sumOf { it.estimatedDuration }
Skippable_opt    = U.filter { it.priority == OPTIONAL  && it.skippable }
Skippable_impopt = Skippable_opt + U.filter { it.priority == IMPORTANT && it.skippable }
                                    ※ REQUIRED は priority で除外、skippable==false も除外（二重ガード）
ETA(K)   = T + (R_all − dur(K)) + V           … 案Kのdeparture = T + (R_all − dur(K))
feasible(K) = ETA(K) <= E                      … §33「予定成立」。境界は等号込みで成立扱い
behindSchedule = T > D                         … 情報のみ。トリガーにしない（誤発火防止）
```

### 7.3 候補生成規則（§31〜§33から導出した完全決定的カスケード）

| # | semanticAction | 省略集合 K | 生成ガード（AND） | ETA |
|---|---|---|---|---|
| **A** | `keep_all_steps` | ∅ | **無条件**（§34：ユーザーが「変更しない」を選べる権利を常に残す） | `T + R_all + V` |
| **B** | `skip_optional_steps` | `Skippable_opt` | `!feasible(∅)` かつ `Skippable_opt ≠ ∅` かつ `dur(K) > 0` | `T + R_all − dur(K) + V` |
| **C** | `skip_optional_and_important_steps` | `Skippable_impopt` | `!feasible(∅)` かつ **`!feasible(Skippable_opt)`**（＝Bで足りないときだけ）かつ `dur(C) > dur(B)` | 同上 |
| **D** | `change_transport_mode` | ∅ | `!feasible(∅)` かつ `currentLocation != null` かつ `event.coordinates != null` かつ RoutingService成功 かつ `V' < V` | `T + R_all + V'` |

**カスケード条件が§33の写像である理由**: 「安全・必須物は勝手に省略しない」＋「予定成立を優先」＝**予定が成立する最小の省略深度で止める**。よってBで成立するならCを生成しない（過剰省略の禁止＝T-BRE-10）。A案が成立しているなら遅れていないので B/C/D を一切生成しない（＝T-BRE-5、options は A 1件のみ）。

**優先順位（全順序・切り詰め規則）** — F74:
```
sortKey(c) = ( feasible(c) ? 0 : 1,      // 予定成立案が先（§33）
               |K(c)|,                    // 省略件数の少ない案が先（§33 過剰省略の禁止）
               ETA(c),                    // 早着が先
               ruleOrdinal(c) )           // A=0,B=1,C=2,D=3 … 完全同値でも順序が一意（決定性）
options = sorted(...).take(3)
if (A !in options) options = options.dropLast(1) + A     // §34：A案は必ず含まれる
```
`ruleOrdinal` の存在により、数値が完全に同値でも順序が一意に定まる（T-BRE-24）。「省略件数の少ない案が先」により、**D案（省略0）は成立するB案（省略あり）より上位に来る**——これも§33（省略しなくて済むなら省略しない）の直接の帰結。

### 7.4 REQUIRED省略禁止（T-DM-9契約の継承）

`MockRecoveryFactory.kt:37-39` が実装している契約——「省略候補にできるのは `skippable == true` かつ `priority != REQUIRED` のステップのみ」——を `BasicRecoveryEngine` がそのまま継承する。検証は3層で行う:
1. **生成側**: 省略集合の構築時点で `priority != REQUIRED && skippable` をフィルタ（T-BRE-11/12）
2. **適用側**: `RecoveryPlanApplier` が `skippedStepIds` にREQUIREDのidを見つけたら `IllegalArgumentException`（信頼境界の二重防御。T-APPLY-3）
3. **UI側**: 既存 T-REC-4（REQUIREDステップ名が省略候補領域に現れない）を維持

### 7.5 移動手段変更案（D案）と§95.2スロットリング

- **`RoutingService` は必ずDI供給インスタンスを注入する**。`AppContainer.routingService`（`AppContainer.kt:108-113`）は既に `CachingRoutingService(RoutesApiRoutingService(...))` または `UnconfiguredRoutingService` に解決済みであり、**Phase 6は `RoutesApiRoutingService` を直接生成しない**（§95.2「スロットリングとキャッシュを義務」を Phase 3 の実装で満たす）。構造ガードをテスト化（T-BRE-18/19、`recovery/` が `RoutesApiRoutingService` を import しない）。
- **API呼び出しは `createRecoveryPlan` 1回あたり最大1回**（T-BRE-18）。A案が成立していれば**0回**（T-BRE-19）。ポーリングしない（§95.2）。
- **代替手段は1つだけ試す**（決定的テーブル・`BasicRecoveryDefaults`へ隔離）:
  `TRANSIT→DRIVING` / `WALKING→TRANSIT` / `CYCLING→TRANSIT` / `DRIVING→TRANSIT`。4モード総当たりは1 Recoveryあたり3コールになり§95.2のコスト方針に反する。
- **現在の移動手段の取得**: §50 `RecoveryContext` に `transportMode` フィールドが無い。**契約を変更しない**ため、`BasicRecoveryEngine` のコンストラクタに `currentTransportMode: () -> TransportMode = { BasicRecoveryDefaults.DEFAULT_TRANSPORT_MODE }` を持たせる。既定値は `TRANSIT`（`DepartureUiState.kt:50` の実測既定値と一致）。**仕様未定義プレースホルダである旨をKDocに明記**（`BasicPlanningDefaults` のG-1先例に倣う）。§4.2 U-4で承認済み。
- **`departureDate` 引数**: `estimateRoute(origin, destination, mode, departureDate)` の `departureDate` には**A案のdeparture（`T + R_all`）** を渡す（TRANSITは出発時刻依存＝ADR-0004の理由）。
- **失敗時**: `RoutingException` を捕捉して**D案を落とすだけ**にし、固定値で穴埋めしない（Phase 3が `UnconfiguredRoutingService` で解消した「20分捏造」の再発禁止）。**ただし握り潰さない**——`RecoveryPlan` に失敗理由を載せてUIへ伝える（§7.7）。

### 7.6 `recovery/LatenessDetector.kt`（F71）— Phase 5非依存の境界定義

**実装する範囲（Phase 6）**:
```
sealed interface LatenessVerdict {
    data object OnTrack : LatenessVerdict
    data class WillMissEvent(val delay: Duration, val projectedArrival: Instant, val behindSchedule: Boolean)
}
object LatenessDetector {
    fun evaluate(context: RecoveryContext): LatenessVerdict   // 純関数・Clock非依存（currentTimeはcontext由来）
}
```
- **発火条件は `ETA(∅) > E` の1点のみ**（＝A案のまま進むと予定が成立しない）。`T > D`（予定出発時刻を過ぎた）は `behindSchedule` として**情報のみ返し、トリガーにしない**——移動時間が縮んでいれば予定は成立するため、これをトリガーにすると誤発火し、既存 T-NAV-1／T-E2E-1（Selection→Review→Execution→Departure の通し）を破壊する。
- **境界は等号込みで OnTrack**（`ETA == E` は成立扱い）。`CachingRoutingService.isReusable` の狭義不等号運用と方向を揃え、境界値をテストで固定（T-LATE-3）。

**評価タイミング（Phase 5非依存の境界）**:
| 契機 | Phase 6で実装するか | 理由 |
|---|---|---|
| Execution route への**入場・フォアグラウンド復帰** | **する**（`ActionStarterNavHost` に `LaunchedEffect` 1箇所。C5統合ウィンドウのみ） | Composeライフサイクル内で完結。通知/FGS/AlarmManager不要 |
| Recovery画面の表示時（自己再評価） | **する**（`RecoveryViewModel` 内） | 画面内で完結 |
| **「Done」タップ時の再評価** | **しない** | ステップ進行ループはPhase 5（§69 Step start/Done/Snooze）の所有。現行 `ExecutionUiState.onDone` は NavHost で `null` 固定（`ActionStarterNavHost.kt:183`）＝**本番では未配線**。ここへ結線するのは死んだ経路への配線になる |
| **「5 min later」タップ時の再評価** | **しない** | 同上。現行 `ExecutionViewModel.handlePostpone()`（`ExecutionViewModel.kt:82-86`）はプレースホルダstepの `scheduledStart` をローカルに +5分するだけで、`confirmedPlan` にも `RecoveryContext` にも反映されない。Phase 5がSnoozeを本実装した時点で `LatenessDetector.evaluate()` を**呼ぶ側**として結線する |
| 定期タイマー／バックグラウンド監視 | **しない** | AlarmManager/FGS＝Phase 5。§95.1 While-in-use制約によりバックグラウンド位置取得も不可 |

**Phase 5への申し送り（必須）**: `LatenessDetector.evaluate()` は純関数として公開する。Phase 5がstep progression（Done/Snooze/next action）を本実装する際、各遷移後にこれを呼び出してRecovery割込を発火させる。**Phase 6はAPIを提供し、呼び出し側の所有権はPhase 5に置く。**

### 7.7 UI層の設計（F77）

- `features/recovery/RecoveryOptionText.kt` — `resolveRecoveryOptionTitle(semanticAction): String` / `resolveRecoveryOptionExplanation(semanticAction, eta): String`。既知4キー（`keep_all_steps`/`skip_optional_steps`/`skip_optional_and_important_steps`/`change_transport_mode`）を `stringResource` へ、**未知キーはフォールバック文言を返し例外を投げない**（`features/common/StepTitle.kt:33` の `resolveStepTitle` 先例をそのまま踏襲）。
- `RecoveryScreen` は各候補に **`recovery_option_eta_label` ＋ 整形済みETA** を表示（§32）。`estimatedArrival == null` の候補はETA行を出さない（偽値表示の禁止。T-RECUI-8）。
- `RecoveryUiState` に **`routingFailureReason: Int?`（string res id）** を追加し、D案が経路取得失敗で落ちたことを明示する（サイレント障害の解消）。
- 「Use this plan」は `RecoveryPlanApplier` を通し `SharedPlanViewModel.confirmPlan(updatedPlan)` を呼ぶ。`RecoveryViewModel` は既に `SharedPlanViewModel` を注入済み（`RecoveryViewModel.kt:31`）のため**共有ファイル変更不要**。
- `RecoveryViewModel` に `Clock` と `LocationService` を注入し、`currentTime` を `Clock` 由来へ、`currentLocation` を `LocationService` 由来へ変更する（§0の不備1・テスト決定性）。`LocationService` は Phase 3 の成果物を**読むだけで再利用**（`DepartureViewModel` の先例）。位置未取得時は `null` を渡してD案のみ落とす。

### 7.8 `MockRecoveryFactory` 削除とテスト移設マッピング

**削除は P6-C5（統合ウィンドウ）で実施**（Phase 4 §7.2 の3段階移行手順をそのまま踏襲）:
1. **P6-C1**: `BasicRecoveryEngine.kt` を `TODO()` で新設。`AppContainer` は `MockRecoveryFactory()` のまま。
2. **P6-C2〜C4**: Red→Green。テストは `BasicRecoveryEngine(...)` を直接インスタンス化して検証（`AppContainer` 未接続）。
3. **P6-C5**: `AppContainer.recoveryEngine` を差替 → `mock/MockRecoveryFactory.kt` ＋ `test/.../mock/MockRecoveryFactoryTest.kt` 削除 → `AppContainer.kt:46-48` / `RecoveryViewModel.kt:22` / `ActionStarterNavHost.kt` のMock言及KDoc更新。

| 旧テスト | 新テスト | 検証意図（assertion強度を維持し弱体化しない） |
|---|---|---|
| T-DM-9（`createRecoveryPlan_neverSkipsRequiredPriorityStep`、`MockRecoveryFactoryTest.kt:71`） | **T-BRE-11** | REQUIREDのidがいずれの `skippedStepIds` にも含まれない（§33） |
| （Mock暗黙契約: `skippable && priority != REQUIRED` のみ省略、`MockRecoveryFactory.kt:37-39`） | **T-BRE-12** | `skippable == false` の IMPORTANT も省略対象にしない（Mockでは未テストだった契約を明示化） |
| （Mock暗黙契約: `options.take(3)`、`MockRecoveryFactory.kt:84`） | **T-BRE-20** | 4案生成条件下でも `options.size <= 3`（§32） |
| T-DM-7/T-DM-8（`RecoveryPlanTest.kt`） | **移設しない**（`domain/RecoveryPlanTest.kt` を現状維持） | `RecoveryPlan` の `init` 不変条件はDomain側の責務のまま |

**注記（Phase 4 R-6の現実化・CRITICAL）**: `MockRecoveryFactory.kt` 削除により `app/src/main/java/com/actionstarter/mock/` は**空になり消滅する**（実測: 同ディレクトリの唯一のファイル。plan-doc-writerによる`ls mock/`実行でも再確認済み）。`di/AppContainerTest.kt` の `resolveMockPackageDir()` はディレクトリ非存在時に `error()` でhard failするため、**T-P4DI-2 と `mockEventSourceKtFile_doesNotExistUnderSrcMain` が同時に落ちる**。P6-C5で同ヘルパを「ディレクトリ非存在は期待パスを返して正常扱い」へ修正する。これは既存テストファイルの変更にあたるため **TEAMS §2の「既存テスト更新承認要請」をG1で提出する**（assertion強度は維持・弱体化しない。§4.2 U-6で承認済み）。

### 7.9 ADR起票候補（P6-C1で起票・番号は§12 V-5で確定）

1. **Recovery候補の生成規則と優先順位を§31〜§33から導出した全順序規則として確定する**（§7.3。S-6の導出根拠、`ruleOrdinal` による決定性保証を含む）
2. **`RecoveryOption.title`/`explanation` は空文字固定とし、UI層で `semanticAction` をlocalizationキーとして解決する**（S-4。ADR-0018のRecoveryへの拡張）
3. **`RecoveryOption.id` は `UUID.nameUUIDFromBytes` による決定的生成へ置き換える**（ADR-0017のRecoveryへの拡張）。**idシードは `"${event.id}:${semanticAction}:${skippedStepIds.sorted().joinToString(",")}"` とする**（`semanticAction`単独ではB/C案の構成差やコンテキスト差で衝突するため。`estimatedArrival`はシードに含めない——RoutingService結果で揺れると決定性が壊れるため、構成のみで一意化する）
4. **`transportMode` は §50 `RecoveryContext` へ追加せず `BasicRecoveryEngine` のコンストラクタで供給する**（S-3）
5. **`mock/MockRecoveryFactory.kt` はP6-C5統合ウィンドウで削除し `BasicRecoveryEngine` へ完全昇格する**（ADR-0019と同型）
6. **lateness detection はフォアグラウンド限定とし、通知/FGS/AlarmManager契機の評価はPhase 5の所有とする**（§7.6の境界確定）

---

## §8. テストケース表（全69件：正常系31／異常系14／エッジケース24。E1区分49件／E2区分18件／E3区分2件）

### 8.1 分類定義（Phase 3/4先例踏襲）

| 区分 | 内容 | source set | runner | Gradleタスク | 必要端末 |
|---|---|---|---|---|---|
| E1 | 純粋JVM（Android Framework非依存。fake の `RoutingService`/`Clock` のみに依存） | `src/test` | JUnit4 | `:app:testDebugUnitTest` | 不要 |
| E2 | Robolectric＋Compose Test（画面・ViewModel・リソース解決） | `src/test` | JUnit4 + Robolectric（＋Compose Test） | `:app:testDebugUnitTest` | 不要 |
| E3 | Compose Test（instrumented） | `src/androidTest` | AndroidJUnitRunner + Compose Test | `:app:connectedDebugAndroidTest` | 必要（エミュレータ） |

全実行は `--console=plain`、ログは `build/agent-logs/` へ保存。

### 8.2 F70/F73/F74/F75 — `BasicRecoveryEngine`（E1・`src/test/java/com/actionstarter/recovery/BasicRecoveryEngineTest.kt`／32件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-BRE-1 | 正常系 | A案（`keep_all_steps`）が常に生成される | BasicRecoveryEngine |
| T-BRE-2 | 正常系 | `ETA(A) = currentTime + R_all + latestTravelEstimate` と一致する | BasicRecoveryEngine |
| T-BRE-3 | 正常系 | `R_all` は TRANSITION/PREPARATION のみの合計（DEPARTURE/TRAVELの `estimatedDuration` を含まない） | BasicRecoveryEngine |
| T-BRE-4 | エッジ | `completedAt != null` のステップは `R_all` から除外される | BasicRecoveryEngine |
| T-BRE-5 | 正常系 | **逼迫度0**: `ETA(A) <= eventStart` のとき `options` はA案1件のみ（B/C/D非生成） | BasicRecoveryEngine |
| T-BRE-6 | 正常系 | **逼迫度小**: `ETA(A) > E` かつ skippable OPTIONAL あり → B案生成 | BasicRecoveryEngine |
| T-BRE-7 | エッジ | skippable OPTIONAL が0件なら B案を生成しない | BasicRecoveryEngine |
| T-BRE-8 | エッジ | `skippable == false` の OPTIONAL は省略集合に入らない | BasicRecoveryEngine |
| T-BRE-9 | 正常系 | **逼迫度大**: `ETA(B) > E` のとき C案（OPTIONAL+IMPORTANT）を生成 | BasicRecoveryEngine |
| T-BRE-10 | エッジ | `ETA(B) <= E`（B案で成立）なら C案を生成しない（過剰省略の禁止・§33） | BasicRecoveryEngine |
| T-BRE-11 | 異常系 | REQUIREDステップのidがいずれの `skippedStepIds` にも含まれない（**旧T-DM-9移設**・§33） | BasicRecoveryEngine |
| T-BRE-12 | 異常系 | `skippable == false` かつ IMPORTANT のidも `skippedStepIds` に含まれない | BasicRecoveryEngine |
| T-BRE-13 | 正常系 | fake RoutingService が現行より短い所要を返すとき D案が生成される | BasicRecoveryEngine |
| T-BRE-14 | エッジ | fake が同等または長い所要を返すとき D案を生成しない | BasicRecoveryEngine |
| T-BRE-15 | エッジ | `currentLocation == null` のとき D案のみ落ち、A/B/Cは生成される | BasicRecoveryEngine |
| T-BRE-16 | エッジ | `event.coordinates == null` のとき D案を生成しない（座標を捏造しない） | BasicRecoveryEngine |
| T-BRE-17 | 異常系 | RoutingService が `RoutingException` を送出 → D案なしで他案を返し、**失敗理由が `RecoveryPlan` に載る**（握り潰さない） | BasicRecoveryEngine |
| T-BRE-18 | 正常系 | `createRecoveryPlan` 1回あたり RoutingService 呼び出しは**最大1回**（fakeで回数計測・§95.2） | BasicRecoveryEngine |
| T-BRE-19 | 正常系 | A案が成立している場合、RoutingService を**1回も呼ばない**（§95.2コスト方針） | BasicRecoveryEngine |
| T-BRE-20 | 正常系 | 4案すべての生成条件が揃っても `options.size <= 3`（§32） | BasicRecoveryEngine |
| T-BRE-21 | 正常系 | 4案生成条件下でも A案が必ず `options` に含まれる（§34） | BasicRecoveryEngine |
| T-BRE-22 | 正常系 | 並び順: `feasible` な案が `!feasible` な案より前 | BasicRecoveryEngine |
| T-BRE-23 | 正常系 | 並び順: `feasible` 同士は省略件数の少ない案が前（D案がB案より前に来る） | BasicRecoveryEngine |
| T-BRE-24 | エッジ | ETA・省略件数が完全同値でも `ruleOrdinal`（A<B<C<D）で順序が一意（決定性） | BasicRecoveryEngine |
| T-BRE-25 | 正常系 | 同一 `RecoveryContext` を2回渡すと `RecoveryOption.id` を含め完全同一の結果（決定的id・ADR-0017踏襲。idシード=`"${event.id}:${semanticAction}:${skippedStepIds.sorted().joinToString(",")}"`、`estimatedArrival`は非含有） | BasicRecoveryEngine |
| T-BRE-26 | 正常系 | 全 `RecoveryOption` の `title`/`explanation` が空文字、`semanticAction` が既定4キーのいずれか（§21・ADR-0018） | BasicRecoveryEngine |
| T-BRE-27 | 異常系 | `latestTravelEstimate` が負 → `IllegalArgumentException`（T-BPE-20先例） | BasicRecoveryEngine |
| T-BRE-28 | エッジ | `currentTime > event.startDate`（開始済み）でも例外を送出せず生成する（T-BPE-19先例） | BasicRecoveryEngine |
| T-BRE-29 | エッジ | DST切替を跨ぐ `eventStart` でも `Instant` 基準演算が1時間ずれない（T-BPE-24先例） | BasicRecoveryEngine |
| T-BRE-30 | 異常系 | `Instant.MIN` 等でオーバーフローする入力 → 例外が握り潰されず伝播（**例外型は要検証**。T-BPE-23はP4-C6で `DateTimeException` と実測確定済みのため同型と推定するが未検証） | BasicRecoveryEngine |
| T-BRE-31 | エッジ | `unfinishedSteps` が空でも A案1件を生成（`options` が0件にならない） | BasicRecoveryEngine |
| T-BRE-32 | 正常系 | `recovery/` 配下が `com.actionstarter.ai` を参照しない構造ガード（§13/§15。`PlanningLlmIsolationTest` 先例／別ファイル `RecoveryLlmIsolationTest.kt`） | recovery package |

### 8.3 F71/F72 — `LatenessDetector`（E1・`src/test/java/com/actionstarter/recovery/LatenessDetectorTest.kt`／10件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-LATE-1 | 正常系 | `ETA(A) < eventStart` → `OnTrack` | LatenessDetector |
| T-LATE-2 | 正常系 | `ETA(A) > eventStart` → `WillMissEvent(delay = ETA − eventStart)` | LatenessDetector |
| T-LATE-3 | エッジ | `ETA(A) == eventStart`（境界）→ `OnTrack`（等号は成立扱い） | LatenessDetector |
| T-LATE-4 | エッジ | `currentTime > plannedDepartureTime` でも `ETA(A) <= eventStart` なら `OnTrack`（誤発火防止） | LatenessDetector |
| T-LATE-5 | 正常系 | `WillMissEvent.behindSchedule` が `currentTime > plannedDepartureTime` と一致（情報のみ・トリガーではない） | LatenessDetector |
| T-LATE-6 | エッジ | `unfinishedSteps` が空 → `R_all = ZERO` として判定 | LatenessDetector |
| T-LATE-7 | エッジ | `completedAt != null` のステップは `R_all` から除外 | LatenessDetector |
| T-LATE-8 | エッジ | DST跨ぎで判定が1時間ずれない | LatenessDetector |
| T-LATE-9 | 異常系 | `latestTravelEstimate` が負 → `IllegalArgumentException` | LatenessDetector |
| T-LATE-10 | 正常系 | 同一入力に対し常に同一判定（純関数・システム時刻非依存） | LatenessDetector |

### 8.4 F76 — `RecoveryPlanApplier`（E1・`src/test/java/com/actionstarter/recovery/RecoveryPlanApplierTest.kt`／7件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-APPLY-1 | 正常系 | 選択案の `skippedStepIds` に対応するステップが `ExecutionPlan.steps` から除去される | RecoveryPlanApplier |
| T-APPLY-2 | 正常系 | `departureTime`/`estimatedArrival` が除去後の残準備で再計算される | RecoveryPlanApplier |
| T-APPLY-3 | 異常系 | `skippedStepIds` にREQUIREDのidが含まれる入力 → `IllegalArgumentException`（信頼境界の二重防御・§33） | RecoveryPlanApplier |
| T-APPLY-4 | エッジ | `skippedStepIds` が空（A案）→ `steps` は不変、時刻のみ現在時刻基準で再計算 | RecoveryPlanApplier |
| T-APPLY-5 | 異常系 | `steps` に存在しないidが含まれる → 黙って無視せず `IllegalArgumentException` | RecoveryPlanApplier |
| T-APPLY-6 | 正常系 | `arrivalBuffer`（希望余裕）は変更されない（§4・Phase 4 G-5の概念区別を維持） | RecoveryPlanApplier |
| T-APPLY-7 | エッジ | 全準備ステップ除去後も `ExecutionPlan` が成立する（`steps` が DEPARTURE のみでも例外なし） | RecoveryPlanApplier |

### 8.5 F77 — `RecoveryViewModel`（E2・`src/test/java/com/actionstarter/features/RecoveryViewModelTest.kt`／8件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-RECVM-1 | 正常系 | `confirmedPlan` から `RecoveryContext` を構築し、`currentTime` が**注入 `Clock`** 由来である | RecoveryViewModel |
| T-RECVM-2 | 異常系 | `currentLocation` が `LocationService` 由来である（**`event.coordinates` を現在地に使わない**＝現行バグの回帰ロック） | RecoveryViewModel |
| T-RECVM-3 | エッジ | 位置取得が `PermissionDenied`/null でも `options` は生成される（D案のみ落ちる） | RecoveryViewModel |
| T-RECVM-4 | エッジ | `confirmedPlan == null` → クラッシュせず `options` 空＋案内状態になる | RecoveryViewModel |
| T-RECVM-5 | 異常系 | engine が例外を送出 → UiStateにエラー理由が現れ、握り潰されない | RecoveryViewModel |
| T-RECVM-6 | 正常系 | 「Use this plan」で `RecoveryPlanApplier` を経由し `SharedPlanViewModel.confirmPlan` が**1回だけ**呼ばれる | RecoveryViewModel |
| T-RECVM-7 | 異常系 | 候補未選択のまま確定操作 → 何も適用されず `confirmPlan` が呼ばれない（§34） | RecoveryViewModel |
| T-RECVM-8 | 正常系 | 適用後は再突入抑止フラグが立ち、Execution復帰後に同一遅延で再びRecoveryへ遷移しない（無限ループ防止） | RecoveryViewModel |

### 8.6 F77 — `RecoveryScreen`（E2・`src/test/java/com/actionstarter/features/RecoveryOptionDisplayTest.kt`／8件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-RECUI-1 | 正常系 | 各候補に `recovery_option_eta_label` ＋整形済みETAが表示される（§32・未配線文字列の解消） | RecoveryScreen |
| T-RECUI-2 | 正常系 | `title`/`explanation` が `semanticAction` から `stringResource` 解決される（Domain層のハードコード英語が消えている） | RecoveryOptionText |
| T-RECUI-3 | エッジ | 未知の `semanticAction` → フォールバック文言を返しクラッシュしない（`resolveStepTitle` 先例） | RecoveryOptionText |
| T-RECUI-4 | 正常系 | ja/en 両ロケールで全候補の文言・ETAラベルが非空（既存T-REC-6の拡張） | RecoveryScreen |
| T-RECUI-5 | 異常系 | REQUIREDステップ名が省略候補領域に現れない（**既存T-REC-4の維持**） | RecoveryScreen |
| T-RECUI-6 | 異常系 | 候補選択だけでは自動適用されない（**既存T-REC-5の維持**・§34） | RecoveryScreen |
| T-RECUI-7 | エッジ | 候補0件 → 案内文言と手動導線（**既存T-REC-3の維持**） | RecoveryScreen |
| T-RECUI-8 | エッジ | `estimatedArrival == null` の候補はETA行を描画しない（偽値を表示しない） | RecoveryScreen |

### 8.7 F78 — DI・削除確認（E2・`src/test/java/com/actionstarter/di/AppContainerTest.kt`／2件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P6DI-1 | 正常系 | `AppContainer.recoveryEngine` が `BasicRecoveryEngine` のインスタンスである | AppContainer |
| T-P6DI-2 | 異常系 | `mock/MockRecoveryFactory.kt` が `src/main` に存在しない。**かつ `mock/` ディレクトリ自体が消滅していても hard fail せず正しく Green になる**（T-P4DI-2 と同じ方式・R-6対応） | AppContainer |

### 8.8 E2E（E3・`src/androidTest/java/com/actionstarter/e2e/RecoveryBasicE2ETest.kt`／2件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P6E2E-1 | 正常系 | Simulate delay → Recovery画面到達、候補1件以上、各候補にETA表示。ja/en スクリーンショット取得（**§70完成条件**） | E2E |
| T-P6E2E-2 | エッジ | 「Use this plan」→ Execution復帰、**Recoveryへ再ループしない** | E2E |

E2E群は実行するまでpassとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ。Phase 1・Phase 2・Phase 4の先例踏襲）。

---

## §9. エラー＆レスキューマップ（全21行・ハンドリング方法列に空欄なし）

| # | 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|---|
| 1 | 候補生成（`createRecoveryPlan`） | REQUIREDステップが省略候補に混入する | 生成側で `priority != REQUIRED && skippable` フィルタ、適用側で `IllegalArgumentException`、UI側でT-REC-4の3層防御（§7.4） | 必須物が勝手に省略されることがない（§33） |
| 2 | 候補生成 | 4案の生成条件が揃い `RecoveryPlan(options)` の `init { require(size <= 3) }` に抵触してクラッシュ | engine側で `sortKey` による切り詰め（§7.3）を行い、`RecoveryPlan` へ渡す前に必ず3件以下にする。`init` は最後の防壁として残す | 選択肢過多にならない（§32）。クラッシュしない |
| 3 | 候補生成 | 生成結果が0件になり画面が空白になる | A案は無条件生成のため構造的に0件にならない（T-BRE-31）。万一0件でもUIは既存の `recovery_no_options_message` 経路（T-REC-3）へ写像する | 常に少なくとも1つの選択肢が提示される |
| 4 | 移動手段変更案（経路再計算） | ネットワーク断・タイムアウト・クォータ超過で `RoutingException` | DI供給の `CachingRoutingService` が既にretry 1回とstaleキャッシュ復帰を実施済み。それでも失敗したらD案を生成せず、`RecoveryUiState.routingFailureReason` へ理由を格納しUIに明示（固定値で穴埋めしない） | 移動手段変更案が出ない旨が画面に表示される。他案は通常どおり選べる |
| 5 | 移動手段変更案 | `ROUTES_API_KEY` 未設定で `UnconfiguredRoutingService` が `RoutingException.NotConfigured` を送出 | #4と同じ経路で処理。未設定であることをログに記録する | D案が出ないだけで、Recovery全体は成立する |
| 6 | 移動手段変更案 | 経路APIを繰り返し呼びコストが膨らむ（§95.2違反） | 呼び出しは `createRecoveryPlan` 1回あたり最大1回・代替手段は決定的テーブルで1つのみ・A案成立時は0回。全てテストで固定（T-BRE-18/19） | 課金・電池消費が抑えられる |
| 7 | 現在地取得 | `ACCESS_FINE_LOCATION` 拒否・`LocationService` が null を返す | `currentLocation = null` のまま `RecoveryContext` を構築し、D案のみ落とす。A/B/Cは位置情報なしで成立する（§95.6「Recovery Modeは位置情報なしでも成立するようフォールバック」に整合） | 移動手段変更案が出ないが、省略案による予定成立支援は継続する |
| 8 | 現在地取得 | アプリがバックグラウンドで位置取得が `SecurityException`／null（§95.1 While-in-use制約） | Phase 6の評価タイミングはフォアグラウンド限定（§7.6）。`FusedLocationService` の `ForegroundGate` が構造的にブロックし、Phase 6は null 扱いで#7へ合流 | バックグラウンドでは検知が保留される旨をPhase 5の通知設計へ申し送る |
| 9 | 遅延検知（`LatenessDetector`） | 遅れていないのにRecoveryへ自動遷移し、通常フロー（T-NAV-1/T-E2E-1）を破壊する | トリガー条件を `ETA(A) > eventStart` の1点に限定し、`currentTime > plannedDepartureTime` はトリガーにしない（§7.6）。境界は等号込みで `OnTrack`（T-LATE-3/4で固定） | 予定が成立している限りRecoveryに邪魔されない |
| 10 | 遅延検知 | Recovery →「Use this plan」→ Execution復帰 → 再評価で再びRecoveryへ、の**無限ループ**（`ActionStarterNavHost.kt:217` の `popBackStack()` でexecutionへ戻る現行結線と、C5で追加する入場時評価の組み合わせで発生する） | `SharedPlanViewModel`（または Phase 6所有の抑止フラグ）に「このExecution滞在中はRecovery自動遷移を1回だけ」の one-shot ガードを置き、適用済みの `RecoveryOption.id` を記録する。手動のDEBUGボタン経路はガード対象外 | 画面が往復し続けて操作不能になることがない |
| 11 | 遅延検知 | `confirmedPlan == null` の状態で評価が走りNPE | `RecoveryContext` を組めないため評価をスキップし、既存T-NAV-4ガード（`ActionStarterNavHost.kt:164-177`）でeventSelectionへ戻す経路に合流 | Plan未確定時は従来どおりイベント選択へ戻される |
| 12 | 候補適用（`RecoveryPlanApplier`） | `skippedStepIds` に `ExecutionPlan.steps` に存在しないidが含まれる | 黙って無視せず `IllegalArgumentException` を送出し、ViewModelが捕捉してUIへエラー表示（T-APPLY-5） | 意図しないステップ構成のまま実行が進むことがない |
| 13 | 候補適用 | 適用処理が途中で失敗し、Planが半端な状態で確定される | `RecoveryPlanApplier` は新しい `ExecutionPlan` インスタンスを**構築し切ってから** `SharedPlanViewModel.confirmPlan` を1回だけ呼ぶ（既存Planを部分変更しない。`ExecutionPlan` は全val＝ADR-0010） | 部分適用された壊れたPlanが残らない |
| 14 | 候補適用 | ユーザーが候補未選択のまま「Use this plan」を押す | 適用を実行せず `confirmPlan` を呼ばない（T-RECVM-7）。選択を促す表示を出す | 意図しない案が勝手に適用されない（§34） |
| 15 | 候補生成（入力検証） | `latestTravelEstimate` が負値（信頼境界: 経路API応答・手動入力由来） | `IllegalArgumentException` を送出し握り潰さない（T-BRE-27・T-LATE-9。`BasicPlanningEngine` T-BPE-20の先例） | 不正な入力で誤ったETAが提示されない |
| 16 | 候補生成（入力検証） | `Instant`/`Duration` 演算がオーバーフロー（極端な `eventStart`） | 例外を捕捉せずそのまま呼び出し元へ伝播させ、ViewModelが#17の経路でUIへ提示する（**例外型は要検証**。§12 V-1） | 誤った時刻が表示されるよりクラッシュ／エラー表示のほうが安全 |
| 17 | ViewModel（engine呼び出し） | `viewModelScope.launch` 内でengineが例外を送出し、ログにも戻り値にも現れない（**現行 `RecoveryViewModel.kt:37-43` のサイレント障害**） | `try/catch` で捕捉し `RecoveryUiState` のエラー欄へ写像。`CancellationException` は再送出（`CachingRoutingService.kt:86` の先例に倣う） | 何も表示されないまま固まる状態がなくなる |
| 18 | UI文言解決 | 未知の `semanticAction` が渡り `stringResource` が解決できずクラッシュ | `when` の `else` でフォールバック文言を返す（`resolveStepTitle` 先例。T-RECUI-3） | 文言が汎用表現になるだけでクラッシュしない |
| 19 | UI表示 | `estimatedArrival == null` の候補でETA欄に「--」等の偽値や空ラベルが出る | ETA行そのものを描画しない（T-RECUI-8） | 存在しない到着時刻を信じてしまうことがない |
| 20 | Mock削除（P6-C5） | `mock/` ディレクトリ消滅により `AppContainerTest.resolveMockPackageDir()` が `error()` でhard failし、T-P4DI-2 と `mockEventSourceKtFile_doesNotExistUnderSrcMain` が同時に落ちる | P6-C5で同ヘルパを「ディレクトリ非存在なら期待パスを返す」実装へ修正する。既存テスト変更のためTEAMS §2の承認要請をG1で提出（assertion強度は維持。§4.2 U-6で承認済み） | Phase 6の完了ゲートが無関係な理由で塞がれない |
| 21 | Mock削除（P6-C5） | `MockRecoveryFactory` へのKDocリンク（`AppContainer.kt:46-48`・`RecoveryViewModel.kt:22`・`ActionStarterNavHost.kt`）がダングリング参照として残る | P6-C5で全参照箇所を grep 実測して `BasicRecoveryEngine` へ更新する（P4-C6で `MockPlanFactory` の同種問題を修正した先例あり） | ドキュメントの記述が実装と食い違わない |

---

## §10. サイクル分解（P6-C1〜C7）

| サイクル | 内容 | 担当（Do） | 到達ゲート |
|---|---|---|---|
| **P6-C1** scaffold | `recovery/BasicRecoveryEngine.kt`・`BasicRecoveryDefaults.kt`・`LatenessDetector.kt`・`RecoveryPlanApplier.kt` を `TODO()` 本体で新設。`features/recovery/RecoveryOptionText.kt` 宣言。既存`RecoveryViewModel`（Clock/LocationService注入のコンストラクタ変更・デフォルト引数でコンパイル互換維持）・`RecoveryUiState`（routingFailureReason等の新フィールドをデフォルト値付き追加）・`RecoveryScreen`（新ラムダのデフォルト`{}`付き追加）のシグネチャscaffoldを含める（P6-C2のRedがコンパイル可能になる前提整備。Phase 3のUiState拡張scaffold先例）。ADR起票（§7.9）。**`AppContainer`には触れない**（`MockRecoveryFactory` を現役のまま並存）。ベースライン実測記録（着手時点の `:app:testDebugUnitTest` 件数）。**完了（実測2026-08-09）**: `recovery/BasicRecoveryEngine.kt`／`BasicRecoveryDefaults.kt`／`LatenessDetector.kt`／`RecoveryPlanApplier.kt`（すべて本体`TODO()`のみ、ロジックなし）・`features/recovery/RecoveryOptionText.kt`（`resolveRecoveryOptionTitle`／`resolveRecoveryOptionExplanation`宣言、本体`TODO()`）を新設した。`RecoveryViewModel`（`locationService: LocationService = UnavailableLocationService`／`clock: Clock = Clock.systemUTC()`をデフォルト値付きで追加注入、`init`／`buildRecoveryContext`のロジックは無変更）・`RecoveryUiState`（`routingFailureReason: Int? = null`／`isPlanApplied: Boolean = false`をデフォルト値付きで追加）・`RecoveryScreen`（`onUseThisPlan: (UUID?) -> Unit = {}`を追加、body内では未使用）のシグネチャscaffoldを実装した。`AppContainer`・`ActionStarterNavHost`・`strings.xml`（ja/en）・`AndroidManifest.xml`には一切触れていない。ベースライン実測（`:app:testDebugUnitTest`、`build/agent-logs/p6c1-baseline.log`）: 245件・failures 0・skipped 1・errors 0。scaffold後の再実測（`--rerun`、`build/agent-logs/p6c1-regression.log`）も245件・failures 0・skipped 1・errors 0で完全一致し、既存テストへの回帰がないことを確認した。`:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`もBUILD SUCCESSFUL実測（`build/agent-logs/p6c1-compile.log`）。**ADR起票（§7.9）はP6-C5へ延期した**（本サイクルとの差異）: §7.9冒頭は「P6-C1で起票」と明記するが、§6.4はDECISIONS.mdを「P6-C5統合ウィンドウでのみ直列に編集」する共有ファイルと明記しており、本書内に矛盾がある。加えて本サイクル実行中に`docs/plans/phase3-routing-location.md`・`app/src/androidTest/java/com/actionstarter/e2e/RoutesApiLiveTest.kt`が別agentにより実際に同時編集されていることを実測確認した（`find -newer`で検出。非gitリポジトリのため競合時のロールバック手段がない）。データ安全性（Fable Protocol Pass1）を優先し、6件のADR候補本文はDECISIONS.mdへ書き込まず、各scaffoldファイルのKDocへ内容を記録するにとどめた（実際の起票はP6-C5または別途のドキュメント同期サイクルで行うこと）。次ADR番号確認: DECISIONS.md最新はADR-0023のまま（P6-C1時点で再実測、V-5未変化）。 | domain-implementer | scaffoldコンパイル成功・ベースラインログ |
| **P6-C2** Red | §8の全69件のうちJVM系67件（E1 49＋E2 18）をfailing化し実測でRed確認。E3 2件は作成のみ。**完了（実測2026-08-09、64/67件・E3除く）**: 新規6ファイル（`test/.../recovery/BasicRecoveryEngineTest.kt` T-BRE-1〜31・`RecoveryLlmIsolationTest.kt` T-BRE-32・`LatenessDetectorTest.kt` T-LATE-1〜10・`RecoveryPlanApplierTest.kt` T-APPLY-1〜7・`test/.../features/RecoveryViewModelTest.kt` T-RECVM-1〜5・`RecoveryOptionDisplayTest.kt` T-RECUI-1〜8）を作成し、既存`test/.../di/AppContainerTest.kt`へT-P6DI-1/2を追加した（既存の`RecoveryScreenTest.kt`・`RecoveryPlanTest.kt`は無変更）。E1（T-BRE 32＋T-LATE 10＋T-APPLY 7＝49件）は計画どおり全件作成。E2はT-RECUI 8件・T-P6DI 2件を全件作成したが、**T-RECVM-6/7/8の3件はC2対象外とした**（理由: `RecoveryScreen.onUseThisPlan: (UUID?) -> Unit`ラムダの受け皿となる「選択候補を適用する」publicメソッドがP6-C1時点の`RecoveryViewModel`に一切存在せず、存在しないメンバー参照はコンパイルエラーになるため。E3（T-P6E2E-1/2）はエミュレータ使用禁止の担当プロンプト制約により本サイクルでは作成していない）。**Red実測**（新規6クラス＋`AppContainerTest`個別実行、`build/agent-logs/p6c2-red.log`）: 69件実行・60件failed・9件passed。failedは全件、5クラス（TODO()本体の`BasicRecoveryEngine`／`LatenessDetector`／`RecoveryPlanApplier`／`RecoveryOptionText`）由来の`NotImplementedError`（直接伝播、またはassertThrowsの期待型不一致によるAssertionError経由）、または実装済み`RecoveryViewModel`・`AppContainer`に対する意図どおりのAssertionError（値の不一致）であることをHTMLレポートの実メッセージで個別確認済み（例: tRecVm1は`expected:<2026-08-10T09:15:00Z> but was:<2026-08-09T11:22:...>`＝注入Clock無視のバグを実測、tP6Di1は`実際: com.actionstarter.mock.MockRecoveryFactory`＝未差替を実測）。passed 9件はすべて事前に「既存の正しい挙動の回帰ガード」と文書化済み（T-BRE-32＝`recovery/`のai非参照は既に真、T-RECVM-4＝`confirmedPlan==null`時に空optionsのまま留まる既存挙動、T-RECUI-5/6/7＝既存`RecoveryScreenTest`のT-REC-4/5/3と同型でRecoveryScreenの既存実装により成立、`docs/plans/phase4-basic-engine.md`のT-BPE-28先例と同じ性質）。**回帰確認**（`:app:testDebugUnitTest --rerun`、`build/agent-logs/p6c2-regression.log`）: 346件実行・90件failed・1件skipped（skipped件数はP6-C1ベースライン245件時点から不変）。failed内訳は(a)自分の新規Red＝60件（上記と一致）、(b)並行Phase 5のC2レーン（`services/notification/`＋`persistence/`、いずれも本サイクル中に新規追加されたファイルであることを`git status`で確認済み・未コミット）＝30件（`AlarmSchedulingTest`9・`ScheduleRestoreReceiverTest`7・`ExecutionScheduleStoreTest`7・`AndroidNotificationServiceTest`6・`NotificationTriggerReceiverTest`1）、(c)上記以外の既存クラスの失敗＝**0件**（回帰なし）。60+30=90で完全一致。**差し戻し事項（quality-runner／domain-implementer・P6-C3向け）**: ①T-BRE-17は「失敗理由が`RecoveryPlan`に載る」部分を検証できない（`domain/model/RecoveryPlan.kt`にP6-C1時点で失敗理由フィールドが未追加。§7.9 ADR起票候補2・U-7の履行漏れの可能性があり、これによりV-3も未確定のまま）。「D案なしで他案を返す」部分のみ実装した。②T-BRE-24はsortKey第4要素`ruleOrdinal`が正規の入力からは実質到達不能であることを分析済み（D案生成ガード`V'<V`の狭義不等号によりA/D間のETAが必ず異なり、B/C/Dは省略集合Kの要素数が構造的に一致しないため）。近似ケースで代替検証した。③`RecoveryPlanApplier.apply`の`departureTime`/`estimatedArrival`再計算式はscaffold KDoc自身が「要検証」と明記しており、T-APPLY-2/4はR_all（TRANSITION/PREPARATION残量）を`clock`起点に加算し移動時間は元Planから維持する解釈で期待値を設定した（ファイル冒頭KDoc参照）。④T-RECVM-6/7/8は上記のとおりC2対象外（`RecoveryViewModel`への適用メソッド追加が前提。契約確定後に追加実装が必要）。⑤T-P6DI-2の「`mock/`ディレクトリ自体が消滅してもhard failしない」というサブ要件は、実ディレクトリを破壊的に削除しない限り現状のテストでは分離検証できないため、C5で`resolveMockPackageDir()`を修正した後に同一テストがそのまま通ることで担保する設計とした（ディレクトリ削除のシミュレーションはP6-C2では行っていない）。V-2（R-3の入場時自動遷移が既存NAV/E2Eテストを壊さないか）は`LatenessDetector`本体が未実装のため引き続き未確定。 | test-writer → quality-runner | **G2** |
| **P6-C3** Green(Domain) | `BasicRecoveryEngine`／`LatenessDetector`／`RecoveryPlanApplier`／`BasicRecoveryDefaults` 実装（T-BRE/T-LATE/T-APPLY 計49件）。**完了（実測2026-08-09、domain-implementer。C4も同一セッションでオーケストレーターの指示により統合実施——通常のC3/C4並列agent起動ではなくC3〜C4を1エージェントへ委譲する形へ変更されたための§10からの逸脱。詳細はP6-C4行参照）**: `BasicRecoveryDefaults`→`LatenessDetector`→`BasicRecoveryEngine`→`RecoveryPlanApplier`の順で段階的にGreen化した（`build/agent-logs/p6c3-green-*.log`）。**裁定1**: `RecoveryPlan.routingFailureReason: String? = null`をデフォルト値付きで追加（`RecoveryPlanTest`のT-DM-7/8は無影響、実測確認済み＝V-3解消）。型を`RoutingException`ではなく`String?`としたのは、`domain.model`パッケージが`services.routing`パッケージへ依存する逆方向importを避けるため（`ARCHITECTURE.md`§1のレイヤー順序）。T-BRE-17を拡張し「失敗理由がRecoveryPlanに載る」側もGreen化した。**裁定2（採用式の報告）**: T-APPLY-2/4の期待値を「`estimatedArrival`は`option.estimatedArrival`をそのまま転記する」方式へ承認済み変更した。根拠: `RecoveryPlanApplier`は`RoutingService`を持たず独自に移動時間を再取得できないため、P6-C2の暫定式（元Planの移動時間を流用）ではD案（`change_transport_mode`）選択時に代替移動手段の短縮効果が消えるという構造的誤りがあった。`RecoveryOption.estimatedArrival`は`BasicRecoveryEngine`が構成ごとに権威的に計算済み（D案なら代替見積り反映済み）でUIにも同じ値を表示するため、これを転記する方式を採用した（詳細根拠は`RecoveryPlanApplier.kt`・`RecoveryPlanApplierTest.kt`双方のKDoc参照）。`departureTime`の式（clock起点＋残TRANSITION/PREPARATION）はP6-C2案のまま変更していない。**RecoveryOption.id**は`UUID.nameUUIDFromBytes("${event.id}:${semanticAction}:${skippedStepIds.sorted().joinToString(",")}")`で実装（T-BRE-25実測Green）。Refactor実施: `BasicRecoveryEngine.createRecoveryPlan`を`buildSkipCandidates`/`attemptChangeTransportModeCandidate`/`sortAndTruncate`等の私有関数へ分割し可読性を改善（再テスト後も4件の既知失敗を含め挙動完全一致を実測確認、`build/agent-logs/p6c4-refactor-BasicRecoveryEngine.log`）。**発見事項（要判断・修正権限外につき未修正で報告）**: `BasicRecoveryEngineTest`のT-BRE-6/7/8/28（4件）は`NeverCalledRoutingService`（呼ばれたら`IllegalStateException`で即失敗するfake）を使用しているが、これら4ケースの`context()`呼び出しは`currentLocation`／`event.coordinates`をデフォルト値（非null）のまま残しており、D案生成ガード（§7.3: `!feasible(∅) かつ currentLocation != null かつ event.coordinates != null`）がB/C案の生成有無と無関係に独立して成立してしまうため、実装は仕様どおりRoutingServiceを呼び出し、fakeがIllegalStateExceptionを送出して失敗する。兄弟テストT-BRE-9/10/11/12は同じ状況で`currentLocation = null`を明示設定し（コメント「D案を本テストの関心事から外す」）、`allFourRulesContext()`（T-BRE-20/21/26が使用）はB案が候補として生成されてもD案が独立に生成されることを明示的に検証しており（コメント「A/B/C/Dすべてのガードが成立する」）、これらはD案の生成条件がB/C案の生成有無と独立であることを裏付ける。本セッションの制約（「テスト側の変更は裁定2の承認済み期待値更新・裁定3の新規Redのみ」）によりテスト修正は行っていない。推奨対応（後続サイクルでの承認要）: 4ケースの`context()`呼び出しへ`currentLocation = null`を追加するか、`NeverCalledRoutingService`を「呼ばれても例外を投げない安全なダミー」へ差し替える。**P6-C3追補3（test-writer、Fable 5裁定、2026-08-09）**: 上記4ケース（T-BRE-6/7/8/28）をFable 5裁定によりfixtureのみ修正（T-BRE-9〜12と同型で`currentLocation = null`を追加しD案をスコープ外へ。`NeverCalledRoutingService`・アサーション本体は無変更）。実測31/31 Green・failures 0・errors 0・skipped 0（本ファイルは冒頭KDocのとおり`T-BRE-1〜31`の31件構成、T-BRE-32は別ファイル`RecoveryLlmIsolationTest`が担当のため対象外。委譲指示にあった「32/32」は本ファイルの実テスト数と不一致のため実測値で記録する）。ログ: `build/agent-logs/p6c3fix-bre.log`。 | domain-implementer（**C4と並列起動**） | **G3** |
| **P6-C4** Green(UI) | `RecoveryOptionText.kt` 実装、`RecoveryScreen.kt`／`RecoveryUiState.kt`／`RecoveryViewModel.kt` の改修（T-RECVM/T-RECUI 計16件）。**完了（実測2026-08-09、domain-implementerがP6-C3と同一セッションで統合実施）**: `RecoveryOptionText.kt`は`resolveRecoveryOptionTitle`/`resolveRecoveryOptionExplanation`を実装したが、既知4キー・未知キーいずれも`stringResource(R.string.step_title_fallback)`へ暫定フォールバックする（strings.xml編集がC5統合ウィンドウ専有のため専用キーを追加できない制約下での、ハードコード禁止を満たす最小実装。T-RECUI-2/3はGreen＝いずれも非空文字列であることのみを要求する仕様のため）。`RecoveryScreen.kt`はETA行（`testTag("recovery_option_eta_<id>")`、`DepartureScreen.kt`と同じ`DateTimeFormatter`パターン）を追加し、「Use this plan」ボタンへ`onUseThisPlan(selectedId)`を結線した（T-RECUI-1/4/8がGreen）。**title/explanationは意図的に`resolveRecoveryOptionTitle`/`resolveRecoveryOptionExplanation`へ切り替えていない**（既存`RecoveryOption.title`/`explanation`を直接表示したまま）。理由: 切り替えると既存`RecoveryScreenTest.tRec2_threeOptions_allDisplayed`の`onNodeWithText(option.title)`（テスト固定値の実テキスト一致を要求）を破壊するため。本セッションの制約は`RecoveryScreenTest.kt`の変更を許可していない（計画書§6.2 U-6は本来この変更を承認済みだが、本タスクの委譲プロンプトはテスト変更を裁定2/3の範囲に限定しているため、より制限的な方の指示に従った）。結果、`MockRecoveryFactory`が現役のP6-C3/C4時点では`option.title`/`explanation`のハードコード英語が引き続き表示される（回帰ではなく現状維持）。`RecoveryViewModel`は**裁定3**（`useThisPlan(optionId: UUID?)`追加、T-RECVM-6/7/8を新規Red→Green化。`build/agent-logs/p6c3-red-additional.log`でNotImplementedError実測後、Green化。one-shotガード`isPlanApplied`で二重適用防止、T-RECVM-8）と**裁定4**（欠陥1: `Instant.now()`→`clock.instant()`、`plan.event.coordinates`→`locationService`経由の実測現在地／欠陥5: `viewModelScope.launch`へtry/catch追加・`CancellationException`は再送出・engine例外は`RecoveryUiState.routingFailureReason`へ表面化。T-RECVM-1/2/3/5がGreen化）を実装した。Phase 6スコープ一括実行（`build/agent-logs/p6c3-phase6-scope-full.log`）: 82件・76 Green・Red 6件はいずれも想定内（T-P6DI-1/2＝C5まで意図的Red2件、P6-C3行「発見事項」のBasicRecoveryEngineTest 4件）。全JVMスイート最終実測（Refactor後、`build/agent-logs/p6c4-full-final.log`）: 364件・failures 50・skipped 1。内訳「3分類」: (a) Phase 5系新規テストの意図的Red 44件（`ExecutionOneActionTest`7・`ExecutionScheduleStoreTest`7・`ExecutionForegroundServiceTest`6・`AlarmSchedulingTest`10・`AndroidNotificationServiceTest`6・`NotificationTriggerReceiverTest`1・`ScheduleRestoreReceiverTest`7、いずれも`git status`で未追跡＝Phase 5新規ファイルと実測確認済み）、(b) `AppContainerTest`のT-P6DI-1/2＝C5まで意図的Red 2件、(c) それ以外＝P6-C3行「発見事項」の4件のみ。44+2+4=50で完全一致し、それ以外の回帰は検出されなかった。**C5必須申し送り**: ①C5で`AppContainer.recoveryEngine`を`BasicRecoveryEngine`へ差替後、`BasicRecoveryEngine`は`title`/`explanation`を空文字固定で返すため、`RecoveryScreen`を`resolveRecoveryOptionTitle`/`resolveRecoveryOptionExplanation`へ結線し直さないと画面が空白表示になる。②strings.xmlへ`recovery_option_title_keep_all_steps`/`_skip_optional_steps`/`_skip_optional_and_important_steps`/`_change_transport_mode`と対応する`_explanation_*`（ja/en計8+α）を追加。③上記①の結線に伴い`RecoveryScreenTest.tRec2`のfixtureを承認済み変更として更新する必要がある（assertion強度は維持）。④`RecoveryUiState.routingFailureReason`はD案専用のはずだが、`RecoveryViewModel`のengine例外ハンドラが暫定的に`R.string.recovery_no_options_message`を転用しているため、専用の`recovery_engine_error_message`追加を検討。⑤P6-C3行「発見事項」（T-BRE-6/7/8/28のfixture不整合）の承認・修正要否判断。 | ui-implementer（**C3と同一メッセージで並列起動**） | **G3** |
| **P6-C5** 統合（直列） | `AppContainer.recoveryEngine` を `BasicRecoveryEngine(...)` へ差替（DI供給の `routingService` を注入）。`ActionStarterNavHost` の execution route に lateness評価フックを1箇所追加。`mock/MockRecoveryFactory.kt` ＋ `MockRecoveryFactoryTest.kt` 削除。`AppContainerTest.resolveMockPackageDir()` の hard fail 修正（§9 #20）。strings.xml ja/en 追加。T-P6DI-1/2 | domain-implementer（integration owner） | **G3** |**完了（実測2026-08-09、domain-implementer）**: 4ゲートすべて通過。①全JVMスイート`:app:testDebugUnitTest --rerun`＝**363 tests・failures 0・errors 0・skipped 1**（`build/agent-logs/p6c5-full.log`。プロジェクト初の完全Green。P6-C4時点の364件から363件への純減1件は`mock/MockRecoveryFactoryTest.kt`削除〔T-DM-9の1件のみで構成〕によるもので、検証意図はP6-C3でT-BRE-11/12/20へ移設済みのため正味の検証カバレッジは減っていない）、②`:app:assembleDebug :app:assembleRelease` BUILD SUCCESSFUL（`build/agent-logs/p6c5-assemble.log`）、③マージ済みManifest（debug/release両変種）検証（`build/agent-logs/p6c5-manifest.log`）——権限構成（5件のPhase5権限＋INTERNET＋位置2権限＋READ_CALENDAR）・service/receiver宣言・MainActivity singleTop・`ACCESS_BACKGROUND_LOCATION`実タグ0件・`AIza`文字列0件のいずれもP5-C6のp5c6-manifest.log記録と完全一致し、意図しない変更が生じていないことを確認済み（本サイクルはManifestを直接編集していない）、④`:app:lintDebug` **error 0**・warning **22件**（P5-C6時点から不変、`build/agent-logs/p6c5-lint.log`）。新規追加stringsのMissingTranslation **0**件・UnusedResources増減 **0件**（既存3件＝`execution_placeholder_step_title`／`location_permission_denied_message`／`travel_time_manual_apply_button`のみで、新規9キー×2ロケール全てが実際に参照されていることを確認）。<br><br>**①AppContainer結線**: `recoveryEngine`プロパティを`planningEngine`の隣接位置から`routingService`宣言の直後へ移動したうえで`BasicRecoveryEngine(routingService)`へ差替（第2/第3引数は計画書§6.4行1のとおり既定値のまま省略。ADR-0035）。Kotlinのプロパティ初期化はクラス本文の宣言順に実行されるため、`recoveryEngine`の初期化式が参照する`routingService`より前に置くと初期化順序違反（未初期化値の参照）になる——この構造的制約により単純な1行差替ではなく宣言順の並べ替えを伴った（実装判断、コンパイル成功で実測確認）。`createViewModelFactory`の`RecoveryViewModel`初期化子へ`locationService`／`clock = Clock.systemUTC()`を実引数化（P6-C1 scaffold既定値`UnavailableLocationService`／`Clock.systemUTC()`から実装へ差替）。<br><br>**②Mock撤去**: `mock/MockRecoveryFactory.kt`・`test/.../mock/MockRecoveryFactoryTest.kt`を削除し、空になった`mock/`ディレクトリ自体もmain/test双方で削除した（`rmdir`、副作用として計画書§6.3が予告したとおり`mock/`パッケージが消滅）。`AppContainerTest.resolveMockPackageDir()`は§9 #20のとおり修正: 3段fallbackいずれも`mock/`を発見できない場合、旧実装は`error()`でhard failしていたが、新実装は同じ3段fallbackで**必ず存在し続ける親パッケージ`com/actionstarter`**を解決し、その子として実在しない`mock`という`File`ハンドルを返す（`File.isFile`/`isDirectory`は対象が存在しなくても例外を投げず`false`を返すため、呼び出し側は引き続き正しく非存在を観測できる）。真にworking directory解決が破綻している場合（親パッケージすら見つからない）は従来どおり`error()`で即座に失敗させ、パス解決失敗によるサイレントな偽陽性を防ぐという元の設計意図を維持した。**tP6Di1_recoveryEngine_isBasicRecoveryEngineType**・**tP6Di2_mockRecoveryFactoryKtFile_doesNotExistUnderSrcMain**とも実測でRed→Green反転を確認（`AppContainerTest`6/6 Green、`build/agent-logs/p6c5-full.log`のJUnit XML実測）。<br><br>**③strings.xml・title/explanation結線**: `recovery_option_title_*`／`recovery_option_explanation_*`（`keep_all_steps`／`skip_optional_steps`／`skip_optional_and_important_steps`／`change_transport_mode`の4キー×2）＋`recovery_engine_error_message`の計9キーをja/en両`strings.xml`へ同時追加（ADR-0033）。`keep_all_steps`等3種の文言は旧`MockRecoveryFactory`がPhase 1〜5でユーザーに表示していた文言をそのまま踏襲し、`change_transport_mode`（D案）はPhase 6での新規文言。`RecoveryOptionText.kt`の`resolveRecoveryOptionTitle`/`resolveRecoveryOptionExplanation`を暫定フォールバック（全キーで`step_title_fallback`）から本結線へ差替え、`RecoveryScreen.kt`を`option.title`/`explanation`直接表示からこれら解決関数経由へ切替えた（`BasicRecoveryEngine`はtitle/explanationを常に空文字で生成するため、この結線がないと画面が空白表示になる——P6-C4申し送り①）。`resolveRecoveryOptionExplanation`の`eta`引数は意図的に未使用のまま維持（`RecoveryScreen`が既に候補ごとに独立したETA行を描画しており、説明文へ埋め込むと同一情報が重複表示されるため。設計判断としてKDocに明記）。`RecoveryViewModel`の`R.string.recovery_no_options_message`暫定転用（engine例外時のエラー表示）は`R.string.recovery_engine_error_message`へ差替済み（P6-C4申し送り④）。`RecoveryScreenTest.tRec2_threeOptions_allDisplayed`は`sampleOption`ヘルパへ`semanticAction`任意引数（既定値は従来どおり`title`由来、他5テストは無変更で成立）を追加したうえで、3候補を既知semanticActionキー（`keep_all_steps`／`change_transport_mode`／`skip_optional_steps`）で構成し、`option.title`との一致ではなく解決後の`stringResource`実テキストとの一致を検証する形へ更新（§4.2 U-6承認範囲、assertion強度は維持——「3件が個別に識別可能な文言で全件表示される」という検証意図は不変）。<br><br>**④LatenessDetector実配線＋無限ループ防止**: `ActionStarterNavHost`のexecution route、`executionServiceController.start(plan)`直後のプレースホルダ位置へ`LaunchedEffect(plan)`を1箇所追加し、`RecoveryContext`（`currentLocation`は`LatenessDetector.evaluate`が参照しないフィールドのためnull固定。`unfinishedSteps`＝`plan.steps`／`latestTravelEstimate`＝`Duration.between(departureTime, estimatedArrival)`の非負クランプ／`plannedDepartureTime`＝`plan.departureTime`）を構築して`LatenessDetector.evaluate()`を呼び、`WillMissEvent`ならRecoveryへ`navigate`する（ADR-0037）。§9エラーマップ#10（Recovery⇄Execution無限ループ）対策として、`rememberSaveable(plan.event.id) { mutableStateOf(false) }`によるone-shotガード（`hasAutoNavigatedToRecovery`）を追加: Recoveryの「Use this plan」は`popBackStack`で同一Execution backstack entryへ戻り、更新されたplanにより同一`LaunchedEffect(plan)`が再発火するが、本ガードにより自動遷移はこのExecution滞在中（`plan.event.id`でスコープ、Recovery適用は`event`を変更しないため往復中も同一キーのまま保たれる）で高々1回に制限される。「Simulate delay (debug)」ボタン起点の`onNavigateToRecovery`は本ガードと無関係の別経路のまま（§11.2.2）。`NavigationFlowTest`5/5 Green実測により、既存5シナリオ（`OnTrack`判定になるplanのため自動遷移が発火しない）が無改造のまま成立することを確認した。<br><br>**⑤useThisPlan→通知再スケジュール（Fable 5裁定、P5-C6申し送り③への回答）**: `RecoveryViewModel`のコンストラクタへ`notificationService: NotificationService? = null`を追加（既定値`null`によりコンストラクタ非互換を回避、`RecoveryViewModelTest`の全8ケースは無変更のままGreen維持）。`useThisPlan(optionId)`内、`recoveryPlanApplier.apply`直後・`sharedPlanViewModel.confirmPlan`直前に、`notificationService`が非nullなら`cancelAll(plan.event.id.toString())`→`schedule(updatedPlan)`を実行する結線を追加した（§34ガード維持——本結線は`useThisPlan`内部にあるため、ユーザーの「Use this plan」タップ起点でのみ発火し自動適用経路は作っていない）。`AppContainer.createViewModelFactory`は`notificationService`（既存プロパティ）を実引数として渡す。計画書に本項目の個別定義がなかったため（P5-C6完了時点でPhase 6未着手だったことに起因する新規申し送り事項）、既存の`AppContainer`公開プロパティのみを用いる範囲で設計した。<br><br>**⑥ADR起票**: P6-C1がscaffold KDoc（`recovery/BasicRecoveryEngine.kt`・`recovery/BasicRecoveryDefaults.kt`・`recovery/LatenessDetector.kt`）へ記録した計画書§7.9の6候補を`grep -rn "ADR" app/src/main/java/com/actionstarter/recovery/ app/src/main/java/com/actionstarter/features/recovery/`で回収し、**ADR-0032〜0037**としてDECISIONS.mdへ正式起票した（起票前に`grep -n "^### ADR-" DECISIONS.md`で実測最新がADR-0031であること、`grep -n "ADR-00[3-9][0-9]" docs/plans/phase5-notification-execution.md docs/plans/phase6-recovery-basic.md`で両計画書がADR-0032以降を予約していないことを確認済み）。内訳: ADR-0032＝候補生成の優先順位規則（S-6）、ADR-0033＝title/explanation空文字化（S-4、ADR-0018拡張）、ADR-0034＝id決定的生成（ADR-0017拡張）、ADR-0035＝transportModeのコンストラクタ供給（S-3）、ADR-0036＝Mock削除・完全昇格（ADR-0019拡張）、ADR-0037＝lateness detectionのフォアグラウンド限定境界（§7.6）。<br><br>**制約遵守の確認**: 変更した本番ファイルは`di/AppContainer.kt`・`features/recovery/RecoveryViewModel.kt`・`features/recovery/RecoveryOptionText.kt`・`features/recovery/RecoveryScreen.kt`・`navigation/ActionStarterNavHost.kt`・`res/values/strings.xml`・`res/values-ja/strings.xml`・`DECISIONS.md`（ADR-0032〜0037追加）・本計画書（本行）。削除した本番ファイルは`mock/MockRecoveryFactory.kt`。変更したテストファイルは`test/java/com/actionstarter/di/AppContainerTest.kt`（`resolveMockPackageDir()`のみ、§9 #20・§4.2 U-6で承認済み）・`test/java/com/actionstarter/features/RecoveryScreenTest.kt`（`tRec2`＋`sampleOption`ヘルパのみ、§4.2 U-6で承認済み）の2件。削除したテストファイルは`test/java/com/actionstarter/mock/MockRecoveryFactoryTest.kt`（計画書§6.3・§7.8で削除指定済み）。`recovery/`配下（`BasicRecoveryEngine.kt`／`BasicRecoveryDefaults.kt`／`LatenessDetector.kt`／`RecoveryPlanApplier.kt`／`RecoveryEngine.kt`）・`domain/model/`（`RecoveryContext.kt`／`RecoveryOption.kt`／`RecoveryPlan.kt`）・`AndroidManifest.xml`・`ActionStarterApplication.kt`・`MainActivity.kt`・`BasicRecoveryEngineTest.kt`等P6-C3/C4完了済みのテストファイル群はいずれも変更していない（読み取りのみ）。`docs/plans/phase3-routing-location.md`は変更していない。エミュレータ・adbは使用していない。git commitは行っていない。Gradleロック競合は発生しなかった（全実行が初回試行で成功、60秒リトライの発動は不要だった）。APIキー（`AIza`等）は出力・記録していない。
| **P6-C6** Refactor | `./gradlew build` ／ `lintDebug` エラー0の再実測。No giant Composable/ViewModel（§89）の確認 | domain/ui-implementer → quality-runner | **G4-JVM** |
| **P6-C7** 薄いG4-E | Simulate delay → Recovery到達・候補ETA表示を ja/en スクリーンショット取得（T-P6E2E-1）、Use this plan復帰の非ループ確認（T-P6E2E-2） | quality-runner | **G4-E** |

**C3/C4並列時の所有権規則**（Phase 1/2/4先例踏襲）: `recovery/` 配下・`di/AppContainer.kt`・`navigation/ActionStarterNavHost.kt` の既定所有者は domain-implementer のみ。ui-implementer はC4の間これらに一切触れず、必要が生じたら中断してFable 5へ報告する。

**着手前提**: P6-C1の着手前提条件はPhase 3クローズとする（本書冒頭・§3参照）。Phase 5とはP6-C1〜C4を並列実行可能だが、Phase 5のC6（統合）およびC7（Refactor）完了後に、Phase 6のC5（統合）を実行する必要がある（理由は§11.2）。

---

## §11. リスク

### 11.1 リスク一覧（R-1〜R-9）

| ID | リスク | 対応 |
|---|---|---|
| R-1 | Phase 5がADR-0014の再検討トリガー（「Phase 5着手時」）に従いHiltを導入すると `di/AppContainer.kt` が全面改稿され、Phase 6のC5差替と激突する | **Phase 5のC6（統合）およびC7（Refactor）完了後に、Phase 6のC5（統合）を実行**する直列化を徹底する（§11.2）。Phase 5計画書に「Hilt採否の判断はP5-C1で確定し、Phase 6へ即時共有する」ことを明記させる |
| R-2 | Phase 5が `ActionStarterNavHost` の execution route を全面改稿し、Phase 6が追加した lateness評価フックを消す | Phase 5計画書に **execution route の lateness評価フック（`LatenessDetector.evaluate` 呼び出し1箇所）を予約項目として明記**させる。Phase 6は「純関数API提供」に留め、呼び出し側の所有権をPhase 5に置く（§7.6） |
| R-3 | C5で追加する入場時自動遷移が、既存 T-NAV-1／T-NAV-2／T-E2E-1／T-E2E-3 を破壊する | トリガー条件を `ETA(A) > eventStart` に限定（§9 #9）。**P6-C2の実測**でこれらの既存テストが使うPlanが `OnTrack` に判定されることを確認する（**要検証**） |
| R-4 | Recovery ⇄ Execution の無限ループ（`popBackStack()` と入場時評価の組み合わせ） | one-shotガード（§9 #10）＋T-RECVM-8／T-P6E2E-2で回帰ロック |
| R-5 | `mock/` ディレクトリ消滅でT-P4DI-2群がhard fail（Phase 4 R-6の現実化） | §9 #20の修正をP6-C5の必須項目とし、TEAMS §2承認要請をG1で提出 |
| R-6 | `RecoveryPlan` へ失敗理由フィールドを追加すると、既存 `RecoveryPlanTest`（T-DM-7/8）が壊れる | デフォルト引数付きで追加し、既存コンストラクタ呼び出しを不変に保つ。**P6-C2の実測で確認**（要検証） |
| R-7 | 代替移動手段テーブル・既定 `TransportMode` に仕様上の根拠がなく、後日の変更箇所が分散する | `BasicRecoveryDefaults.kt` へ隔離し「仕様未定義プレースホルダ・Phase 10/12で置換」とKDoc明記（`BasicPlanningDefaults` のG-1/R-7先例） |
| R-8 | 「Phase 6完了」が「§62 Recovery通知の実装完了」と誤解される | G4完了報告に「Phase 6のlateness detectionはフォアグラウンド限定であり、通知としてのRecovery発火（§62 #3）はPhase 5の通知基盤に依存し対象外」と明記（Phase 4 R-8の先例） |
| R-9 | C3（Domain）とC4（UI）の並列で `features/recovery/` の共有部分が競合する | `RecoveryViewModel.kt`／`RecoveryUiState.kt`／`RecoveryScreen.kt`／`RecoveryOptionText.kt` はすべて **ui-implementer 所有**、`recovery/` 配下はすべて **domain-implementer 所有**と明確に分離する |

### 11.2 Phase 5との並列実行可否の判定

#### 11.2.1 判定: 並列推奨（条件付き）

| 観点 | 判定 | 根拠 |
|---|---|---|
| 新規作成ファイル | **完全に素** | Phase 6は `recovery/`・`features/recovery/`・`test/.../recovery/`。Phase 5は `services/notification/`・FGS・BootReceiver。**交差なし** |
| 既存変更ファイル | **完全に素** | Phase 6は `features/recovery/` 3ファイルのみ。Phase 5は `features/execution/` 3ファイル。**Phase 6は `features/execution/` を1バイトも変更しない** |
| 共有ファイル | **交差する（4件）** | `di/AppContainer.kt`・`navigation/ActionStarterNavHost.kt`・`res/values*/strings.xml`・`DECISIONS.md` |
| ビルド設定 | **交差しうる** | Phase 5がHiltを導入する場合 `libs.versions.toml`／`build.gradle.kts`／`AndroidManifest.xml` を改稿。**Phase 6はこれらに触れない** |

**結論**: **P6-C1〜C4（scaffold・Red・Green×2）はPhase 5と完全並列で実行してよい**。**P6-C5（統合ウィンドウ）のみ直列化が必須**であり、順序は**Phase 5のC6（統合）およびC7（Refactor）完了後に、Phase 6のC5（統合）を実行する**ことを推奨する（§4.2 U-9で承認済み）。理由は (a) Phase 5がHiltを導入すると `AppContainer` が全面改稿されPhase 6の1行差替が無効化される（R-1）、(b) Phase 5が execution route を改稿するため、Phase 6の lateness評価フックは**改稿後の形に対して**入れるほうが手戻りがない（R-2）。

#### 11.2.2 Execution の「5 min later」／「Done」連動部分の所有権整理

| 対象 | 所有 | Phase 6の扱い | 根拠（実測） |
|---|---|---|---|
| `ExecutionScreen.kt` の Done／5 min later ボタン | **Phase 5** | 読むのみ・変更しない | §69「Step start／Done／Snooze／next action／departure」がPhase 5の定義項目 |
| `ExecutionUiState.onDone` / `onPostpone` | **Phase 5** | 読むのみ・結線しない | `ActionStarterNavHost.kt:183-184` で本番は `null` 固定＝**現時点で死んだ経路**。ここへ結線すると死んだコードへの配線になる |
| `ExecutionViewModel.handlePostpone()`（+5分） | **Phase 5** | 読むのみ・変更しない | `ExecutionViewModel.kt:82-86` はプレースホルダstepのローカルコピーを更新するだけで `confirmedPlan` に反映されない。`ExecutionViewModel` 自体がNavHostから未使用（`AppContainer.kt:171-175` のKDocに明記） |
| ステップ進行に伴う `completedAt` の記録 | **Phase 5** | 読むのみ（`completedAt == null` で残準備を判定） | §30の `completedSteps` 供給元（S-5） |
| `LatenessDetector.evaluate()`（純関数API） | **Phase 6** | 実装・公開する | §70「lateness detection」・§13「遅延検知はBasic Engine担当」 |
| Done／Snooze遷移後の `evaluate()` **呼び出し** | **Phase 5** | Phase 6は実装しない。API提供のみ | §7.6の境界表。Phase 5計画書へ予約項目として申し送る |
| Execution route **入場時**の `evaluate()` 呼び出し | **Phase 6**（暫定・P6-C5でNavHostへ1箇所） | 実装する | Phase 5非依存で§70完成条件を満たす最小経路。Phase 5がroute改稿時に引き継ぐ |
| DEBUG「Simulate delay」ボタン | **Phase 1由来・現状維持** | 変更しない | T-NAV-3／T-E2E-2が依存（`NavigationFlowTest.kt:138`・`MainUxFlowTest.kt:63`） |

#### 11.2.3 Phase 5計画書への申し送り（Fable 5がPhase 5計画に反映すべき事項）

1. **`di/AppContainer.kt` のHilt採否をP5-C1で確定し、Phase 6へ即時共有する**（R-1）。
2. **`navigation/ActionStarterNavHost.kt` の execution route に、Phase 6の `LatenessDetector.evaluate()` 呼び出しフック（1箇所）を予約項目として明記する**（R-2）。
3. **Phase 5がstep progression（Done/Snooze/next action）を実装した各遷移後に `LatenessDetector.evaluate()` を呼び、`WillMissEvent` ならRecoveryへ割り込む結線を、Phase 5側の機能として計画に含める**（§7.6）。
4. **`res/values*/strings.xml` の編集はC5統合ウィンドウでのみ行い、両ロケール同時更新する**（`StringResourceParityTest` が検査）。

---

## §12. 未確認事項

以下V-1〜V-6はメモの記載どおり「要検証」のまま転記する。**Gradle実行が本メモ作成時点で禁止されていたため、いずれも実測値を持たない。** P6-C1／P6-C2で実測確定する。

| ID | 内容 |
|---|---|
| **V-1** | T-BRE-30 のオーバーフロー例外型。Phase 4 P4-C6実測で `BasicPlanningEngine` は `java.time.DateTimeException` と確定しているが、`BasicRecoveryEngine` の演算順序では別型になる可能性がある。P6-C1/C2で実測確定する |
| **V-2** | R-3（入場時自動遷移が既存 T-NAV-1／T-NAV-2／T-E2E-1／T-E2E-3 を破壊しないか）。**本メモ作成時点でGradle実行が禁止されているため未実測**。P6-C2で実測確定する |
| **V-3** | R-6（`RecoveryPlan` へのフィールド追加が `RecoveryPlanTest` T-DM-7/8 を壊さないか）。P6-C2で実測確定する |
| **V-4** | Phase 6着手時点のベースライン `:app:testDebugUnitTest` 件数。**未実測**（Gradle実行禁止のため）。P6-C1で記録する |
| **V-5** | 次のADR番号。`DECISIONS.md` の実測最新は **ADR-0023**（`### ADR-0023: RoutingException／ForegroundGateの判定式…`）。plan-doc-writerによる実測（`DECISIONS.md`のgrep）でも同じくADR-0023が最新であることを確認済み。Phase 3/5が本メモ作成後にADR-0024以降を起票する可能性があるため、P6-C1着手直前に再実測して確定する |
| **V-6** | Phase 5の計画書が存在しない（`docs/plans/` 実測: phase0〜phase4のみ）。本メモのPhase 5フットプリント想定は**仕様§69・§95・TEAMS §5からの推定であり、Phase 5計画書との突合は未実施**。plan-doc-writerによる実測（`ls docs/plans/`）では、本書作成時点でphase0〜phase4に加えphase3-routing-location.mdも存在することを確認したが、Phase 5計画書は依然として存在しない（V-6の主張と一致） |

いずれも「要検証（P6-C1/C2で確定）」として扱い、確定するまで本書の該当箇所（§8テストケース表・§10サイクル分解・§11リスク）の記述を最終と見なさない。

---

## §13. 申し送り

- 本計画書はandroid-planner作成のPhase 6計画メモ（§0〜§12、JSONL最終行`message.content[0].text`より抽出）を忠実に文書化したものである。計画メモにない機能・仕様を自己判断で追加していない。
- **承認状態の上書き**: Fable 5はU-1〜U-9のすべてを推奨案どおり承認済みである（2026-08-09、§4.2）。**メモ自身の§12引き渡し指示は「U-1〜U-9をユーザー承認待ちとして『未裁定』で列挙すること」であったが、Fable 5裁定が完了しているため、この指示を上書きして『裁定済み』として記載した**（本書冒頭・§4.0に明記のとおり、上書きはFable 5の指示によるものであり、plan-doc-writerの自己判断ではない）。
- **Geminiクロスレビューは実施済み（2026-08-09）**。CRITICAL指摘6件（G1）についてFable 5がすべて推奨案どおり採用する裁定を行い、本書へ反映した。これによりG1はFable 5 Pass1レビュー・Gemini Pass1レビューの双方が完了し通過した（§3）。
- P6-C1着手の前提条件はPhase 3クローズとする。Phase 5とはP6-C1〜C4を並列実行可、Phase 5のC6（統合）およびC7（Refactor）完了後に、Phase 6のC5（統合）を実行する（§3・§10・§11.2）。
- **転記時の再配置（構造上の判断であり、内容の追加ではない）**: メモの節構成は§0〜§12（末尾に見出しなしの検証済み事項の段落）であり、本書はこれを`docs/plans/phase4-basic-engine.md`の章立て（§0結論〜§13申し送り）へ組み替えた。対応関係は次のとおり。
  - メモ§0 結論ファースト → 本書§0（そのまま）
  - メモ§1 仕様原文の根拠 → 本書§1（そのまま）
  - メモ§2 スコープ → 本書§2（そのまま）
  - メモ§3 サイクル分解 → 本書§10
  - メモ§4 機能一覧 → 本書§5
  - メモ§5 設計の中心（5.1〜5.8） → 本書§7.1〜7.8
  - メモ§6 テストケース表（6.1〜6.8） → 本書§8.1〜8.8
  - メモ§7 エラー＆レスキューマップ → 本書§9（そのまま）
  - メモ§8 ファイルフットプリント宣言（8.1〜8.5） → 本書§6.1〜6.5
  - メモ§9.1 リスク（R-1〜R-9） → 本書§11.1
  - メモ§9.2 仕様の矛盾・未定義（S-1〜S-7） → 本書§4.1（U-1〜U-9の裁定と対で読めるようにするため）
  - メモ§9.3 ユーザー確認事項（U-1〜U-9） → 本書§4.2（裁定内容を追記）
  - メモ§9.4 未検証事項（V-1〜V-6） → 本書§12
  - メモ§10 ADR起票候補 → 本書§7.9（P6-C1サイクル表内の「ADR起票（§10）」という自己参照は、本書では§7.9を指すよう読み替えた）
  - メモ§11 Phase 5との並列実行可否の判定（11.1〜11.3） → 本書§11.2（11.2.1〜11.2.3）
  - メモ§12 plan-doc-writerへの引き渡し指示 → 本書には独立した節として転記せず（`docs/plans/phase4-basic-engine.md`にも対応する節は存在しない）、指示の内容は本書§3・§4・§12の構成に反映した
- **テストケース件数の数え直し**: §8の内訳（T-BRE 32件・T-LATE 10件・T-APPLY 7件・T-RECVM 8件・T-RECUI 8件・T-P6DI 2件・T-P6E2E 2件）を合計すると32+10+7+8+8+2+2=**69件**でメモの主張と一致する。区分別ではE1（T-BRE+T-LATE+T-APPLY）=32+10+7=**49件**、E2（T-RECVM+T-RECUI+T-P6DI）=8+8+2=**18件**、E3（T-P6E2E）=**2件**で、49+18+2=69と一致する。各テーブルの区分列（正常系／異常系／エッジ）を1件ずつ数え直した結果、正常系**31件**・異常系**14件**・エッジケース**24件**（31+14+24=69）となり、メモの主張（正常系31／異常系14／エッジケース24）と一致することを確認した。
- **エラー＆レスキューマップ行数の数え直し**: §9の表は#1〜#21の連番で**21行**あり、メモの主張と一致する。全21行についてハンドリング方法列に空欄がないことも1行ずつ確認した。
- **転記漏れの確認**: 転記元メモ§0〜§12の全項目を本書へ反映した。転記漏れは検出されなかった。
- 本書作成にあたり、plan-doc-writerは転記対象のandroid-planner計画メモに加え、既存ソースファイル（`RecoveryEngine.kt`／`RecoveryContext.kt`／`RecoveryOption.kt`／`RecoveryPlan.kt`／`RecoveryViewModel.kt`／`MockRecoveryFactory.kt`／`AppContainerTest.kt`の`resolveMockPackageDir()`定義部／`RecoveryScreen.kt`／`strings.xml`・`values-ja/strings.xml`／`mock/`ディレクトリ内容）を直接確認し、メモの記載内容（既存コード実欠陥6件、§45/§50/§51契約、`RecoveryPlan`のinit検証）と矛盾がないことを検証した。**唯一の軽微な相違点**として、欠陥2（`MockRecoveryFactory.kt:53,64,76`のtitle/explanation英語ハードコード）の引用行番号は実測では`explanation`行の行番号であり、対応する`title`行は1行上（52/63/75）である。欠陥の実質的内容には影響しない。また`DECISIONS.md`の最新ADR番号（ADR-0023、V-5の主張と一致）、`docs/plans/`にPhase 5計画書が存在しないこと（V-6の主張と一致）もあわせて実測確認した。§12のV-1〜V-6はメモの記載どおり「要検証」のまま転記し、Gradle実行による実測値は本書でも持たない。**本書作成作業ではproduction codeを一切変更していない（読み取りのみ）。**
