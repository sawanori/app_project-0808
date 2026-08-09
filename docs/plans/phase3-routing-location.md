# Action Starter Android ― Phase 3 実装計画書：Routing / Location（FusedLocationProviderClient + Geocoder + Routes API）

**対象Phase**: Phase 3（仕様書§67 Phase 3、§43 Services/LocationService・RoutingService）
**正仕様書**: `Action_Starter_Master_Specification_v2.0_Android.md`
**前提**: **Phase 2クローズ**（G4-JVM達成を含む）。**Phase 2はC5-fixで122/122 Green達成済み（2026-08-09・`p2c5fix-full.log`）。C6/C8のクローズ工程が進行中である**（`docs/plans/phase2-calendar.md`§18参照）。Phase 3 着手条件は引き続きPhase 2のG4-JVM通過とし、P3-C1のベースライン確認は「着手時点の全スイートGreen実測（現行122件＋Phase 2 C6での増減を反映した件数）の記録」に簡素化する（承認状態・R20参照）。
**起点計画メモ**: android-planner（Opus）作成、2026-08-09（サブエージェント実行ログ `/tmp/claude-1000/-home-noritakasawada-project-app-project-0808/62ef4d74-427d-448a-8ab3-ab9f7ead819b/tasks/a9c5f1df8c02216e1.output` 内、最終応答全文。計画メモ自体が§0〜§16の構成を持つ）
**本書作成**: plan-doc-writer（Sonnet）、2026-08-09（初版）
**関連ハーネス文書**: `docs/TEAMS.md`（役割分担・PDCA・品質ゲートの正）、`docs/GOAL.md`（リリース判定基準）、`DECISIONS.md`（ADR記録先）
**関連計画書**: `docs/plans/phase1-ui-skeleton-domain.md`（Phase 1）、`docs/plans/phase2-calendar.md`（Phase 2。C5-fixで122/122 Green達成済み・2026-08-09。C6/C8クローズ工程進行中。§18参照）

**ステータス: Fable 5＋Geminiクロスレビュー済み・CRITICAL 3件反映済み（2026-08-09）→ G1通過。S-1〜S-6裁定済み（2026-08-09・下記のとおり）。着手条件: Phase 2のG4-JVM通過（Phase 2はC5-fixで122/122 Green達成済み・2026-08-09。C6/C8クローズ工程進行中。R20参照）。**

本計画書はandroid-planner（Opus）作成のPhase 3計画メモ（§0〜§16）を忠実に文書化したものであり、計画メモにない機能・仕様を自己判断で追加していない。計画メモが自己補完を禁じてFable 5の裁定を要請した6件（S-1〜S-6、§3）は2026-08-09、いずれもandroid-planner推奨案どおり承認された（詳細は§3.2）。§15項目7（`transport mode`の解釈＝ユーザーが選べることを指すというandroid-plannerの解釈）も同時に承認された。Geminiによる第三者クロスレビュー（`model: "gemini-3.5-flash"`固定）はG1として実施済みであり、指摘されたCRITICAL 3件（Departure層の所有権と直列化、ForegroundGate判定式の拡張、Phase 2クローズ前提の更新）はFable 5裁定（2026-08-09）により本書へ反映済みである（§3.2、§5.5、§6.1〜6.2、§9.3、§10、§12、§14、§15、§16参照。→G1通過）。本書と正仕様書v2.0に差異が生じた場合は仕様書v2.0が正とする。

---

## 0. 結論ファースト

Phase 3 は「現在地→予定先の所要時間が取れる」（§67 完成条件）を、**位置3層・経路3層＋キャッシュデコレータ**という Phase 2 で実証済みの分割方式で実装する。技術的な最大リスクだった **Play Services Location の minCompileSdk 適合（ADR-0011型）は本メモ作成時に実測で解消済み**（`play-services-location:21.4.0` の AAR メタデータは `minCompileSdk=1` / `minAndroidGradlePluginVersion=1.0.0`。推移依存の `play-services-base:18.9.0` / `basement:18.9.0` / `tasks:18.4.0` も同じく `minCompileSdk=1`）。したがって compileSdk 35・AGP 8.13.2（ADR-0007/0011）のまま導入できる。**`RoutingService`（§46）のシグネチャは変更しない**方針を推奨し、TEAMS §5 の契約変更経路を発動させない。Fable 5 の裁定を要する未定義事項は **S-1〜S-6 の6件**である（自己補完していない）。

---

## 1. 仕様原文の引用（根拠）

**§67 Phase 3（1866-1884行）全文**:
> **Routing / Location** / FusedLocationProviderClient + Geocoder + Routes API。
> - Permission（ACCESS_FINE_LOCATION） / current location / destination geocoding / route estimation / transport mode / ETA
> 完成条件： 現在地→予定先の所要時間が取れる。

**§9（392-403行）**: `RoutingService` interface と「初期MVPは Google Maps Platform Routes API を第一候補とし、Mapbox / HERE / OSM系へ差し替え可能な Provider抽象を必須とする」。

**§46（1401-1413行）**: `suspend fun estimateRoute(origin: Coordinate, destination: Coordinate, mode: TransportMode, departureDate: Instant): RouteEstimate` — **`RouteEstimate` を非null で返す契約であり、失敗表現が型に存在しない**。

**§42（1290-1296行）**: Location = FusedLocationProviderClient（Google Play services）／Routing = Routes API（RoutingService抽象で差替可能に）。

**§58（1704行）**: 「可能なら**予定の前後だけ**取得する。この方針により **ACCESS_BACKGROUND_LOCATION を要求しない設計**とし、Play審査リスクを低減する」。

**§95.1「位置情報のWhile-in-use制約」（2532-2536行）**:
> Android 11以降のWhile-in-use制約により、**アプリがバックグラウンドの状態でアラーム等から起動した処理・Foreground Serviceでは位置情報を取得できない**。…位置情報を用いたETA再計算・Reality Checkは、(a) ユーザーが通知をタップする等でアプリがフォアグラウンドに復帰した時点、または (b) ユーザーがフォアグラウンドで開始したExecution Mode中のForeground Service…の継続中、のいずれかでのみ実行する。この設計により…**サイレントな位置取得失敗（SecurityException／null位置）を構造的に回避する**。

**§95.2（2542-2550行）**:
> リクエストごとに出発地・目的地の座標と移動手段のみを送信する。カレンダー本文・イベントタイトル・訪問先名等は送信しない。…**Routes APIをポーリングせず、スロットリングとキャッシュを義務とする**。目安として「前回呼び出しからの移動距離が閾値（例: 500m）未満かつ経過時間が閾値（例: 10分）未満の場合はキャッシュ済みETAを使用する」等のルールを実装時に定め、**決定的な時刻演算（Basic Engine側）はキャッシュ値でも常に成立させる**。…正確な無料枠・単価は変動するため、実装着手時に最新のGoogle Maps Platform Pricingページで再確認し、想定呼び出し回数を再試算すること。

**§95.4 権限表 ACCESS_FINE_LOCATION行（2565行）**:
> | ACCESS_FINE_LOCATION（バックグラウンド位置は不要） | 現在地取得によるRoute/ETA計算（§67）。予定の前後だけ取得し常時監視はしない（§58） | **Departure Mode / Reality Check機能の初回利用時** | **現在地起点の自動ETA計算を無効化し、出発地の手動選択またはTravel Timeの手動入力にフォールバック** |

冒頭文（2560行）: 「該当機能を初めて利用するタイミングで要求し、アプリ起動時に一括要求しない」「拒否された場合も…アプリ全体が停止しないことを必須とする」。

**§95.6 経路取得行（2590行）**:
> | 経路取得（Routes API呼び出し・オフライン） | ネットワーク断・タイムアウト・**クォータ超過**等で失敗する | **retry 1回 → 失敗時は直近の成功したRoute Estimateまたはユーザー入力の目安時間へフォールバック**。Basic Engineの決定的計算（Transition/Preparation/Buffer）は経路取得失敗時も独立して動作を継続する | **ETA精度が低下する旨を画面上に明示する**。Execution自体は停止しない |

**§95.6 While-in-use行（2593行）**: 「バックグラウンドでは位置取得を試みず通知提示のみに限定」。

**§29 Departure Mode（918-943行）** / **§35 Screen 4（1120-1131行）**: `Leave now / ETA 09:52 / Event starts 10:00 / Buffer 8 min / Start navigation`。

**§6 Global-first（285-312行）**: 「撮影 → 電車 → 渋谷」など日本固有の生活前提を埋め込まない。`transportMode` は抽象データとして扱う（必須項目に明記）。

**§43（1327-1342行）**: `Services` 直下に `LocationService` / `RoutingService`。**`LocationService` のシグネチャはコードブロックが与えられていない**（§44/§45/§46 と異なる）→ ADR記録トリガー②。

---

## 2. スコープ

### 2.1 やること
F21〜F31（§4）。§67 の6項目すべてに対応させる。

### 2.2 やらないこと（明示）
- **BasicPlanningEngine への結線**（§68 Phase 4）。Phase 3 は `PlanningContext.travelEstimate: Duration?` を**埋める側の値を用意するだけ**で、`planning/` には一切触れない。
- **通知・AlarmManager・WorkManager・Foreground Service**（§69 Phase 5）。したがって Phase 3 に**バックグラウンドからの位置取得経路は存在しない**（§95.1 の制約は F30 の構造ガードで先回りするのみ）。
- **Reality Check の周期実行**（§30）。Phase 3 は Departure 画面のフォアグラウンド単発計算に限定する。
- **Room/DataStore 永続化**（§74 Phase 10）。キャッシュは**プロセス内メモリのみ**。
- **Start navigation の外部地図アプリ起動**（§67 に列挙なし・§88）。既存の `isStartNavigationEnabled = false` を維持する。
- **地図表示・場所ピッカー**（§61「MVPに入れない」に直接の記載はないが §88 判定でNo）。
- **カレンダー・Event Selection・手動イベント入力**（Phase 2 所管）。
- **サーバサイドAPIキープロキシ**（§88。§13「ユーザーへの依頼文」で残存リスクとして明示する）。

### 2.3 フォアグラウンド限定の設計制約
Phase 3 が追加する処理はすべてフォアグラウンド（Composable のライフサイクル内 / `viewModelScope`）でのみ実行する。BroadcastReceiver・Service・Worker を一切導入しない。位置取得は Departure 画面の明示的トリガー（画面表示・再計算ボタン・transportMode 変更）に限定し、**位置の継続監視（`requestLocationUpdates`）は行わない**（§58「常時監視を前提にしない」）。

---

## 3. Fable 5 の裁定を要する事項（自己補完禁止・6件）

**本節はすべて2026-08-09時点でFable 5により裁定済みである（裁定内容は§3.2）。以下3.1は計画メモ提出時点の論点原文を参考として保持する。**

### 3.1 計画メモが提起した論点（原文）

| ID | 論点 | 仕様上の状況 | android-planner の推奨と根拠 |
|---|---|---|---|
| **S-1** | `ACCESS_COARSE_LOCATION` を Manifest に併記し、FINE と**同時に**実行時要求するか | §95.4 の権限表は **ACCESS_FINE_LOCATION のみ**を列挙。COARSE は仕様に記載なし | **併記を推奨**。根拠: Android 12 (API 31) 以降、FINE のみを実行時要求すると「正確な位置／おおよその位置」トグルが成立せずシステムが要求を無視する挙動が知られている（**要検証 P3-P1**）。併記は権限追加＝ADR記録トリガー⑤に該当するため裁定必須。仕様表からの逸脱ではなく「FINE を成立させるための前提権限」として ADR に記録する案。COARSE のみ許可された場合の精度低下は UI に明示する（T-PERM3-5） |
| **S-2** | 権限拒否時フォールバックを「Travel Time 手動入力」だけにするか、「出発地の手動選択」も作るか | §95.4/§95.6 は**両方を並列に**挙げる（「出発地の手動選択**または**Travel Timeの手動入力」） | **Travel Time 手動入力のみを推奨**。根拠: 「出発地の手動選択」は場所検索UI／地図ピッカーを要し §88 判定で Phase 3 スコープに不釣り合い。「または」なので片方の実装で仕様文言は満たす。Phase 6（Recovery）以降で再検討 |
| **S-3** | `TransportMode` の初期値をどう決めるか | §9 は4値を定義するが**既定値の規定なし**。§6 は日本固有前提の埋め込みを禁止 | **初期値 `TRANSIT`・ユーザーが常時変更可能・セッション内メモリ保持**を推奨。根拠: §5/§81 の検証市場が日本であること。**ただしこれは UI の初期選択値にすぎず、データモデル側は §6 必須項目どおり `transportMode` の抽象フィールドのままである**旨を ADR に明記する。ロケール由来の既定値切替は Phase 11（§75）へ申し送り |
| **S-4** | `RoutingService.estimateRoute`（§46）の失敗表現：例外か、戻り値型の変更か | §46 は `RouteEstimate` 非null 返却。§89 は「Error handling / Offline behavior」を要求、§95.6 は retry+fallback を要求。**両立の方法は未定義** | **§46 のシグネチャを一切変更せず、sealed な `RoutingException` 階層で表現することを推奨**。根拠: (a) 戻り値型変更は ADR①＋③＋TEAMS §5 契約変更経路を発動し Phase 4 の `PlanningContext` 設計にも波及する、(b) sealed 例外＋呼び出し側の網羅 `when`（`else` 禁止・T-DEPVM-9 で検証）でサイレント障害は型で防げる、(c) キャッシュ由来の縮退値は `RouteEstimate.computedAt` の古さで表現でき新型が要らない |
| **S-5** | While-in-use ガード（F30）を Phase 3 で作るか Phase 5 へ送るか | §95.1 は「構造的に回避する」と明記。ただし Phase 3 にバックグラウンド起動経路は存在しない | **Phase 3 で最小構成を作ることを推奨**。根拠: Phase 5 でアラームから同じ `LocationService` を呼ぶ経路が必ず生えるため、安全性プロパティを後付けするより初期から持たせる方が安い（1 interface + 1実装クラス、`Application.ActivityLifecycleCallbacks` ベースで新規依存ゼロ）。**却下案**: `androidx.lifecycle:lifecycle-process` の追加（新規依存＝ADR④＋ADR-0011型リスクの再燃） |
| **S-6** | 3並列（domain×2 ＋ ui×1）実装サイクルを許可するか | TEAMS §5 の並列化ポイントは「ui-implementer と domain-implementer」の2系統を想定 | **3並列を推奨**（P3-C3=`services/location/`、P3-C4=`services/routing/`、P3-C5=`features/departure/`。ファイルフットプリントが完全に素である＝§6参照）。**フォールバック**: 不許可なら C3→C4 直列＋C5 並列の2並列に縮退 |

### 3.2 Fable 5裁定（すべて推奨案どおり承認・2026-08-09）

| # | 裁定内容 | 反映箇所 |
|---|---|---|
| S-1 | `ACCESS_COARSE_LOCATION`の併記を承認（android-planner推奨どおり）。P3-P1の実測で最終確認し、ADRに記録する | 本書§3.1 S-1、§6.4 共有ファイル#3、§9.10 ゲート検証手順3、§11 P3-P1、§15 #3 |
| S-2 | Travel Time手動入力のみを承認（android-planner推奨どおり）。出発地の手動選択は作らない | 本書§2.2、§3.1 S-2、§4 F28、§15 #4 |
| S-3 | 初期値`TRANSIT`を承認（android-planner推奨どおり）。**これはUIの初期選択値にすぎず、データモデル側は§6必須項目どおり`transportMode`の抽象フィールドのままである旨をADRに明記する**。ロケール由来の既定値切替はPhase 11（§75）で再検討する | 本書§3.1 S-3、§4 F26、§15 #5 |
| S-4 | `RoutingService.estimateRoute`（§46）のシグネチャは変更せず、sealedな`RoutingException`階層で失敗を表現する方式を承認（android-planner推奨どおり）。呼び出し側は`else`節なしの網羅`when`で写像する（網羅whenを崩さない・else禁止） | 本書§3.1 S-4、§5.1、§5.4、§14 R18、§15 #2 |
| S-5 | `ForegroundGate`（While-in-useガード、F30）をPhase 3で最小構成実装することを承認（android-planner推奨どおり）。**判定式は2026-08-09のFable 5裁定（Gemini G1 CRITICAL対応）により`isLocationAccessAllowed()`（`isAppInForeground()`または`isExecutionServiceRunning()`が真のとき許可）へ拡張済み（修正2）** | 本書§3.1 S-5、§5.5、§6.1、§9.3、§10 #10、§15 |
| S-6 | 3並列（P3-C3＝domain-implementer A、P3-C4＝domain-implementer B、P3-C5＝ui-implementer）を承認（android-planner推奨どおり） | 本書§3.1 S-6、§6.4、§12、§14 R19 |
| （§15項目7） | `transport mode`の解釈（§67の当該項目は「§9で型定義済みのため、ユーザーが選べること」を指すというandroid-plannerの解釈）を承認 | 本書§4 F26、§15 #7 |

**上記S-1〜S-6および§15項目7の解釈は、いずれもユーザー承認待ちの項目ではない（Fable 5裁定として確定済み・2026-08-09）。** Geminiによる第三者クロスレビュー（`model: "gemini-3.5-flash"`固定）はG1として実施済みである。指摘されたCRITICAL 3件（Departure層の所有権と直列化、ForegroundGate判定式の拡張、Phase 2クローズ前提の更新）はFable 5裁定（2026-08-09）により本書へ反映済みである（→G1通過）。

---

## 4. 機能一覧（§67 の6項目への対応。F番号は Phase 2 の F20 から連番継続）

