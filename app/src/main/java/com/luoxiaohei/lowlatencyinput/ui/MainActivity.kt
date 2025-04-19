package com.luoxiaohei.lowlatencyinput.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import com.luoxiaohei.lowlatencyinput.databinding.ActivityMainBinding
import com.luoxiaohei.lowlatencyinput.model.ServiceStatus
import com.luoxiaohei.lowlatencyinput.network.RttStats
import com.luoxiaohei.lowlatencyinput.service.ServiceManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 应用程序的主界面 Activity。
 * 负责与用户交互，控制后台服务（通过 ServiceManager）的启动与停止，
 * 并展示服务的连接状态、RTT 统计信息以及日志。
 */
class MainActivity : AppCompatActivity() {

    // 使用 ViewBinding 替代 findViewById，提高类型安全和空安全
    private lateinit var binding: ActivityMainBinding
    // --- UI 控件引用 (通过 binding 获取) ---
    private lateinit var connectionStatusTextView: TextView // 显示服务连接状态
    private lateinit var connectButton: Button // 连接按钮 (当前设置为自动连接，按钮禁用)
    private lateinit var logTextView: TextView // 显示运行日志
    private lateinit var startInputButton: Button // 启动/停止服务和输入捕获的按钮
    private lateinit var rttStatusTextView: TextView // 显示网络 RTT 信息
    private lateinit var toggleOverlayButton: Button // 悬浮窗控制按钮
    private lateinit var editOverlayButton: Button // 新增：编辑布局按钮

    // ServiceManager 负责管理后台服务的生命周期和通信
    private lateinit var serviceManager: ServiceManager

    // 创建一个与 Activity 生命周期无关，且运行在主线程的 CoroutineScope，用于更新 UI
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    // 新增：请求悬浮窗权限的启动器
    private lateinit var requestOverlayPermissionLauncher: ActivityResultLauncher<Intent>

    companion object {
        // 日志标签
        private const val TAG = "MainActivityUI"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化 ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        // 设置 Activity 的内容视图
        setContentView(binding.root)

        // 初始化 ServiceManager，传入 ApplicationContext
        serviceManager = ServiceManager(applicationContext)

        // 初始化权限请求启动器
        requestOverlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 检查权限是否已被授予
            if (Settings.canDrawOverlays(this)) {
                logMessage("悬浮窗权限已在设置后授予")
                // 权限已授予，现在可以尝试启动服务
                // 可以在这里再次触发按钮点击或直接调用启动逻辑
                // 为了简单起见，让用户再次点击按钮
                logMessage("请再次点击按钮以启动悬浮窗")
            } else {
                logMessage("用户未在设置中授予悬浮窗权限")
            }
        }

        // 从 binding 获取 UI 控件的引用
        connectionStatusTextView = binding.connectionStatus
        connectButton = binding.connectButton
        logTextView = binding.logTextView
        startInputButton = binding.startInputButton
        rttStatusTextView = binding.rttStatusTextView
        toggleOverlayButton = binding.toggleOverlayButton
        editOverlayButton = binding.editOverlayButton // 新增：获取编辑按钮引用

        // 初始化 UI 状态
        startInputButton.text = "启动输入捕获" // 初始按钮文本
        startInputButton.isEnabled = true // 初始可点击
        connectButton.isEnabled = false // 连接按钮禁用，因为服务是自动连接的
        connectButton.text = "自动连接（Auto Connect）" // 提示用户连接是自动的
        connectionStatusTextView.text = "状态: 初始化中..."
        rttStatusTextView.text = "RTT: N/A" // 初始无 RTT 数据
        toggleOverlayButton.text = "显示悬浮窗"
        editOverlayButton.isEnabled = true // 编辑按钮初始可用

        // 设置启动/停止按钮的点击事件监听器
        startInputButton.setOnClickListener {
            // 使用 lifecycleScope 启动协程，确保协程与 Activity 生命周期关联
            lifecycleScope.launch {
                // 根据当前服务状态决定是启动还是停止服务
                when (serviceManager.serviceStatusFlow.value) {
                    // 如果服务未连接或出错，则启动服务
                    ServiceStatus.DISCONNECTED, ServiceStatus.ERROR -> {
                        logMessage("按钮点击: 启动服务...")
                        serviceManager.startAndBindService()
                    }
                    // 如果服务正在连接或已连接，则停止服务
                    ServiceStatus.CONNECTING, ServiceStatus.CONNECTED -> {
                        logMessage("按钮点击: 停止服务...")
                        serviceManager.stopAndUnbindService()
                    }
                }
            }
        }

