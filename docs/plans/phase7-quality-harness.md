# Action Starter Android ― Phase 7 品質ハーネス設計書：小型量子化LLM（Qwen3-0.6B INT4）から最大品質を引き出す周辺設計

> 位置づけ: `docs/plans/phase7-local-llm-foundation.md`（G1通過・P7-C1完了）の**上に載る補助設計**。基盤計画のF86（adapter）・F93（PlanPromptBuilder）・F95（SchemaValidator）・F96（LocalAiGateway）に「品質を最大化するための追加契約」を与える。**本書は設計文書のみ**（本番コード・テストは書かない）。既存 `phase7-local-llm-foundation.md`／`src/test`／`build.gradle.kts` には触れず、`ai/` scaffold（P7-C1新設12ファイル）へ与える追加契約を「推奨」として提示し、編集中のP7-C2/C3と衝突させない。

---

## §0. 結論ファースト

採用モデル `Qwen3-0.6B` INT4（日本語 MIFEvalJa **0.425**・decode フラッグシップ実測 9〜13 tok/s＝Galaxy Aではさらに遅い）は**二重に非力**である。したがって品質はモデルでなく**周辺設計で下限と期待値を最大化**する。本書の核は5点:

1. **タスク最小化＋Semantic Contextualization**: LLMの唯一の付加価値は「予定の意味を理解した、固定テンプレートでは生成不可能な個別具体的な行動」（例: 結婚式→「ご祝儀を準備する」／歯医者→「歯を磨いておく」／出張→「切符を確認する」）を`display_text`として生成することに絞る（**Semantic Contextualization**。§2）。`event_type`／`action_type`／step種別は固定enumへの**分類**にとどめ、単なる言い換えタスクとしては扱わない。**数値（所要分・時刻）・優先度・skippable・時刻演算はKotlin（Basic）が決定的に担い、LLMに生成させない**（§13/§15/§34）。これによりハルシネーション面と出力トークン数を同時に削りつつ、AIの付加価値をBasicの汎用固定文言との差別化点に集中させる。
2. **プロンプト**: `ConversationConfig.systemInstruction`（役割＋ハードルール）＋`initialMessages`（**実行時localeに基づく動的単一言語few-shot**。locale=jaなら日本語例のみ・enなら英語例のみで、ja/enを常時混在させない）＋当該予定の最小データ。few-shot草案は§3に記載。
3. **サンプリング**: `SamplerConfig(topK=1, topP=1.0, temperature=0.0, seed=固定)`＝**実質greedy**で決定性最大（構造化タスクに最適・テスト再現性も確保）。**一次確認済**（後述）。
4. **出力矯正**: `ResponseFormat.json(schema)`＝LLGuidanceのJSON Schema制約で `enum`／`minLength`／`maxLength`／`minItems`／`maxItems`／`minimum`／`maximum`／`additionalProperties` を**生成段階で強制**（一次確認済）。**唯一 `uniqueItems`（重複step）だけはLLGuidance非対応**のため第2層Kotlin検証が必須。
5. **3段検証＋再試行1回＋Basic固定文言フォールバック**: ①形式（スキーマ/JSON）②内容sanity（長さ・禁止語・**捏造検出**・緩和済みコピー検出・空/意味不明・locale整合）③失敗時**再試行1回（会話履歴を持たない新規single-turnセッションで微小摂動＋静的制約を追加）**→なお失敗で**Basicの決定的文言へ落ちる**（品質下限をBasicが保証）。

**本書が基盤計画に対して修正を要求する唯一のCRITICAL**: 基盤計画 **S-2 の retry 定義「同一プロンプト・temperature=0.0・seed固定での1回再生成」は論理的に無効**である。1回目がgreedy（決定的）で失敗したなら、同一決定的条件の再生成は**同一出力を再現して必ず同じ失敗になる**。ただし0.6B/INT4は**温度を大きく上げると出力が崩壊しやすく、マルチターンでの自己修正能力もほぼ皆無**（Gemini G1 CRITICAL #1）であるため、retryは大きな摂動やマルチターン自己修正ではなく、**微小摂動（`topK=5, temperature=0.1〜0.2`程度）＋会話履歴を持たない新規single-turnセッション＋静的制約の追加**で条件を変える。§4/§6で是正する。

**承認状態**: 本書はGemini G1（`gemini-3.5-flash`固定・本書独自のレビュー。基盤計画のG1 CRITICAL #1〜#5とは別件）CRITICAL 5件（§15）とFable 5裁定UQ-1〜UQ-5（§13）をすべて反映済み（2026-08-10）。**G1通過**。VQ-1〜VQ-3（§14）は未確認のまま残り、P7-C8実機プローブで確定する。

---

## §1. LiteRT-LM 0.15.0 Kotlin API 一次確認結果（可否を明示）

**確認方法**: (A) Context7 MCP `/google-ai-edge/litert-lm`、(B) `raw.githubusercontent.com/google-ai-edge/LiteRT-LM/**v0.15.0**/kotlin/java/com/google/ai/edge/litertlm/Config.kt` を直接WebFetch（**採用バージョンのタグを直接確認**）、(C) `guidance-ai/llguidance` の `docs/json_schema.md`、(D) 基盤計画§14.1 P7-C0の実機/エミュレータ実測（domain-implementer）。推測でAPIを断定していない。

| 項目 | 可否 | 確認結果（一次） | 出典 |
|---|---|---|---|
| **サンプリング temperature/topP/topK/seed** | **確認済（0.15.0）** | `data class SamplerConfig(val topK: Int, val topP: Double, val temperature: Double, val seed: Int = 0)`。制約 `require(topK>0)` / `topP∈[0,1]` / `temperature>=0`。`ConversationConfig.samplerConfig: SamplerConfig? = null` で受け渡す | (B) v0.15.0 `Config.kt` |
| **サンプラ種別の選択（GREEDY/TOP_K/TOP_P）** | **不可（Kotlinから選べない）** | JNIが「SamplerConfig設定時は必ず `TOP_P` 型へ切替える」と実装。GREEDY enumへは到達不能 → **greedyは `topK=1` で近似**する | (A) `jni/litertlm.cc` |
| **出力長 maxNumTokens / maxOutputToken** | **確認済（0.15.0）** | `EngineConfig.maxNumTokens`（コンテキスト長・ピークRAM主因）＋`ConversationConfig.maxOutputToken: Int? = null`＋`sendMessage(maxOutputToken=)`。P7-C0で128/256/`maxOutputToken=100`使用実績 | (B)(D) |
| **few-shot: system prompt** | **確認済（0.15.0）** | `ConversationConfig.systemInstruction: Contents? = null`（`Contents.of("...")`） | (B)(A) |
| **few-shot: マルチターン履歴** | **確認済（0.15.0）** | `ConversationConfig.initialMessages: List<Message> = listOf()`。`Message.user("...")` / `Message.model("...")` で user↔model の例示ペアを事前投入できる | (A) `getting_started.md`／(B) |
| **KVキャッシュ/プレフィル preface再利用** | **部分的に確認済** | `ConversationConfig.prefillPrefaceOnInit: Boolean = false`＝**preface（system+initialMessages）を会話生成時にprefillできる**。会話内の追加ターンは差分のみ再計算（`conversation.cc` の diff方式）。`Session.runPrefill(...)` も存在 | (A) `Config.kt`/`conversation.cc`/`Session.kt` |
| **preface KVを複数リクエスト間で使い回す（履歴を溜めずに）** | **未確認（VQ-1）** | 会話のclone/branch/preface-resetに相当する公開APIを本調査では発見できず。**独立した各 `generatePlan` でprefaceを再prefillする前提**が安全側（速度影響は§7） | 未発見＝未確認 |
| **`ResponseFormat.json()` のスキーマ表現力** | **確認済（LLGuidance）** | バックエンドはLLGuidance（`kJsonSchema`）。**enforce対象**: `enum`/`const`/`type`/`properties`(順序固定)/`required`/`additionalProperties`/`minLength`/`maxLength`/`pattern`(lookaround除く)/`minItems`/`maxItems`/`minimum`/`maximum`。**非対応**: `uniqueItems`（重複禁止は効かない） | (C) `llguidance/docs/json_schema.md` |
| thinking無効化 | **確認済（0.15.0）** | `ThinkingConfig(enableThinking: Boolean = true, thinkingTokenBudget: Int = -1)`。`ConversationConfig.thinkingConfig = ThinkingConfig(enableThinking = false)` のAPI設定のみで `<think>` 混入なし（P7-C0で2回とも実証） | (B)(D) |
| `enableResponseFormat` と `samplerConfig` の共存 | **確認済（0.15.0）** | v0.15.0タグの `ConversationConfig` は12フィールドを持ち、`enableResponseFormat: Boolean = false` と `samplerConfig` が**同一data classに共存**（Context7 mainの抜粋は前者を省略していたが、タグ実体で共存を確認） | (B) |
| Backend.CPU スレッド数 | **確認済（0.15.0）** | `Backend.CPU(threadCount: Int? = null, numOfThreads: Int?=@Deprecated)`。P7-C0で `threadCount=4` 実行実績 | (B)(D) |

