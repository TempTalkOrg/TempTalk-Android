//
// Created by user on 2024/1/19.
//

#include "property.h"
#include "../log/logutil.h"
#include "stringutil.h"
#include <sys/system_properties.h>

std::string getSysProperty(const char* key) {
    char buf[256] = {0};
    int k = __system_property_get(key, buf);
    LOGD("[dpt] getSysProperty:%s", buf);
    if (k > 0) {
        return std::string(buf);
    } else {
        return "";  // 或者抛出一个异常，取决于你的需求
    }
}