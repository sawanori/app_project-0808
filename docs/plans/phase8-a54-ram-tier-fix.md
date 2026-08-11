# Phase 8 実装計画書 — A54実機RAM段判定バグ修正（`DeviceCapability`閾値是正）

> 対象仕様: §5.3・§95.3（Local AI端末対応可否・RAM段判定）
> 前提基盤: Phase 7 P7-C4（`DeviceCapabilityImpl`実装）・ADR-0048（`DeviceCapability`interface化）・ADR-0057（`defaultProfilePeakRamBytes`プロファイル別是正）／Phase 8 C1-C4（実行画面AI配線・エミュ実機実証済み、`docs/plans/phase8-ai-execution-wiring.md`）
> 種別: バグ修正計画書。**Phase 8 C4のA54実機最終確認を阻む実行ブロッカーの解消**。Red→Green一体コミットを想定。
> 承認状態: **ユーザー承認済み・Step 4実装完了（2026-08-11）。残タスクは§8のA54実機受け入れ確認のみ**

---

## §0. 結論ファースト

Galaxy A54 5G（表記6GB RAM）実機で設定画面のAI有効化ができない不具合は、`DeviceCapabilityImpl.classify()`（`app/src/main/java/com/actionstarter/ai/model/DeviceCapability.kt` L82-89）が**「表記RAM」と「`ActivityManager.MemoryInfo.totalMem`（OS/ファームウェア予約控除後の実効値）」を同一視**し、`totalMem < 6GiB`を非対応（`TIER_0_UNSUPPORTED`）と判定していたことが原因である。表記6GB機の`totalMem`は実測で概ね5.3〜5.8GiBしかなく境界を割り込む。

**修正方針**: 閾値定数を「表記RAM段の中点」へ是正する。`TIER_0_MAX_TOTAL_MEM_BYTES`を6GiB→**5GiB**、`TIER_2_MIN_TOTAL_MEM_BYTES`を8GiB→**7GiB**へ変更する。定数名・`classify()`のロジック構造は不変。誤受け入れ側（表記4GB機が段1に混入するリスク）は既存のロード前`hasAvailableMemory`能動ガードが第2防御として引き続き担うため変更しない。

変更対象は`DeviceCapability.kt`（定数2値＋KDoc）と`DeviceCapabilityTest.kt`（境界テスト更新＋新規ケース）の2ファイルのみ。`LocalAiGateway.kt`・`SettingsViewModel.kt`・文言リソース・`ModelCatalog`は無変更のまま、消費先の挙動が自動的に是正される。

**ADR番号についての訂正**: 本タスク発注時点の前提「既存ADR最新は0059」は`docs/plans/phase7-local-llm-foundation.md:729`の記述のみに基づいていたが、本計画書起案時に`DECISIONS.md`を直接grepしたところ、P7-C7で追加された**ADR-0060が既に存在する**ことを確認した（`DECISIONS.md` L1542、コミット`ec0129b`）。したがって本計画書の決定記録は**ADR-0061（ドラフト）**として採番する。詳細は§7。

---

## §1. 目的・背景

ユーザー実機Galaxy A54 5G（表記6GB RAM）で、設定画面に「この端末はプライベートAIに必要なメモリ（6GB以上）を満たしていません。」（`values-ja/strings.xml` L212）が表示され、Local AIを有効化できない。

**根本原因**: `DeviceCapabilityImpl.classify()`は`ActivityManager.MemoryInfo.totalMem`を`TIER_0_MAX_TOTAL_MEM_BYTES`（6GiB＝6,442,450,944B）と直接比較する。しかしAndroidの`totalMem`はカーネル・ファームウェア予約分を控除した実効値であり、製品仕様上「6GB」と表記される端末でも実測5.3〜5.8GiB程度しか報告しない。この「表記RAM（製品仕様値）」と「`totalMem`（実効値）」の単位混同により、表記6GB機が軒並み段0（非対応）へ落ちる。

