# Phase 8.5 実装計画書 — 空きRAM別モデル自動選択＋Settingsモデル選択UI

> 対象仕様: §18「モデル配布」（"端末性能によってモデルを選択できる構造を検討する。"）・§19「AI OFF時でも動作すること」
> 前提基盤: Phase 7 P7-C4/C6/C8（`DeviceCapability`/`ModelStorage`/`AiPreferences`/`ModelCatalog`確定）・ADR-0048（4型interface化）・ADR-0053（`installedEntry`のselectedModelId優先解決）・ADR-0057（`defaultProfilePeakRamBytes`）・phase8-a54-ram-tier-fix.md §10（実機根拠）
> 種別: 新機能（Phase 9より先行実装、ユーザー決定）。F-A/F-Bの2コミット構成を提案。
> 承認状態: **Step 2アーキテクトレビュー済み（Pass 1 CRITICAL 1件修正反映）・ユーザー承認済み（§11含む・2026-08-11）。Step 4（F-A Green）完了・F-B Red待ち**

---

## §0. 結論ファースト

A54実機受け入れ（`phase8-a54-ram-tier-fix.md`§10.3）で、日常使用の表記6GB機はavailMem実測2.0〜2.3GiBしかなく、既定モデルGemma4の必要量2.5GiB（`defaultProfilePeakRamBytes`2GiB＋マージン512MB）を恒常的に下回り、Basicへ静的縮退し続けることが判明した。本計画は**F-A: 空きRAMに応じてGemma4⇄Qwen3-0.6Bを自動選択する`ai/model/ModelSelector`の新設**と、**F-B: Settingsにモデル一覧UIを追加し`SettingsViewModel.kt:56`のGemma4固定を解消**する2機能で構成する。

**核心設計**: `ModelSelector`は「導入済みかつavailMemに収まる、品質順（Gemma4>Qwen0.6B）で最初に一致するモデル」を返す状態レス判定（`DeviceCapability.hasAvailableMemory`を内部で使うのみで新規ロジックを持たない）。`AiPreferences.selectedModelId`の既定値を実モデルid（Gemma4）から**新設の`"auto"`センチネル**へ変更し、`LocalAiGateway`は`selectedModelId=="auto"`のときだけ`ModelSelector`を経由する。**明示選択時（実モデルidが選択済み）の経路は無変更**——A54実測（§10.3）が示した「Gemma4選択時にavailMem不足でBasic縮退・クラッシュなし」という既存の正しい挙動をそのまま活かす。

**新規依存なし**。既存`DeviceCapability`/`ModelStorage`/`ModelCatalog`/`AiPreferences`の4型を再利用し、`ModelStorage`interfaceの変更もしない。

**アーキテクトレビューPass 1指摘（CRITICAL）の反映**: 初稿は`LocalAiGateway.checkInstalledModel()`の解決結果と、`LiteRtLmLocalLanguageModel`が実際にロードするモデル（`modelPathProvider`ラムダが独立に`modelStorage.installedModelPath()`を再解決）の一致を構造的に保証していなかった。auto選択時にGatewayがGemma4を選び検証しても、Engineは別解決でQwenをロードし得るバグを内包していたため、**解決済みモデルの絶対パスをGatewayから`generatePlan`へ明示引数として渡す設計**へ変更した。加えてEngineがプロセス内でキャッシュされ`modelPathProvider`が初回生成時にしか呼ばれない実装のため、**モデル切替時にEngineを明示的に再生成する機構**を追加する。詳細は§2「重要な発見2」・§3設計5〜7・§4・§6を参照。

---

## §1. 目的・背景

`phase8-a54-ram-tier-fix.md`§10.3の実測（詳細は同節参照。要約: ガード要求2.5GiB vs 実機availMem通常時2.01GiB・re boot後も2.23〜2.28GiBで頭打ち）により、**日常使用状態の表記6GB機ではGemma4既定モデルが実質常時Basic縮退する**ことが実機で確認された。同節ではQwen3-0.6B（要求1.75GiB）への**手動`run-as`編集**でのみAI描画を実証できた。これは製品として提供できる状態ではない。

ユーザー決定: 仕様Phase 9（Recovery AI化）より本機能を先行実装する。根拠は上記A54実測そのもの——Local AIの主要ユースケース（表記6GB機）で機能が実質使えない状態を放置したままRecoveryのAI化を進める優先順位は本末転倒である。

---

## §2. 仕様整合（事前確認結果）

**マスター仕様（`Action_Starter_Master_Specification_v2.0_Android.md`）grep結果**:
- `F97`はマスター仕様上の記法ではなくPhase 7計画書内部の機能番号（マスター仕様には存在しない）。矛盾なし。
- `§18`「モデル配布」節（L620〜）に**"端末性能によってモデルを選択できる構造を検討する。"**という記述を発見（L641）。本計画のF-A/F-Bはこの検討事項の具体化であり、**矛盾ではなく仕様が既に予期していた方向性の実装**。
- `§19`「AI OFF時でも動作すること」（Enhancement・非SPOF原則）と整合: 自動選択で候補が0件でも、既存の`AiFallbackReason`（後述§7）でBasicへ縮退するのみで新しい失敗様式を導入しない。
- `§5.3`はマスター仕様には存在しない——`phase7-local-llm-foundation.md`自身の内部節番号（RAM段定義）である。本計画書内で「§5.3」と書く既存コードのKDoc引用は計画書内部参照であり、マスター仕様の章番号と混同しないよう本計画でも同じ書式を踏襲する。

