#include "input_reader_jni_utils.h"
#include "../bridge/jni_bridge.h"   // 提供 g_jvm, g_serviceInstance 等 extern 声明
#include <android/log.h>
#include <string>
#include <system_error>
#include <nlohmann/json.hpp>

// 日志标签
#define TAG "NativeInputReader"

// 使用 json 命名空间简化代码
using json = nlohmann::json;

// 这些全局引用在此文件内定义（与 .h 对应）
jclass g_gyroServiceClass = nullptr;
jmethodID g_sendUiEventMethod = nullptr;
jmethodID g_sendUiLongPressMethod = nullptr;
jmethodID g_sendUiPressDownMethod = nullptr;

/**
 * @brief JNI 初始化时调用，缓存 Class 和 Method ID
 */
bool initializeJniReferences(JNIEnv* env) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "开始初始化 JNI 引用...");

    // 查找 GyroscopeService 类
    jclass localClassRef = env->FindClass("com/luoxiaohei/lowlatencyinput/service/GyroscopeService");
    if (localClassRef == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "初始化 JNI 失败: 找不到 GyroscopeService 类");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return false;
    }

    // 创建全局引用
    g_gyroServiceClass = (jclass)env->NewGlobalRef(localClassRef);
    env->DeleteLocalRef(localClassRef);
    if (g_gyroServiceClass == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "初始化 JNI 失败: 创建 GyroscopeService 全局引用失败");
        if (env->ExceptionCheck()) { 
            env->ExceptionDescribe(); 
            env->ExceptionClear(); 
        }
        return false;
    }
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "GyroscopeService 类全局引用创建成功: %p", g_gyroServiceClass);

    // 获取 sendUiEventPacket 实例方法 ID
    g_sendUiEventMethod = env->GetMethodID(
        g_gyroServiceClass,
        "sendUiEventPacket",
        "(Ljava/lang/String;II)V"
    );
    if (g_sendUiEventMethod == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "初始化 JNI 失败: 找不到 sendUiEventPacket 方法");
        if (env->ExceptionCheck()) { 
            env->ExceptionDescribe(); 
            env->ExceptionClear(); 
        }
        env->DeleteGlobalRef(g_gyroServiceClass);
        g_gyroServiceClass = nullptr;
        return false;
    }
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "sendUiEventPacket 方法 ID 获取成功: %p", g_sendUiEventMethod);

    // 获取 sendUiLongPressPacket 实例方法 ID
    g_sendUiLongPressMethod = env->GetMethodID(
        g_gyroServiceClass,
        "sendUiLongPressPacket",
        "(Ljava/lang/String;II)V"
    );
    if (g_sendUiLongPressMethod == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "初始化 JNI 失败: 找不到 sendUiLongPressPacket 方法");
        if (env->ExceptionCheck()) { 
            env->ExceptionDescribe(); 
            env->ExceptionClear(); 
        }
        env->DeleteGlobalRef(g_gyroServiceClass);
        g_gyroServiceClass = nullptr;
        g_sendUiEventMethod = nullptr;
        return false;
    }
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "sendUiLongPressPacket 方法 ID 获取成功: %p", g_sendUiLongPressMethod);

    // 获取 sendUiPressDownPacket 实例方法 ID (用于按下事件)
    g_sendUiPressDownMethod = env->GetMethodID(
        g_gyroServiceClass,
        "sendUiPressDownPacket",
        "(Ljava/lang/String;IIJ)V" // 参数: String, int, int, long
    );
    if (g_sendUiPressDownMethod == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "初始化 JNI 失败: 找不到 sendUiPressDownPacket 方法");
        if (env->ExceptionCheck()) { 
            env->ExceptionDescribe(); 
            env->ExceptionClear(); 
        }
        env->DeleteGlobalRef(g_gyroServiceClass);
        g_gyroServiceClass = nullptr;
        g_sendUiEventMethod = nullptr;
        g_sendUiLongPressMethod = nullptr;
        return false;
    }
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "sendUiPressDownPacket 方法 ID 获取成功: %p", g_sendUiPressDownMethod);

    __android_log_print(ANDROID_LOG_INFO, TAG, "JNI 引用初始化成功完成。");
    return true;
}

/**
 * @brief JNI 卸载时调用，清理全局引用
 */
void cleanupJniReferences(JNIEnv* env) {
    if (g_gyroServiceClass != nullptr) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "开始清理 JNI 全局引用...");
        env->DeleteGlobalRef(g_gyroServiceClass);
        g_gyroServiceClass = nullptr;
        g_sendUiEventMethod = nullptr;
        g_sendUiLongPressMethod = nullptr;
        g_sendUiPressDownMethod = nullptr;
        __android_log_print(ANDROID_LOG_INFO, TAG, "JNI 全局引用已清理。");
    }
}

