# Action Starter Android ― Phase 5 実装計画書：Notification＋Execution（通知3種・Exact Alarm・boot再登録・Foreground Service・One Action多段階遷移）

**対象Phase**: Phase 5（仕様書§69 Phase 5「Notification＋Execution」、§62 通知3種、§95.1／§95.4／§95.6 Notification・Permission・Background Execution関連、§43 Services命名、§27-28 One Action、§34 ステップ省略のユーザー確認、§15 Local-first原則、§29 継続再計算）
**正仕様書**: `Action_Starter_Master_Specification_v2.0_Android.md`
**前提**: Phase 5サイクル（P5-C1）着手の前提条件は**Phase 3のクローズ**とする（本書の計画内容そのものに対するG1裁定とは別軸の着手条件。Phase 3の具体的なクローズ状況の確認は本書の範囲外＝`docs/plans/phase3-routing-location.md`側で確認する）。
**起点計画メモ**: android-planner（Opus）作成、2026-08-09（サブエージェント実行ログ `/tmp/claude-1000/-home-noritakasawada-project-app-project-0808/62ef4d74-427d-448a-8ab3-ab9f7ead819b/tasks/aa495466e0fc3e1a6.output` 内、最終応答全文。計画メモ自体が§0〜§10の構成を持つ）
**本書作成**: plan-doc-writer（Sonnet）、2026-08-09（初版）
**関連ハーネス文書**: `docs/TEAMS.md`（役割分担・PDCA・品質ゲートの正。契約変更経路は§5）、`docs/GOAL.md`（リリース判定基準）、`DECISIONS.md`（ADR記録先。本メモはADR-0008／ADR-0014／ADR-0015／ADR-0017／ADR-0018／ADR-0019／ADR-0022／ADR-0023を前提として参照し、本Phaseの決定をADR-0024〜0028として記録する想定）
**関連計画書**: `docs/plans/phase4-basic-engine.md`（Phase 4。本書の章立ての起点）、`docs/plans/phase3-routing-location.md`（Phase 3。着手条件となるクローズ状況はこちらを参照）

**ステータス: Fable 5レビュー済み・S-1〜S-9およびユーザー確認事項1〜5すべて裁定済み（2026-08-09・下記§4）・Fable 5＋Gemini G1完了・CRITICAL 6件反映済み（2026-08-09）→ G1通過。着手条件: Phase 3クローズ。**

本計画書はandroid-planner（Opus）が2026-08-09に作成したPhase 5計画メモ（§0〜§10）を忠実に文書化したものであり、計画メモにない機能・仕様を自己判断で追加していない。計画メモが自己補完を禁じてFable 5の裁定を要請した事項（S-1〜S-9＝メモ§2、R-3＝メモ§8、ユーザー確認が必要な事項1〜5＝メモ§10）は2026-08-09、**すべてandroid-planner推奨案どおり承認された**（詳細は§4）。Geminiによる第三者クロスレビュー（`model: "gemini-3.5-flash"`固定）はCRITICAL指摘6件（G1）を提示し、**Fable 5裁定によりすべて推奨案どおり採用し本書へ反映した（2026-08-09）。これによりG1（計画承認）は通過した**（§3）。

本書と正仕様書v2.0に差異が生じた場合は仕様書v2.0が正とする。本書は転記に徹し、メモが引用する実測（M5-1〜M5-16）・ADR番号・ソースファイル行番号について本書側での独自の再検証は行っていない（android-plannerが本メモ作成時に実測方法とあわせて記録済みの内容をそのまま転記する）。ただし`app/src/main/java/com/actionstarter/`配下のパッケージ構成（`mock/`・`ai/`・`features/`・`recovery/`等の実在）はディレクトリ一覧で確認し、メモが`main/.../services/notification/...`のように省略記法で示すパスは`main/java/com/actionstarter/services/notification/...`へ展開して転記した（§6.1）。**本書作成作業ではproduction codeを一切変更していない（読み取りのみ）。**

---

## 1. 目的

Phase 5は、仕様§69（Phase 5「Notification＋Execution」）が定める完成条件を満たすことを目的とする。実装対象は通知3種（§62）の提示、Exact Alarmの予約・boot後再登録（§95.1・§95.6）、Foreground Serviceによる位置アクセス継続（§95.1(b)・§95.4）、Execution画面のOne Action多段階遷移の本番結線（§27・§28）である。

android-planner（Opus）の実測（§7.1 M5-1〜M5-16）に基づく本Phaseの結論ファーストは以下5点（メモ§0）。

1. **本Phase最大の設計論点はboot後のアラーム復元データである。** 推奨は「`persistence/ExecutionScheduleStore`をSharedPreferences実装で新設（PIIゼロ・新規依存ゼロ）」。再登録断念は選択肢にならない（§69が明示的にPhase 5成果物として列挙、§95.6に該当行があり、断念＝「再起動したら通知が一切来ない」という無告知のサイレント障害になるためPass 1 CRITICAL）。Room（§95.1の字面）はKSP導入を伴い、Kotlin 2.4.10／KSP互換が未検証なためPhase 5の主題（exact alarm）に対して不釣り合いなリスクである（→S-1、Fable 5承認済み。§4）。
2. **Hilt再判定（ADR-0014）の推奨は③手動DI継続。** 実測: Hilt Gradle pluginは**2.59まではAGP≥8.4.0**、**2.59.1以降はAGP≥9.0.0**。つまり「AGP 8.x対応の旧Hilt版」は2.59（2026-01-21公開）であり、ADR-0014が書いた「版考古学」という却下理由は**実測により不正確**。ただし採用可否の焦点はAGPからKotlin 2.4.10／KSP互換へ移っており未検証。Phase 5で新規に増えるframework実体化コンポーネント（Service・Receiver 2種）は既存の`applicationContext as ActionStarterApplication`パターンで1行ずつ解決できるため、Hiltの限界価値は依然ゼロ。ADR-0024として記録し直し、再検討トリガーをAGP 9移行（ADR-0007）へ付け替える（→S-2、Fable 5承認済み。§4・§7.2）。
3. **Phase 5は新規依存ゼロで完遂できる（実測）。** `androidx.core 1.16.0`（既存classpath）に`NotificationManagerCompat`／`NotificationCompat`／`NotificationChannelCompat`／`AlarmManagerCompat`（`canScheduleExactAlarms`含む）／`ServiceCompat.startForeground(svc,id,notif,type)`／`PendingIntentCompat`が全て存在する（M5-6）。
4. **テストの大半がエミュレータ不要（実測）。** Robolectric 4.16.1の`ShadowAlarmManager`は`setCanScheduleExactAlarms(boolean)`（static）／`getScheduledAlarms()`／`ScheduledAlarm.getWindowLengthMs()`（`WINDOW_EXACT`／`WINDOW_HEURISTIC`定数あり）／`isAllowWhileIdle()`／`fireAlarm()`を持ち、`ShadowNotificationManager`は`getAllNotifications()`／`getNotificationChannels()`／`setNotificationsEnabled()`を、`ShadowService`は`getLastForegroundNotification()`／`getForegroundServiceType()`／`isForegroundStopped()`／**`setThrowInStartForeground(Exception)`**を持つ。exact許可/拒否・inexactフォールバック・FGS起動失敗フォールバック・通知3種ガードは全て`src/test`（`:app:testDebugUnitTest`）で固定できる（M5-7〜M5-10、§7.1）。
5. **§43の正式名は`NotificationService`（`Services`直下）であり、`NotificationScheduler`ではない。** 仕様遵守ルールに従い最上位契約は`services/notification/NotificationService.kt`とし、`AlarmManager`を隠蔽するL3境界を`AlarmScheduler`（Phase 3の`RawLocationSource`／`GeocoderSource`／`HttpPostClient`と同型）として分ける。`docs/TEAMS.md`§5 Phase 5行も「NotificationService」と表記しており整合する。

**補足（実測の留保、メモ§1補足）**: `title`解決関数`features/common/StepTitle.kt:27`は`@Composable`限定であり、通知本文（Composeの外）からは呼べない。§89「No duplicated domain logic」に反せずに再利用するには非Composeの`semanticId → @StringRes Int`抽出が必要（→S-8、Fable 5承認済み。§7.3）。

---

## 2. スコープ

### 2.1 やること（メモ§4.1。仕様§69の列挙に1:1対応。F番号はF49〜F61）

- F49 `NotificationService`契約（§43準拠の名称）と通知3種の閉じた集合定義（§62）
- F50 `AlarmScheduler`（L3境界）＋`AlarmManagerAlarmScheduler`（`setExactAndAllowWhileIdle`／inexactフォールバック／PendingIntent一意性）
- F51 exact alarm許可状態の判定（`AlarmManagerCompat.canScheduleExactAlarms`）と未許可時の劣化通知＋`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`導線（§95.1・§95.6）
- F52 POST_NOTIFICATIONS実行時権限要求（Execution Plan確定時、§95.4の取得タイミング規定どおり）と拒否時のアプリ内表示フォールバック
- F53 `NotificationTriggerReceiver`（アラーム発火→通知提示のみ。位置取得を試みない＝§95.1 While-in-use）
- F54 `ScheduleRestoreReceiver`（BOOT_COMPLETED／TIME_CHANGED／TIMEZONE_CHANGED／SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED）＋アプリ起動時の整合性チェック再登録（§95.6「Receiver不発時の保険」）
- F55 `persistence/ExecutionScheduleStore`（S-1。最小レコード・PIIゼロ）
- F56 `ExecutionForegroundService`＋`ExecutionServiceController`（S-3）
- F57 `ForegroundGate.isExecutionServiceRunning`の実配線（Phase 3申し送り§15 #10）
- F58 Execution One Actionの多段階前進（Done→次ステップ）の本番結線（M5-14の既知の制限を解消。§27／§28）
- F59 Snooze（5 min later）とアラーム再登録の連動（S-6）
- F60 通知タップ→アプリ起動→該当画面（Execution／Departure）復帰（`MainActivity`の`singleTop`＋`onNewIntent`＋route extra）
- F61 電池最適化除外の案内オンボーディング（§95.1）。`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（一覧を開く。追加権限不要）を用い、`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（制限付き権限＋Play正当化が必要）は使わない

**F番号帯についての注記**: Fable 5裁定サマリー（§4.1）は「F49〜F69をPhase 5に割当（Phase 6計画のU-1裁定と整合）」としているが、メモ§4.1が実際に列挙する機能はF49〜F61（13件）のみであり、F62〜F69の内訳はメモに記載がない。本書はメモに記載されたF49〜F61のみを機能一覧（§5）として転記し、F62〜F69は番号帯の予約として付記するに留める（Phase 6計画書側の裁定内容は本書の対象外であり自己補完しない。§14参照）。

### 2.2 やらないこと（明示、メモ§4.1）

- Recoveryの検知・発火（§70 Phase 6）。`NotificationKind.RECOVERY`はenum定数の宣言のみ（S-5）
- Room／`UserProfileStore`／`AnalyticsStore`（§74 Phase 10）。`persistence/`に置くのは`ExecutionScheduleStore`のみ（§88空プレースホルダ禁止）
- §29「最新現在地・経路情報からの継続再計算」（Phase 4 R-8申し送り。位置を使う再計算はフォアグラウンド復帰時のみという§95.1制約の実装設計を含め、Phase 6以降）
- §35 Screen 4の`Start navigation`（外部地図アプリ起動。Phase 3 §15 #8申し送りを継続保留、§88判定）
- WorkManager（§42は「＋WorkManager補助」と書くが、Phase 5の3通知はすべて時刻厳密＝exact alarmの領域であり、WorkManagerを足す理由が§88基準で立たない。追加依存でもある）
- 通知アクションボタン（§62「通知を増やすアプリにしない」。タップでOne Action画面を開けば足りる。S-7と同根）
- 同期Geocoderの IOディスパッチャ問題（Phase 3 P3-C6の既知ギャップ）。`services/location/`はPhase 5の不可侵領域（§6.4）であり本Phaseでは触れない

