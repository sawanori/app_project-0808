package com.actionstarter.features.eventselection

import android.Manifest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionstarter.ai.LocalAiGateway
import com.actionstarter.services.calendar.CalendarQuerySpec
import com.actionstarter.services.calendar.CalendarResult
import com.actionstarter.services.calendar.CalendarService
import com.actionstarter.services.permission.PermissionGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 仕様§24 Phase A準拠（EventSelectionScreenのViewModel）。計画書§7.3の契約
 * （[CalendarService]＋[PermissionGate]＋[SavedStateHandle]のコンストラクタ注入）に基づく。
 *
 * DI結線は一時ブリッジ（`MockBackedCalendarService`／`GrantedPermissionGate`、いずれも
 * `com.actionstarter.di.AppContainer`参照）を介して行い、既存の挙動（Phase 1の
 * `MockEventSource`ベースの単一イベント表示）を等価に維持する（C5で実実装
 * `CalendarProviderCalendarService`／`AndroidPermissionGate`へ置換予定。本ファイルの
 * スコープ外＝services/はui-implementerの変更対象外）。
 *
 * [savedStateHandle]は[permissionRequested]（起動前拒否と再要求後拒否を区別するフラグ、
 * §7.4改訂・Fable 5裁定2026-08-09）の裏付けに使用する。将来のdirty状態管理（裁定B11、
 * 手動入力フォームとの競合防止。エラー＆レスキューマップ#15）での追加使用はP2-C4の
 * テスト契約（T-PERM-1〜7）に含まれないため引き続き本サイクルの実装対象外
 * （§15(c)、未実装。§18相当の申し送り）。
 */
