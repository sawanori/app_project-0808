# Action Starter
## プロダクト仕様書・実装計画書・開発依頼書・AI引継ぎ書
### Master Specification v1.0

**開発対象**：iOS Native Application  
**初期市場**：日本  
**将来市場**：Global / 英語圏を含む海外  
**設計思想**：Global-first / Local-first / Privacy-first  
**MVP目的**：製品完成ではなく、実ユーザー環境でプロダクト仮説とLocal AI価値を検証すること

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
- Apple Calendar / Google Calendar / Outlook等を利用
- Google Maps / Apple Maps等を利用
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

SwiftUIではString Catalog等によるLocalizationを前提とする。

---

# 8. 時刻・地域対応

絶対に、

```text
Japan Time
24時間表記
YYYY/MM/DD
```

を前提にしない。

内部時刻：

```text
Date
TimeZone
Locale
Calendar
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

```swift
enum TransportMode {
    case walking
    case driving
    case transit
    case cycling
}
```

初期MVPではMapKit対応範囲を優先。

将来的にRouting Provider自体を切替可能にする。

```swift
protocol RoutingService {
    func route(
        from: Coordinate,
        to: Coordinate,
        mode: TransportMode
    ) async throws -> RouteEstimate
}
```

これにより国別の地図・経路提供元変更にも対応できる。

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

> **あなたの予定を理解するAIは、あなたのiPhoneの中にいます。**

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

決定的処理はSwift側。

---

# 16. Local LLM Runtime

実装はModel Adapter方式。

```swift
protocol LocalLanguageModel {
    var modelIdentifier: String { get }

    func generatePlan(
        context: PlanningContext
    ) async throws -> AIPlanResponse

    func generateRecovery(
        context: RecoveryContext
    ) async throws -> AIRecoveryResponse
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
iOS

Language:
Swift

UI:
SwiftUI

Calendar:
EventKit

Location:
Core Location

Routing:
MapKit

Notifications:
UserNotifications

Persistence:
SwiftData

Local AI:
Pluggable Runtime / Adapter Architecture

Testing:
XCTest / Swift Testing

Distribution:
TestFlight
```

---

# 43. アーキテクチャ

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

# 44. PlanningEngine Protocol

```swift
protocol PlanningEngine {
    func createPlan(
        context: PlanningContext
    ) async throws -> ExecutionPlan
}
```

---

# 45. RecoveryEngine Protocol

```swift
protocol RecoveryEngine {
    func createRecoveryPlan(
        context: RecoveryContext
    ) async throws -> RecoveryPlan
}
```

---

# 46. Routing abstraction

```swift
protocol RoutingService {
    func estimateRoute(
        from origin: Coordinate,
        to destination: Coordinate,
        mode: TransportMode,
        departureDate: Date
    ) async throws -> RouteEstimate
}
```

---

# 47. Core Domain — Event

```swift
struct ExecutionEvent: Identifiable {
    let id: UUID
    let externalCalendarID: String?

    let title: String
    let notes: String?

    let startDate: Date

    let locationName: String?
    let coordinates: Coordinate?

    let sourceCalendar: CalendarSource
}
```

---

# 48. Step Model

```swift
enum ExecutionStepType: String, Codable {
    case transition
    case preparation
    case departure
    case travel
}
```

```swift
enum StepPriority: String, Codable {
    case required
    case important
    case optional
}
```

```swift
struct ExecutionStep: Identifiable, Codable {
    let id: UUID

    let semanticID: String
    let type: ExecutionStepType

    var title: String

    var estimatedDuration: TimeInterval

    var priority: StepPriority

    var skippable: Bool

    var scheduledStart: Date?

    var completedAt: Date?
}
```

---

# 49. Execution Plan

```swift
struct ExecutionPlan {
    let event: ExecutionEvent

    var steps: [ExecutionStep]

    var transitionStart: Date
    var departureTime: Date

    var estimatedArrival: Date

    var arrivalBuffer: TimeInterval
}
```

---

# 50. Recovery Context

```swift
struct RecoveryContext {
    let currentTime: Date
    let currentLocation: Coordinate?

    let event: ExecutionEvent

    let unfinishedSteps: [ExecutionStep]

    let latestTravelEstimate: TimeInterval

    let plannedDepartureTime: Date
}
```

---

# 51. Recovery Option

```swift
struct RecoveryOption: Identifiable {
    let id: UUID

    let semanticAction: String

    let title: String
    let explanation: String

    let estimatedArrival: Date?

    let skippedStepIDs: [UUID]
}
```

---

# 52. Personal Execution Profile

```swift
struct PersonalExecutionProfile {
    var eventCategory: String

    var averageTransitionDuration: TimeInterval
    var averagePreparationDuration: TimeInterval

    var averageResponseDelay: TimeInterval
    var averageDepartureDelay: TimeInterval

    var preferredArrivalBuffer: TimeInterval
}
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

---

# 63. Accessibility

将来の海外展開を考え、

- Dynamic Type
- VoiceOver
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

- SwiftUI
- Domain Models
- Event Selection Mock
- Plan Review
- Execution Screen
- Recovery Screen

この時点ではデータMock可。

完成条件：

> Simulator上で一連のUXが動く。

---

# 66. Phase 2

**Calendar**

EventKit。

実装：

- Permission
- Calendar List
- Upcoming Events
- Location付きイベント抽出
- Event Selection

完成条件：

> 実カレンダーから予定を選択可能。

---

# 67. Phase 3

**Routing / Location**

CoreLocation + MapKit。

- Permission
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

- UserNotifications
- Step start
- Done
- Snooze
- next action
- departure

完成条件：

> 実際にiPhone上で時間経過型Executionができる。

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

数値はSwift側が計算しLLMに渡す。

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

**TestFlight**

15人程度。

ただし「意見収集」ではなく、

> **実予定を使う。**

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
19. TestFlight配布
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

英語圏TestFlightを開始。

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

iPhone以外も視野。

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

- Compilable
- Testable
- Modular
- No giant View
- No giant ViewModel
- No hard-coded secrets
- No duplicated domain logic
- no force unwrap unless justified
- Error handling
- Permission failure handling
- Model failure fallback
- Offline behavior

---

# 90. 開発AIへの引継ぎ指示

ここから下は**そのまま別AIへ渡せます。**

> あなたは「Action Starter」プロジェクトを引き継ぐシニアiOSエンジニア兼AIプロダクトエンジニアです。
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
> AIは時刻計算、GPS計算、移動時間計算などの決定的ロジックを担当してはいけません。これらはSwiftの通常コードで処理してください。
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
> PlanningEngineおよびRecoveryEngineをProtocolで抽象化し、
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
> 各Phase終了時に、必ずビルド可能・実機またはSimulatorで確認可能な状態にしてください。
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
> 7. iOS権限設計  
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
> ・SwiftUI Project Structure  
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
> EventKit、GPS、MapKit、LLMはまだ接続しないでください。
>
> Mock Dataのみで、
>
> Event Selection → Plan Review → Execution → Departure → Recovery
>
> の主要UXをSimulator上で確認できる状態にしてください。
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
> LocalLanguageModel ProtocolとModel Adapterを介して実装してください。
>
> 最初に、
>
> ・候補Runtime  
> ・対応モデル形式  
> ・iOS実機対応  
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
