package com.actionstarter.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
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
                val viewModel: EventSelectionViewModel = viewModel(factory = vmFactory)
                val uiState by viewModel.uiState.collectAsState()

                EventSelectionScreen(
                    uiState = uiState,
                    onNavigateToPlanReview = {
                        val content = uiState as? EventSelectionUiState.Content
                        if (content != null) {
                            sharedPlanViewModel.selectEvent(content.nextEvent)
                            navController.navigate(Destinations.PlanReview.route)
                        }
                    }
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
