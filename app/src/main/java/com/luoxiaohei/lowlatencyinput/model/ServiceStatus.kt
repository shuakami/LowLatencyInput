package com.luoxiaohei.lowlatencyinput.model

/**
 * 定义后台服务的不同状态。
 */
enum class ServiceStatus {
    DISCONNECTED, // 服务未连接或未运行
    CONNECTING,   // 正在连接中
    CONNECTED,    // 已连接
    ERROR         // 连接或操作出错
} 