**`SettingsAiSafetyTest`（既存2件、AI OFF安全性2重検証）への再検証結果（初稿の誤りを訂正）**: `LocalAiGateway`への`modelSelector`既定値パラメータ追加自体は、両テストとも名前付き引数で構築しているため無改修で成立する（ADR-0053の`ModelStorageImpl(context, catalog=ModelCatalog.ALL, preferences=null)`と同型の後方互換パターン）。**しかし初稿はこの1点しか検証しておらず誤りだった**。実ファイルを再確認した結果、本ファイルは`LocalLanguageModel`の匿名実装`neverInvokedModel`を自前で持つ（`override suspend fun generatePlan(context: PlanningContext, samplingPolicy: SamplingPolicy): String = error(...)`）。§3設計5で`LocalLanguageModel.generatePlan`へ`modelPath: String`引数を追加するため、**このoverrideも同じ引数を追加しないとコンパイルが通らない**——機械的な1行修正が必須である。ロジック・アサーションの変更は不要（両テストとも`aiEnabled=false`または`未DL`の分岐でFallbackし`generatePlan`自体は呼ばれない設計自体は不変）。したがって`SettingsAiSafetyTest.kt`は**「シグネチャ追随のみの軽微な変更」として§4の変更ファイルへ移す**（非変更リストから削除）。

**隔離ガード（T-AIISO-4〜9・既存3ガード）への抵触なし**: `ModelSelector`は`ai/model/`配下で`DeviceCapability`/`ModelStorage`/`ModelCatalog`のみに依存し、`com.google.ai.edge.litertlm`・`services.routing`のいずれも参照しない。既存ガードは`walkTopDown()`で新規ファイルも自動走査するため、ガード側の追加変更は不要（新規テストケースも不要）。新設`ai/adapter/EngineLoadPolicy.kt`（後述§3設計6）も同様にlitertlm非参照のため抵触しない。

**重要な発見1（既存挙動との緊張関係）**: `ModelStorageImpl.installedEntry()`（ADR-0053／Phase 8 C1-C3）は、明示`selectedModelId`のファイルが**存在しない**場合、catalog順（Qwen先頭）へ**無音で**フォールバックする既存仕様である（`installedEntry()`本体参照）。これはF-Aが掲げる「明示選択時は黙って別モデルへ差し替えない」原則と字義上緊張する——ただし対象が異なる（既存仕様は「ファイル欠落」、F-Aは「availMem不足」）。**本計画はこの既存挙動を変更しない**（スコープ外・意図的）。理由と要否は§11「ユーザー確認事項」参照。

**重要な発見2（CRITICAL・アーキテクトレビューPass 1指摘、実ファイル再検証済み）**: `AppContainer.kt`（L319-326）は`LiteRtLmLocalLanguageModel`を`modelPathProvider = { checkNotNull(modelStorage.installedModelPath()) }`で構築しており、これは`checkInstalledModel()`が使う`modelStorage.installedEntry()`とは**別の、独立した`ModelStorage`呼び出し**である（`installedModelPath()`は内部で`installedEntry()`を再度呼ぶ、`ModelStorage.kt`実装確認済み）。auto選択時、`checkInstalledModel()`が`ModelSelector`経由でGemma4を選び検証を通しても、`modelPathProvider()`は`preferences.selectedModelId`（`"auto"`）がどのcatalog idとも一致しないため`installedEntry()`のADR-0053フォールバックへ落ち、catalog順先頭（Qwen3-0.6B）を独立に返しうる——**検証・選択したモデルと実際にロードされるモデルが乖離する**。さらに`LiteRtLmLocalLanguageModel.obtainEngine()`（L207-224、実ファイル確認済み）はEngineを`private var engine: Engine?`にキャッシュし、`modelPathProvider()`は`engine == null`の初回生成時にしか呼ばれない（`if (existing != null) return@withLock existing to 0L`で以降無条件再利用）——**F-BでユーザーがSettings UIからモデルを切り替えても、プロセス再起動まで旧モデルで推論が続く**（本日のA54実機受け入れで手動切替が`force-stop`を要した直接の理由と一致する）。是正方針は§3設計5〜7に反映した。

---

## §3. 機能一覧と仕様

### F-A: 実行時モデル自動選択（availMemベース）

**決定表**（`defaultProfilePeakRamBytes`＋`MEMORY_SAFETY_MARGIN_BYTES`（512MB）の実値から導出。既存カタログ実測値と完全一致することを確認済み）:

| availMem | 判定 | 根拠 |
|---|---|---|
| ≥2.5GiB | Gemma4選択 | `GEMMA_4_E2B_IT.defaultProfilePeakRamBytes`(2.0GiB)＋0.5GiB |
| 1.75GiB以上2.5GiB未満 | Qwen3-0.6B選択 | `QWEN3_0_6B_INT4_BLOCK32.defaultProfilePeakRamBytes`(1.25GiB)＋0.5GiB |
| 1.75GiB未満 | 候補なし→既存Fallback | いずれの要求も満たさない |

**導入状態との組合せ表**:

| Gemma4導入 | Qwen0.6B導入 | availMem | 自動選択結果 |
|---|---|---|---|
| ○ | 問わず | ≥2.5GiB | Gemma4 |
| ○ | ○ | 1.75〜2.5GiB未満 | Qwen0.6B |
| ○ | ✗ | 1.75〜2.5GiB未満 | 候補なし（Gemma4はavailMem不足・Qwen未導入） |
| ✗ | ○ | ≥1.75GiB | Qwen0.6B |
| 問わず | 問わず | 1.75GiB未満 | 候補なし |
| ✗ | ✗ | 問わず | 候補なし（＝既存`MODEL_NOT_INSTALLED`） |

Qwen3-1.7Bは自動選択の候補に**含めない**（P7-C8既知: 0.6Bより遅く品質も退化、非推奨確定済み）。

