package com.difft.android.network.group

import android.content.Context
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.android.qualifiers.ApplicationContext
import org.difft.app.database.models.GroupModel
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
    suspend fun createGroup(@Body req: CreateGroupReq): BaseResponse<CreateGroupResp>

    @DELETE("v1/groups/{gid}")
    suspend fun deleteGroup(@Path("gid") gid: String): BaseResponse<Any>

    @POST("v1/groups/{gid}")
    suspend fun changeGroupSettings(@Path("gid") gid: String, @Body req: ChangeGroupSettingsReq): BaseResponse<GetGroupInfoResp>

    @GET("v1/groups/{gid}")
    suspend fun getGroupInfo(@Path("gid") gid: String): BaseResponse<GetGroupInfoResp>

    @GET("v1/groups")
    suspend fun getGroups(): BaseResponse<GetGroupsResp>

    @GET("v1/groups/{gid}/members")
    suspend fun getSelfInfo(@Path("gid") gid: String): BaseResponse<SelfInfoResp>

    @PUT("v1/groups/{gid}/members")
    suspend fun addMembers(@Path("gid") gid: String, @Body req: AddOrRemoveMembersReq): BaseResponse<CreateGroupResp>

    @HTTP(method = "DELETE", path = "v1/groups/{gid}/members", hasBody = true)
    suspend fun removeMembers(@Path("gid") gid: String, @Body req: AddOrRemoveMembersReq): BaseResponse<Any>

    @POST("v1/groups/{gid}/members/{uid}")
    suspend fun changeSelfSettingsInGroup(@Path("gid") gid: String, @Path("uid") uid: String, @Body req: ChangeSelfSettingsInGroupReq): BaseResponse<Any>

    @POST("v1/groups/{gid}/members/{uid}")
    suspend fun changeMemberRole(@Path("gid") gid: String, @Path("uid") uid: String, @Body req: ChangeRolepReq): BaseResponse<Any>

    @GET("v1/groups/invitation/{gid}")
    suspend fun getInviteCode(@Path("gid") gid: String): BaseResponse<InviteCodeResp>

    @GET("v1/groups/invitation/groupInfo/{inviteCode}")
    suspend fun getGroupInfoByInviteCode(@Path("inviteCode") inviteCode: String): BaseResponse<GroupInfoByInviteCodeResp>

    @PUT("v1/groups/invitation/join/{inviteCode}")
    suspend fun joinGroupByInviteCode(@Path("inviteCode") inviteCode: String): BaseResponse<JoinGroupByInviteCodeResp>

}

class GroupRepo
@Inject
constructor(
    @param:ApplicationContext context: Context,
    @param:ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient
) {

    private val groupService: GroupService = httpClient.getService(GroupService::class.java)

    suspend fun createGroup(req: CreateGroupReq): BaseResponse<CreateGroupResp> {
        return groupService.createGroup(req)
    }

    suspend fun getGroups(): List<GroupModel> {
        val resp = groupService.getGroups()
        return resp.data?.groups?.map { from(it) } ?: emptyList()
    }

    fun from(groupResp: GroupResp): GroupModel {
        return GroupModel().apply {
            this.gid = groupResp.gid
            this.name = groupResp.name
            this.messageExpiry = groupResp.messageExpiry
            this.avatar = groupResp.avatar
            this.status = groupResp.status
            this.invitationRule = groupResp.invitationRule
            this.linkInviteSwitch = groupResp.linkInviteSwitch
            this.version = groupResp.version
            this.remindCycle = groupResp.remindCycle
            this.anyoneRemove = groupResp.anyoneRemove
            this.rejoin = groupResp.rejoin
            this.publishRule = groupResp.publishRule
        }
    }

    suspend fun getGroupInfo(gid: String): BaseResponse<GetGroupInfoResp> {
        return groupService.getGroupInfo(gid)
    }

    suspend fun leaveGroup(gid: String, req: AddOrRemoveMembersReq): BaseResponse<Any> {
        return groupService.removeMembers(gid, req)
    }

    suspend fun deleteGroup(gid: String): BaseResponse<Any> {
        return groupService.deleteGroup(gid)
    }

    suspend fun addMembers(gid: String, list: List<String>): BaseResponse<CreateGroupResp> {
        return groupService.addMembers(gid, AddOrRemoveMembersReq(list))
    }

    suspend fun removeMembers(gid: String, list: List<String>): BaseResponse<Any> {
        return groupService.removeMembers(gid, AddOrRemoveMembersReq(list))
    }

    suspend fun changeMemberRole(gid: String, uid: String, changeRoleReq: ChangeRolepReq): BaseResponse<Any> {
        return groupService.changeMemberRole(gid, uid, changeRoleReq)
    }

    suspend fun changeSelfSettingsInGroup(gid: String, uid: String, req: ChangeSelfSettingsInGroupReq): BaseResponse<Any> {
        return groupService.changeSelfSettingsInGroup(gid, uid, req)
    }

    suspend fun changeGroupSettings(gid: String, changeGroupSettingsReq: ChangeGroupSettingsReq): BaseResponse<GetGroupInfoResp> {
        return groupService.changeGroupSettings(gid, changeGroupSettingsReq)
    }

    suspend fun getInviteCode(gid: String): BaseResponse<InviteCodeResp> {
        return groupService.getInviteCode(gid)
    }

    suspend fun getGroupInfoByInviteCode(inviteCode: String): BaseResponse<GroupInfoByInviteCodeResp> {
        return groupService.getGroupInfoByInviteCode(inviteCode)
    }

    suspend fun joinGroupByInviteCodeResp(inviteCode: String): BaseResponse<JoinGroupByInviteCodeResp> {
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


