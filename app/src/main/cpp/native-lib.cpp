/**
 * @file native-lib.cpp
 * @brief 陀螺仪服务的JNI实现（委托模式）
 * 
 * 这个文件实现了Android应用与Native层之间的JNI桥接功能。
 * 主要负责处理陀螺仪服务相关的初始化、释放以及输入事件读取等操作。具体实现通过委托模式转发给相应的功能模块处理。
 */

#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <sstream>
#include <chrono>

#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <linux/input.h>
#include <linux/input-event-codes.h>
#include <stdio.h>
#include <grp.h>
#include <pwd.h>
#include <sys/wait.h>
#include <errno.h>
#include <sys/poll.h>
#include <cstring>

#include "bridge/jni_bridge.h"
#include "input/input_reader.h"

/**
 * @brief 初始化陀螺仪服务的JNI环境
 * 该函数负责设置JNI桥接所需的全局引用和方法ID
 * 具体实现委托给jni_bridge.cpp中的nativeInitJNIService
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeInitJNIService(
    JNIEnv* env, jobject serviceInstance
) {
    nativeInitJNIService(env, serviceInstance);
}

/**
 * @brief 释放陀螺仪服务的JNI资源
 * 清理在初始化时创建的全局引用，防止内存泄漏
 * 具体实现委托给jni_bridge.cpp中的nativeReleaseJNIService
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeReleaseJNIService(
    JNIEnv* env, jobject serviceInstance 
) {
    nativeReleaseJNIService(env, serviceInstance);
}

/**
 * @brief 启动输入读取服务
 * 开始监听设备输入事件，并通过JNI回调将数据传递给Java层
 * 具体实现委托给input_reader.cpp中的nativeStartInputReaderService
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeStartInputReaderService(
    JNIEnv* env, jobject serviceInstance 
) {
    nativeStartInputReaderService(env, serviceInstance);
}

/**
 * @brief 停止输入读取服务
 * 终止输入事件监听，清理相关资源
 * 具体实现委托给input_reader.cpp中的nativeStopInputReaderService
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeStopInputReaderService(
    JNIEnv* env, jobject serviceInstance 
) {
    nativeStopInputReaderService(env, serviceInstance);
}
