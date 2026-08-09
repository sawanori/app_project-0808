package com.actionstarter.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.planning.BasicPlanningEngine
import com.actionstarter.recovery.BasicRecoveryEngine
import com.actionstarter.services.calendar.CalendarService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
 *
 * **P4-C2追補（`docs/plans/phase4-basic-engine.md`§8.2 F40構成差し替え、T-P4DI-1／2）**:
 * [mockEventSourceKtFile_doesNotExistUnderSrcMain]（T-DI-2）と同じ「単一Factory集約点＋
 * src/mainソース走査」という本クラスの既存スコープに、Phase 4の構成差し替え検証
 * （`planningEngine`の実装型・`MockPlanFactory.kt`の非存在）を追加する。[resolveMockPackageDir]
 * を再利用する。
 *
 * **P6-C2追補（`docs/plans/phase6-recovery-basic.md`§8.7 F78構成差し替え、T-P6DI-1／2、
 * §4.2 U-6で承認済みの既存テスト変更）**: T-P4DI-1/2と同型の観点をPhase 6の`recoveryEngine`
 * （`MockRecoveryFactory` → `BasicRecoveryEngine`、P6-C5統合ウィンドウで差し替え予定）へ拡張する。
 * [tP6Di1_recoveryEngine_isBasicRecoveryEngineType]は本ファイル作成時点（P6-C2）で
 * `AppContainer.recoveryEngine`が依然`MockRecoveryFactory()`のままのためRedになるのが正しい。
 * [tP6Di2_mockRecoveryFactoryKtFile_doesNotExistUnderSrcMain]は`mock/MockRecoveryFactory.kt`が
 * P6-C5統合ウィンドウでの削除待ちのため存在しており、同じくRedになるのが正しい。
 * 計画書§9エラーマップ#20（`mock/`ディレクトリ自体が消滅した場合の[resolveMockPackageDir]の
 * hard fail回避）はP6-C5で`resolveMockPackageDir`自体を修正する対象であり、本サイクル（P6-C2）
 * では現行の`resolveMockPackageDir`をそのまま再利用する（差し戻し事項は完了報告を参照）。
 *
 * **P6-C5追補（統合ウィンドウ、`docs/plans/phase6-recovery-basic.md`§10 P6-C5行）**:
 * `AppContainer.recoveryEngine`を`BasicRecoveryEngine(...)`へ差替え、`mock/MockRecoveryFactory.kt`
 * を削除したことにより、[tP6Di1_recoveryEngine_isBasicRecoveryEngineType]・
 * [tP6Di2_mockRecoveryFactoryKtFile_doesNotExistUnderSrcMain]とも本サイクルからGreenへ反転する
 * （プロジェクト初の全JVMスイート完全Greenの一部）。[resolveMockPackageDir]も
 * §9エラーマップ#20どおり修正済み（本メソッドのKDoc参照）。
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

    // T-P4DI-1（計画書§8.2 F40構成差し替え、`docs/plans/phase4-basic-engine.md`）:
    // 正常系 - AppContainer.planningEngineの型がBasicPlanningEngineである
    // （MockPlanFactoryではない）。現状（P4-C2時点）AppContainer.planningEngineは
    // 依然MockPlanFactory()のまま（P4-C5統合ウィンドウで差し替え予定、計画書§7.2手順3）
    // のためRedになるのが正しい。
    @Test
    fun tP4Di1_planningEngine_isBasicPlanningEngineType() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())

        val engine = container.planningEngine

        assertTrue(
            "AppContainer.planningEngineの型がBasicPlanningEngineではありません" +
                "（実際: ${engine::class.qualifiedName}）。P4-C5でMockPlanFactory()から" +
                "BasicPlanningEngine()への差し替えが必要です。",
            engine is BasicPlanningEngine
        )
    }

    // T-P4DI-2（計画書§8.2 F40構成差し替え）: 異常系 -
    // com.actionstarter.mock.MockPlanFactoryがsrc/mainに存在しないこと（削除の走査確認）。
    // ディレクトリ非存在ではなくクラス非存在で検証する安全な実装とする（R-6、
    // mockEventSourceKtFile_doesNotExistUnderSrcMainと同じ方式）。現状（P4-C2時点）
    // mock/MockPlanFactory.ktはP4-C5統合ウィンドウでの削除待ちのため存在しており、
    // 本テストはRedになるのが正しい。
    @Test
    fun tP4Di2_mockPlanFactoryKtFile_doesNotExistUnderSrcMain() {
        val mockPackageDir = resolveMockPackageDir()
        val target = File(mockPackageDir, "MockPlanFactory.kt")

        assertFalse(
            "mock/MockPlanFactory.ktがsrc/mainに存在しています（P4-C5統合ウィンドウ未履行のため " +
                "現時点ではRedが正しい）: " + target.absolutePath,
            target.isFile
        )
    }

    // T-P6DI-1（計画書§8.7 F78構成差し替え、`docs/plans/phase6-recovery-basic.md`）:
    // 正常系 - AppContainer.recoveryEngineの型がBasicRecoveryEngineである（MockRecoveryFactoryで
    // はない）。現状（P6-C2時点）AppContainer.recoveryEngineは依然MockRecoveryFactory()のまま
    // （P6-C5統合ウィンドウで差し替え予定）のためRedになるのが正しい。
    @Test
    fun tP6Di1_recoveryEngine_isBasicRecoveryEngineType() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())

        val engine = container.recoveryEngine

        assertTrue(
            "AppContainer.recoveryEngineの型がBasicRecoveryEngineではありません" +
                "（実際: ${engine::class.qualifiedName}）。P6-C5でMockRecoveryFactory()から" +
                "BasicRecoveryEngine(...)への差し替えが必要です。",
            engine is BasicRecoveryEngine
        )
    }

    // T-P6DI-2（計画書§8.7 F78構成差し替え）: 異常系 -
    // com.actionstarter.mock.MockRecoveryFactoryがsrc/mainに存在しないこと（削除の走査確認）。
    // T-P4DI-2と同じ方式（ディレクトリ非存在ではなくクラス非存在で検証）。現状（P6-C2時点）
    // mock/MockRecoveryFactory.ktはP6-C5統合ウィンドウでの削除待ちのため存在しており、
    // 本テストはRedになるのが正しい。
    @Test
    fun tP6Di2_mockRecoveryFactoryKtFile_doesNotExistUnderSrcMain() {
        val mockPackageDir = resolveMockPackageDir()
        val target = File(mockPackageDir, "MockRecoveryFactory.kt")

        assertFalse(
            "mock/MockRecoveryFactory.ktがsrc/mainに存在しています（P6-C5統合ウィンドウ未履行の" +
                "ため現時点ではRedが正しい）: " + target.absolutePath,
            target.isFile
        )
    }

    /**
     * `mock`パッケージディレクトリ自体を解決する。working directoryがGradle実行コンテキストに
     * より`app/`直下・リポジトリルート・その祖先のいずれにもなり得るため、
     * [com.actionstarter.i18n.StringResourceParityTest.resolveStringsXml]と同じ多段fallback
     * 方式を踏襲する。
     *
     * **P6-C5修正（計画書§9エラーマップ#20、Fable 5裁定・§4.2 U-6で承認済み）**:
     * `mock/MockRecoveryFactory.kt`（`mock/`配下の最後の1ファイル）の削除に伴い`mock/`
     * ディレクトリ自体が消滅する（副作用、計画書§6.3）。旧実装は3候補いずれも`isDirectory`が
     * 偽なら`error()`でhard failしていたが、これは`mock/`が正しく消滅した場合（T-P6DI-2が
     * 検証する状態そのもの）まで誤ってテスト失敗にしてしまう。ディレクトリ非存在それ自体を
     * 「パス解決の失敗（誤ったGreen化のリスク）」と区別できないためだった。
     * そこで、`mock/`ではなく**必ず存在し続ける親パッケージ`com/actionstarter`**を同じ
     * 3段fallbackで解決し、その子として`mock`という**実在しないFileハンドル**を返す方式へ
     * 変更した（`java.io.File`の`isFile`/`isDirectory`は対象が存在しなくても例外を投げず
     * `false`を返すため、呼び出し側（[mockEventSourceKtFile_doesNotExistUnderSrcMain]等）は
     * 引き続き正しく`false`＝非存在を観測できる）。親パッケージには他の多数のファイルが
     * 存在するため、3候補すべてで解決できない場合は真にworking directory解決自体が
     * 破綻している状況であり、当初の設計意図どおり`error()`で即座に失敗させる
     * （パス解決失敗によるサイレントな偽陽性の防止、という元の目的は維持する）。
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

        // mock/自体が(正しく)消滅している可能性があるため、必ず存在するはずの親パッケージ
        // ディレクトリを同じ3段fallbackで解決し、その子として非存在のFileハンドルを返す。
        val parentRelative = "src/main/java/com/actionstarter"

        val directParent = File(parentRelative)
        if (directParent.isDirectory) return File(directParent, "mock")

        val parentFromRepoRoot = File("app", parentRelative)
        if (parentFromRepoRoot.isDirectory) return File(parentFromRepoRoot, "mock")

        var parentDir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (parentDir != null) {
            val candidate = File(parentDir, "app/$parentRelative")
            if (candidate.isDirectory) return File(candidate, "mock")
            parentDir = parentDir.parentFile
        }

        error(
            "com.actionstarterパッケージディレクトリ（mockの親）が見つかりません。相対パス " +
                "'$parentRelative' を作業ディレクトリ '${System.getProperty("user.dir")}' " +
                "およびその祖先から探索しましたが解決できませんでした。"
        )
    }
}
