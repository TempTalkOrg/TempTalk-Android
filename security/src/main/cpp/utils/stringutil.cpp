//
// Created by test on 2023/9/21.
//

#include "stringutil.h"


int StringUtil::wrap_strncmp(const char *s1, const char *s2, size_t n){
    if (n == 0)
        return (0);
    do {
        if (*s1 != *s2++)
            return (*(unsigned char *)s1 - *(unsigned char *)--s2);
        if (*s1++ == 0)
            break;
    } while (--n != 0);
    return (0);
}


char* StringUtil::wrap_strstr(const char *s, const char *find)
{
    char c, sc;
    size_t len;

    if ((c = *find++) != 0) {
        len = strlen(find);
        do {
            do {
                if ((sc = *s++) == 0)
                    return (NULL);
            } while (sc != c);
        } while (StringUtil::wrap_strncmp(s, find, len) != 0);
        s--;
    }
    return ((char *)s);
}


