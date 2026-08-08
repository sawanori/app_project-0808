# Action Starter
## プロダクト仕様書・実装計画書・開発依頼書・AI引継ぎ書
### Master Specification v2.0 (Android)

**開発対象**：Android Native Application  
**初期市場**：日本  
**将来市場**：Global / 英語圏を含む海外  
**設計思想**：Global-first / Local-first / Privacy-first  
**MVP目的**：製品完成ではなく、実ユーザー環境でプロダクト仮説とLocal AI価値を検証すること

**本書について**：本書は v1.0（iOS版）を Android 向けに全面改訂したものである。設計思想（Global-first / Local-first / Privacy-first）とMVPの目的（製品完成ではなくプロダクト仮説とLocal AI価値の検証）はv1.0から不変である。プラットフォーム非依存の製品思想・時間モデル・UI原則・Recovery設計等の原文は温存し、Android固有の実装・権限・配布・リスクに関わる箇所のみを改訂した。変更点の一覧は次項「v1.0からの変更概要（Changelog）」を参照。

---

# v1.0からの変更概要（Changelog）

本書（v2.0 Android版）でv1.0（iOS版）から変更したセクションと変更内容の一覧。ここに記載のないセクション（§0〜§4, §6, §10, §12〜§14, §19〜§41の大部分, §53〜§57, §59〜§61, §64, §68, §70, §72, §74〜§76, §79〜§80, §82, §84〜§88, §94 等）は、プロダクト思想・時間モデル・One Action UI原則・Basic/Local AI二重エンジン思想・Recovery設計・Personal Execution Profile・Global-first/Local-first/Privacy-first・MVP禁止機能・KPI・失敗条件・成功シグナル・長期ロードマップ・Moat論を含め、v1.0の文言をそのまま温存している。

| # | セクション | 変更内容 |
|---|---|---|
| 1 | ヘッダー | 開発対象をAndroid Native Applicationに変更。v1.0（iOS版）からの全面改訂である旨と、設計思想・MVP目的が不変である旨を明記。 |
| 2 | §5 MVP対象ユーザー | テスター条件の対応カレンダー・地図アプリ例を「Apple Calendar / Google Calendar / Outlook等」「Google Maps / Apple Maps等」から「Google Calendar / Outlook等（Android端末に同期されるカレンダー）」「Google Maps等」に変更。 |
| 3 | §7 国際化要件 | Localization手段をSwiftUI String Catalogから Android string resources（`values/strings.xml` / `values-ja/strings.xml`、Composeでは`stringResource()`）に変更。 |
| 4 | §8 時刻・地域対応 | 内部時刻の分離手段を java.time（`Instant` / `ZonedDateTime` / `ZoneId` / `Locale`）で行うと明記。OS Locale追従の思想は不変。 |
| 5 | §9 移動手段 | `TransportMode`をKotlin enum classに、`RoutingService`をKotlin interfaceに変更。MVP第一候補をGoogle Maps Platform Routes APIとし、Mapbox / HERE / OSM系（GraphHopper等）へのProvider抽象を必須化。従量課金である点とMVP規模での無料枠試算を注記。 |
| 6 | §11 Local AIのユーザー価値 | 日本語ブランドメッセージ内の「あなたのiPhoneの中にいます」を「あなたのAndroid端末の中にいます」に修正。 |
| 7 | §15 LLMに禁止すること | 「決定的処理はSwift側。」を「決定的処理はKotlin側。」に修正。 |
| 8 | §16 Local LLM Runtime | `LocalLanguageModel` protocolをKotlin interface（suspend fun）に変更。 |
| 9 | §17 モデル選定方針 | 最低評価項目に「Androidデバイス断片化（RAM・チップセット差）への対応」を追加。既存項目は全て維持。 |
| 10 | §18 モデル配布 | アプリ内ダウンロード方式は維持のうえ、Play Asset Delivery / Play Feature Deliveryを配布手段の選択肢として注記。 |
| 11 | §42 技術スタック | Platform/Language/UI/Calendar/Location/Routing/Notifications/Persistence/DI/Local AI/Testing/DistributionをAndroidスタック（Kotlin / Jetpack Compose / CalendarProvider / FusedLocationProviderClient / Routes API / AlarmManager+NotificationManager / Room+DataStore / Hilt / Pluggable Local AI Runtime / JUnit・Robolectric・Compose UI Test / Google Play）に全面書き換え。 |
| 12 | §43 アーキテクチャ | 同一モジュール構成（App / Domain / Services / Planning / Recovery / AI / Persistence / Features）をKotlinパッケージ構造として表現する旨を明記。ツリー構成自体は不変。 |
| 13 | §44 PlanningEngine Protocol | Swift protocolをKotlin interface（suspend fun）に書き換え。セクションタイトルもProtocol→Interfaceへ変更。 |
| 14 | §45 RecoveryEngine Protocol | 同上。セクションタイトルもProtocol→Interfaceへ変更。 |
| 15 | §46 Routing abstraction | 同上。`Date`パラメータを`Instant`に変更。 |
| 16 | §47 Core Domain — Event | `struct`を`data class`に、`Date`を`Instant`に書き換え。フィールド構成は不変。 |
| 17 | §48 Step Model | `enum`を`enum class`に、`struct`を`data class`に、`TimeInterval`を`kotlin.time.Duration`に、`Date`を`Instant`に書き換え。フィールド構成は不変。 |
| 18 | §49 Execution Plan | 同上の型置換。フィールド構成は不変。 |
| 19 | §50 Recovery Context | 同上の型置換。フィールド構成は不変。 |
| 20 | §51 Recovery Option | `struct`を`data class`に書き換え。フィールド構成は不変。 |
| 21 | §52 Personal Execution Profile | `TimeInterval`を`Duration`に書き換え。フィールド構成は不変。 |
| 22 | §58 Privacy | 「予定の前後だけ取得」の方針により`ACCESS_BACKGROUND_LOCATION`を要求しない設計とする旨を追記。 |
| 23 | §62 Notifications | 3通知の時刻厳密性のためexact alarmを前提とする旨を追記し、詳細は§95参照とした。 |
| 24 | §63 Accessibility | Dynamic Type→フォントスケール追従、VoiceOver→TalkBackに置換。 |
| 25 | §65 Phase 1 | SwiftUI→Jetpack Compose、Simulator→Emulatorに置換。 |
| 26 | §66 Phase 2 | EventKit→CalendarProvider（READ_CALENDAR権限）に置換。 |
| 27 | §67 Phase 3 | CoreLocation+MapKit→FusedLocationProviderClient + Geocoder + Routes APIに置換。 |
| 28 | §69 Phase 5 | UserNotifications→AlarmManager exact + NotificationManagerに置換。POST_NOTIFICATIONS・SCHEDULE_EXACT_ALARM権限とExecution中のForeground Service化を追記。Boot/Time/Timezone変更時のアラーム再スケジュールを必須要件に追加。 |
| 29 | §71 Phase 7 | Runtime候補は§42参照、の一文を追記（内容は不変）。 |
| 30 | §73 Phase 9 | 「数値はSwift側が計算」を「数値はKotlin側が計算」に修正。 |
| 31 | §77 Phase 13 | TestFlight→Google Play クローズドテストに置換。個人開発者アカウントの配布要件（§95参照）を注記。 |
| 32 | §78 MVP完成条件 | 項目19「TestFlight配布」を「Google Play 内部/クローズドテスト配布」に変更。他19項目は不変。 |
| 33 | §81 Global Expansion条件 | 「英語圏TestFlightを開始。」を「英語圏Google Play クローズドテストを開始。」に修正。 |
| 34 | §83 長期ロードマップ | Stage 5内「iPhone以外も視野。」を「Android以外も視野。」に修正。ロードマップの思想・段階構成は不変。 |
| 35 | §89 実装品質 | 「no force unwrap unless justified」を「正当な理由のない `!!`（非null断定）禁止」に置換。「Compilable」を「Gradleビルド可能」、「No giant View」を「No giant Composable」に自然置換。他項目は不変。 |
| 36 | §90 開発AIへの引継ぎ指示 | Swift→Kotlin、Protocol→interface、Simulator→Emulatorに置換。「時刻厳密な通知はexact alarmとForeground Serviceで担保し、Doze・メーカー電池最適化下でも成立する設計にすること」を1項目追加。他の指示内容・思想は全て維持。 |
| 37 | §91 最初に生成AIへ実行させる指示 | 項目7「iOS権限設計」を「Android権限設計（通知・exact alarm含む）」に変更。 |
| 38 | §92 Phase 1開始Prompt | SwiftUI→Compose、Simulator→Emulator、EventKit/GPS/MapKit/LLM→CalendarProvider・GPS・Routes API・LLMに置換。 |
| 39 | §93 Local AI実装開始時のPrompt | Protocol→interface、iOS実機対応→Android実機対応に置換。比較項目に「対応モデル形式（gguf / LiteRT等）」を明記。 |
| 40 | §95（新設） | 「Android固有のプラットフォーム制約とリスク」を新規追加。時刻厳密通知リスクと対策（再起動時アラーム再登録を含む）、位置情報While-in-use制約、経路APIのコストとプライバシー（経路APIスロットリングを含む）、端末断片化とLocal LLM、権限一覧表、Play配布・審査要件、エラー＆レスキューマップ（ストレージ容量チェックを含む）を収録。 |