        // 修改：设置悬浮窗按钮点击事件，优先尝试 Root 授权
        toggleOverlayButton.setOnClickListener {
            // 检查权限
            if (!Settings.canDrawOverlays(this)) {
                handleOverlayPermissionRequest()
                return@setOnClickListener // 结束本次点击，等待权限结果
            }

            // --- 权限已存在 --- 
            logMessage("悬浮窗权限已具备")
            toggleOverlayService()
        }

        // 新增：设置编辑布局按钮点击事件
        editOverlayButton.setOnClickListener {
            logMessage("启动布局编辑器...")
            val intent = Intent(this, OverlayEditorActivity::class.java)
            startActivity(intent)
            // TODO: Uncomment the above lines once OverlayEditorActivity is created. - Done
            // logMessage("[占位符] 布局编辑器 Activity 尚未创建")
        }

        // 开始观察 ServiceManager 提供的状态流
        observeServiceState()
        logMessage("MainActivity onCreate 完成")
    }

    override fun onStart() {
        super.onStart()
        logMessage("MainActivity onStart")
    }

    override fun onStop() {
        super.onStop()
        logMessage("MainActivity onStop")
    }

    /**
     * 观察 ServiceManager 中的状态流 (Flow)，并在 UI 上反映变化。
     * 使用 repeatOnLifecycle(Lifecycle.State.STARTED) 确保仅在 Activity 至少处于 STARTED 状态时
     * 才收集 Flow 数据，并在 STOPPED 时自动取消收集，防止资源泄漏和后台 UI 更新。
     */
    private fun observeServiceState() {
        lifecycleScope.launch {
            // repeatOnLifecycle 会在生命周期进入 STARTED 时执行 block，并在进入 STOPPED 时取消
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 启动一个子协程来观察服务状态变化
                launch {
                    serviceManager.serviceStatusFlow.collect { status ->
                        updateUiBasedOnStatus(status) // 根据状态更新 UI
                        logMessage("观察到主服务状态: $status") // 修改日志信息以区分
                    }
                }

                // 启动另一个子协程来观察 RTT 统计数据变化
                launch {
                    serviceManager.rttStatsFlow.collect { stats ->
                        updateRttUi(stats) // 使用辅助方法更新 RTT UI
                    }
                }

                // 新增：观察悬浮窗服务运行状态
                launch {
                    serviceManager.isOverlayRunningFlow.collect { isRunning ->
                        logMessage("观察到悬浮窗服务状态: ${if (isRunning) "运行中" else "已停止"}")
                        toggleOverlayButton.text = if (isRunning) "隐藏悬浮窗" else "显示悬浮窗"
                        // 可以在这里根据需要启用/禁用按钮，例如，权限不足时禁用
                        // toggleOverlayButton.isEnabled = Settings.canDrawOverlays(this@MainActivity)
                    }
                }
            }
        }
    }

    /**
     * 根据 RTT 统计数据更新 UI。
     */
    private fun updateRttUi(stats: RttStats?) {
        if (stats != null) {
            // 直接访问 RttStats 对象的属性
            val avgRttMs = stats.averageMs
            val count = stats.count
            val minMs = stats.minMs
            val maxMs = stats.maxMs
            // 可以显示更详细的信息
            val minStr = if (minMs == -1L) "N/A" else "${minMs}ms"
            val maxStr = if (maxMs == -1L) "N/A" else "${maxMs}ms"
            rttStatusTextView.text = String.format(Locale.US,
                "RTT Avg: %.2fms (Min: %s, Max: %s, n=%d)",
                avgRttMs, minStr, maxStr, count)
        } else {
            rttStatusTextView.text = "RTT: N/A"
        }
    }

    /**
     * 根据服务的当前状态更新 UI 元素（按钮文本、状态文本等）。
     * @param status 当前的服务状态。
     */
    private fun updateUiBasedOnStatus(status: ServiceStatus) {
        when (status) {
            ServiceStatus.DISCONNECTED -> {
                connectionStatusTextView.text = "状态: 服务已停止"
                startInputButton.text = "启动输入捕获"
                startInputButton.isEnabled = true
                rttStatusTextView.text = "RTT: N/A"
            }
            ServiceStatus.CONNECTING -> {
                connectionStatusTextView.text = "状态: 连接中..."
                startInputButton.text = "停止输入捕获" // 连接过程中也允许停止
                startInputButton.isEnabled = true
            }
            ServiceStatus.CONNECTED -> {
                connectionStatusTextView.text = "状态: 已连接"
                startInputButton.text = "停止输入捕获"
                startInputButton.isEnabled = true
            }
            ServiceStatus.ERROR -> {
                connectionStatusTextView.text = "状态: 错误"
                startInputButton.text = "启动输入捕获" // 出错后允许重新启动
                startInputButton.isEnabled = true
                rttStatusTextView.text = "RTT: 错误" // RTT 也显示错误状态
            }
        }
    }

    /**
     * 向 UI 上的 TextView 追加日志消息，并自动滚动到底部。
     * 同时也在 Logcat 中打印日志。
     * 包含简单的日志条数限制，防止 TextView 无限增长。
     * @param message 要记录的日志消息。
     */
    private fun logMessage(message: String) {
        // 确保 UI 更新在主线程执行
        uiScope.launch {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            // 限制日志行数，防止内存占用过高
            if (logTextView.lineCount > 200) {
                try {
                    val text = logTextView.text.toString()
                    val lines = text.split("\n")
                    // 保留最后 100 行
                    val linesToKeep = lines.takeLast(100)
                    logTextView.text = linesToKeep.joinToString("\n") + "\n"
                } catch (e: Exception) {
                    // 防止日志处理本身出错导致崩溃
                    logTextView.text = "" // 极端情况清空日志
                    android.util.Log.e(TAG, "Error trimming log text view", e)
                }
            }
            // 追加带时间戳的新日志
            logTextView.append("[$timestamp] $message\n")

            // 尝试自动滚动到底部，以便查看最新日志
            // 需要在 TextView 布局完成后才能获取正确的高度和行数
            logTextView.post { // post 到消息队列，确保布局计算完成
                try {
                    val scrollAmount = logTextView.layout?.let { it.getLineTop(logTextView.lineCount) - logTextView.height } ?: 0
                    if (scrollAmount > 0) {
                        logTextView.scrollTo(0, scrollAmount)
        } else {
                        logTextView.scrollTo(0, 0) // 如果内容不足一屏，滚动到顶部
                    }
                } catch (e: Exception) {
                    // 捕获可能的异常，例如布局尚未完成
                     android.util.Log.e(TAG, "Error scrolling log text view", e)
                }
            }
        }
        // 同时在 Logcat 输出日志，方便调试
        android.util.Log.i(TAG, message)
    }

    /**
     * 处理悬浮窗权限请求。
     * 优先尝试 Root 授权，失败则跳转系统设置。
     */
    private fun handleOverlayPermissionRequest() {
        logMessage("悬浮窗权限不足，尝试 Root 授权...")
        // 使用协程在后台线程执行 Root 命令
        lifecycleScope.launch(Dispatchers.IO) {
            val command = "appops set --user 0 $packageName SYSTEM_ALERT_WINDOW allow"
            logMessage("执行 Root 命令: $command")
            val result = Shell.cmd(command).exec()
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    logMessage("Root 授权成功: $command")
                    // 权限已授予，提示用户重试
                    logMessage("请再次点击按钮以启动悬浮窗")
                    // 或者直接调用 toggleOverlayService() 如果希望授权后立即启动
                    // toggleOverlayService()
                } else {
                    logMessage("Root 授权失败: ${result.err.joinToString("\n")}")
                    // Root 失败，跳转到系统设置页面
                    launchSystemOverlaySettings()
                }
            }
        }
    }

    /**
     * 启动系统悬浮窗权限设置页面。
     */
    private fun launchSystemOverlaySettings() {
        logMessage("尝试跳转到系统设置页面授权...")
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            requestOverlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            logMessage("无法跳转到悬浮窗权限设置: ${e.message}")
        }
    }

    /**
     * 切换 RuntimeOverlayService 的状态 (启动/停止)。
     */
    private fun toggleOverlayService() {
        lifecycleScope.launch {
            if (serviceManager.isOverlayRunningFlow.value) {
                logMessage("请求停止悬浮窗服务")
                serviceManager.stopOverlayService()
            } else {
                logMessage("请求启动悬浮窗服务")
                serviceManager.startOverlayService()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logMessage("MainActivity onDestroy - 清理资源")
        serviceManager.destroy()
        // 取消与此 Activity 关联的 uiScope 中的所有协程
        uiScope.cancel()
    }
}