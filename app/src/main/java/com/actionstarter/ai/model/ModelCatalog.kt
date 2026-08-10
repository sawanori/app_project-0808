package com.actionstarter.ai.model

/**
 * F87実装（計画書§7.1・§5.3・§5.4・§8.6・§13 #24・§14 P7-C1）。配布モデルの定義台帳。
 *
 * **交換可能性（§17「モデル名を製品仕様として固定しない」・§5.3）**: [ALL]へエントリを
 * 追加するだけで新しいモデルへ対応できる構造とし、モデル名をコード各所へ直書きしない。
 *
 * **ライセンス制約（§13 #24）**: Apache-2.0以外のモデルをカタログへ追加する場合、
 * [ModelLicense]へ新しい値を追加したうえでNotice同梱・EULA組み込み等の追加義務
 * （計画書§5.2「Gemmaへ切り替える場合の差分」参照）を満たす実装を伴わせること。Phase 7時点
 * では[ModelLicense.APACHE_2_0]のみを扱う。
 */
object ModelCatalog {

    /**
     * 主推奨（計画書§0・§5.2・§5.3段1・U-4で最終確定待ち）。`litert-community/Qwen3-0.6B`の
     * `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`。[ModelCatalogEntry.sha256]／
     * [ModelCatalogEntry.sizeBytes]はP7-C0実測値（計画書§8.6・§14.1。開発者が自らDLし
     * sha256sumで計算した値をU-6の方針どおり正とする。HF側`x-linked-etag`と一致確認済みだが、
     * この一致は補助的傍証にとどめる）。
     *
     * [ModelCatalogEntry.peakRamBytes]はTECNO LJ9（Dimensity 8350・準ミッドレンジ）実測値
     * （計画書§5.2、CPU・mixed_int4条件）を採用する。**Galaxy Aクラス実機での値ではない**
     * （§17未確認事項・R-5）。P7-C8（実機プローブ）で確定するまでの暫定値。
     */
    val QWEN3_0_6B_INT4_BLOCK32 = ModelCatalogEntry(
        id = "qwen3-0.6b-int4-block32",
        displayName = "Qwen3-0.6B (INT4 block-32)",
        downloadUrl =
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/" +
                "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        sha256 = "e3e290109da4388d65a17510a0c66af91c8039f52d2c465868dbc43c09a776cf",
        sizeBytes = 344_437_808L,
        peakRamBytes = 2_890L * 1024 * 1024,
        contextLength = 4096,
        quantization = "dynamic-int4-block32",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false,
        // ADR-0057。P7-C5診断実測（probeAdapterThroughGateway_widerContextDiagnostic、
        // maxNumTokens=1024・peakRamBytesフィクスチャ=1.25GiBで実機3件とも実推論成功、
        // build/agent-logs/p7c5-e2e.log）で検証済みの値をそのまま本番値へ採用する。
        // peakRamBytes（フルコンテキスト4096実測=2,890MB）はコンテキストプロファイル非依存の
        // 単一値であり、実際に使う既定プロファイル（LiteRtLmLocalLanguageModel.
        // DEFAULT_MAX_NUM_TOKENS、P7-C5b時点で1024〜1500程度）の実要求量より過大にOOM事前ガード
        // （LocalAiGateway §8.6 #7）を判定させてしまう問題がP7-C5で発見された（ADR-0056決定6b）。
        defaultProfilePeakRamBytes = 1_342_177_280L
    )