※ v2.0初版に対し、アーキテクトレビュー（Fable 5）およびクロスレビュー（Gemini / Codex）の指摘を反映済み（2026-08-08）。

---

# 0. 最重要原則

このプロジェクトに関わる生成AI・開発者は、以下を最上位原則として扱うこと。

> **人は予定を知らないから遅れるのではない。  
> 予定を、今やるべき一つの行動に変えられないから遅れる。**

したがって本アプリは、

- カレンダーアプリではない
- Todoアプリではない
- 習慣化アプリではない
- 単純な遅刻防止アプリではない
- AIスケジューラーではない
- 地図アプリではない

本アプリが担当するのは、

> **Plan → Execution**

の間に存在する空白である。

---

# 1. プロダクトの定義

内部的な定義：

> **Execution Assistant**

または、

> **Action Layer for Calendar**

とする。

既存サービスとの役割分担は以下。

**Calendar**

> いつ・どこで何があるか

**Maps**

> そこまで何分かかるか

**Task Manager**

> 何をする必要があるか

**AI Scheduler**

> 何時にタスクを配置するか

**Action Starter**

> **今の状態から、予定を成立させるために、今何をすればいいか**

---

# 2. ブランド／ユーザー向けメッセージ

日本語の第一候補：

> ## 予定は入れた。あとは動くだけ。

サブコピー：

> **遅れそうになる前に、次の一手を。**

プロダクト思想：

> **「そろそろ」ではなく、「今やる」を。**

海外向け英語コピー候補：

> **Plans are set. Now move.**

または、

> **Turn plans into action.**

補助メッセージ：

> **Know what to do next, right when it matters.**

ただしコピーはMVP完成後に別途検証するため固定しない。

---

# 3. 解決する問題

ユーザーには予定が存在する。

例えば、

> 10:00  
> Shibuya  
> Product shoot

しかし、この情報だけでは実行できない。

現実には、

> 現在の作業をやめる  
> ↓  
> 準備を始める  
> ↓  
> 着替える  
> ↓  
> 必要な物を準備する  
> ↓  
> 家を出る  
> ↓  
> 移動する  
> ↓  
> 到着する

という行動連鎖が存在する。

地図が表示するTravel Timeだけでは、この前半が管理されていない。

---

# 4. コア時間モデル

本アプリでは必ず以下を分離して扱う。

> **Transition Time**  
> ↓  
> **Preparation Time**  
> ↓  
> **Travel Time**  
> ↓  
> **Arrival Buffer**  
> ↓  
> **Event Start**

## Transition Time

現在行っていることを終了し、

> **「準備できる状態」**

になるまでの時間。

例：

- PC作業を終える
- ゲームを終了する
- 家事を中断する
- 支度に意識を切り替える
- シャワーに向かう
- 作業物を片付ける

重要なのは、Preparation Timeとは別に扱うこと。

## Preparation Time

外出可能になるまで。

例：

- 着替え
- 化粧
- 荷物
- PC
- 書類
- 撮影機材
- 水分
- 食事
- トイレ

## Travel Time

現在地から目的地までの実移動時間。

## Arrival Buffer

希望到着余裕。

初期値例：

- Tight：5分
- Normal：10分
- Relaxed：20分

ユーザーごとに変更可能。

---

# 5. MVP対象ユーザー

職業ベースではなく**行動ベース**で定義する。

第一ターゲット：

> **予定はちゃんと管理しているのに、準備・切替・出発で繰り返し崩れる人。**

初期テスター条件：

- 週2回以上、場所と時刻が決まった予定がある
- Google Calendar / Outlook等（Android端末に同期されるカレンダー）を利用
- Google Maps等を利用
- 過去1か月に以下のいずれかを経験
  - 遅刻
  - 忘れ物
  - 出発前の焦り
  - 出発予定時刻超過
  - 30分以上の過剰な早着

---

# 6. Global-first設計

初期リリース・検証市場は日本でも、**コード・データモデル・AI設計は最初から海外展開可能にする。**

これは必須要件。

## 禁止

コード内部に、

```text
撮影 → 電車 → 渋谷
```

など日本固有の生活前提を埋め込まない。

## 必須

内部モデルでは、

```text
eventType
transportMode
locale
timezone
calendar
location
userHistory
preferences
```

などの抽象データとして扱う。

---

# 7. 国際化要件

初期コードからLocalization対応する。

最低対応：

```text
ja-JP
en-US
```

将来的に追加可能：

```text
en-GB
ko-KR
zh-TW
de-DE
fr-FR
etc.
```

UI文字列の直接ハードコードは禁止。

Androidでは string resources（`values/strings.xml` / `values-ja/strings.xml`等、言語別`values-<lang>/strings.xml`）によるLocalizationを前提とする。Jetpack Composeでは`stringResource()`経由で参照し、Composable内への文字列直書きを禁止する。

---

# 8. 時刻・地域対応

絶対に、

```text
Japan Time
24時間表記
YYYY/MM/DD
```

を前提にしない。

内部時刻の分離は java.time（`Instant` / `ZonedDateTime` / `ZoneId` / `Locale`）で行う。

```text
Instant       // タイムゾーン非依存の時刻点
ZonedDateTime // 表示用のタイムゾーン付き時刻
ZoneId        // タイムゾーン
Locale        // 地域・言語設定
```

を分離。

海外では、

- 12時間 / 24時間
- 夏時間
- タイムゾーン移動
- 日付形式
- 週開始曜日

が異なる。

すべてOS Localeへ追従可能な設計にする。

---

# 9. 移動手段

Travel Timeは抽象化する。

```kotlin
enum class TransportMode {
    WALKING,
    DRIVING,
    TRANSIT,
    CYCLING
}
```

初期MVPは Google Maps Platform Routes API を第一候補とし、Mapbox / HERE / OSM系（GraphHopper等）へ差し替え可能な Provider抽象を必須とする。

```kotlin
interface RoutingService {
    suspend fun route(
        from: Coordinate,
        to: Coordinate,
        mode: TransportMode
    ): RouteEstimate
}
```

これにより国別の地図・経路提供元変更にも対応できる。

**注記**：経路APIは従量課金のクラウドAPIである（プライバシー詳細は§95参照）。Google Maps Platformは2025年3月に旧来のAPI横断の月額$200無料クレジットを廃止し、現在はSKU（Compute Routes等）ごとに個別の月次無料呼び出し枠を設定する方式に移行している。MVP規模（テスター15人程度、実予定ベースの低頻度リクエスト：初期Plan生成・Departure時の再計算・Recovery発生時の再計算など、1予定あたり数回程度）であれば、月間の呼び出し回数は数百〜千件程度と見積もられ、無料枠内に収まる可能性が高い。ただし正確な無料枠・単価は変動するため、実装着手時に最新のGoogle Maps Platform Pricingページで再確認し、必要呼び出し回数を再試算すること。

---

# 10. Local-first AI

本プロダクトの重要な設計思想。

扱う情報：

