# Action Starter (Android)

> 本書は `Action_Starter_Master_Specification_v2.0_Android.md`（正仕様書）の要約である。差異がある場合は仕様書が正。v1.0(iOS)はアーカイブ。

## 1. 一行定義（§1）

Action Starterは、カレンダーの「予定」を実行可能な「今の一つの行動」に変換する **Execution Assistant**（Action Layer for Calendar）である。Calendar／Maps／Task Manager／AI Schedulerのいずれでもない、Plan→Executionの間の空白を担当するアプリ。

## 2. 最上位原則と「本アプリでないもの」（§0）

> 人は予定を知らないから遅れるのではない。予定を、今やるべき一つの行動に変えられないから遅れる。

本アプリが**担当しないもの**（§0）:

1. カレンダーアプリ
2. Todoアプリ
3. 習慣化アプリ
4. 単純な遅刻防止アプリ
5. AIスケジューラー
6. 地図アプリ

本アプリが担当するのは **Plan → Execution** の間に存在する空白（§0）。

## 3. Phase進捗表（§64〜§77）

正仕様書のPhase定義（§64〜§77）とAndroid読み替えの詳細マッピングは `docs/TEAMS.md`§5 を正とする。以下は概要のみ。

| Phase | 仕様§ | 内容 | 状態 |
|---|---|---|---|
| 0 | §64 | Repo bootstrap・ドキュメント雛形 | 作成中（本コミットで6文書新規作成。G1内容確認は別途） |
| 1 | §65 | UI Skeleton + Domain（Mock） | 未着手 |
| 2 | §66 | Calendar統合 | 未着手 |
| 3 | §67 | Routing / Location | 未着手 |
| 4 | §68 | Basic Engine | 未着手 |
| 5 | §69 | Notification + Execution | 未着手 |
| 6 | §70 | Recovery Basic | 未着手 |
| 7 | §71 | Local LLM Runtime | 未着手 |
| 8 | §72 | Local AI Planning | 未着手 |
| 9 | §73 | Local AI Recovery | 未着手 |
| 10 | §74 | Personal Profile | 未着手 |
| 11 | §75 | Localization (ja/en) | 未着手 |
| 12 | §76 | Basic/AI Experiment | 未着手 |
| 13 | §77 | Google Play配布・実予定検証 | 未着手 |

第1弾リリース判定（90点基準）はPhase 0〜6 + Phase 11を対象とする。詳細は `docs/GOAL.md` を参照。

## 4. 環境セットアップ（Phase 1以降向け）

Phase 0時点ではGradleプロジェクトは未作成である（§64。計画書`docs/plans/phase0-repo-docs.md`§2.2）。以下はPhase 1着手時に必要な準備。

- **JDK**: 17系を使用する（追加ダウンロード不要な前提）。
- **Android SDK**: `ANDROID_HOME`／`ANDROID_SDK_ROOT`でSDKパスを解決する。Gradle CLIの個別インストールは不要で、リポジトリの`./gradlew`（Wrapper）を常に使用する。
- **`local.properties`**: SDKパス（例: `sdk.dir=/home/noritakasawada/Android/Sdk`）およびRoutes API等のAPIキーをここに置く。Gitに**コミットしない**（`.gitignore`への追記はPhase 1計画書の範囲。§7参照）。
- **AVD**: エミュレータ検証用に`actionstarter_test`という名前のAVDを作成する（ADR-0006のG4-E証拠取得に使用）。
- **KVM**: エミュレータの高速実行にKVMが必要。`kvm`グループに未所属の場合は`sudo usermod -aG kvm noritakasawada`を実行し再ログインする。KVM未解決の間はADR-0006によりG4-E（実機/エミュレータ証拠）がブロックされ、**Phase 3以降へキャリーすることは禁止**されている（`DECISIONS.md` ADR-0006）。G4-JVM（JVM/Robolectric側）はKVM解決を待たずPhase 2着手を許可する。

## 5. コマンド一覧（Phase 1以降で使用）

`--console=plain` を付与するとログが読みやすく、quality-runnerによるログ収集にも適する。

| コマンド | 用途 |
|---|---|
| `./gradlew :app:assembleDebug --console=plain` | Debug APKビルド |
| `./gradlew :app:testDebugUnitTest --console=plain` | JVM/Robolectric unit test実行 |
| `./gradlew :app:connectedDebugAndroidTest --console=plain` | Instrumented test実行（実機/エミュレータ必須） |
| `./gradlew :app:lintDebug --console=plain` | Lint実行 |
| `./gradlew :app:build --console=plain` | フルビルド（テスト・Lint込み） |

## 6. 文書ガイド

| 文書 | 内容 |
|---|---|
| `README.md`（本書） | 全体像・環境構築・コマンド一覧 |
| `ARCHITECTURE.md` | レイヤー構成・契約interface・Domain Model・テスト戦略 |
| `PRODUCT.md` | プロダクト定義・MVPユーザーフロー・KPI・完成条件 |
| `AI.md` | Local AI方針・LLM禁止事項・モデル運用 |
| `PRIVACY.md` | プライバシー方針・権限一覧・送信範囲 |
| `DECISIONS.md` | ADR記録（意思決定履歴） |
| `docs/TEAMS.md` | 開発ハーネス（役割分担・PDCA・呼び出し方法・品質ゲート）の正 |
| `docs/GOAL.md` | リリース判定基準（90点基準） |

開発フロー・agent呼び出し方法の詳細は本書ではなく `docs/TEAMS.md` を参照すること。

## 7. 秘密情報の非コミット方針

Routes APIキー・署名keystore・その他の秘密情報はリポジトリにコミットしない。`local.properties`等のローカル専用ファイルで管理し`.gitignore`で除外する。Phase 13配布時のkeystore・署名情報の非コミット確認は`docs/TEAMS.md`のG4補遺（Phase 13）を参照。権限・送信データの詳細方針は`PRIVACY.md`を参照。
