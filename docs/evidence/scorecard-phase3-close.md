# /goal スコアカード — Phase 3完全クローズ時点

- 採点日: 2026-08-09
- 採点者: Fable 5（docs/GOAL.md基準）
- **総点: 65.75 / 100（リリース基準90に未達・継続）**

## カテゴリ別

| カテゴリ | 得点 | 根拠・証拠 |
|---|---|---|
| A. ビルド・静的品質 | 9 / 10 | `:app:build` BUILD SUCCESSFUL（p3c7-build.log）・lint error 0/warning 13（p3c7-lint.log）・`!!`濫用なし。減点1: lintはP3-C7時点の実測でC8fix〜C10の差分に対する再実測が未実施 |
| B. テスト | 17 / 25 | 実装済み: JVM 247件（246 pass・想定skip1、p3c10-green.log）＋instrumented E2E群Green（各Phase G4-E記録）。未実装: Phase 5計画53件・Phase 6計画69件（G1承認済みのため分母算入）。実行時テスト数ベースの近似按分 ≈259/(≈259+122+実行不能1)=0.68 → 25×0.68≈17。計画書ケース数ベースの厳密再計算はPhase 5/6実装後の再採点で実施 |
| C. 機能完成度 | 18.75 / 25 | §78第1弾14項目中、達成10（実Calendar取得・場所認識・Route取得※・Transition・Preparation・Departure・Arrival Buffer・Basic Engine・AI OFF成立・ja/en）＋部分0.5（One Action UI: 画面は存在するが多段階遷移が本番未結線=F58、Phase 5で結線）＋未達3（Notification=Phase 5・Recovery=Phase 6・行動ログ）。25×10.5/14=18.75。※Route取得はP3-C9/C10の実欠陥修正により実APIで初めて完全成立 |
| D. エミュレータE2E実測 | 11 / 20 | (1)主要UX一気通貫=PASS（Phase 4 G4-E・BasicPlanE2ETest、リリース必須条件クリア） (2)Recoveryシナリオ=未実装（Phase 6） (3)権限拒否フォールバック=カレンダー拒否PASS・位置拒否PASS（T-E2E3-2、p3c8fix-e2e-denied-result.xml）・通知拒否未実装（Phase 5）。20×(1+0+2/3)/3≈11 |
| E. i18n/アクセシビリティ | 5 / 10 | ja/en両ロケールのスクリーンショット実測: Phase 1主要画面＋Phase 3 Departure（docs/evidence/screenshots/）。未実施: フォントスケール1.5x・TalkBack/contentDescription網羅（Phase 11） |
| F. 障害系（§95） | 5 / 10 | 達成3/6: カレンダー権限拒否・位置権限拒否・経路APIオフライン縮退（T-E2E3-3）。未達3/6: 通知権限拒否・exact alarm不許可inexactフォールバック・再起動後アラーム再登録（いずれもPhase 5実装対象）。10×3/6=5 |

## 前回予測（78点前後）との差異について

前回予測はPhase 3クローズによる加点のみを見込み、**Phase 5/6計画書のG1承認により未実装テスト122件が分母へ算入される効果**を織り込んでいなかった。採点ルール（GOAL.md「カテゴリ内は達成割合で按分」）の正直な適用の結果であり、実装の後退ではない（JVMテストは239→247件へ純増・実欠陥5件を修正済み）。

## Phase 3クローズ時点の実欠陥修正実績（本スコアの根拠となる品質イベント）

1. INTERNET権限欠落（P3-C8fix・Manifest 6段階検証）
2. 権限チェックがgeocode後にネストされ拒否状態が非表示（P3-C8fix・T-DEPVM-10・実機T-E2E3-2 PASS）
3. ルート0件時の`routes`キー省略応答をMalformedResponse誤分類（P3-C9・ADR-0029）
4. DRIVEがroutingPreference未指定で常時400（P3-C9・TRAFFIC_AWARE付与）
5. departureTime=nowが往復遅延で400（P3-C10・ADR-0030・120秒クランプ）

## 残ギャップ（既知・環境起因）

- T-E2E3-1: AVD位置バックエンド故障により実行不能（R16。playstoreイメージ換装が代替策）
- Google Routes APIは日本のTRANSITデータ非提供（実測確定）: 既定移動手段TRANSITのままでは日本ユーザーは常時NoRoute→手動入力フォールバックUX。既定モードの仕様判断はユーザー確認事項

## 90点への経路（次の再採点はPhase 5クローズ時）

- Phase 5実装: B +約3.5 / C +約2 / D +約3 / F +約3.3
- Phase 6実装: B +約4.5 / C +約2 / D +約4.5
- Phase 11（i18n/a11y仕上げ）: E +約5
- 上記完遂で約92点（A残1点はlint再実測で回収）