- カレンダー
- イベント名
- 訪問先
- 現在地
- 自宅付近
- 勤務先
- 行動履歴
- 出発履歴
- 移動傾向
- 準備時間
- 訪問頻度

非常にプライベート。

そのためLocal AIモードでは、

> **Calendar / Location / Behavioral Historyを外部LLMへ送らない。**

---

# 11. Local AIのユーザー価値

単純な、

> 「ローカルLLM搭載」

では売らない。

ユーザー価値としては、

> **Your AI lives on your phone.**

> **Your calendar stays private.**

> **Your schedule doesn't need to leave your device.**

日本：

> **あなたの予定を理解するAIは、あなたのAndroid端末の中にいます。**

とする。

---

# 12. AIあり・なしを両方実装

MVP最大の技術検証ポイント。

同一アプリ内に、

### Basic Engine

LLMなし。

### Local AI Engine

端末内LLMあり。

を両方実装する。

理由：

> **「LLMを載せることで、本当にプロダクト価値が増えるか」**

を実測するため。

---

# 13. Basic Engine

LLMに頼らない基本機能。

担当：

- 日時
- 移動時間
- 時差
- Arrival Buffer
- Preparation Time
- Transition Time
- 出発時刻
- 到着予想
- 遅延検知
- 通知
- GPS
- ユーザー入力値
- 固定テンプレート

計算：

```text
StartOfTransition
=
EventStart
- ArrivalBuffer
- TravelTime
- PreparationTime
- TransitionTime
```

数値計算は必ず通常コード。

---

# 14. Local AI Engine

LLMの仕事は、

> **Meaning → Action**

のみ。

担当：

- 予定文脈理解
- eventType推定
- 必要Preparation生成
- Transition Action生成
- 優先順位
- 必須／任意判定
- 省略可能性
- Recovery候補生成
- 個人履歴を考慮した行動提案
- ユーザー向け自然言語説明

---

# 15. LLMに禁止すること

LLM自身に以下を決めさせない。

- GPS位置
- 正確な移動時間
- 時刻演算
- 到着時刻演算
- カレンダー変更
- 通知発火
- メール送信
- SMS送信
- 予約変更
- 勝手なキャンセル
- 決済
- タクシー予約
- 安全上重要な最終判断

決定的処理はKotlin側。

---

# 16. Local LLM Runtime

実装はModel Adapter方式。

```kotlin
interface LocalLanguageModel {
    val modelIdentifier: String

    suspend fun generatePlan(
        context: PlanningContext
    ): AIPlanResponse

    suspend fun generateRecovery(
        context: RecoveryContext
    ): AIRecoveryResponse
}
```

特定モデル依存コードをUIやDomain層へ入れない。

モデルは技術検証で交換可能にする。

---

# 17. モデル選定方針

現時点では、小型多言語モデルを比較対象とする。

モデル名を製品仕様として固定しない。

最低評価項目：

- 日本語
- 英語
- 多言語理解
- Structured Output成功率
- 予定分類精度
- Preparation生成精度
- Recovery精度
- hallucination率
- 初回Token latency
- total latency
- RAM
- model size
- battery
- thermal throttling
- supported devices
- Androidデバイス断片化（RAM・チップセット差）への対応
- commercial license

特にGlobal-firstのため、**日本語だけで選定しない。**

---

# 18. モデル配布

アプリ本体への巨大モデル直接同梱を必須としない。

推奨：

> アプリインストール  
> ↓  
> Local AI有効化  
> ↓  
> Model Download

とする。

例：

```text
Enable Private AI
Additional download required.
```

端末性能によってモデルを選択できる構造を検討する。

配布手段としては、Google Playの Play Asset Delivery / Play Feature Delivery（オンデマンド配信によるモデル同梱・ダウンロード）も選択肢として検討する。ただし必須要件ではなく、上記のアプリ内ダウンロード方式を基本とする。

ダウンロード開始前にはストレージ空き容量の事前検証を必須とする（§95.6参照）。

---

# 19. AI OFF時でも動作すること

非常に重要。

モデルダウンロード失敗、低スペック端末、Local AI OFF等でも、

> **アプリ自体は正常に成立する**

必要がある。

Local AIはEnhancementであって、アプリ全体のSingle Point of Failureにしない。

---

# 20. Structured Output

LLM自由文をDomain Logicへ直接使用禁止。

例：

```json
{
  "event_type": "business_meeting",
  "steps": [
    {
      "id": "prepare_documents",
      "type": "preparation",
      "estimated_minutes": 10,
      "priority": "important",
      "skippable": true
    }
  ]
}
```

Schema validation必須。

Validation失敗：

> retry 1回  
> ↓  
> failure  
> ↓  
> Basic Engine

へフォールバック。

---

# 21. AI Promptの言語非依存化

可能な限り、

```text
eventType
actionType
priority
skippable
duration
```

など内部意味を英語IDで扱う。

LLMのUI表示文とDomain Meaningを分離する。

例：

```json
{
  "action_type": "check_equipment",
  "display_text": "機材を確認する"
}
```

英語環境：

```json
{
  "action_type": "check_equipment",
  "display_text": "Check your equipment"
}
```

---

# 22. Personal Execution Profile

長期的な競争力の中心。

ユーザーごとに端末内に保存。

```json
{
  "event_category": "client_meeting",
  "average_transition_minutes": 11,
  "average_preparation_minutes": 18,
  "average_departure_delay_minutes": 6,
  "preferred_arrival_buffer_minutes": 10
}
```

モデル自体を毎回Fine-tuningしない。

ユーザー履歴をLocal Contextとして与える。

---

# 23. 将来的な「Personal Execution Model」

本アプリの長期的な価値は、

> 一般論として「撮影なら30分」

ではなく、

> **「あなたなら実際には41分かかります」**

になること。

例：

ユーザー履歴：

```text
Client meeting
Preparation average: 18m

Location shoot
Preparation average: 42m

Departure instruction → actual departure:
average +7m
```

アプリ：

> 移動時間は42分です。

ではなく、

> **あなたの場合、出発まで平均7分余分にかかるため、8:58から準備を始めます。**

に進化する。

---

# 24. MVPユーザーフロー

## Phase A：Event Selection

アプリ起動。

```text
Next Event

10:00
Product Shoot
Shibuya

[Prepare this event]
```

---

# 25. Planning

カレンダーから、

```text
title
startDate
location
notes
```

等を取得。

Basic / Local AIがExecution Planを生成。

例：

```text
08:40 Stop current work
08:50 Get dressed
09:00 Check equipment
09:10 Leave
09:52 Estimated arrival
10:00 Event
```

---

# 26. Plan Review

必ずユーザー承認。

```text
Your plan

08:40 Stop working
08:50 Get dressed
09:00 Check equipment
09:10 Leave
09:52 Arrive

[Start]
[Edit]
```

AIが勝手に確定しない。

---

# 27. Execution Mode

プロダクト最重要UI。

原則、

> **ONE ACTION ONLY**

画面中央：

```text
NOW

Finish your current task

[Done]

[5 min later]
```

完了後：

```text
NOW

Get dressed

[Done]
```

---

# 28. Execution UI原則

ユーザーに、

```text
□ 作業終了
□ シャワー
□ 着替え
□ 荷物
□ 財布
□ 電車
□ 到着
```

の長大なリストをメイン画面で見せない。

認知負荷を下げる。

> **今やることだけ**

が本プロダクトのUX。

---

# 29. Departure Mode

最新現在地・経路情報から再計算。

```text
Leave now

Estimated arrival
09:52

Event
10:00

Buffer
8 min

[Start navigation]
```

単に、

> 出発時間です。

だけでは不足。

---

# 30. Reality Check

アプリは計画だけで終わらない。

予定進行中に、

```text
currentTime
currentLocation
completedSteps
unfinishedSteps
travelTime
eventStart
```

を比較する。

ここから、

```text
planned state
vs
actual state
```

を計算。

---

# 31. Recovery Mode

最大の独自価値。

予定：

```text
09:00 Equipment check
09:10 Leave
09:52 Arrival
10:00 Shoot
```

現実：

```text
09:16
Still home
Equipment check incomplete
Travel 42m
```

