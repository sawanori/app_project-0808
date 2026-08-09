# Action Starter Android ― Phase 2 実装計画書：Calendar（CalendarProvider実予定取得）

**対象Phase**: Phase 2（仕様書§66 Phase 2、§43 Services/CalendarService）
**正仕様書**: `Action_Starter_Master_Specification_v2.0_Android.md`
**前提**: Phase 1 **G4-JVM／G4-E達成済み**（`docs/plans/phase1-ui-skeleton-domain.md`。build成功・JVM/Robolectric 72/72 Green・lintDebugエラー0・connectedAndroidTest 3/3 Green・ja/en両ロケールスクリーンショット取得済み。commit `bd30158`）
**起点計画メモ**: android-planner（Opus）作成、2026-08-09（`/tmp/claude-1000/.../scratchpad/phase2-planning-memo.md`）
**追加実測メモ**: android-planner（Opus）作成、2026-08-09（emulator probe実測。M-1〜M-18。Hilt導入可否・カレンダークエリ実測・eventStatus/allDay取扱い・E2E手順確立）
**本書作成**: plan-doc-writer（Sonnet）、2026-08-09（初版）。**本改訂**: plan-doc-writer（Sonnet）、2026-08-09（追加実測メモとFable 5後続裁定B17〜B19を統合）。**本パス**: 計画書整合担当（Sonnet）、2026-08-09（§16〜18に残存していた統合前の記述〔ADR-0014残存言及・旧サイクル番号参照・旧テストケース件数〕を修正し、論理整合を最終確定）。**本改訂パス2**: ドキュメント整合担当（Sonnet）、2026-08-09（P2-C1実測でHilt導入不成立〔P-H2確定失敗〕→ADR-0014確定を受け、§3 G2／§6／§8.4／§10.2／§11／§13／§14／§17／§18のHilt・AppModule・AppEntryPoint・EntryPointAccessors関連記述をAppContainer〔手動DI〕継続へ修正。テストケース件数を64件→61件へ更新）
**関連ハーネス文書**: `docs/TEAMS.md`（役割分担・PDCA・品質ゲートの正）、`docs/GOAL.md`（リリース判定基準）、`DECISIONS.md`（ADR記録先）
**関連計画書**: `docs/plans/phase1-ui-skeleton-domain.md`（Phase 1・G4-JVM/G4-E達成済み）

**ステータス: Pass1/Pass2・Geminiクロスレビュー実施済み・指摘反映済み。両セッションの成果を統合しFable 5が整合確定（2026-08-09）。G1自動進行（ユーザー指示2026-08-08）。Hilt裁定（ADR-0015）はユーザー拒否権留保。**

本計画書はFable 5によるPass1（CRITICAL：データ安全性／信頼境界違反／サイレント障害／論理的整合性）とPass2（INFORMATIONAL）のアーキテクトレビュー、およびGemini（`model: "gemini-3.5-flash"`固定）による第三者クロスレビューを**実施済みである（2026-08-09）**。指摘事項はFable 5裁定B8〜B16として確定し、本書へ反映済みである（§4参照）。その後android-plannerの追加実測メモ（M-1〜M-18）を受けた後続のFable 5レビューにより裁定B17〜B19が確定し、本改訂で統合した（§4参照）。テストケース件数（全64件）・エラー＆レスキューマップ行数（全24行）は反映後の実測件数に更新済み。**2026-08-08のユーザー指示により、本書のG1はユーザー承認を待たず自動進行する（裁定B18）。**（`docs/TEAMS.md`§6 G1は「Pass1/Pass2＋クロスレビューで問題なしと判定された場合はユーザー承認を待たず自動進行する」と定める。当初はF17スコープ追加〔裁定B1〕を含むスコープ判断を伴うことを理由に本書はユーザー確認必須の扱いとしていたが、2026-08-08のユーザー指示によりG1自動進行の対象へ変更された。**唯一の例外として、Hilt導入時期の裁定（B17・ADR-0015）についてはユーザーの拒否権を留保する**。）

本書と正仕様書v2.0に差異が生じた場合は仕様書v2.0が正とする。本書はandroid-plannerの計画メモの内容を忠実に文書化したものであり、計画メモにない機能・仕様を自己判断で追加していない。計画メモ中の4件のエスカレーション事項（§3.1〜3.4）については、Fable 5裁定B1〜B7（2026-08-09・確定済み）を本書§4「承認状態」および該当本文へ反映済みである。G1レビュー（Fable 5 Pass1＋Geminiクロスレビュー、2026-08-09）の指摘は裁定B8〜B16として同じく§4へ反映済みである。

---

## 0. 仕様原文の引用（根拠）

**§66 Phase 2（1846-1866行）全文**:
> **Calendar** / CalendarProvider（CalendarContract）。実装： Permission（READ_CALENDAR） / Calendar List / Upcoming Events / Location付きイベント抽出 / Event Selection
> 完成条件： 実カレンダーから予定を選択可能。

**§95.4 権限一覧表（READ_CALENDAR行、2564行）**:
> | READ_CALENDAR | カレンダー予定の読み取り（Event Selection、§66） | **Event Selection機能の初回利用時** | 自動取得不可を表示し、**手動でのイベント情報入力（title・開始時刻・場所）にフォールバック**。Settingsから再許可すると自動連携に復帰する。 |

冒頭文（2560行）:
> 以下の権限は、いずれも該当機能を初めて利用するタイミングで要求し、**アプリ起動時に一括要求しない**。拒否された場合も、対応する機能がBasic Engineの範囲でフォールバックし、**アプリ全体が停止しない**ことを必須とする

**§95.6 エラーマップ 第1行（2586行）**:
> | カレンダー読み込み | READ_CALENDAR権限が拒否される、**またはOS設定で後から無効化される** | Event Selection画面で権限拒否状態を検知し、**手動でのイベント情報入力（title・開始時刻・場所）フォームへフォールバック**。Settings誘導ボタンを表示し、再許可時に自動でカレンダー連携へ復帰する。 | 自動イベント取得はできないが、手動入力によりBasic Engineの全機能（Transition〜Recovery）は継続利用できる。 |

**§43 アーキテクチャ（1320-1377行）**: `Services` 直下に `CalendarService` を定義。ただし**interfaceシグネチャは仕様書に存在しない**（§44 PlanningEngine・§45 RecoveryEngine・§46 RoutingService と異なり、コードブロックが与えられていない）→ ADR記録トリガー②「仕様未定義箇所の補完」に該当。

**§47 Core Domain — Event（1416-1437行）**: `ExecutionEvent(id: UUID, externalCalendarId: String?, title, notes, startDate: Instant, locationName: String?, coordinates: Coordinate?, sourceCalendar: CalendarSource)`。既存実装 `app/src/main/java/com/actionstarter/domain/model/ExecutionEvent.kt` は仕様どおり（init検証なし）。

**iOS→Android読み替え（`docs/TEAMS.md`§冒頭表）**: EventKit → CalendarProvider。重要な非対称性として、EventKitの `EKEvent.structuredLocation.geoLocation`（緯度経度）に相当する標準カラムが **CalendarContractには存在しない**（§8.2で実測確認）。したがって§66「Location付きイベント抽出」は「**座標付き**イベント抽出」ではなく「**EVENT_LOCATION文字列が非空のイベントの抽出**」と読む。座標化（geocoding）は§67 Phase 3（`destination geocoding`）の担当である。

---

## 1. 目的

Phase 2は、Android標準のCalendarProvider（`CalendarContract`）から実カレンダーの予定を読み取り、Event Selection画面を実データ化することを目的とする（仕様§66）。READ_CALENDAR権限の実行時リクエストUI、権限拒否時の手動イベント入力フォールバック（Fable 5裁定B1）、Location付きイベント抽出（座標なし・§0参照）を実装し、完成条件「実カレンダーから予定を選択可能」を満たす。あわせてPhase 1で`mock/`配下に配置したモック供給（`MockEventSource`）を削除し、実データ経路へ置き換える（Phase 1計画書§8 U6の履行）。

位置情報・Routes API・LLM接続・通知・Room永続化はPhase 2の対象外（§67・§68・§69・§70・§74）とし、本Phaseはフォアグラウンド処理（Composableのライフサイクル内）にとどめる。

実行はHilt導入・graph-only（P2-C1。裁定B17・ADR-0015）を皮切りに、probe＋契約scaffold（P2-C2）→ Red（P2-C3）→ Green並列（P2-C4／P2-C5）→ 統合（P2-C6）→ Refactor（P2-C7）→ instrumented E2E（P2-C8）の8サイクルで進める（§14）。

## 2. スコープ

### 2.1 やること

F12〜F20（詳細は§5）。うちF17（権限拒否時の手動入力フォールバック）は、Fable 5裁定B1（2026-08-09）によりPhase 2スコープに**含める**。ただし最小構成（title／開始日時／場所名の任意入力のみ。座標入力・地図選択・保存/再利用は作らない）に限定する。また、F19（構成差し替え）には**graph-only Hilt導入（裁定B17・ADR-0015。裁定B2は取り消し）**を含む（§4・§8.4参照）。

### 2.2 やらないこと（明示）

位置情報取得・Geocoder・Routes API・ETA計算（§67 Phase 3）／BasicPlanningEngine（§68 Phase 4）／通知・AlarmManager・WorkManager・Foreground Service（§69 Phase 5）／Recovery実ロジック（§70 Phase 6）／Room・DataStore永続化（§74 Phase 10）／カレンダー**書き込み**（§61「自動予定変更」は明示禁止）／カレンダー変更のバックグラウンド監視・同期。

### 2.3 通知SLA・フォアグラウンド限定の設計制約

本Phaseには通知・スケジュール処理を一切含まないため、通知許容遅延SLAの定義対象はない。あわせて次を設計上の制約として固定する：Phase 2で追加する処理はすべて**フォアグラウンド（Composableのライフサイクル内）でのみ実行**し、AlarmManager／WorkManager／ForegroundService／BroadcastReceiverを一切導入しない。これにより§95.1のDoze・exact alarm問題および「バックグラウンドからの位置取得不可（While-in-use制約）」は本Phaseでは発生しない（Phase 2は位置情報を全く使わない）。ContentObserverによるカレンダー変更のバックグラウンド監視も導入しない（§88「予定を今やる一つの行動に変えること」に直接寄与しないため）。再読込は画面のON_RESUME時のみとする。

## 3. ゲート定義

`docs/TEAMS.md`§6「コード（Gradleプロジェクトが成立後）｜G1 + G2 + G3」および「Phase完了｜上記 + G4」に基づきG1〜G4すべてを適用する。ADR-0006に倣い、G4は**G4-JVM**と**G4-E**の2段階とする。

- **G1（計画承認）**: 本計画書＋エラー＆レスキューマップ（§11）＋Fable 5 Pass1/Pass2レビュー記録＋Geminiクロスレビュー結果。**Pass1/Pass2レビューおよびGeminiクロスレビューは実施済みであり（2026-08-09）、指摘事項はFable 5裁定B8〜B16として本書へ反映済みである（§4参照）。2026-08-08ユーザー指示によりG1はユーザー承認を待たず自動進行する（裁定B18）。ただしHilt導入時期の裁定（B17・ADR-0015）はユーザー拒否権を留保する。**
- **G2（Red確認）**: P2-C3（旧P2-C2）でtest-writerが作成したfailingテスト（§10、全61件のうちJVM系56件）をquality-runnerが実測する。**T-HILT-1（1件。T-HILT-2〜4はADR-0014により対象消滅・削除済み）はP2-C1でTDD例外により直接実測・Green確認済みのため、G2のRed実測対象に含めない**。E2E系4件（T-E2E2-1〜4）は作成のみでRed実測はG4-Eまで行わない（Phase 1と同じ扱い）。既存テスト（`MockEventSourceTest`7件の移設、`EventSelectionScreenTest`9件・`NavigationFlowTest`5件の更新）も本サイクルで行う（§6.3）。
- **G3（Green確認）**: P2-C4（Domain側Green、旧P2-C3）・P2-C5（UI側Green、C4と並列、旧P2-C4）・P2-C6（統合、旧P2-C5）それぞれでのGreen実測、およびRefactor（P2-C7、旧P2-C6）後の再実測。
- **G4-JVM（Phase 2完了・JVM側）**: P2-C7（旧P2-C6）完了時点。`./gradlew build`成功・対象範囲のJVM/Robolectric全テストPass・`lintDebug`エラー0を実測する。あわせて、マージ済みマニフェスト成果物（`build/intermediates/merged_manifests/{debug,release}/AndroidManifest.xml`）をquality-runnerがスクリプト検証し、debug変種に`READ_CALENDAR`が含まれること、release変種に`WRITE_CALENDAR`が含まれないことを確認する（旧T-MANIFEST-1/2をゲート検証手順へ統合。裁定B9）。
- **G4-E（Phase 2完了・Emulator側）**: P2-C8（旧P2-C7）完了時点。`connectedDebugAndroidTest`実行（T-E2E2-1〜4）、実カレンダーseedの実行/cleanupログ（§12実測済み手順・Step 0〜7）、権限拒否シナリオ（`pm revoke`、P-6・M-10で手順確立済み）の実測、ja/en両ロケールでのスクリーンショット取得を行う。**G4-E未達のままPhase 3以降へ進むことを禁止する。** 未達の場合はその旨を`DECISIONS.md`と完了報告へ明記する。

**Phase 3着手条件は本書の範囲外**とし、G4-JVM通過のみで着手可とするか（Phase 1→2遷移時の先例）G4-E完了まで待つかは、Phase 3計画立案時にFable 5が別途判断する（本書は先例を前提として断定しない）。

## 4. 承認状態

本計画書自体のG1審査のうち、Fable 5 Pass1/Pass2レビュー＋Geminiクロスレビューは**実施済みである（2026-08-09）**。指摘事項はFable 5裁定B8〜B16として本書へ反映済みであり（下記参照）、その後android-plannerの追加実測メモ（M-1〜M-18）を受けた後続セッションのFable 5レビューにより裁定B17〜B19が確定した（両セッションの成果を統合・2026-08-09）。**2026-08-08ユーザー指示によるG1自動進行の対象であり、ユーザー承認は不要である（裁定B18）。ただしHilt導入時期の裁定（B17・ADR-0015）についてはユーザーの拒否権を留保する**。

これとは別に、計画メモ§3が提起した4件のエスカレーション事項および関連する追加論点について、Fable 5裁定B1〜B7（2026-08-09・確定済み）が個別に下されている。これらは計画書レビューサイクルとは独立した、計画メモ提出時点での**承認済み判断**である。

| # | 裁定内容 | 関連ADR／仕様§ | 反映箇所 |
|---|---|---|---|
| B1 | 手動入力フォールバック（F17）をPhase 2スコープに**含める**。最小構成（title／開始日時／場所名のみ。座標入力・地図選択・保存/再利用なし） | §95.4／§95.6第1行・`docs/GOAL.md`カテゴリD(3)/F | 本書§2.1、§5 F17、§7.5、§10 T-MANUAL-1〜6・T-SEL2-6・T-NAV2-1 |
| B2 | ~~Hilt導入をさらに延期しPhase 5（§69）着手時へ再設定する（ADR-0014として記録）。条件：「AppContainer＋単一Factory 1箇所集約」の維持をP2-C5レビュー観点に含める~~ 【**取り消し（Fable 5・2026-08-09・ADR-0015）**】android-planner追加実測（M-13〜M-18）を受け、裁定B17によりHilt導入をPhase 2内（graph-only方式）へ前倒しし、ADR-0015として記録することへ変更した。B2の保護条件（AppContainer＋単一Factory集約の維持）はgraph-only方式がそのまま満たす | ADR-0003の再検討トリガー／仕様§42／~~ADR-0014~~→**ADR-0015**（B17で確定・P2-C1で`DECISIONS.md`へ記録予定・本書時点では未記録） | 本書§4後続裁定B17、§8.4（全面改訂）、§14 P2-C1（新設） |
| B3 | P2-C2（旧P2-C1）契約scaffoldをTDD例外として承認。`docs/TEAMS.md`のTDD例外規定を「各Phaseの契約scaffoldサイクル」へ一般化（ADR-0006の系として記録）。**この一般化原則は、後続裁定B17が新設したP2-C1（Hilt導入・基盤変更のためRed先行不能）にも同様に適用される** | ADR-0006／`docs/TEAMS.md` TDD原則（P2-C2で文言更新予定・本書は`docs/TEAMS.md`を変更しない） | 本書§14 P2-C1行・P2-C2行 |
| B4 | `externalCalendarId`は`"<EVENT_ID>@<BEGIN>"`複合キーを採用（ADR記録トリガー②・仕様未定義箇所の補完としてP2-C2〔旧P2-C1〕で`DECISIONS.md`へ記録予定） | 仕様§47 | 本書§9（写像・フィルタ規則） |
| B5 | 終日予定はPhase 2で候補から除外し、UI注記で明示。Phase 4（Basic Engine）着手時に再検討 | 仕様§4・§66 | 本書§9（除外3）、§11エラーマップ#12 |
| B6 | NavHost簡略結線はPhase 5へ後送り（P2-C2〔旧P2-C1〕でADRとして文書化）。lint警告はメモ提案どおり（アイコン→Phase 13申し送り・未使用文字列→コメント追記で据え置き） | `docs/GOAL.md`カテゴリA | 本書§15（既知の技術的負債の扱い） |
| B7 | seedは主方式（adb seed・アプリにWRITE_CALENDAR不追加）を既定とし、P-5/P-6のprobe結果を受けてP2-C2（旧P2-C1）完了時に最終確定。**android-planner追加実測（§12実測済み手順Step 0〜7）により主方式が実機で一貫動作することを確認済み** | §58 Privacy-first／§95.5 | 本書§12（seed方針）、§13 P-5/P-6 |

**上記B1〜B7はいずれもユーザー承認待ちの項目ではない**（Fable 5裁定として確定済み）。下記のB8〜B16、および後続のB17〜B19（本節末尾）も同様に確定済みである。本計画書全体としてのG1のうち、Pass1/Pass2アーキテクトレビューとGeminiクロスレビューは実施済みであり指摘は反映済みである（下記参照）。**2026-08-08ユーザー指示によるG1自動進行の対象であり、ユーザー承認は不要である（裁定B18）。唯一の例外はHilt導入時期の裁定（B17・ADR-0015）であり、これについてはユーザーの拒否権を留保する。**

