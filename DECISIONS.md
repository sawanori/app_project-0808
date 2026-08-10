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

---

### ADR-0038: PlanReviewViewModelへ実移動時間サービス（geocoding/location/routing/permission）を配線し仕様§13のTravelTime項を完全実装する（P4-C8、計画の谷間の統合漏れ解消）

- 日付: 2026-08-10 ／ ステータス: 承認済み（Fable 5指示・domain-implementer実装） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer（P4-C8、Red/Green実装・ADR正式起票） ／ 関連仕様§: §13 Basic Engine（記録トリガー①interface契約の変更：`PlanReviewViewModel`のpublicコンストラクタへの引数追加）
- **ADR番号の付番根拠**: 起票直前に`grep -n "^### ADR-" DECISIONS.md`を再実測し、最新確定ADRがADR-0037（本書862行）であることを確認した。`docs/plans/phase11-i18n-a11y.md`8行目が「本Phaseの決定はADR-0038以降として記録する想定」と書いているが、これはPhase 11自身の見込みメモであり、本書（DECISIONS.md）へは未起票（Phase 11のADRは本書に1件も存在しない）。`docs/plans/phase4-basic-engine.md`・`phase5-notification-execution.md`・`phase6-recovery-basic.md`のいずれもADR-0038以降を予約していないことも確認済み。したがってADR-0037の次番としてADR-0038を採番した。

**背景**: 仕様§13の式は`StartOfTransition = EventStart − ArrivalBuffer − TravelTime − PreparationTime − TransitionTime`と定める。`planning/BasicPlanningEngine.kt`（P4-C3実装）はこの式自体を正しく実装しており、F44（`PlanningContext.travelEstimate == null`でもTRAVELステップなしでPlanが成立するフォールバック、固定値で穴埋めしない）も正しい。しかし`features/planreview/PlanReviewViewModel.kt`の`buildPlanningContext`はP4-C6完了時点まで`travelEstimate = null`を無条件にハードコードしており、TravelTime項を取得しにいく主経路（Phase 3が実装済みの`GeocodingService`／`LocationService`／`RoutingService`、`features/departure/DepartureViewModel.kt`で実績あり）がPlan構築側に一切配線されていなかった。結果として、Plan Review画面に表示される`StartOfTransition`・`departureTime`・`estimatedArrival`は、実際の移動時間を一度も反映しない「移動なし」の値に構造的に固定されたままだった。F44自体は「取得できなかったときのフォールバック」として正しい設計判断だが、それが「常に取得を試みずフォールバック値だけを返す」という誤った状態で運用されていた点が、Phase 4完了後に発見された計画の谷間（統合漏れ）である。

**決定**: `PlanReviewViewModel`のコンストラクタへ`geocodingService: GeocodingService? = null`／`locationService: LocationService? = null`／`routingService: RoutingService? = null`／`permissionGate: PermissionGate? = null`の4引数を追加する（`features/execution/ExecutionViewModel.kt`のP5-C2b契約変更・ADR-0028と同型の後方互換パターン：全引数デフォルト`null`で追加し、既存の2引数構築`PlanReviewViewModel(planningEngine, sharedPlanViewModel)`を壊さない）。イベント選択時、まず`travelEstimate=null`でPlanを即座に構築・表示し（Phase 4までの挙動そのまま。ユーザーを待たせない）、続けて`DepartureViewModel.recalculate`と同型の「geocode→currentLocation→estimateRoute」パイプラインを同一suspendチェーン内で実行する。成功した場合のみPlanを再構築して差し替える（差し替えにより`StartOfTransition`は移動時間ぶん早まる方向にのみ変化する）。いかなる失敗（`GeocodeResult.NoMatch`／`Failure`、`LocationResult.PermissionDenied`／`Failure`、`RoutingException`の任意のサブクラス、位置権限なし、`event.locationName`が空/null）でも例外を握り潰さず`null`へフォールバックし、F44の3ステップPlanへ回帰する。`RoutingException`はsealed基底型で一括catchするため、将来サブクラスが増減してもこのフォールバック自体は構造的に維持される。`transportMode`の既定値は`features/departure/DepartureUiState.kt`の既定（`TransportMode.TRANSIT`）と同一値を用いる（設定画面連動はPersonal Execution Profileと同じく将来Phase）。`di/AppContainer.kt`の`createViewModelFactory`は、`DepartureViewModel`と同一の共有インスタンス（`geocodingService`・`locationService`・`routingService`・`permissionGate`、いずれも本コンテナ内で構築済みのプロパティ）をそのまま渡す。`routingService`が`CachingRoutingService`の場合、Plan ReviewとDepartureの間でキャッシュ（§8、目的地・移動手段一致かつ移動500m未満・経過10分未満）を共有するため、Departure画面遷移直後の再取得はキャッシュヒットになりうる（二重の外部API呼び出しを構造的に避ける）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 現状維持（Plan構築時は常に`travelEstimate=null`、実移動時間の反映はDeparture画面のみ） | 仕様§13の式がPlan Review時点で常に不完全な値（TravelTime=0前提）になり、「departure calculation」（仕様§68 Phase 4対象、F42）が実質未完了のまま据え置かれる。Plan ReviewとDepartureで表示される`StartOfTransition`相当の値が乖離したままになり、ユーザーが画面間で矛盾した時刻案内を受け取り得る |
| 4引数を必須（非null）にする | `AppContainerTest`等の既存構築箇所・将来の直接構築テストとのコンパイル互換性が壊れる。ADR-0028（`ExecutionViewModel`）が確立した「新規サービス引数はデフォルト`null`付きで追加し既存呼び出しを壊さない」という本プロジェクトの一貫パターンに反する |
| フェッチ完了までPlan表示自体をブロックする（同期的に見えるUX） | geocode／location／routingそれぞれ既定10秒のタイムアウトを持つため、最悪ケースで数十秒間Planが一切表示されなくなり、Phase 1以来の「画面遷移直後にPlanが即座に見える」というUXを退行させる。F44のフォールバック値（`travelEstimate=null`のPlan）は表示として正当なため、まずそれを見せてから差し替える設計の方が正しい |
| geocode/location/routingのいずれかが失敗したらエラーとしてユーザーに明示する | F44・G-9（移動不要と移動時間不明を区別する専用文言を作らず共通1種類の文言で扱う）の設計方針に反する。位置情報起因の失敗（オフライン・NoMatch・権限なし等）は多数派の正常系であり、これをエラー表示にするとPhase 3の`DepartureViewModel`が確立した「異常ではなく手動導線へ誘導する」思想と一貫しなくなる |

**影響範囲**: `features/planreview/PlanReviewViewModel.kt`（コンストラクタ4引数追加・`fetchTravelEstimate`新設・`buildPlanningContext`のtravelEstimate/transportMode差替）・`di/AppContainer.kt`（`createViewModelFactory`内`PlanReviewViewModel`初期化子）・`test/java/com/actionstarter/features/PlanReviewViewModelTest.kt`（新規、T-P4C8-1〜5）・`docs/plans/phase4-basic-engine.md`（P4-C8行追記）。`features/departure/DepartureViewModel.kt`・`services/`配下は変更していない（呼ぶだけ）。

**検証方法**: Red実測（`build/agent-logs/p4c8-red.log`、4引数未実装のため`PlanReviewViewModelTest.kt`が「No parameter with name 'geocodingService'/'locationService'/'routingService'/'permissionGate' found」でコンパイル不能）→Green実装後、対象5件個別実行で5/5 Green→全JVMスイート`--rerun`で**373 tests・failures 0・errors 0・skipped 1**（`build/agent-logs/p4c8-full.log`。P4-C6完了時点の368件から新規5件増加、純増のみで既存368件は無改造のままGreen維持）。特に`NavigationFlowTest`5/5・`PlanReviewScreenTest`6/6・`PlanReviewStepDisplayTest`5/5・`ExecutionOneActionTest`8/8・`DepartureRoutingViewModelTest`10/10・`DepartureViewModelTest`5/5・`CalendarNavigationFlowTest`1/1のGreenを個別確認した（`NavigationFlowTest`等が使うRobolectricカレンダーfixtureは`EVENT_LOCATION`を`null`で構成しているため、`event.locationName`が常に空となり新規フェッチ経路が構造的に起動せず、Phase 4までの3ステップPlan挙動と1タップ数まで一致することを確認）。`:app:lintDebug`は**error 0**・warning **22件**（`build/agent-logs/p6c5-lint.log`時点から不変）、UnusedResources**3件**（同じく不変、`strings.xml`を変更していないことの構造的裏付け）を実測（`build/agent-logs/p4c8-lint.log`）。T-P4C8-1では、サービス未配線のベースラインPlan（`travelEstimate`常に`null`）との`transitionStart`差分が実測した移動時間（25分）と厳密一致することを直接アサーションしている。

**再検討トリガー**: Personal Execution Profile（Phase 10）または設定画面が`transportMode`のユーザー選択・永続化を提供するようになった場合、`DEFAULT_TRANSPORT_MODE = TransportMode.TRANSIT`固定をその値に差し替える必要がある。将来`PlanReviewViewModel`のスコープ規則が変わり、単一インスタンスが複数の異なる非null`selectedEvent`を順に観測しうるようになった場合（現行のCompose Navigationのbackstack-entryスコープでは発生しない）、stale-write防止チェック（フェッチ直前の`event.id`一致確認）の強度を再評価すること。

---

### ADR-0039: POST_NOTIFICATIONS実行時権限は事前説明カードを挟まずPlanReview「Start」タップで直接システムダイアログを表示し、遷移はlauncherのコールバック内で行う（§12 S-1裁定）

- 日付: 2026-08-10 ／ ステータス: 承認済み（Fable 5裁定・`docs/plans/phase11-i18n-a11y.md`§12 S-1・Gemini G1 CRITICAL #1反映） ／ 決定者: Fable 5 ／ 起案agent: plan-doc-writer（計画書§7.1起筆）→ui-implementer/test-writer（P11-C3実装・正式起票） ／ 関連仕様§: §95.4権限一覧表（POST_NOTIFICATIONS行）・§19原則（記録トリガー②仕様未定義箇所の補完：要求タイミングは規定済みだがUI詳細=事前カード要否は未規定だった）
- **ADR番号の付番根拠**: 起票直前に`grep -n "^### ADR-" DECISIONS.md`を再実測し、最新確定ADRがADR-0038（本書885行）であることを確認した。`docs/plans/phase11-i18n-a11y.md`が予約していた「ADR-0038以降」の想定と異なりADR-0038はP4-C8（別Phase）に既に使用済みだったため、ADR-0038の次番としてADR-0039を採番した。

**背景**: `AndroidManifest.xml:27-31`のコメントおよび§95.4権限表は、POST_NOTIFICATIONSの要求タイミングを「Execution Plan確定時（PlanReview「Start」）」と規定するのみで、事前説明カードを挟むか直接システムダイアログを出すかは未規定だった。カレンダー（`EventSelectionScreen`のPermissionRequired状態）・位置情報（`DepartureScreen`のLocationPermissionRationaleCard）はいずれも事前カードを挟む設計だが、通知権限はこれらと性質が異なる：カレンダー・位置情報は権限なしでは画面そのものが成立しない（§19原則と同型の「Local AIオフでもBasic Engineが成立する」設計原則の裏返し）のに対し、通知はExecution画面での次アクション提示というコア機能自体は権限なしでも成立する増強系の権限である。

**決定**: PlanReviewの「Start」タップで事前説明カードを挟まず、`ActivityResultContracts.RequestPermission()`のシステムダイアログを直接表示する（API 33+のみ）。低摩擦・OS標準ダイアログの説明文で十分と判断し、拒否後の救済はF80の設定導線（`ExecutionDegradationBanners`内の`execution_notification_open_settings_button`）で担保する。Gemini G1 CRITICAL指摘#1を受け、当初案（`launch()`直後に同期的に`navController.navigate()`する設計）は採用せず、画面遷移をlauncherの**コールバック内**へ移した——`launch()`はActivityResultRegistry経由の非同期ディスパッチであり、直後の同期的`navigate()`と競合すると権限ダイアログの表示・消滅と画面遷移アニメーションが競合し、かつコンポジション破棄タイミングと重なると`rememberLauncherForActivityResult`のコールバックが失われるリスクがあるため。コールバックは許可・拒否いずれの結果も分岐せず遷移のみを行う（実際の許可状態は`ExecutionViewModel.isNotificationPermissionDenied()`が`PermissionGate.isGranted()`で都度再照会するため、launcher結果値そのものは不要）。API 33未満は概念上POST_NOTIFICATIONSが存在しないため、launcherを介さず直接`navigate()`する（不要な非同期依存を増やさない）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 事前説明カードを挟む（カレンダー・位置情報と統一） | 通知は権限なしでもコア機能（Execution画面）が成立する増強系権限であり、必須系権限（カレンダー・位置情報）と同じ摩擦を課す理由がない。OS標準ダイアログの説明文で足りると判断した |
| `launch()`直後に同期的に`navigate()`する（当初案） | Gemini G1 CRITICALで指摘された非同期タイミング競合リスク（ダイアログ表示・消滅と画面遷移アニメーションの競合、コンポジション破棄とコールバック解決の競合）を放置することになる |
| API 33未満でも常に`launch()`を呼ぶ（分岐なしで統一） | API 33未満では`RequestPermission()`は即座に`true`をコールバックする既知の設計のため動作上は成立するが、コールバック自体はなお非同期ディスパッチに委ねられ理論上のライフサイクル競合リスクが残る。API 33未満では権限要求が概念上不要であることを踏まえ、この非同期依存を意図的に排除した |

**影響範囲**: `navigation/ActionStarterNavHost.kt`（PlanReview route composable、`requestNotificationPermissionLauncher`新設）。`features/planreview/PlanReviewViewModel.kt`は変更していない（トリガーはNavHostのCompose層に閉じる、既存の`onRequestCalendarPermission`/`onRequestLocationPermission`と同じ設計）。

**検証方法**: T-P11N-1（`@Config(sdk=[33])`、`Shadows.shadowOf(activity).lastRequestedPermission`でPOST_NOTIFICATIONSが実際にリクエストされたことを実測）・T-P11N-5（`@Config(sdk=[26])`、launcherを介さず直接Executionへ遷移することを実測、`lastRequestedPermission`がnullのままであることも確認）・T-P11N-2/3（許可/拒否環境でExecution到達後の`isNotificationPermissionDenied`反映を確認）で検証。全JVMスイート`--rerun`で**412 tests・failures 0・errors 0・skipped 1**（`build/agent-logs/p11c3-green-final.log`）。既存`NavigationFlowTest`のtNav1/tNav3はPlanReview「Start」を経由するため新規launcherの影響を受けて一時的にRed化した（`Done`/`Simulate delay (debug)`ボタンが見つからず失敗）ことを実測確認し、`@Config(sdk=[26])`を付与してAPI 33未満の直接遷移分岐を通す修正で復旧した（両テストの意図＝ナビゲーション構造検証は無変更、対応の詳細は本ファイルのKDoc参照）。

**再検討トリガー**: 将来、通知権限の拒否率が高いことが判明し事前説明カードの追加が製品判断として必要になった場合、本ADRの「直接リクエスト」方針を再検討すること。

---

### ADR-0040: 通知権限（POST_NOTIFICATIONS）もPermissionGateの2値契約を維持し、「拒否」と「永続拒否」をUI上区別しない設計を踏襲する

- 日付: 2026-08-10 ／ ステータス: 承認済み（`docs/plans/phase11-i18n-a11y.md`§7.1、Departure/EventSelectionの既存設計パターンの明示的な踏襲として記録） ／ 決定者: Fable 5（既存パターン踏襲の追認） ／ 起案agent: plan-doc-writer（計画書§7.1起筆）→ui-implementer/test-writer（P11-C3実装・正式起票） ／ 関連仕様§: §95.4・§95.6エラー＆レスキューマップ「通知」行（記録トリガー②仕様未定義箇所の補完：既存Departure/EventSelectionパターンの通知権限への明示適用）
- **ADR番号の付番根拠**: ADR-0039と同一バッチ起票。起票直前の`grep`再実測（ADR-0039参照）によりADR-0039の次番としてADR-0040を採番した。

**背景**: `PermissionGate.isGranted(permission: String): Boolean`（`services/permission/PermissionGate.kt`）は許可/不許可の2値のみを返す契約であり、「拒否（再度リクエスト可能）」と「拒否（今後表示しない＝永続拒否、`shouldShowRequestPermissionRationale`がfalseになる状態）」を区別しない。Departure（位置情報）・EventSelection（カレンダー）は既にこの2値契約のまま3状態パターン（`NOT_REQUESTED`/`DENIED`/`GRANTED`）で実装済みであり、通知権限だけ4状態（未リクエスト／拒否・再request可／拒否・永続／許可）の区別を導入すると、UIロジック・テストの両方で非対称な複雑性が生じる。

**決定**: 通知権限もDeparture/EventSelectionと同じ2値契約をそのまま踏襲する。UI上は「許可」「未許可（理由を問わず設定導線を提示）」の2状態に集約し、F80の設定導線ボタン（`execution_notification_open_settings_button`）は「拒否（再request可）」「拒否（永続）」のいずれの場合でも同一に表示・同一に機能する（設定画面を開けば理由を問わずユーザーは許可操作ができるため、UI側で区別する実益がない）。エラー＆レスキューマップ（計画書§9、#1〜#4）ではこの2値集約を明示した4行構成とし、状態2（拒否・再request可）と状態3（拒否・永続）を意図的に同一ハンドリングとして記録した。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `shouldShowRequestPermissionRationale`を追加照会し3状態（未request／拒否・再request可／拒否・永続）をUIへ反映する | Departure/EventSelectionが確立した2値集約パターンとの一貫性が崩れ、通知権限だけ特別扱いする合理的理由がない。UI分岐が増える割に、いずれの拒否状態でも設定導線ボタンの挙動は同一であるためユーザー体験上の価値が薄い |
| `PermissionGate`インターフェース自体を3値（enum）へ拡張する | 既存2箇所（Departure/EventSelection）の呼び出し元・テストへの影響が生じる大規模変更になり、i18n/a11yスコープ（本Phase）を大きく超える。§88「過剰設計を避ける」に反する |

**影響範囲**: `features/execution/ExecutionViewModel.kt`（`isNotificationPermissionDenied()`、Phase 5で既に2値契約のまま実装済み・本Phaseでは変更なし、ADRとしては設計方針の明示的な確認・記録）。

**検証方法**: T-P11N-3（拒否環境でのバナー表示）・T-P11N-4（設定導線ボタンの表示・タップ動作）・T-P11N-10（`ExecutionOneActionTest`のT-P5UI-6契約が本Phase変更後も維持されることの追加確認）。全JVMスイート412 tests・failures 0（ADR-0039と同一実測、`build/agent-logs/p11c3-green-final.log`）。

**再検討トリガー**: なし（Departure/EventSelectionの既存パターンが変更される場合、本ADRも合わせて再検討する）。

---

### ADR-0041: 未配線文字列2件（`execution_placeholder_step_title`／`travel_time_manual_apply_button`）を削除し、`location_permission_denied_message`をDepartureのDENIED状態説明として配線する

