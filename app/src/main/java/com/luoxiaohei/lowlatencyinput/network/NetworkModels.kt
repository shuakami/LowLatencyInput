package com.luoxiaohei.lowlatencyinput.network

import java.util.concurrent.TimeUnit

/**
 * 网络连接状态枚举。
 */
enum class ConnectionStatus {
    DISCONNECTED, // 已断开
    CONNECTING,   // 连接中
    CONNECTED,    // 已连接
    ERROR         // 发生错误
}

/**
 * RTT（往返时间）统计信息。
 * @param averageMs 平均 RTT (毫秒)。
 * @param minMs 最小 RTT (毫秒)。
 * @param maxMs 最大 RTT (毫秒)。
 * @param count 统计次数。
 */
data class RttStats(
    val averageMs: Double = 0.0,
    val minMs: Long = -1L,
    val maxMs: Long = -1L,
    val count: Int = 0
) {
    override fun toString(): String {
        val minStr = if (minMs == -1L) "N/A" else "${minMs}ms"
        val maxStr = if (maxMs == -1L) "N/A" else "${maxMs}ms"
        return String.format("Avg=%.2fms, Min=%s, Max=%s (Count=%d)", averageMs, minStr, maxStr, count)
    }
} 

/**
 * 封装从服务器接收到的原始数据包信息。
 * 目前只包含包类型，因为 Payload 的读取和解析由订阅者负责。
 */
data class ServerPacket(
    val packetType: Byte,
    val payload: ByteArray? // Payload 数据 (如果存在)
) {
    // 重写 equals 和 hashCode 以正确处理 ByteArray 比较
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ServerPacket

        if (packetType != other.packetType) return false
        if (payload != null) {
            if (other.payload == null) return false
            if (!payload.contentEquals(other.payload)) return false
        } else if (other.payload != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packetType.toInt()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}
