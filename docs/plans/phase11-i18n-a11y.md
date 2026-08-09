# Action Starter Android ― Phase 11 実装計画書：i18n／アクセシビリティ（通知権限リクエスト・contentDescription網羅・フォントスケール1.5x耐性・未配線文字列解消・ja/enパリティ監査）

**対象Phase**: Phase 11（仕様書§75 Phase 11「Localization」、§63 Accessibility、§6 Global-first設計、§7 国際化要件、§95.4 権限一覧表〔POST_NOTIFICATIONS行〕、§95.6 エラー＆レスキューマップ〔通知送信行〕、`docs/GOAL.md` E.i18n/アクセシビリティ・F.障害系カテゴリ）
**正仕様書**: `Action_Starter_Master_Specification_v2.0_Android.md`
**前提**: 本計画書の**作成**（doc-only）自体に前提条件はない。**P11-C1（実装着手）の前提条件はPhase 0〜6のクローズとする**——対象5画面（EventSelection／PlanReview／Execution／Departure／Recovery）が仕様書Phase 1〜6の全域にまたがるため（本書の計画内容そのものに対するレビュー可否とは別軸の着手条件、`docs/GOAL.md`のスコープ定義「Phase 0〜6 + Phase 11」と整合）。**本書作成時点でG4-E（実機/エミュレータ検証）が別エージェントにより並行して進行中であるため、本書はそれと衝突しないようdoc-onlyで作成した**（§2.3）。Phase 0〜6の具体的なクローズ状況の確認は本書の範囲外。
**起点**: 本計画書には転記元となるandroid-planner（Opus）計画メモが**存在しない**。Fable 5が直接指定したスコープ5項目（本書§2.1のF79〜F84に対応）と、plan-doc-writerが本セッションで実施したソースコード・仕様書のgrep/Read実測（引用箇所は各節に明記）に基づき、plan-doc-writerが直接起筆した一次計画書である。phase5/phase6計画書のような「メモの忠実な文書化」ではないため、本書の設計判断はすべてplan-doc-writer自身の実測と提案として起筆したものであり、Step 2（Fable 5裁定＋Gemini G1レビュー）を経て確定済みである（下記ステータス参照）。
**本書作成**: plan-doc-writer（Sonnet）、2026-08-09（初版）
**関連ハーネス文書**: `docs/TEAMS.md`（役割分担・PDCA・品質ゲートの正）、`docs/GOAL.md`（E.i18n/アクセシビリティ・F.障害系カテゴリの採点基準）、`DECISIONS.md`（ADR記録先。実測確認済みの最新確定ADRは**ADR-0037**〔`### ADR-0037: lateness detectionはフォアグラウンド限定とし…`、`DECISIONS.md:862`〕。本Phaseの決定はADR-0038以降として記録する想定）
**関連計画書**: `docs/plans/phase3-routing-location.md`（位置権限の3状態パターン＝`LocationPermissionState`の直接の参照元）、`docs/plans/phase5-notification-execution.md`（Execution劣化バナー・`NotificationService`契約の参照元、S-3/S-4裁定＝ADR-0026）、`docs/plans/phase6-recovery-basic.md`（章立て・様式の直接の参照元）

---

**ステータス: G1通過（2026-08-10）。** 本書はCLAUDE.md開発ワークフローStep 1（実装計画書の作成）の成果物であり、Step 2（アーキテクトレビュー：Claude Opus＋Gemini `gemini-3.5-flash`固定によるダブルレビュー）を経て、Fable 5裁定S-1〜S-9（§12）とGemini G1（`gemini-3.5-flash`）CRITICAL指摘4件（§7.1・§7.3・§7.6・§8.4）を本書へ反映済みである。曖昧だった設計判断・製品判断はすべて§12「Fable 5確認事項」の裁定列に記録済みであり、自己判断のまま確定していない事項は残っていない。

本計画書はFable 5が指定した5つのスコープ項目（①通知権限リクエストフロー、②contentDescription網羅、③フォントスケール1.5x耐性、④未配線文字列2件の解消、⑤ja/en文言パリティ監査）に基づき、plan-doc-writerが`app/src/`配下の実装状態を直接grep/Readで実測したうえで作成した。**本書作成作業ではproduction codeを一切変更していない（読み取りのみ）。他の計画書ファイル（phase5/phase6等）にも一切触れていない。git commitも行っていない。**

---

## §0. 結論ファースト

grep/Read実測（本書全体で引用）により、5項目の現状と設計方針は以下のとおり確定した。

1. **通知権限リクエスト（機能ギャップ、最重要）**: `POST_NOTIFICATIONS`は`AndroidManifest.xml:32`で宣言済みだが、実行時リクエスト（`ActivityResultContracts.RequestPermission().launch(...)`相当の呼び出し）は`app/src/main`のどこにも存在しない（`grep -rn "POST_NOTIFICATIONS" app/src/main/java`で確認、既存の`androidTest/.../NotificationExecutionE2ETest.kt:63-65`のコメントも同じ事実を自己申告済み）。既存コードは`ExecutionViewModel.isNotificationPermissionDenied()`（読み取り専用の`permissionGate.isGranted()`照会）と`ExecutionScreen`の劣化バナー（テキスト表示のみ、アクションなし）までは実装済み（Phase 5 F52の一部）。AndroidManifest.xmlのコメント（`AndroidManifest.xml:27-31`）と§95.4権限表は要求タイミングを「Execution Plan確定時（PlanReview「Start」）」と明記しており、本書はNavHostの`PlanReview` route（`ActionStarterNavHost.kt:203-221`、`onNavigateToExecution`ラムダ）へ`RequestPermission`launcherを追加し、Execution側の劣化バナーへ設定導線ボタンを追加する設計を定義する（§7.1）。リクエスト方式は事前説明カードを挟まず直接システムダイアログを表示する（§12 S-1裁定）。また遷移タイミングはGemini G1 CRITICAL指摘を受け、`launch()`直後に同期的に`navigate()`する設計からlauncherのコールバック内でnavigateする設計へ修正済み（§7.1）。
2. **contentDescription網羅**: grep実測で`contentDescription`はアプリ全体（`app/src`）で**0件**。加えて`Icon(`／`IconButton(`／`Icons.`も**0件**（通知の`setSmallIcon`2件のみ、Compose UI要素ではない）。既存の対話要素はすべて`Text`を子に持つ`Button`／`TextButton`／`OutlinedTextField`であり、Composeの既定`mergeDescendants`挙動により基本的な読み上げは機能する。したがって実質的なギャップは「アイコンにラベルがない」ことではなく、①色のみに依存する警告状態（§63 color-only禁止）、②複合情報（`EventRow`・`PlanReviewStepRow`・Recovery候補行）の読み上げ単位のグルーピングと文脈付与、③`ExecutionScreen.kt:86`・`RecoveryScreen.kt:99`に既存する**空の**`semantics(mergeDescendants = true) {}`（scaffold済みだが中身が空のまま）の完成、の3点である（§7.2）。副次的発見として、`RecoveryScreen.kt`の候補行は選択状態（`selectedId`）を視覚的にもセマンティクス的にも一切表示しておらず、これは晴眼者にも影響する既存UXギャップであった。§12 S-5裁定によりこのギャップは本Phaseで解消する対象に含め、視覚的インジケータ（背景色`primaryContainer`またはボーダー）とTalkBack向け`contentDescription`の双方を実装する設計とする（§7.2）。
3. **フォントスケール1.5x耐性**: grep実測で`fontScale`関連コードはJVM/Robolectric/instrumentedいずれのテストにも**0件**（総ゼロからの新設）。Context7で確認したJetpack Compose公式テストAPI `androidx.compose.ui.test.DeviceConfigurationOverride.FontScale(1.5f)`（`@ExperimentalTestApi`、`ui-test`アーティファクト。本プロジェクトは`androidx-compose-ui-test-junit4`を`testImplementation`済みのため追加依存は不要と推定するが要検証）を用いたRobolectric Composeテストで5画面を検証する設計とする。実機側は`adb shell settings put system font_scale 1.5`によるG4-E補遺（§8.7）。
4. **未配線文字列**: 名指しされた2件に加え、lintのUnusedResources残3件（`execution_placeholder_step_title`／`location_permission_denied_message`／`travel_time_manual_apply_button`。Phase 3〜6を通じ一貫して不変、`docs/plans/phase5-notification-execution.md:712`・`docs/plans/phase6-recovery-basic.md`P6-C5行で実測記録が独立に2回確認されている）は**この3件で一致する**。個別のコード実測（`StepTitle.kt`・`TravelTimeInput.kt`・`DepartureScreen.kt`を直接Read）により、`execution_placeholder_step_title`は`resolveStepTitle`のelse分岐（`step_title_fallback`）に置き換わった死蔵リソース、`travel_time_manual_apply_button`は「値変更時に即時反映」というP3-C5の確定済み設計（`TravelTimeInput.kt`にApplyボタン自体が存在しない）により不要になった死蔵リソースと判断できる。`location_permission_denied_message`のみ、`DENIED`状態の説明文として今も意味を持つため配線を推奨する。結論: **2件削除・1件配線**でUnusedResources 3件はすべて解消する（§7.4・§7.5）。
5. **ja/enパリティ監査**: 既存`StringResourceParityTest.kt`は**すでに全キー横断**（`enStrings.keys`と`jaStrings.keys`の完全一致検証、T-I18N-1）で「全キーの両ロケール存在」を機械化済みであり、これは新設ではなく現状追認である。実測（`app/src/main/res/values{,​-ja}/strings.xml`のキー数はそれぞれ104件、`diff`でキー集合完全一致を確認済み）でも現状パリティは崩れていない。追加検討点は、en/ja値が完全一致するキー（実測2件: `app_name`・`manual_event_picker_confirm_button`＝ともに意図的な一致と判定可能）を検出する新規アサーションであり、§12 S-4裁定により追加が必須と確定した。allowlistはテスト内の明示定数リスト（各エントリに意図的一致の理由コメント必須）で運用する（§7.6・§12 S-4）。

