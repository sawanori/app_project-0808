# Phase 9.5 実装計画書 — Local AI性能・品質の実機計測駆動改善

> 対象仕様: §73「Phase 9・Local AI Recovery」の性能・品質フォローアップ、§57「性能指標」、§11.1〜§11.3実機実測系。直接の起点はPhase 9計画書§4.6「Phase 9.5候補（実機計測ループ要）」と、同計画書Step 4（コミット3 Green）報告が持ち越したリファクタ候補
> 前提基盤: Phase 9（Local AI Recovery完了・A54実機受け入れ合格、ADR-0063）・Phase 8.5（`ModelSelector`自動選択・ADR-0062）・Phase 7（LiteRT-LM基盤・P7-C0/C5/C8実機実測、`LiteRtLmProbeTest`／`ModelComparisonProbeTest`のprobe規約確立）
> 種別: 計測駆動フェーズ（コミット0・M実施済み。F-4/F-5はコード変更を伴う優先繰り上げ機能）
> 承認状態: **敵対的レビュー反映済み（§13）。以下、完了項目は要約のみ（詳細はコミットログ・各節参照）**:
> - **M**（ベースライン計測、コミット9606135）・**F-4/F-5**（Recovery pairing交差一致緩和・warmゲート2層修正、288e9b9）・**F-5b**（既定値配線ギャップ修正、2acd2f2）・**A/B（F-4/F-5/F-5b）**（Recovery完全蘇生3/3確認、08de59a）は完了・コミット済み。
> - **F-1／F-1b／F-1c**（few-shotカテゴリ条件選択、Plan限定）は実装→A/B実測2回（`TITLE_COPY`→`MIN_QUALITY`）→撤退基準発動→**不採用・ロールバック確定**（09f4d99→12a14d6→608a478）。`EventCategoryClassifier`等はドーマント基盤（Phase 12実験材料）として残置。詳細は§14「A/B再実測（F-1）」「A/B判定（F-1b）」、結論は§3.2「F-1c」参照。
> - **F-2（Engineウォームアップ）はStep 3（Red）・Step 4（Green）とも実装済み・コミット済み（3dcb207）**——`LocalAiGateway.warmUp()`（aiEnabled→Tier→ABI→`resolveInstalledEntry`→強化availMemガード→`inferenceMutex.tryLock()`によるin-flightスキップ→`(model as? EngineWarmable)?.warmUpEngine`、例外は`warmUpEngine`呼び出しのみ捕捉しno-op化）・新設任意interface`EngineWarmable`を`LiteRtLmLocalLanguageModel`が実装（`obtainEngine`へ委譲）・`EventSelectionViewModel.init`から`warmUpAiIfPossible()`を1回実配線・`AppContainer`配線（実装時に`LinkageError`回帰〔ナビゲーションフロー起点での`localAiGateway`未捕捉伝播、13件失敗〕を発見し`eventSelectionLocalAiGateway`ガードで解消、§3.3参照）。T-P95-50〜57は全件Green。
> - **F-2b（ウォームアップのヘッドルーム再調整）は実装・実機検証とも完了（コミットf08859a）**——A54実機でF-2ウォームアップが**不発**（トップ画面20秒滞在後もPSS108,062KB＝モデル非常駐）と判明。実際に解決されたQwen3-0.6B（`defaultProfilePeakRamBytes`＝1.25GiB）に対し旧強化ガード要求2.25GiB（1.25GiB＋旧+1GiB）へ起動時availMem2.28GiBが僅差（約34MB）で近接し、`warmUp()`実評価時点にはアプリ自身の起動消費で閾値割れしていた（**+1GiBは6GB端末で常閉の門**）。`WARM_UP_EXTRA_HEADROOM_BYTES`を1GiB→**512MB**（通常ガード`MEMORY_SAFETY_MARGIN_BYTES`と同値）へ再調整し、強化ガードと通常ガードの数式が一致（旧非対称設計は解消）。T-P95-54を新閾値の境界（1バイト手前）へ再設計・対となるT-P95-63（ちょうど→呼ぶ）を新設し境界2点を回帰ロック（詳細は§3.3「F-2b」参照）。**実機再検証（発動確認）**: 修正後PSSが**526,475KB**まで増加しウォームアップ発動・エンジン常駐を確認（起動時availMem2.29GiB≥新閾値1.75GiB、§14「F-2b実機再検証」参照）。
> - **F-3（Recovery用maxNumTokensプロファイル縮小）は結論確定・クローズ・コミット済み（b956fdd）**——Step 3（Red）でアーキテクチャ論点（`LiteRtLmLocalLanguageModel`のEngineは1個・`maxNumTokens`は`generatePlan`／`generateRecovery`共有）を発見し、**F-3裁定（2026-08-12）: 本番配線は不成立として縮退（descope）**——Plan/Recoveryの大きい方を採用=無益、プロファイル不一致リロード=Recovery（時間クリティカル）に1.4秒ロードを差し込む最悪UXのいずれも不採用。計測を1回も実施せず構造分析だけで結論に到達した（§14「F-3裁定」参照）。`RecoveryPromptBuilder.estimateMaxNumTokens`はGreen実装済み（T-P95-58〜62、preface文字数からの直接算出方式・下限上限は`PlanPromptBuilder`と共有）だが「Recoveryトークン予算分析を記録するドーマント設計文書」として`generateRecovery`へは配線しない（`EventCategoryClassifier`と同方針、KDocに裁定を明記）。`LiteRtLmLocalLanguageModel`側の宣言のみスタブは将来性なしのため削除。**実装時の追加発見**: 当初のT-P95-59（「同一shotCount・maxOutputTokenでRecovery≤Plan」）はDEFAULT_SHOT_COUNT=2で実測すると成立しない（Recovery=1664>Plan=1280）ことが判明——原因はPlan版の「baseline-delta方式」が1206文字までのpreface内容を実質無料で1024トークン枠に含める一方、Recovery版の「直接算出方式」は同じ無料枠の恩恵を受けないという計算モデルの違いであり、Recoveryの実内容が大きいことを意味しない。system instructionのみ（shotCount=0）で比較する形にテストのスコープを訂正した（詳細は`RecoveryPromptBuilderTest.kt`のT-P95-59コメント参照）。
> - **PR-1（GPUバックエンド可否プローブ）は事前API調査・プローブ作成・実機検証とも完了——結論: GPU=NO_GO確定**（プローブ作成コミット25f069e）——litertlm-android 0.15.0のAAR実体を`javap -p`で調査しGPU API自体は公開されている（存在確認=GO、詳細は§3.5参照）ことを確認したうえで作成した`GpuBackendProbeTest`をA54実機で実行した結果、GPU側はEngine初期化（`modelLoadMs=6286`）までは成功したが初回推論`sendMessage`で`LiteRtLmJniException: Can not find OpenCL library on this device`により失敗（CPU側はTTFT約2.67秒・約9.71tok/sで完走）。A54はOpenCLライブラリをアプリへ公開しておらずLiteRT-LMのGPUバックエンドは実行不能——**GPU=NO_GO確定**（§14「PR-1実機検証結果」参照）。
> - **PR-2（マスク模範例・カテゴリ条件付きecho検出）は縮退（descope）を裁定——プローブ未実施**（2026-08-12）——根拠: ①本フェーズ全計測でecho発生0件（既存L2防御R1aで実害なし）②L1-bはF-1と同型の品質劣化リスクを便益不明のまま負う③R1bが依存するカテゴリ条件付けはF-1でA/B実測2回により陰性・ロールバック確定済み。Phase 12（品質定量化環境整備後）での再評価へ申し送る（§3.6・§14「PR-2裁定」参照）。
> 全757件Green（skip1）・lint error0（:app:testDebugUnitTest tests=757/skipped=1/failures=0/errors=0・JUnit XML集計で確認済み）。**PR-1/PR-2の実機結果記録は未コミット、報告・検収待ち（このあとRF-1・ADR-0064が続く）**

続けてF-2（Engineウォームアップ、§3.3）のStep 3（Red）を実施——`LocalAiGateway.warmUp()`宣言（本体`TODO()`）・新設任意interface`EngineWarmable`（`BenchmarkMetricsSource`／`EngineLoadStateSource`と同型）を`LiteRtLmLocalLanguageModel`が実装（本体`TODO()`）・`EventSelectionViewModel`へ`localAiGateway`引数と`warmUpAiIfPossible()`フックを宣言（**Step 4まで`init`から呼ばない**——`warmUp()`が`TODO()`のため、呼ぶと本ViewModelを構築する既存テストが軒並み`NotImplementedError`で壊れるため意図的に未配線）・T-P95-50〜57（当初案T-P95-11〜17はF-1/F-1bが1〜12を消費したため衝突、実装時に50〜57へ採番し直し、§7に改訂記録済み）。全8件`NotImplementedError`でRed（`warmUp()`のTODO由来、EventCategoryClassifier.classifyと同型のscaffold）。全ファイルは**未コミット**の変更（:app:testDebugUnitTest tests=751/skipped=1/failures=8/errors=0〔F-2新規8件のみRed、既存743件は無傷〕・:app:lintDebug error=0/warning=23、JUnit XML集計で確認済み）。**Greenには未着手、報告・検収待ち**

---

## §0. 結論ファースト

Phase 9.5は「実装してテストGreenにする」フェーズではなく、**①ベースライン実測→②改善実装→③A/B再実測で効果を数値確認する**計測駆動フェーズである。Phase 9計画書§4.6が申し送ったL1-a／L1-b／P1〜P5の6候補と、Phase 9 Step 4（コミット3）完了報告が持ち越したリファクタ候補を統合し、**M（ベースライン計測・最初に実施）→F-1〜F-3（A/B実測付き機能実装）→PR-1／PR-2（採否判断を要る探索プローブ）→RF-1（実機受け入れとセットのリファクタ）**の順で進める。P4は記録のみ（実装タスクではない）。

事前確認で2件の重要事実が判明した（§2詳細）:
1. **`androidTest`が現在コンパイル不能**。`ModelComparisonProbeTest.kt`／`LiteRtLmAdapterE2EProbeTest.kt`が、Phase 8.5（ADR-0062）で廃止済みの`modelPathProvider`コンストラクタ引数を参照したまま残っており、`:app:compileDebugAndroidTestKotlin`が実測で`FAILED`することを確認した。M着手前の必須前提修正として扱う。
2. `AiMetrics.peakNativeHeapBytes`は`Debug.getNativeHeapAllocatedSize()`（mallocアリーナ）ベースであり、コーディネーターが要求する「ピークPSS」とは別指標（mmapされたモデル重みページを捕捉しない）。既存`ModelComparisonProbeTest.PssPeakSampler`と同型のprobe専用サンプラで計測可能だが、本番`AiMetrics`へのPSSフィールド追加要否はユーザー確認事項とする。

---

## §1. 目的・背景

Phase 9はA54実機受け入れに合格したが（`docs/plans/phase9-recovery-ai.md`§14）、0.6Bモデルの品質天井（explanation文意のぎこちなさ、正直に記録済み）と性能特性（TTFT・メモリ・reject率）は「計測して初めて分かる」段階にとどまっている。Phase 9計画書§4.6はこれを「実機計測ループを要する」として明示的にPhase 9.5へ分離しており、ユーザーが継続実施を希望した。

目的は次の4点である。
1. Plan／Recovery生成のTTFT・decode速度・メモリ・reject率を**ベースラインとして数値化**する。
2. few-shot条件選択・Engineウォームアップ・Recovery専用トークンプロファイルという3つの改善レバーを実装し、各々についてA/B比較で効果を**実測で裏付ける**。
3. GPUバックエンド・マスク模範例という2つの未確定レバーを探索プローブで評価し、**採否をユーザーに委ねる**。
4. Phase 9から持ち越しのリファクタ候補（`LiteRtLmLocalLanguageModel`重複統合）を実機受け入れとセットで解消する。

---

## §2. 仕様整合（事前確認結果）

**重要な発見1（`androidTest`コンパイル不能）**: `grep`実測で`ModelComparisonProbeTest.kt`（2箇所）・`LiteRtLmAdapterE2EProbeTest.kt`（1箇所）が`LiteRtLmLocalLanguageModel(modelPathProvider = ...)`を参照していることを確認した。現行コンストラクタは`shotCount`／`maxNumTokens`／`threadCount`のみで`modelPathProvider`を持たない（Phase 8.5 ADR-0062決定5で廃止済み）。`./gradlew :app:compileDebugAndroidTestKotlin`を実行し、実際に`e: No parameter with name 'modelPathProvider' found`で`FAILED`することを確認した。同一ソースセット内の1件のコンパイルエラーは**他の全probe（`LiteRtLmProbeTest`等）も含めて`connectedAndroidTest`を実行不能にする**ため、これは軽微な既知バグではなくM着手のブロッカーである。

