package com.actionstarter.domain

import com.actionstarter.ai.IsolationGuardSupport
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-C7（計画書§9.3・§14 P7-C7）。`domain/`（および`services/`）がAIランタイム実装へ
 * 依存していないことを検証する新設ガード2本（T-AIISO-4・T-AIISO-7）。
 *
 * 既存3ガード（T-BPE-28／T-BRE-32／T-NOTIF-9）は`planning`/`recovery`/`services.notification`
 * という特定の既存パッケージを個別に保護するのに対し、本ファイルは仕様§16
 * 「特定モデル依存コードをUIやDomain層へ入れない」をより広く、Domain層全体（および
 * [tAiIso7_domainAndServicesPackagesDoNotReferenceLitertlmRuntime]では`services/`全体）に
 * 対して機械検証する。
 *
 * 検出方式・既知の簡略化は[com.actionstarter.ai.IsolationGuardSupport]のクラスKDoc
 * （ADR-0060）参照。
 */
class DomainRuntimeIsolationTest {

    // T-AIISO-4: 正常系の回帰防止 - domain/配下（再帰）がcom.actionstarter.ai /
    // com.actionstarter.llm / LocalLanguageModelのいずれも参照しない（§16「Domain層へ入れない」の
    // 機械検証）
    @Test
    fun tAiIso4_domainPackageDoesNotReferenceAiOrLlmRuntime() {
        val domainDir = IsolationGuardSupport.resolveMainPackageDir("domain")
        val files = IsolationGuardSupport.listKotlinFilesRecursively(domainDir)
        assertTrue("domain/配下にKotlinソースファイルが見つかりません: ${domainDir.absolutePath}", files.isNotEmpty())

        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = files,
            bannedSubstrings = listOf("com.actionstarter.ai", "com.actionstarter.llm", "LocalLanguageModel")
        )

        assertTrue(
            "domain/はcom.actionstarter.ai・com.actionstarter.llm・LocalLanguageModelのいずれも" +
                "参照してはいけませんが(§16)、以下のファイルが参照しています(T-AIISO-4): " +
                "${offenders.map { it.path }}",
            offenders.isEmpty()
        )
    }

    // T-AIISO-7: 正常系の回帰防止 - domain/およびservices/配下（再帰、双方）が
    // com.google.ai.edge.litertlm（ランタイム実装）を一切参照しない（ランタイムがDomain/Serviceへ
    // 漏れない）
    @Test
    fun tAiIso7_domainAndServicesPackagesDoNotReferenceLitertlmRuntime() {
        val domainDir = IsolationGuardSupport.resolveMainPackageDir("domain")
        val servicesDir = IsolationGuardSupport.resolveMainPackageDir("services")

        val domainFiles = IsolationGuardSupport.listKotlinFilesRecursively(domainDir)
        val serviceFiles = IsolationGuardSupport.listKotlinFilesRecursively(servicesDir)
        assertTrue("domain/配下にKotlinソースファイルが見つかりません: ${domainDir.absolutePath}", domainFiles.isNotEmpty())
        assertTrue("services/配下にKotlinソースファイルが見つかりません: ${servicesDir.absolutePath}", serviceFiles.isNotEmpty())

        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = domainFiles + serviceFiles,
            bannedSubstrings = listOf("com.google.ai.edge.litertlm")
        )

        assertTrue(
            "domain/およびservices/はランタイム実装(com.google.ai.edge.litertlm)を直接参照して" +
                "はいけませんが(§16、ランタイムがDomain/Serviceへ漏れない)、以下のファイルが参照" +
                "しています(T-AIISO-7): ${offenders.map { it.path }}",
            offenders.isEmpty()
        )
    }
}
