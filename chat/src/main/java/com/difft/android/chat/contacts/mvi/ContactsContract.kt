package com.difft.android.chat.contacts.mvi

import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.chat.contacts.compose.DirectoryTab
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel

object ContactsContract {
    data class State(
        val pendingRemoval: List<PendingRemovalTile> = emptyList(),
        val expiringGroups: List<ExpiringGroupTile> = emptyList(),
        val selectedTab: DirectoryTab = DirectoryTab.Contacts,
        val contactSections: List<DirectorySection<ContactDirectoryRow>> = emptyList(),
        val groupSections: List<DirectorySection<GroupDirectoryRow>> = emptyList(),
        val indexLetters: List<String> = emptyList(),
        val selectedId: String? = null,
        val textSize: Int = TextSizeUtil.TEXT_SIZE_DEFAULT,
        val initialized: Boolean = false,
        val loading: Boolean = true,
        val hasUserSelectedTab: Boolean = false,
        val refreshing: Boolean = false,
    )

    sealed interface Intent {
        data object Refresh : Intent
        data object PullRefresh : Intent
        data class SelectTab(val tab: DirectoryTab) : Intent
        data class SelectId(val id: String?) : Intent
        data class OpenContact(val id: String) : Intent
        data class OpenGroup(val id: String) : Intent
        data object AddContact : Intent
    }

    sealed interface Effect {
        data class OpenContact(val id: String) : Effect
        data class OpenGroup(val id: String) : Effect
        data object AddContact : Effect
    }
}

data class DirectorySection<T>(val letter: String, val rows: List<T>)

data class ContactDirectoryRow(
    val id: String,
    val displayName: String,
    val contact: ContactorModel,
    val isSelf: Boolean = false,
    val isOfficial: Boolean = false,
    val messageExpirySeconds: Long = 0,
)

data class GroupDirectoryRow(
    val id: String,
    val displayName: String,
    val group: GroupModel,
    val memberCount: Int = 0,
    val messageExpirySeconds: Long = 0,
)

data class PendingRemovalTile(
    val row: ContactDirectoryRow,
    val daysLeft: Int,
    val displayDays: Int,
    val expireAt: Long,
    val deletedAt: Long,
)

data class ExpiringGroupTile(val row: GroupDirectoryRow, val daysLeft: Int, val expireTime: Long)
