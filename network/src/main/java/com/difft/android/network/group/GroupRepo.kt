package com.difft.android.network.group

import android.content.Context
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.google.gson.JsonElement
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Single
import org.difft.app.database.models.GroupModel
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import javax.inject.Inject

interface GroupService {
    @PUT("v1/groups")
    fun createGroup(@Body req: CreateGroupReq): Single<BaseResponse<CreateGroupResp>>

    @DELETE("v1/groups/{gid}")
    fun deleteGroup(@Path("gid") gid: String): Single<BaseResponse<Any>>

    @POST("v1/groups/{gid}")
    fun changeGroupSettings(@Path("gid") gid: String, @Body req: ChangeGroupSettingsReq): Single<BaseResponse<GetGroupInfoResp>>

//    @POST("/v1/groups/{gid}")
//    fun changeGroupSettings(@Path("gid") gid: String, @Body req: ChangeGroupNameReq): Single<BaseResponse<Any>>

    @GET("v1/groups/{gid}")
    fun getGroupInfo(@Path("gid") gid: String): Single<BaseResponse<GetGroupInfoResp>>

    @GET("v1/groups")
    fun getGroups(): Single<BaseResponse<GetGroupsResp>>

    @GET("v1/groups/{gid}/members")
    fun getSelfInfo(@Path("gid") gid: String): Single<BaseResponse<SelfInfoResp>>

    @PUT("v1/groups/{gid}/members")
    fun addMembers(@Path("gid") gid: String, @Body req: AddOrRemoveMembersReq): Single<BaseResponse<CreateGroupResp>>

    @HTTP(method = "DELETE", path = "v1/groups/{gid}/members", hasBody = true)
    fun removeMembers(@Path("gid") gid: String, @Body req: AddOrRemoveMembersReq): Single<BaseResponse<Any>>

    @POST("v1/groups/{gid}/members/{uid}")
    suspend fun changeSelfSettingsInGroup(@Path("gid") gid: String, @Path("uid") uid: String, @Body req: ChangeSelfSettingsInGroupReq): BaseResponse<Any>

    @POST("v1/groups/{gid}/members/{uid}")
    fun changeMemberRole(@Path("gid") gid: String, @Path("uid") uid: String, @Body req: ChangeRolepReq): Single<BaseResponse<Any>>

    @POST("v1/groups/{gid}/members/{uid}")
    fun changeMemberRapidRole(@Path("gid") gid: String, @Path("uid") uid: String, @Body req: ChangeRapidRoleReq): Call<BaseResponse<Any>>

    @GET("v1/groups/invitation/{gid}")
    fun getInviteCode(@Path("gid") gid: String): Single<BaseResponse<InviteCodeResp>>

    @GET("v1/groups/invitation/groupInfo/{inviteCode}")
    fun getGroupInfoByInviteCode(@Path("inviteCode") inviteCode: String): Single<BaseResponse<GroupInfoByInviteCodeResp>>

    @PUT("v1/groups/invitation/join/{inviteCode}")
    fun joinGroupByInviteCode(@Path("inviteCode") inviteCode: String): Single<BaseResponse<JoinGroupByInviteCodeResp>>

    @PUT("v1/groups/{gid}/announcement")
    fun createGroupAnnouncement(@Path("gid") gid: String, @Body req: GroupAnnouncementReq): Call<BaseResponse<GroupAnnouncementIdResp>>

    @DELETE("v1/groups/{gid}/announcement/{gaid}")
    fun deleteGroupAnnouncement(@Path("gid") gid: String, @Path("gaid") gaid: String): Call<BaseResponse<Any>>

    @POST("v1/groups/{gid}/announcement/{gaid}")
    fun changeGroupAnnouncement(@Path("gid") gid: String, @Path("gaid") gaid: String, @Body req: GroupAnnouncementReq): Call<BaseResponse<Any>>

    @GET("v1/groups/{gid}/announcement/")
    fun getGroupAnnouncements(@Path("gid") gid: String): Call<BaseResponse<GetGroupAnnouncementsResp>>

    @PUT("v1/groups/{gid}/pin")
    suspend fun createGroupPin(@Path("gid") gid: String, @Body req: GroupPinReq): BaseResponse<GroupPinIdResp>

    @HTTP(method = "DELETE", path = "v1/groups/{gid}/pin", hasBody = true)
    suspend fun deleteGroupPins(@Path("gid") gid: String, @Body req: DeleteGroupPinsReq): BaseResponse<Any>

