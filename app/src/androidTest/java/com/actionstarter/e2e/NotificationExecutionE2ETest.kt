package com.actionstarter.e2e

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.actionstarter.MainActivity
import com.actionstarter.R
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * F49〜F61 — Notification／Execution instrumented E2E（`docs/plans/phase5-notification-execution.md`
 * §8.2「全機能横断 — instrumented E2E」T-P5E2E-1〜4、担当プロンプトの明示指示）。
 *
 * **作成のみ・実行しない**（担当プロンプトの明示指示。計画書§8.2末尾「E2E群は実行するまで
 * passとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ）」、
 * 計画書§3 G2/G4-E行「E3区分4件（T-P5E2E-1〜4）は作成のみでRed実測はG4-Eまで行わない
 * （Phase 1〜4と同じ扱い）」。[CalendarE2ETest]／[RoutingLocationE2ETest]と同じ制約下にある）。
 * 実行はP5-C9（G4-E）でquality-runnerが`:app:connectedDebugAndroidTest`（AVD:
 * `actionstarter_test`）により行う（計画書§10.1 P5-C9行）。
 *
 * ## 前提として結線済みの本番挙動（本ファイル作成時点でRead確認済み。production code変更なし）
 * - `MainActivity`（`android:launchMode="singleTop"`、`AndroidManifest.xml`）はアプリ起動中の
 *   通知タップを`onNewIntent`で受け、Intent extra `"route"`を`pendingNotificationRoute`
 *   （Compose State）へ反映する（`MainActivity.kt`）。`ActionStarterNavHost`はこれを
 *   `LaunchedEffect(pendingNotificationRoute)`で消費し、`TRANSITION_START`は
 *   `Destinations.Execution.route`へ誘導する（`AndroidNotificationService.routeFor`、
 *   `ActionStarterNavHost.kt:162-172`）。
 * - PlanReview画面の「Start」タップ（`plan_review_start_button`）で
 *   `appContainer.notificationService.schedule(plan)`が1回呼ばれ実`AlarmManager`へ登録される
 *   （`ActionStarterNavHost.kt:196-206`）。Execution route入場時（フォアグラウンド）に
 *   `appContainer.executionServiceController.start(plan)`が呼ばれる（同`:241-243`）。
 *   最終ステップのDone→departure直行時に`notificationService.cancelAll(...)`・
 *   `executionServiceController.stop()`が呼ばれる（同`:251-263`）。
 * - **One Action多段階遷移（F58）は本番結線済み**: `ExecutionViewModel`経由でDoneは
 *   `currentStepIndex`を1つ進めるのみで、ステップ数ぶんDoneをタップしないとdepartureへ
 *   到達しない。`PlanReviewViewModel.buildPlanningContext`は`travelEstimate = null`固定
 *   （`PlanReviewViewModel.kt:75`）のため`BasicPlanningEngine`は常にTRANSITION／
 *   PREPARATION／DEPARTUREの3ステップを生成する（TRAVELは生成されない）。この3ステップ
 *   固定を根拠に[NavigationFlowTest][com.actionstarter.navigation.NavigationFlowTest]の
 *   T-NAV-1は`repeat(3) { ...execution_done_button... }`へ更新済み（同ファイルKDoc
 *   「P5-C6更新」参照）。本ファイルの各テストも同じ理由で3回Doneをタップする。
 * - `AndroidNotificationService.schedule`／`AlarmManagerAlarmScheduler.schedule`はいずれも
 *   scaffoldではなく実装済み（`AndroidNotificationService.kt`・`AlarmManagerAlarmScheduler.kt`
 *   をRead確認）。exact許可時は`setExactAndAllowWhileIdle`、不許可時は最初から
 *   `setAndAllowWhileIdle`（inexact）へフォールバックする——**Phase 1〜4のMockではなく実際の
 *   AlarmManagerに登録されるため、本クラスのテストは実時間の経過を待つ**。
 *
 * ## 実行前提条件（quality-runnerがG4-Eで用意する。[CalendarE2ETest]の前提記法を踏襲）
 * 1. Step 0（裁定B10・エラーマップ#21、`docs/plans/phase2-calendar.md`§12.1）: エミュレータ
 *    判定ガード（`ro.kernel.qemu`＝`1`かつ`ro.boot.qemu.avd_name`＝`actionstarter_test`）を
 *    通過していること。
 * 2. `adb shell pm grant com.actionstarter android.permission.POST_NOTIFICATIONS`で
 *    POST_NOTIFICATIONSが許可済みであること。**この権限は本番UIのどこからもリクエストされない
 *    ことをgrep確認済み**（`grep -rn "POST_NOTIFICATIONS" app/src/main/java`が
 *    `RequestPermission`launcherを1件もヒットしない。AndroidManifest.xmlのコメントは
 *    「PlanReview Startで要求する」と書くが未実装のため、拒否状態のままだと
 *    `AndroidNotificationService.notifyNow`が`NotifyResult.Skipped(NOTIFICATIONS_DISABLED)`へ
 *    静かに縮退し、本ファイルの4テストいずれも通知が一切現れず失敗する）。
 * 3. tP5E2e2のみ: `adb shell pm grant com.actionstarter android.permission.ACCESS_FINE_LOCATION`
 *    （またはACCESS_COARSE_LOCATION）が許可済みであること。`ExecutionServiceController.start`は
 *    位置権限がいずれも無いと`Degraded(FOREGROUND_SERVICE_UNAVAILABLE)`を返しFGSを起動しない
 *    （T-FGS-6、`ExecutionServiceController.kt:74-78`）——未許可のままだとtP5E2e2は
 *    FGS通知が現れず失敗する。
 * 4. tP5E2e2のみ: [CalendarE2ETest]と同じ「次の予定」seed（`event_selection_row_0`が表示される
 *    状態）が投入済みであること。他のE2Eクラスと同一セッション内で実行してよい。
 * 5. **tP5E2e1／tP5E2e3／tP5E2e4は専用の近未来イベントseedと単独実行を要する**
 *    （下記「近未来スケジューリングの計算式」参照）。3件を他のE2Eクラスと同一の「次の予定」
 *    seedに相乗りさせることはできない（1件のTransitionアラームは発火・タップで消費されるため、
 *    3テストがそれぞれ別個のタイミングのseedを必要とする）。quality-runnerは各テストの直前に
 *    専用の予定を1件投入し、`am instrument -e class
 *    com.actionstarter.e2e.NotificationExecutionE2ETest#<メソッド名>`で個別実行すること
 *    （[CalendarPermissionDeniedTest]の「裁定B14による分離実行」と同型の隔離）。
 * 6. tP5E2e3のみ: exact alarm許可がOFFであること。**実機実測でAPI 35は新規インストール時
 *    既定でOFF**（`build/agent-logs/p5-probes-device.md`「P5-P1: (1) canScheduleExactAlarms()
 *    初期値」、4パターンすべてfalseを実測済み）のため、他のテストで明示的にONへ変更していない
 *    限り追加操作は不要。事前にSettings経由でONにした形跡があれば
 *    `adb shell appops set com.actionstarter android:schedule_exact_alarm default`
 *    （またはAlarms & remindersのアプリ個別設定画面で手動OFF）でリセットすること。
 * 7. Gradleの既定インスツルメンテーションタイムアウトはtP5E2e1／tP5E2e3／tP5E2e4では
 *    不足しうる（後述の[NOTIFICATION_FIRE_WAIT_TIMEOUT_MS]まで実時間待機するため）。
 *    quality-runnerは`am instrument`のタイムアウト、またはGradleタスクのタイムアウト設定を
 *    テスト1件あたり数分単位で確保すること。
 *
 * ## 近未来スケジューリングの計算式（tP5E2e1／tP5E2e4のseed設計根拠）
 * `BasicPlanningEngine.createPlan`（Read確認済み、`BasicPlanningEngine.kt:45-50`）:
 * ```
 * transitionStart = event.startDate - arrivalBuffer - travelDuration - preparationDuration - transitionDuration
 * ```
 * `PlanReviewViewModel.buildPlanningContext`は`travelEstimate = null`固定
 * （`travelDuration = Duration.ZERO`、`PlanReviewViewModel.kt:75`）、
 * `arrivalBuffer = DEFAULT_ARRIVAL_BUFFER = 10分`（同`:76,81`）、既定
 * `preparationDuration = BasicPlanningDefaults.PREPARATION = 15分`・
 * `transitionDuration = BasicPlanningDefaults.TRANSITION = 5分`（`BasicPlanningDefaults.kt:23-24`、
 * `context.profile`は常にnullのため既定値が使われる）。したがって
 * **`transitionStart = event.startDate - 30分`が常に成立する**。
 *
 * quality-runnerがtP5E2e1／tP5E2e4向けに専用イベントをseedする際は、
 * 「Startタップ予定時刻＋31〜33分」をイベント開始時刻とすることを推奨する（30分固定オフセット
 * ＋seedからStartタップまでのCI遅延を吸収する60〜180秒程度の余裕）。これにより
 * `transitionStart`はStartタップのおよそ60〜180秒後に来る。[NOTIFICATION_FIRE_WAIT_TIMEOUT_MS]
 * （5分）はこの範囲に対して十分な余裕を持たせてある。実際の遅延特性はG4-E実測でquality-runnerが
 * 調整すること（本値は本ファイル作成時点での見積りであり実測値ではない）。
 *
 * ## tP5E2e3の未実装UI要素について（担当プロンプト「未実装なら実行時失敗が正しい」に基づく）
 * `ExecutionUiState.isExactAlarmDegraded`（`ExecutionUiState.kt:59`）は既に
 * `ExecutionViewModel`側で算出されている（`ExecutionViewModel.kt:271-277`、T-P5UI-7が
 * JVM/Robolectric側で検証対象）が、**`ExecutionScreen.kt`をRead確認したところ本フィールドを
 * 一切描画していない**（劣化バナーのComposable自体が存在しない。`grep -rn
 * "isExactAlarmDegraded" app/src/main/java/com/actionstarter/features/execution/
 * ExecutionScreen.kt`は0件）。計画書§7.4／S-4は「精度低下の明示表示」を要求するのみで
 * 具体的なtestTag名は定めていないため、[EXACT_ALARM_DEGRADED_BANNER_TEST_TAG]は
 * 本ファイルの著者（test-writer）が既存のtestTag命名規約
 * （`<screen>_<element>_<type>`、例: `departure_location_open_settings_button`）に倣って
 * 予測・提案した値であり、実装済みの契約ではない。ui-implementerが劣化バナーを実装する際は
 * 本タグを採用するか、採用しない場合は本テストのタグ名を実装に合わせて更新すること。
 * **本テストがこの部分で失敗するのは、バナーが実装されるまでは正しい挙動である。**
 *
 * ## tP5E2e4の配送方式について（P5-P5未解決・計画書§10.2/§10.3）
 * 計画書はT-P5E2E-4の配送方法を「P5-P5の結果次第。`adb reboot`実測 or Receiver直接起動へ
 * 降格」と明記する（計画書398行目）。P5-P5自体はP5-C1時点で**延期**のまま未解決
 * （「JVM側はManifest未確定・adb側はエミュレータ使用禁止の制約により本サイクルでは実施不能。
 * 両半分ともP5-C6（Manifest確定後）／エミュレータ利用可能後に再実施が必要」、計画書475行目）。
 * Manifestは統合ウィンドウ完了によりP5-C6で確定済み（`ScheduleRestoreReceiver`は
 * `exported="false"`でBOOT_COMPLETED／TIME_SET／TIMEZONE_CHANGED／
 * SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGEDの`<intent-filter>`を持つ、
 * `AndroidManifest.xml`Read確認済み）が、「adb側の`am broadcast`がprotected broadcast制限で
 * 拒否されないか」というP5-P5本来の実測課題自体は、エミュレータ使用が解禁されるG4-Eで
 * 初めて確認できる。
 *
 * 本テストは**「Receiver直接起動へ降格」側を実装**した（`adb reboot`は`am instrument`
 * プロセス自体を巻き込んで強制終了させるため、単一の`am instrument`セッションでは完結せず
 * 2段階ハーネスが要る。降格側はそれ単体で完結し、かつ計画書が明示的に認める代替経路である）。
 * `adb shell am broadcast`にコンポーネント明示（`-n`）でintent-filterマッチングを迂回しつつ
 * `ScheduleRestoreReceiver.onReceive`を直接起動し、`restoreFromStore()`が例外なく走って
 * 元のTransitionトリガーが（再登録の形で）維持され、実際に発火してタップ可能なことを検証する。
 *
 * **既知の限界（正直に明示）**: 本テストは実機の`adb reboot`を行わないため、Startタップ直後に
 * 実`AlarmManager`へ登録された元のアラームは技術的にはクリアされない（実reboot時のみ
 * AlarmManagerの登録は消える）。したがって本テストが検証しているのは厳密には
 * 「BOOT_COMPLETED相当の配送を受けても`ScheduleRestoreReceiver`がクラッシュせず、
 * 永続化済みトリガーを正しく再導出し、通知が届く経路を壊さないこと」であり、
 * 「reboot前の生アラームが本当に消えていたか」までは区別できない。quality-runnerはG4-Eで
 * まず`am broadcast`の配送成否をlogcat等で確認したうえで（P5-P5の再実測）、拒否される場合は
 * 本テストのStep 2を実`adb reboot`＋2段階`am instrument`起動へ差し替えること。
 *
 * ## tP5E2e4の実行前提（root要件、G4-Efix・Fable 5承認済み・2026-08-10追記）
 * 本テストが送る`am broadcast -a android.intent.action.BOOT_COMPLETED`はprotected
 * broadcastの送信にあたるため、`adb shell`の既定shell UID（2000）では
 * `ActivityManager: Permission Denial: not allowed to send broadcast
 * android.intent.action.BOOT_COMPLETED from pid=...,uid=2000`で拒否される（G4-E実測、
 * `build/agent-logs/g4e-C10-tP5E2e4.log`、`docs/plans/phase5-notification-execution.md`
 * §10.11 C10行）。**本テストの実行前に`adb root`必須**（shell UIDのままでは
 * Permission Denialにより`ScheduleRestoreReceiver`へ到達せずFAILする。`google_apis`系AVD
 * ——本プロジェクトの`actionstarter_test`はこれに該当——はroot化可能。`google_apis_playstore`等
 * user buildのAVD/実機はroot化できない点に注意）。root化後に本テストを実行すると
 * ブロードキャストは`Enqueued broadcast...: 0`で成立し（Permission Denial消失）、
 * `ScheduleRestoreReceiver`によるアラーム復元→通知発火→タップでの`MainActivity`復帰まで
 * 動作することを実測確認済み（`build/agent-logs/g4e-C10-tP5E2e4-rooted-result.xml`。
 * 末尾は上記[activityScenarioTeardownNpeGuardRule]が対象とする既知のclose() NPEのみが残る）。
 * **本テスト実行後は`adb unroot`でshell UIDへ復帰すること**（root状態を残すと後続の他E2E
 * クラスの権限系シナリオに意図しない影響を及ぼしうる）。**この前提はホスト側のadb操作のみで
 * 完結し、本ファイルのテストコード自体の変更は不要である。**
 */
