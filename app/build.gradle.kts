import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Phase 3 P3-C1（計画書§6.4#2・§7.3・F29）: local.properties（gitignore済み・.gitignoreで
// 実測確認）からMAPS_ROUTES_API_KEYを読み取り、buildConfigFieldへ渡す。ファイル自体が
// 存在しない場合・キー行が無い場合のいずれも空文字へフォールバックし、Gradle構成が
// 失敗しないようにする（T-CFG-3の前提）。
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val routesApiKey: String = localProperties.getProperty("MAPS_ROUTES_API_KEY") ?: ""

android {
    namespace = "com.actionstarter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.actionstarter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // C6: androidTest（`src/androidTest`）実行用ランナー。`app/src/androidTest/java/com/
        // actionstarter/e2e/MainUxFlowTest.kt`のコンパイル・実行（G4-E、KVM解決後）に必要。
        // 未設定だったため本サイクルで追加（計画書§15 C6行の必須項目）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phase 3 P3-C1（計画書§6.4#2・§7.3・F29）: キー未設定時は""（空文字）。
        // AppContainer側（P3-C6）がisEmpty()でUnconfiguredRoutingServiceへ縮退させる。
        buildConfigField("String", "ROUTES_API_KEY", "\"$routesApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Enables BuildConfig generation (計画書§10.4). Prepares the ground for the
        // ExecutionScreen debug-build gate (`execution_simulate_delay_debug_button`) to be
        // switched from the interim `ApplicationInfo.FLAG_DEBUGGABLE` check to
        // `BuildConfig.DEBUG`; that Kotlin-side replacement is C6 (ui-implementer) scope.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// C6追加修正（ADR-0013）: release変種のホスト側unit testを無効化する。releaseはComponentActivity非宣言の
// マージ済みManifest（ui-test-manifestはdebug専用）とBuildConfig.DEBUG=false（§10.4 U4裁定）により
// UIテストが構造的にpassし得ないため、`:app:testDebugUnitTest`のみを検証面とする（計画書§11.1）。
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    // Phase 3 P3-C1（計画書§6.4#2・§7.1・F22）: 現在地取得。AARメタデータ実測済み
    // （gradle/libs.versions.toml参照）。
    implementation(libs.google.play.services.location)
    // Phase 7 P7-C0/C1（計画書§14・§0・U-2・F85）: LiteRT-LM Kotlin API。P7-C0はraw座標文字列で
    // Go/No-Go実測（GO判定・コミット8967693）。P7-C1でgradle/libs.versions.tomlのバージョン
    // カタログへ正式化した（計画書§7.2フットプリント）。廃止系`litertlm`ではなく
    // `litertlm-android`（現行AAR）を使用。バージョンは0.15.0に固定（R-2）。
    implementation(libs.google.ai.edge.litertlm.android)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // Phase 7 P7-C2（計画書§7.2・§12.1・U-11・Gemini G1 CRITICAL #5）: SchemaValidatorTest等が
    // Robolectric非経由（E1・純JVM）でorg.jsonの実クラス（Android同梱スタブではなく）を使える
    // ようにする。本番実装（P7-C3）はAndroid SDK同梱のorg.jsonを使い続け、この依存はテスト
    // スコープのみに限定する。
    testImplementation(libs.org.json)

    // C6: `src/androidTest`（instrumented E2E、`e2e/MainUxFlowTest.kt`。実行はG4-E待ち）の
    // コンパイルに必要な依存。C3bまで未解決だった申し送り事項（計画書§15 C6行）を解消する。
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // P2-C8fix: OS権限ダイアログ（com.google.android.permissioncontroller、Composeの外側）を
    // 操作するために追加（CalendarPermissionDeniedTest、裁定B14実装の一部）。追加前に
    // AARメタデータのminCompileSdkを実測確認済み（minCompileSdk=34 <= compileSdk 35、
    // ADR-0011の教訓に基づく事前検証）。
    androidTestImplementation(libs.androidx.test.uiautomator)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