**重要な発見2（PSS vs native heap）**: `InferenceBenchmarkSnapshot.peakNativeHeapBytes`（`ai/BenchmarkMetricsSource.kt`）は`Debug.getNativeHeapAllocatedSize()`ベースで、`AiMetrics.peakNativeHeapBytes`（本番）も同一値をそのまま転記している。一方、`ModelComparisonProbeTest.PssPeakSampler`は`ActivityManager.getProcessMemoryInfo().totalPss`ベースの別指標をprobe専用に既に実装済みで、P7-C8実測では両者に約1.2GBの乖離（mmap分の示唆、同ファイルKDoc「実測済み」参照）が記録されている。「ピークPSS」はこのPSS系のことであり、本番`AiMetrics`には**現状フィールドが存在しない**。

**重要な発見3（few-shotのcategory体系の非対称）**: `PlanPromptBuilder.FewShotSeed`は`eventType: String`（値: `social`／`medical`／`travel`／`business_meeting`の4種、8件の模範例すべてに設定済み）を持つが、`RecoveryPromptBuilder.FewShotSeed`は`userTurn`／`eventTitle`／`modelOptions`のみでカテゴリ相当のフィールドを持たない。F-1をRecoveryへ拡張するには`RecoveryPromptBuilder.FewShotSeed`へ新規フィールド追加を要する。

**重要な発見4（カテゴリ推定ロジックは皆無）**: `grep -rln "inferCategory\|categoryFrom\|guessCategory\|detectCategory"`は0件。イベントタイトルからカテゴリを推定するロジックは本プロジェクトに一切存在せず、F-1はゼロから設計する。

**重要な発見5（ウォームアップとModelSelectorの整合リスク）**: `ModelSelectorImpl.select()`は状態を持たず、呼び出しごとに`deviceCapability`／`modelStorage`の**その時点の値**を独立評価する（キャッシュなし）。したがってウォームアップ時点（例: 画面入場時）に`select()`で解決したモデルと、実際の`generatePlan`／`generateRecovery`呼び出し時点（例: 数十秒〜数分後）に再解決されるモデルは、availMemが変動していれば**異なりうる**。`EngineLoadPolicy.requiresEngineReload`（純関数、パス不一致で`true`）が既にこのズレを検知し安全に再ロードするため誤動作はしないが、ウォームアップの投資（時間・電池）が無駄になる、あるいは二重ロードでかえって遅くなるリスクがある。F-2で正面から扱う（§3.3）。

---

## §3. 機能一覧と仕様

### 3.1 M: ベースライン計測基盤（最初に実施）

新設androidTestプローブ`PerformanceBaselineProbeTest`（`probe/`配下、既存`LiteRtLmProbeTest`／`ModelComparisonProbeTest`の実行規約を踏襲: `@Ignore`既定・`-e class`個別実行・Logcat TAG・adb pushモデル前提）。Plan／Recoveryそれぞれについて`LocalAiGateway`経由（3段検証パイプライン込み）でN回試行し、コールド／ウォームを区別して記録する。記録項目は`AiMetrics`実測値（`modelLoadMs`＝0でウォーム判定・`firstTokenMs`・`tokensPerSecond`・`sanityRejectCount`／`lastSanityRejectReason`）に加え、新設`PssPeakSampler`（`ModelComparisonProbeTest`の実装を転用）によるピークPSS。対象モデルはA54のauto選択結果（現状Qwen3-0.6B、Phase 8.5/9実測）を既定とし、Gemma4-E2Bとの再比較はP7-C8で既に完了しているため行わない（§6）。前提として`ModelComparisonProbeTest.kt`／`LiteRtLmAdapterE2EProbeTest.kt`の`modelPathProvider`参照を現行コンストラクタへ機械的に追随させる（挙動変更なし、コンパイル可能化のみ）。

### 3.2 F-1: few-shotイベントカテゴリ条件選択（L1-a／P2統合、**Plan限定スコープ**）

`ai/prompt/`配下に状態レスな純関数`EventCategoryClassifier`（イベントタイトル→カテゴリ）を新設する。入力はタイトル文字列のみ（PII非送信・§10不変、固定キーワード辞書のみでネットワーク不使用）。出力は`PlanPromptBuilder.FewShotSeed.eventType`と同じ4値＋不明時フォールバック。`buildFewShot`を拡張し、推定カテゴリに一致する模範例のみ（1〜2件）を選ぶ。不一致0件時は**現行の全件混合へフォールバックする**（few-shotが0件になる事態を避ける安全側設計）。

**分類器はロケール別（ja/en）キーワード辞書を持つ設計とする**（両レビュー一致）。`PlanPromptBuilder.buildFewShot(locale, ...)`が既にja/en例文プールを言語分離している既存規約と整合させ、`EventCategoryClassifier.classify(title: String, locale: Locale)`のようにlocaleを受け取り、ja辞書／en辞書を切り替える。

**Recovery側への適用は本フェーズでは行わない（敵対的レビュー指摘・採用A-3）**: `RecoveryPromptBuilder`のfew-shot模範例はja/en各2件しかなく、カテゴリ4種の条件選択を適用すると大半の実行で1件も一致せず常時フォールバック（全件混合）へ縮退するため、A/Bで効果を測定できない（模範例が2件しかない時点でカテゴリ選択の恩恵自体が構造的に成立しない）。`RecoveryPromptBuilder.FewShotSeed`へのcategory追加・Recovery側条件選択は行わない。Recovery側への展開はPR-2（few-shot模範例プール自体の見直し）の結果を見て将来判断する。

効果測定はMと同一プローブで、Plan生成について(a)エコー再現率（既知few-shotタイトルを含む無関係イベントでのL5 `FEW_SHOT_ECHO`発生率）、(b)TTFT（プレフィル短縮）をA/B比較する。

**実配線（Step 4 Green実装済み）**: `EventCategoryClassifier.classify`は`CATEGORY_PRIORITY_ORDER`の先頭から順にキーワード`contains`判定（`ignoreCase = true`）を行い、最初に一致したカテゴリを返す（複数一致時の優先順位を決定的にする、T-P95-6）。`PlanPromptBuilder.buildFewShot`は`eventTitle`が非nullなら`classify`でカテゴリを推定し、`FewShotSeed.eventType`が一致する模範例のみへ絞り込み、一致0件（`CATEGORY_UNKNOWN`を含む）なら`ifEmpty`で現行の全件混合へフォールバックする。**呼び出し元の実配線先は`LiteRtLmLocalLanguageModel.buildConversationConfig`（`ai/adapter/`）**——`promptBuilder.buildFewShot(context.locale, shotCount, eventTitle = context.event.title)`として`PlanningContext.event.title`（生タイトル、`build`が使う`truncateForPrompt`切り詰めは適用しない——LLMへ埋め込まれずKotlin側の分類判定にのみ使うため）を渡す。これにより`generatePlan`呼び出しのたびに実際のイベントタイトルからfew-shotのカテゴリ選択が発動する（`RecoveryPromptBuilder`側・`buildRecoveryConversationConfig`は無変更、Plan限定スコープのまま）。

**F-1b: 例文プール増強（実機A/B実測で発見した1-shot品質退化への対応、§14「A/B再実測（F-1）」参照）**: コミット09f4d99（F-1）後の実機A/B実測で、カテゴリ絞り込みが各カテゴリ1件のfew-shotを実質1-shot化させ、パターン多様性の喪失によりQwen3-0.6Bの出力がイベントtitleの丸写し（`TITLE_COPY`）へ退化しPlan生成3/3がFallbackする品質退行が判明した。`ContentSanityChecker`（L2）は安全側に正しく機能した（不良コンテンツを通過させずFallbackへ縮退）が、可用性が犠牲になった。対応として`JAPANESE_FEW_SHOT_SEEDS`／`ENGLISH_FEW_SHOT_SEEDS`を各カテゴリ2件（計16 seed、旧8 seedから倍増）へ増強した——social/medical/travel/business_meetingそれぞれに、既存4件（結婚式／歯科検診／出張／打ち合わせ、Wedding/Dental checkup/Business trip/Team meeting）と重複しない現実的な新規4件（誕生日会／健康診断／旅行／商談、Birthday party/Health checkup/Vacation trip/Client negotiation）を追加した。`buildFewShot`の選択ロジック自体は無変更（`seeds.filter { it.eventType == category }`が2件返すようになるだけ）。新規例文のdisplay_text（カテゴリ固有ステップのみ、`finish_current_task`／`leave`の定型文は対象外）は`ContentSanityChecker.containsVerbLikeExpression`（R2(c)）相当の動詞形チェックを自ら通過するよう作成時に自己検査済み（T-P95-11が回帰ロック）。タイトルの相互非重複はT-P95-10が回帰ロックする。

**F-1c: ロールバック（結論、§14「A/B判定（F-1b）→撤退基準発動→F-1c」参照）**: F-1b後の実機A/B判定でも3/3 Fallbackが継続した（理由は`TITLE_COPY`から`MIN_QUALITY`〔R2(c)動詞相当表現の不在〕へ変化）。2回連続で異なる理由による有意な品質退行を実測したため、計測駆動フェーズの撤退基準（§9・§0）が発動し、カテゴリ条件選択の本番配線を不採用としロールバックした——`LiteRtLmLocalLanguageModel.buildConversationConfig`は`eventTitle`引数を渡さず常に全件混合を使う（実装は同ファイルのKDoc参照）。

**結論**: カテゴリ条件選択は0.6B×現行プロンプト予算では品質下限を割る。1-shot相当（F-1直後）ではタイトル丸写しへ、2-shot相当（F-1b後）でも動詞相当表現を欠く低品質出力へと、失敗の形は変わっても可用性の回復には至らなかった——モデル規模・プロンプト予算に対して模範例の「絞り込みによる特化」よりも「量による安定化」（既存の4テーマ混合2-shot）の方が現行構成には適していたことを示唆する。プレフィル短縮（本F-1の当初の効果測定目標の一つ、§3.2冒頭「効果測定」参照）は本アプローチでは達成できなかった——TTFT短縮の本命はP4（KV／プレフィックスキャッシュAPI、§3.8「記録のみ」）待ちとする。`EventCategoryClassifier`・増強済みseedプール・関連ロジック／テストはPhase 12（より大きいモデル・より大きいプロンプト予算での再評価）向けのドーマント基盤として残す。

### 3.3 F-2: Engineウォームアップ（P1）

**トリガー画面（確定・敵対的レビュー指摘A-7）**: 候補から「PlanReview画面入場時」は除外する——同画面は入場と同時に`aiPlanContextualizer`経由の生成が即時自動開始されるため、その手前でのウォームアップは効果を持たない（オーケストレーター実機検証）。**確定: トップ画面（`EventSelection`）入場時のみ・都度実行・生成in-flight時（既に温め中／利用中）はスキップする**。

**ウォームアップ入口はGateway層（CRITICAL・オーケストレーター指摘・採用A-5）**: `ai/adapter/`のEngine準備メソッドをfeatures層へ直接公開せず、`LocalAiGateway.warmUp()`（仮称）を新設する。`warmUp()`は`generatePlan`／`generateRecovery`と同じ事前ガード列——`preferences.aiEnabled`→`deviceCapability.classify()`（Tier）→`isAbiSupported()`（ABI）→`resolveInstalledEntry()`（モデル解決）→availMemガード——を**すべて通過した場合のみ**adapter（`LiteRtLmLocalLanguageModel`）のEngine準備を呼ぶ。features層は`LocalAiGateway`のみを参照する既存の層規律（AppContainerが`LocalAiGateway`単一インスタンスを配線する既存設計）を維持し、AI OFFユーザーやTier/ABI非対応端末では`warmUp()`呼び出し自体が実質no-opになる（電池保護）。

**LMKマージン強化（採用A-6）**: ウォームアップは「まだ使われるかどうか分からない」任意の先行投資であるため、通常の推論ガード（`MEMORY_SAFETY_MARGIN_BYTES`＝512MB）より厳しい**`WARM_UP_EXTRA_HEADROOM_BYTES`＝+1GiB**を要求する（保守側の閾値、通常ガードを通過してもウォームアップは見送られうる非対称設計）。`onTrimMemory`連動でのウォーム済みEngineの能動的アンロードは本フェーズでは実装しない（§9再検討トリガーへ記載）。

