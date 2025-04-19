#ifndef INPUT_READER_JNI_UTILS_H
#define INPUT_READER_JNI_UTILS_H

#include <jni.h>
#include "input_reader.h" // 包含定义了 ClickableRegion 和 TouchPoint 的头文件

#include <thread>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>


/**
 * @brief JNI 接口：启动输入设备读取线程
 */
void nativeStartInputReaderService(JNIEnv* env, jobject instance);

/**
 * @brief JNI 接口：停止输入设备读取线程
 */
void nativeStopInputReaderService(JNIEnv* env, jobject instance);

/**
 * @brief JNI: 更新可点击区域
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeUpdateClickableRegions(
    JNIEnv* env,
    jclass /* clazz */,
    jstring jsonData
);

/**
 * @brief JNI: 设置屏幕尺寸
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeSetScreenDimensions(
    JNIEnv* env,
    jclass /* clazz */,
    jint width,
    jint height
);

/**
 * @brief JNI: 设置屏幕偏移 (如状态栏高度)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeSetScreenOffsets(
    JNIEnv* env,
    jclass /* clazz */,
    jint topOffset,
    jint leftOffset
);

/**
 * @brief JNI: Native 请求发送 UI 点击事件包（在 C++ 中仅做空实现，逻辑在 Java 层）
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeRequestSendUiEventPacket(
    JNIEnv* env,
    jclass /* clazz */,
    jstring identifier,
    jint x,
    jint y
);

/**
 * @brief JNI: Native 请求发送 UI 长按事件包（在 C++ 中仅做空实现，逻辑在 Java 层）
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeRequestSendUiLongPressPacket(
    JNIEnv* env,
    jclass /* clazz */,
    jstring identifier,
    jint x,
    jint y
);

/**
 * @brief 向 Java 层发送数据的方法 (定义在 jni_bridge.h/.cpp)，用于普通触摸流。
 *
 * 注意在原工程中，这个函数通常由 jni_bridge 提供，以下仅声明。
 */
extern void sendDataToJavaService(const std::string& data);

/**
 * @brief 初始化 JNI 引用 (缓存类和方法 ID)
 * @param env JNI 环境指针
 * @return true 如果成功，false 如果失败
 */
bool initializeJniReferences(JNIEnv* env);

/**
 * @brief 清理 JNI 全局引用
 * @param env JNI 环境指针
 */
void cleanupJniReferences(JNIEnv* env);

/**
 * @brief 调用 Java 层的 sendUiEventPacket 方法
 */
void callSendUiEventPacketJNI(JNIEnv* env, const std::string& identifier, int x, int y);

/**
 * @brief 调用 Java 层的 sendUiLongPressPacket 方法
 */
void callSendUiLongPressPacketJNI(JNIEnv* env, const std::string& identifier, int x, int y);

/**
 * @brief 调用 Java 层的 sendUiPressDownPacket 方法
 */
void callSendUiPressDownPacketJNI(JNIEnv* env, const std::string& identifier, int x, int y, long long downTimestampMs);

/**
 * @brief 发送普通触摸事件数据到 Java 层
 */
void sendTouchEventToJava(JNIEnv* env, const std::string& data);

#endif // INPUT_READER_JNI_UTILS_H
