package com.difft.android.chat.mediasend.v2.review

import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.difft.android.chat.R
import com.luck.picture.lib.entity.LocalMedia
import com.difft.android.chat.util.MediaUtil
import com.difft.android.chat.util.adapter.mapping.LayoutFactory
import com.difft.android.chat.util.adapter.mapping.MappingAdapter
import com.difft.android.chat.util.adapter.mapping.MappingModel
import com.difft.android.chat.util.adapter.mapping.MappingViewHolder
import com.difft.android.chat.util.visible

typealias OnSelectedMediaClicked = (LocalMedia, Boolean) -> Unit

object MediaReviewSelectedItem {
    fun register(mappingAdapter: MappingAdapter, onSelectedMediaClicked: OnSelectedMediaClicked) {
        mappingAdapter.registerFactory(Model::class.java, LayoutFactory({ ViewHolder(it, onSelectedMediaClicked) }, R.layout.v2_media_review_selected_item))
    }

    class Model(val media: LocalMedia, val isSelected: Boolean) : MappingModel<Model> {
        override fun areItemsTheSame(newItem: Model): Boolean {
            return media == newItem.media
        }

        override fun areContentsTheSame(newItem: Model): Boolean {
            return media == newItem.media && isSelected == newItem.isSelected
        }
    }

    class ViewHolder(itemView: View, private val onSelectedMediaClicked: OnSelectedMediaClicked) : com.difft.android.chat.util.adapter.mapping.MappingViewHolder<Model>(itemView) {

        private val imageView: ImageView = itemView.findViewById(R.id.media_review_selected_image)
        private val playOverlay: ImageView = itemView.findViewById(R.id.media_review_play_overlay)
        private val trashOverlay: ImageView = itemView.findViewById(R.id.media_review_trash_overlay)

        override fun bind(model: Model) {
            Glide.with(imageView)
                .load(model.media.realPath)
                .centerCrop()
                .into(imageView)

            playOverlay.visible = MediaUtil.isNonGifVideo(model.media) && !model.isSelected
            trashOverlay.visible = model.isSelected

            itemView.contentDescription = if (model.isSelected) {
                context.getString(R.string.MediaReviewSelectedItem__tap_to_remove)
            } else {
                context.getString(R.string.MediaReviewSelectedItem__tap_to_select)
            }

            itemView.setOnClickListener { onSelectedMediaClicked(model.media, model.isSelected) }
        }
    }
}
