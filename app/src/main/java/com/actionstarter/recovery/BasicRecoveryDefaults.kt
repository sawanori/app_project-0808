package com.actionstarter.recovery

import com.actionstarter.domain.valueobject.TransportMode

/**
 * 計画書§6.1・§7.5・§7.9（ADR起票候補4）準拠（P6-C1 scaffold）。
 *
 * [BasicRecoveryEngine] が移動手段変更案（D案、F75）を生成する際に用いる既定値・代替移動手段
 * テーブルの隔離先（`BasicPlanningDefaults` のG-1/R-7先例に倣う）。
 *
 * **[DEFAULT_TRANSPORT_MODE] は仕様未定義プレースホルダである**（S-3・§4.2 U-4で承認済み）。
 * 仕様§50 `RecoveryContext` には現在の移動手段を保持するフィールドが存在しないため、
 * [BasicRecoveryEngine] のコンストラクタ引数 `currentTransportMode` の既定値としてここへ
 * 集約する。値そのもの（`TRANSIT`）は `DepartureUiState.kt:50` の実測既定値と一致させている。
 *
 * **代替移動手段テーブルも仕様未定義プレースホルダである**（§7.5）。1 Recoveryあたり
 * RoutingService呼び出しは最大1回に制限する方針（§95.2、T-BRE-18）のため、4モード総当たりでは
 * なく決定的に1つだけ代替を試す: `TRANSIT→DRIVING` / `WALKING→TRANSIT` / `CYCLING→TRANSIT` /
 * `DRIVING→TRANSIT`。
 *
 * ロジック本体はP6-C3で実装する（TDD厳守。本ファイルはP6-C1時点では宣言のみ）。
 */
object BasicRecoveryDefaults {

    /**
     * D案生成時に現在の移動手段が不明な場合の既定値（S-3・§4.2 U-4）。
     */
    val DEFAULT_TRANSPORT_MODE: TransportMode
        get() = TransportMode.TRANSIT

    /**
     * 決定的な代替移動手段テーブル（§7.5）。該当なしの場合は`null`を返す契約とする
     * （4モードすべてを網羅する固定テーブルのため実際にはnullは発生しない想定だが、
     * 将来のモード追加に対して安全側に倒す）。
     */
    fun alternativeTransportMode(current: TransportMode): TransportMode? = when (current) {
        TransportMode.TRANSIT -> TransportMode.DRIVING
        TransportMode.WALKING -> TransportMode.TRANSIT
        TransportMode.CYCLING -> TransportMode.TRANSIT
        TransportMode.DRIVING -> TransportMode.TRANSIT
    }
}
