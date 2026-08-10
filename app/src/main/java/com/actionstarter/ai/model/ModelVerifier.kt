package com.actionstarter.ai.model

import java.io.File
import java.security.MessageDigest

/**
 * F89契約（計画書§7.1・§18・§8.6 #5・#12・§14 P7-C1／P7契約確定）。SHA-256・サイズ照合の
 * interface。**検証通過前は絶対にロードしない**（信頼境界。§18）。
 *
 * **interface化（Fable 5裁定5、2026-08-10、ADR-0048）**: 具象クラスから本interfaceへ変更し、
 * 実装は[ModelVerifierImpl]（同ファイル）へ分離した（[DeviceCapability]のKDoc「interface化」
 * 参照、理由は同一）。
 *
 * **二段階の検証頻度（§8.6 #12・Gemini G1 CRITICAL #2）**: DL完了直後の検証（§8.6 #5、
 * `.part`→正式名リネーム前の1回限り）と、モデルロード直前の再検証（§8.6 #12、毎回サイズ照合＋
 * プロセス初回ロード前のみSHA-256再計算・以後はプロセス内キャッシュ）の2種類を担う。両者は
 * 独立して必要（#5は改竄されていない配布物を保存する防御、#12は保存後の劣化・改変を検知する
 * 防御）。このキャッシュ状態は[ModelVerifierImpl]のインスタンスに保持するため、実装は状態を
 * 持たない`object`ではなく`class`とする。
 *
 * 契約scaffold（TDD厳守）時点では[ModelVerifierImpl.verify]は宣言のみであり、実装本体は
 * P7-C4で行う（T-MDL-9〜11）。
 */
interface ModelVerifier {
    /** [file]を[expected]（サイズ・SHA-256）と照合する。 */
    fun verify(file: File, expected: ModelCatalogEntry): ModelVerificationResult
}

/** [ModelVerifier.verify]の戻り値。 */
sealed interface ModelVerificationResult {
    data object Valid : ModelVerificationResult

    data class Invalid(val reason: ModelVerificationFailureReason) : ModelVerificationResult
}

/** [ModelVerificationResult.Invalid]の理由（T-MDL-10・T-MDL-11）。 */
enum class ModelVerificationFailureReason {
    SIZE_MISMATCH,
    HASH_MISMATCH
}

/**
 * [ModelVerifier]の実装（Fable 5裁定5、ADR-0048）。SHA-256計算結果をインスタンス状態として
 * キャッシュする（クラスKDoc「二段階の検証頻度」参照）。
 *
 * **P7-C3実装済み（Green）**: サイズ照合を先に行い、不一致なら[MessageDigest]によるSHA-256計算
 * （相対的に高コスト）を一切実行せず即座に[ModelVerificationFailureReason.SIZE_MISMATCH]を返す
 * （T-MDL-9〜11、KDoc記載の順序保証）。サイズが一致した場合のみハッシュを計算し照合する。
 * **インスタンス単位のキャッシュ自体（「プロセス初回ロード前のみSHA-256再計算」§8.6 #12）は
 * P7-C5で[LocalAiGateway]から呼び出す際の設計として残置**（本クラス単体は毎回計算する契約の
 * まま。呼び出し側が同一[ModelVerifierImpl]インスタンスを再利用しキャッシュするかを決める）。
 */
class ModelVerifierImpl : ModelVerifier {

    override fun verify(file: File, expected: ModelCatalogEntry): ModelVerificationResult {
        if (file.length() != expected.sizeBytes) {
            return ModelVerificationResult.Invalid(ModelVerificationFailureReason.SIZE_MISMATCH)
        }

        val actualSha256 = sha256Hex(file)
        return if (actualSha256.equals(expected.sha256, ignoreCase = true)) {
            ModelVerificationResult.Valid
        } else {
            ModelVerificationResult.Invalid(ModelVerificationFailureReason.HASH_MISMATCH)
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_SIZE_BYTES)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val DIGEST_BUFFER_SIZE_BYTES: Int = 8192
    }
}