---

## 3. ゲート

`docs/TEAMS.md`§6に基づきG1〜G4を適用する。G4は**G4-JVM**と**G4-E**の2段階とする（ADR-0006踏襲、Phase 1〜4の先例と同じ）。

- **G1（計画承認）**: 本計画書＋エラー＆レスキューマップ（§9）＋Fable 5レビュー記録。**Fable 5レビューは、S-1〜S-9（メモ§2）・R-3（メモ§8。既存テスト期待値変更を伴うため裁定事項として扱う）・ユーザー確認が必要な事項1〜5（メモ§10）のすべてについて実施済みであり、いずれも推奨案どおり承認済み（2026-08-09、§4参照）。Geminiクロスレビュー（`model: "gemini-3.5-flash"`固定）はCRITICAL指摘6件を提示し、Fable 5裁定によりすべて採用し本書へ反映済みである（2026-08-09）。これによりG1（計画承認）は通過した。** また、**Phase 5サイクル（P5-C1）着手そのものの前提条件としてPhase 3のクローズを要する**（本書の計画内容そのものに対するG1裁定とは別軸の着手条件）。
- **G2（Red確認）**: P5-C2でtest-writerが作成したfailingテスト（§8、全53件のうちJVM系49件＝E1区分1件＋E2区分48件）をquality-runnerが実測する。E3区分4件（T-P5E2E-1〜4）は作成のみでRed実測はG4-Eまで行わない（Phase 1〜4と同じ扱い）。
- **G3（Green確認）**: P5-C3（Domain側Green）・P5-C4（UI側Green、C3と並列）それぞれでのGreen実測、およびP5-C6（統合ウィンドウ）・P5-C7（Refactor）後の再実測。
- **G4-JVM（Phase 5完了・JVM側）**: P5-C8完了時点。`./gradlew build`成功・対象範囲のJVM／Robolectric全テストPass・`lintDebug`エラー0を実測する。
- **G4-E（Phase 5完了・Emulator側）**: P5-C9完了時点。instrumented E2E（T-P5E2E-1〜4）を実行する。**§95.1が「Emulatorのみでの検証では不十分」と明記しているため、実機での通知遅延実測はリリース前QA（Phase 13）へ申し送る旨をG4報告に明記する**（R-5）。**G4-E未達のままPhase 6以降へ進むことを禁止する**（`docs/plans/phase2-calendar.md`§3の先例を踏襲）。

Phase 6着手条件は本書の範囲外とする。

---

## 4. 承認状態

**ステータス: Fable 5レビュー済み・S-1〜S-9およびユーザー確認事項1〜5すべて裁定済み（2026-08-09・下記）・Fable 5＋Gemini G1完了・CRITICAL 6件反映済み（2026-08-09）→ G1通過。着手条件: Phase 3クローズ。**

android-planner計画メモが提起した論点（S-1〜S-9、メモ§2）、リスクR-3（メモ§8。既存テストの期待値変更を伴うため裁定事項として扱う）、およびユーザー確認が必要な事項1〜5（メモ§10）について、Fable 5は**すべて推奨案どおり承認した（2026-08-09）**。

### 4.1 Fable 5裁定サマリー（2026-08-09、すべて推奨案どおり承認）

| # | 論点 | 裁定内容（承認） | 反映箇所 |
|---|---|---|---|
| S-1 | boot再登録の元データをどう持つか（本Phase最大の論点） | SharedPreferences方式`ExecutionScheduleStore`を承認。仕様「Room」字面からの逸脱にあたるため、**ADR記録トリガー③「仕様推奨からの逸脱」**として記録する。Phase 10でRoomへ吸収する（脚注1参照） | 本書§1、§7.2、§7.3 |
| S-2 | Hilt 3択の再判定 | 手動DI継続（③）。ADR-0024として記録し、ADR-0014の却下理由①を実測で訂正したうえで、再検討トリガーをAGP 9移行時（ADR-0007）へ付け替える | 本書§1、§7.1 |
| S-3 | Foreground Serviceのtype | `android:foregroundServiceType="location"`単独宣言。位置権限がないときはFGSを起動しないDegraded運用とし、Doze保護を受けられない残存リスクを受け入れる | 本書§7.4、§9 |
| S-4 | `USE_EXACT_ALARM`を使うか | `SCHEDULE_EXACT_ALARM`のみ宣言し`USE_EXACT_ALARM`は宣言しない。帰結として**inexactフォールバックが既定パスになることを受け入れる**。許可誘導はバナー＋ワンタップ導線の中強度とし、ブロッキング（モーダルで進行不能にする等）は禁止する | 本書§7.3、§9 |
| S-5 | Recovery通知をPhase 5で作るか | `NotificationKind` enumにRECOVERYを含めるが、Phase 5では発火経路・チャネル生成を作らない（Phase 6へ送る） | 本書§7.3 |
| S-6 | Snoozeの量 | 5分固定。既存`ExecutionViewModel.POSTPONE_DURATION`を単一の出所として維持する | 本書§2.1 F59 |
| S-7 | 「next action」の解釈 | アプリ内のOne Action前進（§27／§28）と解釈し、専用通知は作らない | 本書§2.2、§7.3 |
| S-8 | 通知本文の文言解決経路 | `semanticId → @StringRes Int`の対応表を非Composeの中立パッケージ`i18n/StepTitleKeys.kt`へ抽出する | 本書§6.1、§7.3 |
| S-9 | 取り逃したトリガーの扱い | 猶予（既定15分）以内なら即時発火、それ以外は破棄しExecution画面に「一部の通知を逃しました」を表示する。猶予値は`NotificationDefaults`へ隔離する | 本書§7.3、§9 |
| R-3 | 既存テスト（T-NAV-1／T-NAV-3）の期待値変更 | One Action多段階遷移（F58）は「Doneタップ1回でExecutionから離脱する」という既存前提と衝突するが、これは§27／§28の核心機能であるため、TEAMS§5の契約変更経路（変更提案→影響分析→Fable 5承認→ADR記録→両側テスト更新）を**P5-C2着手前に**発動し、T-NAV-1／T-NAV-3の期待値更新をADR記録つきでP5-C2にて実施することを承認する。テストを回避するためのハードコードや特殊分岐は禁止する | 本書§10、§11 R-3 |

**脚注1（S-1のADR記録トリガー番号についてのメモ内表記の不一致）**: メモ§2のS-1行は「ADRとして記録（記録トリガー②仕様未定義箇所の補完）」と記すが、メモ§10のユーザー確認事項1は同じS-1について「仕様の明示記述（Room）からの逸脱にあたるためADR記録トリガー③『仕様推奨からの逸脱』として承認が必要」としており、**メモ内でトリガー番号の表記が割れている**（②か③か）。本書はFable 5裁定（上表、トリガー③）を採用する。§4.2ではメモ§2原文の「トリガー②」表記もそのまま転記し、自己判断で統一せず本注記で不一致を明示する。

### 4.2 メモ§2原文（S-1〜S-9。仕様側の状況と推奨案の詳細、忠実に転記）

| # | 論点 | 仕様側の状況 | 推奨案と根拠 |
|---|---|---|---|
| **S-1** | **boot再登録の元データをどう持つか**（本Phase最大の論点） | §95.1／§95.6は「Room（ExecutionStore）の未完了Planから再スケジュール」と明記。しかし§64〜§77のどのPhaseも`ExecutionStore`を割り当てていない（§74 Phase 10は「履歴＝preparation actual/transition actual/departure lag/arrival buffer」＝`UserProfileStore`/`AnalyticsStore`側であり`ExecutionStore`ではない）。TEAMS.md §5 Phase 10行も「PersonalExecutionProfileのRoom永続化」と書く | **推奨: `persistence/ExecutionScheduleStore`（契約）＋`SharedPreferencesExecutionScheduleStore`（実装）をPhase 5で新設。** 根拠4点: (a) **断念は不可** — §69がboot再スケジュールをPhase 5成果物として明示、§95.6に専用行があり、断念すれば「再起動後に通知が一切来ることを誰にも知らせない」＝Pass 1 CRITICALのサイレント障害になる。(b) **Roomは主題外リスク** — KSP/kapt導入が必須で、M5-4/M5-5のとおりKotlin 2.4.10とKSPの互換が未検証。ADR-0014がAGP 9引き上げを却下したのと同じ「Phase本題に不釣り合い」論理がそのまま当てはまる。(c) **DataStoreも新規依存**（`androidx.datastore:datastore-preferences`最新安定1.2.1）で、かつ**BroadcastReceiver.onReceiveはコルーチン非対応（`goAsync()`か`runBlocking`が要る）**ため、boot復元という同期・短時間処理にはSharedPreferencesの同期読みが構造的に適合する。(d) **§88に照らして正当** — 「予定を今やる一つの行動に変える」ために、再起動で出発通知が消えないことは直接寄与する。**保存するのは最小レコードのみ**（§7.3。PIIゼロ）。Phase 10でRoomへ吸収する場合も契約は不変（interface差し替え1点）。ADRとして記録（記録トリガー②仕様未定義箇所の補完） |
| **S-2** | **Hilt 3択の再判定** | ADR-0014再検討トリガー「Phase 5着手時」に到達 | **推奨: ③手動DI継続**（詳細は§7.2） |
| **S-3** | **Foreground Serviceのtype** | §95.4は「FOREGROUND_SERVICE（+用途別type、例：FOREGROUND_SERVICE_LOCATION等）」と例示にとどまる。§95.1(b)は「location typeのFGSはフォアグラウンド中に開始した場合のみ位置アクセスを継続できる」と明記 | **推奨: `android:foregroundServiceType="location"`単独宣言＋位置権限が許可されているときのみFGSを起動する。** 位置権限が拒否されている場合はFGSを起動せず、exact alarm＋通知のみで動作し、精度低下を画面に明示する（§95.4 FGS行の「起動失敗時はbest-effortのバックグラウンド通知に切替え、精度低下をユーザーに明示」と同じ扱い）。**却下案**: `specialUse`（`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`とPlay審査での用途正当化が必要＝§95.5の審査リスク増）、`dataSync`（用途と宣言の不一致＝Play審査リスク、Android 15で6時間/24h制限）、`shortService`（3分上限で不成立）。**要検証**: API 34+で「manifestがtypeを宣言済みのServiceに対し`FOREGROUND_SERVICE_TYPE_NONE`で`startForeground`した場合の挙動」と「位置権限なしでlocation type起動時の例外種別」（P5-P2） |
| **S-4** | **`USE_EXACT_ALARM`を使うか** | §95.1は両方を挙げつつ「USE_EXACT_ALARMはカレンダー/アラームアプリに限定される特別権限」と注記 | **推奨: `SCHEDULE_EXACT_ALARM`のみ宣言し`USE_EXACT_ALARM`は宣言しない。** Action Starterはアラームクロック/カレンダーアプリそのものではなく、誤宣言はPlay審査での拒否リスク（§95.5）。**帰結として重要**: targetSdk 35（≥33）のため**SCHEDULE_EXACT_ALARMは新規インストール時に既定で不許可**（**要検証P5-P1**）。すなわち**inexactフォールバックが例外パスではなく既定パス**であり、UI導線（`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`、M5-12で存在確認済み）は必須機能であって「あれば良い」ものではない |
| **S-5** | **Recovery通知（§62の3種目）をPhase 5で作るか** | §62は通知を3種に固定。しかしRecoveryの発火源（lateness detection）は§70 Phase 6 | **推奨: `NotificationKind` enumにはRECOVERYを含める（3種固定を型で閉じ、回帰ガードの対象集合を確定させるため）が、Phase 5では発火経路・チャネル生成を作らない。** 通知が1件も出ないチャネルを設定画面に見せるのは§88の空プレースホルダに当たるため、RECOVERYチャネル生成はPhase 6へ送る。KDocに「Phase 6で発火経路を実装」と明記し、`recovery/`パッケージへの依存は一切作らない |
| **S-6** | **§69「Snooze」の量** | §69は"Snooze"とだけ書き、量を定めない。§27のUIは"[5 min later]"、現行`ExecutionViewModel.POSTPONE_DURATION = 5分` | **推奨: §27の"[5 min later]"に合わせ5分固定とし、既存定数を単一の出所として維持する**（ADR-0015型の既定値隔離と同じ扱い。仕様補完としてADR記録） |
| **S-7** | **§69「next action」の解釈** | 通知種別なのか、アプリ内のOne Action前進なのかが未定義。§62は通知を3種に固定しているので「next action通知」は§62と矛盾する | **推奨: アプリ内のOne Action前進（§27/§28）と解釈し、専用通知は作らない。** §62「通知を増やすアプリにしない／通知疲れを避ける」と整合させる（自己補完ではなく§62優先の解釈として明記し裁定を仰ぐ） |
| **S-8** | **通知本文の文言解決経路** | §7はUI文字列のハードコード禁止、ADR-0018は`semanticId → stringResource`をUI層に置いた。しかし`resolveStepTitle`は`@Composable`限定（M5-16補足） | **推奨: `semanticId → @StringRes Int`の対応表を非Composeの中立パッケージ`i18n/StepTitleKeys.kt`へ抽出し、`features/common/StepTitle.kt`と通知本文ビルダの双方がこれを参照する。** 複製実装（§89違反）と`services/ → features/`の層逆転の双方を避ける。`features/common/StepTitle.kt`の1行委譲化は統合ウィンドウ扱い |
| **S-9** | **時刻変更・boot遅延で「取り逃した」トリガーの扱い** | 仕様に規定なし。§95.6は「再起動直後の数分間は通知が遅延する可能性がある旨を明示」とのみ書く | **推奨: 復元時に`triggerAt <= now`のトリガーは、(a) `now`がイベント開始時刻より前かつ経過が猶予（既定15分）以内なら即時発火、(b) それ以外は発火せず破棄し、Execution画面に「一部の通知を逃しました」を表示する。** 猶予値は仕様未定義プレースホルダとして`NotificationDefaults`に隔離（ADR-0015の先例）。無条件即時発火は、電源を切って翌日起動したユーザーに古い出発通知を投げる事故になるため却下 |

