package com.actionstarter.navigation

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.actionstarter.ActionStarterApplication
import com.actionstarter.R
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.features.departure.DepartureScreen
import com.actionstarter.features.departure.DepartureViewModel
import com.actionstarter.features.eventselection.EventSelectionScreen
import com.actionstarter.features.eventselection.EventSelectionUiState
import com.actionstarter.features.eventselection.EventSelectionViewModel
import com.actionstarter.features.execution.ExecutionScreen
import com.actionstarter.features.execution.ExecutionViewModel
import com.actionstarter.features.planreview.PlanReviewScreen
import com.actionstarter.features.planreview.PlanReviewViewModel
import com.actionstarter.features.recovery.RecoveryScreen
import com.actionstarter.features.recovery.RecoveryViewModel
import com.actionstarter.features.settings.SettingsScreen
import com.actionstarter.features.settings.SettingsViewModel
import com.actionstarter.recovery.LatenessDetector
import com.actionstarter.recovery.LatenessVerdict
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * 通知タップのroute extraとして受理する既知route（F60・エラー&レスキューマップ#18）。
 * [com.actionstarter.services.notification.AndroidNotificationService]が実際に発行するのは
 * この2値のみ（同クラスの`routeFor`参照）。それ以外（未知・欠落）はexecutionへフォールバック
 * する（[ActionStarterNavHost]参照）。
 */
private val NOTIFICATION_TAP_ROUTES: Set<String> = setOf(Destinations.Execution.route, Destinations.Departure.route)