### エスカレーション原文（計画メモ§3、参考として保持）

- **B1の起点（§3.1）**: §66のPhase 2実装項目に「手動イベント入力フォーム」は列挙されていない。しかし§95.4と§95.6第1行は、READ_CALENDAR拒否時のフォールバック先を「手動でのイベント情報入力（title・開始時刻・場所）フォーム」と明示的に規定し、その設置場所を「Event Selection画面」と名指ししている。`docs/GOAL.md`カテゴリD(3)・カテゴリFが得点対象。android-plannerの推奨はF17を含めることであり、根拠は(a)§95.4冒頭「拒否された場合も…アプリ全体が停止しないことを必須とする」、(b)手動入力がなければ権限拒否時のEvent Selectionが行き止まりとなり§19の思想（Basicのみで成立）を満たさない、(c)GOAL.mdの2カテゴリが未達になる、の3点。
- **B2の起点（§3.2）**: ADR-0003の再検討トリガーは「Phase 2開始時」であり本Phaseが期日にあたる。android-plannerの推奨はさらなる延期（Phase 5・ADR-0014）であり、根拠は(a)§42はHiltを「推奨」とし必須要件でない、(b)ADR-0003の延期条件（AppContainer＋単一Factory集約）は現状守られている、(c)HiltのKSP／AGP互換性が未確認のままクリティカルパスに乗せるリスク、(d)Hiltが実質必要になるのはPhase 5（BroadcastReceiver／Foreground Service／Worker登場時）である。**【2026-08-09追記】根拠(c)は、android-plannerの追加実測（M-13〜M-18）により後続裁定B17で更新された。KSP／AGP互換性の一部（Hilt本体のMaven Central実在・compileSdk適合）は実測で解消し、残る協調動作の検証はP-H1/P-H2/P-H3として引き継がれている。詳細は本節末尾の裁定B17・§8.4を参照。**
- **B3の起点（§3.3）**: `docs/TEAMS.md`「TDD原則」の例外は「Phase 1の契約scaffold」と「C1 Gradleブートストラップ」に限定されている。Phase 2でも`CalendarService`・`CalendarResult`・新UiState型が存在しないとRedテストがコンパイルできず、G2定義「単なるコンパイルエラーによるRedは意図した失敗と認めない」に抵触する。Phase 1裁定A1と同一構造の問題。
- **B4の起点（§3.4）**: §47は`externalCalendarId: String?`を定義するのみで意味を規定していない。`sourceCalendar: CalendarSource`が既にカレンダー識別子を保持するため、「外部カレンダー体系における**イベント**の識別子」と解するのが整合的とandroid-plannerは判断し、`"<Instances.EVENT_ID>@<Instances.BEGIN>"`（繰り返し予定の各インスタンスを一意に識別する複合キー）を提案した。

### G1レビュー（Fable 5 Pass1 + Geminiクロスレビュー、2026-08-09）による裁定

Fable 5によるPass1（CRITICAL：データ安全性／信頼境界違反／サイレント障害／論理的整合性）レビュー、およびGemini（`model: "gemini-3.5-flash"`固定）による第三者クロスレビューを実施し（2026-08-09）、以下の指摘をFable 5裁定B8〜B16として確定した。B1〜B7（計画メモ提出時点のエスカレーション裁定）とは出所が異なるため区別して記載する。

| # | 裁定内容 | 指摘の性質・関連仕様§ | 反映箇所 |
|---|---|---|---|
| B8 | `CalendarResult.Success`に`skippedRowCount: Int = 0`を追加し、`CalendarFailureReason`から`ROW_MALFORMED`を削除して`{ PROVIDER_UNAVAILABLE, QUERY_FAILED }`の2値とする。行単位の不正（`begin`欠損等）はスキップし`skippedRowCount`へ計上（`Success`のまま）、クエリ構造の問題（projection列不在等）は`Failure(QUERY_FAILED)`（全体失敗）とする | Pass1 CRITICAL：論理的整合性（契約§7.2とエラーマップ#6/#7・テストT-CALMAP-7/8/9の不整合） | 本書§7.2、§10.2 T-CALMAP-7/8/9、§11エラーマップ#6・#7 |
| B9 | `T-MANIFEST-1/2`はP2-C2（旧P2-C1）のManifest変更時点からGreenでありRed化不能。JVMテストからのマージ済みreleaseマニフェスト検証も不安定なため、テストケース表から「ゲート検証手順」へ移し、P2-C7（G4-JVM、旧P2-C6）でquality-runnerがマージ済みマニフェスト成果物をスクリプト検証する | Pass1 CRITICAL：論理的整合性（G2定義「単なるコンパイルエラー/Green確定済み項目のRed化不能」との矛盾） | 本書§3 G4-JVM、§10.2 F19、§14 P2-C7 |
| B10 | seed/cleanupスクリプトは実行前に対象がエミュレータであることを検証するガード（`adb -s <emulator serial>`明示指定＋`getprop ro.kernel.qemu`等の確認）を設け、不成立時は一切の書込/削除をせず即中断する | Pass1 CRITICAL：データ安全性（破壊的操作にガードがない） | 本書§12、§11エラーマップ#21（新規） |
| B11 | 権限再許可によるON_RESUME自動復帰は、手動入力フォームがdirty（入力中）の間は発生させない。dirty状態はComposeの`rememberSaveable`ではなくViewModelが保持する | Pass1 CRITICAL：データ安全性（入力内容の予告なき破棄防止）の実装手段明確化 | 本書§7.4、§11エラーマップ#15 |
| B12 | 手動入力の開始日時はユーザーのローカル時刻として受け取り、`ZoneId.systemDefault()`で`Instant`へ変換する | Pass2 INFORMATIONAL：論理的整合性（タイムゾーン変換仕様の未記載） | 本書§7.5、§10.2 T-MANUAL-1 |
| B13 | `calendarIds`が空セットの場合はクエリを発行せず`Success(emptyList())`を即座に返す（IN句の構文エラー防止） | Gemini指摘：信頼境界／防御的実装の欠落 | 本書§7.2、§10.2 T-CALSVC-11（新規） |
| B14 | 権限拒否E2E（T-E2E2-2）は、アプリプロセスをkillする`pm revoke`をテスト内（`@Before`）で実行することを禁止する。quality-runnerがテストプロセス起動前にホスト側で実行し、T-E2E2-2は他のE2Eから分離した独立実行とする | Pass1 CRITICAL：信頼境界／テスト実行方式の技術的誤り（自プロセスkillを前提にした手順） | 本書§10.2 T-E2E2-2実装注記、§11エラーマップ#20、§13 P-6 |
| B15 | `Calendars`／`Instances`クエリに`Calendars.VISIBLE = 1`のselectionを適用し、`Instances`のクエリURIは`CONTENT_URI`へbegin/endミリ秒をappendして構築する（`CalendarQuerySpec`の責務） | Gemini指摘：Android固有制約（可視カレンダー・URI構築の未定義） | 本書§8.2 |
| B16 | 手動入力の開始日時を入力必須とし、未入力では確定不可とする。ログ出力禁止対象（エラーマップ#16）に手動入力イベントのtitle・場所も含める | Pass1 CRITICAL：信頼境界／サイレント障害（バリデーション漏れ・ログ混入経路の見落とし） | 本書§7.5、§10.2 T-MANUAL-7（新規）、§11エラーマップ#14・#16 |

**上記B8〜B16もB1〜B7同様、ユーザー承認待ちの項目ではない**（Fable 5裁定として確定済み）。後続のB17〜B19（下記参照）も同様である。テストケース表（§10.2）とエラー＆レスキューマップ（§11）への反映後の件数・行数は、それぞれ全61件（正常系20／異常系13／エッジケース28。2026-08-09のADR-0014によるT-HILT-2〜4削除後の値）・全24行であり、いずれも本書内で数え直して一致させている。

### 後続レビュー（Fable 5、android-planner追加実測メモ受領後、2026-08-09）による追加裁定

android-plannerは計画書提出後、エミュレータ上での追加実測（M-1〜M-18。各項目に「実測済み（2026-08-09 emulator probe）」と出典を付す）を行い、Hilt導入可否・カレンダークエリの列挙可否・`eventStatus`/`allDay`の取扱い・E2E実行手順など、本書の複数の未確定事項（P-1〜P-8、§13）に関する新たな事実を報告した。Fable 5はこれを受けて後続セッションでレビューを実施し、以下をB17〜B19として確定した。G1レビュー由来のB8〜B16（前節）とは別セッションの裁定であるため区別して記載するが、両セッションの成果は本改訂により本書へ統合済みである（2026-08-09）。

| # | 裁定内容 | 指摘の性質・関連仕様§ | 反映箇所 |
|---|---|---|---|
| B17 | **裁定B2を取り消す**。Hilt導入をPhase 5延期からPhase 2内前倒しへ変更し、「graph-only Hilt」方式（`@HiltViewModel`/`hiltViewModel()`/`@AndroidEntryPoint`を使わず、`@HiltAndroidApp`＋モジュール＋`EntryPointAccessors`のみを導入する最小構成）で導入する。ADR-0014は起票せず、**ADR-0015**として記録する。B2の保護条件（「AppContainer＋単一Factory 1箇所集約」の維持）はgraph-only方式がそのまま満たす（ViewModelの生成点`createViewModelFactory`を1箇所のまま維持し、Hiltは`EntryPointAccessors.fromApplication`経由の依存解決にのみ使うため） | android-planner追加実測M-13〜M-18（Hilt 2.60.1のMaven Central実在・compileSdk 35適合、hilt-navigation-compose 1.4.0のminCompileSdk地雷、KSP 2.3.11採用候補、KSP2/Kotlin世代差） | 本書§4（本表）、§8.4（全面改訂）、§14 P2-C1（新設） |
| B18 | 承認状態を更新する：「Fable 5レビュー済み（両セッションの成果を統合・2026-08-09）。ユーザー承認は不要（2026-08-08ユーザー指示によるG1自動進行）。ただしHilt導入時期の裁定（ADR-0015）はユーザー拒否権を留保」とする | G1手続き（`docs/TEAMS.md`§6）の適用範囲の確定 | 本書冒頭ステータス、§3 G1、§4冒頭 |
| B19 | S-8（Calendar Listの用途）を確定する：「表示名解決用。フィルタUIはPhase 2に含めない（§88）」 | android-planner計画メモの残存論点S-8の解消 | 本書§5 F14（既存記述と整合済み） |

**上記B17〜B19もB1〜B16同様、ユーザー承認待ちの項目ではない**（Fable 5裁定として確定済み）。**唯一の例外はB17が記録するADR-0015（Hilt導入時期）であり、これについてはユーザーの拒否権を留保する**（裁定B18）。

---

## 5. 機能一覧（F番号はPhase 1のF1〜F11から連番継続）

| ID | 機能 | 仕様根拠 | 備考 |
|---|---|---|---|
| F12 | `CalendarService` 契約の新設（interface＋結果型） | §43 Services/CalendarService | シグネチャは仕様未定義のためADR記録対象（記録トリガー②）。既存4契約（PlanningEngine／RecoveryEngine／RoutingService／LocalLanguageModel）は変更しない |
| F13 | Upcoming Events読取（`CalendarContract.Instances` → `ExecutionEvent`写像） | §66「Upcoming Events」、§47 | 決定的処理のみ。LLM不使用（§15） |
| F14 | Calendar List読取（`CalendarContract.Calendars` → `CalendarSource`） | §66「Calendar List」、§47 | 可視カレンダーの列挙。**用途は表示名解決用に限定し、フィルタUIはPhase 2に含めない（裁定B19・S-8確定、§88）**。全可視カレンダー横断で読む |
| F15 | Location付きイベント抽出 | §66「Location付きイベント抽出」 | `EVENT_LOCATION`の非空判定→`locationName`。`coordinates`はPhase 2では常に`null`（§8.2） |
| F16 | READ_CALENDAR実行時権限リクエストUI | §66「Permission」、§95.4 | 事前説明カード→明示タップで要求。起動時自動要求は禁止（§95.4） |
| F17 | 権限拒否時フォールバック（手動イベント入力フォーム＋Settings導線＋再許可時の自動復帰） | §95.4／§95.6第1行 | **Fable 5裁定B1（2026-08-09）によりPhase 2スコープに確定**。最小構成（title/開始日時/場所名のみ。座標入力・地図選択・保存/再利用なし） |
| F18 | Event Selection画面の実データ化（Upcoming一覧＋次イベント強調＋選択） | §66「Event Selection」、§24／§35 Screen 1 | 既存UiState契約の変更を伴う（§6.3） |
| F19 | 構成差し替え（**graph-only Hiltへの移行〔裁定B17・ADR-0015〕は不発効・ADR-0014によりPhase 5延期確定**、`mock/MockEventSource`削除、Manifest権限追加） | Phase 1計画書§8 U6「Phase 2で削除」／ADR-0014 | 既存テスト7件の移行を伴う（§6.3）。**DI基盤（Hilt）の変更は不実施。ベースライン`:app:testDebugUnitTest` 73/73 GreenをTDD例外により実測済み（T-HILT-1、§10.2）** |
| F20 | エミュレータ用テストカレンダーseedハーネス | `docs/GOAL.md` D(1)(3)・F | adb経由seedを実測検証済み（§12実測済み手順Step 0〜7）。方式の最終確定はFable 5裁定B7によりP2-C2（旧P2-C1）完了時（**主方式は実機で一貫動作することを確認済み**） |

---

## 6. 変更対象ファイル構成

### 6.1 新規作成

| パス（`app/src/main/java/com/actionstarter/`起点） | 内容 | 担当 |
|---|---|---|
| `services/calendar/CalendarService.kt` | F12 interface＋`CalendarResult`＋`CalendarFailureReason` | domain-implementer |
| `services/calendar/CalendarProviderCalendarService.kt` | F13/F14 サービスロジック（3層分割のL2。§8.3改訂）。`CursorSource`をコンストラクタ注入し、fakeでJVMテスト | domain-implementer |
| `services/calendar/CalendarInstanceMapper.kt` | F13/F15 Cursor行→`ExecutionEvent`の純粋写像（3層分割のL1。§8.3改訂。テスト容易性の要） | domain-implementer |
| `services/calendar/CursorSource.kt` | **【新規】** `fun interface CursorSource { fun query(...): Cursor? }`（3層分割のL2/L3境界。§8.3改訂）。L2がコンストラクタ注入で受け取る抽象 | domain-implementer |
| `services/calendar/ContentResolverCursorSource.kt` | **【新規】** `CursorSource`の実装（3層分割のL3。§8.3改訂）。`contentResolver.query`を1行呼ぶだけ。テストはinstrumentedのみで行う | domain-implementer |
| `services/calendar/CalendarQuerySpec.kt` | 時刻窓・projection・selection・並び順の定数と組み立て | domain-implementer |
| `services/permission/PermissionGate.kt` | `interface PermissionGate { fun isGranted(permission: String): Boolean }` ＋ Android実装 | domain-implementer |
| `features/eventselection/ManualEventEntry.kt` | F17 手動入力フォームのComposable＋入力状態 | ui-implementer |
| `res/values/strings.xml` / `values-ja/strings.xml` へ追記 | 権限説明・拒否時案内・Settings導線・手動入力ラベル・無題イベント代替文言 | ui-implementer |
| `app/src/test/java/com/actionstarter/services/calendar/*` | F12〜F15のテスト | test-writer |
| `app/src/test/java/com/actionstarter/features/EventSelectionPermissionTest.kt` 等 | F16〜F18のテスト | test-writer |
| `app/src/androidTest/java/com/actionstarter/calendar/CalendarSeed.kt` | F20 instrumented用seed/cleanupヘルパ | test-writer |
| `scripts/seed-calendar.sh` / `scripts/cleanup-calendar.sh`（配置先は要相談） | F20 adb seed／cleanup。§12実測済み手順（Step 0〜7）をそのまま実装する。両スクリプトともStep 0のエミュレータ判定ガード（裁定B10）を先頭で必須実行する | domain-implementer |

**空プレースホルダ禁止（§88）**: `services/location`・`services/notification`・`persistence/`は本Phaseでも作らない（Phase 3／5／10まで作成しない）。

### 6.2 既存ファイルの変更

| パス | 変更内容 |
|---|---|
| `app/src/main/AndroidManifest.xml` | `<uses-permission android:name="android.permission.READ_CALENDAR"/>` 追加。**WRITE_CALENDARは追加しない**（§12で理由詳述、裁定B7） |
| `ActionStarterApplication.kt` | **【裁定B17は不発効・ADR-0014】** `@HiltAndroidApp`は付与しない。従来どおり`AppContainer(this)`生成を維持する（変更なし） |
| `features/eventselection/EventSelectionUiState.kt` | `Content(nextEvent)`単一保持 →（推奨）`Content(events: List<ExecutionEvent>)` ＋ `PermissionRequired` / `PermissionDenied` / `Error` 追加 |
| `features/eventselection/EventSelectionViewModel.kt` | `MockEventSource` → `CalendarService`＋`PermissionGate`。`refresh()`をsuspend化（`viewModelScope`） |
| `features/eventselection/EventSelectionScreen.kt` | 一覧表示・権限UI・手動入力フォームの分岐（「巨大Composable禁止」（§89）に留意し状態ごとに関数分割） |
| `navigation/ActionStarterNavHost.kt` | 権限リクエストlauncherとON_RESUME再チェックの結線、手動入力イベントの`SharedPlanViewModel.selectEvent`結線。**【裁定B17は不発効・ADR-0014】** 依存取得は従来どおり`AppContainer`（手動DI）への直接参照を維持する（`EntryPointAccessors`経由への置換は行わない） |
| **削除**: `mock/MockEventSource.kt` | Phase 1計画書§8 U6「Phase 2で削除する」を履行 |
| ~~**削除**: `di/AppContainer.kt`~~ | **【削除しない・ADR-0014】** 裁定B17（ADR-0015）は発効しなかったため`AppContainer`は削除しない。`createViewModelFactory`を含め現状のまま存続し、単一Factory集約構造（裁定B2の保護条件）を維持する |

