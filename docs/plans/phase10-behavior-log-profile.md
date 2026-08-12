# Phase 10 実装計画書 — 行動ログ＋Personal Profile

> 対象仕様: `Action_Starter_Master_Specification_v2.0_Android.md`（本フェーズより採番判明済み、以後この版を「仕様」と呼ぶ）§74「Phase 10」（Personal Profile・履歴保存・次回Planへ反映）・§52「Personal Execution Profile」（Kotlin型定義）・§22/§23（Personal Profile構想・将来像）・§53「Analytics / Test Log」・§54「時刻ログ」・§55/§56（KPI定義）・§10「Local-first AI」・§58「Privacy」・§59「Local Data」・§60「Telemetry」・§32「Recovery Option」・§61「MVPに入れない機能」・§34「ユーザー最終決定」・§88「Developer UX Principle」
> 前提基盤: 第1弾C-18（行動ログ、唯一の未達項目）・Phase 9/9.5（Recovery AI化・計測駆動改善、ADR-0063/0064）・既存scaffold `domain/model/PersonalExecutionProfile.kt`（Phase 8.5 C2で宣言のみ実装済み）・`persistence/ExecutionScheduleStore.kt`（Phase 5、ADR-0025）・ADR-0049決定5（`AnalyticsStore`をPhase 10/12で導入）
> 種別: 機能実装フェーズ（A/B実測は伴わない。Phase 9.5と異なりRed→Green→実機受け入れの通常フローで進める）
> 承認状態: **敵対的レビュー完了（§13、オーケストレーター9点＋Gemini 8件、CRITICAL2件は両者収束）・修正版確定。§12確認事項は委任フローどおり全8件確定。C1〜C4実装完了（Green、§11コミット粒度どおり）。ADR起票・実機受け入れへ進む。**

---

## §0. 結論ファースト

Phase 10は2つの柱からなる。**(A) 行動ログ**: `PlanReviewScreen`（AI文言採否のみ）・`ExecutionScreen`/Recoveryフローで発生するイベント（ステップ完了・スキップ・遅延検出・Recovery選択・AI文言採否）をRoom（本プロジェクト初のDB）へ端末内限定で記録し、C-18（第1弾唯一の未達項目）を解消する。**AI文言採否はPlan側・Recovery側の両方で記録する**（レビューCRITICAL、`domain`カラムで区別。Phase 12比較実験の主データはPlan側）。**(B) Personal Profile永続化**: 行動ログから決定的Kotlin中央値集計（LLM不使用）で`PersonalExecutionProfile`（既存scaffold、仕様§52）の**`averageTransitionDuration`／`averagePreparationDuration`の2フィールドに厳格限定して**算出し、`BasicPlanningEngine`のハードコード既定値を実績値へ置き換える（レビューCRITICAL: 残り3フィールドはarrival/到着検知手段が存在しないため本フェーズは算出不能・null維持）。**プロンプトへのProfile注入（AI個人化）とdelay message（§32 option 3）は本フェーズのスコープに含めない**（§12確認事項4・1）。プライバシーはWAL/SHMサイドカーを含む3ファイルのバックアップ除外・タイトル生文非保存（`EventCategoryClassifier`再利用）で担保する。

---

## §1. 目的・背景

第1弾の`/goal`最終採点（97.6/100、`docs/evidence/scorecard-final-goal.md`）における残存5件のうち、**C-18「行動ログ未実装」のみが第1弾スコープの直接の未達項目**であり、他4件（R16実機起因・TRANSIT既定・adb root前提・exact alarm導線）は環境/UX的な残課題であってC-18とは性質が異なる。C-18の原文（同ファイルL27）: 「第1弾スコープ唯一の未達項目。Phase 10（Personal Profile）の前提データでもあるため、小型フォローアップまたはPhase 10冒頭での実装を推奨」。本フェーズはこの推奨どおり着手する。

`domain/model/PersonalExecutionProfile.kt`は既にPhase 8.5 C2でscaffold実装済みで、KDocに「永続化はRoomで行う（Phase 10、仕様§74）」と明記されている。`BasicPlanningEngine.kt:40-43`は`PlanningContext.profile`（nullable）の6フィールド中2つ（`averageTransitionDuration`／`averagePreparationDuration`）のみを参照し、profile不在時は`BasicPlanningDefaults`のハードコード値（移動5分・準備15分）へフォールバックする——このフォールバックは「Phase 10まで」の暫定値として最初から明示的に設計されている。行動ログはこの実装の入力データそのものであり、C-18とPersonal Profile永続化は不可分の1フェーズとして扱う。

## §2. 仕様整合（事前確認結果）

