package com.luoxiaohei.lowlatencyinput

import java.nio.ByteOrder

/**
 * 存放整个应用程序共享的常量值。
 */
object Constants {

    /**
     * 目标服务器的主机名或 IP 地址。
     */
    const val TARGET_HOST = "127.0.0.1"

    /**
     * 目标服务器监听的 TCP 端口号。
     */
    const val TARGET_PORT = 12345

    /**
     * 建立 TCP 连接时的超时时间（毫秒）。
     */
    const val CONNECTION_TIMEOUT_MS = 5000

    /**
     * 标记数据包包含触摸事件数据。
     */
    const val PACKET_TYPE_TOUCH: Byte = 0x01

    /**
     * 标记数据包包含陀螺仪传感器数据。
     */
    const val PACKET_TYPE_GYRO: Byte = 0x02

    /**
     * 标记数据包是服务器对 PING 的确认响应 (ACK)，
     * 使用 0xFE (-2) 作为 ACK 标识。
     */
    const val PACKET_TYPE_ACK: Byte = 0xFE.toByte()

    /**
     * 标记数据包是客户端发送的 PING 请求，用于 RTT 测量和保持连接。
     */
    const val PACKET_TYPE_PING: Byte = 0x03

    /**
     * 标记数据包包含加速度计传感器数据。
     */
    const val PACKET_TYPE_ACCEL: Byte = 0x04

    /**
     * 标记数据包包含一个 UI 点击事件信息（例如按钮点击）。
     * Payload 通常是触发事件的 UI 元素名称 (UTF-8 String) + (x, y)。
     */
    const val PACKET_TYPE_UI_EVENT: Byte = 0x05

    /**
     * 标记数据包包含设备信息（例如屏幕尺寸）。
     */
    const val PACKET_TYPE_DEVICE_INFO: Byte = 0x06

    /**
     * 标记数据包包含一个 UI 长按事件信息（例如按钮长按）。
     * 这是长按动作**结束**时发送的包。
     */
    const val PACKET_TYPE_UI_LONG_PRESS: Byte = 0x07

    /**
     * 标记数据包包含一个 UI 按下事件信息（例如按钮按下）。
     * 这是长按动作**开始**时发送的包。
     */
    const val PACKET_TYPE_UI_PRESS_DOWN: Byte = 0x08

    /**
     * 网络传输中多字节数据（如 Long, Int, Float）使用的字节序。
     * 这里使用 BIG_ENDIAN（高位字节在前）来示例。
     */
    val BYTE_ORDER: ByteOrder = ByteOrder.BIG_ENDIAN

    /**
     * 单个触摸数据包中允许包含的最大触摸点数量。
     */
    const val MAX_TOUCH_POINTS = 10

    /**
     * 控制 RTT 统计日志输出的频率。
     */
    const val RTT_LOG_INTERVAL = 100

    /**
     * 启动后台捕获服务的 Intent Action 字符串。
     */
    const val ACTION_START_CAPTURE = "com.luoxiaohei.lowlatencyinput.ACTION_START_CAPTURE"

    /**
     * 停止后台捕获服务的 Intent Action 字符串。
     */
    const val ACTION_STOP_CAPTURE = "com.luoxiaohei.lowlatencyinput.ACTION_STOP_CAPTURE"

    /**
     * 广播 Action，当悬浮窗中的 UI 元素被触发时发送。
     */
    const val ACTION_UI_EVENT_TRIGGERED = "com.luoxiaohei.lowlatencyinput.ACTION_UI_EVENT_TRIGGERED"

    /**
     * 用于在 UI 事件广播 Intent 中传递触发事件的 UI 元素名称的 Extra Key。
     */
    const val EXTRA_UI_ELEMENT_NAME = "com.luoxiaohei.lowlatencyinput.EXTRA_UI_ELEMENT_NAME"

    /**
     * 用于在 UI 事件广播 Intent 中传递点击事件 X 坐标的 Extra Key。
     */
    const val EXTRA_CLICK_X = "com.luoxiaohei.lowlatencyinput.EXTRA_CLICK_X"

    /**
     * 用于在 UI 事件广播 Intent 中传递点击事件 Y 坐标的 Extra Key。
     */
    const val EXTRA_CLICK_Y = "com.luoxiaohei.lowlatencyinput.EXTRA_CLICK_Y"

    /**
     * 前台服务所需通知渠道的唯一 ID (Android 8.0+)。
     */
    const val NOTIFICATION_CHANNEL_ID = "LowLatencyInputServiceChannel"

    /**
     * 前台服务通知的唯一 ID。
     */
    const val NOTIFICATION_ID = 1
}