- 日付: 2026-08-10 ／ ステータス: 承認済み（`docs/plans/phase11-i18n-a11y.md`§7.4・§12 S-2/S-3裁定） ／ 決定者: Fable 5 ／ 起案agent: plan-doc-writer（計画書§7.4起筆、grep/Read実測）→ui-implementer/test-writer（P11-C1でstrings.xml削除・P11-C3で配線実装・正式起票） ／ 関連仕様§: §7「UI文字列の直接ハードコード禁止」（記録トリガー①バグ修正／死蔵コード整理：仕様からの逸脱ではなく既存コードの整合性回復）
- **ADR番号の付番根拠**: ADR-0040と同一バッチ起票。起票直前の`grep`再実測（ADR-0039参照）によりADR-0040の次番としてADR-0041を採番した。

**背景**: `:app:lintDebug`のUnusedResources警告がPhase 3〜6を通じ一貫して3件（`execution_placeholder_step_title`／`location_permission_denied_message`／`travel_time_manual_apply_button`）報告され続けていた（`docs/plans/phase5-notification-execution.md:712`・`docs/plans/phase6-recovery-basic.md`P6-C5行の2つの独立した実測ログで確認）。個別調査（`ExecutionViewModel.kt`・`TravelTimeInput.kt`・`DepartureScreen.kt`の直接Read）の結果、`execution_placeholder_step_title`は`resolveStepTitle`のelse分岐（`step_title_fallback`）へP4-C6で構造的に置換済みの死蔵リソース、`travel_time_manual_apply_button`はP3-C5で確定した「値変更時に即時反映」設計（`TravelTimeInput`にApplyボタン自体が存在しない）と両立しない死蔵リソースと判明した。`location_permission_denied_message`のみ、DENIED状態の説明文として今も意味を持つ文言だが描画箇所を持たない未配線リソースだった。

**決定**: `execution_placeholder_step_title`・`travel_time_manual_apply_button`をP11-C1で`res/values/strings.xml`・`res/values-ja/strings.xml`両方から削除し、`ExecutionViewModel.kt`のKDocダングリング参照も是正した。`location_permission_denied_message`はP11-C3で`DepartureScreen.kt`の`DeparturePermissionAndRoutingSection`内`showManualFallback`ブロック（`TravelTimeInput`直前）へ説明`Text`として配線した（文言・配置ともS-2裁定どおり据え置き、Apply方式への設計変更は行わない）。`DepartureRoutingScreenTest.kt`の`tDep2_5_phase3StringKeys...`が参照する`phase3Keys`リストから`travel_time_manual_apply_button`を除外した（TEAMS§2承認要請対象、Fable 5指示書に承認記録あり）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `travel_time_manual_apply_button`を残しApplyボタンUXへ設計変更する | i18n/a11yスコープを超えるUX変更であり本Phaseの範囲外（計画書§2.2で明示除外）。P3-C5で確定済みの即時反映設計とも矛盾する |
| `execution_placeholder_step_title`を残しKDocのみ修正する | UnusedResources警告が解消されないまま残り、G4-JVMゲート（UnusedResources 0件）を満たせない |
| `location_permission_denied_message`も削除する（3件とも削除） | 文言自体は今も正当（手動入力への案内）であり、既存の`departure_eta_stale_notice`型パターンにそのまま適合する。削除するとDENIED状態のユーザー案内が手薄になる |

**影響範囲**: `res/values/strings.xml`・`res/values-ja/strings.xml`（2キー削除・配線対象1キーは既存のまま）・`features/execution/ExecutionViewModel.kt`（KDoc是正）・`features/departure/DepartureScreen.kt`（`location_permission_denied_message`配線）・`test/.../features/DepartureRoutingScreenTest.kt`（`phase3Keys`リスト更新）・`test/.../i18n/StringResourceParityTest.kt`（T-P11S-1/2/4/7新設、削除確認・グレップ構造ガード）。

**検証方法**: T-P11S-1/2（削除2キーがen/ja両方に存在しないことを確認、born-green）・T-P11S-4（削除2キーへのソースコード参照が`app/src/main`全体で0件、born-green）・T-P11S-7（`ExecutionViewModel.kt`単体のダングリング参照0件、born-green）・T-P11S-3（`location_permission_denied_message`がDENIED状態で実際に描画される、Red→Green）。`:app:lintDebug`実測でUnusedResources**0件**・error **0件**（`build/agent-logs/p11c5-lint.log`、XMLレポート直接パースで確認）。全JVMスイート412 tests・failures 0（`build/agent-logs/p11c3-green-final.log`）。

**再検討トリガー**: なし。

---

### ADR-0042: フォントスケール1.5x耐性テストの技術選定として`DeviceConfigurationOverride`（Compose公式テストAPI）を採用する

- 日付: 2026-08-10 ／ ステータス: 承認済み（`docs/plans/phase11-i18n-a11y.md`§7.3、Gemini G1 CRITICAL指摘反映） ／ 決定者: Fable 5 ／ 起案agent: plan-doc-writer（計画書§7.3起筆、Context7でAPI存在を事前確認）→ui-implementer/test-writer（P11-C2でバイトコード実測によりimportパスを確定・正式起票） ／ 関連仕様§: §63 Accessibility「フォントスケール追従（Dynamic Type相当）」（記録トリガー④新規テスト技法の導入：後続Phaseの参照先として記録する価値がある）
- **ADR番号の付番根拠**: ADR-0041と同一バッチ起票。起票直前の`grep`再実測（ADR-0039参照）によりADR-0041の次番としてADR-0042を採番した。

**背景**: grep実測でfontScale関連コードはJVM/Robolectric/instrumentedいずれのテストにも0件（総ゼロからの新設）だった。Context7で`developer.android.com/develop/ui/compose/testing/common-patterns`を確認し、`androidx.compose.ui.test.DeviceConfigurationOverride.FontScale`（`@ExperimentalTestApi`、`ui-test`アーティファクト）がJetpack Compose公式のテスト専用オーバーライド機構であることを確認したが、正確なimportパスはP11-P1（要検証事項）として保留されていた。

**決定**: `DeviceConfigurationOverride.FontScale(1.5f)`を採用する。P11-C2でのコンパイル実測（`Unresolved reference 'FontScale'`エラー）を受け、`ui-test-api.jar`のバイトコードを直接調査（`javap -p`）した結果、`FontScale`は`DeviceConfigurationOverride.Companion`への拡張関数としてパッケージ直下（`androidx.compose.ui.test.FontScale`）にトップレベル定義されており、`DeviceConfigurationOverride`本体のインポートとは別に明示的な`import androidx.compose.ui.test.FontScale`が必要であることを確定した（同様に`ForcedSize`も`import androidx.compose.ui.test.ForcedSize`が必要）。既存の`androidx-compose-ui-test-junit4`依存（compose BOM `2026.06.01`）で追加のGradle依存なしに利用できることも確認済み（P11-P1は肯定的に解決、`LocalDensity`直接オーバーライドへのフォールバック〔リスクR-3〕は不要だった）。全テストメソッドへ`@OptIn(ExperimentalTestApi::class)`を付与した（Gemini G1 CRITICAL指摘反映）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f))`直接オーバーライド（R-3フォールバック案） | `DeviceConfigurationOverride.FontScale`がP11-P1実測で問題なく利用可能と判明したため、フォールバックを発動する必要がなかった。`LocalDensity`直接操作は密度以外の設定（フォントウェイト調整等）を伴わないため公式APIより表現力が劣る |
| fontScaleテストをRobolectricの`RuntimeEnvironment.setQualifiers`（例: `"+fontscale150"`のような擬似qualifier）で代替する | AndroidリソースqualifierにfontScale相当のものは標準で存在せず、この手法自体が成立しない |

**影響範囲**: `test/java/com/actionstarter/features/FontScaleResilienceTest.kt`（新規、T-P11F-1〜8）。副次的発見として、`features/recovery/RecoveryScreen.kt`のルート`Column`がscroll不可の`fillMaxSize()`のみだったため、fontScale=1.5x×候補3件表示時に「Use this plan」ボタンがビューポート外へ押し出され`assertIsDisplayed()`が失敗する実際のレイアウト破綻をT-P11F-5が検出した（§9エラーマップ#7の実例）。`DepartureScreen.kt`の既存`verticalScroll(rememberScrollState())`パターンを踏襲し`RecoveryScreen.kt`へ同modifierを追加する軽微な調整（計画書§12 S-6裁定の許容範囲内）で解消した。

**検証方法**: T-P11F-1〜8（5画面×基本表示＋Done/5min-later非重複＋1.0x/1.5xノード集合一致＋ja×1.5x複合）。全JVMスイート412 tests・failures 0（`build/agent-logs/p11c3-green-final.log`、`--rerun`で2回連続実測・安定）。

**再検討トリガー**: 実機fontScale=1.5目視確認（G4-E、§8.7）で本Robolectricベースの検証が見落とした視覚的破綻が発見された場合、追加のレイアウト調整または本ADRの検証手法自体の見直しを検討すること。

---

### ADR-0043: Local LLMランタイムにLiteRT-LM 0.15.0を採用し、既定モデルはQwen3-0.6B（INT4 block-32）、単一`:app`モジュールを継続する

- 日付: 2026-08-10 ／ ステータス: 承認済み（`docs/plans/phase7-local-llm-foundation.md`§0・§5.1・§5.2・§8.2、Fable 5裁定U-2／U-4、G1通過） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画書§5・§8.2起筆、WebFetch/Context7による一次ソース確認）→domain-implementer（P7-C0でGo/No-Go実測・GO判定、P7-C1で正式起票） ／ 関連仕様§: §42「特定Runtimeを仕様として固定しない」・§17「モデル名を製品仕様として固定しない」・ADR-0002「Phase 7でネイティブ依存が増大した時点で分割要否を再検討する」（記録トリガー③仕様推奨からの逸脱にはあたらないが④依存バージョンの変更・⑥Phaseゲート変更に該当）
- **ADR番号の付番根拠**: 起票直前に`grep -n "^### ADR-" DECISIONS.md`を再実測し、最新確定ADRがADR-0042（本書985行）であることを確認した。`docs/plans/phase7-local-llm-foundation.md`§14 P7-C1行が「ADR-0043（ランタイム/モデル選定・単一モジュール継続）」を明示的に指定しているため、ADR-0042の次番としてADR-0043を採番した。

**背景**: 仕様§42はLocal AI候補として「MediaPipe LLM Inference API／llama.cpp（JNI）／MLC-LLM／ONNX Runtime Mobile／AICore・Gemini Nano」を挙げつつ「特定Runtimeを仕様として固定しない」と定める。計画書§5.1は上記候補にLiteRT-LMを加えた6候補を、①構造化出力（JSON強制）の実現手段（本アプリの用途が「固定スキーマの短いJSONを返すだけ」であるため最優先軸）②Qwen対応の成熟度③ミッドレンジCPU実行④統合工数⑤ライセンス⑥メンテ活性の6軸で比較した。加えてADR-0002は「Phase 7でネイティブ依存が増大した時点」を単一`:app`モジュール継続可否の再検討トリガーとして明示的に指定しており、Phase 7は文字どおりこのトリガーに該当する。

**決定**:
1. **ランタイム**: `com.google.ai.edge.litertlm:litertlm-android:0.15.0`（Maven AAR）を採用する。根拠: (a) Kotlinから`ResponseFormat.json(schema)`＋`ConversationConfig(enableResponseFormat=true)`で直接JSON Schema制約付きdecodingを呼び出せる一次ソース確認済みの唯一の候補である一方、次点のllama.cpp（GBNF文法・GGUF）は公式Maven AARが存在せずNDK/CMake/JNIの自前構築が必要になる（計画書§5.1）。(b) P7-C0のスパイク実測（2026-08-10、AVD `actionstarter_test` x86_64/API35）で`ResponseFormat.json(schema)`が2回連続実行ともスキーマ完全準拠のJSONを生成することを確認し、Go/No-Go判定はGO方向（判定確定はFable 5、計画書§14 P7-C0実測結果）。(c) AAR同梱ABIがarm64-v8a＋x86_64の2種のみ（調査実測・P7-C0で自プロジェクト実測により再確認済み、V-1）であり、本プロジェクトのAVD（x86_64）・Galaxy A実機（arm64-v8a）の両方をカバーする。バージョンは`0.15.0`に固定し自動更新しない（`ResponseFormat`が公式Webドキュメント未掲載でAPI変更リスクがあるため、R-2）。**この決定はP7-C0で④（`ResponseFormat`実機動作）が不成立だった場合にのみ次点のllama.cpp案へ切り替える条件付き決定であり（U-2）、P7-C0はGO方向で確定した。**
2. **既定モデル**: `ModelCatalog`（F87）の既定エントリは`litert-community/Qwen3-0.6B`の`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`（INT4 block-32・ctx4096・Apache-2.0）とする。サイズ344,437,808バイト（328.5MiB）・SHA-256 `e3e290109da4388d65a17510a0c66af91c8039f52d2c465868dbc43c09a776cf`は、開発者（domain-implementer）がP7-C0でHugging Faceから自らダウンロードした個体を`sha256sum`で計算した値であり、これを正の値として`ModelCatalog`へ焼き込む（U-6：HF側`x-linked-etag`ヘッダの値と一致確認済みだが、この一致は補助的傍証にとどめ無条件には信頼しない）。**モデルの最終選択は未確定である**: P7-C8実機プローブ（Galaxy A系、Qwen3-0.6B／Qwen3-1.7B／Gemma3-1B の3者比較）の結果を日本語品質（MIFEvalJa）・decode速度・ピークRAMの数値としてユーザーへ提示したうえで確定する建付け（U-4）。本ADRが記録するのは「P7-C1時点の`ModelCatalog`既定値」であり、製品仕様としてのモデル名固定ではない（§17）。
3. **単一`:app`モジュールを継続する**（ADR-0002の再検討トリガーへの回答）。理由: 採用したLiteRT-LMはMaven AAR依存のみでNDK・CMake・JNI・vendoringをプロジェクトへ一切持ち込まないため、ADR-0002が警戒した「ネイティブビルドによるビルド時間増大」が発生しない（計画書§8.2）。**次点のllama.cpp案へ切り替える場合のみ`:llm`モジュール分離が必要になり、その場合は本ADRを改訂する。**

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| llama.cpp（GGUF・GBNF文法制約、次点案） | 公式Maven AARが存在せず`examples/llama.android`のvendoring＋CMake/NDK/JNI自前構築が必要（統合工数④で大差）。GGUFのモデル交換性の高さ・MITライセンス・メンテ活発さは上回るが、P7-C0でLiteRT-LMの`ResponseFormat`が実機で機能したため切替条件（U-2）が発生しなかった |
| MLC LLM | AndroidはOpenCLのみでCPU/Vulkanバックエンド未提供、Adreno＋`_1`レイアウトでprefill時20〜50秒のUIフリーズの既知不具合があり③ミッドレンジCPU実行が致命的。正式リリースも存在しない（`v0.1.dev0`のみ） |
| ONNX Runtime GenAI | Java版APIに構造化出力の強制手段（`OgaGeneratorParamsSetGuidance`のJavaバインディング）が未実装。公式AARが`--use_guidance`なしでビルドされている |
| MediaPipe LLM Inference API | 公式サポートモデル表にQwenが含まれない。maintenance-onlyモードへ移行しJavaクラスに`@Deprecated`付与済みで、Google自身がLiteRT-LMへの移行を推奨している |
| MNN / MNN-LLM | 構造化出力の強制手段が皆無（grammar/gbnf/json_schemaいずれも実装・配布物に存在しない）。件数制約をretryで代替する設計は§20「Schema validation必須」と両立しない |
| Gemma 4 E2B / Gemma 3 1B を既定モデルにする | Gemma 4は日本語品質最高（MIFEvalJa 0.646）だがGoogle自身が最低8GB RAMを要求しGalaxy Aクラスの大半を切り捨てる。Gemma 3 1Bは高速・省メモリだが日本語MIFEvalJa 0.323と実用域に届かない。いずれもP7-C8実機プローブでの数値提示・ユーザー判断（U-4）を経ずに確定させることは§17「日本語だけで選定しない」の趣旨にも反する |
| Phase 7時点で`:llm`モジュールへ分割する | 採用ランタイムがAAR依存のみでネイティブビルドを持ち込まないため、分割による恩恵（ビルド時間短縮等）が現時点で発生しない。不要な複雑性を先取りしない |

**影響範囲**: `app/build.gradle.kts`（`implementation(libs.google.ai.edge.litertlm.android)`）・`gradle/libs.versions.toml`（`litertlmAndroid = "0.15.0"`）・`app/src/main/java/com/actionstarter/ai/model/ModelCatalog.kt`（既定エントリ`QWEN3_0_6B_INT4_BLOCK32`）・`app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（F86実装先）。単一モジュール継続のため新規Gradleモジュールの追加なし。

**検証方法**: P7-C0スパイク実測（`build/agent-logs/p7c0-*.log`、`app/src/androidTest/java/com/actionstarter/probe/LiteRtLmProbeTest.kt`）で`ResponseFormat.json(schema)`のGO判定を確認済み。P7-C1では依存をバージョンカタログへ正式化し`:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`成功・全JVMスイート417 tests・failures 0・skipped 1（導入前ベースラインと完全一致、`build/agent-logs/p7c1-compile.log`／`p7c1-regression.log`）を確認した。モデルの最終確定はP7-C8実機プローブ（§11.3）の実測を要する。

**再検討トリガー**: (a) P7-C8実機プローブでQwen3-0.6Bが実用に耐えない性能・メモリ特性だった場合、既定モデルをQwen3-1.7BまたはGemma系へ変更し本ADRを改訂する（U-4）。(b) 将来llama.cpp等ネイティブビルドを要するランタイムへ切り替える場合、`:llm`モジュール分割の要否を本ADRの枠内で再検討する。(c) LiteRT-LMが0.15.0から更新される場合、`ResponseFormat`等の非公式API（公式Webドキュメント未掲載）の破壊的変更有無を確認してからバージョンを上げる（R-2）。

---

### ADR-0044: AI隔離ガードT-AIISO-6は`ai/model/ModelDownloader.kt`1ファイルに限定した許可リストを持つ（既存3ガードの「許可リストなし」設計からの例外）

- 日付: 2026-08-10 ／ ステータス: 承認済み（`docs/plans/phase7-local-llm-foundation.md`§9本文・§9.3・注記、Fable 5裁定U-9、Gemini G1 CRITICAL #1反映、G1通過） ／ 決定者: Fable 5 ／ 起案agent: android-planner（計画書§9本文・§9.3起筆、Geminiクロスレビューで迂回穴を指摘されG1で反映）→domain-implementer（P7-C1で正式起票） ／ 関連仕様§: §10「Calendar/Location/Behavioral Historyを外部LLMへ送らない」・§58〜§60「Telemetryはカレンダー本文等を送信しない」（記録トリガー③仕様推奨からの逸脱：既存ガード3本の「許可リストなし」という設計方針からの意図的な逸脱のため）

- **ADR番号の付番根拠**: ADR-0043と同一バッチ起票。起票直前の`grep`再実測（ADR-0043参照）によりADR-0043の次番としてADR-0044を採番した。

