package com.difft.android.chat.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import org.thoughtcrime.securesms.components.reaction.InteractiveConversationElement
import org.thoughtcrime.securesms.components.reaction.Projection
import org.thoughtcrime.securesms.components.reaction.ProjectionList
import org.thoughtcrime.securesms.util.Util

class ChatMessageItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr, defStyleRes), InteractiveConversationElement {


    override val root: ViewGroup
        get() = findViewById(R.id.root)

    override val bubbleView: ViewGroup
        get() = findViewById(R.id.contentContainer)

    override val reactionsView: ViewGroup
        get() = findViewById(R.id.reactions_view)

    override fun getAdapterPosition(recyclerView: RecyclerView): Int {
        throw UnsupportedOperationException("Do not delegate to this method")
    }

    override fun getSnapshotProjections(coordinateRoot: ViewGroup, clipOutMedia: Boolean): ProjectionList {
        return getSnapshotProjections(coordinateRoot, clipOutMedia, true)
    }

    override fun getSnapshotProjections(coordinateRoot: ViewGroup, clipOutMedia: Boolean, outgoingOnly: Boolean): ProjectionList {
        val colorizerProjections = ProjectionList(3)
        val bodyBubbleCorners: Projection.Corners = Projection.Corners(8.dp.toFloat())

        val bodyBubbleToRoot: Projection = Projection.relativeToParent(coordinateRoot, bubbleView, bodyBubbleCorners).translateX(bubbleView.getTranslationX())
        val translationX = Util.halfOffsetFromScale(bubbleView.width, bubbleView.getScaleX())
        val translationY = Util.halfOffsetFromScale(bubbleView.height, bubbleView.getScaleY())
        colorizerProjections.add(
            bodyBubbleToRoot.scale(bubbleView.scaleX)
                .translateX(translationX)
                .translateY(translationY)
        )


//        if ((messageRecord.isOutgoing() || !outgoingOnly) &&
//            hasNoBubble(messageRecord) &&
//            hasWallpaper && bubbleView.getVisibility() === VISIBLE
//        ) {
//            val footer: ConversationItemFooter = getActiveFooter(messageRecord)
//            val footerProjection: Projection = footer.getProjection(coordinateRoot)
//            if (footerProjection != null) {
//                colorizerProjections.add(
//                    footerProjection.translateX(bubbleView.getTranslationX())
//                        .scale(bubbleView.getScaleX())
//                        .translateX(Util.halfOffsetFromScale(footer.getWidth(), bubbleView.getScaleX()))
//                        .translateY(-Util.halfOffsetFromScale(footer.getHeight(), bubbleView.getScaleY()))
//                )
//            }
//        }

        for (i in 0 until colorizerProjections.size) {
            colorizerProjections[i].translateY(translationY)
        }

        return colorizerProjections
    }

    override fun getSnapshotStrategy(): InteractiveConversationElement.SnapshotStrategy? {
        return null
    }

}