- **仕様§74（Phase 10）原文は極めて短い**: 「履歴を保存。preparation actual／transition actual／departure lag／arrival buffer。次回Planへ反映。」——delay message・イベントログ形式・保持期間には一切触れていない。**このうちarrival buffer（＝`preferredArrivalBuffer`）とdeparture lag（＝`averageDepartureDelay`）は、本アプリにGPS/到着検知の実装が存在しないため計測手段自体がなく、本フェーズでは算出対象外とする**（レビューCRITICAL・§13 No.3、正直な記録）。仕様が求める4指標のうち実装可能なのは`preparation actual`（`averagePreparationDuration`）・`transition actual`（`averageTransitionDuration`）の2つのみ。
- **行動ログの形式は仕様§53「Analytics / Test Log」が定義する広い語彙**（`event_selected`〜`next_event_selected`まで19種）と、§54「時刻ログ」（`plannedAt`/`shownAt`/`actedAt`/`completedAt`）が定める。`docs/TEAMS.md`のチーム編成表は「行動ログ（§53-54準拠のイベントログ）」を§76「Basic/AI Experiment」（本プロジェクトのPhase 12相当）の入力として位置づけている。**本フェーズは19種全ては実装しない**——コーディネーター指示どおり最小集合（ステップ完了/スキップ/遅延発生/Recovery選択/AI文言採否）に絞り、スキーマは将来§53-54の残りイベントへ拡張可能な形にする（§12確認事項8）。
- **§22「モデル自体を毎回Fine-tuningしない。ユーザー履歴をLocal Contextとして与える」**——仕様が想定するProfile活用方式はプロンプト注入である。ただし「いつ」注入するかは仕様が指定しておらず、実装順序の判断事項である（§12確認事項4）。
- **§10「Calendar / Location / Behavioral Historyを外部LLMへ送らない」**が「行動履歴, 出発履歴, 移動傾向, 準備時間, 訪問頻度」を機微データとして名指しで列挙——本フェーズが記録する内容そのものであり、外部送信禁止は絶対要件として継承する（ローカルLLMへの送信は既存Phase 7-9で許可された経路のまま変更しない）。
- **§60 Telemetryの外部送信許可リスト**（`event_category_hash`・`plan_generation_ms`・`step_count`・`delay_seconds`・`AI_enabled`）に生タイトルは含まれない——本フェーズはTelemetry送信自体を実装しないが、将来の送信可能性を見据えローカル保存の時点からタイトル生文を持たない設計にする。
- **§59「Local Data」**は保存カテゴリとして「Execution History・Personal Profile・AI Preferences・Plan History」の4つを明記——本フェーズが実装するのはこのうち前2つ。
- **仕様に保持期間・バックアップ除外・削除導線の規定は皆無**（全文grepで0件）——これらは仕様の空白域であり、本計画書が独自に設計しユーザー確認を経る（§12確認事項3・5・7、§12にて全件確定済み）。
- **§32 delay messageと§61「自動メール送信・自動SMS禁止」の緊張関係は不変**——実装するとしても「下書き作成→共有Intentでユーザー自身が送信」の設計しか許されない。Phase 6→Phase 9に続き**本フェーズが3度目の判断機会**（§12確認事項1、含めないことで確定）。
- **ADR-0049決定5「Analytics collaboratorは追加しない。T-GW-14はPhase 10（`AnalyticsStore`導入）／Phase 12（Analytics実装）とともに実装する」**——本フェーズが導入すべきクラス名は`AnalyticsStore`と明示されている。同ADRの再検討トリガーは「Phase 10の`AnalyticsStore`設計時に本ADRを参照し、コラボレータの正式な注入方式を設計すること」と直接指示している。`AiMetrics`（Phase 9で新設、`sanityRejectCount`/`lastSanityRejectReason`等）とは別概念——`AiMetrics`は呼び出し単位のインメモリ診断構造体で非永続、`AnalyticsStore`は本フェーズで新設する永続ストアである。
- **ADR-0025**: `persistence/ExecutionScheduleStore.kt`（PII-zero、SharedPreferences実装）のKDocは「Phase 10でRoomへ吸収する場合もinterface契約は不変とする」と述べる——移行は確定事項ではなく「経路として維持する」という含みであり、本フェーズでは移行しない（§12確認事項6、確定済み）。

## §3. 機能一覧と仕様

### 3.1 Room基盤導入（本プロジェクト初のDB）

`androidx.room:room-runtime`・`room-compiler`（KSP）を新規導入する。バージョンはContext7で確認したが正確なセマンティックバージョン文字列の特定に至らなかった（Context7が返したのはAndroidX開発ツリーのモジュール構成のみ）ため、**Step 3着手時に`developer.android.com/jetpack/androidx/releases/room`で最新安定版を再確認する**。KSP採用（kapt不使用）、`room-ktx`が独立アーティファクトとして必要か`room-runtime`へ統合済みかも未確認——同様にStep 3で再確認する。**KSPのバージョンはKotlinバージョンに連動する制約があるため（レビュー§13 No.9c）、本プロジェクトの現行Kotlinバージョンと互換のKSPバージョンをあわせて確認し、両者の組み合わせをStep 3確認事項として明記してからビルド変更へ進む**。

新設DB名`behavior_log.db`。`exportSchema = true`とし、スキーマJSONは`app/schemas/`配下へコミットする（§12確認事項5、確定）。将来のmigrationテスト基盤として最初から有効化する（v1時点ではmigration元が存在しないためmigrationテスト自体は次回スキーマ変更時に追加する）。

### 3.2 行動ログ（`BehaviorEvent`）

新設`@Entity BehaviorEventEntity`。フィールド:

- `id`（自動採番PK）
- `timestamp: Long`（epoch millis）
- `domain: String`（**`"plan"` / `"recovery"`の2値、レビューCRITICAL・§13 No.1で新設**。`AI_WORDING_OUTCOME`は`ContextualizationResult`〔Plan〕／`RecoveryContextualizationResult`〔Recovery〕のどちらから得たかで区別する。他4種のeventType（`STEP_DONE`/`STEP_SKIPPED`/`DELAY_DETECTED`/`RECOVERY_SELECTED`）は構造上すべてPlan確定後のExecution/Recoveryフローでのみ発生するため、一律`"recovery"`を採る——「Recovery画面限定」ではなく「Plan確定後のランタイム全体」を指す値として運用する旨をKDocに明記する（この割り当ては仕様に規定がなく本計画書の設計判断である）。
- `eventType: String`（`STEP_DONE`/`STEP_SKIPPED`/`DELAY_DETECTED`/`RECOVERY_SELECTED`/`AI_WORDING_OUTCOME`の5種、コーディネーター指示の最小集合）
- `eventCategory: String`（`EventCategoryClassifier.classify(title, locale)`の戻り値をその場で計算し格納、**タイトル自体は保持しない**）
- `semanticAction: String?`（`RECOVERY_SELECTED`時のみ非null）
- `durationMs: Long?`（`STEP_DONE`時の実績所要時間。妥当性クランプ後の値のみ格納、詳細下記）
- `aiAdopted: Boolean?`／`fallbackReason: String?`（`AI_WORDING_OUTCOME`時のみ非null）