### 4.3 F番号のPhase 5割当

Fable 5裁定は「F49〜F69をPhase 5に割当（Phase 6計画のU-1裁定と整合）」としている。メモ§4.1（本書§2.1）が実際に列挙する機能はF49〜F61（13件）であり、F62〜F69の個別内訳はメモに記載がない。番号帯の割当そのものはFable 5裁定として本書冒頭の承認状態に記録するが、F62〜F69が何を指すかは自己補完せず「メモに記載なし」として明示する（Phase 6計画書側での確認が必要。§14参照）。

---

## 5. 機能一覧（F49〜F61。メモ§4.1「やること」を表形式へ転記）

| ID | 機能 | 仕様根拠 | 備考 |
|---|---|---|---|
| F49 | `NotificationService`契約（§43準拠の名称）と通知3種の閉じた集合定義 | §43、§62 | L1契約。Android型を外へ出さない |
| F50 | `AlarmScheduler`（L3境界）＋`AlarmManagerAlarmScheduler` | §95.1 | `setExactAndAllowWhileIdle`／inexactフォールバック／PendingIntent一意性 |
| F51 | exact alarm許可状態の判定と未許可時の劣化通知＋許可誘導導線 | §95.1、§95.6 | `AlarmManagerCompat.canScheduleExactAlarms`／`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`（S-4：中強度・非ブロッキング誘導） |
| F52 | POST_NOTIFICATIONS実行時権限要求と拒否時のフォールバック | §95.4 | Execution Plan確定時に要求 |
| F53 | `NotificationTriggerReceiver`（発火→通知提示のみ） | §95.1 While-in-use | 位置取得を試みない |
| F54 | `ScheduleRestoreReceiver`（BOOT/TIME/TIMEZONE/EXACT_ALARM権限変更） | §95.6 | アプリ起動時の整合性チェック再登録も含む |
| F55 | `persistence/ExecutionScheduleStore` | S-1 | 最小レコード・PIIゼロ・SharedPreferences実装 |
| F56 | `ExecutionForegroundService`＋`ExecutionServiceController` | S-3 | Execution画面フォアグラウンドから起動時のみ |
| F57 | `ForegroundGate.isExecutionServiceRunning`の実配線 | Phase 3申し送り§15 #10 | `ActionStarterApplication.onCreate()`で1行接続 |
| F58 | Execution One Actionの多段階前進の本番結線 | §27、§28 | M5-14の既知の制限（未結線）を解消。R-3の契約変更を伴う |
| F59 | Snooze（5 min later）とアラーム再登録の連動 | §27、§69、S-6 | 5分固定 |
| F60 | 通知タップ→アプリ起動→該当画面復帰 | — | `singleTop`＋`onNewIntent`＋route extra |
| F61 | 電池最適化除外の案内オンボーディング | §95.1 | `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`使用。制限付き版（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）は使わない |

---

## 6. フットプリント

### 6.1 新規作成（`app/src/`起点。メモの`main/.../`省略記法は`main/java/com/actionstarter/...`へ展開して転記）

| パス | 内容 | 担当 |
|---|---|---|
| `main/java/com/actionstarter/services/notification/NotificationService.kt` | F49契約（§43名称）＋`NotificationKind`（3種閉集合）＋`ScheduleResult`/`NotifyResult` sealed | domain-implementer |
| `main/java/com/actionstarter/services/notification/AndroidNotificationService.kt` | F49/F52実装（NotificationManagerCompat・チャネル・POST_NOTIFICATIONS拒否時Skipped） | domain-implementer |
| `main/java/com/actionstarter/services/notification/NotificationContentBuilder.kt` | 文言・時刻フォーマット（S-8の`StepTitleKeys`経由。ハードコード禁止§7） | domain-implementer |
| `main/java/com/actionstarter/services/notification/AlarmScheduler.kt` | F50 L3境界契約（AlarmManager型を外へ出さない） | domain-implementer |
| `main/java/com/actionstarter/services/notification/AlarmManagerAlarmScheduler.kt` | F50/F51実装（exact→inexactフォールバック・PendingIntent一意性） | domain-implementer |
| `main/java/com/actionstarter/services/notification/NotificationTriggerReceiver.kt` | F53（発火→通知のみ。位置取得しない） | domain-implementer |
| `main/java/com/actionstarter/services/notification/ScheduleRestoreReceiver.kt` | F54（BOOT/TIME/TIMEZONE/EXACT_ALARM_PERMISSION_STATE_CHANGED） | domain-implementer |
| `main/java/com/actionstarter/services/notification/NotificationDefaults.kt` | S-9の猶予値等、仕様未定義プレースホルダの隔離（ADR-0015先例） | domain-implementer |
| `main/java/com/actionstarter/persistence/ExecutionScheduleStore.kt` | F55契約＋`ExecutionScheduleRecord` | domain-implementer |
| `main/java/com/actionstarter/persistence/SharedPreferencesExecutionScheduleStore.kt` | F55実装（`org.json`でシリアライズ・schemaVersion検証） | domain-implementer |
| `main/java/com/actionstarter/services/execution/ExecutionForegroundService.kt` | F56 | domain-implementer |
| `main/java/com/actionstarter/services/execution/ExecutionServiceController.kt` | F56/F57 | domain-implementer |
| `main/java/com/actionstarter/i18n/StepTitleKeys.kt` | S-8（非Composeの`semanticId → @StringRes Int`） | ui-implementer |
| `test/java/com/actionstarter/services/notification/AlarmSchedulingTest.kt` | T-ALARM-1〜10 | test-writer |
| `test/java/com/actionstarter/services/notification/AndroidNotificationServiceTest.kt` | T-NOTIF-1〜8 | test-writer |
| `test/java/com/actionstarter/services/notification/ScheduleRestoreReceiverTest.kt` | T-BOOT-1〜7 | test-writer |
| `test/java/com/actionstarter/services/notification/NotificationLlmIsolationTest.kt` | T-NOTIF-9（§15構造ガード。`PlanningLlmIsolationTest`の先例） | test-writer |
| `test/java/com/actionstarter/persistence/ExecutionScheduleStoreTest.kt` | T-STORE-1〜8 | test-writer |
| `test/java/com/actionstarter/services/execution/ExecutionForegroundServiceTest.kt` | T-FGS-1〜6 | test-writer |
| `test/java/com/actionstarter/features/ExecutionOneActionTest.kt` | T-P5UI-1〜8 | test-writer |
| `androidTest/java/com/actionstarter/e2e/NotificationExecutionE2ETest.kt` | T-P5E2E-1〜4 | test-writer |

### 6.2 既存ファイルの変更（Phase 5専有）

| パス | 変更内容 | 担当 |
|---|---|---|
| `features/execution/ExecutionViewModel.kt` | F58/F59。プレースホルダ3ステップを廃し、確定Planのステップ列で多段階前進。Snoozeで`NotificationService`へ再登録を委譲 | ui-implementer |
| `features/execution/ExecutionUiState.kt` | 劣化表示（exact不許可／通知不許可／FGS不可）のフィールド追加 | ui-implementer |
| `features/execution/ExecutionScreen.kt` | 劣化バナー＋設定導線。ONE ACTION原則（同時1ステップのみ描画）は不変 | ui-implementer |
| `features/common/StepTitle.kt` | S-8の1行委譲化のみ | ui-implementer |
| `res/values/strings.xml` / `res/values-ja/strings.xml` | 通知タイトル・本文・チャネル名・劣化文言。ja/en完全対（既存`StringResourceParityTest`が守る） | ui-implementer |

### 6.3 統合ウィンドウ（P5-C6、直列・integration owner = domain-implementer）

`AndroidManifest.xml`（POST_NOTIFICATIONS／SCHEDULE_EXACT_ALARM／RECEIVE_BOOT_COMPLETED／FOREGROUND_SERVICE／FOREGROUND_SERVICE_LOCATIONの5権限追加、`<service android:foregroundServiceType="location" android:exported="false">`、`<receiver>`×2、`MainActivity`に`launchMode="singleTop"`）・`di/AppContainer.kt`・`ActionStarterApplication.kt`（ForegroundGateフック接続1行）・`navigation/ActionStarterNavHost.kt`（通知タップroute解決・POST_NOTIFICATIONS launcher・`onDone`実結線・**Phase 6用`LatenessDetector.evaluate()`呼び出しフック（execution route内1箇所）を予約項目として明記——P5-C6の統合時にプレースホルダコメントを設置し、Phase 6のC5が実配線する**）・`MainActivity.kt`（`onNewIntent`）・`DECISIONS.md`（ADR-0024〜0028）・本計画書。