**重要な留保**: 上表の「enforce対象」はllguidance **本体（main）** のJSON Schema対応であり、**LiteRT-LM 0.15.0 が同梱するネイティブllguidanceのバージョンが古い可能性**は排除できない。P7-C0の実測は `enum`／文字数／数値範囲を **steps=1固定** で満たすことを確認済みだが、**`minItems`/`maxItems`（配列長）は steps=1のため未実測**（VQ-2）。したがって**「decodeで矯正されるはず」に依存せず、第2層Kotlin検証で全制約を独立再検証する**原則（基盤計画§8.4）は本書でも堅持する。

---

## §2. タスク最小化 ― LLMに渡すもの/渡さないもの（§13/§15/§34/§10）

**設計原則（Semantic Contextualization）**: LLMの唯一の付加価値は「予定の意味を理解した、固定テンプレートでは生成できない個別具体的な行動」を`display_text`として生成することであり、単なる種別の言い換えではない（例: 結婚式→「ご祝儀を準備する」／歯医者→「歯を磨いておく」／出張→「切符を確認する」。Basic版の汎用固定文言との差別化点はここにある）。`event_type`／`action_type`は固定enumへの分類にとどめる。決定的に計算できるもの（数値・時刻・優先度・skippable）はすべてKotlin（Basic）が持つ。

| 項目 | 担当 | 根拠 |
|---|---|---|
| `event_type`（予定分類の推定） | **LLM** | §14「eventType推定」 |
| `action_type`（step種別の英語ID・enum） | **LLM** | §14「Action generation」・§21 |
| `display_text`（各stepの短い行動文・ja/en。**Semantic Contextualization**＝予定固有の文脈化された行動） | **LLM** | §14「natural explanation」・§21・§0/§2冒頭 |
| step の並び順・step種別（transition/preparation/departure/travel） | **LLM（種別）／Kotlin（travel/departureの生成有無）** | §14／§13。travel有無は `travelEstimate` の有無でKotlinが決める（`BasicPlanningEngine` L60-61） |
| **所要分・時刻・StartOfTransition・ETA・出発時刻** | **Kotlin専任（LLM禁止）** | §13「数値計算は必ず通常コード」・§15「時刻演算・到着時刻演算」 |
| **priority・skippable** | **Kotlin専任（推奨）** | §33整合。0.6Bの判断より決定的ルールが安全 |

**プロンプトに入れるデータ（最小・PII最小化＝§10 Local-first）**:
- 入れる: イベントtitle（**上限切り詰め**）、`event_category`（あれば）、locale、要求するstep種別の集合。
- **入れない**: カレンダー本文（notes）・住所文字列・参加者・座標・電話/URL。titleは区切りトークンで囲みデータ部として隔離（§13#15・T-PRM-6）。

**基盤計画のPlanJsonSchemaとの関係（衝突回避）**: 基盤計画§8.4のスキーマは `estimated_minutes(1..120)`・`priority`・`skippable` を**LLM出力として**持つ（S-4）。本書は品質観点から**それらをLLMに生成させない「最小スキーマ」を推奨**する（§5、**Fable 5 UQ-1で採用・§13**）。LLMから引き剥がすのは数値・判断（`estimated_minutes`/`priority`/`skippable`）のみであり、`display_text`はKotlinには生成できない**「予定固有の文脈化された行動」**（Semantic Contextualization。§0・本節冒頭）を担う——これがLLMをタスクに残す唯一の理由である。**P7-C2/C3がスキーマを確定中のため本書はこれを強制しない**。仮にP7-C2が `estimated_minutes`/`priority`/`skippable` をスキーマに残す場合は、**LLM値を「参考提案」に降格し、Kotlinが決定的値で上書きする（時刻演算には一切使わない・§15）**運用を必須とする（S-4のClamp `1..120` は上書き前の防御として維持）。どちらでも「数値の最終権限はKotlin」という不変条件は崩さない。

---

## §3. プロンプト設計（system / few-shot / data）＋few-shot草案

**3部構成**（`ConversationConfig` へマップ）:

- **systemInstruction**（英語・簡潔。英語systemは小型モデルでも指示追従が安定）:
  > You convert a calendar event into short preparation action steps.
  > Output ONLY a JSON object that matches the given schema — no prose, no markdown, no `<think>`.
  > Rules: (1) `action_type` is a fixed English ID from the allowed set. (2) `display_text` is a SHORT imperative phrase in **{LOCALE_LANGUAGE}** (Japanese when locale=ja, English when locale=en), max 60 chars. (3) NEVER output clock times, dates, minutes, numbers, addresses, personal names, or any detail not present in the event — those are computed elsewhere. (4) Do NOT copy the event title verbatim into `display_text`. (5) If unsure, produce a generic, safe action rather than inventing specifics.

  → (1)は§21、(2)は§21のdisplay_text分離、(3)は§13/§15＋§34捏造禁止、(4)はコピー抑止、(5)はハルシネーション抑止。すべて後段のsanity検証（§6②）と対で効かせる。