表示：

```text
Plan updated

If you continue preparing,
you'll arrive at 10:06.

Skip the detailed equipment check
and leave now.

Estimated arrival:
09:58

[Use this plan]

[See alternatives]
```

---

# 32. Recovery Option

最大3つまで。

```text
1. Leave now
   ETA 10:02

2. Change transport
   ETA 09:56

3. Prepare a delay message
```

選択肢過多は禁止。

---

# 33. Recoveryの優先原則

AIは、

> **完璧な準備**

ではなく、

> **予定成立**

を優先する。

ただし安全・必須物は勝手に省略しない。

例：

```text
required
important
optional
```

を必ず区別する。

---

# 34. ユーザー最終決定

AIは提案のみ。

以下はユーザー確認必須。

- ステップ省略
- 移動手段変更
- 予定変更
- 対外連絡
- 予約
- 支払い
- 他者への送信

---

# 35. 最初の5画面

### Screen 1 — Next Event

```text
Next event

10:00
Product Shoot
Shibuya

Prepare this event
```

### Screen 2 — Plan

```text
Your plan

08:40 Finish work
08:50 Get dressed
09:00 Check equipment
09:10 Leave
09:52 Arrive

Start
Edit
```

### Screen 3 — Now

```text
NOW

Finish your current task

Done
5 min later
```

### Screen 4 — Leave

```text
Leave now

ETA
09:52

Event starts
10:00

Start navigation
```

### Screen 5 — Recovery

```text
Plan updated

You're behind schedule.

Leave now and move
the detailed check to transit.

ETA
09:58

Use this plan
Other options
```

---

# 36. Basic vs Local AI比較

検証時に切替可能にする。

Developer Settings：

```text
Planner Mode

○ Basic
● Local AI
```

または内部Randomizationも将来的に可能。

---

# 37. Basic Mode

提供：

- Event読み込み
- Travel Time
- 手動Preparation
- 手動Transition
- Arrival Buffer
- Countdown
- Execution
- Departure
- deterministic recovery

---

# 38. Local AI Mode

追加：

- Event semantic understanding
- Event classification
- Action generation
- Transition suggestion
- Preparation suggestion
- priority reasoning
- optional-step detection
- Recovery reasoning
- personalized context
- natural explanation

---

# 39. 検証したいLocal AI価値

単に、

> AI回答が賢い

では不十分。

比較するのは、

### AIによって準備開始率が上がるか

### AIによってPlan修正回数が減るか

### AI Recoveryが選択されるか

### AI提案の却下率

### AI提案の修正率

### AI OFF時より再利用率が上がるか

### AIのために課金したいか

---

# 40. Local AI有料化仮説

Free / Proを想定する。

MVP時点で課金実装は必須ではない。

## Free

- Calendar Integration
- Maps / Travel Time
- Manual Transition
- Manual Preparation
- Basic Execution
- Basic Departure
- deterministic rules

## Pro

- Private Local AI
- Event Understanding
- Automatic Action Plan
- Personalized Execution
- Smart Recovery
- Personal Execution Profile
- Offline intelligence

---

# 41. 有料化メッセージ

避ける：

> AI機能が使えます。

推奨：

> **Private AI that understands how you actually move.**

または、

> **An AI that learns your real preparation time — without sending your calendar to the cloud.**

日本：

> **あなたの予定と行動を、外に送らず理解するAI。**

---

# 42. 技術スタック

初期：

```text
Platform:
Android（minSdk 26 目安 / targetSdk 最新。Local AIは端末要件により段階提供）

Language:
Kotlin

UI:
Jetpack Compose

Calendar:
CalendarProvider（CalendarContract、READ_CALENDAR権限。端末同期済みカレンダーを読むためLocal-firstと整合）

Location:
FusedLocationProviderClient（Google Play services）

Routing:
Google Maps Platform Routes API（RoutingService抽象で差替可能に）

Notifications:
AlarmManager（exact alarm）+ NotificationManager（+ WorkManager補助）

Persistence:
Room（+ Jetpack DataStore）

DI:
Hilt（推奨）

Local AI:
Pluggable Runtime / Adapter Architecture
候補：MediaPipe LLM Inference API（Google AI Edge）/ llama.cpp（JNI）/ MLC-LLM / ONNX Runtime Mobile / AICore・Gemini Nano（対応端末のみ）
特定Runtimeを仕様として固定しない

Testing:
JUnit / Robolectric / Compose UI Test（+ Maestro任意）

Distribution:
Google Play 内部テスト → クローズドテスト（+ Firebase App Distribution 補助）
```

---

# 43. アーキテクチャ

同一モジュール構成を、Androidでは Kotlin パッケージ構造として表現する（例：`com.actionstarter.domain` / `com.actionstarter.services` / `com.actionstarter.planning` 等）。

```text
ActionStarterApp
│
├── App
│
├── Domain
│   ├── Event
│   ├── ExecutionPlan
│   ├── ExecutionStep
│   ├── RecoveryPlan
│   ├── PersonalExecutionProfile
│   └── ValueObjects
│
├── Services
│   ├── CalendarService
│   ├── LocationService
│   ├── RoutingService
│   ├── NotificationService
│   └── LocalizationService
│
├── Planning
│   ├── PlanningEngine
│   ├── BasicPlanningEngine
│   └── LocalAIPlanningEngine
│
├── Recovery
│   ├── RecoveryEngine
│   ├── BasicRecoveryEngine
│   └── LocalAIRecoveryEngine
│
├── AI
│   ├── LocalLanguageModel
│   ├── ModelManager
│   ├── PromptBuilder
│   ├── SchemaValidator
│   └── ModelAdapters
│
├── Persistence
│   ├── ExecutionStore
│   ├── UserProfileStore
│   └── AnalyticsStore
│
└── Features
    ├── EventSelection
    ├── PlanReview
    ├── Execution
    ├── Departure
    ├── Recovery
    └── Settings
```

---

# 44. PlanningEngine Interface

```kotlin
interface PlanningEngine {
    suspend fun createPlan(
        context: PlanningContext
    ): ExecutionPlan
}
```

---

# 45. RecoveryEngine Interface

```kotlin
interface RecoveryEngine {
    suspend fun createRecoveryPlan(
        context: RecoveryContext
    ): RecoveryPlan
}
```

---

# 46. Routing abstraction

```kotlin
interface RoutingService {
    suspend fun estimateRoute(
        origin: Coordinate,
        destination: Coordinate,
        mode: TransportMode,
        departureDate: Instant
    ): RouteEstimate
}
```

---

# 47. Core Domain — Event

```kotlin
data class ExecutionEvent(
    val id: UUID,
    val externalCalendarId: String?,

    val title: String,
    val notes: String?,

    val startDate: Instant,

    val locationName: String?,
    val coordinates: Coordinate?,

    val sourceCalendar: CalendarSource
)
```

---

# 48. Step Model

```kotlin
enum class ExecutionStepType {
    TRANSITION,
    PREPARATION,
    DEPARTURE,
    TRAVEL
}
```

```kotlin
enum class StepPriority {
    REQUIRED,
    IMPORTANT,
    OPTIONAL
}
```

```kotlin
data class ExecutionStep(
    val id: UUID,

    val semanticId: String,
    val type: ExecutionStepType,

    var title: String,

    var estimatedDuration: Duration,

    var priority: StepPriority,

    var skippable: Boolean,

    var scheduledStart: Instant?,

    var completedAt: Instant?
)
```

---

# 49. Execution Plan

```kotlin
data class ExecutionPlan(
    val event: ExecutionEvent,

    var steps: List<ExecutionStep>,

    var transitionStart: Instant,
    var departureTime: Instant,

    var estimatedArrival: Instant,

    var arrivalBuffer: Duration
)
```

---

# 50. Recovery Context

```kotlin
data class RecoveryContext(
    val currentTime: Instant,
    val currentLocation: Coordinate?,

    val event: ExecutionEvent,

    val unfinishedSteps: List<ExecutionStep>,

    val latestTravelEstimate: Duration,

    val plannedDepartureTime: Instant
)
```

---

# 51. Recovery Option