| ID | 機能 | §67項目 | 仕様根拠 | 備考 |
|---|---|---|---|---|
| F21 | ACCESS_FINE_LOCATION 実行時権限リクエストUI（事前説明カード→明示タップ） | Permission | §67・§95.4 | 起動時一括要求は禁止。要求タイミングは「Departure Mode 初回利用時」（§95.4）。Phase 2 の `PermissionGate` をそのまま再利用し**新規抽象を作らない** |
| F22 | 現在地取得（`LocationService` 契約＋Fused実装） | current location | §67・§42・§43 | シグネチャ仕様未定義＝ADR②。単発 fix のみ。継続監視なし（§58） |
| F23 | 目的地 geocoding（`GeocodingService` 契約＋`android.location.Geocoder` 実装） | destination geocoding | §67・§42 | `ExecutionEvent.coordinates` が常に null（Phase 2 §8.2 実測）である前提を解消する。**「geocode不能文字列」は `NoMatch` として正常系に分類**（Phase 2 P-7 申し送り） |
| F24 | Routes API による経路見積り（`RoutesApiRoutingService`） | route estimation | §9・§42・§95.2 | ComputeRoutes を POST。`RoutingService`（§46）の実装であり **interface は変更しない** |
| F25 | スロットリング／キャッシュ層（`CachingRoutingService` デコレータ） | route estimation | **§95.2（義務）**・§95.6 | 移動距離閾値・最小間隔・直近成功値の再利用・retry 1回。**§95.2 が「義務」と明記しているため任意実装ではない** |
| F26 | Transport mode 選択UI（4値） | transport mode | §9・§67・§6 | S-3 裁定に従う |
| F27 | ETA 算出と Departure 画面の実データ化 | ETA | §29・§35 Screen4・§67 | `estimatedArrival = departureTime + duration`。`Instant` 演算のため DST 安全（T-DEPVM-7 で回帰ロック） |
| F28 | 権限拒否／未設定時の Travel Time 手動入力フォールバック | Permission | §95.4・§95.6 | S-2 裁定に従う |
| F29 | APIキー構成（local.properties → BuildConfig）と未設定時の縮退 | route estimation | §89「No hard-coded secrets」・§95.2 | **キー未設定でもビルド・全JVMテストが成立**。キー空時は `UnconfiguredRoutingService`（後述） |
| F30 | While-in-use 構造ガード（`ForegroundGate`） | current location | **§95.1**・§95.6 | S-5 裁定に従う |
| F31 | opt-in 実API疎通E2Eハーネス（キー存在時のみ実行・`Assume` で skip） | route estimation | §89 Testable・TEAMS §6 G4 | CI/通常テストからは常に skip される設計 |

---

## 5. interface 契約案

### 5.1 既存契約は一切変更しない（**重要**）

`PlanningEngine`（§44）／`RecoveryEngine`（§45）／**`RoutingService`（§46）**／`LocalLanguageModel`（§16）／`CalendarService`（Phase 2）を**変更しない**。`RouteEstimate` / `Coordinate` / `TransportMode`（`domain/valueobject/`）も**変更しない**。したがって TEAMS §5「interface契約のバージョン付き変更経路」の発動は不要である。

> `RouteEstimate` に `distanceMeters` を足したくなる誘惑があるが、F25 のスロットリング判定は「**現在地の移動距離**」であり `Coordinate` 2点から計算できるため不要。追加すると Phase 4（`PlanningContext`）へ波及するので明示的に見送る。

### 5.2 新設: `LocationService`（F22。§43 に名前のみ・シグネチャ未定義 ⇒ ADR②）

```kotlin
package com.actionstarter.services.location

interface LocationService {
    /** 単発の現在地 fix を取得する。継続監視はしない（§58）。 */
    suspend fun currentLocation(timeout: Duration = DEFAULT_TIMEOUT): LocationResult
}

sealed interface LocationResult {
    data class Success(
        val coordinate: Coordinate,
        val accuracyMeters: Float?,
        val fixedAt: Instant
    ) : LocationResult
    data object PermissionDenied : LocationResult
    data class Failure(val reason: LocationFailureReason, val cause: Throwable?) : LocationResult
}

enum class LocationFailureReason {
    LOCATION_DISABLED,          // 端末の位置情報がOFF
    PLAY_SERVICES_UNAVAILABLE,  // GmsCore不在・要更新
    TIMEOUT,
    UNAVAILABLE,                // fixがnull／座標が不正
    BACKGROUND_RESTRICTED       // §95.1 While-in-use。Fusedを呼ばずに返す
}
```

**設計根拠（サイレント障害の型による排除）**: `Location?` を直接返すと「取れなかった」と「取れたが精度が悪い」と「権限がない」が全部 `null` に潰れる。§95.1 が名指しで警告する「サイレントな位置取得失敗（SecurityException／null位置）」を型で不可能にする。Phase 2 の `CalendarResult` と同一の設計原則を踏襲する。

### 5.3 新設: `GeocodingService`（F23 ⇒ ADR②）

```kotlin
interface GeocodingService {
    suspend fun geocode(locationName: String, timeout: Duration = DEFAULT_TIMEOUT): GeocodeResult
}

sealed interface GeocodeResult {
    data class Success(val coordinate: Coordinate) : GeocodeResult
    /** 住所として解決できなかった（会議室名・"Zoom"・URL等）。異常ではない。 */
    data object NoMatch : GeocodeResult
    data class Failure(val reason: GeocodeFailureReason, val cause: Throwable?) : GeocodeResult
}

enum class GeocodeFailureReason { GEOCODER_UNAVAILABLE, NETWORK, TIMEOUT, INVALID_INPUT }
```

**`NoMatch` を `Failure` から分離する理由（Phase 2 §4.5 / P-7 申し送りへの回答）**: `CalendarContract.EVENT_LOCATION` は自由記述であり、「渋谷区…」も「Zoom」も「会議室A」も同じ列に入る。**geocode 不能は多数派の正常系**であり、これを `Failure` にすると (a) retry すべきでないものを retry し、(b) UI が毎回エラーを出し、(c) §95.6 の「retry 1回」規則が意味を失う。`NoMatch` は「移動を伴わない予定」または「手動 Travel Time 入力へ誘導すべき予定」として扱い、**リトライしない・キャッシュする**（T-GEO-8）。

### 5.4 新設: `RoutingException`（F24。S-4 推奨案）

```kotlin
sealed class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured  : RoutingException("Routes API key is not configured")
    class Offline(cause: Throwable?) : RoutingException("Network unavailable", cause)
    class Timeout(cause: Throwable?) : RoutingException("Request timed out", cause)
    class Unauthorized(val httpStatus: Int) : RoutingException("Rejected by Routes API")   // 401/403
    class QuotaExceeded(val httpStatus: Int) : RoutingException("Quota exceeded")          // 429
    class ServerError(val httpStatus: Int) : RoutingException("Routes API server error")   // 5xx
    class NoRoute : RoutingException("No route found")
    class MalformedResponse(cause: Throwable?) : RoutingException("Unparsable response", cause)
}
```

**メッセージに座標・APIキー・イベント情報を含めない**（§58/§60、T-ROUTESVC-10 で検証）。呼び出し側（`DepartureViewModel`）は `else` 節なしの網羅 `when` でUI状態へ写像する（T-DEPVM-9）。

### 5.5 3層分割の境界（L3 は gms/framework 型を外へ出さない）

```kotlin
// L3境界（services/location/）
fun interface RawLocationSource {
    /** @throws SecurityException 権限が実行中に剥奪された場合 */
    suspend fun currentFix(timeout: Duration): LocationFix?
}
data class LocationFix(val lat: Double, val lon: Double, val accuracyMeters: Float?, val fixedAt: Instant)

fun interface GeocoderSource {
    fun isAvailable(): Boolean
    suspend fun lookup(query: String, maxResults: Int, timeout: Duration): List<LocationFix>
}

// L3境界（services/routing/）
fun interface HttpPostClient {
    suspend fun post(url: String, headers: Map<String, String>, body: String, timeout: Duration): HttpTextResponse
}
data class HttpTextResponse(val statusCode: Int, val body: String)

// While-in-use ガード（F30。2026-08-09 Fable 5裁定でGemini G1 CRITICAL対応として判定式を拡張）
fun interface ForegroundGate { fun isLocationAccessAllowed(): Boolean }
```

**`ForegroundGate.isLocationAccessAllowed()` の実装（修正2・§95.1改訂対応）**: `isAppInForeground() || isExecutionServiceRunning()` として実装する。前者は既存の `Application.ActivityLifecycleCallbacks` ベースの起動中Activityカウント判定、後者は仕様§95.1(b)「フォアグラウンドで開始したExecution Mode中のForeground Service…の継続中」を許可するために必要な条件である。**`isExecutionServiceRunning()` はPhase 5でForeground Serviceが導入されるまで常に `false` を返す設定可能フック**（例: `var isExecutionServiceRunning: () -> Boolean = { false }` のような関数プロパティ注入、または個別実装クラスでの固定 `false` オーバーライド）とし、**Phase 5で実配線する**（§15 #10・§16参照）。この拡張により、Phase 5のForeground Service経路が同一 `LocationService` を呼ぶ際に安全性プロパティを後付けする必要がなくなる（S-5 の当初根拠と整合）。

この境界により **L1（純粋写像）・L2（サービスロジック）は JVM テストで `com.google.android.gms.*` にも `HttpURLConnection` にも一切触れない**。Phase 2 の `CursorSource` 3層分割（実装済み・13件Green実測）と同一パターンである。

---

## 6. ファイルフットプリント宣言

### 6.1 新規作成（Phase 3 専有。Phase 2・Phase 4 と重複なし）

| パス（`app/src/main/java/com/actionstarter/` 起点） | 層 | 内容 | 担当 |
|---|---|---|---|
| `services/location/LocationService.kt` | 契約 | F22 interface ＋ `LocationResult` ＋ `LocationFailureReason` | domain-implementer A |
| `services/location/RawLocationSource.kt` | L3境界 | `fun interface` ＋ `LocationFix` | domain-implementer A |
| `services/location/FusedRawLocationSource.kt` | L3 | `FusedLocationProviderClient.getCurrentLocation(CurrentLocationRequest, CancellationToken)` を `suspendCancellableCoroutine` で包む。**ロジックを持たない**（テストは instrumented のみ） | domain-implementer A |
| `services/location/FusedLocationService.kt` | L2 | `RawLocationSource` ＋ `PermissionGate` ＋ `ForegroundGate` をコンストラクタ注入。JVM テスト対象 | domain-implementer A |
| `services/location/ForegroundGate.kt` | 契約 | F30 `fun interface`（S-5 裁定次第） | domain-implementer A |
| `services/location/ActivityLifecycleForegroundGate.kt` | 実装 | F30 `Application.ActivityLifecycleCallbacks` で started カウントし `isAppInForeground()` を判定。`isLocationAccessAllowed()` は `isAppInForeground()` または `isExecutionServiceRunning()` が真のとき許可（`isExecutionServiceRunning` はPhase 5まで常にfalseを返す注入フック。修正2・新規依存ゼロ） | domain-implementer A |
| `services/location/GeocodingService.kt` | 契約 | F23 interface ＋ `GeocodeResult` ＋ `GeocodeFailureReason` | domain-implementer A |
| `services/location/GeocoderSource.kt` | L3境界 | `fun interface` | domain-implementer A |
| `services/location/PlatformGeocoderSource.kt` | L3 | API 33+ = `getFromLocationName(String,Int,GeocodeListener)`／API 26–32 = 同期版（`@Suppress("DEPRECATION")` 理由コメント付き）。分岐は `Build.VERSION.SDK_INT >= TIRAMISU` | domain-implementer A |
| `services/location/AndroidGeocodingService.kt` | L2 | 正規化＋キャッシュ＋結果写像。JVM テスト対象 | domain-implementer A |
| `services/location/LocationNameNormalizer.kt` | L1 | 純粋関数。trim／空白畳み込み／空文字拒否／URIスキーム検出。**ロケール固有語のハードコード禁止（§6）** | domain-implementer A |
| `services/routing/RoutingException.kt` | 契約 | F24 sealed 例外階層 | domain-implementer B |
| `services/routing/HttpPostClient.kt` | L3境界 | `fun interface` ＋ `HttpTextResponse` | domain-implementer B |
| `services/routing/UrlConnectionHttpPostClient.kt` | L3 | `java.net.HttpURLConnection` 実装（**新規依存ゼロ**）。テストは instrumented / opt-in のみ | domain-implementer B |
| `services/routing/RoutesApiRequestBuilder.kt` | L1 | ComputeRoutes リクエストJSON生成（純粋） | domain-implementer B |
| `services/routing/RoutesApiResponseParser.kt` | L1 | レスポンスJSON→`Duration`（純粋。入力は String） | domain-implementer B |
| `services/routing/RoutesApiRoutingService.kt` | L2 | `RoutingService` 実装。`HttpPostClient` 注入。JVM テスト対象 | domain-implementer B |
| `services/routing/CachingRoutingService.kt` | L2 | F25。`RoutingService` デコレータ。スロットル／キャッシュ／retry 1回 | domain-implementer B |
| `services/routing/RouteEstimateCache.kt` | L1/L2 | メモリ内キャッシュ（**永続化なし**。Phase 10 まで持ち込まない） | domain-implementer B |
| `services/routing/UnconfiguredRoutingService.kt` | L2 | F29。常に `RoutingException.NotConfigured` を投げる。**偽のETAを返さない** | domain-implementer B |
| `services/routing/GeoDistance.kt` | L1 | Haversine 距離（純粋）。F25 の距離閾値判定用 | domain-implementer B |
| `features/departure/TravelTimeInput.kt` | UI | F28 手動 Travel Time 入力 Composable | ui-implementer |
| `features/departure/TransportModeSelector.kt` | UI | F26 4値セレクタ Composable | ui-implementer |
| `app/src/test/java/com/actionstarter/services/location/*` | test | F21〜F23・F30 のテスト | test-writer |
| `app/src/test/java/com/actionstarter/services/routing/*` | test | F24・F25・F29 のテスト | test-writer |
| `app/src/test/java/com/actionstarter/features/Departure*Test.kt`（新規分） | test | F26〜F28 のテスト | test-writer |
| `app/src/androidTest/java/com/actionstarter/e2e/RoutingLocationE2ETest.kt` | test | T-E2E3-1〜4 | test-writer |
| `app/src/androidTest/java/com/actionstarter/e2e/RoutesApiLiveTest.kt` | test | F31 opt-in（`Assume.assumeTrue(BuildConfig.ROUTES_API_KEY.isNotEmpty())`） | test-writer |
| `scripts/emu-geo-fix.sh` | 補助 | E2E用モック位置投入（Phase 2 の seed スクリプトと同様に**エミュレータ判定ガード必須**） | domain-implementer B |

**空プレースホルダ禁止（§88）**: `services/notification/`・`persistence/` は Phase 3 でも作らない（Phase 5／10 まで）。

### 6.2 Phase 3 が**変更する**既存ファイル（専有・共有ファイル以外）

**前提: Phase 4のDeparture結線完了**（Fable 5裁定2026-08-09）。下表3行はいずれも`features/departure/`配下であり、Phase 4のP4-C5（`SharedPlanViewModel`注入・計画時点値のマッピング）が先行完了した後に着手する（§12 P3-C5参照）。

| パス | 変更内容 | 担当 |
|---|---|---|
| `features/departure/DepartureUiState.kt` | 状態拡張（`transportMode` / `isEtaStale` / `etaFailureReason` / `permissionState` / `manualTravelMinutes`）。**前提: Phase 4のDeparture結線完了** | ui-implementer |
| `features/departure/DepartureViewModel.kt` | `LocationService`／`GeocodingService`／`RoutingService` の注入と再計算ロジック（scaffold の TODO を実装）。**前提: Phase 4のDeparture結線完了** | ui-implementer |
| `features/departure/DepartureScreen.kt` | セレクタ・手動入力・縮退表示の分岐（巨大Composable禁止＝§89。状態ごとに関数分割）。**前提: Phase 4のDeparture結線完了** | ui-implementer |

### 6.3 **非重複の明示（本節が本メモの必須要件）**

- **Phase 2 所管との非重複**: Phase 3 は `services/calendar/` 配下の**いかなるファイルも作成・変更・削除しない**。`features/eventselection/` 配下（`EventSelectionScreen.kt` / `EventSelectionUiState.kt` / `EventSelectionViewModel.kt` / `ManualEventEntry.kt`）も**一切触れない**。`services/permission/`（`PermissionGate.kt` / `AndroidPermissionGate.kt`）は Phase 2 の成果物を**読むだけ**で再利用し、変更しない（`isGranted(permission: String)` が汎用シグネチャのため位置権限にそのまま使える）。
- **Phase 4 所管との非重複**: Phase 3 は `planning/`（`PlanningEngine.kt` 他）を**作成・変更しない**。`mock/MockPlanFactory.kt` を**変更・削除しない**。`mock/MockRecoveryFactory.kt` も同様。`domain/model/PlanningContext.kt`・`domain/valueobject/RouteEstimate.kt`・`Coordinate.kt`・`TransportMode.kt` を**変更しない**（Phase 4 が `travelEstimate: Duration?` 経由で Phase 3 の成果を受け取る境界は既に存在する）。
- **`mock/` への唯一の接触**: `mock/MockRoutingService.kt` の**削除のみ**（KDoc に「Phase 2でGoogle Maps Platform Routes API等の実Provider実装に置き換わり次第、本クラスは削除する」と明記済み。実際には Phase 3 が置き換え先）。`mock/` 配下の他ファイルには触れない。**削除の代替は `UnconfiguredRoutingService`** であり、キー未設定時に固定20分という偽値を返し続ける現状（サイレント障害）を解消する。
  - **既存テスト更新承認要請（TEAMS §2）**: `MockRoutingService` を直接参照するテストファイルは grep 実測で**0件**（参照は `di/AppContainer.kt` のみ）。ただし `app/src/test/java/com/actionstarter/features/DepartureScreenTest.kt`（既存4件 T-DEP-1〜4）は `DepartureUiState` のシグネチャ変更に追随する**更新**が必要。assertion 強度は維持し弱体化しない。

### 6.4 共有ファイルへの変更 — **統合ウィンドウで直列実施**

以下は TEAMS §5「共有ファイル所有権」に該当し、**domain-implementer（integration owner）が P3-C1（基盤）と P3-C6（統合）でのみ直列に編集する**。P3-C3/C4/C5 の並列実装中は**いかなる agent も触れない**。

