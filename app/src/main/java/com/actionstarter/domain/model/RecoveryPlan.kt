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
 * C4でinit検証を実装（T-DM-7: 4件以上は`IllegalArgumentException`、T-DM-8: 0件は成功）。
 * `data class`のまま`copy()`もコンストラクタを経由するため、`copy()`実行時にも同じ検証が
 * 再実行される（ADR-0010）。
 *
 * **P6-C3追補（計画書§7.9 ADR起票候補・U-7、Fable 5裁定1）**: [routingFailureReason]を
 * デフォルト値付きで追加した（既存コンストラクタ呼び出し・`copy()`呼び出しは無変更のまま
 * コンパイルを維持する。`RecoveryPlanTest`のT-DM-7/8は本追加による影響を受けない）。
 * D案（移動手段変更）生成中に[com.actionstarter.services.routing.RoutingException]が
 * 発生した場合、[com.actionstarter.recovery.BasicRecoveryEngine]がその
 * `RoutingException.message`（§58/§60により座標・APIキー・イベント情報を含まない
 * サニタイズ済み文言と保証されている）をここへ転記する（計画書§7.5「握り潰さない」・
 * §9エラーマップ#4）。型を`String?`としたのは、Domain層（`domain.model`パッケージ）が
 * Services層（`services.routing`パッケージ）へ依存する向きの新規importを避けるため
 * （`ARCHITECTURE.md`§1のレイヤー順序 App→Domain→Services→Planning/Recovery→AI に対し、
 * `RoutingException`型をそのまま持たせるとDomainがServicesへ依存する逆方向importになる）。
 * UI層（`RecoveryUiState.routingFailureReason: Int?`）への解決は、strings.xml編集が
 * P6-C5統合ウィンドウ専有のためP6-C3/C4では未結線（完了報告のC5申し送り参照）。
 */
data class RecoveryPlan(
    val options: List<RecoveryOption>,
    val routingFailureReason: String? = null
) {
    init {
        require(options.size <= 3) {
            "RecoveryPlan.options must contain at most 3 options, but had ${options.size}"
        }
    }
}