### 6.4 非重複宣言（他Phase領域への不可侵）

- **Phase 6領域**: `recovery/`・`mock/MockRecoveryFactory.kt`・`features/recovery/`・`RecoveryEngine`には一切触れない。`NotificationKind.RECOVERY`はenum定数の宣言のみで、`recovery/`パッケージへのimportを作らない（構造ガードテストで固定）。`MockRecoveryFactory`はPhase 6まで現役（ADR-0019注記）であり、Phase 5は削除も差し替えもしない。
- **Phase 3領域**: `services/location/`・`services/routing/`のファイルは一切変更しない。`ForegroundGate`への接続は`ActionStarterApplication.kt`（共有ファイル・統合ウィンドウ）側の代入1行のみで達成する。
- **Phase 4領域**: `planning/`（`BasicPlanningEngine`／`BasicPlanningDefaults`）は変更しない。`features/planreview/`も変更しない（Execution起動トリガーの追加はNavHost側で行う）。
- **Phase 10領域**: `persistence/`に作るのは`ExecutionScheduleStore`系のみ。`UserProfileStore`／`AnalyticsStore`／Room entity・DAO・databaseは一切作らない（§88）。
- **Phase 2領域**: `services/calendar/`・`services/permission/`・`features/eventselection/`は変更しない（POST_NOTIFICATIONSの権限判定には既存`PermissionGate.isGranted(permission: String)`の汎用シグネチャをそのまま再利用でき、`services/permission/`への変更は不要）。

---

## 7. 契約・設計

### 7.1 実測記録（M5-1〜M5-16。すべてandroid-plannerが本セッションで実行）

| # | 実測内容 | 実測結果 | 実測方法 |
|---|---|---|---|
| M5-1 | Hilt Gradle pluginの AGP下限 | 2.57.2 / 2.58 / **2.59 → 8.4.0**、**2.59.1 / 2.59.2 / 2.60.1 → 9.0.0** | 各jarをMaven Centralから取得し`strings \| grep "only compatible with Android Gradle plugin (AGP) version"` |
| M5-2 | Hilt各版の公開日 | 2.59: 2026-01-21 / 2.59.1: 2026-02-02 / 2.59.2: 2026-02-20 / 2.60.1: 2026-07-06 | POMの`last-modified`ヘッダ |
| M5-3 | AGPの最新 | stable最新9.3.1、metadata`<release>`は9.4.0-alpha08。8.x系の最新は8.13.2（本プロジェクト現行値と一致） | Google Maven `maven-metadata.xml` |
| M5-4 | KSPの最新 | `symbol-processing-gradle-plugin`最新2.3.11（2026-08-03）。POMの推移依存は`kotlin-stdlib 2.3.20` | Maven Central metadata / POM |
| M5-5 | 本プロジェクトのKotlin | 2.4.10（2026-07-14公開）。**KSP 2.3.11がKotlin 2.4.10を解析対象として支持するかは未確認＝要検証** | `gradle/libs.versions.toml` / Maven Central |
| M5-6 | androidx.core 1.16.0の提供クラス | `NotificationCompat`／`NotificationChannelCompat`／`NotificationManagerCompat`（Api26/30/34Impl含む）／`AlarmManagerCompat`（`canScheduleExactAlarms(AlarmManager)`）／`ServiceCompat.startForeground(Service,int,Notification,int)`／`PendingIntentCompat`すべて存在 | Gradleキャッシュ内`core-1.16.0.aar`の`classes.jar`を`unzip -l`／`javap` |
| M5-7 | Robolectric 4.16.1 `ShadowAlarmManager` | `static setCanScheduleExactAlarms(boolean)`・`canScheduleExactAlarms()`・`getScheduledAlarms()`・`getNextScheduledAlarm()`・`fireAlarm(ScheduledAlarm)`・`setExactAndAllowWhileIdle` shadow・定数`WINDOW_EXACT`/`WINDOW_HEURISTIC`を保持 | `shadows-framework-4.16.1.jar`を`javap` |
| M5-8 | 同`ScheduledAlarm`の観測面 | `type`／`triggerAtTime`／`interval`／`operation`(PendingIntent)／`allowWhileIdle`／`getWindowLengthMs()`／`getTag()`を公開 | 同上 |
| M5-9 | 同`ShadowNotificationManager` | `getAllNotifications()`／`getNotification(id)`／`size()`／`getNotificationChannels()`／`setNotificationsEnabled(boolean)`／`areNotificationsEnabled()` | 同上 |
| M5-10 | 同`ShadowService` | `getLastForegroundNotification()`／`getLastForegroundNotificationId()`／`getForegroundServiceType()`／`isForegroundStopped()`／`stopForeground(int)`／**`setThrowInStartForeground(Exception)`** | 同上 |
| M5-11 | android-35 platformのFGS type定数 | `FOREGROUND_SERVICE_TYPE_LOCATION`／`_SPECIAL_USE`／`_SHORT_SERVICE`／`_DATA_SYNC`／`_MANIFEST`／`_NONE`他が存在 | `~/Android/Sdk/platforms/android-35/android.jar`を`javap android.content.pm.ServiceInfo` |
| M5-12 | AlarmManager / Settings API | `canScheduleExactAlarms()`・`setExactAndAllowWhileIdle(int,long,PendingIntent)`・`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`、`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`・`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`・`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`が存在 | 同android.jarを`javap` |
| M5-13 | `ForegroundGate`のPhase 5フック | `ActivityLifecycleForegroundGate.isExecutionServiceRunning: () -> Boolean = { false }`（`var`、public）。`isLocationAccessAllowed() = isAppInForeground() \|\| isExecutionServiceRunning()` | `services/location/ActivityLifecycleForegroundGate.kt:40-45` |
| M5-14 | Executionの現状結線 | `ActionStarterNavHost`は`ExecutionViewModel`を経由せず`SharedPlanViewModel.confirmedPlan`から`ExecutionUiState`を直接構築し`onDone = null`を渡す。結果、**One Action多段階遷移は本番未結線**（NavHost KDocに既知の制限として明記済み）。`ExecutionViewModel`はプレースホルダ3ステップのみを扱い`handlePostpone`はメモリ上で`scheduledStart`に+5分するだけ | `navigation/ActionStarterNavHost.kt:97-202` / `features/execution/ExecutionViewModel.kt:76-86` |
| M5-15 | Manifestの現状 | 宣言済み権限はREAD_CALENDAR／ACCESS_FINE_LOCATION／ACCESS_COARSE_LOCATIONの3つのみ。`<service>``<receiver>`はゼロ。`MainActivity`に`launchMode`指定なし・deep linkなし | `app/src/main/AndroidManifest.xml` |
| M5-16 | JVMテストのベースライン | ディスク上の`app/build/test-results/testDebugUnitTest/`（XML 39ファイル）集計で**tests=239 / failures=0**。**これは既存成果物の読み取りであり、本セッションでの実行結果ではない**（Gradle実行は禁止制約のため未実施＝P5-C1で再実測が必要） | JUnit XMLの`tests=`/`failures=`属性を集計 |

**補足（実測の留保）**: `title`解決関数`features/common/StepTitle.kt:27`は`@Composable`限定であり、通知本文（Composeの外）からは呼べない。§89「No duplicated domain logic」に反せずに再利用するには非Composeの`semanticId → @StringRes Int`抽出が必要（S-8参照）。

### 7.2 Hilt再判定（ADR-0014再検討トリガー対応）

**推奨: ③手動DI（`AppContainer`）継続。ADR-0024として記録し、ADR-0014の却下理由を実測で訂正した上で再検討トリガーを付け替える。**（Fable 5承認済み＝S-2、§4）

| 選択肢 | 実測に基づく評価 | 判定 |
|---|---|---|
| ① AGP 8.x対応の旧Hilt版を採用 | **ADR-0014の「版考古学」という却下理由は実測により不正確**。AGP 8.13.2で使える最新Hiltは**2.59（2026-01-21公開、最新2.60.1のわずか2マイナー前）**（M5-1/M5-2）。ただし採用可否の焦点はAGPからKotlin/KSPへ移動: Dagger 2.59はKotlin 2.4.10（2026-07-14）より約半年前のリリースで、KSP最新2.3.11のPOM推移依存は`kotlin-stdlib 2.3.20`（M5-4/M5-5）。Kotlin 2.4.10×KSP 2.3.11×Dagger 2.59の三者互換は**未検証**であり、確認にはP2-C1と同型の探索プローブが再度必要 | **却下（ただし理由を差し替え）** |
| ② AGP 9系へ引き上げ | 9.3.1がstableとして入手可能（M5-3）。だがビルド基盤の全面移行であり、Phase 5の主題（exact alarm／FGS）と無関係。ADR-0013で回避済みの`enableUnitTest`以外にもvariant API・Gradle下限・`buildFeatures`既定値等の広範な影響があり、239件のベースライン（M5-16）を主題外の理由で壊すリスクを負う。ADR-0007の再検討トリガー（Phase 13配布前）を前倒しする理由が本Phaseには存在しない | **却下** |
| ③ 手動DI継続 | Phase 5は初めてframework実体化コンポーネント（`ExecutionForegroundService`＋Receiver 2種）を導入するため、これは古典的な「Hiltが効く」場面。しかし本プロジェクトには既に`(context.applicationContext as ActionStarterApplication).appContainer`という確立パターンがあり（`ActionStarterNavHost.kt:119-121`、`AppContainer.kt:141`）、Service/Receiver各1行で解決する。テスト側もRobolectricの実Application経由で同じ経路が使える。**限界価値は依然ゼロで、探索コストだけが発生する構図はP2-C1時点と変わらない** | **推奨** |

**ADR-0024に記録すべき内容**: 決定（③継続）／実測（M5-1〜M5-5の版境界）／ADR-0014の却下理由①の訂正／新しい再検討トリガー＝「AGP 9系への引き上げ時（ADR-0007の再検討と同時）」。Phase 6以降で毎Phase再判定するのは無駄なため、トリガーをPhase到達ベースからAGP移行ベースへ変更する（Fable 5裁定、S-2）。

**F58（`ExecutionViewModel`の結線方式）**: 既存テスト（`SavedStateHandle`のみのコンストラクタに束縛）を壊さないため、**新引数はすべてデフォルト値付きで追加する**（`sharedPlanViewModel: SharedPlanViewModel? = null`等・null時は現行プレースホルダ挙動を維持）。`AppContainer`のFactoryが実引数を供給する。既存テストの構築箇所は無変更で成立し、新テストは実引数で構築する。

### 7.3 `services/notification/`の契約（3層分割はPhase 3の先例と同型）

```
L1 NotificationService（契約・Android型を外へ出さない）
   suspend/非suspend の別・戻り値型は下記方針
     fun schedule(plan: ExecutionPlan): ScheduleResult
     fun cancelAll(planId: String)
     fun notifyNow(kind: NotificationKind, payload: NotificationPayload): NotifyResult
L2 AndroidNotificationService（NotificationManagerCompat + チャネル + 文言解決）
   AlarmSchedulingCoordinator（Plan → トリガー列 → store保存 → AlarmScheduler呼び出し）
L3 AlarmScheduler（fun interface でない通常 interface。android.app.AlarmManager を隠蔽）
   AlarmManagerAlarmScheduler（実装。AlarmManagerCompat 経由）
```

