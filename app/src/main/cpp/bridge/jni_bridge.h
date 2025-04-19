 #ifndef JNI_BRIDGE_H
#define JNI_BRIDGE_H

#include <jni.h>
#include <string>
#include <mutex>

// 全局 JNI 变量 (外部链接)
extern JavaVM* g_jvm;
extern jobject g_serviceInstance;
extern jmethodID g_onInputDataReceivedMethodID_Service;
extern std::mutex g_jniMutex; // JNI 相关操作的互斥锁

/**
 * @brief 初始化 Service 相关的 JNI 资源。
 * 
 * @param env JNI 环境指针。
 * @param serviceInstance GyroscopeService 的实例。
 */
void nativeInitJNIService(JNIEnv* env, jobject serviceInstance);

/**
 * @brief 释放 Service 相关的 JNI 资源。
 * 
 * @param env JNI 环境指针。
 * @param serviceInstance GyroscopeService 的实例 (当前未使用，但保留签名一致性)。
 */
void nativeReleaseJNIService(JNIEnv* env, jobject serviceInstance);

/**
 * @brief 将数据发送到 Java Service 层。
 * 
 * 线程安全：内部使用 g_jniMutex 进行保护。
 * @param data 要发送的数据字符串。
 */
void sendDataToJavaService(const std::string& data);

#endif // JNI_BRIDGE_H