**ModelSelector整合**: ウォームアップ後にavailMemが悪化し`select()`が別モデルを解決した場合は、既存`EngineLoadPolicy.requiresEngineReload`の再ロード検知にそのまま委ねる（§2発見5）。ウォームアップ自体を能動的にキャンセル・再評価する新規機構は本フェーズでは設けない（過剰設計回避）。

効果測定は画面入場からTTFTまでのwall timeをウォームアップ有／無でA/B比較する。

**実装注記（Step 4 Green実装済み）**: `LocalAiGateway.warmUp()`は`resolveInstalledEntry()`（`checkInstalledModel()`のサイズ／SHA-256再検証は行わない——ウォームアップは「使われるかもしれない」先行投資でありモデル整合性の最終確認は実際の`generatePlan`／`generateRecovery`実行時に委ねる設計）でエントリを解決し、`entry.defaultProfilePeakRamBytes + WARM_UP_EXTRA_HEADROOM_BYTES`を`hasAvailableMemory`へ渡す。in-flightスキップは`inferenceMutex.tryLock()`（非suspend、取得できなければ即座に`false`）で実装し、取得できた場合のみ`(model as? EngineWarmable)?.warmUpEngine(modelPath)`を`try/finally`で囲んで呼ぶ（`finally`で必ず`unlock()`）。新設任意interface`EngineWarmable`（`BenchmarkMetricsSource`／`EngineLoadStateSource`と同型）を`LiteRtLmLocalLanguageModel`が実装し、既存`obtainEngine(modelPath)`（`generatePlan`／`generateRecovery`と共有する再利用判定）へ戻り値を捨てて委譲する薄い委譲とした——`Conversation`は作らない。`warmUpEngine`呼び出しのみを`catch (CancellationException) { throw }` / `catch (Throwable) {}`で囲み、ウォームアップの失敗を無害なno-opにする（§8.7原則3を踏襲、`viewModelScope`のキャンセルは正しく伝播する）。

**`EventSelectionViewModel`実配線**: `init`から`refresh()`の直後に`warmUpAiIfPossible()`を1回だけ呼ぶ（`refresh()`自体はON_RESUME再チェック・`onRetry`からも呼ばれ画面滞在中に複数回発生しうるため、「画面入場時のみ」の粒度に合わせ`init`側に置いた）。`localAiGateway`は末尾・既定`null`の新規コンストラクタ引数とし、`null`なら`warmUpAiIfPossible()`は`?.let`でno-op（既存テストとの後方互換）。

**`AppContainer`実配線と実装時に発見した`LinkageError`回帰**: `EventSelectionViewModel`初期化子へ素の`localAiGateway`をそのまま渡す設計にしたところ、`NavigationFlowTest`・`CalendarNavigationFlowTest`・`NotificationPermissionRequestTest`・`SettingsNavigationTest`（計13件）が`UnsupportedClassVersionError`／`NoClassDefFoundError`で新規に失敗することを実測で発見した——`EventSelectionViewModel`はナビゲーションフローの起点（最初に構築されるViewModel）であり、`localAiGateway`（`by lazy`）の評価を`localAiPlanContextualizer`・`localAiRecoveryContextualizer`（既存の`LinkageError`防御）より前のタイミングで踏み抜くため、JDK 21未満の実行環境（本テストJVM）特有の`LinkageError`が未捕捉のまま伝播していた（Phase 8で`PlanReviewViewModel`配線時に発生したのと同型の再発、`AppContainer.localAiPlanContextualizer`のKDoc「実測で新規10件が失敗」参照）。既存の2箇所と同じ`try { localAiGateway } catch (e: LinkageError) { null }`パターンを踏襲した新規`eventSelectionLocalAiGateway: LocalAiGateway?`プロパティを追加し、これを`EventSelectionViewModel`へ渡すことで解消した（実機ART環境では本チェックは発生しない）。

**F-2b: ウォームアップのヘッドルームを512MBへ再調整（実機検証、2026-08-12）**: F-2実装（`WARM_UP_EXTRA_HEADROOM_BYTES`＝+1GiB）をA54実機（6GB端末）で検証したところ、トップ画面（`EventSelection`）へ20秒滞在してもPSSが108MBのままでウォームアップが**不発**（モデル非常駐）と判明した。起動時点のavailMemスナップショットは2,393,044kB≈2.28GiBで、実際に解決されたモデル（Qwen3-0.6B、`defaultProfilePeakRamBytes`＝1.25GiB）に対する旧強化ガード要求2.25GiB（1.25GiB＋旧+1GiB）にわずか約34MBの差で近接しており、`warmUp()`が実際に評価される時点（Compose UI初期化等でさらにメモリ消費が進んだ後）には閾値を割り込んでいた——起動直後のスナップショットと実評価時点は同一ではなく、後者はアプリ自身の起動消費でさらに目減りする。結果として**+1GiBはこの6GB端末では常閉の門**になっていた（P7-C8で「Gemma4の公称8GB前提が実測2.5GiB相当だった」と判明したのと同型の「保守しすぎたガードが実測で暴かれる」パターンの再発）。

これを受け`WARM_UP_EXTRA_HEADROOM_BYTES`を1GiB→**512MB**へ再調整した。根拠は`DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES`（通常推論ガードの512MB）と同値へ揃えること——直後の`generatePlan`/`generateRecovery`が+512MBのマージンで許可される以上、同一メモリを数秒早く確保するだけの行為（ウォームアップ）に独自の倍マージンを課す合理性はない。ウォームアップの真のコストは「温存したのに使われなかった場合の遊休常駐」であり、これは`onTrimMemory`連動アンロード（未実装、上記「LMKマージン強化」§9再検討トリガー参照）の領分であって、起動判定のマージンを厚くしても解決しない。この変更により強化ガードと通常ガードの数式が完全に一致し、旧来の「通常ガードは通るが強化ガードは通らない」非対称設計（採用A-6原案）は境界として存在しなくなった。境界はT-P95-54（新閾値の1バイト手前→呼ばない）・T-P95-63（新閾値ちょうど→呼ぶ、`>=`判定の境界を含む）が回帰ロックする（いずれも実際に解決されたQwen3-0.6Bの`defaultProfilePeakRamBytes`＝1.25GiBを使うfixture、`fakeQwen06bLikeEntry()`で実施——Phase 8.5由来の既存fixtureを流用し、実機で発見された事実に基づく境界値とした）。全757件Green（skip1）・lint error0で確認済み。完了報告後、実機再検証はオーケストレーターが実施する。

### 3.4 F-3: Recovery用maxNumTokensプロファイル縮小（P5）

`PlanPromptBuilder.estimateMaxNumTokens`と同型のRecovery版を新設する。Recoveryの出力上限（最大3件×explanation60字）はPlanのsteps出力より小さいため、より小さい`maxNumTokens`で足りる可能性がある。既存`VERIFIED_WORKING_MAX_NUM_TOKENS`下限のclampはそのまま維持し、実機成功確認済みの値を下回らせない（ADR-0057の教訓の踏襲）。JVMテスト可能（`estimateMaxNumTokens`と同じ純粋計算）。効果測定はRecovery生成のTTFT・メモリをF-1適用後の値と比較する。

**実装時の設計判断（Step 3 Red、`RecoveryPromptBuilder.estimateMaxNumTokens`）**: Plan版が採用する「baseline-delta方式」（`BASELINE_PREFACE_CHARS_P7C5`＝P7-C5実機実測で1024トークンが成功したときのpreface文字数スナップショットからの増分を算出）は、Recoveryには相当する実機検証済みbaselineが存在しない（P7-C5実測はPlanのみ対象）ため踏襲しない。存在しないbaselineを恣意的に仮定すると誤った安全性の印象を与えるため、Recovery版は実際のpreface文字数から**直接**トークン数を見積もる方式とする。下限・上限は`PlanPromptBuilder.VERIFIED_WORKING_MAX_NUM_TOKENS`・`PlanPromptBuilder.CONTEXT_LENGTH_CEILING`をそのまま参照する（Recovery側で再定義しない——この下限はP7-C5実機実測が確認した「モデル・ランタイム自体が確実に動作する最小値」であり、Plan固有の値ではないため単一情報源として共有する）。

**F-3裁定（アーキテクチャ論点の決着、2026-08-12）**: `LiteRtLmLocalLanguageModel`のEngineはインスタンス全体で1個（R-7）であり、`maxNumTokens`は現状コンストラクタレベルの単一値として`generatePlan`・`generateRecovery`の両方に共有されている（`EngineLoadPolicy.requiresEngineReload`はモデルパスの変化のみで再ロードを判定し、`maxNumTokens`の違いでは再ロードしない）。この構造分析の結果、Recovery専用プロファイルのEngineへの実配線は**不成立として縮退（descope）と裁定した**——理由は2点: (a) Plan/Recovery両方の必要量の大きい方をEngine全体で採用する統合は、実質的に現状（Plan基準の`maxNumTokens`をそのまま使う）と同じであり新設する意義がない。(b) 要求元に応じてプロファイルが変わるたびにEngineを再ロードする統合は、Plan⇄Recovery間を行き来するたびに実機実測で確認済みのロード時間（約1.4秒）を挟む。しかもRecoveryは「予定に遅れそうなときに助言する」という**時間クリティカル**な機能であり、まさにその瞬間に1.4秒のロード遅延を追加することは最悪のUXになる。**計測を実施する以前に、構造分析だけでこの結論に到達したケース**として記録する（§14参照）。プロファイル分離という発想自体は、Engineが複数プロファイルを同時に持てる、またはKVキャッシュ機構により再ロードなしでコンテキスト長を切り替えられる将来のランタイムでのみ意味を持つ（§3.8「記録のみ」のP4 KV／プレフィックスキャッシュAPI定点観測と対になる将来課題として申し送る）。

`RecoveryPromptBuilder.estimateMaxNumTokens`自体はGreen実装済み（T-P95-58〜62で検証済み）だが、**「Recoveryトークン予算の分析を実行可能な形で記録する設計文書」としてドーマント残置する**（`EventCategoryClassifier`と同じ方針）——`generateRecovery`へは配線しない。上記の理由と本裁定を関数自身のKDocに明記した。`LiteRtLmLocalLanguageModel`側の宣言のみスタブ（`defaultRecoveryMaxNumTokensFor`）は将来配線の予定がないため削除した（`EventCategoryClassifier`とは異なり、こちらは「将来モデルが変われば有望」という余地がなく、構造的制約〔シングルトンEngine〕そのものが変わらない限り無意味なため）。

### 3.5 PR-1: GPUバックエンド可否プローブ（P3）

Mali-G68（A54搭載GPU）でLiteRT-LM 0.15のGPUバックエンドAPIが公開されているか事前調査したうえで、androidTestプローブ1本（`Backend.GPU(...)`相当）を作成する。API自体が非公開ならプローブを組めない事実そのものをNo-Go記録として報告する。本採用（production defaultsをGPUへ切替）はプローブ結果を踏まえた**別途ユーザー判断**とする（§12確認事項1）。

**事前調査結果（API存在確認、2026-08-12、AAR実体調査）**: Web検索ではなく手元のGradleキャッシュに実際に存在する`litertlm-android-0.15.0.aar`由来のAPI jar（`~/.gradle/caches/8.14.5/transforms/*/transformed/litertlm-android-0.15.0-api.jar`）を`javap -p`で直接調査した。`com.google.ai.edge.litertlm.Backend$GPU.class`が存在し、`public com.google.ai.edge.litertlm.Backend$GPU()`（引数なしコンストラクタ）を確認した。`EngineConfig`のコンストラクタは`backend: Backend`を受け取り（`CPU`／`GPU`／`NPU`／`GOOGLE_TENSOR`いずれのサブ型も可）、`Backend.GPU()`をそのまま渡せることを確認した。**API自体は公開されている（存在確認=GO）**ため、§3.5が定める「API非公開ならプローブを組まずNo-Go記録」の分岐は不採用とし、`GpuBackendProbeTest`（androidTest、`app/src/androidTest/java/com/actionstarter/probe/GpuBackendProbeTest.kt`）を新設した。API存在＝実機での動作保証ではない（モバイルGPU delegateはINT4 block-32量子化・特定演算子に対応しないことが珍しくない）ため、実機Go/No-Goは本プローブの実行結果（オーケストレーター実施）に委ねる。プローブはCPU/GPU双方を完全に同一の`EngineConfig`（`modelPath`・`maxNumTokens`）・同一の`ConversationConfig`・同一のdata message（`PlanPromptBuilder`、Mベースラインと同じ構成）で実行し、TTFT・decode tok/sを直接比較できる設計とした。

