#include "appsign.h"
#include "../utils/register.h"
#include "../log/logutil.h"
#include "../utils/stringutil.h"


const char* APP_SIGN_SHA256 = "b4e071def9a09fbdab690d0aa0583a2f62e7cdaa96a2d167e3fa8ceeb4853e3e";

bool checkAppSign(JNIEnv* env, jobject thiz, jstring currentSignHash){

    bool result = false;
    if(currentSignHash == nullptr){
        return false;
    }

    const char* signHash = env->GetStringUTFChars(currentSignHash, JNI_FALSE);
    if(StringUtil::wrap_strncmp(APP_SIGN_SHA256, signHash, strlen(APP_SIGN_SHA256)) == 0){
        result = true;
    }

    if(signHash != nullptr){
        env->ReleaseStringUTFChars(currentSignHash, signHash);
    }
    return result;
}

//注册获取apkPath、沙箱路径、app签名MD5的JNI方法
static JNINativeMethod appsign_methods[] = {
        {"checkSign", "(Ljava/lang/String;)Z", (void*)checkAppSign},
};

void appSignRegisterMethod(JNIEnv* env){
    const char* securityLib_class = "com/difft/android/security/SecurityLib";
    registerNativeMethods(env,securityLib_class,appsign_methods,sizeof(appsign_methods) / sizeof(appsign_methods[0]));
}