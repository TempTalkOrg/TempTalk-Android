//
// Created by neo on 2023/9/19.
// 功能：调试日志
//

#ifndef ANDROID_SECURITY_SDK_LOGUTIL_H
#define ANDROID_SECURITY_SDK_LOGUTIL_H

#include <android/log.h>

#ifdef __cplusplus
extern "C"{
#endif

//log输出开关，release需要注销 #define __LOG_ON__

//#define __LOG_ON__
#ifdef __LOG_ON__
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "security", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "security", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "security", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "security", __VA_ARGS__)
#else
#define LOGE(...)
#define LOGD(...)
#define LOGW(...)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "security", __VA_ARGS__)
#endif

#ifdef __cplusplus
}
#endif

#endif //ANDROID_SECURITY_SDK_LOGUTIL_H
