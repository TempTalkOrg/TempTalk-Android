//
// Created by test on 2023/9/19.
//

#include "register.h"

/*
 * 动态注册Java层native方法
 */
int registerNativeMethods(JNIEnv* env, const char* className, JNINativeMethod* gMethods, int numMethods)
{
    jclass clazz_hook = env->FindClass(className);
    if (clazz_hook == nullptr) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz_hook, gMethods, numMethods) < 0) {
        if(env->ExceptionCheck()){
            env->ExceptionClear();
        }
        return JNI_FALSE;
    }
    return JNI_TRUE;
}