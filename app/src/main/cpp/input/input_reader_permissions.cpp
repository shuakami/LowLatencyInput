#include "input_reader_permissions.h"
#include <string>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <cstdlib>
#include <sys/wait.h>
#include <android/log.h>

// 日志标签可复用
#define TAG "NativeInputReader"

bool tryFixPermissions(const char* devicePath) {
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "尝试通过 su 修复 %s 的 SELinux 和权限...", devicePath);
    {
        const std::string cmdSetenforce = "su -c setenforce 0";
        int retSetenforce = system(cmdSetenforce.c_str());
        if (retSetenforce == -1) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "system('%s') 调用失败: %s. 继续尝试 chmod。",
                                cmdSetenforce.c_str(), strerror(errno));
        } else if (WIFEXITED(retSetenforce)) {
            int exitCode = WEXITSTATUS(retSetenforce);
            if (exitCode != 0) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                                    "'setenforce 0' 命令退出码: %d", exitCode);
            } else {
                __android_log_print(ANDROID_LOG_INFO, TAG,
                                    "成功执行 'setenforce 0'。");
            }
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "'setenforce 0' 命令未正常退出");
        }
    }
    {
        std::string cmdChmod = "su -c chmod 666 ";
        cmdChmod += devicePath;
        int retChmod = system(cmdChmod.c_str());
        if (retChmod == -1) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "system('%s') 调用失败: %s",
                                cmdChmod.c_str(), strerror(errno));
            return false;
        } else if (WIFEXITED(retChmod)) {
            int exitCode = WEXITSTATUS(retChmod);
            if (exitCode != 0) {
                __android_log_print(ANDROID_LOG_ERROR, TAG,
                                    "'chmod 666' 命令退出码: %d", exitCode);
                return false;
            } else {
                __android_log_print(ANDROID_LOG_INFO, TAG,
                                    "成功执行 'chmod 666'。");
            }
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "'chmod 666' 命令未正常退出");
            return false;
        }
    }
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "权限修复尝试完成 (%s)，将重试 open()。", devicePath);
    return true;
}
