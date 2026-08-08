# DECISIONS — Action Starter (Android)

> 本書は `Action_Starter_Master_Specification_v2.0_Android.md`（正仕様書）の要約である。差異がある場合は仕様書が正。v1.0(iOS)はアーカイブ。

本書はAction Starter Androidプロジェクトの重大な設計判断（Architecture Decision Record, ADR）を記録する（§64）。

## 記録ルール

生成AIは重大な設計変更を行う際、本書へADRを追記する（仕様§64 Development Phase 0）。特にinterface契約の変更は、`docs/TEAMS.md`§5「interface契約のバージョン付き変更経路」の最終ステップ（変更提案→android-planner影響分析→Fable 5承認→本書記録→両側テスト更新）として、本書への記録が完了して初めて変更確定とみなす。

## 記録トリガー（6種）

以下のいずれかに該当する判断は、本書へのADR追記を必須とする。

1. interface契約の変更
2. 仕様未定義箇所の補完
3. 仕様推奨からの逸脱
4. 依存バージョンの変更
5. 権限・プライバシー・外部送信に関わる変更
6. Phaseゲート（G1〜G4）の変更

## ADRテンプレート

```
### ADR-XXXX: <タイトル>

- 日付: ／ ステータス: ／ 決定者: ／ 起案agent: ／ 関連仕様§:

**背景**:

**決定**:

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|

**影響範囲**:
**検証方法**:
**再検討トリガー**:
```

## 初期ADR一覧（10件）

### ADR-0001: v2.0_Androidを正仕様書とし、v1.0をアーカイブ扱いとする

- 日付: 2026-08-08 ／ ステータス: 承認済み（既決事項の記録） ／ 決定者: 仕様書ヘッダー／`docs/TEAMS.md`の既定記載 ／ 起案agent: android-planner（Phase0計画メモ） ／ 関連仕様§: ヘッダー・Changelog

**背景**: v1.0（iOS）からv2.0（Android）へ全面改訂され、両ファイルがリポジトリに併存している。

**決定**: `Action_Starter_Master_Specification_v2.0_Android.md`を正仕様書とし、`Action_Starter_Master_Specification_v1.0.md`はiOS原本のアーカイブとして扱う（実装根拠にしない）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| v1.0を正としAndroid差分を別紙管理する | 二重管理でドリフトが生じる |

**影響範囲**: 全ドキュメント・全実装判断の参照先。
**検証方法**: 各計画書・ADRの参照先が`_v2.0_Android`ファイルであることの確認。
**再検討トリガー**: v3.0策定時。

---

### ADR-0002: Phase 1は単一`:app`モジュール構成を採用する

- 日付: 2026-08-08 ／ ステータス: 承認済み・G1通過 ／ 決定者: Fable 5 ／ 起案agent: android-planner ／ 関連仕様§: §43

**背景**: §43は8つの論理層（App/Domain/Services/Planning/Recovery/AI/Persistence/Features）を定義するが、Gradleモジュール分割の要否は明示していない。

**決定**: Phase 1は単一`:app`モジュールとし、8層はKotlinパッケージとして表現する（`ARCHITECTURE.md`§1）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 最初から機能別/層別マルチモジュール化する | MVP検証段階でのビルド複雑性・ボイラープレート増がPhase1の速度を阻害する |

**影響範囲**: `build.gradle.kts`構成、パッケージ設計。
**検証方法**: Phase1完了時のGradleビルド成功とパッケージ構造が§43ツリーに対応していることの確認。
**再検討トリガー**: Phase 7（Local LLM Runtime導入）でのネイティブ依存増大時、またはビルド時間が実用上の問題になった時点。

---

### ADR-0003: Phase 1は手動DI（AppContainer）とし、Hilt導入はPhase 2先頭へ延期する

- 日付: 2026-08-08 ／ ステータス: 承認済み・G1通過 ／ 決定者: Fable 5 ／ 起案agent: android-planner ／ 関連仕様§: §42

**背景**: §42はDIとしてHiltを「推奨」とするが必須要件ではない。

**決定**: Phase 1は手動DI（`AppContainer`）で開始し、Hilt導入はPhase 2先頭へ延期する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| Phase 1からHiltを導入する | KSPアノテーション処理系のセットアップがPhase1のUI Skeleton検証速度を遅らせるリスクがある |

**影響範囲**: Application初期化、Engine/Serviceの依存解決方法。
**検証方法**: Phase1でAppContainer経由の依存解決が全画面で機能すること。Phase2のHilt移行時の回帰テスト。
**再検討トリガー**: Phase 2開始時（既定の延期先、予定どおりの実行）。

---

### ADR-0004: RoutingServiceは§46のシグネチャに統一する

- 日付: 2026-08-08 ／ ステータス: 承認済み（Fable 5裁定 U1） ／ 決定者: Fable 5 ／ 起案agent: android-planner ／ 関連仕様§: §9・§46

