package com.difft.android.chat.common

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.utils.getLifecycleOwner
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.difft.android.base.log.lumberjack.L
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.databinding.ChatContactAvatarBinding
import com.hi.dhl.binding.viewbind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import java.io.File

class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr, defStyleRes) {

    val binding: ChatContactAvatarBinding by viewbind(this)

    /** URL of the avatar currently being loaded, used to prevent image mismatch */
    private var currentLoadingUrl: String? = null
    
    /** Pending avatar params to be loaded after onAttachedToWindow */
    private var pendingAvatarParams: AvatarParams? = null

    private data class AvatarParams(
        val url: String,
        val key: String?,
        val firstLetter: String?,
        val id: String,
        val letterTextSizeDp: Int,
        val targetSizePx: Int? = null  // Optional target size in pixels for Glide
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Execute pending load task after view is attached
        pendingAvatarParams?.let {
            loadAvatarInternal(it)
            pendingAvatarParams = null
        }
    }

    fun setAvatar(
        url: String?,
        key: String?,
        firstLetter: String?,
        id: String,
        letterTextSizeDp: Int = 22,
        targetSizePx: Int? = null  // Optional: explicit size in pixels for Glide (useful in Compose AndroidView)
    ) {
        resetAnimationState()

        // Setup letter avatar as placeholder
        binding.cvAvatar.setCardBackgroundColor(AvatarUtil.getBgColorResId(id))
        binding.tvAvatar.text = firstLetter
        binding.tvAvatar.textSize = letterTextSizeDp.toFloat()

        if (!url.isNullOrEmpty()) {
            binding.ivFavorites.visibility = GONE
            currentLoadingUrl = url

            val params = AvatarParams(url, key, firstLetter, id, letterTextSizeDp, targetSizePx)

            if (isAttachedToWindow) {
                loadAvatarInternal(params)
            } else {
                // View not attached yet, save params for onAttachedToWindow
                pendingAvatarParams = params
                binding.cvAvatar.visibility = VISIBLE
                binding.ivAvatar.visibility = GONE
            }
        } else {
            // No URL, show letter avatar
            binding.ivFavorites.visibility = GONE
            binding.cvAvatar.visibility = VISIBLE
            binding.ivAvatar.visibility = GONE
            currentLoadingUrl = null
            pendingAvatarParams = null
        }
    }

    /**
     * Internal avatar loading logic, guaranteed to run after view is attached.
     */
    private fun loadAvatarInternal(params: AvatarParams) {
        val (url, key, _, _, _, targetSize) = params

        // Check if URL still matches (prevent mismatch during fast scrolling)
        if (currentLoadingUrl != url) return

        val cachedFile = AvatarUtil.getCacheFile(url)
        if (cachedFile != null) {
            // Cache exists, load directly
            binding.cvAvatar.visibility = VISIBLE
            binding.tvAvatar.text = ""
            loadImageWithGlide(cachedFile, url, key, targetSize)
            binding.ivAvatar.visibility = VISIBLE
        } else {
            // Cache not found, show letter avatar and download in background
            binding.cvAvatar.visibility = VISIBLE
            binding.ivAvatar.visibility = GONE

            val lifecycleOwner = getLifecycleOwner()
            if (lifecycleOwner == null) {
                L.w { "[AvatarView] loadAvatarInternal: getLifecycleOwner() returned null, cannot download avatar for url=$url" }
                return
            }

            lifecycleOwner.lifecycleScope.launch {
                val downloadedFile = withContext(Dispatchers.IO) {
                    AvatarUtil.ensureCached(context.applicationContext, url, key ?: "")
                }
                if (downloadedFile != null && currentLoadingUrl == url && isAttachedToWindow) {
                    loadImageWithGlide(downloadedFile, url, key, targetSize)
                    binding.ivAvatar.visibility = VISIBLE
                }
            }
        }
    }

    fun setAvatar(contact: ContactorModel, letterTextSizeDp: Int = 22, targetSizePx: Int? = null) {
        val avatar = contact.avatar?.getContactAvatarData()
        setAvatar(
            avatar?.getContactAvatarUrl(),
            avatar?.encKey,
            ContactorUtil.getFirstLetter(contact.getDisplayNameWithoutRemarkForUI()),
            contact.id,
            letterTextSizeDp,
            targetSizePx
        )
    }

