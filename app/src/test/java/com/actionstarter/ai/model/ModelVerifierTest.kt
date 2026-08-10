package com.actionstarter.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * P7-C2（計画書§12.4・T-MDL-9〜11相当・F89）。[ModelVerifier]の失敗テスト（Red）。
 *
 * E1（純JVM）。[ModelVerifierImpl]は`android.*`を一切importしないため（`ModelVerifier.kt`実測、
 * `java.io.File`のみ）、Robolectricを必要としない。[ModelVerifierImpl.verify]の本体は
 * `TODO()`のため、以下は全件`NotImplementedError`によりRedになるのが正しい。
 *
 * **P7契約確定での更新（Fable 5裁定5、2026-08-10、ADR-0048）**: [ModelVerifier]は具象クラス
 * からinterfaceへ変更され、実装が[ModelVerifierImpl]へ分離された。本ファイルは
 * `ModelVerifier()`という construction 呼び出しを`ModelVerifierImpl()`へ置き換えた以外は
 * テストの意図・assertionを変更していない。
 *
 * §12.4の元表ではT-MDL-9〜11は`ModelDownloader`と同じ「モデル管理」節に属するが、本タスクの
 * 対象範囲（ModelVerifierのSHA-256計算ロジック）に明示されているため、T-MDL-9〜11相当の
 * 3ケースに加え、`ModelVerifier.verify`のKDoc自身が明記する「サイズ照合→不一致なら
 * HASH計算前にSIZE_MISMATCH」という順序保証を検証する1ケースを追加した。
 *
 * `File`・`ModelCatalogEntry`はいずれも呼び出し側が直接構築して渡せるため、本ファイルは
 * `ModelStorage`の内部ファイル配置規約（P7-C4で確定）に一切依存せず、閉じた形で検証できる。
 */
class ModelVerifierTest {

    private fun tempFileWithContent(content: ByteArray): File {
        val file = File.createTempFile("model-verifier-test", ".bin")
        file.deleteOnExit()
        file.writeBytes(content)
        return file
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entryFor(content: ByteArray, sha256Override: String? = null, sizeOverride: Long? = null): ModelCatalogEntry =
        ModelCatalogEntry(
            id = "test-model",
            displayName = "Test Model",
            downloadUrl = "https://example.invalid/test-model.litertlm",
            sha256 = sha256Override ?: sha256Hex(content),
            sizeBytes = sizeOverride ?: content.size.toLong(),
            peakRamBytes = 1L,
            contextLength = 1,
            quantization = "test",
            license = ModelLicense.APACHE_2_0,
            requiresNoticeFile = false
        )

    // T-MDL-9相当: 正常系 - SHA-256照合・サイズ照合とも一致で合格
    @Test
    fun matchingSizeAndHash_isValid() {
        val content = "sample-model-bytes".toByteArray()
        val entry = entryFor(content)

        val result = ModelVerifierImpl().verify(tempFileWithContent(content), entry)

        assertTrue("サイズ・ハッシュとも一致するのに合格しませんでした: $result", result is ModelVerificationResult.Valid)
    }

    // T-MDL-10相当: 異常系 - SHA-256不一致 → 不合格（理由はHASH_MISMATCH）
    @Test
    fun hashMismatch_isInvalidWithHashMismatchReason() {
        val content = "sample-model-bytes".toByteArray()
        val entry = entryFor(content, sha256Override = "0".repeat(64))

        val result = ModelVerifierImpl().verify(tempFileWithContent(content), entry)

        assertTrue(result is ModelVerificationResult.Invalid)
        assertEquals(
            ModelVerificationFailureReason.HASH_MISMATCH,
            (result as ModelVerificationResult.Invalid).reason
        )
    }

    // T-MDL-11相当: 異常系 - サイズ一致・ハッシュ不一致（改竄想定）→ 不合格
    @Test
    fun sizeMatchesButHashDiffers_tamperedContent_isInvalid() {
        val originalContent = "AAAAAAAAAAAAAAAAAAAA".toByteArray() // 20 bytes
        val tamperedSameSizeContent = "BBBBBBBBBBBBBBBBBBBB".toByteArray() // 20 bytes・別内容
        val entry = entryFor(originalContent) // sha256はoriginalContent基準

        val result = ModelVerifierImpl().verify(tempFileWithContent(tamperedSameSizeContent), entry)

        assertTrue(
            "サイズが一致してもハッシュが異なる改竄ファイルは不合格になるべきです",
            result is ModelVerificationResult.Invalid
        )
    }

    // 追加: サイズ不一致 → HASH計算前にSIZE_MISMATCH（ModelVerifier.verifyのKDoc記載どおりの順序）
    @Test
    fun sizeMismatch_isInvalidWithSizeMismatchReason() {
        val content = "short".toByteArray()
        val entry = entryFor(content, sizeOverride = content.size + 100L)

        val result = ModelVerifierImpl().verify(tempFileWithContent(content), entry)

        assertTrue(result is ModelVerificationResult.Invalid)
        assertEquals(
            ModelVerificationFailureReason.SIZE_MISMATCH,
            (result as ModelVerificationResult.Invalid).reason
        )
    }
}
