package com.luoxiaohei.lowlatencyinput.model

import kotlinx.serialization.Serializable

/**
 * 表示悬浮窗上的单个 UI 元素。
 *
 * @param id 元素的唯一标识符，用于发送给服务器。
 * @param type 元素的类型 (例如 "Button", "TextView")。
 * @param x 左上角 X 坐标 (像素)。
 * @param y 左上角 Y 坐标 (像素)。
 * @param width 宽度 (像素)。
 * @param height 高度 (像素)。
 * @param label 标签，可以为 null。
 * @param alpha 透明度，范围从 0.0 到 1.0，0.0 表示完全透明，1.0 表示完全不透明。
 */
@Serializable
data class OverlayElement(
    val id: String,
    val type: String, // Renamed from elementType
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val label: String? = null, // Added nullable label field
    val alpha: Float = 1.0f // 添加 alpha 属性，默认 1.0 (不透明)
)

/**
 * 表示整个悬浮窗的布局，包含一组元素。
 *
 * @param elements 布局中所有元素的列表。
 */
@Serializable
data class OverlayLayout(
    val elements: List<OverlayElement> = emptyList()
)
