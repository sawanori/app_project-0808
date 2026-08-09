package com.actionstarter.persistence

import com.actionstarter.services.notification.NotificationKind
import java.util.UUID

/**
 * F55契約（計画書§6.1・§7.3、S-1裁定）。boot再登録の元データを持つ最小レコードの
 * 永続化契約。**PIIゼロ**（§58/§60、T-STORE-7）——イベントタイトル・住所・座標を持たない。
 * 通知本文は`semanticId → string resource`と時刻フォーマットのみから組み立てる
 * （PII非保持を正当化する根拠でもある。SharedPreferencesはアプリ専用ディレクトリだが
 * 平文であるため）。
 *
 * [nextRequestCode]はPendingIntent一意性の規約（計画書§7.3）が要求する単調増加カウンタ。
 * `requestCode`をハッシュに賭けず本ストアが払い出すことで、(a)衝突がありえない、
 * (b)再起動後も以前登録した正確なPendingIntentをcancelできる（古いアラームの残存を構造的に
 * 防ぐ）、という2条件を満たす。
 *
 * [loadAll]は破棄したレコード件数を結果型で表現する（エラー&レスキューマップ#12
 * 「パース失敗と版不一致はいずれもレコードを破棄し、結果型で『破棄した』ことを返す。
 * 空を返して沈黙しない」）。空ストアは`discardedCount = 0`の空リストを返す（T-STORE-2）。
 *
 * 実装は[SharedPreferencesExecutionScheduleStore]（P5-C1では宣言のみ、P5-C3で実装）。
 * Phase 10でRoom実装へ差し替える経路として、本契約は不変のまま維持する（R-6）。
 */
interface ExecutionScheduleStore {
    /** 単調増加する`requestCode`を1つ払い出す。 */
    fun nextRequestCode(): Int

    /** [record]を保存する。同一`planId`の既存レコードは置換する。 */
    fun save(record: ExecutionScheduleRecord)

    /** 全レコードを読み出す。破損・版不一致のレコードは破棄し件数を報告する（信頼境界）。 */
    fun loadAll(): ExecutionScheduleLoadResult

    /** [planId]のレコードを削除する（Execution完了・中断時、いずれの経路でも呼ぶ。T-STORE-6）。 */
    fun clear(planId: String)
}

/** [ExecutionScheduleStore.loadAll]の戻り値。[discardedCount]は破損・版不一致で破棄した件数。 */
data class ExecutionScheduleLoadResult(
    val records: List<ExecutionScheduleRecord>,
    val discardedCount: Int
)

/**
 * 計画書§7.3。S-1のレコード定義（永続化される最小データ、PIIゼロ）。[planId]は
 * `event.id`由来（ADR-0017の決定的id生成が前提。stepIdとの突き合わせにも使う）。
 */
data class ExecutionScheduleRecord(
    val schemaVersion: Int,
    val planId: String,
    val eventStartEpochMillis: Long,
    val estimatedArrivalEpochMillis: Long,
    val triggers: List<Trigger>
) {
    companion object {
        /** 読み出し時に本値と不一致なら破棄する（信頼境界、T-STORE-3）。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * 計画書§7.3。個々のアラームトリガー。[stepId]はADR-0017の決定的UUID生成に依存し、
 * 再planning後のPlanのステップと突き合わせる前提（T-STORE-8で依存関係をロックする）。
 * [fired]は取り逃し判定（S-9）に用いる。
 */
data class Trigger(
    val kind: NotificationKind,
    val stepId: UUID,
    val semanticId: String,
    val triggerAtEpochMillis: Long,
    val requestCode: Int,
    val fired: Boolean
)
