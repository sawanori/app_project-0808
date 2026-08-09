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

---

### ADR-0024: Hilt再判定（S-2）— 手動DI継続、ADR-0014却下理由の実測訂正、再検討トリガーの付け替え

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定S-2、計画書§1・§4.1・§7.2） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画メモ§1・§7.2） ／ 関連仕様§: ADR-0014再検討トリガー「Phase 5（§69）着手時」への対応（ADR-0014却下理由①の実測訂正）
- **ADR番号の確定根拠**: 本ADR-0024〜0028は`docs/plans/phase5-notification-execution.md`が2026-08-09時点で予約していた番号帯であり、同日付のADR-0029・ADR-0030（Phase 3側、`grep -n "ADR-00" DECISIONS.md`実測に基づき「予約と衝突するなら更に+1へずらす」規則で0024〜0028を明示的にスキップ）が温存した番号を、予約どおりに充当するものである。

**背景**: ADR-0014はHilt Android Gradle plugin 2.60.1がAGP 9.0.0以上を要求するためHilt導入をPhase 5（本Phase）へ延期し、再検討トリガーを「Phase 5（§69）着手時」と定めていた。本Phaseはこのトリガーに到達した。加えてPhase 5ではForeground Service・BroadcastReceiver 2種という、Hiltが本来得意とするframework実体化コンポーネントが新規に増える。

**決定**: 手動DI（`AppContainer`）を継続する（③）。実測（M5-1・M5-2）により、ADR-0014が却下理由として書いた「AGP 8.x対応の旧Hilt版が存在しない」という前提は不正確であったと訂正する——Hilt Gradle pluginは2.59（2026-01-21公開、最新2.60.1のわずか2マイナー前）まではAGP≥8.4.0で動作し、本プロジェクトのAGP 8.13.2で利用可能である。ただし採用可否の焦点はAGPからKotlin/KSP互換へ移っており（Kotlin 2.4.10×KSP 2.3.11×Dagger 2.59の三者互換はM5-4/M5-5のとおり未検証）、かつ新規に増える2コンポーネントは既存の`(context.applicationContext as ActionStarterApplication).appContainer`パターンで1行ずつ解決できるため、Hiltの限界価値は依然ゼロと判断する。再検討トリガーをPhase到達ベース（Phaseごとの再判定）からAGP移行ベースへ付け替える——次回はAGP 9系への引き上げ時（ADR-0007の再検討と同時）とする。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| ①AGP 8.x対応の旧Hilt版（2.59）を採用する | 却下理由の実測訂正により版そのものは存在するが、Kotlin 2.4.10×KSP 2.3.11×Dagger 2.59の三者互換が未検証（M5-4/M5-5）であり、確認にはP2-C1と同型の探索プローブが再度必要。新規に増える2コンポーネントの限界価値がゼロである以上、探索コストに見合わない（P5-P7として本Phaseでは非実施と裁定済み、計画書§10.2） |
| ②AGP 9系へ引き上げる | 9.3.1がstableで入手可能（M5-3）だが、ビルド基盤の全面移行でありPhase 5の主題（exact alarm／FGS）と無関係。variant API・Gradle下限・`buildFeatures`既定値等の広範な影響があり、245件（P5-C1再実測時点）のベースラインを主題外の理由で壊すリスクを負う。ADR-0007の再検討トリガー（Phase 13配布前）を前倒しする理由が本Phaseには存在しない |

**影響範囲**: `di/AppContainer.kt`（変更なし、既存の手動DI構成を継続）。新規Service（`ExecutionForegroundService`）・Receiver（`NotificationTriggerReceiver`／`ScheduleRestoreReceiver`）はいずれも`applicationContext as ActionStarterApplication`パターンで依存解決する設計とする（実装はP5-C3）。
**検証方法**: 本ADRは既存アーキテクチャの継続（変更なし）の記録であるため新規のビルド検証は不要。P5-C1のコンパイル成功実測（`build/agent-logs/p5c1-compile.log`）が本決定と矛盾しないことを確認済み。
**再検討トリガー**: 旧トリガー「Phaseごとの着手時」を廃し、新トリガー「AGP 9系への引き上げ時（ADR-0007の再検討と同時）」を採用する。Phase 6以降での毎Phase再判定は行わない。

---

### ADR-0025: Boot後アラーム復元の永続化方式（S-1）と取り逃したトリガーの猶予復元（S-9）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定S-1・S-9、計画書§1・§4.1・§4.2・§7.3・§9・§12.2） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画メモ§2） ／ 関連仕様§: §69・§95.1・§95.6（S-1＝記録トリガー③仕様推奨からの逸脱、S-9＝記録トリガー②仕様未定義箇所の補完）

**背景**: §95.1／§95.6は「Room（`ExecutionStore`）の未完了Planから再スケジュール」と明記するが、§64〜§77のどのPhaseにも`ExecutionStore`（Room実装）を割り当てるPhaseが存在しない（§74 Phase 10は`UserProfileStore`/`AnalyticsStore`側であり別物）。再登録の断念は選択肢にならない——§69がboot再スケジュールをPhase 5成果物として明示し、§95.6に専用行があるため、断念すれば「再起動後に通知が一切来ることを誰にも知らせない」というPass 1 CRITICALのサイレント障害になる。加えて、停止中に発火時刻を過ぎた「取り逃した」トリガーの扱いは仕様に一切規定がない（§12.1項目8）。

**決定**:
1. **S-1**: `persistence/ExecutionScheduleStore`（契約）＋`SharedPreferencesExecutionScheduleStore`（実装）をPhase 5で新設する。Room（KSP必須、Kotlin 2.4.10との互換未検証）・DataStore（新規依存、かつ`BroadcastReceiver.onReceive`はコルーチン非対応で同期・短時間処理に構造的に不適合）を退け、SharedPreferencesの同期読みを採用する。保存するのは最小レコード（`ExecutionScheduleRecord`: `schemaVersion`／`planId`／`eventStartEpochMillis`／`estimatedArrivalEpochMillis`／`triggers: List<Trigger>`）のみとし、イベントタイトル・住所・座標は一切含めない（PIIゼロ、§58/§60）。仕様の明示記述（Room）からの逸脱にあたるため**ADR記録トリガー③「仕様推奨からの逸脱」**として本項を記録する（メモ§2原文はトリガー②「仕様未定義箇所の補完」と表記していたが、メモ§10ユーザー確認事項1はトリガー③としており、メモ内で表記が割れている。`docs/plans/phase5-notification-execution.md`§4.1脚注1のとおり、本書はFable 5裁定に従いトリガー③を採用する）。Phase 10でRoomへ吸収する場合もinterface契約は不変（実装差し替え1点）とする。
2. **S-9**: 復元時に`triggerAt <= now`のトリガーは、(a) `now`がイベント開始時刻より前かつ経過が猶予（既定15分）以内なら即時発火し、(b) それ以外は発火せず破棄してExecution画面に「一部の通知を逃しました」を表示する。無条件即時発火は、電源を切って翌日起動したユーザーに古い出発通知を投げる事故になるため却下する。猶予値は仕様未定義プレースホルダとして`NotificationDefaults`へ隔離する（ADR-0015が確立した「仕様未定義の既定値はDefaultsオブジェクトへ隔離する」パターンの踏襲）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 仕様の字面どおりRoomを導入する | KSP導入が必須で、Kotlin 2.4.10×KSP 2.3.11の互換が未検証（M5-4/M5-5）。Phase 5の主題（exact alarm／FGS）に対して不釣り合いなリスクであり、ADR-0014がAGP 9引き上げを却下したのと同じ「Phase本題に不釣り合い」論理が当てはまる |
| DataStore（`androidx.datastore:datastore-preferences`）を導入する | 新規依存であることに加え、`BroadcastReceiver.onReceive`はコルーチン非対応（`goAsync()`か`runBlocking`が要る）ため、boot復元という同期・短時間処理にはSharedPreferencesの同期読みのほうが構造的に適合する |
| boot再登録データの永続化自体を断念する | §69が明示的にPhase 5成果物として列挙し§95.6に専用行がある機能を欠くことになり、「再起動後に通知が一切来ないことを誰にも知らせない」Pass 1 CRITICALのサイレント障害になるため不可 |
| 取り逃したトリガーを無条件で即時発火する | 電源を切って翌日起動したユーザーに古い出発通知を投げる事故になる（S-9却下根拠） |
| 取り逃したトリガーを無条件で破棄する（猶予判定なし） | 短時間のバックグラウンド制限等で本来ユーザーに届くべきだった直近の通知まで一律に握り潰すことになり、§95.6が要求する「通知の到達」を不必要に犠牲にする |

**影響範囲**: `persistence/ExecutionScheduleStore.kt`・`SharedPreferencesExecutionScheduleStore.kt`（新規、契約はP5-C1で宣言済み・実装はP5-C3）・`services/notification/NotificationDefaults.kt`（新規、猶予値の隔離）・`services/notification/ScheduleRestoreReceiver.kt`（S-9の復元判定ロジック、実装はP5-C3）。
**検証方法**: 契約scaffoldのコンパイル成功をP5-C1で実測済み（`build/agent-logs/p5c1-compile.log`）。S-1のラウンドトリップ・PIIゼロ・schemaVersion不一致時の破棄はT-STORE-1〜8（`persistence/ExecutionScheduleStoreTest.kt`、P5-C2でRed作成済み）、S-9の猶予復元判定はT-BOOT-1〜7（`services/notification/ScheduleRestoreReceiverTest.kt`、P5-C2でRed作成済み）で、いずれもP5-C3のGreen実装時に固定する。
**再検討トリガー**: Phase 10（§74）着手時、Room導入への吸収要否を再判定する。猶予値（既定15分）が実運用で不適切と判明した場合。

