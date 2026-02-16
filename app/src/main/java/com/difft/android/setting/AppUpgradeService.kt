package com.difft.android.setting

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.appScope
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.awaitFirst
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil

@AndroidEntryPoint
class AppUpgradeService : Service() {

    @Inject
    @ChativeHttpClientModule.NoHeader
    lateinit var httpClient: ChativeHttpClient

    private var downloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val url = it.getStringExtra(UpdateManager.INTENT_PARAM_APK_DOWNLOAD_URL)?:""
            val apkHash = it.getStringExtra(UpdateManager.INTENT_PARAM_APK_VERIFY_HASH)?:""
            val filepath = it.getStringExtra(UpdateManager.INTENT_PARAM_APK_STORE_PATH)?:""
            val isForce = it.getBooleanExtra(UpdateManager.INTENT_PARAM_APK_FORCE_UPGRADE, false)
            if(!TextUtils.isEmpty(url) && !TextUtils.isEmpty(filepath) &&!TextUtils.isEmpty(apkHash)){
                isDownloading = true
                downloadApkAndInstall(url, filepath, apkHash, isForce)
            }else{
                sendDownloadCompletedBroadcast(UpdateManager.STATUS_DOWNLOAD_FAILED, filepath, isForce)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun downloadApkAndInstall(url: String, filepath: String, apkHash: String, isForce: Boolean = false){
        // 取消之前的下载任务，避免多个并发下载
        downloadJob?.cancel()
        
        ToastUtil.showLong(R.string.status_upgrade_downloading)
        
        val newFile = File(filepath)
        
        // 使用协程简化嵌套逻辑
        downloadJob = appScope.launch {
            try {
                // 1. 下载 APK 文件（使用注入的全局 httpClient，流式下载避免内存溢出）
                // 2. 流式保存文件到本地，避免一次性加载整个文件到内存
                // 直接链式调用 awaitFirst().use，确保 ResponseBody 生命周期在获取作用域内，自动管理资源
                withContext(Dispatchers.IO) {
                    httpClient.httpService.getResponseBody(url, emptyMap(), emptyMap())
                        .awaitFirst()
                        .use { responseBody ->
                            saveResponseToFile(responseBody, filepath)
                        }
                }
                
                // 3. 在后台线程执行 APK 验证，避免 ANR（使用流式哈希计算避免内存溢出）
                val isValid = withContext(Dispatchers.IO) {
                    UpdateManager.verifyApk(filepath, apkHash)
                }
                
                // 4. 处理验证结果
                if(isValid){
                    L.i { "AppUpgradeService downloadApkAndInstall verifyApk success." }
                    sendDownloadCompletedBroadcast(UpdateManager.STATUS_DOWNLOAD_SUCCESS, filepath, isForce)
                }else{
                    L.i { "AppUpgradeService downloadApkAndInstall verifyApk error." }
                    sendDownloadCompletedBroadcast(UpdateManager.STATUS_VERIFY_FAILED, filepath, isForce)
                }
            } catch (error: Exception) {
                // 正确处理 CancellationException，不要执行错误处理逻辑
                if (error is CancellationException) {
                    L.i { "AppUpgradeService downloadApkAndInstall cancelled" }
                    throw error // 重新抛出，让协程取消语义正确传播
                }
                
                L.w { "[AppUpgradeService] download error: ${error.stackTraceToString()}" }
                if (newFile.exists()) {
                    newFile.delete()
                }
                L.i { "AppUpgradeService downloadApkAndInstall error:{${error.message}}" }
                sendDownloadCompletedBroadcast(UpdateManager.STATUS_DOWNLOAD_FAILED, filepath, isForce)
            }
        }
    }

    /**
     * 流式保存响应体到文件，避免一次性加载整个文件到内存
     * 参考 DownloadAttachmentJob 的实现方式
     * 注意：此方法只负责从 ResponseBody 的输入流读取数据，ResponseBody 的关闭由调用者负责
     */
    private fun saveResponseToFile(response: ResponseBody, filepath: String) {
        response.byteStream().use { inputStream ->
            FileOutputStream(filepath).use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
            }
        }
    }

    private fun sendDownloadCompletedBroadcast(result: Int, filepath: String, isForce: Boolean = false) {
        L.i { "AppUpgradeService sendDownloadCompletedBroadcast action:${UpdateManager.ACTION_APK_DOWNLOAD_COMPLETED}" }
        val intent = Intent(UpdateManager.ACTION_APK_DOWNLOAD_COMPLETED)
        intent.setPackage(ApplicationHelper.instance.packageName)
        intent.putExtra(UpdateManager.INTENT_PARAM_APK_DOWNLOAD_STATUS, result)
        intent.putExtra(UpdateManager.INTENT_PARAM_APK_STORE_PATH, filepath)
        intent.putExtra(UpdateManager.INTENT_PARAM_APK_FORCE_UPGRADE, isForce)
        this.sendBroadcast(intent)
        isDownloading = false
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消正在进行的下载任务
        downloadJob?.cancel()
        downloadJob = null
        isDownloading = false
    }

    companion object{
        @Volatile
        var isDownloading: Boolean = false
    }

}