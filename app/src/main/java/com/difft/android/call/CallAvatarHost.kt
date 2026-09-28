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

/**
 * Bridges the call module's data-only [AvatarData] onto the chat avatar chain. Lives in :app
 * because it is the only module that sees both :call and :chat.
 */
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

/** Builds the reusable row avatar binder behind `LCallToChatController.createAvatarViewBinder`. */
internal fun buildCallAvatarViewBinder(context: Context, avatarSizeDp: Int): AvatarViewBinder =
    CallAvatarViewBinder(context, avatarSizeDp)

/**
 * View counterpart of [CallAvatar] for XML RecyclerView rows. Glide gets an explicit target size
 * so the request identity does not change between the first load and a rebind.
 *
 * Every bind re-applies [AvatarView.setAvatar], even for data equal to the last bind: AvatarView
 * drops a download that finishes while the View is detached and does not retry on re-attach, so
 * the rebind is the retry — exactly what the XML avatar rows do. An unchanged, already loaded
 * image is a no-op for Glide (equivalent request), so the re-apply is cheap.
 */
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

/** Letter + colour-seed pair a uid-only avatar renders from. */
internal data class LetterAvatarFields(val firstLetter: String, val id: String)

/**
 * Strips the device suffix and derives the letter through the app-wide rule
 * ([ContactorUtil.getFirstLetter]) so a uid-only call avatar reads the same as the same person
 * rendered from a contact record. [name] already carries the `TT-…` base58 fallback from
 * `ContactorCacheManager.getParticipantDisplayInfo`; a null/blank name yields `#`.
 *
 * The colour seed keeps the `+` strip: `AvatarUtil.getBgColorResId` keys on the last character,
 * so `+123` and `123` are colour-identical.
 */
internal fun letterAvatarFieldsFor(name: String?, uid: String): LetterAvatarFields {
    val userId = uid.substringBefore('.')
    return LetterAvatarFields(
        firstLetter = ContactorUtil.getFirstLetter(name),
        id = userId.replace("+", ""),
    )
}