- **initialMessages（`buildFewShot(locale, shotCount)`＝実行時localeに基づく動的単一言語few-shot。§10 `PlanPromptBuilder`）**: few-shotは**ja例とen例の常時混在をやめ、`locale`が`ja`なら日本語例のみ・`en`なら英語例のみを投入する**（Gemini G1 CRITICAL #2）。狙いは2点: ①**言語汚染の防止**（他言語の例が文脈にあることで出力言語を取り違えるリスクを構造的に排除）②**prefillトークン削減による速度改善**（不要な言語の例を送らない分prefaceが短くなり、§7/§9のTTFT対策と直結）。

  **locale=ja の例（既定2-shot）**:

  例1（ja・business_meeting）
  - `Message.user`: `[EVENT] title="打ち合わせ" category=business_meeting locale=ja → produce steps`
  - `Message.model`:
    ```json
    {"event_type":"business_meeting","steps":[
      {"action_type":"finish_current_task","display_text":"今の作業を切り上げる","type":"transition"},
      {"action_type":"prepare_documents","display_text":"資料を準備する","type":"preparation"},
      {"action_type":"leave","display_text":"出発する","type":"departure"}]}
    ```
  例2（ja・social／結婚式。**Semantic Contextualizationの模範**＝固定テンプレートでは出せない予定固有の行動）
  - `Message.user`: `[EVENT] title="結婚式" category=social locale=ja → produce steps`
  - `Message.model`:
    ```json
    {"event_type":"social","steps":[
      {"action_type":"finish_current_task","display_text":"今の作業を切り上げる","type":"transition"},
      {"action_type":"prepare_gift","display_text":"ご祝儀を準備する","type":"preparation"},
      {"action_type":"leave","display_text":"出発する","type":"departure"}]}
    ```

  **locale=en の例（既定2-shot）**:

  例1（en・medical）
  - `Message.user`: `[EVENT] title="Checkup" category=medical locale=en → produce steps`
  - `Message.model`:
    ```json
    {"event_type":"medical","steps":[
      {"action_type":"finish_current_task","display_text":"Wrap up what you're doing","type":"transition"},
      {"action_type":"get_ready","display_text":"Get changed and ready","type":"preparation"},
      {"action_type":"leave","display_text":"Head out","type":"departure"}]}
    ```
  例2（en・travel／business trip。Semantic Contextualizationの模範）
  - `Message.user`: `[EVENT] title="Business trip" category=travel locale=en → produce steps`
  - `Message.model`:
    ```json
    {"event_type":"travel","steps":[
      {"action_type":"finish_current_task","display_text":"Wrap up what you're doing","type":"transition"},
      {"action_type":"check_ticket","display_text":"Check your ticket","type":"preparation"},
      {"action_type":"leave","display_text":"Head out","type":"departure"}]}
    ```

  **例は意図的に汎用的な状況設定**（固有名詞・数値・時刻ゼロ）とし、2例目は「予定の意味を理解した個別具体的な行動」（ご祝儀／切符確認）を示すことで**Semantic Contextualizationの模範**として機能させる（常識的な文脈推論であって捏造ではないことの線引きも兼ねる）。few-shotの**件数は既定2-shotだが、`shotCount`により1-shot/0-shotへ可変**（§7・§9・反映4）。0-shotの場合はfew-shot例を一切送らず、LLGuidanceのスキーマ強制（§1/§5）のみに頼る。`prefillPrefaceOnInit=true` でこのpreface（system+few-shot）を会話生成時にprefillする。

- **user data message（当該予定・毎回変わる部分）**:
  `[EVENT] title="<切り詰め済みtitle>" category=<event_category|unknown> locale=<ja|en> → produce steps`

**プロンプトに絶対時刻の「計算」を要求する文言を入れない**（§15・T-PRM-7）。要求するのは行動文と種別のみ。

---

## §4. サンプリング設計（推奨値・根拠）

| 局面 | 推奨 `SamplerConfig` | 根拠 |
|---|---|---|
| **1回目（既定）** | `SamplerConfig(topK=1, topP=1.0, temperature=0.0, seed=0)` | JNIが `TOP_P` 型を強制するため、`topK=1` で候補を最尤1トークンに絞る＝**実質greedy**。構造化・低創造性タスクは決定性が品質に直結し、テストも再現可能。`temperature=0.0` は `>=0` で許容 |
| **2回目（再試行・§6③）** | `SamplerConfig(topK=5, topP=0.95, temperature=0.15, seed≠1回目)` ＋ 会話履歴を持たない新規single-turnセッション ＋ 静的制約の追加 | **1回目が決定的だったため、条件を変えないと同一失敗を再現する**（S-2の是正）。ただし**大きな摂動やマルチターン自己修正はしない**（Gemini G1 CRITICAL #1・下記） |

- **なぜ「微小」摂動にとどめるか**: 0.6B/INT4のような超小型量子化モデルは温度を上げるほど出力が崩壊しやすい（非文法的トークン列・スキーマ逸脱の増加）。「別の妥当な出力」より「壊れた出力」が出る確率の方が高いため、**大きく温度・topKを上げる摂動は採らず**、`topK=5, temperature=0.1〜0.2`程度に抑え、「1回目と違う経路を1つだけ試す」ことだけを狙う。
- **なぜマルチターン自己修正を採用しないか**: 0.6B/INT4は、文脈中に残る「自分が出した失敗出力」を参照して修正する自己修正能力がほぼ皆無であることが小型量子化モデルの一般的傾向として知られる。加えて、失敗した1回目の出力を会話履歴に残したまま追加ターンで再生成させると、その失敗トークン列自体が悪い模範として働くリスクもある。したがって2回目は**会話履歴（1回目の失敗出力を含む）を持たない新規single-turnセッション**を生成し直し（system＋few-shotのpreface（§3）は同一のものを再セット）、data messageに「より簡潔に出力せよ」等の**固定・静的**な制約文を追加して再投入する（§6）。「Previous output was rejected」のように1回目の失敗を参照させる動的な是正指示は使わない。
- **`samplerConfig=null`（モデル既定）にしない**: Qwen3の既定温度は0.6前後で構造化タスクに不利。**明示指定して固定**する。
- **`topK=0`（純greedy指定）は不可**（`require(topK>0)`）。greedyは `topK=1` で表現する。
- **GPU経路はサンプラ種別を無視してtop-k+top-pを常時併用**するが、Phase 7はCPU固定（基盤§2.2）のため影響なし。

---

## §5. 出力矯正 ― `ResponseFormat.json()` の最小スキーマ（推奨制約）

**推奨「最小スキーマ」**（LLMの仕事を§2どおり「**分類**（event_type/action_type/type）」と「**Semantic Contextualization**（`display_text`＝予定固有の文脈化された行動生成。§0/§2）」の2つだけに最小化した理想形。数値・優先度・skippableはKotlinへ完全に譲渡し、LLMの出力面積を「AIにしか出せない付加価値」に絞る。P7-C2確定を待つため**推奨として提示**）:

```text
event_type   : string, enum [business_meeting, medical, social, travel, other]   （§21確定語彙はP7-C3で§正仕様確認）
steps        : array, minItems 1, maxItems 8
  action_type : string, enum [<英語IDの確定集合>]           （§21・P7-C3で確定）
  display_text: string, minLength 1, maxLength 60
  type        : string, enum [transition, preparation, departure, travel]
additionalProperties: false（全階層）
```

- **LLGuidanceがenforceする**（§1確認済）: 上記の `enum`／`minItems`/`maxItems`／`minLength`/`maxLength`／`additionalProperties:false`。**形式崩れ・enum外・長さ超過・件数超過は生成段階で発生しない**のが原則。
- **LLGuidanceがenforceしない**: `uniqueItems`。**同一 `action_type` の重複step（T-SCH-21）は第2層Kotlin検証で弾く**。
- **数値（`estimated_minutes`）をスキーマから外す**のが最小案。**P7-C2が残す場合**は `minimum:1, maximum:120`（LLGuidance enforce可）＋Kotlin上書き（§2）で二重防御。
- **絶対時刻・ETA・座標に相当するプロパティは持たせない**（§15・T-RF-4）。
- **矯正の強さの限界**: 上記enforceは「llguidance本体」基準。0.15.0同梱版の配列長enforceは**未実測（VQ-2）**。ゆえに**第2層検証は全制約を独立に再チェック**（基盤§8.4を堅持）。

