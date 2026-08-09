package com.actionstarter.di

import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.actionstarter.ActionStarterApplication
import com.actionstarter.BuildConfig
import com.actionstarter.features.departure.DepartureViewModel
import com.actionstarter.features.eventselection.EventSelectionViewModel
import com.actionstarter.features.planreview.PlanReviewViewModel
import com.actionstarter.features.recovery.RecoveryViewModel
import com.actionstarter.mock.MockRecoveryFactory
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.planning.BasicPlanningEngine
import com.actionstarter.planning.PlanningEngine
import com.actionstarter.recovery.RecoveryEngine
import com.actionstarter.services.calendar.CalendarProviderCalendarService
import com.actionstarter.services.calendar.CalendarService
import com.actionstarter.services.calendar.ContentResolverCursorSource
import com.actionstarter.services.location.AndroidGeocodingService
import com.actionstarter.services.location.ForegroundGate
import com.actionstarter.services.location.FusedLocationService
import com.actionstarter.services.location.FusedRawLocationSource
import com.actionstarter.services.location.GeocodingService
import com.actionstarter.services.location.LocationService
import com.actionstarter.services.location.PlatformGeocoderSource
import com.actionstarter.services.permission.AndroidPermissionGate
import com.actionstarter.services.permission.PermissionGate
import com.actionstarter.services.routing.CachingRoutingService
import com.actionstarter.services.routing.RoutesApiRoutingService
import com.actionstarter.services.routing.RoutingService
import com.actionstarter.services.routing.UnconfiguredRoutingService
import com.actionstarter.services.routing.UrlConnectionHttpPostClient
import com.google.android.gms.location.LocationServices

/**
 * 手動DIコンテナ（計画書§7.3、ADR-0003）。`ActionStarterApplication`から1個生成する。
 * 全画面ViewModelはコンストラクタ注入のみとし、生成箇所は本クラスと
 * [createViewModelFactory]が返す単一の`ViewModelProvider.Factory`に集約する
 * （裁定B2の保護条件。ADR-0014によりHilt導入はPhase 5へ延期されたため、本クラスは
 * 手動DIコンテナとして存続する。計画書§14 P2-C6／旧P2-C5行）。
 *
 * 厳守事項に従い、[recoveryEngine]は引き続き`mock/`パッケージのMock実装である
 * （U6、Phase 6まで現役）。LocalLanguageModelはUI Skeletonフローでは未使用のためDI結線
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
    val recoveryEngine: RecoveryEngine = MockRecoveryFactory()

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
     * `ActionStarterNavHost`（統合サイクル・integration owner所有）から呼び出される単一
     * `ViewModelProvider.Factory`。[sharedPlanViewModel]はactivity-scopedの共有ViewModel
     * （計画書§10.1）であり、[PlanReviewViewModel]／[RecoveryViewModel]がイベント選択・
     * 確定済みPlanを参照するためにクロージャで受け渡す。
     *
     * `ExecutionViewModel`は意図的に本Factoryへ含めない（Phase 1完了報告「タスク7の判断結果」
     * 参照）：そのコンストラクタ契約（`SavedStateHandle`のみ）は`ExecutionViewModelTest`／
     * `ExecutionScreenTest`に直接束縛されており自己判断で変更しない。`ActionStarterNavHost`の
     * execution routeは`SharedPlanViewModel.confirmedPlan`から直接`ExecutionUiState`を構築する
     * （`ActionStarterNavHost`のKDoc参照）。
     */
    fun createViewModelFactory(sharedPlanViewModel: SharedPlanViewModel): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                EventSelectionViewModel(
                    calendarService = calendarService,
                    permissionGate = permissionGate,
                    savedStateHandle = createSavedStateHandle()
                )
            }
            initializer { PlanReviewViewModel(planningEngine, sharedPlanViewModel) }
            initializer {
                DepartureViewModel(
                    sharedPlanViewModel = sharedPlanViewModel,
                    locationService = locationService,
                    geocodingService = geocodingService,
                    routingService = routingService,
                    permissionGate = permissionGate
                )
            }
            initializer { RecoveryViewModel(recoveryEngine, sharedPlanViewModel) }
        }
}
