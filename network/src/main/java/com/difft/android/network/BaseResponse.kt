package com.difft.android.network

data class BaseResponse<T>(
    val ver: Int,
    val status: Int,
    val reason: String?,
    var data: T?,
//    var networkException: NetworkException? = null,

    val account: String? = null,
    val vcode: String? = null,
    val email: String? = null,
    val requirePin: Boolean = false,
    val id: Long? = null,
    val idString: String? = null,
    val location: String? = null,

    val requirePasscode: Boolean = false,
    val passcodeSalt: String? = null,
    val screenLockTimeout: Int? = null
) {
    fun isSuccess() = status == 0

    private fun isFailed() = status != 0

    fun throwIfFailed(): BaseResponse<T> {
        if (isFailed()) {
            throw RuntimeException(reason)
        }
        return this
    }
}