    /**
     * P7-C8モデル比較用（計画書§11.3・§14 P7-C8、U-4）。`litert-community/Qwen3-1.7B`の
     * `Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm`。[sha256]／[sizeBytes]はP7-C8実測（実際に
     * ダウンロードしたファイルへ`sha256sum`を計算し、HF側`x-linked-etag`（補助的傍証）とも
     * 一致確認済み、U-6方針どおり開発者実測値を正とする）。`contextLength`はHFモデルカード記載の
     * この量子化バリアント自身の値（4096、[QWEN3_0_6B_INT4_BLOCK32]と同値）。
     *
     * [peakRamBytes]／[defaultProfilePeakRamBytes]はP7-C8実機（AVD、RAM 4096MB）プローブ実測値
     * （`ModelComparisonProbeTest.probeQwen17B_productionDefaults`、production defaults＝
     * `shotCount=2`・`maxNumTokens`自動算出、5シナリオ全件実行）。**実測の情報源は`ActivityManager.
     * getProcessMemoryInfo().totalPss`によるプロセス全体のピークPSS（実測1,945,677,824バイト、
     * `PssPeakSampler`）であり、本番`LiteRtLmLocalLanguageModel`が内蔵する`peakNativeHeapBytes`
     * （`Debug.getNativeHeapAllocatedSize()`ベース、本モデルでは実測712〜750MB程度）ではない**。
     * P7-C8で「LiteRT-LMがモデル重みをmmapで読み込むため、mallocアリーナ限定の
     * `peakNativeHeapBytes`は真の物理メモリ使用量を大きく過小評価する」ことが実機で判明した
     * （両指標の差が約1.2GB）。[QWEN3_0_6B_INT4_BLOCK32]の`defaultProfilePeakRamBytes`
     * （P7-C5診断実測）はこのPSS実測を伴わずに確定した値であるため、同じ過小評価リスクを
     * 抱えている可能性がある（本体タスク最終報告・ADR-0059「申し送り」参照。本サイクルでは
     * Qwen3-0.6Bの値自体は変更しない）。実測1,945,677,824バイトに安全マージンを載せて
     * 2.0GiB（2,147,483,648バイト）へ切り上げた値を正式採用する。[QWEN3_0_6B_INT4_BLOCK32]と
     * 同じく本フィールドは小コンテキスト・本番プロファイルでの実効ピークであり、フルコンテキスト
     * （ctx4096）を独立測定したものではない（P7-C8のスコープはP7-C5bと同一の小コンテキスト・
     * 本番プロファイル比較であり、フルコンテキスト実測はスコープ外。したがって[peakRamBytes]は
     * [defaultProfilePeakRamBytes]と同値を暫定採用する）。
     */
    val QWEN3_1_7B_INT4_BLOCK32 = ModelCatalogEntry(
        id = "qwen3-1.7b-int4-block32",
        displayName = "Qwen3-1.7B (INT4 block-32)",
        downloadUrl =
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/" +
                "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        sha256 = "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b",
        sizeBytes = 977_184_032L,
        peakRamBytes = 2_147_483_648L,
        contextLength = 4096,
        quantization = "dynamic-int4-block32",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false,
        // P7-C8実測（PSSピーク1,945,677,824バイト）+安全マージンで2.0GiBへ切り上げ。
        // build/agent-logs/p7c8-qwen17b-e2e.log参照。
        defaultProfilePeakRamBytes = 2_147_483_648L
    )