**設計**:
1. 新設`ai/model/ModelSelector.kt`（interface＋`ModelSelectorImpl`、既存`DeviceCapability`/`ModelStorage`と同型の1ファイル完結）。interfaceは`val candidates: List<ModelCatalogEntry>`（品質順候補、Gatewayが後述4の判定で再利用）と`fun select(): ModelCatalogEntry?`を持つ。`ModelSelectorImpl(deviceCapability, modelStorage, override val candidates: List<ModelCatalogEntry> = DEFAULT_AUTO_CANDIDATES)`。`DEFAULT_AUTO_CANDIDATES = listOf(GEMMA_4_E2B_IT, QWEN3_0_6B_INT4_BLOCK32)`（品質順・1.7B除外をコードで固定）。`select()`は`candidates`を順に見て「`modelStorage.finalFile(entry).isFile`（導入済み）かつ`deviceCapability.hasAvailableMemory(entry.defaultProfilePeakRamBytes + DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES)`（適合）」の最初の1件を返す。状態を持たず、呼び出しごとに独立評価（決定的ではないが副作用なし）。
2. `DeviceCapability.kt`: `LocalAiGateway`のprivate定数`MEMORY_SAFETY_MARGIN_BYTES`（512MB）を**`DeviceCapability.Companion`へ昇格**（public化）し、`LocalAiGateway`・`ModelSelectorImpl`の両方が同一シンボルを参照する単一情報源にする（2箇所に同値512MBを独立定義するDRY違反を避ける）。`hasAvailableMemory`本体・RAM段閾値（ADR-0061）は無変更。
3. `AiPreferences.kt`: `AUTO_SELECT_MODEL_ID: String = "auto"`を新設。`DEFAULT_SELECTED_MODEL_ID`の値をこのセンチネルへ変更（2026-08-10「既定モデル=Gemma4」ユーザー決定の**改訂**——本計画の承認をもって新たなユーザー決定とする）。既存フィールド型（`String?`）・KDoc構造は維持し記述のみ更新。
4. `LocalAiGateway.kt`: `checkInstalledModel()`内、`modelStorage.installedEntry()`呼び出しの前段に分岐を追加——`preferences.selectedModelId == AiPreferences.AUTO_SELECT_MODEL_ID`なら`modelSelector.select()`を使い、そうでなければ既存どおり`modelStorage.installedEntry()`を使う。**明示選択の下流処理（`hasAvailableMemory`再チェック・§8.6 #7以降）は完全に無変更**——auto経路で選ばれたエントリも同じ下流チェックを通る（後述§7の二重防御）。`select()`が`null`を返した場合、`modelSelector.candidates`のいずれかが導入済みかを`modelStorage.finalFile(it).isFile`で判定し、1件も導入されていなければ`MODEL_NOT_INSTALLED`、導入済みだが全滅なら`OUT_OF_MEMORY_PREVENTED`を返す（両者を区別する情報は`select()`のnull一値だけでは失われるため、`candidates`公開プロパティで補う）。コンストラクタへ`modelSelector: ModelSelector = ModelSelectorImpl(deviceCapability, modelStorage)`を末尾・既定値付きで追加（Kotlin既定値は直前の`deviceCapability`/`modelStorage`引数を参照可能、ADR-0053と同型の後方互換パターン）。
5. **【アーキテクトレビューPass 1 CRITICAL対応】解決済みモデルの単一情報源化**: `LocalLanguageModel.kt`（`ai/`凍結interface、ADR-0045/0050で契約変更前例あり）の`generatePlan`へ`modelPath: String`引数を追加する: `suspend fun generatePlan(context: PlanningContext, modelPath: String, samplingPolicy: SamplingPolicy = SamplingPolicy.Primary): String`。`LocalAiGateway.generatePlan()`は`checkInstalledModel()`が確定した`entry`から`modelStorage.finalFile(entry).absolutePath`を1回だけ計算し、`runValidationPipeline`→`invokeModel`→`model.generatePlan(context, modelPath, policy)`まで明示的に引き回す。`LiteRtLmLocalLanguageModel`のコンストラクタ引数`modelPathProvider: () -> String`は**廃止**し、`AppContainer.kt`の当該ラムダ配線を削除する（§4）。これによりGatewayが検証・選択したエントリと、Engineが実際にロードするパスが構造的に一致する。`generateRecovery`のシグネチャは対象外（Phase 7時点で未実装・呼び出されない契約のため、Phase 9実装時に同種の是正を要する点のみ申し送る）。
6. **Engineのパス変化検知と再生成**: `LiteRtLmLocalLanguageModel`は`engine: Engine?`に加え`loadedModelPath: String?`を`engineLifecycleMutex`配下で保持する。`obtainEngine(requestedPath: String)`は、既存Engineがあり`loadedModelPath == requestedPath`なら現行どおり再利用（loadMs=0）。パスが異なる場合は既存の`unloadEngine()`（OOM二次防御が既に持つ「Engineを破棄し次回再生成させる」ロジック）を先に呼んでから新パスで生成する（`close()`非冪等・V-2の既存規約をそのまま踏襲）。パス比較の判定自体（`loadedPath != requestedPath`という1行のロジック）は、`com.google.ai.edge.litertlm`を一切importしない新設`ai/adapter/EngineLoadPolicy.kt`（内部関数）へ抽出する——**`LiteRtLmLocalLanguageModel`自体はクラスファイルバージョン不一致（class file version 65、実測）によりJVM単体テストでは`UnsupportedClassVersionError`となりインスタンス化すら不可能**（`AiGatewayTestFixtures.kt`のKDoc確認済み、既存の`LocalAiGatewayTest`／`SettingsAiSafetyTest`が実装を一切参照しない理由と同一）であるため、この抽出によって判定ロジックだけはJVM単体テストの対象にできる（§6参照）。Engine自体の生成・ロードの実機検証は既存の`androidTest`プローブ方式（`LiteRtLmProbeTest`等）に委ねる。
7. **SHA検証キャッシュのper-id化**: `LocalAiGateway`の`sha256VerifiedEntryId: String?`（単一スロット）を`sha256VerifiedEntryIds: MutableSet<String>`へ変更する。モデル切替のたびに2.59GB級ファイルの再ハッシュを誘発する単一スロットの問題を解消し、検証済みidを蓄積する。`MODEL_CORRUPTED`検知時は該当idのみ`remove`する（他モデルの検証済み状態は保持）。Mutex内アクセスの既存前提（`inferenceMutex`）は維持する。
8. 選択結果の非サイレント記録: `AiResult.Success`側は`AiMetrics`へ`selectedModelId: String`を追加（モデルidはPIIでなく§60許可リストの精神に反しない。`T-AIMET-1`の許可リストテスト更新が必要）。`AiResult.Fallback`側は既存の`detail`文字列へ「auto: no candidate fits (availMem=…)」等を含める（新規Fallback理由は追加せず、既存`MODEL_NOT_INSTALLED`／`OUT_OF_MEMORY_PREVENTED`を流用、上記4参照）。
9. 全滅時（候補0件）は上記4のとおり既存`MODEL_NOT_INSTALLED`／`OUT_OF_MEMORY_PREVENTED`をそのまま使う（新規`AiFallbackReason`値は追加しない）。

