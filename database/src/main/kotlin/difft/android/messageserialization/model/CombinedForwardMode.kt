package difft.android.messageserialization.model

import com.google.gson.annotations.SerializedName








enum class CombinedForwardMode {
    
    @SerializedName("UNKNOWN")
    UNKNOWN,

    
    @SerializedName("CONTAINS_COMBINED_FORWARD")
    CONTAINS_COMBINED_FORWARD,

    
    @SerializedName("ALL_COMBINED_FORWARD")
    ALL_COMBINED_FORWARD,

    
    @SerializedName("SUB_COMBINED_FORWARD")
    SUB_COMBINED_FORWARD,
}
