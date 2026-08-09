package com.actionstarter.services.calendar

import android.database.Cursor
import android.net.Uri

/**
 * L2/L3分離のシーム（計画書§8.3改訂・3層分割）。[CalendarProviderCalendarService]（L2）が
 * コンストラクタ注入で受け取る抽象であり、実装（[ContentResolverCursorSource]、L3）は
 * `ContentResolver.query`を1行呼ぶだけの薄いラッパーとする。
 *
 * この抽象を挟むことで、L2のテストがRobolectricの`ContentProvider`登録機構
 * （`Robolectric.buildContentProvider()`）に依存しなくなり、fakeの[CursorSource]実装へ
 * 差し替えるだけでJVM/Robolectric上で決定的にテストできる（旧リスクR7の緩和、計画書§16
 * R7'）。P2-C2のprobe対象P-4（Robolectric権限shadow）の対象範囲がこの分離により縮小した
 * 経緯も§8.3参照。
 *
 * シグネチャは[android.content.ContentResolver.query]と同一とし、L3実装
 * （[ContentResolverCursorSource]）が単純な委譲になるようにする。
 */
fun interface CursorSource {
    fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor?
}