### F-B: Settingsモデル選択UI

1. `SettingsUiState.kt`: 単一`modelStatus: ModelDownloadStatus`を、モデルごとの状態を持つ**リスト構造**（例: `models: List<ModelOptionUiState>`、各要素が`entry`か`"自動"`のいずれかを表し`status: ModelDownloadStatus`・`isRecommended: Boolean`・`isSelected: Boolean`を持つ）へ拡張。既存`DeviceUnsupportedReason`は無変更。
2. `SettingsViewModel.kt`: コンストラクタの`selectedModel: ModelCatalogEntry`単数パラメータを廃し、`availableModels: List<ModelCatalogEntry> = listOf(GEMMA_4_E2B_IT, QWEN3_0_6B_INT4_BLOCK32)`（Qwen1.7B除外、F-Aと同じ候補集合）へ置換。`refresh()`は各モデルの`modelStorage.finalFile(entry).isFile`から一覧状態を組み立てる。「選択」（`aiPreferences.selectedModelId`書き込み。「自動」選択時は`AUTO_SELECT_MODEL_ID`）と「ダウンロード」（特定行のモデルをDL）は**別アクション**として分離する（ダウンロード完了は選択を自動変更しない。§7「DL中に選択変更」参照）。
3. 段ベースの推奨表示: `deviceCapability.classify()`が`TIER_1_STANDARD`ならQwen0.6B行に推奨バッジ、`TIER_2_OPT_IN`ならGemma4行に推奨バッジ（既存`classify()`をそのまま再利用、ロジック変更なし）。
4. `SettingsScreen.kt`: モデル一覧を行ごとに描画（名称・サイズ目安・状態・DL/削除ボタン・選択ラジオ）。**1モデルでもダウンロード中は他行のDL/削除ボタンを無効化**（複数同時DLの排他制御をUI層で担い、`downloadJob`のMap化などViewModel側の並行制御複雑化を避ける）。
5. `strings.xml`（ja/en）: 新規キー4件程度——「自動（推奨）」ラベル、モデル別の説明文（サイズ・対象RAM帯目安）、推奨バッジ文言、選択状態文言。既存`settings_ai_*`／`settings_model_status_*`キーは無変更（意味が変わらないため）。`ModelCatalogEntry.displayName`（例: "Gemma 4 E2B-it (mixed 2/4/8-bit)"）はカタログ内部識別用のまま変更せず、UI表示文言は新規string resourceで別途持つ。
6. `AppContainer.kt`: `val modelSelector: ModelSelector by lazy { ModelSelectorImpl(deviceCapability, modelStorage) }`を追加し、`localAiGateway`構築へ`modelSelector = modelSelector`を明示的に渡す（他のAI関連プロパティと同じ`by lazy`規約）。`createViewModelFactory`の`SettingsViewModel`初期化子を新シグネチャへ更新。
7. 便乗修正: `docs/plans/phase8-a54-ram-tier-fix.md`ヘッダL6「残タスクは§8のA54実機受け入れ確認のみ」を、§10完了を反映した文言へ1行更新する（実装は本計画Step 4で行う。今回の起案では変更しない）。

---

## §4. 変更対象ファイル構成

### 新設
- `app/src/main/java/com/actionstarter/ai/model/ModelSelector.kt`（interface＋Impl）
- `app/src/test/java/com/actionstarter/ai/model/ModelSelectorTest.kt`
- `app/src/main/java/com/actionstarter/ai/adapter/EngineLoadPolicy.kt`（Engine再ロード要否の純粋判定関数、litertlm非依存。アーキテクトレビューPass 1指摘対応、§3設計6）
- `app/src/test/java/com/actionstarter/ai/adapter/EngineLoadPolicyTest.kt`

