package com.difft.android.chat.mediasend.v2.review

import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.luck.picture.lib.entity.LocalMedia
import com.difft.android.chat.mediasend.v2.gif.MediaReviewGifPageFragment
import com.difft.android.chat.mediasend.v2.images.MediaReviewImagePageFragment
import com.difft.android.chat.mediasend.v2.videos.MediaReviewVideoPageFragment
import com.difft.android.chat.util.MediaUtil
import java.util.LinkedList

class MediaReviewFragmentPagerAdapter(fragment: Fragment) : androidx.viewpager2.adapter.FragmentStateAdapter(fragment) {

    private val mediaList: MutableList<LocalMedia> = mutableListOf()

    fun submitMedia(media: List<LocalMedia>) {
        val oldMedia: List<LocalMedia> = LinkedList(mediaList)
        mediaList.clear()
        mediaList.addAll(media)

        DiffUtil
            .calculateDiff(Callback(oldMedia, mediaList))
            .dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long {
        if (position > mediaList.size || position < 0) {
            return RecyclerView.NO_ID
        }

        return mediaList[position].realPath.toUri().hashCode().toLong()
    }

    override fun containsItem(itemId: Long): Boolean {
        return mediaList.any { it.realPath.toUri().hashCode().toLong() == itemId }
    }

    override fun getItemCount(): Int = mediaList.size

    override fun createFragment(position: Int): Fragment {
        val mediaItem: LocalMedia = mediaList[position]
        val mediaUri = mediaItem.realPath.toUri()
        return when {
            MediaUtil.isGif(mediaItem.mimeType) -> MediaReviewGifPageFragment.newInstance(mediaUri)
            MediaUtil.isImageType(mediaItem.mimeType) -> MediaReviewImagePageFragment.newInstance(mediaUri)
            MediaUtil.isVideoType(mediaItem.mimeType) -> MediaReviewVideoPageFragment.newInstance(mediaUri, false)
            else -> {
                throw UnsupportedOperationException("Can only render images and videos. Found mimetype: '" + mediaItem.mimeType + "'")
            }
        }
    }

    private class Callback(
        private val oldList: List<LocalMedia>,
        private val newList: List<LocalMedia>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].realPath == newList[newItemPosition].realPath
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
