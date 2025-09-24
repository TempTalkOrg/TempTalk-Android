package com.difft.android.network.requests

data class AddContactorRequestBody(
    val uid: String,
    val source: AddContactorSource?,
    val action: String?,
)

data class AddContactorSource(
    val type: String?,
    val groupID: String?,
)