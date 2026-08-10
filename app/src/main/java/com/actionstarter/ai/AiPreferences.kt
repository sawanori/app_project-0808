package com.actionstarter.ai

import android.content.SharedPreferences
import com.actionstarter.ai.model.ModelCatalog

/**
 * F92契約（計画書§6・§7.1、仕様§19「AI OFFが既定」、§14 P7-C1／P7契約確定）。AI ON/OFF
 * トグルと選択モデルID（[com.actionstarter.ai.model.ModelCatalogEntry.id]）の永続化interface。
 *
 * **interface化（Fable 5裁定5、2026-08-10、ADR-0048）**: 具象クラスから本interfaceへ変更し、
 * 実装は[AiPreferencesImpl]（同ファイル）へ分離した
 * （[com.actionstarter.ai.model.DeviceCapability]のKDoc「interface化」参照、理由は同一）。
 *
 * **既定OFF（T-SET-1）**: [aiEnabled]は未書き込み時（初回起動）は必ず[DEFAULT_AI_ENABLED]
 * （`false`）を返す契約とする。§19「Local AIはEnhancementでありSingle Point of Failureに
 * しない」の直接の実装根拠であり、[LocalAiGateway]の§8.6 #10チェック
 * （`AiFallbackReason.AI_DISABLED`、T-GW-2）が依存する。この既定値契約はSharedPreferences実装
 * ([AiPreferencesImpl])に限らず、将来別の永続化手段（例: DataStore）へ差し替える場合も
 * interfaceの一部として維持しなければならないため、本interfaceの companion に置く。
 *
 * 契約scaffold（TDD厳守）時点では[AiPreferencesImpl]の各プロパティは宣言のみであり、
 * 実装本体はP7-C6（Green: settings）で行う（T-SET-1〜2、T-GW-2）。
 */
interface AiPreferences {
    /** AI機能が有効か。既定は[DEFAULT_AI_ENABLED]（`false`、T-SET-1）。 */
    var aiEnabled: Boolean

    /**
     * 選択中のモデルID（[com.actionstarter.ai.model.ModelCatalogEntry.id]）。未書き込み時
     * （初回起動）は[DEFAULT_SELECTED_MODEL_ID]を返す契約とする。
     *
     * **既定値変更（P7-C6、ユーザー確定「既定モデル=Gemma 4 E2B」）**: 当初のP7-C1/C3契約
     * scaffoldでは未選択時`null`だったが、Settings画面（F97）がDL対象モデルを常に
     * [DEFAULT_SELECTED_MODEL_ID]（[com.actionstarter.ai.model.ModelCatalog.GEMMA_4_E2B_IT]の
     * id）に固定するP7-C6の要件に伴い、`null`ではなくこの既定値を返す契約へ変更した。
     * ただしAI有効化には別途DL済み＋検証済みが前提であり（[com.actionstarter.ai.model.
     * ModelStorage.installedEntry]が非nullであること）、本プロパティの既定値だけでは
     * AIが即座に使えるようにはならない（[com.actionstarter.ai.LocalAiGateway]の§8.6 #11
     * `MODEL_NOT_INSTALLED`ガードが引き続き働く）。
     */
    var selectedModelId: String?

    companion object {
        /** §19「AI OFFが既定」。実装に依らずinterface契約として維持する。 */
        const val DEFAULT_AI_ENABLED: Boolean = false

        /**
         * P7-C6（ユーザー確定、2026-08-10）。[selectedModelId]の既定値
         * ＝[ModelCatalog.GEMMA_4_E2B_IT]のid。`const val`にできない理由: プロパティアクセスは
         * コンパイル時定数ではないため`val`とする（実行時には1回だけ解決される）。
         */
        val DEFAULT_SELECTED_MODEL_ID: String = ModelCatalog.GEMMA_4_E2B_IT.id
    }
}

/**
 * [AiPreferences]の実装（Fable 5裁定5、ADR-0048）。
 * [com.actionstarter.persistence.SharedPreferencesExecutionScheduleStore]と同型の
 * SharedPreferences実装（新規依存を追加しない）。[preferences]は呼び出し側
 * （[com.actionstarter.di.AppContainer]）が
 * `context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)`で用意済みのインスタンスを渡す
 * （既存プロパティ`notificationService`と同じ配線パターンの踏襲）。
 *
 * **P7-C3での最小実装（判断・報告事項）**: 本来F92はP7-C6（Green: settings）の担当だが、
 * [LocalAiGateway]の§8.6 #10ガード（`preferences.aiEnabled`）はGateway実装が呼び出す
 * 唯一のエントリポイントであり、これがTODO()のままでは`LocalAiGatewayTest`の全ケース
 * （T-GW-1〜20）が本プロパティ経由で無条件に`NotImplementedError`となりGreen化できない
 * （AI ONを期待するテストもAI OFFを期待するテストも等しく落ちる）。本体はTODOコメントが
 * 指定する実装をそのまま一字一句反映した曖昧さのないSharedPreferencesアクセサであり、
 * 設計判断を伴わないため、[ContentSanityChecker]のGatewayへの配線と同じ「Greenに必要な
 * 最小実装」としてP7-C3の範囲内で行った。
 */
class AiPreferencesImpl(private val preferences: SharedPreferences) : AiPreferences {

    override var aiEnabled: Boolean
        get() = preferences.getBoolean(KEY_AI_ENABLED, AiPreferences.DEFAULT_AI_ENABLED)
        set(value) {
            preferences.edit().putBoolean(KEY_AI_ENABLED, value).apply()
        }

    override var selectedModelId: String?
        // P7-C6: 未書き込み時はAiPreferences.DEFAULT_SELECTED_MODEL_ID（Gemma 4 E2Bのid）を返す
        // （interfaceのKDoc「既定値変更」参照、ユーザー確定の既定モデル）。
        get() = preferences.getString(KEY_SELECTED_MODEL_ID, AiPreferences.DEFAULT_SELECTED_MODEL_ID)
        set(value) {
            preferences.edit().putString(KEY_SELECTED_MODEL_ID, value).apply()
        }

    companion object {
        /** [com.actionstarter.di.AppContainer]が`context.getSharedPreferences`へ渡すファイル名。 */
        const val PREFS_NAME: String = "ai_preferences"

        const val KEY_AI_ENABLED: String = "ai_enabled"
        const val KEY_SELECTED_MODEL_ID: String = "selected_model_id"
    }
}