---

## §6. 3段検証＋再試行1回＋Basicフォールバック（観測可能な判定基準）

```text
generatePlan(context)
  └ 1回目生成（§4推奨サンプリング＋§5スキーマ＋thinking無効＋maxOutputToken）
      ├ ① 形式検証（SchemaValidator, §8.4第2層）
      │     JSON構文 / enum / 件数(1..8) / 長さ(1..60) / additionalProperties / action_type等のenum→Domain変換
      ├ ② 内容sanity検証（新設 ContentSanityChecker）
      │     - display_text 長さ ≤ 60（形式の再確認）かつ ≥ 2（空/1字の意味不明を弾く）
      │     - 禁止語/プレースホルダ: "TODO","example","lorem","???","<think", "action_type", JSONメタ語 等
      │     - 【捏造検出・§34】display_text に「数字・時刻(:や時/分)・日付・URL・@・住所らしい語」を含んだら不合格
      │       （数値/時刻はKotlin専任のはず＝§13/§15。LLMのdisplay_textに現れること自体が逸脱）
      │     - 【コピー検出・緩和版・反映3】display_textがtitleの逐語コピーの場合のみ不合格。過検出防止のため
      │       (a) titleが6文字未満なら本検査は適用しない (b) 正規化後の完全一致、またはtitleがdisplay_text
      │       の80%以上を占める場合にのみ不合格（「テニスの準備をする」等の自然な言い換え・包含は合格させる）
      │     - 【locale整合】locale=ja は日本語書記素を含む / locale=en はほぼLatin
      │     - 【重複】同一 action_type のstepが複数（uniqueItems相当・§5でllguidance非enforce）
      ├ ①②通過 → AiResult.Success
      └ ①または②失敗 → ③ 再試行1回
            │ 【Gemini G1 CRITICAL #1・反映1】会話履歴（1回目の失敗出力を含む）は破棄し、**新規
            │ single-turnセッション**を生成し直す（system＋few-shotのpreface＝§3は再セットするが、
            │ 1回目のuser/model往復はこの新セッションに引き継がない＝マルチターン自己修正はしない）。
            │ サンプリングは§4の2回目値（topK=5, temperature=0.15, seed≠1回目）＝**微小摂動のみ**。
            │ data messageに「より簡潔に出力せよ」等の**固定・静的**な制約文を追加する（失敗理由ごとに
            │ 動的生成しない）
            ├ 再度 ①② を実施
            ├ 通過 → Success（metrics.retried=true）
            └ なお失敗 → AiResult.Fallback(SCHEMA_INVALID)         ← 呼び出し側は Basic固定文言を採用
```

- **retryは合計2生成まで**（3回目を呼ばない・T-GW-8整合）。
- **静的制約の追加（2回目のみ・反映1）**: data message末尾に**固定文**を1行追記する（例: `Be extra concise. Do not include any time, date, number, address, or duplicate action_type. Output valid JSON only, following the schema exactly.`）。**「Previous output was rejected」のように1回目の失敗を参照させる動的な是正指示は使わない**——0.6B/INT4はマルチターンで自らの失敗を振り返って修正する自己修正能力がほぼ皆無であり（Gemini G1 CRITICAL #1・§4）、失敗を参照させても効果が薄いばかりか、失敗トークン列が会話に残ること自体が悪い模範として作用しかねない。2回目は**会話履歴を持たない新規single-turnセッション**（system＋few-shotのprefaceは同一のものを再セット）にこの固定文を足すだけにとどめる。**指示部/データ部の構造分離は維持**（§13#15）。
- **Basicフォールバックが品質下限**: `SCHEMA_INVALID`（および基盤§8.6の他Fallback全系統）で、UIは**最初から出ているBasicの決定的文言（`BasicPlanningEngine`＋`semanticId`→`StepTitle`）を採用**する。AIは差し替えに失敗しただけで、予定成立支援は途切れない（§19・§8.7）。
- **②はKotlin決定的処理のみ**（LLM-judgeを使わない）。理由は§7（自己整合や二重推論はGalaxy Aの速度で非現実的）。すべて観測可能・PII非出力・端末内完結。

**②の位置づけ（衝突回避）**: `SchemaValidator`（P7-C2で契約確定中）は**形式①に専念**させ、内容②は**別コンポーネント `ContentSanityChecker`（新設・推奨）**に分離する。`SchemaValidator` の戻り値型未解決論点（P7-C1申し送り3）に手を入れず、②は `SchemaValidationResult.Valid(AIPlanResponse)` を入力に取る後段として足せる。

---

## §7. 速度×品質トレードオフの現実解（Galaxy A decode 数tok/s前提）

| 手法 | 採否 | 根拠 |
|---|---|---|
| **self-consistency（複数サンプリング多数決）** | **不採用** | 数tok/sで数百トークン×N回は数分。§8.7の非同期UXでも許容外。品質向上より速度崩壊の害が大きい |
| **再試行** | **最大1回**（§20・§6） | §20固定。2回目は**新規single-turnセッション＋微小摂動**（温度を0.1〜0.2程度に**わずかに**上げるのみ）で「別の目」を引く（§4・反映1） |
| **maxOutputToken** | **行動文に必要な最小**（steps最大8×display_text≤60字＋JSON骨格 ≒ 実運用は steps 5前後）｜ | §8.4トークン予算。decode律速を直撃 |
| **thinking** | **無効固定**（`enableThinking=false`） | `<think>` 数百トークン浪費を回避（§13#23・P7-C0実証） |
| **few-shot** | **動的単一言語（反映2）＋例数可変（2-shot/1-shot/`0-shotを既定候補`）** | 言語汚染防止でprefillが半減（反映2）。さらに例数を絞るほどprefillが減りTTFTが縮む。**0-shotはLLGuidance（§1/§5のスキーマ強制）を信頼して例を一切送らない案**で、TTFT最優先ならこれが既定候補。例数とdisplay_text品質のトレードオフはP7-C8で実測（下記） |
| **KVキャッシュ preface再利用** | **会話内は差分再計算で自動。リクエスト間の再利用はVQ-1（未確認）** | 独立`generatePlan`ごとにpreface再prefillが安全側前提。**prefillはdecodeより速い**（P7-C0: prefill 46〜58 vs decode 32〜43 tok/s＝x86_64）ため、preface小型化で許容範囲に収める（**Galaxy AでのTTFTリスクは次段落参照**） |
| **UX** | **Basic即時表示 → AI補強を非同期で差し替え** | §8.7。AI待ちでUIをブロックしない。AIが来ないのは正常系（§19） |

