package com.luoxiaohei.lowlatencyinput.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.luoxiaohei.lowlatencyinput.network.RttStats
import com.luoxiaohei.lowlatencyinput.model.ServiceStatus
import com.luoxiaohei.lowlatencyinput.service.GyroscopeService

/**
 * 负责启动并绑定 [GyroscopeService]，并通过 StateFlow 暴露服务的连接状态与 RTT 统计信息。
 * 可在 UI 层监听对应的 Flow 以做出相应更新。
 */
class ServiceManager(context: Context) {

    private val appContext = context.applicationContext

    // 绑定到 GyroscopeService 的 Binder 引用
    private var binder: GyroscopeService.LocalBinder? = null
    
    // 是否已与服务完成绑定
    private var isBound = false

    // 使用在主线程的协程作用域收集 StateFlow（适合 UI 相关更新）
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // StateFlow：当前是否绑定成功
    private val _isBoundStateFlow = MutableStateFlow(false)
    val isBoundStateFlow: StateFlow<Boolean> = _isBoundStateFlow.asStateFlow()

    // StateFlow：服务状态（DISCONNECTED, CONNECTING, CONNECTED, ERROR）
    private val _serviceStatusFlow = MutableStateFlow<ServiceStatus>(ServiceStatus.DISCONNECTED)
    val serviceStatusFlow: StateFlow<ServiceStatus> = _serviceStatusFlow.asStateFlow()

    // StateFlow：RTT 统计信息 (可为 null)
    private val _rttStatsFlow = MutableStateFlow<RttStats?>(null)
    val rttStatsFlow: StateFlow<RttStats?> = _rttStatsFlow.asStateFlow()

    // StateFlow: RuntimeOverlayService 是否正在运行
    private val _isOverlayRunningFlow = MutableStateFlow(false)
    val isOverlayRunningFlow: StateFlow<Boolean> = _isOverlayRunningFlow.asStateFlow()

    // 管理收集服务状态 Flow 的协程任务，用于在解绑或销毁时取消
    private var serviceObserverJob: Job? = null

