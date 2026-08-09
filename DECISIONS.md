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

**付記（C4、Fable 5承認済み）**: `ExecutionPlan`はT-DM-6（`steps`昇順正規化）を満たすため、`data class`ではなく通常の`class`として実装する。理由：`data class`はコンストラクタ引数と同名の公開プロパティしか持てず、「コンストラクタでは未整列の`steps`を受け取りつつ、公開する`steps`プロパティは正規化済みの値にする」実装ができないため。`equals`/`hashCode`/`toString`/`copy`は`data class`が自動生成する場合と同じフィールド単位の構造的等価性・複製セマンティクスになるよう手動実装し、公開コンストラクタの形（引数名・型・順序・可視性）は変更していない。`copy()`もコンストラクタを経由するため`init`再検証（ADR-0010本文）は維持される。

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

---

### ADR-0012: `androidTest`ソースセットの依存関係を追加する（androidx.test.ext:junit / androidx.test:runner）

- 日付: 2026-08-08 ／ ステータス: 承認 ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（C6）

**背景**: C3b時点で`app/src/androidTest/java/com/actionstarter/e2e/MainUxFlowTest.kt`が作成されたが、`app/build.gradle.kts`に`androidTestImplementation`依存が一切なく、同ファイルのimport（`androidx.compose.ui.test.*`／`androidx.test.ext.junit.runners.AndroidJUnit4`／`androidx.test.platform.app.InstrumentationRegistry`）が解決できず`:app:compileDebugAndroidTestKotlin`がコンパイル不能だった（C3b完了報告で申し送り済み、C6/G4-Eの前提）。

**決定**: `gradle/libs.versions.toml`へ`androidx-test-ext-junit`（**1.3.0**）・`androidx-test-runner`（**1.7.0**）を追加し、`app/build.gradle.kts`の`androidTestImplementation`に`platform(libs.androidx.compose.bom)`・`androidx.compose.ui.test.junit4`（既存カタログエントリを流用）・`androidx-test-ext-junit`・`androidx-test-runner`を追加する。あわせて`defaultConfig.testInstrumentationRunner`（未設定だった）へ`"androidx.test.runner.AndroidJUnitRunner"`を設定する。バージョンはdl.google.comのmaven-metadata.xmlで実在を確認した最新安定版（1.3.0 / 1.7.0）を採用した。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| espresso-core も追加する | `MainUxFlowTest.kt`はEspresso APIを使用せずCompose UI Testのみで構成されているため、未使用依存の追加は避けた |
| androidx.test:core を明示的に追加する | `androidx.test.ext:junit:1.3.0`が推移的にandroidx.test:core 1.7.0・androidx.test:monitor 1.8.0（`InstrumentationRegistry`提供元）を引き込むため、明示追加は不要と判断した |

