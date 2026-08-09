package com.actionstarter.services.calendar

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri

/**
 * [CursorSource]のL3実装（計画書§8.3改訂・3層分割）。`contentResolver.query`を1行呼ぶだけの
 * 薄いラッパーであり、フィルタ・写像等のロジックは一切持たない。ロジックを持たないため、
 * テストはinstrumented（`src/androidTest`）のみで行い、JVM/Robolectricではテストしない
 * （計画書§8.3・§10.1）。
 *
 * DI結線（`AppContainer`からの生成）は本サイクル（P2-C2）では行わない（P2-C5で結線する）。
 * 本体は本サイクル（P2-C2、契約scaffold・TDD例外）では`TODO()`とし、P2-C4で実装する。
 */
class ContentResolverCursorSource(
    private val contentResolver: ContentResolver
) : CursorSource {
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        TODO("P2-C4で実装")
    }
}