```kotlin
data class RecoveryOption(
    val id: UUID,

    val semanticAction: String,

    val title: String,
    val explanation: String,

    val estimatedArrival: Instant?,

    val skippedStepIds: List<UUID>
)
```

---

# 52. Personal Execution Profile

```kotlin
data class PersonalExecutionProfile(
    var eventCategory: String,

    var averageTransitionDuration: Duration,
    var averagePreparationDuration: Duration,

    var averageResponseDelay: Duration,
    var averageDepartureDelay: Duration,

    var preferredArrivalBuffer: Duration
)
```

---

# 53. Analytics / Test Log

最低限記録する。

```text
event_selected

plan_requested
plan_created
plan_modified
plan_accepted

execution_started

step_shown
step_done
step_snoozed
step_skipped

departure_prompted
departure_started

recovery_triggered
recovery_option_shown
recovery_selected

navigation_started

arrival_detected

event_completed

next_event_selected
```

---

# 54. 時刻ログ

各ステップに、

```text
plannedAt
shownAt
actedAt
completedAt
```

を可能な限り記録。

---

# 55. MVP KPI

一般的なMAU等より、行動変化を見る。

## Preparation Start Rate

提示後5分以内に開始。

## Action Response Time

通知→アクションまで。

## Departure Delay

予定出発との差。

## Arrival Buffer

早着・遅着の双方。

## Recovery Acceptance Rate

Recovery案を採用した割合。

## Plan Modification Rate

AI提案がどの程度修正されるか。

## Reuse Rate

非常に重要。

> **次の実予定でも自分からExecutionを開始したか。**

---

# 56. Basic vs AI KPI

Local AIの価値を判断する。

```text
Basic Preparation Start Rate
vs
AI Preparation Start Rate

Basic Plan Modification Rate
vs
AI Plan Modification Rate

Basic Reuse
vs
AI Reuse

Basic Recovery acceptance
vs
AI Recovery acceptance
```

---

# 57. Local AI性能指標

```text
Plan generation latency

Recovery latency

JSON validity

Schema validity

Plan acceptance

Plan correction

Hallucination report

RAM

Battery

Thermal

Model download size
```

---

# 58. Privacy

デフォルトで必要最小限。

位置情報常時監視を前提にしない。

可能なら、

> **予定の前後だけ**

取得する。

この方針により **ACCESS_BACKGROUND_LOCATION を要求しない設計**とし、Play審査リスクを低減する（詳細は§95参照）。

---

# 59. Local Data

原則端末保存。

保存：

- Execution History
- Personal Profile
- AI Preferences
- Plan History

MVPではクラウド同期を必須にしない。

---

# 60. Telemetry

検証用Analyticsを導入する場合も、

カレンダー本文・住所等を不用意に送信しない。

例：

送ってよい：

```text
event_category_hash
plan_generation_ms
step_count
delay_seconds
AI_enabled
```

原文イベントタイトル等は送らない方針を優先。

---

# 61. MVPに入れない機能

明示的に禁止。

- 写真証明
- 写真AI
- NFC
- QR
- 金銭ペナルティ
- ストリーク
- SNS
- ランキング
- 友達機能
- 習慣化
- 汎用Todo
- Project Management
- AI Chat
- Health diagnosis
- Sleep diagnosis
- 自動メール送信
- 自動SMS
- 自動予定変更
- 自動予約
- スマートホーム連携

---

# 62. Notifications

通知を増やすアプリにしない。

重要局面は基本3つ。

### 1. Transition開始

### 2. Departure

### 3. Recovery

通知疲れを避ける。

これら3通知は時刻厳密性が要求されるため、Androidでは exact alarm（`SCHEDULE_EXACT_ALARM`等）を前提とする。Doze・電池最適化の影響と対策の詳細は§95を参照。

---

# 63. Accessibility

将来の海外展開を考え、

- フォントスケール追従（Dynamic Typeに相当。Composeの`sp`単位・システムフォントサイズ追従）
- TalkBack（VoiceOverに相当するAndroidのスクリーンリーダー対応）
- reduced motion
- high contrast
- color-only information禁止

を早期から意識する。

---

# 64. Development Phase 0

リポジトリ作成。

以下を先に用意。

```text
README
ARCHITECTURE.md
PRODUCT.md
AI.md
PRIVACY.md
DECISIONS.md
```

生成AIは重大な設計変更時にDECISIONS.mdへ記録する。

---

# 65. Phase 1

**UI Skeleton + Domain**

実装：

- Jetpack Compose
- Domain Models
- Event Selection Mock
- Plan Review
- Execution Screen
- Recovery Screen

この時点ではデータMock可。

完成条件：

> Emulator上で一連のUXが動く。

---

# 66. Phase 2

**Calendar**

CalendarProvider（CalendarContract）。

実装：

- Permission（READ_CALENDAR）
- Calendar List
- Upcoming Events
- Location付きイベント抽出
- Event Selection

完成条件：

> 実カレンダーから予定を選択可能。

---

# 67. Phase 3

**Routing / Location**

FusedLocationProviderClient + Geocoder + Routes API。

- Permission（ACCESS_FINE_LOCATION）
- current location
- destination geocoding
- route estimation
- transport mode
- ETA

完成条件：

> 現在地→予定先の所要時間が取れる。

---

# 68. Phase 4

**Basic Engine**

実装：

- Transition
- Preparation
- Travel
- Buffer
- deterministic planning
- departure calculation

完成条件：

> LLMゼロでExecution Planが成立。

---

# 69. Phase 5

**Notification + Execution**

- AlarmManager（exact alarm）+ NotificationManager
- Boot/Time/Timezone変更時のアラーム再スケジュール（RECEIVE_BOOT_COMPLETED、§95参照）
- POST_NOTIFICATIONS・SCHEDULE_EXACT_ALARM権限
- Execution中はForeground Service化
- Step start
- Done
- Snooze
- next action
- departure

完成条件：

> 実際にAndroid実機上で時間経過型Executionができる。

---

# 70. Phase 6

**Recovery Basic**

- lateness detection
- remaining preparation
- recalculation
- deterministic alternatives

完成条件：

> 遅れをシミュレートするとRecovery画面へ遷移。

---

# 71. Phase 7

**Local LLM Runtime**

- Model Manager
- Download
- Load
- Inference
- Memory Handling
- Structured Output
- Schema validation

Runtime候補は§42参照。

完成条件：

> オフライン状態でテストPrompt→JSON取得。

---

# 72. Phase 8

**Local AI Planning**

Local AIで、

```text
Calendar Event
→
Semantic Event
→
Execution Steps
```

生成。

Basicと比較可能。

---

# 73. Phase 9

**Local AI Recovery**

現在状態から、

```text
remaining steps
travel
deadline
priority
```

を与えてRecovery候補生成。

数値はKotlin側が計算しLLMに渡す。

---

# 74. Phase 10

**Personal Profile**

履歴を保存。

- preparation actual
- transition actual
- departure lag
- arrival buffer

次回Planへ反映。

---

# 75. Phase 11

**Localization**

最低：

```text
Japanese
English
```

英語環境で一通り動作確認。

---

# 76. Phase 12

**Basic / AI Experiment**

内部切替。

データログ。

同一ユーザーで比較可能。

---

# 77. Phase 13

**Google Play クローズドテスト**

15人程度。

ただし「意見収集」ではなく、

> **実予定を使う。**

個人開発者アカウントの配布要件（クローズドテスト実績）については§95を参照。

---

# 78. MVP完成条件

以下すべて。

1. 実Calendar予定取得
2. 場所認識
3. Route取得
4. Transition計算
5. Preparation
6. Departure
7. Arrival Buffer
8. One Action UI
9. Notification
10. Recovery
11. Basic Engine
12. Local AI Engine
13. AI OFFでも成立
14. 完全オフラインLocal AI推論
15. Structured Output
16. Personal History
17. ja/en Localization
18. 行動ログ
19. Google Play 内部/クローズドテスト配布
20. 実予定検証

---

# 79. 失敗条件

以下になった場合は機能追加せず再検討。

