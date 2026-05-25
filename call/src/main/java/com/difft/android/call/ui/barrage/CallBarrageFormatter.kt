package com.difft.android.call.ui.barrage

import com.difft.android.base.user.CallConfig
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.data.BubbleMessageType
import com.difft.android.call.data.EmojiBubbleMessage
import com.difft.android.call.data.RTM_MESSAGE_TYPE_BUBBLE
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.data.TextBubbleMessage
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.StringUtil
import java.text.BreakIterator

/**
 * Renderable payload produced by [CallBarrageFormatter]. The caller is responsible
 * for dispatching each variant to the matching `CallUiController` sink.
 */
sealed interface BarrageRenderable {
    data class Default(val message: BarrageMessage) : BarrageRenderable
    data class Emoji(val message: EmojiBubbleMessage) : BarrageRenderable
    data class Text(val message: TextBubbleMessage) : BarrageRenderable
}

/**
 * Pure formatting helper for call barrage / bubble messages.
 *
 * Encapsulates the text/emoji classification, display-name truncation and
 * bubble animation parameter calculation that was previously inlined in
 * `LCallViewModel.showCallBarrageMessage`.
 */
object CallBarrageFormatter {

    private const val DISPLAY_NAME_MAX_LENGTH = 10

    /**
     * Formats and dispatches a barrage/bubble message to the appropriate
     * [CallUiController] sink in one step. Returns silently if the message
     * is empty or the identity is null.
     */
    suspend fun formatAndDispatch(
        identityValue: String?,
        message: String,
        type: Int?,
        callConfig: CallConfig,
        contactorCacheManager: ContactorCacheManager,
        uiController: CallUiController,
    ) {
        if (identityValue == null || message.isEmpty()) return
        when (val rendered = format(identityValue, message, type, callConfig, contactorCacheManager)) {
            is BarrageRenderable.Default -> uiController.setBarrageMessage(rendered.message)
            is BarrageRenderable.Emoji -> uiController.setEmojiBubbleMessage(rendered.message)
            is BarrageRenderable.Text -> uiController.setTextBubbleMessage(rendered.message)
            null -> Unit
        }
    }

    suspend fun format(
        identityValue: String,
        message: String,
        type: Int?,
        callConfig: CallConfig,
        contactorCacheManager: ContactorCacheManager,
    ): BarrageRenderable? {
        if (message.isEmpty()) return null

        val showName = resolveShowName(identityValue, contactorCacheManager)
        return when (type) {
            RTM_MESSAGE_TYPE_DEFAULT -> BarrageRenderable.Default(
                BarrageMessage(showName, message, System.currentTimeMillis())
            )
            RTM_MESSAGE_TYPE_BUBBLE -> formatBubble(message, showName, callConfig)
            else -> null
        }
    }

    private suspend fun resolveShowName(
        identityValue: String,
        contactorCacheManager: ContactorCacheManager,
    ): String {
        val name = contactorCacheManager.getDisplayName(identityValue)
            ?: IdUtil.convertToBase58UserName(identityValue)
            ?: identityValue
        return StringUtil.truncateWithEllipsis(name, DISPLAY_NAME_MAX_LENGTH)
    }

    private fun formatBubble(
        message: String,
        showName: String,
        callConfig: CallConfig,
    ): BarrageRenderable? {
        val bubbleConfig = callConfig.bubbleMessage
        val baseSpeed = bubbleConfig?.baseSpeed ?: 4600L
        val deltaSpeed = bubbleConfig?.deltaSpeed ?: 400L
        val columns = bubbleConfig?.columns ?: listOf(10, 40, 70)

        val (emojiInfo, textInfo, bubbleType) = classify(message)

        val startOffsetPercent = columns.random()
        val durationMillis = (kotlin.random.Random.nextDouble() * deltaSpeed + baseSpeed).toLong()

        return when (bubbleType) {
            BubbleMessageType.EMOJI -> {
                val emoji = emojiInfo ?: return null
                BarrageRenderable.Emoji(
                    EmojiBubbleMessage(
                        emoji = emoji,
                        userName = showName,
                        startOffsetPercent = startOffsetPercent,
                        durationMillis = durationMillis,
                    )
                )
            }
            BubbleMessageType.TEXT -> BarrageRenderable.Text(
                TextBubbleMessage(
                    emoji = emojiInfo,
                    text = textInfo,
                    userName = showName,
                    startOffsetPercent = startOffsetPercent,
                    durationMillis = durationMillis,
                )
            )
        }
    }

    private fun classify(message: String): Triple<String?, String, BubbleMessageType> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(message)
        val firstStart = iterator.first()
        val firstEnd = iterator.next()

        if (firstEnd == BreakIterator.DONE) {
            return Triple(null, message, BubbleMessageType.TEXT)
        }

        val firstGrapheme = message.substring(firstStart, firstEnd)
        val isSingleEmoji = firstGrapheme == message && StringUtil.isEmojiGrapheme(firstGrapheme)

        return if (isSingleEmoji) {
            Triple(firstGrapheme, "", BubbleMessageType.EMOJI)
        } else {
            val (text, emoji) = StringUtil.splitTextAndTrailingEmoji(message)
            Triple(emoji, text, BubbleMessageType.TEXT)
        }
    }
}
