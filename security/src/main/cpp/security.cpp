#include <jni.h>
#include <string>
#include "check/appsign.h"
#include "check/emulator.h"
#include "check/root.h"


JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved){
    JNIEnv *env;

    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    appSignRegisterMethod(env);//注册native方法
    emulatorCheckRegisterMethod(env);//注册模拟检测native方法
    rootCheckRegisterMethod(env);//注册root检测native方法

    return JNI_VERSION_1_6;
}
