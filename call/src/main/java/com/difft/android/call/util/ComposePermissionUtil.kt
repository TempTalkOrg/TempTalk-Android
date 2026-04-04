package com.difft.android.call.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberPermissionChecker(
    viewModel: LCallViewModel,
    permission: String,
    onGranted: () -> Unit,
    onDenied: (() -> Unit)? = null,
): () -> Unit {
    val permState = rememberPermissionState(permission, onPermissionResult = {
        viewModel.callUiController.setRequestPermissionStatus(false)
    })
    var askedEver by remember { mutableStateOf(false) }
    var requestedByUser by remember { mutableStateOf(false) }


    LaunchedEffect(permState.status) {
        if (permState.status.isGranted && requestedByUser) {
            requestedByUser = false
            onGranted()
            L.i { "[call] PermissionChecker permission granted" }
        } else {
            L.i { "[call] PermissionChecker permission not granted" }
        }
    }

    return {
        when (permState.status) {
            is PermissionStatus.Granted -> onGranted()
            is PermissionStatus.Denied -> {
                val isFirst = !askedEver
                val isDenied = askedEver // User already saw the permission request

                when {
                    // 首次请求
                    isFirst -> {
                        askedEver = true
                        requestedByUser = true
                        permState.launchPermissionRequest()
                        viewModel.callUiController.setRequestPermissionStatus(true)
                    }

                    // 用户拒绝
                    isDenied -> onDenied?.invoke()
                }
            }
        }
    }
}


fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}