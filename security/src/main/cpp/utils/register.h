//
// Created by test on 2023/9/19.
//

#ifndef CHATIVE_REGISTER_H
#define CHATIVE_REGISTER_H
#include <jni.h>

int registerNativeMethods(JNIEnv* env, const char* className, JNINativeMethod* gMethods, int numMethods);

#endif //CHATIVE_REGISTER_H