**戻り値型はsealedで劣化を型に出す**（ADR-0022 `LocationResult` / ADR-0023 `RoutingException`の先例）:

```
ScheduleResult = Exact(count) | Degraded(count, reason: DegradationReason) | Skipped(reason)
DegradationReason = EXACT_ALARM_NOT_PERMITTED | NOTIFICATIONS_DISABLED | FOREGROUND_SERVICE_UNAVAILABLE
```

`Boolean`や`Unit`を返さないこと。`Unit`にすると「exactで組めたのかinexactに落ちたのか」がログにも戻り値にも現れず、§95.6が要求する「精度低下をユーザーに明示」を実装する手段が消える（Pass 1サイレント障害）。

**通知3種（§62、閉じた集合）**

| kind | 発火時刻 | 内容（§29準拠） | Phase 5で発火するか |
|---|---|---|---|
| `TRANSITION_START` | `plan.transitionStart` | 「今の作業を終える」相当（`semanticId=transition`の文言、S-8経由） | する |
| `DEPARTURE` | `plan.departureTime` | 「Leave now」＋到着予測＋イベント開始＋buffer（§29が「単に『出発時間です』では不足」と明記） | する |
| `RECOVERY` | — | — | **しない**（S-5、Phase 6） |

**PendingIntent一意性の規約（§95.1）** — ハッシュに賭けず、**`requestCode`をストアに永続化する**。`ExecutionScheduleStore`が単調増加カウンタから`requestCode`を払い出し、レコードとともに保存する。再登録・キャンセルは常に保存済み`requestCode`を使うため、(a)衝突がありえない、(b)再起動後も「以前登録した正確なPendingIntent」をcancelできる（古いアラームの残存を構造的に防ぐ）、(c)テストで観測可能。フラグは`FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`（API 31+でimmutable明示が必須。`PendingIntentCompat`利用可・M5-6）。通知IDは同じ`requestCode`を流用し、同一トリガーの再通知が増殖しないことを保証する。

**アラームの宛先はBroadcastReceiver（ActivityでもServiceでもない）**。理由: 受信でプロセスが起動し、`onReceive`から`NotificationManagerCompat.notify`するだけなら、Android 12+のバックグラウンドActivity起動制限にもAndroid 14/15の「BOOT_COMPLETEDからのFGS起動制限」にも一切触れない。**`ScheduleRestoreReceiver`はFGSを起動しない**ことを構造ガードのテストで固定する（T-BOOT-7）。

**再登録の元データ（S-1のレコード定義）**:

```
ExecutionScheduleRecord(
  schemaVersion: Int,           // 不一致は破棄（信頼境界）
  planId: String,               // event.id 由来。ADR-0017の決定的id生成が前提
  eventStartEpochMillis: Long,
  estimatedArrivalEpochMillis: Long,
  triggers: List<Trigger>
)
Trigger(kind, stepId: UUID, semanticId: String, triggerAtEpochMillis: Long, requestCode: Int, fired: Boolean)
```

**PIIを保存しない**（§58/§60）: イベントタイトル・住所・座標を一切含めない。通知本文は`semanticId → string resource`（S-8）と時刻フォーマットのみから組み立てる。これはprivacy要件であると同時に、`ExecutionScheduleRecord`をSharedPreferences（アプリ専用ディレクトリだが平文）に置くことを正当化する根拠でもある。

**ADR-0017との関係**: `stepId`が`UUID.nameUUIDFromBytes("${event.id}:$semanticId")`で決定的であること（ADR-0017）が、再起動後に「保存済みトリガー」と「再planningしたPlanのステップ」を突き合わせられる前提である。ADR-0017が崩れると再登録の同一性判定が壊れるため、Phase 5のテストで**依存関係を明示的にロックする**（T-STORE-8）。

**タイムゾーン変更の扱い（重要な設計判断）**: 内部Domainの時刻は`Instant`（ADR-0008）で絶対時刻である。したがってTIMEZONE_CHANGEDでは**トリガーの絶対時刻を再計算してはならない**（表示だけが変わる）。再登録は冪等な保険として同じepoch millisで行う。ここを「ローカル壁時計から再導出」してしまうと、時差移動のたびに予定時刻がずれる重大バグになる（T-BOOT-3で回帰ロック）。

### 7.4 Execution中のForeground ServiceとForegroundGate実配線

- `ExecutionForegroundService`（`services/execution/`）: Execution画面に入った時点で**フォアグラウンドから**起動する。`ServiceCompat.startForeground(this, id, notification, FOREGROUND_SERVICE_TYPE_LOCATION)`（M5-6）。
- `ExecutionServiceController`: `isRunning`フラグ（プロセス内。プロセス死で自然にfalseへ戻るのが正しい挙動）を保持し、start/stopを仲介する。
- **`ForegroundGate`への接続は`ActionStarterApplication.onCreate()`で`foregroundGate.isExecutionServiceRunning = executionServiceController::isRunning`の1行**。`services/location/`のファイルは一切変更しない（Phase 3領域不可侵を守りつつ、M5-13のフックがそのまま使える設計になっている）。
- **§95.1(b)の前提保護**: 「フォアグラウンドで開始した場合のみ」が条件なので、`isRunning`を単なる「起動中」にすると、将来バックグラウンド起動経路が生えた瞬間にサイレントな位置取得失敗が復活する。**`ExecutionServiceController.start()`は`foregroundGate.isAppInForeground()`がtrueのときのみ起動を許し、そうでなければ`Skipped`を返す**。この不変条件をテストで固定する（T-FGS-5）。

---

## 8. テストケース表

### 8.1 分類定義

区分定義はPhase 4 §8.1を踏襲する。

| 区分 | 内容 | source set | runner | Gradleタスク | 必要端末 |
|---|---|---|---|---|---|
| E1 | 純JVM | `src/test` | JUnit4 | `:app:testDebugUnitTest` | 不要 |
| E2 | Robolectric（＋Compose Test） | `src/test` | JUnit4 + Robolectric（＋Compose Test） | `:app:testDebugUnitTest` | 不要 |
| E3 | instrumented | `src/androidTest` | AndroidJUnitRunner | `:app:connectedDebugAndroidTest` | **必要（エミュレータ）** |

### 8.2 テストケース一覧（全53件。**メモ§6見出しは「全50件」だが本書作成時の数え直しで52件と確定していた。Gemini G1 CRITICAL指摘の反映によりT-NOTIF-10を追加し53件に更新（2026-08-09）。差異の詳細は§14参照**。正常系19／異常系14／エッジケース11／回帰ガード9。E1区分1件〔T-NOTIF-9〕／E2区分48件／E3区分4件〔T-P5E2E〕）

#### F50/F51 — `AlarmManagerAlarmScheduler`（E2・Robolectric／`src/test`／`:app:testDebugUnitTest`／端末不要）— 全10件

| ID | 検証内容 | 分類 | 根拠§ |
|---|---|---|---|
| T-ALARM-1 | exact許可時、`RTC_WAKEUP`・`triggerAtTime = 期待Instant.toEpochMilli()`・`allowWhileIdle=true`・`windowLength=WINDOW_EXACT`のアラームが登録される | 正常 | §69・§95.1 |
| T-ALARM-2 | `setCanScheduleExactAlarms(false)`のときinexact（`windowLength != WINDOW_EXACT`）へフォールバックし、戻り値が`Degraded(EXACT_ALARM_NOT_PERMITTED)`になる | 異常 | §95.6 |
| T-ALARM-3 | 1つのPlanからTRANSITION_STARTとDEPARTUREの2件が登録され、`requestCode`が相異なる | 正常 | §95.1 |
| T-ALARM-4 | 同一Planで`schedule()`を2回呼んでもアラーム総数が増えない（同一requestCodeで置換） | エッジ | §95.1 |
| T-ALARM-5 | `cancelAll(planId)`後、該当Planのアラームが0件 | 正常 | §95.1 |
| T-ALARM-6 | `triggerAt <= now`のトリガーは登録せず、戻り値にmissedとして列挙される | エッジ | S-9 |
| T-ALARM-7 | **RECOVERY種別のアラームが1件も作られない**（3種以外を作らない回帰ガード） | 回帰ガード | §62・S-5 |
| T-ALARM-8 | すべてのトリガーが過去（イベント開始済みPlan）でもクラッシュせず`Skipped`を返す | エッジ | §89 |
| T-ALARM-9 | `setExactAndAllowWhileIdle`が`SecurityException`を投げた場合（実行時に許可が取り消された想定）、握り潰さずinexactへ再試行し`Degraded`を返す | 異常 | §95.6 |
| T-ALARM-10 | `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`受信で全アラームが再登録される | 異常 | M5-12（仕様未記載の追加防御） |

#### F49/F52 — `AndroidNotificationService`（E2・Robolectric／`src/test`／`:app:testDebugUnitTest`／端末不要。T-NOTIF-9のみE1）— 全10件

| ID | 検証内容 | 分類 | 根拠§ |
|---|---|---|---|
| T-NOTIF-1 | TRANSITION_START通知が1件だけ提示され、文言がstring resource由来（ハードコードなし） | 正常 | §62・§7 |
| T-NOTIF-2 | DEPARTURE通知が到着予測・イベント開始・bufferを含む（「出発時間です」だけでない） | 正常 | §29 |
| T-NOTIF-3 | `getNotificationChannels()`が宣言済みID集合と完全一致（余分なチャネルを作らない） | 回帰ガード | §62 |
| T-NOTIF-4 | `NotificationKind`の要素数が3（TRANSITION_START / DEPARTURE / RECOVERY） | 回帰ガード | §62 |
| T-NOTIF-5 | `setNotificationsEnabled(false)`のとき`notify`を呼ばず`Skipped(NOTIFICATIONS_DISABLED)`を返す（例外を投げない・握り潰さない） | 異常 | §95.6 |
| T-NOTIF-6 | 通知タップIntentが`MainActivity`宛て・route extraを持ち・`FLAG_IMMUTABLE`である | 正常 | §95.1 |
| T-NOTIF-7 | 同一kind・同一stepの再通知が同一notification idで置換され、通知が増殖しない | エッジ | §62 |
| T-NOTIF-8 | 通知IntentにイベントタイトルなどのPII文字列が含まれない | 回帰ガード | §58・§60 |
| T-NOTIF-9（E1） | `services/notification/`が`com.actionstarter.ai`を一切importしない（通知発火はKotlin側の決定的処理） | 回帰ガード | **§15**（`PlanningLlmIsolationTest`の先例） |
| T-NOTIF-10 | **`NotificationTriggerReceiver`がForeground Serviceを起動しない**（`ShadowApplication.getNextStartedService`等で検証） | 回帰ガード | §95.1（Android 14/15のReceiver起因FGS制限への構造ガード） |

#### F55 — `ExecutionScheduleStore`（E2・Robolectric／`src/test`／`:app:testDebugUnitTest`／端末不要）— 全8件