### 6.3 既存テストの更新承認要請

`docs/TEAMS.md`§2 test-writer禁止事項「承認なしの既存テスト削除・assertion弱体化」に基づき、ケースIDと理由を明示する。

| 既存テスト | 件数 | 措置 | 理由 |
|---|---|---|---|
| `mock/MockEventSourceTest.kt`（7件：`nextEvent_withFutureEvent_returnsNonNull` 他） | 7 | **移設**（削除ではない）。T-MOCK-1/2/3/5/6の検証意図（未来イベント選択・空リスト・全件過去・翌日イベント・開始済み除外）を`CalendarInstanceMapper`/`CalendarService`のフィルタ仕様テストへ1対1で引き継ぐ。T-MOCK-8/9（`createEvent`のrequire）はMock専用APIのため廃止 | F19でMockEventSourceを削除するため。検証意図は実装先へ移る |
| `features/EventSelectionScreenTest.kt`（9件：`tSel1_...`〜） | 9 | **更新**。`EventSelectionUiState.Content`のシグネチャ変更に追随。T-SEL-1〜7のassertion強度は維持し、弱体化しない | F18のUiState契約変更 |
| `navigation/NavigationFlowTest.kt`（5件） | 5 | **更新**（T-NAV-1/T-NAV-3の起点が権限許可済み前提になるため、`shadowOf(app).grantPermissions(READ_CALENDAR)`のsetup追加） | 同上 |
| `i18n/StringResourceParityTest.kt`（3件） | 3 | 変更なし（新規stringキーを自動的に検査対象に含む） | — |

---

## 7. interface契約案

### 7.1 既存4契約は一切変更しない

`PlanningEngine`（§44）／`RecoveryEngine`（§45）／`RoutingService`（§46）／`LocalLanguageModel`（§16）は本Phaseで**変更しない**。したがって`docs/TEAMS.md`§5「interface契約のバージョン付き変更経路」の発動は不要である。

### 7.2 新設：`CalendarService`（F12。仕様未定義のため補完＝ADR必須）

```kotlin
package com.actionstarter.services.calendar

interface CalendarService {
    suspend fun readCalendars(): CalendarResult<List<CalendarSource>>

    suspend fun readUpcomingEvents(
        from: Instant,
        until: Instant,
        calendarIds: Set<String>? = null,   // null = 全可視カレンダー
        limit: Int = DEFAULT_EVENT_LIMIT    // 既定 20
    ): CalendarResult<List<ExecutionEvent>>
}

sealed interface CalendarResult<out T> {
    data class Success<T>(val value: T, val skippedRowCount: Int = 0) : CalendarResult<T>
    data object PermissionDenied : CalendarResult<Nothing>
    data class Failure(val reason: CalendarFailureReason, val cause: Throwable?) : CalendarResult<Nothing>
}

enum class CalendarFailureReason { PROVIDER_UNAVAILABLE, QUERY_FAILED }
```

**契約改訂（裁定B8。契約とエラーマップの不整合解消）**: `Success`に`skippedRowCount`を追加し、`CalendarFailureReason`から`ROW_MALFORMED`を削除した（`{ PROVIDER_UNAVAILABLE, QUERY_FAILED }`の2値）。意味論は以下のとおり明確に区別する。
- **行単位の不正**（`begin`欠損等、個々の行だけが壊れている場合）: 当該行のみをスキップし、`Success.skippedRowCount`へ計上する。全体としては`Success`のまま返す（他の正常な行は表示できるため）。
- **クエリ構造の問題**（projection列不在等、クエリ全体が成立しない場合）: `Failure(QUERY_FAILED)`とし、全体を失敗として扱う（1行だけの問題ではなく、原理的に全行が同じ問題を抱えるため）。

**`calendarIds`が空セットの場合の防御（裁定B13）**: `calendarIds`が`emptySet()`で渡された場合はクエリを発行せず、`Success(emptyList())`を即座に返す（IN句の構文エラー防止。T-CALSVC-11で検証）。

**設計根拠**:
- **戻り値をsealed resultにする理由（サイレント障害の型による排除）**: `SecurityException`（権限が実行中にOS設定で剥奪された場合。§95.6第1行「またはOS設定で後から無効化される」）や`ContentResolver.query`のnull戻り（プロバイダ不在）を、catchして`emptyList()`に潰す実装を型レベルで不可能にする。`emptyList()`縮退は「予定が0件」と「読めなかった」を区別できず、サイレント障害に該当する。
- **`skippedRowCount`を`Success`に持たせる理由（裁定B8）**: 個々の不正行のスキップ（部分的な正常系。表示件数が減るだけ）と、クエリ構造自体の失敗（全体的な異常系。一覧全体が取得できない）を型で区別する。前者を`CalendarFailureReason`（全体失敗を表す型）の一種として扱うと、呼び出し側が「一部スキップされただけ」を「全体失敗」と誤認しうるサイレント障害の温床になるため、行単位のスキップは`Success`側の付随情報として表現する。
- **`from`/`until`をパラメータにする理由（決定的処理の維持・§15）**: `Instant.now()`をサービス内部で読まず呼び出し側から注入することで、テストが固定時刻で決定的になる。Phase 1の`MockEventSource.upcomingEvents(now)`と同じ規約を継承する。
- **`limit`を持つ理由**: 数千件のカーソル全読み込みによるANR/OOMを構造的に防ぐ（エラーマップ#8）。
- **`suspend`である理由**: `ContentResolver.query`はブロッキングIOであり、実装は`withContext(ioDispatcher)`で退避する。`ioDispatcher`はコンストラクタ注入してテストで差し替える。

### 7.3 権限ゲート（F16。UIと分離してテスト可能にする）

```kotlin
interface PermissionGate { fun isGranted(permission: String): Boolean }
```

Android実装は`ContextCompat.checkSelfPermission`（`androidx.core:core-ktx` 1.16.0 に含まれる。既存依存）。Robolectricでは`shadowOf(application).grantPermissions(...)` / `denyPermissions(...)`で状態を作れる（Robolectric ShadowApplication javadoc）。**Robolectric 4.16.1での当該API名の実在は未確認**（4.7時点のjavadocで確認、P2-C2〔旧P2-C1〕probe対象＝§13 P-4。**3層分割〔§8.3改訂〕により`Robolectric.buildContentProvider()`への依存は解消されたため、P-4は権限shadow〔`grantPermissions`/`denyPermissions`〕の実在確認のみに縮小する**）。

**権限リクエストUIの配置（Phase 1計画書§10.6 疎結合規約の継承）**: `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`は`ActionStarterNavHost`（NavHost本体＝domain-implementer所有）またはEventSelection routeのラッパーで保持し、`EventSelectionScreen`は`onRequestCalendarPermission: () -> Unit` / `onOpenAppSettings: () -> Unit`をラムダ引数で受け取る。画面Composableは`ActivityResultLauncher`も`NavController`も直接参照しない。これによりP2-C5（旧P2-C4）のui-implementer/domain-implementer並列時の共有ファイル越境が発生しない（`docs/TEAMS.md`§5）。

### 7.4 権限状態の遷移仕様（F16／F17）

| 状態 | 遷移条件 | 画面 |
|---|---|---|
| `PermissionRequired`（初期・未要求） | `PermissionGate.isGranted == false` かつ `permissionRequested == false`（要求UIを一度も経由していない） | 事前説明カード＋「カレンダーへのアクセスを許可」ボタン。system dialogは自動で出さない（§95.4） |
| `Loading → Content/Empty` | 許可済み | 一覧表示 |
| `PermissionDenied` | `PermissionGate.isGranted == false` かつ `permissionRequested == true`（launcher結果が`false`、または一度要求済みの状態で読取実行中に剥奪された） | 手動入力フォーム＋再要求ボタン＋「アプリ設定を開く」導線を常に併記 |
| （再許可復帰） | ON_RESUMEで`isGranted`が`true`へ変化 | 自動でCalendar読取を再実行（§95.4「Settingsから再許可すると自動連携に復帰する」）。**ただし手動入力フォームがdirty（入力中）の間は自動遷移せず**、「カレンダー連携が有効になりました。切り替えますか？」の明示確認を挟む（エラーマップ#15、裁定B11） |

**確定裁定（Fable 5裁定2026-08-09、§15(d)解消）**: 統合サイクル（C6／旧C5）で発見された「起動前拒否から`PermissionDenied`へ構造的に到達できない」ギャップ（§15(d)参照）を解消するため、`PermissionRequired`と`PermissionDenied`の遷移条件を上表のとおり確定させた。`EventSelectionViewModel`に`SavedStateHandle`裏付けの`permissionRequested: Boolean`フラグ（既定`false`、プロセス再生成を跨いで保持）を追加し、`refresh()`の未許可分岐を`!permissionRequested`→`PermissionRequired`／`permissionRequested`→`PermissionDenied`で判定する。フラグの更新は2つの公開APIのみが行う：`onPermissionRequested()`（許可要求launcher起動直前に`ActionStarterNavHost`が呼ぶ。フラグをtrue化するのみでUI状態は変更しない）と`onPermissionDenied()`（launcher結果が`false`のときに`ActionStarterNavHost`が呼ぶ。フラグをtrue化したうえで即座に`PermissionDenied`へ遷移する）。これにより「起動前から拒否されているが要求UIを一度も経由していない」ケースは`PermissionRequired`（事前説明カードから開始）に、「要求UIを経由して拒否された（または一度要求後に読取実行中に剥奪された）」ケースは`PermissionDenied`に、それぞれ一意に対応する。

**「今後表示しない」（永久拒否）の扱い**: `shouldShowRequestPermissionRationale`による永久拒否判定は「一度要求したか」の永続化（SharedPreferences等）を要し、Phase 2に永続化層を持ち込むことになる。**推奨は永久拒否を状態として区別せず、`PermissionDenied`で常にSettings導線を併記する設計**とする。これにより「許可ボタンを押してもダイアログが出ない」無反応（サイレント障害）を、常時表示のSettings導線と「ダイアログが表示されない場合は端末の設定から許可してください」の説明文でカバーできる。永続化層の追加はPhase 10（Room／DataStore）まで持ち込まない（§88）。

**手動入力とON_RESUME自動復帰の競合防止（裁定B11）**: 手動入力フォームのdirty状態（未確定の入力があるか）は、Composeの`rememberSaveable`ではなく、`EventSelectionViewModel`（または`ManualEventEntry`に対応するViewModel）が状態として保持する。`rememberSaveable`はComposition局所の状態でありON_RESUME時の自動遷移判定（ライフサイクル横断のロジック）から参照できないため、dirty判定はViewModel側で一元管理し、入力内容が予告なく破棄されることを防ぐ。

### 7.5 手動入力イベントの表現（F17。裁定B1・最小構成）

`ExecutionEvent(id = UUID.randomUUID(), externalCalendarId = null, title = <入力>, notes = null, startDate = <入力>, locationName = <入力 or null>, coordinates = null, sourceCalendar = CalendarSource(id = "manual", displayName = ""))`。

**`displayName`に日本語/英語の文言を直接入れない**こと（§7「UI文字列の直接ハードコード禁止」）。UI側で`id == CalendarSource.MANUAL_ID`のとき`stringResource(R.string.calendar_source_manual)`へ解決する。座標入力・地図選択・保存/再利用は裁定B1により本Phaseでは作らない。

**タイムゾーン変換仕様（裁定B12）**: 手動入力の開始日時はユーザーのローカル時刻（端末表示のカレンダー・時刻ピッカー入力値）として受け取り、`ZoneId.systemDefault()`を用いて`Instant`へ変換する（T-MANUAL-1で検証）。

**開始日時の入力必須化（裁定B16）**: 開始日時はtitleと同様に入力必須とし、未入力のままでは確定不可とする（T-MANUAL-7で検証）。

---

## 8. 依存関係・技術選定の根拠

### 8.1 新規ライブラリ追加：Hiltのみ（裁定B17・ADR-0015により判定改訂）

**改訂の経緯**: 計画書提出時点では本節の見出しは「新規ライブラリ追加は不要（判定）」であり、Hilt導入を延期する裁定B2を前提としていた。裁定B17によりB2が取り消され、graph-only HiltがPhase 2内へ前倒しされたため、本節の判定を改訂する。

| 用途 | 採用 | 追加依存 |
|---|---|---|
| カレンダー読取 | `android.provider.CalendarContract`（Framework、API 14+） | なし |
| 実行時権限リクエスト | `androidx.activity.compose.rememberLauncherForActivityResult` ＋ `ActivityResultContracts.RequestPermission` | なし（`androidx.activity:activity-compose` 1.10.1 が既存） |
| 権限状態の照会 | `androidx.core.content.ContextCompat.checkSelfPermission` | なし（`androidx.core:core-ktx` 1.16.0 が既存） |
| ライフサイクル追従（ON_RESUME再チェック） | `androidx.lifecycle.compose.LocalLifecycleOwner` ＋ `LifecycleEventObserver` | **要確認**（P-3）。`lifecycle-runtime-compose`が現行の推移的依存に含まれるか未確認。含まれなければVersion Catalogへ追加＝ADR記録トリガー④ |
| IOディスパッチ | `kotlinx.coroutines.Dispatchers.IO` | **要確認**（P-2）。`kotlinx-coroutines-core`は`lifecycle-viewmodel-compose`経由で推移的に入っている想定だが実測未確認 |
| DI（graph-only Hilt。**裁定B17・ADR-0015、新規追加**） | `dagger.hilt.android` | **追加**。`hilt-android` 2.60.1／`hilt-compiler` 2.60.1（KSP経由）／Hilt Gradle plugin 2.60.1／KSP plugin 2.3.11（Maven Central実在・compileSdk 35適合をM-13/M-14で実測確認済み。KGP 2.4.10との協調はP-H1で要検証、§13）。`hilt-navigation-compose`は**追加しない**（1.4.0がminCompileSdk 37/minAGP 9.1.0を要求し導入不可＝M-15実測。将来必要ならバージョン1.3.0固定で導入可） |

**minSdkとの関係**: `minSdk 26`（ADR-0007）。READ_CALENDARはAPI 23以降dangerous permissionであり、minSdk 26では常に実行時要求が必要（API 22以下のインストール時付与分岐は不要）。`CalendarContract.Instances`はAPI 14以降で利用可能であり、minSdk 26で分岐不要。Hilt導入自体はminSdkと直接の依存関係はないが、既存4ライブラリをminCompileSdk制約で降格させた実績（ADR-0011）と同種のリスクをP-H1〜P-H3（§13）で事前検証する。

### 8.2 `Instances` を使う（`Events`ではない）根拠

API 35エミュレータでの実測（初回probe）により、繰り返しなし予定でも`Events`行の`duration`は`NULL`/`dtend`が設定される一方、繰り返し予定（`rrule`あり）は`dtend`が`NULL`で`duration`のみを持つ形式を取りうることを確認した（`Events`のカラム`rrule`/`duration`/`originalInstanceTime`の存在を実測で確認）。`Instances`はプロバイダ側が繰り返しを展開し常に`begin`/`end`を返す（**M-4・実測済み〔2026-08-09 emulator probe〕**：繰り返し予定はProviderが展開しbegin/end計算済み。`Events`は`dtend`が`NULL`のため2段クエリ不可）ため、Phase 2の「Upcoming Events」には`Instances`が正である。

**`Instances` projectionは14列すべて取得可能（M-1・実測済み〔2026-08-09 emulator probe〕）**：`event_id` / `begin` / `end` / `title` / `eventLocation` / `calendar_id` / `allDay` / `eventStatus` / `deleted` / `selfAttendeeStatus` / `description` / `calendar_displayName` / `eventTimezone` / `availability`。旧P-1（`Instances`でのprojection指定可否が未確認）は本実測により**解消済み**（§13）。当初懸念していた「`Events`との2段クエリ」は不要であり、`CalendarQuerySpec`は単一の`Instances`クエリのみを構築すればよい。

**`Instances` URIでのwhere/sort SQL押し下げが可能（M-2・実測済み〔2026-08-09 emulator probe〕）**：selection／sortOrder引数がプロバイダ側で評価される。フィルタ規則（§9 除外2〜4）をクエリ側へ押し下げる余地はあるが、過度な最適化を避けるため（§88）、本計画書は当面アプリ側（`CalendarInstanceMapper`）での判定を正とし、クエリ側の押し下げをPhase 2の必須要件とはしない。

**`Instances`はVISIBLE=0のカレンダーを自動除外しない（M-3・実測済み〔2026-08-09 emulator probe〕）**：非表示カレンダーのイベントも`Instances`クエリにそのまま含まれることを確認した。したがって`Calendars.VISIBLE = 1`のselection付与（裁定B15）は「望ましい実装」ではなく**必須要件**であり、付与を欠くと非表示カレンダーの予定が候補に混入する（エラーマップ#22、T-CALSVC-13で検証）。

**クエリ構築の仕様（裁定B15・Android固有制約。M-3により必須要件と確定）**:
1. `Calendars`／`Instances`双方のクエリに`Calendars.VISIBLE = 1`のselectionを適用する（F14「可視カレンダー」の実装定義。非表示カレンダーの予定を候補に含めない。**M-3の実測により、この付与を欠くと非表示カレンダーの予定が混入することを確認済み**）。
2. `Instances`のクエリURIは`CalendarContract.Instances.CONTENT_URI`へ`begin`/`end`（エポックミリ秒）をURIパスとしてappendして構築する（`content://com.android.calendar/instances/when/<begin>/<end>`。§12のadb実測で確認済みの形式と同一）。このURI構築はF13が定める`CalendarQuerySpec`の責務とする。

**全日イベントのbegin表現に関する申し送り（M-5・実測済み〔2026-08-09 emulator probe〕）**: 全日イベントの`begin`はUTC深夜（端末ローカル時刻の深夜ではない）で格納される。Phase 2は全日イベントを候補から除外する（裁定B5・§9除外3）ためPhase 2の写像結果には影響しないが、`begin`をそのまま端末ローカル日付として扱うと日付がずれる。Phase 4（Basic Engine、終日予定の再検討時）で`eventTimezone`列と併読する必要がある旨を申し送る（§18）。