| # | 共有ファイル | 変更内容 | 実施サイクル |
|---|---|---|---|
| 1 | `gradle/libs.versions.toml` | `playServicesLocation = "21.4.0"` 追加。`google-play-services-location` ライブラリエントリ追加。**ADR記録トリガー④** | P3-C1 |
| 2 | `app/build.gradle.kts` | (a) `implementation(libs.google.play.services.location)`、(b) `local.properties` 読取→`buildConfigField("String","ROUTES_API_KEY", ...)`（未設定時は `""`）、(c) 必要なら `androidTestImplementation` 追加 | P3-C1 |
| 3 | `app/src/main/AndroidManifest.xml` | `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>`（＋ S-1 裁定次第で `ACCESS_COARSE_LOCATION`）。**`ACCESS_BACKGROUND_LOCATION` は追加禁止（§58）**。**ADR記録トリガー⑤** | P3-C1 |
| 4 | `di/AppContainer.kt` | `locationService` / `geocodingService` / `routingService`（`CachingRoutingService(RoutesApiRoutingService(...))` または `UnconfiguredRoutingService`）の供給。`MockRoutingService` import 削除。`DepartureViewModel` の initializer 更新。**単一 Factory 集約（ADR-0003/0014 の保護条件）を維持すること** | P3-C6 |
| 5 | `navigation/ActionStarterNavHost.kt` | 位置権限 launcher の保持と ON_RESUME 再チェックの結線、`SharedPlanViewModel.confirmedPlan` から departure route への受け渡し | P3-C6 |
| 6 | `ActionStarterApplication.kt` | F30 `ActivityLifecycleForegroundGate` の登録（S-5 裁定次第） | P3-C1 |
| 7 | `app/src/main/res/values/strings.xml` / `values-ja/strings.xml` | 位置権限説明・拒否時案内・手動Travel Time入力・transport mode 4値ラベル・ETA精度低下注記・geocode不能案内。**両ファイルを同時に更新**（`StringResourceParityTest` が既存3件で検査） | P3-C1（キー定義のみ）／P3-C6（文言確定） |
| 8 | `local.properties` | `MAPS_ROUTES_API_KEY=...`。**リポジトリ非コミット**（`.gitignore` に `local.properties` が既に存在することを実測確認済み）。**ユーザー作業**（§13） | ユーザー |

**UI/Domain 並列時の所有権規則（Phase 1・2 の規則をそのまま適用）**: 上表 1〜7 の既定所有者は domain-implementer のみ。ui-implementer は P3-C5 の間これらに一切触れず、必要が生じたら中断して Fable 5 へ報告する。

---

## 7. 依存関係・技術選定の根拠

### 7.1 追加依存は 1 本のみ（実測済み）

| 用途 | 採用 | 追加依存 | 実測根拠 |
|---|---|---|---|
| 現在地取得 | `com.google.android.gms:play-services-location` **21.4.0** | **追加（唯一）** | **実測済み（2026-08-09）**: Google Maven の `maven-metadata.xml` で 21.4.0 が最新（lastUpdated 20260625）。AAR の `META-INF/com/android/build/gradle/aar-metadata.properties` が `minCompileSdk=1` / `minCompileSdkExtension=0` / `minAndroidGradlePluginVersion=1.0.0` → **compileSdk 35・AGP 8.13.2 のまま導入可能。ADR-0011 型の minCompileSdk 地雷は存在しない**。推移依存 `play-services-base:18.9.0` / `play-services-basement:18.9.0` / `play-services-tasks:18.4.0` も**すべて `minCompileSdk=1`** を実測確認 |
| Geocoding | `android.location.Geocoder`（Framework） | なし | **実測済み**: android-35 の `android.jar` を javap 検証。`getFromLocationName(String,int,GeocodeListener)` は `api-versions.xml` で `since="33"`、`getFromLocationName(String,int)` は `deprecated="33"`（削除はされていない）、`isPresent()` は `since="9"`。**minSdk 26 のため両パス実装が必須** |
| Task→coroutine 変換 | `kotlinx.coroutines.suspendCancellableCoroutine` ＋ `CancellationTokenSource` | なし | `kotlinx-coroutines-play-services` は**追加しない**（`Task.await()` のためだけに1本増やす価値がない＝§88）。`FusedLocationProviderClient.getCurrentLocation(CurrentLocationRequest, CancellationToken): Task<Location>` の存在は**21.4.0 の classes.jar を javap で実測確認済み**。`CurrentLocationRequest.Builder`（`setPriority`/`setDurationMillis`/`setMaxUpdateAgeMillis`/`setGranularity`）と `Priority.PRIORITY_BALANCED_POWER_ACCURACY` も実測確認済み |
| コルーチン | `kotlinx-coroutines-core` | なし | **実測済み**: `:app:dependencies --configuration debugRuntimeClasspath` で **1.9.0 に解決済み**（推移依存）。`Dispatchers.IO` は追加依存なしで使用可（Phase 2 の未解決 probe **P-2 を本メモで解消**）。play-services-location が宣言する 1.7.3 は 1.9.0 へ上書き解決されるため衝突しない |
| HTTP | `java.net.HttpURLConnection`（JDK/Framework） | なし | OkHttp/Retrofit/Ktor は**採用しない**。理由: (a) 単一エンドポイントへの単一 POST に SDK を1本足すのは §88 判定で No、(b) 新規 AAR は ADR-0011 型リスクの再燃、(c) `HttpPostClient` 抽象を挟むので後日の差し替えコストはゼロ。**INFORMATIONAL**: Phase 7（モデルDL）で本格的な HTTP クライアントが必要になった時点で再評価する |
| JSON | `org.json`（Framework）を **L3/L1 の内側に閉じ込める** | なし | Robolectric 上で `org.json` の実装が動くかは **要検証（P3-P4）**。不成立時のフォールバックは (a) L1 パーサのテストを instrumented へ移す、(b) `kotlinx-serialization-json`（**AAR ではなく JVM jar のため minCompileSdk 制約を持たない**）＋ `kotlin-serialization` plugin 追加＝ADR④。**推奨は (a)**（依存追加ゼロ） |
| 権限照会 | Phase 2 の `PermissionGate` を再利用 | なし | 新規抽象を作らない |

**minSdk との関係**: minSdk 26（ADR-0007）。ACCESS_FINE_LOCATION は API 23 以降 dangerous permission のため常に実行時要求（API 22 以下分岐は不要）。Geocoder のみ API 33 分岐が必要（上表）。

### 7.2 Routes API の呼び出し仕様（**要検証 P3-P6**）

**実測で確認できたこと（2026-08-09）**: `POST https://routes.googleapis.com/directions/v2:computeRoutes` は APIキーなしで HTTP **403**（`Method doesn't allow unregistered callers`）を返す一方、存在しないメソッド `directions/v2:bogusMethod` は HTTP **404** を返す。**エンドポイントのホストとメソッドパスは実在する**ことをこの差分から確認した。

**未検証（実装着手時に Context7 MCP → 公式ドキュメントで確定させること。CLAUDE.md「ライブラリ調査は Context7 を最初に」に従う）**:
- 認証ヘッダ名 `X-Goog-Api-Key`、および **`X-Goog-FieldMask` ヘッダが必須である**という理解
- リクエスト body のフィールド名（`origin.location.latLng.{latitude,longitude}` / `destination...` / `travelMode` / `departureTime` / `routingPreference`）
- `travelMode` の列挙値（`DRIVE` / `WALK` / `BICYCLE` / `TRANSIT`）と `TransportMode` 4値との対応
- レスポンス `routes[].duration` が `"1234s"` 形式の文字列であること
- `routingPreference: TRAFFIC_AWARE` が DRIVE 系専用であり **別SKU（高価）** であること → **MVP では指定しない**方針（§95.2 コスト方針）

**§95.2 の送信データ制約（実装時の必須確認項目）**: リクエストに含めてよいのは**座標・移動手段・出発時刻のみ**。`ExecutionEvent.title` / `notes` / `locationName` を body・ヘッダ・クエリのいずれにも載せない（T-ROUTESVC-10・G4 コード差分レビュー観点）。

### 7.3 APIキーの取り扱い（F29・§89「No hard-coded secrets」）

```
local.properties (gitignore済み・実測確認)   →   app/build.gradle.kts が読取
   MAPS_ROUTES_API_KEY=AIza...                     ↓
                                    buildConfigField("String","ROUTES_API_KEY", "\"$key\"")
                                                   ↓
                              AppContainer: key.isEmpty() ? UnconfiguredRoutingService
                                                          : CachingRoutingService(RoutesApiRoutingService(key, ...))
```

- `buildFeatures { buildConfig = true }` は**既に有効**（Phase 1 C6 で設定済み・実測確認）。
- **キー未設定でもアプリはビルド・起動し、全 JVM テストが Green になる**。JVM テストは 1件も実キーを必要としない（すべて fake `HttpPostClient`）。
- **残存リスク（ユーザーへ明示すること）**: BuildConfig の文字列は APK から自明に取り出せる。実効的な統制は「キー制限＋クォータ上限」であり、根本解決はサーバサイドプロキシ（§88 により MVP スコープ外）。§13 の依頼文に明記する。

---

## 8. スロットリング／キャッシュ規則（§95.2 の「義務」の具体化 ⇒ ADR②）

**キャッシュキー**: `(destination: Coordinate, mode: TransportMode)`。**保存値**: `RouteEstimate` ＋ 計算時の `origin: Coordinate` ＋ `departureDate: Instant`。

| 判定項目 | 規則 | 根拠 | 検証 |
|---|---|---|---|
| 移動距離閾値 | `haversine(cachedOrigin, newOrigin) < 500m` ならキャッシュ再利用候補。**500m ちょうどは再計算**（`< 500` の狭義不等号で境界を固定） | §95.2「例: 500m」 | T-CACHE-2/3 |
| 最小間隔 | `now - cached.computedAt < 10分` なら再利用候補。**10分ちょうどは再計算** | §95.2「例: 10分」 | T-CACHE-2/4 |
| 目的地一致 | 完全一致（`Coordinate` の等価性）。不一致なら必ず再計算 | 自明 | T-CACHE-5 |
| 移動手段一致 | 完全一致。不一致なら必ず再計算 | §9 | T-CACHE-6 |
| 出発時刻の許容差 | `abs(newDepartureDate - cached.departureDate) >= 10分` なら再計算。**TRANSIT は所要時間が出発時刻に依存する**（ADR-0004 が `departureDate` 引数を残した理由そのもの） | §9・ADR-0004。閾値そのものは**仕様未定義**のため補完＝ADR② | T-CACHE-7 |
| 再利用の成立条件 | 上記5条件が**すべて**成立した場合のみキャッシュを返す（AND） | §95.2 | T-CACHE-2 |
| retry | API 呼び出し失敗時 **1回だけ** retry。2回目以降は行わない | §95.6 経路取得行 | T-CACHE-8/10 |
| retry 後も失敗 | キャッシュがあればそれを返す。**`computedAt` を現在時刻へ書き換えない**（stale を新鮮と詐称しない） | §95.6・サイレント障害防止 | T-CACHE-8 |
| キャッシュもない | `RoutingException` を再送出する。**0分・固定20分などの偽値を返さない** | §95.6・§89 | T-CACHE-9 |
| 並行呼び出し | `Mutex` で直列化し、同一キーへの同時API発行を防ぐ | コスト保護（§95.2） | T-CACHE-11 |
| 永続化 | **しない**（プロセス内メモリのみ。プロセス再生成でキャッシュは失われる） | §74 Phase 10 まで持ち込まない（§88） | 設計注記 |
| 決定的計算との関係 | Basic Engine（Phase 4）は `Duration` だけを受け取り、それがキャッシュ由来か新規かを問わず成立する | **§95.2「決定的な時刻演算はキャッシュ値でも常に成立させる」** | 設計注記 |

**UI への stale 表示**: `RouteEstimate.computedAt` が閾値（10分）より古い場合に `DepartureUiState.isEtaStale = true` とし、§95.6 が要求する「ETA精度が低下する旨を画面上に明示する」を満たす（T-DEPVM-4・T-DEP2-2）。**新しい型を追加せずに既存フィールドで表現できる**点が `RouteEstimate` を変更しない根拠でもある。

---

## 9. テストケース表（全81件：正常系20／異常系27／エッジケース34）

### 9.1 分類定義と source set 方針

| 分類 | source set | runner | Gradleタスク | 必要端末 |
|---|---|---|---|---|
| 純粋ロジック（距離・正規化・JSON生成/解析） | `src/test` | JUnit4（純JVM） | `:app:testDebugUnitTest` | 不要 |
| Service 結線（fake `RawLocationSource`/`GeocoderSource`/`HttpPostClient`） | `src/test` | JUnit4 ＋ Robolectric（権限shadow用）＋ `kotlinx-coroutines-test` | `:app:testDebugUnitTest` | 不要 |
| キャッシュ／スロットル（仮想時間） | `src/test` | JUnit4 ＋ `TestScope`／注入した `Clock` | `:app:testDebugUnitTest` | 不要 |
| ViewModel・Compose（Departure画面） | `src/test` | JUnit4 ＋ Robolectric ＋ Compose Test | `:app:testDebugUnitTest` | 不要 |
| instrumented E2E | `src/androidTest` | AndroidJUnitRunner ＋ Compose Test | `:app:connectedDebugAndroidTest` | 必要（AVD `actionstarter_test`。`adb emu geo fix` でモック位置投入） |
| opt-in 実API疎通 | `src/androidTest` | AndroidJUnitRunner ＋ `Assume` | `:app:connectedDebugAndroidTest --tests '*RoutesApiLiveTest'` | 必要（＋実APIキー） |

全実行は `--console=plain`、ログは `build/agent-logs/` へ保存する。**JVM系76件が G2 Red 対象。E2E系4件（T-E2E3-*）は作成のみで実行は G4-E。opt-in 1件（T-OPTIN-1）はキー存在時のみ G4-E 補遺。**

### 9.2 F25補助 — `GeoDistance` / `LocationNameNormalizer` / Routes JSON（純JVM／`src/test`／`:app:testDebugUnitTest`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-GEODIST-1 | 正常 | 既知の2点間距離が許容誤差（±0.5%）内 | GeoDistance |
| T-GEODIST-2 | エッジ | 同一座標 → 0m（NaN にならない） | GeoDistance |
| T-GEODIST-3 | エッジ | 経度180度線を跨ぐ2点で最短距離（地球一周側にならない） | GeoDistance |
| T-GEONORM-1 | 正常 | 前後空白 trim・連続空白の畳み込み | LocationNameNormalizer |
| T-GEONORM-2 | 異常 | 空文字／空白のみ → `INVALID_INPUT`（Geocoder を呼ばない） | LocationNameNormalizer |
| T-GEONORM-3 | エッジ | `https://...` 等の URI スキーム付き文字列 → Geocoder を呼ばず `NoMatch` 相当で早期返却（§4.5 境界） | LocationNameNormalizer |
| T-GEONORM-4 | エッジ | 非ラテン文字（日本語住所）が破壊・正規化除去されない（§6/§7） | LocationNameNormalizer |
| T-ROUTEREQ-1 | 正常 | origin/destination/mode/departureTime がリクエスト JSON へ正しく写像される | RoutesApiRequestBuilder |
| T-ROUTEREQ-2 | 正常 | `TransportMode` 4値すべてが Routes API の travelMode 値へ写像される（漏れなし） | RoutesApiRequestBuilder |
| T-ROUTEREQ-3 | エッジ | `departureDate` が過去 → 規則どおりに処理される（規則を固定してロック） | RoutesApiRequestBuilder |
| T-ROUTEPARSE-1 | 正常 | `duration: "1234s"` → `Duration.ofSeconds(1234)` | RoutesApiResponseParser |
| T-ROUTEPARSE-2 | 異常 | `routes` 配列が空 → `NoRoute`（0秒へ潰さない） | RoutesApiResponseParser |
| T-ROUTEPARSE-3 | 異常 | 不正 JSON → `MalformedResponse`（例外を握り潰さない） | RoutesApiResponseParser |
| T-ROUTEPARSE-4 | エッジ | `duration` フィールド欠損 → `MalformedResponse` | RoutesApiResponseParser |
| T-ROUTEPARSE-5 | エッジ | 想定外の余剰フィールドがあっても解析が継続する（前方互換） | RoutesApiResponseParser |

### 9.3 F22/F30 — `FusedLocationService`（fake `RawLocationSource`／Robolectric／`src/test`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-LOCSVC-1 | 正常 | fix 取得 → `Success(Coordinate, accuracy, fixedAt)` | FusedLocationService |
| T-LOCSVC-2 | 異常 | 権限未許可（`denyPermissions`）→ `PermissionDenied`。**`RawLocationSource` を呼ばない** | FusedLocationService |
| T-LOCSVC-3 | 異常 | `SecurityException`（実行中の剥奪）→ `PermissionDenied`（例外を外へ漏らさない） | FusedLocationService |
| T-LOCSVC-4 | 異常 | fix が null → `Failure(UNAVAILABLE)`（`Coordinate(0,0)` へ潰さない） | FusedLocationService |
| T-LOCSVC-5 | 異常 | タイムアウト超過 → `Failure(TIMEOUT)` | FusedLocationService |
| T-LOCSVC-6 | エッジ | coroutine キャンセル → `CancellationToken` が発火し即中断 | FusedLocationService |
| T-LOCSVC-7 | 異常 | `ForegroundGate.isLocationAccessAllowed() == false`（Activityフォアグラウンドでなく、かつExecution Service非稼働のときのみ）→ `Failure(BACKGROUND_RESTRICTED)`。**Fused を呼ばない**（§95.1改訂・修正2） | FusedLocationService |
| T-LOCSVC-8 | エッジ | fix の緯度経度が範囲外／NaN → `Failure(UNAVAILABLE)`（`Coordinate` の init 例外を外へ漏らさない） | FusedLocationService |