/**
 * 仕様§35の5画面＋Recovery割込を結ぶNavHost（計画書§10.2グラフ構成）。
 *
 * ```
 * eventSelection → [Prepare] → planReview → [Start] → execution → [最終Done] → departure
 * execution → [割込] → recovery → [Use this plan] → execution
 * ```
 *
 * §10.6の疎結合規約により、各画面Composable（EventSelectionScreen等）は画面遷移を
 * ラムダ引数として受け取りNavControllerを直接参照しない。本Composable（NavHost本体）が
 * NavControllerを保持し、各画面へ実際のnavigate呼び出しを結線する唯一の場所となる。
 *
 * `docs/TEAMS.md`§5「共有ファイル所有権と統合オーナー」により、Navigation配線（NavHost本体）
 * の既定所有者はdomain-implementerであり、ui-implementerはC4の間本ファイルに触れない。
 * 本実装はC5（統合サイクル、integration owner）による。
 *
 * DI（§7.3、ADR-0003）: `LocalContext.current.applicationContext`を
 * [ActionStarterApplication]へキャストして[com.actionstarter.di.AppContainer]を取得する
 * （手動DI、Hilt未導入）。選択イベント・確定済みPlanはactivity-scopedの
 * [SharedPlanViewModel]（`viewModel()`のデフォルトスコープ＝最も近い
 * `ViewModelStoreOwner`＝ホストActivity）で保持する。
 *
 * **権限リクエスト・ON_RESUME再チェック・手動入力の結線（統合サイクル、計画書§7.3／§7.4）**:
 * eventSelection routeが`rememberLauncherForActivityResult(RequestPermission)`を保持する。
 * §7.4改訂（Fable 5裁定2026-08-09、§15(d)解消）により、launcher起動直前に
 * [EventSelectionViewModel.onPermissionRequested]を呼んで「要求済み」を記録し、launcher結果が
 * 許可（`true`）なら[EventSelectionViewModel.onRetry]で再チェック、拒否（`false`）なら
 * [EventSelectionViewModel.onPermissionDenied]で即座に`PermissionDenied`（手動入力フォールバック）
 * へ遷移させる（起動前からの拒否は「要求済み」フラグがfalseのままrefresh()され
 * `PermissionRequired`に、launcher経由の拒否は`PermissionDenied`に、それぞれ一意に対応する。
 * `EventSelectionViewModel`該当KDoc参照）。`onOpenAppSettings`
 * （`Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)`起動）・`onManualEventConfirmed`
 * （[SharedPlanViewModel.selectEvent]へ受け渡しplanReviewへnavigate）も本Composableが保持する。
 * ON_RESUME
 * 再チェック（T-PERM-5）は`DisposableEffect`＋`LifecycleEventObserver`で実現し
 * （`lifecycle-runtime-compose`は`activity-compose`／`navigation-compose`経由で既に
 * コンパイルクラスパス上にあるため追加依存不要。計画書§13 P-3の代替案採用）、
 * 画面Composable自身は`ActivityResultLauncher`・`NavController`・ライフサイクルのいずれも
 * 直接参照しない（§7.3疎結合規約）。
 *
 * **departure routeの位置権限・ON_RESUME結線（Phase 3 P3-C6、integration owner、計画書
 * §6.4#5）**: eventSelection routeと同型の抽出パターンで[DepartureRoute]（private
 * Composable）へ分離した。位置権限launcher（`RequestMultiplePermissions`、FINE＋COARSE
 * 同時要求、S-1裁定）・ON_RESUME再チェック（T-PERM3-4）・Settings導線・手動Travel Time
 * 入力／transport mode選択の[DepartureViewModel]への委譲は[DepartureRoute]のKDoc参照。
 *
 * **T-NAV-4ガード**: execution routeは[SharedPlanViewModel.confirmedPlan]がnullの場合
 * （Planが未確定のままexecutionへ到達しようとした場合）、`popUpTo`でeventSelectionへ戻し
 * Snackbarで通知する（エラー＆レスキューマップ#9）。
 *
 * **ExecutionViewModelの本番結線（P5-C6統合ウィンドウ、ADR-0028、計画書§6.3・§10.6申し送り）**:
 * execution routeは[com.actionstarter.features.execution.ExecutionViewModel]を[vmFactory]
 * （[com.actionstarter.di.AppContainer.createViewModelFactory]がADR-0028の3新引数——
 * [sharedPlanViewModel]／`notificationService`／`permissionGate`、いずれも実引数——で
 * 構築する）経由で取得する。C5時点の設計（[SharedPlanViewModel.confirmedPlan]から直接
 * `ExecutionUiState`を構築し`onDone`を`null`固定で渡す、旧M5-14）はここで置き換わった。
 * F58（Execution One Actionの多段階前進）が本番結線され、`onDone`は非null
 * （`ExecutionViewModel.handleConfirmedPlanDone`）になり、確定Planの実ステップ列を
 * 1つずつ「Done」で進める（Doneタップ1回でExecutionから離脱する、という旧前提と正面から
 * 衝突するため、TEAMS§5の契約変更経路に基づき`NavigationFlowTest`のT-NAV-1／T-NAV-3の
 * 期待値をADR-0028どおり本サイクルで同時更新した）。
 *
 * **通知・Foreground Serviceの結線（F51/F56/F57/F60、計画書§10.6申し送り）**:
 * PlanReview「Start」タップで[appContainer]`.notificationService.schedule(plan)`を1回だけ
 * 呼ぶ（Execution表示開始のたびではなくPlan確定時点。§7.3のexact/inexact判定は
 * `AndroidNotificationService`側の責務）。execution route入場時（フォアグラウンド）に
 * `appContainer.executionServiceController.start(plan)`を呼ぶ（§95.1(b)の前提保護は
 * `ExecutionServiceController`側が担う）。Execution完了（最終ステップのDone→departureへの
 * 直行、`onNavigateToDeparture`）で`notificationService.cancelAll(planId)`・
 * `executionServiceController.stop()`を呼ぶ（T-STORE-6文脈）。**Recovery割込
 * （`onNavigateToRecovery`、「Simulate delay (debug)」ボタン起点）はexecution composableを
 * 一時的に離れるだけの迂回でありExecution中断ではないため、ここではキャンセルしない**
 * （画面破棄一般に紐づけると、Recovery表示のたびに正当なアラームを消してしまう）。
 * 通知タップ→アプリ起動→該当画面復帰（F60）は[pendingNotificationRoute]（[MainActivity]の
 * `onNewIntent`が観測するstate）を本Composableが受け取り、[LaunchedEffect]で解決する
 * （信頼境界: 未知routeはexecutionへフォールバックし、既存のT-NAV-4ガードへ合流させる。
 * エラー&レスキューマップ#18）。
 *
 * **Lateness detection実配線（P6-C5統合ウィンドウ、計画書§7.6・§9エラーマップ#9/#10・
 * §11.2.2）**: execution route入場時・plan更新時（`LaunchedEffect(plan)`、
 * `executionServiceController.start(plan)`の直後）に
 * [com.actionstarter.recovery.LatenessDetector.evaluate]を呼び、`WillMissEvent`ならRecoveryへ
 * 自動遷移する（§70完成条件）。Recovery「Use this plan」からの復帰で同一`LaunchedEffect`が
 * 再発火しても無限ループしないよう、`rememberSaveable`によるone-shotガード
 * （`hasAutoNavigatedToRecovery`、`plan.event.id`スコープ）を設けている。「Simulate delay
 * (debug)」ボタン起点の`onNavigateToRecovery`はこのガードと無関係の別経路のまま
 * （下記composable本体参照）。
 *
 * @param pendingNotificationRoute [MainActivity]の`onNewIntent`が観測した通知タップの
 *   route extra（キー`"route"`）。既定`null`（通常起動）。
 * @param onPendingNotificationRouteConsumed [pendingNotificationRoute]を消費した後に
 *   呼ばれるコールバック（[MainActivity]側の状態をnullへ戻す）。既定は何もしない
 *   （`NavigationFlowTest`等、通知タップを検証しない既存呼び出し元は無変更のまま
 *   `ActionStarterNavHost()`をゼロ引数で呼び続けられる）。
 */