/**
 * @brief 调用 Java 实例方法 sendUiEventPacket(identifier, x, y)
 */
void callSendUiEventPacketJNI(JNIEnv* env, const std::string& identifier, int x, int y) {
    __android_log_print(ANDROID_LOG_INFO, TAG, 
        "准备调用 sendUiEventPacket: identifier=%s, x=%d, y=%d", 
        identifier.c_str(), x, y);

    // 使用缓存的实例引用和方法ID
    if (!g_serviceInstance || !g_sendUiEventMethod) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiEventPacketJNI: Service实例或方法ID未初始化!");
        return;
    }

    jstring jIdentifier = env->NewStringUTF(identifier.c_str());
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiEventPacketJNI: NewStringUTF 失败");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }
    if (jIdentifier == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiEventPacketJNI: NewStringUTF 返回 null");
        return;
    }

    env->CallVoidMethod(g_serviceInstance, g_sendUiEventMethod, jIdentifier, (jint)x, (jint)y);
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiEventPacketJNI: CallVoidMethod 失败");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG, 
            "Java sendUiEventPacket 方法调用成功");
    }

    env->DeleteLocalRef(jIdentifier); // 释放局部引用
}

/**
 * @brief 调用 Java 实例方法 sendUiLongPressPacket(identifier, x, y)
 */
void callSendUiLongPressPacketJNI(JNIEnv* env, const std::string& identifier, int x, int y) {
    __android_log_print(ANDROID_LOG_INFO, TAG, 
        "准备调用 sendUiLongPressPacket: identifier=%s, x=%d, y=%d", 
        identifier.c_str(), x, y);

    if (!g_serviceInstance || !g_sendUiLongPressMethod) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiLongPressPacketJNI: Service实例或方法ID未初始化!");
        return;
    }

    jstring jIdentifier = env->NewStringUTF(identifier.c_str());
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiLongPressPacketJNI: NewStringUTF 失败");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }
    if (jIdentifier == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiLongPressPacketJNI: NewStringUTF 返回 null");
        return;
    }

    env->CallVoidMethod(g_serviceInstance, g_sendUiLongPressMethod, jIdentifier, (jint)x, (jint)y);
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiLongPressPacketJNI: CallVoidMethod 失败");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG, 
            "Java sendUiLongPressPacket 方法调用成功");
    }

    env->DeleteLocalRef(jIdentifier); 
}

/**
 * @brief 调用 Java 实例方法 sendUiPressDownPacket(identifier, x, y, downTimestampMs)
 */
void callSendUiPressDownPacketJNI(JNIEnv* env, const std::string& identifier, int x, int y, long long downTimestampMs) {
    __android_log_print(ANDROID_LOG_INFO, TAG, 
        "准备调用 sendUiPressDownPacket: identifier=%s, x=%d, y=%d, downTs=%lld",
        identifier.c_str(), x, y, downTimestampMs);

    if (!g_serviceInstance || !g_sendUiPressDownMethod) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiPressDownPacketJNI: Service实例或方法ID未初始化!");
        return;
    }

    jstring jIdentifier = env->NewStringUTF(identifier.c_str());
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiPressDownPacketJNI: NewStringUTF 失败");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }
    if (jIdentifier == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiPressDownPacketJNI: NewStringUTF 返回 null");
        return;
    }

    env->CallVoidMethod(g_serviceInstance, g_sendUiPressDownMethod, jIdentifier, (jint)x, (jint)y, (jlong)downTimestampMs);
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "callSendUiPressDownPacketJNI: CallVoidMethod 失败");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG, 
            "Java sendUiPressDownPacket 方法调用成功");
    }

    env->DeleteLocalRef(jIdentifier); 
}

/**
 * @brief 直接调用 Java 的 onInputDataReceivedFromNative 发送普通触摸数据
 */
void sendTouchEventToJava(JNIEnv* env, const std::string& data) {
    if (!g_serviceInstance || !g_onInputDataReceivedMethodID_Service) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "sendTouchEventToJava: Service 实例或 MethodID 为空");
        return;
    }

    jstring jData = env->NewStringUTF(data.c_str());
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "sendTouchEventToJava: NewStringUTF 失败");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }
    if (jData == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "sendTouchEventToJava: NewStringUTF 返回 null");
        return;
    }

    env->CallVoidMethod(g_serviceInstance, g_onInputDataReceivedMethodID_Service, jData);
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, 
            "sendTouchEventToJava: CallVoidMethod 失败");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    env->DeleteLocalRef(jData);
}
