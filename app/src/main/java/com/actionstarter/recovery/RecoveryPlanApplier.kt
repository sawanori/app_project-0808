package com.actionstarter.recovery

import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.StepPriority
import java.time.Clock
import java.time.Duration

/**
 * 計画書§6.1・§7.4・§8.4準拠（F76、P6-C3実装）。選択されたRecovery候補（[RecoveryOption]）を
 * 適用し、更新済みの[ExecutionPlan]を組み立てる（steps除去＋時刻再計算。仕様§34・§49）。
 *
 * **再計算式の確定（Fable 5裁定2、P6-C3）**: P6-C2 test-writerのscaffold時点の暫定解釈
 * （`estimatedArrival`＝新departureTime＋元Planの移動時間）は、D案（`change_transport_mode`）が
 * 選ばれた場合に**元Planの移動時間（変更前の遅い移動手段）をそのまま使ってしまい、D案が実現する
 * はずの短縮された移動時間を無視する**という構造的な誤りを含む。本クラスは`RoutingService`を
 * 保持しない（コンストラクタ引数に存在しない）ため、D案の代替移動時間を独自に再取得する手段が
 * ない。一方、[RecoveryOption.estimatedArrival]は[BasicRecoveryEngine]が§7.2の式で構成ごとに
 * 権威的に計算済みの値であり（D案ならRoutingServiceの代替見積りを反映済み）、UIにも
 * 同じ値が表示される（§32）。よって本クラスは**`estimatedArrival`を`option.estimatedArrival`から
 * そのまま転記する**方式を採用する（`option.estimatedArrival`が`null`の場合のみ、旧来の
 * 「新departureTime＋元Planの移動時間」を安全側フォールバックとして使う。`RecoveryOption
 * .estimatedArrival`は型上nullableであり`!!`を使わないための防御）。`departureTime`の
 * 再計算式（新departureTime＝[clock]の現在時刻＋除去後に残るTRANSITION/PREPARATIONの合計）は
 * P6-C2の解釈のまま変更していない（option側に対応する権威的な値が存在しないため）。
 * `RecoveryPlanApplierTest`のT-APPLY-2/4の期待値は本裁定に合わせて更新済み（変更理由はテスト
 * ファイル冒頭KDoc参照。承認済み変更、テストが誤っていたわけではなく契約確定に伴う更新）。
 *
 * - `apply(plan, option)` は [plan] から `option.skippedStepIds` に該当するstepsを除去した
 *   [ExecutionPlan] を返す（T-APPLY-1）。
 * - `option.skippedStepIds` に [StepPriority.REQUIRED] のidが含まれる場合は
 *   `IllegalArgumentException`（信頼境界の二重防御、T-APPLY-3、§7.4層2）。
 * - `option.skippedStepIds` に `plan.steps` 側に存在しないidが含まれる場合も
 *   `IllegalArgumentException`（黙って無視しない、T-APPLY-5）。
 * - `departureTime`は除去後の残準備（TRANSITION/PREPARATIONかつ`completedAt==null`の合計、
 *   §7.2のR_all定義と同型）を基に、[clock] が示す時刻を基準として再計算する
 *   （T-APPLY-2・T-APPLY-4：`skippedStepIds`が空でも時刻は現在時刻基準で再計算）。
 *   `transitionStart`も同じ基準（[clock]の現在時刻）へ更新する（残準備の開始時刻＝今、
 *   という関係が成立するため。テスト非対象だが不変条件として整合させる）。
 *   `RoutingService`への追加呼び出しは行わない（§95.2のコスト方針を追加消費しない）。
 * - `arrivalBuffer` は変更しない（T-APPLY-6、Phase 4 G-5の概念区別維持）。
 * - `event`は変更しない。
 */
class RecoveryPlanApplier(
    private val clock: Clock = Clock.systemUTC()
) {
    fun apply(plan: ExecutionPlan, option: RecoveryOption): ExecutionPlan {
        val planStepIds = plan.steps.map { it.id }.toSet()
        val unknownIds = option.skippedStepIds.filterNot { it in planStepIds }
        require(unknownIds.isEmpty()) {
            "RecoveryOption.skippedStepIds contains ids not present in ExecutionPlan.steps: $unknownIds"
        }

        val skippedSteps = plan.steps.filter { it.id in option.skippedStepIds }
        val requiredSkipped = skippedSteps.filter { it.priority == StepPriority.REQUIRED }
        require(requiredSkipped.isEmpty()) {
            "RecoveryOption.skippedStepIds must not contain REQUIRED steps: ${requiredSkipped.map { it.id }}"
        }

        val remainingSteps = plan.steps.filterNot { it.id in option.skippedStepIds }

        val remainingPreparation = remainingSteps
            .filter {
                it.completedAt == null &&
                    (it.type == ExecutionStepType.TRANSITION || it.type == ExecutionStepType.PREPARATION)
            }
            .fold(Duration.ZERO) { acc, step -> acc.plus(step.estimatedDuration) }

        val now = clock.instant()
        val newDepartureTime = now.plus(remainingPreparation)
        val originalTravelDuration = Duration.between(plan.departureTime, plan.estimatedArrival)
        val newEstimatedArrival = option.estimatedArrival ?: newDepartureTime.plus(originalTravelDuration)

        return plan.copy(
            steps = remainingSteps,
            transitionStart = now,
            departureTime = newDepartureTime,
            estimatedArrival = newEstimatedArrival
        )
    }
}