---

### ADR-0026: Foreground Serviceのtype宣言と位置権限なし時の運用（S-3）、およびexact alarm許可誘導の強度（S-4）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定S-3・S-4、計画書§1・§4.1・§4.2・§7.4・§9・§12.2。P5-P1・P5-P2実機実測により追加確認済み、`build/agent-logs/p5-probes-device.md`） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画メモ§2） ／ 関連仕様§: §95.1(b)・§95.4・§95.5・§95.6（S-3＝記録トリガー②仕様未定義箇所の補完・記録トリガー⑤権限に関わる変更、S-4＝記録トリガー②仕様未定義箇所の補完・記録トリガー⑤権限に関わる変更）

**背景**: §95.4は「FOREGROUND_SERVICE（+用途別type、例：FOREGROUND_SERVICE_LOCATION等）」と例示にとどまり型を確定していない。§95.1(b)は「location typeのFGSはフォアグラウンド中に開始した場合のみ位置アクセスを継続できる」と明記する。一方§95.1は`SCHEDULE_EXACT_ALARM`と`USE_EXACT_ALARM`の両方を挙げつつ「`USE_EXACT_ALARM`はカレンダー/アラームアプリに限定される特別権限」と注記しており、本アプリが該当するかが未定義である。

**決定**:
1. **S-3（FGS type）**: `android:foregroundServiceType="location"`単独宣言を採用し、位置権限が許可されているときのみFGSを起動する。位置権限が拒否されている場合はFGSを起動せず、exact alarm＋通知のみで動作を継続し、精度低下（Doze保護を受けられない残存リスク）を画面に明示する（`ExecutionServiceController.start()`が`Degraded(FOREGROUND_SERVICE_UNAVAILABLE)`を返す設計。エラー&レスキューマップ#5）。却下案は`specialUse`（Play審査での用途正当化が必要）・`dataSync`（用途と宣言の不一致、Android 15で6/24時間制限）・`shortService`（3分上限で不成立）。
2. **S-4（USE_EXACT_ALARM不採用）**: `SCHEDULE_EXACT_ALARM`のみ宣言し`USE_EXACT_ALARM`は宣言しない。本アプリはアラームクロック/カレンダーアプリそのものではなく、誤宣言はPlay審査での拒否リスク（§95.5）を負う。帰結として、targetSdk 35（≥33）のため`SCHEDULE_EXACT_ALARM`は新規インストール時に既定で不許可となり、**inexactフォールバックが例外パスではなく既定パスになる**ことを製品として受け入れる。許可誘導はバナー＋ワンタップ導線の中強度とし、ブロッキング（モーダルで進行不能にする等）は禁止する。

**実機実測による裏付け（P5-C1、`build/agent-logs/p5-probes-device.md`、2026-08-09実施）**: 本裁定後に実施された実機プローブ（API 35実機、AVD `actionstarter_test`）により、以下が確認された。(a) `canScheduleExactAlarms()`は本番`com.actionstarter`（無改変）・テストAPK baseline・`SCHEDULE_EXACT_ALARM`追加宣言・`USE_EXACT_ALARM`追加宣言の4パターンすべてで`false`——「宣言していないからfalse」ではなく「宣言してもfalseのまま」であり、S-4のinexactフォールバック必須判断をより強固に裏付けた。(b) 位置権限なしで`FOREGROUND_SERVICE_TYPE_LOCATION`起動を試みると`java.lang.SecurityException`（メッセージ要旨: `Starting FGS with type location ... requires permissions: all of the permissions allOf=true [android.permission.FOREGROUND_SERVICE_LOCATION] any of the permissions allOf=false [android.permission.ACCESS_COARSE_LOCATION, android.permission.ACCESS_FINE_LOCATION] ...`）が発生し、S-3「位置権限なし時はFGSを起動しないDegraded運用」の設計と整合することを実機で確認した。(c) manifestでtype宣言済みのServiceに`FOREGROUND_SERVICE_TYPE_NONE`を渡すと、計画書の当初仮説（`IllegalArgumentException`の可能性）とは異なり`android.app.InvalidForegroundServiceTypeException`（`ForegroundServiceTypeException`→`ServiceStartNotAllowedException`→`IllegalStateException`の子孫）が発生することが判明した。`RuntimeException`の子孫である点は変わらず、エラー&レスキューマップ#6の「catchして...握り潰さない」設計はそのまま成立するが、例外クラスで分岐する場合は`IllegalArgumentException`ではなく`InvalidForegroundServiceTypeException`系を参照する必要がある。(d) `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`は`package:`データURI付きでアプリ個別設定画面へ実機で問題なく解決する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `specialUse`（`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`） | Play審査での用途正当化が必要（§95.5の審査リスク増）。全ユーザーを保護できる利点はあるが、S-3裁定は位置権限なしユーザーの残存リスク受容を優先した |
| `dataSync` | 用途と宣言が一致しない（Play審査リスク）。Android 15で6時間/24時間の実行時間制限があり、Execution中の継続利用に不向き |
| `shortService` | 3分の上限があり、Executionフェーズ全体をカバーできず不成立 |
| `USE_EXACT_ALARM`を宣言する | 実機実測（(a)）により`granted=true`表示にもかかわらず`canScheduleExactAlarms()`はfalseのままであることを確認しており、宣言による実益がない。加えて本アプリのカテゴリはPlay審査上カレンダー/アラームアプリに該当せず拒否リスクを負う |
| 許可誘導をブロッキング（モーダルで進行不能）にする | inexactフォールバックが既定パスである以上、通知自体は届く。ブロッキングは§28 One Action原則（1アクションのみに集中させる）と相容れず、ユーザーを不必要に足止めする |

**影響範囲**: `AndroidManifest.xml`（`<service android:foregroundServiceType="location">`宣言、統合ウィンドウP5-C6で追加）・`services/execution/ExecutionForegroundService.kt`／`ExecutionServiceController.kt`（S-3実装、契約はP5-C1で宣言済み）・`services/notification/AlarmManagerAlarmScheduler.kt`（S-4のinexactフォールバック実装、契約はP5-C1で宣言済み・シームはP5-C2bで追加）・Execution画面の劣化バナー（`ExecutionUiState`の`isExactAlarmDegraded`／`isForegroundServiceDegraded`フィールド、P5-C2bで追加）。
**検証方法**: S-3はT-FGS-1〜6（`services/execution/ExecutionForegroundServiceTest.kt`、P5-C2bでRed作成）・S-4はT-ALARM-1〜2（`services/notification/AlarmSchedulingTest.kt`、P5-C2でRed作成済み）で固定する。実機実測は`build/agent-logs/p5-probes-device.md`（P5-P1・P5-P2）参照。
**再検討トリガー**: Google Play審査ポリシーが`specialUse`の要件を緩和した場合。位置権限なしユーザーの残存Dozeリスクが実運用上許容できないと判明した場合。

---

### ADR-0027: 通知本文の文言解決経路の非Compose化（S-8）とSnooze量の単一情報源維持（S-6）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定S-6・S-8、計画書§1補足・§4.1・§4.2・§6.1・§7.3） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画メモ§2） ／ 関連仕様§: §7（UI文字列ハードコード禁止）・§69（Snooze）・§89（重複実装の禁止）（S-8＝記録トリガー①interface契約の変更、S-6＝記録トリガー②仕様未定義箇所の補完）

**背景**: ADR-0018は`semanticId → stringResource`の解決をUI層（`features/common/StepTitle.kt`の`resolveStepTitle`）に置いたが、この関数は`@Composable`限定であり、通知本文の組み立て（Composeの外＝`services/notification/`）から呼び出せない（M5-16補足の実測）。§89「No duplicated domain logic」に反せずに再利用するには非Composeの解決経路が必要である。一方、§69は"Snooze"とだけ書き量を定めず、既存`ExecutionViewModel.POSTPONE_DURATION`が唯一の手がかりである。

**決定**:
1. **S-8**: `semanticId → @StringRes Int`の対応表を非Composeの中立パッケージ`i18n/StepTitleKeys.kt`へ抽出する。`features/common/StepTitle.kt`（Compose層）と通知本文ビルダ`services/notification/NotificationContentBuilder.kt`の双方がこれを参照する。`features/common/StepTitle.kt`は本抽出後、1行委譲化のみを行う（統合ウィンドウP5-C6扱い）。これにより複製実装（§89違反）と`services/ → features/`の層逆転の双方を避ける。
2. **S-6**: §27のUI表記"[5 min later]"に合わせ5分固定とし、既存定数`ExecutionViewModel.POSTPONE_DURATION`を単一の出所として維持する（ADR-0015が確立した「仕様未定義の既定値はDefaultsオブジェクト等へ隔離し複数箇所に値を重複させない」パターンと同じ扱い）。新しい定数を`NotificationDefaults`等に重複定義しない。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `features/common/StepTitle.kt`の`resolveStepTitle`を`@Composable`のまま`services/notification/`から直接呼ぶ | コンパイル不能（`@Composable`関数は`@Composable`コンテキストからしか呼べない）。仮に呼べたとしても`services/ → features/`という層逆転（下位層が上位UI層に依存する設計崩れ）を招く |
| 通知本文用に文言解決ロジックを`services/notification/`側へ複製する | §89「No duplicated domain logic」に反する。将来`semanticId`とstring resourceの対応が変更された際、2箇所を同期させ忘れるサイレントな不整合リスクを負う |
| Snoozeの量を通知側で独自に再定義する（例: `NotificationDefaults`に別の5分値を追加） | `ExecutionViewModel.POSTPONE_DURATION`と値が重複し、片方だけ変更されると挙動が画面と通知で食い違うサイレント障害になる。単一の出所を維持する方針（S-6推奨根拠）に反する |