**背景**: 旧版仕様で§9と§46のRoutingServiceコード例に不一致（`departureDate`引数の有無）があった。

**決定**: `estimateRoute(origin: Coordinate, destination: Coordinate, mode: TransportMode, departureDate: Instant): RouteEstimate`（§46）に統一する。§9のコード例も本裁定と同時に修正済み。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| §9側（departureDateなし）に統一する | Departure Mode（§29）・Reality Check（§30）の再計算にはリクエスト時点の出発時刻が必須であり要件を満たさない |

**影響範囲**: `RoutingService` interface、全呼び出し箇所。
**検証方法**: Phase1契約scaffold時点でのinterfaceコンパイル確認。
**再検討トリガー**: RoutingService Provider追加時にシグネチャ拡張が必要になった場合。

---

### ADR-0005: 仕様書未定義の7型を補完定義する

- 日付: 2026-08-08 ／ ステータス: 承認済み（Fable 5裁定 U2） ／ 決定者: Fable 5 ／ 起案agent: android-planner ／ 関連仕様§: §44-52（Phase1契約scaffoldの前提）

**背景**: §44-52のinterfaceは`PlanningContext`／`RecoveryPlan`／`RouteEstimate`／`Coordinate`／`CalendarSource`／`AIPlanResponse`／`AIRecoveryResponse`を参照するが、仕様書は型定義を明記していない。

**決定**: 上記7型を`ARCHITECTURE.md`§4に補完定義し、Phase1契約scaffoldの根拠とする。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| Phase1実装時にその場で定義する | 未定義のままui-implementer/domain-implementerを並列着手させると契約の手戻りリスクが生じる（`docs/TEAMS.md`§5の循環依存の懸念） |

**影響範囲**: `ARCHITECTURE.md`、Phase1契約scaffold全体。
**検証方法**: Phase1契約scaffoldのコンパイル成功。
**再検討トリガー**: Phase1契約scaffold実装時にフィールド不足が判明した場合。

---

### ADR-0006: G4をG4-JVM/G4-Eに2段化し、TDD例外にC1 Gradleブートストラップを追加する

- 日付: 2026-08-08 ／ ステータス: 承認済み（Fable 5裁定 U3） ／ 決定者: Fable 5 ／ 起案agent: android-planner ／ 関連仕様§: `docs/TEAMS.md` TDD原則／G4

**背景**: TDD原則は「対応する失敗テストが存在しない本番コードを書かない」だが、Gradleプロジェクト自体が存在しない段階ではテストランナーもなくRedを実行できない（ブートストラップ問題）。加えて実機/エミュレータ依存の検証はKVM等の実行環境整備に依存し、Phase1時点で常に用意できるとは限らない。

**決定**: TDD例外にC1（Gradleブートストラップ）を追加し、C1完了時にSmokeComposeTest（Robolectric+Compose）のGreen実測を必須とする。Phase1のG4を即時必須の**G4-JVM**（JVM/Robolectricで完結する範囲）と、KVM解決後に必須となる**G4-E**（実機/エミュレータ）に2段化し、**G4-EをPhase 3以降へキャリーすることを禁止**する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| G4を単一のまま実機検証必須にする | KVM等の実行環境整備を待つ間、Phase1全体がブロックされる |
| エミュレータ検証を全Phaseで任意化する | §95の時刻厳密通知等はエミュレータ/実機実測なしに品質保証できない |

**影響範囲**: `docs/TEAMS.md` TDD原則・G4ゲート定義。
**検証方法**: C1完了時のSmokeComposeTest実行ログ。Phase1完了時のG4-JVM証拠、KVM解決後のG4-E証拠。
**再検討トリガー**: KVM等の実機/エミュレータ実行環境が整備された時点。G4-EがPhase3以降へ持ち越されそうになった場合は本ADRの禁止事項により即エスカレーション。

---

### ADR-0007: compileSdk/targetSdkは35、AGPは8系で開始する

- 日付: 2026-08-08 ／ ステータス: 承認済み・G1通過 ／ 決定者: Fable 5 ／ 起案agent: android-planner ／ 関連仕様§: §42

**背景**: §42はminSdk 26目安・targetSdk最新とするが具体的数値は固定していない。

**決定**: compileSdk/targetSdkは35、AGPは8系で開始する。Phase 13配布前にtargetSdk要件を再検討する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| targetSdkを常に最新へ自動追従する | Phase単位の再現性・ビルド安定性が損なわれる |

**影響範囲**: `build.gradle.kts`、Play審査要件（§95.5）。
**検証方法**: Phase1のGradleビルド成功。
**再検討トリガー**: Phase 13配布前（Google PlayのtargetSdk要件更新に合わせて再確認）。

---

### ADR-0008: 内部Domainの時間型はjava.time.Duration/Instantに統一する

- 日付: 2026-08-08 ／ ステータス: 承認済み・G1通過 ／ 決定者: Fable 5 ／ 起案agent: android-planner ／ 関連仕様§: §8・§48-52

