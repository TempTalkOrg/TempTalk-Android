package com.difft.android.chat.common

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.bumptech.glide.Glide
import com.difft.android.base.utils.appScope
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.databinding.ChatContactAvatarBinding
import com.hi.dhl.binding.viewbind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.difft.app.database.models.ContactorModel

class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr, defStyleRes) {

    val binding: ChatContactAvatarBinding by viewbind(this)

    private var currentJob: Job? = null

    private val glideContext: Context
        get() {
            return if (context is Activity && !(context as Activity).isFinishing && !(context as Activity).isDestroyed) {
                context
            } else {
                context.applicationContext
            }
        }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 手动取消 Job 防止内存泄漏
        currentJob?.cancel()
        currentJob = null
    }

    fun setAvatar(url: String?, key: String?, firstLetter: String?, id: String, letterTextSizeDp: Int = 22) {
        binding.cvAvatar.setCardBackgroundColor(AvatarUtil.getBgColorResId(id))
        binding.tvAvatar.text = firstLetter
        binding.tvAvatar.textSize = letterTextSizeDp.toFloat()
        if (!url.isNullOrEmpty()) {
            binding.ivFavorites.visibility = GONE
            binding.cvAvatar.visibility = GONE
            binding.ivAvatar.visibility = VISIBLE
            loadAvatar(url, key)
        } else {
            binding.ivFavorites.visibility = GONE
            binding.cvAvatar.visibility = VISIBLE
            binding.ivAvatar.visibility = GONE
        }
    }

    fun setAvatar(contact: ContactorModel, letterTextSizeDp: Int = 22) {
        val avatar = contact.avatar?.getContactAvatarData()
        setAvatar(avatar?.getContactAvatarUrl(), avatar?.encKey, ContactorUtil.getFirstLetter(contact.getDisplayNameForUI()), contact.id, letterTextSizeDp)
    }

    private fun loadAvatar(url: String, key: String?) {
        // 取消之前的任务
        currentJob?.cancel()

        currentJob = appScope.launch {
            AvatarUtil.loadAvatar(
                glideContext,
                url,
                key ?: "",
                binding.ivAvatar,
                binding.cvAvatar,
                AvatarUtil.AvatarCacheSize.SMALL
            )
        }
    }

    fun setAvatar(localPath: String) {
        binding.ivFavorites.visibility = View.GONE
        binding.cvAvatar.visibility = View.GONE
        binding.ivAvatar.visibility = View.VISIBLE
        Glide.with(glideContext)
            .asBitmap()
            .load(localPath)
            .into(binding.ivAvatar)
    }

    fun setAvatar(resId: Int) {
        binding.ivFavorites.visibility = View.GONE
        binding.cvAvatar.visibility = View.GONE
        binding.ivAvatar.visibility = View.VISIBLE
        binding.ivAvatar.setImageResource(resId)
    }

    fun showFavorites() {
        binding.ivFavorites.visibility = View.VISIBLE
        binding.cvAvatar.visibility = View.GONE
        binding.ivAvatar.visibility = View.GONE
    }
}