**AI_WORDING_OUTCOMEの取得元を精密化（レビュー§13 No.9a）**: `fallbackReason`は`RecoveryOptionText`（文字列のみ返す純粋関数、採否フラグを持たない）からではなく、**`ContextualizationResult`／`RecoveryContextualizationResult`が`Unchanged(plan, reason: AiFallbackReason)`を返した時点の`reason`を直接使う**（`ai/LocalAiPlanContextualizer.kt`・`ai/LocalAiRecoveryContextualizer.kt`で型定義確認済み。両者は構造的に同型だが別のsealed interfaceであり、`Applied`／`Unchanged`をそれぞれ判定する）。`fallbackReason`は`AiFallbackReason.name`をそのまま格納し、Phase 9で新設済みのL5メトリクス（`AiMetrics.lastSanityRejectReason`）と同じ語彙（`AI_DISABLED`/`MODEL_NOT_INSTALLED`/`UNSUPPORTED_DEVICE`/`UNSUPPORTED_ABI`/`INSUFFICIENT_STORAGE`/`MODEL_LOAD_FAILED`/`MODEL_CORRUPTED`/`OUT_OF_MEMORY_PREVENTED`/`OUT_OF_MEMORY`/`TIMEOUT`/`SCHEMA_INVALID`/`UNKNOWN`）を再利用する——これがコーディネーター指示「L5メトリクスとの接続設計」への回答。

**フック点**（今回の調査で特定済み、実装はC2）:
- `STEP_DONE`: `ExecutionViewModel.kt:260-265` `handleConfirmedPlanDone()`（本番経路。`:196-200`の`handleDone()`は未到達のplaceholderで対象外）。
- `STEP_SKIPPED`: **`ExecutionViewModel`に直接のスキップアクションは存在しない**（`skippable: Boolean`フィールドのみ）。実際のスキップはRecovery適用経路で発生する。`RecoveryPlanApplier.apply(plan, option)`（`option.skippedStepIds: List<UUID>`を除去する純粋関数、副作用なし）は既存どおり純粋に保つ——ログは**`RecoveryViewModel.useThisPlan()`の`recoveryPlanApplier.apply(...)`呼び出し直後**（呼び出し元、ViewModel層）に配置する。純粋関数へ副作用を持ち込まない既存の設計方針（`LatenessDetector`等と同型）を踏襲する本計画書独自の是正であり、コーディネーター指示の原文（Recovery適用経路）とは矛盾しない。
- `DELAY_DETECTED`: **NavHost層のフックを廃止し`RecoveryViewModel`の`init`へ移設する**（レビュー§13 No.4、Gemini G3／オーケストレーターAD11の両者一致）。旧設計（`ActionStarterNavHost.kt:347`の`LatenessDetector.evaluate(...) is LatenessVerdict.WillMissEvent`分岐）はナビゲーション層への層違反であり、かつComposable再コンポジションでの多重記録リスクを抱えていた。`RecoveryViewModel`はこの判定が真のときのみ生成される（Recovery画面遷移＝遅延確定の唯一の経路）ため、**`init`で1回ログすることが「遅延検出イベント」の記録として構造的に十分**であり、ViewModelインスタンスのライフサイクル単位（画面遷移ごとに1個）で重複排除が自然に成立する（NavHostのような多重呼び出しの余地がない）。`LatenessDetector.evaluate`自体は引き続き純粋関数のまま変更しない。
- `RECOVERY_SELECTED`: `RecoveryViewModel.kt:169-189` `useThisPlan(optionId)`。`option.semanticAction`をそのまま格納する。
- `AI_WORDING_OUTCOME`（Plan側・新設）: `PlanReviewViewModel.kt`が`ContextualizationResult`を`Applied`/`Unchanged`でパターンマッチする箇所（`:156`・`:162`）に隣接してログを追加する。
- `AI_WORDING_OUTCOME`（Recovery側）: `RecoveryViewModel.kt`が`RecoveryContextualizationResult`を`Applied`/`Unchanged`でパターンマッチする箇所（`:149`・`:151`）に隣接してログを追加する。

**durationMsのプロセス死対策（レビュー§13 No.5、Gemini G5）**: `ExecutionViewModel`は既に`savedStateHandle: SavedStateHandle`をコンストラクタ第1引数として持ち、`"currentStepIndex"`（`KEY_CURRENT_STEP_INDEX`）キー規約で画面回転（T-EXEC-7）・プロセス再生成（T-EXEC-8）からの復元を実装済み——**同じ確立済みパターンで新規キー（例: `KEY_CURRENT_STEP_STARTED_AT_MILLIS`）を追加し、ステップ表示開始時刻を保存する**（新規DI配線不要）。`durationMs`算出時（`handleConfirmedPlanDone()`）は`doneAtMillis - startedAtMillis`を計算し、**負値、または当該ステップの計画時間窓を明らかに超過する値（プロセス死からの長時間経過等）はnullへクランプする**（不正な実績値をPersonal Profile集計に混入させない防御）。

### 3.3 Personal Profile集計・永続化