| ID | 検証内容 | 分類 | 根拠§ |
|---|---|---|---|
| T-STORE-1 | save→loadのラウンドトリップで全フィールドが一致 | 正常 | S-1 |
| T-STORE-2 | 空ストアからのloadは空を返す（nullや例外でない） | 正常 | §89 |
| T-STORE-3 | `schemaVersion`不一致のレコードを破棄し空を返す | 異常（信頼境界） | §95.6 |
| T-STORE-4 | 壊れたJSONでクラッシュせず破棄し、結果型で「破棄した」ことを返す | 異常（信頼境界） | §95.6 |
| T-STORE-5 | `clear()`でレコードが消える | 正常 | §59 |
| T-STORE-6 | Execution完了・中断のいずれでも`clear()`が呼ばれデータが残留しない | エッジ | §58・§59 |
| T-STORE-7 | 保存内容にイベントタイトル・住所・座標が含まれない（PIIゼロの回帰ガード） | 回帰ガード | §58・§60 |
| T-STORE-8 | 同一event/semanticIdで再planningしたPlanのステップidが保存済み`stepId`と一致する（ADR-0017依存のロック） | エッジ | ADR-0017 |

#### F54 — `ScheduleRestoreReceiver`（E2・Robolectric／`src/test`／`:app:testDebugUnitTest`／端末不要）— 全7件

| ID | 検証内容 | 分類 | 根拠§ |
|---|---|---|---|
| T-BOOT-1 | `ACTION_BOOT_COMPLETED`でストアの未完了レコードからアラームが再登録される | 正常 | §69・§95.1 |
| T-BOOT-2 | `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED`でも同様に再登録される | 正常 | §69・§95.1 |
| T-BOOT-3 | **TIMEZONE_CHANGEDでtriggerAtの絶対時刻が変化しない**（Instant基準・ローカル壁時計から再導出しない） | エッジ | ADR-0008・§7.3 |
| T-BOOT-4 | 再登録は既存アラームをcancelしてから行い、重複が発生しない | エッジ | §95.1 |
| T-BOOT-5 | ストアが空のとき何もせずクラッシュしない | エッジ | §89 |
| T-BOOT-6 | 想定外のactionを無視する（action文字列を検証せずに動かない） | 異常（信頼境界） | §89 |
| T-BOOT-7 | **`ScheduleRestoreReceiver`がForeground Serviceを起動しない**（Android 14/15のBOOT_COMPLETED FGS制限への構造ガード） | 回帰ガード | §95.1・P5-P2 |

#### F56/F57 — `ExecutionForegroundService` / `ExecutionServiceController`（E2・Robolectric／`src/test`／`:app:testDebugUnitTest`／端末不要）— 全6件

| ID | 検証内容 | 分類 | 根拠§ |
|---|---|---|---|
| T-FGS-1 | 起動時`getLastForegroundNotification()`が非nullで`getForegroundServiceType()`が期待値 | 正常 | §69・§95.4 |
| T-FGS-2 | 停止時`isForegroundStopped()`がtrueになり通知が除去される | 正常 | §69 |
| T-FGS-3 | `setThrowInStartForeground(...)`で起動失敗させた場合、best-effort通知へ切替え`Degraded(FOREGROUND_SERVICE_UNAVAILABLE)`を返す（クラッシュしない・握り潰さない） | 異常 | §95.4・§95.6 |
| T-FGS-4 | 起動中は`ForegroundGate.isExecutionServiceRunning()`がtrue、停止でfalse | 正常 | §95.1(b) |
| T-FGS-5 | **アプリがフォアグラウンドでない状態からのstart要求を拒否する**（§95.1(b)の前提保護） | 異常 | §95.1(b) |
| T-FGS-6 | 位置権限が拒否されている場合、location typeでの`startForeground`を試みない | 異常 | §95.4・S-3 |

#### F58/F59/F51/F52 — Execution One Action・Snooze・劣化表示（E2・Robolectric＋Compose／`src/test`／`:app:testDebugUnitTest`／端末不要）— 全8件

| ID | 検証内容 | 分類 | 根拠§ |
|---|---|---|---|
| T-P5UI-1 | Doneで次ステップへ進み、画面には常に1ステップのみ存在する（ONE ACTION回帰） | 正常 | §27・§28 |
| T-P5UI-2 | 最終ステップのDoneでdepartureへ遷移する（既存T-EXEC-4契約を壊さない） | 正常 | §35 |
| T-P5UI-3 | 「5 min later」で当該ステップの`scheduledStart`が+5分され、アラームも+5分で**再登録**される | 正常 | §27・§69 |
| T-P5UI-4 | 「5 min later」で通知が新規に増えない（同一idで置換） | 回帰ガード | §62 |
| T-P5UI-5 | Snoozeでイベント開始時刻を越える場合、ステップを自動省略せず**ユーザーに確認を促す**（AIも自動処理もステップ省略を勝手に決めない） | エッジ | **§34** |
| T-P5UI-6 | POST_NOTIFICATIONS未許可時、Execution画面のNOWカードのみで状態が伝わり設定導線が出る | 異常 | §95.6 |
| T-P5UI-7 | SCHEDULE_EXACT_ALARM未許可時、精度低下の明示表示＋`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`導線が出る（S-4：中強度・バナー＋ワンタップ、ブロッキング禁止） | 異常 | §95.6 |
| T-P5UI-8 | 画面回転で`currentStepIndex`が保持される（既存T-EXEC-7の維持） | エッジ | 既存契約 |

#### 全機能横断 — instrumented E2E（E3／`src/androidTest`／`:app:connectedDebugAndroidTest`／**エミュレータ必要**。**作成のみ・実行はG4-E**）— 全4件

| ID | 検証内容 | 分類 | 根拠§ |
|---|---|---|---|
| T-P5E2E-1 | 近未来（+10秒程度）にスケジュールしたTransition通知が実際に発火し、**通知タップでアプリがExecution画面で開く** | 正常 | §69完成条件 |
| T-P5E2E-2 | Execution開始でFGS通知が常駐し、終了で消える | 正常 | §69 |
| T-P5E2E-3 | exact alarm許可をOFFにした状態でも通知が（inexactで）発火し、精度低下表示が出る | 異常 | §95.6 |
| T-P5E2E-4 | 再起動後にアラームが復元される（**配送方法はP5-P5の結果次第。`adb reboot`実測 or Receiver直接起動へ降格**） | 異常 | §69・§95.1 |

E2E群は実行するまでpassとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ。Phase 1〜4の先例踏襲）。

---

## 9. エラー＆レスキューマップ（全18行。ハンドリング方法列に空欄なし）

| # | 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|---|
| 1 | exact alarm予約 | `SCHEDULE_EXACT_ALARM`が未許可（targetSdk 33+では**既定でこれ**。要検証P5-P1） | `AlarmManagerCompat.canScheduleExactAlarms`で事前判定し、未許可なら`setAndAllowWhileIdle`（inexact）へフォールバック。`ScheduleResult.Degraded(EXACT_ALARM_NOT_PERMITTED)`を返し、Execution画面に精度低下バナーと`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`導線を表示（S-4：中強度・バナー＋ワンタップ、ブロッキング禁止） | 通知が数分ずれうる旨を事前に警告表示。設定を変えれば厳密通知に復帰する（§95.6該当行の具体化） |
| 2 | exact alarm予約 | 予約直前に許可が取り消され`SecurityException`が発生 | try/catchでinexactへ再試行し`Degraded`を返す。**catchして無視しない**。加えて`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`を受信して全アラームを再登録 | 通知は届き続ける（精度のみ低下）。無通知にはならない |
| 3 | 通知提示 | `POST_NOTIFICATIONS`が拒否される（Android 13+） | `notify`をスキップし`Skipped(NOTIFICATIONS_DISABLED)`を返す。Execution画面のNOWカードのみで状態伝達。設定導線を提示し、後から許可されればON_RESUME再チェックで自動再開（`EventSelectionRoute`/`DepartureRoute`と同型） | アプリを開いていないと次アクション通知が届かない。この制約をオンボーディングで明示（§95.6該当行） |
| 4 | 通知提示 | 通知チャネルがユーザーによって個別にブロックされている | `NotificationManagerCompat.getNotificationChannel(id).importance == IMPORTANCE_NONE`を検査し、`Degraded`を返して画面に明示。チャネル設定画面への導線を出す | 特定種別の通知だけが来ない状態をユーザーが把握でき、設定で復旧できる |
| 5 | Foreground Service起動 | 位置権限が未許可でlocation typeの起動ができない | FGSを起動せず`Degraded(FOREGROUND_SERVICE_UNAVAILABLE)`を返す。exact alarm＋通知のみで動作を継続し、Doze下での遅延可能性を画面に明示 | Executionは継続する。Doze下での通知遅延リスクが上がることを明示（§95.4 FGS行） |
| 6 | Foreground Service起動 | `ForegroundServiceStartNotAllowedException`等で起動が拒否される | catchしてbest-effortのバックグラウンド通知へ切替え、`Degraded`を返す。**握り潰さない** | 同上。アプリはクラッシュしない（§95.4 FGS行） |
| 7 | Foreground Service起動 | アプリがバックグラウンドの状態から起動要求が来る | `ExecutionServiceController.start()`が`foregroundGate.isAppInForeground()`を検査し、falseなら起動せず`Skipped`を返す | 位置取得のサイレント失敗（§95.1 While-in-use）が構造的に発生しない |
| 8 | アラーム発火時の処理 | 発火時に位置情報を取りに行き`SecurityException`／nullになる | `NotificationTriggerReceiver`は位置取得を一切呼ばない（構造的排除）。位置を使う再計算は通知タップでのフォアグラウンド復帰後、またはExecution FGS継続中のみ | 通知タップでアプリを開くまでETA再計算が保留される（§95.1・§95.6該当行） |
| 9 | 端末再起動 | AlarmManager登録が全消去され通知が一切発火しなくなる | `ScheduleRestoreReceiver`が`BOOT_COMPLETED`を受け、`ExecutionScheduleStore`の未完了レコードから再登録。**加えてアプリ起動時にも整合性チェックと再登録を実行**（OEM独自の自動起動制限でReceiverが不発の場合の保険） | 通知は復元される。再起動直後の数分間は遅延しうる旨を明示（§95.6該当行） |
| 10 | 時刻/タイムゾーン変更 | 絶対時刻を壁時計から再導出してしまい予定時刻がずれる | 保存レコードはepoch millis（絶対時刻）のみを持ち、TIMEZONE_CHANGEDでは**再計算せず同一値で冪等に再登録**する。T-BOOT-3で回帰ロック | 時差移動しても予定の絶対時刻がずれない |
| 11 | 復元時のトリガー判定 | 停止中に発火時刻を過ぎ、通知が取り逃される | `triggerAt <= now`のトリガーは、イベント開始前かつ猶予（既定15分、`NotificationDefaults`に隔離）以内なら即時発火、それ以外は破棄してExecution画面に「一部の通知を逃しました」を表示 | 数日前の古い通知が突然出る事故を防ぎつつ、取り逃しをユーザーに知らせる（S-9・仕様補完） |
| 12 | 永続レコード読み出し | JSONが破損している／`schemaVersion`が不一致 | パース失敗と版不一致はいずれもレコードを破棄し、結果型で「破棄した」ことを返す。破棄はログに残す。**空を返して沈黙しない** | 通知は復元されないが、アプリは正常起動し次回Plan確定で再スケジュールされる |
| 13 | 永続レコード書き込み | ストレージ書き込み失敗（容量不足等） | 書き込み結果を検査し、失敗時は`ScheduleResult.Degraded`に「再起動時に通知が復元されない可能性」を含めて返し画面に明示 | 当該セッション中の通知は届くが、再起動すると届かない可能性があることを事前に知れる |
| 14 | PendingIntent管理 | 再スケジュール時に古いアラームが残存し重複発火する | `requestCode`をストアに永続化し、再登録は常に「保存済みrequestCodeでcancel→新規schedule」の順で行う。T-ALARM-4で冪等性をロック | 同じ通知が二重に届かない（§95.1「PendingIntent一意性」の具体化） |
| 15 | Execution中の状態保持 | プロセスが死んで`ExecutionServiceController.isRunning`が失われる | フラグはプロセス内メモリのみに持つ（永続化しない）。プロセス死＝false復帰が正しい挙動。復帰後は`ForegroundGate`が`isAppInForeground()`のみで判定 | 位置取得が「サービスが生きているつもり」で誤許可されない |
| 16 | Snooze | Snoozeでイベント開始時刻を越える | ステップを自動省略・自動短縮せず、ユーザーへ確認を促す。決定はユーザーが行う | 勝手に予定を削られない（**§34**「ステップ省略はユーザー確認必須」） |
| 17 | 通知タップ | アプリが既に起動中で新しいIntentが`onCreate`に来ない | `MainActivity`を`launchMode="singleTop"`とし`onNewIntent`でroute extraを処理してNavHostへ反映 | 既に開いている状態でも通知タップで正しい画面へ移動する |
| 18 | 通知タップ | route extraが不正・未知の値 | 未知routeは無視し、既定のExecution（Plan未確定ならEventSelection）へ遷移する。例外を投げない | 通知タップが必ず有効な画面に着地する（信頼境界: Intent extraを検証なしに信頼しない） |

