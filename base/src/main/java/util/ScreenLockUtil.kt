package util

object ScreenLockUtil {
    /**
     * 通用的临时豁免标志
     * 用于：文件选择、分享、图片选择、APK更新等所有临时场景
     * 当需要临时禁用锁屏时设置为 true，操作完成后恢复为 false
     * 或者等待 onForeground 时自动重置
     */
    var temporarilyDisabled = false
}