**実機検証結果（GPU=NO_GO確定、2026-08-12、詳細は§14「PR-1実機検証結果」参照）**: オーケストレーターが`GpuBackendProbeTest`をA54実機で実行した。CPU側は完走（TTFT約2.67秒・約9.71tok/s・schemaValid）した一方、GPU側はEngine初期化（`modelLoadMs=6286`）までは成功したものの、初回推論（`sendMessage`）で`LiteRtLmJniException: Can not find OpenCL library on this device`により失敗した。A54（SCG21）はMali-G68 GPUを搭載するが、OSがOpenCLライブラリをアプリへ公開しておらずLiteRT-LMのGPUバックエンドは実行不能——**GPU=NO_GO確定**。§12確認事項1「GPUバックエンドが動作した場合の本採用可否」は判断対象自体が実機で成立しなかったため**消滅**する。本採用は行わない。

### 3.6 PR-2: マスク模範例・カテゴリ条件付きecho検出（L1-b／R1b統合）

F-1が確立するカテゴリ推定基盤の上に、(a) L1-b: few-shotの`userTurn`から実タイトル文字列を`[EVENT_TITLE]`等のプレースホルダへ置換する設計、(b) R1b: `ContentSanityChecker`へmodelStepsのdisplay_text echo検出（同カテゴリ限定）を追加する設計、の2案を探索的にプローブし、エコー率とSemantic Contextualizationの質（目視評価）を比較する。採否基準は§12確認事項3で確定する。本フェーズの成果物はプローブ結果と採否提案であり、採用が決まった場合の本実装は次フェーズへ回す（スコープ肥大化回避）。

**裁定（縮退、2026-08-12、詳細は§14「PR-2裁定」参照）**: プローブを実施せず縮退（descope）すると裁定した。根拠は3点——①本フェーズを通じた全計測（M・各A/B・GPU probe含む）でecho発生が0件であり既存L2防御（R1a）で実害が観測されていない、②L1-bの模範例構成変更はF-1が実証した「構成変更が0.6B品質を崩しうる」という同型リスクを便益不明のまま負う、③R1bが依存するカテゴリ条件付けはF-1がA/B実測2回で陰性・ロールバック確定済み（§3.2「F-1c」）。§12確認事項3「L1-b／R1bの採否基準」は判断対象（プローブ結果）自体が存在しないため**縮退により消滅**する。Phase 12（品質定量化環境整備後）での再評価へ申し送る。

### 3.7 RF-1: `LiteRtLmLocalLanguageModel`重複統合（持ち越し）

Phase 9 Step 4（コミット3）Green報告の判断を踏襲する: `buildConversationConfig`／`buildRecoveryConversationConfig`・`buildDataMessage`／`buildRecoveryDataMessage`の重複（各ペア10〜15行）を、`toMessages`共有ヘルパー・`buildConversationConfig`統合・`withConcisenessConstraintIfNeeded`共有ヘルパーへ整理する。本ファイルはJVMテストで一切検証できない（class file version不一致、`AiGatewayTestFixtures`のKDoc参照）ため、単独では実施せず、本フェーズの実機受け入れ（§10）とセットで実施し、F-1〜F-3による同ファイルへの変更とあわせて動作確認する。

### 3.8 記録のみ: P4 KV／プレフィックスキャッシュAPI定点観測

LiteRT-LMのリリースノートを定点観測し、対応APIが追加されたらTTFT桁改善が見込めるかを評価する。実装タスクではなく、Phase 9計画書§4.6の記載をそのまま監視対象として申し送る。

### 3.9 F-4: Recovery pairing検証の交差一致緩和（§14発見①起因・優先繰り上げ）

**経緯**: M実測（§14）で、Recovery全15試行が`SCHEMA_INVALID`で一貫してFallbackすることが判明した。実際の失敗パターンは複合的である——Primaryは正しく2件（expected集合と一致）返すが一方の`explanation`がContentSanityChecker②で`MIN_QUALITY`によりreject、続くRetryは3件目`skip_optional_and_important_steps`を余分に追加しpairing（①、完全一致）に失敗する。これは計画書§9の再検討トリガー「pairing不一致に起因するFallback率が高いことが判明した場合、Best-Effort部分適用（交差一致）への緩和を検討する」の発動条件が実測で成立したものであり、当初Phase 9.5の対象外だった（Phase 9計画書§4.4で「本フェーズでは採用しない」としていた）Best-Effort部分適用を、優先繰り上げでF-4として実施する。

**変更内容**: `RecoverySchemaValidator`のpairing検証を「`expectedSemanticActions`との完全一致」から「**交差一致**」へ緩和する。返却された`semantic_action`集合（重複禁止は既存のまま維持）と`expectedSemanticActions`の交差が1件以上あれば、**交差分のみ**を`AIRecoveryResponse.options`として採用し`Valid`とする（余分な`semantic_action`のオプションは応答から除外する。`LocalAiRecoveryContextualizer.overlay`のMap引き当ては元々base側に存在しないsemanticActionを無視する構造のため、除外自体は安全側の整合強化）。交差が0件（完全に無関係な集合）の場合は引き続き`Invalid`。交差の内外を問わず重複検出は既存のまま（緩和しない）。

あわせて`RecoveryPromptBuilder.buildSystemInstruction`のルール1を強化し、「expected件数と同数だけ・列挙された値のみを返す（多くも少なくもしない）」という制約をより明示的な文言で追記する（プロンプト強化はベストエフォートの補助策であり、確実性はKotlin側のF-4検証ロジックが担保する）。

### 3.10 F-5: Planウォームゲート自爆の修正（§14発見②起因・優先繰り上げ、Red検収での差し戻し訂正・Green実装済み）

**経緯**: M実測（§14）で、Engineが既にロード済みの状態でも、2回目以降の呼び出しで§8.6 #7のOOM事前ガードが「これから新規ロードする」前提の閾値をそのまま再適用し、ロード済みEngineの自己メモリ消費によってガード自体が自爆的に発動する（wallMs=1〜3msの即時Fallback）ことが判明した。5バッチ中2バッチ（起点availMemが相対的に低い場合）で再現しており、F-2（Engineウォームアップ）が実装されると「温めたのに2回目呼び出しで即Fallbackする」という直接的な悪影響を及ぼすため、F-2より先に優先繰り上げでF-5として修正する。

**Red検収での差し戻し訂正（設計の根本的な誤同定を修正）**: 当初設計は`LocalAiGateway`のpost-selection OOMガード（`checkInstalledModel`成功後・`runValidationPipeline`直前の`hasAvailableMemory`チェック）のみを対象にしていたが、実測ログの`detail`文字列（`"auto: no candidate fits available memory (§8.6 #7, candidates=[...])"`）は`unresolvedEntryFallback()`が組み立てる固定文言そのものであり、この経路は`checkInstalledModel()`→`resolveInstalledEntry()`→`modelSelector.select()`がauto選択で`null`を返した場合にのみ到達する——すなわち実際の欠陥は**`ModelSelectorImpl.select()`自身が候補ごとに課す`hasAvailableMemory`フィルタの内部**にあり、post-selectionガードへ到達する**前**に選定そのものが失敗していた。当初のRedテスト（T-P95-42/44、明示選択のみ）はこの経路を一切踏んでおらず（明示選択は`ModelSelector`を経由しない）、実際に壊れているauto選択経路を検証できていなかった。§3.10の旧版が記載していた「`ModelSelector.select()`自体は変更しない」という設計判断は、この誤った前提（欠陥は選定後にある）に基づくものであり誤りだった。

**訂正後の変更内容（二層構成、単一の`EngineLoadStateSource`を共有）**:
1. 新規`interface EngineLoadStateSource { fun loadedModelPath(): String? }`（`BenchmarkMetricsSource`と同型の任意実装interface、`ai/EngineLoadStateSource.kt`）を新設し、`LiteRtLmLocalLanguageModel`が実装する（既存の`private var loadedModelPath`フィールドをそのまま公開する薄い委譲）。
2. **層1（選定時点、新設）**: `ModelSelectorImpl`のコンストラクタへ`engineLoadStateSource: EngineLoadStateSource? = null`（既定`null`、後方互換）を追加する。`select()`の候補ごとのフィットネス判定を、「`modelStorage.finalFile(entry)`の絶対パスが`engineLoadStateSource?.loadedModelPath()`と一致すれば`hasAvailableMemory`チェックをスキップして適合扱い」へ変更する。**品質順走査自体は変更しない**——ロード済みでない上位候補が通常のavailMem判定で適合する場合はそちらが優先されたままである（T-P95-47、born-green）。
3. **層2（post-selection、当初設計を維持）**: `LocalAiGateway`の`generatePlan`・`generateRecovery`双方のOOM事前ガードへ、`(model as? EngineLoadStateSource)?.loadedModelPath()`が解決済みエントリの絶対パスと一致する場合にガードをスキップする条件を追加する（新設`private fun isEntryAlreadyLoaded`で共有）。**この層は明示選択（`selectedModelId`が具体的なモデルID、`ModelSelector`を一切経由しない）経路で唯一の防御になるため層1と独立に必要**（T-P95-42・44が明示選択・ロード済みの成功、T-P95-43・45が明示選択・未ロードの回帰、T-P95-48がauto選択でロード済み候補が層1により選定候補へ復帰し層2も正常に通過することを回帰ロックする）。
4. `AppContainer`は`modelSelector`・`localAiGateway`の両方が**同一の`LiteRtLmLocalLanguageModel`インスタンス**（新設`private val localLanguageModel by lazy { ... }`）を参照するよう配線する。`EngineLoadStateSource`が意味を持つのは問い合わせ先が実際にEngineをロードする当のインスタンスと同一である場合に限るため、両層が同じロード状態を観測できることが前提となる。

**`@Volatile`対応（実装時に判明した追加修正）**: `loadedModelPath`フィールドへの書き込みは引き続き`engineLifecycleMutex`配下（`obtainEngine`／`closeEngineAndClearLocked`）に限定するが、新設の`loadedModelPath()`（`EngineLoadStateSource`実装）は`LocalAiGateway`／`ModelSelectorImpl`という別のコルーチン・別のMutex（`inferenceMutex`）から`engineLifecycleMutex`を取得せずに読まれる。Kotlin/JVMの`Mutex`はスレッド非依存でコルーチンを跨ぐため、フィールドを素の`var`のままにすると書き込みが他スレッドから可視とは限らない。同ファイル内で確立済みの`@Volatile private var lastMetrics`と同型のパターン（単一書き込み元をMutexで直列化したまま、フィールドを`@Volatile`化して読み取り側にロック取得を要求せず可視性のみ保証する）を踏襲した。

**F-5b: A/B実測で発見した既定値配線ギャップとその修正（2026-08-12、オーケストレーター実機A/B実測・`build/agent-logs/phase9.5-f45-ab-logcat.log`）**: F-4/F-5 Green後の実機A/B実測で、**F-4は成立**（Recoveryコールド試行がRESULT=Success、ベースライン15/15全滅から初の成功）した一方、**F-5は実機で未発動**（warm試行が依然`auto: no candidate fits`で即Fallback・wallMs=1）と判明した。原因は`LocalAiGateway`コンストラクタの既定値`modelSelector: ModelSelector = ModelSelectorImpl(deviceCapability, modelStorage)`が`engineLoadStateSource`を渡さない（`null`既定）ままだったこと——`AppContainer`は明示配線済みのため無関係（本番は無傷）だが、**`modelSelector`を明示的に渡さずこの既定値経由で構築する全ての呼び出し元でF-5免除が機能しない**。実機A/Bで実際に使われた`PerformanceBaselineProbeTest.probePlanBaseline`／`probeRecoveryBaseline`はいずれも`modelSelector`引数を渡しておらずこの経路を踏んでいた（ソース確認済み）。JVMテスト（T-P95-48等）がGreenだったのは`ModelSelectorImpl`を明示構築して`engineLoadStateSource`を渡していたためであり、既定値そのものは検証していなかった（統合ギャップ）。

