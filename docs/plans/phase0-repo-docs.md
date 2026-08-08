# Action Starter Android ― Phase 0 実装計画書：リポジトリ文書整備

**対象Phase**: Phase 0（仕様書§64 Development Phase 0）
**正仕様書**: `Action_Starter_Master_Specification_v2.0_Android.md`（全95節＋Changelog。2026-08-08レビュー反映版）
**起点計画メモ**: android-planner（Opus）作成、2026-08-08
**本書作成**: plan-doc-writer（Sonnet）、2026-08-08
**関連ハーネス文書**: `docs/TEAMS.md`（役割分担・PDCA・品質ゲートの正）、`docs/GOAL.md`（リリース判定基準）

本書と正仕様書v2.0に差異が生じた場合は仕様書v2.0が正とする。本書はandroid-plannerの計画メモ§3をそのまま文書化したものであり、計画メモにない内容を自己判断で追加していない。

---

## 1. 目的

Phase 0は、実装（Phase 1以降）に着手する前に、仕様書v2.0を正本としたまま「環境セットアップ手順」「アーキテクチャ方針」「製品意図」「AI方針」「プライバシー方針」「意思決定履歴（ADR）」を要約し、以後のPhaseを通じた判断のブレを防ぐための文書一式をリポジトリ直下に新規作成することを目的とする。コードは一切書かない。

対象は以下6ファイルの新規作成のみである：`README.md` / `ARCHITECTURE.md` / `PRODUCT.md` / `AI.md` / `PRIVACY.md` / `DECISIONS.md`。

## 2. スコープ

### 2.1 やること

- 上記6文書の新規作成
- 各文書冒頭への共通ルール文言（§6参照）の明記
- 各節への参照仕様書§番号の付与
- `DECISIONS.md`への記録ルール・記録トリガー6種・ADRテンプレート・初期ADR10件の記載（ADR-0010はG1クロスレビュー〔Gemini〕反映）

### 2.2 やらないこと（スコープ外）

- Gradleプロジェクトの作成（`settings.gradle.kts`・`build.gradle.kts`・Wrapper等はPhase 1計画書`docs/plans/phase1-ui-skeleton-domain.md`の§13〜14で扱う）
- コード実装・テスト実装（Phase 0はコード成果物を持たないため、TDD Red/Greenサイクルの対象外）
- 第二の正本の作成（仕様書との差異が生じた場合は常に仕様書v2.0を正とし、6文書側は要約とポインタに徹する）
- `.gitignore`の追記（計画メモ§11で「Phase 1追記」と明記されており、Phase 1計画書側の作業）

## 3. ゲート

**Phase 0はG1のみを適用する（G2〜G4は適用しない）**。根拠は`docs/TEAMS.md`§6ゲート適用表「文書のみ（Phase 0の雛形ドキュメント等）｜G1のみ（G2〜G4は適用しない）」。

G1（計画承認）の通過に必要な証拠は`docs/TEAMS.md`§6 G1節に定める、計画書ファイル・Fable 5のPass1/Pass2レビュー記録・Geminiクロスレビュー結果である。Phase 0は非コード成果物であるため、`./gradlew build`等のG4証拠は要求しない。Phase 0完了の合図は「6文書が出揃い、内容をFable 5が確認したこと」であり、これは独立のゲートではなくG1判定に含めて扱う（`docs/TEAMS.md`§6注記）。

## 4. 承認状態

本計画書はFable 5（オーケストレーター）によるPass1（CRITICAL）／Pass2（INFORMATIONAL）アーキテクトレビューを経て作成されている。**Gemini 3.5 Flash（`model: "gemini-3.5-flash"`固定）による第三者クロスレビューは実施済みである（2026-08-08）。** 指摘事項は主にPhase 1計画書に対するものであり、Fable 5裁定A1〜A7として反映された（詳細は`docs/plans/phase1-ui-skeleton-domain.md`§4参照）。本書はそのうちA6（ADR-0010の追加）の反映を受けている。

Phase 0そのものにFable 5裁定（2026-08-08、U1〜U6・A1〜A7）の直接該当項目は限定的である。ただし、Phase 0が作成する`DECISIONS.md`の初期ADRのうちADR-0004／ADR-0005／ADR-0006／**ADR-0010**は、Phase 1契約scaffoldの前提としてFable 5が既に個別裁定済みの**承認済み判断**であり、本書§8のADR一覧表では「ユーザー承認待ち」ではなく「承認済み」として記載する（詳細はPhase 1計画書`docs/plans/phase1-ui-skeleton-domain.md`§4を参照）。

