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
 *
 * **P5-C2b契約scaffold追補（本サイクル、計画書§6.2・ADR-0026）**: 以下3フィールドは
 * F51/F52/F56/F57が要求する劣化表示（exact alarm不許可／通知不許可／Foreground Service
 * 不可）のための状態を型として先行確定するために追加した。いずれも末尾へ追加し既定値
 * `false`を持つため、既存の名前付き引数によるコンストラクタ呼び出し（T-EXEC-1〜9、
 * [com.actionstarter.features.ExecutionScreenTest]・[ExecutionViewModelTest]）は無変更で
 * 成立する。本サイクルではフィールドを追加するのみで、[ExecutionViewModel]からこれらを
 * 更新するロジックは書かない（P5-C3で実装する）。
 * - [isNotificationPermissionDenied]: POST_NOTIFICATIONS拒否時、NOWカードのみで状態が伝わり
 *   設定導線を出す（T-P5UI-6、エラー&レスキューマップ#3）。
 * - [isExactAlarmDegraded]: SCHEDULE_EXACT_ALARM未許可時、精度低下の明示表示＋
 *   `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`導線を出す（T-P5UI-7、S-4、エラー&レスキュー
 *   マップ#1、ADR-0026）。
 * - [isForegroundServiceDegraded]: Foreground Service起動不可時、exact alarm＋通知のみで
 *   継続しDoze下での遅延可能性を明示する（F56/F57、S-3、エラー&レスキューマップ#5/#6、
 *   ADR-0026）。
 */
data class ExecutionUiState(
    val currentStep: ExecutionStep? = null,
    val currentStepIndex: Int = 0,
    val snackbarMessageResId: Int? = null,
    val onDone: (() -> Unit)? = null,
    val onPostpone: (() -> Unit)? = null,
    val isNotificationPermissionDenied: Boolean = false,
    val isExactAlarmDegraded: Boolean = false,
    val isForegroundServiceDegraded: Boolean = false
)