**背景**: 既存のAI隔離ガード3本（`PlanningLlmIsolationTest`／`RecoveryLlmIsolationTest`／`NotificationLlmIsolationTest`、T-BPE-28／T-BRE-32／T-NOTIF-9）は、いずれも「対象ディレクトリ直下の`.kt`を非再帰列挙→禁止語の単純部分文字列マッチ→**許可リストなし**」という設計で統一されている（計画書§9.1）。Phase 7は`ai/model/ModelDownloader.kt`（F88）がモデルファイルのHTTPダウンロードのため正当にネットワークAPI（`java.net.`／`HttpURLConnection`／`URL(`）を参照する必要があり、これは`ai/`配下の他の全ファイル（推論経路）には一切許されない例外である。加えてGeminiクロスレビュー（G1、`model: "gemini-3.5-flash"`）のCRITICAL #1指摘により、「生のネットワークAPIを直接使わない」だけでは不十分で、自プロジェクトが`ai/`の外に持つ通信ラッパークラス（`com.actionstarter.services.routing`配下の`UrlConnectionHttpPostClient`等）を`ai/`から呼び出して迂回する経路も同じ強度で塞ぐ必要があることが判明した（計画書§9本文）。

**決定**: T-AIISO-6を「`ai/`配下（再帰）で、①ネットワークAPI（`java.net.`／`HttpURLConnection`／`URL(`）、②`com.actionstarter.services.routing`配下のimport/参照、のいずれかを参照してよいのは`ai/model/ModelDownloader.kt`の1ファイルのみ」と定義し、これを本プロジェクト初の「許可リストを持つ隔離ガード」として承認する。許可リストの肥大化を防ぐため、以下を本プロジェクトの恒久的な規律として記録する:
1. 許可対象は**単一ファイル名の完全一致**（`ModelDownloader.kt`）に限定し、ワイルドカードやディレクトリ単位の許可は用いない。
2. 将来ネットワークアクセスが必要なクラスが増える場合でも、許可リストへの追加は既定で行わない。まずモデルDL機能自体を`ModelDownloader`へ集約する設計変更を優先的に検討し、それでも真に複数ファイルへの分散が必要な場合のみ、本ADRを改訂したうえで許可リストへ追加する（Fable 5承認必須）。
3. 既存3本のガード（T-BPE-28／T-BRE-32／T-NOTIF-9）は許可リストなしの設計を維持する（本ADRはT-AIISO-6のみのスコープであり、既存ガードの設計方針を変更しない）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 許可リストを持たず、`ai/`配下すべてでネットワークAPI参照を禁止する | `ModelDownloader`（F88、§18「アプリ内ダウンロード方式を基本とする」）がモデルDLを実装できなくなり、仕様§18の要求を満たせない |
| 許可リストを`ai/model/`パッケージ全体（ディレクトリ単位）にする | `ModelCatalog`／`ModelVerifier`／`ModelStorage`／`DeviceCapability`（いずれも`ai/model/`配下）は推論経路に近く、ネットワークアクセスを許すべきでない。ディレクトリ単位の許可は許可リストの実質的な肥大化であり、§10「外部LLMへ送らない」の構造的担保が弱まる |
| ネットワークAPIの直接参照は禁止するが`services.routing`配下のラッパー経由は許可する | Gemini G1 CRITICAL #1が指摘した迂回経路そのものであり、`ai/`の外にある既存HTTP手段を経由すれば実質的にどこからでも送信できてしまう。本ADRが最も重視する「§10外部送信禁止の構造的担保」を無意味にする |

**影響範囲**: `app/src/test/java/com/actionstarter/ai/**`（T-AIISO-6の新設。P7-C2で実装）。既存3本の隔離ガード（T-BPE-28／T-BRE-32／T-NOTIF-9）は許可リスト方針を変更しない（穴A〜Cの改修はP7-C7、計画書§14）。

**検証方法**: T-AIISO-6（`ai/`配下再帰走査、`ModelDownloader.kt`以外がネットワークAPI／`services.routing`参照を持たないことを確認、P7-C2でRed→P7-C7でGreen）。§10 L1（構造）・L2（`StrictMode.penaltyDeath`）・L3（機内モード）の3層検証のうちL1を本ADRが直接担保する。

**再検討トリガー**: 将来、モデルDL以外の`ai/`配下の機能で正当なネットワークアクセスが必要になった場合（例: リモートモデルカタログの動的取得）、許可リストへファイルを追加するのではなく、まず当該機能を`ModelDownloader`または新設の単一専用ファイルへ集約できないかを優先的に検討したうえで、Fable 5承認を得て本ADRを改訂すること。

---

### ADR-0045: LLM出力責務分界の再定義とLocalLanguageModel.generatePlan()契約変更（Semantic Contextualization・AIPlanResponse縮小）

- 日付: 2026-08-10 ／ ステータス: 承認済み（`docs/plans/phase7-quality-harness.md`§0/§2/§5・UQ-1、P7契約確定サイクルのFable 5裁定1・3） ／ 決定者: Fable 5 ／ 起案agent: test-writer（P7-C2完了記録で統合ギャップを発見・報告）→domain-implementer（P7契約確定サイクルで正式起票） ／ 関連仕様§: §13「数値計算は必ず通常コード」・§14「Meaning→Action」・§15「時刻演算をLLMに渡さない」・§16（LocalLanguageModel interface）・§20「Schema validation必須」・§21「action_type/display_text分離」（記録トリガー①interface契約の変更・②仕様未定義箇所の補完に該当）

- **ADR番号の付番根拠**: 起票直前に`grep -n "^### ADR-" DECISIONS.md`を再実測し、最新確定ADRがADR-0044（本書1041行）であることを確認した。P7契約確定タスクの指示（Fable 5裁定1・3、TEAMS §5契約変更フロー）に基づきADR-0044の次番としてADR-0045から起票する。

**背景**: `docs/plans/phase7-quality-harness.md`（品質ハーネス、G1通過）はSemantic Contextualization設計原則を提示し、LLM出力を「分類（`event_type`/`action_type`）」と「予定固有の文脈化された行動文生成（`display_text`）」の2つに最小化することを推奨した（品質ハーネス§0/§2/§5、UQ-1）。P7-C2完了記録（本書§14.3〔`docs/plans/phase7-local-llm-foundation.md`〕相当）は差し戻し事項3として「`LocalLanguageModel.generatePlan()`が`AIPlanResponse`（パース済み）を返す契約と、`SchemaValidator.validate()`が`rawJson: String`を受け取る契約との間の統合ギャップ」を報告していた。Fable 5は品質ハーネスのタスク最小化提案とこの統合ギャップの両方を一体で解決する裁定を下した。

**決定**:
1. `LocalLanguageModel.generatePlan()`の戻り値を`AIPlanResponse`から`String`（LLM生JSONテキスト）へ変更する（§16契約変更、TEAMS §5フロー）。
2. `AIPlanResponse`/`AIPlanStepResponse`のフィールドを縮小する: `AIPlanStepResponse`は`actionType`/`displayText`の2フィールドのみとし、`estimated_minutes`/`priority`/`skippable`/`type`を完全に除去する。
3. 除去したフィールドが表していた数値・判断（所要分・優先度・省略可否・ステップ種別）は、`action_type`からKotlin側が決定的にマップする（Phase 8 `LocalAIPlanningEngine`の責務、計画書§18申し送りへ追記する）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `generatePlan()`の戻り値を`AIPlanResponse`のまま維持し、`SchemaValidator`側に`AIPlanResponse`受け入れ用の別経路を追加する | 統合ギャップの本質的解決にならない。「誰が`AIPlanResponse`を生JSON文字列へ再シリアライズするか」という不要な往復コストが残り、LLM生出力への検証を「パース後のオブジェクトの再検証」という迂遠な形にしてしまう |
| `estimated_minutes`/`priority`/`skippable`をLLM出力に残しつつ「参考値」として扱う（品質ハーネス§2の折衷案） | 品質ハーネスがP7-C2/C3未確定時点で提示した折衷案であり、Fable 5裁定1によりP7-C2/C3の裁定が下った今、折衷を維持する理由がない。LLM出力面積の最小化（捏造面・トークン消費の削減）という品質ハーネスの核心的動機を活かせない |
| `type`フィールドを残す（ステップ種別はLLMに判定させる） | §13「数値計算は必ず通常コード」の趣旨はステップ種別のような決定的に導出可能な分類にも及ぶと解釈し、`action_type`から一意に定まる情報をLLMにもう一度出力させることはトークン浪費かつハルシネーション面を広げるだけで得るものがない |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/LocalLanguageModel.kt`（interface契約）、`app/src/main/java/com/actionstarter/ai/AIPlanResponse.kt`（データクラス縮小）、`app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（実装先の契約更新）、`app/src/test/java/com/actionstarter/ai/LocalAiGatewayTest.kt`（fakeモデルの戻り値型・フィクスチャ更新）、`app/src/test/java/com/actionstarter/ai/schema/SchemaValidatorTest.kt`（フィールド縮小に伴うケース調整）。

**検証方法**: `:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`成功（`build/agent-logs/p7-contract-compile.log`）、`:app:testDebugUnitTest --rerun`でtests=476／failures=51／errors=0／skipped=1（`build/agent-logs/p7-contract-regression.log`。既存417件の回帰0、新規59件のうち51件が意図的Red・8件born-green）。

**再検討トリガー**: P7-C3で`SchemaValidator`/`PlanJsonSchema`を実装する際、上記契約が実際にLiteRT-LMの`ResponseFormat.json()`と整合することを実機/エミュレータで再確認する。Phase 8で`LocalAIPlanningEngine`が`action_type`→`ExecutionStepType`/`StepPriority`/`estimatedMinutes`の決定的マップを実装する際、本ADRの「除去フィールドの行き先」を参照すること。

---

### ADR-0046: event_type/action_typeのenum語彙を確定する（8値・7値、実機プローブでの再検証条項付き）

- 日付: 2026-08-10 ／ ステータス: 承認済み（実機プローブP7-C8での再検証条項付き） ／ 決定者: Fable 5 ／ 起案agent: domain-implementer ／ 関連仕様§: §21「内部意味は英語ID」・§17「モデル名を製品仕様として固定しない」（語彙も同様の再検証余地を持たせる。記録トリガー②仕様未定義箇所の補完に該当）

- **ADR番号の付番根拠**: ADR-0045と同一バッチ起票。起票直前の`grep`再実測（ADR-0045参照）によりADR-0045の次番としてADR-0046を採番した。

**背景**: P7-C2完了記録は差し戻し事項2として「正仕様書§21に`event_type`/`action_type`の確定enum語彙（値の列挙）が存在せず、P7-C2時点では確定できない」ことを報告していた（§21が定めるのは命名規約が英語IDであるべきという方針のみで、`business_meeting`という1例と`check_equipment`という1例を除き閉じた語彙が示されていない）。品質ハーネスは`event_type`の5値例（business_meeting/medical/social/travel/other）とfew-shot例に現れる複数のaction_type例（finish_current_task/prepare_documents/leave/prepare_gift/check_ticket/get_ready等）を提示していたが、これも「確定」ではなく例示にとどまっていた。

**決定**: 以下の語彙をPhase 7の確定値として採用する。
1. `event_type`（8値）: `business_meeting`, `medical`, `social`, `meal`, `travel`, `errand`, `personal`, `other`
2. `action_type`（7値）: `finish_current_task`, `prepare_items`, `get_ready`, `gather_belongings`, `leave`, `commute`, `arrive`（Basic版のBasicPlanningEngineのstep意味論に対応させ、Kotlin側がこれで`skippable`/`priority`/種別を決定的にマップする）
3. Kotlin側の実装として`com.actionstarter.ai.schema.PlanEventType`／`com.actionstarter.ai.schema.PlanActionType`の2 enumを新設し、`PlanJsonSchema.TEXT`のenum制約・`SchemaValidator`のmembership検証双方の単一情報源とする。
4. `display_text`はenum制約の対象外（自由文のまま）とし、Semantic Contextualizationの自由度を維持する。
5. 本語彙は実機プローブ（P7-C8）で過不足を再検証する条項を付す（§17「モデル名を製品仕様として固定しない」と同じ精神——語彙も製品仕様として硬直的に固定しない）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 語彙確定を見送り、P7-C3実装時に開発者が自己判断で決める | TEAMS §5「契約変更はFable 5承認必須」の原則に反する。enum語彙はスキーマ制約・検証ロジックの両方に直接影響する契約そのものであり、P7-C2完了記録も明示的にFable 5確認事項として差し戻していた |
| Basic版のstep意味論（`BasicPlanningEngine`の`semanticId`）をそのまま`action_type`語彙に流用する | Basic版の`semanticId`は決定的な固定文言生成用の内部IDであり、LLMのSemantic Contextualization（予定固有の文脈化された行動）を表現する語彙としては粒度が異なる。Basic版のステップ意味に「対応」させつつも独立した語彙集合として新設した |
| `action_type`の語彙をP7-C0の`LiteRtLmProbeTest`が使った暫定値（例:`prepare_item`等）のまま採用する | P7-C0自身が「プローブ用の暫定値」と明記しており、P7-C1完了記録も正式な契約確定の根拠として使うことを明示的に避けていた |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/schema/PlanJsonSchema.kt`（`PlanEventType`/`PlanActionType` enum新設）、`app/src/test/java/com/actionstarter/ai/schema/SchemaValidatorTest.kt`（T-SCH-2・T-SCH-5・T-RF-1のenum参照更新）。

**検証方法**: T-SCH-2（全`event_type`×`action_type`組み合わせの検証通過）・T-RF-1（`PlanJsonSchema.TEXT`のenum配列が`PlanEventType`/`PlanActionType.JSON_VALUES`と一致）でP7-C3以降回帰ロックする。P7-C8実機プローブで実際のLLM出力語彙との過不足を確認する。

**再検討トリガー**: P7-C8実機プローブで語彙の過不足（LLMが頻繁にenum外の値を出力する、または特定のenum値が実用上不要と判明する等）が見つかった場合、本ADRを改訂する。

---

### ADR-0047: ContentSanityChecker新設と3段検証パイプラインを確定する（SchemaValidatorの責務純化・重複action_type検出の移管）

- 日付: 2026-08-10 ／ ステータス: 承認済み ／ 決定者: Fable 5 ／ 起案agent: domain-implementer ／ 関連仕様§: §20「Schema validation必須」・§34（捏造禁止、品質ハーネス引用）・§13/§15（記録トリガー①interface契約の変更・②仕様未定義箇所の補完に該当）

- **ADR番号の付番根拠**: ADR-0045と同一バッチ起票。起票直前の`grep`再実測（ADR-0045参照）によりADR-0046の次番としてADR-0047を採番した。

**背景**: 品質ハーネス§6は「①形式検証→②内容sanity検証→③retry」の3段構成を提案し（UQ-3で「分離を推奨」）、P7-C2完了記録は「重複`action_type`検出（`uniqueItems`相当）の担当が①か②か未確定」という論点を残していた（品質ハーネス§10 `SchemaValidator`行）。またADR-0045の`generatePlan()`契約変更により、`SchemaValidator`/`ContentSanityChecker`の間の入出力の流れを明文化する必要が生じた。

**決定**:
1. 検証パイプラインを`LLM生JSON(String) → SchemaValidator.validate(rawJson)[①形式] → ContentSanityChecker.check(response, context)[②内容] → LocalAiGatewayがAIPlanResponseを保持`と確定する。
2. `SchemaValidator`は①形式検証＋パースに専念する（enum・件数・長さ・`additionalProperties`）。`SchemaValidationResult.Valid(response: AIPlanResponse)`のまま、raw Stringは別途保持しない。
3. `com.actionstarter.ai.schema.ContentSanityChecker`（新設、TODO本体）を追加し、以下を②の責務として集約する: display_text長さ上限の再確認、禁止語/プレースホルダ検出、捏造検出（数字・時刻・URL）、titleコピー検出（緩和版：title6文字未満は免除、完全一致または80%以上占有のみ不合格）、locale整合、**重複action_type検出**。
4. 重複action_type検出は①（`SchemaValidator`）ではなく②（`ContentSanityChecker`）の責務と確定する（`ResponseFormat.json()`のLLGuidanceが`uniqueItems`をenforceしないため後段Kotlin検証が必須だが、担当は②側とする）。
5. `AiFallbackReason.SCHEMA_INVALID`は①・②いずれの不合格も集約して表す（②専用の新規理由コードは追加しない）。
6. `ContentSanityChecker`は本サイクルでは`LocalAiGateway`のコンストラクタへ配線しない（P7-C5で配線。理由はADR-0048参照）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 重複`action_type`検出を`SchemaValidator`（①）に残す | ①は「decode時の文法制約を独立に再検証する形式検証層」という一貫した責務定義を持つ。重複検出は個々のフィールド制約ではなく複数stepにまたがる意味的な整合性チェックであり、②「内容sanity」の性質に近い。品質ハーネスUQ-3の「形式/内容の責務分離」原則に従い②へ寄せた |
| `ContentSanityChecker`を`SchemaValidator`のメソッドとして統合する（別クラスにしない） | 品質ハーネスUQ-3が「分離を推奨」と結論しFable 5が採用済み。単一クラスに統合すると「decode制約の独立再検証」と「LLM出力の意味的な妥当性判定」という異なる性質の責務が混在し、責務の肥大化・テストの複雑化を招く |
| ②専用の`AiFallbackReason`（例:`CONTENT_INVALID`）を新設する | 品質ハーネス§6のフロー図が「①または②失敗→retry1回→なお失敗→`Fallback(SCHEMA_INVALID)`」と明示しており、呼び出し側（UI等）にとって①②の失敗はいずれも「AI提案の生成に失敗した」という同一の意味を持つ。理由コードを分けても呼び出し側の分岐が増えるだけで実益がない |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/schema/ContentSanityChecker.kt`（新設）、`app/src/main/java/com/actionstarter/ai/schema/SchemaValidator.kt`（責務確定のKDoc更新）、`app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（パイプラインKDoc更新）、`app/src/main/java/com/actionstarter/ai/AiFallbackReason.kt`（`SCHEMA_INVALID` KDoc更新）、`app/src/test/java/com/actionstarter/ai/schema/SchemaValidatorTest.kt`（T-SCH-21削除）。

**検証方法**: `SchemaValidatorTest`からT-SCH-21（重複`action_type`検出）を削除し、対応する検証は将来の`ContentSanityCheckerTest`（未作成、P7-C3以降へ申し送り）へ引き継ぐ。`:app:testDebugUnitTest --rerun`で回帰0を確認済み（tests=476／failures=51／errors=0／skipped=1）。

**再検討トリガー**: P7-C5で`LocalAiGateway`へ`ContentSanityChecker`を配線する際、本ADRのパイプライン順序（①→②→retry）を実装が正しく反映しているかをT-GW-*系のGreen化で確認すること。`ContentSanityChecker`の具体的な検出閾値（80%占有等）はP7-C8実機プローブでの人手評価結果次第で再検討の余地がある（品質ハーネス§8）。

---

### ADR-0048: LocalAiGateway依存4型（ModelStorage/ModelVerifier/DeviceCapability/AiPreferences）をinterface化する

- 日付: 2026-08-10 ／ ステータス: 承認済み ／ 決定者: Fable 5 ／ 起案agent: domain-implementer ／ 関連仕様§: 本プロジェクトの既存DI境界規約（`CalendarService`・`RoutingService`・`LocationService`・`GeocodingService`等、いずれもinterfaceベース）との一貫性（記録トリガー①interface契約の変更に該当）

- **ADR番号の付番根拠**: ADR-0045と同一バッチ起票。起票直前の`grep`再実測（ADR-0045参照）によりADR-0047の次番としてADR-0048を採番した。

**背景**: P7-C2完了記録は差し戻し事項4として「`LocalAiGateway`の4つの具象クラス依存（`ModelStorage`/`ModelVerifier`/`DeviceCapability`/`AiPreferences`）が、本プロジェクトの他の全DI境界（`CalendarService`/`RoutingService`/`NotificationService`/`GeocodingService`/`HttpPostClient`、いずれもinterface）と設計が異なる」ことを報告し、「P7-C5でfake注入性を高める設計変更（interface抽出等）を検討するか、Robolectric実状態操作を正式な方式として採用するかの判断が必要」としていた。

**決定**: `ModelStorage`／`ModelVerifier`／`DeviceCapability`／`AiPreferences`の4型を具象クラスからinterfaceへ変更し、実装を`ModelStorageImpl`／`ModelVerifierImpl`／`DeviceCapabilityImpl`／`AiPreferencesImpl`へ分離する。interfaceは元の型名をそのまま引き継ぎ（既存の`LocalAiGateway`コンストラクタ引数の型シグネチャ・`ai/model/ModelDownloader`の型参照は無変更で成立する）、`AppContainer.kt`の構築箇所（`localAiGateway` by lazy ブロック）のみ`XxxImpl`への構築対象変更を行う。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| Robolectric実Context操作を正式な方式として採用し、interface化しない | 本プロジェクトの他の全DI境界がinterfaceでfake差し替え可能という一貫した規約から本コンポーネント群だけが逸脱したままになる。モック不要な軽量fakeでのテストという選択肢を将来にわたって閉ざす |
| 4型すべてを1つの大きなinterfaceへ統合する | 各型が担う責務（ファイル管理・検証・端末判定・設定永続化）が明確に異なり、統合すると単一責任原則に反する。既存の4クラス分割（P7-C1 scaffold）の設計自体は妥当であり、interface化のみを行う |
| interface名を`IModelStorage`等の接頭辞付きにする | 本プロジェクトの既存interface（`CalendarService`・`RoutingService`等）はいずれも接頭辞なしの命名規約であり、一貫性を優先して元の型名をそのままinterfaceへ引き継いだ |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/model/ModelStorage.kt`・`ModelVerifier.kt`・`DeviceCapability.kt`、`app/src/main/java/com/actionstarter/ai/AiPreferences.kt`（いずれもinterface＋Impl分離）、`app/src/main/java/com/actionstarter/di/AppContainer.kt`（`localAiGateway`構築箇所の型名変更のみ、結線ロジック自体は無変更）、`app/src/test/java/com/actionstarter/ai/model/DeviceCapabilityTest.kt`・`ModelVerifierTest.kt`、`app/src/test/java/com/actionstarter/ai/LocalAiGatewayTest.kt`（構築呼び出しの型名変更）。

