package com.actionstarter.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.services.calendar.CalendarService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F19（構成差し替え）— AppContainer（計画書§10.2、T-DI-1／T-DI-2）。
 *
 * ADR-0014（Hilt導入のPhase 5延期確定。P2-C1プローブP-H2の確定失敗を受けた裁定）により、
 * 単一Factory集約点はHiltの`AppModule`/`EntryPointAccessors`ではなく引き続き
 * [AppContainer]（手動DI）である（裁定B2の保護条件、計画書§8.4）。もとの計画書§10.2の
 * T-DI-1はAppModule/EntryPointAccessors経由の解決を想定していたが、ADR-0014により対象を
 * AppContainerへ読み替える（担当プロンプトの指示どおり）。
 *
 * **§18解消（Fable 5裁定2026-08-09、統合修正サイクル）**: [AppContainer]のDI設計が
 * `context: Context`必須引数へ確定した（`AppContainer.kt`該当KDoc参照）ことに追随し、
 * 本テストは[ApplicationProvider.getApplicationContext]が返すRobolectric環境の実Contextで
 * [AppContainer]を構築する。これに伴い本クラスは`@RunWith(AndroidJUnit4::class)`で
 * Robolectric上で実行する（従来の「コンストラクタ引数なし・プレーンJUnit4テスト」という
 * 前提は解消済み）。
 */
@RunWith(AndroidJUnit4::class)
class AppContainerTest {

    // T-DI-1: 正常系 - AppContainer.calendarServiceへのアクセスがCalendarService型の
    // インスタンスを返す（単一Factory集約点AppContainerがCalendarServiceを解決できることの
    // 検証）。
    @Test
    fun calendarService_whenAccessed_returnsCalendarServiceInstance() {
        // Fable 5承認2026-08-09: DI設計のContext必須化に追随
        val container = AppContainer(ApplicationProvider.getApplicationContext())

        val service: CalendarService = container.calendarService

        assertNotNull(service)
    }

    // T-DI-2: 異常系 - src/mainソース走査により`com.actionstarter.mock.MockEventSource`
    // （mock/MockEventSource.kt）が存在しないことを検証する（Phase 1計画書§8 U6の履行、F19）。
    //
    // 【重要】現状はまだ削除されていない（P2-C6／旧P2-C5の統合サイクルで削除予定）ため、
    // 本テストは意図的にfailing（Red）になる。このRedは実装の不備を示すものではなく、
    // 「削除タスクがまだ実行されていない」ことを示す既知のRedであり、P2-C6で
    // `mock/MockEventSource.kt`が削除された時点でGreenに転じる想定である
    // （担当プロンプト「T-DI-2は現状存在するためfailing=C5で解消されるRed」の指示どおり）。
    //
    // `com.actionstarter.di.AppContainer`自体はADR-0014によりAppModuleへ吸収されず存続する
    // ため、非存在の検証対象から除外する（計画書§10.2 T-DI-2注記）。
    @Test
    fun mockEventSourceKtFile_doesNotExistUnderSrcMain() {
        val mockPackageDir = resolveMockPackageDir()
        val target = File(mockPackageDir, "MockEventSource.kt")

        assertFalse(
            "mock/MockEventSource.ktがsrc/mainに存在しています（U6未履行、P2-C6で解消予定）: " +
                target.absolutePath,
            target.isFile
        )
    }

    /**
     * `mock`パッケージディレクトリ自体（`MockPlanFactory.kt`等、削除対象外のファイルも
     * 置かれている）を解決する。working directoryがGradle実行コンテキストにより
     * `app/`直下・リポジトリルート・その祖先のいずれにもなり得るため、
     * [com.actionstarter.i18n.StringResourceParityTest.resolveStringsXml]と同じ多段fallback
     * 方式を踏襲する。ディレクトリ自体が見つからない場合は「MockEventSource.ktが存在しない」
     * という誤ったGreen判定（パス解決失敗によるサイレントな偽陽性）を防ぐため、
     * 例外で即座に失敗させる。
     */
    private fun resolveMockPackageDir(): File {
        val relative = "src/main/java/com/actionstarter/mock"

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
            "mockパッケージディレクトリが見つかりません。相対パス '$relative' を作業ディレクトリ " +
                "'${System.getProperty("user.dir")}' およびその祖先から探索しましたが解決できませんでした。"
        )
    }
}