### 9.4 F23 — `AndroidGeocodingService`（fake `GeocoderSource`／`src/test`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-GEO-1 | 正常 | 住所文字列 → `Success(Coordinate)` | AndroidGeocodingService |
| T-GEO-2 | エッジ | 複数候補 → 先頭を採用し、同入力で常に同結果（決定性） | AndroidGeocodingService |
| T-GEO-3 | 異常 | 候補0件 → **`NoMatch`（`Failure` ではない）** | AndroidGeocodingService |
| T-GEO-4 | 異常 | `IOException`（ネットワーク断）→ `Failure(NETWORK)` | AndroidGeocodingService |
| T-GEO-5 | 異常 | `isAvailable() == false`（`Geocoder.isPresent()` 相当）→ `Failure(GEOCODER_UNAVAILABLE)` | AndroidGeocodingService |
| T-GEO-6 | 異常 | タイムアウト → `Failure(TIMEOUT)` | AndroidGeocodingService |
| T-GEO-7 | エッジ | 同一文字列2回目 → キャッシュ命中で `GeocoderSource` を呼ばない | AndroidGeocodingService |
| T-GEO-8 | エッジ | `NoMatch` もキャッシュされ、2回目に再照会しない（無駄な I/O 抑止） | AndroidGeocodingService |
| T-GEO-9 | エッジ | `@Config(sdk = [33])` で API 33 系（非同期）が `Success` へ収束する。**P3-C6追補**: 当初は `@Config(sdk = [26, 33])` で API 26 系（同期）との収束も検証する設計だったが、Robolectric 4.16.1実測で `sdk=26` 側が `expected:<1> but was:<0>` により失敗することを確認し、`sdk = [33]` のみへ縮小した（詳細は`PlatformGeocoderSourceTest`のクラスKDoc、および本書§15 #12・§16参照） | PlatformGeocoderSource |
| T-GEO-10 | 異常 | 空文字入力 → `Failure(INVALID_INPUT)`。`GeocoderSource` 不呼出（**P3-C6追補・計画書§9.4追補、Fable 5承認2026-08-09**） | AndroidGeocodingService |
| T-GEO-11 | エッジ | `https://` 入力 → `NoMatch`。`GeocoderSource` 不呼出（**P3-C6追補・計画書§9.4追補、Fable 5承認2026-08-09**） | AndroidGeocodingService |

### 9.5 F24/F29 — `RoutesApiRoutingService`（fake `HttpPostClient`／`src/test`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-ROUTESVC-1 | 正常 | HTTP 200 → `RouteEstimate(duration, mode, computedAt)` | RoutesApiRoutingService |
| T-ROUTESVC-2 | 正常 | APIキーが認証ヘッダで送られ、**リクエスト body・URLクエリに含まれない** | RoutesApiRoutingService |
| T-ROUTESVC-3 | 異常 | HTTP 403/401 → `RoutingException.Unauthorized` | RoutesApiRoutingService |
| T-ROUTESVC-4 | 異常 | HTTP 429（**quota超過**）→ `RoutingException.QuotaExceeded` | RoutesApiRoutingService |
| T-ROUTESVC-5 | 異常 | HTTP 5xx → `RoutingException.ServerError` | RoutesApiRoutingService |
| T-ROUTESVC-6 | 異常 | `IOException`（**オフライン**）→ `RoutingException.Offline` | RoutesApiRoutingService |
| T-ROUTESVC-7 | 異常 | **タイムアウト** → `RoutingException.Timeout` | RoutesApiRoutingService |
| T-ROUTESVC-8 | 異常 | `UnconfiguredRoutingService` → `RoutingException.NotConfigured`。**HTTP を1回も発行しない** | UnconfiguredRoutingService |
| T-ROUTESVC-9 | エッジ | 例外発生時も HTTP 接続／ストリームが確実に close される | UrlConnectionHttpPostClient（fake検証） |
| T-ROUTESVC-10 | エッジ | 例外メッセージ・ログに APIキー・座標・イベント情報が含まれない（§58/§60/§95.2） | RoutesApiRoutingService |

### 9.6 F25 — `CachingRoutingService`（注入 `Clock`／`src/test`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-CACHE-1 | 正常 | 初回呼び出しは委譲先 API を呼び、結果を返しキャッシュする | CachingRoutingService |
| T-CACHE-2 | エッジ | 移動499m・経過9分 → **API 未呼び出し**でキャッシュ返却（**スロットリング境界・両閾値未満**） | CachingRoutingService |
| T-CACHE-3 | エッジ | 移動 **500m ちょうど** → API 呼び出し（境界の包含規則を固定） | CachingRoutingService |
| T-CACHE-4 | エッジ | 経過 **10分ちょうど** → API 呼び出し（**キャッシュ有効期限境界**） | CachingRoutingService |
| T-CACHE-5 | エッジ | 目的地が異なる → キャッシュ不使用 | CachingRoutingService |
| T-CACHE-6 | エッジ | `TransportMode` が異なる → キャッシュ不使用 | CachingRoutingService |
| T-CACHE-7 | エッジ | `departureDate` 差が10分以上 → キャッシュ不使用（TRANSIT の出発時刻依存性） | CachingRoutingService |
| T-CACHE-8 | 異常 | API 失敗 → retry 1回 → なお失敗ならキャッシュ返却。**`computedAt` は古い値のまま**（stale を詐称しない） | CachingRoutingService |
| T-CACHE-9 | 異常 | API 失敗かつキャッシュ無し → `RoutingException` を再送出（偽値を返さない） | CachingRoutingService |
| T-CACHE-10 | エッジ | retry は **1回だけ**（3回目の呼び出しが発生しない） | CachingRoutingService |
| T-CACHE-11 | エッジ | 同一キーへの並行呼び出しで API 発行が二重化しない（`Mutex`） | CachingRoutingService |

### 9.7 F26/F27/F28 — `DepartureViewModel`（Robolectric ＋ Compose Test／`src/test`／端末不要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-DEPVM-1 | 正常 | 権限あり・キーあり → Loading → Content（ETA・Buffer 算出、§29/§35 Screen4） | DepartureViewModel |
| T-DEPVM-2 | 異常 | 位置権限拒否 → 手動 Travel Time 入力状態（空表示にしない・§95.4） | DepartureViewModel |
| T-DEPVM-3 | 異常 | 経路取得失敗かつキャッシュ無し → 精度低下明示＋手動入力導線（§95.6） | DepartureViewModel |
| T-DEPVM-4 | エッジ | キャッシュ返却時 `isEtaStale == true`（`computedAt` 由来） | DepartureViewModel |
| T-DEPVM-5 | エッジ | geocode `NoMatch`（会議室名等）→ エラー表示ではなく手動入力導線＋説明文言 | DepartureViewModel |
| T-DEPVM-6 | エッジ | `arrivalBuffer` が負 → 遅刻警告を表示するが**自動補正しない**（§34 ユーザー最終決定） | DepartureViewModel |
| T-DEPVM-7 | エッジ | **DST 跨ぎ ETA**: `America/New_York` の切替日を跨ぐ予定でも、`Instant` 演算による ETA のローカル表示が1時間ずれない（§6 により日本＝DST なしを前提にしない） | DepartureViewModel |
| T-DEPVM-8 | 正常 | `TransportMode` 変更で再計算が **1回だけ** 走る（多重発火しない） | DepartureViewModel |
| T-DEPVM-9 | 異常 | `RoutingException` の全サブクラスが UI 状態へ網羅的に写像される（`when` に `else` なし＝サイレント障害防止） | DepartureViewModel |
| T-DEP2-1 | 正常 | ETA・Event start・Buffer が表示される（§35 Screen 4 の3要素） | DepartureScreen |
| T-DEP2-2 | エッジ | stale ETA 時に「精度が低下している」注記が表示される（§95.6） | DepartureScreen |
| T-DEP2-3 | 異常 | 手動 Travel Time 入力 → ETA が再表示される | TravelTimeInput |
| T-DEP2-4 | 正常 | TransportMode 4択が表示され、選択でラムダが1回呼ばれる | TransportModeSelector |
| T-DEP2-5 | エッジ | ja/en 双方で新規文言が非空かつ相互に異なる（§7） | DepartureScreen(i18n) |
| T-DEP2-6 | エッジ | 遅刻警告が色のみでなくテキストでも伝達される（§63 color-only 情報禁止） | DepartureScreen |
| T-PERM3-1 | 正常 | 未許可の初回表示で **system dialog を自動起動しない**（launcher 呼び出し0回・§95.4） | DepartureScreen |
| T-PERM3-2 | 正常 | 事前説明カードのボタンタップで権限要求ラムダが1回だけ呼ばれる | DepartureScreen |
| T-PERM3-3 | 異常 | 拒否 → 手動 Travel Time 入力 ＋ Settings 導線の両方が表示される | DepartureScreen |
| T-PERM3-4 | エッジ | 拒否後 ON_RESUME で許可へ変化 → 自動で再計算し ETA 表示へ復帰する。**P3-C6追補**: アサーションを絶対値（`1`）から差分形式（`callCountAfterInitialLoad + 1`）へ修正（Fable 5承認2026-08-09: T-DEPVM-8の差分規約に統一・`DepartureViewModel.init`のconfirmedPlan購読開始時automatic recalculateを未考慮だった漏れの修正。詳細は本書§16参照） | DepartureScreen/ViewModel |
| T-PERM3-5 | エッジ | COARSE のみ許可（precise 拒否）時の挙動が定義どおり（**S-1 裁定に依存**） | DepartureScreen/ViewModel |
| T-CFG-1 | 正常 | キーあり → `AppContainer` が `CachingRoutingService(RoutesApiRoutingService)` を供給する | AppContainer |
| T-CFG-2 | エッジ | キーが空文字 → `UnconfiguredRoutingService` を供給し、ビルド・テストが成立する | AppContainer |
| T-CFG-3 | エッジ | `local.properties` に `MAPS_ROUTES_API_KEY` が無くても Gradle 構成が失敗しない | ビルド構成 |

### 9.8 instrumented E2E（`src/androidTest`／`:app:connectedDebugAndroidTest`／エミュレータ必要）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-E2E3-1 | 正常 | `adb emu geo fix` で投入した現在地＋Phase 2 の seed 済みイベントで、Departure 画面に ETA が表示される（**§67 完成条件の実測**） | E2Eフロー |
| T-E2E3-2 | 異常 | 位置権限拒否状態で起動 → 手動 Travel Time 入力で継続動作する（GOAL.md D(3)/F） | E2Eフロー |
| T-E2E3-3 | 異常 | 機内モード（`svc wifi disable` / `svc data disable`）→ 経路取得失敗の縮退表示が出てクラッシュしない（§95.6） | E2Eフロー |
| T-E2E3-4 | 正常 | ja/en 両ロケールで Departure 画面のスクリーンショットを取得（GOAL.md D/E） | E2Eフロー(i18n) |

### 9.9 opt-in 実API疎通（`src/androidTest`／キー存在時のみ）

| ID | 区分 | 内容・期待値 | 対象 |
|---|---|---|---|
| T-OPTIN-1 | 正常 | `Assume.assumeTrue(BuildConfig.ROUTES_API_KEY.isNotEmpty())` で skip 可能。キーがある場合のみ実 Routes API を1回呼び、200 と正の `Duration` を確認する | RoutesApiLiveTest |

**E2E群・opt-in は実行するまで pass として報告することを禁止**し、G2／G3 の証拠に含めない（実行は G4-E のみ）。

### 9.10 テストではなく「ゲート検証手順」で担保する項目（裁定B9 の先例に従う）

Manifest 検証はマージ後に常に Green でありRed化不能のため、P3-C7（G4-JVM）で quality-runner がマージ済みマニフェスト成果物（`build/intermediates/merged_manifests/{debug,release}/AndroidManifest.xml`）をスクリプト検証する。

1. debug/release 両変種に `ACCESS_FINE_LOCATION` が含まれる
2. **どの変種にも `ACCESS_BACKGROUND_LOCATION` が含まれない**（§58 の必須要件）
3. `ACCESS_COARSE_LOCATION` の有無が S-1 裁定と一致する
4. APIキー文字列が `AndroidManifest.xml` および `res/` 配下に出現しない（`grep -r`）

---

## 10. エラー＆レスキューマップ（全22行。ハンドリング方法列に空欄なし）

| # | 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|---|
| 1 | Departure 画面初回表示 | ACCESS_FINE_LOCATION 未許可 | `PermissionRequired` 状態へ写像し、事前説明カードと明示ボタンを表示。system dialog は自動起動しない（§95.4） | 何のために位置が要るかを理解した上で許可を選べる。拒否しても画面は機能する |
| 2 | 位置権限リクエスト | ユーザーが「許可しない」を選択 | `PermissionDenied` へ写像。手動 Travel Time 入力＋再要求ボタン＋アプリ設定導線を同時提示（§95.4/§95.6） | 自動 ETA は得られないが、手動入力で Departure 判断は継続できる |
| 3 | 位置権限の再リクエスト | 永久拒否で dialog が出ず即 `false` が返る | 状態を `PermissionDenied` のまま維持し「ダイアログが表示されない場合は端末の設定から許可してください」＋Settings 導線を常時併記（無反応ボタンにしない） | ボタンが効かない理由が画面上で説明される |
| 4 | 位置権限（precise 拒否） | COARSE のみ許可された（Android 12+） | 取得した精度（`accuracyMeters`）を UI に反映し、ETA が概算である旨を明示する（S-1 裁定に従い実装） | 精度が落ちることを認識した上で使える |
| 5 | 現在地取得 | 実行中に権限が剥奪され `SecurityException` | `LocationResult.PermissionDenied` へ写像し拒否 UI へ遷移。catch するが握り潰さず状態として表出させる | ETA が消えた理由が表示され、手動入力へ切り替えられる |
| 6 | 現在地取得 | fix が null（屋内・測位不能） | `Failure(UNAVAILABLE)`。`Coordinate(0,0)` に潰さず、「現在地を取得できません」＋再試行＋手動入力導線を表示 | 誤った ETA を信じてしまう事故が起きない |
| 7 | 現在地取得 | タイムアウト（既定10秒） | `Failure(TIMEOUT)`。retry ボタンと手動入力導線を提示。自動 retry はしない（電池・§58） | 待たされ続けず、次の手段が即座に選べる |
| 8 | 現在地取得 | 端末の位置情報設定が OFF | `Failure(LOCATION_DISABLED)`。端末位置情報設定への導線を提示 | 何を直せばよいかが分かる |
| 9 | 現在地取得 | Google Play services 不在・要更新の端末 | `Failure(PLAY_SERVICES_UNAVAILABLE)`。手動 Travel Time 入力へフォールバックし、アプリは停止しない（§95.3 端末断片化の思想を継承） | Play services が無い端末でも Basic Engine 全機能を使える |
| 10 | 現在地取得 | **アプリがバックグラウンドの状態で呼ばれた**（While-in-use 制約・§95.1） | `ForegroundGate.isLocationAccessAllowed()`（`isAppInForeground()` または `isExecutionServiceRunning()` が真なら許可。§95.1(b)のExecution Mode中Foreground Service継続を許可するための拡張・修正2）で事前判定し、いずれも偽の場合のみ `Failure(BACKGROUND_RESTRICTED)` を返す。**Fused を呼ばない**。UI 復帰時またはExecution Service稼働中に再計算する設計とする | 「通知をタップしてアプリを開くまで ETA 再計算が保留される」ことが UX に織り込まれる（§95.6） |
| 11 | 目的地 geocoding | `locationName` が空／URL／会議室名など住所でない | `NoMatch`（異常ではない）。「この予定は移動先を特定できません」と表示し、手動 Travel Time 入力へ誘導する。**retry しない・結果をキャッシュする** | 会議室名やオンライン会議の予定でもフローが止まらない |
| 12 | 目的地 geocoding | `IOException`（ネットワーク断） | `Failure(NETWORK)`。retry 導線＋手動入力導線を提示 | オフラインでも手動入力で先へ進める |
| 13 | 目的地 geocoding | `Geocoder.isPresent() == false`（バックエンド不在の端末） | `Failure(GEOCODER_UNAVAILABLE)`。以降そのセッションでは geocoding を試みず、手動入力を既定の導線にする | 毎回失敗する処理を繰り返さず、待ち時間が発生しない |
| 14 | 目的地 geocoding | 応答が返らない（タイムアウト） | `Failure(TIMEOUT)`。既定10秒で打ち切り、UI をブロックしない | 画面が固まらない |
| 15 | 経路取得（Routes API） | **オフライン**（`IOException`） | `RoutingException.Offline` → `CachingRoutingService` が retry 1回 → 直近成功値があれば返し stale 明示、無ければ手動入力へ（§95.6） | ETA 精度低下が明示されるだけで Execution は停止しない |
| 16 | 経路取得（Routes API） | **タイムアウト** | `RoutingException.Timeout` → 同上（retry 1回 → キャッシュ → 手動入力） | 同上 |
| 17 | 経路取得（Routes API） | **クォータ超過（HTTP 429）** | `RoutingException.QuotaExceeded` → キャッシュへフォールバックし、「経路取得の上限に達しました」を明示。**自動 retry を繰り返さない**（課金と 429 の悪循環を防ぐ・§95.2） | 課金が暴走せず、原因が画面に出る |
| 18 | 経路取得（Routes API） | APIキー無効・API 未有効化（401/403） | `RoutingException.Unauthorized` → 同上のフォールバック。ログには HTTP ステータスのみ記録し、**キー本体は記録しない** | 設定不備が分かる。鍵が漏れない |
| 19 | 経路取得（Routes API） | **APIキー未設定**（`local.properties` 空） | `UnconfiguredRoutingService` が `NotConfigured` を投げ、UI は「移動時間の自動取得は未設定です」＋手動入力を提示。**固定20分などの偽 ETA を返さない** | 開発中・キー未配布のビルドでも全機能が動き、値が偽物でないと分かる |
| 20 | 経路取得（Routes API） | レスポンスが不正 JSON／`routes` 空 | `MalformedResponse` / `NoRoute`。0秒へ潰さず、キャッシュまたは手動入力へフォールバック | 「0分で着く」という危険な誤表示が起きない |
| 21 | Routes API 呼び出し | 座標・移動手段以外（title/notes/locationName）が送信される | `RoutesApiRequestBuilder` が受け取る引数を `Coordinate`・`TransportMode`・`Instant` のみに型で限定し、`ExecutionEvent` を渡せない設計にする。T-ROUTESVC-10 と G4 コード差分レビューで検証（§58/§60/§95.2） | 予定の中身が外部送信されない |
| 22 | 再計算のポーリング | Reality Check / Departure で API を高頻度に叩き課金が膨らむ | `CachingRoutingService` が距離500m未満かつ経過10分未満なら API を呼ばない。並行呼び出しは `Mutex` で直列化。§95.2 の義務を実装層で担保 | 無料枠内に収まり、ユーザーに課金影響が及ばない |

