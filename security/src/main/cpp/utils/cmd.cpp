//
// Created by user on 2024/1/18.
//

#include "cmd.h"
#include "../log/logutil.h"

#define BUF_SIZE 1024


/*
 * 执行shell命令，将结果写入map中
 */
std::map<int,std::string> excudeShellCmd(const char* shellcmd){
    std::map<int,std::string> result_map;
    FILE *p_file=NULL;
    char buf[BUF_SIZE];
    p_file=popen(shellcmd,"r");
    if(!p_file){
        LOGD("excudeShellCmd open fail");
        return result_map;
    }
    int index=0;
    while (fgets(buf,BUF_SIZE,p_file)!=NULL){
        if (buf[strlen(buf)-1] == '\n'){
            buf[strlen(buf)-1] = '\0';
        }
        LOGD("excudeShellCmd buf %s",buf);
        result_map.insert(std::map<int ,std::string>::value_type(index,buf));
        index++;
        memset(buf, 0, sizeof(buf));
    }
    pclose(p_file);
    return result_map;
}