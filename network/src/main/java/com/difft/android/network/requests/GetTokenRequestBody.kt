package com.difft.android.network.requests

/**
 * parms[@"appid"] = appid;
 * parms[@"scope"] = @"NameRead,EmailRead";
 */
data class GetTokenRequestBody(
    val appid: String?,
    val scope: String?
)