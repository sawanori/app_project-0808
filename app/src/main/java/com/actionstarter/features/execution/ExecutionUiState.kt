package com.actionstarter.features.execution

import com.actionstarter.domain.model.ExecutionStep

/**
 * 仕様§27-28・§35 Screen3準拠（ExecutionScreen）。One Action原則（仕様§28）により、
 * 同時に1ステップ（[currentStep]）のみを保持する（畳んだリスト・進捗プレビューは
 * 保持しない）。
 *
 * [currentStepIndex]は画面回転後も保持されるべき値（T-EXEC-7）で、プロセス再生成後は
 * `SavedStateHandle`から復元する（T-EXEC-8）。
 * [snackbarMessageResId]は復元不能時にeventSelectionへ遷移する際の通知
 * （エラー＆レスキューマップ#8、T-EXEC-9）に対応するための文字列リソースID
 * （UI文字列ハードコード禁止のためstring resource ID経由で保持する）。
 *
 * [onDone]／[onPostpone]（C4追補・A1後継のUiState公開API拡張。計画書§9.4／本ファイルの
 * 元KDocでは想定されていなかったが、C2契約scaffold時点のExecutionScreenシグネチャ
 * （画面遷移ラムダ3本のみ）ではDone／5 min laterのユーザー操作をViewModelへ橋渡しする
 * 経路が存在しないため、C4でUiStateへイベントコールバックとして追加した）。
 *
 * 既知の制約: 本来はScreen側が受け取るコールバック引数（例: onDone: () -> Unit）として
 * 疎結合に設計するのが望ましいが、C2で確定した`ExecutionScreen`のシグネチャ
 * （`uiState`＋画面遷移ラムダ3本のみ）を変更するとC3で作成済みのテスト呼び出し
 * （`ExecutionScreen(uiState = ..., onNavigateToDeparture = ..., ...)`）と
 * 整合しなくなるおそれがあるため、UiState側の拡張（本フィールド追加）で対応した。
 * [onDone]が`null`のとき、Screenは「次のステップが存在しない＝最終ステップ」とみなし
 * `onNavigateToDeparture()`を直接呼ぶ（T-EXEC-4）。非nullのとき、Screenはそれを
 * 呼び出すのみでナビゲーションは行わない（T-EXEC-3）。[onPostpone]も同様の橋渡し用途。
 *
 * C5裁定（完了報告「タスク7の判断結果」参照）: NavHostは実行時、[ExecutionViewModel]を
 * 経由せず[com.actionstarter.navigation.SharedPlanViewModel.confirmedPlan]から直接
 * [ExecutionUiState]を構築し、[onDone]は`null`のまま渡す設計を採用している。そのため上記の
 * `onDone`が`null`のフォールバック経路（T-EXEC-4）は本番のNavHost結線でも実際に使用される
 * 経路であり、[ExecutionViewModel]経由の非null経路（T-EXEC-3）は単体テストのみが検証する。
 */
data class ExecutionUiState(
    val currentStep: ExecutionStep? = null,
    val currentStepIndex: Int = 0,
    val snackbarMessageResId: Int? = null,
    val onDone: (() -> Unit)? = null,
    val onPostpone: (() -> Unit)? = null
)
