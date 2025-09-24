package util

object ScreenLockUtil {
    var noNeedShowScreenLock = false //一些特殊场景，如发送文件，分享功能，接听电话等，回来时不显示ScreenLock

    var pictureSelectorIsShowing = false

    var appIsForegroundBeforeHandleDeeplink = false  // 处理deeplink前，app是否在前台，如果已经处于前台，就不再显示ScreenLock
}