- AI提案をほぼ全員が大幅修正
- Action通知が行動開始につながらない
- Recoveryが役立たない
- Local AIあり/なしで差がない
- 位置情報許可率が低い
- バッテリー負担が許容不可
- Local AI latencyがUXを阻害
- 再利用されない

---

# 80. 成功シグナル

特に重視：

> **「次の予定でも使う」**

ユーザーが自発的に次回の実予定で使用する。

そのうえで、

> **Local AIをOFFにしたくない**

が出れば、AI Pro化の有力シグナル。

---

# 81. Global Expansion条件

日本で完璧になるまで海外を待つ必要はない。

ただし初期検証で、

- 行動開始改善
- 再利用
- AI価値

の兆候を確認した後、

英語圏Google Play クローズドテストを開始。

---

# 82. 海外検証時に見る項目

- Transport behavior
- Calendar習慣
- driving vs transit
- arrival buffer文化
- punctuality expectations
- notification tolerance
- privacy
- Local AI appeal
- terminology

機能思想は共通でも行動文化は地域で異なるため、固定テンプレートを避ける。

---

# 83. 長期ロードマップ

### Stage 1

External events

> 家を出る予定。

### Stage 2

Online events

> オンライン会議開始への切替。

### Stage 3

Preparation-heavy events

> フライト、面接、プレゼン等。

### Stage 4

Personal Execution Layer

> 日常予定全体。

### Stage 5

Cross-platform Execution Assistant

Android以外も視野。

---

# 84. 長期的Moat

単なるLLMではない。

モデルは他社も使える。

差別化資産は、

> **Personal Execution Profile**

と、

> **予定→実行→結果**

のフィードバックループ。

ユーザーが使うほど、

> 一般的な時間推定

ではなく、

> **「このユーザーなら実際どう動くか」**

へ近づく。

---

# 85. Apple/Googleに模倣されるリスク

高い。

出発通知、位置情報、CalendarはOS側が持つ。

したがってMoatを、

> 「出発通知」

に置かない。

置くのは、

- Preparation behavior
- Transition behavior
- Personal execution history
- Recovery preference
- recurring personal patterns
- Local private intelligence

---

# 86. AI課金の考え方

Local LLMそのものに課金するのではない。

課金対象：

> **Personal + Private Execution Intelligence**

Local AIが、

- 自分の予定を理解
- 自分の過去を理解
- 自分の準備速度を理解
- 自分向けRecovery
- 外部AIへ送らない

というセット。

---

# 87. プライシングは未決定

この仕様では価格を固定しない。

MVPで検証する問い：

> 「Local AIを無くしたFree版でも十分か？」

> 「Local AIがあるから月額を払うか？」

---

# 88. Developer UX Principle

生成AIは機能を勝手に追加しない。

判断基準：

> **その機能は、予定を今やる一つの行動に変えることに直接寄与するか？**

NoならMVPへ入れない。

---

# 89. 実装品質

生成AIに求める。

- Gradleビルド可能（Compilable）
- Testable
- Modular
- No giant Composable（巨大なComposable関数を作らない）
- No giant ViewModel
- No hard-coded secrets
- No duplicated domain logic
- 正当な理由のない `!!`（非null断定）禁止
- Error handling
- Permission failure handling
- Model failure fallback
- Offline behavior

---

# 90. 開発AIへの引継ぎ指示

ここから下は**そのまま別AIへ渡せます。**

> あなたは「Action Starter」プロジェクトを引き継ぐシニアAndroidエンジニア兼AIプロダクトエンジニアです。
>
> このプロジェクトの目的はTodoアプリ、カレンダーアプリ、習慣化アプリを作ることではありません。
>
> ユーザーが現在行っている行動から、予定を成立させるための「今やるべき一つの行動」へ切り替わることを支援するExecution Assistantを開発します。
>
> 時間モデルは必ず、
>
> Transition Time → Preparation Time → Travel Time → Arrival Buffer → Event Start
>
> としてください。
>
> UIの最重要原則は「One Action Only」です。Execution中の主画面には、原則として今やるべき行動を一つだけ表示してください。
>
> 予定どおり進んでいない場合は「遅れています」と通知するだけでは不十分です。現在時刻、現在地、移動時間、未完了ステップ、予定開始時刻を基に、予定成立に必要な最小Recovery Planを提示してください。
>
> AIは時刻計算、GPS計算、移動時間計算などの決定的ロジックを担当してはいけません。これらはKotlinの通常コードで処理してください。
>
> 時刻厳密な通知（Transition開始・Departure・Recovery）はexact alarmとForeground Serviceで担保し、Doze・メーカー電池最適化下でも成立する設計にしてください。
>
> Local LLMは、
>
> ・予定の意味理解  
> ・event classification  
> ・Transition / Preparation Action生成  
> ・priority判断  
> ・skippable判断  
> ・Recovery案生成  
> ・説明文生成
>
> のみに使用してください。
>
> PlanningEngineおよびRecoveryEngineをKotlin interfaceで抽象化し、
>
> Basic Engine  
> Local AI Engine
>
> を同一アプリ内で交換・比較可能にしてください。
>
> Local AIが停止・未インストール・非対応端末の場合でもBasic Engineでアプリが正常動作することを必須とします。
>
> Local AIモードでは、カレンダー本文、位置情報、行動履歴を外部AIサーバーへ送らないLocal-first設計を原則としてください。
>
> プロジェクトは将来的な海外展開を前提にします。初期検証は日本ですが、データモデル、日時、Locale、TimeZone、Transport、UI文字列に日本固有前提をハードコードしてはいけません。
>
> 日本語・英語Localization可能な設計としてください。
>
> 内部Domain Modelは可能な限り言語非依存にしてください。
>
> 写真証明、NFC、罰金、ストリーク、SNS、ランキング、友達機能、汎用Todo、プロジェクト管理、AI ChatなどはMVPに実装してはいけません。
>
> AI出力は必ずStructured Outputとし、Schema Validationを通してください。失敗した場合にはBasic Engineへ安全にフォールバックしてください。
>
> AIによる勝手なカレンダー変更、外部送信、予約変更、キャンセル、決済等は禁止です。対外操作は必ずユーザー確認を通してください。
>
> Personal Execution Profileを端末内に保持し、ユーザーのTransition Time、Preparation Time、出発指示から実際の出発までの遅延、Arrival Buffer等を将来的な個人最適化に利用できる設計にしてください。
>
> いきなり全機能を実装しないでください。
>
> 各Phase終了時に、必ずGradleビルド可能・実機またはEmulatorで確認可能な状態にしてください。
>
> 大きな設計変更を行う場合は、コード変更前に理由、代替案、影響範囲を提示してください。
>
> このプロジェクトの最上位原則は、
>
> 「人は予定を知らないから遅れるのではない。予定を、今やるべき一つの行動に変えられないから遅れる。」
>
> です。
>
> この原則から外れる機能追加を行わないでください。

---

# 91. 最初に生成AIへ実行させる指示

上のマスター仕様書を渡した後、最初の指示はこれでいいです。

> **まずコードを書かないでください。**
>
> この仕様書を読んだ上で、
>
> 1. プロダクト目的の理解  
> 2. MVPスコープ  
> 3. Domain Model  
> 4. Architecture  
> 5. Directory Structure  
> 6. Phase 1〜13の実装順  
> 7. Android権限設計（通知・exact alarm含む）  
> 8. Local LLM Adapter設計  
> 9. Basic/AI切替設計  
> 10. 想定される技術リスク
>
> を整理してください。
>
> 仕様に矛盾・不足・技術的に実現困難な部分があれば、それだけを指摘してください。
>
> 勝手に機能を追加しないでください。
>
> 私の承認後、Phase 1から実装を開始してください。

これが**開発開始Prompt**です。

---

# 92. Phase 1開始Prompt

設計レビュー後：

> Phase 1を実装してください。
>
> 今回の対象は、
>
> ・Jetpack Compose Project Structure  
> ・Domain Models  
> ・Mock Event  
> ・Event Selection Screen  
> ・Plan Review Screen  
> ・Execution One Action Screen  
> ・Departure Screen  
> ・Recovery Screen
>
> のみです。
>
> CalendarProvider、GPS、Routes API、LLMはまだ接続しないでください。
>
> Mock Dataのみで、
>
> Event Selection → Plan Review → Execution → Departure → Recovery
>
> の主要UXをEmulator上で確認できる状態にしてください。
>
> 実装終了時に、
>
> ・変更ファイル  
> ・Architecture  
> ・実行方法  
> ・確認ポイント  
> ・未実装事項
>
> を報告してください。

