# PRODUCT — Action Starter (Android)

> 本書は `Action_Starter_Master_Specification_v2.0_Android.md`（正仕様書）の要約である。差異がある場合は仕様書が正。v1.0(iOS)はアーカイブ。

## 1. 最重要原則（§0）

> 人は予定を知らないから遅れるのではない。予定を、今やるべき一つの行動に変えられないから遅れる。

本アプリはカレンダー・Todo・習慣化・遅刻防止・AIスケジューラー・地図のいずれでもなく、**Plan → Execution の間の空白**を担当する。全機能判断はこの原則に照らして行う。

## 2. 解決する問題（§3）

予定（例: 10:00 Shibuya Product shoot）は「いつ・どこで・何」しか示さない。現実には「今の作業をやめる→準備→着替え→荷物準備→家を出る→移動→到着」という行動連鎖が必要だが、地図アプリのTravel Time表示はこの前半（Transition/Preparation）を管理しない。この空白を埋めるのが本プロダクト。

## 3. プロダクトの定義（§1）

内部定義: **Execution Assistant**（Action Layer for Calendar）。

| 既存サービス | 役割 |
|---|---|
| Calendar | いつ・どこで何があるか |
| Maps | そこまで何分かかるか |
| Task Manager | 何をする必要があるか |
| AI Scheduler | 何時にタスクを配置するか |
| **Action Starter** | **今の状態から、予定を成立させるために、今何をすればいいか** |

## 4. ブランド／ユーザー向けメッセージ（§2）

**注意**: 以下は候補コピーであり、**MVP完成後に別途検証するため確定していない**（§2）。確定コピーとして扱わないこと。

- 日本語第一候補: 「予定は入れた。あとは動くだけ。」／サブコピー「遅れそうになる前に、次の一手を。」
- 英語候補: “Plans are set. Now move.” ／ “Turn plans into action.”
- プロダクト思想: 「そろそろ」ではなく「今やる」を。

## 5. コア時間モデル（§4）

Transition Time → Preparation Time → Travel Time → Arrival Buffer → Event Start を分離して扱う。計算式・詳細は`ARCHITECTURE.md`§5を参照。

## 6. MVP対象ユーザー（§5）

行動ベースで定義: **予定はちゃんと管理しているのに、準備・切替・出発で繰り返し崩れる人**。初期テスター条件は、週2回以上の場所・時刻確定予定、Google Calendar/Outlook等＋Google Maps等の利用、過去1か月の遅刻・忘れ物・出発前の焦り・出発予定超過・30分以上の早着のいずれかの経験。

## 7. MVPユーザーフロー（§24-35）

1. **Event Selection**（§24）: 次の予定を提示し「Prepare this event」で開始。
2. **Planning**（§25）: カレンダー由来のtitle/startDate/location/notesからBasic/Local AIがExecution Planを生成。
3. **Plan Review**（§26）: 生成されたPlanは必ずユーザー承認を経る。AIが勝手に確定しない。
4. **Execution Mode**（§27）: **ONE ACTION ONLY**。画面中央に「今やること」を一つだけ表示。
5. **Execution UI原則**（§28）: 長大なチェックリストをメイン画面に出さない。認知負荷を下げる。
6. **Departure Mode**（§29）: 最新の現在地・経路情報からETAを再計算して提示。
7. **Reality Check**（§30）: 現在時刻・現在地・完了/未完了ステップ・移動時間・予定開始時刻から、計画と実態のズレを継続的に評価。
8. **Recovery Mode**（§31）: 最大の独自価値。遅れ検知時に「予定成立」のための更新Planを提示する。
9. **Recovery Option**（§32）: 提示は最大3案。選択肢過多にしない。
10. **Recoveryの優先原則**（§33）: 「完璧な準備」より「予定成立」を優先するが、required/important/optionalを区別し安全・必須物は勝手に省略しない。
11. **ユーザー最終決定**（§34）: ステップ省略・移動手段変更・予定変更・対外連絡・予約・支払い・他者への送信は必ずユーザー確認を経る。AIは提案のみ。
12. **最初の5画面**（§35）: Next Event／Plan／Now／Leave／Recovery。詳細レイアウトは仕様§35を参照。

