package difft.android.messageserialization.model

import java.io.Serializable

data class Mention(
    val start: Int,
    val length: Int,
    val uid: String?,
    val type: Int
):Serializable

const val MENTIONS_ALL_ID = "MENTIONS_ALL"

const val MENTIONS_TYPE_NONE = -1
const val MENTIONS_TYPE_ALL = 1
const val MENTIONS_TYPE_ME = 2