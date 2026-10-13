package com.difft.android.chat.ui.textpreview

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.chat.R
import com.difft.android.chat.databinding.LayoutTranslateBottomSheetContentBinding
import com.difft.android.chat.translate.TranslateLang
import com.difft.android.chat.translate.TranslateMode
import com.difft.android.chat.translate.TranslateQueue
import com.difft.android.chat.translate.TranslateResult
import com.difft.android.chat.translate.submitWaitingForPacks
import com.difft.android.chat.translate.model.TranslateModelStore
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Bottom sheet dialog for displaying translation results.
 * Shows original text (max 2 lines) and translated text with expand/collapse support.
 */
@AndroidEntryPoint
class TranslateBottomSheetFragment : BaseBottomSheetDialogFragment() {

    @Inject
    lateinit var translateQueue: TranslateQueue

    @Inject
    lateinit var modelStore: TranslateModelStore

    private var _binding: LayoutTranslateBottomSheetContentBinding? = null
    private val binding get() = _binding!!

    private var originalText: String = ""
    private lateinit var targetLanguage: TranslateLang
    private lateinit var alternativeLanguage: TranslateLang
    private var alternativeLanguageName: String = ""

    companion object {
        private const val TAG = "TranslateBottomSheet"
        private const val ARG_ORIGINAL_TEXT = "arg_original_text"

        private const val OWNER = "text-preview"

        fun show(activity: FragmentActivity, text: String) {
            val fragment = TranslateBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ORIGINAL_TEXT, text)
                }
            }
            fragment.show(activity.supportFragmentManager, TAG)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalText = arguments?.getString(ARG_ORIGINAL_TEXT) ?: ""

        // Determine target language based on app language setting
        // First translation goes to app language, label shows alternative for switching
        val appLocale = LanguageUtils.getLanguage(requireContext())
        when (appLocale.language) {
            Locale.CHINA.language, "zh" -> {
                // App is Chinese: translate to Chinese, show "Translate to English" as switch option
                targetLanguage = TranslateLang.ZH
                alternativeLanguage = TranslateLang.EN
                alternativeLanguageName = getString(R.string.language_english)
            }
            else -> {
                // App is English: translate to English, show "Translate to Chinese" as switch option
                targetLanguage = TranslateLang.EN
                alternativeLanguage = TranslateLang.ZH
                alternativeLanguageName = getString(R.string.language_chinese)
            }
        }
    }

    override fun getContentLayoutResId(): Int = R.layout.layout_translate_bottom_sheet_content

    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = LayoutTranslateBottomSheetContentBinding.bind(view)
        setupUI()
        startTranslation()
    }

    private fun setupUI() {
        // Display original text (max 2 lines with ellipsis)
        binding.tvOriginalText.text = originalText

        // Set translate label to show alternative language (switch option)
        updateTranslateLabel()

        // Click to switch translation language
        binding.tvTranslateLabel.setOnClickListener {
            switchLanguage()
        }

        // Initial state: show loading
        showLoading()
    }

    private fun updateTranslateLabel() {
        binding.tvTranslateLabel.text = getString(R.string.chat_translate_to_language, alternativeLanguageName)
    }

    private fun switchLanguage() {
        // Swap target and alternative languages
        val tempLang = targetLanguage

        // Determine new alternative name based on new target
        alternativeLanguageName = if (alternativeLanguage == TranslateLang.ZH) {
            getString(R.string.language_english)
        } else {
            getString(R.string.language_chinese)
        }

        targetLanguage = alternativeLanguage
        alternativeLanguage = tempLang

        // Update label and re-translate
        updateTranslateLabel()
        showLoading()
        startTranslation()
    }

    private fun startTranslation() {
        val target = targetLanguage
        // A selection preview has no message id, so the text has to be part of the dedup key —
        // otherwise a second selection joins the first one's result.
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                // The loading state stays up while a missing pack downloads, instead of failing at once.
                val ready = modelStore.awaitReady(target)
                if (_binding == null || targetLanguage != target) return@launch
                if (!ready) {
                    showError(R.string.chat_translate_model_failed)
                    return@launch
                }
                // A missing source-language pack is waited for too, then the translation runs once more.
                submitWaitingForPacks(modelStore, { _binding != null && targetLanguage == target }) {
                    translateQueue.submit(
                        owner = OWNER,
                        key = "preview#${target.name}#${originalText.hashCode()}",
                        messageId = "",
                        priority = TranslateQueue.Priority.NEW_INCOMING,
                        text = originalText,
                        target = target,
                        mode = TranslateMode.MANUAL,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[Translate] preview translate failed target=${target.name}: ${e.stackTraceToString()}" }
                if (_binding != null && targetLanguage == target) showError(R.string.chat_translate_model_failed)
                return@launch
            }
            if (_binding == null || targetLanguage != target) return@launch
            render(result)
        }
    }

    private fun render(result: TranslateResult) {
        when (result) {
            is TranslateResult.Translated -> showResult(result.text)
            // AUTO-only answer; MANUAL (this sheet) gets the original as a Translated result instead.
            TranslateResult.SameLanguage -> showResult(originalText)
            is TranslateResult.ModelNotReady -> showError(R.string.chat_translate_model_not_ready_short)
            is TranslateResult.Unsupported -> showError(
                if (result.reason == TranslateResult.Reason.TOO_LONG) {
                    R.string.chat_translate_too_long
                } else {
                    R.string.chat_translate_failed_retry
                }
            )
            is TranslateResult.Failed -> showError(R.string.chat_translate_failed_retry)
        }
    }

    private fun showLoading() {
        binding.progressBar.isVisible = true
        binding.tvTranslatedText.isVisible = true
        binding.tvTranslatedText.text = getString(R.string.chat_translating)
        binding.tvError.isVisible = false
    }

    private fun showResult(translatedText: String) {
        binding.progressBar.isVisible = false
        binding.tvTranslatedText.isVisible = true
        binding.tvTranslatedText.text = translatedText
        binding.tvError.isVisible = false
    }

    private fun showError(@StringRes messageResId: Int) {
        binding.progressBar.isVisible = false
        binding.tvTranslatedText.isVisible = false
        binding.tvError.isVisible = true
        binding.tvError.text = getString(messageResId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        translateQueue.cancelOwner(OWNER)
        _binding = null
    }
}