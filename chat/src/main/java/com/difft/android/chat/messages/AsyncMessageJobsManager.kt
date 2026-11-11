package com.difft.android.chat.messages

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.difft.app.database.WCDB
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.models.DBGroupModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton






@Suppress("BlockingWcdbInSuspend")
@Singleton
class AsyncMessageJobsManager @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val wcdb: WCDB,
    private val groupUtil: GroupUtil,
){
    private val contactorIdsForFetching = mutableSetOf<String>()

    private val makeSureGroupExist = mutableSetOf<String>()

    
    private val confirmedContactorIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val confirmedGroupIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Synchronized
    fun needFetchSpecifiedContactors(ids: List<String>) {
        this.contactorIdsForFetching.addAll(ids)
    }

    @Synchronized
    fun runAsyncJobs() {
        
        val contactorsToProcess = contactorIdsForFetching.toList()
        val groupsToProcess = makeSureGroupExist.toList()
        contactorIdsForFetching.clear()
        makeSureGroupExist.clear()

        if (contactorsToProcess.isEmpty() && groupsToProcess.isEmpty()) {
            return
        }

        appScope.launch(Dispatchers.IO) {
            if (wcdb.isDbInaccessible) {
                L.w { "[AsyncMessageJobsManager] skip, DB inaccessible" }
                return@launch
            }
            processContactors(contactorsToProcess)
            processGroups(groupsToProcess)
        }
    }

    private suspend fun processContactors(contactorsToProcess: List<String>) {
        val contactorsNotInCache = contactorsToProcess.filter { it !in confirmedContactorIds }
        if (contactorsNotInCache.isEmpty()) {
            if (contactorsToProcess.isNotEmpty()) {
                L.i { "[AsyncMessageJobsManager] All ${contactorsToProcess.size} contactors already in memory cache, skipping" }
            }
            return
        }

        val existingInDb = wcdb.getContactorsFromAllTable(contactorsNotInCache).map { it.id }.toSet()

        if (existingInDb.isNotEmpty()) {
            confirmedContactorIds.addAll(existingInDb)
            L.i { "[AsyncMessageJobsManager] Found ${existingInDb.size} contactors in local DB (contactor or groupMember), added to cache: $existingInDb" }
        }

        val contactorsToFetch = contactorsNotInCache.filter { it !in existingInDb }
        if (contactorsToFetch.isNotEmpty()) {
            L.i { "[AsyncMessageJobsManager] Fetching ${contactorsToFetch.size} contactors from network: $contactorsToFetch" }
            try {
                ContactorUtil.fetchContactors(contactorsToFetch, context)
                confirmedContactorIds.addAll(contactorsToFetch)
                L.i { "[AsyncMessageJobsManager] Successfully fetched contactors, confirmed cache size: ${confirmedContactorIds.size}" }
            } catch (e: Exception) {
                L.w { "[AsyncMessageJobsManager] fetchContactors error: ${e.stackTraceToString()}" }
            }
        }
    }

    private suspend fun processGroups(groupsToProcess: List<String>) {
        val groupsNotInCache = groupsToProcess.filter { it !in confirmedGroupIds }
        if (groupsNotInCache.isEmpty()) {
            if (groupsToProcess.isNotEmpty()) {
                L.d { "[AsyncMessageJobsManager] All ${groupsToProcess.size} groups already in memory cache, skipping" }
            }
            return
        }

        val existingGroupsInDb = wcdb.group.getAllObjects(
            DBGroupModel.gid.`in`(*groupsNotInCache.toTypedArray())
        ).mapNotNull { it.gid }.toSet()

        if (existingGroupsInDb.isNotEmpty()) {
            confirmedGroupIds.addAll(existingGroupsInDb)
            L.i { "[AsyncMessageJobsManager] Found ${existingGroupsInDb.size} groups already in local DB, added to cache: $existingGroupsInDb" }
        }

        
        val groupsToFetch = groupsNotInCache.filter { it !in existingGroupsInDb }
        if (groupsToFetch.isNotEmpty()) {
            L.i { "[AsyncMessageJobsManager] Fetching ${groupsToFetch.size} groups from network: $groupsToFetch" }
            for (groupId in groupsToFetch) {
                try {
                    groupUtil.getSingleGroupInfo(groupId, forceUpdate = true)
                    confirmedGroupIds.add(groupId)
                } catch (e: Exception) {
                    L.w { "[AsyncMessageJobsManager] getSingleGroupInfo error for $groupId: ${e.stackTraceToString()}" }
                }
            }
        }
    }

    @Synchronized
    fun makeSureGroupExist(groupId: String) {
        makeSureGroupExist.add(groupId)
    }

    


    @Synchronized
    fun clearCaches() {
        confirmedContactorIds.clear()
        confirmedGroupIds.clear()
        L.i { "[AsyncMessageJobsManager] Caches cleared" }
    }
}
