//
// Created by test on 2023/9/21.
//

#ifndef CHATIVE_STRINGUTIL_H
#define CHATIVE_STRINGUTIL_H

#include <string.h>
#include <jni.h>

class StringUtil {

public:
    static int wrap_strncmp(const char *s1, const char *s2, size_t n);
    static char* wrap_strstr(const char *s, const char *find);
};

#endif //CHATIVE_STRINGUTIL_H