**修正**: 既定値を`ModelSelectorImpl(deviceCapability, modelStorage, engineLoadStateSource = model as? EngineLoadStateSource)`へ変更した（Kotlinの既定値式は同一コンストラクタ内の先行パラメータ`model`を参照可能。ADR-0053の`ModelStorageImpl`と同型の既定値パターン）。`AppContainer`の明示配線は無変更。新規JVMテスト`LocalAiGatewayTest.tP95_49`が、`modelSelector`省略・auto選択・ロード済みQwen・availMem低の組み合わせで既定値経由の配線を直接検証する（実カタログ`ModelCatalog.DEFAULT_AUTO_CANDIDATES`はsizeBytes・sha256とも実物のため、344MB実ファイルを用意できない都合上、Fallback理由が`OUT_OF_MEMORY_PREVENTED`〔`select()`除外〕から`MODEL_CORRUPTED`〔`select()`が候補を返した証拠〕へ遷移することで検証し、Success自体の再確認は実機`PerformanceBaselineProbeTest`のA/B再実測に委ねる）。

---

## §4. 計測方法論（必須）

### 4.1 試行回数N（コールド/ウォームのペア計測、敵対的レビュー指摘・採用A-2）

**プロセス起動5回×各回（コールド1回＋ウォーム2回）**を既定とする（コールドN=5・ウォームN=10）。Engineはプロセス内シングルトン（R-7）のため、1プロセス起動につき1回目の呼び出しが必ずコールド、2・3回目が同一Engine再利用のウォームになる（`modelLoadMs`＝0で判定）。**同一プロセス内でコールドとウォームをペアにする理由**: 5回とも独立にプロセス再起動していた旧設計では、コールド試行とウォーム試行が別々のタイミング（プロセスごとに数十秒〜数分の間隔）で行われるため、その間のavailMem変動（他アプリの挙動等）がコールド/ウォーム間の比較に非対称なノイズとして混入しうる。同一プロセス内でコールド→ウォーム→ウォームと連続実行することで、直近のavailMem変動の影響をコールド/ウォーム双方に均等に及ぼし、両者の差分（ロード時間の寄与）をより正確に切り出せる。各プロセス起動は`-e class`個別起動（`ModelComparisonProbeTest`「プロセス分離の必要性」と同じ制約）。根拠: P7-C8実測（Gemma4-E2Bで`modelLoadMs`=4,620ms）から、5プロセス×(load+generate×3)は数分オーダーに収まる一方、それ以上のプロセス数は実施コストに対し追加の統計的価値が乏しいと判断した。

### 4.2 中央値／分位の採り方

主指標は**中央値（median）**とする。コールドN=5・ウォームN=10程度ではP90/P95等の分位点は統計的意味を持たないため使用せず、代わりに**範囲（min-max）**を併記してばらつきを示す。Phase 9のA54受け入れが単発実測だったのに対し、Phase 9.5はN試行の分布を明示する点が方法論上の違いである。

### 4.3 変動要因の統制

- **availMem**: 各試行直前に`ActivityManager.MemoryInfo`（`ModelComparisonProbeTest.logDeviceMemoryInfo`と同型）を記録する。安全マージン（512MB）を割り込んだ試行は除外し再試行する。
- **充電状態（敵対的レビュー指摘・採用A-1、矛盾是正）**: `BatteryManager`／`ACTION_BATTERY_CHANGED`で充電中か否かを記録し、**全試行を「充電状態（給電中・画面ロック運用）」に統一する**（旧稿「既定: 非充電」は今夜の運用実態〔A54は給電＋Wi-Fi維持済みで計測する〕と矛盾していたため是正した）。今夜のベースラインも将来のF-1〜F-3のA/B再実測も同一条件（充電中）で揃え、充電有無の差自体を測定ノイズに混入させない。
- **温度**: Android標準APIに信頼できるCPU温度取得手段がないため、`LiteRtLmProbeTest.tryDumpsysMeminfo`と同様「取得できれば記録・できなければ欠測明記」のbest-effort方針とする。実務上の代替統制として**試行バッチ間に最低180秒のクールダウン**を置く（充電中は熱がこもりやすいため、旧稿の60秒から延長した。敵対的レビュー指摘・採用A-1）。
- **プロセス状態**: 各バッチ開始前に`adb shell am force-stop`でアプリを終了させ、既存A54受け入れの慣行（kill-all）に倣う。
- **画面点灯・スロットリング回避（夜間計測の運用条件、敵対的レビュー指摘・修正採用A-8）**: 計測バッチ実行中は`adb shell svc power stayon true`（給電中のためDoze不突入と併せてCPUスロットリングを回避する）を設定し、バッチ終了後に`svc power stayon false`へ復帰する。プローブは非UI（Instrumentationテストとして裏で動く）であるため、キーガード解除は不要。

### 4.4 実測値の記録先

一次記録はLogcat（専用TAG、例`P95_PERF_PROBE`）経由で`adb logcat`→`build/agent-logs/phase9.5-<milestone>-logcat.log`（Phase 7/8.5/9の既存慣行）。二次記録として、各マイルストーン完了時に本計画書へ「実機計測結果」節を追記し（Phase 9§14と同型）、中央値・範囲・試行数・変動要因の記録値を表形式で転記してA/B比較を明示する。生ログ自体はリポジトリへコミットしない（`build/`配下は既存慣行どおりgit管理外）。

---

## §5. 変更対象ファイル構成

### 新設
- `app/src/androidTest/java/com/actionstarter/probe/PerformanceBaselineProbeTest.kt`（M）
- `app/src/androidTest/java/com/actionstarter/probe/GpuBackendProbeTest.kt`（PR-1）
- `app/src/androidTest/java/com/actionstarter/probe/FewShotCategoryEchoProbeTest.kt`（PR-2）
- `app/src/main/java/com/actionstarter/ai/prompt/EventCategoryClassifier.kt`（F-1、状態レス純関数）＋対応`Test.kt`