## 5. 対象ファイル一覧

| ファイル | 状態 | 概要 |
|---|---|---|
| `README.md` | 新規 | 一行定義・最上位原則・Phase進捗表・環境セットアップ・コマンド一覧・文書ガイド・秘密情報非コミット方針 |
| `ARCHITECTURE.md` | 新規 | レイヤー図・契約interface一覧・Domain Model一覧・時間モデル・決定的計算とLLMの責務境界・2エンジン構成・UI方針・Android固有制約・テスト戦略・時刻方針 |
| `PRODUCT.md` | 新規 | 最重要原則〜プロダクト定義〜MVPユーザーフロー〜KPI〜MVP完成条件〜禁止機能〜Global-first設計〜有料化方針 |
| `AI.md` | 新規 | Local-first AI方針〜LLMに禁止すること〜Runtime〜モデル選定/配布〜AI OFF成立〜Structured Output〜Personal Profile〜検証指標〜現状ステータス |
| `PRIVACY.md` | 新規 | Privacy／Local Data〜位置情報制約〜Routes API送信範囲〜Telemetry許可リスト〜権限一覧〜ユーザー最終決定 |
| `DECISIONS.md` | 新規 | 記録ルール・記録トリガー6種・ADRテンプレート・初期ADR10件（ADR-0010はG1クロスレビュー反映） |

## 6. 全文書共通ルール

計画メモ§3が定める、6文書すべてに共通のルール：

- 全文書の冒頭に次を明記する：「本書は正仕様書v2.0の要約である。差異が生じた場合は仕様書v2.0が正。v1.0（iOS版）はアーカイブである。」
- 各節に、参照している仕様書の§番号を付す
- ゲートはG1のみ
- 第二の正本を作らない（要約とポインタに徹する。仕様書本文の転記による重複を避ける）

## 7. 文書別の内容構成

### 7.1 README.md（仕様§64ベース）

1. 一行定義（§1 プロダクトの定義）
2. 最上位原則（§0 最重要原則）と「本アプリでないもの」6項目
3. Phase進捗表 Phase 0〜13（仕様§64-77、`docs/TEAMS.md`§5のマッピング表と対応させる）
4. 環境セットアップ（JDK 17／SDKパス／Gradle CLI不要・`./gradlew`使用／`local.properties`／AVD `actionstarter_test`／KVM設定手順）
5. コマンド一覧（`:app:assembleDebug` / `:app:testDebugUnitTest` / `:app:connectedDebugAndroidTest` / `:app:lintDebug` / `:app:build`。`--console=plain`推奨を明記）
6. 文書ガイド＋`docs/TEAMS.md`・`docs/GOAL.md`への導線
7. 秘密情報非コミット方針

### 7.2 ARCHITECTURE.md（仕様§43ベース）

1. レイヤー図（§43 アーキテクチャ）＋Kotlinパッケージ対応表
2. 単一`:app`方針と分割再検討トリガー（Phase 7で再検討）
3. 契約interface一覧（§44 PlanningEngine Interface／§45 RecoveryEngine Interface／§46 Routing abstraction／§16 Local LLM Runtime）＋バージョン付き変更経路（`docs/TEAMS.md`§5）
4. Domain Model一覧（§47 Core Domain—Event〜§52 Personal Execution Profile）＋補完型7種の定義と導出根拠
5. 時間モデル（§4 コア時間モデル）と§13 Basic Engineの式
6. 決定的計算とLLMの責務境界（§13／§14／§15。§15はレビュー観点として扱う）
7. 2エンジン並存とフォールバック（§12 AIあり・なしを両方実装／§19 AI OFF時でも動作すること／§20 Structured Output）
8. UI方針（Compose／immutable UiState／One Action §27-28／巨大Composable禁止 §89）
9. Android固有制約（§95）
10. テスト戦略とsource set分類
11. 時刻方針（§8 時刻・地域対応。`java.time`を使用し、`java.time.Duration`に統一。`kotlin.time.Duration`と混在させない＝ADR-0008）

### 7.3 PRODUCT.md

以下の順序で仕様書内容を要約する：

