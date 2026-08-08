package com.actionstarter.domain.model

/**
 * ADR-0005により仕様書未定義のため計画書§9.2で補完定義する型。
 * [com.actionstarter.recovery.RecoveryEngine.createRecoveryPlan]（仕様§45）の戻り値。
 *
 * 仕様§32により[options]は最大3件までとする不変条件を持つ
 * （エラー＆レスキューマップ#12：4件以上生成されようとした場合は黙って切り捨てず
 * `init { require(options.size <= 3) }`で即時失敗させる設計）。候補0件でも生成は成功し、
 * UI側で「案なし」表示に写像される（T-DM-8）。
 *
 * 契約scaffold（C2）時点では上記`init`検証は未実装。C3のRedテスト作成後、C4で実装する
 * （C2で実装するとC3のRedが成立しなくなるため）。
 */
data class RecoveryPlan(
    val options: List<RecoveryOption>
)
