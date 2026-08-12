package com.actionstarter.di

import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.actionstarter.ActionStarterApplication
import com.actionstarter.BuildConfig
import com.actionstarter.ai.AiPreferences
import com.actionstarter.ai.AiPreferencesImpl
import com.actionstarter.ai.LocalAiGateway
import com.actionstarter.ai.LocalAiPlanContextualizer
import com.actionstarter.ai.LocalAiRecoveryContextualizer
import com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel
import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceCapabilityImpl
import com.actionstarter.ai.model.ModelDownloader
import com.actionstarter.ai.model.ModelSelector
import com.actionstarter.ai.model.ModelSelectorImpl
import com.actionstarter.ai.model.ModelStorage
import com.actionstarter.ai.model.ModelStorageImpl
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.analytics.AnalyticsStore
import com.actionstarter.analytics.RoomAnalyticsStore
import com.actionstarter.features.departure.DepartureViewModel
import com.actionstarter.features.eventselection.EventSelectionViewModel
import com.actionstarter.features.execution.ExecutionViewModel
import com.actionstarter.features.planreview.PlanReviewViewModel
import com.actionstarter.features.recovery.RecoveryViewModel
import com.actionstarter.features.settings.SettingsViewModel
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.persistence.SharedPreferencesExecutionScheduleStore
import com.actionstarter.persistence.room.BehaviorLogDatabase
import com.actionstarter.planning.BasicPlanningEngine
import com.actionstarter.planning.PlanningEngine
import com.actionstarter.recovery.BasicRecoveryEngine
import com.actionstarter.recovery.RecoveryEngine
import com.actionstarter.services.calendar.CalendarProviderCalendarService
import com.actionstarter.services.calendar.CalendarService
import com.actionstarter.services.calendar.ContentResolverCursorSource
import com.actionstarter.services.execution.ExecutionServiceController
import com.actionstarter.services.location.AndroidGeocodingService
import com.actionstarter.services.location.ForegroundGate
import com.actionstarter.services.location.FusedLocationService
import com.actionstarter.services.location.FusedRawLocationSource
import com.actionstarter.services.location.GeocodingService
import com.actionstarter.services.location.LocationService
import com.actionstarter.services.location.PlatformGeocoderSource
import com.actionstarter.services.notification.AlarmManagerAlarmScheduler
import com.actionstarter.services.notification.AndroidNotificationService
import com.actionstarter.services.notification.NotificationContentBuilder
import com.actionstarter.services.notification.NotificationService
import com.actionstarter.services.permission.AndroidPermissionGate
import com.actionstarter.services.permission.PermissionGate
import com.actionstarter.services.routing.CachingRoutingService
import com.actionstarter.services.routing.RoutesApiRoutingService
import com.actionstarter.services.routing.RoutingService
import com.actionstarter.services.routing.UnconfiguredRoutingService
import com.actionstarter.services.routing.UrlConnectionHttpPostClient
import com.google.android.gms.location.LocationServices
import java.time.Clock

