package com.difft.android.chat.translate

import com.difft.android.chat.translate.backend.TranslateLanguageCodes

/**
 * The two auto-translate targets a conversation can be set to.
 *
 * [setting] is the value persisted in `RoomModel.translateSetting` (0 means off and therefore has
 * no enum constant); [code] is the BCP-47 tag a translate backend uses for the same language.
 */
enum class TranslateLang(val setting: Int, val code: String) {
    ZH(1, TranslateLanguageCodes.ZH),
    EN(2, TranslateLanguageCodes.EN);

    companion object {
        /** @return null for 0 (off) and for any value written by a newer build. */
        fun fromSetting(value: Int): TranslateLang? = entries.firstOrNull { it.setting == value }
    }
}

/**
 * A translation model the backend needs on the device, identified by its BCP-47 code.
 *
 * Deliberately a different type from [TranslateLang]: a target language is what the user picked,
 * a model is what has to be on the device, and the two are not one-to-one
 * (see `TranslateModelStore.requiredModels`).
 */
@JvmInline
value class ModelId(val code: String)

/**
 * AUTO is the per-conversation setting acting on incoming messages; MANUAL is an explicit user
 * action (long-press translate, text-selection translate). The two differ in what they are allowed
 * to do: MANUAL may translate from any detected source language, may pull a model, treats
 * undetermined text as English and shows same-language text as-is (the user asked for a result);
 * AUTO is restricted to zh/en, skips same-language and undetermined text, and never downloads on
 * the message path.
 */
enum class TranslateMode { AUTO, MANUAL }

/** Outcome of a single translation attempt. */
sealed interface TranslateResult {
    data class Translated(val text: String, val sourceCode: String) : TranslateResult

    /** AUTO only: source and target are the same language, nothing to show. MANUAL returns [Translated]. */
    data object SameLanguage : TranslateResult

    /** The text itself is out of scope; [reason] says why. */
    data class Unsupported(val reason: Reason) : TranslateResult

    /** [missing] — the target's models or the detected source's — are not on the device yet. */
    data class ModelNotReady(val missing: Set<ModelId>) : TranslateResult

    data class Failed(val cause: Throwable) : TranslateResult

    enum class Reason { EMPTY, TOO_LONG, LANGUAGE_NOT_SUPPORTED }
}