---

# 93. Local AI実装開始時のPrompt

> Local AIフェーズを開始してください。
>
> ただし、特定のモデルをDomain層へ直接依存させないでください。
>
> LocalLanguageModel interfaceとModel Adapterを介して実装してください。
>
> 最初に、
>
> ・候補Runtime  
> ・対応モデル形式（gguf / LiteRT等）  
> ・Android実機対応  
> ・Memory footprint  
> ・Model download方法  
> ・4bit等の量子化対応  
> ・Structured Outputの方法  
> ・license  
> ・Japanese / English性能
>
> を比較し、採用案を提示してください。
>
> 私の承認後にモデルを組み込んでください。
>
> Local AIが使用できない状態ではBasicPlanningEngineへフォールバックしてください。

---

# 94. 最終的なプロダクト像

本アプリが目指すのは、

> 「予定表をもっと賢くする」

ことではありません。

ユーザーが、

> 「そろそろ準備しなきゃ」

と思いながら動けない瞬間に、

> **今やっていることを終えてください。**

次に、

> **着替えてください。**

そして、

> **今出てください。**

遅れたら、

> **これは省いてください。今出ればまだ間に合います。**

と、

**認知→判断→実行の負荷を一つずつ引き取る。**

それがAction Starterです。

そして将来的に、

> Calendar knows **when**.  
> Maps knows **how long**.  
> **Action Starter knows what you should do now.**

という立ち位置まで持っていく。

これを**日本で検証し、最初からGlobal-first / Local-firstで実装する**のが、現時点でのマスター方針です。

---

# 95. Android固有のプラットフォーム制約とリスク

iOS版（v1.0）には存在しなかった、Android固有のプラットフォーム制約・審査要件・リスクをここに集約する。§9・§18・§42・§58・§62・§77等の該当箇所からも本セクションを参照している。

## 1. 時刻厳密通知（最重要リスク）

本アプリの通知3種（Transition開始・Departure・Recovery、§62参照）は、数分のズレが「間に合う／間に合わない」を左右するため、時刻の厳密性がプロダクト価値に直結する。Androidでは以下の要因により通知が遅延・抑制されうる。

- **Doze mode / App Standby**：画面OFF・非充電・端末静止状態が続くと、OSがバックグラウンド処理・ネットワーク・アラームをバッチ化・延期する。
- **メーカー独自の電池最適化**：特にXiaomi（MIUI）、OPPO、vivo、Huawei等の一部OEMは、AOSP標準のDoze以上に積極的なバックグラウンドプロセスの停止・通知抑制を行うことが知られている（中華系OEMで顕著）。

対策：

- `SCHEDULE_EXACT_ALARM`（API 31以降で必要）または `USE_EXACT_ALARM`（API 33以降。ただしカレンダー/アラームアプリに該当するカテゴリのアプリに限定される特別権限）によるexact alarmを用い、`AlarmManager.setExactAndAllowWhileIdle()`等でDoze下でも発火させる。
- Execution Mode中（予定成立に向けて実行中の間）はForeground Service化し、プロセスと通知の優先度を確保する。
- 初回のLocal AI有効化・通知許可時に、電池最適化除外（メーカー独自の「バッテリー使用量の最適化」対象外設定）を案内するオンボーディングUIを用意する。
- Pixel、Samsung、Xiaomi等、主要OEMの実機での通知遅延実測をリリース前QAの必須項目とする（Emulatorのみでの検証では不十分とする）。
- exact alarm設定前に `AlarmManager.canScheduleExactAlarms()` で許可状態を確認し、未許可時はinexact alarmへフォールバックした上で精度低下をユーザーに明示する（§95.6のエラーマップと整合）。
- **端末再起動でAlarmManagerの登録は全消去される**ため、`RECEIVE_BOOT_COMPLETED` 権限と `ACTION_BOOT_COMPLETED` を受けるBroadcastReceiverを実装し、Room（ExecutionStore）の未完了Execution Planからexact alarmを再スケジュールする。`ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` 受信時も同様に再登録する。
- 通知用PendingIntentはrequestCode等で一意性を担保し、再スケジュール時の重複発火・古いアラームの残存を防ぐ。

### 位置情報のWhile-in-use制約

本アプリは `ACCESS_BACKGROUND_LOCATION` を要求しない（§58）。Android 11以降のWhile-in-use制約により、**アプリがバックグラウンドの状態でアラーム等から起動した処理・Foreground Serviceでは位置情報を取得できない**。

したがって、アラーム発火時のバックグラウンド処理では位置取得を前提とせず、通知の提示のみを行う。位置情報を用いたETA再計算・Reality Checkは、(a) ユーザーが通知をタップする等でアプリがフォアグラウンドに復帰した時点、または (b) ユーザーがフォアグラウンドで開始したExecution Mode中のForeground Service（location type。フォアグラウンド中に開始した場合のみ位置アクセスを継続できる）の継続中、のいずれかでのみ実行する。

この設計により、Privacy-first（§58）とPlay審査リスク回避を維持したまま、サイレントな位置取得失敗（SecurityException／null位置）を構造的に回避する。

## 2. 経路APIのコストとプライバシー

Google Maps Platform Routes APIは従量課金のクラウドAPIであり、リクエストごとに出発地・目的地の座標と移動手段のみを送信する。カレンダー本文・イベントタイトル・訪問先名等は送信しない（§58〜§60のPrivacy/Telemetry方針と整合する）。

Google Maps Platformは2025年3月に、従来のAPI横断の月額$200無料クレジットを廃止し、現在はSKU（Compute Routesを含む）ごとに個別の月次無料呼び出し枠を設定する方式に移行している。MVP規模（テスター15人程度、実予定ベースの低頻度リクエスト：初期Plan生成時の見積もり・Departure時の再計算・Recovery発生時の再計算など、1予定あたり数回程度）であれば、月間の呼び出し回数は数百〜千件程度にとどまると見積もられ、無料枠内に収まる可能性が高い。ただし正確な無料枠・単価は変動するため、実装着手時に最新のGoogle Maps Platform Pricingページで再確認し、想定呼び出し回数を再試算すること（§9参照）。

コストやリージョン都合に応じて、§9のRoutingService Provider抽象によりMapbox / HERE / OSM系（GraphHopper等）へ切り替え可能な設計を維持する。

Reality Check（§30）・Departure Mode等での再計算は、Routes APIをポーリングせず、スロットリングとキャッシュを義務とする。目安として「前回呼び出しからの移動距離が閾値（例: 500m）未満かつ経過時間が閾値（例: 10分）未満の場合はキャッシュ済みETAを使用する」等のルールを実装時に定め、決定的な時刻演算（Basic Engine側）はキャッシュ値でも常に成立させる。

## 3. 端末断片化とLocal LLM

iOSと異なりAndroidは端末・チップセットのラインナップが広く、Local LLM推論の実行可否・速度・安定性が端末ごとに大きく異なる。

- 目安として **RAM 6GB未満の端末はLocal AI対象外**とし、Basic Engineのみで完結させる。
- §18のとおり、端末性能に応じて配布するモデル（サイズ・量子化レベル）を出し分けられる構造を持つ。
- iOS以上に「Local AIが使えない、または不安定な端末が一定割合存在する」ことが前提となるため、§19「AI OFF時でも動作すること」の原則はAndroidにおいてより重要度が高い。Local AIはあくまでEnhancementであり、対象外・非対応端末でもBasic Engineでアプリ全体が成立することを、開発の全フェーズで検証すること。

## 4. 権限一覧表

以下の権限は、いずれも該当機能を初めて利用するタイミングで要求し、アプリ起動時に一括要求しない。拒否された場合も、対応する機能がBasic Engineの範囲でフォールバックし、アプリ全体が停止しないことを必須とする（詳細は6.のエラー＆レスキューマップ参照）。

