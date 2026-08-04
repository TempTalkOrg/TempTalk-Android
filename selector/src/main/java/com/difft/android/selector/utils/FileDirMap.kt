package com.difft.android.selector.utils

import android.content.Context
import android.os.Environment

import com.difft.android.selector.config.SelectMimeType

import java.io.File
import java.util.HashMap

object FileDirMap {
    private val dirMap = HashMap<Int, String>()

    @JvmStatic
    fun init(context: Context) {
        if (!ActivityCompatHelper.assertValidRequest(context)) {
            return
        }
        if (dirMap[SelectMimeType.TYPE_IMAGE] == null) {
            val externalFilesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val path = if (externalFilesDir != null && externalFilesDir.exists()) {
                externalFilesDir.path
            } else {
                context.cacheDir.path
            }
            dirMap[SelectMimeType.TYPE_IMAGE] = path
        }
        if (dirMap[SelectMimeType.TYPE_VIDEO] == null) {
            val externalFilesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val path = if (externalFilesDir != null && externalFilesDir.exists()) {
                externalFilesDir.path
            } else {
                context.cacheDir.path
            }
            dirMap[SelectMimeType.TYPE_VIDEO] = path
        }
        if (dirMap[SelectMimeType.TYPE_AUDIO] == null) {
            val externalFilesDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val path = if (externalFilesDir != null && externalFilesDir.exists()) {
                externalFilesDir.path
            } else {
                context.cacheDir.path
            }
            dirMap[SelectMimeType.TYPE_AUDIO] = path
        }
    }

    @JvmStatic
    fun getFileDirPath(context: Context, type: Int): String? {
        var dir = dirMap[type]
        if (dir == null) {
            init(context)
            dir = dirMap[type]
        }
        return dir
    }

    @JvmStatic
    fun clear() {
        dirMap.clear()
    }
}