---

## 11. 検証が必要な不明点（P3-C1 の probe 対象）

| # | 項目 | 確定方法 | 未確定時の影響 |
|---|---|---|---|
| **P3-P1** | Android 12+ で `ACCESS_FINE_LOCATION` のみを実行時要求した場合の挙動（COARSE 併記が必須か） | エミュレータ（API 35）で FINE のみ要求する最小テストを実行し、dialog の表示内容と結果を実測 | S-1 の裁定根拠。COARSE が必要なら Manifest 変更＝ADR⑤ |
| **P3-P2** | AVD `actionstarter_test`（`system-images/android-35/google_apis/x86_64`）に Google Play services が同梱され、`FusedLocationProviderClient` が動作するか | 最小 instrumented テストで `getCurrentLocation` を1回実行 | 不成立なら E2E の位置系（T-E2E3-1）を `google_apis_playstore` イメージへ変更、または LocationManager フォールバックの要否を再検討 |
| **P3-P3** | `adb emu geo fix <lon> <lat>` で投入した位置を FusedLocationProvider が返すか | 同上のテストで座標一致を確認 | E2E のモック位置手法を `setMockMode`/`setMockLocation`（21.4.0 に実在を実測済み）へ変更 |
| **P3-P4** | Robolectric 4.16.1 上で `org.json` の実装が動作するか | `RoutesApiResponseParser` の最小テスト1本を実行 | 不成立なら L1 パーサのテストを instrumented へ移すか、`kotlinx-serialization-json` 追加＝ADR④ |
| **P3-P5** | エミュレータ上で `Geocoder.isPresent()` が true を返し、実際に住所解決できるか（google_apis イメージのネットワーク geocoding バックエンド有無） | 最小 instrumented テストで日本語住所1件を解決 | 不成立でも JVM テストは fake で成立する。E2E の geocoding 経路のみ「実行不能」として報告し、手動座標投入へ切替 |
| **P3-P6** | Routes API の正確なリクエスト／レスポンス仕様（ヘッダ名・FieldMask 必須性・フィールド名・travelMode 列挙値・duration 形式） | **Context7 MCP（`resolve-library-id` → `get-library-docs`）で公式ドキュメントを取得**。実キー入手後に T-OPTIN-1 で疎通実測 | 不確定なら T-ROUTEREQ-*/T-ROUTEPARSE-* の期待値が確定しない。**P3-C2（Red）着手前に必ず解消すること** |
| **P3-P7** | Routes API の現行 SKU 別無料枠・単価（§9/§95.2 が「実装着手時に再確認」を明記） | ユーザーが Google Cloud Console の Pricing / Quotas で確認（§13 の依頼文） | コスト見積りが確定しない。クォータ上限の設定値が決められない |
| **P3-P8** | `HttpURLConnection` の接続／読取タイムアウト設定と coroutine キャンセルの協調（キャンセル時に接続が確実に切れるか） | 最小テスト（fake サーバ or 遅延レスポンス）で実測 | 不成立なら `disconnect()` を `invokeOnCancellation` で明示呼び出しする実装へ変更 |

**Phase 2 から引き継いだ未解決 probe のうち本メモで解消したもの**: **P-2（`Dispatchers.IO` の推移的解決）は解消済み**（`debugRuntimeClasspath` で `kotlinx-coroutines-core:1.9.0` に解決されることを実測）。**P-7（`EVENT_LOCATION` の実態分布）は設計上の回答を用意した**（`GeocodeResult.NoMatch` による正常系分類。実分布の統計自体は依然 未計測であり、Phase 13 の実予定検証で観測する）。

---

## 12. PDCA サイクル分解（P3-C1〜C8。TEAMS §3 粒度規則・Phase 1/2 の形式を踏襲）

| サイクル | 内容 | 担当agent（Do） | 到達ゲート |
|---|---|---|---|
| **P3-C1** | **probe ＋ 基盤 ＋ 契約 scaffold**（**TDD例外**＝裁定B3 が確立した「各Phaseの契約scaffoldサイクル」の系）: P3-P1〜P3-P8 の実測。共有ファイル 1/2/3/6/7（Version Catalog・build.gradle.kts の BuildConfig 配線・Manifest 権限・Application・strings キー）を直列で編集。`LocationService`／`GeocodingService`／`RoutingException`／各 L3 境界の**コンパイル可能な scaffold（実装は `TODO()`）**。ADR 記録（②依存追加④権限⑤＝計3〜5件）。**完了条件: 既存 `:app:testDebugUnitTest` が P3-C1 前と同一件数で Green（回帰なし）を実測** | domain-implementer | TDD例外。scaffold コンパイル成功＋probe 実測ログ＋回帰なし実測 |
| **P3-C2** | **Red**: §9 の全81件のうち **JVM系76件を failing 化し実測で Red を確認**。E2E系4件＋opt-in 1件は作成のみ（実行は G4-E）。既存 `DepartureScreenTest`（T-DEP-1〜4）の更新も本サイクルで行う（§6.2 の承認要請に基づく） | test-writer → quality-runner | **G2** |
| **P3-C3** | **Green（位置・geocoding）**: `services/location/` 配下 11ファイル（L1/L2/L3・`ForegroundGate`） | domain-implementer A | **G3** |
| **P3-C4** | **Green（経路・キャッシュ）**: `services/routing/` 配下 9ファイル（L1/L2/L3・`CachingRoutingService`・`UnconfiguredRoutingService`） | domain-implementer B（**C3 と同一メッセージで並列起動**。S-6 裁定次第） | **G3** |
| **P3-C5** | **Green（UI）**: `features/departure/` 配下（`DepartureUiState`／`ViewModel`／`Screen`／`TravelTimeInput`／`TransportModeSelector`）。画面 Composable は権限要求も遷移もラムダ引数で受け取る（Phase 1 §10.6 疎結合規約）。**前提: Phase 4のDeparture結線完了**（Phase 4のP4-C5が`SharedPlanViewModel`注入・計画時点値のマッピングを先行実施した後に着手する。Fable 5裁定2026-08-09） | ui-implementer（**Phase 3内ではC3/C4と並列可。ただしPhase 4のP4-C5完了が前提のため、Phase 4側の進捗次第でC3/C4より後倒しになりうる**） | **G3** |
| **P3-C6** | **統合（直列）**: 共有ファイル 4/5/7（`AppContainer` 結線・NavHost の権限launcher/ON_RESUME 結線・文言確定）、`mock/MockRoutingService.kt` 削除。**「ViewModel 生成点1箇所への集約」維持をレビュー観点に含める**（ADR-0003/0014 の保護条件） | domain-implementer（integration owner） | **G3** |
| **P3-C7** | **Refactor ＋ G4-JVM**: `./gradlew build` 成功・JVM/Robolectric 全 Green 再実測・`lintDebug` エラー0。**§9.10 のマージ済みマニフェスト検証4項目**を quality-runner がスクリプト実行 | ui/domain-implementer → quality-runner | **G4-JVM** |
| **P3-C8** | **instrumented（G4-E）**: Phase 2 §12.1 Step 0〜7 の seed 手順を再利用し、位置権限 grant/revoke（**`pm revoke` の終了コードを信用せず `dumpsys package` で `granted=false` を確認**＝Phase 2 M-10 の教訓）、`adb emu geo fix` によるモック位置投入、T-E2E3-1〜4 実行、ja/en スクリーンショット取得。キーがある場合のみ T-OPTIN-1 を追加実行 | quality-runner | **G4-E** |

**P3-C3/C4/C5 並列時の所有権規則**: `gradle/libs.versions.toml`／`app/build.gradle.kts`／`AndroidManifest.xml`／`di/AppContainer.kt`／`ActionStarterApplication.kt`／`navigation/ActionStarterNavHost.kt`／`res/values*/strings.xml` の既定所有者は domain-implementer（integration owner）のみ。C3 の A・C4 の B・C5 の ui-implementer はこれらに一切触れず、必要が生じたら中断して Fable 5 へ報告する。C3 と C4 は `services/location/` と `services/routing/` でディレクトリが完全に素であり、共有ファイルを介さない。

**C5の直列制約（Fable 5裁定2026-08-09・修正1）**: C5（`features/departure/`）はC3・C4とはファイルフットプリントが素で技術的には並列可能だが、**Phase 4のP4-C5（`SharedPlanViewModel`注入・計画時点値のマッピング）が完了するまで着手しない**という追加の直列制約を持つ。`di/AppContainer.kt`の編集も統合オーナーがP4統合→P3統合の順で直列実施する。**C3・C4はこの制約を受けず、Phase 4の全ドメインサイクルと引き続き完全並列可**（フットプリント素であるため）。

### 12.1 完了実績（P3-C2〜C8fix、実測記録。P3-C6はintegration owner、P3-C7・P3-C8・P3-C8fixはquality-runner/Sonnet実装担当がそれぞれの実施サイクルで追記）

各サイクルの完了状態を、保存済みログ（`build/agent-logs/`）に接地して記録する。P3-C8（instrumented・G4-E）は本改訂で実施済み・結果を下表に記載する。P3-C8で検出された2欠陥（INTERNET権限欠落・位置権限拒否時の手動Travel Time入力欄非表示）の修正とE2E再実測（P3-C8fix）は本改訂でさらに追記する。

