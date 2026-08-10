package com.actionstarter.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P7-C7（計画書§9.3・§14 P7-C7）。`ai/`パッケージ自身の依存境界を検証する新設ガード3本
 * （T-AIISO-5・T-AIISO-6・T-AIISO-9）。
 *
 * 既存3ガード（T-BPE-28／T-BRE-32／T-NOTIF-9）が「他パッケージが`ai/`を参照しない」ことを
 * 保証するのに対し、本ファイルは逆方向――「`ai/`自身が越えてはいけない境界を越えていないか」
 * ――を保証する（§8.1のパッケージ構造・依存方向規律の機械検証）。
 *
 * 検出方式・既知の簡略化は[IsolationGuardSupport]のクラスKDoc（ADR-0060）参照。
 */
class AiRuntimeIsolationTest {

    // T-AIISO-5: 正常系の回帰防止 - ai/配下（再帰）がcom.actionstarter.features（UI層）を
    // 一切参照しない（AI層がUIに依存しない＝headless維持）
    @Test
    fun tAiIso5_aiPackageDoesNotReferenceFeaturesLayer() {
        val aiDir = IsolationGuardSupport.resolveMainPackageDir("ai")
        val files = IsolationGuardSupport.listKotlinFilesRecursively(aiDir)
        assertTrue("ai/配下にKotlinソースファイルが見つかりません: ${aiDir.absolutePath}", files.isNotEmpty())

        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = files,
            bannedSubstrings = listOf("com.actionstarter.features")
        )

        assertTrue(
            "ai/はheadless層としてfeatures/(UI層)へ依存してはいけませんが、以下のファイルが参照して" +
                "います(T-AIISO-5): ${offenders.map { it.path }}",
            offenders.isEmpty()
        )
    }

    // T-AIISO-6: 正常系の回帰防止 - ai/配下（再帰）でネットワークAPI（java.net./
    // HttpURLConnection/URL(）またはcom.actionstarter.services.routing配下を参照できるのは
    // ai/model/ModelDownloader.ktの1ファイルのみ（ADR-0044許可リスト・Gemini G1 CRITICAL #1、
    // 自プロジェクトHTTP手段経由の迂回穴を塞ぐ。§10外部送信禁止の構造的担保）
    @Test
    fun tAiIso6_networkAccessAndRoutingWrapperReferencedOnlyFromModelDownloader() {
        val aiDir = IsolationGuardSupport.resolveMainPackageDir("ai")
        val files = IsolationGuardSupport.listKotlinFilesRecursively(aiDir)
        assertTrue("ai/配下にKotlinソースファイルが見つかりません: ${aiDir.absolutePath}", files.isNotEmpty())

        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = files,
            bannedSubstrings = listOf(
                "java.net.",
                "HttpURLConnection",
                "URL(",
                "com.actionstarter.services.routing"
            ),
            allowedFileNames = setOf(MODEL_DOWNLOADER_FILE_NAME)
        )

        assertTrue(
            "ai/配下でネットワークAPIまたはcom.actionstarter.services.routingを参照できるのは" +
                "${MODEL_DOWNLOADER_FILE_NAME}のみのはずですが、以下のファイルが参照しています" +
                "(T-AIISO-6・ADR-0044): ${offenders.map { it.path }}",
            offenders.isEmpty()
        )
    }

    // T-AIISO-9: 正常系の回帰防止 - com.google.ai.edge.litertlmをimportしてよいのは
    // ai/adapter/配下のみ。ai/の他のサブパッケージ・LocalAiGateway.kt・features/から参照したら
    // 失敗（§16「モデルは技術検証で交換可能にする」の構造担保）
    @Test
    fun tAiIso9_litertlmRuntimeReferencedOnlyFromAiAdapterPackage() {
        val aiDir = IsolationGuardSupport.resolveMainPackageDir("ai")
        val featuresDir = IsolationGuardSupport.resolveMainPackageDir("features")
        val adapterDir = File(aiDir, "adapter")
        val adapterDirPrefix = adapterDir.absolutePath + File.separator

        val aiFilesOutsideAdapter = IsolationGuardSupport.listKotlinFilesRecursively(aiDir)
            .filterNot { it.absolutePath.startsWith(adapterDirPrefix) }
        val featureFiles = IsolationGuardSupport.listKotlinFilesRecursively(featuresDir)
        assertTrue(
            "ai/配下(adapter/除く)にKotlinソースファイルが見つかりません: ${aiDir.absolutePath}",
            aiFilesOutsideAdapter.isNotEmpty()
        )
        assertTrue("features/配下にKotlinソースファイルが見つかりません: ${featuresDir.absolutePath}", featureFiles.isNotEmpty())
        assertTrue(
            "ai/adapter/配下は除外対象自体が空であってはいけません（除外ロジックの検算）: ${adapterDir.absolutePath}",
            adapterDir.isDirectory && IsolationGuardSupport.listKotlinFilesRecursively(adapterDir).isNotEmpty()
        )

        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = aiFilesOutsideAdapter + featureFiles,
            bannedSubstrings = listOf("com.google.ai.edge.litertlm")
        )

        assertTrue(
            "com.google.ai.edge.litertlmはai/adapter/配下からのみ参照可能なはずですが、以下の" +
                "ファイルが参照しています(T-AIISO-9・§16「モデルは技術検証で交換可能にする」): " +
                "${offenders.map { it.path }}",
            offenders.isEmpty()
        )
    }

    private companion object {
        const val MODEL_DOWNLOADER_FILE_NAME = "ModelDownloader.kt"
    }
}