**検証方法**: `:app:compileDebugKotlin`成功（`AppContainer.kt`含む本番コード全体のコンパイル）、`:app:compileDebugUnitTestKotlin`成功、`:app:testDebugUnitTest --rerun`で回帰0確認。`AppContainerTest`（T-P4DI-1／T-P6DI-1、`calendarService`/`planningEngine`/`recoveryEngine`とmockファイル非存在のみを検証）は本ADRの変更対象（`localAiGateway`）に触れないため無変更・無影響。

**再検討トリガー**: P7-C4／P7-C5でこれら4型の本体を実装する際、Robolectric実状態操作を続けるか軽量fakeへ切り替えるかは、実装したロジックの複雑度（Robolectric shadowでしか再現できない挙動があるか）を見て判断すること（本ADRはinterface化という前提を確定するのみで、フィクスチャ方式そのものの選択は含まない）。

---

### ADR-0049: retry契約の是正・AiMetrics.sanityPassed追加・PlanPromptBuilder few-shot契約追加、およびP7-C2差し戻し論点（T-GW-11/14/18）の帰属確定

- 日付: 2026-08-10 ／ ステータス: 承認済み ／ 決定者: Fable 5 ／ 起案agent: domain-implementer ／ 関連仕様§: §20「Validation失敗→retry1回→Basic Engine」・§60（Analytics許可リスト）（記録トリガー①interface契約の変更〔PlanPromptBuilderメソッド追加・AiMetricsフィールド追加〕・②仕様未定義箇所の補完に該当）

- **ADR番号の付番根拠**: ADR-0045と同一バッチ起票。起票直前の`grep`再実測（ADR-0045参照）によりADR-0048の次番としてADR-0049を採番した（本バッチADR-0045〜0049で5件。次ADR番号はADR-0050）。

**背景**: 品質ハーネス§0は基盤計画S-2「retryは同一プロンプト・temperature=0.0・seed固定での1回再生成」が論理的に無効（決定的retryは同一失敗を再現する）と指摘し、「新規single-turnセッション＋微小摂動＋静的制約」への是正を提案していた（UQ-2、Gemini G1 CRITICAL #1でさらに精緻化）。また品質ハーネスUQ-5は`AiMetrics.sanityPassed`の追加を、§10は`PlanPromptBuilder.buildSystemInstruction`/`buildFewShot`の追加をそれぞれ提案していた。加えてP7-C2完了記録は差し戻し事項5〜7として、T-GW-11（§12.5配置の妥当性）・T-GW-14（Analyticsコラボレータ追加の承認要否）・T-GW-18（フィクスチャ完成待ち）の3件をFable 5確認事項として残していた。

**決定**:
1. **retry契約の是正**: retryは「新規single-turnセッション（1回目の失敗出力を含む会話履歴を破棄）＋微小摂動（temperature 0.1〜0.2, topK=5程度）＋静的な簡潔化制約文の追加」と確定する（マルチターン自己修正は採らない）。retryの発生判断・呼び出し回数の制御は`LocalAiGateway`（Gateway起点で`model.generatePlan()`を最大2回呼ぶ、既存T-GW-7/T-GW-8の設計を維持）が担い、2回目呼び出し時にどのサンプリング条件を使うかは`LiteRtLmLocalLanguageModel`（P7-C5実装）の内部関心事とする。この2層の役割分担をKDocへ明記した。
2. **`AiMetrics.sanityPassed: Boolean`を追加**する（品質ハーネスUQ-5）。§60許可リストの範囲内（非PII bool値）。
3. **`PlanPromptBuilder`へ`buildSystemInstruction(locale)`／`buildFewShot(locale, shotCount)`を追加**する（品質ハーネス§10、既存`build`の署名は変更しない）。`buildFewShot`の戻り値型は品質ハーネスが提案する`List<com.google.ai.edge.litertlm.Message>`ではなく、ランタイム非依存の`PromptExample`（新設データクラス）とする——`com.google.ai.edge.litertlm`のimportを`ai/adapter/`配下に限定する既存のT-AIISO-9規律（§8.1）を優先したため。
4. **T-GW-11はLocalAiGatewayでなくModelDownloader/Settings領域の責務と確定**する（P7-C2の判断を追認）。
5. **Analytics collaboratorは追加しない**。T-GW-14はPhase 10（`AnalyticsStore`導入）／Phase 12（Analytics実装）とともに実装することを確定し、`LocalAiGateway`のコンストラクタへのワークアラウンド的な追加は行わない。
6. **T-GW-18はP7-C4（ModelStorageファイルレイアウト規約確定時）まで据え置く**ことを確定する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| retryの2回目サンプリング条件を`LocalLanguageModel`interfaceへパラメータとして追加する（例:`generatePlan(context, isRetry: Boolean)`） | §16の`LocalLanguageModel`は「凍結」（計画書全体で繰り返し明記）interfaceであり、本タスクの裁定（1〜8）はいずれもこのinterfaceへ新規パラメータを追加することを明示的に指示していない。パラメータ追加は裁定の対象外の設計変更に当たるため見送り、adapter内部状態での解決余地をP7-C5へ残した |
| `buildFewShot`の戻り値型を品質ハーネス案どおり`List<Message>`にする | `com.google.ai.edge.litertlm`をimportしてよいのは`ai/adapter/`配下のみという既存規律（§8.1・T-AIISO-9、計画書§9.3で確定済みの構造的制約）と直接衝突する。`ai/prompt/`パッケージがランタイム型に依存すると、次点のllama.cpp案への切替時に`ai/adapter/`の差し替えだけでは済まなくなり、§16「モデルは技術検証で交換可能にする」という上位契約を壊す |
| T-GW-14のために`LocalAiGateway`のコンストラクタへ`analyticsRecorder: ((AiFallbackReason) -> Unit)? = null`のような任意コラボレータを先回りで追加する | 本タスクの制約「`AppContainer`は裁定5のinterface化に必要な最小変更のみ可」を超える。Analytics基盤（Phase 10/12）の設計と無関係に器だけ先行させると、実際のAnalytics実装時に器の再設計が必要になるリスクがある（YAGNI） |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（`AiMetrics.sanityPassed`追加、パイプラインKDoc更新）、`app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（retry契約のKDoc明記）、`app/src/main/java/com/actionstarter/ai/prompt/PlanPromptBuilder.kt`（`buildSystemInstruction`/`buildFewShot`/`PromptExample`新設）、`app/src/test/java/com/actionstarter/ai/AiMetricsTest.kt`（期待フィールド集合更新）、`app/src/test/java/com/actionstarter/ai/LocalAiGatewayTest.kt`（T-GW-11/14/18対象外理由のKDoc更新）。

**検証方法**: `AiMetricsTest.fieldNames_matchConfirmedAllowList`（9フィールドへ更新、born-green維持）。`PlanPromptBuilderTest`は既存7件（T-PRM-1〜7、`build`のみ対象）を無変更のままRed維持していることを確認済み（`buildSystemInstruction`/`buildFewShot`の新規Redテストは本サイクルの対象外、次サイクルへ申し送り）。

---

### ADR-0050: `LocalLanguageModel.generatePlan()`へ`samplingPolicy: SamplingPolicy`引数を追加し、`SamplingPolicy` enumを新設する（Fable 5裁定9、ADR-0049の一部却下判断を覆す）

- 日付: 2026-08-10 ／ ステータス: 承認済み ／ 決定者: Fable 5（追加裁定9） ／ 起案agent: test-writer（P7-C2c、品質ハーネス由来の新設部品へのRed補完サイクル） ／ 関連仕様§: §16（`LocalLanguageModel` interface）・§20「Validation失敗→retry1回→Basic Engine」・品質ハーネス§4「サンプリング設計」・§6「3段検証＋再試行1回」（記録トリガー①interface契約の変更に該当）

- **ADR番号の付番根拠**: 起票直前に`grep -n "^### ADR-" DECISIONS.md`を再実測し、最新確定ADRがADR-0049（本書1188行）であることを確認した。ADR-0049の背景欄が「次ADR番号はADR-0050」と明記済みのため、その次番としてADR-0050を採番する。

**背景**: 品質ハーネス§10は`LocalAiGateway`のretry是正として「2回目呼び出し時にどのサンプリング条件を使うか」を要求していたが、ADR-0049はこれを「retryの発生判断・呼び出し回数の制御は`LocalAiGateway`が担い、2回目呼び出し時にどのサンプリング条件を使うかは`LiteRtLmLocalLanguageModel`（P7-C5実装）の内部関心事とする」と裁定し、**`LocalLanguageModel`interfaceへのパラメータ追加は明示的に却下**していた（ADR-0049「代替案と却下理由」表1行目、理由:「§16の`LocalLanguageModel`は凍結interfaceであり、裁定1〜8はいずれもこのinterfaceへ新規パラメータを追加することを明示的に指示していない」）。この却下により、`LiteRtLmLocalLanguageModel`のKDocには「Gateway起点の『これは何回目の呼び出しか』をどう本クラスへ伝えるかは未確定のまま残す」という未解決の設計課題が残置されていた（`docs/plans/phase7-local-llm-foundation.md`§14.4申し送り5）。

P7-C2c（品質ハーネス由来の新設部品へのRed補完サイクル）の指示として、Fable 5が追加裁定9を下し、この却下判断を明示的に覆した。理由: adapter内部でのカウンタ追跡（ADR-0049が想定した代替案）は、adapterが「検証の成否」という本来Gatewayの関心事に属する情報を推測して持つことになり責務が曖昧になる。Gatewayが検証結果に基づき使用する[SamplingPolicy]を型で明示的に指定する設計の方が、責務分担（Gateway=方針決定、adapter=方針に従うだけ）がより明確である。

**決定**:
1. `SamplingPolicy` enumを`ai/`パッケージ直下へ新設する（`Primary(topK=1, temperature=0.0, appendConcisenessConstraint=false)`・`Retry(topK=5, temperature=0.15, appendConcisenessConstraint=true)`の2値、品質ハーネス§4準拠）。ランタイム非依存（`com.google.ai.edge.litertlm`をimportしない）とし、T-AIISO-9規律と衝突しない。
2. `LocalLanguageModel.generatePlan`のシグネチャを`generatePlan(context: PlanningContext, samplingPolicy: SamplingPolicy = SamplingPolicy.Primary): String`へ変更する（§16契約変更、TEAMS §5フロー）。
3. `LocalAiGateway`が1回目=`SamplingPolicy.Primary`、検証パイプライン（`SchemaValidator`→`ContentSanityChecker`）不合格による2回目=`SamplingPolicy.Retry`で`model.generatePlan`を呼び分ける契約とする（この呼び分けはGatewayの責務。adapterは検証を知らず渡された方針に従うだけ）。
4. `LiteRtLmLocalLanguageModel`は渡された`SamplingPolicy`の`topK`／`temperature`を実際の`SamplerConfig`へマップし、`appendConcisenessConstraint=true`のときのみdata message末尾に固定簡潔化制約文を追記する（マップの具体値・実装自体はP7-C5、本ADRはscaffold契約のみ確定する）。
5. `AppContainer`は変更不要と確認する（`generatePlan`の呼び出し箇所が存在せず、既定値`SamplingPolicy.Primary`で解決されるため）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| ADR-0049の判断を維持し、`LiteRtLmLocalLanguageModel`が内部カウンタで「これは何回目の呼び出しか」を追跡する | Gateway側が既に検証結果（①②合否）を知っているにもかかわらず、adapter側でそれを「呼び出し回数」という間接的な代理指標から再推測させる設計になる。Gatewayが並行呼び出しをMutexで直列化する設計（T-GW-15）と組み合わさると、内部カウンタの状態管理がGatewayのライフサイクルと暗黙に同期していなければならず、責務境界が曖昧になる |
| `samplingPolicy`を`Boolean`（`isRetry`）で表現する | ADR-0049が却下理由に挙げた案そのもの。加えて、`Boolean`では品質ハーネス§4が定めるtopK/temperatureの具体値・簡潔化制約フラグをadapter側に暗黙の対応表として持たせる必要があり、契約が型で表現されず可読性・テスト容易性の両方で劣る |
| `SamplingPolicy`の`topK`/`temperature`に加えて`topP`/`seed`もscaffoldへ含める | 品質ハーネス§4は`topP`/`seed`の具体値にも言及するが、これらはLiteRT-LMの`SamplerConfig`への実際のマッピング（P7-C5の実装詳細）に属し、Gateway/interfaceレベルの契約としては`topK`/`temperature`/`appendConcisenessConstraint`で十分。過剰な先回りはP7-C5の設計自由度を不必要に縛る（YAGNI） |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/SamplingPolicy.kt`（新設）、`app/src/main/java/com/actionstarter/ai/LocalLanguageModel.kt`（`generatePlan`シグネチャ変更・KDoc更新）、`app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（overrideシグネチャ追随・KDoc更新）、`app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（パイプラインKDoc更新、本体`TODO()`は無変更）、`app/src/main/java/com/actionstarter/ai/schema/ContentSanityChecker.kt`・`app/src/main/java/com/actionstarter/ai/prompt/PlanPromptBuilder.kt`（「Redテスト未作成」というKDoc記述の是正）、`app/src/test/java/com/actionstarter/ai/SamplingPolicyTest.kt`（新設）、`app/src/test/java/com/actionstarter/ai/schema/ContentSanityCheckerTest.kt`（新設）、`app/src/test/java/com/actionstarter/ai/prompt/PlanPromptBuilderTest.kt`（QH-8・QH-14相当8件追加）、`app/src/test/java/com/actionstarter/ai/LocalAiGatewayTest.kt`（フェイクのoverride署名追随、T-GW-19・T-GW-20追加）。`AppContainer.kt`は変更なし。

**検証方法**: `:app:compileDebugKotlin`／`:app:compileDebugUnitTestKotlin`成功（`build/agent-logs/p7c2c-compile.log`）。新設・更新4クラスの個別実行で50件中46件Red（45件`NotImplementedError`＋既存T-GW-13由来の`AssertionError`1件、本ADRと無関係）・4件born-green（`build/agent-logs/p7c2c-red.log`）。`:app:testDebugUnitTest --rerun`でtests=505／failures=76／errors=0／skipped=1、既存476件の回帰0（`build/agent-logs/p7c2c-regression.log`）。詳細は`docs/plans/phase7-local-llm-foundation.md`§14.5参照。

**再検討トリガー**: P7-C3以降のGreen実装で`LocalAiGateway`が実際にPrimary→Retryの呼び分けを実装する際、T-GW-19・T-GW-20がその実装を正しく回帰ロックしているかを確認すること。P7-C5で`LiteRtLmLocalLanguageModel`が`SamplingPolicy`を実際の`SamplerConfig`へマップする際、`topP`/`seed`の具体値をどう決定するかは本ADRの対象外として残る（次ADRで確定すること）。

---

### ADR-0051: `LocalAiGateway`のP7-C3 Green実装スコープを確定する（`ContentSanityChecker`直接配線・`modelStorage`/`modelVerifier`チェックのP7-C4延期）

- 日付: 2026-08-10 ／ ステータス: 承認済み（domain-implementer判断・報告事項、タスク指示「配線がAppContainer等の凍結ファイルに波及するなら延期して報告」の枠内） ／ 決定者: domain-implementer（P7-C3、Fable 5への報告を前提とした実装時判断） ／ 起案agent: domain-implementer ／ 関連仕様§: §20「Schema validation必須」・§8.6発動条件表・ADR-0047（3段検証パイプライン）・ADR-0048（4型のinterface化）（記録トリガー②仕様未定義箇所の補完に該当。既存interfaceのシグネチャは無変更のため①契約変更には該当しない）

- **ADR番号の付番根拠**: 起票直前に`grep -n "^### ADR-" DECISIONS.md`を再実測し、最新確定ADRがADR-0050（本書1218行）であることを確認した。その次番としてADR-0051を採番する。

