package com.difft.android.chat.common

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.bumptech.glide.Glide
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import org.difft.app.database.wcdb
import com.difft.android.chat.databinding.LayoutGroupAvatarBinding
import com.difft.android.network.group.GroupAvatarData
import com.hi.dhl.binding.viewbind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel

class GroupAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr, defStyleRes) {

    val binding: LayoutGroupAvatarBinding by viewbind(this)

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

    init {
        resetView()
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
        resetView()

        if (groupAvatarData != null) {
            currentJob?.cancel()

            currentJob = appScope.launch {
                GroupAvatarUtil.loadGroupAvatar(glideContext, groupAvatarData, binding.ivAvatar, GroupAvatarUtil.AvatarCacheSize.SMALL)

                updateMembersNumber(showMembersNumber, membersNumber, gid)
            }
        }
    }

    fun setAvatar(localPath: String) {
        Glide.with(glideContext)
            .asBitmap()
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
            // 在 IO 线程中查询数据库
            val count = withContext(Dispatchers.IO) {
                wcdb.groupMemberContactor.getValue(
                    DBGroupMemberContactorModel.databaseId.count(),
                    DBGroupMemberContactorModel.gid.eq(gid)
                )?.int ?: 0
            }

            // 更新 UI 状态
            binding.tvMembersNumber.apply {
                visibility = if (count != 0) View.VISIBLE else View.GONE
                text = count.toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            L.e { "[GroupAvatarView] get group members number error: ${e.stackTraceToString()}" }
        }
    }
}