### 変更
- `app/src/main/java/com/actionstarter/ai/model/DeviceCapability.kt`（`MEMORY_SAFETY_MARGIN_BYTES`をpublic companion定数として追加。RAM段閾値・`classify()`・`hasAvailableMemory()`は無変更）
- `app/src/main/java/com/actionstarter/ai/AiPreferences.kt`（`AUTO_SELECT_MODEL_ID`新設・`DEFAULT_SELECTED_MODEL_ID`の値変更・KDoc更新）
- `app/src/main/java/com/actionstarter/ai/LocalLanguageModel.kt`（`generatePlan`へ`modelPath: String`引数追加。アーキテクトレビューPass 1指摘対応、§3設計5）
- `app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（`modelPathProvider`コンストラクタ引数廃止・`obtainEngine`のパス変化検知/再生成・`generatePlan`シグネチャ追随）
- `app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（`checkInstalledModel()`にauto分岐追加・コンストラクタへ`modelSelector`既定値付きパラメータ追加・`MEMORY_SAFETY_MARGIN_BYTES`参照先変更・`modelPath`引き回し追加・`sha256VerifiedEntryId`のSet化・`AiMetrics.selectedModelId`追加）
- `app/src/main/java/com/actionstarter/features/settings/SettingsUiState.kt`（モデル一覧構造への拡張）
- `app/src/main/java/com/actionstarter/features/settings/SettingsViewModel.kt`（コンストラクタ・`refresh()`・DL/削除/選択アクションの多モデル対応）
- `app/src/main/java/com/actionstarter/features/settings/SettingsScreen.kt`（一覧UI描画）
- `app/src/main/java/com/actionstarter/di/AppContainer.kt`（`modelSelector`プロパティ追加・`localAiGateway`の`modelPathProvider`ラムダ配線削除・`localAiGateway`/`SettingsViewModel`配線更新）
- `app/src/main/res/values/strings.xml`・`values-ja/strings.xml`（新規キー追加のみ、既存キー無変更）
- `app/src/test/java/com/actionstarter/ai/LocalAiGatewayTest.kt`（auto分岐・明示選択回帰の新規ケース追加、既存fakeの`generatePlan`シグネチャへ`modelPath`追加）
- `app/src/test/java/com/actionstarter/ai/AiGatewayTestFixtures.kt`（`FakeLocalLanguageModel.generatePlan`のシグネチャ追随。受け取った`modelPath`を記録し新規テストで検証可能にする）
- `app/src/test/java/com/actionstarter/features/SettingsAiSafetyTest.kt`（**初稿の「無改修」判定を訂正**。`neverInvokedModel`匿名実装の`generatePlan`へ`modelPath`引数を追加する機械的1行修正のみ。アサーション・テストの意図は無変更。§2参照）
- `app/src/test/java/com/actionstarter/features/SettingsViewModelTest.kt`（既存18件中、`selectedModel`単数依存分を新シグネチャへ書き換え。既存548行の大部分に影響）
- `docs/plans/phase8-a54-ram-tier-fix.md`（ヘッダ1行、便乗修正）

### 非変更（明示）
- `ai/model/ModelStorage.kt`（interface・`installedEntry()`のファイル欠落フォールバック挙動を含め無変更。§2「重要な発見1」参照）
- `ai/model/ModelCatalog.kt`（エントリ定義・`ALL`順序とも無変更。Qwen1.7Bはカタログに残置しUI非表示のみ）
- `ai/model/ModelDownloader.kt`・`ModelVerifier.kt`
- `ai/schema/`・`ai/prompt/`
- `generateRecovery`関連一式（Phase 7時点で未実装・未呼び出し契約のまま。Phase 9実装時に同種の是正を要する点のみ§3設計5で申し送り）
- 8件の隔離ガード本体（T-AIISO-4〜9・既存3ガード。新規ファイルは既存`walkTopDown`が自動網羅）
- マスター仕様書（§18の記述はそのまま。実装注記のみ本計画に記載）

---

## §5. 依存関係・技術選定の根拠

新規外部依存なし。`ai/model/`配下の純Kotlinクラス追加のみ。

**`ModelSelector`を「候補リスト＋auto判定」に限定し「明示/自動の分岐」自体は持たせない設計の理由**: `ModelSelector`に`AiPreferences`まで注入し「明示ならこう、自動ならこう」を内部で分岐させる設計も検討したが、責務が肥大化し（`AiPreferences`・`DeviceCapability`・`ModelStorage`の3依存になる）、かつ`LocalAiGateway.checkInstalledModel()`が既に「どの経路で解決するか」を判断する場所として確立している（既存の`modelStorage.installedEntry()`呼び出し）ため、分岐はGateway側に残し`ModelSelector`は「autoならどれを選ぶか」の1責務に絞った。テスト容易性も高い（`DeviceCapability`/`ModelStorage`ともfake実装のみで足り、Robolectric不要）。

**`MEMORY_SAFETY_MARGIN_BYTES`を`DeviceCapability`へ昇格する理由**: `ModelSelectorImpl`が`LocalAiGateway`のprivate定数を参照する手段がない。案として(a)独自定数を`ModelSelector`側に複製、(b)`DeviceCapability`へ昇格し単一情報源化、(c)呼び出し側から`Long`引数として渡す、の3案を検討。(a)は512MBという同一の意味を持つ値が2箇所で独立に宣言され将来ドリフトするリスクがあり却下。(c)は`ModelSelectorImpl`のコンストラクタが呼び出し側の知識（マージン値）に依存し過ぎる。(b)は`DeviceCapability`が既に`hasAvailableMemory`とRAM段閾値の両方を持つ「メモリ判定の権威」であるため意味的に最も自然であり採用した。

**`ModelStorageImpl.installedEntry()`のファイル欠落フォールバックに手を入れない理由**: この既存挙動（ADR-0053／Phase 8）は「ファイルが存在しない」という別種の異常に対する既存のグレースフルデグレードであり、F-Aが導入する「availMemに収まるか」という新しい判定軸とは独立の関心事である。同時に変更すると本計画のテスト範囲が既存Phase 8実装の再検証にまで広がり、スコープが肥大化する。§11で扱うか否かをユーザーに確認する。

---

## §6. テストケースリスト

