package org.thoughtcrime.securesms.mediasend.v2.review

import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.luck.picture.lib.entity.LocalMedia
import org.thoughtcrime.securesms.mediasend.v2.gif.MediaReviewGifPageFragment
import org.thoughtcrime.securesms.mediasend.v2.images.MediaReviewImagePageFragment
import org.thoughtcrime.securesms.mediasend.v2.videos.MediaReviewVideoPageFragment
import org.thoughtcrime.securesms.util.MediaUtil
import java.util.LinkedList

class MediaReviewFragmentPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

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

        return Uri.parse(mediaList[position].realPath).hashCode().toLong()
    }

    override fun containsItem(itemId: Long): Boolean {
        return mediaList.any { Uri.parse(it.realPath).hashCode().toLong() == itemId }
    }

    override fun getItemCount(): Int = mediaList.size

    override fun createFragment(position: Int): Fragment {
        val mediaItem: LocalMedia = mediaList[position]
        val mediaUri = Uri.parse(mediaItem.realPath)
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