---

## §1. 仕様原文の根拠（引用箇所）

| § | 引用（要点） | 本Phaseでの使い方 |
|---|---|---|
| §75 Phase 11 | 「Localization。最低：Japanese／English。英語環境で一通り動作確認」 | ja/enパリティ監査（§7.6）の直接根拠 |
| §63 Accessibility | 「フォントスケール追従（Dynamic Type相当）」「TalkBack（VoiceOver相当）」「reduced motion」「high contrast」「color-only information禁止」 | contentDescription網羅（§7.2）・フォントスケール耐性（§7.3）の直接根拠。reduced motion/high contrastは`docs/GOAL.md` Eカテゴリの採点文言に明記がないため本書スコープ外と判断（§12 S-8） |
| §6 Global-first設計 | 「コード・データモデル・AI設計は最初から海外展開可能にする（必須要件）」 | ja/enパリティ監査・未配線文字列処置の方針的根拠 |
| §7 国際化要件 | 「UI文字列の直接ハードコード禁止」「`stringResource()`経由で参照」 | 未配線文字列の処置（配線／削除、直書き禁止）の直接根拠 |
| §95.4 権限一覧表 | POST_NOTIFICATIONS行：「取得タイミング＝通知が必要になる最初のExecution Plan確定時」「拒否時：通知を送らずアプリ内表示のみにフォールバック」（`Action_Starter_Master_Specification_v2.0_Android.md:2568`） | 通知権限リクエストフローのトリガー地点（PlanReview「Start」）の直接根拠 |
| §95.6 エラー＆レスキューマップ | 通知送信行：「拒否時は通知送信をスキップしアプリ内表示のみに切替え、設定導線を提示し後から許可された場合は自動的に通知を再開する」（`同ファイル:2588`） | 設定導線ボタン＋ON_RESUME再評価（§7.1 F80）の直接根拠。「自動的に通知を再開する」はON_RESUME時の再照会なしには成立しない |
| `docs/GOAL.md` Eカテゴリ | 「ja/en全画面切替で文字列欠落なし・レイアウト破綻なし。主要UI要素にcontentDescription、フォントスケール1.5倍で崩れなし」（10点） | 本Phaseの直接の採点基準。§8のテスト・§8.7のゲート検証手順が証拠になる |
| `docs/GOAL.md` Fカテゴリ | 「カレンダー/位置/通知権限拒否…の実装＋テスト」（10点、仕様§95エラーマップ該当） | 通知権限拒否時のフォールバック実装（F79/F80）はFカテゴリの証拠も兼ねる |

---

## §2. スコープ

### 2.1 やること（F79〜F84）

- **F79** `PlanReview`の「Start」タップ時にPOST_NOTIFICATIONS実行時権限リクエスト（`RequestPermission`launcher）を発火させる本番結線
- **F80** 通知権限拒否時の劣化バナーへの設定導線ボタン追加＋Execution画面のON_RESUME時の権限状態再評価（Settingsから戻った際にバナーが自動的に消える）
- **F81** EventSelection／PlanReview／Execution／Departure／Recoveryの5画面へcontentDescription／semantics付与（アイコンラベルではなく、警告状態の非視覚的伝達と複合情報のグルーピング）＋Recovery候補行の選択状態への視覚的インジケータ追加（§12 S-5裁定、背景色`primaryContainer`またはボーダー、`Modifier`1行規模）
- **F82** 5画面のフォントスケール1.5x耐性（JVM/Robolectric Composeテスト基盤の新設＋レイアウト調整）
- **F83** 未配線文字列・UnusedResources全3件の処置（`execution_placeholder_step_title`／`travel_time_manual_apply_button`の削除、`location_permission_denied_message`の配線）
- **F84** `StringResourceParityTest`の拡張検討（en/ja値完全一致検出）とKDoc陳腐化の是正

### 2.2 やらないこと（明示）

- **src/への実装着手そのもの**: 本書はdoc-onlyであり、上記F79〜F84のコード実装はP11-C1（Phase 0〜6クローズ後）まで行わない（§2.3）。
- **Phase 7〜10領域**: `ai/`（Local AI）・`PersonalExecutionProfile`・Room永続化には一切触れない。F番号もF79から連番するが、Phase 7〜10がまだ計画書を持たないため空き番号帯の予約は行わない（§12 S-7裁定により予約なしの連番を採用することが確定）。
- **新規AndroidManifest権限の追加**: POST_NOTIFICATIONS含む全権限は宣言済み（実測、§6.3）。Manifestの変更は不要。
- **高コントラストモード・reduced motionの実装**: §63は列挙するが`docs/GOAL.md` Eカテゴリの採点文言に明記がないため対象外とする（§12 S-8裁定により確定）。
- **exact alarm劣化バナーへのワンタップ導線追加**: Phase 5 S-4裁定（`ADR-0026`）は「バナー＋ワンタップ導線の中強度」としていたが実装は文言のみで導線が未実装というギャップが別途存在する（`ExecutionScreen.kt:131-140`実測）。これは通知**権限**ではなくexact alarm**設定**の話であり、`docs/GOAL.md` Fカテゴリ（障害系）寄りの課題のため本書のスコープには含めない（§11 R-6で申し送り）。
- **`travel_time_manual_apply_button`削除に伴うUX変更**: 削除は「使われていない文字列リソースを消す」だけであり、Apply方式への設計変更（新規ボタン追加）は行わない。

### 2.3 前提・制約（G4-E並行制約）

- 本書作成時点で、Phase 5または Phase 6（あるいは双方）のG4-E（実機/エミュレータ検証）が別エージェントにより並行して進行中である（Fable 5指定）。本書はこれと衝突しないよう**doc-onlyで作成し、`docs/plans/phase11-i18n-a11y.md`以外のファイルには一切触れていない**。
- **P11-C1（scaffold、実装着手）は、Phase 0〜6のクローズ（G4-JVM・G4-E双方の完了）を前提とする。** 対象5画面すべてがPhase 1〜6の成果物であり、フォントスケール・contentDescriptionの変更はレイアウト・testTag構造に触れるため、並行中のPhase実装と衝突するリスクが高い（`docs/plans/phase6-recovery-basic.md`§11.2「Phase 5との並列実行可否の判定」と同種の懸念）。
- 本書の記述する「新規作成」「変更」ファイルは**すべて設計であり、本セッションでは一切作成・変更していない**。

---

## §3. ゲート

`docs/TEAMS.md`§6に基づきG1〜G4を適用する。G4は**G4-JVM**と**G4-E**の2段階とする（Phase 1〜6の先例踏襲）。

- **G1（計画承認）**: 本計画書＋エラー＆レスキューマップ（§9）＋Fable 5 Pass1レビュー記録。Step 2（アーキテクトレビュー：Opus＋Gemini `gemini-3.5-flash`）を実施し、§12のFable 5確認事項（S-1〜S-9）全件の裁定とGemini G1 CRITICAL指摘4件の反映が完了した。**本書は2026-08-10付でG1通過済み。**
- **G2（Red確認）**: P11-C2でtest-writerが作成したfailingテスト（§8、41件）をquality-runnerが実測する。
- **G3（Green確認）**: P11-C3（Green）完了時点での実測。
- **G4-JVM（Phase 11完了・JVM側）**: P11-C5完了時点。`./gradlew build`成功・対象範囲のJVM/Robolectric全テストPass・`lintDebug`エラー0・**UnusedResources警告0件**（§7.5）を実測する。
- **G4-E（Phase 11完了・実機/エミュレータ側）**: P11-C4完了時点。§8.7「ゲート検証手順」（実機TalkBack読み上げ確認・実機fontScale=1.5目視確認・ja/enスクリーンショット取得）を実施する。**G4-E未達のままリリース判定へ進むことを禁止する**（`docs/plans/phase2-calendar.md`§3以降の先例踏襲）。

Phase 11は`docs/GOAL.md`の対象スコープの最終要素であるため、Phase 11のG4-E達成は/goal採点（Eカテゴリ）の直接証拠となる。

---

## §4. 承認状態

Fable 5裁定S-1〜S-9済み＋Gemini G1（gemini-3.5-flash）CRITICAL 4件反映済み（2026-08-10）→ **G1通過**。本書はStep 1成果物として起筆され、Fable 5 Pass1（CRITICAL）レビューに相当するS-1〜S-9の裁定（§12）と、GeminiクロスレビューのCRITICAL指摘4件（§7.1の非同期タイミング設計、§7.6のallowlist具体化、§7.3／§8.4の`@OptIn(ExperimentalTestApi::class)`必須化）の反映が完了した。

