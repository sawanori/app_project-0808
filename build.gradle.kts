plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Phase 10 C1（計画書`docs/plans/phase10-behavior-log-profile.md`§3.1）: Room用KSP。
    // 本プロジェクト初のアノテーション処理系プラグイン。
    alias(libs.plugins.ksp) apply false
}
