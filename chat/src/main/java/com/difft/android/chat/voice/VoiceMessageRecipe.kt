package com.difft.android.chat.voice

import com.github.TempTalkOrg.audio_pipeline.AudioModule
import com.github.TempTalkOrg.audio_pipeline.PipelineTapConfig
import com.github.TempTalkOrg.audio_pipeline.SoundTouchConfig
import java.io.File












enum class VoiceMessageEffect(internal val sdkPreset: String) {
    HIGHER("goddess"),
    DEEPER("uncle"),
}









data class VoiceMessageRecipe(
    
    val id: String,
    
    val denoise: Boolean = false,
    
    val effect: VoiceMessageEffect? = null,
) {
    
    internal fun toTapConfig(): PipelineTapConfig {
        val st = effect?.let { SoundTouchConfig.PRESETS[it.sdkPreset] }
        return PipelineTapConfig(denoise = denoise, soundTouch = st)
    }
}







data class VoiceMessageRecordingCandidate(
    val recipe: VoiceMessageRecipe,
    val file: File?,
    val durationMs: Long,
)

object VoiceMessageRecipes {
    








    val DEFAULT: List<VoiceMessageRecipe> = listOf(
        VoiceMessageRecipe(id = "denoised", denoise = true),
        VoiceMessageRecipe(
            id = "denoised+higher",
            denoise = true,
            effect = VoiceMessageEffect.HIGHER,
        ),
    )

    











    fun buildRandomRecipes(): List<VoiceMessageRecipe> {
        val effect = VoiceMessageEffect.entries.random()
        return listOf(
            VoiceMessageRecipe(id = "denoised", denoise = true),
            VoiceMessageRecipe(
                id = "denoised+${effect.sdkPreset}",
                denoise = true,
                effect = effect,
            ),
        )
    }

    









    val DEFAULT_DENOISE_MODEL: AudioModule = AudioModule.DEEP_FILTER_NET
}
