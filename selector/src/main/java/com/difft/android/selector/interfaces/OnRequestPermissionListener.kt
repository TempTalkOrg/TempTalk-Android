package com.difft.android.selector.interfaces

interface OnRequestPermissionListener {
    fun onCall(permissionArray: Array<String>, isResult: Boolean)
}
