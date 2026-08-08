# ARCHITECTURE — Action Starter (Android)

> 本書は `Action_Starter_Master_Specification_v2.0_Android.md`（正仕様書）の要約である。差異がある場合は仕様書が正。v1.0(iOS)はアーカイブ。

## 1. レイヤー図とKotlinパッケージ対応（§43）

正仕様書§43のモジュール構成は、Androidでは単一`:app`モジュール内のKotlinパッケージ構造として表現する（ADR-0002）。パッケージ名は§43ツリーを機械的に命名したものであり、ツリー構成自体の正は仕様§43。

```
App         → com.actionstarter.app         (Application/AppContainer/Navigation)
Domain      → com.actionstarter.domain      (Event/ExecutionPlan/ExecutionStep/RecoveryPlan/PersonalExecutionProfile/ValueObjects)
Services    → com.actionstarter.services    (CalendarService/LocationService/RoutingService/NotificationService/LocalizationService)
Planning    → com.actionstarter.planning    (PlanningEngine/BasicPlanningEngine/LocalAIPlanningEngine)
Recovery    → com.actionstarter.recovery    (RecoveryEngine/BasicRecoveryEngine/LocalAIRecoveryEngine)
AI          → com.actionstarter.ai          (LocalLanguageModel/ModelManager/PromptBuilder/SchemaValidator/ModelAdapters)
Persistence → com.actionstarter.persistence (ExecutionStore/UserProfileStore/AnalyticsStore)
Features    → com.actionstarter.features.*  (eventselection/planreview/execution/departure/recovery/settings)
```

## 2. 単一`:app`方針と再検討トリガー（ADR-0002）

Phase 1は単一`:app`モジュールで開始し、マルチモジュール化は行わない（ADR-0002、`DECISIONS.md`参照。関連§43）。**Phase 7（Local LLM Runtime導入）でネイティブ依存が増大した時点、またはビルド時間が実用上の問題になった時点で分割要否を再検討する。**

## 3. 契約interface一覧とバージョン管理（§44-46・§16）

| interface | シグネチャ概要 | 参照§ |
|---|---|---|
| `PlanningEngine` | `suspend fun createPlan(context: PlanningContext): ExecutionPlan` | §44 |
| `RecoveryEngine` | `suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan` | §45 |
| `RoutingService` | `suspend fun estimateRoute(origin: Coordinate, destination: Coordinate, mode: TransportMode, departureDate: Instant): RouteEstimate` | §46（ADR-0004でシグネチャ確定） |
| `LocalLanguageModel` | `suspend fun generatePlan(context: PlanningContext): AIPlanResponse` ／ `suspend fun generateRecovery(context: RecoveryContext): AIRecoveryResponse` | §16 |

この4つはPhase 1の契約scaffoldで確定した後も凍結ではなく、**version付きの承認済み契約**として扱う（`docs/TEAMS.md`§5）。変更経路は固定: ①変更提案（起案agentが影響範囲メモを添付）→②android-plannerが影響分析→③Fable 5が承認→④`DECISIONS.md`へ記録→⑤UI/Domain両側のテスト更新。この経路を通らない契約変更は禁止する。

## 4. Domain Model一覧と補完型（§47-52・ADR-0005）

**仕様書定義済みのDomain Model**:

| 型 | 用途 | 参照§ |
|---|---|---|
| `ExecutionEvent` | カレンダー由来のイベント（id/title/startDate/location/coordinates/sourceCalendar） | §47 |
| `ExecutionStepType`（enum） | TRANSITION/PREPARATION/DEPARTURE/TRAVEL | §48 |
| `StepPriority`（enum） | REQUIRED/IMPORTANT/OPTIONAL | §48 |
| `ExecutionStep` | 個々の実行ステップ（種別・優先度・skippable・時刻） | §48 |
| `ExecutionPlan` | Event＋Stepsからなる実行計画全体 | §49 |
| `RecoveryContext` | Recovery生成の入力（現在時刻・現在地・未完了Step・最新Travel見積） | §50 |
| `RecoveryOption` | Recovery提案の1候補（最大3件、§32） | §51 |
| `PersonalExecutionProfile` | ユーザーごとの平均所要時間・遅延傾向 | §52 |