## 8. Basic Mode / Local AI Mode（§37・§38）

| モード | 提供機能 |
|---|---|
| Basic Mode（§37） | Event読み込み・Travel Time・手動Preparation/Transition・Arrival Buffer・Countdown・Execution・Departure・deterministic recovery |
| Local AI Mode（§38） | 上記に加え、event理解・分類・Action生成・Transition/Preparation提案・優先順位推論・省略可能性判定・Recovery推論・パーソナライズ・自然言語説明 |

両モードはDeveloper Settingsで切替可能（§36）。

## 9. KPI（§55-57）

**MVP KPI**（§55）: Preparation Start Rate（提示後5分以内開始）／Action Response Time／Departure Delay／Arrival Buffer／Recovery Acceptance Rate／Plan Modification Rate／**Reuse Rate**（次の実予定でも自分から開始したか、特に重要）。

**Basic vs AI KPI**（§56）: 上記主要指標をBasic/AIモード間で比較し、Local AIの価値を判定する。

**Local AI性能指標**（§57）: Plan/Recovery生成latency、JSON/Schema validity、Plan acceptance/correction、Hallucination report、RAM/Battery/Thermal、モデルダウンロードサイズ。

## 10. MVP完成条件・失敗条件・成功シグナル（§78-80）

**MVP完成条件**（§78、全20項目）: 実Calendar予定取得／場所認識／Route取得／Transition計算／Preparation／Departure／Arrival Buffer／One Action UI／Notification／Recovery／Basic Engine／Local AI Engine／AI OFFでも成立／完全オフラインLocal AI推論／Structured Output／Personal History／ja-en Localization／行動ログ／Google Play内部・クローズドテスト配布／実予定検証。

**失敗条件**（§79）: AI提案がほぼ全員に大幅修正される／通知が行動開始につながらない／Recoveryが役立たない／Local AIあり無しで差がない／位置情報許可率が低い／バッテリー負担が許容不可／Local AI latencyがUXを阻害／再利用されない。いずれかに該当した場合、機能追加ではなく再検討する。

**成功シグナル**（§80）: 「次の予定でも自発的に使う」を最重視。加えて「Local AIをOFFにしたくない」が出ればAI Pro化の有力シグナル。

## 11. MVPに入れない機能（§61）

明示的に禁止: 写真証明／写真AI／NFC／QR／金銭ペナルティ／ストリーク／SNS／ランキング／友達機能／習慣化／汎用Todo／Project Management／AI Chat／Health diagnosis／Sleep diagnosis／自動メール送信／自動SMS／自動予定変更／自動予約／スマートホーム連携。

判断基準（Developer UX Principle、§88）: **その機能は、予定を今やる一つの行動に変えることに直接寄与するか？** Noなら追加しない。生成AIは機能を勝手に追加しない。

## 12. Global-first設計・国際化要件（§6・§7）

初期検証市場は日本だが、コード・データモデル・AI設計は最初から海外展開可能にする（§6）。日本固有の生活前提（例:「撮影→電車→渋谷」）をコードへ埋め込むことを禁止し、eventType/transportMode/locale/timezone等の抽象データとして扱う。最低対応ロケールはja-JP/en-US（§7）。UI文字列の直接ハードコードを禁止し、string resources経由で参照する（`values-ja/`等。既定`values/`はADR-0009によりen）。

## 13. Local AI有料化仮説（§40・§41・§87）

Free（Calendar Integration・Maps・手動Transition/Preparation・Basic Execution/Departure・deterministic rules）とPro（Private Local AI・Event Understanding・Automatic Action Plan・Personalized Execution・Smart Recovery・Personal Execution Profile・Offline intelligence）を想定するが、**MVP時点で課金実装は必須ではない**（§40）。有料化メッセージは「AI機能が使えます」ではなく「Private AI that understands how you actually move.」等、価値ベースで訴求する（§41）。**プライシングは本仕様で固定しない**（§87）。検証する問いは「Local AI無しのFree版で十分か」「Local AIに月額を払うか」。
