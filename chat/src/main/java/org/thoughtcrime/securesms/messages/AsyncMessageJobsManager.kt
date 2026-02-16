package org.thoughtcrime.securesms.messages

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitFirstOrNull
import org.difft.app.database.WCDB
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.models.DBGroupModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by King.W on 2023/10/31
 * Desc: Used to manage async jobs comes from message processor [MessageContentProcessor]
 *
 * This class uses in-memory caches to avoid redundant network requests for contacts and groups
 * that have already been confirmed to exist locally. The caches are automatically cleared on
 * app restart. For logout/account switch scenarios, call [clearCaches] explicitly.
 */
@Singleton
class AsyncMessageJobsManager @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val wcdb: WCDB,
){
    private val contactorIdsForFetching = mutableSetOf<String>()

    private val makeSureGroupExist = mutableSetOf<String>()

    // Cache of contactor IDs that have been confirmed to exist locally (either already in DB or fetched)
    // This prevents redundant network requests within the same app lifecycle
    // Using ConcurrentHashMap for thread-safe read/write from multiple coroutines
    private val confirmedContactorIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Cache of group IDs that have been confirmed to exist locally
    private val confirmedGroupIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Synchronized
    fun needFetchSpecifiedContactors(ids: List<String>) {
        this.contactorIdsForFetching.addAll(ids)
    }

    @Synchronized
    fun runAsyncJobs() {
        // Copy and clear pending tasks first to avoid holding lock during async operations
        val contactorsToProcess = contactorIdsForFetching.toList()
        val groupsToProcess = makeSureGroupExist.toList()
        contactorIdsForFetching.clear()
        makeSureGroupExist.clear()

        if (contactorsToProcess.isEmpty() && groupsToProcess.isEmpty()) {
            return
        }

        appScope.launch(Dispatchers.IO) {
            processContactors(contactorsToProcess)
            processGroups(groupsToProcess)
        }
    }

    private suspend fun processContactors(contactorsToProcess: List<String>) {
        // Filter out contactors that have already been confirmed in memory cache
        val contactorsNotInCache = contactorsToProcess.filter { it !in confirmedContactorIds }
        if (contactorsNotInCache.isEmpty()) {
            if (contactorsToProcess.isNotEmpty()) {
                L.i { "[AsyncMessageJobsManager] All ${contactorsToProcess.size} contactors already in memory cache, skipping" }
            }
            return
        }

        // Check local database first (both contactor and groupMemberContactor tables)
        val existingInDb = wcdb.getContactorsFromAllTable(contactorsNotInCache).map { it.id }.toSet()

        // Add existing ones to cache directly
        if (existingInDb.isNotEmpty()) {
            confirmedContactorIds.addAll(existingInDb)
            L.i { "[AsyncMessageJobsManager] Found ${existingInDb.size} contactors in local DB (contactor or groupMember), added to cache: $existingInDb" }
        }

        // Only fetch those not in local DB
        val contactorsToFetch = contactorsNotInCache.filter { it !in existingInDb }
        if (contactorsToFetch.isNotEmpty()) {
            L.i { "[AsyncMessageJobsManager] Fetching ${contactorsToFetch.size} contactors from network: $contactorsToFetch" }
            try {
                ContactorUtil.fetchContactors(contactorsToFetch, context).await()
                confirmedContactorIds.addAll(contactorsToFetch)
                L.i { "[AsyncMessageJobsManager] Successfully fetched contactors, confirmed cache size: ${confirmedContactorIds.size}" }
            } catch (e: Exception) {
                L.w { "[AsyncMessageJobsManager] fetchContactors error: ${e.stackTraceToString()}" }
            }
        }
    }

    private suspend fun processGroups(groupsToProcess: List<String>) {
        // Filter out groups that have already been confirmed in memory cache
        val groupsNotInCache = groupsToProcess.filter { it !in confirmedGroupIds }
        if (groupsNotInCache.isEmpty()) {
            if (groupsToProcess.isNotEmpty()) {
                L.i { "[AsyncMessageJobsManager] All ${groupsToProcess.size} groups already in memory cache, skipping" }
            }
            return
        }

        // Check local database first - batch query for better performance
        val existingGroupsInDb = wcdb.group.getAllObjects(
            DBGroupModel.gid.`in`(*groupsNotInCache.toTypedArray())
        ).map { it.gid }.toSet()

        // Add existing ones to cache directly
        if (existingGroupsInDb.isNotEmpty()) {
            confirmedGroupIds.addAll(existingGroupsInDb)
            L.i { "[AsyncMessageJobsManager] Found ${existingGroupsInDb.size} groups already in local DB, added to cache: $existingGroupsInDb" }
        }

        // Only fetch those not in local DB - use forceUpdate=true since we already checked DB
        val groupsToFetch = groupsNotInCache.filter { it !in existingGroupsInDb }
        if (groupsToFetch.isNotEmpty()) {
            L.i { "[AsyncMessageJobsManager] Fetching ${groupsToFetch.size} groups from network: $groupsToFetch" }
            for (groupId in groupsToFetch) {
                try {
                    GroupUtil.getSingleGroupInfo(context, groupId, forceUpdate = true).awaitFirstOrNull()
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

    /**
     * Clear all caches. Should be called on logout or account switch.
     */
    @Synchronized
    fun clearCaches() {
        confirmedContactorIds.clear()
        confirmedGroupIds.clear()
        L.i { "[AsyncMessageJobsManager] Caches cleared" }
    }
}