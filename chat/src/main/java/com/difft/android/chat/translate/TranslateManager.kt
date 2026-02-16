package com.difft.android.chat.translate

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.google.common.base.Optional
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.optimaize.langdetect.LanguageDetector
import com.optimaize.langdetect.LanguageDetectorBuilder
import com.optimaize.langdetect.i18n.LdLocale
import com.optimaize.langdetect.ngram.NgramExtractors
import com.optimaize.langdetect.profiles.LanguageProfile
import com.optimaize.langdetect.profiles.LanguageProfileReader
import com.optimaize.langdetect.text.CommonTextObjectFactories
import com.optimaize.langdetect.text.TextObjectFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    // 缓存 Translator，避免重复创建
    private val translatorCache = ConcurrentHashMap<String, Translator>()

    /**
     * **获取 Translator（按需创建）**
     */
    private fun getTranslator(sourceLang: String, targetLang: String): Translator {
        val key = "$sourceLang-$targetLang"
        return translatorCache.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLang)
                    .setTargetLanguage(targetLang)
                    .build()
            )
        }
    }

    private var languageDetector: LanguageDetector? = null
    private var languageProfiles: List<LanguageProfile>? = null
    private val textObjectFactory: TextObjectFactory = CommonTextObjectFactories.forDetectingOnLargeText()

    /**
     * **使用Lingua 进行本地语言识别**
     */
    fun translateText(
        text: String,
        targetLang: String,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            if (languageDetector == null) {
                try {
                    languageProfiles = LanguageProfileReader().readAllBuiltIn()

                    languageDetector = LanguageDetectorBuilder.create(NgramExtractors.standard())
                        .withProfiles(languageProfiles)
                        .build()
                } catch (e: Exception) {
                    L.e { "[TranslateManager] LanguageDetector 初始化失败: ${e.stackTraceToString()}" }
                }
            }

            val detectedLang = try {
                val textObject = textObjectFactory.forText(text)
                val lang: Optional<LdLocale>? = languageDetector?.detect(textObject)
                if (lang?.isPresent == true) {
                    val language = lang.get().language
                    if (language != "no") {
                        L.d { "[TranslateManager] languageDetector 识别到的语言: $language" }
                        language
                    } else {
                        detectLanguage(text)
                    }
                } else {
                    detectLanguage(text)
                }
            } catch (e: Exception) {
                L.e { "[TranslateManager] Unable to identify the original language: ${e.stackTraceToString()}" }
                detectLanguage(text)
            }

            withContext(Dispatchers.Main) {
                translate(detectedLang, targetLang, text, onSuccess, onFailure)
            }
        }
    }

    private fun detectLanguage(text: String): String {
        var chineseCount = 0
        var englishCount = 0

        for (char in text) {
            // 判断是否是中文字符
            if (char in '\u4e00'..'\u9fa5') {
                chineseCount++
            }
            // 判断是否是英文字符
            else if (char.isLetter() && char in 'a'..'z' || char in 'A'..'Z') {
                englishCount++
            }
        }

        // 根据中文和英文字符的比例来决定语言
        val totalCount = chineseCount + englishCount
        if (totalCount == 0) return TranslateLanguage.ENGLISH

        val chineseRatio = chineseCount.toDouble() / totalCount
        val englishRatio = englishCount.toDouble() / totalCount

        val language = when {
            chineseRatio > 0.5 -> TranslateLanguage.CHINESE
            englishRatio > 0.5 -> TranslateLanguage.ENGLISH
            else -> TranslateLanguage.ENGLISH
        }
        L.d { "[TranslateManager] 本地识别到的语言: $language" }

        return language
    }

    /**
     * **翻译**
     */
    private fun translate(
        sourceLang: String,
        targetLang: String,
        text: String,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val translator = getTranslator(sourceLang, targetLang)

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                L.i { "[TranslateManager] 模型下载成功: $sourceLang -> $targetLang" }
                translator.translate(text)
                    .addOnSuccessListener { translatedText -> onSuccess(translatedText) }
                    .addOnFailureListener { e -> onFailure(e) }
            }
            .addOnFailureListener { e ->
                L.e { "[TranslateManager] 模型下载失败: $sourceLang -> $targetLang ${e.stackTraceToString()}" }
                onFailure(e)
            }
    }

    /**
     * **释放所有 Translator 资源**
     */
    fun close() {
        for (translator in translatorCache.values) {
            translator.close()
        }
        translatorCache.clear()
    }
}