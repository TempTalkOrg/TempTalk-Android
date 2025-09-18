package org.thoughtcrime.securesms.messages

import android.annotation.SuppressLint
import android.content.Context
import com.difft.android.base.utils.RxUtil.getSingleSchedulerComposer
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by King.W on 2023/10/31
 * Desc: Used to manage async jobs comes from message processor [MessageContentProcessor]
 */
@Singleton
class AsyncMessageJobsManager @Inject constructor(
    @ApplicationContext
    private val context: Context,
){
    private val contactorIdsForFetching = mutableSetOf<String>()

    private val makeSureGroupExist = mutableSetOf<String>()

    @Synchronized
    fun needFetchSpecifiedContactors(ids: List<String>) {
        this.contactorIdsForFetching.addAll(ids)
    }

    @SuppressLint("CheckResult")
    @Synchronized
    fun runAsyncJobs() {
        if (contactorIdsForFetching.isNotEmpty()) {
            ContactorUtil.fetchContactors(contactorIdsForFetching.toList(), context)
                .compose(getSingleSchedulerComposer())
                .subscribe({
                }) { it.printStackTrace() }
        }
        makeSureGroupExist.forEach {
            GroupUtil.getSingleGroupInfo(context, it).subscribe()
        }
        contactorIdsForFetching.clear()
        makeSureGroupExist.clear()
    }

    @Synchronized
    fun makeSureGroupExist(groupId: String) {
        makeSureGroupExist.add(groupId)
    }
}