**背景**: §48-52のコード例はDuration/Instantを使うが、`kotlin.time.Duration`と`java.time.Duration`が同名で存在し混在リスクがある。

**決定**: 内部Domainの時間型は`java.time.Duration`／`Instant`に統一し、`kotlin.time.Duration`と混在させない。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `kotlin.time.Duration`に統一する | `java.time`系API（`ZonedDateTime`等、§8）との相互運用で変換コードが増える |

**影響範囲**: 全Domain Model、Engine実装。
**検証方法**: importの静的チェックで`kotlin.time.Duration`の誤importを検知する。
**再検討トリガー**: なし（Kotlin Multiplatform化等の大方針転換時のみ再検討）。

---

### ADR-0009: 既定ロケールリソースは`values/`=en、`values-ja/`=jaとする

- 日付: 2026-08-08 ／ ステータス: 承認済み・G1通過 ／ 決定者: Fable 5 ／ 起案agent: android-planner ／ 関連仕様§: §7

**背景**: §7は最低ja-JP/en-US対応を必須とするが、無指定デフォルト（`values/`）をどちらにするかは明記していない。

**決定**: `values/`（無指定デフォルト）= en、`values-ja/`= jaとする。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `values/`=ja（日本語デフォルト）とする | Android文字列リソースの一般的規約から外れ、将来の多言語追加時の一貫性が下がる |

**影響範囲**: `strings.xml`構成全体。
**検証方法**: Phase11 Localization完了時の英語/日本語切替確認。
**再検討トリガー**: なし。

---

### ADR-0010: Domain modelは全フィールドval＋copy()＋init再検証方式とする

- 日付: 2026-08-08 ／ ステータス: 承認済み（Fable 5裁定・Geminiクロスレビュー起点） ／ 決定者: Fable 5 ／ 起案agent: Geminiクロスレビュー（指摘）→Fable 5（裁定） ／ 関連仕様§: §48・§49・§52

**背景**: 仕様§48（`ExecutionStep`）／§49（`ExecutionPlan`）／§52（`PersonalExecutionProfile`）のコード例は一部フィールドを`var`としている。生成後の直接再代入は`init`検証を迂回し、Domain不変条件が破られてもエラーにならないサイレント障害を生みうる。

**決定**: Domain modelは全フィールド`val`とし、状態変更は`copy()`経由、`init{}`での再検証を必須とする。仕様§48/§49/§52の`var`表記からは意図的に逸脱する。`copy()`はコンストラクタ経由で`init`が再実行されるため、検証が常に効く。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 仕様書どおり`var`を許容する | 生成後の再代入が`init`検証を迂回し、サイレント障害を生む（Fable Protocol接地原則に抵触） |

**影響範囲**: §47-52の全Domain Model実装。
**検証方法**: Domain modelのunit testで、`copy()`経由の不正値が`init`により再検証・拒否されることを確認する。
**再検討トリガー**: `copy()`のパフォーマンスコストが実測で問題化した場合（現時点では想定薄）。

---

### ADR-0011: AndroidXライブラリ4本のバージョン降格（minCompileSdk制約対応）

- 日付: 2026-08-08 ／ ステータス: 承認 ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（C1）

**背景**: 計画時ピン（activity-compose 1.13.0 / navigation-compose 2.9.8 / lifecycle 2.11.0 / core-ktx 1.19.0）で`:app:assembleDebug`が`checkDebugAarMetadata`13件失敗。AARメタデータ実測により、これらの新版がminCompileSdk 36〜37（一部はAGP 9.1.0+も）を要求することが判明。本プロジェクトはcompileSdk 35で確定済み（ADR-0007）。

**決定**: activity-compose **1.10.1** / navigation-compose **2.9.0** / lifecycle **2.10.0** / core-ktx **1.16.0** へ降格。Gradle 8.14.5 / AGP 8.13.2 / Kotlin 2.4.10 / Compose BOM 2026.06.01 は計画どおり維持する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| compileSdk 36へ引き上げる | platforms;android-36追加DL・AGP 9系移行の連鎖リスクがPhase 1スコープに不釣り合いのため却下。ADR-0007の再検討時に再評価する |

**影響範囲**: `gradle/libs.versions.toml`のみ。
**検証方法**: T-BUILD-1/2/3全pass＋SmokeComposeTest 1/1 pass（ログ: `build/agent-logs/c1-*.log`）。
**再検討トリガー**: compileSdk引き上げ時（ADR-0007と同時）。
**付記（C1で実測確定した知見）**: Robolectric 4.16.1はSDK 35対応済み（`@Config`固定不要）／LintのHardcodedTextはComposeのKotlinコード内文字列を検出しない（プローブ実測。i18n強制はT-I18NテストとG4レビューで担保）／`createComposeRule()`にdeprecated警告あり（v2 APIへの将来移行を申し送り）。
