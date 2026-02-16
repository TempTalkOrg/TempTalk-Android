package com.difft.android.chat.common

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.getLifecycleOwner
import org.difft.app.database.wcdb
import com.difft.android.chat.databinding.LayoutGroupAvatarBinding
import com.difft.android.network.group.GroupAvatarData
import com.hi.dhl.binding.viewbind
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import java.io.File

class GroupAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr, defStyleRes) {

    val binding: LayoutGroupAvatarBinding by viewbind(this)

    /** ID of the avatar currently being loaded, used to prevent image mismatch */
    private var currentLoadingId: String? = null
    
    /** Pending avatar params to be loaded after onAttachedToWindow */
    private var pendingAvatarParams: GroupAvatarParams? = null
    
    /** Pending members params to be updated after onAttachedToWindow */
    private var pendingMembersParams: MembersParams? = null

    private data class GroupAvatarParams(
        val groupAvatarData: GroupAvatarData
    )
    
    private data class MembersParams(
        val showMembersNumber: Boolean,
        val membersNumber: Int,
        val gid: String?
    )

    private val glideContext: Context
        get() {
            return if (context is Activity && !(context as Activity).isFinishing && !(context as Activity).isDestroyed) {
                context
            } else {
                context.applicationContext
            }
        }

    init {
        resetView()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Execute pending members update after view is attached
        pendingMembersParams?.let { params ->
            getLifecycleOwner()?.lifecycleScope?.launch {
                updateMembersNumber(params.showMembersNumber, params.membersNumber, params.gid)
            }
            pendingMembersParams = null
        }
        // Execute pending avatar load after view is attached
        pendingAvatarParams?.let {
            loadAvatarInternal(it)
            pendingAvatarParams = null
        }
    }

    private fun resetView() {
        binding.ivAvatar.setImageResource(com.difft.android.base.R.drawable.base_ic_group)
        binding.tvMembersNumber.text = ""
        binding.tvMembersNumber.visibility = View.GONE
    }

    fun setAvatar(
        groupAvatarData: GroupAvatarData?,
        showMembersNumber: Boolean = false,
        membersNumber: Int = 0,
        gid: String? = null
    ) {
        // Update members count independently of avatar loading
        if (isAttachedToWindow) {
            getLifecycleOwner()?.lifecycleScope?.launch {
                updateMembersNumber(showMembersNumber, membersNumber, gid)
            } ?: L.e { "[GroupAvatarView] Failed to get lifecycleOwner for members update" }
        } else {
            // Will be updated in onAttachedToWindow via pendingMembersParams
            pendingMembersParams = MembersParams(showMembersNumber, membersNumber, gid)
        }

        val newAvatarId = groupAvatarData?.serverId

        // No avatar data, show default icon
        if (newAvatarId == null || groupAvatarData == null) {
            binding.ivAvatar.setImageResource(com.difft.android.base.R.drawable.base_ic_group)
            currentLoadingId = null
            pendingAvatarParams = null
            return
        }

        currentLoadingId = newAvatarId
        
        val params = GroupAvatarParams(groupAvatarData)
        
        if (isAttachedToWindow) {
            loadAvatarInternal(params)
        } else {
            // View not attached yet, save params for onAttachedToWindow
            pendingAvatarParams = params
            binding.ivAvatar.setImageResource(com.difft.android.base.R.drawable.base_ic_group)
        }
    }

    /**
     * Internal avatar loading logic, guaranteed to run after view is attached.
     */
    private fun loadAvatarInternal(params: GroupAvatarParams) {
        val groupAvatarData = params.groupAvatarData
        val avatarId = groupAvatarData.serverId ?: return
        
        // Check if ID still matches (prevent mismatch during fast scrolling)
        if (currentLoadingId != avatarId) return
        
        val cacheFile = GroupAvatarUtil.getCacheFile(avatarId)
        if (cacheFile != null) {
            // Cache exists, load directly
            loadFromCacheFile(avatarId, cacheFile, groupAvatarData)
        } else {
            // Cache not found, show default icon and download in background
            binding.ivAvatar.setImageResource(com.difft.android.base.R.drawable.base_ic_group)
            
            getLifecycleOwner()?.lifecycleScope?.launch {
                val downloadedFile = withContext(Dispatchers.IO) {
                    GroupAvatarUtil.ensureCached(context.applicationContext, groupAvatarData)
                }
                if (downloadedFile != null && currentLoadingId == avatarId && isAttachedToWindow) {
                    loadFromCacheFile(avatarId, downloadedFile, groupAvatarData)
                }
            }
        }
    }

    /**
     * Load and display avatar from cache file, retry download on failure.
     */
    private fun loadFromCacheFile(avatarId: String, cacheFile: File, groupAvatarData: GroupAvatarData) {
        Glide.with(glideContext)
            .load(cacheFile)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    handleGlideLoadFailed(avatarId, groupAvatarData)
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }
            })
            .into(binding.ivAvatar)
    }

    /**
     * Handle Glide load failure: delete corrupted cache and re-download.
     */
    private fun handleGlideLoadFailed(avatarId: String, groupAvatarData: GroupAvatarData) {
        getLifecycleOwner()?.lifecycleScope?.launch {
            val downloadedFile = withContext(Dispatchers.IO) {
                GroupAvatarUtil.getCacheFile(avatarId)?.delete()
                GroupAvatarUtil.ensureCached(context.applicationContext, groupAvatarData)
            }
            if (downloadedFile != null && currentLoadingId == avatarId && isAttachedToWindow) {
                Glide.with(glideContext)
                    .load(downloadedFile)
                    .into(binding.ivAvatar)
            }
        }
    }

    fun setAvatar(localPath: String) {
        currentLoadingId = null
        Glide.with(glideContext)
            .load(localPath)
            .into(binding.ivAvatar)
    }

    private suspend fun updateMembersNumber(show: Boolean, number: Int, gid: String?) = withContext(Dispatchers.Main) {
        if (!show) {
            binding.tvMembersNumber.visibility = View.GONE
            return@withContext
        }

        if (number > 0) {
            binding.tvMembersNumber.visibility = View.VISIBLE
            binding.tvMembersNumber.text = number.toString()
            return@withContext
        }

        if (gid == null) {
            binding.tvMembersNumber.visibility = View.GONE
            return@withContext
        }

        try {
            val count = withContext(Dispatchers.IO) {
                wcdb.groupMemberContactor.getValue(
                    DBGroupMemberContactorModel.databaseId.count(),
                    DBGroupMemberContactorModel.gid.eq(gid)
                )?.int ?: 0
            }

            binding.tvMembersNumber.apply {
                visibility = if (count != 0) View.VISIBLE else View.GONE
                text = count.toString()
            }
        } catch (e: Exception) {
            L.e { "[GroupAvatarView] get group members number error: ${e.stackTraceToString()}" }
        }
    }
}