@Composable
fun ActionStarterNavHost(
    pendingNotificationRoute: String? = null,
    onPendingNotificationRouteConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContainer = remember(context) {
        (context.applicationContext as ActionStarterApplication).appContainer
    }
    val sharedPlanViewModel: SharedPlanViewModel = viewModel()
    val vmFactory = remember(sharedPlanViewModel) {
        appContainer.createViewModelFactory(sharedPlanViewModel)
    }
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // F60実配線（エラー&レスキューマップ#17/#18）: 通知タップで観測されたrouteへ誘導する。
    // 信頼境界: 未知routeはexecutionへフォールバックする（既存のT-NAV-4ガードが、Planが
    // 未確定ならさらにeventSelectionへ縮退させる）。1回消費したら
    // onPendingNotificationRouteConsumedでMainActivity側の状態をnullへ戻し、再コンポーズの
    // たびに同じnavigateが再発火しないようにする。
    LaunchedEffect(pendingNotificationRoute) {
        val route = pendingNotificationRoute ?: return@LaunchedEffect
        val targetRoute = if (route in NOTIFICATION_TAP_ROUTES) route else Destinations.Execution.route
        navController.navigate(targetRoute) { launchSingleTop = true }
        onPendingNotificationRouteConsumed()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destinations.EventSelection.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destinations.EventSelection.route) {
                EventSelectionRoute(
                    vmFactory = vmFactory,
                    navController = navController,
                    sharedPlanViewModel = sharedPlanViewModel
                )
            }

            composable(Destinations.PlanReview.route) {
                val viewModel: PlanReviewViewModel = viewModel(factory = vmFactory)
                val uiState by viewModel.uiState.collectAsState()

                // F79実配線（P11-C3、計画書§7.1、Gemini G1 CRITICAL #1反映）:
                // POST_NOTIFICATIONS実行時権限リクエスト。EventSelectionRoute/DepartureRouteと
                // 同型のActivityResultContracts.RequestPermission()単一権限launcherを、
                // PlanReview route自身が保持する。旧設計案（launch()直後に同期的にnavigate()）は
                // 権限ダイアログの表示・消滅と画面遷移アニメーションが競合しうるライフサイクル
                // リスクがあるため採用せず、遷移をlauncherのコールバック内へ移した
                // （§7.1「非同期タイミングを再設計」）。結果（許可・拒否いずれも）は分岐しない
                // ——ExecutionViewModel側がisNotificationPermissionDenied()で都度isGranted()を
                // 再照会するため、ここでは遷移のみを行う（DepartureRouteのrequestLocationPermissionLauncher
                // と同じ設計判断）。
                val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {
                    navController.navigate(Destinations.Execution.route)
                }

                PlanReviewScreen(
                    uiState = uiState,
                    onNavigateToExecution = {
                        viewModel.confirmAndStart()
                        // F51実配線（計画書§10.6申し送り）: Plan確定（Startタップ）時点で
                        // 1回だけアラームを予約する（Execution表示開始のたびではない）。
                        // uiState.plan は confirmAndStart() が SharedPlanViewModel.confirmPlan
                        // へ渡すのと同一のPlanインスタンス（PlanReviewViewModel.confirmAndStart
                        // 参照）。PlanReviewScreenのStartボタンはplanがnullの間は描画されない
                        // ため通常nullにはならないが、信頼境界として?.letで防御する。
                        // アラームは権限の有無と無関係に登録する（schedule()自体はPOST_NOTIFICATIONS
                        // が対象とする「通知の表示」とは独立して成功する、Phase 5既存の設計を踏襲）。
                        uiState.plan?.let { plan -> appContainer.notificationService.schedule(plan) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // API 33+のみ実行時リクエストが必要。遷移はlauncherのコールバック内
                            // （上記）で行う。
                            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // API 33未満はPOST_NOTIFICATIONSが概念上存在しないため、launcherを
                            // 介さず直接遷移する（§7.1「ライフサイクルの安全性の観点で不要な
                            // 非同期コールバックへの依存を増やさない」）。
                            navController.navigate(Destinations.Execution.route)
                        }
                    }
                )
            }

            composable(Destinations.Execution.route) {
                val confirmedPlan by sharedPlanViewModel.confirmedPlan.collectAsState()
                val plan = confirmedPlan
                val unconfirmedPlanMessage = stringResource(R.string.nav_plan_not_confirmed_snackbar_message)

                if (plan == null) {
                    // T-NAV-4ガード: Planが未確定のままexecutionへ到達しようとした場合、
                    // popUpToでeventSelectionへ戻しSnackbarで通知する。
                    LaunchedEffect(Unit) {
                        navController.navigate(Destinations.EventSelection.route) {
                            popUpTo(Destinations.EventSelection.route) { inclusive = true }
                        }
                        // 外側のcoroutineScopeを使う: navigate()によりこのcomposableは
                        // 破棄されLaunchedEffect自体はキャンセルされ得るため、Snackbar表示は
                        // composable本体のライフサイクルに縛られないscopeで行う。
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(unconfirmedPlanMessage)
                        }
                    }
                } else {
                    // F58本番結線（P5-C6統合ウィンドウ、ADR-0028）: ExecutionViewModel経由で
                    // ExecutionUiStateを取得する（旧M5-14の直接構築は廃止）。onDoneは非nullと
                    // なり、確定Planのステップ列に沿った多段階前進（Done→次ステップ）が
                    // 本番結線される。
                    val viewModel: ExecutionViewModel = viewModel(factory = vmFactory)
                    val executionUiState by viewModel.uiState.collectAsState()

                    // F80実配線（P11-C3、計画書§7.1「PermissionGateの2値契約とONResume再評価」・
                    // §95.6「後から許可された場合は自動的に通知を再開する」）: Settingsから
                    // 戻った際に劣化フラグ（isNotificationPermissionDenied／isExactAlarmDegraded）
                    // を再照会するON_RESUME結線。EventSelectionRoute（onPermissionRequested系）・
                    // DepartureRoute（onResume系）と同型のDisposableEffect＋LifecycleEventObserver
                    // （lifecycle-runtime-compose追加依存不要、同KDoc参照）。
                    val executionLifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(executionLifecycleOwner, viewModel) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                viewModel.refreshDegradationState()
                            }
                        }
                        executionLifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { executionLifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    // F56/F57実配線: Execution画面表示開始時（フォアグラウンド）にForeground
                    // Serviceを起動する。Recovery割込からの復帰でも本composableは再入場する
                    // ため再度呼ばれるが、ExecutionServiceController.startは再入場時に呼ばれ
                    // ても安全（フォアグラウンド・位置権限の各ガードを都度再評価するのみ）。
                    LaunchedEffect(plan) {
                        appContainer.executionServiceController.start(plan)
                    }

                    // Phase 6実配線（P6-C5、計画書§7.6・§9エラーマップ#9/#10・§11.2.2）:
                    // execution route入場時・plan更新時にLatenessDetector.evaluate()を評価し、
                    // WillMissEventならRecoveryへ自動割り込みする（§70完成条件）。
                    // currentLocationはLatenessDetector.evaluate()が参照しないフィールドのため
                    // （recovery/LatenessDetector.ktのevaluate実装参照。unfinishedSteps／
                    // currentTime／latestTravelEstimate／event.startDate／plannedDepartureTime
                    // のみを使う）nullで構わない——本Composableに位置情報取得の非同期処理を
                    // 追加で持ち込まずに済む。
                    //
                    // one-shotガード（hasAutoNavigatedToRecovery、§9エラーマップ#10）:
                    // Recoveryの「Use this plan」はpopBackStackで本routeへ戻り、
                    // SharedPlanViewModel.confirmPlanで更新されたplanにより本LaunchedEffectが
                    // （planをキーとしているため）再発火する。適用後のplanが依然
                    // WillMissEventのままだと自動遷移を繰り返す無限ループになるため、この
                    // Execution滞在中（plan.event.idでスコープ——RecoveryPlanApplier.applyは
                    // eventを変更しないため、Recovery往復中も同一event.idのまま保たれる）は
                    // 自動遷移を高々1回に制限する。rememberSaveableを使うのは、Recoveryを
                    // 一時的に表示している間もExecutionのcomposable自体はバックスタックに
                    // 残り続け（popされない）、状態が保持される前提のため。「Simulate delay
                    // (debug)」ボタン起点の下記onNavigateToRecoveryは本ガードと独立した別経路
                    // であり対象外（§11.2.2、手動操作は常に効く）。
                    var hasAutoNavigatedToRecovery by rememberSaveable(plan.event.id) { mutableStateOf(false) }
                    LaunchedEffect(plan) {
                        if (hasAutoNavigatedToRecovery) return@LaunchedEffect

                        val remainingTravelEstimate = Duration.between(plan.departureTime, plan.estimatedArrival).let {
                            if (it.isNegative) Duration.ZERO else it
                        }
                        val recoveryContext = RecoveryContext(
                            currentTime = Instant.now(),
                            currentLocation = null,
                            event = plan.event,
                            unfinishedSteps = plan.steps,
                            latestTravelEstimate = remainingTravelEstimate,
                            plannedDepartureTime = plan.departureTime
                        )
                        if (LatenessDetector.evaluate(recoveryContext) is LatenessVerdict.WillMissEvent) {
                            hasAutoNavigatedToRecovery = true
                            navController.navigate(Destinations.Recovery.route)
                        }
                    }

                    ExecutionScreen(
                        uiState = executionUiState,
                        onNavigateToDeparture = {
                            // F56/F57・T-STORE-6文脈（計画書§10.6申し送り）: Execution完了
                            // （最終ステップのDone→departureへの直行）でアラーム・FGSを終了
                            // する。Recovery割込（onNavigateToRecovery）は本composableを
                            // 離れるだけの一時的な迂回でありExecution中断ではないため、
                            // そちらには紐付けない（画面破棄一般に紐付けるとRecovery表示の
                            // たびに正当なアラームを消してしまう）。
                            appContainer.notificationService.cancelAll(plan.event.id.toString())
                            appContainer.executionServiceController.stop()
                            navController.navigate(Destinations.Departure.route)
                        },
                        onNavigateToRecovery = {
                            navController.navigate(Destinations.Recovery.route)
                        },
                        onNavigateToEventSelection = {
                            navController.navigate(Destinations.EventSelection.route) {
                                popUpTo(Destinations.EventSelection.route) { inclusive = true }
                            }
                        },
                        onOpenNotificationSettings = {
                            // F80実配線: EventSelectionRoute/DepartureRouteのSettings導線と同じ
                            // Settings.ACTION_APPLICATION_DETAILS_SETTINGSパターン。
                            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(settingsIntent)
                        }
                    )
                }
            }

            composable(Destinations.Departure.route) {
                DepartureRoute(vmFactory = vmFactory)
            }

            composable(Destinations.Recovery.route) {
                val viewModel: RecoveryViewModel = viewModel(factory = vmFactory)
                val uiState by viewModel.uiState.collectAsState()

                RecoveryScreen(
                    uiState = uiState,
                    onNavigateToExecution = {
                        // execution → recovery → execution（計画書§10.2）。executionは
                        // バックスタックに残っているため、popBackStackで同一エントリへ戻る。
                        navController.popBackStack()
                    }
                )
            }

            // F97実配線（計画書§7.2フットプリント「Settings route 1本追加」、P7-C6）。
            composable(Destinations.Settings.route) {
                val viewModel: SettingsViewModel = viewModel(factory = vmFactory)
                val uiState by viewModel.uiState.collectAsState()

                SettingsScreen(
                    uiState = uiState,
                    onNavigateBack = { navController.popBackStack() },
                    onAiEnabledToggled = viewModel::onAiEnabledToggled,
                    onModelSelected = viewModel::onModelSelected,
                    onDownloadRequested = { entry -> viewModel.onDownloadRequested(entry) },
                    onDeleteRequested = viewModel::onDeleteRequested
                )
            }
        }
    }
}

