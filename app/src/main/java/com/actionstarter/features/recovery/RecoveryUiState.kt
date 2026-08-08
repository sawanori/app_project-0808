package com.actionstarter.features.recovery

import com.actionstarter.domain.model.RecoveryOption
import java.util.UUID

/**
 * 仕様§31-34・§35 Screen5準拠（RecoveryScreen）。最大3案（仕様§32）。
 *
 * [options]が空のとき案内文言と手動導線を表示する（T-REC-3）。[selectedOptionId]は
 * 候補選択だけでは自動適用されないという制約（仕様§34、T-REC-5）を表すための
 * 「仮選択中」の状態。「Use this plan」等の確認操作を経て初めて適用される想定
 * （適用ロジックはC4で実装）。
 */
data class RecoveryUiState(
    val options: List<RecoveryOption> = emptyList(),
    val selectedOptionId: UUID? = null
)