| サイクル | 完了実績（実測接地） |
|---|---|
| P3-C2 | Red化・既存`DepartureScreenTest`（T-DEP-1〜4）更新を実施。`p3c2a-compile.log`／`p3c2b-compile-*.log`でscaffold＋Redテストのコンパイル成功を実測確認済み。個別のRed実行時点の失敗件数を記録した専用ログは本統合サイクル開始時点で確認できなかったため、具体的な失敗件数は「不明」として扱う（捏造しない）。P3-C3実測（次行）で`services/location/`実装後の残存失敗が4件まで縮小していることから、間接的にC2時点でより多くのJVM系テストがfailingだったことが推測できるが、これは推測であり実測ではない旨を明記する |
| P3-C3 | `services/location/`実装完了。`p3c3-full.log`実測: `:app:testDebugUnitTest`は**238件中4件失敗**（`AppContainerRoutingConfigTest.tCfg1_apiKeyConfigured...`・`DepartureRoutingScreenTest.tPerm3_4_deniedThenGrantedOnResume...`・`DepartureScreenTest.tDep4_startNavigationButton_disabledWithReason`・`PlatformGeocoderSourceTest.lookup...[26]`）＋skip 1件。4件はいずれもC3のスコープ外（`AppContainer`結線＝C6所管／`DepartureScreen`のtDep4＝C5所管／Robolectric制約＝後述P3-C6行）であり、C3自身の対象（`services/location/`）に起因する失敗は残っていない |
| P3-C4 | `services/routing/`実装完了。`p3c4-full.log`実測: **238件中4件失敗（内訳はP3-C3と完全一致）**。C4は`services/routing/`のみを変更したため、この4件に対する増減がないことが実測で確認できる（回帰なし） |
| P3-C5 | `features/departure/`実装完了。`p3c5-full.log`実測: **238件中3件失敗**（`DepartureScreenTest.tDep4`がC5の`DepartureScreen`実装により解消）。残り3件（`AppContainerRoutingConfigTest.tCfg1`・`DepartureRoutingScreenTest.tPerm3_4`・`PlatformGeocoderSourceTest[26]`）はC6へ申し送りされた |
| P3-C6 | `AppContainer`実結線（§6.4#4）・NavHost結線（§6.4#5）・`mock/MockRoutingService.kt`削除・承認済みテスト修正3件（`DepartureRoutingScreenTest.tPerm3_4`差分規約化／`PlatformGeocoderSourceTest`のsdk絞込＋KDoc追記／`AndroidGeocodingServiceTest`へT-GEO-10・T-GEO-11追加）を実施。**`p3c6-full.log`と`p3c6-full-rerun.log`（`--rerun`による強制再実行。2回実測とも同一結果で再現性を確認）: 総239件（P3-C5の238件からの純増+1＝T-GEO-10/T-GEO-11追加2件－`PlatformGeocoderSourceTest`のsdk=26系除去1件）、failures 0、errors 0、skipped 1（`tCfg2_apiKeyEmpty_appContainerSuppliesUnconfiguredRoutingService`。本開発環境の`local.properties`に`MAPS_ROUTES_API_KEY`が設定済みのため`Assume`によりskip＝T-CFG-1/T-CFG-2の対称設計どおり）、passed 238。** C5引き継ぎの3件はいずれも解消を実測確認した：`tCfg1`は`AppContainer`実結線後にGreen（`container.routingService is CachingRoutingService`が成立）、`tPerm3_4`はアサーション差分化後にGreen、`PlatformGeocoderSourceTest`は`sdk = [33]`のみへ絞り込み後にGreen。`:app:assembleDebug`は`p3c6-assembleDebug.log`でexit 0（BUILD SUCCESSFUL）を実測確認した |
| **P3-C7（本サイクル）** | **P3-C6の実装コードに対し初回のlintDebug実測を実施したところ4件のerror**（`FusedRawLocationSource.kt:44`のMissingPermission1件、`PlatformGeocoderSource.kt:46/49`のNewApi3件。いずれもP3-C3で追加された`services/location/`のGMS/Geocoder呼び出しが原因で、C2〜C6のいずれの完了記録もlint実測を含んでいなかったため未検出だった）を検出。ソースを確認したところ両クラスとも設計上安全（`FusedRawLocationSource`はKDoc・`FusedLocationService.currentLocation`実装（`permissionGate.isGranted`を先行判定し偽なら`rawLocationSource`を呼ばない設計＋`SecurityException`をcatchする設計）で権限判定が呼び出し元L2に一元化されており、`PlatformGeocoderSource.lookupAsync`は呼び出し元`lookup()`の`Build.VERSION.SDK_INT >= TIRAMISU`分岐でのみ到達する設計）ため、制約どおり**ロジック変更なしの機械的修正**（`@SuppressLint("MissingPermission")`を`FusedRawLocationSource.currentFix`に、`@RequiresApi(Build.VERSION_CODES.TIRAMISU)`を`PlatformGeocoderSource.lookupAsync`に追加。いずれもマーカーアノテーションのみで理由コメント併記）のみで解消し、再実行で**`:app:lintDebug`エラー0**を実測確認した（`build/agent-logs/p3c7-lint.log`）。warningは13件で、Phase 4完了時点の既知基準24件（`docs/plans/phase4-basic-engine.md` P4-C6行）から**-11件**。カテゴリ別内訳: `OldTargetApi`1・`AndroidGradlePluginVersion`1・`GradleDependency`6・`MissingApplicationIcon`1はいずれも24件基準から増減なし、`UnusedResources`のみ15→4（-11）。24件基準でPhase 3所有と特定されていた未配線文字列13件（`location_permission_*`／`travel_time_manual_*`／`transport_mode_*`／`departure_eta_stale_notice`／`departure_geocode_no_match_message`）のうち11件はC5の`features/departure/`実装により配線され警告解消（予測どおり）、残り2件（`location_permission_denied_message`・`travel_time_manual_apply_button`）は本サイクル時点でも未配線のまま残存（`grep`でソース参照0件を実測確認。エラーではなくwarningのためG4-JVM達成条件外だが後続へ申し送る）。`execution_placeholder_step_title`（Phase 4所有）・`recovery_option_eta_label`（既存許容分）は不変。`:app:build`（テスト込みフルビルド）は**BUILD SUCCESSFUL・exit 0**を実測（`build/agent-logs/p3c7-build.log`）。JUnit XML集計で`testDebugUnitTest`は**239件中238 pass・0 fail・1 skip**（P3-C6から変化なし＝lint修正2件がテスト回帰を生んでいないことを実測確認）、`assembleDebug`・`assembleRelease`双方成功。**計画書§9.10のマージ済みマニフェスト4項目検証**（`:app:assembleRelease`実行後、`build/agent-logs/p3c7-manifest.log`）: (1)debug/release両変種の`<uses-permission>`に`ACCESS_FINE_LOCATION`存在＝**PASS**、(2)両変種に`ACCESS_BACKGROUND_LOCATION`の`<uses-permission>`タグ不在＝**PASS**（初回は単純文字列grepでマージ元Manifestのコメント（この制約自体を説明する日本語コメント文）に誤ヒットし`[FAIL]`と誤判定したが、`<uses-permission>`タグに限定した再検証で誤検知と確認し訂正。ログに経緯をそのまま残す）、(3)両変種に`ACCESS_COARSE_LOCATION`存在（S-1裁定どおり）＝**PASS**、(4)`grep -r "AIza" app/src/main/ app/build/intermediates/merged_manifest*`で0件＝**PASS**（`MAPS_ROUTES_API_KEY`は`local.properties`→`BuildConfig.ROUTES_API_KEY`経由のみで注入されManifest/resには一切現れない設計どおり）。4項目すべてPASSにより**G4-JVM達成** |
| **P3-C8（本サイクル・quality-runner、instrumented E2E／G4-E）** | **Step 0ガード実測**: `ro.kernel.qemu=1`・`ro.boot.qemu.avd_name=actionstarter_test`を確認（`emulator-5554`は実行開始前から起動中でありコールドブート不要と判断）。`sys.boot_completed=1`、アニメーション3値（`window_animation_scale`／`transition_animation_scale`／`animator_duration_scale`）はいずれも`0`を実測確認。**seed**: 冪等cleanup（`p3c8-step2-cleanup.log`、3テーブルとも事前に空を確認）→ローカルカレンダー作成（account`p3c8@local`、`calendar._id=1`、`p3c8-step3-calendar.log`）→場所付き通常イベント1件投入（title=`P3C8_Meeting_TokyoTower`／eventLocation=`東京都港区芝公園4丁目2-8`／eventTimezone=`Asia/Tokyo`／`_id=17`、`p3c8-step4-events.log`）→14列projectionで1行・14列すべて充足（`event_id`〜`availability`）を実測確認（`p3c8-step5-seed-verify.log`）。`:app:assembleDebug`＋`:app:assembleDebugAndroidTest`は**BUILD SUCCESSFUL**（`p3c8-assemble.log`）、両APKを`install -r`成功、`READ_CALENDAR`は`pm grant`後`dumpsys`で`granted=true`実測、位置権限（FINE/COARSE）は初期状態`granted=false`実測（`p3c8-install.log`／`p3c8-grant-read-calendar.log`）。**AGPの`connectedDebugAndroidTest`はテスト実行完了ごとに対象アプリ・テストAPKを自動アンインストールする**ことを本サイクルで実測確認した（1回目実行直後に`pm list packages`が空を返した。Phase 2 P2-C8fix2の教訓と整合）ため、以降の各テストケース前に`install -r`を都度再実行した。<br><br>**T-E2E3-2（位置権限拒否フォールバック）**: 事前dumpsysで`ACCESS_FINE_LOCATION: granted=false`を確認し単独実行。**結果：FAIL**（`tests="1" failures="1"`、`p3c8-e2e-denied-result.xml`）。`departure_title`表示アサーションは通過したが、後続の`travel_time_manual_label`（"Travel time (minutes)"）の`assertIsDisplayed`で`AssertionError`（該当ノード非検出）。Departure画面自体への到達は成立している一方、権限を一度も要求していない状態では手動Travel Time入力UIが表示されないことを実測した（原因分析はquality-runnerの職掌外のため事実のみ報告）。<br><br>**T-E2E3-3（オフライン縮退）**: `install -r`再実行→`pm grant`でFINE/COARSE双方`granted=true`実測後、`adb shell svc wifi disable`／`svc data disable`実行（`Wi-Fi is disabled`実測）→実行。**結果：PASS**（`tests="1" failures="0"`、`p3c8-e2e-offline-result.xml`）。終了後`svc wifi enable`／`svc data enable`を実行し`Wi-Fi is enabled`を実測確認、後続テストへの影響を残さず復旧した（`p3c8-e2e-offline.log`）。<br><br>**T-E2E3-4（ja/enスクリーンショット）**: `cmd locale set-app-locales com.actionstarter --user 0 --locales {en,ja}`のper-app locale方式を採用。Compose `testTag`が`uiautomator`のresource-idとして露出しないこと（`testTagsAsResourceId`未設定）を実測確認したため、`uiautomator dump`のtext一致で座標を特定し`input tap`による手動操作（event row→Start/開始→Done/完了→Departure）でスクリーンショットを取得した。両ロケールとも`departure_title`／`departure_estimated_arrival_label`／`departure_event_label`／`departure_buffer_label`の表示を`uiautomator dump`実測で確認し、`docs/evidence/screenshots/phase3/en/01-departure.png`（1080×2400・85KB）・`docs/evidence/screenshots/phase3/ja/01-departure.png`（1080×2400・112KB）を`adb exec-out screencap`で取得した（手動Travel Time欄に`25`を入力した状態のスクリーンショット。`travel_time_manual_apply_button`はP3-C7時点の記録どおり本サイクルでも未配線でUI上に現れないことを実測確認したため、入力欄への値入力のみで確定操作はできない仕様のままである）。**別途、自動化テスト`tE2e3_4_departureScreen_reachableForScreenshotCapture`も単独実行し結果：PASS**（`tests="1" failures="0"`、`p3c8-e2e-screenshot-test-result.xml`）。<br><br>**T-E2E3-1（実位置ETA）**: `install -r`再実行→FINE/COARSE`granted=true`実測確認→`adb emu geo fix 139.767125 35.681236`実行（コンソール応答`OK`）→`dumpsys location`で`fused provider`／`gps provider`とも`last location=null`を実測（geo fixがコンソールに受理されてもプロバイダへ反映されないことを本サイクルで新規に実測し、既知問題を追認）。テスト実行**結果：FAIL**（`tests="1" failures="1"`、`p3c8-e2e-geofix-result.xml`。`assertDoesNotExist`失敗＝`departure_eta_unavailable_message`「Travel time not available」ノードが検出された）。テスト実行後も両プロバイダとも`last location=null`のままであることを再確認した。**プロンプト指示済みの分類ルールに従い「実行不能（R16・AVD位置バックエンド制約）」として記録する**（P3-P2／P3-P3の既知問題を本サイクルの実測で再確認したものであり、新規のアプリ側欠陥ではない）。<br><br>**T-OPTIN-1（実Routes API疎通）**: `install -r`再実行→ネットワーク有効（`Wi-Fi is enabled`）を確認→実行。**結果：FAIL**（`tests="1" failures="1"`、`p3c8-optin-result.xml`）。**ただし失敗の実態はプロンプトが想定していたHTTP 403ではなく、`java.lang.SecurityException: Permission denied (missing INTERNET permission?)`（`android_getaddrinfo failed: EPERM`、DNS解決の時点で失敗）であることを実測した。** `app/src/main/AndroidManifest.xml`および`build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`の両方を`grep`し、`android.permission.INTERNET`の`<uses-permission>`宣言が（依存ライブラリからの推移的マージも含め）存在しないことを実測確認した。**Routes APIキーのCloud Console側403問題（ユーザー操作待ち）とは別に、アプリ側に`INTERNET`権限宣言そのものが欠落しており、キー問題が解消してもこのままではRoutes APIへの通信が原理的に成立しない**（P3-C4／C6のいずれの完了記録も実機ネットワーク疎通を検証していなかったため未検出だった。修正はquality-runnerの職掌外のため事実のみ報告する）。<br><br>**cleanup**: sync adapterで`p3c8@local`カレンダーを削除し、`calendars`／`events`／`instances`3テーブルとも空を実測確認（`p3c8-step7-cleanup.log`）。per-app locale復帰は、T-OPTIN-1実行後にAGPが自動アンインストールした状態のままだったため`cmd locale set-app-locales`が`Unknown package`を返した＝アプリ自体が存在せずlocale上書きも残存し得ない状態であることを確認した。ネットワークは`Wi-Fi is enabled`・`airplane_mode_on=0`を最終確認、アニメーション3値も`0/0/0`のまま不変であることを確認した。**G4-Eの4件中2件Pass（T-E2E3-3／T-E2E3-4）・1件Fail（T-E2E3-2、機能ギャップの実測）・1件実行不能（T-E2E3-1、R16環境制約）。T-OPTIN-1はFail（想定と異なる原因＝INTERNET権限欠落を新規実測）** |
| **P3-C8fix（本サイクル・Sonnet実装担当、P3-C8で検出された2欠陥の修正＋再実測）** | **欠陥1（INTERNET権限欠落）**: `app/src/main/AndroidManifest.xml`へ`<uses-permission android:name="android.permission.INTERNET"/>`を追加（normal permission、実行時要求不要）。`:app:assembleDebug`＋`:app:assembleRelease`＋`:app:assembleDebugAndroidTest`実行後（`p3c8fix-assemble.log`、BUILD SUCCESSFUL）、`merged_manifest`／`merged_manifests`／`packaged_manifests`の3段階×debug/release全6ファイルを`grep`し、**全ファイルでINTERNETタグ1件・ACCESS_BACKGROUND_LOCATIONタグ0件・ACCESS_COARSE_LOCATION 1件・ACCESS_FINE_LOCATION 1件、かつ`AIza`文字列（APIキー漏洩）が`app/src/main/`・全merged manifest配下で0件を実測確認**（`p3c8fix-manifest.log`、B9型ゲート）。<br><br>**欠陥2（位置権限拒否時に手動Travel Time入力欄が非表示）根本原因**: `DepartureViewModel.recalculate()`は`plan.event.locationName`のgeocode（`geocodingService.geocode`、suspend・実機では実際の非同期Geocoder呼び出しを伴う）が`GeocodeResult.Success`を返した後にのみ`recalculateRoute()`を呼び、位置権限チェック（`permissionGate.isGranted`、同期関数）は同メソッド内にのみネストされていた。そのため`permissionState`が`DENIED`へ遷移するタイミングがgeocode完了という非同期処理に依存しており、実機（`pm revoke`直後のcold start）ではCompose testの`waitForIdle`がgeocode完了を待たずにアサーションへ進み、`permissionState`が初期値`NOT_REQUESTED`のまま観測されて`travel_time_manual_label`が表示されなかった（`DepartureScreen`側の表示条件`showManualFallback`自体は正しく、既存T-PERM3-3がGreenであることと整合する）。**Redテスト**: `DepartureRoutingViewModelTest`にT-DEPVM-10（`tDepVm10_permissionGateDeniesBothFineAndCoarse_setsDeniedStateWithoutCallingGeocodeOrLocationOrRouting`、Fable 5承認済み追加）を新設し、`FakePermissionGate`注入で拒否を模した上で「geocode/location/routingのいずれも呼ばれることなくpermissionState=DENIEDへ遷移する」ことを主張。修正前に単独実行し**Red実測**（`p3c8fix-red.log`：`geocodingService.callCount`が期待値`0`に対し実測`1`でAssertionError。他の既存9ケースはGreenのまま）。**本番修正（最小）**: `recalculate()`の先頭（`locationName`空チェックの直後・geocode呼び出しの前）に同期的な権限事前チェックを追加し、権限が無ければgeocode／location／routingのいずれも呼ばずに`permissionState=DENIED`を即時確定するよう変更（`hasAnyLocationPermission()`private関数へ抽出し、`recalculateRoute()`内の既存チェック（T-DEPVM-2が要求する「LocationServiceの応答を権威とする」防御的二重チェック）と共有）。修正後に同テストを再実行し**Green実測**（`p3c8fix-green-targeted.log`：10/10 pass）。`travel_time_manual_apply_button`（P3-C7既知の未配線）は`TravelTimeInput.kt`・`showManualFallback`のいずれからも一切参照されていないことを`grep`で再確認し、本欠陥の表示条件に無関係と判断したため配線せず既知ギャップのまま維持した。<br><br>**全スイート回帰確認**: 変更前ベースラインを`--rerun`で強制再実行し**239件・failures 0・errors 0・skipped 1**を実測（`p3c8fix-baseline-tests-rerun.log`）。両修正適用後に`--rerun`で再度強制実行し**240件（+1はT-DEPVM-10）・failures 0・errors 0・skipped 1（変化なし＝T-CFG-2の想定skipのまま）**を実測（`p3c8fix-full-green.log`、XML集計）。回帰なしを確認した。<br><br>**E2E再実測（emulator-5554、`actionstarter_test` AVD、Step 0ガード`ro.kernel.qemu=1`／`ro.boot.qemu.avd_name=actionstarter_test`／アニメーション3値0/0/0を再確認）**: P3-C8終了時点でP3-C8のseedカレンダー（`p3c8@local`）は同サイクルのcleanupで既に削除済み、かつAGPの`connectedDebugAndroidTest`は実行完了ごとに対象アプリを自動アンインストールする（P3-C8既知の挙動、本サイクルでも初回試行時に`event_selection_row_0`未検出のFAILとして再現・確認）ため、再実測用に新しいseedカレンダー（account`p3c8fix@local`、`calendar._id=1`、`p3c8fix-seed-roundA.log`）と場所付きイベント1件（title=`P3C8fix_Meeting_TokyoTower`／eventLocation=`東京都港区芝公園4丁目2-8`／`_id=18`）を投入し、14列Instances projectionで充足を実測確認した（`p3c8fix-seed-roundA-event.log`）。<br><br>**T-E2E3-2再実行**: `adb install -r`（app＋androidTest）→`pm grant READ_CALENDAR`→seed→`pm revoke`でFINE/COARSE双方`granted=false`実測（`p3c8fix-revoke-location-roundA.log`）→単独実行。**結果：PASS**（`tests="1" failures="0" errors="0"`、`p3c8fix-e2e-denied-result.xml`。P3-C8で失敗していた`travel_time_manual_label`の`assertIsDisplayed`を含め全アサーション通過）。欠陥2の修正を実機で確認した。<br><br>**T-OPTIN-1再実行**: `adb install -r`再実行→位置権限grant（`p3c8fix-seed-roundB.log`）→ネットワーク有効（`Wi-Fi is enabled`・`airplane_mode_on=0`）確認→単独実行。**結果：FAIL**（`tests="1" failures="1"`、`p3c8fix-optin-result.xml`）。**ただしP3-C8のSecurityException/EPERM（INTERNET権限欠落起因）は再現せず、欠陥1の修正を実機で確認した。** 実際の失敗は`RoutingException$MalformedResponse: Unparsable response`（`RoutesApiResponseParser.kt:40`、cause=`IllegalStateException: response JSON has no top-level "routes" array`）。スタックトレースが`RoutesApiRoutingService.kt:62`（`when(response.statusCode)`の**`200 ->`分岐内**の`RoutesApiResponseParser.parse`呼び出し）を経由していることから、**HTTPステータスは200（認証・疎通とも成功）であったと判定できる**（401/403/429/5xx分岐はいずれも`RoutesApiResponseParser`を呼ばず別exceptionを送出するため、parseへ到達した時点で200確定）。レスポンス断片としては、`MinimalJson`のJSON構文解析自体は成功し（`JsonSyntaxException`ではなく`routesキー不在`のIllegalStateExceptionのため）、トップレベルに`"routes"`キーを含まない有効なJSONオブジェクトが返っていることまでを実機ログ（`logcat-...RoutesApiLiveTest...txt`、`p3c8fix-optin.log`）から確認した。生のレスポンスボディそのものはアプリ・ログいずれにも出力されず、本文字列の取得にはAPIキーをコマンドラインへ渡す追加のcurl代替検証が必要となるため、**キー文字列をログへ残さないという制約を優先し、生バイト取得は行わなかった**（未検証のまま「不明」と明記）。**仮説（未検証）**: `X-Goog-FieldMask: routes.duration`のみを要求した状態でComputeRoutesが有効経路0件を返す場合、proto3 JSON既定のフィールド省略規則により`"routes": []`ではなく`routes`キー自体が省略される可能性があり、`RoutesApiResponseParser.parse`（40〜42行目）が`routes`キー不在を`NoRoute`ではなく`MalformedResponse`に分類してしまう設計ギャップが疑われる。**この経路（`RoutesApiRoutingService.kt`／`RoutesApiResponseParser.kt`）は本サイクルの変更許可ファイル一覧（Manifest／DepartureViewModel.kt／DepartureScreen.kt等）に含まれないため修正していない。P3-C8が想定していたCloud Console側403問題・INTERNET権限問題のいずれとも異なる、新規の第3の欠陥（暫定名: P3-C9候補）として本行に事実のみ報告し、修正はスコープ外として次サイクルへ申し送る**。<br><br>**cleanup**: sync adapterで`p3c8fix@local`カレンダーを削除し、`calendars`テーブルが空・`events`テーブルにP3C8fix由来の行が残存しないことを実測確認した（`p3c8fix-final-cleanup.log`）。<br><br>**T-E2E3-1**: 本サイクルでは再実行していない（プロンプト指示どおりR16・AVD位置バックエンド制約による実行不能のまま、P3-C8の記録を既知ギャップとして維持）。**まとめ: 欠陥1・欠陥2とも実機修正確認（T-E2E3-2 PASS・T-OPTIN-1のSecurityException消失）。T-OPTIN-1は新規の第3欠陥（Routes APIレスポンスのroutesキー省略ケース未対応の疑い）によりFailのまま、修正はスコープ外につき申し送り。T-E2E3-1はR16のまま既知ギャップ維持** |
| **P3-C9（本サイクル・Sonnet実装担当domain-implementer、P3-C8fixで申し送られた第3欠陥の修正＋T-OPTIN-1/2）** | **Fable 5事前確定実測（curl、本番同一FieldMask`routes.duration`使用）**: (1) TRANSIT・東京タワー(35.6586,139.7454)→明治神宮(35.6595,139.7005) → HTTP 200・body`{}`（`routes`キー自体が省略、日本は公共交通データ非提供）。(2) DRIVE + departureTime（routingPreference未指定） → HTTP 400 `"Timestamp cannot be set for TRAFFIC_UNAWARE routing mode."`（**現行実装はDRIVEが常に失敗する欠陥**）。(3) DRIVE + departureTime + `"routingPreference":"TRAFFIC_AWARE"` → HTTP 200・duration`"1045s"`。(4) WALK/BICYCLE + departureTime → HTTP 200（`"4158s"`/`"1400s"`、routingPreferenceなしで正常）。**Fable 5裁定1〜4（承認済み）を実装**：①`RoutesApiResponseParser`はJSONオブジェクトroot＋`routes`キー欠落を`NoRoute`へマップ（rootが非オブジェクトなら従来どおり`MalformedResponse`維持）②`RoutesApiRequestBuilder`は`travelMode=DRIVE`の場合のみ`routingPreference:TRAFFIC_AWARE`をbodyへ追加、WALK/BICYCLE/TRANSITには付与しない③④`RoutesApiLiveTest.kt`のT-OPTIN-1をmode TRANSIT→WALKへ変更しT-OPTIN-2（TRANSIT→NoRoute検証）を新設。<br><br>**既存テスト確認（TDD手順①）**: `RoutesApiResponseParserTest.kt`／`RoutesApiRequestBuilderTest.kt`を確認した結果、「`{}`またはroutesキー欠落→MalformedResponseを期待する既存テスト」「DRIVEのbodyにroutingPreferenceが無いことを固定する既存テスト」はいずれも**存在しなかった**（既存T-ROUTEREQ-1がrouting Preference不在を検証する対象はTRANSITモードのみであり、TRANSITは修正後も不在のままのため無変更で有効）。したがって**既存テストの期待値変更は0件**、新規テスト追加のみで対応した。<br><br>**Red実測**（`p3c9-red.log`）: 新規5件追加（`RoutesApiResponseParserTest`にT-ROUTEPARSE-6〜8、`RoutesApiRequestBuilderTest`にT-ROUTEREQ-4〜5）のうち**3件が意図どおり失敗**：`parse_withEmptyObjectResponse_throwsNoRoute`・`parse_withObjectMissingRoutesKey_throwsNoRoute`はいずれも`AssertionError: expected:<NoRoute> but was:<MalformedResponse>`（修正前の実装がまだ`{}`／routesキー欠落を`MalformedResponse`に分類している証拠）、`build_withDrivingMode_includesTrafficAwareRoutingPreference`は`IllegalStateException: field "routingPreference" not found`（修正前はDRIVEでもrouting Preferenceを付与しない証拠）。残り2件（`parse_withArrayRootResponse_throwsMalformedResponse`・`build_withNonDrivingModes_omitsRoutingPreference`）は仕様上すでに満たされていたため**即Green**（回帰ロックとして機能、13 tests completed, 3 failed）。<br><br>**Green実測**: 対象2クラスの再実行でBUILD SUCCESSFUL（全13件Green）を確認後、`:app:testDebugUnitTest --rerun`で全JVMスイートを強制再実行し（`p3c9-green.log`、JUnit XML集計）**245件・failures 0・errors 0・skipped 1**（skipは`tCfg2_apiKeyEmpty_...`、既知の想定skip）を実測。P3-C8fixの240件から**+5＝新規追加5件のみの純増**（回帰なし）。<br><br>**emulator実測（emulator-5554、`actionstarter_test` AVD、事前`adb devices`で`device`状態を確認済み・起動不要）**: 計画書§9.1記載の`:app:connectedDebugAndroidTest --tests '*RoutesApiLiveTest'`構文は本プロジェクトのAGP 8.13.2では**`DeviceProviderInstrumentTestTask`が`--tests`オプションを登録しておらず使用不可**（`gradlew help --task :app:connectedDebugAndroidTest`実測で登録オプションが`--serial`／`--rerun`のみと確認、Unknown command-line optionで実際に失敗）と判明したため、代わりに標準の`-Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.e2e.RoutesApiLiveTest`プロパティ方式を使用した（計画書の当該記載は本サイクルの実測により不正確と判明、次回参照者への申し送り）。初回実行で**T-OPTIN-1（WALK）がFAIL**（`RoutingException$ServerError`）、T-OPTIN-2（TRANSIT→NoRoute）はPASS。原因切り分けのため`RoutesApiLiveTest.kt`へ一時診断テスト（`RoutesApiRoutingService`を経由せず生のHTTPステータス/bodyを取得、APIキーは`BuildConfig`経由のみ参照）を追加して単独実行した結果、**HTTP 400 `"Timestamp must be set to a future time."`（`INVALID_ARGUMENT`）を実測**。host／emulator／Google自身のサーバ（`curl -I https://www.google.com`のDateヘッダ）の3者の時計が数秒以内で一致していることを実測確認し（時計ズレ原因を排除）、`departureDate = Instant.now()`（バッファ0）がネットワーク往復＋サーバ処理の間に過去時刻へずれる**構造的な競合状態**と判定した（TRANSITは同じバッファ0で本サイクルのT-OPTIN-2がPASSしており、有効経路0件の応答経路がこの検証を通過しない、またはより緩い可能性がある。未確認のため申し送り）。**対応（テストのみ・許可ファイル内）**: `RoutesApiLiveTest.kt`にT-OPTIN-1／T-OPTIN-2共通の`FUTURE_DEPARTURE_BUFFER`（2分）を追加し`departureDate`へ加算。診断テストで200・正のduration実測を再確認後、診断テストを削除し最終形（T-OPTIN-1・T-OPTIN-2のみ）へ復元。最終実行（`p3c9-optin.log`）で**T-OPTIN-1・T-OPTIN-2とも実行完了、BUILD SUCCESSFUL**、生JUnit XML（`app/build/outputs/androidTest-results/connected/debug/TEST-actionstarter_test(AVD) - 15-_app-.xml`）で`tests="2" failures="0" errors="0" skipped="0"`を確認した。**この`Instant.now()`バッファ0の競合は本アプリの実利用（`DepartureViewModel`が計算する将来のdepartureDate）で顕在化するかは本サイクルの範囲外（`DepartureViewModel.kt`は変更許可ファイル外）のため未検証のまま次サイクルへ申し送る**。<br><br>**変更ファイル**: `RoutesApiResponseParser.kt`（本番、`routes`キー欠落→NoRouteのロジック変更＋KDoc）・`RoutesApiRequestBuilder.kt`（本番、DRIVE限定routingPreference追加＋KDoc）・`RoutesApiResponseParserTest.kt`（T-ROUTEPARSE-6〜8追加）・`RoutesApiRequestBuilderTest.kt`（T-ROUTEREQ-4〜5追加）・`RoutesApiLiveTest.kt`（T-OPTIN-1のmode変更＋T-OPTIN-2新設＋`FUTURE_DEPARTURE_BUFFER`）・本ファイル・`DECISIONS.md`（ADR-0029追加）。**まとめ: JVM全245件Green（新規5件含む、回帰なし）。T-OPTIN-1（WALK・正のDuration）／T-OPTIN-2（TRANSIT・NoRoute）とも実機PASSを確認し、P3-C8fixから申し送られた第3欠陥を解消。副次的に発見した「`Instant.now()`バッファ0のdepartureTimeがGoogle側『must be a future time』検証と競合しうる」事象はテスト側のみ緩和し、本番`DepartureViewModel`経路への影響は未検証のまま次サイクルへ申し送る** |
| **P3-C10（本サイクル・domain-implementer、ADR-0029付記2で申し送られた「本番`departureDate`バッファ0競合」の恒久対応＋T-OPTIN-3）** | **Fable 5裁定（承認済み）を実装**：Routes API固有の時刻制約（departureTimeがネットワーク往復中に過去へずれるとHTTP 400 "Timestamp must be set to a future time."）への適応をRoutes APIアダプタ層（`RoutesApiRequestBuilder`）の責務とし、`build`へ`clock: java.time.Clock = Clock.systemUTC()`パラメータを追加、送信する`departureTime`を`max(departureTime, clock.instant() + 120秒)`へクランプする。`RoutesApiRoutingService`・`DepartureViewModel`はいずれも無変更（前者はデフォルト引数で無改修動作、後者は`departureDate=now`の意味論が正しいため）。<br><br>**Red実測**（`p3c10-red.log`）: `RoutesApiRequestBuilderTest.kt`へT-ROUTEREQ-6（departureTimeがclock.instant()ちょうど→+120秒へクランプ）・T-ROUTEREQ-7（departureTimeがclock.instant()+10分→クランプされずそのまま。回帰ロック）を固定Clock注入で追加した時点で、本番未実装の`clock`名前付き引数を参照するため`:app:compileDebugUnitTestKotlin`が**コンパイルエラー**（`No parameter with name 'clock' found`、2箇所）で失敗することを実測した（コンパイル不能という形のRed）。<br><br>**Green実測**: `RoutesApiRequestBuilder.kt`へ`clock`パラメータと`clampToFuture`private関数を実装。対象クラス単体を実行したところ**当初7件中2件が新規に失敗**（T-ROUTEREQ-1・T-ROUTEREQ-3。いずれも既定Clock＝実時刻を使用する既存テストがクランプの新契約と衝突したもので実装のバグではないと判断）。T-ROUTEREQ-1（`build_mapsOriginDestinationModeAndDepartureTime`）はハードコードされた`departureTime`が実時刻の経過により偶発的に「過去」化しクランプが意図せず発火したための非決定性で、固定Clock（departureTimeより十分過去）を注入しアサーション値は無変更のまま決定的にする最小修正で解消した。T-ROUTEREQ-3（`build_withPastDepartureDate_mapsAsIsWithoutValidation`）は「過去日付は無加工でそのまま写像される」という**旧契約そのもの**を検証していたテストであり、ADR-0030によりこの契約自体が意図的に変更されたため、`build_withFarPastDepartureDate_clampsToClockPlus120Seconds`へ改名し新契約（クランプされた値が返る）を検証するよう更新した。他の全JVMテストファイル（`RoutesApiRoutingServiceTest.kt`・`CachingRoutingServiceTest.kt`等）を`grep`で確認し、`RoutesApiRequestBuilder`／`RoutesApiRoutingService`への参照が無い、またはリクエストbodyの`departureTime`文字列を検証しないことを確認したため無影響と判定した。対象クラス7件全Green（`p3c10-green-target.log`、JUnit XML実測`tests="7" failures="0" errors="0" skipped="0"`）を確認後、`:app:testDebugUnitTest --rerun`で全JVMスイートを強制再実行し（`p3c10-green.log`）**247件・failures 0・errors 0・skipped 1**（`tCfg2_apiKeyEmpty_...`のみ、既知の想定skip）を実測。P3-C9の245件から**+2＝新規追加2件（T-ROUTEREQ-6・7）のみの純増**（T-ROUTEREQ-1・3は書き換えのため件数増減なし）で回帰なしを確認した。<br><br>**emulator実測（emulator-5554、`actionstarter_test` AVD、事前`adb devices`で`device`状態を確認済み・起動不要）**: `RoutesApiLiveTest.kt`へT-OPTIN-3（WALK・`departureDate = Instant.now()`＝バッファ0・本番`DepartureViewModel`と完全同一条件→正のDuration）を新設。既存T-OPTIN-1（2分バッファ・WALK）・T-OPTIN-2（TRANSIT→NoRoute）は無変更のまま維持。`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.e2e.RoutesApiLiveTest`を実行し（`p3c10-optin.log`）、生JUnit XML（`TEST-actionstarter_test(AVD) - 15-_app-.xml`）で**T-OPTIN-1・T-OPTIN-2・T-OPTIN-3の3件とも`tests="3" failures="0" errors="0" skipped="0"`＝全PASS**を確認した。T-OPTIN-3のPASSにより、ADR-0029付記2が申し送った「本番`DepartureViewModel`経路でこの競合が顕在化するかは未検証」という懸念を実機で解消し、本サイクルの目的（本番シナリオの端到端証明）を達成した。<br><br>**変更ファイル**: `RoutesApiRequestBuilder.kt`（本番、`clock`パラメータ＋`clampToFuture`関数追加＋KDoc）・`RoutesApiRequestBuilderTest.kt`（T-ROUTEREQ-6・7新設、T-ROUTEREQ-1・3更新）・`RoutesApiLiveTest.kt`（T-OPTIN-3新設＋KDoc追記）・本ファイル・`DECISIONS.md`（ADR-0030追加）。**まとめ: JVM全247件Green（新規2件含む、回帰なし）。T-OPTIN-1／T-OPTIN-2／T-OPTIN-3とも実機PASSを確認し、ADR-0029付記2から申し送られた本番競合の懸念を解消した** |

