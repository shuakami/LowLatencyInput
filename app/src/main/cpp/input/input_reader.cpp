#include "input_reader.h"
#include "input_reader_loop.h"
#include "input_reader_jni_utils.h"
#include "input_reader_permissions.h"

#include "../bridge/jni_bridge.h"  // g_jvm, g_serviceInstance, g_onInputDataReceivedMethodID_Service
#include <android/log.h>
#include <unistd.h>
#include <cerrno>
#include <string>
#include <stdexcept>
#include <sys/poll.h>
#include <cstring>
#include <system_error>
#include <thread>
#include <vector>
#include <mutex>
#include <atomic>
#include <condition_variable>
#include <chrono>
#include <cstdio>
#include <nlohmann/json.hpp>
/** 
 * 这里定义 input_reader.h 中的 extern 全局变量 
 */
std::atomic<bool> g_isRunning(false);
std::vector<std::thread> g_readerThreads;
std::mutex g_threadMutex;

std::vector<ClickableRegion> g_clickableRegions;
std::mutex g_regionMutex;

int g_screenWidthPx = 0;
int g_screenHeightPx = 0;
int g_screenTopOffsetPx = 0;
int g_screenLeftOffsetPx = 0;

// 日志标签
#define TAG "NativeInputReader"

// ---------------- JNI 导出函数的实现 ----------------

/**
 * @brief JNI 接口：启动输入设备读取线程
 */
void nativeStartInputReaderService(JNIEnv* env, jobject /* instance */) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeStartInputReaderService: 开始初始化");

    static constexpr const char* TOUCH_DEVICE_PATH = "/dev/input/event4"; // 示例

    try {
        // 在启动线程前，尝试初始化 JNI 引用
        if (!initializeJniReferences(env)) {
            throw std::runtime_error("JNI 引用初始化失败");
        }

        {
            std::lock_guard<std::mutex> lock(g_threadMutex);
            if (g_isRunning.load()) {
                __android_log_print(ANDROID_LOG_WARN, TAG, "输入读取线程已在运行中");
                return;
            }

            // 检查设备文件是否可访问
            if (access(TOUCH_DEVICE_PATH, R_OK) != 0) {
                if (errno == EACCES || errno == EPERM) {
                    __android_log_print(ANDROID_LOG_WARN, TAG, 
                        "设备文件权限不足，尝试修复: %s", TOUCH_DEVICE_PATH);
                    if (!tryFixPermissions(TOUCH_DEVICE_PATH)) {
                        throw std::runtime_error("无法获取设备文件访问权限");
                    }
                } else {
                    throw std::runtime_error("设备文件不存在或无法访问");
                }
            }

            g_isRunning.store(true);
            __android_log_print(ANDROID_LOG_INFO, TAG, 
                "准备创建输入读取线程，监听设备: %s", TOUCH_DEVICE_PATH);

            std::thread readerThread(inputReaderLoop, TOUCH_DEVICE_PATH);
            auto threadId = readerThread.get_id();
            __android_log_print(ANDROID_LOG_INFO, TAG, 
                "已创建并启动线程: %llu", 
                static_cast<unsigned long long>(std::hash<std::thread::id>{}(threadId)));
            
            readerThread.detach();
        }
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "启动服务失败: %s", e.what());
        g_isRunning.store(false);
        cleanupJniReferences(env);
        throw; // 重新抛出异常，让 Java 层处理
    }
}

/**
 * @brief JNI 接口：停止输入设备读取线程
 */
void nativeStopInputReaderService(JNIEnv* env, jobject /* instance */) {
    std::lock_guard<std::mutex> lock(g_threadMutex);
    if (!g_isRunning.load()) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "读取线程未运行，忽略停止请求。");
        return;
    }

    g_isRunning.store(false);
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "nativeStopInputReaderService: 已设置 g_isRunning=false。");
    g_readerThreads.clear(); // 已经detach的线程无法join，清空vector

    // 停止时清理 JNI 引用
    cleanupJniReferences(env);
}

/**
 * @brief JNI: 更新可点击区域
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeUpdateClickableRegions(
    JNIEnv *env,
    jclass /* clazz */,
    jstring jsonData)
{
    const char *nativeJsonString = env->GetStringUTFChars(jsonData, nullptr);
    if (!nativeJsonString) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "nativeUpdateClickableRegions: GetStringUTFChars失败。");
        return;
    }
    std::string jsonStr(nativeJsonString);
    env->ReleaseStringUTFChars(jsonData, nativeJsonString);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "nativeUpdateClickableRegions: 收到 JSON: %s", jsonStr.c_str());

    try {
        // 使用 nlohmann::json
        nlohmann::json parsed = nlohmann::json::parse(jsonStr);
        if (!parsed.is_array()) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "JSON不是数组结构，终止。");
            return;
        }
        std::vector<ClickableRegion> tmpRegions;
        for (auto &item : parsed) {
            if (item.is_object()) {
                ClickableRegion r;
                r.identifier = item.value("identifier", "");
                r.left      = item.value("leftPx", 0);
                r.top       = item.value("topPx", 0);
                r.width     = item.value("widthPx", 0);
                r.height    = item.value("heightPx", 0);
                if (!r.identifier.empty() && r.width > 0 && r.height > 0) {
                    tmpRegions.push_back(r);
                }
            }
        }
        {
            std::lock_guard<std::mutex> lk(g_regionMutex);
            g_clickableRegions = std::move(tmpRegions);
        }
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "nativeUpdateClickableRegions: 更新成功, count=%zu", g_clickableRegions.size());
    } catch (nlohmann::json::parse_error& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "JSON parse error: %s", e.what());
    } catch (std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "处理区域JSON出错: %s", e.what());
    }
}

/**
 * @brief JNI: 设置屏幕尺寸
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeSetScreenDimensions(
    JNIEnv *env,
    jclass /* clazz */,
    jint width,
    jint height)
{
    g_screenWidthPx = width;
    g_screenHeightPx = height;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "nativeSetScreenDimensions: 屏幕大小 %d x %d",
        g_screenWidthPx, g_screenHeightPx);
}

/**
 * @brief JNI: 设置屏幕偏移 (如状态栏高度)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeSetScreenOffsets(
    JNIEnv *env,
    jclass /* clazz */,
    jint topOffset,
    jint leftOffset)
{
    g_screenTopOffsetPx = topOffset;
    g_screenLeftOffsetPx = leftOffset;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "nativeSetScreenOffsets: Top=%d, Left=%d",
        g_screenTopOffsetPx, g_screenLeftOffsetPx);
}

/**
 * @brief JNI: Native 请求发送 UI 点击事件包 (空实现，真正逻辑在 Java 端)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeRequestSendUiEventPacket(
    JNIEnv *env,
    jclass /* clazz */,
    jstring identifier,
    jint x,
    jint y)
{
    __android_log_print(ANDROID_LOG_DEBUG, TAG,
        "nativeRequestSendUiEventPacket (no-op in C++)");
}

/**
 * @brief JNI: Native 请求发送 UI 长按事件包 (空实现，真正逻辑在 Java 端)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeRequestSendUiLongPressPacket(
    JNIEnv *env,
    jclass /* clazz */,
    jstring identifier,
    jint x,
    jint y)
{
    __android_log_print(ANDROID_LOG_DEBUG, TAG,
        "nativeRequestSendUiLongPressPacket (no-op in C++)");
}
