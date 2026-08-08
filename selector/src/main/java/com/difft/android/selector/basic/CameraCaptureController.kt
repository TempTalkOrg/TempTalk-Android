package com.difft.android.selector.basic

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.text.TextUtils
import android.view.View
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.config.PermissionEvent
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.dialog.PhotoItemSelectedDialog
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnItemClickListener
import com.difft.android.selector.permissions.PermissionChecker
import com.difft.android.selector.permissions.PermissionConfig
import com.difft.android.selector.permissions.PermissionResultCallback
import com.difft.android.selector.thread.PictureThreadUtils
import com.difft.android.selector.utils.ActivityCompatHelper
import com.difft.android.selector.utils.BitmapUtils
import com.difft.android.selector.utils.MediaStoreUtils
import com.difft.android.selector.utils.MediaUtils
import com.difft.android.selector.utils.PictureFileUtils
import com.difft.android.selector.utils.SdkVersionUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream

/**
 * In-grid camera-cell (Option A) capture: builds capture intents and parses the
 * result into a [LocalMedia]. Extracted from PictureCommonFragment (issue #1077).
 */
internal class CameraCaptureController(private val host: PictureCommonFragment) {

    private val config get() = host.selectorConfig

    fun openSelectedCamera() {
        when (config.chooseMode) {
            SelectMimeType.TYPE_ALL ->
                when (config.ofAllCameraType) {
                    SelectMimeType.ofImage() -> openImageCamera()
                    SelectMimeType.ofVideo() -> openVideoCamera()
                    else -> onSelectedOnlyCamera()
                }

            SelectMimeType.TYPE_IMAGE -> openImageCamera()
            SelectMimeType.TYPE_VIDEO -> openVideoCamera()
            SelectMimeType.TYPE_AUDIO -> host.openSoundRecording()
            else -> {}
        }
    }

    fun onSelectedOnlyCamera() {
        val selectedDialog = PhotoItemSelectedDialog.newInstance()
        selectedDialog.setOnItemClickListener(object : OnItemClickListener {
            override fun onItemClick(v: View, position: Int) {
                when (position) {
                    PhotoItemSelectedDialog.IMAGE_CAMERA -> openImageCamera()
                    PhotoItemSelectedDialog.VIDEO_CAMERA -> openVideoCamera()
                    else -> {}
                }
            }
        })
        selectedDialog.setOnDismissListener(object : PhotoItemSelectedDialog.OnDismissListener {
            override fun onDismiss(isCancel: Boolean, dialog: DialogInterface) {
                if (config.isOnlyCamera && isCancel) {
                    host.onKeyBackFragmentFinish()
                }
            }
        })
        selectedDialog.show(host.childFragmentManager, "PhotoItemSelectedDialog")
    }

    fun openImageCamera() {
        host.onPermissionExplainEvent(true, PermissionConfig.CAMERA)
        if (config.onPermissionsEventListener != null) {
            host.onApplyPermissionsEvent(PermissionEvent.EVENT_IMAGE_CAMERA, PermissionConfig.CAMERA)
        } else {
            PermissionChecker.getInstance().requestPermissions(host, PermissionConfig.CAMERA,
                object : PermissionResultCallback {
                    override fun onGranted() {
                        startCameraImageCapture()
                    }

                    override fun onDenied() {
                        host.handlePermissionDenied(PermissionConfig.CAMERA)
                    }
                })
        }
    }

