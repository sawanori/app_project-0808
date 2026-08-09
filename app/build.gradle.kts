import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

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

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

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
