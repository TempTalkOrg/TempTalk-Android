package difft.android.messageserialization.model

data class Draft(
    val content: String? = null,
    val quote: Quote? = null,
    val mentions: List<Mention> = emptyList(),
)