### 8.3 テスト容易性のための3層分割（2層から3層へ強化。後続レビューによる改訂）

`CalendarInstanceMapper`（L1・Cursor 1行 → `ExecutionEvent?` の純粋写像）／`CalendarProviderCalendarService`（L2・サービスロジック。`fun interface CursorSource`をコンストラクタ注入して受け取る）／`ContentResolverCursorSource`（L3・`CursorSource`の実装。`contentResolver.query`を1行呼ぶだけ）の3層に分割する。

- **L1（`CalendarInstanceMapper`）**: MatrixCursorを手組みしてRobolectric上でテストする。写像規則（フィルタ・null処理・境界値）の網羅テストをここに集約する。
- **L2（`CalendarProviderCalendarService`）**: `CursorSource`をfake実装に差し替えてJVMテストする。`ContentResolver`にもRobolectricのContentProvider機構にも依存しない。
- **L3（`ContentResolverCursorSource`）**: `contentResolver.query`を1行呼ぶだけの薄いラッパー。ロジックを持たないため、テストはinstrumented（`src/androidTest`）のみで行い、JVM/Robolectricではテストしない。

**旧2層分割からの変更点**: 従来案は`CalendarProviderCalendarService`が`ContentResolver`を直接保持し、Robolectricの`ContentProvider`登録機構（`ShadowContentResolver.registerProviderInternal`はinternal扱いで、代替は`Robolectric.buildContentProvider()`）へ依存する設計だった。3層分割により`CursorSource`という薄い抽象を挟むことで、**`Robolectric.buildContentProvider()`への依存が完全に消える**。これによりリスクR7'（旧R7、§16）・不明点P-4（§13）の対象は「権限shadow（`grantPermissions`/`denyPermissions`）が動作するか」のみに縮小し、より複雑で実在確認が難しかったContentProvider登録機構への依存を構造的に排除できる。

### 8.4 DI方針の再確認：graph-only HiltをPhase 2で導入する（裁定B17・ADR-0015。裁定B2は取り消し）**→のちADR-0014により不発効（下記追記参照）**

ADR-0003の再検討トリガー（「Phase 2開始時」）が本Phaseで到来した。計画書提出時点ではFable 5裁定B2により「Hilt導入をPhase 5へ再延期し、ADR-0014として記録する」との判断が下されていたが、**android-plannerが追加実測（M-13〜M-18、2026-08-09 emulator probe）を行った結果、Hilt導入の実行可能性に関する未確認要素の多くが解消され、Fable 5は後続レビューにより裁定B2を取り消し、Hilt導入をPhase 2内へ前倒しする裁定B17（ADR-0015）を下した**。

**【2026-08-09追記・ADR-0014確定によりB17は不発効】** 上記裁定B17に基づきP2-C1でHilt導入を実行したところ、プローブP-H2で確定的に失敗した（Hilt Android Gradle plugin 2.60.1がAGP 9.0.0以上を必須とする内蔵チェックにより`apply`時点で失敗。実測ログ`build/agent-logs/p2c1-probe-ksp-hilt.log`、実測AGP 8.13.2）。この失敗はKSP/kapt選択とは無関係にplugin適用そのものが拒否される事象であり、下記フォールバック順の①②（KSP降格／kapt切替）では解消不能なため、Fable 5はフォールバック③（裁定B2の内容へ復帰）を適用し、**ADR-0014としてHilt導入のPhase 5延期を確定した**。ADR-0015は発効せず、以下の「実測で判明した事実」「採用方式」節はgraph-only Hilt導入の**実施されなかった設計案として参考保持**する。手動DI（`AppContainer`＋単一Factory集約）を継続する。詳細は`DECISIONS.md` ADR-0014、本書§14 P2-C1を参照。

**実測で判明した事実**:
- Hilt 2.60.1はMaven Centralに実在し、AARにaar-metadata制約がないためcompileSdk 35で導入可能（M-13/M-14。ADR-0011で4ライブラリを降格させたminCompileSdk地雷は本バージョンには存在しない）。
- ただし`hilt-navigation-compose` 1.4.0は`minCompileSdk = 37`／`minAGP = 9.1.0`を要求し、本プロジェクト（compileSdk 35・AGP 8.13.2）では**即座に導入不可**（M-15）。将来的に必要になった場合は1.3.0固定で回避できる。
- KSPは`2.4.10-*`という採番が存在せず、KGP（Kotlin Gradle Plugin）2.4.10とは独立した単独採番へ移行している。採用候補は**KSP 2.3.11**（M-16）。ただしKGP 2.4.10との協調動作は未検証（P-H1）。
- KSP2はKotlin本体から独立した設計志向であり、Hilt Gradle pluginはKotlin 2.3.21世代を前提にしている可能性がある（M-17/M-18）。KSP／Hilt Gradle plugin／KGPの3者間の互換性はP-H1／P-H2で要検証。

**採用方式（graph-only Hilt）**: `@HiltAndroidApp`を`ActionStarterApplication`に付与し、`di/AppModule.kt`（`@Module @InstallIn(SingletonComponent)`）で依存を提供する。`di/AppEntryPoint.kt`（`@EntryPoint`）を新設し、`ActionStarterNavHost`はここから`EntryPointAccessors.fromApplication`経由で依存を取得する。**`@HiltViewModel`／`hiltViewModel()`／`@AndroidEntryPoint`は使わない**。理由は、既存6件のCompose画面テスト（`createAndroidComposeRule<ComponentActivity>`使用）がHiltテストランナー（`HiltAndroidRule`等）や`@AndroidEntryPoint`化されたActivityを前提にしておらず、これらを導入すると既存テストの土台を壊すためである。ViewModelの生成点は従来どおり`createViewModelFactory`（1箇所）に集約し、`AppModule`側へ移設する。

**裁定B2の保護条件はgraph-only方式でも満たされる**: B2が課していた条件「AppContainer＋単一Factory 1箇所集約の維持」は、`AppContainer`というクラス自体は`AppModule`へ吸収され消滅するものの、「ViewModelの生成点が1箇所（`createViewModelFactory`）に集約されている」という**構造上の性質そのもの**は維持される。したがってgraph-only方式はB2の保護条件をそのまま満たしており、DIの一元管理という設計原則に矛盾しない。この保護条件の維持は、新設P2-C1（Hilt導入サイクル）自体の完了条件、および後続P2-C6（統合サイクル、旧P2-C5）のレビュー観点の両方に含める。

**追加依存**（§8.1改訂）: `hilt-android` 2.60.1／`hilt-compiler` 2.60.1（KSP経由）／Hilt Gradle plugin 2.60.1／KSP plugin 2.3.11。`hilt-navigation-compose`は追加しない。

**P-H1失敗時のフォールバック順（P2-C1手順内で解決。いずれもFable 5報告後に決定）**:
1. KSPを2.3.x系の別バージョンへ降格する。
2. KSPを諦めkaptへ切替える。
3. それでも解決しない場合は、**裁定B2の内容へ復帰**する（Phase 5延期・ADR-0014を復活させる）。

**TDD例外の適用**: Hilt導入はビルド基盤の変更であり、Red先行が構造的に不可能なため、`docs/TEAMS.md`のTDD例外規定（裁定B3が確立した「各Phaseの契約scaffoldサイクル」という先例と同種）を適用する。完了時点で全既存テストが同一件数でGreenであることの実測を必須とする（T-HILT-1、§10.2）。**【結果】ベースライン73/73 Green実測は完了したが、P-H2確定失敗によりHilt導入自体は不実施。ADR-0014でPhase 5延期を確定した（T-HILT-2〜4は対象消滅）**。

---

## 9. 写像・フィルタ規則（決定的処理。§15によりLLM禁止）

| 項目 | 規則 |
|---|---|
| `id` | `UUID.nameUUIDFromBytes("$eventId@$begin".toByteArray())`（同一インスタンスの再読込で安定。繰り返しの各回は別ID） |
| `externalCalendarId` | `"$eventId@$begin"`（**Fable 5裁定B4により確定**。ADR記録対象＝記録トリガー②「仕様未定義箇所の補完」） |
| `title` | `Instances.TITLE`。**null/空白でも行を捨てない**。UI側で`stringResource(R.string.event_untitled)`を表示 |
| `startDate` | `Instant.ofEpochMilli(Instances.BEGIN)` |
| `locationName` | `EVENT_LOCATION`をtrimし、空文字は`null`へ正規化 |
| `coordinates` | **常に`null`**（CalendarContractに座標カラムなし＝§8.2実測）。Phase 3のGeocoderで埋める |
| `notes` | `DESCRIPTION`。**あらゆるログレベルで出力禁止**（§58／§60） |
| `sourceCalendar` | `CalendarSource(id = calendarId.toString(), displayName = CALENDAR_DISPLAY_NAME ?: "")` |
| 除外1 | `begin <= from`（未来のみ。Phase 1 `MockEventSource`の`startDate.isAfter(now)`と同一セマンティクスを継承） |
| 除外2 | `deleted != 0` または `eventStatus == STATUS_CANCELED`（`= 2`）。**`eventStatus`が`NULL`（未設定）の行は除外しない**（NULLをキャンセル扱いにしない。M-6・実測済み〔2026-08-09 emulator probe〕。T-CALMAP-17で検証） |
| 除外3 | `allDay == 1`（終日予定）。**Fable 5裁定B5により本Phaseでは候補から除外することを確定**する（§4の時間モデル〔Transition/Preparation/Travel/Buffer〕は具体的な開始時刻を前提とするため）。除外する場合も「終日の予定は対象外です」を画面に明示し、黙って消さない（エラーマップ#12）。**Phase 4（Basic Engine）着手時に再検討する**。**全日イベントの`begin`はUTC深夜で格納される（M-5・実測済み）ため、除外判定は`begin`の時刻値ではなく`allDay`フラグそのものに基づく（T-CALMAP-19で検証）** |
| 除外4 | `selfAttendeeStatus == ATTENDEE_STATUS_DECLINED`（自分が辞退済み） |
| **非**除外 | `availability == AVAILABILITY_FREE`（「空き時間」設定でも移動が必要な予定はありうるため、意図的にフィルタしない） |
| 並び順 | `begin ASC`、同時刻は`event_id ASC`でtie-break（表示順の決定性を担保） |
| 時刻窓 | `from = now`、`until = now + 7日`（定数化。§6 Global-first：ロケール固有の週境界に依存しない単純な経過時間窓とする） |

---

## 10. テストケース表

### 10.1 分類定義とAndroid固有のsource set方針

本Phaseの権限連携テストは、Robolectricのshadow機構（`shadowOf(application).grantPermissions/denyPermissions`）を用いて`src/test`（JVM）上で実行する設計とする。**3層分割（§8.3改訂）により`Robolectric.buildContentProvider()`への依存は解消済みであり、残るRobolectric依存は権限shadowのみである**。これはP2-C2（旧P2-C1）のprobe（P-4、§13）が成立することを前提とした設計上の選択であり、成立しない場合はリスクR7'（§16。3層分割により構造的リスクは大幅に低減済み）の緩和策を適用する。Room migrationは本Phase対象外（Phase 10）。実カレンダーを用いたOS連携の真の検証（seed済みデータでのE2E）は`src/androidTest`のF20区分でのみ実施する。

| 分類 | source set | runner | Gradleタスク | 必要端末 |
|---|---|---|---|---|
| 純粋Domainロジック（Cursor→ExecutionEvent写像） | `src/test` | JUnit4（`MatrixCursor`使用、Android Framework依存最小） | `:app:testDebugUnitTest` | 不要 |
| Robolectric＋fake CursorSource（Service結線。§8.3改訂の3層分割によりfake ContentProviderは使わない） | `src/test` | JUnit4 + Robolectric | `:app:testDebugUnitTest` | 不要（P-4検証対象） |
| Robolectric＋Compose Test（画面・ViewModel挙動） | `src/test` | JUnit4 + Robolectric + Compose Test | `:app:testDebugUnitTest` | 不要（P-4検証対象） |
| JUnit4純JVM／Robolectric（構成差し替え検証） | `src/test` | JUnit4（一部Robolectric） | `:app:testDebugUnitTest` | 不要 |
| Compose Test（instrumented） | `src/androidTest` | AndroidJUnitRunner + Compose Test | `:app:connectedDebugAndroidTest` | 必要（エミュレータ。AVD: `actionstarter_test`。seed実行前提） |

全実行は`--console=plain`で行い、ログを`build/agent-logs/`へ保存する。

### 10.2 テストケース一覧（全61件：正常系20／異常系13／エッジケース28。裁定B9によりT-MANIFEST-1/2をゲート検証手順〔§3 G4-JVM、§14 P2-C7〕へ移設し、裁定B13/B16によりT-CALSVC-11・T-MANUAL-7を新規追加（メモ時点で55件）。後続レビューでandroid-planner追加実測メモを受け、T-HILT-1〜4（Hilt導入、正常系4）・T-CALSVC-12/13（正常系1・エッジケース1）・T-CALMAP-17/18/19（エッジケース3）の計9件を新規追加し、55件→64件へ拡大した。**2026-08-09、P2-C1プローブ実測（P-H2確定失敗）を受けたFable 5裁定（ADR-0014、Hilt導入のPhase 5延期確定）によりT-HILT-2〜4（正常系3件）を対象消滅として削除し、64件→61件へ縮小した。T-HILT-1はベースライン実測として完了済み扱いのまま残置する**）

#### F13/F15 — `CalendarInstanceMapper`（純粋Domainロジック／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-CALMAP-1 | 正常系 | 全列が揃った行が`ExecutionEvent`へ写像され、`startDate == Instant.ofEpochMilli(begin)` | CalendarInstanceMapper |
| T-CALMAP-2 | 正常系 | `eventLocation`が非空 → `locationName`に設定され、`coordinates == null` | CalendarInstanceMapper |
| T-CALMAP-3 | エッジケース | `eventLocation`が`"   "`（空白のみ）→ `locationName == null` に正規化される | CalendarInstanceMapper |
| T-CALMAP-4 | エッジケース | `eventLocation`が`NULL` → `locationName == null`（例外を投げない） | CalendarInstanceMapper |
| T-CALMAP-5 | エッジケース | `title`が`NULL`/空文字 → 行は破棄されず、`title`は空のまま写像される（UI側で代替文言） | CalendarInstanceMapper |
| T-CALMAP-6 | 正常系 | 同一`event_id`で`begin`が異なる2行（繰り返し）→ `id`が互いに異なり、各`id`は再実行でも同値（安定性） | CalendarInstanceMapper |
| T-CALMAP-7 | 異常系 | `begin`が`NULL`の行 → `null`を返して**呼び出し側でスキップし、`Success.skippedRowCount`へ計上**する（例外で全件を落とさない。裁定B8で契約改訂） | CalendarInstanceMapper |
| T-CALMAP-8 | 異常系 | projectionに無い列を`getColumnIndexOrThrow`した場合 → 例外がそのまま伝播し、呼び出し元（`CalendarProviderCalendarService`）で`Failure(QUERY_FAILED)`へ写像される（握り潰されない。裁定B8で契約改訂） | CalendarInstanceMapper |
| T-CALMAP-9 | エッジケース | `allDay == 1` の行は結果に含まれない（フィルタ除外は正常動作であり`skippedRowCount`には計上しない。裁定B5・B8） | CalendarInstanceMapper |
| T-CALMAP-10 | エッジケース | `deleted == 1` / `eventStatus == STATUS_CANCELED` の行が除外される | CalendarInstanceMapper |
| T-CALMAP-11 | エッジケース | `selfAttendeeStatus == DECLINED` の行が除外される | CalendarInstanceMapper |
| T-CALMAP-12 | エッジケース | `availability == FREE` の行は**除外されない** | CalendarInstanceMapper |
| T-CALMAP-13 | エッジケース | `begin == from`（境界ちょうど）は除外、`begin == from + 1ms`は含む | CalendarInstanceMapper |
| T-CALMAP-14 | エッジケース | DST切替を跨ぐ窓でも、`Instant`基準の窓判定が1時間ずれない | CalendarInstanceMapper |
| T-CALMAP-15 | 正常系 | 並び順が`begin ASC`、同時刻は`event_id ASC`で決定的 | CalendarInstanceMapper |
| T-CALMAP-16 | エッジケース | `limit = 20`に対し行が50件 → 先頭20件のみ返り、以降のCursor走査を打ち切る | CalendarInstanceMapper |
| T-CALMAP-17 | エッジケース | `eventStatus`が`NULL`の行は除外されない（未設定時はNULLになるが、キャンセル扱いにしない。M-6・実測済み〔2026-08-09 emulator probe〕） | CalendarInstanceMapper |
| T-CALMAP-18 | エッジケース | 端末のタイムゾーン設定を切り替えても`startDate`（`Instant`）の値が変化しない（`Instant`はタイムゾーンに依存しないエポック値であることの回帰確認） | CalendarInstanceMapper |
| T-CALMAP-19 | エッジケース | 全日イベント（`allDay == 1`）の`begin`がUTC深夜値で格納されている場合でも、除外規則（除外3）が`begin`の時刻値ではなく`allDay`フラグに基づいて正しく機能する（M-5・実測済み〔2026-08-09 emulator probe〕） | CalendarInstanceMapper |