    /**
     * P7-C8モデル比較用（計画書§11.3・§14 P7-C8、U-4）。`litert-community/gemma-4-E2B-it-litert-lm`の
     * `gemma-4-E2B-it.litertlm`。[sha256]／[sizeBytes]はP7-C8実測（U-6方針、HF側`x-linked-etag`とも
     * 一致確認済み）。`contextLength`はHFモデルカード記載値（「up to 32k」を採用。実行時に
     * 実際へ渡す`maxNumTokens`は[PlanPromptBuilder.CONTEXT_LENGTH_CEILING]＝4096で別途clampされる
     * ため、本フィールドはモデル自身の公称上限の記録用であり、Phase 7の実行時コンテキスト長を
     * 拡張するものではない）。`quantization`はHFモデルカード記載の「2bit/4bit/8bit混在」構成
     * （Gemma 3n系のPer-Layer Embeddings類似の設計と見られ、ファイルサイズ2.59GBがそのまま
     * 常駐RAMになるとは限らない。[peakRamBytes]のKDoc参照）。
     *
     * [peakRamBytes]／[defaultProfilePeakRamBytes]はP7-C8実機（AVD、RAM 4096MB、`MemAvailable`
     * 実測約2.6〜2.9GB）プローブ実測値。**AVD 4096MBでの実推論に成功した**（本体タスク指示の
     * 懸念「4GBでOOMの可能性」は本モデルでは的中しなかった）——production defaults
     * （`shotCount=2`）・reduced context（`shotCount=1`）のいずれも5シナリオ中Fallback/成功の
     * 差はあれどOOMやプロセスクラッシュは一切発生せず、`PssPeakSampler`実測ピークPSSは
     * production defaultsで1,980,168,192バイト、reduced contextで1,972,925,440バイトと
     * 両条件でほぼ同一（約1.84GiB。KV-cache/プロンプト長の差より常駐重みページの方が支配的で
     * あることを示唆）。HFモデルカードが公表するCPUバックエンド実測（Samsung S26 Ultra、
     * ピークメモリ約1,733MB）はAVDと異なる条件下の値だが、いずれも「ファイルサイズ2.59GBが
     * そのまま常駐RAMにはならない」というquantization（KDoc「quantization」参照）の性質と
     * 整合する結果になった。**「公式8GB宣言」は本体タスク調査時点の前提だったが、HFモデルカード
     * （`litert-community/gemma-4-E2B-it-litert-lm`）自体には8GBという最小RAM要件の記載は
     * 見つからなかった**（本体タスク最終報告「Gemma4-E2Bの8GB言及可否」参照。カード記載の実測
     * ピークメモリは607MB〜3,681MB〔デバイス依存〕であり、8GBはむしろ乖離が大きい側の主張と
     * なる）。値はproduction defaults実測1,980,168,192バイトに安全マージンを載せて2.0GiB
     * （2,147,483,648バイト）へ切り上げて採用する。[QWEN3_1_7B_INT4_BLOCK32]と同じくフル
     * コンテキスト（ctx32768、本モデルの公称上限）の独立測定はスコープ外であり、
     * [peakRamBytes]は[defaultProfilePeakRamBytes]と同値を暫定採用する。
     */
    val GEMMA_4_E2B_IT = ModelCatalogEntry(
        id = "gemma-4-e2b-it",
        displayName = "Gemma 4 E2B-it (mixed 2/4/8-bit)",
        downloadUrl =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/" +
                "gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        sizeBytes = 2_588_147_712L,
        peakRamBytes = 2_147_483_648L,
        contextLength = 32_768,
        quantization = "mixed-2_4_8bit",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false,
        // P7-C8実測（production defaultsのPSSピーク1,980,168,192バイト）+安全マージンで
        // 2.0GiBへ切り上げ。build/agent-logs/p7c8-gemma4e2b-e2e.log参照。
        defaultProfilePeakRamBytes = 2_147_483_648L
    )

    /**
     * カタログ全体。§5.3段2以降・Gemma系等を追加する際はここへ足す（U-4確定後）。
     *
     * **順序の意図（P7-C8で2件追加・Phase 8 C3で前提更新、Gemini G1 CRITICAL④／B4確定）**:
     * [QWEN3_0_6B_INT4_BLOCK32]を先頭に置く並び自体はP7-C8時点のまま変更していない。
     * ただし「Qwen3-0.6Bを先頭に置く限り本番の既定選択は変わらない」という**P7-C8時点の前提は
     * Phase 8で崩れている**——[ModelStorageImpl.installedEntry]は
     * `AiPreferences.selectedModelId`（P7-C6でGemma4-E2Bが既定値として確定済み）が設定済みかつ
     * 対応ファイルが実在する場合、その導入済みモデルをcatalog順走査より**最優先**で返すように
     * 改修された（[ModelStorageImpl.installedEntry]のKDoc参照）。したがって、端末にQwenと
     * Gemma4の両方が導入されている状態でも、`selectedModelId=gemma-4-e2b-it`であれば
     * `installedEntry()`はGemma4を返す。[ALL]内のcatalog順（Qwen先頭）は
     * `selectedModelId`が未設定、または`selectedModelId`に対応するファイルが未DLの場合の
     * **フォールバック順序としてのみ**意味を持つ（T-P8-24が回帰ロックする）。
     * [QWEN3_1_7B_INT4_BLOCK32]／[GEMMA_4_E2B_IT]はP7-C8比較probe（`ModelComparisonProbeTest`）が
     * `ModelStorageImpl(context, catalog = listOf(entry))`で単一エントリのcatalogへ差し替えて
     * 使うため、[ALL]内の並び自体が比較probeの動作に影響することはない。
     */
    val ALL: List<ModelCatalogEntry> = listOf(QWEN3_0_6B_INT4_BLOCK32, QWEN3_1_7B_INT4_BLOCK32, GEMMA_4_E2B_IT)