新設`@Entity PersonalExecutionProfileEntity`（`domain/model/PersonalExecutionProfile.kt`のRoom版、`eventCategory`をキーとしカテゴリ単位で1行）。**本フェーズが算出・反映する対象は`averageTransitionDuration`・`averagePreparationDuration`の2フィールドへ厳格に限定する**（レビューCRITICAL・§13 No.3）。残る`averageResponseDelay`・`averageDepartureDelay`・`preferredArrivalBuffer`の3フィールドは、GPS/到着検知の実装が本アプリに存在せず計測手段自体がないため**本フェーズでは算出せずnull維持**とする（§2参照、正直な記録）。エンティティ自体には将来の拡張余地として6フィールド分のカラムを用意してよいが、書き込みロジックが値を入れるのは2フィールドのみとする。

**導出表**（レビュー§13 No.3で新設）:

| `BehaviorEventEntity`由来 | フィルタ条件 | 集計方法 | `PersonalExecutionProfile`フィールド |
|---|---|---|---|
| `STEP_DONE`の`durationMs` | 対象ステップの`ExecutionStepType == TRANSITION` | 同一`eventCategory`・直近N件（§3.4保持期間内）の中央値 | `averageTransitionDuration` |
| `STEP_DONE`の`durationMs` | 対象ステップの`ExecutionStepType == PREPARATION` | 同上（中央値） | `averagePreparationDuration` |
| （計測手段なし） | — | 算出不能 | `averageResponseDelay`／`averageDepartureDelay`／`preferredArrivalBuffer`（null維持） |

**命名と実装の乖離を明記（レビュー§13 No.3）**: フィールド名は`average*`だが実装は**中央値**を格納する。理由: サンプル数が少ない初期段階（Personal Profileの本質的な制約）で外れ値（渋滞・忘れ物取りに戻った等の異常値1件）に平均が引っ張られることを避け、代表値としての頑健性を優先するため。この判断はKotlin実装側のKDocへ明記し、型定義（`domain/model/PersonalExecutionProfile.kt`）自体は変更しない。

**集計トリガー**: イベント記録の都度、非同期で該当カテゴリの集計を再計算し`upsert`する（同期実行しない。ユーザー操作をブロックしない、Phase 9.5で確立した「補助データはブロッキングしない」原則を継承）。**集計対象の時間窓は保持期間ローテーションに依存させず、集計クエリ自体が`timestamp > (現在時刻 − 集計窓ミリ秒)`という独立した述語でフィルタする**（レビュー§13 No.9b）——ローテーション処理のタイミングやバグに集計結果が左右されない設計。イベント0件のカテゴリは`PersonalExecutionProfile`を返さない（`PlanningContext.profile`の既存nullable設計と整合、既存の初回利用フローを変えない）。

### 3.4 プライバシー・保持期間・削除導線

- **外部送信ゼロ**（§10継承、既存方針の追認のみ・新規実装なし）。
- **バックアップ除外（レビューCRITICAL・§13 No.2で3ファイルへ訂正）**: 現状`AndroidManifest.xml:62`は`android:allowBackup="true"`かつ除外ルール未設定（実測確認済み）。minSdk 26/targetSdk 35（実測確認済み）のため新旧両方式が必要——`android:dataExtractionRules`（API 31+）と`android:fullBackupContent`（API 26-30）を両方追加する。**Roomのjournal_mode既定（WAL）によりDB本体だけでなくサイドカーファイル`behavior_log.db-wal`・`behavior_log.db-shm`も同時に生成されるため、両XMLとも`behavior_log.db`・`behavior_log.db-wal`・`behavior_log.db-shm`の3ファイルを`<exclude domain="database" path="...">`で明記する**（1ファイルのみの除外はWAL/SHM経由でのデータ漏出を防げない）。
- **保持期間・ローテーション**: 仕様に規定なし。**確定値は直近180日 or 直近500件のいずれか小さい方**（§12確認事項3、確定）。書き込み時に古い行を削除するローテーションとし、ローテーション自体の失敗は書き込み本体をブロックしない（§8）。
- **全削除導線**: Settings画面へ「行動ログを削除」アクションを新設し、確認ダイアログ（破壊的操作ガード必須）を経て`BehaviorEventEntity`・`PersonalExecutionProfileEntity`両方を全削除する`AnalyticsStore.clearAll()`を設ける。削除処理自体の失敗は握り潰さずUIへ結果を返す（§8、サイレント障害の原則違反を避ける）。
- **書き込み/clearAll排他（レビュー§13 No.6、Gemini G6）**: `RoomAnalyticsStore`内で`Mutex`を保持し、通常のイベント記録・Profile集計・`clearAll()`の全操作を直列化する。`clearAll()`実行中に別コルーチンからの書き込みが割り込み、削除直後に古いデータが復活する競合を防ぐ。

#### 3.4.1 実装注記（C4 Green、Step 4完了記録）