#### F12/F13/F14 — `CalendarProviderCalendarService`（Robolectric＋fake CursorSource／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-CALSVC-1 | 正常系 | 可視カレンダー2件 → `Success(List<CalendarSource>)`が2件、`displayName`が保持される | CalendarProviderCalendarService |
| T-CALSVC-2 | 正常系 | `readUpcomingEvents`が`Success`で昇順のイベントを返す | CalendarProviderCalendarService |
| T-CALSVC-3 | 異常系 | `query`が`SecurityException`を投げる → `PermissionDenied`（例外は外に漏れない/`emptyList`へ潰さない） | CalendarProviderCalendarService |
| T-CALSVC-4 | 異常系 | `query`が`null`を返す → `Failure(PROVIDER_UNAVAILABLE)` | CalendarProviderCalendarService |
| T-CALSVC-5 | 異常系 | `query`が`IllegalArgumentException`（列不在）→ `Failure(QUERY_FAILED, cause保持)` | CalendarProviderCalendarService |
| T-CALSVC-6 | エッジケース | 予定0件 → `Success(emptyList())`（`Failure`ではない。0件と失敗を区別する） | CalendarProviderCalendarService |
| T-CALSVC-7 | エッジケース | カレンダーが1件も存在しない → `Success(emptyList())` | CalendarProviderCalendarService |
| T-CALSVC-8 | 異常系 | 例外発生時もCursorが`close`される（`use`の検証。fake providerのcloseカウンタ） | CalendarProviderCalendarService |
| T-CALSVC-9 | 正常系 | 呼び出しがテスト用ディスパッチャ上で実行される（メインスレッドでブロッキングIOしない） | CalendarProviderCalendarService |
| T-CALSVC-10 | エッジケース | coroutineキャンセル時に走査が中断される（`ensureActive`） | CalendarProviderCalendarService |
| T-CALSVC-11 | エッジケース | `calendarIds = emptySet()` → クエリを発行せず`Success(emptyList())`を即座に返す（IN句の構文エラー防止。裁定B13） | CalendarProviderCalendarService |
| T-CALSVC-12 | 正常系 | 2件以上のカレンダーを横断して`begin ASC`（同時刻`event_id ASC`）でマージ・整列され、各イベントの`sourceCalendar`が元のカレンダーと正しく対応する | CalendarProviderCalendarService |
| T-CALSVC-13 | エッジケース | `Calendars.VISIBLE = 0`のカレンダーに属する予定が結果に混入しない（`Instances`はVISIBLE=0を自動除外しないため、selectionでの除外が必須。M-3・実測済み〔2026-08-09 emulator probe〕） | CalendarProviderCalendarService |

#### F16/F17/F18 — 権限とEvent Selection（Robolectric＋Compose Test／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-PERM-1 | 正常系 | 権限未許可の初回表示で**system dialogを自動起動しない**（launcher呼び出しが0回。§95.4） | EventSelectionScreen/PermissionGate |
| T-PERM-2 | 正常系 | 事前説明カードのボタンタップで権限要求ラムダが1回だけ呼ばれる | EventSelectionScreen |
| T-PERM-3 | 正常系 | 権限許可済みで表示 → `CalendarService`が呼ばれ`Content`が表示される | EventSelectionScreen/ViewModel |
| T-PERM-4 | 異常系 | 拒否結果 → `PermissionDenied`状態になり、手動入力フォーム＋Settings導線の両方が表示される | EventSelectionScreen |
| T-PERM-5 | エッジケース | 拒否後にON_RESUMEで許可済みへ変化 → 自動で再読込し`Content`へ復帰する（§95.4） | EventSelectionScreen/ViewModel |
| T-PERM-6 | 異常系 | 読取中に`PermissionDenied`が返る（実行中の剥奪） → 空表示ではなく拒否UIへ遷移する | EventSelectionViewModel |
| T-PERM-7 | エッジケース | 権限状態のチェックがON_RESUMEごとに行われ、画面回転では二重読込しない | EventSelectionScreen/ViewModel |
| T-SEL2-1 | 正常系 | 3件のイベントが開始時刻昇順で表示され、先頭が「Next event」として強調される（§35 Screen 1） | EventSelectionScreen |
| T-SEL2-2 | 正常系 | 任意の1件のタップで`planReview`へ遷移し、選択したイベントが共有される | EventSelectionScreen |
| T-SEL2-3 | エッジケース | 0件（権限あり・予定なし）→ 既存の空状態文言が表示されクラッシュしない | EventSelectionScreen |
| T-SEL2-4 | エッジケース | `locationName == null`のイベントは場所行が非表示（既存T-SEL-4を継承） | EventSelectionScreen |
| T-SEL2-5 | エッジケース | `title`が空のイベントは代替文言が表示される（無題イベントを不可視にしない） | EventSelectionScreen |
| T-SEL2-6 | 異常系 | `Failure`（プロバイダ異常）→ 空状態ではなくエラー表示＋再試行導線が出る（0件と失敗をUI上で区別） | EventSelectionScreen |
| T-SEL2-7 | 正常系 | ja/en双方で新規文言が非空かつ異なる | EventSelectionScreen(i18n) |
| T-MANUAL-1 | 正常系 | title＋開始日時を入力して確定 → `ExecutionEvent`が生成され`planReview`へ遷移する（§95.6）。生成される`startDate`は入力値を`ZoneId.systemDefault()`で`Instant`変換した値と一致する（裁定B12） | ManualEventEntry |
| T-MANUAL-2 | 異常系 | titleが空白のみ → 確定ボタンが無効、理由が表示される | ManualEventEntry |
| T-MANUAL-3 | 異常系 | 開始日時が過去 → 確定は可能だが「すでに開始時刻を過ぎています」を表示（自動補正しない。§34ユーザー最終決定） | ManualEventEntry |
| T-MANUAL-4 | エッジケース | 場所未入力で確定 → `locationName == null`のイベントが生成される | ManualEventEntry |
| T-MANUAL-5 | エッジケース | 生成イベントの`sourceCalendar.id == "manual"`であり、`displayName`にハードコード文言が入っていない（§7） | ManualEventEntry |
| T-MANUAL-6 | エッジケース | 入力途中で画面回転しても入力内容が失われない（`rememberSaveable`） | ManualEventEntry |
| T-MANUAL-7 | エッジケース | 開始日時が未入力 → 確定ボタンが無効になる（裁定B16） | ManualEventEntry |
| T-NAV2-1 | 正常系 | 権限拒否→手動入力→PlanReview→Executionの通しフローが成立する | NavHost（統合フロー） |

#### F19（Hilt導入部分） — graph-only Hilt（`src/test`／`:app:testDebugUnitTest`／端末不要。**P2-C1・TDD例外〔裁定B3の系〕**）。**【2026-08-09結果】P2-C1プローブ実測でHilt導入不成立。ADR-0014によりPhase 5延期確定。T-HILT-2〜4は対象消滅（下記参照）**

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-HILT-1 | 正常系 | Hilt導入前のベースラインとして`:app:testDebugUnitTest`を実測し、実行件数を確定する（既存メモ間の件数不一致「72/73/69」を実測値で確定。S-6解消） | ビルド全体（ベースライン測定） |
| ~~T-HILT-2~~ | ~~正常系~~ | **【削除・対象消滅（ADR-0014）】** ~~`@HiltAndroidApp`＋`AppModule`＋`AppEntryPoint`導入後、`:app:testDebugUnitTest`がT-HILT-1と同一件数ですべてGreenになる（回帰なし）~~ Hilt自体を導入しないため対象消滅 | — |
| ~~T-HILT-3~~ | ~~正常系~~ | **【削除・対象消滅（ADR-0014）】** ~~既存6件のCompose画面テスト（`createAndroidComposeRule<ComponentActivity>`使用）が変更なしでGreenのまま動作する（`@HiltViewModel`/`hiltViewModel()`/`@AndroidEntryPoint`を使わない設計の検証）~~ Hilt自体を導入しないため対象消滅（既存Compose画面テストはHiltと無関係に動作継続する） | — |
| ~~T-HILT-4~~ | ~~正常系~~ | **【削除・対象消滅（ADR-0014）】** ~~`ActionStarterNavHost`が`EntryPointAccessors.fromApplication(context, AppEntryPoint::class.java)`経由で`CalendarService`等の依存を正しく解決できる~~ `EntryPointAccessors`自体を導入しないため対象消滅 | — |

**T-HILT-1実測結果（2026-08-09）**: `:app:testDebugUnitTest` 73/73 Green（`build/agent-logs/p2c1-baseline.log`、`app/build/test-results/testDebugUnitTest/`JUnit XML集計でtests=73・failures=0を確認）として完了。件数不一致「72/73/69」は本実測により**73**で確定した（S-6解消）。以後の対応表（§10.2ヘッダ・§3 G2・§14 P2-C3）ではT-HILT-1のみを完了済み1件として計上する。

**実装注記（結果確定・2026-08-09）**: TDD例外を適用した（裁定B3の系。基盤変更のためRed先行不能）。P-H2（Hilt Gradle plugin/AGP世代）が確定失敗し、§8.4のフォールバック順に従いFable 5へ報告のうえADR-0014（Phase 5延期）へ復帰した。P-H1（KSP/KGP協調）・P-H3（Robolectric×`@HiltAndroidApp`の共存）はP-H2失敗によりビルド自体が成立せず検証機会が生じなかったため対象消滅（ADR-0014、§13参照）。T-HILT-2〜4は上記のとおり対象消滅した。

#### F19（構成差し替え・その他） — JUnit4純JVM／Robolectric／`src/test`／`:app:testDebugUnitTest`／端末不要

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-DI-1 | 正常系 | `AppContainer`の`createViewModelFactory`から`EventSelectionViewModel`が生成できる（単一Factory集約点が保たれることの検証。**ADR-0014によりAppModuleではなくAppContainerが対象**） | AppContainer（Robolectric、Contextが必要） |
| T-DI-2 | 異常系 | `com.actionstarter.mock.MockEventSource`がsrc/mainに存在しないこと（U6の履行）。**`com.actionstarter.di.AppContainer`は存続する（ADR-0014によりAppModuleへの吸収は発生しないため、非存在の検証対象から除外）** | パッケージ構成（JUnit4純JVM） |

**マージ済みManifest検証の移設（裁定B9）**: 旧`T-MANIFEST-1`（`READ_CALENDAR`含有確認）・`T-MANIFEST-2`（release変種に`WRITE_CALENDAR`が含まれないことの確認）は、P2-C2（旧P2-C1）のManifest変更完了時点で即座にGreenとなりRed化不能（G2定義「単なるコンパイルエラー等ではなく期待値差分によるRed」の対象外）であり、かつJVMテストからのマージ済みreleaseマニフェスト取得は実行方式が不安定なため、**テストケースからは削除し「ゲート検証手順」へ移す**。P2-C7（G4-JVM、旧P2-C6）でquality-runnerがマージ済みマニフェスト成果物（`build/intermediates/merged_manifests/{debug,release}/AndroidManifest.xml`）をスクリプトで検証する（§3 G4-JVM、§14 P2-C7参照）。

#### F20 — instrumented E2E（`src/androidTest`／`:app:connectedDebugAndroidTest`／エミュレータ必要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-E2E2-1 | 正常系 | seed済みの実カレンダー予定が一覧に表示され、選択→PlanReview→Execution→Departureが通る（§66完成条件・GOAL.md D(1)） | E2Eフロー（全画面） |
| T-E2E2-2 | 異常系 | READ_CALENDAR拒否状態で起動 → 手動入力フォームが表示され、アプリが継続動作する（GOAL.md D(3)/F） | E2Eフロー（権限拒否シナリオ） |
| T-E2E2-3 | エッジケース | seed済み予定が0件 → 空状態が表示されクラッシュしない | E2Eフロー |
| T-E2E2-4 | 正常系 | ja/en両ロケールで全画面スクリーンショットを取得する（GOAL.md D/E） | E2Eフロー（i18n） |

**T-E2E2-2実装注記（裁定B14で修正）**: 権限ダイアログの自動操作を伴う。`GrantPermissionRule`は「許可」しか作れないため、拒否シナリオが必要である。ただし実行時権限の`revoke`は当該アプリのプロセスをkillするため、**テスト内（`@Before`）でのpm revoke実行は禁止**とする。quality-runnerがテストプロセス起動前に**ホスト側**で`adb shell pm revoke <pkg> android.permission.READ_CALENDAR`を実行し、その後にT-E2E2-2のみを他のE2Eケースから分離した独立の実行（別の`am instrument`／Gradle`--tests`指定呼び出し）として起動する。同一テストプロセス内で他のE2Eケースと連続実行しない。**このrevoke手法の当該エミュレータでの動作は未確認**（P-6、§13）。

E2E群は実行するまでpassとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ）。

---

## 11. エラー＆レスキューマップ（全24行。ハンドリング方法列に空欄なし。#22〜#24は後続レビューによる追加）

| # | 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|---|
| 1 | Event Selection初回表示 | READ_CALENDAR未許可 | `PermissionRequired`状態へ写像し、事前説明カードと明示ボタンを表示。system dialogは自動起動しない（§95.4） | 何が読まれるかを理解した上で許可を選べる。拒否しても画面は機能する |
| 2 | 権限リクエスト | ユーザーが「許可しない」を選択 | `PermissionDenied`へ写像。手動イベント入力フォーム＋再要求ボタン＋アプリ設定導線を同時に提示（§95.6第1行） | 自動取得はできないが、手動入力でTransition〜Recoveryの全機能を継続利用できる |
| 3 | 権限再リクエスト | 永久拒否によりsystem dialogが表示されず結果が即`false`で返る | 状態を`PermissionDenied`のまま維持し、「ダイアログが表示されない場合は端末の設定から許可してください」の説明＋Settings導線を常時併記する（無反応ボタンにしない） | ボタンが効かない理由が画面上で説明され、設定への到達手段が示される |
| 4 | カレンダー読取中 | 実行中にOS設定で権限が剥奪され`SecurityException` | `CalendarResult.PermissionDenied`へ写像し、拒否UIへ遷移。例外はcatchするが握り潰さず状態として表出させる（§95.6第1行） | 一覧が消えた理由が表示され、手動入力へ切り替えられる |
| 5 | `ContentResolver.query` | 戻り値が`null`（プロバイダ不在・無効化された端末） | `Failure(PROVIDER_UNAVAILABLE)`。空状態ではなくエラー表示＋手動入力導線 | 「予定なし」と誤解させず、手動入力で先へ進める |
| 6 | Cursor列アクセス | projection外の列参照・型不一致で`IllegalArgumentException` | `Failure(QUERY_FAILED, cause)`で`cause`を保持しログへ**例外種別のみ**記録（本文・タイトルは記録しない）。裁定B8により`CalendarFailureReason`は`PROVIDER_UNAVAILABLE`/`QUERY_FAILED`の2値に統一し、行単位の不正（#7）とクエリ構造の不正（本行）を型で区別する | 一覧取得に失敗した旨と再試行導線が表示される |
| 7 | 個別行の写像 | `begin`欠損等の不正行 | 当該行のみスキップし、スキップ件数を`Success.skippedRowCount`として保持する（裁定B8で契約に正式化）。全件を落とさない | 一部の壊れた予定が表示されないだけで、他の予定は正常に選べる |
| 8 | 大量イベント読取 | 数千件のカーソル全走査によるANR/OOM | `limit`（既定20）到達で走査を打ち切り、`withContext(ioDispatcher)`でメインスレッドを塞がない | 画面が固まらず、直近の予定が即座に表示される |
| 9 | Cursorのライフサイクル | 例外発生時にCursorが閉じられずリーク | `cursor.use { }`で必ずclose。T-CALSVC-8で閉鎖を検証 | 長時間利用でも動作が劣化しない |
| 10 | coroutineキャンセル | 画面破棄後も走査が続く | ループ内で`ensureActive()`を呼び、キャンセル時に即中断 | 画面を離れた後の無駄な処理・電池消費が発生しない |
| 11 | 候補0件 | 予定なし/全件過去/終日のみ/全件辞退済み | `Success(emptyList())`→ 既存の`Empty`状態へ写像（`Failure`と明確に区別） | 「予定なし」の案内が表示され、クラッシュしない |
| 12 | 終日予定の扱い | 開始時刻を持たない予定がPlan生成対象になりうる | 候補から除外し、「終日の予定は対象外です」を注記として明示する（黙って消さない。裁定B5） | 終日予定が出てこない理由が分かる |
| 13 | 無題イベント | `title`がnull/空で行が不可視になる | 行を破棄せず、UIで`event_untitled`文言を表示 | タイトル未設定の予定も選択できる |
| 14 | 手動入力の検証 | titleが空白のみ/開始日時が過去/開始日時が未入力 | 空白titleおよび開始日時未入力は確定不可（理由表示。裁定B16）。過去日時は警告表示のみで確定は許可し、自動補正しない（§34） | 誤入力が防がれ、意図的な過去日時の入力もユーザー判断で通せる |
| 15 | 手動入力と再許可の競合 | 手動入力中にON_RESUMEで権限が許可され、入力内容が破棄される | 入力中（フォームがdirty）の間は自動遷移せず、「カレンダー連携が有効になりました。切り替えますか？」の明示操作を挟む。dirty状態は`rememberSaveable`ではなくViewModelが保持する（裁定B11） | 入力内容が予告なく消えることがない |
| 16 | ログ・外部送信 | カレンダーのtitle/notes/locationがログや将来のTelemetryへ混入（手動入力イベントのtitle・場所も含む。裁定B16） | Domain/Service層でこれらのフィールドをログ出力しない規約とし、G4のコード差分レビュー観点に加える（§58/§60） | 予定の中身が端末外・ログへ出ない |
| 17 | Mock削除に伴う回帰 | `MockEventSource`削除で既存7テストが消え、検証意図が失われる | 削除ではなく検証意図の移設とし、対応表（§6.3）を計画書に明記。移設後の件数をG3証拠に含める | 開発プロセス上の担保 |
| 18 | E2Eのseedデータ | seedが残存し他テストへ影響/二重seedで重複表示 | seedスクリプトを冪等化（実行前に同一account配下を全削除）し、`@After`でも削除。実行前後に件数をログ出力 | テスト結果の再現性が保たれる |
| 19 | adb経由のseed | `content insert`が`DeadObjectException`で失敗（実測発生） | 失敗を検知して1回リトライし、なお失敗なら**実行不能としてそのまま報告**する（推測でGreenと報告しない。`docs/TEAMS.md`§7） | G4-Eの証拠が揃わない旨が明示され、未達のまま次Phaseへ進まない |
| 20 | 権限拒否E2E | 権限ダイアログが自動テストをブロックする。`pm revoke`はアプリプロセスをkillするためテスト内実行は不能。**また`pm revoke`は終了コード0・無出力でも実際には権限が剥奪されていない場合がある（対象権限が未宣言の場合等のサイレント障害。M-10・実測済み〔2026-08-09 emulator probe〕）** | quality-runnerがテストプロセス起動前にホスト側で`pm revoke`を実行し拒否状態を作った上でT-E2E2-2を独立実行する（アプリ内`@Before`でのrevokeは禁止。裁定B14）。**`pm revoke`の終了コードを成功判定に使わず、必ず`adb shell dumpsys package <pkg> \| grep "android.permission.READ_CALENDAR: granted="`で`granted=false`を確認したうえでテストを実行する（M-10）**。テスト中はダイアログを起動しない経路のみ操作する | 開発プロセス上の担保。誤って権限が残ったままテストが「成功」と誤認されることを防ぐ |
| 21 | seed/cleanup実行 | 実機・意図しないデバイスへの誤実行 | 実行前に対象がエミュレータであることを検証するガード（`adb -s <emulator serial>`の明示指定＋`getprop ro.kernel.qemu`等の確認）を設け、不成立時は一切の書込/削除をせず即中断する（裁定B10） | 実カレンダーデータが保護される |
| 22 | Calendar List／Upcoming Events読取 | `Calendars.VISIBLE = 0`のカレンダーが`Instances`クエリから自動除外されない（M-3・実測済み） | `Calendars.VISIBLE = 1`のselectionを`Calendars`／`Instances`双方のクエリに必須付与する（裁定B15。T-CALSVC-13で検証） | 非表示に設定したカレンダーの予定が候補に混入しない |
| 23 | イベントの写像 | `eventStatus`が`NULL`（未設定）の行を誤ってキャンセル済みとして除外してしまう（M-6・実測済み） | 除外条件を`eventStatus == STATUS_CANCELED`（`= 2`）の場合のみとし、`NULL`は除外しない（T-CALMAP-17で検証） | 未設定状態の正常な予定が消えずに表示される |
| 24 | Hilt導入（P2-C1） | KSP／KGP／Hilt Gradle pluginのバージョン協調が失敗する（P-H1/P-H2/P-H3のいずれか不成立） | フォールバック順（§8.4）：①KSPを2.3.x系の別バージョンへ降格②kaptへ切替③それでも不可なら裁定B2の内容（Phase 5延期・ADR-0014）へ復帰。**いずれの段階でもFable 5への報告を必須とし、無断でフォールバックを選択しない**。**【結果注記・2026-08-09】発生し、ADR-0014で解消。P-H2でHilt Android Gradle plugin 2.60.1がAGP 9.0.0以上を必須とすることが判明（実測AGP 8.13.2、`p2c1-probe-ksp-hilt.log`）。KSP/kaptと無関係にplugin適用自体が拒否される事象のためフォールバック①②は適用不能と判断し、フォールバック③（Phase 5延期）を適用してADR-0014を確定した** | Phase 2のクリティカルパスがビルド基盤問題で止まらず、必要なら旧方針へ安全に戻せる |