/**
 * 手動DIコンテナ（計画書§7.3、ADR-0003）。`ActionStarterApplication`から1個生成する。
 * 全画面ViewModelはコンストラクタ注入のみとし、生成箇所は本クラスと
 * [createViewModelFactory]が返す単一の`ViewModelProvider.Factory`に集約する
 * （裁定B2の保護条件。ADR-0014によりHilt導入はPhase 5へ延期されたため、本クラスは
 * 手動DIコンテナとして存続する。計画書§14 P2-C6／旧P2-C5行）。
 *
 * [recoveryEngine]はP6-C5統合ウィンドウ（`docs/plans/phase6-recovery-basic.md`§6.4行1）で
 * `mock/MockRecoveryFactory`（削除済み）から仕様§70 Phase 6「Recovery Basic」の本番実装
 * [com.actionstarter.recovery.BasicRecoveryEngine]へ切り替え済みであり、Mock実装ではない
 * （プロパティ本体のKDoc参照）。LocalLanguageModelはUI Skeletonフローでは未使用のためDI結線
 * しない。[planningEngine]はP4-C5統合ウィンドウ（`docs/plans/phase4-basic-engine.md`§6.4・
 * §7.2手順3）で`MockPlanFactory`（削除済み）から仕様§68 Phase 4「Basic Engine」の本番実装
 * [com.actionstarter.planning.BasicPlanningEngine]へ切り替え済みであり、Mock実装ではない。
 *
 * [calendarService]／[permissionGate]は統合サイクル（旧C6／旧C5・integration owner）で
 * 一時ブリッジ（`MockBackedCalendarService`／`GrantedPermissionGate`、いずれも削除済み）から
 * 実装（[CalendarProviderCalendarService]／[AndroidPermissionGate]）へ置換した。いずれも
 * [context]（`applicationContext`）を要する。
 *
 * **Phase 3 P3-C6（本サイクル、integration owner、計画書§6.4#4）**: [routingService]を
 * `mock/MockRoutingService`（本サイクルで削除済み）から実装へ置換した。
 * `BuildConfig.ROUTES_API_KEY`が空でなければ
 * `CachingRoutingService(RoutesApiRoutingService(UrlConnectionHttpPostClient(), key))`
 * （F24/F25。§8のスロットリング・キャッシュ・retry 1回・Mutex直列化を含む）、空文字なら
 * [UnconfiguredRoutingService]（F29。固定20分等の偽ETAを返さず`RoutingException.NotConfigured`
 * を送出する）を供給する（T-CFG-1／T-CFG-2）。
 *
 * [locationService]／[geocodingService]も同サイクルで一時ブリッジ（常に`PermissionDenied`／
 * `NoMatch`を返す固定値オブジェクト、いずれも削除済み）から実装へ置換した：
 * [locationService]は`FusedLocationService(FusedRawLocationSource(
 * LocationServices.getFusedLocationProviderClient(context)), permissionGate, foregroundGate)`
 * （F22／F30）、[geocodingService]は`AndroidGeocodingService(PlatformGeocoderSource(
 * Geocoder(context)))`（F23）。[foregroundGate]は`ActionStarterApplication.onCreate()`が
 * `registerActivityLifecycleCallbacks`で登録した**唯一**の
 * [com.actionstarter.services.location.ActivityLifecycleForegroundGate]インスタンスを、
 * [context]（＝`applicationContext`。`ActionStarterApplication`自身と同一インスタンスであり、
 * `ActionStarterNavHost`が`context.applicationContext as ActionStarterApplication`で
 * [AppContainer]自身を取得する既存パターンと同型）から`as`キャストして再利用する
 * （新規インスタンスを生成すると起動中Activityカウンタが二重化し、`isAppInForeground()`が
 * 実際のActivityライフサイクルと無関係な値を返すようになるため。[foregroundGate]の
 * プロパティKDoc参照）。
 *
 * **§18解消（Fable 5裁定2026-08-09、統合修正サイクル）**: [context]は必須引数へ確定した。
 * 従来存在した「評価すると即座に例外を送出する」互換用デフォルト値（T-DI-1の
 * 零引数呼び出し`AppContainer()`をコンパイル可能に保つためだけの暫定スタブ）は撤去した。
 * `AppContainerTest`（T-DI-1）はRobolectric環境上の実Context
 * （`ApplicationProvider.getApplicationContext()`）を渡す形へ追随済みであり、本クラスの
 * DI設計（Context必須）と整合する。
 *
 * @param context `ActionStarterApplication.onCreate()`が渡す`applicationContext`（本番）、
 *   またはテストが渡すRobolectric環境の実Context。いずれも実体は[ActionStarterApplication]
 *   インスタンスである（[foregroundGate]の`as`キャストの前提）。
 */