- **全削除導線**: `SettingsViewModel`に`onDeleteBehaviorLogRequested`（確認ダイアログ表示のみ）・`onDeleteBehaviorLogDialogDismissed`（キャンセル）・`onDeleteBehaviorLogConfirmed`（`AnalyticsStore.clearAll()`を呼ぶ唯一の経路、結果をSuccess/Failureで`SettingsUiState.deleteBehaviorLogResult`へ明示）・`onDeleteBehaviorLogResultAcknowledged`（結果バナーのクローズ）の4メソッドを実装した。`analyticsStore`が`null`（Room初期化失敗、C1の`AppContainer`防御と同型）の場合もサイレントに無視せず`Failure`として明示する。
- **確認ダイアログ**: `SettingsScreen`に`AlertDialog`（破壊的操作の文言を明示するtitle/message、confirm/dismiss独立ボタン）を追加した。`confirmButton`のonClickのみが`onDeleteBehaviorLogConfirmed`（clearAllを呼ぶ経路）を呼び、通常ボタン・`dismissButton`・`onDismissRequest`はいずれも到達しない（T-P10-18の構造的保証）。
- **結果表示の実装方式（コーディネーター指示「スナックバー等既存様式に合わせる」への回答）**: 当初`ActionStarterNavHost`の`SnackbarHostState`（T-NAV-4で確立済みの既存様式）の使用を検討したが、これは`SettingsScreen`自体には結びついておらずNavHostスコープの状態であり、配線するには`SettingsScreen`の§10.6疎結合契約（`uiState`＋コールバック引数のみで完結）を破ってNavHost状態への依存を持ち込む必要があった。代わりに、同じ`SettingsScreen.kt`内に既に存在する条件付きインラインText様式（`settings_ai_unsupported_reason`のエラー色表示、`settings_model_status_installed`の成功色表示）を「既存様式」として採用し、成功/失敗バナー＋明示的な閉じるボタン（`onDeleteBehaviorLogResultAcknowledged`）として実装した。§10.6準拠を維持しつつ、コーディネーター指示の趣旨（既存様式の踏襲・サイレント化しない明示表示）を満たす判断として完了報告で開示した。
- **T-P10-16b（Mutex直列化の決定的検証）**: `RoomAnalyticsStoreTest`へ`tP10_16b_clearAll_concurrentWithInFlightRecord_mutexSerializes_noStaleDataSurvives`を追加した。T-P95-55（`LocalAiGatewayTest`）で確立した`CompletableDeferred`シグナル方式を踏襲し、`record()`がmutex内でinsert中（人工遅延200ms）であることを確認してから`clearAll()`を起動、`insertCompleted`が必ず`deleteAllInvoked`より先に記録されることを直列化の決定的証拠とした。`clearAll()`から`mutex.withLock`を一時的に外すミューテーション検査で本テストが正しくRed化することを確認済み。
- **strings.xml**: `settings_data_section_title`ほか新規10キーをen/ja両方へ追加し、`StringResourceParityTest`のキー総数ピン（130→140）と新規キー一覧テストを追随させた。

### 3.5 スコープ外（見送り・理由を明記）

- **Profileのプロンプト注入（AI個人化）**: 見送り、Phase 11/12へ（§12確認事項4、確定）。理由: (a) 本フェーズ完了直後はProfileデータがほぼ空（コールドスタート）で注入の実効性がない。(b) Phase 9.5のF-1（few-shot条件選択）が実機A/B未検証のプロンプト変更で2度連続の品質退行を出した教訓——Profile注入も同種のプロンプト変更であり、Phase 12（Basic/AI比較実験基盤）が整うまで計測なしに投入すべきでない。(c) 仕様§22自体「モデル自体を毎回Fine-tuningしない」という設計方針は確認済みなので、実装時期を後ろ倒しにしても仕様との矛盾はない。
- **delay message（§32 option 3）**: 見送り、3度目の再送り（§12確認事項1、確定）。理由は不変——§61の自動送信禁止・§88の目的適合度の低さ・「対外連絡」という質的に異なるリスク領域。仕様§74自体がdelay messageに一切触れていないことも追認材料。
- **`ExecutionScheduleStore`のRoom移行**: 見送り（§12確認事項6、確定）。現状PII-zero・SharedPreferencesで機能上の問題なし。ADR-0025は「移行する場合の経路」を残しただけで移行を義務付けていない。
- **`AnalyticsStore`のPhase 12実装（Basic/AI比較実験の分析・レポーティングロジック）**: ADR-0049決定5どおりPhase 12。本フェーズは「導入」（永続化基盤とイベントスキーマ）のみ。
- **`averageResponseDelay`／`averageDepartureDelay`／`preferredArrivalBuffer`の算出**: GPS/到着検知の実装自体がPhase 10のスコープにないため見送り（§3.3参照）。

## §4. 計測方法論

本フェーズはPhase 9.5と異なりA/B実測を伴わない機能実装フェーズである。効果検証は「行動ログが正しく記録されているか」「Personal Profileが正しい値を算出するか」という機能正当性の検証であり、Red→Green→実機受け入れの通常TDDフローで進める（§10）。

## §5. 変更対象ファイル構成

