package com.difft.android.chat.setting.sync

/** Notify type 4 `changeType` for a confidential-mode change (0 mute, 1 block, 2 remark, 3 confidential). */
private const val NOTIFY_CHANGE_TYPE_CONFIDENTIAL_MODE = 3

/**
 * Conversation settings that are written locally first and synced to the server by
 * [com.difft.android.chat.jobs.SettingSyncJob]. Every other field keeps the wait-for-response path.
 *
 * @property key stable wire/serialisation name, also part of the job queue key
 * @property changeType the notify type 4 `changeType` that marks a change of this field
 */
enum class SyncField(val key: String, val changeType: Int) {
    CONFIDENTIAL_MODE("confidentialMode", NOTIFY_CHANGE_TYPE_CONFIDENTIAL_MODE);

    companion object {
        fun fromKey(key: String): SyncField? = entries.firstOrNull { it.key == key }
    }
}
