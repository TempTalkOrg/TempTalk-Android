package com.difft.android.selector.pictureselector

import android.content.Context
import android.net.Uri
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.engine.CompressFileEngine
import com.difft.android.selector.interfaces.OnKeyValueResultCallbackListener
import top.zibin.luban.Luban
import top.zibin.luban.OnNewCompressListener
import util.FileSystemUtils
import java.io.File
import java.util.ArrayList

/**
 * 自定义压缩
 */
class ImageFileCompressEngine : CompressFileEngine {

    override fun onStartCompress(context: Context, source: ArrayList<Uri>, call: OnKeyValueResultCallbackListener) {
        Luban.with(context).load(source).ignoreBy(100).setRenameListener { filePath ->
            FileSystemUtils.getFileName(filePath)
        }.filter { path ->
            if (PictureMimeType.isUrlHasImage(path) && !PictureMimeType.isHasHttp(path)) {
                true
            } else {
                !PictureMimeType.isUrlHasGif(path)
            }
        }.setCompressListener(object : OnNewCompressListener {
            override fun onStart() {
            }

            override fun onSuccess(source: String?, compressFile: File) {
                call.onCallback(source, compressFile.absolutePath)
            }

            override fun onError(source: String?, e: Throwable?) {
                call.onCallback(source, null)
            }
        }).launch()
    }
}