---

## 10. サイクル分解

### 10.1 P5-C1〜C9

| サイクル | 内容 | 担当 | ゲート |
|---|---|---|---|
| **P5-C1** | **probe＋契約scaffold**（TDD例外）。P5-P1〜P5-P6を実測し、scaffoldは全て`TODO()`。**例外的に完全実装するのは`ExecutionServiceController`の`isRunning`フラグのみ**（`ActionStarterApplication`へ無条件接続され、`TODO()`のままだと全Robolectricテストが`NotImplementedError`で壊れる。ADR-0023と同型の構造的必然） | domain-implementer | ベースライン再実測（M5-16の239件を本セッション未実行として再確認）＋コンパイル成功 |
| **P5-C2** | **Red**。§8のテストケース全件を作成し、意図した理由での失敗を実測。**R-3の契約変更経路（TEAMS§5）をP5-C2着手前に発動し、T-NAV-1/T-NAV-3の期待値更新をADR記録つきで実施する**（§4・§11 R-3） | test-writer → quality-runner | G2 |
| **P5-C3** | **Green（Domain側・並列A）**。`services/notification/`・`persistence/`・`services/execution/` | domain-implementer | G3 |
| **P5-C4** | **Green（UI側・並列B）**。`features/execution/`・`i18n/StepTitleKeys.kt`・strings | ui-implementer | G3 |
| **P5-C5** | **同期ポイント**。並列2本のGreen確認とAPI齟齬の解消 | Fable 5 | — |
| **P5-C6** | **統合ウィンドウ（直列）**。§6.3の共有ファイル群。ここで初めてManifest権限・Service・Receiverが有効になる | domain-implementer（integration owner） | G3 |
| **P5-C7** | **Refactor＋Green再実測** | 両implementer | G3 |
| **P5-C8** | **G4-JVM**。`./gradlew build`＋全JVMテスト | quality-runner | G4-JVM |
| **P5-C9** | **G4-E**。エミュレータでのinstrumented E2E（T-P5E2E）。**§95.1が「Emulatorのみでの検証では不十分」と明記しているため、実機での通知遅延実測はリリース前QA（Phase 13）へ申し送る旨をG4報告に明記する** | quality-runner | G4-E |

**着手前提**: P5-C1の着手前提条件はPhase 3のクローズとする（本書冒頭の承認状態参照）。

### 10.2 probe対象（P5-P1〜P5-P7。すべてP5-C1で実測し結果を計画書へ追記）

| # | probe | 現時点の想定（**すべて要検証**） | 未達時のフォールバック |
|---|---|---|---|
| **P5-P1** | エミュレータ（API 35）での`canScheduleExactAlarms()`初期値、および`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`の解決可否 | targetSdk 33+のため**既定false**（S-4の帰結） | falseが確認できない場合でもinexactフォールバック実装は必須（§95.6）。テストは`ShadowAlarmManager.setCanScheduleExactAlarms`で両分岐を固定 |
| **P5-P2** | API 34+のFGS type要件。(a)位置権限なしで`FOREGROUND_SERVICE_TYPE_LOCATION`起動時の例外種別、(b)manifestがtype宣言済みのServiceに`FOREGROUND_SERVICE_TYPE_NONE`を渡した場合の挙動 | (a)`SecurityException`、(b)`IllegalArgumentException`の可能性 | (a)が確認されればS-3の「位置権限なし時はFGSを起動しない」設計が確定。(b)が不可ならtype選択肢をS-3の却下案から再検討（Fable 5へ再エスカレーション） |
| **P5-P3** | Robolectric `ShadowAlarmManager`の**挙動**（API面はM5-7で実測済み）: `setCanScheduleExactAlarms(false)`時に実`setExactAndAllowWhileIdle`が例外を投げるか黙って記録するか／`getWindowLengthMs()`がexactで`WINDOW_EXACT`になるか／`setAutoSchedule`の既定値 | exact判定は`windowLength == WINDOW_EXACT`＋`isAllowWhileIdle()`で観測可能 | 観測不能な項目があれば、その1項目だけをinstrumented（E3）へ降格し、他はJVMに残す |
| **P5-P4** | `NotificationManagerCompat.notify`が`ShadowNotificationManager.getAllNotifications()`に捕捉されるか（Compat経由でもshadowが効くか） | 捕捉される | 捕捉されない場合は`NotificationManagerCompat`を薄い自前seamでラップし、seamをfake化 |
| **P5-P5** | Robolectric（`src/test`）でmanifest登録Receiverへ`ACTION_BOOT_COMPLETED`を配送できるか。およびinstrumented側で`adb shell am broadcast -a android.intent.action.BOOT_COMPLETED`がprotected broadcast制限で拒否されないか | JVM側は`context.sendBroadcast`で到達する見込み。**adb側は拒否される可能性が高い** | adbで不可ならE2Eは`adb reboot`後の実測に切り替える（時間はかかるが正直な検証）。JVM側で不可ならReceiverの`onReceive`を直接呼ぶ形に落とす |
| **P5-P6** | `Robolectric.buildService()`＋`ShadowService`がAPI 35で`getForegroundServiceType()`を正しく返すか（M5-10でAPI面は実測済み） | 返す | 返さない場合はFGS typeの検証のみE3へ |
| **P5-P7** | （**S-2で①が選ばれた場合のみ実施**）Hilt 2.59×AGP 8.13.2×Kotlin 2.4.10×KSP 2.3.11の四者互換 | 未検証 | 失敗したら即③へ戻し、ADR-0024へ実測ログとともに記録（P2-C1と同じ手順） |

**P5-P7についての注記**: Fable 5はS-2について推奨案どおり「③手動DI継続」を承認した（§4）。したがってP5-P7の実施条件（S-2で①が選ばれた場合）は満たされず、**本Phaseの承認済み計画ではP5-P7を実施しない**。将来①を再検討する場合にのみ再度実施対象となる。

---

## 11. リスク

| # | リスク | 対応 |
|---|---|---|
| R-1 | **並行E2E実行中のためベースライン（M5-16の239件）が本セッションで未実測** | P5-C1の最初のタスクとしてベースラインを再実測し、計画書§実測欄に実行ログの絶対パスとともに記録する。差異があればP5-C1で報告 |
| R-2 | FGS typeの裁定（S-3）がP5-P2の結果で覆ると、Manifest・Service・テストが同時に手戻る | P5-P2をP5-C1の最優先probeとし、**結果が出るまでP5-C2（Red）のT-FGS群を着手しない**。Manifest変更は統合ウィンドウ（P5-C6）まで遅らせる設計にしてあるため、手戻り範囲は`services/execution/`内に収まる |
| R-3 | `ExecutionViewModel`のコンストラクタ契約変更が既存テスト（`ExecutionViewModelTest`／`ExecutionScreenTest`／`NavigationFlowTest` T-NAV-1/T-NAV-3）を壊す。特に**T-NAV-1/T-NAV-3は「Doneタップ1回でExecutionから離脱する」前提**（NavHost KDocに明記） | F58（多段階前進）は**この前提と正面から衝突する**。TEAMS§5「interface契約のバージョン付き変更経路」（変更提案→影響分析→Fable 5承認→ADR記録→両側テスト更新）を**P5-C2着手前に**発動し、T-NAV-1/T-NAV-3の期待値更新をADRで承認する。テストを回避するためのハードコードや特殊分岐は禁止。**Fable 5承認済み（2026-08-09、§4参照）** |
| R-4 | Kotlin 2.4.10×KSP互換が未検証のままRoom／Hiltを選ぶと、Phase 5の主題と無関係な探索に時間を溶かす（P2-C1の再演） | S-1でSharedPreferences、S-2で手動DI継続を推奨し、KSPを導入しない。P5-P7は①が選ばれた場合のみ実施（本Phaseでは非該当。§10.2） |
| R-5 | Robolectricで検証できない項目（実機Doze・OEM独自の電池最適化）を「テストGreen＝安全」と誤読する | §95.1が「Emulatorのみでの検証では不十分」と明記。G4-E報告に「Pixel / Samsung / Xiaomi実機での通知遅延実測はPhase 13リリース前QAの必須項目として未実施」と明記する |
| R-6 | `persistence/`パッケージを新設することで、Phase 10のRoom導入時に二重実装になる | `ExecutionScheduleStore`はinterfaceとして定義し、Phase 10でRoom実装へ差し替える経路をADRに明記。`UserProfileStore`/`AnalyticsStore`は作らない（§88） |
| R-7 | `NotificationKind.RECOVERY`を宣言するだけで発火経路がない状態が「空プレースホルダ」（§88）と判定される | enum定数のKDocに「Phase 6（§70）で発火経路を実装」と明記し、チャネル生成も行わない。「3種固定」という§62の制約を型で表現するための最小宣言であることを計画書に記載（S-5） |
| R-8 | 「Phase 5完了」が「§29の継続再計算が動く」と誤解される（Phase 4 R-8と同型） | G4完了報告に「§29の再計算・`Start navigation`・Recovery検知はPhase 5スコープ外」と明記 |
| R-9 | `features/common/StepTitle.kt`（Phase 4資産）に触れることで所有権が曖昧になる | S-8の抽出はui-implementer所有とし、`services/notification/`からは`i18n/StepTitleKeys.kt`（中立パッケージ）のみを参照する。P5-C5同期ポイントで齟齬を確認 |

**Phase 6用フック予約**: Phase 6用`LatenessDetector.evaluate()`呼び出しフック（execution route内1箇所）を予約項目として明記する——P5-C6の統合時にプレースホルダコメントを設置し、Phase 6のC5が実配線する（§6.3）。

---

## 12. 仕様の矛盾・未定義とユーザー確認が必要な事項

### 12.1 仕様の矛盾・未定義（自己補完していない・8件）

