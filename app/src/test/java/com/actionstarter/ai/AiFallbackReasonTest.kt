package com.actionstarter.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-C2（計画書§8.6・§14 P7-C1完了記録）。[AiFallbackReason]の回帰ロックテスト。
 *
 * **born-green（本タスクの制約により意図的にGreenとして追加）**: [AiFallbackReason]は
 * P7-C1で全13値が既に確定・実装済み（`TODO()`を含まない完成した`enum class`）であるため、
 * 本テストはRedにならない。「契約が既に確定している型・enum等はborn-green回帰ガードとして
 * 許容する」という本タスクの制約に基づき、将来の変更で値が誤って増減した場合に検知する
 * 回帰ガードとして追加する。
 *
 * **Phase 9での契約変更（計画書`docs/plans/phase9-recovery-ai.md`§9決定2、ADR-0063想定）**:
 * `generateRecovery`の実装完了に伴い`NOT_IMPLEMENTED_IN_PHASE7`を削除し13値→12値へ更新した
 * （既存呼び出し元は本値を参照していなかったため削除自体に伴う波及はない、
 * `grep`実測で確認済み）。
 */
class AiFallbackReasonTest {

    // 正常系: §8.6発動条件表・T-GW-1〜18が要求する12値が過不足なく揃っている
    // （Phase 9でNOT_IMPLEMENTED_IN_PHASE7を削除、13→12値）
    @Test
    fun allTwelveConfirmedValues_arePresentExactly() {
        val expected = setOf(
            "AI_DISABLED",
            "MODEL_NOT_INSTALLED",
            "UNSUPPORTED_DEVICE",
            "UNSUPPORTED_ABI",
            "INSUFFICIENT_STORAGE",
            "MODEL_LOAD_FAILED",
            "MODEL_CORRUPTED",
            "OUT_OF_MEMORY_PREVENTED",
            "OUT_OF_MEMORY",
            "TIMEOUT",
            "SCHEMA_INVALID",
            "UNKNOWN"
        )

        val actual = AiFallbackReason.entries.map { it.name }.toSet()

        assertEquals(expected, actual)
    }

    // エッジ: CANCELLED／DL失敗系／BUSYが意図的に含まれない（AiFallbackReason.ktのKDoc「意図的に
    // 含めない値」参照。§8.7原則3・T-GW-13、T-GW-15のMutex直列化採用、ModelDownloadResult側の関心事）
    @Test
    fun cancelledDownloadAndBusyReasons_areDeliberatelyExcluded() {
        val names = AiFallbackReason.entries.map { it.name }.toSet()

        assertTrue("CANCELLEDは§8.7原則3により意図的に含めない設計のはずです", "CANCELLED" !in names)
        assertTrue("BUSYはT-GW-15のMutex直列化採用により意図的に含めない設計のはずです", "BUSY" !in names)
        assertTrue(
            "DL失敗系はModelDownloadResult側の関心事のため含めない設計のはずです",
            names.none { it.contains("DOWNLOAD") }
        )
    }
}