**影響範囲**: `gradle/libs.versions.toml`・`app/build.gradle.kts`（`androidTestImplementation`構成・`testInstrumentationRunner`のみ。既存の`implementation`／`testImplementation`構成は変更なし）。
**検証方法**: `:app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL実測（ログ: `build/agent-logs/c6-androidtest-compile.log`）。ADR-0011同様のminCompileSdk制約超過（`checkDebugAndroidTestAarMetadata`失敗）は発生しなかった（compileSdk 35のまま解決）。実機／エミュレータでの実行（`connectedDebugAndroidTest`）はG4-E（KVM解決後）まで未実施。
**再検討トリガー**: G4-E実行時にランタイムのAAR/バイトコード非互換が判明した場合。compileSdk引き上げ時（ADR-0007／ADR-0011と同時）。

---

### ADR-0013: release変種のホスト側unit testを無効化する

- 日付: 2026-08-08 ／ ステータス: 承認済み（Fable 5裁定） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（C6追加修正） ／ 関連仕様§: 計画書`docs/plans/phase1-ui-skeleton-domain.md`§10.4（U4裁定）・§11.1

**背景**: G4-JVM実測で`./gradlew build`が`:app:testReleaseUnitTest`の38件失敗により失敗した（ログ: `build/agent-logs/c6-g4jvm-build.log`）。失敗38件は全件「release変種のマージ済みManifestに`androidx.activity.ComponentActivity`が未宣言」（Robolectric #4736）が原因であり、ComponentActivity宣言を提供する`ui-test-manifest`はdebug専用依存（`debugImplementation(libs.androidx.compose.ui.test.manifest)`、`app/build.gradle.kts`）のためrelease変種には適用されない。加えて§10.4（U4裁定）により「Simulate delay (debug)」ボタンは`BuildConfig.DEBUG=false`のrelease変種では非搭載となる設計であり、これを操作する`T-NAV-3`等はrelease変種では設計上必ず失敗する。つまりrelease変種でのUIテスト全passは構造的に不可能である。計画書§11.1はJVM/Robolectricテストの検証面を`:app:testDebugUnitTest`と定義しており、release変種でのunit test実行はそもそも検証面に含まれていない。

**決定**: `app/build.gradle.kts`へAGP `androidComponents` API（`ApplicationAndroidComponentsExtension`）を用いてrelease変種のホスト側unit testを無効化する。

```kotlin
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}
```

実測により、`ApplicationVariantBuilder`が継承する`HasUnitTestBuilder.enableUnitTest`はAGP 8.13.2時点でコンパイル・実行可能だが非推奨（`Will be removed in AGP 9.0`、Gradle Configure時にdeprecation警告実測）と判明したため、非推奨でない後継API`HasHostTestsBuilder.hostTests[HostTestBuilder.UNIT_TEST_TYPE].enable`を採用した（`com.android.build.api.variant.HostTestBuilder`のimportを追加）。これにより`:app:testReleaseUnitTest`タスク自体がタスクグラフから消え、`:app:build`は`:app:testDebugUnitTest`（72件）のみを実行して成立する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| release変種のManifestへ`ui-test-manifest`由来のComponentActivity宣言を混入させる | debug専用の仕組みをreleaseへ持ち込むと、テスト専用Activity等がreleaseの実マージ済みManifestに混入するリスクがあり、リリース成果物の健全性を損なう。releaseは本来「テスト用ホスト」を必要としない変種であり、本質的な解決にならない |
| `T-NAV-3`等release非搭載機能に依存するテストのみを`assumeTrue(BuildConfig.DEBUG)`等でrelease時にスキップさせる | ComponentActivity未宣言の根本原因（Manifest側）は解決しないため、他の37件は依然として失敗する。個別テストへの分岐追加は対症療法でありrelease変種unit test全体を無効化する本裁定より複雑性が高い |
| `enableUnitTest = false`（非推奨API）のまま採用する | コンパイル・実行は可能だが「deprecated APIは避ける」方針、およびAGP 9.0で削除予定である旨がConfigure時に実測されたため、非推奨でない後継APIへ調整した |

**影響範囲**: `app/build.gradle.kts`（`androidComponents`ブロック追加・`HostTestBuilder`のimport追加のみ）。debug変種のテスト実行（`:app:testDebugUnitTest`）・release変種のビルド（`assembleRelease`・lint等）には影響しない。
**検証方法**: `./gradlew build --console=plain`のBUILD SUCCESSFUL実測（ログ: `build/agent-logs/c6-g4jvm-build-2.log`）。同ログに`:app:testReleaseUnitTest`タスクが一切出現しないこと（タスクグラフから除外）、`:app:testDebugUnitTest`が実行され`app/build/test-results/testDebugUnitTest/`配下のJUnit XML集計でtests=72・failures=0・errors=0であることを確認した。
**再検討トリガー**: release固有ロジック（release専用の分岐処理・release専用Feature Flag等）が増え、release変種特有のunit testが必要になった場合。AGP 9.0への移行時（`enableUnitTest`非推奨API自体は本ADRでは未使用のため直接の影響はないが、`hostTests`系APIの変更有無を再確認する）。

---

### ADR-0014: Hilt導入のPhase 5延期（P2-C1プローブ実測による確定）

- 日付: 2026-08-09 ／ ステータス: 承認 ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P2-C1） ／ 関連仕様§: 計画書`docs/plans/phase2-calendar.md`§8.4・§14 P2-C1（ADR-0003の再検討トリガー「Phase 2開始時」に対応）

**背景**: ADR-0003はPhase 2先頭でのHilt導入を予定していた。`docs/plans/phase2-calendar.md`裁定B17により、この方針は一旦「graph-only Hilt」方式（`@HiltViewModel`／`hiltViewModel()`／`@AndroidEntryPoint`を使わず、`@HiltAndroidApp`＋モジュール＋`EntryPointAccessors`のみを導入する最小構成。ADR-0015案）へ前倒しされたが、そのP2-C1プローブ実測で、Hilt Android Gradle plugin 2.60.1が**AGP 9.0.0以上を必須**とする内蔵チェックにより`apply`時点で失敗することが判明した（実測ログ: `build/agent-logs/p2c1-probe-ksp-hilt.log`。エラー原文「The Hilt Android Gradle plugin is only compatible with Android Gradle plugin (AGP) version 9.0.0 or higher (found Android Gradle Plugin version 8.13.2).」）。本プロジェクトのAGPは8.13.2（ADR-0007）で確定済みである。この失敗はKSP/kapt選択（計画書§8.4のフォールバック①②が対処する領域）とは無関係にplugin適用そのものが拒否される事象（プローブP-H2の確定失敗）であり、フォールバック①（KSPバージョン変更）／②（kapt切替）では解消不能と判断した。

**決定**: Hilt導入をPhase 5（§69 Notification+Execution）へ延期する。`docs/plans/phase2-calendar.md`裁定B17が前提としていたADR-0015（graph-only Hilt導入）は発効しない。手動DI（`AppContainer`＋単一`ViewModelProvider.Factory`集約＝ADR-0003の延期条件）を継続する。ベースライン`:app:testDebugUnitTest` 73/73 Greenは、P2-C1で加えた変更（Hilt plugin適用等）を全復元した状態で復元検証済みである（実測ログ: `build/agent-logs/p2c1-post-revert-sanity.log`。`app/build/test-results/testDebugUnitTest/`配下のJUnit XML集計でtests=73・failures=0を確認）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| AGP 9系へ引き上げる | 影響範囲がPhase 2本題（Calendar機能）に不釣り合いであり、ADR-0007の再検討トリガー（Phase 13配布前）を待たずに前倒しする理由がない。ADR-0007の再検討時にあわせて再評価する |
| AGP 8.x対応の旧Hilt版を採用する | 機能価値ゼロ（DIの実利益は依然として発生しない）のまま、動作するHiltバージョンを探索する「版考古学」の検証コストだけが発生する。空プレースホルダ禁止（§88）に反する |

**影響範囲**: なし。P2-C1でHilt関連に加えた変更（`app/build.gradle.kts`のplugin適用等）は全復元済み（`git diff --stat HEAD -- app/build.gradle.kts gradle/libs.versions.toml`で差分0、両ファイルに`hilt`文字列の残存なしを実測確認）。**なお本ADR記録時点でP2-C2（probe＋契約scaffold）が並行して進行中であり、`AndroidManifest.xml`（READ_CALENDAR追加）・`services/calendar/`・`services/permission/`配下に未コミットの変更が別途存在するが、いずれもHilt/AppModule/AppEntryPointへの参照を含まないことを確認済みであり、本ADRの対象（Hilt導入可否）とは無関係のP2-C2作業である。**
**検証方法**: `build/agent-logs/p2c1-baseline.log`（変更前ベースライン、BUILD SUCCESSFUL）／`build/agent-logs/p2c1-probe-ksp-hilt.log`（Hilt plugin適用失敗の実測）／`build/agent-logs/p2c1-post-revert-sanity.log`（復元後の健全性確認、BUILD SUCCESSFUL）。
**再検討トリガー**: Phase 5（§69）着手時（その時点のAGP/Hilt環境で①旧Hilt版の採用②AGP 9系への引き上げ③手動DI継続の3択を再判定する）。またはAGP 9系への引き上げがADR-0007の再検討により本ADRより先に発生した場合。

**付記**: `:app:testDebugUnitTest`のテスト総数の正は**73**である（ADR-0013時点の72件〔release変種unit test無効化時点の実測〕に、T-MOCK-11回帰テスト（`app/src/test/java/com/actionstarter/mock/MockEventSourceTest.kt`、コミット`1808128`「T-MOCK-11回帰テスト追加」）が追加され+1。2026-08-09のベースライン実測（`p2c1-baseline.log`、JUnit XML集計）で73/73 Greenとして確定した）。

---

### ADR-0015: BasicPlanningEngineの既定値は`BasicPlanningDefaults`へ隔離し仕様未定義プレースホルダと明記する

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P4-C1） ／ 関連仕様§: §4・§13・G-1

**背景**: `BasicPlanningEngine`（F40〜F46）はtransition／preparation／arrivalBufferの既定値を要する。4種テンプレート構造とtransition 5分／preparation 15分の分数は仕様未定義のプレースホルダである一方、arrivalBuffer 10分は仕様§4「希望到着余裕」のNormalプリセットに根拠がある（G-1裁定、計画書§4）。既定値が実装各所へ分散すると、Phase 10のPersonal Execution Profile置換時に変更箇所を把握できなくなる（R-7）。

**決定**: 既定値定数を`planning/BasicPlanningDefaults.kt`（`object`）へ集約する。KDocでtransition／preparationを「仕様未定義プレースホルダ・Phase 10で置換」、arrivalBufferを「仕様§4 Normalプリセットに根拠あり」と明記する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 既定値を`BasicPlanningEngine`内に`private`で保持する | `PlanReviewViewModel`の`DEFAULT_ARRIVAL_BUFFER`との出所統一（計画書§6.2）ができず、値の不一致リスクが残る（R-7） |

**影響範囲**: `planning/BasicPlanningDefaults.kt`（新設）、`BasicPlanningEngine`（P4-C3で参照）、`PlanReviewViewModel`（P4-C5で参照先変更）。
**検証方法**: T-BPE-13/14（既定値・profile優先の適用確認）。
**再検討トリガー**: Phase 10 Personal Execution Profile永続化実装時（既定値をprofile実測値へ置換）。

---

### ADR-0016: BasicPlanningEngineのstep構築順序は仕様§48 enum順に一致させる

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P4-C1） ／ 関連仕様§: §48・G-6

**背景**: 実測M4-8（計画書§7.1）により、現行Mock実装はTRANSITION→PREPARATION→TRAVEL→DEPARTUREの順でステップを構築しており、TRAVELとDEPARTUREが同一`scheduledStart`（`departureTime`）を持つため`ExecutionPlan`のnullsLast安定ソート（`ExecutionPlan.kt:40`）を経ても構築順がそのまま保たれ、仕様§48のenum順（TRANSITION, PREPARATION, DEPARTURE, TRAVEL）と逆順で確定してしまうことが判明した。

**決定**: `BasicPlanningEngine`は`steps`のリスト構築順を仕様§48のenum順（TRANSITION→PREPARATION→DEPARTURE→TRAVEL）へ一致させる（G-6）。`ExecutionPlan`のソート仕様（`scheduledStart`昇順・nullsLast、T-DM-6）自体は変更しない。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `ExecutionPlan`のソートキーにtype enum順を副キーとして追加する | C2確定済みの`ExecutionPlan`契約（M4-2で変更不要と確認済み）への変更が必要になり影響範囲が拡大するため |

**影響範囲**: `BasicPlanningEngine.createPlan`内のstep構築順序のみ（P4-C3で実装）。
**検証方法**: T-BPE-5（順序の回帰ロック）。
**再検討トリガー**: 仕様§48のenum定義順が変更された場合。

---

### ADR-0017: ExecutionStep.idはUUID.nameUUIDFromBytesによる決定的生成へ置き換える

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P4-C1） ／ 関連仕様§: §48

**背景**: 実測M4-9（計画書§7.1）により、現行Mock実装は`id = UUID.randomUUID()`を用いており、同一入力で再planningしても`id`が変化することが判明した。Execution画面の完了状態追跡等、同一ステップの同一性に依存する処理が不安定になる（§9エラーマップ#10）。

**決定**: `BasicPlanningEngine`は`ExecutionStep.id`を`UUID.nameUUIDFromBytes("${event.id}:$semanticId".toByteArray())`で決定的に生成する。`event.id`（イベント単位の一意性）と`semanticId`（ステップ種別、4種固定）の組を鍵とする。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `event.id`＋`ExecutionStepType`（enum）を鍵にする | `semanticId`は既にG-4によりlocalizationキーとして安定契約化されており、二重に鍵概念を持つ必要がない |

**影響範囲**: `BasicPlanningEngine.createPlan`内の`ExecutionStep.id`生成箇所のみ（P4-C3で実装）。
**検証方法**: T-BPE-26（同一入力での再planningでも同一`id`）。
**再検討トリガー**: 同一event内で`semanticId`が重複しうる仕様変更が生じた場合（現状F41は4種固定で重複なし）。

---

### ADR-0018: ExecutionStep.titleは空文字固定としUI層でsemanticIdをlocalizationキーとして解決する

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P4-C1） ／ 関連仕様§: §7・§48・G-4

**背景**: 実測M4-7（計画書§7.1）により、現行Mock実装は`title`に"Transition"等の英語文字列を直接埋め込んでおり、仕様§7「UI文字列の直接ハードコード禁止」に違反することが判明した。Domain層（`planning/`）がAndroidリソース（`stringResource`）へ直接依存することは仕様§7.1のレイヤー越境禁止規約にも反する。

**決定**: `BasicPlanningEngine`は`ExecutionStep.title`を常に空文字で生成する（G-4）。表示文言は`features/common/StepTitle.kt`が提供する`semanticId → stringResource`解決関数をUI層（`PlanReviewScreen`／`ExecutionScreen`）が呼び出して解決する。未知`semanticId`はフォールバック文言を返しクラッシュしない。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| Engine内で`PlanningContext.locale`を見てtitle文字列を直接組み立てる | Domain層がロケール判定・文言管理を持つことになり§7.1のレイヤー分離規約に反する |

**影響範囲**: `BasicPlanningEngine`（P4-C3）、`features/common/StepTitle.kt`（本ファイル・P4-C4）、`PlanReviewScreen.kt`／`ExecutionScreen.kt`（P4-C4）。
**検証方法**: T-BPE-8（titleが空文字）、T-P4UI-2/3（表示・フォールバック確認）。
**再検討トリガー**: なし。

---

### ADR-0019: mock/MockPlanFactory.ktはP4-C5統合ウィンドウで削除しBasicPlanningEngineへ完全昇格する

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P4-C1） ／ 関連仕様§: §89・計画書§2.1・§7.2

**背景**: `BasicPlanningEngine`は完全決定的（LLM等の非決定的要素を含まない）処理であり、テスト用fakeを別途用意する必要がない。`MockPlanFactory`と`BasicPlanningEngine`を並存させたまま`PlanningEngine`実装を2系統保持することは、仕様§89「No duplicated domain logic」に違反する（計画書§7.2 Mock昇格方針）。

**決定**: P4-C5統合ウィンドウで`AppContainer`の`planningEngine`実装を`MockPlanFactory()`から`BasicPlanningEngine()`へ切替え、`mock/MockPlanFactory.kt`と`test/.../mock/MockPlanFactoryTest.kt`を削除する。検証意図（T-MOCK-4/7/10）はT-BPE-11/18/1へ移設し弱体化しない（計画書§7.2対応表）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `MockPlanFactory`をテスト専用fakeとして残す | `BasicPlanningEngine`が完全決定的で挙動が同一のためfakeとして独立に存在させる理由がなく、仕様§89違反が生じる |

**影響範囲**: `mock/MockPlanFactory.kt`・`mock/MockPlanFactoryTest.kt`（P4-C5で削除）、`di/AppContainer.kt`（P4-C5で1行差替、本ADR起票時点では未着手）。
**検証方法**: T-P4DI-2（クラス非存在確認）、P4-C5完了後の全スイートGreen実測（件数比較）。
**再検討トリガー**: なし。

---

### ADR-0020: play-services-location 21.4.0を追加依存とする

- 日付: 2026-08-09 ／ ステータス: 承認済み ／ 決定者: Fable 5（計画書§3.2 S-1関連実測、§7.1で事前承認済みの技術選定） ／ 起案agent: domain-implementer（P3-C1） ／ 関連仕様§: §67・§42・§43（記録トリガー④依存バージョンの変更）

**背景**: F22（現在地取得）実装のため`FusedLocationProviderClient`が必要（仕様§42）。計画書§7.1が実測済みのとおり、`play-services-location:21.4.0`のAARメタデータは`minCompileSdk=1`／`minAndroidGradlePluginVersion=1.0.0`であり、推移依存`play-services-base:18.9.0`／`play-services-basement:18.9.0`／`play-services-tasks:18.4.0`も同一値である（ADR-0011型のminCompileSdk地雷は存在しない）。

**決定**: `gradle/libs.versions.toml`に`playServicesLocation = "21.4.0"`と`google-play-services-location`ライブラリエントリを追加し、`app/build.gradle.kts`へ`implementation(libs.google.play.services.location)`を追加する。`kotlinx-coroutines-play-services`（`Task.await()`用）は追加しない（`suspendCancellableCoroutine`で手動ラップする方針、§88判定）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `kotlinx-coroutines-play-services`も追加する | `Task.await()`のためだけに依存を1本増やす価値がない（§88）。`suspendCancellableCoroutine`で代替可能（P3-P8実測でdisconnect/キャンセル協調パターンの実現可能性を確認済み） |
| `android.location.LocationManager`のみで実装しGMS依存を回避する | 仕様§42が明示的に`FusedLocationProviderClient`（Google Play services）を指定しており、独自実装は仕様からの逸脱になる |

**影響範囲**: `gradle/libs.versions.toml`・`app/build.gradle.kts`（依存追加のみ）。`services/location/FusedRawLocationSource.kt`（P3-C1でscaffold・P3-C3で実装）。
**検証方法**: `:app:assembleDebug`のBUILD SUCCESSFUL実測（`checkDebugAarMetadata`含む。ログ: `build/agent-logs/p3c1-edit2-buildgradle.log`）。
**再検討トリガー**: compileSdk引き上げ時（ADR-0007／ADR-0011と同時）。Play Services側の将来バージョンでminCompileSdkが引き上げられた場合。

---

### ADR-0021: ACCESS_FINE_LOCATIONとACCESS_COARSE_LOCATIONを追加し、ACCESS_BACKGROUND_LOCATIONは追加しない

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定S-1、計画書§3.2） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画メモ）→domain-implementer（P3-C1で実装反映） ／ 関連仕様§: §67・§95.4・§58・§95.1（記録トリガー⑤権限・プライバシー・外部送信に関わる変更）

**背景**: §95.4の権限表はACCESS_FINE_LOCATIONのみを列挙するが、Android 12（API 31）以降はFINE単独の実行時要求で「正確な位置／おおよその位置」トグルが成立せずシステムが要求を無視する既知挙動がある（計画書§3.1 S-1）。一方§58／§95.1は「予定の前後だけ取得し常時監視はしない」「ACCESS_BACKGROUND_LOCATIONを要求しない設計」を明記する。

**決定**: `AndroidManifest.xml`に`ACCESS_FINE_LOCATION`と`ACCESS_COARSE_LOCATION`を併記する（FINEを成立させるための前提権限として扱う）。`ACCESS_BACKGROUND_LOCATION`は追加しない。COARSEのみ許可された場合の精度低下はUIに明示する（T-PERM3-5、P3-C3/C5で実装）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| ACCESS_FINE_LOCATIONのみ追加する（§95.4表の記載どおり） | Android 12+でのトグル不成立リスクを放置することになる（計画書S-1論点） |
| ACCESS_BACKGROUND_LOCATIONも追加し将来のPhase 5に備える | §58・§95.1が明示的に禁止し、Play審査リスクを増やす。Phase 3にバックグラウンド起動経路は存在しない |

**影響範囲**: `app/src/main/AndroidManifest.xml`（uses-permission 2行追加のみ）。
**検証方法**: debug/release両変種のマージ済みManifestに`ACCESS_FINE_LOCATION`／`ACCESS_COARSE_LOCATION`が含まれ、`ACCESS_BACKGROUND_LOCATION`がいずれにも含まれないことを実測確認済み（`app/build/intermediates/merged_manifests/{debug,release}/*/AndroidManifest.xml`、ログ: `build/agent-logs/p3c1-edit3-manifest.log`・`p3c1-edit3-manifest-release-check.log`）。P3-C7で§9.10のスクリプト検証4項目により再確認する。
**再検討トリガー**: P3-P1（Android 12+でのFINE単独要求挙動の実機UI確認）が本サイクルでは未実施（範囲外）のため、実施され本裁定の前提と異なる結果が出た場合。Phase 5でForeground Service経由の位置取得経路が追加される場合（ACCESS_BACKGROUND_LOCATIONの要否再検討）。

---

### ADR-0022: LocationService／GeocodingServiceの契約（シグネチャ未定義箇所）を補完する

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定、計画書§5.2・§5.3） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画メモ§5）→domain-implementer（P3-C1で実装反映） ／ 関連仕様§: §43（記録トリガー②仕様未定義箇所の補完）

**背景**: 仕様§43は`Services`直下に`LocationService`を列挙するが、`PlanningEngine`／`RecoveryEngine`／`RoutingService`とは異なりシグネチャのコードブロックが与えられていない（計画書§15 #1）。`GeocodingService`も同様に名前・役割のみが§43系の記載から類推される。

**決定**: 計画書§5.2／§5.3の契約案どおり、`LocationService.currentLocation(timeout: Duration = DEFAULT_TIMEOUT): LocationResult`（`LocationResult`はsealed interface＝`Success`／`PermissionDenied`／`Failure(LocationFailureReason, Throwable?)`）、`GeocodingService.geocode(locationName: String, timeout: Duration = DEFAULT_TIMEOUT): GeocodeResult`（`GeocodeResult`はsealed interface＝`Success`／`NoMatch`／`Failure(GeocodeFailureReason, Throwable?)`。`NoMatch`を`Failure`から分離）を`services/location/`に実装する。両interfaceの`DEFAULT_TIMEOUT`はエラー＆レスキューマップ#7/#14（計画書§10）の「既定10秒」に準拠し10秒とした（計画書のコードブロックには定数の実体が示されていなかったための補完）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `Location?`を直接返す（Android標準APIに近い形） | 「取れなかった」「精度が悪い」「権限がない」が全部nullへ潰れ、§95.1が名指しで警告する「サイレントな位置取得失敗」を型で防げない |
| geocode不能を`Failure`に含める | `CalendarContract.EVENT_LOCATION`は自由記述であり会議室名等の非住所が多数派の正常系になる。`Failure`にするとretry規則（§95.6）が意味を失う（Phase 2 P-7申し送りへの回答） |

**影響範囲**: `services/location/LocationService.kt`・`GeocodingService.kt`（新規、契約のみ）。実装（`FusedLocationService`／`AndroidGeocodingService`）はP3-C3。
**検証方法**: `:app:compileDebugKotlin`のBUILD SUCCESSFUL実測（ログ: `build/agent-logs/p3c1-scaffold-compile2.log`）。付記: 同時に作成した`GeocoderSource`（L3境界、計画書§5.5）は計画書の当該コードブロックが`fun interface`（Kotlin SAM、abstractメソッド1個限定）でありながらabstractメソッドを2個（`isAvailable`／`lookup`）宣言しておりコンパイル不能だったため、`fun`キーワードを外した通常の`interface`へ実装時に訂正した（メソッド構成・シグネチャ自体は計画書のまま）。
**再検討トリガー**: P3-C3実装時にシグネチャ不足が判明した場合。

---

### ADR-0023: RoutingException／ForegroundGateの判定式を記録し、P3-C1で完全実装する2クラス（UnconfiguredRoutingService・ActivityLifecycleForegroundGate）の判断根拠を明記する

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定S-4／S-5、計画書§3.2・§5.4・§5.5） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画メモ）→domain-implementer（P3-C1で実装反映） ／ 関連仕様§: §46・§95.1・§95.6（記録トリガー②仕様未定義箇所の補完）

**背景**: §46`RoutingService.estimateRoute`は`RouteEstimate`非null返却の契約だが§89／§95.6はretry・エラーハンドリングを要求する（両立方法が仕様上未定義＝S-4論点）。§95.1のWhile-in-use制約はForegroundGateという新規ガード機構を要求する（S-5論点）。加えてP3-C1（契約scaffold・TDD例外工程）は原則すべての実装本体を`TODO()`とするが、`UnconfiguredRoutingService`と`ActivityLifecycleForegroundGate`の2クラスのみ本サイクルで完全実装した。

**決定**: (1) `RoutingException`をsealed class階層（`NotConfigured`／`Offline`／`Timeout`／`Unauthorized`／`QuotaExceeded`／`ServerError`／`NoRoute`／`MalformedResponse`）として`services/routing/RoutingException.kt`に定義し、§46のシグネチャは変更しない。(2) `ForegroundGate.isLocationAccessAllowed()`の判定式を`isAppInForeground() || isExecutionServiceRunning()`とし、後者はPhase 5まで常にfalseを返す注入フック（`var isExecutionServiceRunning: () -> Boolean = { false }`）とする。(3) `UnconfiguredRoutingService`（常に`RoutingException.NotConfigured()`を送出する1行）と`ActivityLifecycleForegroundGate`（`Application.ActivityLifecycleCallbacks`による起動中Activityカウント、7メソッド全て明示オーバーライド）はP3-C1で完全実装する。理由: 両クラスとも分岐・条件判断を持たないトリビアルな実装であり「テストを通すための恣意的な実装」のリスクが実質的にないこと、加えて`ActivityLifecycleForegroundGate`は本サイクルで`ActionStarterApplication.onCreate()`に無条件登録されるため（共有ファイル#6）、コールバックがTODO()のまま登録されると全画面へ影響し既存Compose/Robolectricテスト（Activity起動を伴う全テスト）を破壊する構造的必然性がある。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| S-4でRoutingServiceの戻り値型自体を変更する（`Result<RouteEstimate>`等） | TEAMS§5の契約変更経路（変更提案→影響分析→Fable5承認→ADR記録→両側テスト更新）を発動し、Phase 4のPlanningContext設計にも波及する。sealed例外＋網羅whenで同等の型安全性を達成できる |
| `ActivityLifecycleForegroundGate`もTODO()スタブのままP3-C3まで実装を遅延する | Application.onCreate()での無条件登録と組み合わせると、全Compose/Robolectricテストがコールバック呼び出し時に`NotImplementedError`で失敗し、P3-C1完了条件（回帰なし・123/123 Green維持）を構造的に満たせない |
| `ActivityLifecycleForegroundGate`のApplication登録自体をP3-C6まで遅延する | 本タスク（P3-C1）の指示「ActionStarterApplication.ktへのForegroundGate登録」に反する。また登録を遅延させても、いずれ登録する時点で同じ制約に直面するだけで問題を先送りするに過ぎない |

**影響範囲**: `services/routing/RoutingException.kt`・`UnconfiguredRoutingService.kt`（新規）、`services/location/ForegroundGate.kt`・`ActivityLifecycleForegroundGate.kt`（新規）、`ActionStarterApplication.kt`（登録呼び出し追加）。
**検証方法**: `:app:testDebugUnitTest`が123/123 Green（P3-C1前と同一件数、回帰なし）であることを実測（ログ: `build/agent-logs/p3c1-regression.log`）。`UnconfiguredRoutingService`／`ActivityLifecycleForegroundGate`の網羅的な単体テスト（T-ROUTESVC-8相当・ForegroundGate相当）はP3-C2（Red）／P3-C3・C4（Green）で別途固定する。
**再検討トリガー**: P3-C2でのテスト作成時にActivityLifecycleForegroundGateのカウンタロジックに不備が判明した場合。S-4がFable 5により「戻り値型の変更」へ裁定変更された場合（R18参照、TEAMS§5契約変更経路の発動）。