**影響範囲**: `i18n/StepTitleKeys.kt`（新規、ui-implementer担当）・`features/common/StepTitle.kt`（1行委譲化、統合ウィンドウ）・`services/notification/NotificationContentBuilder.kt`（新規、`StepTitleKeys`参照）。`ExecutionViewModel.POSTPONE_DURATION`は変更しない（既存の唯一の出所のまま）。
**検証方法**: S-8はT-NOTIF-1〜2（通知文言がstring resource由来であることの検証、P5-C2でRed作成済み）で固定する。S-6はT-P5UI-3（「5 min later」でのアラーム再登録、P5-C2bでRed作成）で固定する。
**再検討トリガー**: `features/common/StepTitle.kt`の抽出後、Compose側テスト（`ExecutionScreenTest`等）に回帰が生じた場合。Snoozeの量が製品要件として可変化する場合（現状は固定値のみ）。

---

### ADR-0028: Execution One Action多段階前進に伴う`ExecutionViewModel`のコンストラクタ契約変更（R-3）と「next action」の解釈（S-7）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定R-3・S-7、計画書§4.1・§4.2・§7.2・§11 R-3・§12.2。TEAMS§5「interface契約のバージョン付き変更経路」に基づく必須ADR記録） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画メモ§8） ／ 関連仕様§: §27・§28（One Action多段階前進）・§62（通知3種の閉じた集合）（記録トリガー①interface契約の変更）

**背景**: F58（Execution One Actionの多段階前進、Done→次ステップの本番結線）はM5-14が記録した既知の制限（`ActionStarterNavHost`が`ExecutionViewModel`を経由せず`SharedPlanViewModel.confirmedPlan`から直接`ExecutionUiState`を構築し、`onDone = null`を渡す設計）を解消する§27／§28の核心機能である。しかし既存テスト（`ExecutionViewModelTest`／`ExecutionScreenTest`は`SavedStateHandle`のみのコンストラクタに束縛。`NavigationFlowTest`のT-NAV-1／T-NAV-3は「Doneタップ1回でExecutionから離脱する」ことを前提にUI操作列を組んでいる）が、この前提と正面から衝突する。加えて§69「next action」が通知種別を指すのかアプリ内遷移を指すのか仕様上未定義であり（§62は通知を3種に固定しているため「next action通知」と解釈すると矛盾する）、この解釈がF58の設計範囲を左右する。

**決定**:
1. **S-7**: 「next action」はアプリ内のOne Action前進（§27/§28）と解釈し、専用の通知種別は作らない。§62「通知を増やすアプリにしない／通知疲れを避ける」との整合を優先した解釈であり、自己補完ではなくFable 5裁定として明記する。この解釈により、F58の実装はもっぱら`ExecutionViewModel`／`ExecutionUiState`の拡張で完結し、`NotificationKind`（§62の3種閉じた集合）へ4番目の値を追加する必要はない。
2. **R-3**: TEAMS§5「interface契約のバージョン付き変更経路」（変更提案→android-planner影響分析→Fable 5承認→ADR記録→両側テスト更新）を発動し、`ExecutionViewModel`のコンストラクタへ新引数（`sharedPlanViewModel: SharedPlanViewModel?`／`notificationService: NotificationService?`／`permissionGate: PermissionGate?`）を追加する契約変更を承認する。既存テスト（`SavedStateHandle`のみで構築する`ExecutionViewModelTest`／`ExecutionScreenTest`）を壊さないため、**新引数はすべてデフォルト値`null`付きで追加し、`null`時は現行プレースホルダ挙動（3ステップ固定・Snoozeはメモリ上+5分のみ）を維持する**。`T-NAV-1`／`T-NAV-3`の期待値更新（「Doneタップ1回で離脱」から「多段階Done後に離脱」への書き換え）自体は承認するが、実施タイミングは本ADRの決定に含める（下記参照）。テストを回避するためのハードコードや特殊分岐は禁止する。

**NavigationFlowTest期待値更新のタイミング（Fable 5裁定2026-08-09、P5-C2差し戻し対応）**: `NavigationFlowTest`（T-NAV-1/T-NAV-3）の期待値更新はNavHost実配線と同時（P5-C6統合ウィンドウ）に行う——早期更新は既存クラスへ意図的Redを持ち込み全レーンの回帰判定を汚染するため（Fable 5裁定2026-08-09）。本サイクル（P5-C2b、`ExecutionViewModel`／`ExecutionUiState`のscaffold拡張のみを行う段階）では`NavigationFlowTest`を一切変更しない。`ActionStarterNavHost`は本サイクルでも引き続き`SharedPlanViewModel.confirmedPlan`から直接`ExecutionUiState`を構築する現行設計のままであり（M5-14の結線は本サイクルでは変更しない）、T-NAV-1／T-NAV-3は現行アサーション（Doneタップ1回で離脱）のままGreenを維持する（本サイクルでの実測: `build/agent-logs/p5c2b-scaffold.log`）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `ExecutionViewModel`のコンストラクタを非nullable必須引数へ変更する | `ExecutionViewModelTest`／`ExecutionScreenTest`（`SavedStateHandle`のみで構築）を即座に壊す。TEAMS§5の契約変更経路は「両側テスト更新」を要求するが、更新前に破壊するのは経路の趣旨に反する |
| `NavigationFlowTest`のT-NAV-1／T-NAV-3の期待値を本サイクル（P5-C2b）で先行更新する | `ActionStarterNavHost`の実配線（M5-14の結線変更）はP5-C6統合ウィンドウまで行わないため、先行更新すると「本番結線が伴わない意図的Red」を`NavigationFlowTest`という既存の安定クラスへ持ち込むことになり、P5-C2b／P5-C3／P5-C4等、並行する全レーンの回帰判定（既存クラスは全てGreen維持という判定基準）を汚染する |
| 「next action」を新しい通知種別として実装する | §62が通知を3種（TRANSITION_START/DEPARTURE/RECOVERY）に固定しており矛盾する。§62「通知を増やすアプリにしない」の精神にも反する |

**影響範囲**: `features/execution/ExecutionViewModel.kt`・`ExecutionUiState.kt`（P5-C2bでscaffold拡張。新引数・新フィールドはすべてデフォルト値付き）。`navigation/ActionStarterNavHost.kt`・`test/java/com/actionstarter/navigation/NavigationFlowTest.kt`は本サイクルでは変更しない（P5-C6統合ウィンドウで同時実施）。
**検証方法**: 本サイクルでの検証は、拡張後も既存`ExecutionViewModelTest`／`ExecutionScreenTest`／`NavigationFlowTest`がGreen維持であることを`./gradlew :app:testDebugUnitTest --rerun`で実測することにより行う（`build/agent-logs/p5c2b-scaffold.log`）。新引数を用いた多段階遷移の振る舞い自体はT-P5UI-1〜8（`features/ExecutionOneActionTest.kt`、P5-C2bでRed作成）がP5-C3のGreen実装時に固定する。
**再検討トリガー**: P5-C6統合ウィンドウでの実配線時、`ExecutionViewModel`の新引数だけでは表現不能な追加要件が判明した場合。`NavigationFlowTest`の期待値更新自体がP5-C6で新たな設計課題を提起した場合。

---

### ADR-0029: Routes APIルート0件応答形状とDRIVEのTRAFFIC_AWARE

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定、curl実測2026-08-09。本番同一FieldMask`routes.duration`使用） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P3-C9） ／ 関連仕様§: 計画書`docs/plans/phase3-routing-location.md`§7.2・§12.1（P3-C8fixで「新規の第3の欠陥（暫定名: P3-C9候補）」として申し送られた事項の解消。記録トリガー③実装中に発覚した仕様/実装ギャップの記録）
- **ADR番号の付番根拠**: `DECISIONS.md`の実測最新確定ADRはADR-0023だが、`docs/plans/phase5-notification-execution.md`が本Phase 5の決定をADR-0024〜0028として記録する想定であることを事前に`grep`で確認した（予約済み）。「予約と衝突するなら更に+1へずらす」規則に従い、0024〜0028を避けた最小の空き番号としてADR-0029を採番した。

**背景**: P3-C8fixのT-OPTIN-1実行（TRANSIT・東京タワー→明治神宮）で`RoutingException$MalformedResponse: Unparsable response`（cause=`response JSON has no top-level "routes" array`）が発生し、HTTPステータス200かつ`routes`キー不在の有効なJSONオブジェクトが返っていることをスタックトレースから確認した（P3-C8fix §12.1行）。同サイクルは「proto3 JSON既定のフィールド省略規則により有効経路0件時は`routes`キー自体が省略される可能性」を未検証の仮説として申し送り、修正はスコープ外（変更許可ファイル外）としていた。本サイクルでFable 5がcurlにより本番同一のFieldMask（`routes.duration`）を使って実測し、以下4点を確定した：(1) TRANSIT・東京タワー(35.6586,139.7454)→明治神宮(35.6595,139.7005) → HTTP 200・body`{}`（`routes`キー自体が省略される。Google Routes APIは日本の公共交通データを提供していない）。(2) DRIVE + departureTime（routingPreference未指定＝既定`TRAFFIC_UNAWARE`） → HTTP 400 `"Timestamp cannot be set for TRAFFIC_UNAWARE routing mode."`（現行実装はDRIVEが常に失敗する未発見の別欠陥だったと判明）。(3) DRIVE + departureTime + `"routingPreference":"TRAFFIC_AWARE"` → HTTP 200・duration`"1045s"`。(4) WALK/BICYCLE + departureTime → HTTP 200（`"4158s"`／`"1400s"`、routingPreferenceなしで正常）。

