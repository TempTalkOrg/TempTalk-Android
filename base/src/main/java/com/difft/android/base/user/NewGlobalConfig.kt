package com.difft.android.base.user

import android.graphics.drawable.Drawable
import com.google.gson.annotations.SerializedName
import javax.annotation.concurrent.Immutable

data class NewGlobalConfig(
    val code: Int,
    val `data`: Data?,
    val sign: String?
)

data class Data(
    val audit: List<String>?,
    val avatarFile: String,
    val certExpireRemind: Int,
    val conversation: Conversation?,
    val disappearanceTimeInterval: DisappearanceTimeInterval?,
    val emojiReaction: List<String>?,
    val fileSuffixRegex: List<String>?,
    val group: Group?,
    val hosts: List<Host>?,
    val maxStickCount: Int,
    val meeting: Meeting?,
    val message: MessageX?,
    val recall: Recall?,
    val spookyBotId: String?,
    val srvs: Srvs?,
    val task: Task?,
    val vega: String?,
    val voteExpireConfig: List<Int>?,
    val weaAppBotId: String?,
    var verifyCodePattern: String?,
    var enableDoh: Boolean,
    val dohv2: DohV2,
    val floatMenuActions: List<FloatMenuAction>?,
    val shortUrl: ShortUrl?,
    val call: CallConfig?,
    val domains: List<Domain>?,
    val services: List<Service>?
)
data class Host(
    val certType: String?,
    val name: String?,
    val servTo: String?
)


data class Srvs(
    val botconf: String?,
    val caption: String?,
    val calendar: String?,
    val scheduler: String?,
    val chat: String?,
    val conversationConfig: String?,
    val device: String?,
    val difftoss: String?,
    val dot: String?,
    val fileSharing: String?,
    val ldap: String?,
    val recording: String?,
    val risk: String?,
    val task: String?,
    val voice: String?,
    val call: String?,
    val vote: String?,
    val workflow: String?,
    val gifs: String?,
)

data class Task(
    val maxAssigneeCount: Int
)

data class Name(
    val cn_name: String,
    val en_name: String
)

data class Icon(
    val svg: String,
    val png: String,
    var drawable: Drawable? = null
)

data class DohV2(
    val android: DohConfig
)

data class DohConfig(
    val enabled: Boolean,
    val enabledVersions: List<Int>?
)

data class FloatMenuAction(
    val appId: String,
    val name: FloatMenuActionName?,
    val jumpUrl: String?,
    val iconUrl: String?
)

data class FloatMenuActionName(
    @SerializedName("zh-cn")
    val zhCn: String?,
    @SerializedName("en-us")
    val enUs: String?
)

data class ShortUrl(
    val hosts: List<String>?,
    val expirationTime: Int,
    val clientSupportedHosts: List<String>?
)

data class Conversation(
    val blockRegex: String
)

data class DisappearanceTimeInterval(
    val conversation: ConversationX?,
    val default: Long,
    val message: Message?,
    val messageArchivingTimeOptionValues: List<Long>?,
    val otherMessageArchivingTimeOptionValues: List<Long>?
)

data class Group(
    val chatTunnelSecurityThreshold: Double,
    val chatWithoutReceiptThreshold: Double,
    val groupRemind: GroupRemind?,
    val largeGroupThreshold: Double,
    val meetingWithoutRingThreshold: Double,
    val messageArchivingTimeOptionValues: List<Long>?
)

data class Meeting(
    val maxAudioPushStreamCount: Double,
    val maxVideoPushStreamCount: Double,
    val meetingInviteForbid: List<String>?,
    val meetingPreset: List<String>?,
    val messageDisappearTime: Double,
    val openMuteOther: Boolean
)

data class MessageX(
    val tunnelSecurityEnable: Boolean, //发消息需不需要传递legacyContent明文内容，默认不传
    val tunnelSecurityEnds: List<String>?,
    val tunnelSecurityForced: Boolean
)

data class Recall(
    val editableInterval: Long,
    val timeoutInterval: Long
)

data class ConversationX(
    val default: Double,
    val group: Double,
    val me: Double,
    val other: Double
)

data class Message(
    val default: Long,
    val me: Long
)

data class GroupRemind(
    val remindCycle: List<String>?,
    val remindDescription: String,
    val remindMonthDay: Double,
    val remindTime: Double,
    val remindWeekDay: Double
)

data class CallConfig(
    val autoLeave: AutoLeave,
    val chatPresets: List<String>,
    val chat: CallChat?,
    val muteOtherEnabled: Boolean = false,
    val createCallMsg: Boolean = false,
    val countdownTimer: CountdownTimer,
    val denoise: DeNoiseConfig? = null,
    val callServers: CallServers? = null
)

data class CallServers(
    val clusters: List<CallClusterInfo>?,
)

data class CallClusterInfo(
    val id: String,
    val global_url: String?,
    val mainland_url: String?
)

@Immutable
data class DeNoiseConfig(
    val bluetooth: BluetoothConfig?,
)

@Immutable
data class BluetoothConfig(
    val excludedNameRegex: String?,
)

data class CallChat(
    val autoHideTimeout: Long = 9000L,
)

val defaultBarrageTexts = listOf(
    "Good 👍",
    "Agree ✅",
    "Disagree ❌",
    "Bad 😝",
    "Can't hear you. Bad Signal",
    "Can't hear you. Your voice is too low",
    "Please make screen bigger",
    "Please go faster",
    "Gotta go, bye"
)

data class AutoLeave(
    val promptReminder: PromptReminder,
    val runAfterReminderTimeout: Long = 180000
)

data class PromptReminder(
    val silenceTimeout: Long = 300000,
    val soloMemberTimeout: Long = 300000
)

data class CountdownTimer(
    val warningThreshold: Long = 10000,
    val shakingThreshold: Long = 5000
)

data class Domain(
    val domain: String?,
    val certType: String?,
    val label: String?
)

data class Service(
    val name: String?,
    val path: String?,
    val domains: List<String>?
)