---

## 12. エミュレータ上のテストカレンダーseed（実測検証済み・裁定B7）

AVD `actionstarter_test`は`system-images/android-35/google_apis/x86_64`。実測結果:

**確認できたこと**:
1. `com.android.providers.calendar`パッケージが存在（google_apisイメージにCalendarProvider同梱）。
2. sync adapter URIでのローカルカレンダー作成が成功する（`caller_is_syncadapter=true`＋`account_name`＋`account_type=LOCAL`をURIクエリとbindの両方に与える必要がある）。
3. `content://com.android.calendar/events`へのイベント挿入が成功する。
4. `content://com.android.calendar/instances/when/<begin>/<end>` に `--projection event_id:begin:end:title:eventLocation:calendar_id` でクエリすると、挿入イベントが展開済みインスタンスとして返る。
5. `Events`テーブルの全カラムダンプにより、**緯度経度カラムが存在しない**こと、`eventLocation`が自由記述文字列であること、`rrule`/`duration`/`originalInstanceTime`/`eventStatus`/`deleted`/`selfAttendeeStatus`/`availability`/`allDay`/`eventTimezone`の存在を確認。
6. 作成したprobeデータは削除し、`calendars`/`events`が0件であることを確認した。

**確認できなかったこと／注意事項**:
- probe中に`content insert`が一度`android.os.DeadObjectException`で失敗し、その後復帰した。さらにprobe終盤で**エミュレータプロセス自体が消失した**。このため`Instances`に対する一部projection可否は**未確認**のまま残る。また最後のprobeで作成しようとした`probe2@local`カレンダー/`InstProbe`イベントが**残存している可能性がある（未確認）**。seedスクリプトは必ず冪等（事前全削除）にすること。
- WSL再起動で`/dev/kvm`のパーミッションが660へ戻る可能性がある（Phase 1申し送り事項）。

**採用方針（Fable 5裁定B7）**:
- **主方式（既定）**: quality-runnerが`connectedDebugAndroidTest`の直前にadb seedスクリプトを実行し、直後にcleanupする。**アプリ側に`WRITE_CALENDAR`を追加せずに済む**ため、§58 Privacy-firstおよびPlay審査リスク（§95.5）の観点で最も安全。
- **副方式（主方式が不安定な場合のみ）**: `src/androidTest`からContentResolverで挿入する。ただしinstrumentedテストのコードはアプリ本体のプロセス/UIDで動くため、`WRITE_CALENDAR`をアプリ側のマニフェストに宣言する必要がある（一般的なInstrumentationの仕組みに基づく推定・未実測＝P-5）。採用する場合は`app/src/debug/AndroidManifest.xml`にのみ宣言し、releaseへ混入しないことをP2-C7（旧P2-C6）のマージ済みマニフェスト検証手順（旧T-MANIFEST-2、裁定B9）で機械的に検証する。
- **最終確定のタイミング**: 裁定B7により、上記いずれを採用するかはP-5／P-6のprobe結果を受けて最終確定する。**android-plannerの追加実測（下記Step 0〜7）により主方式（adb seed）がStep 0からStep 7まで一貫して実機で動作することを確認済みであり、事実上主方式が確定している。P-6（`pm revoke`後の拒否UI表示）はM-10により検証手順が確立された（dumpsysでの確認必須）。残るP-5（副方式に必要な権限宣言配置）は主方式を採用する限り不要であり、主方式が失敗した場合のフォールバックとしてのみ検証対象に残る。**新設P2-C1（Hiltサイクル）完了後、P2-C2（旧P2-C1、probe＋契約scaffold）完了時点で正式に`DECISIONS.md`へ最終確定を記録する。

**エミュレータ判定ガード（裁定B10・データ安全性の必須要件）**: seed／cleanupスクリプトは、実行前に**対象デバイスがエミュレータであることを検証するガード**を備えることを必須とする。具体的には、(a) `adb -s <emulator serial>`でシリアルを明示指定し、(b) `adb -s <serial> shell getprop ro.kernel.qemu`が`1`であること（またはそれに準ずるエミュレータ判定プロパティの確認）を検証する。**判定に失敗した場合はいかなる書込／削除も実行せず即座に中断する**。これにより実機や意図しないデバイスへの誤実行を構造的に防止する（エラーマップ#21）。

### 12.1 実測済みE2Eハーネス手順（android-planner追加実測、2026-08-09 emulator probe。P2-C8〔旧P2-C7〕で実行）

第1回probe（上記）に続き、android-plannerはseedからE2E実行までの全手順を実機で検証し、以下のStep 0〜7として確定した。quality-runnerはP2-C8でこの手順をそのまま実行する。

- **Step 0（破壊的操作ガード。裁定B10の実装）**: `adb shell getprop ro.kernel.qemu`が`1`であること、および`adb shell getprop ro.boot.qemu.avd_name`が`actionstarter_test`であることを確認する。いずれかが不成立ならいかなる書込も行わず即座に中断する。
- **Step 1（コールドブート）**: `-no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect`オプションでエミュレータをコールドブートし、`sys.boot_completed`プロパティが`1`になるまでポーリングする（実測で約15秒）。
- **Step 2（冪等cleanup）**: sync-adapter URI（`caller_is_syncadapter=true&account_name=probe@local&account_type=LOCAL`）でカレンダーを削除し、`calendars`/`events`/`instances`の3テーブルが空であることを確認する。
- **Step 3（ローカルカレンダー作成）**: `account_type=LOCAL`でカレンダーを作成する（Googleアカウント不要。URIクエリとbindの両方に`account_name`/`account_type`の指定が必要。実測で`_id=1`が生成されることを確認済み）。複数カレンダーが必要な場合は`probe2@local`等で同手順を繰り返す。
- **Step 4（イベント投入・3種）**: 通常予定／全日予定／繰り返し予定の3種を投入する。**`rrule`の`;`区切り文字はシェル上でクォート必須**（クォートを外すと無関係な「`eventTimezone`必須」エラーが出る、実測で確認済みの罠）。`eventTimezone`は全イベント種別で必須。
- **Step 5（seed検証）**: `instances/when/<from>/<until>`への14列projection（M-1）で期待どおり5行が返ることを確認する。件数が一致しない場合はテストを実行せず、「実行不能」として報告する（推測でGreenと報告しない）。
- **Step 6（権限E2E。裁定B14の実装）**: ①ホスト側で`pm revoke`を実行 ②`adb shell dumpsys package com.actionstarter | grep "android.permission.READ_CALENDAR: granted="`で`granted=false`を確認（**終了コードは信用しない。M-10**）③`CalendarPermissionDeniedTest`（T-E2E2-2相当）を他のE2Eケースから独立実行 ④`pm grant`で権限を戻す ⑤`granted=true`を確認 ⑥残りのE2Eケースを実行する。**`pm revoke`はアプリプロセスをkillするため、テスト内`@Before`での実行は禁止**（裁定B14で既に確定済みの制約と整合）。
- **Step 7（cleanup）**: Step 2を再実行し、テストカレンダーを全削除する。

**環境注記**: 本手順の実測時点（2026-08-09）で`emulator-5554`は起動中であり、probeデータは全て削除済みで再利用可能な状態にある。

**P2-C8fix2追記（ヒーロー行clickable欠陥修正の再実測、2026-08-09。§14 P2-C8・§15(f)参照）**: 上記Step 0〜7に加え、本サイクルの実測で確認・確定した運用上の留意点を追記する。

