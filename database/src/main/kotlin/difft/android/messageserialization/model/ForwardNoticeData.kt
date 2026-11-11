package difft.android.messageserialization.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable







data class ForwardNoticeData(
    val scene: Scene,
    
    val sourceAuthorIds: List<String>,
    
    val messageCount: Int,
    
    val combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
) : Serializable {
    





    enum class Scene {
        @SerializedName("SINGLE")
        SINGLE,
        @SerializedName("ONE_BY_ONE")
        ONE_BY_ONE,
        @SerializedName("COMBINED")
        COMBINED,
        @SerializedName("SAVE_TO_NOTES")
        SAVE_TO_NOTES,
    }
}
