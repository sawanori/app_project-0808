package com.actionstarter.planning

import java.time.Duration

/**
 * [BasicPlanningEngine]（F40〜F46）が`profile`（[com.actionstarter.domain.model.
 * PersonalExecutionProfile]）未取得時に用いる既定値の隔離先（G-1、ADR-0015、R-7）。
 *
 * **[TRANSITION]・[PREPARATION]は仕様未定義のプレースホルダである**（G-1裁定）。仕様書に
 * 根拠となる数値記載はなく、Phase 10のPersonal Execution Profile（仕様§52・§74、永続化された
 * ユーザーごとの実測学習値）が供給されるようになり次第、当該ユーザーの実測値に置き換わる
 * 前提の暫定値である。値そのもの（transition 5分・preparation 15分）は現行`MockPlanFactory`
 * （Phase 1限定Mock）のフォールバック既定値をそのまま踏襲している。
 *
 * **[ARRIVAL_BUFFER]は仕様§4「希望到着余裕」のプリセット（Tight 5分／Normal 10分／
 * Relaxed 20分）のうちNormalを採用したものであり、値自体は仕様根拠がある**（G-1）。
 * ただし「調整UI非搭載のPhase 4では常にNormalを既定にする」という選択自体は本プロジェクトの
 * 実装判断であり、仕様が既定プリセットそのものを指定しているわけではない。
 *
 * 既定値を本オブジェクトへ集約する理由・変更履歴はADR-0015参照。
 */
object BasicPlanningDefaults {
    val TRANSITION: Duration = Duration.ofMinutes(5)
    val PREPARATION: Duration = Duration.ofMinutes(15)
    val ARRIVAL_BUFFER: Duration = Duration.ofMinutes(10)
}