class AppContainer(
    private val context: Context
) {

    val planningEngine: PlanningEngine = BasicPlanningEngine()

    /**
     * F24／F25／F29実装（計画書§6.4#4・§7.3・§8、S-4裁定、T-CFG-1〜3）。キーが空文字なら
     * 固定20分等の偽ETAを返さず[UnconfiguredRoutingService]（常に
     * `RoutingException.NotConfigured`を送出、エラー＆レスキューマップ#19）へ縮退する。
     * キー設定時は[RoutesApiRoutingService]（[UrlConnectionHttpPostClient]経由でComputeRoutes
     * を呼ぶ）を[CachingRoutingService]で包み、§95.2が義務付けるスロットリング（移動500m未満
     * かつ経過10分未満はAPI未呼び出し）・retry 1回・並行呼び出しの`Mutex`直列化を適用する。
     * 旧`mock/MockRoutingService`（本サイクルで削除済み）が抱えていた「キー未設定でも常に
     * 20分を返す」サイレント障害を解消する。
     */
    val routingService: RoutingService =
        if (BuildConfig.ROUTES_API_KEY.isNotEmpty()) {
            CachingRoutingService(RoutesApiRoutingService(UrlConnectionHttpPostClient(), BuildConfig.ROUTES_API_KEY))
        } else {
            UnconfiguredRoutingService()
        }

    /**
     * F70実配線（計画書§6.4行1・§7.1・§7.5、P6-C5統合ウィンドウ、ADR-0032〜0037）。
     * `BasicRecoveryEngine(routingService)`として構築する。第2引数`defaults`（既定
     * [com.actionstarter.recovery.BasicRecoveryDefaults]）・第3引数`currentTransportMode`
     * （既定`{ BasicRecoveryDefaults.DEFAULT_TRANSPORT_MODE }`、S-3・§4.2 U-4で承認済みの
     * 仕様未定義プレースホルダ）はいずれも計画書§6.4行1が明示する`BasicRecoveryEngine(
     * routingService)`のとおりデフォルト値のまま省略する（計画書に個別の供給源指定がない
     * ため。§7.5参照）。[routingService]は本プロパティより前に宣言済みの同一インスタンス
     * （`CachingRoutingService`または`UnconfiguredRoutingService`）を再利用し、
     * `RoutesApiRoutingService`を新規生成しない（§95.2、T-BRE-18/19の構造ガード対象）。
     * 本プロパティを[routingService]プロパティの直後に配置しているのは、Kotlinのプロパティ
     * 初期化がクラス本文の宣言順に実行されるため（[recoveryEngine]の初期化式が
     * [routingService]を参照する以上、[routingService]より前に置くと初期化順序違反になる）。
     */
    val recoveryEngine: RecoveryEngine = BasicRecoveryEngine(routingService)

    /**
     * F12〜F15実装（計画書§7.2）。[ContentResolverCursorSource]経由で実カレンダー
     * （`CalendarContract`）を読む。旧一時ブリッジ`MockBackedCalendarService`は削除済み。
     */
    val calendarService: CalendarService =
        CalendarProviderCalendarService(ContentResolverCursorSource(context.contentResolver))

    /**
     * F16実装（計画書§7.3）。`ContextCompat.checkSelfPermission`ベース。旧一時ブリッジ
     * `GrantedPermissionGate`は削除済み。[DepartureViewModel]の位置権限判定にも再利用する
     * （計画書§9.7コンストラクタ注記「既存/新規プロパティから」・`isGranted(permission:
     * String)`が汎用シグネチャのため位置権限にもそのまま使える）。
     */
    private val permissionGate: PermissionGate = AndroidPermissionGate(context)

    /**
     * F30実配線（計画書§5.5・§6.1・§6.4#4、S-5裁定）。`ActionStarterApplication.onCreate()`が
     * `registerActivityLifecycleCallbacks`で登録した唯一の
     * [com.actionstarter.services.location.ActivityLifecycleForegroundGate]インスタンスを
     * [context]から`as`キャストして再利用する（クラスKDoc参照）。[AppContainer]自身が新しい
     * インスタンスを生成しない理由: `Application.ActivityLifecycleCallbacks`は
     * `ActionStarterApplication`が保持するインスタンスに対してのみコールバックされるため、
     * 別インスタンスを生成すると起動中Activityカウンタが常に0のまま（実際のActivity起動と
     * 無関係）になり、`isLocationAccessAllowed()`が構造的に常時`false`を返す
     * サイレント障害になる。
     */
    private val foregroundGate: ForegroundGate = (context as ActionStarterApplication).foregroundGate

    /**
     * F22実装（計画書§6.1・§6.4#4）。旧一時ブリッジ（常に`PermissionDenied`を返す固定値
     * オブジェクト）は本サイクルで置き換え済み。[FusedRawLocationSource]（L3。
     * `LocationServices.getFusedLocationProviderClient(context)`が返す
     * `FusedLocationProviderClient`をラップし、gms型を[LocationService]の外へ出さない）・
     * [permissionGate]・[foregroundGate]をコンストラクタ注入する（§95.1 While-in-use構造
     * ガード込み、[FusedLocationService]のKDoc参照）。
     */
    private val locationService: LocationService = FusedLocationService(
        rawLocationSource = FusedRawLocationSource(LocationServices.getFusedLocationProviderClient(context)),
        permissionGate = permissionGate,
        foregroundGate = foregroundGate
    )

    /**
     * F23実装（計画書§6.1・§6.4#4）。旧一時ブリッジ（常に`NoMatch`を返す固定値オブジェクト）は
     * 本サイクルで置き換え済み。[PlatformGeocoderSource]（L3。`android.location.Geocoder`を
     * ラップし、API 33以降／26〜32の両分岐を吸収する）を注入する
     * （[AndroidGeocodingService]のKDoc参照）。
     */
    private val geocodingService: GeocodingService = AndroidGeocodingService(PlatformGeocoderSource(Geocoder(context)))

    /**
     * F49/F52実配線（計画書§6.1・§6.3・§7.3、P5-C6統合ウィンドウ、ADR-0025〜0027）。
     * [AndroidNotificationService]を構築する。永続化は[SharedPreferencesExecutionScheduleStore]
     * （[SharedPreferencesExecutionScheduleStore.PREFS_NAME]経由で`context.
     * getSharedPreferences(...)`を取得）。`NotificationTriggerReceiver`・
     * `ScheduleRestoreReceiver`はいずれも引数なしコンストラクタ制約（`BroadcastReceiver`）の
     * ためDI注入を受けられず、各`onReceive`内で同一の`PREFS_NAME`を使って独立に
     * [AndroidNotificationService]を構築する（両レシーバのKDoc参照）。本プロパティは
     * `ActionStarterNavHost`（PlanReview「Start」でのschedule・Execution画面でのcancelAll）と
     * [createViewModelFactory]（`ExecutionViewModel`のSnooze経由の再schedule）から参照される。
     */
    val notificationService: NotificationService = AndroidNotificationService(
        context = context,
        store = SharedPreferencesExecutionScheduleStore(
            context.getSharedPreferences(SharedPreferencesExecutionScheduleStore.PREFS_NAME, Context.MODE_PRIVATE)
        ),
        alarmScheduler = AlarmManagerAlarmScheduler(context),
        contentBuilder = NotificationContentBuilder(context),
        permissionGate = permissionGate
    )

    /**
     * F56/F57実配線（計画書§6.1・§6.3・§7.4、P5-C6統合ウィンドウ）。[ExecutionServiceController]は
     * 具象[com.actionstarter.services.location.ActivityLifecycleForegroundGate]
     * （`isAppInForeground()`の公開が必須、§7.4「`ForegroundGate`ではなく具象クラスを直接
     * 要求する」理由はそのKDoc参照）を要求するため、[foregroundGate]（`ForegroundGate`型）とは
     * 別に`ActionStarterApplication`から同一インスタンスを再取得する（新規インスタンス化
     * しない。[foregroundGate]プロパティKDocと同じ理由——別インスタンスを生成すると
     * 起動中Activityカウンタが二重化する）。本プロパティは`ActionStarterNavHost`
     * （Execution画面表示開始時のstart／離脱時のstop）と`ActionStarterApplication.onCreate()`
     * （`foregroundGate.isExecutionServiceRunning`フックの接続元、M5-13）から参照される。
     */
    val executionServiceController: ExecutionServiceController = ExecutionServiceController(
        context = context,
        foregroundGate = (context as ActionStarterApplication).foregroundGate
    )

    /**
     * F90実配線（計画書§7.1・§14 P7-C4、ADR-0053）。[localAiGateway]・[modelDownloader]の両方が
     * 参照する単一の`ModelStorage`インスタンス（同一の`noBackupFilesDir/models/`を見る必要が
     * あるため、それぞれが別々に`ModelStorageImpl(context)`を生成しない）。`by lazy`は
     * [localAiGateway]と同じくR-7（起動を重くしない）を理由とする——本プロパティ自体は
     * ファイルI/Oを伴わない軽量なコンストラクタだが、起動シーケンスの一貫性のため他のAI関連
     * プロパティと同じ遅延方針に揃える。
     */
    /**
     * Phase 8（計画書§6.4、Gemini G1 CRITICAL④／B4確定）: `preferences = aiPreferences`を渡す
     * 1行変更。`aiPreferences`はクラス内の宣言順としては本プロパティより後方だが、
     * [modelStorage]は`by lazy`（初回アクセス時にのみ評価）のため、コンストラクタ完了後に
     * 初めて評価される時点では[aiPreferences]は必ず初期化済みであり安全（§6.4参照）。
     * 新規インスタンスは作らず、既にeager初期化済みの単一[aiPreferences]を再利用する。
     */
    private val modelStorage: ModelStorage by lazy { ModelStorageImpl(context, preferences = aiPreferences) }

    /**
     * F92実配線（計画書§7.1・§14 P7-C1／P7-C6、ADR-0048）。[localAiGateway]・
     * [createViewModelFactory]の`SettingsViewModel`初期化子の両方が同一インスタンスを共有する
     * （Settings画面で書き込んだ`aiEnabled`／`selectedModelId`が、同一プロセス内であれば
     * 追加のSharedPreferences再読込を要さず`localAiGateway`側にも一貫して見える設計。
     * `SharedPreferences`自体は変更をディスクへ永続化する層のため、複数インスタンスを作っても
     * 最終的な整合性は保たれるが、単一共有インスタンスの方が本コンテナの既存規約
     * （[modelStorage]・[routingService]等の「1インスタンスを複数消費者で共有する」パターン）と
     * 一貫する）。**eager初期化（`by lazy`ではない）**: `AiPreferencesImpl`の構築は
     * `context.getSharedPreferences(...)`の参照取得のみでI/Oを伴わず軽量なため、R-7
     * （LLMランタイムの重い初期化を避ける目的）の対象外——[localAiGateway]／[modelDownloader]の
     * `by lazy`とは理由が異なる（[notificationService]が使う
     * `SharedPreferencesExecutionScheduleStore`と同型のeager構築判断）。
     */
    private val aiPreferences: AiPreferences = AiPreferencesImpl(
        context.getSharedPreferences(AiPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE)
    )

    /**
     * F91実配線（計画書§7.1・§14 P7-C1／P7-C6、ADR-0048）。[localAiGateway]・
     * [createViewModelFactory]の`SettingsViewModel`初期化子が共有する。[aiPreferences]と同じ
     * 理由でeager構築（`DeviceCapabilityImpl`のコンストラクタは`context`参照を保持するのみで
     * 副作用を持たない。実際の`ActivityManager`照会は`classify()`／`hasAvailableMemory()`呼び出し
     * 時に都度行われる遅延評価のため、ここでeagerに構築しても起動を重くしない）。
     */
    private val deviceCapability: DeviceCapability = DeviceCapabilityImpl(context)

    /**
     * Phase 9.5新設（計画書`docs/plans/phase9.5-performance-quality.md`§3.10 F-5、§14発見②、
     * Red検収での差し戻し訂正）。[modelSelector]・[localAiGateway]の両方が同一インスタンスを
     * 共有するために昇格した単一の[LiteRtLmLocalLanguageModel]（従来は[localAiGateway]の
     * `by lazy`ブロック内でインラインに`LiteRtLmLocalLanguageModel()`を生成していた）。
     * [EngineLoadStateSource]（`loadedModelPath()`）が意味を持つのは、問い合わせ先のインスタンス
     * が実際にEngineをロードする当のインスタンスと同一である場合に限るため、[modelSelector]の
     * 選定時点ガードと[localAiGateway]のpost-selectionガードが同じロード状態を観測できるよう
     * 単一インスタンスを両者へ配線する（計画書§3.10「両者は同じ`EngineLoadStateSource`単一情報源を
     * 共有する」）。`by lazy`は他のAI関連プロパティと同じくR-7（起動を重くしない）を理由とする。
     */
    private val localLanguageModel: LiteRtLmLocalLanguageModel by lazy { LiteRtLmLocalLanguageModel() }

    /**
     * Phase 8.5新設（計画書`docs/plans/phase8.5-adaptive-model-selection.md`§3設計4、
     * ADR-0062）。[localAiGateway]の`selectedModelId`＝`"auto"`時の自動選択に使う
     * （Step 4で`LocalAiGateway.resolveInstalledEntry`内部の分岐へ配線済み）。
     * `by lazy`は他のAI関連プロパティと同じくR-7（起動を重くしない）を理由とする。
     *
     * **Phase 9.5で`engineLoadStateSource`を追加配線（計画書§3.10 F-5、Red検収での差し戻し
     * 訂正）**: [localLanguageModel]（[localAiGateway]と共有する単一インスタンス）を渡し、
     * auto選択時にロード済み候補がavailMemガードで誤って除外されないようにする
     * （[com.actionstarter.ai.model.ModelSelectorImpl]のクラスKDoc「@param
     * engineLoadStateSource」参照）。
     */
    private val modelSelector: ModelSelector by lazy {
        ModelSelectorImpl(deviceCapability, modelStorage, engineLoadStateSource = localLanguageModel)
    }

    /**
     * F96実配線の入口（計画書§7.2・§14 P7-C1／P7-C4）。Phase 7完成条件（`ai/`が単体で完結して動く、
     * 仕様§71・計画書§0）を満たすための新規プロパティ。[planningEngine]／
     * [recoveryEngine]の右辺は本サイクルでは無変更（計画書§2.2「Phase 7はAI配線をしない」・
     * `AppContainerTest`のT-P4DI-1／T-P6DI-1を維持）。
     *
     * **`by lazy`必須（R-7・T-P7DI-2）**: 本クラスはeager初期化（クラスKDoc参照）だが、
     * LLMランタイムを素で足すと起動時に重い初期化が走る。`by lazy`により初回アクセスまで
     * 生成を遅延させ、起動を重くしない。
     *
     * [LiteRtLmLocalLanguageModel]の`modelPathProvider`は
     * [com.actionstarter.ai.model.ModelStorage.installedModelPath]を都度参照する（未導入時は
     * 例外——実際のガードは[LocalAiGateway]内の§8.6 #11チェックが先に走る想定。
     * [LiteRtLmLocalLanguageModel]のKDoc参照）。Analytics収集用コラボレータは未配線
     * （[LocalAiGateway]のKDoc「Analytics記録の設計は未確定」参照、Fable 5裁定7・ADR-0049で
     * Phase 10/12へ据え置き確定）。
     *
     * **4型のinterface化（Fable 5裁定5、2026-08-10、ADR-0048）**: `ModelStorage`／
     * `ModelVerifier`／`DeviceCapability`／`AiPreferences`は具象クラスからinterfaceへ変更され、
     * 実装は`XxxImpl`（[ModelStorageImpl]・[ModelVerifierImpl]・[DeviceCapabilityImpl]・
     * [AiPreferencesImpl]）へ分離された。本プロパティは実装クラスを構築して
     * [LocalAiGateway]のinterface型パラメータへ渡す（本コンテナ側の結線ロジック自体は
     * 無変更、コンストラクタ呼び出しの型名のみがinterface化に伴い変わった）。
     *
     * **P7-C4での変更**: `modelStorage`をローカル変数から[modelStorage]プロパティ（上記）へ
     * 昇格した（[modelDownloader]と共有するため）。`ModelVerifierImpl()`は
     * [LocalAiGateway]・[modelDownloader]それぞれが独立したインスタンスを持つ（状態は
     * 「プロセス内SHA-256キャッシュ」が[LocalAiGateway]側にあるため、`ModelVerifierImpl`自体は
     * 状態を持たず共有の必要がない）。
     *
     * **P7-C6での変更**: `AiPreferencesImpl(...)`／`DeviceCapabilityImpl(context)`の
     * インライン構築を、[modelStorage]と同じ理由で[aiPreferences]／[deviceCapability]
     * プロパティ（上記）へ昇格した（`SettingsViewModel`との共有のため。[createViewModelFactory]
     * 参照）。生成される実インスタンス自体・[LocalAiGateway]への渡し方は無変更。
     */
    val localAiGateway: LocalAiGateway by lazy {
        LocalAiGateway(
            // Phase 8.5（計画書§3設計5、ADR-0062、アーキテクトレビューPass 1 CRITICAL対応）:
            // 旧modelPathProviderラムダ（ModelStorageを独立に再解決していた）は廃止した。
            // モデルパスはLocalAiGatewayが検証・選択したエントリからgeneratePlan呼び出し時に
            // 明示的に渡される（LiteRtLmLocalLanguageModelのクラスKDoc参照）。
            // Phase 9.5（計画書§3.10 F-5、Red検収での差し戻し訂正）: インラインの
            // LiteRtLmLocalLanguageModel()生成をやめ、modelSelectorと共有する単一インスタンス
            // localLanguageModelを渡す（localLanguageModelプロパティのKDoc参照）。
            model = localLanguageModel,
            modelStorage = modelStorage,
            modelVerifier = ModelVerifierImpl(),
            deviceCapability = deviceCapability,
            preferences = aiPreferences,
            modelSelector = modelSelector
        )
    }

    /**
     * F88実配線（計画書§7.1・§14 P7-C4、ADR-0053）。[modelStorage]を共有し、DL完了直後の
     * 検証（§8.6 #5）に使う`ModelVerifierImpl()`を独立に持つ（状態レスのため共有不要、
     * [localAiGateway]のKDoc参照）。**P7-C6で呼び出し元を配線した**: [createViewModelFactory]の
     * `SettingsViewModel`初期化子（F97）が本プロパティを注入される。`by lazy`は他のAI関連
     * プロパティと同じくR-7を理由とする。
     */
    val modelDownloader: ModelDownloader by lazy {
        ModelDownloader(modelStorage = modelStorage, modelVerifier = ModelVerifierImpl())
    }

    /**
     * Phase 8実配線（計画書§6.3・§12）。[localAiGateway]（既存の単一lazyインスタンス、
     * `inferenceMutex`による直列化を1本に保つ）をそのまま再利用する。`by lazy`は他のAI関連
     * プロパティと同じくR-7（起動を重くしない）を理由とする。
     * [createViewModelFactory]の`PlanReviewViewModel`初期化子へ注入する。
     *
     * **[LinkageError]防御（domain-implementer実装時の実測発見・報告事項、計画書§6.3の字義からの
     * 逸脱）**: [localAiGateway]の構築（[LiteRtLmLocalLanguageModel]のインスタンス化）は、
     * 依存先`com.google.ai.edge.litertlm`（litertlm-android 0.15.0 AAR）のクラスファイル
     * バージョン（実測: class file version 65 = Java 21相当）が実行時JVMのバージョンを上回る
     * 環境では`ExceptionInInitializerError`→`NoClassDefFoundError`（いずれも[LinkageError]の
     * サブクラス）で失敗する（実測: JDK 17.0.19実行時に`UnsupportedClassVersionError`。本プロジェクトの
     * 実機/ART上では発生しない、JVM単体のクラス検証固有の制約）。この経路はPhase 7時点では
     * `AppContainer.localAiGateway`をどのProduction消費者も参照していなかったため潜在していた
     * （`SettingsViewModel`はlocalAiGatewayを注入されない。既存`LocalAiGatewayTest`／
     * `SettingsAiSafetyTest`はいずれも[LocalAiGateway]を直接fakeモデルで構築し`AppContainer`経由
     * ではない）。Phase 8で`PlanReviewViewModel`へ実配線した結果、既存のRobolectric全画面遷移
     * テスト（`NavigationFlowTest`等、実測で新規10件が失敗）が初めてこの経路を踏むことで顕在化した。
     *
     * [LocalAiGateway.invokeModel]が既に`UnsatisfiedLinkError`（同じく[LinkageError]の
     * サブクラス）を「ネイティブ資源がロードできない」既知の縮退経路として捕捉している
     * （§8.6 #6 MODEL_LOAD_FAILED）のと**同じ設計原則**を、DI構築の1つ手前の層へ適用する。
     * 捕捉は[LinkageError]のみに意図的に狭く限定する（ビジネスロジックの例外は握り潰さない）。
     * 実機（ART、本チェックが発生しない環境）では通常どおり構築が成功し非nullとなる。
     *
     * **申し送り**: 本ガードは計画書§6.3の例示スニペット（無条件`LocalAiPlanContextualizer(
     * localAiGateway)`）からの実装時逸脱であり、アーキテクトレビュー（opus）での確認を要する
     * （JDK 21環境の用意、または`litertlm-android`側の対応バージョン確認が本質的解決策の候補）。
     */
    val localAiPlanContextualizer: LocalAiPlanContextualizer? by lazy {
        try {
            LocalAiPlanContextualizer(localAiGateway)
        } catch (e: LinkageError) {
            null
        }
    }

    /**
     * Phase 9実配線（計画書`docs/plans/phase9-recovery-ai.md`§3.1・§11コミット3、ADR-0063想定、
     * Green実装済み）。[localAiPlanContextualizer]と同型の構成（同一の[localAiGateway]単一
     * インスタンスを再利用・同じ[LinkageError]防御・`by lazy`によるR-7「起動を重くしない」）。
     * [createViewModelFactory]の`RecoveryViewModel`初期化子へ注入する（T-P9-36/37）。
     *
     * **[LinkageError]防御は[localAiPlanContextualizer]と同じ理由**: [localAiGateway]の構築
     * （[LiteRtLmLocalLanguageModel]のインスタンス化）はJDK 21未満の実行環境で
     * `ExceptionInInitializerError`→`NoClassDefFoundError`（いずれも[LinkageError]の
     * サブクラス）に失敗しうる（[localAiPlanContextualizer]のKDoc「[LinkageError]防御」参照）。
     * [localAiGateway]自体が`by lazy`のため、本プロパティと[localAiPlanContextualizer]の
     * どちらが先にアクセスされても[localAiGateway]の構築は一度だけ行われ、結果はプロセス内で
     * 共有される。
     */
    val localAiRecoveryContextualizer: LocalAiRecoveryContextualizer? by lazy {
        try {
            LocalAiRecoveryContextualizer(localAiGateway)
        } catch (e: LinkageError) {
            null
        }
    }

    /**
     * Phase 9.5 F-2実配線（計画書§3.3、Step 4 Green、実装時の実測発見）。
     * `EventSelectionViewModel`（[createViewModelFactory]）へ渡す[localAiGateway]の
     * [LinkageError]防御版。[localAiPlanContextualizer]・[localAiRecoveryContextualizer]と
     * **同じ理由**（[localAiGateway]の構築＝[LiteRtLmLocalLanguageModel]のインスタンス化は
     * JDK 21未満の実行環境で`ExceptionInInitializerError`→`NoClassDefFoundError`〔いずれも
     * [LinkageError]のサブクラス〕に失敗しうる、[localAiPlanContextualizer]のKDoc
     * 「[LinkageError]防御」参照）だが、**本プロパティが必要になった経緯は異なる**:
     * `EventSelectionViewModel`はナビゲーションフローの起点（最初に構築されるViewModel）であり、
     * 素の[localAiGateway]（非nullable）をそのまま渡す設計にすると、`NavigationFlowTest`等の
     * 既存Robolectricテストが[localAiGateway]の遅延評価を*[localAiPlanContextualizer]より前の
     * タイミング*で踏み抜き、[LinkageError]が未捕捉のまま伝播して失敗する（実装時の実測発見、
     * `PlanReviewViewModel`配線時に[localAiPlanContextualizer]のKDoc「実測で新規10件が失敗」が
     * 記録した経緯と同型の再発）。`EventSelectionViewModel`の`localAiGateway`引数は元々
     * nullable（既存呼び出し元との後方互換のため既定`null`）なので、本プロパティも同じ
     * nullable型のまま[LinkageError]を吸収する。
     */
    private val eventSelectionLocalAiGateway: LocalAiGateway? by lazy {
        try {
            localAiGateway
        } catch (e: LinkageError) {
            null
        }
    }

    /**
     * Phase 10 C1（計画書`docs/plans/phase10-behavior-log-profile.md`§5・§8、レビュー§13
     * No.8・Gemini G8）。行動ログ・Personal Profile用DB（本プロジェクト初のDB）。
     *
     * **初期化失敗防御**: [localAiPlanContextualizer]等と**同型**の`by lazy`+try/catch→null
     * パターンだが、捕捉範囲は[LinkageError]限定ではなく**[Throwable]全体**とする——Roomの
     * 実際の失敗モード（スキーマ不整合・破損ファイル等の`IllegalStateException`／SQLite例外）は
     * litertlmのJDKバージョン起因の[LinkageError]より広く、狭い捕捉では防御にならないため
     * （計画書§8「DB初期化失敗」参照）。[analyticsStore]が`null`のとき、行動ログ呼び出し元は
     * 全てno-opにフォールバックする（アプリ本体は継続動作、AI機能がOFF/未導入時にno-opになる
     * のと同じ設計）。`fallbackToDestructiveMigration()`は**呼ばない**（計画書§12確認事項7
     * 「確定: 既定で有効化しない」——データ消失を伴う自動復旧より、初期化失敗時のno-op縮退を
     * 優先する）。
     */
    private val behaviorLogDatabase: BehaviorLogDatabase? by lazy {
        try {
            Room.databaseBuilder(
                context,
                BehaviorLogDatabase::class.java,
                BehaviorLogDatabase.DATABASE_NAME
            ).build()
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Phase 10 C1（計画書§3・§5）。[AnalyticsStore]の唯一のインスタンス。features層は本
     * プロパティ経由でのみ行動ログを記録する（`persistence.room`配下のRoom型を直接参照
     * しない、[localAiGateway]と同型の層規律）。[behaviorLogDatabase]が`null`
     * （初期化失敗）のときは[analyticsStore]も`null`——呼び出し元は`?.let { }`等でno-op化する。
     */
    val analyticsStore: AnalyticsStore? by lazy {
        behaviorLogDatabase?.let { db ->
            RoomAnalyticsStore(db.behaviorEventDao(), db.personalExecutionProfileDao())
        }
    }

    /**
     * `ActionStarterNavHost`（統合サイクル・integration owner所有）から呼び出される単一
     * `ViewModelProvider.Factory`。[sharedPlanViewModel]はactivity-scopedの共有ViewModel
     * （計画書§10.1）であり、[PlanReviewViewModel]／[RecoveryViewModel]／[ExecutionViewModel]が
     * イベント選択・確定済みPlanを参照するためにクロージャで受け渡す。
     *
     * **P5-C6統合ウィンドウでの結線（ADR-0028・§10.6申し送り）**: `ExecutionViewModel`を
     * 本Factoryへ追加した。Phase 1完了報告「タスク7の判断結果」時点では、
     * `ExecutionViewModel`のコンストラクタ契約（当時`SavedStateHandle`のみ）が
     * `ExecutionViewModelTest`／`ExecutionScreenTest`に直接束縛されていたため意図的に
     * 除外していたが、ADR-0028（P5-C2b）により新規3引数（[sharedPlanViewModel]／
     * `notificationService`／`permissionGate`、いずれも既定値`null`）が追加され、
     * 既存テスト（`SavedStateHandle`のみで構築）を壊さずに実引数を注入できるようになった。
     * `ActionStarterNavHost`のexecution routeは本Factory経由で`ExecutionViewModel`を取得し、
     * `SharedPlanViewModel.confirmedPlan`から直接`ExecutionUiState`を構築していた旧設計
     * （M5-14）を置き換える（`ActionStarterNavHost`のKDoc参照）。
     */
    fun createViewModelFactory(sharedPlanViewModel: SharedPlanViewModel): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                // Phase 9.5 F-2実配線（計画書§3.3、Step 4 Green）: localAiGatewayを渡し、
                // トップ画面（EventSelection）入場のたびにLocal AI Engineの先行ウォームアップ
                // （EventSelectionViewModel.warmUpAiIfPossible→LocalAiGateway.warmUp）を
                // 発動させる。素のlocalAiGatewayではなくeventSelectionLocalAiGateway
                // （LinkageError防御版、上記プロパティのKDoc参照）を渡す——実測で、素のまま
                // 渡すとNavigationFlowTest等の既存テストがLinkageErrorを未捕捉のまま踏み抜く
                // ことが判明したため。
                EventSelectionViewModel(
                    calendarService = calendarService,
                    permissionGate = permissionGate,
                    savedStateHandle = createSavedStateHandle(),
                    localAiGateway = eventSelectionLocalAiGateway
                )
            }
            // P4-C8実配線（`docs/plans/phase4-basic-engine.md`P4-C8行、仕様§13の完全実装）:
            // geocodingService／locationService／routingService／permissionGateを実引数で渡す。
            // routingServiceは本コンテナ内で既に構築済みの単一インスタンス
            // （CachingRoutingServiceまたはUnconfiguredRoutingService、上記routingServiceプロパティ
            // 参照）をDepartureViewModelと共有するため、Departure画面遷移時の再取得は
            // CachingRoutingServiceのキャッシュ（§8、目的地・移動手段一致かつ移動500m未満・
            // 経過10分未満）によりヒットしうる（二重フェッチにならない）。
            initializer {
                PlanReviewViewModel(
                    planningEngine = planningEngine,
                    sharedPlanViewModel = sharedPlanViewModel,
                    geocodingService = geocodingService,
                    locationService = locationService,
                    routingService = routingService,
                    permissionGate = permissionGate,
                    // Phase 8実配線（計画書§6.1・§6.3）: イベント選択のたびにPlanReviewViewModel
                    // 側のcollectLatestがtravel取得と並行してcontextualizer.contextualizeを呼ぶ
                    // （§4.1）。localAiPlanContextualizerはLinkageError発生時null（KDoc参照）。
                    aiPlanContextualizer = localAiPlanContextualizer,
                    // Phase 10 C2（計画書§3.2、ADR-0049決定5）: AI_WORDING_OUTCOME（domain=PLAN、
                    // Phase 12比較実験の主データ）の記録先。analyticsStoreはbehaviorLogDatabase
                    // 初期化失敗時null（C1のAppContainer防御、KDoc参照）。
                    analyticsStore = analyticsStore
                )
            }
            initializer {
                DepartureViewModel(
                    sharedPlanViewModel = sharedPlanViewModel,
                    locationService = locationService,
                    geocodingService = geocodingService,
                    routingService = routingService,
                    permissionGate = permissionGate
                )
            }
            initializer {
                // F70/F78実配線（計画書§6.4行1、P6-C5統合ウィンドウ）: locationService／clockを
                // 実引数で渡す（P6-C1 scaffold既定値のUnavailableLocationService／
                // Clock.systemUTC()から実装へ差替え）。notificationServiceは
                // useThisPlan→通知再スケジュール結線（Fable 5裁定・P5-C6申し送り③への回答）に
                // 用いる。Phase 9実配線（計画書§3.1・§11コミット3、Green実装済み）:
                // aiRecoveryContextualizerはlocalAiPlanContextualizerと同型のパターンで注入する
                // （T-P9-36/37。localAiRecoveryContextualizerはLinkageError発生時null、
                // localAiRecoveryContextualizerのKDoc参照）。
                RecoveryViewModel(
                    recoveryEngine = recoveryEngine,
                    sharedPlanViewModel = sharedPlanViewModel,
                    locationService = locationService,
                    clock = Clock.systemUTC(),
                    notificationService = notificationService,
                    aiRecoveryContextualizer = localAiRecoveryContextualizer,
                    // Phase 10 C2（計画書§3.2、ADR-0049決定5）: DELAY_DETECTED（init）・
                    // STEP_SKIPPED/RECOVERY_SELECTED（useThisPlan）・AI_WORDING_OUTCOME
                    // （domain=RECOVERY、refresh）の記録先。
                    analyticsStore = analyticsStore
                )
            }
            initializer {
                ExecutionViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    sharedPlanViewModel = sharedPlanViewModel,
                    notificationService = notificationService,
                    permissionGate = permissionGate,
                    // Phase 10 C2（計画書§3.2、ADR-0049決定5）: STEP_DONEの記録先。
                    analyticsStore = analyticsStore
                )
            }
            // F97実配線（計画書§7.1・§14 P7-C6／Phase 8.5 F-B）: SettingsViewModelは他画面と異なり
            // sharedPlanViewModelを一切参照しない（Settings画面は選択済みイベント／確定Planの
            // どちらにも関心を持たない独立した設定画面のため）。availableModelsはコンストラクタの
            // 既定値（Gemma4・Qwen0.6B、ModelSelector.DEFAULT_AUTO_CANDIDATESと同じ候補集合）を
            // そのまま使う。
            initializer {
                SettingsViewModel(
                    aiPreferences = aiPreferences,
                    modelDownloader = modelDownloader,
                    modelStorage = modelStorage,
                    deviceCapability = deviceCapability
                )
            }
        }
}
