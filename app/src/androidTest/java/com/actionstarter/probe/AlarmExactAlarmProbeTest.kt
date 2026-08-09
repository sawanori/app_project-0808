package com.actionstarter.probe

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.AlarmManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * probe専用・正式テストではない。
 *
 * Phase 5計画書（docs/plans/phase5-notification-execution.md）§10.2 P5-P1の
 * 実機実測のための使い捨てクラス。P5-C1時点ではエミュレータ使用が禁止されており
 * 「延期（エミュレータ必要）。未実測」のまま残っていた項目（計画書§10.3 P5-P1行）を、
 * 実機（AVD actionstarter_test, API 35）で埋める。
 *
 * 測定対象:
 * 1. `targetContext`（`com.actionstarter`、本番`app/src/main/AndroidManifest.xml`は
 *    このタスクで無改変）における`AlarmManagerCompat.canScheduleExactAlarms()`の
 *    初期値＝実機での「本当の」現状値。
 * 2. `context`（instrumentationの自プロセス、テストAPK`com.actionstarter.test`。
 *    `app/src/androidTest/AndroidManifest.xml`側の宣言を実測時に一時的に書き換えて
 *    SCHEDULE_EXACT_ALARM／USE_EXACT_ALARM有無の3バリアントを比較する。結果は
 *    build/agent-logs/p5-probes-device.md参照。本ファイル自体は最後に「どちらも
 *    宣言しない」baseline版に復元した状態でコンパイルされている）。
 * 3. `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`のintentがresolveActivityで
 *    解決できるか（package data URIあり／なし両方）。
 *
 * 実行方法: `adb shell am instrument -w -e class com.actionstarter.probe.AlarmExactAlarmProbeTest com.actionstarter.test/androidx.test.runner.AndroidJUnitRunner`
 * （`@Ignore`があるため、通常の`connectedDebugAndroidTest`一括実行やG4-Eには含まれない。
 * 再実測する場合は`@Ignore`を外すか、`-e class`で本クラスを明示指定して実行する）。
 * 結果はLogcat（TAG=P5_PROBE_ALARM）へ出力する。
 */
@RunWith(AndroidJUnit4::class)
@Ignore(
    "probe専用（P5-P1実測、docs/plans/phase5-notification-execution.md §10.2）。" +
        "connectedDebugAndroidTest一括実行やG4-Eの対象に含めないため意図的にIgnore。" +
        "実測結果はbuild/agent-logs/p5-probes-device.mdへ転記済み。再実行する場合は" +
        "@Ignoreを外すか `-e class` で本クラスを直接指定する。"
)
class AlarmExactAlarmProbeTest {

    @Test
    fun probeCanScheduleExactAlarmsAndSettingsIntentResolution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext // com.actionstarter（本番、無改変）
        val testContext = instrumentation.context // com.actionstarter.test（androidTest宣言比較用）

        logAlarmState("target(com.actionstarter,unmodified-main-manifest)", targetContext)
        logAlarmState("test(com.actionstarter.test,androidTest-manifest-variant)", testContext)

        // Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM の解決可否（package指定あり/なし両方）
        val intentWithPackage = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:" + targetContext.packageName))
        val resolvedWithPackage = intentWithPackage.resolveActivity(targetContext.packageManager)
        Log.e(TAG, "intentResolve dataUri=package:${targetContext.packageName} resolved=$resolvedWithPackage")

        val intentNoPackage = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        val resolvedNoPackage = intentNoPackage.resolveActivity(targetContext.packageManager)
        Log.e(TAG, "intentResolve dataUri=none resolved=$resolvedNoPackage")

        Log.e(
            TAG,
            "sdkInt=${Build.VERSION.SDK_INT} " +
                "targetPkg=${targetContext.packageName} targetPkgTargetSdk=${targetContext.applicationInfo.targetSdkVersion} " +
                "testPkg=${testContext.packageName} testPkgTargetSdk=${testContext.applicationInfo.targetSdkVersion}"
        )
    }

    private fun logAlarmState(label: String, context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: error("AlarmManager service unavailable for $label")
        val canSchedule = AlarmManagerCompat.canScheduleExactAlarms(alarmManager)
        Log.e(TAG, "canScheduleExactAlarms label=$label package=${context.packageName} value=$canSchedule")
    }

    private companion object {
        private const val TAG = "P5_PROBE_ALARM"
    }
}
