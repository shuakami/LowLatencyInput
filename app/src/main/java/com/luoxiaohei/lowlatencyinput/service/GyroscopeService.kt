package com.luoxiaohei.lowlatencyinput.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.luoxiaohei.lowlatencyinput.Constants
import com.luoxiaohei.lowlatencyinput.R
import com.luoxiaohei.lowlatencyinput.model.ServiceStatus
import com.luoxiaohei.lowlatencyinput.network.ConnectionStatus
import com.luoxiaohei.lowlatencyinput.network.RttStats
import com.luoxiaohei.lowlatencyinput.network.TcpCommunicator
import com.luoxiaohei.lowlatencyinput.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class GyroscopeService : Service(), SensorEventListener {

    private val TAG = "GyroscopeService"

    // 使用 SupervisorJob 确保协程出错时不会轻易取消整个作用域
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Binder 用于与 Activity 等客户端通信
    private val binder = LocalBinder()

    // 标记当前 Service 是否有绑定
    private val _isBound = MutableStateFlow(false)
    val isBoundStateFlow: StateFlow<Boolean> = _isBound.asStateFlow()

    // Service 状态流（连接中、已连接、断开、错误等）
    private val _serviceStatusFlow = MutableStateFlow<ServiceStatus>(ServiceStatus.DISCONNECTED)
    val serviceStatusFlow: StateFlow<ServiceStatus> = _serviceStatusFlow.asStateFlow()

    // RTT 统计信息流，用于观察网络延迟
    private val _rttStatsFlow = MutableStateFlow<RttStats?>(null)
    val rttStatsFlow: StateFlow<RttStats?> = _rttStatsFlow.asStateFlow()

    // 传感器管理器与所需的传感器实例
    private lateinit var sensorManager: SensorManager
    private var gyroscopeSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    // TCP 通信器，用于与远程服务器进行数据传输
    private lateinit var tcpCommunicator: TcpCommunicator

    // 日志打印的时间戳，避免过于频繁地输出陀螺仪数据
    private var lastLogTimeMs: Long = 0L

    // 标记是否已经发送过设备信息包
    private var deviceInfoSent = false

    companion object {
        // 尝试加载 Native 库
        init {
            try {
                System.loadLibrary("lowlatencyinput")
                Log.i("GyroscopeService", "成功加载 Native 库 (lowlatencyinput)")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("GyroscopeService", "加载 Native 库失败: ${e.message}")
            }
        }

        // JNI 方法声明，供 Kotlin 与 C/C++ 交互
        @JvmStatic external fun nativeUpdateClickableRegions(jsonData: String)
        @JvmStatic external fun nativeSetScreenDimensions(width: Int, height: Int)
        @JvmStatic external fun nativeSetScreenOffsets(topOffset: Int, leftOffset: Int)
        @JvmStatic external fun nativeRequestSendUiEventPacket(identifier: String, x: Int, y: Int)
        @JvmStatic external fun nativeRequestSendUiLongPressPacket(identifier: String, x: Int, y: Int)
    }

    // 用于完整的 JNI 生命周期管理
    private external fun nativeInitJNIService()
    private external fun nativeStartInputReaderService()
    private external fun nativeStopInputReaderService()
    private external fun nativeReleaseJNIService()

    /**
     * 本地 Binder，用于客户端(如 Activity)获取 Service 实例。
     */
    inner class LocalBinder : Binder() {
        fun getService(): GyroscopeService = this@GyroscopeService

        /**
         * 检查 Service 侧是否与服务器保持连接。
         */
        fun isServiceConnected(): Boolean = (tcpCommunicator.connectionStatusFlow.value == ConnectionStatus.CONNECTED)
    }

    override fun onBind(intent: Intent?): IBinder {
        _isBound.value = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _isBound.value = false
        log("服务 onUnbind")
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        log("服务已创建")

        // 初始化传感器管理器及陀螺仪、加速度计传感器
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // 创建通知渠道（针对 Android 8.0+）
        createNotificationChannel()

        // 初始化 TCP 通信器，并观察其连接状态
        tcpCommunicator = TcpCommunicator(serviceScope)
        observeCommunicatorStatus()

        // 调用 JNI 初始化
        try {
            nativeInitJNIService()
            log("Native JNI 服务端已初始化。")
        } catch (e: UnsatisfiedLinkError) {
            log("nativeInitJNIService 错误: ${e.message}")
        }

        // 同步屏幕尺寸与偏移给 Native 层
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        nativeSetScreenDimensions(screenWidth, screenHeight)

        val topOffset = getStatusBarHeightPx(this)
        nativeSetScreenOffsets(topOffset, 0)
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("服务收到启动命令")

        when (intent?.action) {
            // 如果接收到停止捕获的指令，则停止服务
            Constants.ACTION_STOP_CAPTURE -> {
                log("停止捕获指令。")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 启动前台服务，创建并展示通知
                val notification = createNotification()
                startForeground(Constants.NOTIFICATION_ID, notification)

                // 与指定主机端口建立 TCP 连接
                tcpCommunicator.connect(Constants.TARGET_HOST, Constants.TARGET_PORT)

                // 注册陀螺仪与加速度计监听
                registerGyroListener()
                registerAccelListener()

                // 启动 Native 层输入读取线程
                try {
                    nativeStartInputReaderService()
                    log("已调用 nativeStartInputReaderService()，请求 Native 层启动输入读取器。")
                } catch (e: UnsatisfiedLinkError) {
                    log("nativeStartInputReaderService 错误: ${e.message}")
                }

                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log("服务正在销毁")

        // 停止 Native 层的输入读取服务
        try {
            nativeStopInputReaderService()
            log("已请求 Native 层停止输入读取器。")
        } catch (e: UnsatisfiedLinkError) {
            log("nativeStopInputReaderService 错误: ${e.message}")
        }

        // 注销传感器监听
        unregisterGyroListener()
        unregisterAccelListener()

        // 断开网络连接，取消协程
        tcpCommunicator.disconnect()
        tcpCommunicator.cancelJobs()
        serviceScope.cancel()
        log("GyroscopeService 清理完成 (网络, 协程)。")

        // 释放 Native 相关资源
        try {
            nativeReleaseJNIService()
            log("Native JNI 服务端资源已释放。")
        } catch (e: UnsatisfiedLinkError) {
            log("nativeReleaseJNIService 错误: ${e.message}")
        }

        // 停止前台通知
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    //region --------- 供 Native 层回调的函数 ---------
    /**
     * 必须为 public 实例方法并加 @Keep，防止被混淆或省略。
     * 当 Native 层检测到触摸数据时，会调用该方法，
     * 本地再将触摸信息组装为特定格式通过 TCP 发送到服务器。
     */
    @Keep
    fun onInputDataReceivedFromNative(data: String) {
        val parts = data.split(";", limit = 2)
        if (parts.size != 2) {
            log("Native 数据格式异常: $data")
            return
        }
        val coordsPart = parts[0] // T|id,x,y|id2,x2,y2...
        val tsStr = parts[1]

        try {
            val eventTimestamp = tsStr.toLong()
            val touches = coordsPart.substring(1).split('|').filter { it.isNotEmpty() }
            val count = touches.size.coerceAtMost(Constants.MAX_TOUCH_POINTS)

            // 计算所需字节大小：8字节时间戳 + 1字节触摸数量 + 每个触摸 (12字节)
            val payloadSize = 8 + 1 + (count * 12)
            val buffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)

            buffer.putLong(eventTimestamp)
            buffer.put(count.toByte())

            for (i in 0 until count) {
                val nums = touches[i].split(',')
                val id = nums.getOrNull(0)?.toIntOrNull() ?: -1
                val sx = nums.getOrNull(1)?.toIntOrNull() ?: 0
                val sy = nums.getOrNull(2)?.toIntOrNull() ?: 0
                buffer.putInt(id)
                buffer.putInt(sx)
                buffer.putInt(sy)
            }
            buffer.flip()

            tcpCommunicator.sendPacket(Constants.PACKET_TYPE_TOUCH, buffer, "触摸数据(来自Native)")
        } catch (e: Exception) {
            log("处理Native触摸数据时出错: ${e.message}")
        }
    }
    //endregion

    //region --------- 供 Native 层调用的 UI 交互相关函数 ---------
    /**
     * 发送 UI 点击事件包 (0x03)。
     */
    fun sendUiEventPacket(uiName: String, clickX: Int, clickY: Int) {
        val nameBytes = uiName.toByteArray(Charsets.UTF_8)
        val size = 4 + 4 + nameBytes.size

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(clickX)
            putInt(clickY)
            put(nameBytes)
            flip()
        }

        tcpCommunicator.sendPacket(Constants.PACKET_TYPE_UI_EVENT, buffer, "UI事件($uiName@$clickX,$clickY)")
    }

    /**
     * 发送 UI 长按事件包 (0x04)。
     */
    fun sendUiLongPressPacket(uiName: String, clickX: Int, clickY: Int) {
        val nameBytes = uiName.toByteArray(Charsets.UTF_8)
        val size = 4 + 4 + nameBytes.size

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(clickX)
            putInt(clickY)
            put(nameBytes)
            flip()
        }

        tcpCommunicator.sendPacket(Constants.PACKET_TYPE_UI_LONG_PRESS, buffer, "UI长按($uiName@$clickX,$clickY)")
    }

    /**
     * 发送 UI 按下事件包 (0x08)。由 Native 层调用。
     * @param uiName UI 元素名称
     * @param clickX 按下位置 X
     * @param clickY 按下位置 Y
     * @param downTimestampMs 按下动作发生的时间戳（毫秒）
     */
    @Keep
    fun sendUiPressDownPacket(uiName: String, clickX: Int, clickY: Int, downTimestampMs: Long) {
        val nameBytes = uiName.toByteArray(Charsets.UTF_8)
        // Payload: X(4) + Y(4) + DownTimestamp(8) + Identifier(N)
        val size = 4 + 4 + 8 + nameBytes.size

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(clickX)
            putInt(clickY)
            putLong(downTimestampMs)
            put(nameBytes)
            flip()
        }

        tcpCommunicator.sendPacket(
            Constants.PACKET_TYPE_UI_PRESS_DOWN,
            buffer,
            "UI按下($uiName@$clickX,$clickY,ts=$downTimestampMs)"
        )
        log("发送 UI 按下事件: $uiName, X=$clickX, Y=$clickY, DownTs=$downTimestampMs")
    }
    //endregion

    //region --------- 传感器回调函数 ---------
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val name = when (sensor?.type) {
            Sensor.TYPE_GYROSCOPE -> "陀螺仪"
            Sensor.TYPE_ACCELEROMETER -> "加速度计"
            else -> "未知传感器"
        }
        log("$name 精度变为: $accuracy")
    }

    /**
     * 当陀螺仪或加速度计数据变化时被调用。
     * 这里会将传感器数据打包并通过 TCP 发送给远程服务器。
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor?.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val eventTsMs = TimeUnit.NANOSECONDS.toMillis(event.timestamp)
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // 控制日志输出频率，避免过度刷屏
                val now = System.currentTimeMillis()
                if (now - lastLogTimeMs >= 1000) {
                    Log.d(TAG, "陀螺仪数据: x=$x, y=$y, z=$z (eventTs=$eventTsMs)")
                    lastLogTimeMs = now
                }

                try {
                    // 分配 28 字节缓冲: 8字节时间戳 + 8字节保留(目前未用) + 3个float
                    val payload = ByteBuffer.allocate(28).order(Constants.BYTE_ORDER).apply {
                        putLong(eventTsMs)
                        put(ByteArray(8)) // 预留的空字节区
                        putFloat(x)
                        putFloat(y)
                        putFloat(z)
                        flip()
                    }
                    tcpCommunicator.sendPacket(Constants.PACKET_TYPE_GYRO, payload, "陀螺仪数据")
                } catch (e: Exception) {
                    log("发送陀螺仪数据出错: ${e.message}")
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val eventTsMs = TimeUnit.NANOSECONDS.toMillis(event.timestamp)
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                try {
                    // 与陀螺仪格式保持一致
                    val payload = ByteBuffer.allocate(28).order(Constants.BYTE_ORDER).apply {
                        putLong(eventTsMs)
                        put(ByteArray(8))
                        putFloat(x)
                        putFloat(y)
                        putFloat(z)
                        flip()
                    }
                    tcpCommunicator.sendPacket(Constants.PACKET_TYPE_ACCEL, payload, "加速度计数据")
                } catch (e: Exception) {
                    log("发送加速度计数据出错: ${e.message}")
                }
            }
        }
    }
    //endregion

    /**
     * 观察 TCP 通信器的状态变化，并同步更新 Service 自身状态或 RTT 统计信息。
     */
    private fun observeCommunicatorStatus() {
        tcpCommunicator.connectionStatusFlow
            .onEach { status ->
                log("网络状态更新: $status")
                _serviceStatusFlow.value = when (status) {
                    ConnectionStatus.DISCONNECTED -> {
                        deviceInfoSent = false
                        ServiceStatus.DISCONNECTED
                    }
                    ConnectionStatus.CONNECTING -> ServiceStatus.CONNECTING
                    ConnectionStatus.CONNECTED -> {
                        // 初次连接成功后，自动发送一次设备信息
                        if (!deviceInfoSent) {
                            sendDeviceInfoPacket()
                            deviceInfoSent = true
                        }
                        ServiceStatus.CONNECTED
                    }
                    ConnectionStatus.ERROR -> {
                        deviceInfoSent = false
                        ServiceStatus.ERROR
                    }
                }
            }
            .launchIn(serviceScope)

        tcpCommunicator.rttStatsFlow
            .onEach { stats ->
                _rttStatsFlow.value = stats
            }
            .launchIn(serviceScope)

        tcpCommunicator.serverPacketFlow
            .onEach { serverPacket ->
                val type = serverPacket.packetType
                val payload = serverPacket.payload
                val payloadHex = payload?.joinToString(" ") { b -> "%02X".format(b) } ?: "null"
                log("收到服务器数据包: Type=${String.format("0x%02X", type)}, Payload=[$payloadHex]")
            }
            .launchIn(serviceScope)
    }

    /**
     * 注册陀螺仪监听器，若注册失败则停止自身服务。
     */
    private fun registerGyroListener() {
        gyroscopeSensor?.let {
            val samplingPeriodUs = 20_000 // 50Hz
            val ok = sensorManager.registerListener(this, it, samplingPeriodUs)
            if (ok) {
                log("陀螺仪监听器已注册 (50Hz，自定义)")
            } else {
                log("注册陀螺仪监听器失败。")
                stopSelf()
            }
        } ?: run {
            log("设备无陀螺仪传感器")
            stopSelf()
        }
    }

    /**
     * 注销陀螺仪监听器。
     */
    private fun unregisterGyroListener() {
        gyroscopeSensor?.let {
            sensorManager.unregisterListener(this, it)
            log("陀螺仪监听器已注销。")
        }
    }

    /**
     * 注册加速度计监听器，频率与陀螺仪一致(50Hz)。
     */
    private fun registerAccelListener() {
        accelerometerSensor?.let {
            val samplingPeriodUs = 20_000
            val ok = sensorManager.registerListener(this, it, samplingPeriodUs)
            if (ok) {
                log("加速度计监听器已注册 (50Hz，与陀螺仪一致)")
            } else {
                log("注册加速度计监听器失败。")
            }
        } ?: run {
            log("设备无加速度计传感器")
        }
    }

    /**
     * 注销加速度计监听器。
     */
    private fun unregisterAccelListener() {
        accelerometerSensor?.let {
            sensorManager.unregisterListener(this, it)
            log("加速度计监听器已注销。")
        }
    }

    /**
     * 创建通知渠道，仅在 Android 8.0+ 上执行。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "后台传感器捕获",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于前台服务通知"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            log("通知渠道已创建")
        }
    }

    /**
     * 构建前台服务通知，用于提醒用户当前正在后台采集数据。
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, flags)

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("低延迟输入服务")
            .setContentText("正在后台捕获传感器数据...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 简化打印日志方法。
     */
    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    /**
     * 向服务器发送一次设备信息(分辨率等)，仅在初次连接成功后发送。
     */
    private fun sendDeviceInfoPacket() {
        try {
            val dm = resources.displayMetrics
            val maxX = dm.widthPixels
            val maxY = dm.heightPixels
            log("发送设备信息: MaxX=$maxX, MaxY=$maxY")

            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(maxX)
                putInt(maxY)
                flip()
            }
            tcpCommunicator.sendPacket(Constants.PACKET_TYPE_DEVICE_INFO, buffer, "设备信息")
        } catch (e: Exception) {
            log("发送设备信息时出错: ${e.message}")
        }
    }

    /**
     * 获取状态栏高度(px)，若无法通过系统资源获取，则返回0。
     */
    private fun getStatusBarHeightPx(context: Context): Int {
        var res = 0
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) {
            res = context.resources.getDimensionPixelSize(resId)
        }
        if (res == 0) {
            log("未能通过资源ID获取状态栏高度，默认使用0")
        }
        return res
    }
}