**ADR-0010（重要）**: Domain modelは全フィールド`val`＋`copy()`＋`init`再検証方式を採用する。仕様§48/§49/§52のコード例は一部`var`表記だが、生成後の再代入が`init`検証を迂回しサイレント障害を生むため、本プロジェクトでは意図的に`val`へ統一する（詳細は`DECISIONS.md` ADR-0010）。

**仕様書未定義の補完型7種（ADR-0005）**: §44-52のinterfaceが参照するが仕様書が型定義を明記していないため、Phase1契約scaffoldの前提として以下を補完定義する。最終フィールドはPhase1契約scaffold実装時に確定する。

| 型 | 用途 | 主要フィールド（案） | 導出根拠 |
|---|---|---|---|
| `Coordinate` | 緯度経度座標 | `latitude: Double`, `longitude: Double` | §46引数、§47 `coordinates` |
| `RouteEstimate` | `RoutingService.estimateRoute`の戻り値 | `travelDuration: Duration`, `distanceMeters: Double?`, `mode: TransportMode` | §46戻り値、§9 |
| `CalendarSource` | イベントの取得元カレンダー | `calendarId: Long`, `displayName: String`, `accountName: String` | §47 `sourceCalendar`、§66 |
| `PlanningContext` | `PlanningEngine.createPlan`の入力 | `event: ExecutionEvent`, `originLocation: Coordinate?`, `personalProfile: PersonalExecutionProfile?`, `arrivalBufferPreference: Duration` | §44引数、§25、§52 |
| `RecoveryPlan` | `RecoveryEngine.createRecoveryPlan`の戻り値 | `options: List<RecoveryOption>`（最大3件、§32）, `generatedAt: Instant` | §45戻り値、§31-32 |
| `AIPlanResponse` | `LocalLanguageModel.generatePlan`の戻り値。Schema Validation対象 | §20のJSON例（`event_type`/`steps[]`）に準拠する構造 | §16戻り値、§20 |
| `AIRecoveryResponse` | `LocalLanguageModel.generateRecovery`の戻り値。Schema Validation対象 | §32 `RecoveryOption`フィールドに準拠する候補リスト（最大3件） | §16戻り値、§32 |

## 5. 時間モデルと計算式（§4・§13）

コア時間モデルは常に以下の順で分離して扱う（§4）: Transition Time → Preparation Time → Travel Time → Arrival Buffer → Event Start。

- Transition Time: 現在の行動をやめ「準備できる状態」になるまで
- Preparation Time: 外出可能になるまで（着替え・荷物等）
- Travel Time: 現在地→目的地の実移動時間
- Arrival Buffer: 希望到着余裕（Tight 5分／Normal 10分／Relaxed 20分が初期値例、ユーザーごとに変更可能）

Basic Engineの出発準備開始時刻の計算式（§13）:

```
StartOfTransition = EventStart − ArrivalBuffer − TravelTime − PreparationTime − TransitionTime
```

この数値計算は必ず通常のKotlinコードで行い、LLMに委譲しない（§13・§15）。

## 6. 決定的計算とLLMの責務境界（§13・§14・§15）

| 領域 | 担当 | 参照§ |
|---|---|---|
| 日時・移動時間・時差・Arrival Buffer・出発/到着時刻演算・遅延検知・GPS・通知発火 | 決定的コード（Kotlin） | §13 |
| 予定文脈理解・eventType推定・Preparation/Transition Action生成・優先順位・省略可能性判定・Recovery候補生成・自然言語説明 | Local AI（LLM） | §14 |

**LLMに禁止すること（§15。コードレビュー時のチェック観点として扱う）**: GPS位置・正確な移動時間・時刻演算・到着時刻演算・カレンダー変更・通知発火・メール/SMS送信・予約変更・キャンセル・決済・タクシー予約・安全上重要な最終判断。これらをLLM出力に基づき直接実行する設計・実装があれば即座に差し戻す。決定的処理は常にKotlin側（§15）。詳細は仕様§15参照。