**決定**:
1. **`RoutesApiResponseParser`**: レスポンスが「JSONオブジェクトだが`routes`キーが無い」場合を`RoutingException.NoRoute`へマップする（従来は`MalformedResponse`だった誤分類の修正）。レスポンスroot自体が配列・文字列などオブジェクト以外の場合は従来どおり`MalformedResponse`を維持する（レスポンス形状そのものが想定と異なるため）。`"routes":[]`（空配列）→`NoRoute`は既存挙動のまま変更しない。
2. **`RoutesApiRequestBuilder`**: `travelMode`が`DRIVE`の場合のみ`"routingPreference":"TRAFFIC_AWARE"`をリクエストbodyへ追加する。WALK／BICYCLE／TRANSITには付与しない（ドキュメント記載どおりAPIがDRIVE系以外へのrouting Preference指定を拒否するため、かつ実測でも不要）。`departureTime`は全モードで維持する（本アプリは未来出発時刻のETAがコア機能のため）。
3. **T-OPTIN-1の前提修正**: `RoutesApiLiveTest.kt`のmodeをTRANSIT→WALKへ変更する。TRANSITは対象座標間で構造的にHTTP 200・有効経路0件（上記(1)）を返すため、「正のDurationを確認する」というT-OPTIN-1本来の役割（計画書§9.9）を果たせない。WALKは実測(4)によりHTTP 200・正のdurationが確認されており、正常系検証の役割を引き継げる。
4. **T-OPTIN-2の新設**: 同ファイルへ、TRANSIT・同座標で`RoutingException.NoRoute`が送出されることを検証するopt-inテストを追加する。「日本TRANSIT＝API非提供→NoRoute縮退」という(1)の実測結果を回帰ロックとして固定する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| rootの型を問わず「`routes`キーが見つからない」すべてのケースを`NoRoute`とする | レスポンスrootが配列・文字列等の完全に異なる形状で返った場合（サーバ側の重大な破損応答等）まで「経路が無い」と誤診断し、真の構造異常を握り潰すリスクを負う。実測（(1)）で確認できたのは「オブジェクトroot＋キー欠落」の1パターンのみであり、確定した実測範囲に限定してマップする |
| 全`travelMode`に一律で`routingPreference:TRAFFIC_AWARE`を付与する | ドキュメント記載（`RoutesApiRequestBuilder.kt`KDoc既存記述）どおり`TRAFFIC_AWARE`はDRIVE／TWO_WHEELERの場合のみ指定可であり、それ以外は失敗する旨が明記されている。実測でもWALK/BICYCLEはrouting Preference**なし**でHTTP 200を確認したのみであり、あえて未検証の指定を全モードに広げる理由がない |
| `departureTime`の送信自体をDRIVE以外で省略し、DRIVEのTRAFFIC_UNAWARE 400を回避する | 未来出発時刻のETAが本アプリのコア機能（計画書§7.2既存記述）であり、`departureTime`省略は全モードで主要機能を損なう。根本原因（TRAFFIC_UNAWAREとdepartureTimeの組み合わせ拒否）はrouting Preference指定で解消できるため不要な代替 |
| T-OPTIN-1のmodeをTRANSITのまま残し、アサーションを「NoRouteが送出されること」の異常系検証へ転用する | T-OPTIN-1は計画書§9.9で「正常系・正のDuration確認」という役割が明示されている（テスト名`...ReturnsPositiveDuration`とも整合）。役割を転用するとテスト名・既存KDocとの不整合が生じる。モードを差し替えて正常系の役割を維持し、異常系はT-OPTIN-2として新設する方が既存の正常/異常の役割分担を崩さない |

**影響範囲**: `services/routing/RoutesApiResponseParser.kt`（`parse`のroutesキー欠落判定ロジック）・`services/routing/RoutesApiRequestBuilder.kt`（`build`のDRIVE限定routingPreference追加）・`services/routing/RoutesApiResponseParserTest.kt`（T-ROUTEPARSE-6〜8追加）・`services/routing/RoutesApiRequestBuilderTest.kt`（T-ROUTEREQ-4〜5追加）・`e2e/RoutesApiLiveTest.kt`（T-OPTIN-1のmode変更、T-OPTIN-2新設）。`RoutesApiRoutingService.kt`・`DepartureViewModel.kt`等の呼び出し側は変更していない（例外型の網羅whenで既に`NoRoute`／`MalformedResponse`双方をハンドリング済みのため、パーサ側のマップ先変更のみで呼び出し側は無改修）。

**検証方法**: 対象2クラスのJVMテスト（`RoutesApiResponseParserTest`・`RoutesApiRequestBuilderTest`）でRed（新規5件中3件が意図した期待値差分で失敗、ログ: `build/agent-logs/p3c9-red.log`）→Green（同2クラス13件全pass）を実測後、`:app:testDebugUnitTest --rerun`で全JVMスイート**245件・failures 0・errors 0・skipped 1**（`tCfg2`のみ、P3-C8fixの240件から新規5件の純増）を実測（`build/agent-logs/p3c9-green.log`）。実機（emulator-5554、`actionstarter_test` AVD）で`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.e2e.RoutesApiLiveTest`を実行し、T-OPTIN-1（WALK・正のDuration）／T-OPTIN-2（TRANSIT・NoRoute）とも生JUnit XML（`TEST-actionstarter_test(AVD) - 15-_app-.xml`）で`tests="2" failures="0" errors="0"`を確認した（ログ: `build/agent-logs/p3c9-optin.log`）。**付記**: 計画書§9.1が記載する`--tests`フィルタ構文はAGP 8.13.2の`DeviceProviderInstrumentTestTask`が対応しておらず（`gradlew help --task`実測で登録オプションは`--serial`／`--rerun`のみ）使用不可だったため、標準の`-Pandroid.testInstrumentationRunnerArguments.class=`プロパティ方式へ切り替えた。**付記2（次サイクル申し送り、未検証）**: 実機診断中に、`departureDate = Instant.now()`（バッファ0）をDRIVE/WALK系へ送るとネットワーク往復の間に過去時刻へずれ`"Timestamp must be set to a future time."`（HTTP 400）と競合しうることを新規に実測した（host/emulator/Google自身のサーバ時計が数秒以内で一致することを確認済みのため時計ズレが原因ではない）。TRANSITはバッファ0でもT-OPTIN-2がPASSしており競合しない（またはより緩い）可能性がある。`RoutesApiLiveTest.kt`にのみ2分バッファ（`FUTURE_DEPARTURE_BUFFER`）を追加して回避したが、本番`DepartureViewModel`が計算する`departureDate`がこの競合を実運用で踏むかは`DepartureViewModel.kt`が本サイクルの変更許可ファイル外のため未検証のまま残す。

**再検討トリガー**: Routes APIが将来`routes`キー省略以外の形状（例: 明示的なエラーオブジェクトを伴う0件応答）へ変更された場合。`TWO_WHEELER`モードを本プロジェクトへ追加する場合（DRIVEと同様`routingPreference:TRAFFIC_AWARE`要否の再検証が必要）。次サイクルで`DepartureViewModel`の実際の`departureDate`計算値が「現在時刻に極めて近い」ケース（例: 予定開始直前のETA再計算）を持つと判明し、上記付記2の競合が実運用で顕在化した場合。

---

### ADR-0030: Routes API departureTimeの未来時刻クランプ

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定。P3-C9実機診断で発見・本サイクルで実装） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P3-C10） ／ 関連仕様§: 計画書`docs/plans/phase3-routing-location.md`§12.1（ADR-0029付記2「実機診断中に…新規に実測した…本番`DepartureViewModel`が計算する`departureDate`がこの競合を実運用で踏むかは…未検証のまま残す」で申し送られた事項の解消）
- **ADR番号の付番根拠**: `DECISIONS.md`の実測最新確定ADRはADR-0029。`grep -n "ADR-00" DECISIONS.md docs/plans/phase5-notification-execution.md docs/plans/phase6-recovery-basic.md`を事前実行し、`docs/plans/phase5-notification-execution.md`が本Phase 5の決定をADR-0024〜0028として記録する想定（予約済み。ADR-0029自身の付番根拠と同一の制約）であること、`docs/plans/phase6-recovery-basic.md`は新規ADR範囲を予約しておらず実際の起票をP6-C5統合ウィンドウへ延期する方針（既存ADR-0003〜0024への言及はいずれも前提参照であり新規予約ではない）であることを確認した。ADR-0029自身が採った「予約と衝突するなら更に+1へずらす」規則に従い、ADR-0029の次の空き番号としてADR-0030を採番した（0024〜0028を再度スキップする必要はない。0029の時点で既にスキップ済みのため連続する0030が最小の空き番号）。

**背景**: P3-C9の実機診断（`RoutesApiLiveTest`）で、`departureDate = Instant.now()`（バッファ0）をWALK/DRIVE系で送信すると、ネットワーク往復＋サーバ側処理の間に送信時点の「now」がサーバ評価時点の「now」より過去へずれ、Routes APIがHTTP 400 `"Timestamp must be set to a future time."`（`INVALID_ARGUMENT`）を返す競合状態が実測確定した。host／emulator／Google自身のサーバ（`curl -I https://www.google.com`のDateヘッダ）の3者の時計比較により、環境の時計ズレが原因でないことも確認済み（TRANSITは有効経路0件の応答経路が本検証より先に短絡すると推測され、この検証を受けない）。P3-C9はこの競合を`RoutesApiLiveTest.kt`のopt-inテストのみ2分バッファ（`FUTURE_DEPARTURE_BUFFER`）で回避したが、本番`DepartureViewModel.estimateAndApplyRoute`（252〜258行目）は`departureDate = clock.instant()`（バッファ0）をそのまま`RoutingService.estimateRoute`へ渡す設計であるため、**本番のWALK/DRIVE/BICYCLE再計算がこの400を踏みうる**ことが本サイクルの調査でソース確認された。

