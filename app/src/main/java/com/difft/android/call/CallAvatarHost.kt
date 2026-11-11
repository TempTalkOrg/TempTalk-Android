package com.difft.android.call

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.difft.android.base.ui.avatar.AvatarLetterSize
import com.difft.android.call.data.AvatarData
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.common.compose.ContactAvatar
import com.difft.android.chat.common.compose.LetterAvatar
import com.difft.android.chat.contacts.data.ContactorUtil
import kotlin.math.roundToInt





@Composable
internal fun CallAvatar(data: AvatarData, size: Dp, modifier: Modifier, letterTextSizeDp: Int) {
    when (data) {
        is AvatarData.FromContactor ->
            ContactAvatar(data.contactor, size, modifier, letterTextSizeDp)

        is AvatarData.FromNameOrUid -> {
            val fields = letterAvatarFieldsFor(data.name, data.userId)
            LetterAvatar(fields.firstLetter, fields.id, size, modifier, letterTextSizeDp)
        }
    }
}


internal fun buildCallAvatarViewBinder(context: Context, avatarSizeDp: Int): AvatarViewBinder =
    CallAvatarViewBinder(context, avatarSizeDp)










private class CallAvatarViewBinder(context: Context, avatarSizeDp: Int) : AvatarViewBinder {

    private val avatarView = AvatarView(context)
    private val letterTextSizeDp = AvatarLetterSize.forDiameterDp(avatarSizeDp)
    private val targetSizePx = (avatarSizeDp * context.resources.displayMetrics.density).roundToInt()

    override val view: View get() = avatarView

    override fun bind(data: AvatarData) {
        when (data) {
            is AvatarData.FromContactor ->
                avatarView.setAvatar(data.contactor, letterTextSizeDp, targetSizePx)

            is AvatarData.FromNameOrUid -> {
                val fields = letterAvatarFieldsFor(data.name, data.userId)
                avatarView.setAvatar(
                    url = null,
                    key = null,
                    firstLetter = fields.firstLetter,
                    id = fields.id,
                    letterTextSizeDp = letterTextSizeDp,
                    targetSizePx = targetSizePx,
                )
            }
        }
    }
}


internal data class LetterAvatarFields(val firstLetter: String, val id: String)










internal fun letterAvatarFieldsFor(name: String?, uid: String): LetterAvatarFields {
    val userId = uid.substringBefore('.')
    return LetterAvatarFields(
        firstLetter = ContactorUtil.getFirstLetter(name),
        id = userId.replace("+", ""),
    )
}