`T-P85-*`で採番（新規体系）。分類ラベル: **[Red]**=新規ロジックの実装を要し現状は失敗する、**[born-green]**=既存コードの挙動がそのまま成立し回帰ロックとして機能する、**[既存書換]**=既存テストの構造を新シグネチャへ書き換える。**（アーキテクトレビューPass 1指摘②の訂正: 初稿の「Red対象は全件」という一括記述は誤りだった。明示選択経路は無変更のため一部born-greenが存在する。以下、各行に個別明記する）**

### ModelSelector単体（Robolectric不要、fake `DeviceCapability`/`ModelStorage`）

| ID | 分類 | 入力 | 期待 | 種別 |
|---|---|---|---|---|
| T-P85-1 | 正常 | Gemma4・Qwen0.6B両方導入・availMem=3GiB | Gemma4選択 | [Red] |
| T-P85-2 | 正常 | 両方導入・availMem=2.0GiB | Qwen0.6B選択 | [Red] |
| T-P85-3 | エッジ | 両方導入・availMem=2.5GiBちょうど | Gemma4選択（境界は「以上」） | [Red] |
| T-P85-4 | エッジ | 両方導入・availMem=1.75GiBちょうど | Qwen0.6B選択 | [Red] |
| T-P85-5 | 異常 | 両方導入・availMem=1.0GiB | 候補なし(null) | [Red] |
| T-P85-6 | 異常 | Gemma4のみ導入・availMem=1.8GiB | 候補なし(null)（Gemma4不足・Qwen未導入で代替不可） | [Red] |
| T-P85-7 | 正常 | Qwen0.6Bのみ導入・availMem=2GiB | Qwen0.6B選択 | [Red] |
| T-P85-8 | 異常 | 両方未導入 | 候補なし(null) | [Red] |
| T-P85-9 | エッジ | Qwen1.7Bのみ導入・availMem=3GiB | 候補なし(null)（自動選択対象外の回帰ロック） | [Red] |

`ModelSelector`は新設クラスのため1〜9は全件Red（実装前は`ModelSelectorImpl`自体が存在しないか`TODO()`のため失敗するのが正しい）。

### LocalAiGateway統合（fake `LocalLanguageModel`、既存`LocalAiGatewayTest`方式）

| ID | 分類 | 入力 | 期待 | 種別 |
|---|---|---|---|---|
| T-P85-10 | 正常 | selectedModelId=auto・両方導入・availMem十分 | Gemma4がロード対象として選ばれ推論経路へ進む | [Red]（auto分岐が未実装のため） |
| T-P85-11 | 正常 | selectedModelId=auto・Gemma4不足／Qwen適合 | Qwen0.6Bへ進む | **[born-green訂正済み]**（Red実測結果の反映。旧catalog順fallbackが「Qwenが先頭かつ本ケースの正解」という偶然によりauto分岐実装前から一致していたため） |
| T-P85-12 | 異常 | selectedModelId=auto・両方未導入 | `Fallback(MODEL_NOT_INSTALLED)` | [born-green]（旧ロジックでも`"auto"`はcatalog idと一致せずcatalog順fallback→未導入なら結果は同じくnull。Red実行時に実測で最終確認） |
| T-P85-13 | 異常 | selectedModelId=auto・両方導入だがavailMem不足 | `Fallback(OUT_OF_MEMORY_PREVENTED)` | [born-green]（旧ロジックのcatalog順fallbackで解決されるQwenも同じくavailMem不足でOOM_PREVENTEDとなるため偶然結果が一致。Red実行時に実測で最終確認） |
| T-P85-14 | 正常・既存回帰の固定化 | selectedModelId=gemma-4-e2b-it（明示）・availMem不足 | `Fallback(OUT_OF_MEMORY_PREVENTED)`。Qwenへの無音差し替えが起きないことをアサート（A54実測§10.3のGreen回帰ロック） | [born-green]（明示選択経路は無変更、アーキテクトレビューPass 1確定） |
| T-P85-15 | 正常 | selectedModelId=qwen3-0.6b-int4-block32（明示）・導入済み・availMem十分 | `ModelSelector`を経由せずQwenへ進む | [born-green]（同上） |

### Engine再ロード判定（新設、アーキテクトレビューPass 1 CRITICAL対応）

| ID | 分類 | 内容 | 種別・検証層 |
|---|---|---|---|
| T-P85-25 | エッジ | `EngineLoadPolicy`単体: `loadedPath=null`→`requestedPath="A"` | [Red]・JVM単体（再ロード要=true） |
| T-P85-26 | 正常 | `EngineLoadPolicy`単体: `loadedPath="A"`→`requestedPath="A"`（同一パス） | [Red]・JVM単体（再利用=false） |
| T-P85-27 | 異常 | `EngineLoadPolicy`単体: `loadedPath="A"`→`requestedPath="B"`（別パス） | [Red]・JVM単体（再ロード要=true） |
| T-P85-28 | 正常 | `LocalAiGatewayTest`層: auto解決がQwenのfinalFile→（fakeのavailMemを回復させ再呼び出し）→Gemma4のfinalFileへ変わったとき、fake`LocalLanguageModel.generatePlan`が受け取る`modelPath`引数が実際に切り替わることをアサート | [Red]・JVM |
| T-P85-29 | 回帰 | 明示選択（Gemma4）の経路でも、`model.generatePlan`へ渡る`modelPath`が`modelStorage.finalFile(gemma4Entry).absolutePath`と一致する | [born-green]・JVM |

**検証境界の明記**: `LiteRtLmLocalLanguageModel.obtainEngine()`自体が実際に行う「同一パス2回→Engine1回生成のみ・別パス→unload+再生成」というEngine生成回数の挙動は、クラス自体がJVM単体テストでインスタンス化不可（class file version 65、§3設計6参照）のため`app/src/androidTest/`の実機/エミュレータプローブでのみ検証する（既存`LiteRtLmProbeTest`と同型の使い捨てprobeを想定）。JVM側（T-P85-25〜27）が担保するのは「再ロードすべきか」という**決定ロジック**のみであり、実際のEngine生成・破棄の実機動作はT-P85-24（全体回帰）のJVM Green判定には含まれない。