**決定**:
1. **`RoutesApiRequestBuilder.build`へ`clock: java.time.Clock = Clock.systemUTC()`パラメータを追加**し、送信する`departureTime`を`max(departureTime, clock.instant() + 120秒)`へクランプする。120秒はC9実測（opt-inテストの`FUTURE_DEPARTURE_BUFFER`と同根拠）で十分性が確認された安全マージン。この対応はRoutes API固有の作法（FieldMask必須・DRIVE限定TRAFFIC_AWAREと同格）として**Routes APIアダプタ層（本ビルダー）の責務**と裁定した。
2. **`RoutesApiRoutingService`は変更しない**。`RoutesApiRequestBuilder.build`呼び出し（4引数の位置渡し）はデフォルト引数により無変更のまま動作することを実機・JVM双方で確認した。
3. **`DepartureViewModel`は変更しない**。`departureDate = clock.instant()`は「今すぐ出発する」という意味論として正しく、Routes API固有の時刻制約への適応を呼び出し元へ持ち込むべきではないため。
4. **`RoutesApiLiveTest.kt`にT-OPTIN-3を新設**し、WALK・`departureDate = Instant.now()`（バッファ0、本番と完全に同一条件）で正のDurationが得られることを実機で端到端検証する。既存T-OPTIN-1（2分バッファ・WALK）・T-OPTIN-2（TRANSIT→NoRoute）は無変更のまま回帰ロックとして維持する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `DepartureViewModel`側でバッファを加算してから`RoutingService`へ渡す | Routes API固有の実装都合（ネットワーク往復のレイテンシに対する安全マージン）がドメイン層のViewModelへ漏れ出す。`departureDate=now`という「今すぐ出発」の意味論自体は正しく、ViewModel側に欠陥はない。FieldMaskやDRIVE限定TRAFFIC_AWAREと同様、API側の作法はアダプタ（`RoutesApiRequestBuilder`／`RoutesApiRoutingService`）に閉じ込めるべき |
| `RoutesApiRoutingService`側でクランプする（`RoutesApiRequestBuilder`ではなく） | `RoutesApiRequestBuilder`は「送信する`departureTime`文字列を最終決定する」責務を既に持つ純粋関数（origin/destination/mode/departureTimeの写像＋DRIVE限定routingPreference付与）であり、クランプもその一部としてまとめる方が単一責任の観点で一貫する。`RoutesApiRoutingService`へ分散させると`departureTime`の最終値の決定箇所が2つに分裂し追跡しにくくなる |
| クランプ閾値を120秒よりさらに大きく（例: 5分）取る | C9実測で2分バッファがopt-in環境（エミュレータ〜実サーバ間RTT）で十分機能することを確認済みであり、根拠のない値の拡大は「未来すぎる出発時刻」によるETA精度の劣化という別のコストを生む。実測に基づく最小限の値を採用する |
| 過去日付一般（例: 2020年などの大きく過去の値）はクランプ対象から除外し、「現在時刻付近のみ」をクランプする特別分岐にする | `max(departureTime, clock.instant()+120秒)`という単純な数式のほうが分岐が無く実装・検証が容易で、かつ「過去日付は例外を投げず処理する」という既存契約（T-ROUTEREQ-3）の精神（バリデーションで拒否しない）とも整合する。特別分岐を設ける合理的理由が実測上見当たらない |

**影響範囲**: `services/routing/RoutesApiRequestBuilder.kt`（本番、`clock`パラメータ追加＋`clampToFuture`private関数新設＋KDoc）・`services/routing/RoutesApiRequestBuilderTest.kt`（T-ROUTEREQ-6・T-ROUTEREQ-7新設。既存T-ROUTEREQ-1・T-ROUTEREQ-3はクランプの影響を受けたため固定Clock注入へ更新——T-ROUTEREQ-1は非決定性回避のための最小修正（アサーション値は無変更）、T-ROUTEREQ-3は「過去日付は無加工で通過する」という旧契約から「クランプされた値が返る」という新契約への更新であり、テスト名も`build_withFarPastDepartureDate_clampsToClockPlus120Seconds`へ変更した）・`e2e/RoutesApiLiveTest.kt`（T-OPTIN-3新設＋クラスKDoc追記）。`RoutesApiRoutingService.kt`・`DepartureViewModel.kt`は無変更（呼び出しシグネチャ・意味論とも影響なし）。`CachingRoutingServiceTest.kt`・`RoutesApiRoutingServiceTest.kt`を含む他の全JVMテストファイルを`grep`で確認し、`RoutesApiRequestBuilder`／`RoutesApiRoutingService`への参照が無い、またはリクエストbodyの`departureTime`文字列を検証していないことを確認したため無影響。

**検証方法**: Red実測（`build/agent-logs/p3c10-red.log`）: `RoutesApiRequestBuilderTest.kt`へT-ROUTEREQ-6・T-ROUTEREQ-7（固定Clock注入）を追加した時点で、本番未実装の`clock`名前付き引数を参照するため`:app:compileDebugUnitTestKotlin`がコンパイルエラー（`No parameter with name 'clock' found`、2箇所）で失敗することを実測した（コンパイル不能という形のRed。Kotlinの単一コンパイル単位の性質上、同ファイル内の全テストが実行不能になる）。Green実装後、対象クラス単体を実行したところ当初7件中2件が失敗した（T-ROUTEREQ-1・T-ROUTEREQ-3。いずれも既定Clock＝実時刻を使う設計のため、固定Clockを注入していなかった既存テストの前提とクランプの新契約が衝突したもので、実装のバグではないと判断）。上記「影響範囲」記載のとおり該当2テストを新契約に整合するよう修正後、対象クラス7件全Green（`build/agent-logs/p3c10-green-target.log`、JUnit XML実測`tests="7" failures="0" errors="0" skipped="0"`）を確認した。`:app:testDebugUnitTest --rerun`で全JVMスイートを強制再実行し（`build/agent-logs/p3c10-green.log`）、JUnit XML集計で**247件・failures 0・errors 0・skipped 1**（`tCfg2_apiKeyEmpty_...`のみ、既知の想定skip）を実測。P3-C9の245件から**+2＝新規追加2件（T-ROUTEREQ-6・7）のみの純増**（T-ROUTEREQ-1・3は既存2件の書き換えのため件数の増減なし）で回帰なしを確認した。実機（emulator-5554、`actionstarter_test` AVD、事前`adb devices`で`device`状態を確認済み・起動不要）で`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.e2e.RoutesApiLiveTest`を実行し（`build/agent-logs/p3c10-optin.log`）、生JUnit XML（`TEST-actionstarter_test(AVD) - 15-_app-.xml`）で**T-OPTIN-1・T-OPTIN-2・T-OPTIN-3の3件とも`tests="3" failures="0" errors="0" skipped="0"`**を確認した。T-OPTIN-3（WALK・バッファ0・本番と完全同一条件）のPASSにより、ADR-0029付記2が申し送った「本番`DepartureViewModel`経路でこの競合が顕在化するかは未検証」という懸念が実機で解消されたことを端到端で実証した。

**再検討トリガー**: 実機診断で120秒のクランプ閾値でもなお`"Timestamp must be set to a future time."`（HTTP 400）が発生した場合（RTTが想定を超えて悪化する環境）。`TWO_WHEELER`モードを本プロジェクトへ追加する場合（同種の時刻検証挙動を持つか未検証のため再確認要）。Routes APIの`departureTime`検証仕様が将来変更された場合（例: 猶予期間の撤廃・拡大）。

---

### ADR-0031: P5-C6統合ウィンドウ — 通知文言の暫定差し替え経路、Execution終了時のみのアラーム取消、通知タップ結線の設計

- 日付: 2026-08-09 ／ ステータス: 承認済み（domain-implementer起票・統合ウィンドウの実装判断） ／ 決定者: domain-implementer（P5-C6） ／ 起案agent: domain-implementer（P5-C6） ／ 関連仕様§: §62・§29・§27-28・§9エラーマップ#17/#18（記録トリガー②仕様未定義箇所の補完）
- **ADR番号の付番根拠**: `grep -n "ADR-00" DECISIONS.md`実測により実測最新確定ADRはADR-0030（本ウィンドウ着手時点）であることを確認した。`docs/plans/phase5-notification-execution.md`／`docs/plans/phase6-recovery-basic.md`のいずれも本番号を新規に予約していないことを確認済みのため、次の空き番号としてADR-0031を採番した。

**背景**: P5-C6（統合ウィンドウ）では、計画書§10.6申し送り・タスク指示に基づき3点の設計判断を行った。いずれも計画書・既存ADRが具体的な実装方式まで指定していなかった（仕様未定義箇所の補完）ため、ここに記録する。

1. **通知文言の暫定差し替え経路**: ADR-0027（S-8）は`semanticId → @StringRes Int`の解決を非Composeの中立パッケージ`i18n.StepTitleKeys`（ui-implementer・P5-C4成果物）経由に一本化する設計を定めたが、本ウィンドウ着手時点で`grep -rn "StepTitleKeys" app/src/main`を実測したところ該当ファイルは存在しなかった（P5-C4がこの成果物を作成しないまま完了していた）。一方でタスク指示は`NotificationContentBuilder`／`AndroidNotificationService`が借用していたDeparture/StepTitle文言（`departure_title`等・`step_title_transition`・`execution_now_label`）を専用文言へ差し替えることを求めていた。
2. **Execution終了時のアラーム取消・FGS停止の呼び出し位置**: §10.6申し送りは「Execution画面の`onNavigateToDeparture`／画面破棄相当の箇所で`notificationService.cancelAll(planId)`・`executionServiceController.stop()`を呼ぶ」とのみ記載し、具体的な呼び出し位置（`onNavigateToDeparture`のみか、Composable disposal全般か）を確定していなかった。
3. **通知タップ→アプリ内route解決の状態受け渡し方式**: 計画書は「`MainActivity`の`onNewIntent`でextra `"route"`を解決しNavHostへ誘導」とのみ記載し、`onNewIntent`（Composeの外）から`ActionStarterNavHost`（Composable）へどう値を橋渡しするかの具体的な機構を指定していなかった。