**消費箇所（2箇所、いずれも`TIER_0_UNSUPPORTED`のみ判定）**:
- `LocalAiGateway.kt` L158-163: `generatePlan`冒頭で`deviceCapability.classify() == DeviceTier.TIER_0_UNSUPPORTED`なら即`Fallback(UNSUPPORTED_DEVICE)`。
- `SettingsViewModel.kt` L79・L83: `refresh()`が`classify()`の結果を`DeviceUnsupportedReason.INSUFFICIENT_RAM`へ変換し、AIスイッチ無効化・非対応理由文言表示・DL導線非表示を行う。

**同型の潜在欠陥**: `TIER_2_MIN_TOTAL_MEM_BYTES`（8GiB）にも同じ単位混同がある（表記8GB機の`totalMem`は実測約7.0〜7.7GiB）。ただし`classify()`の戻り値`TIER_2_OPT_IN`を挙動として消費するコードは現状存在しない（`LocalAiGateway`/`SettingsViewModel`ともTIER_0判定のみ参照）ため、これは**挙動中立の予防的是正**である。

本バグはPhase 8最終確認（C4: A54実機でのAI有効化→Gemma4 DL→実行画面AI文言描画確認）の**前提ブロッカー**であり、本修正なしにはPhase 8を完了できない。

---

## §2. 機能一覧と仕様

**F: `DeviceCapabilityImpl.classify()`の判別点を、`totalMem`実効値ベースで正しく「表記RAM段」を判別する値へ是正する。**

### 修正後の判定表

| `totalMem`（実効値） | `classify()`戻り値 | 対応する表記RAM目安 |
|---|---|---|
| `< 5,368,709,120B`（5GiB未満） | `TIER_0_UNSUPPORTED` | 表記4GB以下相当 |
| `5,368,709,120B`以上`7,516,192,768B`未満（5GiB以上7GiB未満） | `TIER_1_STANDARD` | 表記6GB相当（**Galaxy A54含む**） |
| `7,516,192,768B`以上（7GiB以上） | `TIER_2_OPT_IN` | 表記8GB以上相当 |

### 定数変更

| 定数（`DeviceCapability.Companion`） | 現行値 | 修正後の値 |
|---|---|---|
| `TIER_0_MAX_TOTAL_MEM_BYTES`（L52） | `6L * 1024 * 1024 * 1024`（6,442,450,944） | `5L * 1024 * 1024 * 1024`（**5,368,709,120**） |
| `TIER_2_MIN_TOTAL_MEM_BYTES`（L55） | `8L * 1024 * 1024 * 1024`（8,589,934,592） | `7L * 1024 * 1024 * 1024`（**7,516,192,768**） |

定数名・`classify()`の`when`分岐構造（L82-89）はいずれも不変。数値リテラルのみ差し替える。

### KDoc是正方針

現行KDocは「6GB未満」「6GB以上」「8GB以上」という表現が定数値と字面一致するため無矛盾に見えるが、実際は「`totalMem`実効値」と「表記(製品仕様)RAM」を暗に同一視した記述であり、これが単位混同バグの温床だった。是正箇所3点:

1. interface `DeviceCapability`のKDoc「段の対応（§5.3）」（L26-28）: 各段が指す製品仕様（表記6GB以上が対象、等）自体は不変。「`totalMem`実効値の判別点は5GiB/7GiBであり、OS/ファームウェア予約分（表記値からの控除、目安0.2〜1GiB）を織り込んだ上で表記6GB/8GB機を正しく判別するよう選定した値である」旨を追記する。
2. companion定数のKDoc（L51「§95.3・§5.3段0上限。この値未満はLocal AI対象外。」／L54「§5.3段2下限」）: 「本値は`totalMem`実効値の閾値であり、表記(製品仕様)RAMの数値そのものではない」旨を一文追記する。
3. `DeviceCapabilityImpl`クラスKDoc（L71-74）: 「境界値は『未満／以上』で判定し、ちょうど6GBは段1・ちょうど8GBは段2」という例示は、新閾値でも6GiB/8GiBが引き続きTIER_1/TIER_2の内側に収まるため事実として誤りではないが、「6GB/8GBが境界値である」という誤った印象を与える。「実際の境界は5GiB/7GiB。6GB/8GBはいずれも新境界の内側」と明記する。

### 仕様不変の確認