**背景**: P7-C3（Green実装）のタスク指示は、`ContentSanityChecker`の`LocalAiGateway`への実配線を「Gatewayの3段検証Greenに必要なら計画書§14 P7-C3の範囲で配線し、判断を報告——配線がAppContainer等の凍結ファイルに波及するなら延期して報告」という条件付きで許可していた（ADR-0047は当初この配線をP7-C5としていた）。実装に着手したところ、以下2点の設計判断が必要になった。

1. **`ContentSanityChecker`／`SchemaValidator`の配線方法**: 両クラスはいずれもコンストラクタ引数を持たない状態レス（純粋関数的）クラスであり、`LocalAiGatewayTest`もこれらをfakeへ差し替える手段を一切要求していない（fakeで差し替える必要があるのは§16凍結interfaceの`LocalLanguageModel`のみ）。
2. **`modelStorage`（F90・§8.6 #11「モデル未導入」）・`modelVerifier`（F89・§8.6 #12「ロード前再検証」）チェックの配線可否**: `LocalAiGatewayTest`の`installedModelStorage()`ヘルパーは、同テストのクラスKDoc（P7-C2c時点で既に記載済み）が明記するとおり`notInstalledModelStorage()`と**同一の未初期化`ModelStorageImpl`インスタンス**を返す「意図表明のみのプレースホルダ」である。`ModelStorageImpl`のファイル配置規約はP7-C4で確定する契約（ADR-0048）であり、本サイクルの対象範囲外。`modelStorage.installedModelPath()`を`generatePlan()`から呼び出すと、この未実装呼び出しが**「導入済み」を意図したフィクスチャも含め全T-GW-*ケースで無条件に`NotImplementedError`を送出**し、5系統フォールバック（T-GW-1・4〜10・12・13・15・17・19・20、14件）が1件もGreen化できなくなることが実装検証で判明した。

**決定**:
1. **`ContentSanityChecker`・`SchemaValidator`は`LocalAiGateway`のコンストラクタへ注入せず、private フィールドとして直接インスタンス化する**（`private val schemaValidator = SchemaValidator()`・`private val contentSanityChecker = ContentSanityChecker()`）。この設計により、両クラスの利用開始に`AppContainer.kt`の変更は一切不要となった（凍結ファイルへの波及なし。タスク指示の条件を満たし配線を実施）。
2. **`modelStorage.installedModelPath()`（§8.6 #11）・`modelVerifier`によるロード前再検証（§8.6 #12）は、`LocalAiGateway.generatePlan()`の実行パスから本サイクルでは意図的に除外する**。`modelStorage`／`modelVerifier`はコンストラクタ引数として維持し（テストが構築時に渡すためシグネチャは変更不可）、P7-C4で`ModelStorage`のファイル配置規約が確定した時点でP7-C5がこの2ステップを実装する受け皿として残す。
3. 上記2の結果として、**T-GW-3**（モデル未導入→`Fallback(MODEL_NOT_INSTALLED)`）は本サイクルではGreen化の対象外とする。失敗の性質は`NotImplementedError`（`LocalAiGateway`最上位の`TODO()`起因）から`AssertionError`（`installedModelStorage()`使用時に`model.generatePlan`が実行され`AiResult.Success`が返るため、`Fallback`を期待するアサーションに反する）へ変わるが、依然としてRedのままである。**T-GW-18**（ロード前再検証失敗→`MODEL_CORRUPTED`）はADR-0049裁定8により元々P7-C4まで据え置き確定済みであり（テストメソッド自体が未作成）、本決定と整合する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `modelStorage.installedModelPath()`の呼び出しを`try/catch`で`NotImplementedError`のみ捕捉し「導入済みとみなして続行」する | 「未実装であること」を「モデルが導入済みであること」の代理シグナルとして扱う設計であり、意味的に誤り（本番環境で`ModelStorageImpl`が正しく実装された後もこの`catch`節が予期せず生き残るリスクがあるサイレント障害の温床）。§95.6「サイレントに握り潰さない」の精神に反する |
| `ModelStorageImpl.installedModelPath()`を本サイクルで最小実装する（例: `noBackupFilesDir/models/`配下に1件でもファイルがあれば非null） | ファイル配置規約（`.part`拡張子・原子的リネーム・複数モデル対応時の命名等）はP7-C4のスコープとして明示的に計画書へ切り出されており、本タスクの対象範囲（1〜8の列挙）にも含まれない。`LocalAiGatewayTest`自身のKDocも「本ヘルパーは...P7-C4・P7-C5が上記2点の内部規約を確定させた時点で...実際にファイルを配置する形へ更新する必要がある」と将来更新を明示しており、テスト側の意図に反してまで自己判断でファイルI/Oを組み立てることは「計画書のケースが曖昧でテスト化できない場合は差し戻し報告」という同テストの既定方針（T-GW-11/14/18で採用済み）と整合しない |
| `SchemaValidator`／`ContentSanityChecker`を`LocalAiGateway`のコンストラクタへデフォルト引数付きで追加する（`schemaValidator: SchemaValidator = SchemaValidator()`等） | `AppContainer.kt`の呼び出し側が名前付き引数のみを使用しているため技術的には無変更で成立するが、状態を持たない純粋関数的クラスをテストがfake差し替えを一切要求していない以上、コンストラクタへ公開する動機がない（YAGNI）。privateフィールドとして直接保持する方が依存グラフが単純になる |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（`generatePlan`本体実装、`schemaValidator`／`contentSanityChecker`のprivateフィールド追加）。`app/src/main/java/com/actionstarter/di/AppContainer.kt`は**無変更**（本ADRの主要な結論の1つ）。`app/src/test/java/com/actionstarter/ai/LocalAiGatewayTest.kt`は無変更（既存フィクスチャそのままでT-GW-1・4〜10・12・13・15・17・19・20がGreen化、T-GW-3のみ引き続きRed）。

**検証方法**: `:app:testDebugUnitTest --tests "com.actionstarter.ai.LocalAiGatewayTest"`実測で16件中15件Green・1件（T-GW-3）Red（`AssertionError`、`build/agent-logs/p7c3-green-LocalAiGateway.log`）。`:app:testDebugUnitTest --rerun`全体でtests=505／failures=1（T-GW-3のみ）／errors=0／skipped=1、既存417件（416 pass+1 skip）の回帰0（`build/agent-logs/p7c3-full.log`、JUnit XML集計で失敗クラスが`LocalAiGatewayTest`1件のみであることを確認済み）。

**再検討トリガー**: P7-C4で`ModelStorage`のファイル配置規約が確定した時点で、`LocalAiGateway.generatePlan()`へ§8.6 #11（`modelStorage.installedModelPath()`チェック）・#12（`modelVerifier`ロード前再検証）を配線し、`LocalAiGatewayTest`の`installedModelStorage()`ヘルパーを実際にファイルを配置する形へ更新してT-GW-3をGreen化すること（`LocalAiGatewayTest`自身のKDocが既に明記している更新要求）。同時にT-GW-18（ADR-0049裁定8）のフィクスチャ完成も検討すること。

---

### ADR-0052: `AiPreferencesImpl`の最小実装をP7-C6からP7-C3へ前倒しする（`LocalAiGateway`のGreen化に必要な最小範囲）

- 日付: 2026-08-10 ／ ステータス: 承認済み（domain-implementer判断・報告事項） ／ 決定者: domain-implementer（P7-C3、Fable 5への報告を前提とした実装時判断） ／ 起案agent: domain-implementer ／ 関連仕様§: §19「AI OFFが既定」（記録トリガー②仕様未定義箇所の補完に該当。`AiPreferences`interface自体〔ADR-0048〕・KDocが指定する実装内容には一切矛盾しないため新規の仕様判断は伴わない）

- **ADR番号の付番根拠**: ADR-0051と同一バッチ起票。起票直前の`grep`再実測（ADR-0051参照）によりADR-0051の次番としてADR-0052を採番した。

**背景**: 計画書§14サイクル表はF92（`AiPreferences`）をP7-C6（Green: settings）の担当としているが、`LocalAiGateway.generatePlan()`が実行時に確認する順序の最初のステップ（§8.6 #10「AI OFF判定」）は`preferences.aiEnabled`を読む。P7-C1完了時点の`AiPreferencesImpl.aiEnabled`は`TODO()`（P7-C6実装予定）のままであり、これを未実装のまま残すと、`LocalAiGatewayTest`の**全16ケース**（AI ONを期待するケースもAI OFFを期待するケースも等しく）が最初のガードで`NotImplementedError`となり、本タスクが対象とする「5系統フォールバック」「3段検証パイプライン」「retry呼び分け」のいずれも実測・Green化できないことが実装検証で判明した。

**決定**: `AiPreferencesImpl.aiEnabled`（get/set）・`selectedModelId`（get/set）の2プロパティを、既存のTODOコメントが一字一句指定する実装（`SharedPreferences.getBoolean(KEY_AI_ENABLED, DEFAULT_AI_ENABLED)`／`.edit().putBoolean(KEY_AI_ENABLED, value).apply()`、`getString`/`putString`の対も同型）どおりにP7-C3の範囲内で実装する。両プロパティとも設計上の曖昧さを一切伴わない（`AiPreferences`interfaceのKDoc・`AiPreferencesImpl`のコンストラクタKDocが実装内容を既に確定済みであり、P7-C3で新たに決める事項がない）ため、ContentSanityCheckerのGateway配線（ADR-0051）と同じ「Greenに必要な最小実装」の扱いとする。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `AiPreferencesImpl`を`TODO()`のまま残し、`LocalAiGatewayTest`全16ケースをP7-C6まで意図的にRed維持する | 本タスクの主目的（LocalAiGatewayの5系統フォールバック・3段検証パイプライン・retry呼び分けをJVMでGreen化する）が一切達成できなくなる。5系統フォールバックはT-GW-1・4〜10・12・13・15・17・19・20の14件に跨っており、これらすべてを次サイクルへ丸ごと先送りすることは本タスクの趣旨（実装可能な範囲を最大化する）に反する |
| `LocalAiGateway`内で`preferences.aiEnabled`の代わりに独自のフォールバック値（例: `try { preferences.aiEnabled } catch (e: NotImplementedError) { true }`）を使う | ADR-0051が却下した「`NotImplementedError`を制御フローの代理シグナルとして扱う」パターンと同種の問題を抱える。本番で`AiPreferencesImpl`が正しく実装された後も検出されにくいサイレント障害の温床になり得る |
| `AiPreferences`をP7-C3専用のfake実装（`FakeAiPreferences`等）に差し替えるようテスト側の変更を提案する | 本タスクは「テスト側の変更禁止（テストが誤りと判断したら変更せず報告）」を明示しており、`LocalAiGatewayTest`は`AiPreferencesImpl`を直接構築する設計を意図的に採っている（クラスKDoc「Robolectric実Context・実SharedPreferences・実shadowで状態を制御する既存方式は維持」）。テストの意図を尊重し、本番実装側を完成させる方を選んだ |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/AiPreferences.kt`（`AiPreferencesImpl.aiEnabled`／`selectedModelId`のTODO本体を実装）。P7-C6が対象とする他のF92/F97関連（Settings画面・`T-SET-*`）には触れていない。

**検証方法**: `:app:testDebugUnitTest --tests "com.actionstarter.ai.LocalAiGatewayTest"`実測でT-GW-2（AI OFF→`Fallback(AI_DISABLED)`、`model.generatePlanCallCount==0`）を含む16件中15件がGreen化したことを確認（`build/agent-logs/p7c3-green-LocalAiGateway.log`）。`:app:lintDebug`はBUILD SUCCESSFUL・error 0（`SharedPreferences.edit`のKTX化を促す警告1件のみ、既存`SharedPreferencesExecutionScheduleStore`と同型のため許容、`build/agent-logs/p7c3-lint.log`）。

**再検討トリガー**: P7-C6でSettings画面（F97）を実装する際、T-SET-1（初回起動時`aiEnabled==false`）・T-SET-2（トグルON永続化）が本実装で正しく満たされることを確認すること（本ADRの実装は`AiPreferences`interfaceの契約どおりであり、T-SET-1〜2の期待値と矛盾しないと判断済みだが、P7-C6側での再確認を推奨する）。

**再検討トリガー**: P7-C5実装時、adapterがGateway起点の「これは何回目の呼び出しか」をどう判断するか（内部カウンタ等）を確定した時点で、本ADRの該当箇所（決定1）を実装詳細として追記すること。T-GW-14はPhase 10の`AnalyticsStore`設計時に本ADRを参照し、コラボレータの正式な注入方式を設計すること。

---

### ADR-0053: `ModelStorage`のファイル配置規約を確定し、`LocalAiGateway`へ§8.6 #11/#12を配線する（ADR-0051の再検討トリガーへの回答）

- 日付: 2026-08-10 ／ ステータス: 承認済み（domain-implementer判断・報告事項、P7-C4タスク指示「モデルファイルの配置規約を確定」の枠内） ／ 決定者: domain-implementer（P7-C4、Fable 5への報告を前提とした実装時判断） ／ 起案agent: domain-implementer ／ 関連仕様§: §95.6「ダウンロード開始前にStatFsで空き容量を検証」・§8.6発動条件表#11/#12・ADR-0048（4型のinterface化）・ADR-0051（P7-C4への延期決定、再検討トリガー）（記録トリガー②仕様未定義箇所の補完に該当。`ModelStorage`interfaceへの`installedEntry()`追加は既存メソッドの署名を変更しない後方互換な追加のみのため①契約変更には該当しない）

- **ADR番号の付番根拠**: 起票直前に`grep -n "^### ADR-" DECISIONS.md`を再実測し、最新確定ADRがADR-0052（本書1283行）であることを確認した。その次番としてADR-0053を採番する。

**背景**: P7-C3は`ModelStorage`のファイル配置規約が未確定であることを理由に、`LocalAiGateway.generatePlan()`から§8.6 #11（モデル未導入判定）・#12（ロード前SHA-256再検証）を意図的に除外し（ADR-0051）、結果としてT-GW-3が唯一のRedとして残った。ADR-0051の再検討トリガーは「P7-C4で`ModelStorage`のファイル配置規約が確定した時点で配線する」ことを明記していた。本ADRはその配置規約を確定し、配線を完了する。

**決定**:
1. **保存先**: `context.noBackupFilesDir/models/`固定（既存interfaceのKDoc・T-MDL-14の要求どおり）。ファイル名は`<ModelCatalogEntry.id>.litertlm`（正式配置、[ModelStorage.finalFile]）／`<id>.litertlm.part`（DL中一時ファイル、[ModelStorage.partFile]）。
2. **「導入済み」の解決方法**: `ModelStorageImpl`はコンストラクタで`catalog: List<ModelCatalogEntry>`（既定`ModelCatalog.ALL`）を新たに受け取り、`installedEntry()`はこの`catalog`を順に走査して`finalFile(entry)`が実在する最初のエントリを返す（`installedModelPath()`は内部でこれを使い、`installedEntry()?.let { finalFile(it).absolutePath }`として実装）。Phase 7時点は`catalog`が単一エントリのため事実上の二値判定と同義だが、走査ベースにすることで①複数モデル対応時のコード変更が最小で済む（§17「モデル名を製品仕様として固定しない」の交換可能性）、②テストが本番の`ModelCatalog.ALL`（実モデル328MB・SHA-256実測値`e3e290...`）を経由せず、`catalog`引数を差し替えて小さなfixtureエントリで「導入済み」状態を作れる（328MBファイルの実体を持たずに本物の`ModelVerifierImpl`によるSHA-256照合を高速に完走できる）、という2つの利点を得る。
3. `ModelStorage`interfaceへ`installedEntry(): ModelCatalogEntry?`を新設した（既存`installedModelPath(): String?`はシグネチャ・意味とも無変更で維持）。`LocalAiGateway`が§8.6 #12の再検証で必要とする「期待値」（`ModelCatalogEntry.sizeBytes`／`sha256`）を得るための最小の追加。
4. **`LocalAiGateway.generatePlan()`の配線**: `isAbiSupported()`チェックとOOM事前ガード（§8.6 #7）の間、`inferenceMutex`内の先頭で新設private関数`checkInstalledModel()`を呼ぶ。
   - `modelStorage.installedEntry()`が`null` → `Fallback(MODEL_NOT_INSTALLED)`（§8.6 #11、T-GW-3）
   - 毎回: `modelStorage.finalFile(entry)`（`installedEntry()`と同一の解決結果を再利用し、`installedModelPath()`を別途呼ぶことによる二重走査・TOCTOUの窓を避ける）のファイルサイズと`entry.sizeBytes`を照合。不一致 → `modelStorage.delete(entry)`のうえ`Fallback(MODEL_CORRUPTED)`
   - プロセス内（`LocalAiGateway`インスタンス単位、`AppContainer`が`by lazy`で1個のみ生成するためプロセス寿命と一致）で当該エントリが未検証のときのみ`modelVerifier.verify()`を呼び、結果を`sha256VerifiedEntryId`（`entry.id`）へキャッシュする（§8.6 #12「以後の呼び出しでは再計算しない」、Gemini G1 CRITICAL #2、T-GW-18）。不一致 → 同様に削除＋`Fallback(MODEL_CORRUPTED)`
5. **OOM事前ガードの動的化（副次的な改善）**: 従来`LocalAiGateway`の companion object が`ModelCatalog.QWEN3_0_6B_INT4_BLOCK32.peakRamBytes`を固定的に参照していたが、`checkInstalledModel()`が解決した実際の導入済み`entry.peakRamBytes`を使う形へ改めた（§17「モデル名を製品仕様として固定しない」との整合、および`ModelStorage`のテストfixtureエントリでもOOMガードが正しい値で動作するようにするため）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `installedEntry()`を新設せず、`installedModelPath()`のみで済ませ、`LocalAiGateway`側で`ModelCatalog.QWEN3_0_6B_INT4_BLOCK32`を再検証対象として直接ハードコードする | テストが「導入済み」状態を作るために実モデル328MB・SHA-256実測値`e3e290...`と一致する巨大ファイルを用意する必要が生じ、実用的な速度でのJVMテストが成立しない。`LocalAiGatewayTest`の既存14ケース（T-GW-1・4〜10・12・13・15・17・19・20）すべてに影響する |
| `ModelStorage`にentryを持たせず、`LocalAiGateway`のコンストラクタへ`modelCatalogEntry: ModelCatalogEntry`パラメータを追加して直接注入する | 既存16件のGateway構築呼び出し（`LocalAiGatewayTest`内）すべてに新引数を追加する必要が生じ、タスクが許可する変更範囲（`installedModelStorage()`/`notInstalledModelStorage()`ヘルパーのみ）を超える。`catalog`を`ModelStorage`側に持たせる方が、変更をヘルパー2つに閉じ込められる |
| SHA-256の「プロセス内キャッシュ」を`ModelVerifierImpl`自身に実装する | `ModelVerifierImpl`のKDoc（P7-C3確定）が「本クラス単体は毎回計算する契約のまま。呼び出し側が同一インスタンスを再利用しキャッシュするかを決める」と既に明記しており、キャッシュの要否は呼び出し側（`LocalAiGateway`）の設計判断であるべき。`ModelVerifier`interfaceを状態依存にすると、他の呼び出し元（将来の`ModelDownloader`のDL直後検証、ADR-0054）が意図せずキャッシュの影響を受けるリスクがある |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/model/ModelStorage.kt`（interface拡張・`ModelStorageImpl`全実装）、`app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（`generatePlan`内の#11/#12配線・`sha256VerifiedEntryId`フィールド追加・OOM必要RAMの動的化）、`app/src/main/java/com/actionstarter/di/AppContainer.kt`（`modelStorage`をローカル変数からプロパティへ昇格、[modelDownloader]と共有するため）、`app/src/test/java/com/actionstarter/ai/LocalAiGatewayTest.kt`（`installedModelStorage()`/`notInstalledModelStorage()`ヘルパー更新・T-GW-18a/b新設。ADR-0051が予告した承認済み変更範囲）。新設`app/src/test/java/com/actionstarter/ai/model/ModelStorageTest.kt`（T-MDL-4〜5・12〜15、11件）。

**検証方法**: `:app:testDebugUnitTest --tests "com.actionstarter.ai.model.ModelStorageTest"`実測で11/11 Green（`build/agent-logs/p7c4-green-ModelStorage.log`）。`:app:testDebugUnitTest --tests "com.actionstarter.ai.LocalAiGatewayTest"`実測で18/18 Green（T-GW-3・T-GW-18a・T-GW-18b含む、`build/agent-logs/p7c4-green-LocalAiGateway.log`）。`:app:testDebugUnitTest --rerun`全体でtests=528/failures=0/errors=0/skipped=1（`build/agent-logs/p7c4-full.log`、既存417件＋P7-C2〜C2c追加88件＋P7-C4新規23件の合計と一致）。`:app:lintDebug --rerun-tasks`はBUILD SUCCESSFUL・error 0（新規warning 0件、既存22件は`AiPreferences.kt`・`SharedPreferencesExecutionScheduleStore.kt`由来でP7-C4のコード変更に起因するものはゼロ、`build/agent-logs/p7c4-lint.log`）。

**再検討トリガー**: P7-C6で`AiPreferences.selectedModelId`による複数モデル選択が実装された時点で、`installedEntry()`の走査基準を「`catalog`全走査（先頭一致）」から「`selectedModelId`に一致する単一エントリ」へ絞ることを検討する（現状は単一カタログのため実害はないが、複数モデル導入時は「導入済みだが選択されていないモデル」の扱いを明確にする必要がある）。

---

### ADR-0054: `ModelDownloader`を実装する：容量ガードは`ModelStorage`（`DeviceCapability`ではない）が担い、DL完了後の検証・コミットまでを一体のパイプラインとする

- 日付: 2026-08-10 ／ ステータス: 承認済み（domain-implementer判断・報告事項、P7-C4タスク指示の枠内での訂正） ／ 決定者: domain-implementer（P7-C4、Fable 5への報告を前提とした実装時判断） ／ 起案agent: domain-implementer ／ 関連仕様§: §18「ダウンロード開始前にストレージ空き容量の事前検証を必須」・§95.6・§8.6発動条件表#3/#5・§9本文（T-AIISO-6・ADR-0044の許可リスト）（記録トリガー②仕様未定義箇所の補完、および担当タスク指示文言とプロジェクト既存契約〔`ModelStorage.hasSufficientSpace`〕との不整合の訂正に該当。`ModelDownloader`の公開APIシグネチャ〔`download(entry, onProgress)`〕自体は無変更のため①契約変更には該当しない）

- **ADR番号の付番根拠**: ADR-0053と同一バッチ起票。起票直前の`grep`再実測（ADR-0053参照）によりADR-0053の次番としてADR-0054を採番した。

**背景**: 本サイクルのタスク指示は「`ModelDownloader`: HTTPダウンロード...・`ModelVerifier`でSHA-256検証・**`DeviceCapability`で容量ガード**（空き容量不足→DL拒否）・破損/検証失敗時は削除して`Fallback`/再DL導線」と記述していたが、実装着手前に既存契約を確認したところ、以下の訂正が必要と判明した。

1. **容量ガードの担当コラボレータ**: `DeviceCapability`interface（F91、`ai/model/DeviceCapability.kt`）は`classify()`／`isAbiSupported()`／`hasAvailableMemory(requiredBytes: Long)`の3メソッドのみを持ち、いずれもRAM総量・ABI・空きRAMの判定用であり、ストレージ空き容量の概念を一切持たない（同ファイルのKDoc「§5.3の段階判定」「§8.6 #7の主防御」参照）。一方`ModelStorage`interface（F90）は既存scaffold（P7-C1）の時点で`hasSufficientSpace(requiredBytes: Long): Boolean`を「StatFsベースの容量ガード」用メソッドとして既に確定・KDoc化しており（「`[requiredBytes]×[CAPACITY_SAFETY_FACTOR]`の空き容量が確保できるか（§95.6）」）、計画書§8.6 #3の検知方法列も一貫して`StatFs(noBackupFilesDir).availableBytes`をModelStorageの管轄としている。したがって実際の容量ガードは`ModelStorage.hasSufficientSpace`を使う実装とし、タスク指示の「`DeviceCapability`で容量ガード」という記述は責務の取り違え（誤記）と判断して訂正した。タスク指示自身が「計画書§8・§14 P7-C4が正」と明記しているため、既存の`ModelStorage`interface契約（計画書の実体）を優先した。
2. **DL完了後の検証・コミットの帰属**: §8.6 #5「検証失敗（改竄・破損。DL完了直後の1回目検証）」の判定タイミングは「DL完了直後（`.part`→正式名リネーム前）」であり、`ModelDownloader`の「Download」責務（F88）と時系列上ほぼ不可分である。タスク指示も「`ModelVerifier`でSHA-256検証...破損/検証失敗時は削除して`Fallback`/再DL導線」を`ModelDownloader`の記述に含めていたため、`download()`自体が転送完了後に検証→合格なら`commit`、不合格なら`delete`まで行う一体パイプラインとして実装した。

**決定**:
1. `ModelDownloader`のコンストラクタへ`modelVerifier: ModelVerifier`を追加した（既存`modelStorage: ModelStorage`に加えて。公開APIシグネチャの破壊的変更ではない——本タスク開始時点で本クラスを構築する呼び出し元は存在しなかったため）。
2. `download(entry, onProgress)`は次の順で一体実行する: ①HTTPS検証（T-MDL-16）→②`modelStorage.hasSufficientSpace(entry.sizeBytes)`による容量ガード（§8.6 #3・§95.6）→③HTTP転送（レジューム対応、T-MDL-6・7、無限DL防止、T-MDL-8）→④転送完了後の`modelVerifier.verify()`（§8.6 #5）→⑤合格なら`modelStorage.commit(entry)`、不合格なら`modelStorage.delete(entry)`。
3. HTTP接続をfakeで差し替え可能にするため、`ai/model/ModelDownloader.kt`内に自己完結した`HttpRangeClient`／`HttpRangeConnection`interfaceと、本番実装`UrlConnectionHttpRangeClient`（`java.net.HttpURLConnection`ベース、`com.actionstarter.services.routing.UrlConnectionHttpPostClient`と同型の設計だが`services.routing`配下を一切importしない自己完結実装）を新設した。T-AIISO-6の許可対象ファイルは変わらず本ファイル1つのままである（ADR-0044）。可視性は`public`とした（`ModelDownloader`の`public`コンストラクタが`httpClient`引数の型として公開するため、`internal`のままでは「publicな宣言がinternal型を公開している」というKotlinの可視性整合性エラーになる。単一`:app`モジュールのため実質的な公開範囲への影響はない）。
4. `ModelDownloadFailureReason`へ`INSUFFICIENT_STORAGE`／`VERIFICATION_FAILED`／`STORAGE_ERROR`（`commit()`失敗）の3値を追加した（既存`INSECURE_URL`/`NETWORK_ERROR`/`HTTP_ERROR`/`SIZE_EXCEEDED`はそのまま維持、破壊的変更なし）。
5. 実ネットワークDLはテストで一切実行しない（fake `HttpClient`/URLでのロジック検証のみ、本タスクの制約）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| タスク指示の記述どおり`DeviceCapability`へ容量チェックメソッドを追加する | F91の責務（RAM/ABI判定）にストレージ容量という異質な関心事を混入させ、既存3メソッド構成の一貫性を壊す。P7-C1で既に`ModelStorage.hasSufficientSpace`が同じ目的で確定・KDoc化されており、追加すると同一目的の実装が2箇所に重複する |
| `ModelDownloader.download()`を転送のみに限定し、検証・コミットは呼び出し側（将来のSettings ViewModel、P7-C6）の責務とする | タスク指示が明示的に検証・削除・再DL導線までを`ModelDownloader`の記述に含めており、これに従った。加えて「DLしたが未検証のファイル」を呼び出し側が誤ってロード対象として扱ってしまうリスク（信頼境界違反）を、`download()`の戻り値契約自体で構造的に防げる利点がある |
| `HttpRangeClient`を`internal`のまま維持し、`ModelDownloader`の`httpClient`パラメータを取り除いてコンストラクタ内で直接`UrlConnectionHttpRangeClient()`を生成する（テストはリフレクション等で差し替える） | タスク指示「実HTTP DLのテストはfake HttpClient/URLで」に反する。リフレクションベースの差し替えは可読性・保守性を損ない、本プロジェクトの既存fakeパターン（コンストラクタ注入、`CalendarService`/`RoutingService`等）とも異なる |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/model/ModelDownloader.kt`（全面実装。`HttpRangeClient`等の新設含む）。新設`app/src/test/java/com/actionstarter/ai/model/ModelDownloaderTest.kt`（T-MDL-6〜8・16、および検証・コミット統合の追加ケース、計10件）。`app/src/main/java/com/actionstarter/di/AppContainer.kt`（`modelDownloader`プロパティ新設。**呼び出し元は未配線**——Settings画面はF97・P7-C6のスコープであり本サイクルでは作らない）。