    fun startCameraImageCapture() {
        if (ActivityCompatHelper.isDestroy(host.activity)) {
            return
        }
        host.onPermissionExplainEvent(false, arrayOf())
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(host.requireActivity().packageManager) != null) {
            val imageUri = MediaStoreUtils.createCameraOutImageUri(host.getAppContext(), config)
            if (imageUri != null) {
                if (config.isCameraAroundState) {
                    cameraIntent.putExtra(PictureConfig.CAMERA_FACING, PictureConfig.CAMERA_BEFORE)
                }
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
                host.startActivityForResult(cameraIntent, PictureConfig.REQUEST_CAMERA)
            }
        }
    }

    fun openVideoCamera() {
        host.onPermissionExplainEvent(true, PermissionConfig.CAMERA)
        if (config.onPermissionsEventListener != null) {
            host.onApplyPermissionsEvent(PermissionEvent.EVENT_VIDEO_CAMERA, PermissionConfig.CAMERA)
        } else {
            PermissionChecker.getInstance().requestPermissions(host, PermissionConfig.CAMERA,
                object : PermissionResultCallback {
                    override fun onGranted() {
                        startCameraVideoCapture()
                    }

                    override fun onDenied() {
                        host.handlePermissionDenied(PermissionConfig.CAMERA)
                    }
                })
        }
    }

    fun startCameraVideoCapture() {
        if (ActivityCompatHelper.isDestroy(host.activity)) {
            return
        }
        host.onPermissionExplainEvent(false, arrayOf())
        val cameraIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        if (cameraIntent.resolveActivity(host.requireActivity().packageManager) != null) {
            val videoUri = MediaStoreUtils.createCameraOutVideoUri(host.getAppContext(), config)
            if (videoUri != null) {
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, videoUri)
                if (config.isCameraAroundState) {
                    cameraIntent.putExtra(PictureConfig.CAMERA_FACING, PictureConfig.CAMERA_BEFORE)
                }
                cameraIntent.putExtra(PictureConfig.EXTRA_QUICK_CAPTURE, config.isQuickCapture)
                cameraIntent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, config.recordVideoMaxSecond)
                cameraIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, config.videoQuality)
                host.startActivityForResult(cameraIntent, PictureConfig.REQUEST_CAMERA)
            }
        }
    }

    /** Camera capture result callback processing. */
    fun dispatchHandleCamera(intent: Intent?) {
        PictureThreadUtils.executeByIo(object : PictureThreadUtils.SimpleTask<LocalMedia?>() {
            override fun doInBackground(): LocalMedia? {
                val outputPath = getOutputPath(intent)
                if (!TextUtils.isEmpty(outputPath)) {
                    config.cameraPath = outputPath!!
                }
                if (TextUtils.isEmpty(config.cameraPath)) {
                    return null
                }
                if (config.chooseMode == SelectMimeType.ofAudio()) {
                    copyOutputAudioToDir()
                }
                val media = buildLocalMedia(config.cameraPath)
                media.isCameraSource = true
                return media
            }

            override fun onSuccess(result: LocalMedia?) {
                PictureThreadUtils.cancel(this)
                if (result != null) {
                    onScannerScanFile(result)
                    host.dispatchCameraMediaResult(result)
                }
                config.cameraPath = ""
            }
        })
    }

    /** Copy the recorded audio file to the configured directory. */
    private fun copyOutputAudioToDir() {
        try {
            if (!TextUtils.isEmpty(config.outPutAudioDir)) {
                val inputStream: InputStream? = if (PictureMimeType.isContent(config.cameraPath)) {
                    PictureContentResolver.openInputStream(host.getAppContext(), Uri.parse(config.cameraPath))
                } else {
                    FileInputStream(config.cameraPath)
                }
                val audioFileName: String = if (TextUtils.isEmpty(config.outPutAudioFileName)) {
                    ""
                } else {
                    if (config.isOnlyCamera) {
                        config.outPutAudioFileName
                    } else {
                        System.currentTimeMillis().toString() + "_" + config.outPutAudioFileName
                    }
                }
                val outputFile = PictureFileUtils.createCameraFile(host.getAppContext(),
                    config.chooseMode, audioFileName, "", config.outPutAudioDir)
                val outputStream = FileOutputStream(outputFile.absolutePath)
                if (PictureFileUtils.writeFileFromIS(inputStream, outputStream)) {
                    MediaUtils.deleteUri(host.getAppContext(), config.cameraPath)
                    config.cameraPath = outputFile.absolutePath
                }
            }
        } catch (e: FileNotFoundException) {
            L.w(e) { "[PictureCommonFragment] handleAudioCameraResult error:" }
        }
    }

    /** Try to resolve the output path returned by a custom camera. */
    fun getOutputPath(data: Intent?): String? {
        if (data == null) {
            return null
        }
        var outPutUri: Uri? = data.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
        val cameraPath = config.cameraPath
        val isCameraFileExists = TextUtils.isEmpty(cameraPath) ||
            PictureMimeType.isContent(cameraPath) || File(cameraPath).exists()
        if ((config.chooseMode == SelectMimeType.ofAudio() || !isCameraFileExists) && outPutUri == null) {
            outPutUri = data.data
        }
        if (outPutUri == null) {
            return null
        }
        return if (PictureMimeType.isContent(outPutUri.toString())) outPutUri.toString() else outPutUri.path
    }

    /** Refresh the media gallery. */
    private fun onScannerScanFile(media: LocalMedia) {
        if (ActivityCompatHelper.isDestroy(host.activity)) {
            return
        }
        if (SdkVersionUtils.isQ()) {
            if (PictureMimeType.isHasVideo(media.mimeType) && PictureMimeType.isContent(media.path)) {
                PictureMediaScannerConnection(host.requireActivity(), media.realPath)
            }
        } else {
            val path = if (PictureMimeType.isContent(media.path)) media.realPath else media.path
            PictureMediaScannerConnection(host.requireActivity(), path)
            if (PictureMimeType.isHasImage(media.mimeType)) {
                val dirFile = File(path)
                val lastImageId = MediaUtils.getDCIMLastImageId(host.getAppContext(), dirFile.parent ?: "")
                if (lastImageId != -1) {
                    MediaUtils.removeMedia(host.getAppContext(), lastImageId)
                }
            }
        }
    }

    fun buildLocalMedia(absolutePath: String): LocalMedia {
        val media = LocalMedia.generateLocalMedia(host.getAppContext(), absolutePath)
        media.chooseModel = config.chooseMode
        if (SdkVersionUtils.isQ() && !PictureMimeType.isContent(absolutePath)) {
            media.sandboxPath = absolutePath
        } else {
            media.sandboxPath = null
        }
        if (config.isCameraRotateImage && PictureMimeType.isHasImage(media.mimeType)) {
            BitmapUtils.rotateImage(host.getAppContext(), absolutePath)
        }
        return media
    }

    /** RESULT_CANCELED cleanup for a camera request. */
    fun handleCameraCancel() {
        if (!TextUtils.isEmpty(config.cameraPath)) {
            MediaUtils.deleteUri(host.getAppContext(), config.cameraPath)
            config.cameraPath = ""
        }
    }
}