- 仕様§5.3「表記6GB未満は対象外」／§95.3の製品仕様自体は変更しない（実装の判別点のみ是正）。
- `values(-ja)/strings.xml`の`settings_ai_unsupported_ram_reason`（「6GB以上」文言）は表記RAM基準の文言として修正後も正確なため**変更しない**。

---

## §3. 変更対象ファイル構成

### 変更するファイル（3件）

1. **`app/src/main/java/com/actionstarter/ai/model/DeviceCapability.kt`** — `TIER_0_MAX_TOTAL_MEM_BYTES`・`TIER_2_MIN_TOTAL_MEM_BYTES`の値変更（L52, L55）＋KDoc是正（L26-28, L51, L54, L71-74）。`classify()`本体（L82-89）・`isAbiSupported()`・`hasAvailableMemory()`はロジック無変更。
2. **`app/src/test/java/com/actionstarter/ai/model/DeviceCapabilityTest.kt`** — 境界テスト更新＋新規ケース（§5参照）。
3. **本計画書**（`docs/plans/phase8-a54-ram-tier-fix.md`）。

### 変更しないもの（明示）

- `app/src/main/java/com/actionstarter/ai/LocalAiGateway.kt`（L158-163の`classify()`消費ロジック。閾値是正の効果を無改修で受ける）
- `app/src/main/java/com/actionstarter/features/settings/SettingsViewModel.kt`（L77-84の消費ロジック、同上）
- `values/strings.xml`（L219）・`values-ja/strings.xml`（L212）の`settings_ai_unsupported_ram_reason`
- `hasAvailableMemory()`・`MEMORY_SAFETY_MARGIN_BYTES`（ロード前availMem能動ガード。ADR-0057の`defaultProfilePeakRamBytes`含む）
- `ModelCatalog.kt`
- AVD設定（`hw.ramSize`等）
- 仕様書§5.3・§95.3本文（実装注記は本計画書側で扱う）

---

## §4. 依存関係・技術選定の根拠

新規ライブラリ依存なし。既存コンパイル単位内の定数値変更のみ。

**中点閾値（表記X GB → X−1GiB）の根拠**: 表記段は4/6/8GBの2GB刻み。表記X GB機の`totalMem`はOS/ファームウェア/ベンダーオーバーレイの予約分により、機種・ベンダー差を含めおよそ`X−0.8`〜`X−0.2`GiBに分布する。境界を表記段の中点（`X−1GiB`）に置くことで、表記6GB（重い予約=5.3GiB級）の取りこぼし（false reject）と表記4GB（軽い予約=3.8GiB級）の誤受け入れ（false accept）の双方に対して最大マージンを確保できる。

**5.5GiB案（不採用）**: 表記6GBの下限ぎりぎりに閾値を置く案は、重カーブアウト端末（`totalMem`実測5.3GiB級）を依然取りこぼすため不採用。

**TIER_2側を同時是正する根拠**: 現状`TIER_2_OPT_IN`の挙動消費箇所はゼロで実害はないが、放置すると将来消費コードが追加された時点で同型の実機ブロッカーが再発する。定数の意味を「表記RAM判別点」に揃えるという本修正の目的からも片方だけの是正は一貫性を欠く。

**誤受け入れ側の第2防御（変更なし）**: `LocalAiGateway`のロード前`hasAvailableMemory`能動ガード（要求ピークRAM＋512MBマージン、ADR-0057の`defaultProfilePeakRamBytes`使用）が、万一の表記4GB機のTIER_1誤混入を実行時に個別ブロックする。本修正はこの層に触れない。

---

## §5. テストケースリスト

`GB = 1024L * 1024 * 1024`（既存`DeviceCapabilityTest`の定数を継続使用）。分類ラベル: **[Red]**=現行実装で失敗させる主目的ケース、**[born-green]**=現行実装でも通るが新契約を固定する回帰ガード、**[既存期待値更新]**=既存テストの期待値を書き換え、**[既存維持]**=無変更で回帰確認。

### 正常系