- **アニメーション無効化（前提条件）**: E2E実行前に`adb shell settings put global window_animation_scale 0` / `transition_animation_scale 0` / `animator_duration_scale 0`が0であることを確認する（本サイクルではStep 0のガード直後に3値とも`0`であることを実測確認済み）。無効化されていないとComposeの`waitForIdle()`／`performClick()`のアイドル判定がアニメーション中の状態と競合し間欠的に不安定化するおそれがある。
- **実行順序＝0件テストを先行させる**: `tE2e2_3_zeroSeededEvents_showsEmptyStateWithoutCrash`はStep 4（イベント投入）前、またはStep 7（cleanup）後の「カレンダーが空」の状態でのみ意味のある検証になるため、他のT-E2E2ケース（seed済み状態を前提とする1・2・4）とは別の実行（別のカレンダー状態で起動し直したテストプロセス）として、時系列上は先行させる（`build/agent-logs/p2c8fix-stage-a-tE2e2_3-result.xml`でtests=1 failures=0を確認済み）。
- **各段階で`adb install -r`を挟む**: 本サイクルではmain側コード変更（`EventSelectionScreen.kt`）を反映させるため、`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`でビルドした2つのAPK（`app-debug.apk`／`app-debug-androidTest.apk`）をそれぞれ`adb install -r`で明示的に反映してから`pm grant`→`dumpsys`確認→`connectedDebugAndroidTest`の順で実行した（`build/agent-logs/p2c8fix2-e2e-install.log`／`p2c8fix2-e2e-grant.log`）。`connectedDebugAndroidTest`タスク自身のinstallタスクに任せきりにせず明示的に`install -r`を挟むことで、コード変更が確実に反映された状態でのpermission grantの再現性を高める。
- **UiAutomator依存とOSダイアログ実文言（tE2e2_2／`CalendarPermissionDeniedTest`向け）**: OS権限ダイアログ（`com.google.android.permissioncontroller`）はComposeのセマンティクスツリー外にあるため`ComposeTestRule`では操作できず、`UiDevice`（UiAutomator）による resource-id 指定操作が必須となる。「Don't allow」ボタンの実文言はタイポグラフィックアポストロフィ（**U+2019**、ASCIIの`'`＝U+0027ではない）を含むため、テキスト一致ではなく resource-id（`com.android.permissioncontroller:id/permission_deny_button`。package名は`By.pkg`が照合する`com.google.android.permissioncontroller`とは異なる点に注意）で選択する。詳細・実測根拠は`CalendarPermissionDeniedTest.kt`冒頭KDoc（2026-08-09実測）を参照。本項目は本サイクルでは再実行していないが、P2-C8fixで確立済みの手順としてここに集約する。

---

## 13. 検証が必要な不明点リスト（P2-C1〔Hilt関連〕・P2-C2〔probe〕対象）

| # | 項目 | 確定方法 | 未確定時の影響 |
|---|---|---|---|
| P-1 | ~~`Instances`のprojectionで`description`/`calendar_displayName`/`allDay`/`eventStatus`/`deleted`/`selfAttendeeStatus`/`eventTimezone`/`availability`が指定可能か~~ **【解決済み】** 14列すべて指定可能（M-1・実測済み〔2026-08-09 emulator probe〕）。`Instances`→`Events`の2段クエリは不要と確定（M-4） | 解決済み（エミュレータ実測） | 影響なし（解決済み。§8.2参照） |
| P-2 | `kotlinx.coroutines.Dispatchers.IO`が現行依存構成で解決できるか | `./gradlew :app:dependencies`で確認 | 依存追加が必要ならVersion Catalog変更＝ADR記録トリガー④ |
| P-3 | ON_RESUME検知に`lifecycle-runtime-compose`の追加が必要か | 同上 | 追加が必要ならADR。代替は`DisposableEffect`＋`LifecycleEventObserver` |
| P-4 | **（3層分割・§8.3改訂により対象を権限shadowのみへ縮小）** Robolectric 4.16.1で`shadowOf(app).grantPermissions/denyPermissions`が動くか。`Robolectric.buildContentProvider()`への依存は3層分割により消滅したため検証不要になった | 最小テスト1本を実行 | テスト戦略（JVM中心）の成立可否。**本Phaseの前提**であり最優先で潰す |
| P-5 | instrumentedテストからのカレンダー書込に必要な権限宣言の配置（副方式用） | `src/debug/AndroidManifest.xml`有無で最小テスト実行 | **主方式（adb seed、§12実測済み手順）を採用する限り不要**。主方式が失敗した場合のフォールバックとしてのみ検証対象（seed方式の最終確定は§12、裁定B7） |
| P-6 | ~~ホスト側で`adb shell pm revoke`を実行した後、新規起動したテストプロセスで拒否UIが表示されるか（裁定B14方式）~~ **【解決済み】** `pm revoke`は終了コード0でも実際には権限が剥奪されていない場合があるため、`dumpsys package <pkg> \| grep "android.permission.READ_CALENDAR: granted="`で`granted=false`を確認する手順を確立（M-10・§12 Step 6） | 実測済み | 影響なし（解決済み。手順どおりP2-C8で実行） |
| P-7 | `EVENT_LOCATION`の実態分布（住所/店名/会議室名/URL等） | Phase 3のGeocoder設計時に再検討 | Phase 2への影響なし。Phase 3への申し送り |
| P-8 | エミュレータの安定性（プロセス消失を観測） | **軽減策を実測で確立**（コールドブートオプション＋`sys.boot_completed`ポーリング、約15秒。§12 Step1） | 軽減策はあるが根本的な安定性リスクはR10'として残存 |
| P-H1 | ~~KSP 2.3.11とKGP（Kotlin Gradle Plugin）2.4.10の協調動作可否（M-16）~~ **【対象消滅（ADR-0014）】** P-H2が確定失敗（plugin適用時点でビルド不能）したため、KSP/KGP協調を検証する機会自体が生じなかった | 対象消滅につき実施せず | 影響なし（対象消滅。§8.4・`DECISIONS.md` ADR-0014参照） |
| P-H2 | ~~Hilt Gradle plugin 2.60.1（Kotlin 2.3.21世代想定）とプロジェクトのKotlin 2.4.10との互換性（M-17/M-18）~~ **【実測済み・失敗確定】** Hilt Android Gradle plugin 2.60.1はAGP 9.0.0以上を必須とする内蔵チェックにより`apply`時点で失敗（実測AGP 8.13.2） | 実測済み（`build/agent-logs/p2c1-probe-ksp-hilt.log`） | 影響なし（実測により確定。ADR-0014でHilt導入をPhase 5延期） |
| P-H3 | ~~`@HiltAndroidApp`付与後、既存Robolectric JVMテストが追加設定なしにそのまま動作するか（`HiltTestApplication`が必要にならないか）~~ **【対象消滅（ADR-0014）】** P-H2が確定失敗したため、`@HiltAndroidApp`を付与する段階に到達せず検証機会が生じなかった | 対象消滅につき実施せず | 影響なし（対象消滅。§8.4・`DECISIONS.md` ADR-0014参照） |

**未検証事項一覧（要検証・P2-C2〔probe〕で確定）**: P-2／P-3／P-4（権限shadowのみ）／P-5（主方式採用時は優先度低）。**P-H1／P-H2／P-H3はP2-C1で実測済み・確定済み（P-H2失敗確定、P-H1/P-H3対象消滅。ADR-0014）のため本一覧から除外する**。C2のprobeで解決しない依存関係は、C3（Red）でのコンパイル・テスト実装時に併せて最終確認される。

---

## 14. PDCAサイクル分解（P2-C1〜C8。裁定B17によるHilt導入サイクル新設で8サイクル構成へ拡張。Phase 1のC1〜C7形式を踏襲）

**改訂の経緯**: 裁定B17（B2取り消し・graph-only Hilt導入）により、新規サイクルP2-C1（Hilt導入）を挿入した。これに伴い旧P2-C1〜C7はそれぞれ1つずつ繰り下げ、旧P2-C1→新P2-C2、旧P2-C2→新P2-C3、…、旧P2-C7→新P2-C8となった。以下は繰り下げ後の最終構成である。

| サイクル | 内容 | 担当agent（Do） | 到達ゲート |
|---|---|---|---|
| **P2-C1** | **実施済み（結果: Hilt導入不成立→ADR-0014確定）**。成果物=ベースライン実測73/73（T-HILT-1完了）＋P-H2失敗の実測記録＋ADR-0014。（当初計画：`@HiltAndroidApp`付与＋`di/AppModule.kt`／`di/AppEntryPoint.kt`新設によるgraph-only Hilt導入〔裁定B17／ADR-0015〕。②P-H2確定失敗によりこの導入部分〔③④⑤〕は不実施、ADR-0015は発効せず） | domain-implementer | **TDD例外の適用（裁定B3の系）**。ベースライン73/73 Green実測・P-H2失敗実測ともに完了 |
| **P2-C2**（旧P2-C1） | probe＋契約scaffold: P-2/P-3/P-4（権限shadowのみ）/P-5/P-7/P-8の実測、`CalendarService`/`CalendarResult`/`PermissionGate`/`CursorSource`/新UiState型のコンパイル可能なscaffold（実装は`TODO()`）、Manifestへ`READ_CALENDAR`追加。**P-1はM-1/M-2により、P-6はM-10により本サイクル開始前に解決済みのため実測のみ再確認する**。probe結果を`DECISIONS.md`へ記録。**裁定B4（externalCalendarId）・B6（NavHost簡略結線の既知の制限）のADR記録もあわせて本サイクルで行う（裁定B2／ADR-0014は裁定B17により取消・無効）** | domain-implementer | **TDD例外の適用（裁定B3）**。scaffoldコンパイル成功とprobe実測ログ |
| **P2-C3**（旧P2-C2） | Red: §10の全61テストケースのうちJVM系56件をfailing化し実測でRedを確認する（T-HILT-1〔1件。T-HILT-2〜4はADR-0014により対象消滅済み〕はP2-C1でTDD例外により実測済みのためG2対象外、E2E系4件〔T-E2E2-1〜4〕は作成のみ・実行はG4-E。Phase 1と同じ扱い）。旧T-MANIFEST-1/2は裁定B9によりP2-C7のゲート検証手順へ移設済みのため本サイクルの対象外。既存テストの更新と`MockEventSourceTest`7件の移設も本サイクルで行う（§6.3の対応表に基づく） | test-writer → quality-runner | **G2** |
| **P2-C4**（旧P2-C3） | Green（Domain側）: `CalendarInstanceMapper`（L1）/`CalendarProviderCalendarService`（L2）/`ContentResolverCursorSource`（L3）/`PermissionGate`のAndroid実装（3層分割、§8.3改訂） | domain-implementer | **G3** |
| **P2-C5**（旧P2-C4） | Green（UI側）: 事前説明カード・権限拒否UI・手動入力フォーム・Event Selection一覧化。画面Composableは遷移も権限要求もラムダ引数で受け取る | ui-implementer（**P2-C4と同一メッセージで並列起動**） | **G3** |
| **P2-C6**（旧P2-C5） | 統合（直列）: `AppContainer`（手動DI）への結線、NavHostへの権限launcher/ON_RESUME再チェック/手動入力イベントの結線、`mock/MockEventSource.kt`削除（U6履行）。**「ViewModel生成点1箇所への集約」の維持を本サイクルのレビュー観点に含める（裁定B2の保護条件。ADR-0014により手動DI継続のため`AppContainer`がそのまま維持対象）** | domain-implementer（integration owner） | **G3** |
| **P2-C7**（旧P2-C6） | Refactor＋`./gradlew build`/`lintDebug`エラー0の再実測。**あわせてquality-runnerがマージ済みマニフェスト成果物（`build/intermediates/merged_manifests/{debug,release}/AndroidManifest.xml`）をスクリプト検証し、debug変種に`READ_CALENDAR`が含まれること・release変種に`WRITE_CALENDAR`が含まれないことを確認する（旧T-MANIFEST-1/2、裁定B9）**。**実施済み（結果、2026-08-09）**: (1)`NavigationFlowTest`5テストの間欠フレークを`waitUntil`方式（§15(e)参照）で安定化し`--rerun`3回連続実行で3回とも`:app:testDebugUnitTest`122/122 Green実測（`build/agent-logs/p2c6-stability-run{1,2,3}.log`）。(2)`:app:lintDebug`エラー0実測（warning9件はいずれも§15(b)記載の既存許容分。新規warning無し。`build/agent-logs/p2c6-lint.log`）。(3)マージ済みマニフェスト検証：debug変種`merged_manifests/debug/processDebugManifest/AndroidManifest.xml`に`READ_CALENDAR`含有・release変種`merged_manifests/release/processReleaseManifest/AndroidManifest.xml`に`WRITE_CALENDAR`非含有をいずれもPASS実測（`:app:assembleRelease`もBUILD SUCCESSFUL。`build/agent-logs/p2c6-manifest-check.log`）。(4)`EventSelectionScreen.kt`を状態ごとのprivate Composable（`EventSelectionEmptyContent`/`EventSelectionContentList`/`EventSelectionPermissionRequiredContent`/`EventSelectionPermissionDeniedContent`/`EventSelectionErrorContent`）へ、`ActionStarterNavHost.kt`のeventSelectionルート結線を`EventSelectionRoute`private Composableへ、それぞれ挙動変更なし（UIツリー・testTag・文字列リソース不変）で構造分割し、リファクタ後の全スイート再実測でも122/122 Green維持を確認（`build/agent-logs/p2c6-postrefactor.log`）| ui-implementer/domain-implementer → quality-runner | **G4-JVM** |
| **P2-C8**（旧P2-C7） | instrumented: §12実測済み手順（Step 0〜7）でseed実行→`connectedDebugAndroidTest`（T-E2E2-1〜4、Step 6の権限E2Eを含む）→cleanup。**完了（4/4 Green・2026-08-09。ヒーロー行clickable欠陥をT-SEL2-8で回帰ロック）**: 初回実測でtE2e2_1／tE2e2_4がFAILし（`EventRow`のヒーロー行＝`event_selection_row_0`が行レベルでクリック不可という本番欠陥を検出。詳細は§15(f)）、P2-C8fixでの実機タップ再現実験により原因を確定。P2-C8fix2で`EventSelectionScreen.kt`を修正し、JVM回帰テストT-SEL2-8追加（Red実測後Green化、`:app:testDebugUnitTest`123/123 Green、`build/agent-logs/p2c8fix2-jvm-full.log`）のうえで、tE2e2_1／tE2e2_4を再実測しGreen化した（`build/agent-logs/p2c8fix2-e2e.log`、XML実測`tests="2" failures="0" errors="0"`）。tE2e2_2／tE2e2_3はP2-C8fixで別途Green実測済み（`build/agent-logs/p2c8fix-stage-a-tE2e2_3-result.xml`／`p2c8fix-stage-b-tE2e2_2-result.xml`、いずれも本欠陥の影響を受けない）。以上でT-E2E2-1〜4の4件全てが（実測時点は各々異なるが）Green達成 | quality-runner | **G4-E** |

**P2-C5並列時の所有権規則（旧P2-C4。Phase 1計画書§15の規則をそのまま適用）**: `build.gradle.kts`/`settings.gradle.kts`/`AndroidManifest.xml`/`AppContainer`/`ActionStarterApplication`/`ActionStarterNavHost`の既定所有者はdomain-implementerのみ。ui-implementerはP2-C5の間これらに一切触れず、必要が生じたら中断してFable 5へ報告する。

---

## 15. 既知の技術的負債の扱い（裁定B6）

### (a) NavHost executionルートの簡略結線 → Phase 2では扱わず、Phase 5（§69）へ後送り

現状は`ActionStarterNavHost.kt`のKDocに記載のとおり、execution routeが`ExecutionViewModel`を経由せず`confirmedPlan.steps.firstOrNull()`から直接`ExecutionUiState`を構築し、Done 1回でDepartureへ遷移する（複数ステップの逐次走破が未結線）。

**後送りの根拠**:
1. §66のPhase 2実装項目にExecutionは含まれない。Phase 2で扱うのはスコープ拡大にあたる。
2. 正しい結線には`ExecutionViewModel`のコンストラクタ契約へ`ExecutionPlan`を渡す変更が必要で、その契約は§68 Basic Engine（Phase 4）と§69（Phase 5）で必然的に再設計される。Phase 2で直すと同じ箇所を2回作り直すことになる。
3. リリース判定への即時影響はない。GOAL.md D(1)は現行の簡略フローで既にT-E2E-1がGreen実測されている。

**Fable 5裁定B6により確定**: 本制限をP2-C2（旧P2-C1）で「既知の制限：Execution多段階遷移の未結線とPhase 5での解消計画」としてADRに追記する（文書のみ。本計画書自体は`DECISIONS.md`を変更しない）。

### (b) lint警告2件（裁定B6・メモ提案どおり据え置き）

| 警告 | 対応 | 根拠 |
|---|---|---|
| `MissingApplicationIcon` | Phase 2スコープに含めない。Phase 13（§77 配布）へ申し送る | GOAL.mdカテゴリAは「lintエラー0（warning許容）」であり得点に影響しない。adaptive icon一式はデザイン成果物を伴う。Play配布の前提であるためPhase 13の必須TODOとして明記 |
| `UnusedResources: recovery_option_eta_label` | 削除せず据え置き。Phase 2で`strings.xml`にPhase 6で使用予定である旨のコメントのみ追記 | 当該文字列は§70 Phase 6（Recovery Basic）で使用見込み。今削除するとi18nパリティを跨ぐ往復作業になる |

その他の警告（`GradleDependency`×5・`AndroidGradlePluginVersion`・`OldTargetApi`）は、ADR-0007/ADR-0011で意図的に固定した結果であり、再検討トリガーはPhase 13。Phase 2での対応不要。

### (c) エラーマップ#15（手動入力dirty状態でのON_RESUME自動更新ガード）→ 統合サイクル（C6／旧C5）時点で未実装の既知負債

`EventSelectionViewModel`のKDoc（`savedStateHandle`は本ファイル時点では未使用、将来のdirty状態管理〔裁定B11、エラーマップ#15〕で使用する想定と明記）のとおり、裁定B11・エラーマップ#15が要求する「手動入力フォームがdirty（入力中）の間はON_RESUME自動復帰を発生させない」ガードは、統合サイクル（C6／旧C5、本サイクル）で結線したON_RESUME経路（`ActionStarterNavHost`の`DisposableEffect`＋`LifecycleEventObserver`）にも未実装のまま残置されている。本サイクルで結線したON_RESUME経路は無条件に`viewModel.onRetry()`を呼ぶため、手動入力中にON_RESUMEが発火すると入力内容が予告なく上書きされ得る（裁定B11の未充足）。

**既知負債として記録（Fable 5裁定2026-08-09）**: エラーマップ#15（手動入力dirty状態でのON_RESUME自動更新ガード）はC5（統合サイクル）時点で未実装の既知負債とする。C8のE2E検証時に影響評価し、必要ならPhase 2内で追補する。追補にはdirty状態をViewModel側に持たせるための`EventSelectionViewModel`側の変更（features/実装ロジック）を要するため、本サイクル（結線のみ）のスコープ外である。

### (d) `EventSelectionViewModel`の権限状態遷移ギャップ：起動前拒否から`PermissionDenied`へ到達できない（本サイクルで発見・要architect裁定）

`EventSelectionViewModel.refresh()`は`permissionGate.isGranted(...)`が`false`の場合、無条件で`EventSelectionUiState.PermissionRequired`（事前説明カード表示）へ遷移して`return@launch`する。`EventSelectionUiState.PermissionDenied`（手動入力フォーム表示、F17）へ遷移するのは`calendarService.readUpcomingEvents(...)`が`CalendarResult.PermissionDenied`を返した場合（＝一度許可された状態から読取実行中に剥奪された場合）のみであり、「アプリ起動時点で既に拒否／未許可」というシナリオからは`PermissionDenied`へ構造的に到達できない。

計画書§7.4の状態遷移表は`PermissionRequired`の遷移条件を「`PermissionGate.isGranted == false`」、`PermissionDenied`の遷移条件を「launcher結果が`false`」とそれぞれ記載するが、両条件の重なり（起動前から拒否されているケースがどちらに属するか）を明示的に裁定していない。`CalendarNavigationFlowTest`（T-NAV2-1）は`shadowOf(application).denyPermissions(...)`による起動前拒否シナリオで`PermissionDenied`側（手動入力フォーム表示）を期待しており、現行実装（`PermissionRequired`側に帰着する）と齟齬がある。

**実測（2026-08-09、統合サイクル）**: `:app:testDebugUnitTest`実行により、T-NAV2-1が`manual_event_title_field`の非表示で失敗することを確認した（`build/agent-logs/p2c5-full.log`）。本ギャップの解消には`EventSelectionViewModel.kt`（features/実装ロジック）の変更を要するため、統合サイクル（結線のみ、`services/calendar`・`features/`変更禁止）のスコープでは解消不能と判断した。architectレビューでの裁定を要する。

**解消記録（Fable 5裁定2026-08-09、統合修正サイクル）**: 上記裁定に基づき`EventSelectionViewModel.kt`へ`permissionRequested`フラグ（`SavedStateHandle`裏付け）を追加し、`refresh()`の未許可分岐を`!permissionRequested`→`PermissionRequired`／`permissionRequested`→`PermissionDenied`へ確定させた（§7.4改訂参照）。`ActionStarterNavHost.kt`は許可要求launcher起動直前に`onPermissionRequested()`、結果`false`に`onPermissionDenied()`、結果`true`に`onRetry()`を結線した。`CalendarNavigationFlowTest.kt`（T-NAV2-1）は「起動前拒否のまま何も操作しない」だけでは新しい状態機械上`PermissionRequired`にしか到達しないため、`LocalActivityResultRegistryOwner`経由でlauncher起動時に即座に`false`を返すfake `ActivityResultRegistry`を供給したうえで、事前説明カードの許可ボタンをUI経由でタップする手順を環境整備として追加した（既存アサーション本体は無変更。`CalendarNavigationFlowTest.kt`該当KDoc参照）。

副次的に、上記修正後の実測で`ManualEventEntry`の確定ボタンが開始日時（`startDateTime`）未設定のままでは送信を無視すること（裁定B16、`ManualEventEntryTest` T-MANUAL-7と同じ契約）が新たに顕在化した（従来はこの経路まで到達したことがなく未発見だった）。UI経由（実際のMaterial3 `DatePickerDialog`操作、日付セルタップ→確定）で開始日時を設定する手順を追加して解消した（詳細は`CalendarNavigationFlowTest.kt`該当KDocおよび完了報告）。最終実測：`:app:testDebugUnitTest`122/122 Green（`build/agent-logs/p2c5fix-full.log`）。

### (e) `NavigationFlowTest`・`CalendarNavigationFlowTest`のRobolectric環境前提レグレッション（本サイクルで発見・要architect裁定）

`AppContainer`を一時ブリッジ（`MockBackedCalendarService`／`GrantedPermissionGate`）から実装（`CalendarProviderCalendarService`／`AndroidPermissionGate`）へ結線した結果、`navigation/NavigationFlowTest.kt`の既存5件（T-NAV-1〜5）が新規にRed化した（実測2026-08-09、`build/agent-logs/p2c5-full.log`）。原因を診断用一時テスト（本サイクル内で作成・実行・削除済み。成果物には残していない）で実測した結果、次の2点を確認した：

1. Robolectricの`READ_CALENDAR`権限shadowの既定状態は**未許可**（明示的な`shadowOf(app).grantPermissions(...)`なしでは`AndroidPermissionGate(context).isGranted(...)`が`false`を返す）。§6.3は`NavigationFlowTest.kt`へ`shadowOf(app).grantPermissions(READ_CALENDAR)`のsetup追加を予定していたが、現行ファイルには当該追加が反映されていない。
2. 権限を明示的に許可しても、`ContentResolverCursorSource`の実クエリはこのRobolectric JVMテスト環境にCalendarProvider相当のContentProviderが登録されていないため`null`を返し、`Failure(PROVIDER_UNAVAILABLE)`となる（`ioDispatcher`を`Dispatchers.IO`既定・`Dispatchers.Unconfined`のいずれにしても実測結果は同一であり、ディスパッチャ差異が原因ではないことを確認済み）。

`services/calendar`自体の実装は正しく動作しており（`CalendarProviderCalendarServiceTest`13件はGreenのまま）、原因は「実`CalendarService`／`PermissionGate`を、権限未許可・CalendarProvider未登録のJVM/Robolectric環境で、`EventSelectionUiState.Content`到達を前提に書かれた既存テストへ結線した」という、統合時点で初めて顕在化した環境前提のギャップである。

**既知負債として記録（本サイクル、要architect裁定）**: 修正には`NavigationFlowTest.kt`／`CalendarNavigationFlowTest.kt`（いずれもテストファイル、本サイクルの変更許可範囲外）側の対応（権限shadowの明示的許可設定、および何らかのfake/registered CalendarProviderまたはfake `CalendarService`注入手段の追加）が必要と見られる。`features/`・`services/calendar`・テストファイルのいずれにも触れない「結線のみ」の制約下では解消不能と判断し、architectレビューへ裁定を仰ぐ。

**解消記録（Fable 5裁定2026-08-09、統合修正サイクル）**: 上記裁定に基づき`NavigationFlowTest.kt`へ`@Before`（`setUpCalendarEnvironment`）を追加し、(a)`shadowOf(application).grantPermissions(READ_CALENDAR)`で権限shadowを明示的に許可、(b)`Robolectric.buildContentProvider(...)`で`CalendarContract.AUTHORITY`へfakeの`ContentProvider`（`FakeCalendarContentProvider`、`MatrixCursor`で妥当な1件のInstances行・Calendars行を返す）を登録し、実`ContentResolverCursorSource`経由のクエリが`EventSelectionUiState.Content`へ到達できる状態を作った。5テストの本体・アサーションは無変更（`NavigationFlowTest.kt`該当KDoc参照）。

**既知の残存事象（実測、統合修正サイクルで発見）**: 上記修正後、`tNav2_backPress_returnsToPreviousScreen`が単体実行では100%再現性よくGreenになる一方、5テストを連続実行すると稀に（実測目安：数回に1回程度）`setContent`直後の最初のUI操作で対象ノードが見つからず失敗する間欠的事象を確認した。原因は本サイクルの変更対象外である`AppContainer`／`CalendarProviderCalendarService`の既定`Dispatchers.IO`（実バックグラウンドスレッド）とRobolectricのCompose test同期機構の間の競合と推定される（`services/calendar`実装自体は変更していない）。防御的に`setContent`直後へ`composeTestRule.waitForIdle()`を5テスト共通で追加した（既存アサーション本体は無変更）が、上記競合を完全には解消していない。既存アサーション改変・`AppContainer`のdispatcher変更（本サイクルの変更許可範囲外）を伴わずに完全解消する対応は本サイクルのスコープ外と判断し、既知事象として記録する。最終実測：`:app:testDebugUnitTest`（`--continue`再実行込み）122/122 Green（`build/agent-logs/p2c5fix-full.log`）。詳細・再現条件は完了報告を参照。

**C6で安定化（Fable 5裁定2026-08-09、P2-C7〔旧P2-C6〕リファクタ＋G4-JVMサイクル）**: 上記の間欠事象は、`waitForIdle()`がComposeのアイドル到達のみを保証し`EventSelectionUiState.Content`への実際の到達を保証しない点が根本原因だったため、`NavigationFlowTest.kt`の各`@Test`にある`setContent`直後の`composeTestRule.waitForIdle()`を、対象ノード（`event_selection_prepare_button`）の出現そのものを条件とする`composeTestRule.waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(...).fetchSemanticsNodes().isNotEmpty() }`（5テスト共通の`waitForEventSelectionContent()`ヘルパーへ集約。`NavigationFlowTest.kt`該当KDoc参照）へ置換することでwaitUntil方式により安定化した。`AppContainer`のdispatcher変更・既存アサーション本体の変更は伴わない（待機ヘルパーの追加・置換のみ）。`:app:testDebugUnitTest`を`--rerun`で3回連続実行し、3回とも122/122 Greenを実測した（`build/agent-logs/p2c6-stability-run1.log`／`p2c6-stability-run2.log`／`p2c6-stability-run3.log`）。

### (f) `EventRow`のヒーロー行（`event_selection_row_0`）が行レベルでクリック不可だった欠陥（P2-C8で発見・P2-C8fix2で解消）

**発見（P2-C8、実測2026-08-09）**: `connectedDebugAndroidTest`初回実測で`tE2e2_1_seededCalendarEvents_selectionToDepartureFlowSucceeds`／`tE2e2_4_allScreensFromCalendarFlow_reachableForScreenshotCapture`が`event_selection_row_0`への`performClick()`後、遷移先画面のテキストが見つからずFAILした（`java.lang.AssertionError: Failed to inject touch input.`、`build/agent-logs/p2c8fix-failure-tE2e2_1-logcat.txt`）。

**原因**: `EventSelectionScreen.kt`の`EventRow`は`if (!isNext) { rowModifier = rowModifier.clickable(...) }`という条件分岐により、先頭行（`index == 0`、「次の予定」バッジと「Prepare this event」ボタンを持つヒーロー行）にのみ`.clickable`を**付与していなかった**（ボタン単体のみクリック可能）。Compose UI Testの`performClick()`は対象ノードにセマンティクス`OnClick`アクションが無い場合、実座標へのフォールバックgesture（ノード全体の中心座標へのタップ）に切り替わる。ヒーロー行は次バッジ・タイトル・時刻・場所・ボタンを縦に積んだ構成のため、行全体の中心座標が必ずしも内側のボタン領域と一致せず、フォールバックタップがボタンを外れて何も起こらないケースが実機で発生していた（`build/agent-logs/p2c8fix-failure-tE2e2_1-repro-*.png`で実機タップ実験により再現・確定）。これはE2Eテストのみの問題ではなく、実ユーザーが行のボタン以外の領域（バッジ・タイトル・時刻付近）をタップしても遷移しないという**本番UXの欠陥**である。

**修正が単純な`.clickable`追加では済まなかった理由**: `Modifier.clickable(...)`は内部で`mergeDescendants = true`を強制する（子孫のセマンティクスを自身へ統合し1つのアクセシビリティノードとして扱う設計）。ヒーロー行に単純に`.clickable`を追加すると、子の"event_selection_next_badge"（バッジText、独自のマージ境界を持たない）が親へ吸収され、`hasAnyDescendant(hasTestTag("event_selection_next_badge"))`で個別ノードとして検出できなくなり、既存の`T-SEL2-1`（変更禁止）が破壊されることを実測で確認した（`build/agent-logs/p2c8fix2-green-candidate1.log`、`AssertionError: ... However, the unmerged tree contains '1' node that matches.`）。

**解消記録（Fable 5承認2026-08-09、P2-C8fix2）**: `EventRow`のヒーロー行のみ、`Modifier.pointerInput(onNavigateToPlanReview) { detectTapGestures(onTap = { onNavigateToPlanReview() }) }`（実タップ応答）と`Modifier.semantics(mergeDescendants = false) { onClick(action = { onNavigateToPlanReview(); true }) }`（`mergeDescendants`を明示的に`false`のまま保つクリックセマンティクス）を組み合わせた個別実装へ変更し、`.clickable`の暗黙のマージを回避しつつ行レベルのクリックを実現した。2件目以降（バッジを持たない非ヒーロー行）は従来どおり素の`Modifier.clickable`のまま変更していない。JVM回帰テスト`T-SEL2-8`（`EventSelectionListTest.kt`）を追加し、`onNodeWithTag("event_selection_row_0")`が`hasClickAction()`を満たすことを幾何非依存に固定した。最終実測：`:app:testDebugUnitTest`123/123 Green（`build/agent-logs/p2c8fix2-jvm-full.log`）、`connectedDebugAndroidTest`でtE2e2_1／tE2e2_4ともGreen（`build/agent-logs/p2c8fix2-e2e.log`、XML`tests="2" failures="0" errors="0"`）。詳細は§12.1追記・§14 P2-C8行を参照。

---

## 16. リスク

| ID | リスク | 対応 |
|---|---|---|
| R7 | Robolectricの権限shadow（`grantPermissions`/`denyPermissions`）が期待どおり動かず、権限連携テスト戦略（JVM中心）が崩れる | 3層分割（§8.3改訂）により`Robolectric.buildContentProvider()`への依存は構造的に解消済みで、リスクは権限shadowの実在確認のみに縮小している（R7'）。P-4（P2-C2〔旧P2-C1〕の最優先probe対象）が不成立の場合は、権限shadow依存のテスト（T-PERM-1〜7）のみinstrumentedへ移し、`CalendarInstanceMapper`（L1）・`CalendarProviderCalendarService`（L2、fake `CursorSource`のみに依存）はJVM側に残して被害を局所化する |
| R8 | §66と§95.4/§95.6のスコープ齟齬が未裁定のまま実装が進む | **裁定B1により解消済み**。裁定前にP2-C3（旧P2-C2、Red）へ進まない方針は履行済み（本計画書はB1確定後に作成） |
| R9 | Hilt導入判断を曖昧にしたまま進み、Phase 5で手戻りする | **【2026-08-09解消】** 裁定B17（ADR-0015・graph-only方式への前倒し）はP2-C1プローブ実測（P-H2確定失敗）を受けて**発効せず**、フォールバック③が適用された。**ADR-0014（Hilt導入のPhase 5延期）が確定し、判断の曖昧さは解消済み**である。手動DI（`AppContainer`＋単一Factory集約。裁定B2の保護条件）を継続し、Phase 5着手時に旧Hilt版／AGP9引上げ／手動DI継続の3択を再判定する（ADR-0014再検討トリガー） |
| R10 | エミュレータの不安定性によりG4-Eが再び未達となる | P-8。P2-C8（旧P2-C7）手順にコールドブート＋確認＋リトライ1回を組み込み、失敗時は推測せず実行不能として報告 |
| R11 | 実カレンダーのtitle/notesがログへ混入する（§58/§60違反） | エラーマップ#16。G4コード差分レビューの必須確認項目に追加。権限・プライバシー変更のためGemini/Codexクロスレビュー必須（`docs/TEAMS.md`§6 G4） |
| R12 | `MockEventSource`削除によりUI検証が実カレンダー依存になりテスト不安定化 | `CalendarService`のfake実装を`src/test`配下に置き、UI層テストは常にfake経由で決定的に動かす |

---

## 17. 未確認事項の明示（Fable Protocol）

- `Dispatchers.IO`/`lifecycle-runtime-compose`の推移的依存の有無（P-2/P-3）は未確認。
- Robolectric 4.16.1における権限shadow（`shadowOf(application).grantPermissions`/`denyPermissions`）の動作（P-4）は未確認（4.7時点javadocに基づく推定）。3層分割（§8.3改訂）により`Robolectric.buildContentProvider()`への依存は解消済みのため、検証対象はこの権限shadowのみに縮小している。
- instrumentedテストからのカレンダー書込に必要な権限宣言の配置（P-5、副方式採用時のみ必要）は未確認。
- probe終盤のエミュレータプロセス消失により、`probe2@local`カレンダー/`InstProbe`イベントの残存有無は未確認。

**解決済み（参考。上記の未確認リストから除外済み）**: `Instances`のprojection列指定可否（P-1）は§8.2・M-1により14列とも解決済み。`pm revoke`後の拒否UI検証手順（P-6）は§12 Step 6・M-10により解決済み。**KSP/KGP協調（P-H1）・Hilt Gradle plugin/Kotlin世代互換性（P-H2）・Robolectric×`@HiltAndroidApp`共存（P-H3）は2026-08-09のP2-C1実測により確定した：P-H2はHilt Android Gradle plugin 2.60.1がAGP 9.0.0以上を必須とする内蔵チェックで`apply`時点で失敗することが確定し（実測AGP 8.13.2）、P-H1／P-H3はその時点でビルド自体が成立せず検証機会が生じなかった（対象消滅）。Fable 5はADR-0014によりHilt導入をPhase 5へ延期した（§13・§14 P2-C1・`DECISIONS.md`参照）。**

Sources:
- Robolectric ShadowApplication javadoc (4.7)
- Robolectric ShadowContentResolver javadoc (4.14)
- robolectric/ShadowContentResolverTest.java (GitHub master)
- Calendar provider overview | Android Developers
- CalendarContract.Instances | Android Developers

---

## 18. 未解決事項・申し送り

- 本計画書はFable 5 Pass1/Pass2アーキテクトレビューおよびGeminiクロスレビュー（`model: "gemini-3.5-flash"`）を**実施済みである（2026-08-09）**。指摘事項はFable 5裁定B8〜B16として本書へ反映済みであり（§4参照）、その後の後続レビューによる裁定B17〜B19も両セッションの成果として本書へ統合済みである。**2026-08-08のユーザー指示によりG1はユーザー承認を待たず自動進行する（裁定B18）。唯一の例外はHilt導入時期の裁定（B17・ADR-0015）であり、これについてはユーザーの拒否権を留保する。**
- Fable 5裁定B1〜B7（2026-08-09、計画メモ提出時点のエスカレーション裁定）、B8〜B16（2026-08-09、G1レビュー由来の裁定）、およびB17〜B19（2026-08-09、後続レビュー由来の裁定）はいずれも確定済みであり、ユーザー承認待ちの対象ではない（唯一の例外はB17が記録するADR-0015〔Hilt導入時期〕で、これについてはユーザーの拒否権を留保する）。
- **【2026-08-09更新】** P2-C1実測の結果、ADR-0015（graph-only Hilt導入、裁定B17）は発効せず`DECISIONS.md`へ記録されなかった。フォールバック③（裁定B2の内容への復帰）が適用され、**ADR-0014（Hilt導入のPhase 5延期）が`DECISIONS.md`へ記録・確定した**（詳細は本書§14 P2-C1、`DECISIONS.md` ADR-0014）。externalCalendarId複合キー（裁定B4・ADR記録トリガー②）およびNavHost簡略結線の既知の制限（裁定B6）のADRは、P2-C2（旧P2-C1）完了時に`DECISIONS.md`へ記録する予定であり、いずれも本計画書時点では未記録である。
- seed方式（§12）は主方式（adb seed）を既定として設計しており、android-planner追加実測（§12.1 Step 0〜7）により主方式が実機で一貫動作することを確認済みである。残るP-5（副方式に必要な権限宣言配置）は主方式を採用する限り不要であり、主方式が失敗した場合のフォールバックとしてのみ検証対象となる（P-6の`pm revoke`検証手順はM-10により確立済みで解決済み）。正式な最終確定はP2-C2（旧P2-C1、probe＋契約scaffold）完了時点で`DECISIONS.md`へ記録する（裁定B7）。
- P2-C2（旧P2-C1）のprobe対象はP-2／P-3／P-4（権限shadowのみ）／P-5／P-7／P-8であり、いずれも未実測である（P-1はM-1/M-2により、P-6はM-10により本書時点で解決済み）。**P2-C1のprobe対象であったP-H1／P-H2／P-H3（Hiltのバージョン協調・既存テスト互換性）は2026-08-09に実測済みである（P-H2確定失敗、P-H1/P-H3対象消滅。ADR-0014）**。本計画書の一部設計（フィルタ規則の実装可否・依存追加要否・テスト戦略・seed方式）は、残るP-2／P-3／P-4／P-5／P-7／P-8のprobe結果により変更されうる（Hilt導入可否はADR-0014により確定済みのため変更対象から除く）。
- 計画メモに記載のなかった内容の追加、および転記漏れは確認していない（本書は計画メモ§0〜§14の全項目を転記済み）。B8〜B16はG1レビューにより、B17〜B19は後続レビュー（android-planner追加実測メモ受領後）により、それぞれ新たに追加された裁定であり、計画メモには存在しない（メモとの差分は§4で「G1レビューによる裁定」「後続レビューによる追加裁定」としてそれぞれ明示区分している）。
- テストケース件数の反映結果（裁定B9でT-MANIFEST-1/2をゲート検証手順へ移設・裁定B13/B16でT-CALSVC-11／T-MANUAL-7を新規追加・後続レビューでT-HILT-1〜4／T-CALSVC-12/13／T-CALMAP-17/18/19を新規追加・**2026-08-09のADR-0014によりT-HILT-2〜4を対象消滅として削除**）: §10.2ヘッダ・§3 G2・§14 P2-C3のいずれも「**全61件（正常系20／異常系13／エッジケース28）**、うちJVM系56件がRed対象（**T-HILT-1〔1件〕**はP2-C1でTDD例外により実測済みのため対象外、**T-HILT-2〜4はADR-0014により対象消滅**）・E2E系4件は作成のみ」で一致していることを本書内で数え直して確認済み。エラー＆レスキューマップ（§11）は裁定B10の#21追加および後続レビューの#22〜#24追加により全24行で一致している（#24は2026-08-09、ADR-0014による結果注記を追加済み）。
- **【統合サイクル（C6／旧C5）申し送り・2026-08-09、domain-implementer（integration owner）追記】** `calendarIds`非空部分集合のフィルタ仕様が未定義（C4実装報告より）：`CalendarProviderCalendarService.readUpcomingEvents`の`calendarIds`パラメータは空集合の防御（裁定B13、T-CALSVC-11）のみ契約・実装済みであり、**非空の部分集合（例：3件中2件のカレンダーIDのみ指定）を渡した場合の絞り込み方式（selectionへのIN句追加等）は未実装・未定義**のまま（§7.2「個別カレンダーID集合による絞り込み方式は本サイクルの契約・テストの対象外」）。現状は`calendarIds`に非空集合を渡しても無視され全件が返る。F14のフィルタUIはPhase 2に含めない（裁定B19）ため現時点で実害はないが、将来この引数を使う場合は追加のADR・テストケースを要する。
- **【統合サイクル（C6／旧C5）申し送り・2026-08-09】** §6.3の表は`mock/MockEventSourceTest.kt`を「7件」と記載しているが、**8件が正**（T-MOCK-11〔ソート順回帰ロック、Fable 5裁定2026-08-09でTDD例外承認・追加〕を含む。削除前のファイルで`@Test`8個を実測確認済み）。T-MOCK-1/2/3/5/6の検証意図は`CalendarInstanceMapperTest`／`CalendarProviderCalendarServiceTest`のKDoc相互参照で移設済みであることを確認、T-MOCK-8/9はMock専用API（`createEvent`のrequire）のため廃止、T-MOCK-11相当のソート順保証は`T-CALMAP-15`／`T-CALSVC-12`が担うことを確認したうえで、本サイクルで`mock/MockEventSource.kt`と併せて削除済み（U6履行）。
- **【統合サイクル（C6／旧C5）で新たに判明した問題・2026-08-09、domain-implementer実測。詳細は§15 (d)(e)】** (1) T-NAV2-1（`CalendarNavigationFlowTest`）は計画書どおりNavHost結線を実施してもGreen化しない。`EventSelectionViewModel`の権限状態遷移が「起動前からの拒否」を`PermissionDenied`ではなく`PermissionRequired`として扱う実装になっているためであり、解消には`features/`の実装ロジック変更を要するため統合サイクル（結線のみ）のスコープでは対応不能だった。(2) `AppContainer`を実結線した結果、既存`AppContainerTest`のT-DI-1（コンストラクタ引数なし呼び出し`AppContainer()`を前提とするテスト。§7.3改訂によりコンストラクタがContext必須となったため、`AppContainer.kt`側でテスト互換用のデフォルト値〔評価されると例外送出〕を追加してコンパイルは維持したが、当該テスト自体はGreen化できていない）と、`navigation/NavigationFlowTest.kt`の既存5件（T-NAV-1〜5、Robolectric環境の既定権限未許可・CalendarProvider未登録という環境前提により新規Red化）が、テストファイル変更禁止の制約下では回避不能な形でRedのまま残った。`:app:testDebugUnitTest`実測（`build/agent-logs/p2c5-full.log`）で122件中115件Green・7件Red（内訳：T-DI-1、T-NAV2-1、NavigationFlowTestのT-NAV-1〜5）であることを確認した。T-DI-2（MockEventSource非存在検証）は本サイクルで新規Green化した。`:app:assembleDebug`はBUILD SUCCESSFUL（`build/agent-logs/p2c5-assemble.log`）。architectレビューでの裁定・NavigationFlowTest／CalendarNavigationFlowTestの改修方針決定を要する。
