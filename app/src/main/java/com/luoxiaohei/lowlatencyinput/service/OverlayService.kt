package com.luoxiaohei.lowlatencyinput.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.luoxiaohei.lowlatencyinput.R
import com.luoxiaohei.lowlatencyinput.Constants

/**
 * 后台 Service，用于在全局范围显示/隐藏自定义悬浮窗（Overlay）。
 * 使用 StateFlow 来追踪悬浮窗的显示状态，方便外部监听。
 */
class OverlayService : Service() {

    private val TAG = "OverlayService"

    // WindowManager 用于在系统层面添加或移除悬浮窗
    private var windowManager: WindowManager? = null

    // overlayView 即真正的悬浮窗视图
    private var overlayView: View? = null

    // 使用可变的 StateFlow 来管理当前悬浮窗是否正在显示
    private val _isShowingFlow = MutableStateFlow(false)
    val isShowingFlow: StateFlow<Boolean> = _isShowingFlow.asStateFlow()

    /**
     * 提供给绑定组件（Activity 等）的 Binder，可获取本 Service 实例。
     */
    inner class LocalBinder : Binder() {
        fun getService(): OverlayService = this@OverlayService
    }

    override fun onBind(intent: Intent): IBinder {
        return LocalBinder()
    }

    override fun onCreate() {
        super.onCreate()
        // 获取系统级 WindowManager 服务，方便后续添加/移除悬浮窗
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    /**
     * 显示全屏覆盖的悬浮窗。若已经显示，则直接返回不重复添加。
     */
    fun showOverlay() {
        // 如果悬浮窗已在显示，直接返回
        if (_isShowingFlow.value) return

        try {
            // 通过 ContextThemeWrapper 来正确加载特定主题下的布局
            val themeResId = R.style.Theme_LowLatencyInput
            val themedContext = ContextThemeWrapper(this, themeResId)
            val inflater = LayoutInflater.from(themedContext)

            // 加载自定义布局作为悬浮窗内容
            val view = inflater.inflate(R.layout.overlay_layout, null)

            // 获取自定义布局的容器，并设置长按事件用来关闭悬浮窗
            val overlayWidgetContainer =
                view.findViewById<MaterialCardView>(R.id.overlay_widget_container)
            overlayWidgetContainer?.setOnLongClickListener {
                Log.i(TAG, "检测到长按，隐藏悬浮窗")
                hideOverlay()
                true
            }

            // 取得 RTT 测试按钮，为其设置点击监听
            val rttTestButton = view.findViewById<Button>(R.id.rtt_test_button)
            rttTestButton?.setOnClickListener { buttonView ->
                // 此按钮对应的名称，可用于广播传递
                val uiElementName = "rtt_test_button"

                // 计算按钮在屏幕上的中心坐标，便于调试或后续使用
                val location = IntArray(2)
                buttonView.getLocationOnScreen(location)
                val clickX = location[0] + buttonView.width / 2
                val clickY = location[1] + buttonView.height / 2

                Log.i(TAG, "UI 元素 '$uiElementName' 被点击 ($clickX, $clickY)，发送 UI 事件广播...")

                // 发送广播携带 UI 元素名称以及点击位置
                val intent = Intent(Constants.ACTION_UI_EVENT_TRIGGERED).apply {
                    putExtra(Constants.EXTRA_UI_ELEMENT_NAME, uiElementName)
                    putExtra(Constants.EXTRA_CLICK_X, clickX)
                    putExtra(Constants.EXTRA_CLICK_Y, clickY)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }

            // 配置悬浮窗需要的 LayoutParams，设置为全屏覆盖
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            // 真正把视图添加到系统窗口中
            overlayView = view
            windowManager?.addView(view, params)

            // 更新 Flow 状态，表示悬浮窗已显示
            _isShowingFlow.value = true
            Log.i(TAG, "悬浮窗已显示")

        } catch (e: Exception) {
            // 如果添加过程出现异常，记录日志并重置显示状态
            Log.e(TAG, "显示悬浮窗时出错: ${e.message}", e)
            _isShowingFlow.value = false
        }
    }

    /**
     * 隐藏并移除悬浮窗。若当前未显示，则直接返回不处理。
     */
    fun hideOverlay() {
        // 如果当前没有显示，则不执行任何操作
        if (!_isShowingFlow.value) return

        try {
            overlayView?.let { view ->
                // 从系统窗口中移除悬浮视图
                windowManager?.removeView(view)
                overlayView = null
                _isShowingFlow.value = false
                Log.i(TAG, "悬浮窗已隐藏")
            }
        } catch (e: Exception) {
            Log.e(TAG, "隐藏悬浮窗时出错: ${e.message}", e)
            // 遇到异常也要保证 Flow 状态正确
            _isShowingFlow.value = false
        }
    }

    /**
     * Service 被销毁时，确保悬浮窗视图一并移除。
     */
    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
    }
}
