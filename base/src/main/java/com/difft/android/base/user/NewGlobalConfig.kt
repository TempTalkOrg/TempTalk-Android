package com.difft.android.base.user

import javax.annotation.concurrent.Immutable

data class NewGlobalConfig(
    val code: Int = 0,
    val data: Data? = null
)

data class Data(
    val audit: List<String>? = null,
    val avatarFile: String? = null,
    val avatarStorage: List<String>? = null,
    val conversation: Conversation? = null,
    val disappearanceTimeInterval: DisappearanceTimeInterval? = null,
    val emojiReaction: List<String>? = null,
    val group: Group? = null,
    val hosts: List<Host>? = null,
    val maxStickCount: Int = 0,
    val meeting: Meeting? = null,
    val message: MessageX? = null,
    val recall: Recall? = null,
    val srvs: Srvs? = null,
    val call: CallConfig? = null,
    val domains: List<Domain>? = null,
    val services: List<Service>? = null,
    val chatFolder: ChatFolder? = null,
    val proxy: ProxyConfigData? = null
)

/**
 * Proxy-specific section of the global config. Drives the self-hosted-proxy
 * routing: while the proxy is active, [TunnelDomains] is the single source of
 * truth for BOTH the tunnel-host whitelist (which hosts go through the proxy
 * vs direct) AND the domains each client module is forced onto. See
 * `docs/claude/self-hosted-proxy-design.md` §7.3.
 */
data class ProxyConfigData(
    val tunnelDomains: TunnelDomains? = null
)

/**
 * Per-module domains used when the proxy is active. Each list is a set of FQDNs
 * that the relay's `ssl_preread` whitelist must also accept (kept in sync
 * server-side). [chat] feeds HTTP/WS host selection ([com.difft.android] URL
 * manager); [call] feeds the meeting/call-service connection domains.
 */
data class TunnelDomains(
    val chat: List<String>? = null,
    val call: List<String>? = null
)

/**
 * Legacy host list (paired with the old `srvs` routing via `servTo`). No longer
 * read for URL building in this app — routing now uses `services` + `domains`
 * (see ServiceUrlResolver), aligned with iOS/Desktop. Kept only for
 * cross-platform config parity (server still sends it).
 */
data class Host(
    val certType: String? = null,
    val name: String? = null,
    val servTo: String? = null
)

/**
 * Legacy service-path config. No longer read for URL building in this app —
 * routing now uses `services` + `domains` (see ServiceUrlResolver), aligned with
 * iOS/Desktop. Kept only for cross-platform config parity (server still sends it).
 */
data class Srvs(
    val chat: String? = null,
    val voice: String? = null,
    val fileSharing: String? = null,
    val miniProgram: String? = null,
    val call: String? = null,
    val whisperX: String? = null
)

data class Conversation(
    val blockRegex: String? = null
)

data class DisappearanceTimeInterval(
    val conversation: ConversationX? = null,
    val default: Long = 0,
    val message: Message? = null,
    val messageArchivingTimeOptionValues: List<Long>? = null,
    val activeConversation: ActiveConversation? = null
)

/**
 * 活跃会话过期配置（秒）
 * 用于控制空会话的清理时间
 * - group: 群聊会话
 * - other: 单聊会话
 * - me: 自己的会话（Saved）
 * - default: 默认值
 */
data class ActiveConversation(
    val default: Long = 604800,
    val me: Long = 0,
    val other: Long = 604800,
    val group: Long = 604800
)

data class Group(
    val chatTunnelSecurityThreshold: Double = 0.0,
    val chatWithoutReceiptThreshold: Double = 0.0,
    val confidentialModeThreshold: Int = 20,
    val groupRemind: GroupRemind? = null,
    val largeGroupThreshold: Double = 0.0,
    val meetingWithoutRingThreshold: Double = 0.0,
    val membersMaxSize: Int = 200,
    val messageArchivingTimeOptionValues: List<Long>? = null,
    val encryptionEnabled: Boolean = false,
    // Independent gate for the encrypted-group key reset/rotation entry.
    // false → hide the reset entry even when encryptionEnabled is true.
    val encryptionKeyResetEnabled: Boolean = false
)

data class Meeting(
    val maxAudioPushStreamCount: Double = 0.0,
    val maxVideoPushStreamCount: Double = 0.0,
    val meetingInviteForbid: List<String>? = null,
    val meetingPreset: List<String>? = null,
    val messageDisappearTime: Double = 0.0,
    val openMuteOther: Boolean = false
)

data class MessageX(
    val tunnelSecurityEnds: List<String>? = null,
    val tunnelSecurityForced: Boolean = false
)

data class Recall(
    val editableInterval: Long = 0,
    val timeoutInterval: Long = 0
)

data class ConversationX(
    val default: Double = 0.0,
    val group: Double = 0.0,
    val me: Double = 0.0,
    val other: Double = 0.0
)

data class Message(
    val default: Long = 0,
    val me: Long = 0
)

data class GroupRemind(
    val remindCycle: List<String>? = null,
    val remindDescription: String? = null,
    val remindMonthDay: Double = 0.0,
    val remindTime: Double = 0.0,
    val remindWeekDay: Double = 0.0
)

data class CallConfig(
    val autoLeave: AutoLeave? = null,
    val chatPresets: List<String>? = null,
    val chat: CallChat? = null,
    val muteOtherEnabled: Boolean = false,
    val createCallMsg: Boolean = false,
    val countdownTimerEnabled: Boolean = false,
    val countdownTimer: CountdownTimer? = null,
    val denoise: DeNoiseConfig? = null,
    val callServers: CallServers? = null,
    val bubbleMessage: BubbleMessage? = null,
    val chatMessage: ChatMessage? = null
)

data class ChatMessage(
    val maxLength: Int = 30,
)

data class BubbleMessage(
    val emojiPresets: List<String>,
    val textPresets: List<String>,
    val columns: List<Int>,
    val baseSpeed: Long,
    val deltaSpeed: Long
)

data class CallServers(
    val clusters: List<CallClusterInfo>? = null
)

data class CallClusterInfo(
    val id: String? = null,
    val global_url: String? = null,
    val mainland_url: String? = null
)

@Immutable
data class DeNoiseConfig(
    val bluetooth: BluetoothConfig? = null,
    val mode: String? = null
)

@Immutable
data class BluetoothConfig(
    val excludedNameRegex: String? = null
)

data class CallChat(
    val autoHideTimeout: Long = 9000L
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
    val promptReminder: PromptReminder? = null,
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
    val domain: String? = null,
    val certType: String? = null,
    val label: String? = null
)

data class Service(
    val name: String? = null,
    val path: String? = null,
    val domains: List<String>? = null
)

data class ChatFolder(
    val maxFolderCount: Int = 10
)