**検証方法**: `:app:testDebugUnitTest --tests "com.actionstarter.ai.model.ModelDownloaderTest"`実測で10/10 Green（`build/agent-logs/p7c4-green-ModelDownloader.log`）。`:app:testDebugUnitTest --rerun`全体でtests=528/failures=0/errors=0/skipped=1（`build/agent-logs/p7c4-full.log`）。実ネットワークDLを伴うテストは0件（全件fake `HttpRangeClient`経由）。

**再検討トリガー**: P7-C6でSettings画面（F97）が`ModelDownloader`の呼び出し元になる際、進捗UI・キャンセル操作・再DL導線の具体的な配線を設計すること。T-GW-11（容量不足→DL開始しない）はADR-0049裁定6のとおり`ModelDownloader`/Settings領域のテストであり、本ADRの容量ガード実装（`download_insufficientStorage_returnsFailedInsufficientStorage_noConnectionOpened`）がその実装的な裏付けとなる。

---

### ADR-0055: `AiMetrics`実測値配線を`BenchmarkMetricsSource`という任意実装interfaceで行う（`LocalLanguageModel`は無変更のまま）

- 日付: 2026-08-10 ／ ステータス: 承認済み（domain-implementer判断・報告事項） ／ 決定者: domain-implementer（P7-C5、Fable 5への報告を前提とした実装時判断） ／ 起案agent: domain-implementer ／ 関連仕様§: §57「Local AI性能指標」・§8.5「AiMetrics」（記録トリガー②仕様未定義箇所の補完に該当。§16の凍結`LocalLanguageModel`interfaceには一切手を加えていないため①契約変更には該当しない）

- **ADR番号の付番根拠**: 起票直前に`grep -n "^### ADR-" DECISIONS.md`を再実測し、最新確定ADRがADR-0054（本書1345行）であることを確認した。その次番としてADR-0055を採番する。

**背景**: P7-C3完了記録・P7-C4完了記録がいずれもP7-C5への申し送りとして「`AiMetrics`の`modelLoadMs`／`firstTokenMs`／`outputTokens`／`tokensPerSecond`／`peakNativeHeapBytes`を`BenchmarkInfo`から実測値へ差し替えること」を明記していた。しかし`LocalAiGateway`は`model`を§16で凍結された`LocalLanguageModel`型（`generatePlan(context, samplingPolicy): String`のみ）で保持しており、この型からは`Conversation.getBenchmarkInfo()`由来の実測値を一切取得できない。`LocalLanguageModel`interfaceへ戻り値やメトリクス取得メソッドを追加することは、ADR-0045（戻り値をStringへ変更）・ADR-0050（`samplingPolicy`引数追加）に続く3度目の契約変更になり、いずれも過去にFable 5裁定を要した重い変更である。

**決定**: `LocalLanguageModel`自体は変更せず、新設`interface BenchmarkMetricsSource { fun lastInferenceMetrics(): InferenceBenchmarkSnapshot? }`（`ai/BenchmarkMetricsSource.kt`、`InferenceBenchmarkSnapshot`データクラスを同居）を`ai/`パッケージ直下に新設した。`LiteRtLmLocalLanguageModel`は`LocalLanguageModel`に加えて本interfaceを**追加実装**し、直近の`generatePlan`呼び出しの実測値を保持する。`LocalAiGateway`は`model.generatePlan()`成功直後に`(model as? BenchmarkMetricsSource)?.lastInferenceMetrics()`で**型検査により任意に**読み出し、非nullなら`AiMetrics`へ反映し、`null`（`model`が本interfaceを実装しない場合）なら従来どおり`0`のプレースホルダへ縮退する（`LocalAiGateway.buildMetrics`）。`modelLoadMs`は「Engine再利用時は`0`」という契約（[InferenceBenchmarkSnapshot]のKDoc）とし、モデルロードが実際にプロセス生涯で1回しか起きない（R-7・T-GW-16）という設計を数値としても正直に表現する。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `LocalLanguageModel.generatePlan`の戻り値を`String`から`Pair<String, InferenceBenchmarkSnapshot?>`のような複合型へ再度変更する | §16の凍結interfaceへの4度目の契約変更（ADR-0045・0049・0050に続く）になり、影響範囲が`LocalAiGatewayTest`の`FakeLocalLanguageModel`／`ConcurrencyTrackingFakeModel`を含む既存テストへ及ぶ。P7-C5タスク自体にはこの契約変更を裁定する権限がない（過去3回とも計画書側のFable 5裁定が必要だった） |
| `LocalAiGateway`のコンストラクタが`model`を`LiteRtLmLocalLanguageModel`の具象型で直接受け取る（`as?`を使わない） | T-AIISO-9（`com.google.ai.edge.litertlm`をimportしてよいのは`ai/adapter/`配下のみ、§16「モデルは技術検証で交換可能にする」）と正面から矛盾する。次点のllama.cpp案（計画書§5.1）へ切り替える際`ai/adapter/`の差し替えだけでは済まなくなる |
| Gatewayが独自にモデルロード時間・TTFT等を計測する（adapter側の実測値を使わない） | Gateway境界からは`Engine`初期化やdecode速度を直接観測できない（`ai/adapter/`配下の内部実装詳細のため）。`totalMs`（呼び出し全体のwall time）はGateway境界で計測可能だが、それ以外の粒度の細かい指標はadapter内部でしか測れない |
| プレースホルダ`0`のまま据え置き、P7-C5の対象外として次サイクルへ送る | P7-C3・P7-C4完了記録がいずれも「P7-C5で差し替えること」と明記した申し送り事項であり、かつ本タスク指示が「AiMetrics実測差し替え」を明示的に要求している。据え置く理由がない |

**影響範囲**: 新設`app/src/main/java/com/actionstarter/ai/BenchmarkMetricsSource.kt`（`BenchmarkMetricsSource`interface・`InferenceBenchmarkSnapshot`データクラス）。`app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（`ModelAttempt.RawJson`へ`benchmark`フィールド追加・`invokeModel`が`as?`で読み出し・`buildMetrics`のシグネチャ変更、KDoc更新）。`app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（`BenchmarkMetricsSource`を追加実装）。**`LocalLanguageModel.kt`・`LocalAiGatewayTest.kt`はいずれも無変更**（`FakeLocalLanguageModel`等が本interfaceを実装しないため`as?`は常に`null`となり、既存T-GW-*群の期待値に影響しない）。

**検証方法**: `:app:testDebugUnitTest --rerun`実測でtests=528/failures=0/errors=0/skipped=1（変更前後で完全一致、`build/agent-logs/p7c5-jvm.log`）。`:app:lintDebug --rerun-tasks`はBUILD SUCCESSFUL・error 0、warning 22件は全て既存分（`build/agent-logs/p7c5-lint.log`）。実機E2E（`app/src/androidTest/java/com/actionstarter/probe/LiteRtLmAdapterE2EProbeTest.kt`の`probeAdapterThroughGateway_widerContextDiagnostic`）で`AiMetrics.modelLoadMs`＝4,073ms（1件目）／`0`ms（2・3件目、Engine再利用の実証）、`firstTokenMs`＝1,534〜1,878ms、`tokensPerSecond`＝25.7〜35.2、`peakNativeHeapBytes`＝約536〜545MBが実測プレースホルダでない値として得られたことを確認した（`build/agent-logs/p7c5-e2e.log`）。

**再検討トリガー**: Phase 9で`generateRecovery`を実装する際、同種のベンチマーク配線が必要になれば`BenchmarkMetricsSource`をそのまま再利用できる設計にしてある（`lastInferenceMetrics()`は`generatePlan`／`generateRecovery`のどちらの直近呼び出しかを区別しないため、両方を実装する場合は用途を再検討すること）。

---

### ADR-0056: `LiteRtLmLocalLanguageModel`の実装詳細（Engine/Conversationライフサイクル・`SamplerConfig`のtopP/seed・出力トークン上限）を確定し、`maxNumTokens`の実測制約を記録する

- 日付: 2026-08-10 ／ ステータス: 承認済み（domain-implementer判断・報告事項） ／ 決定者: domain-implementer（P7-C5、Fable 5への報告を前提とした実装時判断） ／ 起案agent: domain-implementer ／ 関連仕様§: §16「Model Adapter方式」・品質ハーネス§4「サンプリング設計」・§7「速度×品質トレードオフ」（記録トリガー②仕様未定義箇所の補完、および③新事実発覚に該当。`LiteRtLmLocalLanguageModel`のpublicコンストラクタシグネチャは無変更のため①契約変更には該当しない）

- **ADR番号の付番根拠**: ADR-0055と同一バッチ起票。起票直前の`grep`再実測（ADR-0055参照）によりADR-0055の次番としてADR-0056を採番した。

**背景**: `LiteRtLmLocalLanguageModel`のP7-C1 scaffold KDocおよび品質ハーネス§4・§10は、`SamplerConfig`の`topP`／`seed`具体値、Engine/Conversationのライフサイクル方式、出力トークン上限を「P7-C5の実装詳細」として明示的に未確定のまま残していた。P7-C5はこれらを確定させる必要があった。加えて、実機（AVD）E2Eプローブの過程で、P7-C1が定めた`DEFAULT_MAX_NUM_TOKENS=256`が本番の`PlanPromptBuilder`（system instruction＋既定2-shot few-shot＋data message）と組み合わせるとネイティブ層のエラーで推論自体が失敗するという、計画書・品質ハーネスのいずれにも実測記録がなかった制約を発見した。

