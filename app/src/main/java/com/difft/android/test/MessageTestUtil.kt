package com.difft.android.test

import android.app.Activity
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.group.GROUP_ROLE_OWNER
import com.difft.android.network.NetworkException
import com.difft.android.network.group.AddOrRemoveMembersReq
import com.difft.android.network.group.CreateGroupReq
import com.difft.android.network.group.GroupRepo
import difft.android.messageserialization.For
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.members
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.random.Random

class MessageTestUtil @Inject constructor(
    private var groupRepo: GroupRepo,
    private var pushTextSendJobFactory: PushTextSendJobFactory
) {

    private val testGroupNamePrefix = "TestGroup"
    private val testMemberIds = listOf("+74463043388", "+72268589462")

    fun createTestGroups(
        activity: Activity,
        scope: CoroutineScope,
        memberIds: List<String>? = null,
        count: Int = 50
    ) {
        ComposeDialogManager.showWait(activity, "")
        val testMemberIds = if (!memberIds.isNullOrEmpty()) memberIds else testMemberIds

        scope.launch {
            try {
                val startNameNumber = withContext(Dispatchers.IO) {
                    wcdb.group.allObjects
                        .filter { it.name?.startsWith(testGroupNamePrefix) == true }
                        .mapNotNull { it.name?.replace(testGroupNamePrefix, "")?.toIntOrNull() }
                        .maxOrNull() ?: 0
                }

                withContext(Dispatchers.IO) {
                    repeat(count) { index ->
                        val name = testGroupNamePrefix + (startNameNumber + index + 1)
                        val result = groupRepo.createGroup(CreateGroupReq(name, testMemberIds)).blockingGet()
                        if (result.status == 0) {
                            L.d { "[test] create Test Group success:" + result.data?.gid + "   name:" + name }
                        } else {
                            L.e { "[test] create Test Group error:" + result.reason }
                            throw NetworkException(result.status, result.reason ?: "")
                        }
                        delay(100)
                    }
                }
                ComposeDialogManager.dismissWait()
                ToastUtil.show("create success")
            } catch (e: Exception) {
                e.printStackTrace()
                ComposeDialogManager.dismissWait()
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }

    fun disbandOrLeaveTestGroups(activity: Activity, scope: CoroutineScope) {
        ComposeDialogManager.showWait(activity, "")

        scope.launch {
            try {
                val testGroups = withContext(Dispatchers.IO) {
                    wcdb.group.allObjects.filter { it.name?.startsWith(testGroupNamePrefix) == true }
                }
                L.d { "[test] disband or leave testGroups:" + testGroups.joinToString { it.name } }

                withContext(Dispatchers.IO) {
                    testGroups.forEach { group ->
                        val role = group.members.find { member -> member.id == globalServices.myId }?.groupRole
                        val result = if (role == GROUP_ROLE_OWNER) {
                            groupRepo.deleteGroup(group.gid).blockingGet()
                        } else {
                            groupRepo.leaveGroup(group.gid, AddOrRemoveMembersReq(mutableListOf(globalServices.myId))).blockingGet()
                        }
                        if (result.status == 0) {
                            L.d { "[test] disband or leave Test Group success: ${group.name}" }
                        } else {
                            L.e { "[test] disband or leave Test Group error:" + result.reason }
                            throw NetworkException(result.status, result.reason ?: "")
                        }
                        delay(100)
                    }
                }
                ComposeDialogManager.dismissWait()
                ToastUtil.show("disband or leave success")
            } catch (e: Exception) {
                e.printStackTrace()
                ComposeDialogManager.dismissWait()
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }

    fun sendTestMessageToAllTestGroups(
        activity: Activity,
        scope: CoroutineScope,
        isConfidential: Boolean = false
    ) {
        ComposeDialogManager.showWait(activity, "")

        scope.launch {
            try {
                val testGroups = withContext(Dispatchers.IO) {
                    wcdb.group.allObjects.filter { it.name?.startsWith(testGroupNamePrefix) == true }
                }
                L.d { "[test] sendTestMessageToAllTestGroups testGroups:" + testGroups.size }

                val content = generateTestMessage()
                testGroups.forEach { group ->
                    sendTextPush(group.gid, content, isConfidential)
                }
                ComposeDialogManager.dismissWait()
                ToastUtil.show("send success")
            } catch (e: Exception) {
                e.printStackTrace()
                ComposeDialogManager.dismissWait()
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }

    private fun generateTestMessage(): String {
        return buildString {
            repeat(500) {
                append(RANDOM_CHARS[Random.nextInt(RANDOM_CHARS.length)])
            }
        }
    }

    private val lastTimestamp = AtomicLong(0L)

    private fun getSafeTimestamp(): Long {
        while (true) {
            val current = System.currentTimeMillis()
            val last = lastTimestamp.get()
            val next = if (current <= last) last + 1 else current
            if (lastTimestamp.compareAndSet(last, next)) {
                return next
            }
        }
    }

    private fun sendTextPush(gid: String, content: String, isConfidential: Boolean = false) {
        val timeStamp = getSafeTimestamp()
        val forWhat = For.Group(gid)
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}$DEFAULT_DEVICE_ID"

        val textMessage = TextMessage(
            messageId,
            For.Account(globalServices.myId),
            forWhat,
            timeStamp,
            timeStamp,
            System.currentTimeMillis(),
            -1,
            3600,
            0,
            0,
            if (isConfidential) 1 else 0,
            content,
        )
        ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))
    }

    companion object {
        private const val RANDOM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}