§0（最重要原則）→ §3（解決する問題）→ §1（プロダクトの定義）→ §2（ブランド／ユーザー向けメッセージ。**「コピー未確定」であることを明記し、確定コピーであるかのように書かない**）→ §4（コア時間モデル）→ §5（MVP対象ユーザー）→ §24〜§35（MVPユーザーフロー／Planning／Plan Review／Execution Mode／Execution UI原則／Departure Mode／Reality Check／Recovery Mode／Recovery Option／Recoveryの優先原則／ユーザー最終決定／最初の5画面）→ §37・§38（Basic Mode／Local AI Mode）→ §55〜§57（MVP KPI／Basic vs AI KPI／Local AI性能指標）→ §78〜§80（MVP完成条件／失敗条件／成功シグナル）→ §61（MVPに入れない機能。**独立見出しとして構成**）＋§88（Developer UX Principleの判断基準）→ §6・§7（Global-first設計／国際化要件）→ §40・§41・§87（Local AI有料化仮説／有料化メッセージ／プライシングは未決定）

### 7.4 AI.md

以下の順序で仕様書内容を要約する：

§10・§11（Local-first AI／Local AIのユーザー価値）→ §12〜§14（AIあり・なしを両方実装／Basic Engine／Local AI Engine）→ **§15（LLMに禁止すること）を最上段に配置**（他節より優先して目立たせる）→ §16（Local LLM Runtime）→ §17（モデル選定方針）→ §18（モデル配布）＋§95.6（該当小節）→ §19（AI OFF時でも動作すること）→ §20（Structured Output）→ §21（AI Promptの言語非依存化）→ §22・§23・§52（Personal Execution Profile／将来的な「Personal Execution Model」／Domain定義としてのPersonal Execution Profile）→ §95.3（該当小節）→ §39・§56・§57（検証したいLocal AI価値／Basic vs AI KPI／Local AI性能指標）→ 現状ステータス（**Phase 7以降で実装。Phase 1〜6はinterface宣言のみであることを明記**）

### 7.5 PRIVACY.md

以下の順序で仕様書内容を要約する：

§58・§59（Privacy／Local Data）→ §58の中でも§95.1（`ACCESS_BACKGROUND_LOCATION`を要求しない設計方針）→ §10（Local-first AIのプライバシー文脈での参照）→ §95.2（Routes APIへ送信するのは座標と移動手段のみである旨）→ §60（Telemetryの許可リストベース送信方針）→ §95.4（権限一覧表を転記）→ §34（ユーザー最終決定＝対外操作はユーザー確認を経る）→ §95.5（該当小節）

### 7.6 DECISIONS.md

- **記録ルール**：§64（Development Phase 0）／`docs/TEAMS.md`§5に基づく
- **記録トリガー6種**：①interface契約変更 ②仕様未定義の補完 ③仕様推奨からの逸脱 ④依存バージョン変更 ⑤権限・プライバシー・外部送信 ⑥Phaseゲート変更
- **ADRテンプレート**：ID／日付／ステータス／決定者／起案agent／関連仕様§／背景／決定／代替案と却下理由（表）／影響範囲／検証方法／再検討トリガー
- **初期ADR10件**（ADR-0010はG1クロスレビュー〔Gemini〕反映）：詳細は下記§8参照

## 8. DECISIONS.md 初期ADR一覧

`DECISIONS.md`に記載する初期10件のADR。**ADR-0004／ADR-0005／ADR-0006／ADR-0010はFable 5が個別裁定済み（前3件は2026-08-08付U1〜U3、ADR-0010はG1クロスレビュー〔Gemini〕を受けた同日付A2）の承認済み判断であり、本一覧でも「ユーザー承認待ち」ではなく「承認済み」と明記する。**