**TTFT/Prefillリスクの明記（Gemini G1 CRITICAL #4）**: VQ-1（preface KVのリクエスト間再利用不可・未確認）を前提にすると、独立した`generatePlan`ごとに毎回preface全体（system+few-shot）を再prefillすることになる。Galaxy A級CPUのprefill速度がフラッグシップ実測（46〜58 tok/s・x86_64）を大きく下回る可能性を踏まえると、**TTFT（Time To First Token）が10〜20秒以上に達するリスクがある**。§8.7の非同期UXで機能としては成立するが、体感の遅さとしては無視できない。対策は二段構え: ①**動的単一言語few-shot（反映2・§3）でpreface長を約半分に**削減する。②**few-shot例数を可変にし、LLGuidanceのJSON Schema強制（§1/§5）を信頼した`0-shot`をデフォルト候補とする**（例文を一切送らずprefaceをsystemInstructionのみに切り詰める）。0-shotは言語汚染・コピー模倣のリスクをfew-shot自体から排除できる副次効果もあるが、Semantic Contextualization（§0/§2）の「模範」を示せないため出力品質が下がる可能性がある。**したがってTTFTと品質はトレードオフであり、モデル任せにせず実測で決める**。

**preface再利用の設計判断**: 最速は「preface（system+few-shot）を1回prefillして各リクエストで分岐再利用」だが、その分岐/cloneに相当する公開APIは未確認（VQ-1）。**安全側の既定は「Engine常駐＋各generatePlanでprefaceを含む会話を生成しpreface再prefill」**。few-shotを短文・可変例数に抑えることで再prefillコストを最小化し、真の増分はP7-C8実機で測る。

**P7-C8測定項目（追加・反映4）**: few-shot例数（2-shot/1-shot/0-shot）×単一言語（locale別）の組み合わせで、①TTFT ②decode tok/s ③品質（§8代理指標＝スキーマ適合率・sanity通過率・捏造ヒット率＋Semantic Contextualizationの人手評価）④フォールバック率 を記録し比較する（QH-16・§11）。§9の量子化比較とは独立した軸として実施し、**最終的な既定`shotCount`はこの実測後にFable 5がユーザーへ数値提示のうえ確定する**（§9のモデル選定手順と同型）。

---

## §8. 品質計測（Basic vs AI）― 自動代理指標＋人手評価（§57・Phase 12接続）

**端末内・オフライン計測。PIIを出さない（§60許可リストのみ・T-AIMET-1が回帰ロック）。**

**自動代理指標（`AiMetrics` 由来＋ハーネス集計。すべる非PII）**:

| 指標 | 定義 | §57対応 |
|---|---|---|
| スキーマ適合率 | ①形式を1回目で通過した割合 | JSON/Schema validity |
| sanity通過率 | ②内容を通過した割合 | Hallucination report（間接） |
| 再試行率 `retried` | 2回目に入った割合 | （品質安定性） |
| フォールバック率 | `SCHEMA_INVALID` 等でBasicへ落ちた割合 | （下限発動頻度） |
| display_text 文字数分布 | ja/en別の長さヒストグラム | （品質の形状） |
| 捏造ヒット率 | ②の捏造検出で弾いた割合 | Hallucination report |
| decode tok/s・TTFT・total ms・peakRAM | `BenchmarkInfo`（P7-C0発見・`ExperimentalFlags.enableBenchmark=true`） | latency/RAM |

**人手評価（少数・端末内固定データセット）**: 20〜30件の合成予定（PIIなし）に対し、①行動文が自然で実行可能か ②捏造がないか ③**予定固有の文脈に適合した提案か（単なる種別の言い換えで終わっていないか。Semantic Contextualization・§0/§2）**を3段階でレビュー。**代理指標だけでは「賢さ」を測れない（§39）ため補完**。

**Phase 12接続・主評価指標の変更（Gemini G1 CRITICAL #5・反映5）**: Phase 12「Basic/AI実験」（§76 Developer Settings・§36）の主評価指標は「時刻正確性」ではなく**「ステップの文脈適合度（ユーザーが納得するか）」**に置く。理由: UQ-1（§2・§13）によりestimated_minutes等の数値・時刻演算はBasic/AI双方が同一のKotlin決定的ロジックを共有するため、両エンジン間で時刻正確性に原理的な差は生じず比較指標として意味をなさない。両エンジンの差は`display_text`のSemantic Contextualization品質にのみ現れる。したがって上記人手評価③（文脈適合度）と代理指標（sanity通過率・捏造ヒット率）が、Phase 12実験の主評価軸として引き継がれる。この代理指標一式はそのままPhase 12へ渡せる（`AiMetrics` を§60許可リスト外へ拡張しないこと・基盤§18申し送り6）。

---

## §9. モデル/量子化 実機比較設計（P7-C8実機プローブ拡張・U-4整合）

基盤§11.3 P7-P2（Qwen3-0.6B / Qwen3-1.7B / Gemma3-1B）へ**量子化軸と品質軸を追加**する。

| 比較対象 | 追加理由 |
|---|---|
| Qwen3-0.6B **INT4 block-32**（既定） | 主推奨 |
| Qwen3-0.6B **mixed_int4**（TorchAO・ctx2048・474MiB） | 同一モデルの量子化差（品質×速度×RAM）を1軸で見る |
| Qwen3-1.7B INT4 / Gemma3-1B INT4 | 基盤どおり（日本語品質×速度のトレードオフ） |

**評価軸（各対象で記録）**: ①**日本語行動文品質**（§8の代理指標＝スキーマ適合率・sanity通過率・捏造ヒット率・文字数分布＋人手20〜30件スコア。**Semantic Contextualizationの文脈適合度を含む**）②decode tok/s ③TTFT ④ピークRAM（フルctx4096実測＝実機側の責務・基盤§12.8）⑤フォールバック率。**同一プロンプト・同一few-shot（shotCountは§7の既定候補で統一。例数自体の比較はQH-16で別軸として実施）・同一サンプリング（§4）・同一sanity（§6②）で条件を固定**して比較する。

**選定手順**: 冷却状態と連続5回後（スロットリング）で各対象を測定 → 表を作り **Fable 5 → ユーザーへ数値提示 → 最終選択**（U-4裁定と整合）。**この実測完了までGalaxy A最良は確定しない**（基盤§17根本未確認事項：Galaxy Aクラスの公開ベンチが世界に存在しない）。

---

## §10. 実装への反映（P7-C2 Red／C3実装への追加契約・すべて「追加」＝既存契約を弱めない）

