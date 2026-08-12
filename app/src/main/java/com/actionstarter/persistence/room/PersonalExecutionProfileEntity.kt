package com.actionstarter.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 10 C1（計画書`docs/plans/phase10-behavior-log-profile.md`§3.3）。
 * `domain/model/PersonalExecutionProfile.kt`（仕様§52、Phase 8.5 C2 scaffold）のRoom版。
 * [eventCategory]をキーとしカテゴリ単位で1行。
 *
 * **本フェーズが実際に書き込むのは[averageTransitionDurationMs]・
 * [averagePreparationDurationMs]の2フィールドのみ**（計画書§3.3、レビューCRITICAL・§13
 * No.3）。[averageResponseDelayMs]・[averageDepartureDelayMs]・[preferredArrivalBufferMs]は
 * GPS/到着検知の実装が本アプリに存在せず計測手段自体がないため、本フェーズを通じて常に
 * `null`のまま（将来フェーズでの拡張余地として列自体は用意する）。
 *
 * **命名と実装の乖離（計画書§3.3明記）**: `average*`という列名だが、集計側（C3実装予定）は
 * 中央値を格納する設計とする——サンプル数が少ない初期段階で外れ値に平均が引っ張られることを
 * 避けるため。
 *
 * **単位はミリ秒（`Long?`）とし`java.time.Duration`へは変換しない**（Room TypeConverterの
 * 追加を本フェーズの必須スコープに含めないための単純化）。`domain.model.
 * PersonalExecutionProfile`（全フィールド非null`Duration`）への変換は集計・読み出し側
 * （C3実装予定）の責務とする。
 *
 * **C3で解消した設計テンション（C1メモの申し送りへの回答、コーディネーター裁定）**:
 * `domain.model.PersonalExecutionProfile`は6フィールドとも非null`Duration`で宣言されており
 * null許容ではない（型定義自体は本フェーズ非変更、計画書§5）。本エンティティ→ドメイン型への
 * 変換（`RoomAnalyticsStore.getProfile`）では、常にnullな3フィールド（および現時点で
 * サンプルがなくnullな残り2フィールドも同様に）へ**`Duration.ZERO`をプレースホルダとして
 * 充てる**と裁定した。本フェーズは`BasicPlanningEngine`がこの3フィールドを一切参照しない
 * ため実害はない。**将来フェーズでこの3フィールドを消費する実装を追加する場合は、
 * プレースホルダZEROと「実測ゼロ」を区別する手段（例: `domain.model.
 * PersonalExecutionProfile`自体のnull許容化、または別途「計測済みフィールドの集合」を
 * 返す設計）を先に用意すること——本裁定はこの区別を後回しにする決定であり、解決した
 * わけではない点に注意。**
 */
@Entity(tableName = "personal_execution_profiles")
data class PersonalExecutionProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_category")
    val eventCategory: String,
    @ColumnInfo(name = "average_transition_duration_ms")
    val averageTransitionDurationMs: Long? = null,
    @ColumnInfo(name = "average_preparation_duration_ms")
    val averagePreparationDurationMs: Long? = null,
    @ColumnInfo(name = "average_response_delay_ms")
    val averageResponseDelayMs: Long? = null,
    @ColumnInfo(name = "average_departure_delay_ms")
    val averageDepartureDelayMs: Long? = null,
    @ColumnInfo(name = "preferred_arrival_buffer_ms")
    val preferredArrivalBufferMs: Long? = null
)
