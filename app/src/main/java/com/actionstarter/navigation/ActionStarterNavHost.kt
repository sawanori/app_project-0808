package com.actionstarter.navigation

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.actionstarter.features.departure.DepartureScreen
import com.actionstarter.features.departure.DepartureViewModel
import com.actionstarter.features.eventselection.EventSelectionScreen
import com.actionstarter.features.eventselection.EventSelectionUiState
import com.actionstarter.features.eventselection.EventSelectionViewModel
import com.actionstarter.features.execution.ExecutionScreen
import com.actionstarter.features.execution.ExecutionUiState
import com.actionstarter.features.planreview.PlanReviewScreen
import com.actionstarter.features.planreview.PlanReviewViewModel
import com.actionstarter.features.recovery.RecoveryScreen
import com.actionstarter.features.recovery.RecoveryViewModel
import kotlinx.coroutines.launch

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
 * **T-NAV-4ガード**: execution routeは[SharedPlanViewModel.confirmedPlan]がnullの場合
 * （Planが未確定のままexecutionへ到達しようとした場合）、`popUpTo`でeventSelectionへ戻し
 * Snackbarで通知する（エラー＆レスキューマップ#9）。
 *
 * **ExecutionViewModelの扱い（C5の判断・完了報告「タスク7の判断結果」参照）**:
 * execution routeは[com.actionstarter.features.execution.ExecutionViewModel]を経由せず、
 * [SharedPlanViewModel.confirmedPlan]の最初のステップから直接[ExecutionUiState]を構築する。
 * 理由：[com.actionstarter.features.execution.ExecutionViewModel]のコンストラクタ契約
 * （`SavedStateHandle`のみ）はC4の`ExecutionViewModelTest`／`ExecutionScreenTest`に直接
 * 束縛されており自己判断で変更しない。一方、[com.actionstarter.mock.MockPlanFactory]は
 * 常に3ステップ以上（Transition／Preparation／[Travel]／Departure）を生成する構造であり、
 * `NavigationFlowTest`（変更不可）のT-NAV-1／T-NAV-3は「Doneタップ1回でExecutionから
 * 離脱する」ことを前提としている。両立不能なため、[ExecutionUiState.onDone]を`null`のまま
 * 渡し、既存の[ExecutionScreen]のフォールバック（`onDone`が`null`のときDoneタップで
 * 即座に`onNavigateToDeparture`を呼ぶ、T-EXEC-4で既にGreenの契約）を利用する。
 * 結果として、複数ステップを1つずつ「Done」で進める本来のOne Action多段階遷移は、
 * 現状NavHost経由では未結線（ExecutionViewModel／ExecutionScreenの単体テスト
 * T-EXEC-3/6/7/8/9では引き続き検証・Green）。既知の制限として完了報告に明記する。
 */
@Composable
fun ActionStarterNavHost() {
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

                PlanReviewScreen(
                    uiState = uiState,
                    onNavigateToExecution = {
                        viewModel.confirmAndStart()
                        navController.navigate(Destinations.Execution.route)
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
                    val executionUiState = ExecutionUiState(
                        currentStep = plan.steps.firstOrNull(),
                        currentStepIndex = 0,
                        snackbarMessageResId = null,
                        onDone = null,
                        onPostpone = null
                    )

                    ExecutionScreen(
                        uiState = executionUiState,
                        onNavigateToDeparture = {
                            navController.navigate(Destinations.Departure.route)
                        },
                        onNavigateToRecovery = {
                            navController.navigate(Destinations.Recovery.route)
                        },
                        onNavigateToEventSelection = {
                            navController.navigate(Destinations.EventSelection.route) {
                                popUpTo(Destinations.EventSelection.route) { inclusive = true }
                            }
                        }
                    )
                }
            }

            composable(Destinations.Departure.route) {
                val viewModel: DepartureViewModel = viewModel(factory = vmFactory)
                val uiState by viewModel.uiState.collectAsState()

                DepartureScreen(uiState = uiState)
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
}
