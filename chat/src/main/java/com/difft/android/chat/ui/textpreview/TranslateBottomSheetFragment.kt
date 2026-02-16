package com.difft.android.chat.ui.textpreview

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.chat.R
import com.difft.android.chat.databinding.LayoutTranslateBottomSheetContentBinding
import com.difft.android.chat.translate.TranslateManager
import com.google.mlkit.nl.translate.TranslateLanguage
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * Bottom sheet dialog for displaying translation results.
 * Shows original text (max 2 lines) and translated text with expand/collapse support.
 */
@AndroidEntryPoint
class TranslateBottomSheetFragment : BaseBottomSheetDialogFragment() {

    @Inject
    lateinit var translateManager: TranslateManager

    private var _binding: LayoutTranslateBottomSheetContentBinding? = null
    private val binding get() = _binding!!

    private var originalText: String = ""
    private var targetLanguage: String = ""
    private var alternativeLanguage: String = ""
    private var alternativeLanguageName: String = ""

    companion object {
        private const val TAG = "TranslateBottomSheet"
        private const val ARG_ORIGINAL_TEXT = "arg_original_text"

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
                targetLanguage = TranslateLanguage.CHINESE
                alternativeLanguage = TranslateLanguage.ENGLISH
                alternativeLanguageName = getString(R.string.language_english)
            }
            else -> {
                // App is English: translate to English, show "Translate to Chinese" as switch option
                targetLanguage = TranslateLanguage.ENGLISH
                alternativeLanguage = TranslateLanguage.CHINESE
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
        alternativeLanguageName = if (alternativeLanguage == TranslateLanguage.CHINESE) {
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
        translateManager.translateText(
            text = originalText,
            targetLang = targetLanguage,
            onSuccess = { translatedText ->
                if (_binding != null) {
                    showResult(translatedText)
                }
            },
            onFailure = { _ ->
                if (_binding != null) {
                    showError()
                }
            }
        )
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

    private fun showError() {
        binding.progressBar.isVisible = false
        binding.tvTranslatedText.isVisible = false
        binding.tvError.isVisible = true
        binding.tvError.text = getString(R.string.chat_translate_fail)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}