---

## §5. 機能一覧（F79〜F84）

| ID | 機能 | 仕様根拠 | 備考 |
|---|---|---|---|
| F79 | POST_NOTIFICATIONS実行時権限リクエストのNavHost本番結線 | §95.4 | `PlanReview`「Start」タップ地点（`ActionStarterNavHost.kt:209-221`）。§12 S-1裁定により事前カードなしの直接リクエスト方式に確定。遷移タイミングはGemini G1 CRITICAL指摘反映によりlauncherコールバック内へ変更（§7.1） |
| F80 | 通知拒否時の設定導線ボタン＋Execution ON_RESUME再評価 | §95.6 | 既存`ExecutionDegradationBanners`（`ExecutionScreen.kt:131-157`）へボタン追加。§12 S-9裁定によりON_RESUME再評価をスコープに含めることが確定 |
| F81 | 5画面へのcontentDescription／semantics付与 | §63 | アイコンラベルではなく警告状態の非視覚的伝達＋複合情報のグルーピング（§7.2）＋Recovery選択行の視覚的インジケータ（§12 S-5裁定） |
| F82 | 5画面のフォントスケール1.5x耐性 | §63 | `DeviceConfigurationOverride.FontScale`によるRobolectric Composeテスト新設（`@OptIn(ExperimentalTestApi::class)`必須、Gemini G1 CRITICAL指摘反映。§7.3） |
| F83 | 未配線文字列・UnusedResources全3件の処置 | §7 | 2件削除・1件配線（§7.4・§7.5） |
| F84 | `StringResourceParityTest`の拡張検討 | §75、§7 | en/ja値完全一致検出アサーションの追加が§12 S-4裁定により確定（allowlist方式、§7.6） |

---

## §6. フットプリント

### 6.1 新規作成（`app/src/`起点、実装はP11-C1以降）

| パス | 内容 | 担当 |
|---|---|---|
| `test/java/com/actionstarter/navigation/NotificationPermissionRequestTest.kt` | T-P11N-1〜10（F79/F80） | test-writer |
| `test/java/com/actionstarter/features/AccessibilitySemanticsTest.kt` | T-P11A-1〜10（F81、5画面横断） | test-writer |
| `test/java/com/actionstarter/features/FontScaleResilienceTest.kt` | T-P11F-1〜8（F82、5画面横断） | test-writer |

新規のproductionファイルは想定していない（既存5画面・NavHost・ViewModel1件への追記で完結する設計。§88に照らし新規クラスの追加は必要最小限に留めた）。

### 6.2 既存ファイルの変更（Phase 11専有）

| パス | 変更内容 | 担当 |
|---|---|---|
| `main/.../navigation/ActionStarterNavHost.kt` | PlanReview route: `requestPermissionLauncher`（`RequestPermission`、POST_NOTIFICATIONS）追加（F79）。Execution route: `onOpenNotificationSettings`結線＋ON_RESUME用`DisposableEffect`追加（F80） | ui-implementer |
| `main/.../features/execution/ExecutionScreen.kt` | `onOpenNotificationSettings: () -> Unit = {}`引数追加、`ExecutionDegradationBanners`へ設定導線ボタン追加（F80）。ステップ表示・劣化バナーへcontentDescription付与（F81） | ui-implementer |
| `main/.../features/execution/ExecutionViewModel.kt` | ON_RESUME再評価用の軽量メソッド追加（例: `refreshDegradationState()`、F80）。KDoc中の`execution_placeholder_step_title`参照を`step_title_fallback`経由の実態へ是正（F83、ロジック変更なし） | ui-implementer |
| `main/.../features/departure/DepartureScreen.kt` | `location_permission_denied_message`をDENIED状態の説明文として描画追加（F83）。`TravelTimeInput`・`LocationPermissionRationaleCard`・設定導線ボタンへcontentDescription付与（F81） | ui-implementer |
| `main/.../features/eventselection/EventSelectionScreen.kt` | `EventRow`のグルーピングsemantics＋contentDescription付与（F81） | ui-implementer |
| `main/.../features/planreview/PlanReviewScreen.kt` | `PlanReviewStepRow`のグルーピングsemantics＋contentDescription付与（F81） | ui-implementer |
| `main/.../features/recovery/RecoveryScreen.kt` | 候補行の既存の空`semantics(mergeDescendants = true) {}`へcontentDescription（選択状態を含む）を実装＋選択中の行への視覚的インジケータ（背景色`primaryContainer`またはボーダー）を追加（F81、§12 S-5裁定） | ui-implementer |
| `main/.../res/values/strings.xml` / `values-ja/strings.xml` | `execution_placeholder_step_title`・`travel_time_manual_apply_button`削除。新規contentDescription用文言・設定導線ボタン文言を追加（F81/F83） | ui-implementer |
| `test/java/com/actionstarter/i18n/StringResourceParityTest.kt` | T-P11S-1/2/4／T-P11P-1〜5追加。KDocの陳腐化した記述（「2キーのみ」）を是正（F83/F84） | test-writer |
| `test/java/com/actionstarter/features/DepartureRoutingScreenTest.kt` | `tDep2_5_phase3StringKeys...`の`phase3Keys`リストから削除2キーを除外。T-P11S-3/5追加（F83）。**既存テスト変更のためTEAMS§2承認要請対象** | test-writer |

### 6.3 共有ファイル

- **`res/values/strings.xml` / `res/values-ja/strings.xml`**: 本Phase最大の共有ファイル。5スコープ項目すべてが編集対象（削除2キー・配線1キー・新規contentDescription/ボタン文言）。`StringResourceParityTest`（T-I18N-1〜3、T-P11P-1〜5）が両ファイルの同時更新を機械的に守る。
- **`AndroidManifest.xml`**: **変更不要と判断する**。POST_NOTIFICATIONSは既に宣言済み（`AndroidManifest.xml:32`、`<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`）であり、本書が追加する実行時リクエストはこの既存宣言を前提として動作する。新規権限は発生しない（実測確認済み）。
- **`navigation/ActionStarterNavHost.kt`**: 共有ファイル。PlanReview route・Execution route双方に手を入れる（§6.2）。**「PermissionRequiredカード」の新規結線は不要と判断する**——POST_NOTIFICATIONSの要求タイミングは§95.4・Manifestコメントより「PlanReview『Start』時点」と定義済みであり、EventSelection（カレンダー）／Departure（位置情報）のように「画面へ到達した時点でまだ権限交渉が済んでいない」状態がUI上に観測されない（Startタップと同時にリクエストが発火する設計のため、`EventSelectionUiState.PermissionRequired`や`LocationPermissionState.NOT_REQUESTED`に相当する画面状態がExecution側には存在しない）。「Startタップで直接ダイアログを出す」か「事前説明カードを挟む」かは§12 S-1裁定により直接ダイアログ方式（事前カードなし）に確定した。

### 6.4 非重複宣言（他Phase領域への不可侵）

- **Phase 7〜10領域**: `ai/`・`PersonalExecutionProfile`・Room永続化には一切触れない。
- **Phase 5/6のドメインロジック**: `services/notification/`・`recovery/`配下のロジック（`AlarmScheduler`・`BasicRecoveryEngine`等）は一切変更しない。本書が触れるのは`RecoveryScreen.kt`のUI層（semantics付与）のみ。
- **他Phaseの計画書**: `docs/plans/phase0〜10-*.md`のいずれにも触れない（本書自身以外は読み取りのみ）。

---

## §7. 契約・設計

### 7.1 通知権限リクエストフロー（F79/F80）

**現状（実測）**: `ActionStarterNavHost.kt:203-221`のPlanReview route composableは、「Start」タップで`viewModel.confirmAndStart()` → `notificationService.schedule(plan)` → `navController.navigate(Execution)`の3行を実行する。POST_NOTIFICATIONSのリクエストはこの経路のどこにも存在しない。`ExecutionViewModel.kt:268-269`の`isNotificationPermissionDenied()`は`permissionGate.isGranted(POST_NOTIFICATIONS_PERMISSION)`（読み取り専用）を呼ぶのみで、権限を積極的に要求することはない。

**設計（Gemini G1 CRITICAL #1反映により非同期タイミングを再設計）**: `EventSelectionRoute`（`ActionStarterNavHost.kt:367-375`、単一`RequestPermission`launcher）と同型のlauncherをPlanReview route composableへ追加する。ただし旧設計案（「Startタップ→`launch()`直後に同期的に`navigate()`」）は採用しない。`launch()`のコールバックは`ActivityResultRegistry`経由で非同期に発火する一方、直後の`navigate()`はボタンのクリックハンドラ内で同期的に実行されるため、権限ダイアログの表示・消滅と画面遷移アニメーションが競合して二重に描画されうる。さらに`navigate()`によってPlanReview routeのコンポジションが破棄されるタイミングと`launch()`のコールバック解決タイミングが競合すると、`rememberLauncherForActivityResult`が依拠する`ActivityResultRegistry`への登録が不安定化し、コールバックが失われる・意図しないコンポーザブルへ配信される等の不具合を招くライフサイクル上のリスクがある。したがって本書は、画面遷移をlauncherの**コールバック内**へ移し、権限フローが確定してから遷移する設計に変更する。

