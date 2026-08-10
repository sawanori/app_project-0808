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

    /** カタログ全体。§5.3段2以降・Gemma系等を追加する際はここへ足す（U-4確定後）。 */
    val ALL: List<ModelCatalogEntry> = listOf(QWEN3_0_6B_INT4_BLOCK32)

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
 * §13 #24。Phase 7は[APACHE_2_0]のみを扱う（唯一のカタログエントリがQwen3、Apache-2.0）。
 * Gemma 3／3n等Gemma Terms下のモデルを将来追加する場合はここへ値を追加する（計画書§5.2
 * 「Gemmaへ切り替える場合の差分」の追加義務を実装に伴わせること）。
 */
enum class ModelLicense {
    APACHE_2_0
}
