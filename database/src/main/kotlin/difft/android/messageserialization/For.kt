package difft.android.messageserialization

/**
 * type:
 * 0: single chat
 * 1: group chat
 */
sealed class For(val id: String, val typeValue: Int) {
    override fun hashCode(): Int {
        return id.hashCode() + typeValue
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is For) return false
        return this.id == other.id && this.typeValue == other.typeValue
    }

    class Account(id: String) : For(id, 0)


    class Group(id: String) : For(id, 1)
}