    @GET("v1/groups/{gid}/pin?page=1&size=100")
    suspend fun getGroupPins(@Path("gid") gid: String): BaseResponse<GetGroupPinsResp>
}

class GroupRepo
@Inject
constructor(
    @param:ApplicationContext context: Context,
    @param:ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient
) {

    private val groupService: GroupService = httpClient.getService(GroupService::class.java)

    fun createGroup(req: CreateGroupReq): Single<BaseResponse<CreateGroupResp>> {
        return groupService.createGroup(req)
    }

    fun getGroups(): Single<List<GroupModel>> {
        return groupService.getGroups().covertToGroup()
    }

    private fun Single<BaseResponse<GetGroupsResp>>.covertToGroup(): Single<List<GroupModel>> =
        this.map { resp ->
            resp
                .data
                ?.groups
                ?.map { from(it) }
                ?: emptyList()
        }

    fun from(groupResp: GroupResp): GroupModel {
        val id = groupResp.gid
        val name = groupResp.name
        val messageExpiry = groupResp.messageExpiry
        val avatar = groupResp.avatar
        val status = groupResp.status
        val invitationRule = groupResp.invitationRule
        val linkInviteSwitch = groupResp.linkInviteSwitch
        val version = groupResp.version
        val remindCycle = groupResp.remindCycle
        val anyoneRemove = groupResp.anyoneRemove
        val rejoin = groupResp.rejoin
        val publishRule = groupResp.publishRule
        return GroupModel().apply {
            this.gid = id
            this.name = name
            this.messageExpiry = messageExpiry
            this.avatar = avatar
            this.status = status
            this.invitationRule = invitationRule
            this.linkInviteSwitch = linkInviteSwitch
            this.version = version
            this.remindCycle = remindCycle
            this.anyoneRemove = anyoneRemove
            this.rejoin = rejoin
            this.publishRule = publishRule
        }
    }

    fun getGroupInfo(gid: String): Single<BaseResponse<GetGroupInfoResp>> {
        return groupService.getGroupInfo(gid)
    }

    fun leaveGroup(gid: String, req: AddOrRemoveMembersReq): Single<BaseResponse<Any>> {
        return groupService.removeMembers(gid, req)
    }

    fun deleteGroup(gid: String): Single<BaseResponse<Any>> {
        return groupService.deleteGroup(gid)
    }

    fun addMembers(gid: String, list: List<String>): Single<BaseResponse<CreateGroupResp>> {
        return groupService.addMembers(gid, AddOrRemoveMembersReq(list))
    }

    fun removeMembers(gid: String, list: List<String>): Single<BaseResponse<Any>> {
        return groupService.removeMembers(gid, AddOrRemoveMembersReq(list))
    }

    fun changeMemberRole(gid: String, uid: String, changeRoleReq: ChangeRolepReq): Single<BaseResponse<Any>> {
        return groupService.changeMemberRole(gid, uid, changeRoleReq)
    }

    suspend fun changeSelfSettingsInGroup(gid: String, uid: String, req: ChangeSelfSettingsInGroupReq): BaseResponse<Any> {
        return groupService.changeSelfSettingsInGroup(gid, uid, req)
    }

    fun changeGroupSettings(gid: String, changeGroupSettingsReq: ChangeGroupSettingsReq): Single<BaseResponse<GetGroupInfoResp>> {
        return groupService.changeGroupSettings(gid, changeGroupSettingsReq)
    }

    fun getInviteCode(gid: String): Single<BaseResponse<InviteCodeResp>> {
        return groupService.getInviteCode(gid)
    }

    fun getGroupInfoByInviteCode(inviteCode: String): Single<BaseResponse<GroupInfoByInviteCodeResp>> {
        return groupService.getGroupInfoByInviteCode(inviteCode)
    }

    fun joinGroupByInviteCodeResp(inviteCode: String): Single<BaseResponse<JoinGroupByInviteCodeResp>> {
        return groupService.joinGroupByInviteCode(inviteCode)
    }
}

data class CreateGroupReq(
//    val invitationRule: Int,//默认为2
//    val notification: Int?,
//    val messageExpiry: Int,
    val name: String,
    val numbers: List<String>,
    val avatar: String? = null
)

data class CreateGroupResp(
    val gid: String,
    val strangers: List<Stranger>?
)