**決定**:
1. **Engine/Conversationのライフサイクル**: `Engine`はプロセス内で高々1個（`engineLifecycleMutex`配下の遅延生成、R-7・T-GW-16「2回目以降は再ロードせず再利用する」）。`Conversation`は`generatePlan`呼び出しごとに新規生成し`finally`で`close()`する。「KVキャッシュのみクリア」（既存KDoc）と「retryは新規single-turnセッション」（S-2是正・Gemini G1 CRITICAL #1）の両要求は、Conversationを毎回作り直すこの単一の設計で同時に満たされると判断した（`systemInstruction`＋`initialMessages`のprefaceを`prefillPrefaceOnInit=true`で毎回再prefillする）。
2. **`SamplerConfig`の`topP`／`seed`**: `SamplingPolicy.Primary`→`topP=1.0, seed=0`、`SamplingPolicy.Retry`→`topP=0.95, seed=1`（品質ハーネス§4の推奨値表をそのまま採用。`seed`はPrimaryと異なる値であることのみが要件〔S-2是正「条件を変える」〕のため、具体的な数値としては1を選んだ）。
3. **`modelLoadMs`の情報源**: `Conversation.getBenchmarkInfo().initTimeInSecond`ではなく`SystemClock.elapsedRealtime()`のwall-clock差分を採用した。P7-C0実測（`LiteRtLmProbeTest`のKDoc）が「`initTimeInSecond`は自前のwall-clockロード時間や試験全体の経過時間と桁が一致しない、数値の意味を断定できない」と明記済みであり、この不確実な値を本番`AiMetrics`の情報源にしないため。`firstTokenMs`／`outputTokens`／`tokensPerSecond`は`BenchmarkInfo`の対応フィールド（`timeToFirstTokenInSecond`／`lastDecodeTokenCount`／`lastDecodeTokensPerSecond`）をそのまま採用した（これらはP7-C0のKDocで「要注意」と指摘されていない）。
4. **出力トークン上限**: `MAX_OUTPUT_TOKEN=200`（暫定）。確定契約（ADR-0045・0046）でstepあたり`action_type`／`display_text`の2フィールドのみに削減されており、P7-C0実測（6フィールド・1step固定で56 decodeトークン）より単価が下がる見込みだが、`maxItems=8`まで許容する安全側の値とした。
5. **`DEFAULT_MAX_NUM_TOKENS`（P7-C1が定めた256）は変更しない**。理由: 本番運用値の確定は計画書§17 V-8・§11.3がP7-C8実機プローブの責務と明記しており、P7-C5がこれを追い越して確定させることは計画のサイクル分解（§14）と整合しない。ただし**この暫定値のまま`AppContainer`の実配線（本番`PlanPromptBuilder`一式）と組み合わせると、実機（AVD）で`LiteRtLmJniException: FAILED_PRECONDITION: Chosen prefill work group size exceeds available state entries (73)`により推論そのものが失敗することを実測した**（`app/src/androidTest/java/com/actionstarter/probe/LiteRtLmAdapterE2EProbeTest.kt`の`probeAdapterThroughGateway_smallContextProfile`、`build/agent-logs/p7c5-e2e.log`）。同一条件で`maxNumTokens`のみ1024へ引き上げた診断実行（`probeAdapterThroughGateway_widerContextDiagnostic`）では3件とも成功した。この事実を確定値化せず「P7-C6/C8への申し送り」として記録するにとどめる（決定6参照）。
6. **P7-C6/C8への申し送り（本ADRが確定させない事項）**: (a) `DEFAULT_MAX_NUM_TOKENS`の本番値は256のままでは実機で機能しない可能性が高く、少なくとも1024相当への引き上げ、または`shotCount`削減（品質ハーネス§7が既に0-shotを候補として挙げている）のいずれかが必要——最終値はP7-C8のGalaxy A実測で確定すること。(b) `ModelCatalogEntry.peakRamBytes`がコンテキストプロファイル非依存の単一値であるため、§8.6 #7のOOM事前ガードが小コンテキスト・テストプロファイルの実要求量より過大な閾値で判定してしまう構造的なギャップがある（AVDで実測確認済み）。プロファイル別`peakRamBytes`の要否をP7-C8で検討すること。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `DEFAULT_MAX_NUM_TOKENS`を実機E2Eの実測結果を受けてP7-C5の時点で1024へ確定的に変更する | 計画書§14がP7-C8（実機プローブ＋Refactor）を「本番運用値の確定」ゲートとして明示的に置いており（§17 V-8「P7-C0の必須測定項目」・§11.3「本実測が完了するまで...確定していない」）、P7-C5が単独でこれを追い越すのは計画のサイクル分解を無視する越権になる。AVD（x86_64エミュレータ）1台の実測だけでGalaxy A実機の値を確定させることは§11.2の留保（速度・メモリの絶対値はエミュレータでは意味を持たない）にも反する |
| `BenchmarkInfo.initTimeInSecond`を`modelLoadMs`の情報源として採用する（実装を単純化） | P7-C0が「数値の意味を断定できない」と明記した値を本番メトリクスとして使うのは知的誠実性に反する。P7-C0自身のwall-clock計測手法（`SystemClock.elapsedRealtime()`）を踏襲する方が実測として説明可能 |
| `Conversation`もEngineと同様プロセス内で使い回す（毎回新規生成しない） | retryが要求する「新規single-turnセッション」（会話履歴を持たない）と、通常呼び出し間でのプロンプト内容の入れ替え（毎回異なる`PlanningContext`）の両方を満たすには、結局`sendMessage`前に会話状態をリセットする処理が必要になり、`Conversation`インスタンス自体を作り直すより複雑になる。V-2実測（`close()`非冪等）を踏まえても、Conversationの生成・close 1回はEngineロードよりコストが小さい（実測: 2・3件目`totalMs`5〜7秒に対し`modelLoadMs`は初回のみ4,073ms） |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（全面実装）。新設`app/src/androidTest/java/com/actionstarter/probe/LiteRtLmAdapterE2EProbeTest.kt`（P7-C5自身の実機検証プローブ、`@Ignore`既定）。**`DEFAULT_MAX_NUM_TOKENS`の値自体は変更していない**（決定5参照）。

**検証方法**: `:app:compileDebugKotlin`成功（実際のAAR 0.15.0のバイトコードに対してコンパイルが通ることを`SamplerConfig`／`ConversationConfig`／`ResponseFormat`等のAPI形状の一次確認とした）。`:app:testDebugUnitTest --rerun`でtests=528/failures=0/errors=0/skipped=1（`build/agent-logs/p7c5-jvm.log`）。`:app:lintDebug --rerun-tasks`でBUILD SUCCESSFUL・error 0（`build/agent-logs/p7c5-lint.log`）。実機E2E3パターン（`build/agent-logs/p7c5-e2e.log`）: (1)本番`peakRamBytes`のまま→3件とも`Fallback(OUT_OF_MEMORY_PREVENTED)`、(2)`maxNumTokens=256`のまま→3件とも`Fallback(UNKNOWN)`（ネイティブ`FAILED_PRECONDITION`）、(3)`maxNumTokens=1024`→3件とも`AiResult.Success`（`schemaValid=true`・`sanityPassed=true`、実際に生成された`display_text`をログへ記録）。

**再検討トリガー**: P7-C8実機プローブ完了後、決定6(a)(b)の申し送り事項を本ADRの「決定」欄へ追記して確定させること。決定4のTTFT/tok/s実測値がGalaxy A実機でエミュレータ実測（`tokensPerSecond`=25.7〜35.2、x86_64のため参考値）と大きく乖離する場合、`MAX_OUTPUT_TOKEN`（決定4）の再検討も合わせて行うこと。

---

### ADR-0057: `maxNumTokens`既定値を実際のpreface文字数から算出する計算式へ変更し、OOM事前ガードへプロファイル別の実効ピークRAMフィールドを追加する（ADR-0056決定6の申し送り解消）

- 日付: 2026-08-10 ／ ステータス: 承認済み（Fable 5指示「品質ハーネス強化」の一部・報告事項） ／ 決定者: domain-implementer（P7-C5b、Fable 5への報告を前提とした実装時判断） ／ 起案agent: domain-implementer ／ 関連仕様§: 計画書§8.6#7（OOM事前ガード）・§17 V-8・§11.2〜3、品質ハーネス§7（速度×品質トレードオフ）、ADR-0056決定6(a)(b)の申し送り解消（記録トリガー②仕様未定義箇所の補完・③新事実発覚。`LocalAiGateway`のOOM判定に使うフィールドの変更・`LiteRtLmLocalLanguageModel`の`maxNumTokens`既定値算出方法の変更のため①契約変更にも該当）

- **ADR番号の付番根拠**: `grep -n "^### ADR-00" DECISIONS.md`を起票直前に再実行し、実測最新確定ADRがADR-0056であることを確認した。本タスク指示書自身も「ADR-0057〜」を明示的に指定しているため、これと整合する形で0057を採番した。

**背景**: P7-C5実機実測（ADR-0056決定6・計画書§14.8）が2つの未解決課題を残していた。(a) `LiteRtLmLocalLanguageModel.DEFAULT_MAX_NUM_TOKENS`が256のままでは本番プロンプト一式（`PlanPromptBuilder`のsystemInstruction＋既定2-shot few-shot＋data message）が実機`FAILED_PRECONDITION`で失敗し、`maxNumTokens=1024`への引き上げで解消することを実測したが、この値自体はP7-C8実機プローブの確定事項として据え置かれていた。(b) `ModelCatalogEntry.peakRamBytes`（2,890MB、フルコンテキスト4096の実測値）がコンテキストプロファイル非依存の単一値であるため、`LocalAiGateway`のOOM事前ガード（§8.6#7）が実際に使う小コンテキスト・本番プロファイルの実要求量に対して過大判定し、実機で空きメモリが十分あるにもかかわらず`Fallback(OUT_OF_MEMORY_PREVENTED)`を返す構造的なギャップがあった。本タスク（Fable 5指示・品質ハーネス強化サイクル、P7-C5b）はこの2点を「方針非依存・必須の基盤バグ修正」として明示的にP7-C8を待たず是正することを指示した。

**決定**:

1. **`maxNumTokens`の既定値算出（256のハードコード差し替えではなく計算式）**: `PlanPromptBuilder`（`ai/prompt/`、ランタイム非依存）へ`estimateMaxNumTokens(shotCount, maxOutputToken): Int`を新設した。実際に組み立てる`buildSystemInstruction`＋`buildFewShot`の文字数（ja/en両locale中の最悪ケース。Engineはプロセス内で高々1個の遅延シングルトンでありインスタンス生成時点でどちらのlocaleが最初に呼ばれるか不明なため）を、P7-C5実機実測で`maxNumTokens=1024`が成功したときのpreface文字数のスナップショット（`BASELINE_PREFACE_CHARS_P7C5=1206`文字、当時のソースから本ADR起票時に再計算）と比較し、増分文字数をトークン数の増分へ保守的な比率で変換して1024へ加算する。**変換比はja/en個別**（日本語1.5トークン/文字・英語0.5トークン/文字）とし、大きい方（最悪ケース）を採用する——Gemini（`gemini-3.5-flash`、P7-C5bアーキテクトレビュー）CRITICAL指摘「文字数が少ない方を素朴に安全側とみなすと、Qwen3のbyte-level BPEでは日本語の方が1文字あたりのトークン消費が英語より多くなり得るため危険」の反映。最終結果は128トークン単位へ切り上げ（同レビュー「端数値はネイティブランタイムのメモリアラインメントで無駄なパディングを招きうる」の反映）、`VERIFIED_WORKING_MAX_NUM_TOKENS=1024`を下限・`CONTEXT_LENGTH_CEILING=4096`（`ModelCatalog`の`contextLength`と同値）を上限にclampする。**実測結果**: 既定`shotCount=2`・`maxOutputToken=200`で`DEFAULT_MAX_NUM_TOKENS=1280`（1024の約1.25倍）と算出された（JVM実行での実測確認済み）。`LiteRtLmLocalLanguageModel`に`shotCount`コンストラクタパラメータを新設し（既定`PlanPromptBuilder.DEFAULT_SHOT_COUNT`）、`maxNumTokens`の既定値式が`shotCount`に自動追従する設計とした——「`shotCount`だけ変えて`maxNumTokens`は既定のまま」という食い違いで`FAILED_PRECONDITION`が再発することを構造的に防ぐため。
2. **OOM事前ガードのプロファイル依存是正**: `ModelCatalogEntry`へ`defaultProfilePeakRamBytes: Long = peakRamBytes`（新規・既定値は`peakRamBytes`と同値で後方互換）を追加し、`LocalAiGateway`のOOM事前ガードが参照するフィールドを`peakRamBytes`（フルコンテキスト実測・プロファイル非依存）から`defaultProfilePeakRamBytes`（実際に使う既定プロファイルでの実効ピーク）へ変更した。`ModelCatalog.QWEN3_0_6B_INT4_BLOCK32`のみ`defaultProfilePeakRamBytes=1,342,177,280バイト（1.25GiB）`を明示指定する——この値はP7-C5診断実測（`probeAdapterThroughGateway_widerContextDiagnostic`、`maxNumTokens=1024`・`peakRamBytes`フィクスチャ=1.25GiBで実機3件とも実推論成功）で**既に検証済みの値をそのまま採用**したものであり、新たな未検証の数値ではない。マージン自体（`MEMORY_SAFETY_MARGIN_BYTES=512MB`）は変更していない——過大判定の原因は`peakRamBytes`側のプロファイル非依存性であり、マージンの大きさの問題ではないと判断したため。
3. **既存fixtureへの影響ゼロ**: `defaultProfilePeakRamBytes`の既定値を`peakRamBytes`と同値にしたことで、`ModelCatalogEntry`を構築する既存の全呼び出し箇所（`ModelDownloaderTest`・`ModelStorageTest`・`ModelVerifierTest`・`LocalAiGatewayTest.fakeInstalledEntry`、いずれも名前付き引数で構築）は無変更のまま挙動が変わらない。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| `DEFAULT_MAX_NUM_TOKENS`を`1024`（P7-C5実測値）へ直接ハードコード差し替えする | 本タスク指示が「ハードコードでなく算出」を第一選択として明示。将来few-shot内容やsystemInstructionが変更された場合に同じ`FAILED_PRECONDITION`が無警告で再発するリスクを、実際のプロンプト構造から算出する設計で構造的に防ぐ方が安全（本ADR決定1の設計により、`estimateMaxNumTokens`のJVMテスト3件が「shotCount/maxOutputTokenが増えると見積りも増える」ことを回帰ロックしている） |
| `peakRamBytes`を実際のcontextLength比で線形スケーリングする動的計算式（`baseRam + kvCachePerToken × maxNumTokens`） | Gemini（`gemini-3.5-flash`）アーキテクトレビューが提案した代替だが、`baseRam`・KVキャッシュのトークン単価はいずれも実測データが不足しており（フルコンテキストの2,890MBとP7-C0/P7-C5の小コンテキスト実測〔異なる測定手法・異なる端末〕の2点だけでは線形モデルを確信を持って当てはめられない）、未検証の比例定数を事実であるかのように埋め込むことになり知的誠実性に反する。既に実機で検証済みの単一の定数値（1.25GiB）を明示的な既定プロファイル専用フィールドとして持たせる方が、精度は劣るが正直である |
| `LiteRtLmLocalLanguageModel`に`ContextProfileSource`のような任意interfaceを追加し、`LocalAiGateway`が`as?`で実際の`maxNumTokens`を読み取ってスケーリングする（`BenchmarkMetricsSource`＝ADR-0055と同型のパターン） | 上記と同じ理由（線形スケーリング定数が未検証）に加え、本サイクルのスコープ（AppContainer最小変更）を超える設計変更になるため見送った。`defaultProfilePeakRamBytes`と実際の`maxNumTokens`が将来乖離しうる残存リスクは「再検討トリガー」で明示し、未対応のまま隠さない |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/prompt/PlanPromptBuilder.kt`（`estimateMaxNumTokens`新設）。`app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（`shotCount`パラメータ新設、`DEFAULT_MAX_NUM_TOKENS`の算出方法変更）。`app/src/main/java/com/actionstarter/ai/model/ModelCatalog.kt`（`ModelCatalogEntry.defaultProfilePeakRamBytes`新設）。`app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（OOM事前ガードの参照フィールド変更）。テスト: `PlanPromptBuilderTest`・`ModelCatalogTest`・`LocalAiGatewayTest`（新規14件、詳細は計画書§14.9）。`app/src/androidTest/java/com/actionstarter/probe/LiteRtLmAdapterE2EProbeTest.kt`（`probeAdapterThroughGateway_productionDefaultsAfterFix`・shotCount比較3メソッド新設）。`AppContainer.kt`は無変更（コンストラクタ既定値のみで解決するよう設計したため）。

**検証方法**: `:app:testDebugUnitTest --rerun`でtests=542/failures=0/errors=0/skipped=1（528+新規14件、`build/agent-logs/p7c5b-jvm.log`）。`:app:lintDebug`でBUILD SUCCESSFUL・error 0・warning 22（既存分と同数、`build/agent-logs/p7c5b-lint.log`）。実機E2E（`build/agent-logs/p7c5b-e2e.log`）: production defaults（手動迂回なし）で5シナリオ中4件`Success`・1件`Fallback(SCHEMA_INVALID)`（OOM/FAILED_PRECONDITIONは1件も発生せず、Part A是正の直接証拠）。shotCount 0/2/3比較（独立プロセス実行）でも0/2/3いずれもOOM/FAILED_PRECONDITIONなしで完走。

**再検討トリガー**: P7-C8のGalaxy A実機実測で`DEFAULT_MAX_NUM_TOKENS`（決定1）・`defaultProfilePeakRamBytes`（決定2）とも最終確定させること。特に`defaultProfilePeakRamBytes=1.25GiB`は現状AVD（x86_64エミュレータ）実測の外挿であり、Galaxy Aクラス実機での確認が必要（`ModelCatalogEntry.peakRamBytes`のKDocが持つ既存の留保と同種）。`DEFAULT_SHOT_COUNT`（現行2、未変更）を変更する場合は`estimateMaxNumTokens`の既定値追従設計により`DEFAULT_MAX_NUM_TOKENS`も自動的に再算出されるが、`defaultProfilePeakRamBytes=1.25GiB`との整合は別途確認が必要（本ADR代替案却下理由3行目の残存リスク）。

---

### ADR-0058: `PlanPromptBuilder`のfew-shot模範プールを2件→4件へ拡張し、systemInstructionへ「タイトルの言い換え抑止＋具体的行動の明示要求」「文法的自然さ」ルールを追加する（P7-C5実測の浅いSemantic Contextualization・文法崩れへの対処）

- 日付: 2026-08-10 ／ ステータス: 承認済み（Fable 5指示「品質ハーネス強化」の一部・報告事項） ／ 決定者: domain-implementer（P7-C5b） ／ 起案agent: domain-implementer ／ 関連仕様§: 品質ハーネス§0・§2・§3（Semantic Contextualization）、計画書§14.8（P7-C5実測）（記録トリガー②仕様未定義箇所の補完に該当。`PlanPromptBuilder.buildFewShot`／`buildSystemInstruction`の戻り値の中身が変わるがpublicシグネチャは無変更のため①契約変更には該当しない）

- **ADR番号の付番根拠**: ADR-0057と同一バッチ起票。起票直前の`grep`再実測（ADR-0057参照）によりADR-0057の次番としてADR-0058を採番した。

**背景**: P7-C5実機実測（計画書§14.8）は、既存few-shot（2例・ja: 打ち合わせ／結婚式）とsystemInstructionのままでは、次の2種の品質課題が生じることを発見した。(1) 文法崩れ（歯科検診→「歯科検診に手順を計らる」）。(2) 浅い言い換え（友人の結婚式→「結婚式に参加する」、タイトルの当たり前の再述にとどまり、few-shotが示す「ご祝儀を準備する」級の具体的な文脈化に届かない）。本タスク（Fable 5指示）はFable 5自身が指定した3例（結婚式／歯科検診／出張）を反映したfew-shot強化と、systemInstructionでの明示的なルール化を求めた。

**決定**:

1. **few-shot模範プールをja/enとも2件→4件へ拡張**: `JAPANESE_FEW_SHOT_SEEDS`を[結婚式(social/prepare_items/「ご祝儀を用意する」)、歯科検診(medical/prepare_items/「保険証を持って出る」)、出張(travel/gather_belongings/「切符と充電器を確認する」)、打ち合わせ(business_meeting/prepare_items/「資料を準備する」、既存踏襲)]の順で構成した（Fable 5指示の3例をそのまま採用し、既存の打ち合わせ例を4件目として残置）。`ENGLISH_FEW_SHOT_SEEDS`も同じ4テーマ（Wedding／Dental checkup／Business trip／Team meeting）でja側と対称に構成した。**先頭2件（既定`shotCount=2`で採用される組）は意図的に同一`action_type=prepare_items`だが`display_text`が大きく異なる組み合わせ**にした——「同じaction_typeでも予定の意味によって全く違う具体物になる」ことを最優先で模範として伝えるための順序設計（KDoc「順序の意図」参照）。`DEFAULT_SHOT_COUNT`（既定2）自体は変更していない——最終確定はP7-C8実測後にFable 5が行うとする既存の裁定（品質ハーネスUQ-4）を尊重し、本サイクルは模範プールの質のみを強化した。
2. **systemInstructionルール2（自然さ）**: 「SHORT imperative phrase」に「grammatically natural」を追加した。
3. **systemInstructionルール4（具体性の明示要求）**: 「Do NOT copy the event title verbatim」（コピー抑止のみ）から、「タイトルの単純な言い換え（軽微な改変）も抑止し、その予定固有の具体的な準備物・行動を1つ挙げるよう積極的に要求する」文言へ強化した（例示付き: "do not turn 'wedding' into 'attend the wedding'"）。
4. **回帰テスト**: `PlanPromptBuilderTest`へ9件追加した（QH-8d・QH-8e＝systemInstruction強化の検証、QH-14f＝few-shot模範自身がタイトルコピー抑止ルールに違反していないことの自己整合性チェック、QH-14g・QH-14h＝プール拡張の回帰ロック、`estimateMaxNumTokens`関連4件はADR-0057側）。既存QH-8a〜c・QH-14a〜eはいずれも文言変更なしで通過した（変更後もJSON/action_type/time・number/Japanese/Englishの各キーワードを保持する形でルール文を書いたため）。

**実機での効果（正直な評価、詳細は本体タスク最終報告参照）**: P7-C5b実測（`build/agent-logs/p7c5b-e2e.log`）では、文法崩れは解消された（「歯科検診に手順を計らる」のような非文法的出力は本サイクルの5+3+3=11件中0件）。一方、「具体的な行動」を挙げる目標（ご祝儀・保険証・切符級の具体性）は**達成できなかった**——実際の出力は「歯科検診を受けてください」「友人の結婚式を迎える」「大阪の出張を予定した」のようにタイトルの自然な言い換えにとどまり、few-shotが示す具体物（保険証・ご祝儀・切符）を転用できていない。さらに「チームMTG」入力でshotCount 0/2/3のいずれでも不安定な挙動（無関係な「歯科検診」関連文言の生成・locale不一致・誤分類）を横断的に観測した。**この結果は隠さず正直に報告する**（本タスク制約「出力が改善しない/0.6Bの限界が明確な場合もそのまま報告」）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| few-shot例を5件以上に増やす、またはCoT風の説明を追加する | 品質ハーネス§7のTTFT/maxTokensトレードオフ（例数が増えるほどprefillコストが増大、本サイクルの実測でもshotCount=3はtok/sが明確に低下）と、Fable 5指示「実際の採用例数shotCountは...2〜3例」の範囲を尊重し、既定2-shotのまま模範の質のみを強化する方針を採った |
| systemInstructionのルール4をさらに強く・長くする（複数の具体例を列挙する等） | 品質ハーネス§3「英語systemは小型モデルでも指示追従が安定」の前提は簡潔さに支えられており、ルールを冗長にするとpreface文字数が増えADR-0057の`maxNumTokens`見積りも連動して増える（RAM・速度トレードオフの悪化）。実測（P7-C5b）でも具体性向上の効果は限定的だったため、これ以上ルール文だけを強化しても0.6Bモデルの根本的な指示追従力の限界を超えられない可能性が高いと判断し、追加の言語強化ではなく実測結果の正直な報告とP7-C8モデル比較への申し送りを優先した |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/prompt/PlanPromptBuilder.kt`（`JAPANESE_FEW_SHOT_SEEDS`・`ENGLISH_FEW_SHOT_SEEDS`・`buildSystemInstruction`の中身）。`app/src/test/java/com/actionstarter/ai/prompt/PlanPromptBuilderTest.kt`（9件追加）。

