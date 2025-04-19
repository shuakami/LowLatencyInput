package com.luoxiaohei.lowlatencyinput.network

import android.util.Log
import com.luoxiaohei.lowlatencyinput.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 负责 TCP 网络通信、PING/ACK 机制和 RTT 计算的组件。
 * 使用 Kotlin 协程对 Socket 进行管理，通过 StateFlow 暴露连接状态和 RTT 统计信息。
 */
class TcpCommunicator(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val TAG = "TcpCommunicator"

    // 新增：存储最后连接的主机和端口以供重连使用
    private var currentHost: String? = null
    private var currentPort: Int? = null

    // --- 配置常量 ---
    private val PING_INTERVAL_MS = 1000L                 // PING 发送间隔
    private val SOCKET_READ_TIMEOUT_MS = 5000            // 读取超时时间
    private val BYTE_ORDER: ByteOrder = Constants.BYTE_ORDER
    private val PACKET_TYPE_ACK: Byte = Constants.PACKET_TYPE_ACK
    private val PACKET_TYPE_PING: Byte = Constants.PACKET_TYPE_PING

    // --- 网络组件引用 ---
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val outputStreamLock = Any() // 通过锁同步写操作，防止多协程并发写造成混乱

    // --- 连接状态 Flow ---
    private val _connectionStatusFlow = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatusFlow: StateFlow<ConnectionStatus> = _connectionStatusFlow.asStateFlow()

    // --- RTT 统计相关变量 ---
    private val rttSum = AtomicLong(0)
    private val rttCount = AtomicInteger(0)
    private val minRtt = AtomicLong(Long.MAX_VALUE)
    private val maxRtt = AtomicLong(0)
    private val RTT_LOG_INTERVAL = 100  // 每收集多少次 RTT 后输出一次日志

    // --- RTT 统计 Flow ---
    private val _rttStatsFlow = MutableStateFlow<RttStats?>(null)
    val rttStatsFlow: StateFlow<RttStats?> = _rttStatsFlow.asStateFlow()

    // 新增：用于广播非 ACK 服务器数据包的 SharedFlow
    // 使用 replay = 0 避免新订阅者收到旧消息，extraBufferCapacity 增加缓冲区应对突发
    private val _serverPacketFlow = MutableSharedFlow<ServerPacket>(replay = 0, extraBufferCapacity = 64)
    val serverPacketFlow: SharedFlow<ServerPacket> = _serverPacketFlow.asSharedFlow()

    // --- 协程 Jobs (便于统一管理) ---
    private var connectJob: Job? = null
    private var pingJob: Job? = null
    private var serverListenerJob: Job? = null

    /**
     * 尝试连接到指定服务器。若已处于连接中或已连接则直接返回。
     * @param host 服务器主机名或 IP 地址
     * @param port 服务器端口号
     */
    fun connect(host: String, port: Int) {
        // 避免重复连接
        if (_connectionStatusFlow.value == ConnectionStatus.CONNECTING
            || _connectionStatusFlow.value == ConnectionStatus.CONNECTED
        ) {
            Log.i(TAG, "外部调用 connect 时，连接尝试已在进行中或已连接。")
            return
        }
        _connectionStatusFlow.value = ConnectionStatus.CONNECTING

        // 新增：存储主机和端口
        this.currentHost = host
        this.currentPort = port

        // 在开始连接循环前先清理旧连接
        cleanupConnection(ConnectionStatus.CONNECTING)
        resetRttStats() // 重置 RTT 统计

        // 调用内部函数启动连接循环
        launchConnectionLoop(host, port)
    }

    /**
     * 内部函数，启动包含重试逻辑的连接协程。
     */
    private fun launchConnectionLoop(host: String, port: Int) {
        connectJob = scope.launch {
            var retryDelay = 1000L
            val maxRetryDelay = 30000L
            var attempt = 1

            while (isActive) {
                try {
                    Log.i(TAG, "尝试连接 $host:$port (第 $attempt 次)...")
                    val socket = Socket()
                    withContext(Dispatchers.IO) {
                        // 在 IO 线程上进行阻塞式连接
                        socket.connect(InetSocketAddress(host, port), Constants.CONNECTION_TIMEOUT_MS)
                        socket.tcpNoDelay = true               // 禁用 Nagle 算法，减少延迟
                        socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                    }

                    clientSocket = socket
                    outputStream = socket.getOutputStream()
                    inputStream = socket.getInputStream()
                    _connectionStatusFlow.value = ConnectionStatus.CONNECTED
                    Log.i(TAG, "成功连接到服务器 (第 $attempt 次)。")

                    // 启动 ACK 监听和 PING 协程
                    startServerListener()
                    startPingJob()
                    break // 连接成功后退出重试循环

                } catch (e: Exception) {
                    Log.w(TAG, "连接尝试 $attempt 失败: ${e.javaClass.simpleName} - ${e.message}")
                    if (!isActive) {
                        _connectionStatusFlow.value = ConnectionStatus.DISCONNECTED
                        break
                    }
                    // 若协程仍活跃，重试连接
                    Log.i(TAG, "将在 ${retryDelay / 1000} 秒后重试。")
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(maxRetryDelay)
                    attempt++
                } finally {
                    // 若退出循环仍未成功连接，则标记状态
                    if (isActive && _connectionStatusFlow.value != ConnectionStatus.CONNECTED) {
                        _connectionStatusFlow.value = ConnectionStatus.ERROR
                    }
                }
            }
            Log.i(TAG, "连接协程结束 (isActive=$isActive, Status=${_connectionStatusFlow.value})。")
        }
    }

    /**
     * 主动断开与服务器的连接，并清理资源。
     */
    fun disconnect() {
        cleanupConnection(ConnectionStatus.DISCONNECTED)
    }

    /**
     * 清理当前连接的网络资源，并根据需要重置连接状态。
     * @param finalStatus 断开后 Flow 中的连接状态
     */
    private fun cleanupConnection(finalStatus: ConnectionStatus) {
        val previousStatus = _connectionStatusFlow.value
        _connectionStatusFlow.value = finalStatus

        // 取消所有协程
        connectJob?.cancel()
        pingJob?.cancel()
        serverListenerJob?.cancel()

        // 关闭流和 Socket
        try { outputStream?.close() } catch (e: IOException) { /* 忽略异常 */ }
        try { inputStream?.close() } catch (e: IOException) { /* 忽略异常 */ }
        try {
            clientSocket?.close()
            if (previousStatus == ConnectionStatus.CONNECTED) {
                Log.i(TAG, "Socket 已断开并关闭。连接状态: $finalStatus")
            }
        } catch (e: IOException) {
            /* 忽略异常 */
        }

        // 置空资源引用
        clientSocket = null
        outputStream = null
        inputStream = null
        connectJob = null
        pingJob = null
        serverListenerJob = null
    }

    /**
     * 检测到连接错误后，更新状态并决定是否需要后续重连逻辑。
     * @param caller 调用此方法的来源标签，仅用于日志
     */
    private fun handleConnectionError(caller: String = "Unknown") {
        val currentStatus = _connectionStatusFlow.value
        if (currentStatus == ConnectionStatus.CONNECTED || currentStatus == ConnectionStatus.CONNECTING) {
            // 在清理之前获取主机和端口
            val hostToReconnect = currentHost
            val portToReconnect = currentPort

            Log.w(TAG, "检测到连接错误 (来自 $caller)，当前状态: $currentStatus。 尝试重连...")
            // 先清理连接，将状态置为 CONNECTING，准备重连
            cleanupConnection(ConnectionStatus.CONNECTING)

            // 如果有有效的主机和端口，则启动新的重连协程
            if (hostToReconnect != null && portToReconnect != null) {
                scope.launch {
                    Log.i(TAG, "将在 2 秒后尝试重新连接 $hostToReconnect:$portToReconnect...")
                    delay(2000L)
                    launchConnectionLoop(hostToReconnect, portToReconnect)
                }
            } else {
                Log.e(TAG, "无法重连：缺少主机或端口信息。")
                _connectionStatusFlow.value = ConnectionStatus.ERROR
            }
        }
    }

    /**
     * 发送自定义数据包到服务器。若未连接则跳过。
     * @param packetType 数据包类型标识
     * @param payload 数据包负载内容
     * @param description 用于日志识别此发送操作的名称或描述
     */
    fun sendPacket(packetType: Byte, payload: ByteBuffer, description: String) {
        val currentOutputStream = outputStream
        if (_connectionStatusFlow.value == ConnectionStatus.CONNECTED && currentOutputStream != null) {
            scope.launch {
                val sendTimestampNanos = System.nanoTime() // RTT 起始时间戳
                val payloadLength = payload.remaining()

                // 根据包类型确定最终包大小和结构
                val buffer: ByteBuffer
                if (packetType == Constants.PACKET_TYPE_UI_EVENT ||
                    packetType == Constants.PACKET_TYPE_UI_LONG_PRESS ||
                    packetType == Constants.PACKET_TYPE_UI_PRESS_DOWN
                ) {
                    // 新结构: 类型(1) + 时间戳(8) + Payload长度(2, LittleEndian) + Payload(N)
                    val packetSize = 1 + 8 + 2 + payloadLength
                    buffer = ByteBuffer.allocate(packetSize)
                    // 写入类型和时间戳 (使用默认的大端序)
                    buffer.order(Constants.BYTE_ORDER)
                    buffer.put(packetType)
                    buffer.putLong(sendTimestampNanos)
                    // 写入 Payload 长度 (使用小端序)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putShort(payloadLength.toShort())
                    // 写入 Payload
                    buffer.put(payload)
                } else {
                    // 其他类型保持旧结构: 类型(1) + 时间戳(8) + Payload(N)
                    val packetSize = 1 + 8 + payloadLength
                    buffer = ByteBuffer.allocate(packetSize).order(Constants.BYTE_ORDER)
                    buffer.put(packetType)
                    buffer.putLong(sendTimestampNanos)
                    buffer.put(payload)
                }

                try {
                    synchronized(outputStreamLock) {
                        currentOutputStream.write(buffer.array())
                        currentOutputStream.flush()
                    }
                } catch (e: SocketException) {
                    Log.w(TAG, "发送 $description 时出错 (SocketException): ${e.message}")
                    handleConnectionError("sendPacket")
                } catch (e: IOException) {
                    Log.w(TAG, "发送 $description 时出错 (IOException): ${e.message}")
                    handleConnectionError("sendPacket")
                } catch (e: Exception) {
                    Log.e(TAG, "发送 $description 时未知异常: ${e.message}", e)
                    handleConnectionError("sendPacket")
                }
            }
        }
        // 若当前未连接，则省略发送，不做额外处理
    }

    /**
     * 启动周期性的 PING 协程，定时向服务器发送 PING 包，服务器端可以 RTT。
     */
    private fun startPingJob() {
        pingJob?.cancel()
        pingJob = scope.launch {
            Log.i(TAG, "Ping 任务启动。")
            try {
                while (isActive && _connectionStatusFlow.value == ConnectionStatus.CONNECTED) {
                    sendPing()
                    delay(PING_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Ping 任务被取消。")
            } finally {
                Log.i(TAG, "Ping 任务停止 (isActive=$isActive, Status=${_connectionStatusFlow.value})。")
            }
        }
    }

    /**
     * 发送空负载的 PING 包，用于测试连通性和测量 RTT。
     */
    private fun sendPing() {
        val emptyPayload = ByteBuffer.allocate(0)
        sendPacket(PACKET_TYPE_PING, emptyPayload, "Ping")
    }

    /**
     * 启动监听协程，从输入流中读取数据包并进行处理（ACK 计算 RTT、或广播其他类型数据包）。
     */
    private fun startServerListener() {
        serverListenerJob?.cancel()

        serverListenerJob = scope.launch {
            Log.i(TAG, "服务器 ACK 监听器启动。")
            // 新协议头部: 类型(1) + 时间戳(8) + 长度(2) = 11 字节
            val headerBuffer = ByteArray(11)
            val headerByteBuffer = ByteBuffer.wrap(headerBuffer)

            try {
                val stream = inputStream ?: return@launch
                while (isActive && _connectionStatusFlow.value == ConnectionStatus.CONNECTED) {
                    val bytesRead = try {
                        withContext(Dispatchers.IO) { stream.read(headerBuffer) }
                    } catch (e: SocketTimeoutException) {
                        // 超时后继续下一轮，若仍连接则保持监听
                        if (isActive && _connectionStatusFlow.value == ConnectionStatus.CONNECTED) {
                            // do nothing, just loop again
                        } else {
                            break
                        }
                        continue
                    } catch (e: IOException) {
                        // 包括连接重置等异常
                        if (isActive && _connectionStatusFlow.value == ConnectionStatus.CONNECTED) {
                            Log.w(TAG, "读取服务器数据时 IO 错误: ${e.message}")
                            handleConnectionError("serverListener-read")
                        }
                        break
                    }

                    if (bytesRead == 11) {
                        headerByteBuffer.rewind()
                        // 读取类型和时间戳 (BigEndian)
                        headerByteBuffer.order(BYTE_ORDER)
                        val packetType = headerByteBuffer.get()
                        val receivedTimestampNanos = headerByteBuffer.long
                        // 读取 Payload 长度 (LittleEndian)
                        headerByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        val payloadLength = headerByteBuffer.short.toInt() and 0xFFFF

                        when (packetType) {
                            PACKET_TYPE_ACK -> {
                                if (payloadLength != 0) {
                                    Log.w(TAG, "收到 ACK 包但 Payload 长度不为 0 ($payloadLength)，可能存在协议错误。")
                                }
                                val currentTimeNanos = System.nanoTime()
                                val rttNanos = currentTimeNanos - receivedTimestampNanos
                                updateRttStats(rttNanos)
                            }
                            else -> {
                                Log.d(TAG, "收到非 ACK 类型数据包: $packetType, Payload 长度: $payloadLength")
                                var payloadBytes: ByteArray? = null
                                if (payloadLength > 0) {
                                    payloadBytes = ByteArray(payloadLength)
                                    try {
                                        withContext(Dispatchers.IO) {
                                            var bytesReadTotal = 0
                                            while (bytesReadTotal < payloadLength) {
                                                val bytesReadNow = stream.read(
                                                    payloadBytes,
                                                    bytesReadTotal,
                                                    payloadLength - bytesReadTotal
                                                )
                                                if (bytesReadNow == -1) {
                                                    // 在读取完整 Payload 前意外到达流末尾
                                                    throw IOException("Stream closed prematurely while reading payload (expected $payloadLength bytes, got $bytesReadTotal)")
                                                }
                                                bytesReadTotal += bytesReadNow
                                            }
                                        }
                                    } catch (e: IOException) {
                                        Log.e(TAG, "读取 Payload 时 IO 错误 (Type=$packetType, Length=$payloadLength): ${e.message}")
                                        handleConnectionError("serverListener-payloadRead")
                                        break
                                    }
                                }
                                // 发送到 SharedFlow
                                val emitResult = _serverPacketFlow.tryEmit(ServerPacket(packetType, payloadBytes))
                                if (!emitResult) {
                                    Log.w(TAG, "发送服务器数据包到 Flow 失败 (缓冲区可能已满): Type=$packetType")
                                }
                            }
                        }
                    } else if (bytesRead == -1) {
                        // 服务器主动关闭
                        Log.i(TAG, "服务器流已关闭 (read 返回 -1)。")
                        if (isActive && _connectionStatusFlow.value == ConnectionStatus.CONNECTED) {
                            handleConnectionError("serverListener-eof")
                        }
                        break
                    } else {
                        // 理论上应始终读到 11 字节，出现其他情况表明异常
                        Log.w(TAG, "警告: 读取服务器数据时收到意外字节数 ($bytesRead)。")
                        if (isActive && _connectionStatusFlow.value == ConnectionStatus.CONNECTED) {
                            handleConnectionError("serverListener-readLen")
                        }
                        break
                    }
                }
            } catch (e: SocketException) {
                if (isActive && _connectionStatusFlow.value == ConnectionStatus.CONNECTED) {
                    Log.w(TAG, "读取服务器数据时 Socket 错误: ${e.message}")
                    handleConnectionError("serverListener-socketEx")
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "服务器 ACK 监听器被取消。")
            } catch (e: Exception) {
                if (isActive && _connectionStatusFlow.value == ConnectionStatus.CONNECTED) {
                    Log.e(TAG, "读取服务器数据时意外错误: ${e.message}", e)
                    handleConnectionError("serverListener-genericEx")
                }
            } finally {
                Log.i(TAG, "服务器 ACK 监听器停止 (isActive=$isActive, Status=${_connectionStatusFlow.value})。")
            }
        }
    }

    /**
     * 更新 RTT 统计数据并向外部 Flow 发送最新结果。
     */
    private fun updateRttStats(rttNs: Long) {
        rttSum.addAndGet(rttNs)
        val count = rttCount.incrementAndGet()
        minRtt.updateAndGet { currentMin -> if (rttNs < currentMin) rttNs else currentMin }
        maxRtt.updateAndGet { currentMax -> if (rttNs > currentMax) rttNs else currentMax }

        // 计算平均值
        val avgRttNs = rttSum.get() / count
        val avgRttMs = TimeUnit.NANOSECONDS.toMillis(avgRttNs).toDouble()
        val currentMin = minRtt.get()
        val currentMax = maxRtt.get()
        val minMs = if (currentMin == Long.MAX_VALUE) -1L else TimeUnit.NANOSECONDS.toMillis(currentMin)
        val maxMs = if (currentMax == 0L) -1L else TimeUnit.NANOSECONDS.toMillis(currentMax)

        _rttStatsFlow.value = RttStats(avgRttMs, minMs, maxMs, count)

        // 每当计数达到某个间隔时，输出日志便于观察
        if (count % RTT_LOG_INTERVAL == 0) {
            logRttStats(_rttStatsFlow.value)
        }
    }

    /**
     * 打印当前的 RTT 统计日志。
     */
    private fun logRttStats(stats: RttStats?) {
        stats?.let {
            Log.i(TAG, "RTT: $it")
        }
    }

    /**
     * 重置 RTT 统计及其相关 Flow。
     */
    private fun resetRttStats() {
        rttSum.set(0)
        rttCount.set(0)
        minRtt.set(Long.MAX_VALUE)
        maxRtt.set(0)
        _rttStatsFlow.value = null
        Log.i(TAG, "RTT 统计已重置。")
    }

    /**
     * 取消所有内部协程，一般在外部不再需要此组件时调用。
     */
    fun cancelJobs() {
        scope.cancel()
        Log.i(TAG, "TcpCommunicator jobs cancelled.")
    }

    // 新增：辅助函数，将字节数组转为十六进制字符串（若有调试需求可使用）
    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
}