1. **§43は`NotificationService`をServices直下に置くが、通知の「スケジューリング」責務の置き場所は未定義。** 本書は`NotificationService`（L1）＋`AlarmScheduler`（L3）の2契約案を提示するが（§7.3）、命名・分割は裁定事項。
2. **§95.1は「Room（ExecutionStore）から再スケジュール」と書くが、`ExecutionStore`を導入するPhaseが§64〜§77のどこにも存在しない。** §74 Phase 10は履歴/プロファイル（`UserProfileStore`/`AnalyticsStore`側）であり`ExecutionStore`ではない。→ S-1。
3. **§62は通知3種を固定するが、Recovery通知の発火源（lateness detection）は§70 Phase 6。** Phase 5でRecovery通知を作るか否かが未定義。→ S-5。
4. **§69「Snooze」の量が未定義**（§27 UIの"[5 min later]"のみが手がかり）。→ S-6。
5. **§69「next action」の指すものが未定義**（通知種別なら§62の3種と矛盾する）。→ S-7。
6. **§95.4はFGS typeを「例：FOREGROUND_SERVICE_LOCATION等」と例示するのみで確定していない。** → S-3。
7. **§95.1は`USE_EXACT_ALARM`を選択肢として挙げるが、同じ文で「カレンダー/アラームアプリに限定」とも書いており、本アプリが該当するかの判断が未定義。** → S-4。
8. **取り逃したトリガー（停止中に発火時刻を過ぎた場合）の扱いが仕様に一切ない。** → S-9。

**注記**: 上記8件のうちS-2（Hilt再判定）とS-8（i18n文言解決経路）はメモ§2の裁定事項ではあるが、仕様書自体の矛盾・未定義には該当しない（Hiltはアーキテクチャ再検討、i18nはコード構造の課題）ため、本一覧（8件）には含まれない。これは自己補完ではなくメモ§9原文の数え方をそのまま踏襲したものである。

### 12.2 ユーザー確認が必要な事項（5件。**すべて裁定済み**、メモ§10を忠実に転記＋Fable 5裁定を併記）

1. **S-1（永続化方針）** — 「§95.1の字面どおりRoomを入れる」か「最小SharedPreferencesで済ませPhase 10でRoomへ吸収する」か。推奨は後者だが、仕様の明示記述（Room）からの逸脱にあたるためADR記録トリガー③「仕様推奨からの逸脱」として承認が必要。
   → **Fable 5裁定（2026-08-09）**: SharedPreferences方式`ExecutionScheduleStore`を承認。ADR記録トリガー③「仕様推奨からの逸脱」として記録し、Phase 10でRoomへ吸収する（§4.1脚注1のとおり、メモ§2原文の「トリガー②」表記とは齟齬があり、本書はトリガー③を採用）。
2. **S-3（FGS type）** — 位置権限が拒否されているユーザーはDoze対策のFGS保護を受けられない、という残存リスクを受け入れるか。`specialUse`を選べば全ユーザーを保護できるがPlay審査での用途正当化が必要（§95.5）。
   → **Fable 5裁定（2026-08-09）**: `location`単独宣言を承認。位置権限なし時はFGS非起動のDegraded運用とし、残存リスクを受け入れる。
3. **S-4（USE_EXACT_ALARM不採用）** — その帰結として**inexactが既定パスになる**こと（新規インストール直後は厳密通知が効かない）を製品として受け入れるか。受け入れる場合、オンボーディングでの許可誘導の強さをどこまでにするか。
   → **Fable 5裁定（2026-08-09）**: inexact既定パスを受け入れる。許可誘導はバナー＋ワンタップ導線の中強度とし、ブロッキングは禁止する。
4. **R-3（既存テストの期待値変更）** — `NavigationFlowTest`のT-NAV-1／T-NAV-3は「Doneタップ1回でExecutionから離脱」を前提としており、§27/§28のOne Action多段階遷移（F58）とは両立しない。TEAMS§5の契約変更経路を発動し期待値を更新する承認が必要。
   → **Fable 5裁定（2026-08-09）**: 契約変更経路の発動を承認。One Action多段階遷移が§27/28の核心機能であるため、P5-C2で両テストの期待値更新をADR記録つきで実施する。
5. **S-2（Hilt）** — 推奨は③継続だが、①（Hilt 2.59）が「版考古学ではない」ことが実測で判明したため、あえて①を試すかどうかの判断。試す場合はP5-P7のプローブ時間（P2-C1同等）を見込む必要がある。
   → **Fable 5裁定（2026-08-09）**: 手動DI継続（③）を承認。ADR-0024としてADR-0014の却下理由①を実測訂正のうえ、再検討トリガーをAGP 9移行時へ付け替える。①は試さない（P5-P7は非実施、§10.2）。

---

## 13. 未確認事項

**未検証事項の明示（Fable Protocol、メモ§9原文）**: 本メモ中、Android実行時の挙動に関する記述（SCHEDULE_EXACT_ALARMの既定拒否、位置権限なしでのlocation type FGSの例外種別、`FOREGROUND_SERVICE_TYPE_NONE`の可否、`adb shell am broadcast BOOT_COMPLETED`のprotected broadcast制限、Robolectricの**挙動**面）は**いずれも本セッションで実行検証していない＝要検証**であり、P5-P1〜P5-P6としてprobe化してある（§10.2）。実測済みなのはM5-1〜M5-16のみ（実測方法を各行に明記。§7.1）。エミュレータ操作・Gradle実行は本タスクの禁止制約により未実施。

上記に加え、本書作成時点で個別に「要検証」と明記されている項目は以下のとおり（いずれもP5-C1／P5-C2で確定する）。

- **M5-5**: KSP 2.3.11がKotlin 2.4.10を解析対象として支持するかは未確認。ただしS-2裁定（手動DI継続）によりKSPを導入しないため、本Phaseの実装判断には影響しない（Phase 6以降でHilt再検討する場合にのみ再浮上する）。
- **M5-16**: JVMテストのベースライン（239件）はディスク上の既存成果物の読み取りであり、本セッションでの実行結果ではない。P5-C1で再実測が必要（R-1）。
- **S-3**: API 34+で「manifestがtype宣言済みのServiceに`FOREGROUND_SERVICE_TYPE_NONE`で`startForeground`した場合の挙動」と「位置権限なしでlocation type起動時の例外種別」（P5-P2）。
- **P5-P1〜P5-P6**: §10.2の表のとおり、すべてP5-C1で実測し計画書へ追記する。
- **F62〜F69**: Fable 5裁定サマリーが割り当てるF番号帯のうち、メモ本文に記載のない範囲（§2.1・§4.3参照）。

いずれも「要検証（P5-C1/C2で確定）」として扱い、確定するまで本書の該当箇所（§8テストケース表・§10サイクル分解・§11リスク）の記述を最終と見なさない。

---

## 14. 申し送り

- 本計画書はandroid-planner作成のPhase 5計画メモ（§0〜§10）を忠実に文書化したものである。計画メモにない機能・仕様を自己判断で追加していない。実測M5-1〜M5-16（§7.1）・裁定事項S-1〜S-9（§4）・Hilt再判定（§7.2）・契約設計（§7.3〜7.4）・フットプリント（§6）・サイクルP5-C1〜C9（§10.1）・probe P5-P1〜P5-P7（§10.2）・テストケース（§8）・エラーマップ18行（§9）・リスクR-1〜R-9（§11）・仕様矛盾8件（§12.1）を、いずれも本書内へ反映済みである。
- Fable 5はS-1〜S-9（メモ§2）・R-3（メモ§8）・ユーザー確認が必要な事項1〜5（メモ§10）のすべてについて**推奨案どおり承認した（2026-08-09、§4）**。Geminiクロスレビュー（`model: "gemini-3.5-flash"`固定）はCRITICAL指摘6件（G1）を提示し、**Fable 5裁定によりすべて採用し本書へ反映した。これによりG1は通過した**（2026-08-09）。
- **Phase 5サイクル（P5-C1）の着手前提条件はPhase 3のクローズ**とする。Phase 3の具体的なクローズ状況（G4-JVM／G4-E達成の有無）は本書の範囲外であり、`docs/plans/phase3-routing-location.md`側で確認する。
- **テストケース件数を本書内で数え直したところ、実数は52件であり、メモ§6見出しの「全50件」とは一致しない。** 内訳: T-ALARM 10＋T-NOTIF 9＋T-STORE 8＋T-BOOT 7＋T-FGS 6＋T-P5UI 8＋T-P5E2E 4 ＝ **52件**（各サブテーブル自身の小計見出し「全10件」「全9件」等とはすべて一致）。区分内訳はE1＝1件（T-NOTIF-9）／E2＝47件／E3＝4件（合計52件、一致）。分類内訳は正常19／異常14（うち信頼境界3）／エッジ11／回帰ガード8（合計52件、一致）で、正常系の「19」はメモ§6見出しの「正常系19」と一致するが、異常系・エッジケースの内訳（メモ見出しは「異常系17／エッジケース14」＝合計50）とは一致しない。差異は「回帰ガード」8件をメモ見出しの3分類（正常/異常/エッジ）のいずれにも算入せず独立集計している影響と推測されるが、メモにその旨の明記はなく、原因は確定できない。本書はメモ§6の各テーブルの行内容自体は忠実に全件（52件）転記したうえで、本注記により差異を明示する（android-plannerへの確認事項として申し送る）。**Gemini G1 CRITICAL指摘の反映（2026-08-09）**: 上記52件に対し、`NotificationTriggerReceiver`がForeground Serviceを起動しないことを検証する回帰ガードT-NOTIF-10（E2・Robolectric）をGeminiクロスレビューのCRITICAL指摘としてFable 5裁定により追加し、テスト総数は**53件**に更新した（T-NOTIF区分9件→10件、回帰ガード8件→9件、E2区分47件→48件。他の内訳・§8.2の分類は不変）。
- **エラー＆レスキューマップは全18行（メモ§7と一致）であることを確認済み。** ハンドリング方法列に空欄はない。
- **リスクは全9件（R-1〜R-9、メモ§8と一致）であることを確認済み。**
- **仕様の矛盾・未定義は全8件（メモ§9と一致）であることを確認済み。**
- **裁定事項S-1〜S-9（メモ§2）は全9件であることを確認済みで、いずれもFable 5により推奨案どおり承認された（§4）。**
- **転記漏れの確認**: 転記元メモ§0〜§10の全項目を本書へ反映した。以下2点は転記時に発見した、メモ自体に内在する不一致・不足であり、自己判断で解消せず本書内に明示した。
  1. **S-1のADR記録トリガー番号**: メモ§2は「トリガー②」、メモ§10（ユーザー確認事項1）は「トリガー③」と表記が割れている。Fable 5裁定（本書冒頭）はトリガー③を前提としているため、本書はトリガー③を採用しつつ、メモ§2原文の「トリガー②」表記もそのまま転記した（§4.1脚注1、§4.2）。
  2. **F番号帯**: Fable 5裁定サマリーは「F49〜F69をPhase 5に割当」とするが、メモ§4.1が実際に列挙する機能はF49〜F61（13件）のみで、F62〜F69の内訳はメモに記載がない。本書はF49〜F61のみを機能一覧（§5）として転記し、F62〜F69は番号帯の予約として付記するに留めた（§2.1、§4.3、§13）。
- 本書作成にあたり、plan-doc-writerは転記対象のandroid-planner計画メモの内容をそのまま構造化したものであり、メモが引用する実測（M5-1〜M5-16）・ADR番号・ソースファイル行番号について本書側での独自の再検証は行っていない。ただしパッケージパスの展開（`main/.../` → `main/java/com/actionstarter/...`、§6.1）は`app/src/main/java/com/actionstarter/`配下の実在ディレクトリ構成（`mock/`・`ai/`・`features/`・`recovery/`等）を確認したうえで行った。**本書作成作業ではproduction codeを一切変更していない（読み取りのみ）。**
