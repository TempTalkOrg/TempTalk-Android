package com.difft.android.chat.setting.sync


private const val NOTIFY_CHANGE_TYPE_CONFIDENTIAL_MODE = 3








enum class SyncField(val key: String, val changeType: Int) {
    CONFIDENTIAL_MODE("confidentialMode", NOTIFY_CHANGE_TYPE_CONFIDENTIAL_MODE);

    companion object {
        fun fromKey(key: String): SyncField? = entries.firstOrNull { it.key == key }
    }
}
