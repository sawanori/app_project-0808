package com.actionstarter.features.departure

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import com.actionstarter.R

/**
 * F28（計画書§6.1・§4、S-2裁定）位置権限拒否時などのTravel Time手動入力フォールバック
 * Composable（T-DEP2-3「手動 Travel Time 入力 → ETA が再表示される」）。
 *
 * P3-C5（Green）実装。Composeの単方向データフロー（State Hoisting）規約に従い、入力値は
 * [minutes]として呼び出し側（`DepartureScreen`経由で`DepartureViewModel`／
 * [DepartureUiState.manualTravelMinutes]）から受け取り、変更は[onMinutesChange]で通知する
 * （内部stateを保持しない。[com.actionstarter.features.eventselection.ManualEventEntry]と
 * 同じ規約）。ETAの再表示自体は本Composableの責務外であり、
 * `DepartureViewModel.onManualTravelMinutesChanged`経由で呼び出し側が行う。
 *
 * 数字以外の入力・空入力は`onMinutesChange(null)`として通知する（自動補正・丸め込みをせず、
 * 不正入力をnullという型で表現する。§89 サイレント障害防止方針の継承）。
 *
 * testTag契約（[com.actionstarter.features.DepartureRoutingScreenTest] T-DEP2-3が要求）:
 * - [TRAVEL_TIME_INPUT_TEST_TAG]（"travel_time_input_field"）… 入力欄本体
 *
 * ラベル文言（[R.string.travel_time_manual_label]）はMaterial3の`label`スロットとして
 * 描画するため、[DepartureScreen]がDENIED状態で本Composableを埋め込む際もT-PERM3-3が
 * 要求する同文言のテキストノードとして検出できる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelTimeInput(
    minutes: Int?,
    onMinutesChange: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    // P11-C6（F81取りこぼし是正、`docs/plans/phase11-i18n-a11y.md`§10、T-P11A-12b）:
    // 計画書§6.1のfootprint表が元々要求していたcontentDescription付与。testTagと同一ノードへ
    // 追加するため、T-DEP2-3（`performTextInput`によるテキスト入力検証）への回帰影響はない。
    val label = stringResource(R.string.travel_time_manual_label)
    OutlinedTextField(
        value = minutes?.toString().orEmpty(),
        onValueChange = { raw ->
            val digitsOnly = raw.filter { it.isDigit() }
            onMinutesChange(digitsOnly.toIntOrNull())
        },
        label = { Text(text = label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = modifier
            .testTag(TRAVEL_TIME_INPUT_TEST_TAG)
            .semantics { contentDescription = label }
    )
}

/** [TravelTimeInput]の入力欄本体に付与するtestTag（T-DEP2-3契約、P3-C5で配線）。 */
const val TRAVEL_TIME_INPUT_TEST_TAG: String = "travel_time_input_field"
