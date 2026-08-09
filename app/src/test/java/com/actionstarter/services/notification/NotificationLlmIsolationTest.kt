package com.actionstarter.services.notification

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-NOTIF-9（計画書§8.2「F49/F52 — AndroidNotificationService」、E1区分・端末不要、
 * 全10件中の唯一のE1ケース）。
 *
 * `services/notification/`パッケージ配下の全ソースファイルが`com.actionstarter.ai`パッケージ・
 * `LocalLanguageModel`のいずれも参照しないことをソーステキスト走査で検証する構造ガード
 * （通知発火はKotlin側の決定的処理のみで構成される、仕様§15）。
 * [com.actionstarter.planning.PlanningLlmIsolationTest]（T-BPE-28）の先例と同一方針。
 *
 * [resolveNotificationPackageDir]は同テストの`resolvePlanningPackageDir`と同じ多段fallback
 * 方式でパッケージディレクトリを解決する（working directoryがGradle実行コンテキストにより
 * `app/`直下・リポジトリルート・その祖先のいずれにもなり得るため）。
 *
 * **本テストの性質に関する留意（PlanningLlmIsolationTestと同型）**: `services/notification/`
 * 配下の現行ソース（P5-C1契約scaffold、全ファイルKDoc確認済み）は本ファイル作成時点で既に
 * `com.actionstarter.ai`・`LocalLanguageModel`のいずれも参照していないため、他のP5-C2ケース
 * （TODO()起因で確実にRedになる）とは異なり、本ケースは作成時点からGreenの見込みが高い。
 * これは仕様不備ではなく、本ケースの目的が「現状の非参照を証明すること」ではなく
 * 「将来の変更で誤って参照を混入させてしまうことを防ぐ回帰ガード」であるため。
 */
class NotificationLlmIsolationTest {

    // T-NOTIF-9: 回帰ガード - services/notification/配下のソースがcom.actionstarter.ai /
    // LocalLanguageModelを一切参照しない（構造ガード、§15）
    @Test
    fun tNotif9_notificationPackageSources_doNotReferenceAiPackageOrLocalLanguageModel() {
        val notificationDir = resolveNotificationPackageDir()
        val kotlinFiles = notificationDir.listFiles { file -> file.isFile && file.extension == "kt" }

        assertTrue(
            "services/notification/パッケージディレクトリにKotlinソースファイルが見つかりません: ${notificationDir.absolutePath}",
            kotlinFiles != null && kotlinFiles.isNotEmpty()
        )

        val offendingFiles = kotlinFiles!!.filter { file ->
            val text = file.readText()
            text.contains("com.actionstarter.ai") || text.contains("LocalLanguageModel")
        }

        assertTrue(
            "services/notification/配下のソースがcom.actionstarter.aiまたはLocalLanguageModelを参照しています" +
                "（仕様§15の決定的処理原則違反）: ${offendingFiles.map { it.name }}",
            offendingFiles.isEmpty()
        )
    }

    /**
     * `services/notification/`パッケージディレクトリ（`NotificationService.kt`等が置かれている
     * `src/main/java/com/actionstarter/services/notification`）を解決する。ディレクトリ自体が
     * 見つからない場合は「参照がない」という誤ったGreen判定（パス解決失敗によるサイレントな
     * 偽陽性）を防ぐため、例外で即座に失敗させる。
     */
    private fun resolveNotificationPackageDir(): File {
        val relative = "src/main/java/com/actionstarter/services/notification"

        val direct = File(relative)
        if (direct.isDirectory) return direct

        val fromRepoRoot = File("app", relative)
        if (fromRepoRoot.isDirectory) return fromRepoRoot

        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/$relative")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }

        error(
            "services/notification/パッケージディレクトリが見つかりません。相対パス '$relative' を作業ディレクトリ " +
                "'${System.getProperty("user.dir")}' およびその祖先から探索しましたが解決できませんでした。"
        )
    }
}