### SettingsViewModel（多モデルUI）

| ID | 分類 | 内容 | 種別 |
|---|---|---|---|
| T-P85-16 | 正常 | モデル一覧が「自動」「Gemma4」「Qwen0.6B」の3件（Qwen1.7B非含有）で構成される | [Red] |
| T-P85-17 | 正常 | 各行の状態（未DL/DL中/検証済み）が個別のインストール状況を反映する | [Red] |
| T-P85-18 | 正常 | TIER_1端末→Qwen0.6B行に推奨バッジ／TIER_2端末→Gemma4行に推奨バッジ | [Red] |
| T-P85-19 | 正常 | 「自動」選択で`aiPreferences.selectedModelId`が`AUTO_SELECT_MODEL_ID`になる | [Red] |
| T-P85-20 | 異常 | TIER_0端末→モデル一覧全体が無効化される（既存`isDeviceSupported`連動の維持） | [Red]（構造自体が新設のため。判定ロジック`classify()`自体は既存流用） |
| T-P85-21 | エッジ | 1モデルDL中は他行のDL/削除ボタンが無効化される | [Red] |
| T-P85-22 | エッジ | 明示選択中のモデルを削除した直後のUI状態（§7参照） | [Red] |
| T-P85-23 | 回帰 | 既存18件中`selectedModel`単数依存分を新シグネチャで書き換え、意図・アサーションは維持 | [既存書換] |

### 全体回帰

| ID | 内容 |
|---|---|
| T-P85-24 | `:app:testDebugUnitTest`既存全件（本計画着手時点の実測件数を起票時にベースライン記録）Green維持。`:app:lintDebug` error 0維持 |

---

## §7. エラー＆レスキューマップ

| 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|
| 自動選択 | 候補が0件（導入済みモデルなし、または導入済みだが全てavailMem不足） | 導入済み0件なら既存`AiFallbackReason.MODEL_NOT_INSTALLED`、導入済みだが不適合なら既存`OUT_OF_MEMORY_PREVENTED`を流用。`detail`文字列に「auto: no candidate fits」等を明記（サイレントでない） | AIなしでBasic継続。理由はSettings/ログで確認可能 |
| Settings UI | DL中に別モデルのダウンロードが要求される | UI層で他行のDL/削除ボタンを無効化し二重起動を防止（ViewModelのdownloadJob単一フィールドを維持し複雑化を避ける） | 一度に1モデルずつのDLに制限されるが失敗やクラッシュはない |
| Settings UI | 明示選択中のモデルが削除される（ユーザー操作または破損検知） | `ModelStorageImpl.installedEntry()`の既存フォールバック（ファイル欠落時はcatalog順で他モデルへ）がそのまま働く。UIは削除直後に`refresh()`し、選択中モデルの状態が「未DL」に変わったことを表示する（選択値自体は変更しない） | 次回`generatePlan`実行時、実際にロードされるモデルが表示上の「選択」と一致しない可能性がある既存の既知ギャップ（§2「重要な発見1」・§11参照）。クラッシュはしない |
| LocalAiGateway | 自動選択直後にavailMemが急減する（他アプリのバックグラウンド起動等） | `ModelSelector.select()`後も既存の`hasAvailableMemory`再チェック（§8.6 #7）がそのまま働く二重防御。再チェックで不適合なら`OUT_OF_MEMORY_PREVENTED`。実ロード中のOOMは既存`catch (OutOfMemoryError)`（§8.6 #13、二次防御）が最終防御のまま。新規のロック機構は導入しない | 極めて狭い競合窓のみ。既存の二重防御で捕捉されクラッシュしない |
| `AiMetrics`拡張 | `selectedModelId`追加により`T-AIMET-1`許可リストテストが失敗する | テスト側の許可リストを新フィールド込みで更新（サイレントに素通りさせない。モデルidはPIIでないことを§60の精神に照らして確認済み） | ユーザー影響なし（内部テスト契約の更新） |
| `AiPreferences`既定値変更 | 2026-08-10ユーザー決定「既定=Gemma4」との不整合に見える | 本計画の承認自体を新たなユーザー決定として明示的に記録する（ADR、§8）。既存ユーザーで既に`selectedModelId`にGemma4が明示書き込みされている場合は移行対象外（§11で確認） | 新規ユーザー・未書き込みユーザーはauto既定の恩恵を受ける |
| モデル切替（アーキテクトレビューPass 1対応） | モデル切替直後の初回推論（旧Engine解放＋新モデルロードで初回ロードレイテンシが数十秒単位で再発する） | 既存の非同期Basic先行表示（`PlanReviewViewModel`のBasic即時表示→AI後差し替え設計、Phase 8 C1-C3で確立済み）がそのまま吸収する。新規のローディングUI・待機機構は追加しない | 切替直後の1回のみAI差し替えが遅い。Basic文言は即座に見えるためUXブロックはない |

---

## §8. ADR起票方針

