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
 *
 * **P6-C1 scaffold注記（計画書§6.2・§7.7）**: [routingFailureReason]／[isPlanApplied]は
 * 本サイクルで追加したフィールドで、いずれも呼び出し箇所（`RecoveryViewModel`の
 * `RecoveryUiState(options = ..., selectedOptionId = ...)`／`RecoveryScreenTest`の各種
 * 生成箇所）が無変更でコンパイルを維持できるようデフォルト値を持たせている。この時点では
 * 生成・参照ロジックを追加していない（値は常に既定のまま）。
 * - [routingFailureReason]: D案（移動手段変更）の経路取得が失敗した理由を表す文字列リソースID
 *   （§7.7・§9エラーマップ#4、握り潰さないための明示欄）。P6-C4で結線する。
 * - [isPlanApplied]: 「Use this plan」適用後の再突入抑止（無限ループ防止、§9エラーマップ#10、
 *   T-RECVM-6/8）に使う想定のプレースホルダ。フラグの最終的な保持場所（本フィールド／
 *   `SharedPlanViewModel`側）はP6-C3/C5で確定する（計画書§9エラーマップ#10の「または」表記の
 *   とおり未確定。計画書§6.2はRecoveryUiStateへのフィールド追加を明記しているため、
 *   その指示に従いここへ置く）。
 */
data class RecoveryUiState(
    val options: List<RecoveryOption> = emptyList(),
    val selectedOptionId: UUID? = null,
    val routingFailureReason: Int? = null,
    val isPlanApplied: Boolean = false
)