```kotlin
val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
) {
    // 許可・拒否（再リクエスト可）・拒否（永続）のいずれの結果でも、コールバックは即時発火する（要検証P11-P2）。
    // 結果は分岐せず、ExecutionViewModel側が都度isGranted()で再照会するため
    // （DepartureRouteのrequestLocationPermissionLauncherと同じ設計判断、§7.3先例踏襲）、
    // ここでは遷移のみを行う。
    navController.navigate(Destinations.Execution.route)
}

// PlanReviewScreen(onNavigateToExecution = { ... })内:
viewModel.confirmAndStart()
// アラームは権限の有無と無関係に登録する。POST_NOTIFICATIONSが対象とするのは
// 「通知の表示」のみであり、schedule()自体は権限とは独立して成功する（Phase 5既存の設計を踏襲）。
uiState.plan?.let { plan -> appContainer.notificationService.schedule(plan) }
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    // API 33+のみ実行時リクエストが必要。遷移はlauncherのコールバック内で行う（上記）。
    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
} else {
    // API 33未満はPOST_NOTIFICATIONSが概念上存在しないため、launcherを介さず直接遷移する。
    navController.navigate(Destinations.Execution.route)
}
```

API 33未満を直接遷移に分岐させた理由は、ライフサイクルの安全性の観点で「不要な非同期コールバックへの依存を増やさない」ことを優先したためである。`ActivityResultContracts.RequestPermission()`はAPI 33未満の端末（本アプリの`minSdk = 26`、`app/build.gradle.kts:29`実測）に対してはシステムダイアログを表示せず即座に`true`をコールバックする既知の設計であり、この事実だけを見ればSDKバージョン分岐なしに`launch()`を常に呼んでも動作上は成立する。しかしこの場合でもコールバックの発火自体は`ActivityResultRegistry`経由の非同期ディスパッチに委ねられる（同期的にインラインで返るわけではない）ため、API 33未満でも上記のライフサイクル競合リスクが理論上残る。本設計はAPI 33未満では権限要求そのものが不要（概念上存在しない）であることを踏まえ、launcherを介さず直接`navigate()`する経路を明示的に分けることで、この非同期依存を意図的に排除する（要検証P11-P2は、API 33+経路における「許可済み・永続拒否時もコールバックが即時発火する」という前提の実測に絞られる）。

**PermissionGateの2値契約とONResume再評価（F80）**: `PermissionGate.isGranted(permission: String): Boolean`（`services/permission/PermissionGate.kt`）は許可/不許可の2値のみを返す契約であり、「拒否（再度リクエスト可能）」と「拒否（今後表示しない＝永続拒否）」を区別しない。これはDeparture/EventSelectionの既存3状態パターン（`NOT_REQUESTED`/`DENIED`/`GRANTED`、`shouldShowRequestPermissionRationale`を使わない）と同じ意図的な簡略化であり、本書もこれを踏襲する（§9のエラーマップで4状態を提示しつつ、UI上は2状態＝「許可」「未許可（理由を問わず設定導線を提示）」に集約する）。

Departure（`DepartureRoute`、`ActionStarterNavHost.kt:476-485`）・EventSelection双方が備える「Settingsから戻ると自動的に最新の権限状態へ復帰する」ON_RESUME再照会（`DisposableEffect`＋`LifecycleEventObserver`）は、現在Execution routeには存在しない（`ActionStarterNavHost.kt:223-323`実測、`LaunchedEffect(plan)`はFGS起動とlateness評価のみ）。§95.6エラーマップの「後から許可された場合は自動的に通知を再開する」という要求はこの再照会なしには成立しないため、本書はExecution routeにも同型の`DisposableEffect`を追加し、`ExecutionViewModel`へ軽量な再評価メソッド（`currentStep`／`currentStepIndex`は変更せず劣化フラグ3種のみ再計算する）を追加する設計とする。

**PlanReviewViewModelは変更しない**: 実行時リクエストのトリガーはNavHostのComposable内（Compose層）に閉じ、`PlanReviewViewModel`のコンストラクタ・状態には手を入れない（既存の`onRequestCalendarPermission`／`onRequestLocationPermission`がいずれもViewModel非経由でNavHost内`launch()`を直接呼ぶ設計と一致させる。§88に照らし不要な複雑化を避けた）。

### 7.2 contentDescription／semantics設計方針（F81）

grep実測（§0-2）のとおり、対象はアイコンラベルではなく次の3種：

1. **警告状態の非視覚的伝達（§63 color-only禁止）**: `ExecutionDegradationBanners`の3バナー（`ExecutionScreen.kt:131-157`、いずれも`color = MaterialTheme.colorScheme.error`のみで警告であることを示す）へ`Modifier.semantics { contentDescription = "警告: " + ... }`相当を付与し、色を認識できないユーザーにも「これは警告である」ことを伝える。
2. **複合情報のグルーピング**: `EventRow`（`EventSelectionScreen.kt:273-`）・`PlanReviewStepRow`（`PlanReviewScreen.kt:122-`）・Recovery候補行（`RecoveryScreen.kt:96-122`）はいずれも複数の`Text`を1つの視覚的な行として描画するが、TalkBackは既定で各`Text`を個別のスワイプ停止点として読み上げる。`Modifier.semantics(mergeDescendants = true) { contentDescription = "..." }`で1行1停止点＋文脈のある読み上げ文（例: 「09:15 準備を始める」「Keep all steps、到着予定10:06、タップして選択」）へ統合する。
3. **既存の空semanticsブロックの完成**: `ExecutionScreen.kt:86`・`RecoveryScreen.kt:99`は`semantics(mergeDescendants = true) {}`という空ブロックがすでに存在する（過去のPhaseでscaffoldされたが中身が実装されなかったもの）。本Phaseはこれらへ`contentDescription`を実装する。

**RecoveryScreenの選択状態の視覚的インジケータ（§12 S-5裁定）**: `RecoveryScreen.kt:66-100`の`selectedId`（仮選択中の候補ID）は、色・枠線・アイコン等いずれの視覚的インジケータも持たない——晴眼者も現在どの候補が選択中か画面から判別できない。§12 S-5裁定によりこのギャップは本Phaseのスコープに含める（§2.1のF81へスコープ化・§2.2の対象外リストからは削除済み）。TalkBack向けに`contentDescription`へ選択状態（例:「選択中」）を含める設計に加え、選択中の候補行に対し視覚的にも識別できるインジケータ（`Modifier.background(color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)`相当、またはボーダー。`Modifier`1行規模の最小差分）を実装する。色のみに依存しない設計とするため視覚的インジケータとcontentDescriptionを併用し、§63のcolor-only禁止にも同時に適合させる。T-P11A-11（§8.3）で機械検証する。

**回帰保護**: `mergeDescendants`の追加はComposeのsemanticsツリー構造を変えるため、既存の`testTag`ベースのアサーション（`onNodeWithTag`）が`useUnmergedTree = true`を要するようになる可能性がある（`PlanReviewStepDisplayTest.kt`の先例KDocが同種の注意点を明記済み）。T-P11A-10で既存テストの回帰有無を確認する。

### 7.3 フォントスケール1.5x耐性（F82）

**技術選定**: Context7で確認したJetpack Compose公式テストAPI（`developer.android.com/develop/ui/compose/testing/common-patterns`）。

```kotlin
@OptIn(ExperimentalTestApi::class)
@Test
fun t_fontScale_executionScreen_staysDisplayedAt1_5x() {
    composeTestRule.setContent {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
            ExecutionScreen(uiState = ...)
        }
    }
    // assertIsDisplayed() 等、本節「検証方法」のアサーションへ続く
}
```

`DeviceConfigurationOverride`は`androidx.compose.ui.test`パッケージの`@ExperimentalTestApi`マーカーが付与されたAPIである。`PlanReviewStepDisplayTest.kt`が既に採用している「同一コンポジション内で設定を差し替える」方式（`CompositionLocalProvider`によるロケール切替）と同系統の、Compose公式のテスト専用オーバーライド機構である。既存の`androidx-compose-ui-test-junit4`依存（`ui-test`アーティファクトを推移的に含む）で利用できると推定する。**Gemini G1 CRITICAL指摘により、`@ExperimentalTestApi`を用いる全テストメソッド（`FontScaleResilienceTest.kt`の該当箇所）へ`@OptIn(ExperimentalTestApi::class)`の付与を必須条件として明記する**（コンパイルエラー防止。上記コード例参照）。正確な最小Composeバージョン・importパス（`androidx.compose.ui.test.ExperimentalTestApi`）はP11-C1で実際にコンパイルして確定する（要検証P11-P1。`@OptIn`付与の要否そのものはP11-P1の対象外＝確定済み）。

**検証方法**: Compose UIテストはピクセル単位の「重なり」「はみ出し」を直接アサートする標準APIを持たないため、本書は次の間接的だが実務的な基準を採用する。
- 主要な操作可能要素（ボタン等）が`assertIsDisplayed()`を満たす（クリップされて非表示にならない）。
- 複数の操作可能要素（例: ExecutionのDone／5 min laterボタン）が個別にヒットテスト可能である（座標が重ならない、または少なくとも別ノードとして`onNodeWithTag`等で一意に取得できる）。
- fontScale=1.0（既定）とfontScale=1.5の双方を同一テストメソッド内で描画し、1.5でも1.0と同じノード集合が揃って表示されることを比較する（要素の消失がないことの確認）。

