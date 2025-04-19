package com.luoxiaohei.lowlatencyinput.model

import androidx.annotation.DrawableRes
import com.luoxiaohei.lowlatencyinput.R

/**
 * 定义一个可用的悬浮窗元素类型及其默认属性。
 *
 * @param type 元素的唯一类型标识符（例如 "button"、"joystick_left"）
 * @param defaultLabel 在编辑器中使用的默认标签文本
 * @param defaultWidthDp 元素的默认宽度（单位：dp）
 * @param defaultHeightDp 元素的默认高度（单位：dp）
 * @param isMandatory 是否为必需元素，如为 true 则在编辑器中不允许删除
 * @param iconResId 图标类型元素的 Drawable 资源 ID (可选)
 */
data class AvailableElementInfo(
    val type: String,
    val defaultLabel: String,
    val defaultWidthDp: Float,
    val defaultHeightDp: Float,
    val isMandatory: Boolean = false,
    @DrawableRes val iconResId: Int? = null
)

/**
 * 存放所有可用的悬浮窗元素信息，供编辑器或其他功能模块使用。
 * 也提供根据类型查询具体元素信息的便捷方法。
 */
object AvailableElements {

    // 关闭按钮的常量标识，用于在代码中统一引用
    const val TYPE_CLOSE_BUTTON = "close_button"

    // 图标类型常量
    const val TYPE_ICON_GRENADE = "icon_grenade"
    const val TYPE_ICON_CROSSHAIR = "icon_crosshair"
    const val TYPE_ICON_BULLET = "icon_bullet"
    const val TYPE_ICON_JUMPAD = "icon_jumpad"
    const val TYPE_ICON_RIFLE = "icon_rifle"
    const val TYPE_ICON_SMOKE = "icon_smoke"
    const val TYPE_ICON_TURRET = "icon_turret"
    const val TYPE_ICON_CROUCH = "icon_crouch"

    /**
     * all 列表：包含所有可选元素的默认配置。
     * 例如：关闭按钮、通用按钮、两个示例摇杆等。
     */
    val all: List<AvailableElementInfo> = listOf(
        AvailableElementInfo(
            type = TYPE_CLOSE_BUTTON,
            defaultLabel = "长按关闭",
            defaultWidthDp = 70f,
            defaultHeightDp = 24f,
            isMandatory = true
        ),
        AvailableElementInfo(
            type = "icon_jump",
            defaultLabel = "跳跃",
            defaultWidthDp = 32f,
            defaultHeightDp = 32f,
            iconResId = R.drawable.bg_icon_jump
        ),
        AvailableElementInfo(
            type = "joystick_ring",
            defaultLabel = "轮盘",
            defaultWidthDp = 100f,
            defaultHeightDp = 100f,
            iconResId = R.drawable.bg_icon_joystick_ring
        ),
        AvailableElementInfo(
            type = TYPE_ICON_GRENADE,
            defaultLabel = "手雷",
            defaultWidthDp = 32f,
            defaultHeightDp = 32f,
            iconResId = R.drawable.bg_icon_grenade
        ),
        AvailableElementInfo(
            type = TYPE_ICON_CROSSHAIR,
            defaultLabel = "准星",
            defaultWidthDp = 32f,
            defaultHeightDp = 32f,
            iconResId = R.drawable.bg_icon_crosshair
        ),
        AvailableElementInfo(
            type = TYPE_ICON_BULLET,
            defaultLabel = "子弹",
            defaultWidthDp = 32f,
            defaultHeightDp = 32f,
            iconResId = R.drawable.bg_icon_bullet
        ),
        AvailableElementInfo(
            type = TYPE_ICON_JUMPAD,
            defaultLabel = "跳板",
            defaultWidthDp = 32f,
            defaultHeightDp = 32f,
            iconResId = R.drawable.bg_icon_jumpad
        ),
        AvailableElementInfo(
            type = TYPE_ICON_RIFLE,
            defaultLabel = "步枪",
            defaultWidthDp = 80f,
            defaultHeightDp = 40f,
            iconResId = R.drawable.bg_icon_rifle
        ),
        AvailableElementInfo(
            type = TYPE_ICON_SMOKE,
            defaultLabel = "烟雾弹",
            defaultWidthDp = 32f,
            defaultHeightDp = 32f,
            iconResId = R.drawable.tfui_icon_smoke_grenade
        ),
        AvailableElementInfo(
            type = TYPE_ICON_TURRET,
            defaultLabel = "炮塔",
            defaultWidthDp = 32f,
            defaultHeightDp = 32f,
            iconResId = R.drawable.bg_icon_turret
        ),
        AvailableElementInfo(
            type = TYPE_ICON_CROUCH,
            defaultLabel = "蹲伏",
            defaultWidthDp = 32f,
            defaultHeightDp = 32f,
            iconResId = R.drawable.bg_icon_crouch
        )
    )

    /**
     * 根据类型在 all 列表中查找对应的元素信息
     *
     * @param type 元素的类型标识符
     * @return 匹配到的 AvailableElementInfo 或 null（若未找到）
     */
    fun findByType(type: String): AvailableElementInfo? {
        return all.find { it.type == type }
    }
}
