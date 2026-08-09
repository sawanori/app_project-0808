# /goal 最終スコアカード — 第1弾リリース判定

- 採点日: 2026-08-10
- 採点者: Fable 5（docs/GOAL.md基準・全項目実測証拠に接地）
- **総点: 97.6 / 100 — 目標90点を超過。リリース可（Google Play内部テスト配布レベル）**
- 必須条件D(1)「主要UX一気通貫」: **PASS**（リリース可の前提条件クリア）

## カテゴリ別採点

| カテゴリ | 得点 | 根拠・証拠 |
|---|---|---|
| A. ビルド・静的品質 | 10 / 10 | assembleDebug/Release成功（p11c5-assemble.log）・lint error 0・UnusedResources 0・MissingTranslation 0（p11c5-lint.log, p11c6-lint.log）・`!!`濫用なし |
| B. テスト | 24.9 / 25 | JVM 417件全Green（想定skip1のみ・3回連続実測含む、p11c6-full.log）＋実機E2E 19/19 PASS（final-round*-result.xml群）。唯一の未実行はT-E2E3-1（AVD位置バックエンド故障=R16・環境起因・コード欠陥ではない）。按分25×(436/437)=24.9 |
| C. 機能完成度 | 23.2 / 25 | §78第1弾14項目中13達成（実Calendar・場所認識・Route取得・Transition計算〔P4-C8でTravelTime項含む仕様§13完全式〕・Preparation・Departure・Arrival Buffer・One Action多段階・Notification 3種実発火・Recovery実機2/2・Basic Engine・AI OFF成立・ja/en）。未達1: 「18. 行動ログ」（どのPhaseにも未割当のまま残存）。25×13/14=23.2 |
| D. エミュレータE2E実測 | 20 / 20 | (1)一気通貫PASS（BasicPlanE2ETest・多段階Done） (2)RecoveryシナリオPASS（tP6E2e1/2: 遅延→候補→適用→復帰） (3)権限拒否3種すべて実機PASS: カレンダー（tE2e2_2）・位置（tE2e3_2）・通知（実OSダイアログ拒否→バナー→動作継続、02-notification-denied-banner.png＋logcatクラッシュ0） |
| E. i18n/アクセシビリティ | 9.5 / 10 | ja/en 5画面スクリーンショット欠落なし＋パリティテスト（値一致検出込み）。contentDescription 5画面完備（P11-C6でDeparture取りこぼし解消・JVM semanticsテスト16/16）。fontScale 1.5x実機5画面破綻なし（実破綻1件はテストが検出し修正済み）。減点0.5: 実TalkBack音声はエミュレータ制約で未実施（ノードダンプ＋semanticsテストで代替実証） |
| F. 障害系（仕様§95） | 10 / 10 | 6/6実機実証: カレンダー拒否・位置拒否・通知拒否・exact alarm不許可→inexactフォールバック＋劣化バナー（tP5E2e3）・経路APIオフライン縮退（tE2e3_3）・再起動後アラーム再登録（tP5E2e4・要adb root手順文書化済み） |

## 実装完了までの品質イベント（要約）

- 実機E2Eが発見し修正した本番欠陥: 計6件（INTERNET権限欠落・権限チェック順序・routes キー省略応答・DRIVE TRAFFIC_AWARE欠落・departureTime未来クランプ・fontScale 1.5xのRecoveryレイアウト破綻）
- 計画の谷間で発見した統合漏れ: 仕様§13のTravelTime項（P4-C8で解消・厳密一致テスト付き）
- Phase 11でPOST_NOTIFICATIONS未リクエスト（実ユーザーに通知が出ない）を解消

## 既知の残存事項（リリース判定に含めた上での開示）

1. **C-18 行動ログ未実装**: 第1弾スコープ唯一の未達項目。Phase 10（Personal Profile）の前提データでもあるため、小型フォローアップまたはPhase 10冒頭での実装を推奨
2. **T-E2E3-1実行不能（R16）**: AVD位置バックエンド故障。コードはJVM側で検証済み。playstoreイメージAVDへの換装で実測可能
3. **既定移動手段TRANSITは日本でAPI構造的にNoRoute**（Google Routes APIが日本の公共交通データ非提供・実測確定）→手動入力フォールバックUXになる。既定をWALKへ変更するかはユーザー判断待ち
4. tP5E2e4（boot復元E2E）は`adb root`前提（保護ブロードキャストのOS制約・手順文書化済み）
5. exact alarm設定画面へのワンタップ導線（ADR-0026の中強度案の残り半分）は未実装・バナー文言のみ

## 判定

**リリース可**（90点基準に対し97.6点）。実際の配布操作（Google Play Console登録・内部テストトラック公開）はユーザー確認必須のため実施していない。