ピクセル単位の「文字が切れる」「重なる」の完全な自動検出はComposeテストの標準機能を超えるため、**実際の見た目の破綻検出は最終的に実機目視（G4-E補遺、§8.7）に依存する**——これは自動テストの限界であり、サイレントに省略せず明記する。

### 7.4 未配線文字列2件の処置（F83）

| キー | 現状の実測 | 処置 | 根拠 |
|---|---|---|---|
| `execution_placeholder_step_title` | `ExecutionViewModel.kt:34`のKDoc内でのみ言及（`[R.string.execution_placeholder_step_title]`というKDocリンク構文であり実コード参照ではない）。実際の描画は`ExecutionScreen.kt:89`の`currentStep.title.ifBlank { resolveStepTitle(currentStep.semanticId) }`が担い、`resolveStepTitle`（`features/common/StepTitle.kt:27-35`）は未知の`semanticId`（プレースホルダの`"execution_placeholder_step_$index"`を含む）を`step_title_fallback`へ解決する。P4-C6完了記録（`docs/plans/phase4-basic-engine.md`）も「C4のtitle解決統一（`resolveStepTitle`経由化）に伴い不使用化した」と明記済み | **削除**。`ExecutionViewModel.kt:34`のKDocも「`step_title_fallback`経由でフォールバック」へ修正する（ダングリング参照防止、`MockPlanFactory`のP4-C6先例踏襲） | `resolveStepTitle`により構造的に置換済みの死蔵リソース |
| `travel_time_manual_apply_button` | `TravelTimeInput.kt`（全38行実測）は`OutlinedTextField`単体で、`onValueChange`のたびに`onMinutesChange`を即時呼ぶ設計（KDoc:「変更は`onMinutesChange`で通知する」）。Applyボタンに相当する要素はコード上に存在しない | **削除** | P3-C5で確定した「即時反映」設計と両立しない死蔵リソース。今Applyボタンを新設するのはi18n/a11yスコープを超えるUX変更（§2.2） |
| `location_permission_denied_message` | 文言「Automatic travel time isn't available, but you can still continue by entering your travel time manually.」（`strings.xml:114`）は定義済みだが、`DeparturePermissionAndRoutingSection`（`DepartureScreen.kt:100-163`）のDENIED分岐は`TravelTimeInput`と設定導線ボタン（別文言`location_open_settings_button`）のみを描画し、本文言は未使用 | **配線**。`showManualFallback`ブロック（`DepartureScreen.kt:145-153`）の`TravelTimeInput`直前に説明`Text`として追加する（同関数内の`departure_eta_stale_notice`等と同じパターン） | 内容が今も的確（手動入力への案内）で、既存の`departure_eta_stale_notice`型パターンにそのまま適合する |

`DepartureRoutingScreenTest.kt:194-210`の`tDep2_5_phase3StringKeys_nonEmptyAndDistinctBetweenEnAndJa`が参照する`phase3Keys`リストから削除2キーを除外する変更を伴う（**既存テスト変更、TEAMS§2承認要請対象**、§6.2）。

### 7.5 UnusedResources残3件の解消（F83）

§7.4の3キーが、Phase 3〜6を通じ一貫して報告され続けているUnusedResources警告の**全数**である（`docs/plans/phase5-notification-execution.md:712`「UnusedResources 3件は既存3件（`execution_placeholder_step_title`／`location_permission_denied_message`／`travel_time_manual_apply_button`）のみ」、`docs/plans/phase6-recovery-basic.md`P6-C5行「UnusedResources増減0件（既存3件＝…）」の2つの独立した実測ログで確認）。§7.4の処置（削除2件・配線1件）を実施すれば、`:app:lintDebug`のUnusedResources警告は3件から**0件**になる見込みである。G4-JVM（§3）でこれを実測確認する。

### 7.6 ja/enパリティ監査の拡張検討（F84）

`StringResourceParityTest.kt`（全117行、実測でKDoc・実装ともに直接確認済み）は、`res/values/strings.xml`と`values-ja/strings.xml`をXMLパースし、以下3点をキー集合全体に対して機械的に検証している——KDoc冒頭の「現状のstrings.xmlは`app_name`／`hello_smoke`の2キーのみ」という記述はPhase 0時点のまま**陳腐化しており**、実際には現在104キー×2ロケールを検証している（実測: `grep -c '<string name='`で両ファイルとも104件、キー集合`diff`で完全一致確認済み）。

- T-I18N-1: キー集合の完全一致（＝スコープ5項目「全キーの両ロケール存在」はこれで既に充足）
- T-I18N-2: `%1$s`等のフォーマット引数個数の一致
- T-I18N-3: 非空文字列であること

**確定方針（§12 S-4裁定）**: en/ja値が完全一致するキーの検出（未翻訳の疑い）アサーションは**追加必須**とする。実測（Python/`xml.etree`でのワンオフ確認）で現在2件該当することを確認済み: `app_name`（"Action Starter"、固有名詞のため意図的一致）・`manual_event_picker_confirm_button`（"OK"、両ロケールで慣用的に使われる国際慣用表現のため意図的一致）。この2件はいずれも正当な一致であり、機械的に「一致＝バグ」と判定するとfalse positiveになるため、**allowlistはテストコード内の明示的な定数リスト**として実装する（例: `private val INTENTIONAL_SAME_VALUE_KEYS = setOf("app_name", "manual_event_picker_confirm_button")`）。allowlistの各エントリには意図的な一致として許容する理由コメントの付与を必須とする（例: `// app_name: 固有名詞のため翻訳しない`／`// manual_event_picker_confirm_button: "OK"は国際的に共通の慣用表現`）。将来allowlistへ新規キーを追加する場合は、本計画書の完了記録（§10サイクル分解の該当サイクル欄）へ追加理由を1行（キー名・理由・追加日）残す運用とする。`<string-array>`／`<plurals>`要素は現在0件（実測）のため、既存パーサの`<string>`限定スコープを変更する必要はない。

### 7.7 ADR起票候補（P11-C5で起票、§12裁定後に番号を最終確定）

`docs/plans/phase6-recovery-basic.md`§7.9の先例（scaffold時にKDocへ記録し統合ウィンドウで正式起票）を踏襲し、P11-C1のscaffold時点で各該当ファイルのKDocへ以下候補を記録しておき、P11-C5で`DECISIONS.md`へ正式起票する。起票直前に`grep -n "^### ADR-" DECISIONS.md`で最新番号を再実測し、他Phaseが並行してADR-0038以降を使用していないことを確認してから採番する（`docs/plans/phase6-recovery-basic.md`P6-C5「⑥ADR起票」と同じ手順）。

| 候補 | 内容 | 記録トリガー |
|---|---|---|
| ADR案A | POST_NOTIFICATIONSの要求方式は直接リクエスト・事前カードなしに確定（§12 S-1裁定） | ②仕様未定義箇所の補完（§95.4は「Start時点」のみ規定していたUI詳細をS-1裁定で確定） |
| ADR案B | `PermissionGate`の2値契約を通知権限でも維持し、「拒否」と「永続拒否」をUI上区別しない設計（§7.1） | ②仕様未定義箇所の補完（Departure/EventSelectionの既存設計パターンの明示的な踏襲として記録） |
| ADR案C | `execution_placeholder_step_title`・`travel_time_manual_apply_button`の削除、`location_permission_denied_message`の配線（§7.4） | ①バグ修正／死蔵コード整理（仕様からの逸脱ではなく既存コードの整合性回復） |
| ADR案D | フォントスケール1.5xテストに`DeviceConfigurationOverride`（またはP11-P1の結果次第で`LocalDensity`直接オーバーライド）を採用する技術選定（§7.3） | ④新規テスト技法の導入（後続Phaseの参照先として記録する価値がある） |

---

## §8. テストケース表（全41件：正常系17／異常系10／エッジケース14。すべてE1〔JVM純粋〕またはE2〔Robolectric＋Compose Test〕。E3〔instrumented〕は作成せず、§8.7「ゲート検証手順」で代替担保する）

### 8.1 分類定義（Phase 3/5/6先例踏襲）

| 区分 | 内容 | source set | runner | Gradleタスク | 必要端末 |
|---|---|---|---|---|---|
| E1 | 純粋JVM（XML/テキストパース等、Android Framework非依存） | `src/test` | JUnit4 | `:app:testDebugUnitTest` | 不要 |
| E2 | Robolectric＋Compose Test（画面・ViewModel・semantics/密度オーバーライド） | `src/test` | JUnit4 + Robolectric（＋Compose Test） | `:app:testDebugUnitTest` | 不要 |

全実行は`--console=plain`、ログは`build/agent-logs/`へ保存する（Phase 3〜6先例踏襲）。