| 対象 | 現状（P7-C1 scaffold） | 品質ハーネスが要求する追加（推奨） |
|---|---|---|
| **`PlanPromptBuilder`（F93）** | `build(context): String` 1メソッド | **preface生成を追加**: `buildSystemInstruction(locale): String` と `buildFewShot(locale, shotCount: Int = 2): List<Message>`（**localeで日本語例/英語例を分岐**＝ja→ja例のみ・en→en例のみを返す。`shotCount`で0/1/2件に絞れる。反映2/反映4）（またはまとめて `PromptBundle(systemInstruction, fewShot, dataMessage)` を返す新メソッド）。既存 `build` は「user data message」に相当。**署名を壊さず追加メソッドで拡張**する（P7-C2のT-PRM-*は現行`build`前提のため、bundle系は別テストで足す） |
| **`ai/adapter/LiteRtLmLocalLanguageModel`（F86）** | `ConversationConfig(enableResponseFormat, thinkingConfig, maxOutputToken)` を想定 | **`systemInstruction`／`initialMessages`／`samplerConfig`／`prefillPrefaceOnInit=true` を追加でセット**。**2生成ループ**（1回目greedy→失敗時、**会話履歴を破棄した新規single-turnセッションで§4の微小摂動サンプリング＋静的制約を追加した2回目**。反映1）を内部実装。`SamplingPolicy(primary, retry)` を注入可能にし既定を§4に置く |
| **`SchemaValidator`（F95）** | `validate(rawJson): SchemaValidationResult`（形式①） | **変更しない**（形式①専念）。**重複 `action_type`（uniqueItems相当）だけは①側で弾く**か②へ寄せるかをP7-C2で確定（LLGuidanceが非enforceのため必ずどちらかが担う） |
| **`ContentSanityChecker`（新設・推奨）** | 不在 | **②内容sanity**（§6）。入力 `AIPlanResponse`＋`PlanningContext`（title/locale）、出力 合否＋理由。**Kotlin決定的・PII非出力**（Fable 5 UQ-3で採用・§13） |
| **`LocalAiGateway`（F96）** | §8.6#9「retry1回」＝同一プロンプト再生成 | **retryを「新規single-turnセッションでの微小摂動再生成＋静的制約追加」に変更**（S-2是正・Gemini G1 CRITICAL #1・反映1）。**②sanityを①の後段に挿入**。`AiMetrics` に `sanityPassed: Boolean`（非PII・§60許容）を**追加する**（Fable 5 UQ-5で採用確定・§13）。T-AIMET-1の許可リストへの反映はP7-C2の実装時に行う |

**衝突回避の明示**: 上記はすべて**既存 scaffold の署名を壊さない加算**。P7-C2（Red・並行）が確定させる `SchemaValidator` 戻り値型（P7-C1申し送り3）・`PlanJsonSchema` の enum語彙（申し送り4）・Analytics収集口（申し送り1）には**本書は触れず**、確定後にその上へ②とサンプリング/few-shotを載せる。**`PlanJsonSchema` の `estimated_minutes`/`priority`/`skippable` の去就（§2/§5）はP7-C2/C3の裁定に従う**。

**§10実装反映の整合確認（P7契約確定サイクル、2026-08-10、domain-implementer。`docs/plans/phase7-local-llm-foundation.md`§14.4・DECISIONS.md ADR-0045〜0049）**: 上表5行の反映状況を行ごとに確認する。

| 対象 | 反映状況 | 差分・備考 |
|---|---|---|
| `PlanPromptBuilder`（F93） | **反映済（scaffold）**。`buildSystemInstruction(locale): String`・`buildFewShot(locale, shotCount: Int = 2)`を追加した（ADR-0049） | `buildFewShot`の戻り値型は本書提案の`List<Message>`ではなく、ランタイム中立の`List<PromptExample>`（新設データクラス）とした——`com.google.ai.edge.litertlm`のimportを`ai/adapter/`配下に限定する既存T-AIISO-9規律（基盤計画§8.1）を優先したため（ADR-0049「代替案と却下理由」参照）。対応するRedテスト（QH-8・QH-9・QH-14相当）は未作成、P7-C3以降へ申し送り |
| `LiteRtLmLocalLanguageModel`（F86） | **KDoc反映のみ（本体はTODO()のまま、契約確定サイクルの対象外）**。retry契約（2生成ループ・微小摂動・新規single-turnセッション）をKDocへ明記した | `SamplingPolicy(primary, retry)`の注入可能化はscaffold未実装（P7-C5の実装詳細として残置）。Gateway起点の「これは何回目の呼び出しか」判断方法も未確定（ADR-0049再検討トリガー） |
| `SchemaValidator`（F95） | **反映済（確定）**。「変更しない（形式①専念）」を採用し、重複`action_type`検出は**②`ContentSanityChecker`側へ確定**（ADR-0047） | P7-C2完了記録が残した「①か②か」の論点はこれで解決。`SchemaValidatorTest`のT-SCH-21（重複検出）は削除し②側へ責務移管した |
| `ContentSanityChecker`（新設） | **scaffold新設済み**（`app/src/main/java/com/actionstarter/ai/schema/ContentSanityChecker.kt`、TODO本体、ADR-0047） | 入力を`AIPlanResponse`＋`PlanningContext`とする本書の提案どおり実装した。対応するRedテスト（QH-4〜7・QH-10〜11・QH-15相当）は本サイクルでは新設していない（新規コンポーネントへの新規テスト作成は「P7-C2の66テストの整合調整」の範囲外と判断）。P7-C3以降へ申し送り |
| `LocalAiGateway`（F96） | **反映済（確定）**。retry是正・`AiMetrics.sanityPassed`追加ともに採用（ADR-0049） | `ContentSanityChecker`の実際の配線（コンストラクタへの注入）は本サイクルでは行っていない——本タスクの制約「AppContainerは裁定5のinterface化に必要な最小変更のみ可」を超えるため。P7-C5で配線すること |

**未確認事項（VQ-1〜VQ-3）・Gemini G1 CRITICAL 5件の反映状況**: 本サイクルはscaffold契約確定が対象であり、VQ-1〜VQ-3（§14、preface KV再利用・LLGuidance配列長enforce・実機適用効果）はいずれも実機/エミュレータ実測が必要な未確認事項のままP7-C8へ持ち越す。変更なし。

**P7-C2c反映確認（2026-08-10、test-writer。品質ハーネス由来の新設部品へのRed補完＋samplingPolicy契約追加）**: 上表が「対応するRedテストは本サイクルでは新設していない／未作成」と記録していた3行（`ContentSanityChecker`・`PlanPromptBuilder`・`LocalAiGateway`retry是正の一部）について、以下のとおり反映状況を更新する。

| 対象 | P7-C2c反映状況 | 詳細 |
|---|---|---|
| `ContentSanityChecker` | **Redテスト新設済み**（QH-4〜7・QH-10〜11・QH-15相当、15件） | `app/src/test/java/com/actionstarter/ai/schema/ContentSanityCheckerTest.kt`を新設。本体`TODO()`のため全件`NotImplementedError`によりRed |
| `PlanPromptBuilder`（`buildSystemInstruction`／`buildFewShot`） | **Redテスト新設済み**（QH-8・QH-14相当、8件） | 既存`PlanPromptBuilderTest.kt`へ追加。両メソッドとも本体`TODO()`のため全件`NotImplementedError`によりRed |
| `LocalAiGateway`のretry是正（samplingPolicy呼び分け） | **契約追加＋Redテスト新設済み**（QH-9はSamplingPolicy自体の契約テストとして独立、GatewayのRed 2件を追加） | Fable 5裁定9（ADR-0050）により`LocalLanguageModel.generatePlan`へ`samplingPolicy: SamplingPolicy = SamplingPolicy.Primary`引数を追加（契約変更）。新設`SamplingPolicy` enum（`ai/SamplingPolicy.kt`）はPrimary/Retryの値が確定済みのためborn-green（`SamplingPolicyTest.kt`、4件）。Gatewayが1回目Primary・2回目Retryで呼び分ける契約を検証するT-GW-19・T-GW-20を`LocalAiGatewayTest.kt`へ追加（Gateway本体`TODO()`のためRed） |

この反映確認により、本書§10が新設を推奨した5行のうち「Redテスト未作成」として残っていた項目はすべて解消した（`SchemaValidator`・`PlanJsonSchema.TEXT`本体のGreen実装自体はP7-C3のスコープのまま、変更なし）。詳細は`docs/plans/phase7-local-llm-foundation.md`§14.5「P7-C2c完了記録」・`DECISIONS.md` ADR-0050参照。

---