| 権限 | 用途 | 取得タイミング | 拒否時のフォールバック挙動 |
|---|---|---|---|
| READ_CALENDAR | カレンダー予定の読み取り（Event Selection、§66） | Event Selection機能の初回利用時 | 自動取得不可を表示し、手動でのイベント情報入力（title・時刻・場所）にフォールバック。Settingsから再許可すると自動連携に復帰する。 |
| ACCESS_FINE_LOCATION（バックグラウンド位置は不要） | 現在地取得によるRoute/ETA計算（§67）。予定の前後だけ取得し常時監視はしない（§58） | Departure Mode / Reality Check機能の初回利用時 | 現在地起点の自動ETA計算を無効化し、出発地の手動選択またはTravel Timeの手動入力にフォールバック。 |
| POST_NOTIFICATIONS | Transition開始・Departure・Recovery通知の送信（§62） | 通知が必要になる最初のExecution Plan確定時 | 通知を送らず、アプリ内表示（Execution画面のNOWカード等）のみで状態を伝達する設計にフォールバック。 |
| SCHEDULE_EXACT_ALARM（API 31+） | 時刻厳密な通知のためのexact alarm設定（本節1参照） | Plan Review承認によるExecution Plan確定時 | inexact alarmにフォールバックし、通知が数分単位でずれる可能性をユーザーに明示。電池最適化除外設定への導線を提示。 |
| RECEIVE_BOOT_COMPLETED | 再起動後のexact alarm再登録（本節1参照） | インストール時に自動付与（normal permission、ユーザー操作不要） | 拒否は発生しない（ただしOEM独自の自動起動制限がある端末では再登録が遅延・失敗しうるため、次回アプリ起動時にも再登録処理を走らせる） |
| FOREGROUND_SERVICE（+ 用途別type、例：FOREGROUND_SERVICE_LOCATION等） | Execution Mode中の継続的な状態保持・通知保証。location typeのForeground Serviceはアプリがフォアグラウンド中に開始した場合のみ位置情報アクセスを継続できる（本節1の位置情報制約参照） | Execution Mode開始時 | Foreground Service起動失敗時はbest-effortのバックグラウンド通知に切替え、精度低下をユーザーに明示。 |
| INTERNET | Routes API呼び出し・モデルダウンロード・（任意の）Telemetry送信 | アプリ起動時（常時） | オフライン時はキャッシュ済みRoute推定値または手動Travel Time入力にフォールバック。 |

## 5. Play配布・審査

Google Playでは、新規の個人開発者アカウントは、製品版（Production）公開前に**クローズドテストで12人以上のテスターが14日間連続して参加した実績**を作る必要がある（Google Playの新規デベロッパー向け要件）。

これは§77 Phase 13で計画している「15人程度・実予定を使ったGoogle Play クローズドテスト」の規模と整合する。Phase 13のクローズドテストは、テスター人数（12人以上）と参加期間（14日間）がこの審査要件を満たす形で計画し、リリース前に充足していることを確認すること。

## 6. エラー＆レスキューマップ（表形式）

以下は、Android固有のリスクに関するエラー＆レスキューマップである。ハンドリング方法が空欄のセル（サイレント障害）は存在しない。

| 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|
| カレンダー読み込み | READ_CALENDAR権限が拒否される、またはOS設定で後から無効化される | Event Selection画面で権限拒否状態を検知し、手動でのイベント情報入力（title・開始時刻・場所）フォームへフォールバック。Settings誘導ボタンを表示し、再許可時に自動でカレンダー連携へ復帰する。 | 自動イベント取得はできないが、手動入力によりBasic Engineの全機能（Transition〜Recovery）は継続利用できる。 |
| 現在地取得 | ACCESS_FINE_LOCATIONが拒否される | 自動Route計算・Reality Checkを無効化し、出発地の手動選択またはTravel Timeの手動入力を促す。Departure Mode / Recovery Modeは位置情報なしでも成立するようフォールバックする。 | 自動ETA・Reality Checkの精度は下がるが、手動入力でExecution自体は継続できる。位置情報なしのUXであることを画面上で明示する。 |
| 通知送信 | POST_NOTIFICATIONSが拒否される（Android 13+） | 通知送信をスキップし、アプリ内フォアグラウンド表示（Execution画面のNOWカード等）のみで状態を伝達する設計に切替える。設定導線を提示し、後から許可された場合は自動的に通知を再開する。 | アプリを開いていないと次のアクション通知が届かない。この制約をオンボーディングで明示する。 |
| 通知スケジューリング（exact alarm） | SCHEDULE_EXACT_ALARMが許可されない、またはAPI 33+でUSE_EXACT_ALARMの対象カテゴリと認められない | inexact alarm（`AlarmManager.set`等）へ自動フォールバックし、通知が数分単位でずれうる旨を明示する。電池最適化除外設定への導線をあわせて提示する。 | Transition開始・Departure・Recovery通知のタイミング精度が低下する可能性がある旨を事前に警告表示する。 |
| 経路取得（Routes API呼び出し・オフライン） | ネットワーク断・タイムアウト・クォータ超過等で失敗する | retry 1回 → 失敗時は直近の成功したRoute Estimateまたはユーザー入力の目安時間へフォールバック。Basic Engineの決定的計算（Transition/Preparation/Buffer）は経路取得失敗時も独立して動作を継続する。 | ETA精度が低下する旨を画面上に明示する。Execution自体は停止しない。 |
| Local AIモデルのダウンロード | 回線不良・容量不足等でダウンロードが中断・失敗する | retry導線を提示しつつ、ダウンロード完了までBasic Engineで正常動作を継続する（§19原則）。失敗をログに記録し、「Private AIは現在利用できません」等をユーザーに明示する。 | Local AIの追加提案・パーソナライズは受けられないが、予定成立支援というアプリの基本機能は損なわれない。 |
| Local LLM推論・Structured Output検証 | 推論エラー・タイムアウト、またはJSON出力がSchema Validationを通過しない | retry 1回 → 失敗時はBasic Engineへ安全にフォールバックする（§20の方針を継承）。失敗はAnalyticsへ記録し、サイレントに握り潰さない。 | AI由来の個別提案は受けられないが、Basic Engineの決定的なExecution Planにより予定成立は継続できる。 |
| Doze/電池最適化下での通知遅延 | App Standby・Doze・メーカー独自の電池最適化により、exact alarmや通知そのものが遅延・抑制される | Execution Mode中はForeground Service化してプロセス・通知の優先度を確保する。初回のLocal AI/通知有効化時に電池最適化除外を案内するオンボーディングUIを表示し、主要OEM（Pixel / Samsung / Xiaomi等）実機での遅延実測をリリース前QAに組み込む。 | 対策を講じても一部端末では数十秒〜数分の遅延が残る可能性があることをリスクとして明示し、過信させない。 |
| 端末再起動・時刻/タイムゾーン変更 | AlarmManager登録が全消去され、通知が一切発火しなくなる | BOOT_COMPLETED/TIME_CHANGED/TIMEZONE_CHANGED受信でRoomの未完了PlanからExact alarmを再スケジュール。加えてアプリ起動時にも整合性チェックと再登録を行う（Receiver不発時の保険） | 対策により通知は復元される。再起動直後の数分間は通知が遅延する可能性がある旨を明示 |
| バックグラウンドでの位置取得（While-in-use制約） | アラーム起点の処理から位置情報が取得できない（SecurityException / null） | バックグラウンドでは位置取得を試みず通知提示のみに限定。位置を使う再計算はフォアグラウンド復帰時またはフォアグラウンド開始済みExecution FGS内で実行（本節1参照） | 通知タップでアプリを開くまでETA再計算が保留される旨をUX設計に織り込む |
| Local AIモデルのダウンロード（空き容量） | ストレージ空き容量不足によりダウンロードが途中失敗、または端末全体の動作を圧迫 | ダウンロード開始前に`StatFs`でアプリ専用ストレージの空き容量を検証（目安: モデルサイズ×1.5倍以上）。不足時はダウンロードを開始せず、必要容量と現在の空き容量を明示した警告を表示 | 容量確保まではLocal AIを利用できないが、Basic Engineで全機能が継続する（§19原則） |