### 8.2 T-P11N — 通知権限リクエストフロー（F79/F80。E2、`test/.../navigation/NotificationPermissionRequestTest.kt`／全10件のうちT-P11N-9のみE1）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P11N-1 | 正常 | API 33+環境（`@Config(sdk = [33])`）でPlanReviewの「Start」タップ時に`requestNotificationPermissionLauncher.launch(POST_NOTIFICATIONS)`が発火する（Robolectric、`Shadows.shadowOf(activity).getLastRequestedPermission()`で検証。要検証P11-P3、下記） | NavHost（PlanReview route） |
| T-P11N-2 | 正常 | 許可済み環境でStartタップ後、Executionへ遷移した時点で`isNotificationPermissionDenied == false`（バナー非表示） | NavHost→Execution |
| T-P11N-3 | 異常 | 拒否環境でStartタップ後、Execution画面に劣化バナーが表示される（既存`isNotificationPermissionDenied`の回帰確認） | ExecutionScreen |
| T-P11N-4 | 正常 | 拒否時バナーに設定導線ボタンが表示され、タップで`onOpenNotificationSettings`が呼ばれる | ExecutionScreen |
| T-P11N-5 | エッジ | `@Config(sdk = [26])`（本アプリのminSdk）環境では`launch()`を呼ばずExecutionへ直接遷移する（API 33未満分岐、§7.1。API 26はPOST_NOTIFICATIONSが概念上存在しない）。例外は投げない | NavHost |
| T-P11N-6 | エッジ | 「Start」を2回連続タップしても、`notificationService.schedule()`・`launch()`いずれも呼び出し回数が破綻しない（多重発火防止の回帰確認） | NavHost |
| T-P11N-7 | 正常 | Execution画面がON_RESUMEした際、`isNotificationPermissionDenied`が最新のOS権限状態へ再同期される（設定から戻った直後にバナーが消える） | ExecutionViewModel |
| T-P11N-8 | エッジ | `sharedPlanViewModel`が`null`（プレースホルダ経路）のときも`isNotificationPermissionDenied`算出は従来どおり動作し新規回帰を生まない | ExecutionViewModel |
| T-P11N-9 | 正常 | ja/en両ロケールで新規追加文言（設定導線ボタン等）が非空かつ相互に異なる | strings.xml |
| T-P11N-10 | 回帰 | 既存T-P5UI-6（`ExecutionOneActionTest.kt`、通知拒否時NOWカードのみで状態が伝わる）が本Phase変更後も成立する | ExecutionViewModel |

**要検証P11-P3**: `ShadowActivity.getLastRequestedPermission()`（Robolectric、Context7で存在を確認済み）がCompose `rememberLauncherForActivityResult`経由の`launch()`呼び出しを正しく捕捉できるか（Composeの`ActivityResultRegistry`委譲層を経由するため、Activity直接の`requestPermissions()`呼び出しとは経路が異なる可能性がある）。P11-C1で実際にコンパイル・実行して確定する。代替手段（`ActivityResultRegistry`をfakeで差し替える等）が必要になる可能性を残す。

### 8.3 T-P11A — contentDescription／semantics網羅（F81。E2、`test/.../features/AccessibilitySemanticsTest.kt`／全11件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P11A-1 | 正常 | EventSelectionの`EventRow`が1つの`mergeDescendants`ノードとして、日時・タイトル・場所を含む`contentDescription`を持つ | EventSelectionScreen |
| T-P11A-2 | 正常 | PlanReviewの`PlanReviewStepRow`が時刻＋タイトルを含む`contentDescription`を持つ | PlanReviewScreen |
| T-P11A-3 | 正常 | Executionの現在ステップ表示（`semantics(mergeDescendants=true)`）が非空の`contentDescription`を持つ | ExecutionScreen |
| T-P11A-4 | 正常 | Executionの劣化バナー3種（exact alarm／通知／FGS）がいずれも「警告」であることを`contentDescription`または同等のsemanticsで伝える | ExecutionScreen |
| T-P11A-5 | 正常 | DepartureのTransportModeSelector各選択肢が識別可能な`contentDescription`を持つ | DepartureScreen |
| T-P11A-6 | 正常 | Recovery候補行が`mergeDescendants`ノードとして、案の内容＋ETA＋選択状態を含む`contentDescription`を持つ | RecoveryScreen |
| T-P11A-7 | エッジ | Recovery候補行のうち`selectedId`と一致する行の`contentDescription`が「選択中」を含む一方、他の行は含まない | RecoveryScreen |
| T-P11A-8 | エッジ | `estimatedArrival == null`の候補行はETA情報を`contentDescription`に含めない（偽情報を読み上げない、T-RECUI-8と同種の設計） | RecoveryScreen |
| T-P11A-9 | 正常 | ja/en両ロケールで新規`contentDescription`文言が非空かつ相互に異なる | strings.xml |
| T-P11A-10 | 回帰 | `mergeDescendants`追加後も既存`testTag`ベースの主要アサーション（T-EXEC-2、T-REC-2等の一部を代表サンプルとして再実行）が`useUnmergedTree`調整のみで成立する | 全5画面 |
| T-P11A-11 | 正常 | Recovery候補行のうち選択中の行に、他の行と異なる視覚的インジケータ（背景色`primaryContainer`またはボーダー）が適用される（Compose semantics/背景プロパティで検証、§12 S-5裁定） | RecoveryScreen |

### 8.4 T-P11F — フォントスケール1.5x耐性（F82。E2、`test/.../features/FontScaleResilienceTest.kt`／全8件。全テストメソッドへ`@OptIn(ExperimentalTestApi::class)`の付与を必須とする——`DeviceConfigurationOverride`が`@ExperimentalTestApi`を要求するため。Gemini G1 CRITICAL指摘反映、§7.3）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P11F-1 | 正常 | EventSelectionScreenがfontScale=1.5でも主要ボタン・テキストが`assertIsDisplayed()` | EventSelectionScreen |
| T-P11F-2 | 正常 | PlanReviewScreen同上（ステップ一覧＋Start/Editボタン） | PlanReviewScreen |
| T-P11F-3 | 正常 | ExecutionScreen同上（劣化バナー3種同時表示ケースを含む、最も要素密度が高い画面） | ExecutionScreen |
| T-P11F-4 | 正常 | DepartureScreen同上（DENIED状態でTravelTimeInput＋設定ボタン＋新規説明文が同時表示されるケースを含む） | DepartureScreen |
| T-P11F-5 | 正常 | RecoveryScreen同上（候補3件表示時） | RecoveryScreen |
| T-P11F-6 | エッジ | fontScale=1.5でExecutionのDone／5 min laterボタンが別ノードとしてヒットテスト可能（重なりによる誤操作がない） | ExecutionScreen |
| T-P11F-7 | エッジ | fontScale=1.0とfontScale=1.5を同一テスト内で比較し、1.5でも1.0と同じノード集合が揃う（要素消失がない） | ExecutionScreen |
| T-P11F-8 | エッジ | ja（長め文言になりやすい）×fontScale=1.5の組み合わせでも主要ボタンが`assertIsDisplayed()` | ExecutionScreen |

**要検証P11-P1**: `DeviceConfigurationOverride`の正確なimportパス・追加依存の要否（§7.3）。P11-C1で実際にコンパイルして確定する。（`@OptIn(ExperimentalTestApi::class)`の付与要否はGemini G1 CRITICAL指摘により必須と確定済みのため、P11-P1の対象外）

### 8.5 T-P11S — 未配線文字列・UnusedResources処置（F83。E1中心、`StringResourceParityTest.kt`拡張＋`DepartureRoutingScreenTest.kt`拡張／全7件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P11S-1 | 正常 | `execution_placeholder_step_title`が`strings.xml`（en/ja）に存在しない | strings.xml（E1） |
| T-P11S-2 | 正常 | `travel_time_manual_apply_button`が`strings.xml`（en/ja）に存在しない | strings.xml（E1） |
| T-P11S-3 | 正常 | `location_permission_denied_message`がDepartureのDENIED状態で実際に描画される（`onNodeWithText`で検出） | DepartureScreen（E2） |
| T-P11S-4 | エッジ | 削除した2キーへのソースコード参照が0件である（`app/src/main`をテキスト走査する構造ガード、コメント/KDoc含む） | 全体（E1） |
| T-P11S-5 | 回帰 | `DepartureRoutingScreenTest.tDep2_5`の`phase3Keys`リストから削除2キーを除外してもテストが成立する（**既存テスト変更、TEAMS§2承認要請対象**） | DepartureRoutingScreenTest（E2） |
| T-P11S-6 | 正常 | `:app:lintDebug`実行結果でUnusedResources警告が0件（G4-JVMのゲート項目、テストではなく実測手順） | lint |
| T-P11S-7 | エッジ | `ExecutionViewModel.kt`のKDocが削除済みリソースへのダングリング参照を含まない | ExecutionViewModel（E1、grep構造ガード） |

### 8.6 T-P11P — ja/enパリティ監査の拡張（F84。E1、`StringResourceParityTest.kt`拡張／全5件）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-P11P-1 | 正常 | 既存T-I18N-1〜3が104キー＋本Phase追加キーの時点でもGreenのまま（回帰確認） | StringResourceParityTest |
| T-P11P-2 | 正常 | 本Phaseで新規追加した全キー（設定ボタン・contentDescription用文言）がen/ja両方に存在しパリティが崩れない | strings.xml |
| T-P11P-3 | エッジ | allowlist（`app_name`・`manual_event_picker_confirm_button`、テスト内明示定数リスト・各エントリ理由コメント必須）を除き、en/ja値が完全一致するキーが存在しないことを検証する新規アサーション（§12 S-4裁定により実装必須、§7.6） | StringResourceParityTest |
| T-P11P-4 | 正常 | `StringResourceParityTest.kt`のKDoc冒頭記述（「2キーのみ」）を実キー数（104+α件）へ更新する | StringResourceParityTest（ドキュメント正確性） |
| T-P11P-5 | エッジ | `<string-array>`／`<plurals>`が0件であることの前提をKDocに明記する（将来追加時にパーサ拡張が必要になる旨の申し送り） | StringResourceParityTest |