## §11. テスト観点（本書が新設を推奨する分。ID衝突回避のため `QH-` 名前空間）

| ID | 区分 | 内容・期待値 |
|---|---|---|
| QH-1 | E1 正常 | 1回目greedyが①②を通過 → `Success`・`retried=false` |
| QH-2 | E1 異常 | ①失敗→2回目通過 → `Success`・`retried=true`。**2回目のサンプリングが1回目と異なる**（`topK=5,temperature=0.15`等の微小摂動）**かつ2回目が1回目の会話履歴を含まない新規single-turnセッションで生成される**ことを検証（S-2是正＋マルチターン自己修正の不採用・反映1の回帰ロック） |
| QH-3 | E1 異常 | ①も②も2回失敗 → `Fallback(SCHEMA_INVALID)`。生成が**ちょうど2回**（3回目を呼ばない） |
| QH-4 | E1 異常 | display_text に時刻/数字（"10:00"・"15分"・"3"）→ ②捏造検出で不合格（§13/§15/§34） |
| QH-5 | E1 異常 | display_textがイベントtitle正規化後の**完全一致**、または**titleがdisplay_textの80%以上を占める逐語コピー**（title長6文字以上）→ ②不合格（緩和後の閾値・反映3） |
| QH-6 | E1 異常 | locale=ja なのに display_text が英語のみ → ②locale整合で不合格 |
| QH-7 | E1 異常 | 同一 action_type 重複step（LLGuidance非enforce・§5）→ 不合格（①か②） |
| QH-8 | E1 正常 | プロンプトに system/few-shot/data の3部が含まれる。**few-shotはlocaleに応じて単一言語のみ**（locale=jaなら日本語例のみ・enなら英語例のみで他言語の例が混入しない。反映2）。各modelターンが**有効JSON・数値/時刻ゼロ**（模範の回帰ロック） |
| QH-9 | E1 正常 | `SamplingPolicy` 既定が §4（primary `topK=1,temp=0.0`／retry `topK=5,temp=0.15,seed≠`）。`topK>0` 制約を破らない |
| QH-10 | E1 エッジ | ②禁止語/プレースホルダ（"TODO"・"example"・"<think"）を含む display_text → 不合格 |
| QH-11 | E1 正常 | ②は決定的（LLM非依存）で、同一入力に同一判定（PII非出力・T-AIMET整合） |
| QH-12 | E3 | 実機/エミュ小コンテキストで、few-shot付きプロンプト→①②通過JSONが得られる（基盤T-P7E2E-2の品質版・小プロファイル） |
| QH-13 | E4(probe) | §9の量子化/品質比較を記録（Qwen3-0.6B INT4 / mixed_int4 / 1.7B / Gemma3-1B） |
| QH-14 | E1 正常 | `buildFewShot(locale, shotCount)` が locale=ja/en それぞれで単一言語の例のみを返し、`shotCount`=0/1/2の境界値で正しい件数を返す（反映2/反映4） |
| QH-15 | E1 エッジ | title="テニス"（6文字未満）を含む自然な display_text（例:"テニスの準備をする"）→ **①②とも合格**（コピー検出が短いtitleで誤検出しないことの回帰ロック。反映3・Gemini G1 CRITICAL #3） |
| QH-16 | E4(probe) | few-shot例数（2-shot/1-shot/0-shot）別のTTFT・decode速度・品質（§8代理指標）・フォールバック率を記録（§7・反映4） |

---

## §12. エラー＆レスキューマップ（ハンドリング欄に空欄なし）

| # | 処理 | 想定異常 | ハンドリング | ユーザー影響 |
|---|---|---|---|---|
| 1 | サンプリング指定 | 0.15.0の `SamplerConfig`/`systemInstruction`/`initialMessages` が実は不在（VQ-3の否定側） | 起動時にadapterで存在をfail-fast検知し、**few-shotを単一プロンプト文字列に埋め込む縮退**＋**サンプリングはモデル既定**へ。ログとAnalyticsに記録 | AI品質はやや低下するがBasicで全機能継続 |
| 2 | 再試行 | 1回目greedy＝決定的で失敗、同条件2回目が同一失敗（S-2の欠陥）。**加えて0.6B/INT4は温度を大きく上げると出力崩壊し、マルチターン自己修正もほぼ機能しない**（Gemini G1 CRITICAL #1） | **2回目は会話履歴を破棄した新規single-turnセッション＋微小摂動（`topK=5,temperature=0.1〜0.2`程度）＋静的制約追加を必須**（§4/§6）。大きな摂動やマルチターン自己修正は採用しない。摂動が効かない・旧設計へ退行する実装はQH-2が検知 | 再試行が実際に別解を試み、無駄打ちしない。出力崩壊も起きない |
| 3 | 出力矯正 | LLGuidanceが配列長/長さを実はenforceしない（0.15.0同梱版差・VQ-2） | 第2層Kotlin検証が全制約を独立再チェック（基盤§8.4）。決してdecode矯正に依存しない | 誤形式がDomainに入らない |
| 4 | 内容sanity | display_text に予定に無い固有名詞/数値を捏造（§34） | ②捏造検出（数字/時刻/URL/コピー）で不合格→retry→Basic。**黙って通さない・黙って剥がさない** | 捏造提案がユーザーに出ない |
| 5 | 内容sanity | locale不一致（ja要求にen出力等） | ②locale整合で不合格→retry→Basic | 言語が崩れた提案を出さない |
| 6 | 速度 | preface再prefill＋decodeで秒オーダー待ち | Basic即時表示→AI非同期差し替え（§8.7）。few-shot例数可変・0-shotを既定候補に（§7・反映4）・maxOutputToken最小 | 画面は止まらない。AI未達は正常系 |
| 7 | preface再利用 | リクエスト間KV再利用APIが無い（VQ-1）。**Galaxy A CPUではTTFTが10〜20秒以上になりうる**（Gemini G1 CRITICAL #4・反映4） | 各generatePlanでpreface再prefillを既定に（安全側）。**動的単一言語few-shot（反映2）でpreface半減＋例数可変・0-shotを既定候補**（反映4・§7）でprefillコストを抑制。真の増分・最終既定値はP7-C8実測 | 体感待ちが増える（Basic即時表示との併用＝§8.7で機能は成立） |
| 8 | thinking | `<think>` 混入でトークン浪費 | `enableThinking=false`固定（§4）。混入検知時は②で不合格 | 現実的時間で終わる |
| 9 | 計測 | 代理指標にPII混入 | `AiMetrics`/ハーネス集計を§60許可リストに限定（T-AIMET-1）。②はPII非出力 | 送信可能指標にPIIが混ざらない |
| 10 | 内容sanity | titleコピー検出が短い/自然な言い換えまで誤検出する（過検出。Gemini G1 CRITICAL #3） | **閾値ルールへ緩和**: title6文字未満は非適用。正規化後完全一致、またはtitleがdisplay_textの80%以上を占める場合のみ不合格（§6②・反映3） | 自然な提案（例:「テニスの準備をする」）が誤って弾かれない |

---

## §13. Fable 5 確認事項（UQ-1〜UQ-5・裁定済み）

**Fable 5がPass1/Pass2レビューにより本表「裁定」列のとおり確定した（2026-08-10）。UQ-1〜UQ-5は全項目「推奨どおり採用」（UQ-1は精緻化、UQ-2は是正内容をGemini G1 CRITICAL #1でさらに精緻化、UQ-4はCRITICAL #2/#4で設計を発展）。全5項目裁定済みのためG1通過条件を満たす。**

