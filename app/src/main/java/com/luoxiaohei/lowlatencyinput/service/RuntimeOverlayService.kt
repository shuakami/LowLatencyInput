package com.luoxiaohei.lowlatencyinput.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.luoxiaohei.lowlatencyinput.R
import com.luoxiaohei.lowlatencyinput.model.AvailableElements
import com.luoxiaohei.lowlatencyinput.model.OverlayElement
import com.luoxiaohei.lowlatencyinput.model.OverlayLayout
import com.luoxiaohei.lowlatencyinput.utils.LayoutManager
import com.luoxiaohei.lowlatencyinput.Constants
import android.content.res.Resources
import com.google.gson.Gson

/**
 * 该服务用于在系统层面呈现悬浮窗 (Overlay)，
 * 通过加载存储的 OverlayLayout，动态创建并显示各种可交互元素。
 */
class RuntimeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var layoutInflater: LayoutInflater
    private var overlayLayout: OverlayLayout? = null
    private var statusBarHeightPx: Int = 0 // 新增：存储状态栏高度

    /**
     * 记录已添加到屏幕上的视图，以便在停止服务时移除
     * key: element ID, value: 实际对应的 View
     */
    private val activeViews = mutableMapOf<String, View>()

    // 新增：用于存储可点击区域信息的数据类
    data class ClickableRegionInfo(val identifier: String, val leftPx: Int, val topPx: Int, val widthPx: Int, val heightPx: Int)
    // 新增：用于收集所有区域信息的列表
    private val clickableRegions = mutableListOf<ClickableRegionInfo>()

    private var overlayContainerView: FrameLayout? = null // 全屏悬浮容器

    companion object {
        private const val TAG = "RuntimeOverlayService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建 onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        layoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        // --- 新增：获取状态栏高度 ---
        statusBarHeightPx = getStatusBarHeight()
        Log.i(TAG, "状态栏高度: $statusBarHeightPx px")
        // --- 新增结束 ---

        // 从本地加载已保存的布局
        overlayLayout = LayoutManager.loadLayout(this)

        // 创建并添加悬浮窗视图
        createOverlayViews()
    }

    /**
     * 服务启动时的回调，若因异常被系统杀死后重启，也需要重建视图
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动 onStartCommand")
        // 如果 service 重启，且当前没有 activeViews，则重新创建
        if (activeViews.isEmpty() && overlayLayout != null) {
            Log.d(TAG, "服务重启，重新创建视图")
            createOverlayViews()
        }
        // START_STICKY 表示被系统杀死后会自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁 onDestroy")
        removeOverlayViews()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 该服务不支持绑定，不返回 IBinder
        return null
    }

    /**
     * 创建并添加全屏容器及具体元素视图
     */
    private fun createOverlayViews() {
        val layout = overlayLayout ?: return
        Log.d(TAG, "开始创建悬浮窗视图，共 ${layout.elements.size} 个元素")

        // 确保重复调用时先移除旧视图
        removeOverlayViews()
        // 清空上次收集的区域信息
        clickableRegions.clear()

        // 创建一个全屏覆盖的 FrameLayout，负责承载具体元素
        overlayContainerView = FrameLayout(this)

        // 配置全屏容器的 LayoutParams
        val windowFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val containerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(overlayContainerView, containerParams)
            Log.d(TAG, "全屏透明容器已添加到 WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "添加全屏容器到 WindowManager 时出错", e)
            overlayContainerView = null
            return // 没有容器就无法继续添加元素
        }

        // 逐个创建并添加布局中的元素
        for (element in layout.elements) {
            try {
                // 如果已存在相同 ID 的视图，跳过（正常不会出现）
                if (activeViews.containsKey(element.id)) {
                    Log.w(TAG, "视图已存在于 activeViews 中: ${element.id}, 跳过创建")
                    continue
                }

                // 计算元素的最终屏幕像素坐标和尺寸
                val elementParams = createFrameLayoutLayoutParams(element)
                val leftPx = elementParams.leftMargin
                val topPx = elementParams.topMargin
                val widthPx = elementParams.width
                val heightPx = elementParams.height
                val elementType = element.type

                // 根据元素类型生成对应的 View
                val view: View? = when {
                    elementType == AvailableElements.TYPE_CLOSE_BUTTON -> {
                        // 对于可点击类型（包括关闭按钮），记录其区域信息
                        clickableRegions.add(ClickableRegionInfo(elementType, leftPx, topPx, widthPx, heightPx))
                        val linearLayout = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(
                                (4 * resources.displayMetrics.density).toInt(),
                                (2 * resources.displayMetrics.density).toInt(),
                                (6 * resources.displayMetrics.density).toInt(),
                                (2 * resources.displayMetrics.density).toInt()
                            )
                        }
                        val imageView = ImageView(this).apply {
                            setImageResource(R.drawable.ic_close_small)
                            setColorFilter(
                                ContextCompat.getColor(
                                    this@RuntimeOverlayService,
                                    android.R.color.white
                                )
                            )
                            layoutParams = LinearLayout.LayoutParams(
                                (16 * resources.displayMetrics.density).toInt(),
                                (16 * resources.displayMetrics.density).toInt()
                            )
                        }
                        val textView = TextView(this).apply {
                            text = element.label ?: "关闭"
                            setTextColor(ContextCompat.getColor(this@RuntimeOverlayService, android.R.color.white))
                            textSize = 10f
                            setShadowLayer(1f, 1f, 1f, Color.parseColor("#80000000"))
                            val textParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            textParams.marginStart = (2 * resources.displayMetrics.density).toInt()
                            layoutParams = textParams
                        }
                        linearLayout.addView(imageView)
                        linearLayout.addView(textView)
                        linearLayout.setOnLongClickListener {
                            Log.i(TAG, "长按关闭按钮触发，停止服务...")
                            stopSelf()
                            true
                        }
                        linearLayout.layoutParams = elementParams
                        linearLayout.alpha = element.alpha
                        linearLayout
                    }
                    elementType == "button" -> {
                        clickableRegions.add(ClickableRegionInfo(elementType, leftPx, topPx, widthPx, heightPx))
                        val button = Button(this).apply {
                            text = element.label ?: element.id
                            layoutParams = createFrameLayoutLayoutParams(element)
                            alpha = element.alpha
                            setOnClickListener { buttonView ->
                                Log.d(TAG, "悬浮按钮点击: ${element.id}")
                            }
                        }
                        button
                    }
                    // 只要iconResId不为null就走图标分支
                    AvailableElements.findByType(elementType)?.iconResId != null -> {
                        clickableRegions.add(ClickableRegionInfo(elementType, leftPx, topPx, widthPx, heightPx))
                        val elementInfo = AvailableElements.findByType(element.type)
                        val imageView = ImageView(this).apply {
                            setImageResource(elementInfo!!.iconResId!!)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            layoutParams = createFrameLayoutLayoutParams(element)
                            alpha = element.alpha
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { imageView ->
                                Log.d(TAG, "悬浮图标点击: ${element.id}")
                            }
                        }
                        imageView
                    }
                    else -> {
                        Log.w(TAG, "不支持的元素类型: ${element.type}")
                        null
                    }
                }

                // 将生成的视图添加到容器，并保存引用
                if (view != null) {
                    overlayContainerView?.addView(view)
                    activeViews[element.id] = view
                    Log.d(TAG, "添加元素视图到容器: ${element.id}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "创建或添加元素视图时出错: ${element.id}", e)
            }
        }
        Log.d(TAG, "悬浮窗元素添加完成，共 ${activeViews.size} 个活动视图")

        // --- 新增：打印收集到的所有可点击区域信息 --- 
        if (clickableRegions.isNotEmpty()) {
            val gson = Gson()
            val regionsJson = gson.toJson(clickableRegions)
            Log.i(TAG, "收集到的可点击区域信息 (JSON): $regionsJson")
            // --- 新增：调用 JNI 方法传递数据 ---
            try {
                GyroscopeService.nativeUpdateClickableRegions(regionsJson)
                Log.i(TAG, "成功调用 JNI 传递区域信息给 Native 层。")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "调用 nativeUpdateClickableRegions 失败: ${e.message}", e)
                // 这里可以考虑添加错误处理，例如通知用户或停止服务
            }
            // --- 新增结束 ---
        } else {
            Log.i(TAG, "未找到可点击的悬浮窗元素区域。")
        }
        // --- 新增结束 ---
    }

    /**
     * 为给定的 OverlayElement 生成在 FrameLayout 中使用的布局参数，
     * 包括宽、高、左边距和上边距
     */
    private fun createFrameLayoutLayoutParams(element: OverlayElement): FrameLayout.LayoutParams {
        val density = resources.displayMetrics.density
        val elementWidthPx = (element.width * density).toInt()
        val elementHeightPx = (element.height * density).toInt()
        val elementXPx = (element.x * density).toInt()
        val elementYPx = (element.y * density).toInt()
        val adjustedYPx = elementYPx + statusBarHeightPx

        return FrameLayout.LayoutParams(elementWidthPx, elementHeightPx).apply {
            leftMargin = elementXPx
            topMargin = adjustedYPx.coerceAtLeast(0) // 确保 topMargin 不为负
        }
    }

    /**
     * 移除容器及其所有子视图，以便在销毁或重建时使用
     */
    private fun removeOverlayViews() {
        if (overlayContainerView != null) {
            Log.d(TAG, "开始移除悬浮窗容器及所有元素 (${activeViews.size} 个)")
            try {
                // 移除容器会自动移除所有子视图
                windowManager.removeView(overlayContainerView)
                Log.d(TAG, "悬浮窗容器已移除")
            } catch (e: Exception) {
                Log.e(TAG, "移除悬浮窗容器时出错", e)
            }
            overlayContainerView = null
        }
        // 清空当前记录的视图引用
        activeViews.clear()
        // Log.d(TAG, "活动视图列表已清空")
    }

    // --- 新增：获取状态栏高度的方法 ---
    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        // 如果获取失败，尝试一个基于密度的估算值
        if (result == 0) {
            result = (24 * resources.displayMetrics.density).toInt()
            Log.w(TAG, "无法精确获取状态栏高度，使用估算值: $result px")
        }
        return result
    }
    // --- 新增结束 ---
}