### 8.7 テストではなく「ゲート検証手順」で担保する項目（Phase 3 §9.10の先例踏襲）

TalkBackの実際の読み上げ内容・実機でのfontScale目視は、Compose Test標準APIでは意味のある形で自動アサートできないため、以下はG4-Eで**手順**として実施し証拠（スクリーンショット・実施ログ）を残す。テストケース数（41件）には含めない。

1. 実機（Pixel等）でTalkBackを有効化し、5画面それぞれで主要な操作導線（イベント選択→Plan確認→Start→Execution Done/5 min later→Departure、Recoveryの候補選択→Use this plan）をTalkBack操作のみで完了できることを確認する。
2. `adb shell settings put system font_scale 1.5`で実機フォントスケールを1.5へ設定し、5画面を目視確認する（`adb shell settings put system font_scale 1.0`で元に戻すことを含む）。
3. ja/en切替後の5画面スクリーンショットを取得し、文字列欠落・レイアウト破綻がないことを比較する（`docs/GOAL.md` Eカテゴリの証拠要件）。

---

## §9. エラー＆レスキューマップ（全10行。通知権限4状態遷移を#1〜#4に含む。ハンドリング方法列に空欄なし）

| # | 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|---|
| 1 | 通知権限（状態: 未リクエスト→許可） | ―（正常系。ただし遷移自体が発生しないまま`schedule()`が呼ばれるルートが残っていないかの確認は必要） | API 33+では`launch(POST_NOTIFICATIONS)`を呼び、許可済み環境ではOSが即座に`true`をコールバックしダイアログを出さない（遷移はコールバック内、§7.1）。API<33では`launch()`自体を呼ばず直接遷移する | 通知が正常に届く。ユーザー体験上の変化なし |
| 2 | 通知権限（状態: 未リクエスト→拒否／再度リクエスト可能） | ユーザーがシステムダイアログで「許可しない」を選択 | `isNotificationPermissionDenied=true`となり劣化バナー＋設定導線ボタンを表示（§95.6準拠）。`notificationService.schedule()`自体は成功し続け、実際の通知提示（`notifyNow`）側がスキップする設計（Phase 5既存） | 通知は届かないが、Execution画面のNOWカードで状態を把握できる |
| 3 | 通知権限（状態: 未リクエスト→拒否／永続、"今後表示しない"） | ユーザーが「許可しない」を2回目選択、または端末設定で明示的にブロック | `PermissionGate.isGranted()`は状態2と同じ`false`を返すため、UI上は状態2と同一の劣化バナー＋設定導線ボタンで扱う（2値契約による意図的な集約、§7.1） | 状態2と同じ。次回Startタップでもダイアログは出ないが、設定導線ボタンから手動で許可できる |
| 4 | 通知権限（状態: 拒否→Settings経由で許可） | ユーザーが設定導線ボタン経由でOS設定を開き、通知を許可してアプリへ戻る | Execution画面のON_RESUME再評価（F80、新規`DisposableEffect`）で`isNotificationPermissionDenied`を再照会し`false`へ更新 | バナーが自動的に消え、次回以降の通知が届くようになる（§95.6「自動的に通知を再開する」の充足） |
| 5 | 通知権限リクエスト（多重発火） | 「Start」を連打、または遅延コールバック中に再タップ | `launch()`はActivityResultLauncherの標準的な冪等動作に委ねる。`notificationService.schedule()`の多重呼び出しは既存のアラーム一意性設計（`AlarmScheduler`のPendingIntent一意性、Phase 5既存）で吸収される | 通知が重複して届くことはない |
| 6 | contentDescription | 新規追加文言がen/ja片方のみに存在、または空文字 | `StringResourceParityTest`（T-I18N-1/3、既存）が機械的に検出しRedになる | リリース前にビルドが失敗し、欠落が本番へ到達しない |
| 7 | フォントスケール1.5x | レイアウトが実際に破綻する（要素の重なり・クリップ） | T-P11F系のRobolectricテストで`assertIsDisplayed()`・ノード非重複を機械検出。検出時はP11-C3でComposable側の`Modifier.weight`／`Arrangement`／`maxLines`等を調整する（本書はレイアウト破綻の**検出**の仕組みを定義するものであり、破綻が見つかった場合の具体的な修正内容はP11-C3実装時に個別対応する） | 自動テストで機械的に検出されるため、破綻したまま気づかれずリリースされることを防ぐ |
| 8 | 未配線文字列削除 | 削除した2キーへの参照が実は他に残っている（見落とし） | T-P11S-4（grep構造ガードテスト）で0件を機械確認してから削除する。既存テスト（`DepartureRoutingScreenTest`）の参照はT-P11S-5で先に更新する | ビルド時に未解決リソース参照でコンパイルエラーとなり、サイレントな崩壊がない |
| 9 | UnusedResources解消の確認漏れ | §7.4の処置後もlint未実測のまま「解消した」と報告する | G4-JVM（§3）で`:app:lintDebug`のUnusedResources件数を実測してからG4-JVM通過を宣言する（T-P11S-6） | 報告の正確性が実測で担保される |
| 10 | Recovery候補の選択状態（視覚表示） | 視覚的インジケータ（背景色`primaryContainer`／ボーダー）の実装漏れ、またはコントラスト不足で判別しづらい | §12 S-5裁定により視覚的インジケータの実装をスコープに含め、T-P11A-11（§8.3）で機械検証する。色のみに依存しないよう既存のcontentDescription「選択中」（TalkBack向け）と併用する設計とする | 晴眼者・TalkBackユーザー双方が選択状態を判別できるようになる（従来の非対称ギャップを解消） |

---

## §10. サイクル分解（P11-C1〜C5）

| サイクル | 内容 | 担当（Do） | 完了記録 | 到達ゲート |
|---|---|---|---|---|
| **P11-C1** scaffold | 5画面・NavHost・`ExecutionViewModel`へ新規パラメータ／メソッドをデフォルト値付きで追加（コンパイル可能な足場）。`strings.xml`から2キー削除・`location_permission_denied_message`は据え置き。要検証P11-P1（`DeviceConfigurationOverride`）・P11-P3（`ShadowActivity.getLastRequestedPermission()`）をprobeとして実測し本書へ追記する。ベースライン実測（`:app:testDebugUnitTest`件数） | domain/ui-implementer | （未着手。実装後に本欄へ実測結果を追記する） | scaffoldコンパイル成功・ベースラインログ |
| **P11-C2** Red | §8の全41件をfailing化しRed実測 | test-writer → quality-runner | （未着手） | **G2** |
| **P11-C3** Green | F79〜F84を実装（NavHost launcher結線、設定導線ボタン、ON_RESUME再評価、contentDescription本実装、fontScale耐性のためのレイアウト調整、strings.xml文言確定） | ui-implementer | （未着手） | **G3** |
| **P11-C4** 統合／実機 | NavHost統合ウィンドウ（直列）。§8.7のゲート検証手順（実機TalkBack操作確認・実機fontScale=1.5目視確認・ja/enスクリーンショット取得） | domain-implementer（integration owner）→ quality-runner | （未着手） | **G4-E** |
| **P11-C5** クローズ | Refactor。`./gradlew build`／`lintDebug`再実測（UnusedResources 0件・エラー0件を確認）。全41件Green再確認。`DECISIONS.md`へADR起票（ADR-0038〜、§7.1のS-1裁定内容等）。`docs/GOAL.md` Eカテゴリの採点根拠を提示 | domain/ui-implementer → quality-runner | （未着手） | **G4-JVM** |

**着手前提**: P11-C1の着手前提条件はPhase 0〜6のクローズとする（§2.3）。G1判定（Step 2アーキテクトレビュー：Opus＋Gemini）は2026-08-10付で通過済み（§4）であり、残る着手前提はPhase 0〜6のクローズ確認のみである。P11-C1着手直前にこれを改めて実測確認する。

---

## §11. リスク