**検証方法**: ADR-0057と同一の`:app:testDebugUnitTest --rerun`（tests=542/failures=0）・`:app:lintDebug`（error 0）・実機E2E（`build/agent-logs/p7c5b-e2e.log`）で検証した。

**再検討トリガー**: P7-C8でのモデル比較（Qwen3-0.6B／1.7B／Gemma3-1B）実測後、「0.6Bモデルは周辺設計（few-shot/systemInstruction強化）だけでは深いSemantic Contextualizationに到達しない」という本ADRの結論が他モデルでも成り立つか確認すること。成り立たない場合（より大きいモデルなら同じfew-shotで具体性が出る場合）、0.6B自体の能力限界が主因という結論を補強する追加証拠になる。

---

### ADR-0059: `ModelCatalog`へQwen3-1.7B・Gemma4-E2Bを追加し、`ActivityManager`ベースのPSSピークサンプリングで`defaultProfilePeakRamBytes`を実機実測する（P7-C8モデル比較。計画書原案のGemma3-1BはHFゲート付きのためGemma4-E2Bで代替）

- 日付: 2026-08-10 ／ ステータス: 承認済み（本体タスク指示に基づく実装時判断・報告事項） ／ 決定者: domain-implementer（P7-C8、実機実測を前提とした実装時判断） ／ 起案agent: domain-implementer ／ 関連仕様§: 計画書§11.3・§14 P7-C8・§17 V-7・V-8、ADR-0057（`defaultProfilePeakRamBytes`新設）・ADR-0058（P7-C8への申し送り1〜4）（記録トリガー②仕様未定義箇所の補完〔計画書原案のGemma3-1B→Gemma4-E2Bへの代替〕・③新事実発覚〔PSSとnative heapの乖離〕。`ModelCatalog.ALL`へエントリを追加するため①契約変更にも該当するが、`ModelStorageImpl.installedEntry()`の解決順序を保つ設計〔決定4〕により既定モデル選択への実害はない）

- **ADR番号の付番根拠**: `grep -n "^### ADR-00" DECISIONS.md`を起票直前に再実行し、実測最新確定ADRがADR-0058であることを確認した。本タスク指示書自身も「ADR-0059〜」を明示的に指定しているため、これと整合する形で0059を採番した。

**背景**: 計画書§14のP7-C8原案は「Qwen3-0.6B / Qwen3-1.7B / Gemma3-1B の3者比較」だったが、本体タスク（Fable 5指示）が発注前の調査でGemma3-1BはHuggingFaceゲート付き（利用にHF側の承認申請が必要）であることを確認し、代わりにゲートなし・Apache-2.0の`litert-community/gemma-4-E2B-it-litert-lm`を比較対象として明示的に指定した。ADR-0058は「0.6Bのまま周辺設計を強化しても深いSemantic Contextualizationには到達しない」ことを実測済みであり、より大きい/新しいモデルで改善するかの検証がP7-C8の主目的だった。あわせて、AVD（RAM 4096MB）でGemma4-E2B（ファイル2.59GB）が実際に動作するか、モデルカードで言及されうる「8GB」級のRAM要求と実測が整合するかも検証課題だった。

**決定**:

1. **`ModelCatalog`へ2エントリ追加**: `QWEN3_1_7B_INT4_BLOCK32`（`litert-community/Qwen3-1.7B`の`Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm`、977,184,032バイト、SHA-256は開発者自身がダウンロード後`sha256sum`で計算し`2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b`と確認、HF側`x-linked-etag`とも一致・U-6方針）・`GEMMA_4_E2B_IT`（`litert-community/gemma-4-E2B-it-litert-lm`の`gemma-4-E2B-it.litertlm`、2,588,147,712バイト、SHA-256=`181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`、同じくHF側`x-linked-etag`と一致）。いずれもApache-2.0（HFモデルカードで確認）。
2. **測定方法の拡張（`PssPeakSampler`新設、`app/src/androidTest/java/com/actionstarter/probe/ModelComparisonProbeTest.kt`）**: 本番`LiteRtLmLocalLanguageModel`が内蔵する`peakNativeHeapBytes`実測（`Debug.getNativeHeapAllocatedSize()`＝bionic mallocアリーナの割当量ベース）に加えて、`ActivityManager.getProcessMemoryInfo().totalPss`をバックグラウンドスレッドで定期サンプリングするピークPSS計測を新設した。**実機実測でこの2指標に大きな乖離があることが判明した**——Qwen3-1.7Bで`peakNativeHeapBytes`は712〜750MBだが`PssPeakSampler`実測ピークPSSは1,945,677,824バイト（約1.81GiB、約1.2GBの乖離）。LiteRT-LMがモデル重みをmmapで読み込むため、mallocアリーナ限定の`peakNativeHeapBytes`は真の物理メモリ使用量を大きく過小評価すると判断する（P7-C0/P7-C5/P7-C5bはこの乖離を認識せず`peakNativeHeapBytes`のみで`defaultProfilePeakRamBytes`を検証していた）。
3. **新2エントリの`defaultProfilePeakRamBytes`はPSS実測値を根拠に確定**: Qwen3-1.7B（production defaults、`shotCount=2`実行時のピークPSS=1,945,677,824バイト）・Gemma4-E2B（production defaults実行時のピークPSS=1,980,168,192バイト、reduced context実行時は1,972,925,440バイトとほぼ同一）とも実測値に安全マージンを載せて**2.0GiB（2,147,483,648バイト）**へ切り上げて採用した。`peakRamBytes`（フルコンテキスト参考値、ADR-0057の意味論）はフルコンテキストctx4096/ctx32768の独立測定を本サイクルでは行っていないため、`defaultProfilePeakRamBytes`と同値を暫定採用する（[ModelCatalog.kt]KDoc「フルコンテキスト値としての独立検証は今後の課題」）。
4. **`ModelCatalog.ALL`の順序保持**: `QWEN3_0_6B_INT4_BLOCK32`を先頭に維持したまま2エントリを末尾に追加した。`ModelStorageImpl.installedEntry()`は`catalog.firstOrNull { finalFile(entry).isFile }`で解決するため、本番の既定モデル（Settings未実装のため事実上唯一のインストール経路）はQwen3-0.6Bのまま変わらない。
5. **Gemma4-E2Bは production defaults（`shotCount=2`、他モデルと同一条件）で完走を確認**: 本体タスク指示は「AVD 4096MBでOOMの可能性に注意し、必要なら小コンテキストプロファイル（`shotCount` 0/1）で試行」だったため、まず安全側の`probeGemma4E2B_reducedContext`（`shotCount=1`）を先に実行し、OOM・クラッシュなく完走することを確認した（5シナリオ中2件`Success`・3件`Fallback(SCHEMA_INVALID`、duplicate action_type検出）うえで、他モデルと横並び比較可能な`probeGemma4E2B_productionDefaults`（`shotCount=2`）を追加実行し、**こちらも5シナリオ全件`Success`かつより高品質**（具体的にはADR本文末尾「実測結果」参照）だったため、比較表・カタログの代表値はproduction defaultsを正とした。

**実測結果（正直な評価、詳細な比較表は本体タスク最終報告・計画書§14.10参照）**:

- **Qwen3-1.7B（production defaults）**: 文法は自然だが、`SamplingPolicy.Primary`（`topK=1, temperature=0.0`＝greedy）下でfriend-wedding・team-meetingの2/5シナリオが**全く無関係な同一の`display_text`「歯科検診の準備」**に収束するという、0.6Bにはなかった新規の退化的失敗モードを実測した。decode速度（12.9〜16.6 tok/s）・TTFT（4.4〜5.2秒）とも0.6Bより明確に劣化した。「モデルサイズを上げれば単純に改善する」という仮説は本モデルでは反証された。
- **Gemma4-E2B（production defaults）**: 5/5 `Success`（Fallback 0件）。dental-checkup→「保険証を持って行く」（few-shot模範「保険証を持って出る」とほぼ同水準の具体性）、friend-wedding→「招待状を確認する」（模範と異なるが独自に妥当）、team-meeting→「資料を準備する」（**0.6B/1.7Bで横断的に観測された無関係文言・誤分類が解消**）、osaka-business-trip→「出張に必要な書類をまとめる」、friend-birthday-party→「手土産を用意する」。全件2〜3ステップの複数ステップ構成（0.6B/1.7Bはほぼ全件1ステップに収束）。decode速度（22.8〜25.5 tok/s）・TTFT（1.57〜2.04秒）とも0.6Bと同水準かそれ以上。ADR-0058の「0.6Bで頭打ちなら、より大きいモデルでの改善幅がモデルサイズ起因の証拠になる」という仮説は、**Qwen系列の拡大では反証されたが、Gemma4-E2Bへのモデル変更では支持された**——「サイズ」ではなく「モデルファミリー／instruction tuningの質」が主要因である可能性が高い。
- **「公式8GB宣言」は確認できなかった**: HFモデルカード（`litert-community/gemma-4-E2B-it-litert-lm`）を実際に確認したが、8GBという最小RAM要件の記載は見つからなかった。カード自体が公表する実測ピークメモリは607MB（iPhone 17 Pro CPU）〜3,681MB（Jetson Orin Nano CPU）、Samsung S26 Ultra CPUで約1,733MBであり、本プロジェクトのAVD実測（約1.98GB）とも大きくは乖離しない。8GBという数値の出典は本体タスク発注時点の前提以上には特定できず、少なくとも本モデルの実際のメモリ使用量とは整合しない可能性が高い。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| Gemma4-E2Bはモデルカードの「8GB」を字義通り信じ、AVD 4096MBでの実推論を試行せずOOM前提でスキップする | 知的誠実性に反する（未検証の伝聞を事実として扱うことになる）。実際に試行したところ4096MBのAVDで問題なく完走し、この前提が実測と食い違うことが分かった。試行しなければこの重要な発見（PSS実測1.98GB、8GB宣言と実測の乖離）を得られなかった |
| `defaultProfilePeakRamBytes`を`peakNativeHeapBytes`実測値（Qwen1.7Bなら712〜750MB程度）に安全マージンを載せて確定する（既存Qwen3-0.6Bエントリと同じ方法論） | 本サイクルで`peakNativeHeapBytes`が真のメモリ使用量を約1.2GB過小評価することを発見したため、同じ方法論を踏襲すると新2エントリのOOM事前ガードが実際の要求量に対して過小な閾値になり、§8.6 #7の主防御が機能しなくなるリスクがある。`PssPeakSampler`実測（総プロセスPSS）を正とする決定2の方が安全側である |
| Qwen3-1.7Bの「歯科検診の準備」への収束を明らかなバグとみなし、`SamplingPolicy.Primary`のtopK/temperatureを変更するなど本番コードを修正する | 本体タスクのスコープ外（「本番domain変更禁止」「比較は専用probe内で完結」の制約）。またGateway/adapterの実装不備という証拠はなく（Conversation/Engineライフサイクルの正しさは既にADR-0056 V-2実測で確認済み）、greedy decodingという既定サンプリング戦略下でのモデル自体の挙動である可能性が高いため、実測事実として正直に報告するにとどめた |

**影響範囲**: `app/src/main/java/com/actionstarter/ai/model/ModelCatalog.kt`（`QWEN3_1_7B_INT4_BLOCK32`・`GEMMA_4_E2B_IT`新設、`ALL`へ追加）。`app/src/test/java/com/actionstarter/ai/model/ModelCatalogTest.kt`（新規7件・`all_containsQwen3Entry`更新）。`app/src/androidTest/java/com/actionstarter/probe/ModelComparisonProbeTest.kt`（新設、`@Ignore`既定）。本番の既定モデル選択・`AiPreferences`既定値・`AppContainer`配線・NavHost/Manifest/UIはいずれも無変更。

**検証方法**: `:app:testDebugUnitTest --rerun`でtests=549/failures=0/errors=0/skipped=1（542+新規7件、`build/agent-logs/p7c8-jvm.log`）。`:app:lintDebug`でBUILD SUCCESSFUL・error 0・warning 22（P7-C5bベースラインと同数、`build/agent-logs/p7c8-lint.log`）。実機E2E（AVD `actionstarter_test` x86_64/API35、RAM 4096MB）: `build/agent-logs/p7c8-e2e.log`（Qwen3-1.7B production defaults・Gemma4-E2B reduced context・Gemma4-E2B production defaultsの3実行の統合ログ）、個別Gradle/Logcatログは`p7c8-qwen17b-e2e-*.log`・`p7c8-gemma4e2b-reduced-*.log`・`p7c8-gemma4e2b-prod-*.log`。

**再検討トリガー**:
1. **既存`QWEN3_0_6B_INT4_BLOCK32.defaultProfilePeakRamBytes`（1.25GiB、ADR-0057）はPSS実測を経ていない**（P7-C5診断実測は`peakNativeHeapBytes`のみを見ていた）。本ADRの発見（PSSとnative heapの約1.2GB乖離）を踏まえると、Qwen3-0.6B自体も`PssPeakSampler`で再実測し、OOM事前ガードの閾値が実際に安全か確認することを推奨する（本サイクルでは本体タスクの制約「本番既定モデル変更禁止」により据え置いた）。
2. **フルコンテキスト（Qwen3-1.7B: ctx4096、Gemma4-E2B: ctx32768）の`peakRamBytes`独立測定は未実施**。§5.3の段階推奨を厳密に確定する場合は追加実測が必要。
3. **最終的な既定モデルの選択はユーザー判断**（U-4の既存裁定どおり）。本ADRは比較用データの整備・実測方法論の記録に留め、Gemma4-E2Bを新既定にするかどうかの決定はしていない（本体タスク最終報告の推奨を参照のうえユーザーが確定すること）。
4. Gemma3-1B自体（HFゲート解除後）との比較は本ADRの対象外のまま残る。ゲートが将来解除された場合、または別ルートでアクセス可能になった場合は追加比較を検討する余地がある。
