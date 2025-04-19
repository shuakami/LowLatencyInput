#include "jni_bridge.h"
#include <android/log.h>
#include <mutex>
#include <string>

#define TAG "JniBridge" // 定义此模块在日志中的标识

// --------------- 全局 JNI 变量定义 ---------------
// 保持 JVM 实例、服务对象以及回调方法的全局引用，方便在 native 层与 Java 层交互
JavaVM* g_jvm = nullptr;
jobject g_serviceInstance = nullptr;
jmethodID g_onInputDataReceivedMethodID_Service = nullptr;
std::mutex g_jniMutex; // 用于在 JNI 回调操作时保证线程安全

// --------------- JNI_OnLoad ---------------
// 当系统加载此动态库时，会调用 JNI_OnLoad 来获取当前 JNI 版本并保存全局的 JVM 引用
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }
    
    g_jvm = vm;
    __android_log_print(ANDROID_LOG_INFO, TAG, "JNI_OnLoad: JVM saved successfully");
    
    return JNI_VERSION_1_6;
}

// --------------- JNI 回调实现 ---------------
// 将字符串数据从 C++ 层回调到 Java 层的 Service 中
void sendDataToJavaService(const std::string& data) {
    // 在回调过程中使用互斥锁，避免多线程对全局引用的竞争
    std::lock_guard<std::mutex> lock(g_jniMutex);

    // 在任何 JNI 操作前，先确认关键资源是否已经初始化
    if (!g_jvm || !g_serviceInstance || !g_onInputDataReceivedMethodID_Service) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "JNI callback to service skipped: JVM, Service instance, or MethodID not ready.");
        return;
    }

    JNIEnv* env = nullptr;
    // 将当前线程附着到 JVM，获取 JNIEnv 以调用 Java 方法
    jint attachResult = g_jvm->AttachCurrentThread(&env, nullptr);
    if (attachResult != JNI_OK || !env) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Failed to attach JNI thread in sendDataToJavaService. Error code: %d", attachResult);
        // 无法附着线程时直接返回
        return;
    }

    // 将 C++ 字符串转换为 Java 层可识别的 jstring
    jstring javaString = env->NewStringUTF(data.c_str());
    if (!javaString) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Failed to create Java string for data.");
        // 创建 jstring 失败后，需要分离线程并返回
        g_jvm->DetachCurrentThread();
        return;
    }

    // 调用 Java Service 中的 onInputDataReceivedFromNative(String) 方法
    env->CallVoidMethod(g_serviceInstance, g_onInputDataReceivedMethodID_Service, javaString);
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Exception occurred calling Service callback method. Clearing...");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    // 释放局部引用，避免局部引用表溢出
    env->DeleteLocalRef(javaString);
}

// --------------- Service JNI 实现 ---------------
// nativeInitJNIService / nativeReleaseJNIService 供 Java 层初始化与释放服务引用

// 初始化 Service 的全局引用，并获取回调方法的 MethodID
void nativeInitJNIService(JNIEnv* env, jobject serviceInstance) {
    if (!env) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeInitJNIService: null env");
        return;
    }
    
    std::lock_guard<std::mutex> lock(g_jniMutex);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeInitJNIService: starting initialization");
    
    try {
        // 清理旧的引用
        if (g_serviceInstance) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "Cleaning up old service reference");
            env->DeleteGlobalRef(g_serviceInstance);
            g_serviceInstance = nullptr;
            g_onInputDataReceivedMethodID_Service = nullptr;
        }
        
        // 创建新的全局引用
        g_serviceInstance = env->NewGlobalRef(serviceInstance);
        if (!g_serviceInstance) {
            throw std::runtime_error("Failed to create global ref for Service instance");
        }
        
        // 获取类引用
        jclass serviceClass = env->GetObjectClass(g_serviceInstance);
        if (!serviceClass) {
            throw std::runtime_error("Failed to get Service class");
        }
        
        // 获取方法ID
        g_onInputDataReceivedMethodID_Service = env->GetMethodID(
            serviceClass, "onInputDataReceivedFromNative", "(Ljava/lang/String;)V"
        );
        if (!g_onInputDataReceivedMethodID_Service) {
            env->DeleteLocalRef(serviceClass);
            throw std::runtime_error("Failed to get method ID");
        }
        
        env->DeleteLocalRef(serviceClass);
        __android_log_print(ANDROID_LOG_INFO, TAG, "nativeInitJNIService: initialization successful");
        
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeInitJNIService failed: %s", e.what());
        if (g_serviceInstance) {
            env->DeleteGlobalRef(g_serviceInstance);
            g_serviceInstance = nullptr;
        }
        g_onInputDataReceivedMethodID_Service = nullptr;
        
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }
}

// 释放全局的 Service 引用，清理相关资源
void nativeReleaseJNIService(JNIEnv* env, jobject /* serviceInstance */) {
    std::lock_guard<std::mutex> lock(g_jniMutex);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeReleaseJNIService called.");

    if (g_serviceInstance) {
        env->DeleteGlobalRef(g_serviceInstance);
        g_serviceInstance = nullptr;
        g_onInputDataReceivedMethodID_Service = nullptr;
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "Service JNI global reference released.");
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "nativeReleaseJNIService called but no service reference to release.");
    }
}
