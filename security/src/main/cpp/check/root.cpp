
#include "root.h"
#include <jni.h>
#include "../log/logutil.h"
#include <unistd.h>
#include "../utils/cmd.h"
#include "../utils/stringutil.h"
#include "../utils/register.h"
#include <string>
#include "../utils/property.h"




bool checkRoot(JNIEnv *env, jobject thiz){

    LOGD("[checkRoot] check start");
    bool result = true;

    //基于su文件路径的检查
    const char *types[] = {
            "/system/app/Superuser.apk",
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/su/bin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/system/bin/cufsdosck",
            "/system/xbin/cufsdosck",
            "/system/bin/cufsmgr",
            "/system/xbin/cufsmgr",
            "/system/bin/cufaevdd",
            "/system/xbin/cufaevdd",
            "/system/bin/conbb",
            "/system/xbin/conbb"
    };
    for(const char* type:types){
        if (access(type, F_OK) == 0) {
            LOGE("[checkRoot] find su bin file reason：%s",type);
            result = false;
            break;
        }
    }

    //基于which命令的检查su程序
    if(result){
        std::map<int,std::string> which_result = excudeShellCmd("which su");
        if(!which_result.empty()){
            const char* info=which_result[0].c_str();
            if(StringUtil::wrap_strstr(info,"not found") == nullptr){
                LOGE("[checkRoot] find su cmd:%s",info);
                result = false;
            }
            which_result.clear();
        }else{
            LOGD("[checkRoot] which su cmd result is null");
        }
    }

    //正式发布的Android系统则使用的是"release-keys"
    if(result){
        std::string tags = getSysProperty("ro.build.tags");
        if(!tags.empty()){
            LOGD("[checkRoot] tags:%s", tags.c_str());
            if(StringUtil::wrap_strstr(tags.c_str(),"dev-keys") != nullptr
            || StringUtil::wrap_strstr(tags.c_str(),"test-keys") != nullptr
            ){
                LOGD("[checkRoot] find root");
                result = false;
            }
        }
    }

    //ro.secure=0表示设备未开启安全性强化措施
    if(result){
        std::string secure = getSysProperty("ro.secure");
        if(!secure.empty()){
            LOGD("[checkRoot] secure:%s", secure.c_str());
            if(StringUtil::wrap_strncmp(secure.c_str(),"0",1) == 0){
                LOGD("[checkRoot] find root");
                result = false;
            }
        }
    }

    //检测magisk root工具
    if(result){
        std::map<int,std::string> magisk_result=excudeShellCmd("magisk --list");
        std::map<int,std::string>::iterator iterator_magisk;
        if(!magisk_result.empty()){
            for(iterator_magisk=magisk_result.begin();iterator_magisk!=magisk_result.end();iterator_magisk++){
                const char* data=iterator_magisk->second.c_str();
                if(StringUtil::wrap_strncmp(data, "su", 2) == 0){
                    LOGE("[checkRoot] find magisk su");
                    result = false;
                    break;
                }
            }
            magisk_result.clear();
        }
    }

    LOGD("[checkRoot] check end");
    return result;
}


//注册native方法
static JNINativeMethod rootcheck_methods[] = {
        {"checkRoot", "()Z", (void*)checkRoot},
};

void rootCheckRegisterMethod(JNIEnv* env){
    const char* securityLib_class = "com/difft/android/security/SecurityLib";
    registerNativeMethods(env,securityLib_class,rootcheck_methods,sizeof(rootcheck_methods) / sizeof(rootcheck_methods[0]));
}