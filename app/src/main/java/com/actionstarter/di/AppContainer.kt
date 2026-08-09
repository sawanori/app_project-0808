package com.actionstarter.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.actionstarter.features.departure.DepartureViewModel
import com.actionstarter.features.eventselection.EventSelectionViewModel
import com.actionstarter.features.planreview.PlanReviewViewModel
import com.actionstarter.features.recovery.RecoveryViewModel
import com.actionstarter.mock.MockPlanFactory
import com.actionstarter.mock.MockRecoveryFactory
import com.actionstarter.mock.MockRoutingService
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.planning.PlanningEngine
import com.actionstarter.recovery.RecoveryEngine
import com.actionstarter.services.calendar.CalendarProviderCalendarService
import com.actionstarter.services.calendar.CalendarService
import com.actionstarter.services.calendar.ContentResolverCursorSource
import com.actionstarter.services.permission.AndroidPermissionGate
import com.actionstarter.services.permission.PermissionGate
import com.actionstarter.services.routing.RoutingService

/**
 * 手動DIコンテナ（計画書§7.3、ADR-0003）。`ActionStarterApplication`から1個生成する。
 * 全画面ViewModelはコンストラクタ注入のみとし、生成箇所は本クラスと
 * [createViewModelFactory]が返す単一の`ViewModelProvider.Factory`に集約する
 * （裁定B2の保護条件。ADR-0014によりHilt導入はPhase 5へ延期されたため、本クラスは
 * 手動DIコンテナとして存続する。計画書§14 P2-C6／旧P2-C5行）。
 *
 * 厳守事項に従い、[planningEngine]／[recoveryEngine]／[routingService]はいずれも
 * `mock/`パッケージのMock実装であり（U6、実データ実装に置き換わり次第削除予定。
 * `MockPlanFactory`／`MockRecoveryFactory`はPhase 4/6まで、`MockRoutingService`は
 * 位置情報・Routes API導入までのPhase 3以降まで現役）、LocalLanguageModelはUI Skeleton
 * フローでは未使用のためDI結線しない。
 *
 * [calendarService]／[permissionGate]は本サイクル（統合サイクル、C6／旧C5・
 * integration owner）で一時ブリッジ（`MockBackedCalendarService`／`GrantedPermissionGate`、
 * いずれも削除済み）から実装（[CalendarProviderCalendarService]／[AndroidPermissionGate]）へ
 * 置換した。いずれも[context]（`applicationContext`）を要する。
 *
 * **§18解消（Fable 5裁定2026-08-09、統合修正サイクル）**: [context]は必須引数へ確定した。
 * 従来存在した「評価すると即座に例外を送出する」互換用デフォルト値（T-DI-1の
 * 零引数呼び出し`AppContainer()`をコンパイル可能に保つためだけの暫定スタブ）は撤去した。
 * `AppContainerTest`（T-DI-1）はRobolectric環境上の実Context
 * （`ApplicationProvider.getApplicationContext()`）を渡す形へ追随済みであり、本クラスの
 * DI設計（Context必須）と整合する。
 *
 * @param context `ActionStarterApplication.onCreate()`が渡す`applicationContext`（本番）、
 *   またはテストが渡すRobolectric環境の実Context。
 */
class AppContainer(
    private val context: Context
) {

    val planningEngine: PlanningEngine = MockPlanFactory()
    val recoveryEngine: RecoveryEngine = MockRecoveryFactory()
    val routingService: RoutingService = MockRoutingService()

    /**
     * F12〜F15実装（計画書§7.2）。[ContentResolverCursorSource]経由で実カレンダー
     * （`CalendarContract`）を読む。旧一時ブリッジ`MockBackedCalendarService`は削除済み。
     */
    val calendarService: CalendarService =
        CalendarProviderCalendarService(ContentResolverCursorSource(context.contentResolver))

    /** F16実装（計画書§7.3）。`ContextCompat.checkSelfPermission`ベース。旧一時ブリッジ`GrantedPermissionGate`は削除済み。 */
    private val permissionGate: PermissionGate = AndroidPermissionGate(context)

    /**
     * `ActionStarterNavHost`（統合サイクル・integration owner所有）から呼び出される単一
     * `ViewModelProvider.Factory`。[sharedPlanViewModel]はactivity-scopedの共有ViewModel
     * （計画書§10.1）であり、[PlanReviewViewModel]／[RecoveryViewModel]がイベント選択・
     * 確定済みPlanを参照するためにクロージャで受け渡す。
     *
     * `ExecutionViewModel`は意図的に本Factoryへ含めない（Phase 1完了報告「タスク7の判断結果」
     * 参照）：そのコンストラクタ契約（`SavedStateHandle`のみ）は`ExecutionViewModelTest`／
     * `ExecutionScreenTest`に直接束縛されており自己判断で変更しない。`ActionStarterNavHost`の
     * execution routeは`SharedPlanViewModel.confirmedPlan`から直接`ExecutionUiState`を構築する
     * （`ActionStarterNavHost`のKDoc参照）。
     */
    fun createViewModelFactory(sharedPlanViewModel: SharedPlanViewModel): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                EventSelectionViewModel(
                    calendarService = calendarService,
                    permissionGate = permissionGate,
                    savedStateHandle = createSavedStateHandle()
                )
            }
            initializer { PlanReviewViewModel(planningEngine, sharedPlanViewModel) }
            initializer { DepartureViewModel(routingService) }
            initializer { RecoveryViewModel(recoveryEngine, sharedPlanViewModel) }
        }
}
