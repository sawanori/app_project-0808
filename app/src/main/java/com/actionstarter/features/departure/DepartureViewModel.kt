package com.actionstarter.features.departure

import androidx.lifecycle.ViewModel
import com.actionstarter.services.routing.RoutingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 仕様§29準拠（DepartureScreenのViewModel）。
 *
 * 契約scaffold（C2）時点では初期[DepartureUiState]（既定値＝未取得状態）のStateFlow公開
 * のみを行う。[routingService]はコンストラクタ注入の形で宣言するが、最新現在地・経路情報
 * からの再計算ロジック（仕様§29）は未実装（C4で実装する）。
 */
class DepartureViewModel(
    private val routingService: RoutingService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DepartureUiState())
    val uiState: StateFlow<DepartureUiState> = _uiState.asStateFlow()
}