| ID | `totalMem`入力 | 期待値 | 種別 |
|---|---|---|---|
| T-DCT-1 | 5.5GiB（≈5,905,580,032B、**A54実測相当**） | `TIER_1_STANDARD` | **[Red] 主目的ケース** |
| T-DCT-2 | 7.4GiB（≈7,945,689,498B、表記8GB実測相当） | `TIER_2_OPT_IN` | [Red] |
| T-DCT-3 | 6GiB（既存`classify_sixGbTotalMem_returnsTier1Standard`） | `TIER_1_STANDARD` | [既存維持]（無変更） |
| T-DCT-4 | 8GiB（既存`classify_eightGbTotalMem_returnsTier2OptIn`） | `TIER_2_OPT_IN` | [既存維持]（無変更） |

### 異常系（誤分類防止）

| ID | `totalMem`入力 | 期待値 | 種別 |
|---|---|---|---|
| T-DCT-5 | 3.6GiB（≈3,865,470,566B、表記4GB実測相当） | `TIER_0_UNSUPPORTED` | [born-green] 誤受け入れ防止 |
| T-DCT-6 | `totalMem = 0`（未取得・異常値の代表） | `TIER_0_UNSUPPORTED` | [born-green] |

### エッジケース（境界値）

| ID | `totalMem`入力 | 期待値 | 種別 |
|---|---|---|---|
| T-DCT-7 | 5GiBちょうど（5,368,709,120B） | `TIER_1_STANDARD` | [Red] 新下限境界・以上で段1 |
| T-DCT-8 | 5GiB−1（5,368,709,119B） | `TIER_0_UNSUPPORTED` | [born-green] 新下限境界の下側 |
| T-DCT-9 | 7GiBちょうど（7,516,192,768B） | `TIER_2_OPT_IN` | [Red] 新段2下限境界 |
| T-DCT-10 | 7GiB−1（7,516,192,767B） | `TIER_1_STANDARD` | [born-green] 段2境界の下側 |
| T-DCT-11 | 6GiB−1（既存`classify_justUnderSixGb_returnsTier0Unsupported`） | `TIER_0_UNSUPPORTED`→**`TIER_1_STANDARD`へ変更** | **[既存期待値更新]** 新契約では境界外の一点。メソッド名も`…returnsTier1Standard`へ改名要 |

### 回帰（無関係機能・全体）

| ID | 内容 | 種別 |
|---|---|---|
| T-DCT-12 | `hasAvailableMemory`系2件（`availMemBelowRequired`/`AboveRequired`、`totalMem=8GB`固定・`availMem`500MB/4GB） | [既存維持] 本修正と無関係、無変更 |
| T-DCT-13 | `isAbiSupported`系2件 | [既存維持] 本修正と無関係、無変更 |
| T-DCT-14 | `:app:testDebugUnitTest`既存スイート全件（直近639件、`build/agent-logs/p8c3-full.log`実測基準）Green維持。`:app:lintDebug` error 0維持 | [全体回帰] |

---

## §6. エラー＆レスキューマップ

| 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|
| `classify()`（是正後） | 超重カーブアウト端末が表記6GBでも`totalMem`実効値5GiB未満を報告する | `TIER_0_UNSUPPORTED`のまま。`LocalAiGateway`が`Fallback(UNSUPPORTED_DEVICE)`、`SettingsViewModel`が非対応理由文言を画面に明示表示（サイレントでない） | AIは使えないが理由が明示され、Basicへの縮退は既存経路で保証される |
| `classify()`（是正後） | 表記4GB級の軽量カーブアウト機が新閾値で`TIER_1_STANDARD`に誤って混入する | `LocalAiGateway`のロード前`hasAvailableMemory`能動ガード（`defaultProfilePeakRamBytes`＋512MBマージン）が`INSUFFICIENT_MEMORY`で別途Fallbackし、Basicへ縮退。ログ・`AiMetrics`に記録される（サイレントでない、第2防御） | AI初回試行が失敗してもクラッシュせず安全に縮退。以降も同様 |
| `classify()`（是正後） | AVD等の高メモリ実行環境（`hw.ramSize=8192`、`totalMem`実効値約7.6GiB）が`TIER_2_OPT_IN`化する | `TIER_2_OPT_IN`を挙動として消費する呼び出し箇所は現状ゼロ（§1参照） | 挙動非消費のためユーザー影響なし |
| `classify()`本体 | `ActivityManager.getMemoryInfo()`呼び出し自体が例外を送出する（OS/実機異常） | 本修正のスコープ外（定数値変更は例外経路に影響しない）。既存挙動のまま変更しない。なお`generatePlan`側の例外封じ込め契約の検証は本修正の対象外 | 本修正による新規リスクなし |
| KDoc更新 | 定数値だけ変更しコメントを放置し、ドキュメントと実装が乖離する | §3で該当箇所（L26-28, L51, L54, L71-74）を明示し、コード変更と同一コミットに含める（§9） | 直接影響なし。将来の誤解釈を予防する明示対応 |