- **ビルド変更**: `app/build.gradle.kts`（Room依存追加・KSPプラグイン適用）、ルート`build.gradle.kts`（KSPプラグインバージョン宣言、未適用なら）。
- **新設**: `app/src/main/java/com/actionstarter/persistence/room/`配下——`BehaviorLogDatabase.kt`（`@Database`）、`BehaviorEventEntity.kt`、`PersonalExecutionProfileEntity.kt`、`BehaviorEventDao.kt`、`PersonalExecutionProfileDao.kt`。`analytics/AnalyticsStore.kt`（インターフェース）＋`analytics/RoomAnalyticsStore.kt`（実装、`Mutex`による記録・集計・削除の直列化窓口）。`app/schemas/`（Room exportSchema出力先）。`app/src/main/res/xml/data_extraction_rules.xml`・`backup_rules.xml`（新設、DB本体＋WAL＋SHMの3ファイル除外）。
- **変更**: `AndroidManifest.xml`（バックアップ属性追加）、`di/AppContainer.kt`（DB・DAO・Repository配線、既存`by lazy`規約＋`LinkageError`防御と同型のtry/catch→null防御を新設、§8参照）、`features/execution/ExecutionViewModel.kt`（`STEP_DONE`ログ呼び出し・`SavedStateHandle`新規キー追加）、`features/recovery/RecoveryViewModel.kt`（`init`での`DELAY_DETECTED`ログ・`useThisPlan()`での`STEP_SKIPPED`/`RECOVERY_SELECTED`ログ・`RecoveryContextualizationResult`パターンマッチ箇所での`AI_WORDING_OUTCOME`ログ）、`features/planreview/PlanReviewViewModel.kt`（`ContextualizationResult`パターンマッチ箇所での`AI_WORDING_OUTCOME`ログ、`domain="plan"`）、`recovery/BasicPlanningEngine.kt`（Profile 2フィールドの実データ利用、ハードコード既定値フォールバックは維持）、`features/settings/`配下（削除導線UI追加）。
- **非変更**（明記）: `domain/model/PersonalExecutionProfile.kt`（既存scaffold、型定義は変更しない）、`ai/prompt/EventCategoryClassifier.kt`（Phase 9.5のドーマント実装をそのまま再利用、変更しない）、`recovery/RecoveryPlanApplier.kt`（純粋関数のまま維持、ログは呼び出し元`RecoveryViewModel`側、§3.2参照）、`navigation/ActionStarterNavHost.kt`（レビューによりDELAY_DETECTEDフックを廃止したため無改修に変更）、`persistence/ExecutionScheduleStore.kt`系（§3.5により見送り）、`ai/`配下のプロンプト生成系（§3.5によりProfile注入を見送るため無改修）。

## §6. 依存関係・技術選定の根拠

- **Room採用根拠**: オーケストレーター指示（本プロジェクト初のDB）。代替（引き続きSharedPreferences）は却下——行動ログは構造化された時系列データであり件数増加・カテゴリ別集計・保持期間ローテーションを要するため、キーバリュー型のSharedPreferencesでは表現力・クエリ性能の両面で不適。
- **KSP採用根拠**（kaptでなく）: Context7調査で確認済み、Room自体がKSPベースのコンパイラ処理へ移行済み。ビルド速度面でもkaptより有利。
- **JVMテスト可能性の根拠**: Context7で`Room.inMemoryDatabaseBuilder(context, klass)`がRobolectric環境（`ApplicationProvider.getApplicationContext()`／本プロジェクト既存の`RuntimeEnvironment.getApplication()`パターン）で動作することを確認済み——新規のテスト基盤パラダイムは不要。
- **未確定事項（Step 3で再確認）**: 正確な最新安定版バージョン文字列（Context7では特定できず）。`room-ktx`が独立アーティファクトかどうか。KSPバージョンとKotlinバージョンの互換組み合わせ（レビュー§13 No.9c）。Migration APIのシグネチャ——Context7で確認したAndroidX開発ツリーは`suspend fun migrate(connection: SQLiteConnection)`という新方式を示したが、これがピン留めする安定版でも同じ形かは未確認——古い`migrate(database: SupportSQLiteDatabase)`方式の可能性も残るため、対象バージョンのリリースノートで確定させる。

## §7. テストケースリスト（Robolectric JVM、既存757件無傷が前提）

**テスト方針（レビュー§13 No.7、Gemini G7）**: Room in-memory DBを使うテストは非決定的な非同期実行を避けるため、同期実行の`Executor`（`setQueryExecutor`/`setTransactionExecutor`）または`TestDispatcher`をテスト側から明示的に注入し、集計・ローテーション等の非同期処理を決定的に完了させてからアサーションする。本番コード側のディスパッチャ選択とテスト側の同期化を分離し、Phase 9.5で確立した`CompletableDeferred`等による確定的タイミング制御の精神を踏襲する。