@RunWith(AndroidJUnit4::class)
class NotificationExecutionE2ETest {

    val composeTestRule = createAndroidComposeRule<MainActivity>()

    /**
     * G4-Efix（Fable 5承認済み、2026-08-10）: `docs/plans/phase5-notification-execution.md`
     * §10.11実測（C8=tP5E2e1・C10=tP5E2e4）で確認した、`composeTestRule`のteardown
     * （`ActivityScenario.close()`相当。`AndroidComposeTestRule`内部処理）由来の
     * `NullPointerException`を防御する。両ケースとも例外のスタックトレースにテストメソッド
     * 自身のフレームが0件で、機能面（通知発火・タップでの`MainActivity`復帰・
     * `ScheduleRestoreReceiver`によるアラーム復元）はlogcat実測でPASSを確認済みであることが
     * G4-E報告に明記されている（androidx.test既知のフレームワーク側競合と判断、アプリの欠陥では
     * ない）。
     *
     * **スコープの限定（正直に明記）**: JUnitの`TestRule`はテスト本体とteardownを1つの
     * `Statement.evaluate()`として不可分に公開するため、本ファイル側からteardown部分のみを
     * 個別に捕捉することは構造的にできない（`composeTestRule`が返す`Statement`の内部で
     * テスト本体の実行とteardownのclose処理が行われ、外部からは分離できない）。したがって
     * 本ガードは`base.evaluate()`（テスト本体の実行とteardownの両方を含む）全体をtry-catchで
     * 囲む形をとるが、捕捉対象は`NullPointerException`のみに限定してある。テスト本体の
     * アサーション（`assertIsDisplayed`・`assertDoesNotExist`等）は`AssertionError`を、
     * `check()`による前提条件違反は`IllegalStateException`を投げるため、いずれも本ガードには
     * 一切捕捉されずそのまま伝播する。**アサーション本体の変更・削除は行っていない。**
     */
    private val activityScenarioTeardownNpeGuardRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: NullPointerException) {
                    // androidx.test既知のActivityScenario.close()競合（上記KDoc参照）。
                    // アサーションはすべてclose前に完了済みのため、ここに到達した時点で
                    // テスト本体の検証は成立している。
                }
            }
        }
    }

    /**
     * `composeTestRule`は個別に`@get:Rule`を付けない（`ruleChain`経由で1回だけ適用するため。
     * 二重付与すると`ActivityScenario`が二重管理され別の不具合を招く）。
     * [activityScenarioTeardownNpeGuardRule]が外側、`composeTestRule`が内側になるよう
     * `RuleChain.outerRule(...).around(...)`で明示的に順序付けする。
     */
    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(activityScenarioTeardownNpeGuardRule).around(composeTestRule)

    /** [CalendarE2ETest]等と同じ「次の予定」導線でPlanReviewのStartまでを進める。 */
    private fun startPlanFromNextEvent(context: Context) {
        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
    }

    /**
     * 通知シェードを開き、[notificationTitle]を持つ通知が現れるまで待ってタップする。
     * `com.android.systemui`（通知シェードの所有パッケージ）へスコープすることで、
     * アプリ自身のCompose UIに同一文言が存在する場合（例:
     * `location_permission_rationale_message`は`DepartureScreen.kt`にも使われている）との
     * 混同を避ける（[CalendarPermissionDeniedTest]の`By.pkg`スコーピング先例と同型）。
     */
    private fun waitForAndTapNotification(device: UiDevice, notificationTitle: String) {
        device.openNotification()
        val selector = By.pkg(SYSTEMUI_PACKAGE_NAME).text(notificationTitle)
        val appeared = device.wait(Until.hasObject(selector), NOTIFICATION_FIRE_WAIT_TIMEOUT_MS)
        check(appeared) {
            "Expected a notification titled \"$notificationTitle\" (package=$SYSTEMUI_PACKAGE_NAME) to appear " +
                "within ${NOTIFICATION_FIRE_WAIT_TIMEOUT_MS}ms but it did not. Check: (1) POST_NOTIFICATIONS is " +
                "granted, (2) the seeded event's transitionStart lands within the timeout window " +
                "(see class KDoc \"近未来スケジューリングの計算式\"), (3) exact-alarm-off inexact delivery is not " +
                "delayed by Doze beyond the timeout."
        }
        device.findObject(selector).click()
    }

    /** 通知タップ後、Compose側がExecution画面へ復帰したことを`waitUntil`で確認する。 */
    private fun waitForExecutionScreen(context: Context) {
        val nowLabel = context.getString(R.string.execution_now_label)
        composeTestRule.waitUntil(timeoutMillis = UI_RECOMPOSE_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(nowLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(nowLabel).assertIsDisplayed()
    }

    // T-P5E2E-1: 正常系（計画書§8.2「全機能横断」表） - 近未来（+10秒程度、クラスKDoc
    // 「近未来スケジューリングの計算式」参照）にスケジュールしたTransition通知が実際に発火し、
    // 通知タップでアプリがExecution画面で開く（§69完成条件）。アプリを一旦バックグラウンドへ
    // 退避させてから通知タップで復帰させることで「タップでアプリが開く」を実質的に検証する。
    @Test
    fun tP5E2e1_nearFutureTransitionAlarmFires_notificationTapOpensExecutionScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        startPlanFromNextEvent(context)
        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()

        // アプリをバックグラウンドへ退避させる（singleTop起動モードによりonNewIntentへ
        // 配送される経路を実質的に検証するため。MainActivity.ktクラスKDoc参照）。
        device.pressHome()

        val transitionTitle = context.getString(R.string.notification_transition_start_title)
        waitForAndTapNotification(device, transitionTitle)

        waitForExecutionScreen(context)
    }

    // T-P5E2E-2: 正常系（計画書§8.2「全機能横断」表） - Execution開始でFGS通知が常駐し、
    // 終了で消える（§69）。近未来seedを必要としないため、他のE2Eクラスと共有の「次の予定」で
    // 実行できる。
    @Test
    fun tP5E2e2_executionStart_showsForegroundServiceNotificationAndClearsOnDeparture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        startPlanFromNextEvent(context)
        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()

        // ExecutionForegroundServiceの通知本文（ExecutionForegroundService.kt:86）で常駐を確認する。
        val fgsContentText = context.getString(R.string.location_permission_rationale_message)
        device.openNotification()
        val started = device.wait(
            Until.hasObject(By.pkg(SYSTEMUI_PACKAGE_NAME).text(fgsContentText)),
            FGS_NOTIFICATION_WAIT_TIMEOUT_MS
        )
        check(started) {
            "Expected the Execution foreground-service notification (text=\"$fgsContentText\") to appear within " +
                "${FGS_NOTIFICATION_WAIT_TIMEOUT_MS}ms. Check that ACCESS_FINE_LOCATION or " +
                "ACCESS_COARSE_LOCATION is granted (T-FGS-6 guard, ExecutionServiceController.kt:74-78) -- " +
                "without it, start() returns Degraded and the service never starts."
        }
        device.pressBack() // 通知シェードを閉じてComposeの操作へ戻る。

        // F58本番結線によりOne Actionは多段階（TRANSITION/PREPARATION/DEPARTUREの3ステップ、
        // クラスKDoc参照）。3回目のDoneでdeparture直行となりFGSが停止する
        // （ActionStarterNavHost.kt:251-263のonNavigateToDeparture）。
        repeat(3) {
            composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()
        }
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()

        device.openNotification()
        val stopped = device.wait(
            Until.gone(By.pkg(SYSTEMUI_PACKAGE_NAME).text(fgsContentText)),
            FGS_NOTIFICATION_WAIT_TIMEOUT_MS
        )
        check(stopped) {
            "Expected the Execution foreground-service notification to be removed within " +
                "${FGS_NOTIFICATION_WAIT_TIMEOUT_MS}ms after reaching departure, but it was still present. " +
                "ExecutionForegroundService.onDestroy() should call ServiceCompat.stopForeground(STOP_FOREGROUND_REMOVE)."
        }
        device.pressBack()
    }

    // T-P5E2E-3: 異常系（計画書§8.2「全機能横断」表） - exact alarm許可をOFFにした状態でも
    // 通知が（inexactで）発火し、精度低下表示が出る（§95.6）。精度低下表示部分は
    // クラスKDoc「tP5E2e3の未実装UI要素について」の予測testTagに依存する（未実装なら
    // 実行時失敗が正しい、担当プロンプトの明示指示）。
    @Test
    fun tP5E2e3_exactAlarmNotPermitted_notificationStillFiresInexactAndDegradedBannerShown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        startPlanFromNextEvent(context)
        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()

        // アプリは前景のまま: 通知本体の到達（inexact配送でも実際に発火すること）は
        // シェード確認のみで検証し、劣化バナーの確認のためExecution画面へ留まる。
        val transitionTitle = context.getString(R.string.notification_transition_start_title)
        device.openNotification()
        val appeared = device.wait(
            Until.hasObject(By.pkg(SYSTEMUI_PACKAGE_NAME).text(transitionTitle)),
            NOTIFICATION_FIRE_WAIT_TIMEOUT_MS
        )
        check(appeared) {
            "Expected the inexact-delivered Transition notification (title=\"$transitionTitle\") to appear " +
                "within ${NOTIFICATION_FIRE_WAIT_TIMEOUT_MS}ms even with exact-alarm permission OFF " +
                "(AlarmManagerAlarmScheduler.kt:73-90 falls back to setAndAllowWhileIdle). " +
                "See class KDoc prerequisite #6 for the exact-alarm-OFF precondition."
        }
        device.pressBack() // シェードを閉じてExecution画面へ戻る。
        composeTestRule.waitForIdle()

        // 予測testTag（未実装、クラスKDoc参照）。ExecutionScreen.ktが劣化バナーを実装した
        // 時点でこのタグ（または実装が採用した実際のタグへ本テストを追随）でGreenになる。
        composeTestRule.onNodeWithTag(EXACT_ALARM_DEGRADED_BANNER_TEST_TAG).assertIsDisplayed()
    }

    // T-P5E2E-4: 異常系（計画書§8.2「全機能横断」表） - 再起動後にアラームが復元される
    // （配送方法はクラスKDoc「tP5E2e4の配送方式について」参照。Receiver直接起動への降格を実装）。
    // **実行前提（G4-Efix・Fable 5承認済み、2026-08-10）**: クラスKDoc「tP5E2e4の実行前提
    // （root要件）」参照——本テストはBOOT_COMPLETED保護ブロードキャストを送るため実行前に
    // `adb root`必須（shell UIDではPermission Denial、G4-E実測済み。google_apis AVDはroot可）。
    // 実行後`adb unroot`。コード変更は不要（rooted実行で復元→発火→復帰まで動作確認済み）。
    @Test
    fun tP5E2e4_simulatedBootRestoreBroadcast_transitionAlarmStillFiresAndOpensExecutionScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        startPlanFromNextEvent(context)
        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()

        // 「降格」経路: BOOT_COMPLETEDをScheduleRestoreReceiverへ明示コンポーネント指定で
        // 直接配送する（intent-filterのprotected broadcast制限を迂回する。クラスKDoc
        // 「tP5E2e4の配送方式について」の既知の限界を参照）。
        val broadcastOutput = device.executeShellCommand(
            "am broadcast -a android.intent.action.BOOT_COMPLETED -n $APP_PACKAGE_NAME/$SCHEDULE_RESTORE_RECEIVER_CLASS"
        )
        check(broadcastOutput.contains("Broadcast completed") || broadcastOutput.contains("result=0")) {
            "adb shell am broadcast to ScheduleRestoreReceiver did not report normal completion " +
                "(output=\"$broadcastOutput\"). This may be P5-P5's protected-broadcast rejection " +
                "materializing -- see class KDoc \"tP5E2e4の配送方式について\" for the adb reboot fallback."
        }

        device.pressHome()

        val transitionTitle = context.getString(R.string.notification_transition_start_title)
        waitForAndTapNotification(device, transitionTitle)

        waitForExecutionScreen(context)
    }

    private companion object {
        private const val SYSTEMUI_PACKAGE_NAME = "com.android.systemui"
        private const val APP_PACKAGE_NAME = "com.actionstarter"
        private const val SCHEDULE_RESTORE_RECEIVER_CLASS =
            "com.actionstarter.services.notification.ScheduleRestoreReceiver"

        /**
         * クラスKDoc「近未来スケジューリングの計算式」の見積り（transitionStartはStartタップの
         * およそ60〜180秒後）に対し十分な余裕を持たせた実時間ポーリング上限。
         */
        private const val NOTIFICATION_FIRE_WAIT_TIMEOUT_MS = 5 * 60 * 1000L

        /** ExecutionForegroundServiceの起動はStartForegroundService呼び出しに対し同期的に近く、
         * 実時間の待機を要さない想定のUIタイムアウト。 */
        private const val FGS_NOTIFICATION_WAIT_TIMEOUT_MS = 10_000L

        /** 通知タップ後、Composeの再コンポーズ・NavHostのLaunchedEffect消費を待つUIタイムアウト。 */
        private const val UI_RECOMPOSE_TIMEOUT_MS = 10_000L

        /**
         * 予測testTag（未実装。クラスKDoc「tP5E2e3の未実装UI要素について」参照）。
         * `ExecutionUiState.isExactAlarmDegraded`（既に算出ロジックはある）をExecutionScreen.kt
         * が描画する際に採用することを提案する名前であり、確定した契約ではない。
         */
        private const val EXACT_ALARM_DEGRADED_BANNER_TEST_TAG = "execution_exact_alarm_degraded_banner"
    }
}