| ID | 決定内容 | ステータス | 関連仕様§／備考 |
|---|---|---|---|
| ADR-0001 | v2.0（Android版）を正仕様書とする | 既決 | ヘッダー |
| ADR-0002 | 単一`:app`モジュール構成を採用する | 起案済み（android-planner。本計画書のG1レビューで確定） | §43 |
| ADR-0003 | Phase 1は手動DI（`AppContainer`）とし、HiltはPhase 2先頭へ延期する | 起案済み（android-planner。本計画書のG1レビューで確定） | §42 |
| ADR-0004 | RoutingServiceは§46のシグネチャ（`estimateRoute(origin, destination, mode, departureDate: Instant): RouteEstimate`）を採用する | **承認済み（Fable 5裁定 U1、2026-08-08）**。仕様書§9のコード例へ本計画書提出と同時に適用済み（小修正A） | §9／§46 |
| ADR-0005 | 未定義型7種（PlanningContext／RecoveryPlan／RouteEstimate／Coordinate／CalendarSource／AIPlanResponse／AIRecoveryResponse）を計画メモ§7.2の補完案どおり確定する | **承認済み（Fable 5裁定 U2、2026-08-08）** | Phase 1契約scaffold |
| ADR-0006 | G4をG4-JVMとG4-Eの2段ゲートに分割し、TDD原則の例外にC1 Gradleブートストラップを追加する | **承認済み（Fable 5裁定 U3、2026-08-08）**。`docs/TEAMS.md`のTDD原則（62行目）へ本計画書提出と同時に適用済み（小修正B） | `docs/TEAMS.md` TDD原則／G4 |
| ADR-0007 | compileSdk／targetSdkは35で開始し、Phase 13配布前に再検討する | 起案済み（android-planner。再検討トリガーを明記） | §42 |
| ADR-0008 | 時間表現は`java.time.Duration`／`Instant`に統一し、`kotlin.time.Duration`と混在させない | 起案済み（android-planner。本計画書のG1レビューで確定） | §8／§48-52 |
| ADR-0009 | デフォルトロケールは`values/`=en、日本語は`values-ja/`=ja | 起案済み（android-planner。本計画書のG1レビューで確定） | §7 |
| ADR-0010 | Domain modelは全フィールド`val`＋`copy()`＋`init`再検証方式で実装する。仕様§48-52の`var`表記からの意図的逸脱である（生成後の再代入がinit検証を迂回するサイレント障害を防ぐため） | **承認済み（Fable 5裁定 A2、2026-08-08。G1クロスレビュー〔Gemini〕反映）** | §48／§49／§52。詳細は`docs/plans/phase1-ui-skeleton-domain.md`§9.1 |

※「起案済み」区分のADR（0002/0003/0007/0008/0009）は、Fable 5個別裁定（U1〜U6、A1〜A7）の対象ではなく、本計画書自体のG1レビュー（Pass1/Pass2＋Geminiクロスレビュー）を経て確定する通常の計画内判断である。ADR-0004／0005／0006はFable 5裁定U1〜U3、ADR-0010はG1クロスレビュー（Gemini）を受けたFable 5裁定A2により、いずれも個別に承認済みである。

## 9. 依存関係・技術選定の根拠

Phase 0はライブラリ依存を持たない（Gradleプロジェクト未成立。`docs/TEAMS.md`§6注記）。技術選定として記録すべきは以下の文書設計判断のみである。

- 6文書構成（README／ARCHITECTURE／PRODUCT／AI／PRIVACY／DECISIONS）は、実装フェーズで参照頻度・参照者の異なる情報（環境構築／設計／製品意図／AI方針／プライバシー／意思決定履歴）を分離し、各agentが必要な文書のみを読めば足りるようにするための分割である。
- 各文書冒頭に要約である旨を明記する方式は、仕様書v2.0との内容重複による将来的な齟齬（第二の正本化）を防ぐための設計判断である。
- `DECISIONS.md`のADRテンプレート採用は、`docs/TEAMS.md`§5「interface契約のバージョン付き変更経路」の記録先として機能させるための技術選定である。

## 10. 完了基準（G1証拠として揃えるもの）

- 6文書がリポジトリ直下に存在すること
- 各文書冒頭に§6の共通ルール文言があること
- 各節に参照仕様書§番号が付されていること
- `DECISIONS.md`に記録ルール・6トリガー・ADRテンプレート・初期ADR10件（ADR-0004/0005/0006/0010は承認済みと明記）が揃っていること
- 第二の正本化（仕様書と矛盾する断定的記述、または仕様書内容の丸ごと転記）がないことをFable 5が確認していること

## 11. 未解決事項・申し送り

- Gemini 3.5 Flashクロスレビューは実施済み。Phase 1計画書への指摘（A1〜A7）のうちA6（ADR-0010追加）を本書§8へ反映済み（§4参照）。
- `PRODUCT.md`§2（ブランド／ユーザー向けメッセージ）は仕様書内で「コピー未確定」と明記されている項目である。`PRODUCT.md`へ転記する際もこの未確定フラグをそのまま残し、確定コピーであるかのように書かないこと（§7.3に明記済み）。
- 空プレースホルダ禁止の原則（仕様§88）はPhase 0の文書自体には直接適用されないが、Phase 1以降のパッケージ構成（`services/calendar`等）で適用されるため、`ARCHITECTURE.md`の7.2②で言及するに留める。
- 計画メモに記載のなかった内容の追加、および転記漏れは確認していない（本書は計画メモ§3の全項目を転記済み）。