| ID | リスク | 対応 |
|---|---|---|
| R-1 | Phase 0〜6のいずれかが本書作成時点でまだクローズしておらず、P11-C1が対象画面（5画面）と競合する | §2.3のとおり着手前提を明記。P11-C1着手直前にPhase 0〜6全ての`docs/plans/phase*.md`完了記録を再確認する |
| R-2 | `mergeDescendants`追加によりComposeのsemanticsツリー構造が変わり、既存の`onNodeWithTag`ベースのテスト（T-EXEC-*、T-REC-*等）が広範囲に壊れる | T-P11A-10で代表サンプルの回帰確認を行うが、実際の影響範囲はP11-C2のRed実測で全件確定する。想定より広範囲に破壊する場合はP11-C3の作業量が本書の見積りを超える可能性がある（要Fable5報告） |
| R-3 | `DeviceConfigurationOverride`が本プロジェクトのCompose BOM（`2026.06.01`）・Robolectric 4.16.1の組み合わせで期待どおり動作しない（P11-P1が否定的な結果になる） | 代替として`CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f))`（Compose標準の`LocalDensity`直接オーバーライド、Composeの初期リリースから存在する枯れたAPI）にフォールバックする設計とする |
| R-4 | `ShadowActivity.getLastRequestedPermission()`がCompose `rememberLauncherForActivityResult`経由の呼び出しを捕捉できない（P11-P3が否定的な結果になる） | T-P11N-1の検証方法を「実際にlaunch()が呼ばれたか」から「Executionへ遷移後、権限状態を反映した`isNotificationPermissionDenied`が正しいか」（T-P11N-2/3、既存の`PermissionGate`経路）へ後退させ、launcher呼び出し自体の検証はE3（instrumented、任意）へ格下げする |
| R-5 | `location_permission_denied_message`の配線が既存`DepartureRoutingScreenTest`の他のアサーション（テキスト数・順序等）と衝突する | T-P11S-3を新規テストとして追加するに留め、既存テストのテキスト完全一致アサーションがある場合は影響範囲をP11-C2のRedで確認してから対応する |
| R-6 | exact alarm劣化バナーのワンタップ導線未実装（§2.2で対象外と宣言）が、Fable 5レビューで「i18n/a11yの文脈でついでに直すべき」と判断される可能性 | §2.2に判断根拠を明記済み。Step 2レビューで指摘があれば範囲追加を検討する（本書の変更ではなくレビュー後の追補として扱う） |

---

## §12. Fable 5確認事項（全9件、S-1〜S-9。Fable 5裁定済み・裁定列に記録）

| ID | 確認事項 | plan-doc-writerの推奨（参考） | 裁定（Fable 5、2026-08-10） |
|---|---|---|---|
| **S-1** | POST_NOTIFICATIONSのリクエストをPlanReview「Start」タップで**直接**システムダイアログ表示するか、EventSelection（カレンダー）／Departure（位置情報）と同様に**事前説明カード**を挟むか、§95.4・Manifestコメントは「Start時点で要求する」としか書いておらずカードの要否は未定義 | 直接表示を推奨（低摩擦・OS標準ダイアログの説明文で足りると判断）。ただし他2権限との一貫性を崩すトレードオフがあるため確定を仰ぐ | **直接リクエスト採用**（Startタップ・事前カードなし）。カレンダー/位置は権限なしでは画面そのものが成立しないため事前カードが要るが、通知は§19原則（Local AIオフ時でもBasic Engineでアプリが成立するという設計原則と同型）により、無くてもアプリのコア機能（Execution画面での次アクション提示）が成立する増強系の権限であり、他2権限とは性質が異なる。拒否後の救済はF80の設定導線で担保する |
| **S-2** | `location_permission_denied_message`の配線位置・最終文言（本書提案: `TravelTimeInput`直前に既存文言をそのまま使用） | 提案どおりでよいか確認 | **提案どおり採用**（`TravelTimeInput`直前・既存文言をそのまま使用） |
| **S-3** | `travel_time_manual_apply_button`の削除可否（本書提案: 削除。将来「入力確定ボタン」UXへ戻す可能性を残すなら保持も検討） | 削除を推奨（現在の即時反映設計と矛盾するため） | **削除採用**。即時反映設計（P3-C5）はテストで固定済みであり、当該リソースはgit履歴に残置される |
| **S-4** | `StringResourceParityTest`へのen/ja値完全一致検出アサーション（T-P11P-3）を追加するか、追加する場合のallowlist方針 | 追加を推奨するが、allowlistの具体的な運用（新規の意図的一致語をどう追加するか）は製品判断のため確認を仰ぐ | **アサーション追加を必須へ格上げ**。allowlistはテスト内の明示定数リストとし、各エントリへ意図的一致の理由コメントを必須とする。新規追加時は本計画書の完了記録（§10）へ1行（キー名・理由・追加日）を残す運用とする（§7.6） |
| **S-5** | RecoveryScreenの選択状態の視覚的インジケータ追加要否（本書はセマンティクスのみ対応と決めたが、発見時点でスコープ外と自己判断した） | 視覚面も含めるべきか、別Phase/別タスクとするか確認を仰ぐ | **視覚インジケータも本Phaseに含める**。§2.2の対象外リストから削除し、選択候補行への視覚表示（背景色`primaryContainer`またはボーダー、`Modifier`1行規模）を§2.1・§7.2でスコープ化する。T-P11A-11で検証する（テスト総数40→41） |
| **S-6** | フォントスケール1.5xで実際にレイアウト破綻が見つかった場合の改修範囲。本書はP11-C3のGreen化作業に含める前提だが、破綻の規模次第でPhase 11の分量（400〜600行目安）を超える可能性がある | 軽微な調整（`Modifier.weight`等）はP11-C3内で許容し、大規模な画面再設計が必要と判明した場合は別Phaseへ切り出す方針でよいか確認を仰ぐ | **推奨どおり採用**。軽微な調整はP11-C3内で許容し、大規模な改修が判明した場合は別Phaseへ切り出しエスカレーションする |
| **S-7** | F番号の採番方針。本書はF79から連番したが、Phase 7〜10（未計画）向けの空き番号帯を予約すべきか | 予約せず連番を推奨（Phase 5→6の先例のように後から番号帯の食い違いが生じてもF番号は単なるラベルであり実害は小さいと判断） | **予約なしの連番を採用**（推奨どおり） |
| **S-8** | §63が列挙するreduced motion／high contrastを本Phaseに含めるか（本書は`docs/GOAL.md` Eカテゴリの採点文言に明記がないことを根拠に対象外とした） | 対象外を推奨（§88「予定を今やる一つの行動に変えることに直接寄与するか」に照らし、採点基準にない項目まで拡張するのは過剰と判断） | **対象外で確定**。reduced motion／high contrastはpost-goalバックログへ1行記録する（本書の実装範囲には含めない） |
| **S-9** | `ExecutionViewModel`へのON_RESUME再評価メソッド追加（F80）が、Fable 5指定スコープ原文「劣化バナー連携」の解釈として適切か（plan-doc-writerの自己判断による範囲拡張の可能性がある） | §95.6「自動的に通知を再開する」の充足に必須と判断し含めることを推奨するが、範囲外と判断されれば§7.1・§6.2から除外する | **含める確定**。§95.6「自動的に通知を再開する」の充足に接地しており、Departure/EventSelectionの既存ON_RESUME再評価パターンとの一貫性からも妥当と判断する |

---

## §13. 未確認事項・申し送り

- **要検証（P11-C1で確定、§8内に個別記載済み）**: P11-P1（`DeviceConfigurationOverride`の正確なimportパス・依存要否）、P11-P2（`ActivityResultContracts.RequestPermission()`のコールバックが許可済み・永続拒否時にも即時発火するという挙動。一般的に知られた設計だが本プロジェクトでの実機/Robolectric実測はまだ行っていない。新設計（§7.1、Gemini G1 CRITICAL #1反映）はAPI 33+の全経路をこのコールバック起点の単一フローへ統合したため、即時発火性の実測確認が従来以上に重要となる）、P11-P3（`ShadowActivity.getLastRequestedPermission()`がCompose経由のlauncher呼び出しを捕捉できるか）。
- **本書はandroid-planner（Opus）計画メモの転記ではない**。plan-doc-writerが本セッションで実施した以下の直接実測に基づき起筆した：`grep -rn "POST_NOTIFICATIONS"`（app全体）、`grep -rn "contentDescription\|Icon(\|IconButton(\|fontScale"`（app全体、いずれも0件または該当箇所限定）、`ActionStarterNavHost.kt`・`ExecutionScreen.kt`・`ExecutionUiState.kt`・`ExecutionViewModel.kt`・`DepartureScreen.kt`・`TravelTimeInput.kt`・`StepTitle.kt`・`RecoveryScreen.kt`・`PermissionGate.kt`・`AndroidPermissionGate.kt`・`StringResourceParityTest.kt`の直接Read、`strings.xml`両ロケールのキー数・キー集合・同一値キーのPython実測、`Action_Starter_Master_Specification_v2.0_Android.md`の§63・§75・§95.4・§95.6・§6・§7の直接Read、`docs/plans/phase3/5/6-*.md`の該当節Read、`DECISIONS.md`の最新ADR番号grep、`app/build.gradle.kts`のminSdk/targetSdk/compileSdk実測、Context7による`DeviceConfigurationOverride`（Jetpack Compose Testing公式ドキュメント）・`ShadowActivity.getLastRequestedPermission()`（Robolectric公式Javadoc）の確認。
- **本書作成作業ではproduction codeを一切変更していない（読み取りのみ）。他の計画書ファイルには一切触れていない。git commitも行っていない。**
- 本書提出後の次アクションは、CLAUDE.md開発ワークフローStep 2（アーキテクトレビュー：Claude Opus＋Gemini `gemini-3.5-flash`固定によるダブルレビュー、Pass 1 CRITICAL→Pass 2 INFORMATIONAL）であった。§12の9件（S-1〜S-9）の裁定と、GeminiクロスレビューのCRITICAL指摘4件の反映が完了し、G1（計画承認）は2026-08-10付で通過した（§4）。次アクションはStep 3（テスト実装・Red）——P11-C1のscaffold・P11-C2でのfailingテスト作成である。