**決定**:
1. `i18n.StepTitleKeys`経由への一本化は据え置き、`values/strings.xml`・`values-ja/strings.xml`へ通知専用の`notification_*`接頭辞キー（チャネル名/説明×3種＝6キー、TRANSITION_START/DEPARTUREのタイトル・本文用ラベル＝7キー、RECOVERYのタイトル/本文＝2キー、計15キー、ja/en対）を新設し、`NotificationContentBuilder`（title/text）・`AndroidNotificationService`（`channelNameFor`・新設`channelDescriptionFor`）を専用文言参照へ差し替えた。`i18n.StepTitleKeys`が将来作成された時点でTRANSITION_START分岐のみ差し替える形の申し送り事項とする（両ファイルのKDocに明記済み）。ExecutionScreen側の`isExactAlarmDegraded`等の劣化バナー文言は、`ExecutionScreen.kt`がP5-C6の変更許可ファイル外（ui-implementer領域）であり実際の描画コンシューマが存在しないため、本ウィンドウでは追加しなかった（§88「空プレースホルダ禁止」に照らし、参照先のないstring resourceを追加しない判断。P6-C5または次のui-implementerサイクルへの申し送り）。
2. `notificationService.cancelAll(planId)`・`executionServiceController.stop()`は`ExecutionScreen`の`onNavigateToDeparture`コールバック内でのみ呼ぶ。`ActionStarterNavHost`の`composable(Destinations.Execution.route)`ブロック全体に対する汎用`DisposableEffect(onDispose)`には紐付けない。理由: Recovery割込（「Simulate delay (debug)」ボタン起点の`onNavigateToRecovery`）はComposeのNavigation機構上、execution routeのcomposableを一時的に離れる（disposeされる）が、これはExecution中断ではなくExecution継続中の一時的な迂回である。disposal全般に紐付けると、Recovery画面を表示するたびに正当な（まだ有効な）アラーム・FGSを誤って取り消してしまう（サイレントな機能後退）。
3. `MainActivity`に`private var pendingNotificationRoute by mutableStateOf<String?>(null)`を保持し、`onCreate`（初回Intent）・`onNewIntent`（`singleTop`経由の再配送）の両方でIntent extra（キー`"route"`）から更新する。`ActionStarterNavHost`は`pendingNotificationRoute: String? = null`・`onPendingNotificationRouteConsumed: () -> Unit = {}`を新規パラメータ（既定値付き）として受け取り、`LaunchedEffect(pendingNotificationRoute)`で消費する（消費後は`onPendingNotificationRouteConsumed()`で`MainActivity`側をnullへ戻し、再コンポーズ時の再発火を防ぐ）。両パラメータを既定値付きにしたことで、`NavigationFlowTest`等の既存呼び出し元（`ActionStarterNavHost()`をゼロ引数で呼ぶ）は無変更のまま成立する。未知route・欠落は`Destinations.Execution.route`へフォールバックする（エラー&レスキューマップ#18、既存のT-NAV-4ガードへ合流）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `i18n.StepTitleKeys`を本ウィンドウで新規作成し正式経路を完成させる | `i18n/`・`features/common/StepTitle.kt`はP5-C6のタスク指示（AndroidManifest.xml／AppContainer.kt／ActionStarterNavHost.kt／strings.xml排他所有）に含まれずui-implementer領域であり、他agentの担当領域へ無許可で越境することになる（`docs/TEAMS.md`§5の所有権原則） |
| `cancelAll`／`stop`を`composable`全体の`DisposableEffect(onDispose)`に紐付ける | Recovery割込のたびに正当なアラーム・FGSを取り消してしまう回帰を生む（上記「決定」2参照）。実機テストでは検知しづらい種類のサイレント障害になるため却下 |
| 通知タップの状態を`SharedPlanViewModel`等の既存ViewModelへ持たせる | 通知タップはActivityレベルのIntentイベントであり、Activityより広いスコープを持つ`ViewModelStoreOwner`側の責務ではない。`MainActivity`が直接保持しComposableへパラメータとして渡す方が責務が明確（`NavHost`自体が`NavController`を保持し外部へ公開しない既存の疎結合規約とも整合する） |

**影響範囲**: `app/src/main/res/values/strings.xml`・`values-ja/strings.xml`（`notification_*`15キー追加）・`services/notification/NotificationContentBuilder.kt`・`services/notification/AndroidNotificationService.kt`（`channelNameFor`差し替え・`channelDescriptionFor`新設）・`navigation/ActionStarterNavHost.kt`（execution route内の`cancelAll`/`stop`呼び出し位置、`pendingNotificationRoute`パラメータ）・`MainActivity.kt`（`onNewIntent`・`pendingNotificationRoute`状態）。
**検証方法**: `StringResourceParityTest`（T-I18N-1〜3）3/3 Green実測（キー集合一致・フォーマット引数一致・空文字列なしを新規15キー込みで確認）。`AndroidNotificationServiceTest`（T-NOTIF-1〜8）8/8 Green維持（文言の借用元変更後も非空・可変性の検証は影響を受けないことを実測）。`NavigationFlowTest`（T-NAV-1〜5）5/5 Green実測（`pendingNotificationRoute`が既定`null`のため通知タップ関連コードパスは非発火のまま既存5シナリオが成立することを確認）。全JVMスイート`:app:testDebugUnitTest --rerun`364 tests・failures 2（`AppContainerTest`のtP6Di1/tP6Di2のみ、P6-C5待ちの既知Red）・skipped 1（`build/agent-logs/p5c6-full.log`）。
**再検討トリガー**: `i18n.StepTitleKeys`がui-implementerにより作成された場合、`NotificationContentBuilder`のTRANSITION_START分岐をそれ経由へ差し替える。`ExecutionScreen.kt`が劣化バナー（`isExactAlarmDegraded`等）を描画するようになった時点で、専用string resourceの要否を再判定する。Phase 6のRecovery発火経路実装（P6-C5）でExecution→Recovery→Execution以外の新しい遷移経路が追加された場合、`cancelAll`/`stop`の呼び出し位置設計を再確認する。

---

### ADR-0032: Recovery候補の生成規則と優先順位を§31〜§33由来の全順序規則として確定する（S-6）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定・`docs/plans/phase6-recovery-basic.md`§7.9起票候補1） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P6-C1 scaffold KDocへ内容記録、P6-C5統合ウィンドウで正式起票） ／ 関連仕様§: §31・§32・§33（記録トリガー②仕様未定義箇所の補完、S-6）
- **ADR番号の付番根拠**: `grep -n "^### ADR-" DECISIONS.md`実測により実測最新確定ADRはADR-0031であることを確認した。`grep -n "ADR-00[3-9][0-9]" docs/plans/phase5-notification-execution.md docs/plans/phase6-recovery-basic.md`実測により、phase5計画書はADR-0031までしか言及せず新規番号を予約していないこと、phase6計画書はADR-0017/0018/0019等の既存番号への言及のみで新規番号を予約していないこと（実際の起票をP6-C5統合ウィンドウへ延期する方針、§7.8・§13）を確認した。したがって次の空き番号としてADR-0032から本ADR群（ADR-0032〜0037、P6-C1が計画書§7.9でscaffold KDocへ記録した6候補に1:1対応）を連番で採番した。

**背景**: §32「最大3つ」への切り詰め時にどの候補を残すかが仕様未定義（S-6）。`BasicRecoveryEngine`実装（P6-C3）はこの空白を埋めるため、§33（予定成立優先・過剰省略の禁止）＋§34（ユーザー最終決定・A案は必ず残す権利）から導出した全順序規則を実装した。P6-C1はこの起票候補をscaffold KDoc（`recovery/BasicRecoveryEngine.kt`・`recovery/BasicRecoveryDefaults.kt`）へ記録し、DECISIONS.mdへの正式記録をP6-C5統合ウィンドウへ延期していた（同時並行agentによるDECISIONS.md編集競合を避けるため。計画書§6.4「共有ファイル」がDECISIONS.mdをP6-C5専有としているため。P6-C1完了記録、計画書§10参照）。

**決定**:
1. `sortKey(c) = (feasible(c) ? 0 : 1, |skippedStepIds(c)|, ETA(c), ruleOrdinal(c))`の昇順で並べ、上位3件を採用する（`ruleOrdinal`: A=0, B=1, C=2, D=3）。
2. 上位3件にA案（`keep_all_steps`）が含まれない場合、末尾を落としてA案を追加する（§34「変更しない」選択肢を常に残す）。
3. B案（OPTIONAL省略）はA案が成立しない場合のみ、C案（OPTIONAL+IMPORTANT省略）はB案でも成立しない場合のみ生成する（§33「過剰省略の禁止」）。
4. D案（移動手段変更）は現在地・目的地座標が揃い`RoutingService`が短い代替所要を返した場合のみ生成する（§7.5）。

