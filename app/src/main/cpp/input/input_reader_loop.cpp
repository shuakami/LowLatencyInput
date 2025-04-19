#include "input_reader_loop.h"
#include "input_reader_jni_utils.h"
#include "input_reader_permissions.h"

#include "input_reader.h"
#include <nlohmann/json.hpp>
#include <thread>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <sstream>
#include <chrono>
#include <algorithm>
#include <functional>
#include <fcntl.h>
#include <unistd.h>
#include <linux/input.h>
#include <linux/input-event-codes.h>
#include <android/log.h>
#include <sys/poll.h>
#include <cerrno>
#include <system_error>
#include "../bridge/jni_bridge.h"

// 使用 json 命名空间简化代码
using json = nlohmann::json;

// 日志标签
#define TAG "NativeInputReader"

// 长按开始检测的延迟常量（毫秒）
static constexpr long long LONG_PRESS_START_DELAY_MS = 150;
// 目标触摸设备路径 (示例)，如果需要自定义，这里可以改成可配置
static constexpr const char* TOUCH_DEVICE_PATH_DEFAULT = "/dev/input/event4";
static constexpr int MAX_SLOTS = 10;

/**
 * @brief 输入读取线程主循环
 */
void inputReaderLoop(const char* devicePath) {
    static constexpr size_t EVENT_SIZE = sizeof(struct input_event);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "inputReaderLoop: 线程已启动，准备处理设备 %s", devicePath);

    std::thread::id this_id = std::this_thread::get_id();
    std::stringstream ss_id;
    ss_id << this_id;
    const std::string threadTag = ss_id.str();

    int fd = open(devicePath, O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "触摸线程 %s: 初次打开 %s 失败: %s (%d)，尝试修复权限...",
            threadTag.c_str(), devicePath, strerror(errno), errno);
        if (errno == EACCES || errno == EPERM) {
            if (tryFixPermissions(devicePath)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(200));
                fd = open(devicePath, O_RDONLY | O_NONBLOCK);
                if (fd >= 0) {
                    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "触摸线程 %s: 修复后成功打开 %s", threadTag.c_str(), devicePath);
                }
            }
        }
    }
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "inputReaderLoop: 无法打开 %s，线程退出。", devicePath);
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "inputReaderLoop: 成功打开设备 %s (fd=%d)", devicePath, fd);

    // 附加到 JVM
    JNIEnv* env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "触摸线程 %s: 附加到 JVM 失败，退出。", threadTag.c_str());
        close(fd);
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "触摸线程 %s: 已附加到 JVM。", threadTag.c_str());

    // 读取 ABS 范围，用于坐标转换
    int nativeMaxX = 0;
    int nativeMaxY = 0;
    {
        struct input_absinfo absinfo_x;
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &absinfo_x) == 0) {
            nativeMaxX = absinfo_x.maximum;
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "Native X-axis range: min=%d, max=%d", absinfo_x.minimum, absinfo_x.maximum);
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "无法获取 ABS_MT_POSITION_X info: %s", strerror(errno));
        }
        struct input_absinfo absinfo_y;
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &absinfo_y) == 0) {
            nativeMaxY = absinfo_y.maximum;
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "Native Y-axis range: min=%d, max=%d", absinfo_y.minimum, absinfo_y.maximum);
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "无法获取 ABS_MT_POSITION_Y info: %s", strerror(errno));
        }
    }

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    // 本地维护的触摸点信息
    TouchPoint localTouches[MAX_SLOTS];
    for (int i = 0; i < MAX_SLOTS; i++) {
        localTouches[i].id = -1;
    }

    int currentSlot = 0;
    bool touchDataUpdated = false;

    static constexpr size_t READ_BUF_SIZE = sizeof(input_event) * 64;
    unsigned char readBuffer[READ_BUF_SIZE];
    unsigned char leftoverBuf[READ_BUF_SIZE];
    size_t leftoverCount = 0;

    size_t totalBytesRead = 0;
    auto lastLogTime = std::chrono::steady_clock::now();

    static constexpr int POLL_TIMEOUT_MS = 1;
    // 下列常量目前并未在本循环中使用
    static constexpr long long TAP_TIMEOUT_MS = 200;
    static constexpr long long LONG_PRESS_THRESHOLD_MS = 500;

    while (g_isRunning.load(std::memory_order_relaxed)) {
        int pollRet = poll(&pfd, 1, POLL_TIMEOUT_MS);
        if (pollRet < 0) {
            if (errno == EINTR) continue;
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "触摸线程 %s: poll 错误: %s (%d)，停止。",
                threadTag.c_str(), strerror(errno), errno);
            break;
        } else if (pollRet == 0) {
            // 超时，无事件
            continue;
        }
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "触摸线程 %s: poll revents=%d，停止。",
                threadTag.c_str(), pfd.revents);
            break;
        }
        // 可读
        if (!(pfd.revents & POLLIN)) {
            continue;
        }

        // 读取数据（可能一次性读到多个 struct input_event）
        ssize_t bytesRead = read(fd, readBuffer, READ_BUF_SIZE);
        if (bytesRead < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "触摸线程 %s: read 错误: %s (%d), 停止。",
                threadTag.c_str(), strerror(errno), errno);
            break;
        } else if (bytesRead == 0) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "触摸线程 %s: read=0(EOF)，设备被移除？停止。",
                threadTag.c_str());
            break;
        }

        totalBytesRead += bytesRead;
        size_t bufferOffset = 0;

        // -------------------- 先处理 leftoverBuf 里的半包 --------------------
        if (leftoverCount > 0) {
            size_t copyLen = std::min(static_cast<size_t>(bytesRead), (size_t)(EVENT_SIZE - leftoverCount));
            std::memcpy(leftoverBuf + leftoverCount, readBuffer, copyLen);
            leftoverCount += copyLen;
            if (leftoverCount == EVENT_SIZE) {
                struct input_event ev;
                std::memcpy(&ev, leftoverBuf, EVENT_SIZE);
                leftoverCount = 0;

                // 按事件类型处理
                if (ev.type == EV_ABS) {
                    if (ev.code == ABS_MT_SLOT) {
                        currentSlot = ev.value;
                        if (currentSlot < 0 || currentSlot >= MAX_SLOTS) {
                            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "无效 slot %d，重置为0", currentSlot);
                            currentSlot = 0;
                        }
                    } else if (ev.code == ABS_MT_TRACKING_ID) {
                        TouchPoint& tp = localTouches[currentSlot];
                        if (ev.value == -1) {
                            // 手指抬起
                            if (tp.isDown && tp.maybeUiTap) {
                                int rotatedX = (nativeMaxY > 0) 
                                    ? (tp.y * g_screenWidthPx / nativeMaxY) : tp.y;
                                int rotatedY = (nativeMaxX > 0) 
                                    ? ((nativeMaxX - tp.x) * g_screenHeightPx / nativeMaxX)
                                    : (nativeMaxX - tp.x);
                                int adjustedX = rotatedX - g_screenLeftOffsetPx;
                                int adjustedY = rotatedY - g_screenTopOffsetPx;

                                if (tp.longPressStartSent) {
                                    __android_log_print(ANDROID_LOG_INFO, TAG,
                                        "[Slot=%d] 长按结束 (已发送0x08): %s",
                                        currentSlot, tp.downRegionIdentifier.c_str());
                                    callSendUiLongPressPacketJNI(env, tp.downRegionIdentifier, adjustedX, adjustedY);
                                }
                                tp.uiTapHandled = true;
                            }
                            tp.id = -1;
                            tp.isDown = false;
                            tp.maybeUiTap = false;
                            tp.uiTapHandled = false;
                            tp.downRegionIdentifier.clear();
                            tp.isCheckingForLongPressStart = false;
                            tp.longPressStartSent = false;
                        } else {
                            // 手指按下
                            tp.id = ev.value;
                            tp.isDown = true;
                            tp.uiTapHandled = false;
                            tp.isCheckingForLongPressStart = false;
                            tp.longPressStartSent = false;
                            long long nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::steady_clock::now().time_since_epoch()).count();
                            tp.downTimestampMs = nowMs;
                            tp.downRegionIdentifier.clear();
                        }
                        touchDataUpdated = true;
                    } else if (ev.code == ABS_MT_POSITION_X) {
                        localTouches[currentSlot].x = ev.value;
                        touchDataUpdated = true;
                    } else if (ev.code == ABS_MT_POSITION_Y) {
                        localTouches[currentSlot].y = ev.value;
                        touchDataUpdated = true;
                    }
                } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                    if (touchDataUpdated) {
                        long long currentTimestampMs =
                            (long long)ev.time.tv_sec * 1000 + (long long)ev.time.tv_usec / 1000;
                        if (currentTimestampMs == 0) {
                            currentTimestampMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::steady_clock::now().time_since_epoch()).count();
                        }
                        std::string dataToSend = "T";
                        int activePoints = 0;

                        {
                            std::lock_guard<std::mutex> lock(g_regionMutex);
                            for (int i = 0; i < MAX_SLOTS; i++) {
                                TouchPoint& tp = localTouches[i];
                                if (tp.id != -1) {
                                    int rotatedX = (nativeMaxY > 0)
                                        ? (tp.y * g_screenWidthPx / nativeMaxY) : tp.y;
                                    int rotatedY = (nativeMaxX > 0)
                                        ? ((nativeMaxX - tp.x) * g_screenHeightPx / nativeMaxX)
                                        : (nativeMaxX - tp.x);
                                    int adjustedX = rotatedX - g_screenLeftOffsetPx;
                                    int adjustedY = rotatedY - g_screenTopOffsetPx;

                                    if (tp.isDown && !tp.maybeUiTap) {
                                        tp.downX = adjustedX;
                                        tp.downY = adjustedY;
                                        for (auto & region : g_clickableRegions) {
                                            if (adjustedX >= region.left && 
                                                adjustedX < (region.left + region.width) &&
                                                adjustedY >= region.top && 
                                                adjustedY < (region.top + region.height)) {
                                                tp.maybeUiTap = true;
                                                tp.downRegionIdentifier = region.identifier;
                                                tp.isCheckingForLongPressStart = true;
                                                tp.longPressStartSent = false;
                                                __android_log_print(ANDROID_LOG_INFO, TAG,
                                                    "[Slot=%d] 按下命中区域: %s (X=%d,Y=%d), 立即发送点击事件并准备检查长按...",
                                                    i, region.identifier.c_str(), adjustedX, adjustedY);
                                                callSendUiEventPacketJNI(env, region.identifier, adjustedX, adjustedY);
                                                break;
                                            }
                                        }
                                    }

                                    if (!tp.uiTapHandled) {
                                        dataToSend += "|" + std::to_string(tp.id)
                                            + "," + std::to_string(adjustedX)
                                            + "," + std::to_string(adjustedY);
                                        activePoints++;
                                    }
                                }
                            }
                        }
                        if (activePoints > 0) {
                            dataToSend += ";" + std::to_string(currentTimestampMs);
                            sendTouchEventToJava(env, dataToSend);
                        }
                        touchDataUpdated = false;
                    }
                }
            }
            bufferOffset = copyLen;
        }

        // -------------------- 处理本次新读取的数据 --------------------
        while (bufferOffset + EVENT_SIZE <= (size_t)bytesRead) {
            struct input_event ev;
            std::memcpy(&ev, readBuffer + bufferOffset, EVENT_SIZE);
            bufferOffset += EVENT_SIZE;

            if (ev.type == EV_ABS) {
                if (ev.code == ABS_MT_SLOT) {
                    currentSlot = ev.value;
                    if (currentSlot < 0 || currentSlot >= MAX_SLOTS) {
                        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "无效 slot %d => 置0", currentSlot);
                        currentSlot = 0;
                    }
                } else if (ev.code == ABS_MT_TRACKING_ID) {
                    TouchPoint& tp = localTouches[currentSlot];
                    if (ev.value == -1) {
                        // 手指抬起
                        if (tp.isDown && tp.maybeUiTap) {
                            int rotatedX = (nativeMaxY > 0) 
                                ? (tp.y * g_screenWidthPx / nativeMaxY) : tp.y;
                            int rotatedY = (nativeMaxX > 0)
                                ? ((nativeMaxX - tp.x) * g_screenHeightPx / nativeMaxX)
                                : (nativeMaxX - tp.x);
                            int adjustedX = rotatedX - g_screenLeftOffsetPx;
                            int adjustedY = rotatedY - g_screenTopOffsetPx;

                            if (tp.longPressStartSent) {
                                __android_log_print(ANDROID_LOG_INFO, TAG,
                                    "[Slot=%d] 长按结束 (已发送0x08): %s",
                                    currentSlot, tp.downRegionIdentifier.c_str());
                                callSendUiLongPressPacketJNI(env, tp.downRegionIdentifier, adjustedX, adjustedY);
                            }
                            tp.uiTapHandled = true;
                        }
                        tp.id = -1;
                        tp.isDown = false;
                        tp.maybeUiTap = false;
                        tp.uiTapHandled = false;
                        tp.downRegionIdentifier.clear();
                        tp.isCheckingForLongPressStart = false;
                        tp.longPressStartSent = false;
                    } else {
                        // 手指按下
                        tp.id = ev.value;
                        tp.isDown = true;
                        tp.uiTapHandled = false;
                        tp.isCheckingForLongPressStart = false;
                        tp.longPressStartSent = false;
                        long long nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now().time_since_epoch()).count();
                        tp.downTimestampMs = nowMs;
                        tp.downRegionIdentifier.clear();
                    }
                    touchDataUpdated = true;
                } else if (ev.code == ABS_MT_POSITION_X) {
                    localTouches[currentSlot].x = ev.value;
                    touchDataUpdated = true;
                } else if (ev.code == ABS_MT_POSITION_Y) {
                    localTouches[currentSlot].y = ev.value;
                    touchDataUpdated = true;
                }
            } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                if (touchDataUpdated) {
                    long long currentTimestampMs =
                        (long long)ev.time.tv_sec * 1000 + (long long)ev.time.tv_usec / 1000;
                    if (currentTimestampMs == 0) {
                        currentTimestampMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now().time_since_epoch()).count();
                    }
                    std::string dataToSend = "T";
                    int activePoints = 0;

                    {
                        std::lock_guard<std::mutex> lock(g_regionMutex);
                        for (int i = 0; i < MAX_SLOTS; i++) {
                            TouchPoint& tp = localTouches[i];
                            if (tp.id != -1) {
                                int rotatedX = (nativeMaxY > 0)
                                    ? (tp.y * g_screenWidthPx / nativeMaxY) : tp.y;
                                int rotatedY = (nativeMaxX > 0)
                                    ? ((nativeMaxX - tp.x) * g_screenHeightPx / nativeMaxX)
                                    : (nativeMaxX - tp.x);
                                int adjustedX = rotatedX - g_screenLeftOffsetPx;
                                int adjustedY = rotatedY - g_screenTopOffsetPx;

                                if (tp.isDown && !tp.maybeUiTap) {
                                    tp.downX = adjustedX;
                                    tp.downY = adjustedY;
                                    for (auto & region : g_clickableRegions) {
                                        if (adjustedX >= region.left && 
                                            adjustedX < (region.left + region.width) &&
                                            adjustedY >= region.top && 
                                            adjustedY < (region.top + region.height)) {
                                            tp.maybeUiTap = true;
                                            tp.downRegionIdentifier = region.identifier;
                                            tp.isCheckingForLongPressStart = true;
                                            tp.longPressStartSent = false;
                                            __android_log_print(ANDROID_LOG_INFO, TAG,
                                                "[Slot=%d] 按下命中区域: %s (X=%d,Y=%d), 立即发送点击事件并准备检查长按...",
                                                i, region.identifier.c_str(), adjustedX, adjustedY);
                                            callSendUiEventPacketJNI(env, region.identifier, adjustedX, adjustedY);
                                            break;
                                        }
                                    }
                                }

                                if (!tp.uiTapHandled) {
                                    dataToSend += "|" + std::to_string(tp.id)
                                        + "," + std::to_string(adjustedX)
                                        + "," + std::to_string(adjustedY);
                                    activePoints++;
                                }
                            }
                        }
                    }

                    if (activePoints > 0) {
                        dataToSend += ";" + std::to_string(currentTimestampMs);
                        sendTouchEventToJava(env, dataToSend);
                    }
                    touchDataUpdated = false;
                }
            }
        }

        // 处理剩余不完整数据
        size_t remainingBytes = bytesRead - bufferOffset;
        if (remainingBytes > 0) {
            std::memcpy(leftoverBuf, readBuffer + bufferOffset, remainingBytes);
            leftoverCount = remainingBytes;
        } else {
            leftoverCount = 0;
        }

        // -------------------- 长按开始检测 --------------------
        long long checkTimeMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        for (int i = 0; i < MAX_SLOTS; ++i) {
            TouchPoint& tp = localTouches[i];
            if (tp.isDown && tp.maybeUiTap && tp.isCheckingForLongPressStart && !tp.longPressStartSent) {
                long long duration = checkTimeMs - tp.downTimestampMs;
                if (duration >= LONG_PRESS_START_DELAY_MS) {
                    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "[Slot=%d] 达到长按开始延迟 (%lld ms >= %lld ms), 发送按下事件: %s",
                        i, duration, LONG_PRESS_START_DELAY_MS, tp.downRegionIdentifier.c_str());
                    callSendUiPressDownPacketJNI(env, tp.downRegionIdentifier, tp.downX, tp.downY, tp.downTimestampMs);
                    tp.longPressStartSent = true;
                    tp.isCheckingForLongPressStart = false;
                }
            }
        }
        // -------------------- 检测结束 --------------------

        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::seconds>(now - lastLogTime).count() >= 10) {
            __android_log_print(ANDROID_LOG_DEBUG, TAG,
                "触摸线程 %s: 循环活跃。已读 %zu 字节, leftover=%zu",
                threadTag.c_str(), totalBytesRead, leftoverCount);
            lastLogTime = now;
        }
    }

    // 清理资源
    if (fd >= 0) {
        close(fd);
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "触摸线程 %s: 关闭设备 fd=%d", threadTag.c_str(), fd);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "触摸线程 %s: 准备退出", threadTag.c_str());

    // 从JVM分离当前线程
    if (g_jvm->DetachCurrentThread() != JNI_OK) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "触摸线程 %s: 从 JVM 分离失败", threadTag.c_str());
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "触摸线程 %s: 退出。", threadTag.c_str());
}
