package difft.android.messageserialization.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable









data class MessageActivityNoticeData(
    val type: Type,
    
    val sourceAuthorIds: List<String>,
    
    val messageCount: Int,
    
    val combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
) : Serializable {

    






    enum class Type {
        @SerializedName("COPY")
        COPY,
    }
}