---

## §7. 決定記録（ADR-0061ドラフト）

### ADR-0061: `DeviceCapability`のRAM段判別点を`totalMem`実効値ベースへ是正する（表記RAMとの単位混同解消・A54実機ブロッカー解消）

**ADR番号の付番根拠（訂正あり）**: 本タスク発注時点の想定「既存ADR最新は0059」は`docs/plans/phase7-local-llm-foundation.md:729`の記述のみを参照したものだった。本計画書起案時（2026-08-11）に`grep -n "^### ADR-" DECISIONS.md`を実行したところ、**最新確定ADRは0060**（P7-C7 AI隔離ガード拡張、2026-08-10、`DECISIONS.md` L1542、コミット`ec0129b`）であることを確認した。既存ADR（0057/0059/0060）はいずれも「起票直前の再grep」で番号を確定する慣行を持つため、これに倣い本決定を**ADR-0061**として採番する。本番号は計画書時点の暫定値であり、Step 4実装後に`DECISIONS.md`へ正式起票する際は同じ手順で再確認すること。**2026-08-11のStep 4完了時にDECISIONS.mdへADR-0061として正式起票済み。本節は起票時の原案である。**

- 日付: 2026-08-11（本計画書起案時点） ／ ステータス: 採用（2026-08-11・DECISIONS.mdへ正式起票済み） ／ 起案: 計画書起案担当（sonnet） ／ 関連仕様§: §5.3・§95.3、ADR-0048（`DeviceCapability`interface化）、ADR-0057（`defaultProfilePeakRamBytes`プロファイル別是正）

**背景**: §1参照。Galaxy A54 5G（表記6GB）実機で`classify()`が`totalMem`実効値（実測5.3〜5.8GiB相当）を表記RAMと同一視して非対応判定し、Phase 8最終確認（実機A54でのAI有効化）が進められない。

**決定**:
1. `TIER_0_MAX_TOTAL_MEM_BYTES`を6GiB→5GiB、`TIER_2_MIN_TOTAL_MEM_BYTES`を8GiB→7GiBへ変更する。定数名・型・`classify()`の`when`分岐構造は変更しない。
2. KDocへ「`totalMem`実効値」と「表記(製品仕様)RAM」が異なる値であることを明記する（§2参照）。
3. 誤受け入れ側の防御は既存の`hasAvailableMemory`能動ガードに委ね、変更しない（責務分離を維持）。

**代替案と却下理由**:

| 代替案 | 却下理由 |
|---|---|
| 5.5GiBへ是正（表記6GB下限ぎりぎり） | 重カーブアウト端末（`totalMem`実測5.3GiB級）を依然取りこぼす。中点(5GiB)よりマージンが小さい |
| `TIER_2_MIN_TOTAL_MEM_BYTES`（8GiB）は据え置き、`TIER_0`側のみ是正 | 同型の単位混同バグが表記8GB機にも存在する。現状消費箇所ゼロで実害はないが、将来`TIER_2_OPT_IN`を消費するコードが追加された時点で同じ実機ブロッカーが再発する。予防的に同時是正する方が安全 |
| 実機ごとにOEM提供の「表記RAM」を別APIで取得し直接比較する動的検出 | Android標準にOEM非依存の公式APIが存在しない。既存の`totalMem`ベース設計を維持しつつ閾値のみ是正する方が構造変更が小さく安全 |

**影響範囲**: `DeviceCapability.kt`のみ（定数2件＋KDoc）。`LocalAiGateway.kt`・`SettingsViewModel.kt`は無変更だが、消費するTIER判定結果が変わるため実質的な挙動変化（A54でAI有効化可能になる）が生じる。

