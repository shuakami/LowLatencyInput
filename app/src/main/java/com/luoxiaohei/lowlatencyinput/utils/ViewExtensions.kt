package com.luoxiaohei.lowlatencyinput.utils

import android.view.View
import com.google.android.material.snackbar.Snackbar

/**
 * 提供一组与 Snackbar 显示相关的工具函数：
 * - 支持传入字符串或资源ID
 * - 可选添加一个操作按钮
 * - Snackbar 的具体样式可通过全局主题自定义
 */
fun showStyledSnackbar(
    view: View,
    message: CharSequence,
    duration: Int,
    actionText: CharSequence? = null,
    action: ((View) -> Unit)? = null
): Snackbar {
    // 创建并配置 Snackbar
    val snackbar = Snackbar.make(view, message, duration)
    if (actionText != null && action != null) {
        snackbar.setAction(actionText, action)
    }
    // 样式通过主题进行全局应用
    return snackbar
}

/**
 * 使用字符串资源ID显示 Snackbar
 */
fun showStyledSnackbar(
    view: View,
    messageResId: Int,
    duration: Int,
    actionText: CharSequence? = null,
    action: ((View) -> Unit)? = null
): Snackbar {
    val message = view.context.getText(messageResId)
    return showStyledSnackbar(view, message, duration, actionText, action)
}

/**
 * 使用字符串资源ID显示 Snackbar，并可同时传入操作按钮文案的资源ID
 */
fun showStyledSnackbar(
    view: View,
    messageResId: Int,
    duration: Int,
    actionTextResId: Int? = null,
    action: ((View) -> Unit)? = null
): Snackbar {
    val message = view.context.getText(messageResId)
    val actionText = if (actionTextResId != null) view.context.getText(actionTextResId) else null
    return showStyledSnackbar(view, message, duration, actionText, action)
}
