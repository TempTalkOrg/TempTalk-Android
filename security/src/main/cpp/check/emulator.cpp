
#include "emulator.h"
#include <jni.h>
#include <sys/stat.h>
#include "../log/logutil.h"
#include <unistd.h>
#include <cstring>
#include "../utils/stringutil.h"
#include "../utils/register.h"
#include <malloc.h>
#include <sys/fcntl.h>
#include "../utils/property.h"

#define BUFFER_SIZE 1024

/*
 * 检测文件是否存在
 * stat函数通过文件名filename获取文件信息，并保存在buf所指的结构体stat中
   返回值:执行成功则返回0，失败返回-1，错误代码存于errno
 */
static bool isFileExist(const char *res) {
    bool result = false;
    result = (access(res, 0) == 0 ? true : false);
    //LOGE("[checkEmulator]:文件检测结果为：%d", result);
    return result;
}


/*
 * 基于文件特征的模拟器检测
 */
bool checkByFeatureFile(JNIEnv* env){
    LOGD("[checkEmulator] checkByFeatureFile check start");
    bool result = false;

    const char* types[]={
            "/system/bin/androVM-prop", //检测androidVM
            "/system/bin/microvirt-prop", //检测逍遥模拟器--新版本找不到特征
            "/system/lib/libdroid4x.so", //检测海马模拟器
            "/system/bin/windroyed", //检测文卓爷模拟器
            "/system/bin/nox-prop", //检测夜神模拟器--某些版本找不到特征
            "system/lib/libnoxspeedup.so", //检测夜神模拟器
            "/system/bin/ttVM-prop", //检测天天模拟器
            "/data/.bluestacks.prop", //检测bluestacks模拟器  51模拟器
            "/system/bin/duosconfig", //检测AMIDuOS模拟器
            "/system/etc/xxzs_prop.sh", //检测星星模拟器
            "/system/etc/mumu-configs/device-prop-configs/mumu.config", //网易MuMu模拟器
            "/system/priv-app/ldAppStore", //雷电模拟器
            "/system/app/AntStore", //小蚁模拟器
            "vmos.prop", //vmos虚拟机
            "fstab.titan", //光速虚拟机
            "x8.prop", //x8沙箱和51虚拟机
    };

    for(const char* type:types){
        if(isFileExist(type)){
            LOGE("[checkEmulator] checkByFeatureFile find emulator reason:%s",type);
            result = true;
        }
    }

    LOGD("[checkEmulator] checkByFeatureFile check end");
    return result;
}


/*
 * 基于rom编译信息检测模拟器特征
 */
bool checkByRomBuildInfo(JNIEnv* env){

    LOGD("[checkEmulator] checkByRomBuildInfo check start");

    bool result = false;

    std::string characteristics = getSysProperty("ro.build.characteristics");
    if(!characteristics.empty()){
        LOGD("[checkEmulator] checkByRomBuildInfo characteristics:%s", characteristics.c_str());
        if(StringUtil::wrap_strstr(characteristics.c_str(),"emulator")){
            LOGD("[checkEmulator] checkByRomBuildInfo characteristics find emulator reason:%s", characteristics.c_str());
            result = true;
        }
    }

    if(!result){
        std::string fingerprint = getSysProperty("ro.product.build.fingerprint");
        if(!fingerprint.empty()){
            LOGD("[checkEmulator] checkByRomBuildInfo fingerprint:%s", fingerprint.c_str());
            //腾讯手游助手特征
            if(StringUtil::wrap_strstr(fingerprint.c_str(),"tencent/vbox64tp/")){
                LOGD("[checkEmulator] checkByRomBuildInfo fingerprint find emulator reason:%s", fingerprint.c_str());
                result = true;
            }
        }
    }

    if(!result){
        std::string platform = getSysProperty("ro.board.platform");
        if(!platform.empty()){
            LOGD("[checkEmulator] checkByRomBuildInfo platform:%s", platform.c_str());
            //windows android模拟器
            if(StringUtil::wrap_strstr(platform.c_str(), "windows")){
                LOGD("[checkEmulator] checkByRomBuildInfo platform find emulator reason:%s", platform.c_str());
                result = true;
            }
        }
    }

    LOGD("[checkEmulator] checkByRomBuildInfo check end");
    return result;
}


/*
 * 基于应用进程被注入的模拟器库文件的检测
 */
bool checkEmulatorLib(JNIEnv *env){

    LOGD("[checkEmulator] checkEmulatorLib check start");

    bool result = false;
    char path[512] = {0};
    char buffer[BUFFER_SIZE];
    int from_fd;

    const char* types[]={
            "libhoudini",//houdini x86 CPU通用arm指令兼容库 /system/lib/libhoudini.so
            "com.vmos.pro",//vmos pro
            "com.vmos.app",//vmos
            "com.vphonegaga.titan",//光速虚拟机
            "com.f1player",//51虚拟机
    };

    int pid = getpid();
    sprintf(path, "/proc/%d/maps", pid);

    if ((from_fd = open(path, O_RDONLY)) == -1){
        LOGD("[checkEmulator] checkEmulatorLib open maps fail");
        return result;
    }

    while (true){
        const ssize_t readSize = read(from_fd, buffer, BUFFER_SIZE - 1);
        if(readSize == -1){
            break;
        }else if (readSize == 0) {
            break; // 文件结束，退出循环
        }
        else if (readSize > 0){
            for(const char* type:types){
                buffer[readSize] = '\0';
                if(StringUtil::wrap_strstr(buffer, type)){
                    LOGD("[checkEmulator] checkEmulatorLib find emulator reason:%s",type);
                    result = true;
                    break;
                }
            }
            memset(buffer, 0, sizeof(buffer));
            if(result){
                break;
            }
        }
    }
    close(from_fd);

    LOGD("[checkEmulator] checkEmulatorLib check end");
    return result;
}


/*
 * 模拟器检测
 */
bool checkEmulator(JNIEnv *env,jobject thiz){

    if(checkByFeatureFile(env) || checkByRomBuildInfo(env) || checkEmulatorLib(env)){
        return false;
    }

    return true;
}


//注册native方法
static JNINativeMethod emulatorcheck_methods[] = {
        {"checkEmulator", "()Z", (void*)checkEmulator},
};

void emulatorCheckRegisterMethod(JNIEnv* env){
    const char* securityLib_class = "com/difft/android/security/SecurityLib";
    registerNativeMethods(env,securityLib_class,emulatorcheck_methods,sizeof(emulatorcheck_methods) / sizeof(emulatorcheck_methods[0]));
}