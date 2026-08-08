# AI — Action Starter (Android)

> 本書は `Action_Starter_Master_Specification_v2.0_Android.md`（正仕様書）の要約である。差異がある場合は仕様書が正。v1.0(iOS)はアーカイブ。

## 最優先原則：LLMに禁止すること（§15）

他のどの節よりも優先して遵守する。LLM自身に以下を決めさせてはならない。

GPS位置／正確な移動時間／時刻演算／到着時刻演算／カレンダー変更／通知発火／メール送信／SMS送信／予約変更／勝手なキャンセル／決済／タクシー予約／安全上重要な最終判断。

**決定的処理は常にKotlin側**（§15）。これらをLLM出力に基づき直接実行する設計・実装は即座に差し戻す。

## 1. Local-first AI（§10・§11）

カレンダー・イベント名・訪問先・現在地・自宅付近・勤務先・行動履歴・出発履歴・移動傾向・準備時間・訪問頻度は非常にプライベートな情報である。そのためLocal AIモードでは**Calendar / Location / Behavioral Historyを外部LLMへ送らない**（§10）。

ユーザー価値は「ローカルLLM搭載」という技術訴求ではなく、体験価値として訴求する（§11）: “Your AI lives on your phone. Your calendar stays private.”（日本語:「あなたの予定を理解するAIは、あなたのAndroid端末の中にいます。」）。

## 2. Basic EngineとLocal AI Engineの両方実装（§12-14）

MVP最大の技術検証ポイントとして、同一アプリ内にBasic Engine（LLMなし）とLocal AI Engine（端末内LLM）を両方実装する（§12）。目的は「LLMを載せることで本当にプロダクト価値が増えるか」の実測。

LLMの担当は **Meaning → Action** のみ（§14）: 予定文脈理解／eventType推定／Preparation・Transition Action生成／優先順位／必須・任意判定／省略可能性／Recovery候補生成／個人履歴を考慮した提案／自然言語説明。日時・移動時間・Arrival Buffer等の数値計算はBasic Engine（通常コード）が担当する（§13）。

## 3. Local LLM Runtime（§16）

Model Adapter方式で実装し、特定モデル依存コードをUIやDomain層へ入れない。

```kotlin
interface LocalLanguageModel {
    val modelIdentifier: String
    suspend fun generatePlan(context: PlanningContext): AIPlanResponse
    suspend fun generateRecovery(context: RecoveryContext): AIRecoveryResponse
}
```

モデルは技術検証で交換可能にする（§16）。

## 4. モデル選定方針（§17）

モデル名を製品仕様として固定しない。最低評価項目: 日本語／英語／多言語理解／Structured Output成功率／予定分類精度／Preparation生成精度／Recovery精度／hallucination率／初回Token latency／total latency／RAM／model size／battery／thermal throttling／supported devices／**Androidデバイス断片化（RAM・チップセット差）への対応**／commercial license。Global-firstのため日本語だけで選定しない。

## 5. モデル配布（§18・§95.6）

アプリ本体への巨大モデル直接同梱は必須としない。推奨フローは「アプリインストール→Local AI有効化→Model Download」（§18）。配布手段としてPlay Asset Delivery / Play Feature Deliveryも選択肢だが必須ではない。

**ダウンロード開始前にストレージ空き容量の事前検証を必須とする**（目安: モデルサイズ×1.5倍以上の空き容量。`StatFs`で検証し、不足時はダウンロードを開始せず必要容量と現在の空き容量を明示した警告を表示する。§95.6）。

## 6. AI OFF時でも動作すること（§19）

モデルダウンロード失敗・低スペック端末・Local AI OFFのいずれでも**アプリ自体は正常に成立する**必要がある。Local AIはEnhancementであり、アプリ全体のSingle Point of Failureにしない。

## 7. Structured Output（§20）

LLMの自由文をDomain Logicへ直接使用することを禁止する。JSON出力はSchema Validation必須。Validation失敗時は retry 1回 → なお失敗 → **Basic Engineへフォールバック**。

## 8. AI Promptの言語非依存化（§21）

eventType／actionType／priority／skippable／durationなど内部意味は英語IDで扱い、LLMのUI表示文（`display_text`）とDomain Meaning（`action_type`）を分離する。表示文はロケールに応じて出し分ける。

## 9. Personal Execution Profile（§22・§23・§52）

ユーザーごとに端末内保存する長期的な競争力の中心（§22）。モデル自体を毎回Fine-tuningせず、ユーザー履歴をLocal Contextとして与える。将来的には「一般論として30分」ではなく「あなたなら実際には41分かかります」という個人化された提示へ進化させる（§23、将来的な「Personal Execution Model」）。

Domain定義としてのフィールド（`eventCategory`／`averageTransitionDuration`／`averagePreparationDuration`／`averageResponseDelay`／`averageDepartureDelay`／`preferredArrivalBuffer`）は仕様§52を正とし、Duration型は`ARCHITECTURE.md`のADR-0008方針（`java.time.Duration`）に従う。

## 10. 端末断片化とLocal LLM（§95.3）

Androidは端末・チップセットのラインナップが広く、Local LLM推論の実行可否・速度・安定性が端末ごとに大きく異なる。目安として**RAM 6GB未満の端末はLocal AI対象外**とし、Basic Engineのみで完結させる。端末性能に応じて配布モデル（サイズ・量子化レベル）を出し分ける。iOS以上に「Local AIが使えない/不安定な端末」の存在を前提とするため、§19の原則はAndroidでより重要度が高い。

## 11. 検証したいLocal AI価値とKPI（§39・§56・§57）

単に「AI回答が賢い」では不十分。検証する問い（§39）: 準備開始率が上がるか／Plan修正回数が減るか／AI Recoveryが選択されるか／AI提案の却下率・修正率／AI OFF時より再利用率が上がるか／AIのために課金したいか。Basic/AI比較KPI（§56）とLocal AI性能指標（§57）の一覧は`PRODUCT.md`§9を参照（重複記載を避ける）。

## 12. 現状ステータス

**Phase 1〜6の時点ではLocal AI関連コードはinterface宣言（`LocalLanguageModel`等の契約scaffold）のみであり、実装は行わない。** Local AIの実装は**Phase 7（Local LLM Runtime）以降**に開始する（`docs/TEAMS.md`§5 Phaseマッピング）。Phase 0時点（本書作成時点）ではリポジトリにコードは一切存在しない。