---

## 13. ユーザーへの依頼文（そのまま使える形）

> ### 【ご依頼】Google Maps Platform の Routes API 有効化と APIキーの発行（所要 15〜20分）
>
> Phase 3（現在地→予定先の所要時間の取得）の実装に、Google Maps Platform の **Routes API** のキーが必要です。以下の手順をお願いします。**キーが用意できるまでの間も、アプリのビルドと自動テストはすべて動作します**（キー未設定時は「移動時間の自動取得は未設定です」と表示され、手動入力にフォールバックする設計にしてあります）。実際にキーが必要になるのは、実機での疎通確認（E2E）のときだけです。
>
> **① Google Cloud プロジェクトの用意**（初回のみ・約3分）
> 1. https://console.cloud.google.com/ を開きます。
> 2. 画面上部のプロジェクト選択から「新しいプロジェクト」を作成します（名前の例: `action-starter-mvp`）。
> 3. 課金アカウントの紐付けを求められた場合は設定します。**Google Maps Platform は課金アカウントの紐付けが必須です**（無料枠内でも紐付けは要求されます）。
>
> **② Routes API の有効化**（約2分）
> 1. 左メニュー「APIとサービス」→「ライブラリ」を開きます。
> 2. 検索欄に `Routes API` と入力し、表示された **Routes API** を選択して「有効にする」を押します。
> 3. **Directions API（旧API）ではなく Routes API** であることをご確認ください。仕様書§9 が第一候補として指定しているのは Routes API（ComputeRoutes）です。
>
> **③ APIキーの発行**（約2分）
> 1. 「APIとサービス」→「認証情報」→「認証情報を作成」→「APIキー」を選択します。
> 2. 発行されたキー（`AIza...` で始まる文字列）をコピーします。
>
> **④ キーの制限設定（重要・セキュリティ）**（約5分）
> 発行直後のキーは無制限であり、漏洩すると第三者に課金される状態です。必ず以下2つの制限をかけてください。
> - **アプリケーションの制限**: 「Android アプリ」を選択し、以下を追加します。
>   - パッケージ名: `com.actionstarter`
>   - SHA-1 証明書フィンガープリント: 次のコマンドの出力（`SHA1:` の行）を貼り付けてください。
>     ```fish
>     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
>     ```
>     （これはデバッグ用証明書です。Phase 13 の配布時にはリリース用証明書の SHA-1 を追加登録する必要があります）
> - **API の制限**: 「キーを制限」を選び、**Routes API のみ**にチェックを入れます。
>
> **⑤ クォータ上限の設定（推奨・コスト暴走の防止）**（約3分）
> 1. 「APIとサービス」→「Routes API」→「割り当てとシステム上限」を開きます。
> 2. 1日あたりのリクエスト数の上限を、検証規模に見合う値（例: 500）に**引き下げて**ください。
> 3. あわせて「お支払い」→「予算とアラート」で少額（例: 1,000円）の予算アラートを設定しておくと安全です。
>
> **⑥ キーをプロジェクトに設定**（約1分）
> `/home/noritakasawada/project/app_project-0808/local.properties` に次の1行を追記してください。
> ```
> MAPS_ROUTES_API_KEY=（④で制限をかけたキー）
> ```
> `local.properties` は `.gitignore` に登録済みで、**Git にコミットされません**（実測確認済み）。キーをソースコードや `AndroidManifest.xml` に直接書くことは §89「No hard-coded secrets」により禁止しており、本プロジェクトではしません。
>
> ---
>
> **無料枠について（重要・数値は当方で断定できません）**
> Google Maps Platform は 2025年3月に「API横断の月額 $200 無料クレジット」を廃止し、現在は **SKU（Compute Routes を含む）ごとの月次無料呼び出し枠**に移行しています（仕様書§9・§95.2 に記載）。**正確な無料枠の回数と単価は変動するため、当方から具体的な数値を断定することはできません。** ②の有効化後に Cloud Console の「お支払い」→「料金」または Google Maps Platform Pricing ページで、Compute Routes SKU の現行の無料枠と単価をご確認いただけますでしょうか。仕様書はテスター15人・1予定あたり数回・月間で数百〜千件程度と見積もっており、無料枠内に収まる可能性が高いとしていますが、実装着手時の再確認を仕様書自身が要求しています（§9・§95.2）。
>
> なお実装側では、§95.2 が義務付けるスロットリング（前回から500m未満の移動かつ10分未満の経過ならキャッシュを使い API を呼ばない）とキャッシュを `CachingRoutingService` として実装し、呼び出し回数を構造的に抑えます。
>
> **正直にお伝えする残存リスク**: APIキーを `BuildConfig` 経由でアプリに埋め込む方式は、APK からキーを取り出すこと自体は技術的に可能です。したがって実効的な防御は「④のキー制限」と「⑤のクォータ上限」の2つになります。完全な解決にはサーバサイドのプロキシが必要ですが、これは MVP のスコープ（§88）を超えるため、Phase 13（配布）の前に改めてご相談させてください。
>
> **確認できていないこと**: Routes API の REST 呼び出しに Android アプリ制限を効かせるには、リクエストに `X-Android-Package` と `X-Android-Cert` ヘッダを自前で付与する必要がある可能性があります（Maps SDK 経由なら自動付与されますが、本実装は REST 直叩きです）。この点は実装着手時に公式ドキュメントで確定させます（probe P3-P6）。もし制限が効かないことが判明した場合は、④の「アプリケーションの制限」を「なし」に戻したうえで、⑤のクォータ上限をより厳しく設定する運用に切り替える提案を改めて行います。

---

## 14. リスクと対応

| ID | リスク | 対応 |
|---|---|---|
| R13 | Play services の AAR が compileSdk 35 と非互換で ADR-0011 の再来になる | **本メモの実測で解消済み**（`play-services-location:21.4.0` および推移依存3本すべて `minCompileSdk=1` / `minAGP=1.0.0`）。P3-C1 で `:app:assembleDebug` の `checkDebugAarMetadata` が通ることを実測して最終確認する |
| R14 | Routes API の実仕様（フィールド名・FieldMask）が推測と食い違い、Red テストの期待値が誤る | P3-P6 を **P3-C2（Red）着手前の必須ブロッカー**とし、Context7 MCP で公式ドキュメントを確定させる。確定するまで `RoutesApiRequestBuilder`/`ResponseParser` のテストを書かない |
| R15 | APIキーが無い状態で開発が進み、経路系が一切検証されないまま G4-JVM を通過する | JVM 76件はすべて fake で完結する設計のため検証は成立する。ただし**実 API 疎通（T-OPTIN-1）が未実行のまま Phase 4 へ進むことを禁止**し、未実行なら G4-E 報告に「実API未疎通」と明記する（推測で「動く」と報告しない） |
| R16 | エミュレータに Play services / geocoding バックエンドが無く、E2E の位置・geocoding 経路が動かない | P3-P2/P3-P3/P3-P5 で先に実測する。不成立なら被害を局所化する: JVM 76件は fake で無傷、E2E は「実行不能」としてそのまま報告し（TEAMS §7）、`google_apis_playstore` イメージへの切替を Fable 5 へ提起する |
| R17 | 位置情報がログ・将来の Telemetry へ混入する（§58/§60 違反） | エラーマップ #21。`Coordinate`・`Location` を `Log.*` へ渡さない規約とし、G4 コード差分レビューの必須確認項目に加える。**権限・プライバシー・外部API に関わる高リスク変更のため Gemini/Codex クロスレビュー必須**（TEAMS §6 G4） |
| R18 | `RoutingService` を例外契約のまま使うことで、呼び出し側が `try { } catch { }` で握り潰す | S-4 の推奨案は sealed 例外＋網羅 `when`（`else` 禁止）。T-DEPVM-9 でこれをテストとして固定し、G4 レビュー観点にも入れる。**ここが Pass1 CRITICAL の焦点になるため、Fable 5 が S-4 を「戻り値型の変更」に裁定した場合は TEAMS §5 の契約変更経路を発動する** |
| R19 | 3並列（C3/C4/C5）で共有ファイル競合が起きる | S-6 の裁定。共有ファイル7本の編集は P3-C1 と P3-C6 に完全隔離し、並列3サイクル中は誰も触らない。不許可なら C3→C4 直列へ縮退。**加えて、C5（`features/departure/`）はPhase 4のP4-C5（Departure結線）完了後に着手する直列制約を持つ（Fable 5裁定2026-08-09。詳細は§6.2・§12参照）。C3・C4はPhase 4の全ドメインサイクルと引き続き完全並列可（フットプリント素）** |
| R20 | Phase 2 の残存事項（既知負債 (d)(e)の解消状況は本書時点で未確認。旧懸念であった`:app:testDebugUnitTest`の7件RedはC5-fixで解消済み・122/122 Green＝2026-08-09）が Phase 3 の G2/G3 判定に影響する | **Phase 2はC5-fixで122/122 Green達成済み（2026-08-09・`p2c5fix-full.log`）。C6/C8のクローズ工程が進行中である。** Phase 3 着手条件は引き続きPhase 2のG4-JVM通過とする。Phase 3 の Red/Green 実測は「Phase 3 対象テストのみ」ではなく `:app:testDebugUnitTest` 全体で行うため、**P3-C1のベースライン確認は「着手時点の全スイートGreen実測（現行122件＋Phase 2 C6での増減を反映した件数）の記録」に簡素化する**（Fable 5裁定2026-08-09） |
| R21 | エミュレータの不安定性（Phase 2 P-8 でプロセス消失を観測）により G4-E が未達になる | Phase 2 §12.1 Step 1 で確立済みのコールドブート手順（`-no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect` ＋ `sys.boot_completed` ポーリング、実測約15秒）を再利用。失敗時は推測せず「実行不能」として報告 |

