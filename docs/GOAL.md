# /goal — リリース判定基準（第1弾: Basic Engine完結アプリ）

- **設定日**: 2026-08-08（ユーザー指示によりFable 5が設定）
- **目標スコア**: **90/100点以上でリリース可**（Google Play内部テスト配布レベル）
- **採点者**: Fable 5（オーケストレーター）。全採点は実測証拠に接地させる。

## スコープ

第1弾の対象は仕様書v2.0のPhase 0〜6 + Phase 11（Basic Engine完結・仕様§40のFree版相当）。

Local AI（Phase 7〜9）・Personal Profile（Phase 10）・Basic/AI実験（Phase 12）・実配布（Phase 13）は/goal第2弾以降とする。

根拠: 仕様§19「AI OFF時でも動作すること」により、Basic Engineのみでアプリは完全に成立する。

## 採点表

| カテゴリ | 配点 | 判定内容 | 証拠 |
|---|---|---|---|
| A. ビルド・静的品質 | 10 | `./gradlew build`成功、lintエラー0（warning許容）、`!!`濫用なし | quality-runnerビルドログ・lintレポート |
| B. テスト | 25 | 承認済み計画書の全テストケースがコード化され全Green（JVM unit / Robolectric / instrumented） | テスト実行ログ（全suite） |
| C. 機能完成度 | 25 | 仕様§78のうち第1弾スコープ該当項目: 1.実Calendar予定取得 2.場所認識 3.Route取得 4.Transition計算 5.Preparation 6.Departure 7.Arrival Buffer 8.One Action UI 9.Notification 10.Recovery 11.Basic Engine 13.AI OFF成立(Basicのみで成立) 17.ja/en 18.行動ログ | 機能ごとのテスト＋エミュレータ実測 |
| D. エミュレータE2E実測 | 20 | (1)主要UX一気通貫: Event Selection→Plan Review→Execution→Departure (2)Recoveryシナリオ（遅延シミュレート→Recovery画面→案選択） (3)権限拒否時フォールバック（カレンダー/位置/通知の各拒否で機能縮退しつつ動作継続） | E2E実行ログ＋スクリーンショット（Fable 5が確認） |
| E. i18n / アクセシビリティ | 10 | ja/en全画面切替で文字列欠落なし・レイアウト破綻なし。主要UI要素にcontentDescription、フォントスケール1.5倍で崩れなし | 両ロケールのスクリーンショット比較・a11yチェック結果 |
| F. 障害系（仕様§95エラーマップ） | 10 | 第1弾該当行の実装＋テスト: カレンダー/位置/通知権限拒否、exact alarm不許可時のinexactフォールバック、経路APIオフライン時フォールバック、再起動後のアラーム再登録 | 各異常系テストログ＋該当エミュレータ実測 |

## 採点ルール

- カテゴリ内は達成割合で按分する（例: C項目14個中7個達成 = 12.5点）。
- ただしD(1)主要UX一気通貫が不通過の場合、総点に関わらずリリース不可とする。
- 各カテゴリの採点には証拠（ログパス・スクリーンショットパス）を必ず添付する。

## 改善ループ（/loop）

1. 実装完了 → 全テスト実行＋エミュレータE2E（quality-runner）
2. Fable 5が採点し、点数と根拠を記録（`docs/evidence/` 配下にスコアカードを残す）
3. 90点未満: 不足カテゴリの改善点を洗い出し（android-planner）→ TDDサイクルで改修 → 再テスト → 再採点
4. 90点到達まで反復。2周連続で+5点未満なら停滞としてユーザーへ報告
5. 90点到達 → リリース可判定をユーザーへ報告（実際の配布操作はユーザー確認必須）
