package org.thoughtcrime.securesms.components.reaction

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import com.difft.android.chat.group.ChatUIData

/**
 * Contains information on a single selected conversation item. This is used when transitioning
 * between selected and unselected states.
 */
data class SelectedConversationModel(
    val bitmap: Bitmap,
    val itemX: Float,
    val itemY: Float,
    val bubbleY: Float,
    val bubbleWidth: Int,
    val audioUri: Uri? = null,
    val isOutgoing: Boolean,
    val focusedView: View?,
    val snapshotMetrics: InteractiveConversationElement.SnapshotMetrics,
//    val emojis: List<String>?,
    val mostUseEmojis: List<String>?,
    val chatUIData: ChatUIData?,
    val isForForward: Boolean, //是否是转发页面使用
    val isSaved: Boolean //是否是收藏
)