| ID | 分類 | 内容 |
|---|---|---|
| T-P10-1 | 正常 | in-memory DBが正常にopenし、DAO経由でinsert/queryが往復する |
| T-P10-2 | 正常 | `STEP_DONE`ログが正しい`domain="recovery"`・`eventCategory`（`EventCategoryClassifier`経由）・クランプ後`durationMs`で記録される |
| T-P10-3 | 正常 | `STEP_SKIPPED`ログが`RecoveryViewModel.useThisPlan()`から`semanticAction`付きで記録される（`RecoveryPlanApplier`自体は無改修） |
| T-P10-4 | 正常（設計変更の回帰） | `DELAY_DETECTED`が`RecoveryViewModel`の`init`で1回だけ記録され、同一ViewModelインスタンス内で再度発火しない |
| T-P10-5 | 正常 | `RECOVERY_SELECTED`ログが選択された`option.semanticAction`で記録される |
| T-P10-6 | 正常 | `AI_WORDING_OUTCOME`（Recovery側）: `RecoveryContextualizationResult.Applied`なら`domain="recovery"`・`aiAdopted=true`・`fallbackReason=null` |
| T-P10-6b | 正常（レビュー§13 No.1） | `AI_WORDING_OUTCOME`（Plan側）: `ContextualizationResult.Applied`なら`domain="plan"`・`aiAdopted=true` |
| T-P10-7 | 異常 | `AI_WORDING_OUTCOME`: `Unchanged(plan, reason)`なら`aiAdopted=false`・`fallbackReason=reason.name`（Plan/Recovery両方で検証） |
| T-P10-8 | エッジケース | 未知タイトル（`CATEGORY_UNKNOWN`）でもログ記録がクラッシュせず継続する |
| T-P10-9 | 異常（回帰ガード） | いかなる`BehaviorEventEntity`カラムにもタイトル生文が格納されない（プライバシー回帰防止のpinningテスト） |
| T-P10-9b | 異常（回帰ガード・レビュー§13 No.2） | `data_extraction_rules.xml`・`backup_rules.xml`双方のソースを走査し、`behavior_log.db`・`behavior_log.db-wal`・`behavior_log.db-shm`の3ファイルすべてが除外リストに列挙されていることを確認するpinningテスト |
| T-P10-10 | 正常 | Personal Profile集計: N件のサンプルから正しい中央値`averageTransitionDuration`/`averagePreparationDuration`を算出する（`ExecutionStepType`でフィルタ） |
| T-P10-10b | エッジケース（レビューCRITICAL・§13 No.3） | `averageResponseDelay`/`averageDepartureDelay`/`preferredArrivalBuffer`は本フェーズを通じて常にnullのまま（誤って値が入らないことの回帰ガード） |
| T-P10-11 | エッジケース | 対象カテゴリのイベント0件（新規ユーザー）で集計がnullを返す（クラッシュしない） |
| T-P10-11b | エッジケース（レビュー§13 No.9b） | 集計クエリが保持期間ローテーション未実行でも独立した`timestamp`述語で正しい直近N件のみを対象にする |
| T-P10-12 | 正常（回帰） | `BasicPlanningEngine`がProfile非null時に実績値を使い、null時は既存ハードコード既定値（移動5分・準備15分）へフォールバックする |
| T-P10-13 | エッジケース | 保持期間ローテーション: 閾値（180日／500件）を超えた古いイベントが削除される |
| T-P10-14 | 異常 | ローテーション処理自体が失敗しても直前の書き込みはコミット済みのまま |
| T-P10-15 | 異常 | DB書き込み失敗（IO例外）がユーザー操作（ステップ完了等）をブロックしない（try/catchでno-op化） |
| T-P10-16 | 正常 | 全削除APIが`BehaviorEventEntity`・`PersonalExecutionProfileEntity`の両方を完全に削除する |
| T-P10-16b | 異常（レビュー§13 No.6） | `clearAll()`実行中に別コルーチンからの書き込みを試みても、`Mutex`直列化により削除後に古いデータが残らない（競合ケース） |
| T-P10-17 | 異常 | 全削除APIが失敗した場合、例外を握り潰さず呼び出し元へ結果を返す（サイレント化しない） |
| T-P10-18 | UI | Settings「行動ログを削除」ボタンが確認ダイアログを経てからのみ削除APIを呼ぶ（誤タップ防止） |
| T-P10-19 | 回帰 | Room・KSP導入後も既存757件が無傷でGreenを維持する |

## §8. エラー＆レスキューマップ

| 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|
| 行動ログ書き込み | Room I/O例外（ディスク容量不足等） | try/catchで捕捉しno-op化。ログは補助データであり本体機能をブロックしない | 影響なし（ログ欠落のみ、ステップ完了等の本体操作は継続） |
| Personal Profile集計 | 対象カテゴリのイベント0件 | null返却→`BasicPlanningEngine`は既存ハードコード既定値へフォールバック | 影響なし（既存の初回利用フローと同一） |
| 保持期間ローテーション | ローテーション処理自体が失敗 | 書き込み本体は継続、ローテーションのみ次回書き込み時に再試行 | 軽微（一時的に件数上限を超過する可能性のみ） |
| 全データ削除（Settings） | 削除処理中の例外 | 例外を握り潰さず結果をUIへ明示（成功/失敗表示）。破壊的操作のためサイレント化しない | 失敗時は明示エラー表示・再試行を促す |
| カテゴリ分類 | 未知のタイトルパターン | `EventCategoryClassifier`が`CATEGORY_UNKNOWN`を返す（既存の非クラッシュ設計を再利用） | 影響なし（unknown分類のまま記録継続） |
| **DB初期化失敗（レビュー§13 No.8・Gemini G8で具体化）** | Room初期化時の例外（スキーマ不整合・破損ファイル等） | `AppContainer`で`behaviorLogDatabase`・`analyticsStore`を`by lazy { try { ... } catch (e: Throwable) { null } }`（既存`localAiPlanContextualizer`等の`LinkageError`防御と同型パターン）で保護する。`analyticsStore`が`null`のときログ呼び出し元は全てno-opにフォールバックする（AI機能がOFF/未導入時にno-opになるのと同じ設計）。`fallbackToDestructiveMigration()`は**既定で有効化しない**（データ消失を伴うため、§12確認事項7で確定） | 行動ログ機能・Personal Profile反映のみ無効化。アプリ本体（Plan/Execution/Recovery）は継続動作する |

## §9. ADR起票方針

起票直前に`grep -n "^### ADR-" DECISIONS.md | tail -3`を再実行し最新確定ADRを確認したうえで、本フェーズの決定（Room採用・スキーマ設計・スコープ確定〔Profile注入/delay message見送り・arrival buffer等3フィールド算出見送り〕・`AnalyticsStore`のクラス設計、ADR-0049再検討トリガーへの回答）を実装完了後に正式起票する。

## §10. 実機受け入れ手順（A54）

Room自体はJVM（Robolectric）で完全にテスト可能なため、Phase 9.5のようなJVM検証不能ファイルは本フェーズには存在しない見込み——実機受け入れは「実デバイスのファイルシステム上でDBが正しく永続化されるか（WAL/SHMサイドカー含む）」「バックアップ除外ルールが実機のバックアップ設定画面で確認できるか」「Settings削除導線が実機で動作するか」の3点確認に絞る。既存Plan/Recovery生成の無傷確認（RF-1と同様の回帰確認）も併せて実施する。

## §11. コミット粒度（確定）