    /**
     * ServiceConnection 用于监听服务绑定、断连等事件。
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? GyroscopeService.LocalBinder
            val gyroService = binder?.getService() ?: run {
                log("获取 GyroscopeService 实例失败")
                cleanupConnection(updateStatus = ServiceStatus.ERROR)
                return
            }
            
            isBound = true
            _isBoundStateFlow.value = true
            log("服务已连接，开始观察服务状态")

            // 取消旧的观察任务（如果有）
            serviceObserverJob?.cancel()
            
            // 启动新的观察任务来收集服务状态和 RTT 信息
            serviceObserverJob = managerScope.launch {
                // 收集服务状态
                launch {
                    gyroService.serviceStatusFlow.collect { status ->
                        log("收到服务状态更新: $status")
                        _serviceStatusFlow.value = status
                    }
                }
                // 收集 RTT 统计
                launch {
                    gyroService.rttStatsFlow.collect { stats ->
                        // 若 RTT 更新较为频繁，可选择只在需要时打印日志
                        // log("收到 RTT 更新: $stats")
                        _rttStatsFlow.value = stats
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log("服务意外断开")
            cleanupConnection()
        }

        override fun onBindingDied(name: ComponentName?) {
            log("服务绑定死亡 (onBindingDied)")
            cleanupConnection()
        }

        override fun onNullBinding(name: ComponentName?) {
            log("服务返回 Null Binding (onNullBinding)")
            cleanupConnection(updateStatus = ServiceStatus.ERROR)
        }
    }

    /**
     * 启动目标服务并尝试绑定。
     */
    fun startAndBindService() {
        val currentStatus = _serviceStatusFlow.value
        // 若已处于连接或连接中，不重复操作
        if (!isBound && currentStatus != ServiceStatus.CONNECTING && currentStatus != ServiceStatus.CONNECTED) {
            log("Requesting service start and bind (Current Status: $currentStatus).")
            val serviceIntent = Intent(appContext, GyroscopeService::class.java)
            try {
                // 兼容不同 Android 版本，启动服务
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(serviceIntent)
                } else {
                    appContext.startService(serviceIntent)
                }
                // 尝试绑定服务
                val bound = appContext.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                if (bound) {
                    log("Bind command issued successfully.")
                    _serviceStatusFlow.value = ServiceStatus.CONNECTING
                } else {
                    log("Bind command failed.")
                    cleanupConnection(updateStatus = ServiceStatus.ERROR)
                }
            } catch (e: Exception) {
                log("Error starting or binding service: ${e.message}")
                cleanupConnection(updateStatus = ServiceStatus.ERROR)
            }
        } else {
            // 若已在连接中或已连接，则忽略重复请求
            log("Service start request ignored (isBound=$isBound, currentStatus=$currentStatus).")
        }
    }

    /**
     * 解除绑定并停止服务。
     */
    fun stopAndUnbindService() {
        log("Requesting service stop and unbind.")
        if (isBound) {
            try {
                appContext.unbindService(serviceConnection)
                log("Unbind command issued.")
            } catch (e: IllegalArgumentException) {
                log("Error unbinding service (already unbound?): ${e.message}")
            }
            // 立即清理绑定状态
            cleanupConnection()
        } else {
            log("Service was not bound, attempting to stop anyway.")
        }

        val serviceIntent = Intent(appContext, GyroscopeService::class.java)
        try {
            val stopped = appContext.stopService(serviceIntent)
            if (stopped) {
                log("Stop command issued successfully.")
            } else {
                log("Stop command failed (service not running?).")
                _serviceStatusFlow.value = ServiceStatus.DISCONNECTED
            }
        } catch (e: Exception) {
            log("Error stopping service: ${e.message}")
            _serviceStatusFlow.value = ServiceStatus.ERROR
        }
        // 无论如何，都进行一次最终的状态清理
        cleanupConnection()
    }

    /**
     * 清理当前所有绑定与状态，并更新为指定的最终状态。
     * @param updateStatus 服务状态 (默认为 DISCONNECTED)
     */
    private fun cleanupConnection(updateStatus: ServiceStatus = ServiceStatus.DISCONNECTED) {
        // 取消观察协程
        serviceObserverJob?.cancel()
        serviceObserverJob = null

        binder = null
        isBound = false
        _isBoundStateFlow.value = false
        _serviceStatusFlow.value = updateStatus
        _rttStatsFlow.value = null // 重置 RTT 统计
        log("连接已清理。状态设为: $updateStatus")
    }

    /**
     * 销毁时应主动调用，确保关闭 Scope 并清理连接。
     */
    fun destroy() {
        log("销毁 ServiceManager.")
        if (isBound) {
            try {
                appContext.unbindService(serviceConnection)
                log("销毁时解绑服务.")
            } catch (e: IllegalArgumentException) {
                log("销毁时解绑服务出错 (已解绑?): ${e.message}")
            }
        }
        cleanupConnection()
        // 停止悬浮窗服务（如果正在运行）
        stopOverlayService()
        managerScope.cancel()
        log("ServiceManager 已销毁.")
    }

    /**
     * 获取当前最新的 RTT 统计快照（非异步方法）。
     * @return 最新的 RTT 统计，或 null (若还未收集到).
     */
    fun getRttStatsSnapshot(): RttStats? {
        return _rttStatsFlow.value
    }

    /**
     * 查询服务是否已建立连接（非异步方法）。
     */
    fun isServiceConnectedSnapshot(): Boolean {
        return _serviceStatusFlow.value == ServiceStatus.CONNECTED
    }

    /**
     * 启动 RuntimeOverlayService。
     */
    fun startOverlayService() {
        if (!_isOverlayRunningFlow.value) {
            log("请求启动 RuntimeOverlayService.")
            val serviceIntent = Intent(appContext, RuntimeOverlayService::class.java)
            try {
                appContext.startService(serviceIntent)
                _isOverlayRunningFlow.value = true
                log("RuntimeOverlayService 启动命令已发出.")
            } catch (e: Exception) {
                log("启动 RuntimeOverlayService 时出错: ${e.message}")
                // 可以在这里添加错误处理逻辑，例如更新一个错误状态流
            }
        } else {
            log("RuntimeOverlayService 启动请求被忽略 (已在运行).")
        }
    }

    /**
     * 停止 RuntimeOverlayService。
     */
    fun stopOverlayService() {
        if (_isOverlayRunningFlow.value) {
            log("请求停止 RuntimeOverlayService.")
            val serviceIntent = Intent(appContext, RuntimeOverlayService::class.java)
            try {
                val stopped = appContext.stopService(serviceIntent)
                if (stopped) {
                    log("RuntimeOverlayService 停止命令已发出.")
                } else {
                    log("RuntimeOverlayService 停止命令失败 (可能未在运行?).")
                }
            } catch (e: Exception) {
                log("停止 RuntimeOverlayService 时出错: ${e.message}")
                // 可以在这里添加错误处理逻辑
            }
            // 无论停止是否成功，都更新状态为 false
            _isOverlayRunningFlow.value = false
        } else {
             log("RuntimeOverlayService 停止请求被忽略 (未在运行).")
        }
    }

    /**
     * 简单的日志函数，可根据需要替换为其他日志系统。
     */
    private fun log(message: String) {
        Log.i("ServiceManager", message)
    }
}
