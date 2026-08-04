package com.difft.android.selector.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils

import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.basic.PictureContentResolver
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.interfaces.OnCallbackListener
import com.difft.android.selector.thread.PictureThreadUtils

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL

object DownloadFileUtils {

    /** Save the resource at [path] into MediaStore, reporting the resulting path to [listener]. */
    @JvmStatic
    fun saveLocalFile(
        context: Context,
        path: String,
        mimeType: String?,
        listener: OnCallbackListener<String>?
    ) {
        PictureThreadUtils.executeByIo(object : PictureThreadUtils.SimpleTask<String?>() {

            override fun doInBackground(): String? {
                try {
                    val uri: Uri?
                    val contentValues = ContentValues()
                    val time = ValueOf.toString(System.currentTimeMillis())
                    if (PictureMimeType.isHasAudio(mimeType)) {
                        contentValues.put(MediaStore.Audio.Media.DISPLAY_NAME, DateUtils.getCreateFileName("AUD_"))
                        contentValues.put(
                            MediaStore.Audio.Media.MIME_TYPE,
                            if (mimeType.isNullOrEmpty() ||
                                mimeType.startsWith(PictureMimeType.MIME_TYPE_PREFIX_VIDEO) ||
                                mimeType.startsWith(PictureMimeType.MIME_TYPE_PREFIX_IMAGE)
                            ) PictureMimeType.MIME_TYPE_AUDIO else mimeType
                        )
                        if (SdkVersionUtils.isQ()) {
                            contentValues.put(MediaStore.Audio.Media.DATE_TAKEN, time)
                            contentValues.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                        } else {
                            val dir = if (TextUtils.equals(Environment.getExternalStorageState(), Environment.MEDIA_MOUNTED)) {
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                            } else {
                                File(FileDirMap.getFileDirPath(context, SelectMimeType.TYPE_AUDIO)!!)
                            }
                            contentValues.put(
                                MediaStore.MediaColumns.DATA,
                                dir.absolutePath + File.separator + DateUtils.getCreateFileName("AUD_") + PictureMimeType.AMR
                            )
                        }
                        uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                    } else if (PictureMimeType.isHasVideo(mimeType)) {
                        contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, DateUtils.getCreateFileName("VID_"))
                        contentValues.put(
                            MediaStore.Video.Media.MIME_TYPE,
                            if (mimeType.isNullOrEmpty() ||
                                mimeType.startsWith(PictureMimeType.MIME_TYPE_PREFIX_AUDIO) ||
                                mimeType.startsWith(PictureMimeType.MIME_TYPE_PREFIX_IMAGE)
                            ) PictureMimeType.MIME_TYPE_VIDEO else mimeType
                        )
                        if (SdkVersionUtils.isQ()) {
                            contentValues.put(MediaStore.Video.Media.DATE_TAKEN, time)
                            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                        } else {
                            val dir = if (TextUtils.equals(Environment.getExternalStorageState(), Environment.MEDIA_MOUNTED)) {
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                            } else {
                                File(FileDirMap.getFileDirPath(context, SelectMimeType.TYPE_VIDEO)!!)
                            }
                            contentValues.put(
                                MediaStore.MediaColumns.DATA,
                                dir.absolutePath + File.separator + DateUtils.getCreateFileName("VID_") + PictureMimeType.MP4
                            )
                        }
                        uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    } else {
                        contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, DateUtils.getCreateFileName("IMG_"))
                        contentValues.put(
                            MediaStore.Images.Media.MIME_TYPE,
                            if (mimeType.isNullOrEmpty() ||
                                mimeType.startsWith(PictureMimeType.MIME_TYPE_PREFIX_AUDIO) ||
                                mimeType.startsWith(PictureMimeType.MIME_TYPE_PREFIX_VIDEO)
                            ) PictureMimeType.MIME_TYPE_IMAGE else mimeType
                        )
                        if (SdkVersionUtils.isQ()) {
                            contentValues.put(MediaStore.Images.Media.DATE_TAKEN, time)
                            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, PictureMimeType.DCIM)
                        } else {
                            if (PictureMimeType.isHasGif(mimeType) || PictureMimeType.isUrlHasGif(path)) {
                                val dir = if (TextUtils.equals(Environment.getExternalStorageState(), Environment.MEDIA_MOUNTED)) {
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                                } else {
                                    File(FileDirMap.getFileDirPath(context, SelectMimeType.TYPE_IMAGE)!!)
                                }
                                contentValues.put(
                                    MediaStore.MediaColumns.DATA,
                                    dir.absolutePath + File.separator + DateUtils.getCreateFileName("IMG_") + PictureMimeType.GIF
                                )
                            }
                        }
                        uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    }
                    if (uri != null) {
                        val inputStream: InputStream? = if (PictureMimeType.isHasHttp(path)) {
                            URL(path).openStream()
                        } else {
                            if (PictureMimeType.isContent(path)) {
                                PictureContentResolver.openInputStream(context, Uri.parse(path))
                            } else {
                                FileInputStream(path)
                            }
                        }
                        val outputStream: OutputStream? = PictureContentResolver.openOutputStream(context, uri)
                        if (PictureFileUtils.writeFileFromIS(inputStream, outputStream)) {
                            return PictureFileUtils.getPath(context, uri)
                        }
                    }
                } catch (e: Exception) {
                    L.w(e) { "[DownloadFileUtils] saveLocalFile error:" }
                }
                return null
            }

            override fun onSuccess(result: String?) {
                PictureThreadUtils.cancel(this)
                listener?.onCall(result)
            }
        })
    }
}
