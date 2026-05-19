package com.difft.android.chat.crypto

import com.difft.android.PushGroupKeySendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.dependencies.ApplicationDependencies
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues PushGroupKeySendJob to distribute R_group to group members after creating/upgrading
 * an encrypted group or after adding new members. The Job system provides persistent retry
 * across app restarts and network changes; the R_group bytes are loaded by the Job at send time
 * from [GroupCryptoRepo].
 */
@Singleton
class GroupKeyDistributor @Inject constructor(
    private val groupCryptoRepo: GroupCryptoRepo,
    private val pushGroupKeySendJobFactory: PushGroupKeySendJobFactory,
) {
    /**
     * Enqueue a broadcast R_group delivery to all group members via a group message.
     * Used after creating an encrypted group or upgrading a plain group.
     */
    fun distributeToGroup(gid: String) {
        if (groupCryptoRepo.getRGroupBytes(gid) == null) {
            L.w { "[GE][GroupKeyDistributor] No R_group for group $gid, skip distribution" }
            return
        }
        L.i { "[GE][GroupKeyDistributor] Enqueue broadcast R_group for group $gid" }
        ApplicationDependencies.getJobManager().add(
            pushGroupKeySendJobFactory.create(
                parameters = null,
                gid = gid,
                recipientId = gid,
                isBroadcast = true,
            )
        )
    }

    /**
     * Enqueue a targeted 1v1 R_group delivery to a specific member.
     * Used after adding new members to an encrypted group.
     */
    fun distributeToMember(gid: String, memberUid: String) {
        if (groupCryptoRepo.getRGroupBytes(gid) == null) {
            L.w { "[GE][GroupKeyDistributor] No R_group for group $gid, skip distribution to $memberUid" }
            return
        }
        L.i { "[GE][GroupKeyDistributor] Enqueue targeted R_group for group $gid to $memberUid" }
        ApplicationDependencies.getJobManager().add(
            pushGroupKeySendJobFactory.create(
                parameters = null,
                gid = gid,
                recipientId = memberUid,
                isBroadcast = false,
            )
        )
    }

    /**
     * Enqueue R_group delivery to multiple newly added members via 1v1 messages.
     * Skips self.
     */
    fun distributeToMembers(gid: String, memberUids: List<String>) {
        memberUids.forEach { uid ->
            if (uid != globalServices.myId) {
                distributeToMember(gid, uid)
            }
        }
    }
}
