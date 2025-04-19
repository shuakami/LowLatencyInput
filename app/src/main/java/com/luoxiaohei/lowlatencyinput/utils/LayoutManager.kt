package com.luoxiaohei.lowlatencyinput.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.luoxiaohei.lowlatencyinput.model.OverlayElement
import com.luoxiaohei.lowlatencyinput.model.OverlayLayout
import com.luoxiaohei.lowlatencyinput.model.AvailableElements
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

/**
 * 负责管理悬浮窗布局数据的加载和保存。
 * 使用 SharedPreferences 存储布局的 JSON 表示。
 */
object LayoutManager {

    private const val TAG = "LayoutManager"
    private const val PREFS_NAME = "OverlayLayoutPrefs"
    private const val KEY_LAYOUT_JSON = "layoutJson"

    // 配置 kotlinx.serialization Json 解析器
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 保存给定的悬浮窗布局到 SharedPreferences。
     *
     * @param context Context 对象，用于访问 SharedPreferences。
     * @param layout 要保存的 OverlayLayout 对象。
     */
    fun saveLayout(context: Context, layout: OverlayLayout) {
        try {
            val jsonString = json.encodeToString(layout)
            Log.d(TAG, "Layout JSON data: $jsonString")
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_LAYOUT_JSON, jsonString).apply()
            Log.d(TAG, "布局已保存: ${layout.elements.size} 个元素")
        } catch (e: SerializationException) {
            Log.e(TAG, "序列化布局时出错", e)
        } catch (e: Exception) {
            Log.e(TAG, "保存布局到 SharedPreferences 时出错", e)
        }
    }

    /**
     * 从 SharedPreferences 加载悬浮窗布局。
     *
     * @param context Context 对象，用于访问 SharedPreferences。
     * @return 加载到的 OverlayLayout 对象。如果未找到或解析失败，则返回包含空列表的默认布局。
     */
    fun loadLayout(context: Context): OverlayLayout {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_LAYOUT_JSON, null)

        return if (jsonString != null) {
            try {
                val loadedLayout = json.decodeFromString<OverlayLayout>(jsonString)
                Log.d(TAG, "布局已加载自 SharedPreferences: ${loadedLayout.elements.size} 个元素")
                loadedLayout
            } catch (e: SerializationException) {
                Log.e(TAG, "反序列化布局时出错，返回默认布局", e)
                getDefaultLayout(context)
            } catch (e: Exception) {
                Log.e(TAG, "从 SharedPreferences 加载布局时出错，返回默认布局", e)
                getDefaultLayout(context)
            }
        } else {
            Log.d(TAG, "未找到已保存的布局，返回默认布局")
            getDefaultLayout(context)
        }
    }

    /**
     * 创建一个包含默认元素的悬浮窗布局，用于首次启动或加载失败时。
     */
    private fun getDefaultLayout(context: Context): OverlayLayout {
        Log.d(TAG, "创建默认布局 (仅含关闭按钮)")

        val closeButtonInfo = AvailableElements.findByType(AvailableElements.TYPE_CLOSE_BUTTON)
        val defaultElements = mutableListOf<OverlayElement>()

        // 这里依旧使用屏幕宽度计算以放置默认关闭按钮
        val displayMetrics = context.resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val safeMarginDp = 10f // 保证按钮不会贴到屏幕边缘
        
        if (closeButtonInfo != null) {
            val elementWidthDp = closeButtonInfo.defaultWidthDp
            val defaultX = screenWidthDp - elementWidthDp - safeMarginDp
            val defaultY = 80f // 相对顶部的距离固定为 80dp

            defaultElements.add(
                OverlayElement(
                    id = "default_${closeButtonInfo.type}",
                    type = closeButtonInfo.type,
                    width = closeButtonInfo.defaultWidthDp,
                    height = closeButtonInfo.defaultHeightDp,
                    x = defaultX.coerceAtLeast(0f),
                    y = defaultY.coerceAtLeast(safeMarginDp),
                    label = closeButtonInfo.defaultLabel
                )
            )
        }

        return OverlayLayout(
            elements = defaultElements
        )
    }
}