**検証方法**: §5のテストケース（T-DCT-1〜14）。`:app:testDebugUnitTest`全件Green（既存639件＋新規/更新分）・`:app:lintDebug` error 0。実機検証は§8のA54受け入れ手順で別途実施し、結果をStep 4完了記録へ追記する。

**再検討トリガー**: A54実機の`totalMem`実測値が5GiB未満だった場合（想定外の重カーブアウト）、本ADRの閾値を再検討する。Phase 9以降で表記4GB機のTIER_1混入が実機報告された場合、中点(5GiB)自体を見直す。

---

## §8. 実機受け入れ手順（A54・Phase 8最終確認そのもの）

本手順はPhase 8 C4「A54実機最終確認」の実体であり、本バグ修正はその前提ブロッカーの解消に相当する。

1. `adb devices`でGalaxy A54 5G実機の接続を確認する。
2. `adb shell cat /proc/meminfo` で`MemTotal`実測値を記録する（この値と`ActivityManager.MemoryInfo.totalMem`は近似するが完全一致は保証されないため、可能ならアプリ内ログでも`totalMem`実測値を別途記録する）。
3. 本修正を適用したアプリをA54実機へインストールし、設定画面を開く。「メモリ不足」の非対応文言が消え、AI有効化トグルが操作可能であることを確認しスクリーンショットを取得する。
4. AIトグルをONにし、Gemma4のダウンロード導線からモデルをダウンロードする。
5. 予定を1件選び実行画面まで進め、AI生成の行動文言（`display_text`由来の`title`）が描画されることを確認しスクリーンショットを取得する。
6. 3・5の結果を本計画書のStep 4完了記録（または後続の完了報告）へ証拠として残す。

---

## §9. コミット粒度

**1コミット**（Red→Green一体）: `DeviceCapabilityTest.kt`のテスト更新・新規ケース追加、`DeviceCapability.kt`の定数値変更＋KDoc是正、本計画書を同一コミットに含める。Red確認（テストのみ更新した状態で失敗実行）はコミット前のローカル検証手順とし、コミット自体は最終Green状態で1つ作成する。

---

## §10. A54実機受け入れ結果（2026-08-11実施・完了）

**実施環境**: Galaxy A54 5G au版（SCG21）・Android 16・日常使用状態（他アプリ常駐あり、テスト専用端末ではない）。接続はWSL2環境のためワイヤレスデバッグ（`adb pair`／`adb connect`）を使用（usbipd-win未導入のためUSB直結不可という環境制約による）。証拠スクリーンショットは`docs/evidence/screenshots/phase8/a54-*.png`に格納。

### 10.1 totalMem実測（§8手順2対応）

`adb shell cat /proc/meminfo`実測: `MemTotal=5,556,216kB`＝**5.30GiB**（`dumpsys meminfo`の「Total RAM: 5,556,216K」と一致）。

- 旧閾値6GiBのままなら5.30GiB<6GiBで段0（`TIER_0_UNSUPPORTED`）落ち＝**本バグの実機実証**。
- 新閾値5GiBでは5.30GiB≥5GiBのため**TIER_1_STANDARD通過（余裕約306MiB）**。
- ADR-0061で却下した5.5GiB案では5.30GiB<5.5GiBのため依然取りこぼす＝**却下判断の実測的裏付け**。
- ADR-0061再検討トリガー「`totalMem`実測値5GiB未満」には非該当。

### 10.2 設定画面・Gemma4ダウンロード（§8手順3・4対応）

- 設定画面: 「メモリ不足（6GB以上）」の非対応文言が消滅し、AI有効化トグルが操作可能。スクショ`a54-settings-after-fix.png`。
- Gemma4（既定モデル、2.59GB）をアプリ内ダウンロード導線から取得: 完走（実測約4分、Wi-Fi 14MB/s級）→SHA-256検証パス→「ダウンロード済み・検証済み」表示。実ファイルサイズ2,588,147,712B確認。スクショ`a54-model-installed.png`。

### 10.3 重要発見: 日常使用A54ではGemma4既定モデルが恒常的にBasicへフォールバックする

