package com.difft.android.selector.basic

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.config.Crop
import com.difft.android.selector.config.CustomIntentKey
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnCallbackIndexListener
import com.difft.android.selector.interfaces.OnCallbackListener
import com.difft.android.selector.interfaces.OnKeyValueResultCallbackListener
import com.difft.android.selector.thread.PictureThreadUtils
import com.difft.android.selector.utils.DateUtils
import com.difft.android.selector.utils.FileDirMap
import com.difft.android.selector.utils.SdkVersionUtils
import com.difft.android.selector.utils.ToastUtils
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * crop → compress → sandbox-transform → result handoff (image-load/ucrop/Luban
 * seams). Extracted from PictureCommonFragment (issue #1077); 1:1 semantics.
 */
internal class MediaTransformPipeline(private val host: PictureCommonFragment) {

    private val config get() = host.selectorConfig

    /** Dispatch the transform (crop / compress / sandbox) chain. */
    fun dispatchTransformResult() {
        if (host.validator.checkCompleteSelectLimit()) {
            return
        }
        if (!host.isAdded) {
            return
        }
        val result = ArrayList(config.selectedResult)
        if (checkCropValidity()) {
            onCrop(result)
        } else if (checkOldCropValidity()) {
            onOldCrop(result)
        } else if (checkCompressValidity()) {
            onCompress(result)
        } else if (checkOldCompressValidity()) {
            onOldCompress(result)
        } else {
            onResultEvent(result)
        }
    }

    fun onCrop(result: ArrayList<LocalMedia>) {
        var srcUri: Uri? = null
        var destinationUri: Uri? = null
        val dataCropSource = ArrayList<String>()
        for (i in result.indices) {
            val media = result[i]
            val availablePath = media.availablePath
            // Only add valid paths, guarding against possible empty paths on e.g. Android 9.
            if (!availablePath.isNullOrEmpty()) {
                dataCropSource.add(availablePath)
            }
            if (srcUri == null && PictureMimeType.isHasImage(media.mimeType)) {
                val currentCropPath = availablePath
                if (!currentCropPath.isNullOrEmpty()) {
                    srcUri = if (PictureMimeType.isContent(currentCropPath) || PictureMimeType.isHasHttp(currentCropPath)) {
                        Uri.parse(currentCropPath)
                    } else {
                        Uri.fromFile(File(currentCropPath))
                    }
                    val fileName = DateUtils.getCreateFileName("CROP_") + ".jpg"
                    val context = host.getAppContext()
                    val externalFilesDir = File(FileDirMap.getFileDirPath(context, SelectMimeType.TYPE_IMAGE)!!)
                    val outputFile = File(externalFilesDir.absolutePath, fileName)
                    destinationUri = Uri.fromFile(outputFile)
                }
            }
        }
        if (dataCropSource.isNotEmpty() && srcUri != null && destinationUri != null) {
            config.cropFileEngine!!.onStartCrop(host, srcUri, destinationUri, dataCropSource, Crop.REQUEST_CROP)
        } else {
            onResultEvent(result)
        }
    }

    fun onOldCrop(result: ArrayList<LocalMedia>) {
        var currentLocalMedia: LocalMedia? = null
        for (i in result.indices) {
            val item = result[i]
            if (PictureMimeType.isHasImage(result[i].mimeType)) {
                currentLocalMedia = item
                break
            }
        }
        config.cropEngine!!.onStartCrop(host, currentLocalMedia!!, result, Crop.REQUEST_CROP)
    }

    fun onCompress(result: ArrayList<LocalMedia>) {
        host.showLoading()
        val queue = ConcurrentHashMap<String, LocalMedia>()
        val source = ArrayList<Uri>()
        for (i in result.indices) {
            val media = result[i]
            val availablePath = media.availablePath
            if (PictureMimeType.isHasHttp(availablePath)) {
                continue
            }
            if (config.isCheckOriginalImage && config.isOriginalSkipCompress) {
                continue
            }
            if (PictureMimeType.isHasImage(media.mimeType)) {
                val uri = if (PictureMimeType.isContent(availablePath)) Uri.parse(availablePath) else Uri.fromFile(File(availablePath))
                source.add(uri)
                queue[availablePath] = media
            }
        }
        if (queue.size == 0) {
            onResultEvent(result)
        } else {
            config.compressFileEngine!!.onStartCompress(host.getAppContext(), source, object : OnKeyValueResultCallbackListener {
                override fun onCallback(srcPath: String?, resultPath: String?) {
                    if (TextUtils.isEmpty(srcPath)) {
                        onResultEvent(result)
                    } else {
                        val key = srcPath!!
                        val media = queue[key]
                        if (media != null) {
                            if (SdkVersionUtils.isQ()) {
                                if (!TextUtils.isEmpty(resultPath) && (resultPath!!.contains("Android/data/") || resultPath.contains("data/user/"))) {
                                    media.compressPath = resultPath
                                    media.compressed = !TextUtils.isEmpty(resultPath)
                                    media.sandboxPath = media.compressPath
                                }
                            } else {
                                media.compressPath = resultPath
                                media.compressed = !TextUtils.isEmpty(resultPath)
                            }
                            queue.remove(key)
                        }
                        if (queue.size == 0) {
                            onResultEvent(result)
                        }
                    }
                }
            })
        }
    }

    fun onOldCompress(result: ArrayList<LocalMedia>) {
        host.showLoading()
        if (config.isCheckOriginalImage && config.isOriginalSkipCompress) {
            onResultEvent(result)
        } else {
            config.compressEngine!!.onStartCompress(host.getAppContext(), result, object : OnCallbackListener<ArrayList<LocalMedia>> {
                override fun onCall(data: ArrayList<LocalMedia>?) {
                    onResultEvent(data!!)
                }
            })
        }
    }

    fun checkCropValidity(): Boolean {
        if (config.cropFileEngine != null) {
            val filterSet = HashSet<String>()
            val filters = config.skipCropList
            if (filters.isNotEmpty()) {
                filterSet.addAll(filters)
            }
            if (config.selectCount == 1) {
                val mimeType = config.resultFirstMimeType
                val isHasImage = PictureMimeType.isHasImage(mimeType)
                if (isHasImage) {
                    if (filterSet.contains(mimeType)) {
                        return false
                    }
                }
                return isHasImage
            } else {
                var notSupportCropCount = 0
                for (i in 0 until config.selectCount) {
                    val media = config.selectedResult[i]
                    if (PictureMimeType.isHasImage(media.mimeType)) {
                        if (filterSet.contains(media.mimeType)) {
                            notSupportCropCount++
                        }
                    }
                }
                return notSupportCropCount != config.selectCount
            }
        }
        return false
    }

    fun checkOldCropValidity(): Boolean {
        if (config.cropEngine != null) {
            val filterSet = HashSet<String>()
            val filters = config.skipCropList
            if (filters.isNotEmpty()) {
                filterSet.addAll(filters)
            }
            if (config.selectCount == 1) {
                val mimeType = config.resultFirstMimeType
                val isHasImage = PictureMimeType.isHasImage(mimeType)
                if (isHasImage) {
                    if (filterSet.contains(mimeType)) {
                        return false
                    }
                }
                return isHasImage
            } else {
                var notSupportCropCount = 0
                for (i in 0 until config.selectCount) {
                    val media = config.selectedResult[i]
                    if (PictureMimeType.isHasImage(media.mimeType)) {
                        if (filterSet.contains(media.mimeType)) {
                            notSupportCropCount++
                        }
                    }
                }
                return notSupportCropCount != config.selectCount
            }
        }
        return false
    }

    fun checkCompressValidity(): Boolean {
        if (config.compressFileEngine != null) {
            for (i in 0 until config.selectCount) {
                val media = config.selectedResult[i]
                if (PictureMimeType.isHasImage(media.mimeType)) {
                    return true
                }
            }
        }
        return false
    }

    fun checkOldCompressValidity(): Boolean {
        if (config.compressEngine != null) {
            for (i in 0 until config.selectCount) {
                val media = config.selectedResult[i]
                if (PictureMimeType.isHasImage(media.mimeType)) {
                    return true
                }
            }
        }
        return false
    }

    fun checkTransformSandboxFile(): Boolean {
        return SdkVersionUtils.isQ() && config.uriToFileTransformEngine != null
    }

    fun checkOldTransformSandboxFile(): Boolean {
        return SdkVersionUtils.isQ() && config.sandboxFileEngine != null
    }

    private fun dispatchUriToFileTransformResult(result: ArrayList<LocalMedia>) {
        host.showLoading()
        host.results.onCallBackResult(result)
    }

    /** SDK > 29: copy external resources into the app sandbox. */
    private fun uriToFileTransform29(result: ArrayList<LocalMedia>) {
        host.showLoading()
        val queue = ConcurrentHashMap<String, LocalMedia>()
        for (i in result.indices) {
            val media = result[i]
            queue[media.path] = media
        }
        if (queue.size == 0) {
            dispatchUriToFileTransformResult(result)
        } else {
            PictureThreadUtils.executeByIo(object : PictureThreadUtils.SimpleTask<ArrayList<LocalMedia>>() {
                override fun doInBackground(): ArrayList<LocalMedia> {
                    for (entry in queue.entries) {
                        val media = entry.value
                        if (config.isCheckOriginalImage || TextUtils.isEmpty(media.sandboxPath)) {
                            config.uriToFileTransformEngine!!.onUriToFileAsyncTransform(host.getAppContext(), media.path, media.mimeType, object : OnKeyValueResultCallbackListener {
                                override fun onCallback(srcPath: String?, resultPath: String?) {
                                    if (TextUtils.isEmpty(srcPath)) {
                                        return
                                    }
                                    val key = srcPath!!
                                    val target = queue[key]
                                    if (target != null) {
                                        if (TextUtils.isEmpty(target.sandboxPath)) {
                                            target.sandboxPath = resultPath
                                        }
                                        if (config.isCheckOriginalImage) {
                                            target.originalPath = resultPath
                                            target.isOriginal = !TextUtils.isEmpty(resultPath)
                                        }
                                        queue.remove(key)
                                    }
                                }
                            })
                        }
                    }
                    return result
                }

                override fun onSuccess(result: ArrayList<LocalMedia>) {
                    PictureThreadUtils.cancel(this)
                    dispatchUriToFileTransformResult(result)
                }
            })
        }
    }

    /** SDK > 29: copy external resources into the app sandbox (legacy). */
    @Deprecated("")
    private fun copyExternalPathToAppInDirFor29(result: ArrayList<LocalMedia>) {
        host.showLoading()
        PictureThreadUtils.executeByIo(object : PictureThreadUtils.SimpleTask<ArrayList<LocalMedia>>() {
            override fun doInBackground(): ArrayList<LocalMedia> {
                for (i in result.indices) {
                    val media = result[i]
                    config.sandboxFileEngine!!.onStartSandboxFileTransform(host.getAppContext(), config.isCheckOriginalImage, i, media, object : OnCallbackIndexListener<LocalMedia> {
                        override fun onCall(data: LocalMedia, index: Int) {
                            val target = result[index]
                            target.sandboxPath = data.sandboxPath
                            if (config.isCheckOriginalImage) {
                                target.originalPath = data.originalPath
                                target.isOriginal = !TextUtils.isEmpty(data.originalPath)
                            }
                        }
                    })
                }
                return result
            }

            override fun onSuccess(result: ArrayList<LocalMedia>) {
                PictureThreadUtils.cancel(this)
                dispatchUriToFileTransformResult(result)
            }
        })
    }

    /** Build original-image data. */
    private fun mergeOriginalImage(result: ArrayList<LocalMedia>) {
        if (config.isCheckOriginalImage) {
            for (i in result.indices) {
                val media = result[i]
                media.isOriginal = true
                media.originalPath = media.path
            }
        }
    }

    fun onResultEvent(result: ArrayList<LocalMedia>) {
        if (checkTransformSandboxFile()) {
            uriToFileTransform29(result)
        } else if (checkOldTransformSandboxFile()) {
            copyExternalPathToAppInDirFor29(result)
        } else {
            mergeOriginalImage(result)
            dispatchUriToFileTransformResult(result)
        }
    }

    /** Parse a REQUEST_CROP activity result, then dispatch compress/result. */
    fun handleCropResult(data: Intent?) {
        val selectedResult = config.selectedResult
        try {
            val cropData = data!!
            if (selectedResult.size == 1) {
                val media = selectedResult[0]
                val output = Crop.getOutput(cropData)
                media.cutPath = if (output != null) output.path else ""
                media.isCut = !TextUtils.isEmpty(media.cutPath)
                media.cropImageWidth = Crop.getOutputImageWidth(cropData)
                media.cropImageHeight = Crop.getOutputImageHeight(cropData)
                media.cropOffsetX = Crop.getOutputImageOffsetX(cropData)
                media.cropOffsetY = Crop.getOutputImageOffsetY(cropData)
                media.cropResultAspectRatio = Crop.getOutputCropAspectRatio(cropData)
                media.customData = Crop.getOutputCustomExtraData(cropData)
                media.sandboxPath = media.cutPath
            } else {
                var extra = cropData.getStringExtra(MediaStore.EXTRA_OUTPUT)
                if (TextUtils.isEmpty(extra)) {
                    extra = cropData.getStringExtra(CustomIntentKey.EXTRA_OUTPUT_URI)
                }
                val array = JSONArray(extra)
                if (array.length() == selectedResult.size) {
                    for (i in selectedResult.indices) {
                        val media = selectedResult[i]
                        val item = array.optJSONObject(i)!!
                        media.cutPath = item.optString(CustomIntentKey.EXTRA_OUT_PUT_PATH)
                        media.isCut = !TextUtils.isEmpty(media.cutPath)
                        media.cropImageWidth = item.optInt(CustomIntentKey.EXTRA_IMAGE_WIDTH)
                        media.cropImageHeight = item.optInt(CustomIntentKey.EXTRA_IMAGE_HEIGHT)
                        media.cropOffsetX = item.optInt(CustomIntentKey.EXTRA_OFFSET_X)
                        media.cropOffsetY = item.optInt(CustomIntentKey.EXTRA_OFFSET_Y)
                        media.cropResultAspectRatio = item.optDouble(CustomIntentKey.EXTRA_ASPECT_RATIO).toFloat()
                        media.customData = item.optString(CustomIntentKey.EXTRA_CUSTOM_EXTRA_DATA)
                        media.sandboxPath = media.cutPath
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e) { "[PictureCommonFragment] handleEditMediaResult error:" }
            ToastUtils.showToast(host.getAppContext(), e.message.orEmpty())
        }

        val result = ArrayList(selectedResult)
        if (checkCompressValidity()) {
            onCompress(result)
        } else if (checkOldCompressValidity()) {
            onOldCompress(result)
        } else {
            onResultEvent(result)
        }
    }

    /** RESULT_CROP_ERROR handling. */
    fun handleCropError(data: Intent?) {
        val throwable = if (data != null) Crop.getError(data) else Throwable("image crop error")
        if (throwable != null) {
            ToastUtils.showToast(host.getAppContext(), throwable.message.orEmpty())
        }
    }
}