---

## 15. 仕様の矛盾・未定義の列挙（自己補完していない）

| # | 箇所 | 内容 | 扱い |
|---|---|---|---|
| 1 | §43 vs §44〜§46 | `LocationService` は §43 に**名前だけ**あり、シグネチャのコードブロックが与えられていない（`PlanningEngine`/`RecoveryEngine`/`RoutingService` には与えられている） | **仕様未定義箇所の補完＝ADR記録トリガー②**。§5.2 の契約案を ADR として記録する |
| 2 | §46 vs §89/§95.6 | §46 の `estimateRoute` は失敗を表現できない非null契約だが、§89 は「Error handling / Offline behavior」を、§95.6 は「retry 1回 → フォールバック」を要求する。**両立方法が仕様に書かれていない** | **S-4 として Fable 5 裁定を要請**。推奨は §46 を変えず sealed 例外で表現 |
| 3 | §95.4 権限表 | ACCESS_FINE_LOCATION のみを列挙し、Android 12+ で必要になりうる ACCESS_COARSE_LOCATION の扱いが未記載 | **S-1 として裁定要請**。P3-P1 の実測結果を添えて提起する |
| 4 | §95.4/§95.6 | 拒否時フォールバックが「出発地の手動選択**または** Travel Time の手動入力」と選択的に書かれ、どちらを実装すべきかが未確定 | **S-2 として裁定要請**。推奨は Travel Time 手動入力のみ |
| 5 | §9 | `TransportMode` 4値を定義するが**既定値の規定がない**。一方 §6 は日本固有の生活前提の埋め込みを禁止しており、どの既定値も何らかの前提を含む | **S-3 として裁定要請**。推奨は「初期値 TRANSIT・常時変更可能・ADR に UI 既定値である旨を明記・Phase 11 でロケール対応」 |
| 6 | §95.2 | スロットリング閾値が「例: 500m」「例: 10分」と例示にとどまる。また**出発時刻の変化に対する再計算規則が全く言及されていない**（TRANSIT では所要時間が出発時刻に依存するにもかかわらず） | 500m/10分は例示どおり採用。**出発時刻許容差（10分）は仕様未定義の補完＝ADR②** として §8 の表に記載し記録する |
| 7 | §67 | 「transport mode」が実装項目に挙がっているが、それが**UI での選択機能**を指すのか**内部モデルの抽象化**（§9 で既に完了）を指すのかが不明確 | android-planner の解釈は「§9 で型は定義済みなので、Phase 3 の項目は**ユーザーが選べること**を指す」。F26 として UI 選択を実装する。**この解釈自体を Fable 5 が確認すること** |
| 8 | §29/§35 Screen 4 | `Start navigation` ボタンが画面仕様に存在するが、§67 の Phase 3 実装項目には含まれない | Phase 3 では既存の `isStartNavigationEnabled = false`（Phase 1 T-DEP-4）を**維持する**。外部地図アプリ起動は §88 判定で Phase 3 スコープ外。Phase 5 以降へ申し送り |
| 9 | §30 Reality Check | `currentLocation` を比較対象に含むが、§67 Phase 3 には Reality Check 自体が含まれない（§70 Phase 6 が Recovery） | Phase 3 は `LocationService` を提供するのみで、Reality Check の周期実行は実装しない。**§95.4 は権限取得タイミングを「Departure Mode / Reality Check 機能の初回利用時」としているため、Phase 3 では Departure Mode 側のみが要求点になる**旨を ADR に記録 |
| 10 | §95.1(b) | Execution Mode中のForeground Service継続時の位置取得許可条件（`isExecutionServiceRunning()`）は、Phase 5でForeground Serviceが実装されるまで実体を持たない | Phase 3 では`ForegroundGate.isLocationAccessAllowed()`の一部として常にfalseを返す設定可能フックを実装し、Phase 5で実配線する（修正2・Fable 5裁定2026-08-09、Gemini G1 CRITICAL対応）。**Phase 5への申し送り事項とする** |
| 11 | §7.1（JSON選定表）・P3-P4 | 当初計画は「Robolectric上で`org.json`実装が動くか」をP3-P4として検証し、不成立時は(a) L1パーサのテストをinstrumentedへ移す、または(b) `kotlinx-serialization-json`追加、のいずれかへフォールバックする設計だった（**P3-C6・本統合サイクルでの追加実測により発見**）。しかし実装（P3-C4）では`RoutesApiRequestBuilderTest`／`RoutesApiResponseParserTest`がRobolectric不使用のプレーンJUnit（`@RunWith`指定なし）であるため、org.jsonはRobolectric互換性以前にAndroidスタブ実装（`Stub!`例外を投げる）を踏んでしまい使用不能と判明した（`RoutesApiRequestBuilder.kt`・`RoutesApiResponseParser.kt`のKDoc参照） | **計画にない第3の経路（(a)(b)いずれでもない）を採用**: `RoutesApiRequestBuilder`はStringテンプレートで固定形状のリクエストJSONを直接組み立て、`RoutesApiResponseParser`は依存ゼロの手書き`MinimalJson`パーサ（`private object`、object/array/string/number/boolean/nullの再帰下降解析）を自前実装した。org.json・kotlinx-serialization-jsonいずれも追加していない（依存追加ゼロ、§88）。**org.json／kotlinx-serialization等への置換可否は、P3-C8（instrumented・G4-E）でRobolectric非依存の実環境検証が揃った後に再評価する**（本行を申し送り事項として記録） |
| 12 | §10エラー＆レスキューマップ#17〜20 vs 実装 | エラー＆レスキューマップは401/403（Unauthorized）・429（QuotaExceeded）・5xx（ServerError）・`routes`配列空（NoRoute）／不正JSON（MalformedResponse）の扱いを規定するが、**それ以外のHTTPステータス（例: 404・400）の扱いは仕様にもエラーマップにも規定がない**（**P3-C6・本統合サイクルでの追加実測により発見**） | `RoutesApiRoutingService.estimateRoute`（P3-C4実装）は、200/401/403/429/5xx以外のすべてのステータスを`RoutingException.ServerError`へ寄せるデフォルト分岐（`when`式の`else`節）を採用している（データを偽装せず、クラッシュもしない安全側の選択。`RoutesApiRoutingService.kt`のKDoc「契約の未定義域」参照）。**この分岐を専用に検証するJVMテストは存在しない**（T-ROUTESVC-3〜5は仕様で規定済みの401/403/429/5xxのみを対象とする）。404/400等の未定義ステータスに対する挙動は実装のみで担保されコード上の回帰検証がない状態である旨を記録し、Phase 3の残存ギャップとする |

---

## 16. 未検証事項の明示（Fable Protocol）

**本メモで実測により確定した事項**（すべて本セッションのツール実行結果に接地）:
- `play-services-location:21.4.0` が Google Maven の最新であり、AAR メタデータが `minCompileSdk=1` / `minCompileSdkExtension=0` / `minAndroidGradlePluginVersion=1.0.0` であること。推移依存 `play-services-base:18.9.0` / `play-services-basement:18.9.0` / `play-services-tasks:18.4.0` も同一値であること。
- `FusedLocationProviderClient.getCurrentLocation(CurrentLocationRequest, CancellationToken): Task<Location>` / `getLastLocation()` / `setMockMode` / `setMockLocation`、`CurrentLocationRequest.Builder`（`setPriority`/`setDurationMillis`/`setMaxUpdateAgeMillis`/`setGranularity`）、`Priority.PRIORITY_*` が 21.4.0 の classes.jar に実在すること（javap 実測）。
- `com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(Context): FusedLocationProviderClient` が21.4.0のapi.jarに実在すること（**P3-C6実測・javap**）。`AppContainer`から`LocationServices.getFusedLocationProviderClient(context)`／`Geocoder(context)`を実結線した状態で、Robolectric上の全239件（`Application.onCreate()`を経由する全テストを含む）が構築時例外なく成立すること（**P3-C6実測**。P3-P2「AVD上でのPlay services実在」とは別軸の「Robolectric JVM環境でのクライアント構築可否」を実測確認した）。
- `android.location.Geocoder` の非同期版 3メソッドが **API 33 以降**、同期版 3メソッドが **API 33 で deprecated（削除されていない）**、`isPresent()` が **API 9 以降**であること（android-35 の `api-versions.xml` 実測）。`GeocodeListener.onError(String)` が default メソッドとして存在すること。
- **Robolectric 4.16.1の`ShadowGeocoder`は、同期`getFromLocationName(String,Int)`（API 26〜32相当の実装分岐）で`setFromLocation`により設定した値を返さない（`assertEquals(1, result.size)`が`expected:<1> but was:<0>`で実測失敗）。非同期`getFromLocationName(String,Int,GeocodeListener)`（API 33+分岐）は同じfake設定から正しく`Success`へ収束する（P3-C6実測、`PlatformGeocoderSourceTest`参照）**。P3-P4「Robolectric上でのorg.json動作」は実装がorg.jsonを採用しなかったため事後的に無関係となった（本節末尾および§15 #11参照）。
- `kotlinx-coroutines-core` が現行構成の `debugRuntimeClasspath` で **1.9.0 に解決済み**であること（`:app:dependencies` 実測）。
- `https://routes.googleapis.com/directions/v2:computeRoutes` がキーなしで 403、存在しないメソッドが 404 を返すこと（**メソッドパスの実在**）。
- `.gitignore` に `local.properties` が含まれること。`buildConfig = true` が既に有効であること。`MockRoutingService` を参照するテストファイルが 0件であること（P3-C1〜C5時点の実測）。**`mock/MockRoutingService.kt`はP3-C6で削除済み**（削除後の`:app:testDebugUnitTest`全件実測はGreen。本節末尾参照）。
- **P3-C6実測（`p3c6-full.log`／`p3c6-full-rerun.log`、`--rerun`による強制再実行で2回とも同一結果を確認）**: `:app:testDebugUnitTest`は総239件・failures 0・errors 0・skipped 1（`AppContainerRoutingConfigTest.tCfg2`、本開発環境で`MAPS_ROUTES_API_KEY`が設定済みのためAssumeでskip＝設計どおり）・passed 238。`:app:assembleDebug`はexit 0（`p3c6-assembleDebug.log`）。詳細な内訳・引き継ぎ3件の解消記録は本書§12.1参照。

**未検証（要検証）**:
- **Routes API のリクエスト／レスポンスのフィールド仕様一式**（ヘッダ名 `X-Goog-Api-Key`／`X-Goog-FieldMask` の必須性、body フィールド名、`travelMode` 列挙値、`duration` の `"1234s"` 形式）。403 応答からエンドポイントの実在は確認したが、**中身は一切未検証**である（P3-P6）。
- Android 12+ における FINE 単独要求の挙動（COARSE 併記の必要性）。**「必須である」と断定できる根拠を本セッションでは取得していない**（P3-P1）。
- Routes API REST 直叩きで Android アプリ制限を効かせるための `X-Android-Package` / `X-Android-Cert` ヘッダの要否（§13 の依頼文にも未確認である旨を記載済み）。
- AVD `actionstarter_test` における Google Play services の実在と `FusedLocationProviderClient` の動作（P3-P2。**P3-C6でRobolectric JVM環境でのクライアント構築可否は実測済みだが、実AVD上での動作は依然未検証**であり両者は別軸である旨を明記する）。
- `adb emu geo fix` と FusedLocationProvider の連携（P3-P3）。
- ~~Robolectric 4.16.1 上での `org.json` 実装の動作（P3-P4）~~ → **P3-C6で事後的に無関係と判明**: 実装（P3-C4）は`RoutesApiRequestBuilder`／`RoutesApiResponseParser`のいずれもorg.jsonを採用せず、Stringテンプレート＋依存ゼロの手書き`MinimalJson`パーサで代替したため、org.json自体のRobolectric上の動作を検証する必要がなくなった（§15 #11に経緯を記録。**org.json／kotlinx-serialization等への置換可否はP3-C8後に再評価する**申し送り事項として残る）。
- **同期Geocoder（API 26〜32、`PlatformGeocoderSource.lookupSync`）のIOディスパッチャ課題（P3-C6・本統合サイクルで発見、既知ギャップとして記録・本サイクルでは未修正）**: `lookupSync`は`geocoder.getFromLocationName(query, maxResults)`という**ブロッキングI/O呼び出し**を`withContext(Dispatchers.IO)`等で明示的にディスパッチせず、呼び出し元のディスパッチャ（`DepartureViewModel`経由では`viewModelScope`＝`Dispatchers.Main.immediate`が既定）のまま実行する（`UrlConnectionHttpPostClient`が`withContext(Dispatchers.IO)`を明示している設計と非対称）。API 26〜32の実機ではメインスレッドをブロックしうる潜在的ANRリスクである。加えて上記のとおりRobolectric 4.16.1の`ShadowGeocoder`は同期パスで`setFromLocation`の値を返さないため、**JVM/Robolectricテストではこの経路のスレッド挙動もfake到達も検証できない**（instrumented環境でのみ検証可能・P3-C8のスコープ外〔E2Eの対象はT-E2E3-1〜4でありスレッド挙動の専用検証は含まれない〕）。本サイクルは`services/location/`配下のファイル変更が制約で禁止されているため、**修正は行わずPhase 3の既知ギャップとして記録するに留める**（是正はPhase 5以降の申し送り候補）
- エミュレータ上での `Geocoder.isPresent()` と実際の住所解決可否（P3-P5）。
- `HttpURLConnection` と coroutine キャンセルの協調（P3-P8）。
- Routes API の現行 SKU 別無料枠・単価（P3-P7。**仕様書自身が「実装着手時に再確認」を要求しており、本メモでは数値を一切断定していない**）。
- 200/401/403/429/5xx以外のHTTPステータス（例: 404/400）に対する`RoutesApiRoutingService`の`ServerError`デフォルト分岐（P3-C6・本統合サイクルで発見）を専用に検証するJVMテストが存在しないこと（§15 #12参照。実装は安全側のデフォルトを採っているが、この分岐自体の回帰検証は未整備）。
- **Phase 2 の現状**: **Phase 2はC5-fixで122/122 Green達成済み（2026-08-09・`p2c5fix-full.log`）。C6/C8のクローズ工程が進行中である。** R20 のとおり、Phase 3 着手条件は引き続きPhase 2のG4-JVM通過とし、P3-C1のベースライン確認は「着手時点の全スイートGreen実測（現行122件＋Phase 2 C6での増減を反映した件数）の記録」に簡素化する。**C6/C8完了後の最終件数は本書時点では未確定であり、P3-C1着手直前に再実測して確定することを必須条件とする。**

**§61（MVP に入れない機能）・§88（Developer UX Principle）への抵触なし**: 本計画は写真証明・NFC・QR・SNS・ランキング・友達機能・習慣化・自動予約・自動予定変更のいずれも含まない。追加した機能はすべて §67 の6項目、または §95 が「義務」「必須」と明記する制約（§95.2 スロットリング、§95.1 While-in-use、§95.4 権限フォールバック）への対応であり、「その機能は、予定を今やる一つの行動に変えることに直接寄与するか？」に Yes で答えられる。逆に §88 判定で No として明示的に見送ったものを §2.2 に列挙した（地図表示・場所ピッカー・出発地手動選択・外部ナビ起動・HTTP SDK 追加・キャッシュ永続化・サーバサイドプロキシ）。

---

## 本書についての注記

本書は起点計画メモ（android-planner、2026-08-09）の§0〜§16を全項目転記済みである。計画メモに記載のなかった機能・仕様の追加、および転記漏れは確認していない。テストケース表（§9）は全81件（正常系20／異常系27／エッジケース34）、エラー＆レスキューマップ（§10）は全22行、検証が必要な不明点（§11）はP3-P1〜P3-P8の全8件、PDCAサイクル（§12）はP3-C1〜P3-C8の全8サイクルであることを、本書内で数え直して計画メモと一致することを確認済みである。§3のFable 5裁定（S-1〜S-6・§15項目7の解釈）は2026-08-09にすべてandroid-planner推奨案どおり承認された。**Geminiによる第三者クロスレビュー（`model: "gemini-3.5-flash"`固定）はG1として実施済みであり、指摘されたCRITICAL 3件（Departure層の所有権と直列化、ForegroundGate判定式の拡張、Phase 2クローズ前提の更新）はFable 5裁定（2026-08-09）により本改訂で反映済みである（→G1通過）。** Phase 3の着手条件はPhase 2のG4-JVM通過（R20参照）であり、**Phase 2はC5-fixで122/122 Green達成済み（2026-08-09・`p2c5fix-full.log`）。C6/C8のクローズ工程が進行中であり、P3-C1のベースライン確認は「着手時点の全スイートGreen実測（現行122件＋Phase 2 C6での増減を反映した件数）の記録」に簡素化する**（`docs/plans/phase2-calendar.md`§18）。