/**
 * eventSelection routeの結線本体（P2-C7〔旧P2-C6〕リファクタサイクルで[ActionStarterNavHost]の
 * `composable(Destinations.EventSelection.route)`ブロックから抽出。ViewModel生成・権限
 * launcher・ON_RESUME再チェック・[EventSelectionScreen]への5ラムダ結線を担う。抽出前と
 * 完全に同一の挙動（結線内容・呼び出し順序は無変更）。詳細は[ActionStarterNavHost]のKDoc
 * 「権限リクエスト・ON_RESUME再チェック・手動入力の結線」節を参照。
 */
@Composable
private fun EventSelectionRoute(
    vmFactory: ViewModelProvider.Factory,
    navController: NavHostController,
    sharedPlanViewModel: SharedPlanViewModel
) {
    val context = LocalContext.current
    val viewModel: EventSelectionViewModel = viewModel(factory = vmFactory)
    val uiState by viewModel.uiState.collectAsState()

    // 権限リクエストUIの配置（計画書§7.3）：ActivityResultLauncherはNavHost本体が
    // 保持し、EventSelectionScreenへはラムダのみを渡す。§7.4改訂（Fable 5裁定
    // 2026-08-09）：結果がtrue（許可）ならviewModel.onRetry()でrefresh()を再実行し、
    // false（拒否）ならviewModel.onPermissionDenied()で即座にPermissionDeniedへ遷移する。
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onRetry()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    // T-PERM-5（ON_RESUMEでの自動復帰、§95.4「Settingsから再許可すると自動連携に
    // 復帰する」）のUI側経路。lifecycle-runtime-compose追加なしでDisposableEffect＋
    // LifecycleEventObserverにより実現する（計画書§13 P-3の代替案）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onRetry()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // F97導線（計画書§7.2フットプリント「既存画面からの導線＝EventSelection等からの設定
    // アイコン/メニュー」、P7-C6）: Settingsへの入口は`EventSelectionScreen`自身のパラメータ
    // ではなく、本Route（NavHost側）がBoxで重ねるボタンとして持たせる。理由:
    // `EventSelectionScreen`は§10.6疎結合規約により画面遷移をラムダで受け取るのみで
    // NavController等のナビゲーション関心を一切持たない設計であり、この既存契約
    // （`EventSelectionScreenTest`／`EventSelectionListTest`／`FontScaleResilienceTest`
    // T-P11F-1・`AccessibilitySemanticsTest`が広く依存する既存シグネチャ・内部構造）へ手を
    // 加えず、既存の広範なテスト群への回帰リスクをゼロにするため（`DepartureRoute`の
    // KDoc「§10.6疎結合規約」と同じ設計方針をNavHost側でも徹底する）。アイコンではなく
    // テキストボタンにしているのは、本プロジェクトがMaterial Iconグリフを一切使わない
    // （既存画面は全てテキストラベル付きボタンのみ）既存方針に合わせるため。
    Box(modifier = Modifier.fillMaxSize()) {
        EventSelectionScreen(
            uiState = uiState,
            onNavigateToPlanReview = {
                val content = uiState as? EventSelectionUiState.Content
                if (content != null) {
                    // P2-C3前段scaffold：EventSelectionUiState.ContentはnextEvent単一保持
                    // からevents: List<ExecutionEvent>保持へ変更された（計画書§7）。
                    // 複数選択UIの結線はP2-C4/C5以降のため、ここでは従来どおり
                    // 先頭（次の）イベントのみを選択する最小適応とする。
                    sharedPlanViewModel.selectEvent(content.events.first())
                    navController.navigate(Destinations.PlanReview.route)
                }
            },
            onRequestCalendarPermission = {
                // §7.4改訂（Fable 5裁定2026-08-09）：launcher起動前に「要求済み」を記録する。
                // これにより起動前からの拒否（PermissionRequired）とlauncher経由の拒否
                // （PermissionDenied）をEventSelectionViewModel側で一意に判別できる。
                viewModel.onPermissionRequested()
                requestPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            },
            onOpenAppSettings = {
                val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(settingsIntent)
            },
            onRetry = viewModel::onRetry,
            onManualEventConfirmed = { event ->
                // F17（手動入力フォールバック、裁定B1）：選択イベントとしてSharedPlanViewModel
                // へ反映し、通常の選択導線（onNavigateToPlanReview）と同じ遷移先へ進む。
                sharedPlanViewModel.selectEvent(event)
                navController.navigate(Destinations.PlanReview.route)
            }
        )
        TextButton(
            onClick = { navController.navigate(Destinations.Settings.route) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .testTag("event_selection_open_settings_button")
        ) {
            Text(stringResource(R.string.settings_open_button_label))
        }
    }
}

/**
 * departure routeの結線本体（Phase 3統合サイクル・P3-C6、integration owner。計画書§6.4#5・
 * §9.7）。[EventSelectionRoute]と同型の抽出パターン（§10.6疎結合規約：
 * `ActivityResultLauncher`・`NavController`・ライフサイクルは本Composableのみが保持し、
 * [DepartureScreen]へはラムダのみを渡す）。
 *
 * **位置権限launcher（計画書§9.7 T-PERM3-1〜5、S-1裁定）**: `RequestMultiplePermissions`で
 * [Manifest.permission.ACCESS_FINE_LOCATION]・[Manifest.permission.ACCESS_COARSE_LOCATION]を
 * 同時要求する（S-1裁定「Android 12+でFINEのみを実行時要求すると『正確な位置／おおよその
 * 位置』トグルが成立しない」への対応。`AndroidManifest.xml`の両権限宣言と対）。結果
 * （許可・拒否のいずれの組み合わせでも）は[DepartureViewModel.onResume]へ委譲して再計算する：
 * `recalculateRoute`は実際のOSレベル権限状態を`AppContainer.permissionGate`
 * （[com.actionstarter.services.permission.AndroidPermissionGate]、`ContextCompat.
 * checkSelfPermission`ベース）経由でその都度再照会するため、launcherが返す`Map<String,
 * Boolean>`の個別値をここで分岐する必要はない（`EventSelectionRoute`の単一権限launcherが
 * `isGranted`個別分岐を要するのとは異なり、Departure側は「許可の有無を問わず現在の実際の
 * 状態で再計算する」という[onResume]の設計にそのまま委譲できる）。
 *
 * **ON_RESUME再チェック（計画書§9.7 T-PERM3-4、§95.4「Settingsから再許可すると自動連携に
 * 復帰する」）**: [EventSelectionRoute]と同型の`DisposableEffect`＋`LifecycleEventObserver`で
 * 実現する（`lifecycle-runtime-compose`は`activity-compose`／`navigation-compose`経由で
 * 既にコンパイルクラスパス上にあるため追加依存不要）。`Lifecycle.addObserver`は登録時点の
 * 現在状態へ追いつくよう即座にコールバックを再生する仕様のため、本Composableが
 * コンポジションに入った直後（画面表示直後）にも1回`ON_RESUME`相当が発火し
 * [DepartureViewModel.onResume]が呼ばれる。これは[DepartureViewModel.init]が
 * `confirmedPlan`購読開始時に行う自動再計算に対する**追加の**呼び出しであり、
 * [com.actionstarter.features.DepartureRoutingScreenTest]のT-PERM3-4が
 * `callCountAfterInitialLoad + 1`という差分規約（T-DEPVM-8と同型）でこの重複発火を
 * 前提として検証している。
 *
 * `onOpenLocationSettings`は[EventSelectionRoute]の`onOpenAppSettings`と同じ
 * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`パターンを用いる。
 * `onManualTravelMinutesChange`／`onTransportModeSelected`は状態を持たず
 * [DepartureViewModel]へそのまま委譲する（メソッド参照）。
 */
@Composable
private fun DepartureRoute(vmFactory: ViewModelProvider.Factory) {
    val context = LocalContext.current
    val viewModel: DepartureViewModel = viewModel(factory = vmFactory)
    val uiState by viewModel.uiState.collectAsState()

    val requestLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 個々の許可/拒否はDepartureViewModel.onResume()経由でAppContainer.permissionGateが
        // 都度再照会するため、ここではlauncherが返すMapの値を分岐しない（クラスKDoc参照）。
        viewModel.onResume()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DepartureScreen(
        uiState = uiState,
        onRequestLocationPermission = {
            requestLocationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        },
        onOpenLocationSettings = {
            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(settingsIntent)
        },
        onManualTravelMinutesChange = viewModel::onManualTravelMinutesChanged,
        onTransportModeSelected = viewModel::onTransportModeSelected
    )
}
