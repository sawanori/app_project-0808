package com.actionstarter.features.departure

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import com.actionstarter.domain.valueobject.TransportMode

/**
 * F26（計画書§6.1・§4・§9、S-3裁定／§15項目7）Transport mode 4値セレクタComposable
 * （T-DEP2-4「TransportMode 4択が表示され、選択でラムダが1回呼ばれる」）。
 *
 * **Phase 3 C2後半 scaffold追補（TDD例外）**: 本サイクルではシグネチャのみを確定する。
 * Composeの単方向データフロー（State Hoisting）規約に従い、選択中の値は[selectedMode]として
 * 呼び出し側（`DepartureViewModel`／[DepartureUiState.transportMode]、既定値`TRANSIT`＝
 * S-3裁定）から受け取り、選択操作は[onModeSelected]で通知する（内部stateを保持しない）。
 *
 * 本文は意図的に空実装であり、[TransportMode]の4値（`entries`）それぞれに
 * [transportModeSelectorTestTag]を付与した選択UIの描画、およびタップ1回につき
 * [onModeSelected]を1回だけ呼ぶ多重発火防止ロジックはP3-C5（Green）で行う。現時点でこの
 * Composableを`setContent`するテスト（T-DEP2-4）は「期待する要素が見つからない」ことで
 * Redになるのが正しい（`DepartureScreen`のC2契約scaffold時点と同じ設計。
 * `DepartureScreenTest`のKDoc参照）。
 *
 * testTag契約（P3-C5実装が付与すること。T-DEP2-4が要求）:
 * - [transportModeSelectorTestTag]`(mode)` … [TransportMode]の値ごとの選択項目
 *   （例: [TransportMode.TRANSIT] → "transport_mode_selector_TRANSIT"）
 *
 * P3-C5（Green）実装: Material3の`FilterChip`を[TransportMode.entries]の順で並べる
 * （4値、S-3裁定・§15項目7）。各chipの`onClick`は[onModeSelected]をそのmodeで1回だけ呼ぶ
 * （多重発火しない。T-DEP2-4）。ラベルは[transportModeLabelRes]で解決する
 * （§7 UI文字列直接ハードコード禁止）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportModeSelector(
    selectedMode: TransportMode,
    onModeSelected: (TransportMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        TransportMode.entries.forEach { mode ->
            // P11-C6（F81取りこぼし是正、T-P11A-12c）: 既存T-P11A-5はmergeされたラベルText
            // へのフォールバックを許容する設計だったが、フォールバックに頼らず明示的
            // contentDescriptionを実装する。testTagと同一ノードへ追加するため、T-DEP2-4
            // （`assertIsDisplayed`／`performClick`）への回帰影響はない。
            val label = stringResource(transportModeLabelRes(mode))
            FilterChip(
                selected = mode == selectedMode,
                onClick = { onModeSelected(mode) },
                label = { Text(text = label) },
                modifier = Modifier
                    .testTag(transportModeSelectorTestTag(mode))
                    .semantics { contentDescription = label }
            )
        }
    }
}

/** [mode]の表示ラベル文字列リソースを返す（else禁止の網羅`when`、値追加時にコンパイルで検出）。 */
private fun transportModeLabelRes(mode: TransportMode): Int = when (mode) {
    TransportMode.WALKING -> R.string.transport_mode_walking
    TransportMode.DRIVING -> R.string.transport_mode_driving
    TransportMode.TRANSIT -> R.string.transport_mode_transit
    TransportMode.CYCLING -> R.string.transport_mode_cycling
}

/** [TransportModeSelector]の各選択項目に付与するtestTagの接頭辞（T-DEP2-4契約）。 */
const val TRANSPORT_MODE_SELECTOR_TEST_TAG_PREFIX: String = "transport_mode_selector_"

/**
 * [mode]に対応する[TransportModeSelector]の選択項目testTagを返す（T-DEP2-4契約、
 * P3-C5で配線）。形式: `"transport_mode_selector_" + mode.name`
 * （例: [TransportMode.TRANSIT] → "transport_mode_selector_TRANSIT"）。
 */
fun transportModeSelectorTestTag(mode: TransportMode): String =
    TRANSPORT_MODE_SELECTOR_TEST_TAG_PREFIX + mode.name