- **C1**: Room基盤（依存＋KSP・DB/Entity/DAO・`AnalyticsStore`・バックアップ除外XML〔3ファイル除外〕・`AppContainer`防御配線）
- **C2**: 行動ログ5種のフック配線（Plan/Recovery両`domain`）
- **C3**: Personal Profile集計＋`BasicPlanningEngine`の2フィールド反映
- **C4**: Settings削除導線＋クローズ（ADR起票・実機受け入れ）

**C1のStep 3（Red）から着手する**: Room最新安定版・KSP・room-ktx要否をContext7／リリースノートで確定→ビルド変更＋scaffold→T-P10-1/9/9b/15/17系のRed→既存757件無傷確認→報告して待つ。

## §12. ユーザー確認事項（Pass 2、確定済み・委任フロー）

1. **delay message（§32 option 3）を本フェーズに含めるか**: **【確定】含めない**。3度目の再送り、Phase 11以降で改めて判断。
2. **タイトル生文の保存可否**: **【確定】保存しない**。`EventCategoryClassifier`でカテゴリのみ抽出し記録する。
3. **保持期間の既定値**: **【確定】直近180日 or 直近500件の小さい方**。
4. **Profileのプロンプト注入（AI個人化）を本フェーズに含めるか**: **【確定】含めない**。Phase 11/12（測定基盤整備後）へ。
5. **Room schema export運用**: **【確定】`app/schemas/`へコミットしバージョン管理する**。
6. **`ExecutionScheduleStore`のRoom移行を本フェーズに含めるか**: **【確定】含めない**。現状PII-zero・SharedPreferencesのまま維持。
7. **DBスキーマ不整合時の`fallbackToDestructiveMigration()`可否**: **【確定】既定で有効化しない**。初期化失敗時は§8の`AppContainer`防御パターンにより行動ログ機能のみを無効化し、アプリ本体は継続動作させる。
8. **仕様§53-54の広いイベント語彙（19種）のうちどこまで実装するか**: **【確定】最小5種のみ**。残りはPhase 12（Basic/AI比較実験）で必要に応じ拡張する。

## §13. 敵対的レビュー記録（オーケストレーター＋Gemini、2026-08-12）

初稿ドラフト（起案版）に対する2系統レビュー（オーケストレーター9点・Gemini 8件、うちCRITICAL2件は両者収束）の指摘・採否を全件記録する。

### 採用（計画書修正）

| No | レビュー元 | 指摘要約 | 反映箇所 |
|---|---|---|---|
| 1 | 両者収束（CRITICAL） | `AI_WORDING_OUTCOME`がRecovery側のみで、Phase 12の主データであるPlan側の記録が欠落している | `BehaviorEventEntity`へ`domain`カラム新設、`PlanReviewViewModel`側のフック追加（§3.2・§5・§7 T-P10-6b） |
| 2 | 両者収束（CRITICAL） | バックアップ除外ルールがDB本体1ファイルのみで、Room既定のWAL journal modeが生成する`-wal`/`-shm`サイドカーからの漏出を防げない | 両バックアップXMLへ3ファイル除外を明記、ソーススキャン型pinningテスト追加（§3.4・§7 T-P10-9b） |
| 3 | Gemini（CRITICAL） | GPS/到着検知が存在せず`preferredArrivalBuffer`等3フィールドは算出不能なのに、当初案は6フィールド全てを算出対象としていた | 算出・反映対象を2フィールドへ厳格限定、導出表・命名乖離のKDoc明記を§3.3へ新設、§2へ正直な記録を追加（§2・§3.3・§7 T-P10-10b） |
| 4 | Gemini G3／オーケストレーターAD11（両者一致） | `DELAY_DETECTED`のNavHostフックは層違反かつComposable再コンポジションでの多重記録リスクを抱える | `RecoveryViewModel.init`へ移設、単一点記録の根拠を明記（§3.2・§5・§7 T-P10-4） |
| 5 | Gemini G5 | `durationMs`算出がプロセス死・画面回転で失われるステップ開始時刻に依存しうる | 既存`SavedStateHandle`パターンへ新規キー追加、妥当性クランプ（負値/計画窓超過→null）を仕様化（§3.2） |
| 6 | Gemini G6 | `RoomAnalyticsStore`の通常書き込みと`clearAll()`が並行実行された場合、削除直後に古いデータが復活しうる | `Mutex`による直列化を明記、競合ケースのテスト追加（§3.4・§7 T-P10-16b） |
| 7 | Gemini G7 | Room in-memoryテストの非同期処理が非決定的だとテストが偽陽性/偽陰性を生みうる | §7冒頭へ同期Executor/TestDispatcher注入の方針段落を新設 |
| 8 | Gemini G8 | DBスキーマ不整合時の初期化失敗ハンドリングが未具体化だった | `AppContainer`の`by lazy`+try/catch→null（既存`LinkageError`防御と同型）を§8へ明記、ログ機能のみ無効化しアプリ本体は継続 |
| 9 | 未特定（本ラウンド指摘、個別の帰属は伝達に含まれず） | (a) `fallbackReason`の取得元が`RecoveryOptionText`では不正確 (b) 集計窓が保持期間ローテーションに暗黙依存していた (c) KSPバージョンがKotlinバージョンに連動する制約が未記載 | (a) `ContextualizationResult`/`RecoveryContextualizationResult`の`reason`を直接参照するよう精密化（§3.2） (b) 集計クエリを独立した`timestamp`述語化（§3.3・§7 T-P10-11b） (c) Step 3確認事項へ追記（§3.1・§6） |

### 棄却

本ラウンドでオーケストレーター・Geminiより伝達された指摘は上記9件（一部は複数の下位指摘を束ねる）すべてが採用された。棄却として明示的に伝達された項目はない。
