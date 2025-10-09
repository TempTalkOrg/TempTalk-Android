package com.difft.android.test

import android.app.Activity
import androidx.lifecycle.LifecycleOwner
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import org.difft.app.database.wcdb
import com.difft.android.chat.group.GROUP_ROLE_OWNER
import difft.android.messageserialization.For
import difft.android.messageserialization.model.TextMessage
import com.difft.android.network.NetworkException
import com.difft.android.network.group.AddOrRemoveMembersReq
import com.difft.android.network.group.CreateGroupReq
import com.difft.android.network.group.GroupRepo
import com.difft.android.base.widget.ComposeDialogManager
import io.reactivex.rxjava3.core.Observable
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
class MessageTestUtil @Inject constructor(
    private var groupRepo: GroupRepo,
    private var pushTextSendJobFactory: PushTextSendJobFactory
) {

    private val testGroupNamePrefix = "TestGroup"
    private val testMemberIds = listOf("+74463043388")

    fun createTestGroups(activity: Activity, memberIds: List<String>? = null, count: Long = 20) {
        ComposeDialogManager.showWait(activity, "")
        val startNameNumber = wcdb.group.allObjects
            .filter { it.name?.startsWith(testGroupNamePrefix) == true }
            .maxByOrNull { it.name }?.name
            ?.replace(testGroupNamePrefix, "")
            ?.toIntOrNull() ?: 0
        val testMemberIds = if (!memberIds.isNullOrEmpty()) memberIds else testMemberIds
        Observable.interval(0, 200, TimeUnit.MILLISECONDS)
            .take(count)
            .flatMap {
                val name = testGroupNamePrefix + (startNameNumber + it + 1)
                val result = groupRepo.createGroup(CreateGroupReq(name, testMemberIds)).blockingGet()
                if (result.status == 0) {
                    L.d { "[test] create Test Group success:" + result.data?.gid + "   name:" + name }
                    Observable.just("ok")
                } else {
                    L.e { "[test] create Test Group error:" + result.reason }
                    Observable.error(NetworkException(result.status, result.reason ?: ""))
                }
            }
            .toList()
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                ComposeDialogManager.dismissWait()
                ToastUtil.show("create success")
            }, {
                it.printStackTrace()
                ComposeDialogManager.dismissWait()
                it.message?.let { message -> ToastUtil.show(message) }
            })
    }

    fun disbandOrLeaveTestGroups(activity: Activity) {
        ComposeDialogManager.showWait(activity, "")
        val testGroups = wcdb.group.allObjects.filter { it.name?.startsWith(testGroupNamePrefix) == true }
        L.d { "[test] disband or leave testGroups:" + testGroups.joinToString { it.name } }
        Observable.interval(0, 200, TimeUnit.MILLISECONDS)
            .take(testGroups.size.toLong())
            .flatMap {
                val group = testGroups[it.toInt()]
                val role = group.members.find { member -> member.id == globalServices.myId }?.groupRole
                val result = if (role == GROUP_ROLE_OWNER) {
                    groupRepo.deleteGroup(group.gid).blockingGet()
                } else {
                    groupRepo.leaveGroup(group.gid, AddOrRemoveMembersReq(mutableListOf(globalServices.myId))).blockingGet()
                }
                if (result.status == 0) {
                    L.d { "[test] disband or leave Test Group success:" }
                    Observable.just("ok")
                } else {
                    L.e { "[test] disband or leave Test Group error:" + result.reason }
                    Observable.error(NetworkException(result.status, result.reason ?: ""))
                }
            }
            .toList()
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                ComposeDialogManager.dismissWait()
                ToastUtil.show("disband or leave success")
            }, {
                it.printStackTrace()
                ComposeDialogManager.dismissWait()
                it.message?.let { message -> ToastUtil.show(message) }
            })
    }

    fun sendTestMessageToAllTestGroups(activity: Activity, content: String = "test", isConfidential: Boolean = false) {
        val testGroups = wcdb.group.allObjects.filter { it.name.startsWith(testGroupNamePrefix) }
        L.d { "[test] sendTestMessageToAllTestGroups testGroups:" + testGroups.joinToString { it.name } }
        ComposeDialogManager.showWait(activity, "")
        Observable.interval(0, 200, TimeUnit.MILLISECONDS)
            .take(testGroups.size.toLong())
            .flatMap {
                sendTextPush(testGroups[it.toInt()].gid, content, isConfidential)
                Observable.just("ok")
            }
            .toList()
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                ComposeDialogManager.dismissWait()
                ToastUtil.show("send success")
            }, {
                it.printStackTrace()
                ComposeDialogManager.dismissWait()
                it.message?.let { message -> ToastUtil.show(message) }
            })
    }

    private fun sendTextPush(gid: String, content: String = "test", isConfidential: Boolean = false) {
        val timeStamp = System.currentTimeMillis()
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
}