### 変更
- `app/src/androidTest/java/com/actionstarter/probe/ModelComparisonProbeTest.kt`・`LiteRtLmAdapterE2EProbeTest.kt`（前提修正: `modelPathProvider`→現行コンストラクタへ機械的追随）
- `app/src/main/java/com/actionstarter/ai/prompt/PlanPromptBuilder.kt`（`buildFewShot`のカテゴリ条件選択、F-1。**Plan限定**、§3.2敵対的レビューA-3参照）
- `app/src/main/java/com/actionstarter/ai/prompt/RecoveryPromptBuilder.kt`（`estimateMaxNumTokens`相当新設のみ、F-3。**category追加・条件選択は行わない**、§3.2敵対的レビューA-3で削除済み）
- `app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（`warmUp()`新設＝F-2、既存事前ガード列を再利用しadapterのEngine準備を条件付きで呼ぶ。§3.3敵対的レビューA-5。`AiMetrics`へのPSSフィールド追加は§12確認事項4で非追加確定、本ファイルは非変更）
- `app/src/main/java/com/actionstarter/ai/adapter/LiteRtLmLocalLanguageModel.kt`（Engine準備の内部メソッド新設＝F-2〔`LocalAiGateway.warmUp()`からのみ呼ばれる〕、重複統合＝RF-1）
- `app/src/main/java/com/actionstarter/features/eventselection/`配下（ウォームアップのトリガー配線、F-2。トップ画面＝`EventSelection`入場時のみ・都度・in-flight時スキップ、§3.3確定。`LocalAiGateway.warmUp()`のみを参照し`ai/adapter/`は直接参照しない）

### 非変更（明示）
- `recovery/BasicRecoveryEngine.kt`等の決定的計算群・`domain/model/`配下（性能・品質改善はAI経路にのみ閉じる）
- `ai/model/ModelCatalog.kt`・`ModelStorage.kt`（モデルカタログ自体は対象外。PR-1はBackend種別の変更でありカタログとは独立）

---

## §6. 依存関係・技術選定の根拠

新規外部依存なし。PR-1のみlitertlm-android 0.15.0のGPU Backend API公開状況に依存する（§2事前確認で調査、未公開ならNo-Go記録のみで終わる）。**Gemma4-E2Bとの性能比較を本フェーズで繰り返さない理由**: Phase 7 P7-C8実測（`ModelComparisonProbeTest`クラスKDoc「実測済み」）が既にQwen3-0.6B／1.7B／Gemma4-E2Bの3モデル比較を完了しており、A54の自動選択結果はQwen3-0.6Bで確定している（Phase 8.5/9実測）。本フェーズは「選ばれたモデルでの性能・品質を計測駆動で改善する」ことが目的であり、モデル選定自体の再比較はスコープ外（蒸し返し回避）。

---

## §7. テストケースリスト

`T-P95-*`で採番（敵対的レビュー反映により再採番済み）。

### EventCategoryClassifier（F-1、JVM、ja/en両言語・敵対的レビューA-4）
| ID | 分類 | 内容 |
|---|---|---|
| T-P95-1 | 正常 | ja: 医療関連キーワード（例:「検診」「病院」）を含むタイトル → medical判定 |
| T-P95-2 | 正常 | en: 医療関連キーワード（例:"checkup"/"clinic"）を含むタイトル → medical判定 |
| T-P95-3 | 正常 | ja: 出張関連キーワードを含むタイトル → travel判定 |
| T-P95-4 | 正常 | en: 出張関連キーワード（例:"business trip"）を含むタイトル → travel判定 |
| T-P95-5 | エッジ | ja/enともキーワード非一致タイトル → フォールバック値を返しクラッシュしない |
| T-P95-6 | 正常 | 複数カテゴリのキーワードを同時に含む場合の優先順位が決定的（同一入力に同一出力、ja/en両方） |

### few-shotカテゴリ条件選択（F-1、JVM、**Plan限定**）
| ID | 分類 | 内容 |
|---|---|---|
| T-P95-7 | 正常 | 推定カテゴリに一致する模範例のみが返る（不一致の模範例は含まれない） |
| T-P95-8 | エッジ | 不明カテゴリ → 現行の全件混合へ縮退する（0件にならない） |
| T-P95-9 | 正常・回帰 | 既存`PlanPromptBuilderTest`の非カテゴリ依存箇所が無傷 |

### Recovery用maxNumTokensプロファイル（F-3、JVM）
**採番改訂（実装時）**: 当初案のT-P95-10はF-1bが実際にT-P95-1〜12を消費したため衝突する（F-2のT-P95-11〜17と同じ経緯、§3.3該当節参照）。実装時にT-P95-58〜62へ採番し直した。

| ID | 分類 | 内容 |
|---|---|---|
| T-P95-58 | 正常 | Recovery版`estimateMaxNumTokens`が`PlanPromptBuilder.VERIFIED_WORKING_MAX_NUM_TOKENS`下限を下回らない（最小入力） |
| T-P95-59 | 正常 | 同一のshotCount・maxOutputTokenでは、Recovery版の見積りがPlan版を上回らない |
| T-P95-60 | 正常 | maxOutputTokenが大きいほど見積りも大きい（境界、Plan版と同型） |
| T-P95-61 | エッジ | 見積り結果は`PlanPromptBuilder.CONTEXT_LENGTH_CEILING`を超えない（境界、Plan版と同型） |
| T-P95-62 | 正常 | 現実的な3件×60字の出力サイズを前提にした場合、見積りは下限を満たしつつCONTEXT_LENGTH_CEILINGの半分未満という妥当域に収まる |

### `LocalAiGateway.warmUp()`（F-2、JVM、Gateway層ゲーティング・敵対的レビューA-5/A-6/A-7）
**採番改訂（実装時）**: 当初案のT-P95-11〜17はF-1／F-1bが実際にT-P95-1〜12を消費したため衝突する。実装時にT-P95-50〜57へ採番し直した（値・意味とも当初案から不変、in-flightスキップ〔T-P95-55〕を1件追加）。

**採番改訂（F-2b実装時）**: T-P95-54はF-2b（実機検証、§3.3「F-2b」）で`WARM_UP_EXTRA_HEADROOM_BYTES`が通常ガードの`MEMORY_SAFETY_MARGIN_BYTES`と同値（512MB）になったことに伴い、当初の「通常ガードは満たすが強化ガードは満たさない非対称ケース」という前提そのものが境界として存在しなくなったため、「強化ガード自体の境界（1バイト手前）」を精密に固定する内容へ再設計した（ID・分類は維持）。対となる境界（ちょうど→呼ぶ）はT-P95-58〜62（F-3、`RecoveryPromptBuilderTest`）と衝突しないT-P95-63として新規追加した。

| ID | 分類 | 内容 |
|---|---|---|
| T-P95-50 | 異常 | `aiEnabled=false`のとき`warmUp()`はadapterのEngine準備を一切呼ばない |
| T-P95-51 | 異常 | Tier非対応（`TIER_0_UNSUPPORTED`）のとき`warmUp()`は何もしない |
| T-P95-52 | 異常 | ABI非対応のとき`warmUp()`は何もしない |
| T-P95-53 | 異常 | モデル未解決（未導入／破損）のとき`warmUp()`は何もしない |
| T-P95-54 | 異常 | 【F-2b改訂】強化ガード（`defaultProfilePeakRamBytes`＝1.25GiB＋新`WARM_UP_EXTRA_HEADROOM_BYTES`＝512MB＝1.75GiB）の1バイト手前では`warmUp()`は何もしない。実際にF-2実機検証で解決されたQwen3-0.6B相当のfixture（`fakeQwen06bLikeEntry()`）を使用 |
| T-P95-55 | 異常 | 生成in-flight中（`inferenceMutex`取得中）に`warmUp()`を呼んでも、ロック解放を待たず即座にno-opでEngine準備を呼ばない（`tryLock()`による非ブロッキングスキップ） |
| T-P95-56 | 正常 | 全ガード通過時のみadapterのEngine準備が呼ばれる |
| T-P95-57 | 正常・回帰 | ウォームアップ後の`generatePlan`が通常どおり成功し、Engine準備（`warmUpEngine`）を再度呼ばない（Engine再利用は既存`EngineLoadPolicy.requiresEngineReload`／adapter内部の関心事、新規ロック機構を導入しない） |
| T-P95-63 | 正常 | 【F-2b新設、T-P95-54の対】強化ガードちょうど（1.75GiB）では`warmUp()`がEngine準備を呼ぶ（`hasAvailableMemory`は`>=`判定、境界値そのものを含む） |

### 実機プローブ（androidTest、M/PR-1/PR-2）
| ID | 内容 |
|---|---|
| T-P95-18（M） | Plan/Recovery各5プロセス×（コールド1＋ウォーム2）試行のTTFT/decode tok/s/ピークPSS/L5 reject率をベースライン記録する（§4.1ペア計測） |
| T-P95-19（PR-1） | GPUバックエンド利用可否・利用可能な場合の性能値をprobeで記録する（Go/No-Go判定） |
| T-P95-20（PR-2） | L1-b/R1bそれぞれのエコー率をプローブで比較記録する |
| T-P95-21（前提修正） | `ModelComparisonProbeTest`/`LiteRtLmAdapterE2EProbeTest`修正後、`:app:compileDebugAndroidTestKotlin`がBUILD SUCCESSFULになる（現状FAILUREの解消確認） |

### 全体回帰
| ID | 内容 |
|---|---|
| T-P95-22 | `:app:testDebugUnitTest`既存719件Green維持・`:app:lintDebug` error 0維持（各コミット完了時点） |

---

## §8. エラー＆レスキューマップ

| 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|
| カテゴリ推定（F-1） | キーワード一致0件（未知の予定名） | フォールバック値→現行の全件混合few-shotへ縮退 | 品質・TTFTともカテゴリ選択導入前と同等（劣化なし） |
| `LocalAiGateway.warmUp()`のガード列（F-2） | aiEnabled/Tier/ABI/モデル解決/availMemのいずれかで不合格 | `warmUp()`は例外を投げず静かに何もしない（`generatePlan`/`generateRecovery`本体の既存ガードと同一判定を再利用するため新規の失敗系統を持ち込まない） | 通常推論には無影響（ウォームアップが単に発動しないだけ）。AI OFFユーザーは常にno-op（電池保護） |
| Engineウォームアップ（F-2） | ウォームアップ後にavailMemが悪化し別モデルが解決される | 既存`EngineLoadPolicy.requiresEngineReload`が検知し再ロード（誤動作なし、投資のみ無駄） | 稀にウォームアップなしより遅くなる可能性。§9再検討トリガーで監視 |
| Recovery用maxNumTokens縮小（F-3） | 縮小しすぎてFAILED_PRECONDITION再発（ADR-0057と同種の罠） | 既存`VERIFIED_WORKING_MAX_NUM_TOKENS`下限のclampを流用し下回らせない | 実機で発生した場合は縮小を撤回しPlanと同一値へ復帰 |
| GPUバックエンドプローブ（PR-1） | API自体が0.15.0で未公開 | プローブを組めない事実そのものをNo-Go記録として報告する（無理に実装しない） | 影響なし（既定CPUバックエンドを維持） |
| androidTestコンパイル前提修正 | 修正が他のprobeへ波及し新たな不整合を生む | 機械的な引数差し替えのみ行い、ロジック変更はしない | 影響なし |
| N試行中にavailMem変動で無効試行が混入 | 測定バイアス | §4.3の統制どおり安全マージン割れの試行を除外し再試行 | 計測結果の信頼性が保たれる |
| 夜間計測中のADB接続断・実機不応答 | Wi-Fi ADB切断・端末スリープ復帰失敗等 | 無理にリトライし続けず、直前までの進捗（コミット0の成否・M計測の途中結果）を記録して停止する（§10・§11） | 翌朝ユーザーが状態を確認し再開できる。中途半端な変更はコミットしない |

---

## §9. ADR起票方針（ADR-0064想定）

起票直前の再確認（既存慣行）: 本計画書起案時点で`grep -n "^### ADR-" DECISIONS.md | tail -3`を実行し、最新確定ADRは**ADR-0063**（Phase 9）であることを確認済み。したがって本計画の決定は**ADR-0064**（暫定）として、各マイルストーン完了後に同じ手順で再確認のうえ正式起票する（M/F-1/F-2/F-3実装完了時点で1本、PR-1/PR-2の採否確定時点で追加要否を判断）。記録予定の決定（暫定、敵対的レビュー反映後）:
1. カテゴリ推定を`ai/prompt/`配下の状態レス純関数（`EventCategoryClassifier`、ja/en別辞書）として新設し、PII非送信（タイトルのみ・固定辞書・ネットワーク不使用）を維持する。**Plan限定**（Recovery側は模範例2件しかなく効果測定不能なため対象外、敵対的レビューA-3）
2. Engineウォームアップの入口は`LocalAiGateway.warmUp()`とし、既存の事前ガード列（aiEnabled/Tier/ABI/モデル解決/availMem）をすべて経由してからadapterのEngine準備を呼ぶ（features層はGatewayのみ参照する既存層規律を維持、敵対的レビューA-5）。ガードのavailMem閾値は通常推論（+512MB）より厳しい`WARM_UP_EXTRA_HEADROOM_BYTES`＝+1GiBとする（敵対的レビューA-6）。トリガーはトップ画面（`EventSelection`）入場時のみ・都度・in-flight時スキップに確定する（敵対的レビューA-7）
3. `AiMetrics`へのPSSフィールドは追加しない（§12確認事項4で確定。probe専用`PssPeakSampler`で計測を完結させ、Phase 12のAnalytics基盤設計時に本番計測の要否を再検討する）
4. GPUバックエンド・マスク模範例の本採用可否（PR-1/PR-2の結果とユーザー判断をここへ反映、確定後に起票）

**再検討トリガー**: (a) F-2実装後のA/B実測でウォームアップが正味の遅延・電池消費の悪化になっていることが判明した場合、トリガー画面の見直しまたはF-2自体の撤回を検討する。(b) `onTrimMemory`連動でのウォーム済みEngineの能動的アンロード（本フェーズでは非実装、§3.3敵対的レビューA-6）は、F-2実機運用でメモリ圧迫の実害が観測された場合に導入を検討する。

---

## §10. 実機受け入れ手順（A54）

1. **前提修正の確認**: `:app:compileDebugAndroidTestKotlin`がBUILD SUCCESSFULになることを確認する（T-P95-15）。
2. **M**: `PerformanceBaselineProbeTest`をA54実機（充電中・§4.3統制条件）で実行し、Plan/Recovery各々5プロセス×（コールド1＋ウォーム2）のベースラインを記録する（§4方法論に従う）。
3. **F-1〜F-3実装後**: 同一プローブを再実行しA/B比較する（施策ごとに個別実行、混同を避ける）。
4. **PR-1**: GPUバックエンドプローブを実行し結果を記録する（利用不可の場合はその事実を記録して終了）。
5. **PR-2**: L1-b/R1bプローブを実行しエコー率を比較記録する。
6. **RF-1**: リファクタ後のPlan/Recovery生成が既存の実機受け入れシナリオ（Phase 9§10と同一手順）で無傷であることを確認する。
7. 各マイルストーン完了時点でLogcatを`build/agent-logs/`へ保存し、本計画書へ結果節を追記する（§4.4）。

---

## §11. コミット粒度

- **コミット0（前提修正）**: androidTestコンパイル復旧（`modelPathProvider`追随のみ、機械的変更）。
- **コミットM**: `PerformanceBaselineProbeTest`新設＋A54実測＋ベースライン記録。
- **コミットF-1**: `EventCategoryClassifier`（ja/en）＋few-shotカテゴリ条件選択（**Plan限定**）＋A/B再実測。
- **コミットF-2**: Engineウォームアップ＋A/B再実測。
- **コミットF-3**: Recovery用maxNumTokensプロファイル＋再実測。
- **コミットPR-1/PR-2**: 探索プローブ（実装ではなく計測、新設プローブファイルのみ）＋結果報告。
- **コミットRF-1**: `LiteRtLmLocalLanguageModel`重複統合＋実機受け入れ。

理由: Mを独立コミットにすることで「施策実装前の基準点」をgit historyへ固定し、F-1〜F-3各々のA/Bが常に同一の基準と比較できるようにする（Phase 9の3コミット構成が踏襲した「段階的な検証境界」の精神を計測駆動フェーズ向けに適用したもの）。

---

## §12. ユーザー確認事項（Pass 2）— 全件【確定】（委任パターン、2026-08-11敵対的レビュー反映時に確定）

1. **GPUバックエンドが動作した場合、本採用するか**: 【確定→NO_GOにより消滅】プローブ（PR-1）を実機実行した結果、GPUバックエンドはOpenCLライブラリ非公開により実行不能と判明した（**GPU=NO_GO確定**、2026-08-12、§3.5・§14「PR-1実機検証結果」参照）。判断すべき対象（GPU動作時の本採用可否）自体が実機で成立しなかったため、本確認事項は消滅する。本採用は行わない。
2. **ウォームアップのトリガー画面と電池影響の許容度**: 【確定】**トップ画面（`EventSelection`）入場時のみ・都度実行・AI ON時のみ**（§3.3敵対的レビューA-7）。PlanReview画面入場時は除外（同画面は即時自動生成のためウォームアップが無意味）。常時ウォームは採用しない（電池影響を画面遷移ベースの都度実行に限定して抑える）。
3. **L1-b／R1bの採否基準**: 【確定→縮退により消滅】PR-2（L1-b／R1b）はプローブを実施せず縮退（descope）すると裁定した（2026-08-12、§3.6・§14「PR-2裁定」参照。根拠: 全計測でecho実害0件・L1-bのF-1同型品質リスク・R1bが依存するF-1のカテゴリ条件付けが陰性確定済み）。判断対象（プローブ結果）自体が存在しないため、本確認事項の採否基準は消滅する。Phase 12（品質定量化環境整備後）での再評価へ申し送る。
4. **計測のための`AiMetrics`拡張（ピークPSSフィールド追加）の承認可否**: 【確定】**追加しない**。probe専用`PssPeakSampler`（`ModelComparisonProbeTest`実装の転用）で計測要件を満たせるため、本番`AiMetrics`は変更しない。Phase 12のAnalytics基盤設計時に本番計測としての要否を再検討する。

---

## §13. 敵対的レビュー記録（オーケストレーター＋Gemini、2026-08-11）

初稿ドラフトに対する2系統レビューの指摘・採否を全件記録する。

### 採用（計画書修正）

| No | レビュー元 | 指摘要約 | 反映箇所 |
|---|---|---|---|
| A-1 | 両レビュー一致 | §4.3「既定: 非充電」が今夜の実運用（A54は給電＋Wi-Fi維持済み）と矛盾する | §4.3を「全試行を充電状態に統一」へ変更。熱対策としてクールダウンを60秒→180秒へ延長 |
| A-2 | Gemini | コールドN=5とウォームN=5を別々に取ると、プロセス起動タイミングのずれによるavailMem変動ノイズがコールド/ウォーム間で非対称に混入する | §4.1を「プロセス起動5回×各回（コールド1回＋ウォーム2回）」のペア計測へ変更。T-P95-18（旧T-P95-12）の記述を整合させた |
| A-3 | Gemini | Recovery側few-shotはja/en各2件しかなく、カテゴリ4種の条件選択を適用すると常時フォールバック化し効果測定が構造的に不能 | §3.2からRecovery側のcategory追加・条件選択を削除し**Plan限定スコープ**へ変更。§5・§7（T-P95-8相当のRecoveryテスト）からも除去し、Recovery側展開はPR-2の結果を見て将来判断すると申し送った |
| A-4 | 両レビュー一致 | `EventCategoryClassifier`が単一言語辞書のみだと、既存`buildFewShot(locale, ...)`のja/en言語分離規約と整合しない | §3.2へロケール別（ja/en）辞書設計を明記。T-P95-1〜6をja/en両方のケースへ拡充 |
| A-5 | オーケストレーター（CRITICAL） | ウォームアップをadapter層へ直接公開すると、features層がGateway経由の既存事前ガード（aiEnabled/Tier/ABI等）を迂回しうる（AI OFFユーザーでも誤ってEngineが温まるリスク） | §3.3へ`LocalAiGateway.warmUp()`新設を明記。既存事前ガード列をすべて経由してからadapterを呼ぶ設計とし、features層はGatewayのみ参照する既存層規律を維持 |
| A-6 | Gemini | ウォームアップは「使われるかどうか分からない」任意の先行投資であり、通常推論と同じavailMemマージンでは保守性が不足する（LMK誘発リスク） | §3.3へ`WARM_UP_EXTRA_HEADROOM_BYTES`＝+1GiB（通常ガード512MBより厳格）を明記。`onTrimMemory`連動アンロードは本フェーズ非実装とし§9再検討トリガーへ記載 |
| A-7 | オーケストレーター実機検証 | PlanReview画面は入場と同時に生成が自動開始されるため、その手前でのウォームアップは効果を持たない | トリガー候補から「PlanReview画面入場時」を削除。トップ画面（`EventSelection`）入場時のみ・都度・in-flight時スキップに確定（§3.3・§12確認事項2） |
| A-8 | Gemini（修正採用） | 夜間・充電中の計測で、端末がDozeやCPUスロットリングへ入ると計測値が不安定化する | §4.3へ計測バッチ実行中の`svc power stayon true`（給電中のためDoze非突入と併せてスロットリング回避）・バッチ終了後の`false`復帰を明記。プローブは非UIのためキーガード解除は不要と付記 |

**A-6の実機後日談（F-2b、2026-08-12）**: A-6「保守性が不足する」という懸念の**方向は正**だったが、+1GiBという**数値は過剰だった**と実測が判定した（A54実機でウォームアップが常閉の門と化し、§3.3「F-2b」参照）——保守側に振ったマージンが行き過ぎて機能そのものを無効化する具体例として記録する。

### 棄却（理由を記録）

| No | レビュー元 | 指摘要約 | 棄却理由 |
|---|---|---|---|
| R-1 | Gemini | T-P95-10（現T-P95-11〜17相当）はJVMで実行不能ではないか | `DeviceCapability`は`interface`であり、`AiGatewayTestFixtures`等が確立済みのfake注入パターン（JVM単体テストで`hasAvailableMemory`等を差し替え可能）がそのまま使える。前提誤り。既存`LocalAiGatewayTest`が同型の判定をJVMで既に検証している実績とも整合しない指摘だったため不採用 |
| R-2 | Gemini | Engineウォームアップと`engineLifecycleMutex`が奪い合いになり、ウォームアップがかえって本推論を遅延させ本末転倒になるのではないか | `engineLifecycleMutex`は「片方がロード中はもう片方が待つ」構造であり、ウォームアップが先に完了していれば本推論はEngine再利用（ロード待ちなし）で純粋に得をする。ロード完了前に本推論が割り込んだ場合も、待ち時間はウォームアップなしでの自前ロード時間と同程度でしかなく正味の損失はない。ただし「無意味なタイミングでの発火」自体への懸念はトリガー画面の見直し（A-7）で別途対応済みのため、Mutex奪い合いそのものを理由とした追加対策は不要と判断し不採用 |

**Red検収時点の追加訂正（2026-08-12、初稿レビュー後）**: F-4/F-5のRed実装完了後の検収で、F-5が当初`LocalAiGateway`のpost-selectionガードのみを対象としており、実測ログの`detail`文字列から特定される実際の欠陥（`ModelSelectorImpl.select()`自身のavailMemフィルタがロード済み候補を除外する、`unresolvedEntryFallback()`経由）を修正できていないことが判明し、二層構成（`ModelSelector`層＋`LocalAiGateway`層、§3.10）へ差し戻し訂正した。

**Green後A/B実測時点の追加訂正（2026-08-12、F-5b）**: 二層構成Green後の実機A/B実測で、F-4成立を確認する一方F-5が実機で未発動と判明し、`LocalAiGateway`コンストラクタの既定値`modelSelector`が`engineLoadStateSource`を渡さないままだった統合ギャップ（`AppContainer`は明示配線済みのため無関係、既定値経由の呼び出し元のみ影響）を特定し修正した（§3.10「F-5b」）。

---

## §14. ベースライン実測結果（M、2026-08-12実施）

A54実機（充電中・§4.3統制条件）で`PerformanceBaselineProbeTest`を実行し、Plan/Recoveryそれぞれ5プロセス×（コールド1＋ウォーム2）＝計30試行を計測した。`build/agent-logs/phase9.5-baseline-logcat.log`のTRIAL行を独立に集計し、オーケストレーターの暫定集計と照合した（完全一致）。

**計測実施注記**: 実行は前夜のバックグラウンド実行が上限タイムアウトの影響で状態確認が困難になったため、オーケストレーターが直接、5バッチ（Plan）→5バッチ（Recovery）の逐次連鎖方式（1バッチ完了ごとに次バッチを起動、`am instrument`直接呼び出しでGradleの`connectedDebugAndroidTest`が持つ自動アンインストール挙動を回避）で実施した。

### Plan（5コールド・6ウォーム有効、うち4ウォームはOOM_PREVENTEDで無効）

| 指標 | コールド（n=5） | ウォーム（有効n=6） |
|---|---|---|
| modelLoadMs | 中央値1114ms 範囲[1070, 1292] | 0ms（Engine再利用、定義どおり） |
| firstTokenMs（TTFT） | 中央値2394ms 範囲[2323, 3197] | 中央値2146ms 範囲[2113, 2646] |
| tokensPerSecond | 中央値9.96 範囲[8.22, 10.39] | 中央値10.75 範囲[9.17, 11.05] |
| outputTokens | 56（全試行一定） | 56（全試行一定） |

**PSS実測**: 中央値1,100,378,112バイト（≈1.02GiB）範囲[1,064,641,536, 1,100,737,536]（≈[0.99, 1.02]GiB）。native heap実測（`AiMetrics.peakNativeHeapBytes`）は541〜559MB程度でPSSの半分程度にとどまり、既存`ModelComparisonProbeTest`実測（P7-C8）と同様mmap分の乖離が示唆される。

**エコー発動0件**: Plan全11件の成功試行（コールド5＋有効ウォーム6）すべてで`sanityRejectCount=0`・`lastSanityRejectReason=null`。few-shotエコー・品質rejectとも一切発生しなかった。

### Recovery（15/15試行、全件Fallback）

15試行すべてが`AiResult.Fallback(SCHEMA_INVALID)`で、Recoveryの成功試行は**0件**。`sanityRejectCount=1`・`lastSanityRejectReason=MIN_QUALITY`が全15件で一貫して記録されている。

### 重大発見①: Recovery pairing不一致による全滅（§9再検討トリガー発動）

ログの`detail`メッセージとL5メトリクスを突き合わせると、全15試行で同一の複合的な失敗パターンが再現している:
1. **Primary**: `semantic_action`のpairingは正しく2件（`keep_all_steps`／`skip_optional_steps`、probe側が要求した集合と一致）返すが、いずれか一方の`explanation`がContentSanityChecker②で`MIN_QUALITY`によりreject（`sanityRejectCount`が1になるのはこの時点）。
2. **Retry**（`SamplingPolicy.Retry`、topK5/temp0.15でPrimaryより探索的）: `semantic_action`を3件（`keep_all_steps`／`skip_optional_steps`／余分な`skip_optional_and_important_steps`）返し、`RecoverySchemaValidator`のpairing検証（①、expected集合との完全一致）に失敗——`Fallback`が最終的に報告する`detail`はこのRetryの失敗メッセージ。

15試行全件が寸分違わずこのパターンを再現しており（Primary=2件中1件content-reject→Retry=3件でpairing不一致）、単発の偶然ではなく**Qwen3-0.6Bがこの`RecoveryPromptBuilder`のOPTIONSブロックに対して構造的に再現する挙動**であると判断する。

**これは計画書§9が定めた再検討トリガー「pairing不一致（`RecoverySchemaValidator`のAll-or-Nothing全体reject）に起因するFallback率が高いことが判明した場合、Best-Effort部分適用（交差一致）への緩和を検討する」の発動条件そのものである**。Recovery機能はPhase 9完了時点でA54実機受け入れに合格しているが（`docs/plans/phase9-recovery-ai.md`§14）、あの時の実機確認は単発試行であり、本フェーズのN試行計測で初めて「常時Fallback」という構造的な問題が定量的に判明した。F-4（後述§3.9）として優先繰り上げで対応する。

### 重大発見②: Planウォームアップ後の2回目呼び出しでOOM事前ガードが自爆（5バッチ中2バッチで再現）

バッチ1・2（availMem起点がそれぞれ約1.94GiB・2.07GiBと他バッチ〔2.26〜2.34GiB〕より低い）で、ウォーム試行2件がともに`OUT_OF_MEMORY_PREVENTED`（`auto: no candidate fits available memory`）で**wallMs=0〜3ms**の即時Fallbackになった。コールド試行（1回目）は同一プロセス内で正常に成功しているため、**Engine自体は既にロード済みで再ロード不要**にもかかわらず、2回目以降の呼び出しでも§8.6 #7の事前ガードが「これから新規ロードする」前提の閾値（`defaultProfilePeakRamBytes`＋`MEMORY_SAFETY_MARGIN_BYTES`＝1.75GiB）をそのまま再適用している。ロード済みEngineがプロセスのavailMemを実際に消費した状態（PSS実測で約1.02GiB相当）で同じ閾値を再チェックするため、**ロード自体は不要な状況でガートが自滅的に発動する**（設計上の過剰防御）。バッチ3〜5（起点availMemに余裕があったため閾値を割り込まなかった）ではウォーム試行も正常に成功しており、この欠陥はavailMemが閾値ぎりぎりの端末状態でのみ顕在化する。F-5（後述§3.10）として優先繰り上げで対応する。

### 計測条件の記録

- 充電状態: 30試行すべてで`isChargingOrPlugged=true`（`plugged=2`＝USB給電、`status=5`）。§4.3の統制どおり全試行を充電状態に統一できた。
- 方式: 5プロセス×（コールド1＋ウォーム2）のペア計測をPlan・Recoveryそれぞれ独立に実施（§4.1）。
- クールダウン: Plan最終バッチ終了後、Recovery第1バッチ開始前に180秒以上のクールダウンを実施（実測ログのタイムスタンプで確認）。
- **軽度の熱ドリフト（オーケストレーター指摘を独自に再計算・裏付け）**: Recoveryの1バッチあたり平均wallMs（3試行平均）はバッチ1の約27.5秒からバッチ5の約29.7秒へ緩やかに増加した（約8%増）。オーケストレーターが報告した具体値「17.4→19.7秒」は本ログから同一の計算方法では再現できなかった（算出基準の相違の可能性があり、断定を避けるためここでは独自集計値のみを正式記録とする）。傾向自体（バッチが進むほど緩やかに遅くなる）は一致しており、§4.3のクールダウン統制が有効に機能していることの記録として残す。

### A/B再実測（F-4/F-5/F-5b後、2026-08-12実施）

コミット288e9b9（F-4/F-5）・2acd2f2（F-5b）のそれぞれ後にオーケストレーターが実機A/B再測定を実施し、`build/agent-logs/phase9.5-f45-ab-logcat.log`へ記録した。**Recovery完全蘇生を確認**——F-5b適用後バッチでは3/3試行が全てSuccessし、ベースライン（M、上記15/15全滅）から構造的に回復したことを実測で裏付けた。

**推移表**:

| 段階 | 対象コミット | 試行結果 | 備考 |
|---|---|---|---|
| ベースライン（M） | （Phase 9.5 M時点、修正前） | 0/15 Success | 上記「Recovery（15/15試行、全件Fallback）」節 |
| F-4後 | 288e9b9（F-5は当時post-selectionガードのみ、既定値配線ギャップが未修正） | 1/3 Success | 06:24バッチ。cold=Success・warm×2=Fallback |
| F-5b後 | 2acd2f2（既定値`modelSelector`へのengineLoadStateSource自動配線含む） | **3/3 Success** | 06:36バッチ。cold・warm×2すべてSuccess |

**F-5b後バッチ（06:36）の実測値**:

| 試行 | firstTokenMs（TTFT） | tokensPerSecond | wallMs | retried | sanity | selectedModelId |
|---|---|---|---|---|---|---|
| cold | 2407ms | 9.59 | 12185ms | false | clean | qwen |
| warm1 | 2147ms | 10.70 | 9571ms | false | clean | qwen |
| warm2 | 2164ms | 10.42 | 9985ms | false | clean | qwen |

全3試行で`retried=false`（Primary単独でpairing・sanityとも通過、F-4の交差一致緩和がRetryを不要化したことの直接的な裏付け）・sanity clean（②内容sanityのreject 0件）・`selectedModelId=qwen`（auto選択がQwen3-0.6Bへ到達、F-5bの既定値配線が機能したことの裏付け）。

**二重試行の無駄（26〜29s）解消・warm全体約10s**: M時点はPrimary・Retryの2回連続LLM呼び出しが常に発生しており（上記「軽度の熱ドリフト」節の1試行あたり平均wallMs実測27.5〜29.7秒はこの二重試行込みの値）、かつ全試行が最終的にFallbackしていた（無駄な二重試行）。F-5b後はPrimary単独（`retried=false`）で完結し、warm試行のwallMsは9571〜9985ms（約9.6〜10.0秒）——旧来の二重試行込みの27.5〜29.7秒から、単発成功の約10秒へ大幅に短縮された。

**n=3である旨と根拠**: 本A/B再測定はF-5b後バッチ1回（cold1＋warm2＝3試行）のみであり、M（§4.1、5プロセス×3試行＝N=15のペア計測）と比べて意図的に小さいサンプルサイズである。理由は次の2点: (1)`SamplingPolicy.Primary`は`topK=1, temperature=0.0`（+アダプタ側で`topP=1.0, seed=0`固定）という完全決定的サンプリング設定であり（`SamplingPolicy.kt`のKDoc「品質ハーネス§4の表」参照）、同一モデル・同一入力・同一コードパスであれば出力は決定的に再現される。(2)本A/Bで検証している対象はモデル出力の品質・性能特性のばらつき（Mの目的、§4.1参照）ではなく、F-4（pairing検証ロジックの交差一致緩和）・F-5/F-5b（OOM事前ガードの構造的欠陥）という「構造的に常に発生するか、修正により構造的に発生しなくなるか」の二値的なロジック修正であるため、追加試行を重ねて確率的な変動を捉える限界価値は小さいと判断した。M自体のN=15設計は無効化していない（性能特性の分布計測という別目的にはNの大きいMの方法論が引き続き妥当）。

### A/B再実測（F-1、2026-08-12実施、負の結果）

コミット09f4d99（F-1）後にオーケストレーターが実機A/B再測定を実施し、`build/agent-logs/phase9.5-f1-ab-logcat.log`へ記録した。**品質退化を検出**——Plan生成3/3試行が`AiResult.Fallback(SCHEMA_INVALID)`・`TITLE_COPY`（`sanityRejectCount=2`＝Primary・Retry両attemptともreject）でFallbackした。実測エラー文言:「display_text is a verbatim (>=80%) copy of the event title (action_type=prepare_items)」。ベースライン（F-1前の混合2-shot）は同条件でSuccessしていたため、これはF-1配線が直接引き起こした退行である。

**診断**: F-1のカテゴリ絞り込みは、各カテゴリのseedが1件しかない状態では実質的にfew-shotを1-shot化する。パターン多様性の喪失により、Qwen3-0.6Bが「予定の意味を理解した個別具体的な行動を提案する」という一般化ではなく、イベントtitleを字面どおりdisplay_textへ写し取る挙動へ退化した。

**L2ハーネスは正しく機能した（安全側縮退）**: `ContentSanityChecker.check`のtitleコピー検出（[isTitleCopy]）が実際に不正な出力を検出し、Fallbackへ正しく縮退させた——サイレントに不良コンテンツを通過させてはいない。ただし結果としてPlan生成の可用性が3/3失敗という形で犠牲になった。安全機構は設計どおり動作したが、その安全機構が守るべき生成品質自体をF-1配線が悪化させた、という構図である。

**A/Bなしでは検出不能だった品質退行**: F-1コミット（09f4d99）時点で全739件のJVMユニットテスト（T-P95-1〜8含む）はすべてGreenだった。JVMテストはfew-shotのcontains/フィルタリングロジックの正しさ（「正しいカテゴリのseedが選ばれるか」）を検証するものであり、選ばれたseedの組み合わせで実際のLLM（Qwen3-0.6B）がどう生成するか（パターン多様性が失われたときにモデルが退化的な挙動を取るかどうか）はJVMテストの検証範囲外である。この退行は実機A/B測定によって初めて発見された——計測駆動フェーズ（§0「①ベースライン実測→②改善実装→③A/B再実測で効果を数値確認する」）が想定していた「実装のみでは検出できない副作用をA/Bで捕捉する」という設計目的そのものが機能した実例として記録する。

**対応**: F-1b（few-shot例文プールを各カテゴリ2件へ増強し、実質1-shot化を解消する）で対応する（§3.2追記）。それでもTITLE_COPY退行が続く場合はF-1配線のロールバックを次の一手とする（計測駆動の撤退基準）。

### A/B判定（F-1b、2026-08-12実施）→撤退基準発動→F-1c（ロールバック）

コミット12a14d6（F-1b、各カテゴリ2件への増強）後にオーケストレーターが実機A/B判定を実施し、`build/agent-logs/phase9.5-f1b-ab-logcat.log`へ記録した。**3/3 Fallback継続**——`AiResult.Fallback(SCHEMA_INVALID)`、ただし`lastSanityRejectReason`は`TITLE_COPY`から`MIN_QUALITY`（`ContentSanityChecker.containsVerbLikeExpression`、R2(c)、動詞相当表現の不在）へ変化した。**失敗の理由は変わったが、可用性の退行そのものは解消しなかった**。

**判定**: プール増強（1-shot→2-shot相当）は「イベントtitleの丸写し」というF-1の失敗パターンAを直接是正したが、Qwen3-0.6B（0.6B、現行プロンプト予算）はカテゴリ限定した2-shotのプロンプト構成では、失敗パターンAを避けた先で失敗パターンB（動詞相当表現を欠く低品質な出力）に陥った。2回連続で異なる理由による有意な品質退行を実測したことは、計測駆動フェーズが定めた撤退基準（§9・§0「A/B再実測で効果を数値確認する」の裏面——効果がない・悪化する場合は数値で判断し撤退する）を満たす。

**結論（不採用・ロールバック、F-1c）**: F-1のカテゴリ条件選択（`eventTitle`実配線）を不採用としロールバックする。`EventCategoryClassifier`・`PlanPromptBuilder.buildFewShot`のカテゴリ絞り込みロジック・増強済みseedプール（各カテゴリ2件、計16 seed）・関連テスト（T-P95-1〜12）はコードから削除せず、ドーマント基盤（Phase 12実験材料）としてKDocに明記のうえ残置する（§3.2「結論」参照）。ロールバック後の全JVMテストGreen・lint 0を確認済み（下記コミットログ参照）。

### F-3裁定（計測以前に構造が答えを出したケース）

F-3（Recovery用maxNumTokensプロファイル縮小）は、実機A/B実測を実施する前に、`LiteRtLmLocalLanguageModel`のEngineがシングルトン（R-7）であるという構造分析だけで「本番配線は不成立」という結論に到達した——F-1／F-4／F-5のように実測して初めて欠陥が判明したケースとは対照的に、**計測サイクルを1回も回さずに構造そのものが答えを出した**（詳細は§3.4「F-3裁定」参照）。見積り関数自体はドーマント設計文書として残す。

### F-2b実機再検証（発動確認、2026-08-12実施）

オーケストレーターがコミットf08859a（F-2b）適用後のA54実機でトップ画面（`EventSelection`）滞在を再計測した。修正前（旧`WARM_UP_EXTRA_HEADROOM_BYTES`＝+1GiB）はPSS 108,062KBのまま20秒経過後もモデル非常駐（§3.3「F-2b」記載の不発）だったのに対し、修正後（新+512MB）はPSSが**526,475KB**まで増加し、ウォームアップが発動しエンジンが常駐したことを実機で確認した。起動時点のavailMemは2,400,692kB≈2.29GiBで、新強化ガード要求1.75GiB（Qwen3-0.6Bの`defaultProfilePeakRamBytes`1.25GiB＋新`WARM_UP_EXTRA_HEADROOM_BYTES`512MB）を上回っており、単体テスト（T-P95-54・T-P95-63）が固定した境界の実機側での成立を裏付けた。F-2bはこれをもって実機検証も完了する。

### PR-1実機検証結果: GPU=NO_GO確定（OpenCLライブラリ非公開、2026-08-12実施）

オーケストレーターが`GpuBackendProbeTest`をA54実機で実行し、`build/agent-logs/phase9.5-gpu-probe-logcat.log`（14行）へ記録した。

**CPU側（完走）**: `modelLoadMs=1092`・`firstTokenS=2.6700296774999996`（TTFT約2.67秒）・`decodeTokPerS=9.706930443053377`（約9.71tok/s）・`peakTotalPssKb=1039485`・`schemaValid=true`。

**GPU側（Engine初期化は成功、推論で失敗）**: `modelLoadMs=6286`・`afterInitTotalPssKb=1459784`まではCPU側と同様に完走したが、続く`sendMessage`（初回推論呼び出し）で`LiteRtLmJniException: Failed to call nativeSendMessage: UNKNOWN: UNKNOWN: Can not find OpenCL library on this device`が発生し失敗した。プローブのGPU_RESULT判定行は`GPU_RESULT=NO_GO reason=INIT_OR_INFERENCE_FAILED`。

**判定**: A54（Samsung Galaxy A54、SoC SCG21・One UI）はハードウェアとしてMali-G68 GPUを搭載しているが、OSがOpenCLライブラリをアプリ（NDK経由）へ公開していないため、LiteRT-LMのGPUバックエンド（内部でOpenCLへ委譲するdelegate実装と見られる）は実行不能——**GPU=NO_GO確定**。§3.5「本採用（production defaultsをGPUへ切替）は結果を踏まえたユーザー判断」（§12確認事項1）は、判断すべき対象自体が実機で成立しなかったため**NO_GOにより消滅**する。

### PR-2裁定: マスク模範例・カテゴリ条件付きecho検出は縮退（2026-08-12）

L1-b（few-shotのuserTurnを`[EVENT_TITLE]`等へマスクする設計）・R1b（`ContentSanityChecker`へのカテゴリ条件付きecho検出追加）について、プローブを実施せず縮退（descope）する判断をオーケストレーターが下した。根拠は3点:

1. **L2防御が実務上十分**: 本フェーズを通じた全計測（M・A/B各回・GPU probe含む）でecho発生は0件——既存のR1a（模範例タイトルのecho検出、常時reject）で実害が観測されていない。
2. **L1-b自体がF-1と同型のリスクを負う**: few-shotの模範例構成を変更する（`userTurn`をマスクへ差し替える）ことは、F-1がカテゴリ条件選択で実証した「模範例構成の変更がQwen3-0.6Bの0.6B・現行プロンプト予算下での品質を崩しうる」というリスクをそのまま継承する。エコー実害が0件という前提①のもとでは、この構造的リスクに見合う便益が確認できていない。
3. **R1bがF-1のカテゴリ条件付けに依存する**: R1bの「カテゴリ一致判定」はF-1が確立するはずだったカテゴリ推定基盤の応用だが、F-1自体がA/B実測2回で陰性・ロールバック確定済み（§3.2「F-1c」）——依存するレバー自体が計測駆動フェーズで否定されている。

**結論**: PR-2プローブは実施せず、Phase 12（品質定量化環境の整備後）での再評価へ申し送る。§3.6「本フェーズの成果物はプローブ結果と採否提案」は不成立となり、§12確認事項3「L1-b／R1bの採否基準（エコー率がベースライン比50%以下、かつ目視品質劣化なしの場合に採用を提案）」は**判断対象（プローブ結果）自体が存在しないため縮退により消滅**する。

---