| ID | 確認事項 | 推奨 | 裁定（Fable 5・2026-08-10） |
|---|---|---|---|
| **UQ-1** | **タスク最小化の徹底度**: `estimated_minutes`/`priority`/`skippable` をLLM出力から外し（§2/§5最小スキーマ）、Kotlin決定に一本化してよいか。P7-C2確定スキーマとの整合はどちらを優先するか | **外す（Kotlin一本化）を推奨**。§13/§15/§34と最も整合。残す場合もLLM値は参考降格＋Kotlin上書き | **採用（精緻化）**。estimated_minutes/priority/skippableはKotlin一本化で確定。加えて、`display_text`は「単なる種別の言い換え」ではなく**Semantic Contextualization**（予定固有の文脈化された行動。§0/§2/§5）を生成させることをタスク定義として明記する。Basic版の汎用固定文言との差別化点はここに置く |
| **UQ-2** | **S-2 retry定義の是正**（本書CRITICAL）: 「同一プロンプト・temp=0.0・seed固定」→「**温度/seed摂動＋是正指示**」へ変更してよいか | **是正を推奨**（決定的retryは同一失敗を再現し無効）。基盤§8.6#9・S-2の本文更新が必要 | **採用（是正・精緻化）**。決定的retryが無効という指摘は採用のうえ、是正の中身をGemini G1 CRITICAL #1でさらに精緻化: 大きな温度上昇・マルチターン自己修正ではなく、**微小摂動（topK=5,temperature=0.1〜0.2）＋会話履歴を破棄した新規single-turnセッション＋静的制約追加**（§4/§6）とする。基盤§8.6#9・S-2の本文更新はP7-C2/C3側での反映を要request（本書はsrc/基盤計画に触れない） |
| **UQ-3** | **②内容sanityを新設 `ContentSanityChecker` として `SchemaValidator` と分離**してよいか（重複action_typeの担当も確定） | **分離を推奨**（形式/内容の責務分離・P7-C1申し送り3に触れない） | **採用**。`ContentSanityChecker` を `SchemaValidator` と分離して新設する（§6/§10）。重複action_typeの担当（①か②か）はP7-C2で最終確定 |
| **UQ-4** | few-shotを**2例固定・ja/en両掲**でよいか（トークン予算 vs 刷り込み強度） | 推奨どおり。P7-C8で例数×品質×速度を実測し再調整余地 | **採用（発展）**。「2例固定・ja/en両掲」という当初案は、Gemini G1 CRITICAL #2で**動的単一言語**（ja/en常時混在の廃止・§3）へ、CRITICAL #4で**例数可変（2/1/0-shot、0-shotを既定候補）**（§7）へとそれぞれ発展した。最終的な既定`shotCount`はP7-C8実測後にFable 5が確定する |
| **UQ-5** | `AiMetrics` に `sanityPassed`（非PII bool）を1項目追加してよいか（T-AIMET-1許可リスト拡張） | 追加を推奨（§60範囲内）。可否はP7-C2で最終確定 | **採用**。`AiMetrics` へ `sanityPassed: Boolean`（非PII・§60許容）を追加する（§10）。T-AIMET-1許可リストへの実反映はP7-C2の実装時に行う |

---

## §14. 未確認事項（VQ-1〜VQ-3・P7-C0/C8で実測確定）

| ID | 内容 | 影響 | 確定方法 |
|---|---|---|---|
| **VQ-1** | **preface（system+few-shot）のKVを複数の独立`generatePlan`間で再利用する公開API**（会話clone/branch/preface-reset）の有無 | §7の再prefillコスト・体感速度（**TTFT 10〜20秒以上のリスク・Gemini G1 CRITICAL #4・反映4**） | 0.15.0 AARのバイトコード精査＋P7-C8実測（persistent会話reset vs fresh会話/回。shotCount別のTTFTも含む＝QH-16） |
| **VQ-2** | **LiteRT-LM 0.15.0 同梱llguidanceが `minItems`/`maxItems`（配列長）を実際にenforceするか**（P7-C0は steps=1固定で未実測） | §5出力矯正の強さ | P7-C8で steps 0件/9件/8件を投げてdecode段で弾かれるか実測（弾かれずとも第2層で担保） |
| **VQ-3** | **0.15.0の `SamplerConfig`/`systemInstruction`/`initialMessages` を実機で実際に適用したときの効果**（v0.15.0タグのソースでは存在確認済だが、adapter経由での実適用は未実施） | §3/§4全体 | P7-C0後続 or P7-C5実装時に「温度0で決定的・few-shotでlocale分離が効く」ことを実測 |

**根本前提（基盤§17と共通）**: Galaxy AクラスのオンデバイスLLM実測は世界に存在しない。**本書の品質・速度の現実解はP7-C8実機プローブ（§9）完了まで暫定**である。だからこそサンプリング/few-shot/②sanity/摂動retry/Basic下限という**モデルに依存しない周辺設計で下限を固める**。

---

## §15. 承認状態

**Fable 5裁定UQ-1〜5済み＋Gemini G1（`gemini-3.5-flash`）CRITICAL 5件反映済み（2026-08-10）→ G1通過。**

**Fable 5裁定（UQ-1〜UQ-5）**: §13の表のとおり全5項目「採用」で裁定済み（UQ-1は精緻化・UQ-2はGemini G1 CRITICAL #1でさらに精緻化・UQ-4はCRITICAL #2/#4で発展）。未裁定項目なし。

**Gemini G1（`model: "gemini-3.5-flash"`固定。本書独自のレビュー＝基盤計画のG1 CRITICAL #1〜#5とは別件）CRITICAL 5件の反映先**:

| # | 指摘 | 反映先 |
|---|---|---|
| CRITICAL #1 | retry摂動の設計が同一条件下で無効。かつマルチターン自己修正は0.6B/INT4に不向き | §0（S-2是正）／§4（サンプリング値・微小摂動）／§6（再試行フロー・静的制約）／§10（`LiteRtLmLocalLanguageModel`/`LocalAiGateway`）／§11（QH-2）／§12（#2）／§13（UQ-2） |
| CRITICAL #2 | ja/en常時混在few-shotによる言語汚染 | §0／§3（`buildFewShot(locale)`の動的単一言語化）／§10（`PlanPromptBuilder`）／§13（UQ-4） |
| CRITICAL #3 | titleコピー検出の過検出 | §6②（閾値ルールへ緩和）／§11（QH-5改訂・QH-15新設）／§12（#10新設） |
| CRITICAL #4 | Prefill/TTFTの過小評価 | §7（TTFTリスク明記＋例数可変・0-shot既定候補）／§9（比較条件の整合）／§11（QH-16新設）／§12（#7）／§13（UQ-4）／§14（VQ-1の影響欄） |
| CRITICAL #5 | AIの付加価値の再定義（Semantic Contextualization） | §0／§2／§5／§8（Phase 12比較指標の差し替え）／§13（UQ-1精緻化） |

**未確認事項（VQ-1〜VQ-3）**: §14のとおり維持する。P7-C8実機プローブでの確定項目であり、本書のG1通過はVQ-1〜VQ-3の未確定を妨げない（確定するまで該当箇所は暫定として扱う。§14末尾の根本前提と同じ扱い）。
