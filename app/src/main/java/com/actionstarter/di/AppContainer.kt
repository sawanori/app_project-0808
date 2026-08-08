package com.actionstarter.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.features.departure.DepartureViewModel
import com.actionstarter.features.eventselection.EventSelectionViewModel
import com.actionstarter.features.planreview.PlanReviewViewModel
import com.actionstarter.features.recovery.RecoveryViewModel
import com.actionstarter.mock.MockEventSource
import com.actionstarter.mock.MockPlanFactory
import com.actionstarter.mock.MockRecoveryFactory
import com.actionstarter.mock.MockRoutingService
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.planning.PlanningEngine
import com.actionstarter.recovery.RecoveryEngine
import com.actionstarter.services.routing.RoutingService
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 手動DIコンテナ（計画書§7.3、ADR-0003）。Phase 1限定（`ActionStarterApplication`から
 * 1個生成する）。全画面ViewModelはコンストラクタ注入のみとし、生成箇所は本クラスと
 * [createViewModelFactory]が返す単一の`ViewModelProvider.Factory`に集約する。Hilt移行時
 * （Phase 2先頭）は本ファイルの削除とアノテーション付与のみで完了する設計とする。
 *
 * 厳守事項に従い、Phase 1では契約interface 4本（PlanningEngine／RecoveryEngine／
 * RoutingService／LocalLanguageModel）の本実装（Basic/Local AI Engine）は追加しない。
 * [planningEngine]／[recoveryEngine]／[routingService]はいずれも`mock/`パッケージの
 * Phase 1限定Mock実装であり（U6、Phase 2で実データ実装に置き換わり次第削除）、
 * LocalLanguageModelはPhase 1のUI Skeletonフローでは未使用のためDI結線しない。
 *
 * [mockEventSource]の初期データ（[seedEvent]）は実カレンダー接続（Phase 2、
 * 仕様§66 CalendarProvider）までの暫定シード値であり、Mock供給と同様にPhase 2で削除する。
 */
class AppContainer {

    val mockEventSource: MockEventSource = MockEventSource(events = listOf(seedEvent()))
    val planningEngine: PlanningEngine = MockPlanFactory()
    val recoveryEngine: RecoveryEngine = MockRecoveryFactory()
    val routingService: RoutingService = MockRoutingService()

    /**
     * `ActionStarterNavHost`（C5 integration owner所有）から呼び出される単一
     * `ViewModelProvider.Factory`。[sharedPlanViewModel]はactivity-scopedの共有ViewModel
     * （計画書§10.1）であり、[PlanReviewViewModel]／[RecoveryViewModel]がイベント選択・
     * 確定済みPlanを参照するためにクロージャで受け渡す。
     *
     * `ExecutionViewModel`は意図的に本Factoryへ含めない（C5完了報告「タスク7の判断結果」
     * 参照）：そのコンストラクタ契約（`SavedStateHandle`のみ）はC4の
     * `ExecutionViewModelTest`／`ExecutionScreenTest`に直接束縛されており自己判断で
     * 変更しない。`ActionStarterNavHost`のexecution routeは
     * `SharedPlanViewModel.confirmedPlan`から直接`ExecutionUiState`を構築する
     * （`ActionStarterNavHost`のKDoc参照）。
     */
    fun createViewModelFactory(sharedPlanViewModel: SharedPlanViewModel): ViewModelProvider.Factory =
        viewModelFactory {
            initializer { EventSelectionViewModel(mockEventSource) }
            initializer { PlanReviewViewModel(planningEngine, sharedPlanViewModel) }
            initializer { DepartureViewModel(routingService) }
            initializer { RecoveryViewModel(recoveryEngine, sharedPlanViewModel) }
        }

    private fun seedEvent(): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = Instant.now().plus(SEED_EVENT_LEAD_TIME),
        locationName = "Shibuya Studio",
        coordinates = Coordinate(lat = 35.6595, lon = 139.7005),
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private companion object {
        val SEED_EVENT_LEAD_TIME: Duration = Duration.ofHours(2)
    }
}
