package com.difft.android.chat.voice

import com.github.TempTalkOrg.audio_pipeline.AudioModule
import com.github.TempTalkOrg.audio_pipeline.PipelineTapConfig
import com.github.TempTalkOrg.audio_pipeline.SoundTouchConfig
import java.io.File

/**
 * App-friendly voice preset name. Maps to the SDK's underlying SoundTouch
 * preset — kept name-aligned with the RTC call voice changer so the same
 * "higher" / "deeper" buttons across call and voice message sound identical.
 *
 *   HIGHER → SDK "goddess" (+4 semitones)
 *   DEEPER → SDK "uncle"   (-4 semitones)
 *
 * Closed enum on purpose: adding a preset should require touching both the
 * call and voice-message UIs at the same time so they stay aligned.
 */
enum class VoiceMessageEffect(internal val sdkPreset: String) {
    HIGHER("goddess"),
    DEEPER("uncle"),
}

/**
 * One parallel candidate the [DualCandidateVoiceRecorder] should produce.
 *
 * Each recipe becomes a tap of the underlying multi-tap audio pipeline
 * (shared denoise inference, per-tap voice changer). Mirrors the JS SDK's
 * `VoiceMessageRecipe` shape so cross-platform integration code stays
 * familiar.
 */
data class VoiceMessageRecipe(
    /** UI uses this to find the right candidate file after recording. */
    val id: String,
    /** Take the shared denoise output as the tap base. */
    val denoise: Boolean = false,
    /** Apply a voice changer preset on top of denoise / raw. `null` skips it. */
    val effect: VoiceMessageEffect? = null,
) {
    /** Convert to the SDK tap config the pipeline is built from. */
    internal fun toTapConfig(): PipelineTapConfig {
        val st = effect?.let { SoundTouchConfig.PRESETS[it.sdkPreset] }
        return PipelineTapConfig(denoise = denoise, soundTouch = st)
    }
}

/**
 * A produced candidate file. `file == null` means that recipe failed
 * (pipeline init failure, encoder failure, etc.). Callers should fall
 * back gracefully — e.g. pick the first non-null candidate, or surface
 * "processing failed" to the user.
 */
data class VoiceMessageRecordingCandidate(
    val recipe: VoiceMessageRecipe,
    val file: File?,
    val durationMs: Long,
)

object VoiceMessageRecipes {
    /**
     * Default 2-candidate set. Matches the JS SDK demo / chative-desktop
     * default: one denoised candidate plus one denoised + pitch-shifted
     * candidate, with `HIGHER` (goddess +4) as the default effect.
     *
     * If the UI offers a preset toggle later, swap the second recipe's
     * `effect` field and keep the id format `denoised+<effect>` so the
     * preview / picker can key off the id.
     */
    val DEFAULT: List<VoiceMessageRecipe> = listOf(
        VoiceMessageRecipe(id = "denoised", denoise = true),
        VoiceMessageRecipe(
            id = "denoised+higher",
            denoise = true,
            effect = VoiceMessageEffect.HIGHER,
        ),
    )

    /**
     * Build a 2-candidate recipe set based on the user's voice changer
     * preference. Maps the call-module SDK key to the matching
     * [VoiceMessageEffect]:
     *
     * - `"uncle"` → [VoiceMessageEffect.DEEPER]
     * - anything else (including `"original"` and `"goddess"`) → [VoiceMessageEffect.HIGHER]
     *
     * The first recipe is always plain denoised (no effect) — used when
     * the user releases without sliding to "Add effect". The second
     * recipe carries the effect — used when the user slides to "Add effect".
     */
    fun buildRecipes(voicePresetSdkKey: String?): List<VoiceMessageRecipe> {
        val effect = when (voicePresetSdkKey) {
            "uncle" -> VoiceMessageEffect.DEEPER
            else -> VoiceMessageEffect.HIGHER
        }
        return listOf(
            VoiceMessageRecipe(id = "denoised", denoise = true),
            VoiceMessageRecipe(
                id = "denoised+${effect.sdkPreset}",
                denoise = true,
                effect = effect,
            ),
        )
    }

    /**
     * Default denoise backend used when caller doesn't specify.
     *
     * DeepFilterNet ships with a stronger model than RNNoise and matches the
     * default JS / chative-desktop voice-message build. Cold-start cost
     * (`df_create_default` on the ~16 MB model) is ~100–600 ms on Pixel-class
     * devices and is hidden behind the connect-first-then-init pattern in
     * [DualCandidateVoiceRecorder] — mic capture starts immediately and
     * pre-init chunks are queued, so the user-visible latency is unchanged.
     */
    val DEFAULT_DENOISE_MODEL: AudioModule = AudioModule.DEEP_FILTER_NET
}