実装は`recovery/BasicRecoveryEngine.kt`の`buildSkipCandidates`/`attemptChangeTransportModeCandidate`/`sortAndTruncate`（P6-C3・P6-C4のRefactorで私有関数へ抽出済み）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 生成順（A→B→C→D）をそのまま表示順とする | §33「省略件数の少ない案が先」の要求を満たさない。D案（省略0件）が成立する場合、生成順のままだとB案（省略あり）より後に表示され、過剰省略回避の原則に反する |
| ETAのみで単純ソートする | ETAが完全同値になるケースで順序が不定になり決定性が壊れる。`ruleOrdinal`を第4キーとして追加することで完全な全順序を保証する（T-BRE-24） |

**影響範囲**: `recovery/BasicRecoveryEngine.kt`（`sortAndTruncate`）。
**検証方法**: `BasicRecoveryEngineTest`のT-BRE-1〜10・T-BRE-20〜24・T-BRE-26（優先順位・上限3件・A案保証・`ruleOrdinal`決定性）。P6-C5統合ウィンドウ時点で31/31 Green実測（`build/agent-logs/p6c5-full.log`）。
**再検討トリガー**: §32の上限が3件以外に変更された場合。新たな候補種別（E案等）が追加された場合、`sortKey`の`ruleOrdinal`割当を再設計する必要がある。

---

### ADR-0033: RecoveryOption.title/explanationは空文字固定としUI層でsemanticActionをlocalizationキーとして解決する（S-4、ADR-0018のRecoveryへの拡張）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定・§4.2 U-5） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P6-C1 scaffold KDocへ内容記録、P6-C5統合ウィンドウで正式起票） ／ 関連仕様§: §7・§21・§51（記録トリガー③仕様推奨からの逸脱、S-4）
- **ADR番号の付番根拠**: ADR-0032と同一バッチ起票（grep実測根拠はADR-0032参照）。ADR-0032の次番としてADR-0033を採番した。

**背景**: 仕様§51は`RecoveryOption(id, semanticAction, title, explanation, estimatedArrival, skippedStepIds)`とtitle/explanationを表示文言として定義しているが、§7 Global-firstはUI文字列のDomain層ハードコードを禁止する。実測（本Phase §0欠陥2）でも旧`mock/MockRecoveryFactory.kt`（削除済み）が"Continue as planned"等の英語文字列をtitle/explanationへ直接埋め込んでいたことを確認済み。Phase 4は同型の問題（`ExecutionStep.title`）をADR-0018（空文字固定＋`semanticId`解決）で解決しており、Recoveryにも同じ設計を拡張する。

**決定**: `BasicRecoveryEngine`は`RecoveryOption.title`/`explanation`を常に空文字で生成する。表示文言は`features/recovery/RecoveryOptionText.kt`の`resolveRecoveryOptionTitle`/`resolveRecoveryOptionExplanation`が`semanticAction`（`keep_all_steps`/`skip_optional_steps`/`skip_optional_and_important_steps`/`change_transport_mode`の4値）を`stringResource`へ解決する。未知の`semanticAction`はクラッシュせずフォールバック文言（`step_title_fallback`）を返す（`resolveStepTitle`先例）。`RecoveryScreen`はP6-C5で`option.title`/`explanation`の直接表示からこれらの解決関数経由へ結線し直した（P6-C3/C4時点は`RecoveryScreenTest.tRec2`の既存アサーション保護のため意図的に未結線のままだった。§4.2 U-6でその後の結線変更・fixture更新を承認済み）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| Engine内で表示文言を直接組み立てる（ロケール判定含む） | Domain層（`recovery/`）がロケール判定・`stringResource`解決を持つことになり、ADR-0018が確立したレイヤー分離規約（§7.1）に反する |
| `RecoveryOption`へ`titleResId: Int`等の新フィールドを追加する | §51契約（`RecoveryOption`のフィールド構成）の変更にあたり、TEAMS§5のversion付き変更経路（両側テスト更新等）を要する。既存の`semanticAction`フィールドで同じ目的を達成でき契約変更が不要 |

**影響範囲**: `recovery/BasicRecoveryEngine.kt`（title/explanation常に空文字）・`features/recovery/RecoveryOptionText.kt`（新規、P6-C5で本結線）・`features/recovery/RecoveryScreen.kt`（結線）・`res/values/strings.xml`・`res/values-ja/strings.xml`（`recovery_option_title_*`/`recovery_option_explanation_*`各4キー）。
**検証方法**: T-RECUI-2（既知4キー→非空文字列）・T-RECUI-3（未知キー→フォールバック）。`RecoveryScreenTest.tRec2`（fixture更新後、`resolveRecoveryOptionTitle`の解決結果と突き合わせ）。P6-C5統合ウィンドウ時点で`RecoveryOptionDisplayTest`9/9・`RecoveryScreenTest`7/7 Green実測（`build/agent-logs/p6c5-full.log`）。
**再検討トリガー**: `i18n.StepTitleKeys`（S-8、ui-implementer成果物）がRecoveryにも拡張される場合、`RecoveryOptionText.kt`をそちら経由へ差し替える再検討が必要（`NotificationContentBuilder`のADR-0031と同じ再検討トリガー）。

---

### ADR-0034: RecoveryOption.idはUUID.nameUUIDFromBytesによる決定的生成へ置き換える（ADR-0017のRecoveryへの拡張）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定・`docs/plans/phase6-recovery-basic.md`§7.9起票候補3） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P6-C1 scaffold KDocへ内容記録、P6-C5統合ウィンドウで正式起票） ／ 関連仕様§: §51
- **ADR番号の付番根拠**: ADR-0032と同一バッチ起票。ADR-0033の次番としてADR-0034を採番した。

**背景**: 旧`mock/MockRecoveryFactory.kt`（削除済み）は`id = UUID.randomUUID()`で非決定的に生成しており、同一入力から再生成しても`id`が変化するため、回帰テストで期待値を固定できない（ADR-0017が`ExecutionStep.id`で解決したのと同型の問題）。

**決定**: `BasicRecoveryEngine`は`RecoveryOption.id`を`UUID.nameUUIDFromBytes(seed.toByteArray())`で決定的に生成する。`seed = "${event.id}:${semanticAction}:${skippedStepIds.sorted().joinToString(",")}"`とする（`semanticAction`単独ではB/C案の構成差やコンテキスト差で衝突しうるため、`skippedStepIds`のソート済み結合を含める）。`estimatedArrival`はseedに含めない（RoutingService結果で揺れると決定性が壊れるため、構成のみで一意化する）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `event.id`＋`semanticAction`のみを鍵にする | 将来同一`semanticAction`で異なる`skippedStepIds`構成が生じる設計変更があった場合にid衝突するリスクを残す。`skippedStepIds`を含めることで構成差を確実に区別する |
| `estimatedArrival`もseedに含める | RoutingServiceの応答（D案の代替移動時間）はネットワーク条件で揺れうるため、含めると同一構成でも実行のたびに`id`が変わりうる。回帰テストの決定性が損なわれる |

**影響範囲**: `recovery/BasicRecoveryEngine.kt`（`Candidate.toRecoveryOption`）。
**検証方法**: T-BRE-25（同一入力での再生成でも同一`id`）。P6-C5統合ウィンドウ時点で31/31 Green実測（`build/agent-logs/p6c5-full.log`）。
**再検討トリガー**: なし。

---

### ADR-0035: transportModeはRecoveryContextへ追加せずBasicRecoveryEngineのコンストラクタで供給する（S-3）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定・§4.2 U-4） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P6-C1 scaffold KDocへ内容記録、P6-C5統合ウィンドウで正式起票） ／ 関連仕様§: §50（記録トリガー②仕様未定義箇所の補完、S-3）
- **ADR番号の付番根拠**: ADR-0032と同一バッチ起票。ADR-0034の次番としてADR-0035を採番した。

**背景**: 仕様§50 `RecoveryContext(currentTime, currentLocation, event, unfinishedSteps, latestTravelEstimate, plannedDepartureTime)`には現在の移動手段を保持するフィールドが存在しないため、D案（移動手段変更）を生成するのに現在の移動手段をEngineが知る手段が仕様上存在しない。

**決定**: `RecoveryContext`（§50契約）は変更しない。`BasicRecoveryEngine`のコンストラクタに`currentTransportMode: () -> TransportMode = { BasicRecoveryDefaults.DEFAULT_TRANSPORT_MODE }`を追加し、既定値`TRANSIT`（`DepartureUiState.kt`の実測既定値と一致）を`BasicRecoveryDefaults.kt`（仕様未定義プレースホルダ、`BasicPlanningDefaults`のG-1先例踏襲）へ隔離する。`AppContainer`は`BasicRecoveryEngine(routingService)`と第3引数を省略して構築し、この既定値をそのまま用いる（計画書§6.4行1が明示する構築形そのもの。現在選択中の移動手段を実際に追跡・供給する仕組みは本Phaseのスコープ外）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `RecoveryContext`へ`transportMode`フィールドを追加する | §50契約の変更にあたり、TEAMS§5のversion付き変更経路（両側テスト更新等）を要する。既存の`RecoveryViewModel.buildRecoveryContext`呼び出し元・テストへの影響範囲が本ADR単体の解決範囲を超える |
| `DepartureUiState`の選択済み移動手段を`AppContainer`経由で`BasicRecoveryEngine`へ供給する | Departure画面の状態（`DepartureViewModel`が保持）をRecovery生成時に横断的に参照する導線が現状存在せず、新たな共有状態導線の新設を要する。仕様上の根拠もないため、既定値プレースホルダで十分と判断した |