data class Stranger(
    val name: String?,
    val uid: String?
)

//data class ChangeGroupNameReq(val name: String)

data class ChangeGroupSettingsReq(
    val name: String? = null,
    val anyoneRemove: Boolean? = null,
    val avatar: String? = null,
    val invitationRule: Int? = null,
    val messageExpiry: Long? = null,
    val owner: String? = null,
    val publishRule: Int? = null,
    val rejoin: Boolean? = null,
    val remindCycle: String? = null,
    val privateChat: Boolean? = null,
    val linkInviteSwitch: Boolean? = null,
    val criticalAlert: Boolean? = null,
)

data class GetGroupInfoResp(
    val anyoneRemove: Boolean,
    val avatar: String,
    val ext: Boolean,
    val invitationRule: Int,
    val members: List<Member>,
    val messageExpiry: Int,
    val name: String,
    val publishRule: Int,
    val rejoin: Boolean,
    val remindCycle: String,
    val version: Int,
    val linkInviteSwitch: Boolean,
    val privateChat: Boolean,
    val messageClearAnchor: Long,
    val criticalAlert: Boolean,
)

data class Member(
    val displayName: String?,
    val extId: Int,
    val notification: Int,
    val rapidRole: Int,
    val remark: String?,
    val role: Int,
    val uid: String,
    val useGlobal: Boolean
)

data class GetGroupsResp(
    val groups: List<GroupResp>?
)

data class GroupResp(
    val anyoneRemove: Boolean,
    val avatar: String,
    val ext: Boolean,
    val gid: String,
    val invitationRule: Int,
    val linkInviteSwitch: Boolean,
    val messageExpiry: Int,
    val name: String,
    val publishRule: Int,
    val rejoin: Boolean,
    val remindCycle: String,
    val status: Int,
    val version: Int,
    val criticalAlert: Boolean
)

data class GroupAvatarResponse(
    val `data`: String
)

data class GroupAvatarData(
    val attachmentType: Int,
    val byteCount: String?,
    val contentType: String?,
    val digest: String?,
    val encryptionKey: String?,
    val serverId: String?
)

data class SelfInfoResp(
    val displayName: String,
    val extId: Int,
    val notification: Int,
    val rapidRole: Int,
    val remark: String,
    val role: Int,
    val uid: String,
    val useGlobal: Boolean
)

data class AddOrRemoveMembersReq(
    val numbers: List<String>
)

data class ChangeRolepReq(
    val role: Int
)

data class ChangeRapidRoleReq(
    val rapidRole: Int
)

data class ChangeSelfSettingsInGroupReq(
    val displayName: String? = null,
    val notification: Int? = null,
    val remark: String? = null,
    val useGlobal: Boolean?= null
)

data class InviteCodeResp(
    val inviteCode: String
)

data class GroupInfoByInviteCodeResp(
    val avatar: String?,
    val invitationRule: Int,
    val members: Any?,
    val membersCount: Int,
    val messageExpiry: Int,
    val name: String?,
    val version: Int
) : java.io.Serializable

data class JoinGroupByInviteCodeResp(
    val anyoneRemove: Boolean,
    val avatar: String,
    val ext: Boolean,
    val gid: String,
    val invitationRule: Int,
    val members: List<Member>,
    val messageExpiry: Int,
    val name: String,
    val publishRule: Int,
    val rejoin: Boolean,
    val remindCycle: String,
    val version: Int
)

data class GroupAnnouncementReq(
    val announcementExpiry: Int,
    val centent: String
)

data class GroupAnnouncementIdResp(
    val id: String
)

data class GetGroupAnnouncementsResp(
    val gid: String,
    val groupAnnouncements: List<GroupAnnouncement>
)

data class GroupAnnouncement(
    val announcementExpiry: Int,
    val content: String,
    val id: String,
    val reviseTime: String
)

data class GroupPinReq(
    val content: String,
    val conversationId: String,
    val businessId: String
)

data class GroupPinIdResp(
    val id: String
)

data class DeleteGroupPinsReq(
    val pins: List<String>
)

data class GetGroupPinsResp(
    val gid: String,
    val groupPins: List<GroupPin>
)

data class GroupPin(
    val content: String,
    val conversationId: String?,
    val createTime: Long,
    val creator: String,
    val id: String,
    val businessId: String?,
    val businessInfo: JsonElement
)

