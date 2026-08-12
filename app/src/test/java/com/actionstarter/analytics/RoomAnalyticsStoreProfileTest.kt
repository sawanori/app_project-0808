package com.actionstarter.analytics

import androidx.room.Room
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.persistence.room.BehaviorLogDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Duration

/**
 * Phase 10 C3（計画書`docs/plans/phase10-behavior-log-profile.md`§3.3導出表、Step 3）。
 * [RoomAnalyticsStore]のPersonal Profile集計（[AnalyticsStore.recordStepDone]の都度の
 * 再集計トリガー・[AnalyticsStore.getProfile]の読み出し・中央値算出・保持期間の
 * timestamp述語窓）を、実in-memory Room DB（C1確立のRobolectricパターン）で検証する。
 * フェイクDAOではなく実DBを使う理由: `getRecentStepDurations`のSQL（カテゴリ・種別・
 * timestamp述語・LIMITの組み合わせ）自体がテスト対象の一部であるため。
 */
@RunWith(RobolectricTestRunner::class)
class RoomAnalyticsStoreProfileTest {

    private lateinit var db: BehaviorLogDatabase
    private lateinit var store: RoomAnalyticsStore
    private var fakeNowMillis: Long = BASE_TIME_MILLIS

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BehaviorLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomAnalyticsStore(
            behaviorEventDao = db.behaviorEventDao(),
            personalExecutionProfileDao = db.personalExecutionProfileDao(),
            nowMillis = { fakeNowMillis }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // T-P10-11: エッジケース - 対象カテゴリのイベント0件（新規ユーザー）で集計がnullを返す
    // （クラッシュしない）。
    @Test
    fun tP10_11_getProfile_noEventsForCategory_returnsNull() = runTest {
        assertNull(store.getProfile("medical"))
    }

    // T-P10-10 / 導出表1行目: 正常 - STEP_DONE×stepType=TRANSITIONの実績から
    // averageTransitionDurationへ中央値が入る。
    @Test
    fun tP10_10_transitionStepDone_populatesAverageTransitionDurationAsMedian() = runTest {
        // 中央値が分かりやすい3件: 10分・20分・30分 → 中央値20分。
        recordStepDoneAt(category = "medical", stepType = ExecutionStepType.TRANSITION, durationMinutes = 10, atMillis = BASE_TIME_MILLIS)
        recordStepDoneAt(category = "medical", stepType = ExecutionStepType.TRANSITION, durationMinutes = 30, atMillis = BASE_TIME_MILLIS + 1)
        recordStepDoneAt(category = "medical", stepType = ExecutionStepType.TRANSITION, durationMinutes = 20, atMillis = BASE_TIME_MILLIS + 2)

        val profile = store.getProfile("medical")

        assertEquals(Duration.ofMinutes(20), profile?.averageTransitionDuration)
    }

    // 導出表2行目: 正常 - STEP_DONE×stepType=PREPARATIONの実績から
    // averagePreparationDurationへ中央値が入る（TRANSITIONとは独立に集計される）。
    @Test
    fun tP10_10b_preparationStepDone_populatesAveragePreparationDurationAsMedian_independentOfTransition() = runTest {
        recordStepDoneAt(category = "medical", stepType = ExecutionStepType.TRANSITION, durationMinutes = 5, atMillis = BASE_TIME_MILLIS)
        recordStepDoneAt(category = "medical", stepType = ExecutionStepType.PREPARATION, durationMinutes = 15, atMillis = BASE_TIME_MILLIS + 1)

        val profile = store.getProfile("medical")

        assertEquals(Duration.ofMinutes(5), profile?.averageTransitionDuration)
        assertEquals(Duration.ofMinutes(15), profile?.averagePreparationDuration)
    }

    // 導出表3行目: エッジケース - 計測手段がない3フィールド（averageResponseDelay／
    // averageDepartureDelay／preferredArrivalBuffer）は、行が存在してもDuration.ZEROの
    // プレースホルダのまま（レビューCRITICAL・§13 No.3、PersonalExecutionProfileEntityの
    // KDoc「C3で解消した設計テンション」）。
    @Test
    fun tP10_10c_unmeasurableThreeFields_remainZeroPlaceholder() = runTest {
        recordStepDoneAt(category = "medical", stepType = ExecutionStepType.TRANSITION, durationMinutes = 5, atMillis = BASE_TIME_MILLIS)

        val profile = store.getProfile("medical")

        assertEquals(Duration.ZERO, profile?.averageResponseDelay)
        assertEquals(Duration.ZERO, profile?.averageDepartureDelay)
        assertEquals(Duration.ZERO, profile?.preferredArrivalBuffer)
    }

    // T-P10-13: エッジケース - 保持期間の窓（180日）を超えたイベントは中央値計算から除外される
    // （timestamp述語、レビュー§13 No.9b「ローテーション非依存」）。
    @Test
    fun tP10_13_eventsOlderThan180Days_areExcludedFromMedian() = runTest {
        val twoHundredDaysMillis = 200L * 24 * 60 * 60 * 1000
        // 180日超過（除外されるべき）
        recordStepDoneAt(
            category = "medical",
            stepType = ExecutionStepType.TRANSITION,
            durationMinutes = 999,
            atMillis = BASE_TIME_MILLIS - twoHundredDaysMillis
        )
        // 直近（含まれるべき）
        recordStepDoneAt(category = "medical", stepType = ExecutionStepType.TRANSITION, durationMinutes = 10, atMillis = BASE_TIME_MILLIS)

        val profile = store.getProfile("medical")

        assertEquals(
            "180日超過イベント(999分)が中央値計算に混入せず、直近イベント(10分)のみが" +
                "反映されるべきです(T-P10-13)",
            Duration.ofMinutes(10),
            profile?.averageTransitionDuration
        )
    }

    // T-P10-13b: エッジケース - 直近500件を超える分は除外される（件数上限、
    // 計画書§3.4「直近180日 or 直近500件の小さい方」の500件側）。600件記録し、
    // 最も古い100件（外れ値999分）が中央値計算から漏れることを確認する。
    @Test
    fun tP10_13b_moreThan500RecentEvents_onlyMostRecent500ConsideredForMedian() = runTest {
        // 最も古い100件は外れ値（999分）、以降500件は一律10分。もし件数上限が効いていなければ
        // 999分の外れ値が中央値計算に混入し20分にならない。
        repeat(100) { index ->
            recordStepDoneAt(
                category = "medical",
                stepType = ExecutionStepType.TRANSITION,
                durationMinutes = 999,
                atMillis = BASE_TIME_MILLIS + index
            )
        }
        repeat(500) { index ->
            recordStepDoneAt(
                category = "medical",
                stepType = ExecutionStepType.TRANSITION,
                durationMinutes = 10,
                atMillis = BASE_TIME_MILLIS + 100 + index
            )
        }

        val profile = store.getProfile("medical")

        assertEquals(
            "直近500件のみが中央値計算に含まれ、それより古い外れ値(999分)は" +
                "除外されるべきです(T-P10-13b)",
            Duration.ofMinutes(10),
            profile?.averageTransitionDuration
        )
    }

    // T-P10-12（集計側の前提確認）: 正常 - カテゴリが異なれば集計は独立する（medicalの実績が
    // socialのプロファイルへ混入しない）。
    @Test
    fun tP10_categoriesAreAggregatedIndependently() = runTest {
        recordStepDoneAt(category = "medical", stepType = ExecutionStepType.TRANSITION, durationMinutes = 10, atMillis = BASE_TIME_MILLIS)
        recordStepDoneAt(category = "social", stepType = ExecutionStepType.TRANSITION, durationMinutes = 50, atMillis = BASE_TIME_MILLIS)

        assertEquals(Duration.ofMinutes(10), store.getProfile("medical")?.averageTransitionDuration)
        assertEquals(Duration.ofMinutes(50), store.getProfile("social")?.averageTransitionDuration)
    }

    private suspend fun recordStepDoneAt(category: String, stepType: ExecutionStepType, durationMinutes: Long, atMillis: Long) {
        fakeNowMillis = atMillis
        store.recordStepDone(
            eventCategory = category,
            stepType = stepType.name,
            durationMs = Duration.ofMinutes(durationMinutes).toMillis()
        )
    }

    private companion object {
        const val BASE_TIME_MILLIS = 1_700_000_000_000L
    }
}
