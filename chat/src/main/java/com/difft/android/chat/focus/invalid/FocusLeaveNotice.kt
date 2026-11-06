package com.difft.android.chat.focus.invalid

import android.content.Context
import com.difft.android.chat.focus.GroupFocusRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** D4: the leave-group confirm body when the group is the current valid focus group. */
object FocusLeaveNotice {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RepositoryEntryPoint {
        fun groupFocusRepository(): GroupFocusRepository
    }

    /** The focus variant of the leave body for [gid], or [default] when the group is not the focus group. */
    fun message(context: Context, gid: String, default: String): String {
        val repository = EntryPointAccessors.fromApplication(context.applicationContext, RepositoryEntryPoint::class.java).groupFocusRepository()
        val res = FocusInvalidRules.leaveNoticeRes(repository.effectiveNow(), gid) ?: return default
        return context.getString(res)
    }
}
