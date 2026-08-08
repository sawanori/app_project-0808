# PRIVACY — Action Starter (Android)

> 本書は `Action_Starter_Master_Specification_v2.0_Android.md`（正仕様書）の要約である。差異がある場合は仕様書が正。v1.0(iOS)はアーカイブ。

## 1. Privacy方針の基本（§58）

デフォルトで必要最小限のデータのみを扱う。位置情報の常時監視は前提にせず、可能な限り「予定の前後だけ」取得する。

## 2. Local Data（§59）

Execution History／Personal Profile／AI Preferences／Plan Historyは原則**端末保存**とする。MVPではクラウド同期を必須にしない。

## 3. 位置情報制約：ACCESS_BACKGROUND_LOCATIONを要求しない設計（§58・§95.1）

「予定の前後だけ取得する」という方針により、**`ACCESS_BACKGROUND_LOCATION` を要求しない設計**とする（§58）。これによりPlay審査リスクを低減する。

Android 11以降のWhile-in-use制約により、アプリがバックグラウンド状態でアラーム等から起動した処理・Foreground Serviceでは位置情報を取得できない（§95.1）。したがって、アラーム発火時のバックグラウンド処理は位置取得を前提とせず通知の提示のみを行い、位置情報を用いたETA再計算・Reality Checkは (a) ユーザーがフォアグラウンドに復帰した時点、または (b) フォアグラウンドで開始したExecution Mode中のForeground Service継続中、のいずれかでのみ実行する。この設計により、サイレントな位置取得失敗（SecurityException／null位置）を構造的に回避する。

## 4. Local-first AIとプライバシー（§10）

カレンダー・イベント名・訪問先・現在地・自宅付近・勤務先・行動履歴・出発履歴・移動傾向・準備時間・訪問頻度は非常にプライベートな情報である。Local AIモードでは**Calendar / Location / Behavioral Historyを外部LLMへ送らない**（詳細は`AI.md`§1）。

## 5. Routes APIへの送信範囲（§95.2）

Google Maps Platform Routes APIへは、リクエストごとに**出発地・目的地の座標と移動手段のみ**を送信する。カレンダー本文・イベントタイトル・訪問先名等は送信しない。Reality Check・Departure Mode等の再計算はRoutes APIをポーリングせず、スロットリングとキャッシュを義務とする（移動距離・経過時間が閾値未満の場合はキャッシュ済みETAを使用する等）。

## 6. Telemetry：許可リストベースの送信方針（§60）

検証用Analyticsを導入する場合も、カレンダー本文・住所等を不用意に送信しない。

- 送ってよい例: `event_category_hash` / `plan_generation_ms` / `step_count` / `delay_seconds` / `AI_enabled`
- 原文イベントタイトル等は送らない方針を優先する。

## 7. 権限一覧表（§95.4）

以下は該当機能を初めて利用するタイミングで要求し、アプリ起動時に一括要求しない。拒否時もBasic Engineの範囲でフォールバックし、アプリ全体は停止しない。

| 権限 | 用途 | 取得タイミング | 拒否時のフォールバック |
|---|---|---|---|
| READ_CALENDAR | カレンダー予定の読み取り（§66） | Event Selection初回利用時 | 手動でのイベント情報入力（title・時刻・場所）にフォールバック |
| ACCESS_FINE_LOCATION（バックグラウンド位置は不要） | 現在地取得によるRoute/ETA計算（§67）。常時監視はしない | Departure Mode / Reality Check初回利用時 | 出発地の手動選択またはTravel Timeの手動入力にフォールバック |
| POST_NOTIFICATIONS | Transition開始・Departure・Recovery通知（§62） | 通知が必要になる最初のPlan確定時 | アプリ内表示（NOWカード等）のみで状態を伝達 |
| SCHEDULE_EXACT_ALARM（API 31+） | 時刻厳密な通知のためのexact alarm | Plan Review承認によるPlan確定時 | inexact alarmへフォールバックし精度低下を明示 |
| RECEIVE_BOOT_COMPLETED | 再起動後のexact alarm再登録 | インストール時に自動付与（normal permission） | 拒否は発生しない（OEM独自の自動起動制限には次回起動時の再登録処理で対応） |
| FOREGROUND_SERVICE（+用途別type） | Execution Mode中の状態保持・通知保証 | Execution Mode開始時 | best-effortのバックグラウンド通知に切替え、精度低下を明示 |
| INTERNET | Routes API呼び出し・モデルダウンロード・任意のTelemetry送信 | アプリ起動時（常時） | オフライン時はキャッシュ済みRoute推定値または手動入力にフォールバック |

## 8. ユーザー最終決定：対外操作はユーザー確認を経る（§34）

AIは提案のみ行う。ステップ省略／移動手段変更／予定変更／対外連絡／予約／支払い／他者への送信は、いずれも**ユーザー確認必須**とし、AIが勝手に実行しない。

## 9. Play配布審査とプライバシー（§95.5）

Google Playでは新規の個人開発者アカウントは、製品版公開前にクローズドテストで12人以上のテスターが14日間連続参加した実績が必要である。Phase 13（§77）のクローズドテスト計画（15人程度・実予定使用）はこの要件を満たす形で実施し、リリース前に充足を確認する。配布・審査時は上記権限一覧表に基づくデータ収集の開示（Google Play Data Safety等）が必要になる点に留意する。
