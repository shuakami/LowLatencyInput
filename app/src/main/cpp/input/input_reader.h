#ifndef INPUT_READER_H
#define INPUT_READER_H

#include <thread>
#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

/**
 * @brief 可点击区域信息结构体
 */
struct ClickableRegion {
    std::string identifier;
    int left = 0;
    int top = 0;
    int width = 0;
    int height = 0;
};

/**
 * @brief 单个触摸点 (slot) 的状态信息
 */
struct TouchPoint {
    int id = -1;              // tracking ID, -1 表示抬起
    int x = 0;
    int y = 0;

    bool isDown = false;      // 当前手指是否按下
    bool maybeUiTap = false;  // 是否命中了 UI 区域
    bool uiTapHandled = false;// 是否已处理（用于阻止回传普通触摸数据）
    long long downTimestampMs = 0; // 按下时的时间戳 (毫秒)
    std::string downRegionIdentifier; // 命中的区域标识
    int downX = 0;
    int downY = 0;

    // --- 新增：长按延迟判断状态 ---
    bool isCheckingForLongPressStart = false; // 是否正在检查长按开始
    bool longPressStartSent = false;         // 是否已发送 0x08 包
};

// ----------------- 全局变量 -----------------
extern std::atomic<bool> g_isRunning;                 // 控制线程是否继续运行
extern std::vector<std::thread> g_readerThreads;
extern std::mutex g_threadMutex;

extern std::vector<ClickableRegion> g_clickableRegions;
extern std::mutex g_regionMutex;

extern int g_screenWidthPx;
extern int g_screenHeightPx;
extern int g_screenTopOffsetPx;
extern int g_screenLeftOffsetPx;

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
    JNIEnv *env,
    jclass /* clazz */,
    jstring jsonData
);

/**
 * @brief JNI: 设置屏幕尺寸
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeSetScreenDimensions(
    JNIEnv *env,
    jclass /* clazz */,
    jint width,
    jint height
);

/**
 * @brief JNI: 设置屏幕偏移 (如状态栏高度)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeSetScreenOffsets(
    JNIEnv *env,
    jclass /* clazz */,
    jint topOffset,
    jint leftOffset
);

/**
 * @brief JNI: Native 请求发送 UI 点击事件包（在 C++ 中仅做空实现，逻辑在 Java 层）
 */
extern "C" JNIEXPORT void JNICALL
Java_com_luoxiaohei_lowlatencyinput_service_GyroscopeService_nativeRequestSendUiEventPacket(
    JNIEnv *env,
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
    JNIEnv *env,
    jclass /* clazz */,
    jstring identifier,
    jint x,
    jint y
);

/**
 * @brief 向 Java 层发送数据的方法 (定义在 jni_bridge.h/.cpp)，用于普通触摸流。
 *        注意在原工程中，这个函数通常由 jni_bridge 提供，以下仅声明。
 */
extern void sendDataToJavaService(const std::string& data);

#endif // INPUT_READER_H