PlanReview画面初回表示はBasic文言のまま推論が発火しない。原因は`INSUFFICIENT_MEMORY`によるロード前availMem能動ガードの静的縮退（クラッシュなし、設計どおりの安全側動作）。

実測根拠:
- ガード要求量 ＝ `ModelCatalog.GEMMA_4_E2B_IT.defaultProfilePeakRamBytes`（2GiB）＋`MEMORY_SAFETY_MARGIN_BYTES`（512MB、ADR-0057）＝**2.5GiB**
- 実機availMem実測: 通常時2.01GiB／`kill-all`後2.31GiB／`force-stop`後2.29GiB／再起動後も2.23〜2.28GiBで頭打ち＝**いずれも2.5GiB未満**

**結論**: 日常使用状態（他アプリ常駐あり）の表記6GB機では、Gemma4既定モデルは実質常時Basicへ縮退する。これはPhase 9最優先課題（10.8参照）の実測根拠である。

### 10.4 Qwen 0.6BでのAI文言実機描画実証

Gemma4がガードで発火しないため、実機でのAI描画パイプライン自体の実証には別モデルへの切替が必要だった。**本切替は本番UIでは不可能な操作**である（`SettingsViewModel.kt` L56が`selectedModel`を`ModelCatalog.GEMMA_4_E2B_IT`に固定しており、モデル選択UI自体がS-6裁定によりスコープ外——既存のP7-C6申し送り事項がここで実際に表面化した）。そのため**テスト目的の手動操作**として`adb shell run-as`で`ai_preferences.xml`を直接編集し`selectedModelId=qwen3-0.6b-int4-block32`へ切替、モデル本体は開発機`build/models/`の検証済み同一SHAファイル（`e3e290...776cf`、344,437,808B）を`adb push`で端末へ設置した。

結果:
- PlanReview画面: 16:35ステップの文言が「出かける準備をする」（Basic）→「歯科検診を受ける場所を確認する」（AI差替）へ変化。タップから描画まで約30〜40秒（初回エンジンロード込み、CPU使用率93%実測）。
- Execution画面: 同じAI文言がカレントステップとして表示。
- 時刻・順序・構造は不変（§13不変条件の成立を実機で確認）。

スクショ: `a54-qwen-plan-t8.png`（Basic段階）→`a54-qwen-check2.png`（AI差替後）→`a54-execution-ai.png`／`a54-execution-ai-step2.png`（実行画面）。

### 10.5 PSS実測

推論後`TOTAL PSS`＝1,027,562kB≈1.00GiB（Qwen 0.6B）。カタログの`defaultProfilePeakRamBytes`（1.25GiB）の範囲内。

### 10.6 既知残存事項の再確認

実行画面にexact alarm許可バナーが表示される（第1弾残存事項⑤。本修正とは無関係の既知の未対応事項）。

### 10.7 受け入れ判定

**§8の目的（閾値是正の実機検証）は合格**。AI文言の実機描画は、既定のGemma4では10.3の理由により確認できず、Qwen 0.6Bへの手動切替により実証した（配線・§13不変条件そのものは実機で実証済み。Gemma4での確認は10.8のモデル自動選択対応後に持ち越し）。

**受け入れ試験終了時点で端末に残した状態**（次回操作者向け申し送り）: `selectedModelId=qwen3-0.6b-int4-block32`のまま／AI有効化ON／モデルファイル2つ（Gemma4・Qwen 0.6B）が端末内に残存／テスト用に操作した予定「歯科検診」17:00がユーザーの実カレンダーに残存。

### 10.8 Phase 9への申し送り

1. **空きRAM（availMem）ベースのモデル自動選択を最優先実装**する（Gemma4⇄Qwen 0.6Bなど）。10.3の実測（日常使用6GB機のavailMem 2.0〜2.3GiB＜Gemma4ガード要求2.5GiB）が根拠。
2. **`SettingsViewModel`の`selectedModel`Gemma4固定を解消**する（P7-C6申し送り事項の本実装。10.4で手動`run-as`編集を要した直接原因）。
3. Qwen 0.6Bの品質はGemma4より下限（P7-C8既知）だが、A54実機で文脈化そのものは機能することを確認した（「歯科検診を受ける場所を確認する」）。