    private fun resetAnimationState() {
        binding.cvAvatar.animate().cancel()
        binding.ivAvatar.animate().cancel()
        binding.cvAvatar.alpha = 1f
        binding.ivAvatar.alpha = 1f
    }

    /**
     * Load cache file with Glide, retry download on failure.
     *
     * @param targetSizePx Optional explicit size in pixels. If provided, Glide will use this fixed size
     *                     instead of waiting for View layout. This solves timing issues in Compose AndroidView.
     */
    private fun loadImageWithGlide(file: File, url: String, key: String?, targetSizePx: Int?) {
        // Use the View (this) for Glide lifecycle binding instead of context
        // This ensures proper lifecycle management especially in Compose AndroidView
        val requestBuilder = Glide.with(this)
            .load(file)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    handleGlideLoadFailed(url, key)
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    if (currentLoadingUrl == url) {
                        binding.cvAvatar.visibility = GONE
                    }
                    return false
                }
            })

        // Strategy selection based on whether explicit size is provided
        if (targetSizePx != null) {
            // Explicit size provided: use it directly, no need to wait for View layout
            // This ensures consistent sizing and avoids layout timing issues
            requestBuilder.override(targetSizePx, targetSizePx).into(binding.ivAvatar)
        } else {
            // No explicit size: fall back to original behavior
            // If view size is 0, use override to force Glide to load immediately
            // This can happen in Compose AndroidView before layout pass completes
            if (binding.ivAvatar.width == 0 || binding.ivAvatar.height == 0) {
                requestBuilder.override(Target.SIZE_ORIGINAL).into(binding.ivAvatar)
            } else {
                requestBuilder.into(binding.ivAvatar)
            }
        }
    }

    /**
     * Handle Glide load failure: delete corrupted cache and re-download.
     */
    private fun handleGlideLoadFailed(url: String, key: String?) {
        getLifecycleOwner()?.lifecycleScope?.launch {
            val downloadedFile = withContext(Dispatchers.IO) {
                AvatarUtil.getCacheFile(url)?.delete()
                AvatarUtil.ensureCached(context.applicationContext, url, key ?: "")
            }
            if (downloadedFile != null && currentLoadingUrl == url && isAttachedToWindow) {
                Glide.with(this@AvatarView)
                    .load(downloadedFile)
                    .into(binding.ivAvatar)
                binding.ivAvatar.visibility = VISIBLE
                crossfadeToImage()
            }
        }
    }

    /**
     * Crossfade: fade in image, fade out letter avatar.
     */
    private fun crossfadeToImage() {
        binding.ivAvatar.alpha = 0f
        binding.ivAvatar.visibility = VISIBLE
        binding.ivAvatar.animate()
            .alpha(1f)
            .setDuration(CROSSFADE_DURATION)
            .start()
        binding.cvAvatar.animate()
            .alpha(0f)
            .setDuration(CROSSFADE_DURATION)
            .withEndAction {
                binding.cvAvatar.visibility = GONE
                binding.cvAvatar.alpha = 1f
            }
            .start()
    }

    fun setAvatar(localPath: String) {
        resetAnimationState()
        currentLoadingUrl = null
        binding.ivFavorites.visibility = View.GONE
        binding.cvAvatar.visibility = View.GONE
        binding.ivAvatar.visibility = View.VISIBLE
        Glide.with(this)
            .load(localPath)
            .into(binding.ivAvatar)
    }

    fun setAvatar(resId: Int) {
        resetAnimationState()
        currentLoadingUrl = null
        binding.ivFavorites.visibility = View.GONE
        binding.cvAvatar.visibility = View.GONE
        binding.ivAvatar.visibility = View.VISIBLE
        binding.ivAvatar.setImageResource(resId)
    }

    fun showFavorites() {
        resetAnimationState()
        currentLoadingUrl = null
        binding.ivFavorites.visibility = View.VISIBLE
        binding.cvAvatar.visibility = View.GONE
        binding.ivAvatar.visibility = View.GONE
    }

    companion object {
        private const val CROSSFADE_DURATION = 150L
    }
}