## 7. 2エンジン並存とフォールバック（§12・§19・§20）

Basic Engine（LLM非依存）とLocal AI Engine（端末内LLM）を同一アプリ内に両方実装し、`PlanningEngine`/`RecoveryEngine` interfaceで交換可能にする（§12）。目的は「LLM追加が本当にプロダクト価値を増やすか」の実測。

Local AIはEnhancementであり、モデル未ダウンロード・低スペック端末・AI OFF設定のいずれでも**アプリ全体が正常動作すること**が必須（§19、Single Point of Failureにしない）。

LLM出力の自由文をDomain Logicへ直接使用することを禁止し、Schema Validationを通過させる（§20）。Validation失敗時は retry 1回 → 失敗 → **Basic Engineへフォールバック**。

## 8. UI方針（§27-28・§89）

- Jetpack Compose。UiStateは不変（immutable data class）で表現し、ViewModelから単方向に流す。
- **One Action Only**（§27）: Execution中の主画面には原則1つの行動のみを表示する。
- 長大なチェックリストをメイン画面に出さない（§28）。認知負荷の最小化がUXの核。
- 巨大なComposable関数・巨大なViewModelを作らない（§89 No giant Composable / No giant ViewModel）。UI文字列の直書き禁止（string resources経由、§7）。

## 9. Android固有制約（§95）

詳細は仕様§95を参照。要点のみ列挙:

| # | 論点 | 概要 | 参照 |
|---|---|---|---|
| 1 | 時刻厳密通知 | Doze/OEM電池最適化下でも3通知（Transition/Departure/Recovery）を成立させるため、exact alarm＋Execution中Foreground Service化、再起動時の再登録が必須 | §95.1 |
| 2 | 経路APIのコスト/プライバシー | Routes APIは座標と移動手段のみ送信。スロットリング・キャッシュ必須 | §95.2 |
| 3 | 端末断片化とLocal LLM | RAM 6GB未満はLocal AI対象外、Basic Engineのみで完結 | §95.3 |
| 4 | 権限一覧表 | `PRIVACY.md`に転記済み | §95.4 |
| 5 | Play配布・審査 | 個人開発者アカウントはクローズドテスト12人以上×14日間の実績が必要 | §95.5 |
| 6 | エラー＆レスキューマップ | サイレント障害ゼロを要求する異常系一覧 | §95.6 |

## 10. テスト戦略とsource set分類

| source set | 対象 | 実行コマンド | 例 |
|---|---|---|---|
| `app/src/test`（JVM unit） | Domain/Engine等、Androidフレームワーク非依存の純Kotlinロジック | `:app:testDebugUnitTest` | BasicPlanningEngineの時刻計算 |
| `app/src/test`（Robolectric） | Composeの軽量スモークテスト等、Android APIスタブが必要だが実機不要な範囲 | `:app:testDebugUnitTest` | SmokeComposeTest（ADR-0006、C1完了条件） |
| `app/src/androidTest`（Instrumented） | Compose UI Test・実機/エミュレータ依存の統合検証（AVD: `actionstarter_test`） | `:app:connectedDebugAndroidTest` | 画面遷移・権限フローのE2E |

ADR-0006により、Phase 1のG4は即時必須の**G4-JVM**（上記2区分。KVM解決を待たずPhase 2着手を許可）と、KVM解決後必須の**G4-E**（Instrumented）に2段化する。**G4-EをPhase 3以降へキャリーすることは禁止する。**

## 11. 時刻方針（§8・ADR-0008）

内部時刻は`java.time`（`Instant`/`ZonedDateTime`/`ZoneId`/`Locale`）で分離して扱い、OS Localeへ追従する（§8）。Duration型は`java.time.Duration`に統一し、`kotlin.time.Duration`と混在させない（ADR-0008）。日本時間・24時間表記・`YYYY/MM/DD`固定を前提にしない（§8）。