**影響範囲**: `recovery/BasicRecoveryEngine.kt`（コンストラクタ第3引数）・`recovery/BasicRecoveryDefaults.kt`（新規、`DEFAULT_TRANSPORT_MODE`・`alternativeTransportMode`）・`di/AppContainer.kt`（`BasicRecoveryEngine(routingService)`、第3引数は既定値のまま省略）。
**検証方法**: T-BRE-13〜19（D案生成条件・代替移動手段テーブル）。P6-C5統合ウィンドウ時点で31/31 Green実測（`build/agent-logs/p6c5-full.log`）。
**再検討トリガー**: 仕様がRecoveryContextへtransportModeフィールドの追加を明示的に要求するよう改訂された場合。ユーザーが実際に選択中の移動手段をアプリ全体で一貫して追跡する仕組み（例: `SharedPlanViewModel`への保持）が別Phaseで導入された場合、既定値プレースホルダをその実測値へ差し替える再検討が必要。

---

### ADR-0036: mock/MockRecoveryFactory.ktはP6-C5統合ウィンドウで削除しBasicRecoveryEngineへ完全昇格する（ADR-0019のRecoveryへの拡張）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定・`docs/plans/phase6-recovery-basic.md`§7.9起票候補5） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P6-C1 scaffold KDocへ内容記録、P6-C5統合ウィンドウで実施・正式起票） ／ 関連仕様§: §89・計画書§2.1・§6.3
- **ADR番号の付番根拠**: ADR-0032と同一バッチ起票。ADR-0035の次番としてADR-0036を採番した。

**背景**: `BasicRecoveryEngine`は完全決定的（LLM等の非決定的要素を含まない）処理であり、テスト用fakeを別途用意する必要がない。`MockRecoveryFactory`と`BasicRecoveryEngine`を並存させたまま`RecoveryEngine`実装を2系統保持することは、仕様§89「No duplicated domain logic」に違反する（Phase 4の`MockPlanFactory`→`BasicPlanningEngine`昇格〔ADR-0019〕と同型）。

**決定**: P6-C5統合ウィンドウで`AppContainer.recoveryEngine`実装を`MockRecoveryFactory()`から`BasicRecoveryEngine(routingService)`へ切替え、`mock/MockRecoveryFactory.kt`と`test/.../mock/MockRecoveryFactoryTest.kt`を削除した。検証意図（T-DM-9＝REQUIRED省略禁止、Mock暗黙契約＝`skippable && priority != REQUIRED`限定・`options.take(3)`）はT-BRE-11／T-BRE-12／T-BRE-20へ移設し弱体化しない（計画書§7.8対応表、P6-C3で実装済み）。`mock/`ディレクトリはmain/test双方で消滅した（副作用、§9エラーマップ#20は`AppContainerTest.resolveMockPackageDir()`の修正〔本ADRと同一ウィンドウで実施〕で対処済み）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `MockRecoveryFactory`をテスト専用fakeとして残す | `BasicRecoveryEngine`が完全決定的で挙動を代替できるためfakeとして独立に存在させる理由がなく、仕様§89違反が生じる（ADR-0019と同じ理由） |
| `MockRecoveryFactory`を段階的に空実装へ縮退させてから別サイクルで削除する | Phase 4（`MockPlanFactory`）・Phase 2（`MockEventSource`）いずれも即時削除の先例があり、段階的縮退は追加の中間状態（誰も呼ばない空クラス）を一時的に生むだけで§89の解消を遅らせる合理的理由がない |

**影響範囲**: `mock/MockRecoveryFactory.kt`（削除）・`test/.../mock/MockRecoveryFactoryTest.kt`（削除）・`di/AppContainer.kt`（`recoveryEngine`差替）・`di/AppContainerTest.kt`（`resolveMockPackageDir()`修正、§9エラーマップ#20）。
**検証方法**: T-P6DI-1（`recoveryEngine`が`BasicRecoveryEngine`型）・T-P6DI-2（`MockRecoveryFactory.kt`非存在、`mock/`ディレクトリ消滅時もhard failしない）。P6-C5統合ウィンドウ時点で`AppContainerTest`6/6 Green実測（tP6Di1/tP6Di2ともRed→Green反転を確認、`build/agent-logs/p6c5-full.log`）。全JVMスイート363 tests・failures 0・errors 0・skipped 1（`build/agent-logs/p6c5-full.log`。364から363への純減1件は`MockRecoveryFactoryTest.kt`削除〔同ファイルはT-DM-9の1件のみで構成〕による対象テスト減少であり、検証意図はT-BRE-11/12/20へ移設済みのため正味の検証カバレッジは減っていない）。
**再検討トリガー**: なし。

---

### ADR-0037: lateness detectionはフォアグラウンド限定とし、通知/FGS/AlarmManager契機の評価はPhase 5の所有とする（§7.6の境界確定）

- 日付: 2026-08-09 ／ ステータス: 承認済み（Fable 5裁定・`docs/plans/phase6-recovery-basic.md`§7.9起票候補6、§11.2.3でPhase 5計画書へも申し送り済み） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P6-C1 scaffold KDocへ内容記録、P6-C5統合ウィンドウで実配線・正式起票） ／ 関連仕様§: §13・§30（記録トリガー②仕様未定義箇所の補完）
- **ADR番号の付番根拠**: ADR-0032と同一バッチ起票。ADR-0036の次番としてADR-0037を採番した（本ADR群ADR-0032〜0037で6件。次ADR番号はADR-0038）。

**背景**: §30の`completedSteps`比較（Reality Check）や「遅延検知」がどの契機（画面表示時／通知発火時／定期監視時）で走るべきかは仕様上明確に切り分けられていない。一方、通知・Exact Alarm・Foreground Service（Phase 5所有）とRecovery候補生成（Phase 6所有）は独立して開発・テストできる必要があり（§11.2並列実行可否判定）、境界を明示しないと両Phaseの責務が曖昧になる。

**決定**: `recovery/LatenessDetector.evaluate(context: RecoveryContext): LatenessVerdict`を**純関数**として実装する（`context`以外のシステム時刻・状態を一切参照しない）。P6-C5統合ウィンドウで実際に呼び出すのは`ActionStarterNavHost`のexecution route入場・plan更新時（`LaunchedEffect(plan)`、one-shotガード付き）のみ。「Done」／「5 min later（Snooze）」タップ時の再評価、定期タイマー／バックグラウンド監視での再評価は**Phase 6では実装しない**——前者はPhase 5のstep progression実装（§69）が本実装した際に呼び出し側として結線する責務であり、後者はAlarmManager/FGS（Phase 5所有）・§95.1 While-in-use制約（バックグラウンド位置取得不可）に抵触するため。トリガー条件は`ETA(∅) > event.startDate`の1点のみとし、`currentTime > plannedDepartureTime`は`WillMissEvent.behindSchedule`として情報のみ返しトリガーにしない（誤発火防止、既存T-NAV-1/T-E2E-1の保護）。**「Recovery画面の表示時（自己再評価）」（計画書§7.6表）は`LatenessDetector.evaluate`を別途明示的に呼ぶ形では実装していない**——`RecoveryViewModel.init`が画面表示のたびに`recoveryEngine.createRecoveryPlan(...)`を呼び直す既存設計（P6-C3から不変）自体が、`BasicRecoveryEngine`内部の`feasibleA`判定（`LatenessDetector.evaluate`と同一のETA比較式）を通じて実質的に同じ再評価を行っているため、重複した呼び出しを追加しないと判断した（正直な報告：計画書の表現は「する」だが、実装は別関数呼び出しではなく既存ロジックへの機能的な包含として満たしている）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| Phase 6が「Done」／「5 min later」タップ時の再評価も実装する | 現行`ExecutionUiState.onDone`/`onPostpone`はPhase 5のstep progression（§69）が所有する経路であり、Phase 6が先取りして結線すると所有権が交錯し、Phase 5が後日この経路を改稿した際に競合・重複評価が生じるリスクがある（§11.2.2所有権整理） |
| 定期タイマーでバックグラウンドでも遅延を検知する | §95.1のWhile-in-use制約により、アプリがバックグラウンドにある間の位置取得・定期実行はそもそも許可されない設計方針（§58「常時監視を前提にしない」）に反する。AlarmManager/FGS基盤（Phase 5所有）を新たに使う設計変更になり、Phase 6のスコープ（§2.2「Phase 5領域に一切触れない」）を超える |
| `RecoveryViewModel.init`内でも`LatenessDetector.evaluate`を明示的に呼び、結果をログ等へ記録する | `BasicRecoveryEngine`が同一のETA比較式を内包しており（`feasibleA`判定）、同じ入力に対し同じ結果を2つの独立した関数呼び出しで二重計算するだけの冗長な変更になる。将来2つの判定式が乖離しないための一貫性維持コストの方が、記録用の追加呼び出しの価値を上回ると判断した |

**影響範囲**: `recovery/LatenessDetector.kt`（純関数として新規、P6-C3実装）・`navigation/ActionStarterNavHost.kt`（execution route内`LaunchedEffect`1箇所、one-shotガード、P6-C5実配線）。
**検証方法**: T-LATE-1〜10（`LatenessDetectorTest`、P6-C3で10/10 Green）。NavHost結線はP6-C5統合ウィンドウ時点で`NavigationFlowTest`5/5 Green維持（既存の`OnTrack`判定になるplanでは自動遷移が発火せず、既存5シナリオが無改造のまま成立することを実測確認）を根拠とする。全JVMスイート363 tests・failures 0・errors 0・skipped 1（`build/agent-logs/p6c5-full.log`）。
**再検討トリガー**: Phase 5がstep progression（Done/Snooze/next action）を本実装する際、各遷移後に`LatenessDetector.evaluate()`を呼び出す結線を追加する必要がある（§7.6申し送り、§11.2.3でPhase 5計画書への申し送り事項として明記済み）。`RecoveryViewModel`のcandidate生成ロジックが将来`BasicRecoveryEngine`から分離され`feasibleA`相当の判定を含まなくなった場合、「自己再評価」を明示的な`LatenessDetector.evaluate`呼び出しとして再実装する必要がある。
