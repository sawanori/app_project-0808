package com.actionstarter.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * probe専用・正式テストではない。
 *
 * <p>Phase 5計画書（docs/plans/phase5-notification-execution.md）§10.2 P5-P2の実機実測の
 * ための使い捨てクラス。P5-C1（JVM/Robolectric代替実測、計画書§10.3 P5-P2行）では
 * {@code ShadowService} に実機バリデーションロジックが一切存在しないことが判明し
 * （{@code setThrowInStartForeground} で手動設定した例外以外は投げない）、位置権限なしで
 * FOREGROUND_SERVICE_TYPE_LOCATIONを起動しても例外が発生しなかった（Robolectricでは
 * (a)(b)いずれも実機相当のバリデーションをシミュレートしないことの確認に留まった）。
 * 本クラスは実機（AVD actionstarter_test, API 35）上で実際に投げられる例外の完全な
 * クラス名・メッセージを実測するために存在する。
 *
 * <p>このServiceは {@code app/src/androidTest/AndroidManifest.xml} にのみ宣言されており、
 * 本番 {@code app/src/main/AndroidManifest.xml} には一切影響しない（テストAPK＝既定
 * {@code applicationId} "com.actionstarter.test" にのみマージされる）。
 *
 * <p><b>実装上の2つの回避策（いずれも実機実測で判明した制約への対応。試行錯誤の記録
 * として残す）</b>:
 * <ol>
 *   <li><b>Kotlinではなく意図的にJavaで書いている</b>: 第1試行でKotlinクラスとして実装
 *       したところ、{@code adb shell am start-foreground-service} から直接起動した際に
 *       {@code java.lang.NoClassDefFoundError: Failed resolution of: Lkotlin/jvm/internal/Intrinsics;}
 *       で即クラッシュした（実機実測で確認。dexdumpでテストAPKの全classes*.dexに
 *       kotlin-stdlib由来のクラスが1つも存在しないことを確認済み。本番APKには存在する）。</li>
 *   <li><b>androidx.core（{@code ServiceCompat}/{@code NotificationChannelCompat}等）ではなく
 *       意図的にフレームワークAPI（{@code android.app.Service#startForeground(int, Notification, int)}／
 *       {@code android.app.NotificationManager}／{@code android.app.NotificationChannel}）を直接
 *       使っている</b>: 第2試行でandroidx.core経由に書き換えたところ、同様に
 *       {@code NoClassDefFoundError: Landroidx/core/app/NotificationChannelCompat$Builder;}
 *       で即クラッシュした（実機実測で確認）。原因は(1)と同根: AGPはテストAPK
 *       （com.actionstarter.test）をビルドする際、本番APK（com.actionstarter）に既に
 *       含まれるクラス（androidx.core由来のクラスも含む）をテストAPK自体には再パッケージ
 *       しない設計になっており、これは「テストAPKは{@code am instrument}経由でターゲット
 *       アプリと同一プロセス・同一クラスローダで動く」という前提に基づく最適化である。
 *       本Serviceは{@code com.actionstarter.test}パッケージ自身の宣言のためデフォルトで
 *       {@code com.actionstarter.test}自身の独立プロセスとして起動され（ターゲットアプリと
 *       プロセスを共有しない）、本番APK側にしか無いクラスには一切到達できない。
 *       {@code android.app.*}はOSのブートクラスパス（全アプリ共通、どのAPKにも
 *       パッケージされない）なのでこの制約を受けない。API 35（&gt;=29）では
 *       {@code Service.startForeground(int, Notification, int)}の3引数版がフレームワークに
 *       直接存在するため（javapで確認済み）、Compatシムを使わずに済む。</li>
 * </ol>
 * <b>この2つの制約自体が実測の副産物であり、androidTestソースセットで本番APK専用
 * ライブラリ（androidx.core等）に依存するprobeコンポーネントを「単独プロセスで」動かす
 * 設計は本プロジェクトでは構造的に成立しないことを示している</b>（`am instrument`経由で
 * ターゲットプロセス内で動かす、またはbuild.gradle.ktsに`androidTestImplementation`を
 * 追加する、のいずれかが必要になる。後者はP5-C2レーンが編集中の共有ファイルであり
 * 本タスクのスコープ外のため未実施）。
 *
 * <p><b>起動方法</b>: {@code am instrument} 経由のJUnitではなく、
 * {@code adb shell am start-foreground-service -n com.actionstarter.test/com.actionstarter.probe.FgsTypeProbeService --es mode <MODE>}
 * で直接起動する。結果はLogcat（TAG=P5_PROBE_FGS）へ出力し、呼び出し側（adb）が
 * logcatを読んで回収する。
 *
 * <p><b>mode一覧</b>（Intent extra "mode"、{@link #EXTRA_MODE}）:
 * <ul>
 *   <li>{@code control_success}: 呼び出し時type=LOCATION（manifest宣言と一致）・
 *       位置権限あり（実測前に{@code pm grant}必要）。ハーネス自体が正しく動作している
 *       ことを確認するcontrolラン（例外が出ないことを期待）。</li>
 *   <li>{@code no_location_permission}: 呼び出し時type=LOCATION・位置権限なし
 *       （既定状態）。<b>P5-P2(a)本体</b>。</li>
 *   <li>{@code type_none}: 呼び出し時type=NONE（0）・位置権限あり（実測前に
 *       {@code pm grant}必要、manifestの{@code foregroundServiceType}宣言自体は
 *       locationのまま）。<b>P5-P2(b)本体</b>。</li>
 *   <li>{@code type_none_no_permission}: 呼び出し時type=NONE・位置権限なし。
 *       (a)(b)の交絡（「型不一致」と「位置権限欠如」のどちらが例外原因か）を
 *       切り分けるための補助データ。</li>
 * </ul>
 */
public class FgsTypeProbeService extends Service {

    public static final String EXTRA_MODE = "mode";
    private static final String TAG = "P5_PROBE_FGS";
    private static final String CHANNEL_ID = "p5_probe_channel";
    private static final int NOTIFICATION_ID = 9901;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String mode = intent != null ? intent.getStringExtra(EXTRA_MODE) : null;
        if (mode == null) {
            mode = "control_success";
        }
        int calledType = ("type_none".equals(mode) || "type_none_no_permission".equals(mode))
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;

        Log.e(TAG, "start mode=" + mode + " calledType=" + calledType + " sdkInt=" + Build.VERSION.SDK_INT);
        try {
            ensureChannel();
            Notification notification = buildNotification();
            // フレームワークAPI直接呼び出し（ServiceCompatではなく）。API35は3引数版を
            // ネイティブに持つ（javap確認済み）。
            startForeground(NOTIFICATION_ID, notification, calledType);
            Log.e(TAG, "mode=" + mode + " calledType=" + calledType + " result=SUCCESS");
        } catch (Throwable t) {
            Log.e(
                    TAG,
                    "mode=" + mode + " calledType=" + calledType + " result=EXCEPTION"
                            + " class=" + t.getClass().getName() + " message=" + t.getMessage(),
                    t
            );
        }
        // 結果送出後は常駐させず自己停止する（probe目的のみ。実FGSとして残さない。
        // 例外発生時は「startForegroundServiceで開始されたのにstartForegroundが完了
        // しなかった」状態を放置するとタイムアウトkill（ANR的挙動）を招くため、
        // 成否に関わらず即座にstopSelfする）。
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void ensureChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "P5 Probe", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("P5 FGS Probe")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }
}