class EventSelectionViewModel(
    private val calendarService: CalendarService,
    private val permissionGate: PermissionGate,
    private val savedStateHandle: SavedStateHandle,
    private val localAiGateway: LocalAiGateway? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<EventSelectionUiState>(EventSelectionUiState.Loading)
    val uiState: StateFlow<EventSelectionUiState> = _uiState.asStateFlow()

    /**
     * 実行中の[refresh]の[Job]（T-PERM-7）。ON_RESUME相当の再チェックが短時間に複数回
     * 発生しても（画面回転時の複数ON_RESUME発火を含む）、前回の読込が完了していない間は
     * [calendarService]への実呼出しを2重に発生させないためのガードに使う。
     */
    private var refreshJob: Job? = null

    /**
     * 権限リクエストを一度でも明示的に発火したか（[SavedStateHandle]裏付け、プロセス再生成を
     * 跨いで保持される。既定`false`）。
     *
     * **§7.4改訂（Fable 5裁定2026-08-09、§15(d)解消）**: 従来の実装は「起動前から拒否／未許可」
     * と「一度要求UIを経由して拒否された」を区別できず、[refresh]の未許可分岐が常に
     * [EventSelectionUiState.PermissionRequired]へ帰着していた（`CalendarNavigationFlowTest`
     * T-NAV2-1が要求する起動前拒否シナリオでの[EventSelectionUiState.PermissionDenied]へ
     * 構造的に到達できないギャップ）。本フラグにより両ケースを一意に判別する：
     * - `false`（未要求）→[EventSelectionUiState.PermissionRequired]（事前説明カード）
     * - `true`（要求済み）→[EventSelectionUiState.PermissionDenied]（手動入力フォールバック）
     *
     * [onPermissionRequested]（要求UI起動時）・[onPermissionDenied]（要求結果が拒否）の
     * 2箇所でのみtrueへ更新する（`ActionStarterNavHost`結線）。
     */
    private var permissionRequested: Boolean
        get() = savedStateHandle[KEY_PERMISSION_REQUESTED] ?: false
        set(value) {
            savedStateHandle[KEY_PERMISSION_REQUESTED] = value
        }

    init {
        refresh()
        warmUpAiIfPossible()
    }

    /**
     * Phase 9.5新設（計画書§3.3 F-2、敵対的レビュー採用A-7「トップ画面（`EventSelection`）
     * 入場時のみ・都度実行」、Step 4実装済み）。トップ画面入場（本ViewModelの構築＝画面入場と
     * 対応）の都度、Local AI Engineの先行ウォームアップ（[LocalAiGateway.warmUp]）を試みる。
     *
     * **[refresh]からではなく[init]から1回だけ呼ぶ理由**: 「画面入場時のみ」は「1回の画面
     * 入場につき1回」を意味し、[refresh]（ON_RESUME再チェック・[onRetry]からも呼ばれ、
     * 同一画面滞在中に複数回起こりうる）から呼ぶと過剰発火する。[init]は本ViewModelの構築
     * （＝画面入場）ごとに厳密に1回だけ実行されるため、トリガー粒度が正確に一致する。
     *
     * **[localAiGateway]が`null`ならno-op**: 既存呼び出し元（`localAiGateway`引数を渡さない
     * `EventSelectionViewModelPermissionTest`等）は`null`のままであり、`?.let`が発火しないため
     * 既存テストの挙動に一切影響しない（後方互換）。
     *
     * **`viewModelScope.launch`で起動する理由**: [LocalAiGateway.warmUp]は`suspend fun`であり、
     * [init]（非suspendコンテキスト）から直接呼べない。`viewModelScope`はViewModelの破棄
     * （＝画面離脱）時に自動キャンセルされるため、画面離脱後もウォームアップが居残り続ける
     * ことはない（[LocalAiGateway.warmUp]のKDoc「[CancellationException]は握り潰さず再送出
     * する」と対になる設計）。
     */
    private fun warmUpAiIfPossible() {
        localAiGateway?.let { gateway ->
            viewModelScope.launch { gateway.warmUp() }
        }
    }

    /**
     * [permissionGate]で権限状態を確認し、未許可なら[permissionRequested]の値に応じて
     * [EventSelectionUiState.PermissionRequired]（§7.4「初期・未要求」、未要求＝`false`）または
     * [EventSelectionUiState.PermissionDenied]（§7.4、要求済み＝`true`。Fable 5裁定2026-08-09）
     * へ遷移する。許可済みなら[calendarService.readUpcomingEvents]を呼び出し、結果を写像する：
     * - [CalendarResult.Success] → 0件は[EventSelectionUiState.Empty]、1件以上は
     *   [EventSelectionUiState.Content]（エラー＆レスキューマップ#4）
     * - [CalendarResult.PermissionDenied] → [EventSelectionUiState.PermissionDenied]
     *   （読取実行中の権限剥奪。§95.6第1行、T-PERM-6）
     * - [CalendarResult.Failure] → [EventSelectionUiState.Error]（T-SEL2-6の再試行導線対象）
     *
     * `viewModelScope`上でsuspend起動する公開メソッドであり、`init`からの初回呼び出しに加え、
     * ON_RESUME再チェック（T-PERM-5/7、呼び出し側＝Screen／NavHostが結線）・[onRetry]
     * （T-SEL2-6）の両方から呼ばれる想定。
     *
     * **in-flightガード（T-PERM-7）**: 前回起動した[refreshJob]がまだ完了していない
     * （[Job.isActive]）間に本メソッドが再度呼ばれた場合は新規launchを行わず即座に戻る。
     * これにより画面回転等で短時間に複数回呼ばれても[calendarService]への実呼出しは
     * 1回に収束する（計画書§10.2「画面回転では二重読込しない」）。既に完了したJobに対する
     * 再呼び出し（例：T-PERM-5のonRetry）は通常どおり新規launchする。
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return

        refreshJob = viewModelScope.launch {
            if (!permissionGate.isGranted(Manifest.permission.READ_CALENDAR)) {
                _uiState.value = if (permissionRequested) {
                    EventSelectionUiState.PermissionDenied
                } else {
                    EventSelectionUiState.PermissionRequired
                }
                return@launch
            }

            val now = Instant.now()
            when (
                val result = calendarService.readUpcomingEvents(
                    from = now,
                    until = now.plus(CalendarQuerySpec.UPCOMING_WINDOW)
                )
            ) {
                is CalendarResult.Success -> {
                    _uiState.value = if (result.value.isEmpty()) {
                        EventSelectionUiState.Empty
                    } else {
                        EventSelectionUiState.Content(events = result.value)
                    }
                }

                is CalendarResult.PermissionDenied -> {
                    _uiState.value = EventSelectionUiState.PermissionDenied
                }

                is CalendarResult.Failure -> {
                    _uiState.value = EventSelectionUiState.Error
                }
            }
        }
    }

    /**
     * エラー表示からの再試行導線（T-SEL2-6）が呼ぶ公開メソッド。[refresh]をそのまま
     * 再実行する（専用の状態は持たない）。
     */
    fun onRetry() {
        refresh()
    }

    /**
     * 権限リクエストランチャー（`ActivityResultContracts.RequestPermission`）を起動する
     * 直前に`ActionStarterNavHost`が呼ぶ（§7.4改訂、Fable 5裁定2026-08-09）。[permissionRequested]
     * をtrueへ設定するのみで、[uiState]は変更しない（この時点ではまだlauncherの結果が
     * 届いていないため）。結果が届いた後は、許可なら[onRetry]、拒否なら[onPermissionDenied]を
     * `ActionStarterNavHost`が呼ぶ。
     */
    fun onPermissionRequested() {
        permissionRequested = true
    }

    /**
     * 権限リクエストランチャーの結果が拒否（`false`）だった場合に`ActionStarterNavHost`が
     * 呼ぶ（§7.4改訂、Fable 5裁定2026-08-09）。[permissionRequested]をtrueへ設定したうえで、
     * 即座に[EventSelectionUiState.PermissionDenied]（手動入力フォールバック、F17）へ遷移する。
     * [refresh]を経由しないのは、この時点で結果（拒否）が既に確定しており
     * [permissionGate.isGranted]の再照会を待つ必要がないため。
     */
    fun onPermissionDenied() {
        permissionRequested = true
        _uiState.value = EventSelectionUiState.PermissionDenied
    }

    private companion object {
        private const val KEY_PERMISSION_REQUESTED = "permissionRequested"
    }
}