起票直前の再確認（既存慣行）: `grep -n "^### ADR-" DECISIONS.md | tail -3`を計画書起案時点で実行した結果、最新確定ADRは**ADR-0061**（本Phase 8で本計画書起案者自身が起票）。したがって本計画の決定は**ADR-0062**（暫定）として、Step 4実装完了後に同じ手順で再確認のうえ`DECISIONS.md`へ正式起票する。記録する決定:
1. `AiPreferences.selectedModelId`の既定値をGemma4固定から`"auto"`センチネルへ変更する（2026-08-10決定の改訂）
2. 明示選択時は代替なしで縮退する（`ModelSelector`を経由しない）。ただし`ModelStorage.installedEntry()`の既存ファイル欠落フォールバックはスコープ外として維持する
3. Qwen3-1.7Bを自動選択の対象外とする（P7-C8既定の品質劣化・非推奨判断の踏襲）
4. **（アーキテクトレビューPass 1 CRITICAL対応）** `LocalLanguageModel.generatePlan`へ`modelPath: String`引数を追加し、Gatewayが検証・選択したモデルとEngineが実ロードするモデルを構造的に一致させる。`LiteRtLmLocalLanguageModel`の`modelPathProvider`コンストラクタ引数（独立解決の原因）は廃止する
5. `LiteRtLmLocalLanguageModel`のEngineキャッシュへパス変化検知を追加し、モデル切替時に明示的な再ロードを行う（従来はプロセス再起動まで旧モデルを使い続けていた）。SHA検証キャッシュも単一スロットからper-idのSetへ変更し、モデル切替のたびに大容量ファイルを再ハッシュする不具合を解消する

---

## §9. 実機受け入れ手順（A54）

1. Phase 8.5実装後のアプリをA54実機（§10と同じ日常使用状態）へインストールする。
2. Settings画面でモデル一覧が「自動（推奨）」「Gemma 4 E2B」「Qwen3 0.6B」の3項目で表示されることを確認する。
3. Gemma4・Qwen3-0.6Bの両方をダウンロードする。
4. AI有効化＋モデル選択を「自動」のままにする（新規既定）。
5. 予定を実行し、**`run-as`等の手動介入なしに**Qwen3-0.6Bが自動選択されAI文言（`phase8-a54-ram-tier-fix.md`§10.4と同種の文脈化文言）が描画されることを確認する。
6. Settings画面へ戻り、各モデル行の状態表示（検証済み・推奨バッジ）が実態と一致することを確認する。
7. スクリーンショットを取得し、本計画書または完了記録へ証拠として残す。

---

## §10. コミット粒度

**2コミット構成を提案（コーディネーター提案どおり妥当と判断）**:
- **コミット1（F-A）**: 本計画書新規作成＋`phase8-a54-ram-tier-fix.md`便乗修正＋`ModelSelector`新設＋`EngineLoadPolicy`新設＋`LocalLanguageModel`/`LiteRtLmLocalLanguageModel`の`modelPath`引き回し・Engine再ロード対応（アーキテクトレビューPass 1 CRITICAL対応）＋`DeviceCapability`/`AiPreferences`/`LocalAiGateway`の配線変更＋対応テスト（T-P85-1〜15・25〜29）。F-Aは既存呼び出し元に対し後方互換（既定値パラメータ）で単独に動作確認可能なため独立コミットに値する。
- **コミット2（F-B）**: `SettingsUiState`/`SettingsViewModel`/`SettingsScreen`刷新＋`AppContainer`DI更新＋strings追加＋対応テスト（T-P85-16〜23）。既存548行のテストファイル書き換えを伴う大きめの変更のため、F-Aと分離しレビュー・リバートを容易にする。

理由: F-AとF-Bは技術的に独立（F-BはF-Aが提供する`ModelSelector`/`AUTO_SELECT_MODEL_ID`を消費するのみで、F-A単体でも「自動」既定への切り替えという意味のある挙動変化が成立する）。2コミットに分けても各コミット時点でテスト全件Green・lint 0を維持できる。

---

## §11. ユーザー確認事項（Pass 2）

1. **DL済みGemma4を持つ6GB機ユーザーへの案内**: A54のように既にGemma4（2.59GB）をDL済みだが実質使えない端末に対し、Settings上で「このモデルは現在の空きメモリでは動作しない可能性が高い」といった警告や削除提案を出すか。出す場合、文言・判定タイミング（DL済み表示時に毎回`hasAvailableMemory`を再評価するか）を確定する必要がある。
   **【確定】推奨案どおり**: 削除提案はしない。Gemma4行に注記（警告文言）のみを表示する。
2. **「自動」の説明文言**: ユーザーに「自動」がどう振る舞うかをどこまで開示するか（「空き容量に応じてGemma 4またはQwen 3のいずれかを使用します」等の説明を出すか、単に「自動（推奨）」とだけ表示するか）。
   **【確定】推奨案どおり**: 「自動」に一文の説明文言を表示する（空き容量に応じてモデルを選ぶ旨を簡潔に開示）。
3. **既存ユーザーの移行方針**: 本計画以前に実際にSettings経由でGemma4をダウンロード済み（＝`selectedModelId`に`"gemma-4-e2b-it"`が明示書き込み済み）のユーザーは、既定値変更の対象外（明示選択のまま）になる。これを「自動」へ強制移行すべきか、明示選択を尊重して現状維持すべきか。
   **【確定】推奨案どおり**: 既存の明示選択は現状維持し、「自動」へ強制移行しない。
4. **§2「重要な発見1」への対応要否**: `ModelStorageImpl.installedEntry()`のファイル欠落時フォールバック（明示選択でも他モデルへ無音代替）を本計画のスコープに含め是正するか、既存の意図された挙動として維持するか。
   **【確定】推奨案どおり**: 本計画のスコープには含めない。Phase 9以降で再検討する申し送り事項とする（今回は不変）。
5. **Qwen3-1.7Bの扱い**: 「カタログに残すがUI非表示」を暫定措置とするか、Phase 9以降で削除も含め再検討する明示的な申し送り事項とするか。
   **【確定】推奨案どおり**: カタログ残置・UI非表示を維持し、Phase 9以降で削除を含め再検討する申し送り事項として明記する。
