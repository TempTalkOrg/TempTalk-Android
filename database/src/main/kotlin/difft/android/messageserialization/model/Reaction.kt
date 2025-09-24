package difft.android.messageserialization.model

import java.io.Serializable

data class Reaction(
    val emoji: String,
    val uid: String,
    val remove: Boolean = false,
    val originTimestamp: Long = 0,
    val realSource: RealSource? = null
):Serializable