    /** [id]に一致する[ModelCatalogEntry]を返す。存在しなければ`null`。 */
    fun findById(id: String): ModelCatalogEntry? = ALL.firstOrNull { it.id == id }
}

/**
 * 1モデルの配布定義。仕様§17「最低評価項目」のうちPhase 7の判定・DL・検証に直接必要な
 * フィールドのみを持つ（日本語品質等の評価軸は計画書§5.2の比較表に留め、カタログには
 * 持たせない）。
 *
 * @param id [com.actionstarter.ai.AiPreferences.selectedModelId]と対応させる一意キー。
 * @param sha256 [ModelVerifier]が検証に用いる正の値（U-6: 開発者自身が計算した値を焼き込む。
 *   HF側ハッシュを無条件には信頼しない）。
 * @param peakRamBytes 実測ピークRAM（近縁機、計画書§5.3表）。**フルコンテキスト
 *   （[contextLength]）実測値であり、コンテキストプロファイル非依存の単一値**（P7-C5実測で
 *   発見された制約、ADR-0056決定6b・ADR-0057）。§5.3の段判定など「モデルを最大構成で使った
 *   場合の参考値」としての用途に残す。**OOM事前ガードには使わない**（[defaultProfilePeakRamBytes]
 *   参照）。
 * @param defaultProfilePeakRamBytes ADR-0057新設。**実際に使う既定プロファイル
 *   （[com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]の既定`maxNumTokens`、
 *   フルコンテキストより十分小さい）での実効ピークRAM**。
 *   [com.actionstarter.ai.LocalAiGateway]のOOM事前ガード（§8.6 #7）が安全マージンを加えて
 *   参照するのはこちらであり、[peakRamBytes]ではない——フルコンテキスト値をそのまま使うと
 *   小コンテキスト・本番プロファイルの実要求量に対してガードが過大判定してしまう問題を
 *   P7-C5が実機で発見したため（ADR-0056決定6b）。**既定値は[peakRamBytes]と同値**（未指定の
 *   既存fixtureエントリの挙動を変えない後方互換性）。実際に値を引き下げるのは
 *   [ModelCatalog.QWEN3_0_6B_INT4_BLOCK32]のみであり、P7-C5診断実測（`maxNumTokens=1024`で
 *   実推論成功）で検証済みの値を焼き込む。
 * @param requiresNoticeFile Apache-2.0以外のライセンスでNoticeファイル同梱が必要か（§13 #24）。
 */
data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val peakRamBytes: Long,
    val contextLength: Int,
    val quantization: String,
    val license: ModelLicense,
    val requiresNoticeFile: Boolean,
    val defaultProfilePeakRamBytes: Long = peakRamBytes
)

/**
 * §13 #24。Phase 7は[APACHE_2_0]のみを扱う（P7-C8時点のカタログ3エントリ〔Qwen3-0.6B・
 * Qwen3-1.7B・Gemma4-E2B〕はいずれもApache-2.0ライセンス、HFモデルカードで確認済み）。
 * Gemma 3／3n等Gemma Terms下のモデルを将来追加する場合はここへ値を追加する（計画書§5.2
 * 「Gemmaへ切り替える場合の差分」の追加義務を実装に伴わせること）。
 */
enum class ModelLicense {
    APACHE_2_0
}
