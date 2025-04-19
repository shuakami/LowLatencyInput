package com.luoxiaohei.lowlatencyinput.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.luoxiaohei.lowlatencyinput.R
import com.luoxiaohei.lowlatencyinput.model.AvailableElements
import com.luoxiaohei.lowlatencyinput.model.OverlayElement
import com.luoxiaohei.lowlatencyinput.model.OverlayLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * 用于在画布上编辑、移动各类 OverlayElement 的自定义视图
 * 支持顶部下拉以展开 AppBar。
 * 维护了对元素的选中状态，并在拖动时进行边界检测。
 */
interface EditingCanvasListener {
    fun onLayoutChanged(newLayout: OverlayLayout)
    fun onSelectionChanged(isSelected: Boolean)
    fun onElementAlphaChanged(elementId: String, newAlpha: Float)
}

class EditingCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "EditingCanvasView"
        private const val INVALID_POINTER_ID = -1
    }

    // ------------------------------
    // 属性与状态
    // ------------------------------

    // 触摸判定相关
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var activePointerId = INVALID_POINTER_ID
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging = false
    private var isPotentialTopEdgeSwipe = false

    // 记录当前布局与选中元素
    private var currentLayout: OverlayLayout = OverlayLayout()
    private var selectedElement: OverlayElement? = null
    var listener: EditingCanvasListener? = null

    // 用于展开/折叠 AppBar
    private var appBarLayoutRef: AppBarLayout? = null

    // contentOffsetY 不再用于绘制，只用于顶部区域下拉判断
    private var contentOffsetY = 0f

    // 在此处统一缓存屏幕密度，免得多次重复 getDensity
    private val density: Float by lazy {
        resources.displayMetrics.density
    }

    // 画笔相关
    private lateinit var elementPaint: Paint
    private lateinit var elementBorderPaint: Paint
    private lateinit var selectedBorderPaint: Paint
    private lateinit var selectedOuterBorderPaint: Paint
    private lateinit var textPaint: Paint
    private lateinit var backgroundPaint: Paint
    private lateinit var gridPaint: Paint

    // 关闭图标资源
    private var closeIconDrawable: android.graphics.drawable.Drawable? = null

    // 编辑器 Logo 相关
    private var logoDrawable: android.graphics.drawable.Drawable? = null
    private val logoSizeDp = 80f
    private val logoAlpha = 128 // Logo 的透明度 (0-255, 128 约 50%)

    init {
        initializePaints()
        closeIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_close_small)?.mutate()
        // 加载 Logo
        try {
            logoDrawable = ContextCompat.getDrawable(context, R.drawable.tfui_logo_editsystem)?.mutate()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading logo drawable", e)
        }
    }

    /**
     * 初始化各类 Paint 对象，部分颜色从 MaterialTheme 获取。
     */
    private fun initializePaints() {
        val defaultStrokeWidth = 2f * density
        val selectedInnerStrokeWidth = 3f * density
        val selectedOuterStrokeWidth = 4.5f * density
        val shadowRadius = 6f * density

        val colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val colorSurfaceContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer)
        val colorOutline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
        val colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val colorSurfaceDim = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceDim)
        val colorPrimaryTransparent = Color.argb(100, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary))
        val colorWhiteTransparent = Color.parseColor("#A0FFFFFF")

        elementPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorSurfaceContainer
            style = Style.FILL
            alpha = 230
        }

        elementBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOutline
            style = Style.STROKE
            strokeWidth = defaultStrokeWidth
        }

        selectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorPrimary
            style = Style.STROKE
            strokeWidth = selectedInnerStrokeWidth
        }

        selectedOuterBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorWhiteTransparent
            style = Style.STROKE
            strokeWidth = selectedOuterStrokeWidth
            setShadowLayer(shadowRadius, 0f, 0f, colorPrimaryTransparent)
        }

        textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOnSurface
            textSize = 12f * resources.displayMetrics.scaledDensity
            textAlign = Paint.Align.CENTER
        }

        backgroundPaint = Paint().apply {
            color = ContextCompat.getColor(context, android.R.color.transparent)
        }

        gridPaint = Paint().apply {
            color = colorSurfaceDim
            style = Style.STROKE
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        }
    }

    // ------------------------------
    // 处理 AppBar 下拉展开
    // ------------------------------

    /**
     * 由外部将 AppBarLayout 传入，用于下拉展开
     */
    fun setAppBarLayout(appBar: AppBarLayout) {
        appBarLayoutRef = appBar
        updateContentOffsetY()
    }

    /**
     * 计算当前顶部偏移量，仅用于判断下拉距离，绘制不做使用
     */
    private fun updateContentOffsetY() {
        val appBarHeight = appBarLayoutRef?.height ?: 0
        val statusBarHeight = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        contentOffsetY = (statusBarHeight + appBarHeight).toFloat()
        Log.d(TAG, "Calculated content offset: $contentOffsetY (StatusBar: $statusBarHeight, AppBar: $appBarHeight)")
    }

    /**
     * 不再实际用于绘制，可提供外部查询兼容
     */
    fun getContentOffsetY(): Float {
        return contentOffsetY
    }

    // ------------------------------
    // 公共 API
    // ------------------------------

    fun setLayout(layout: OverlayLayout) {
        currentLayout = layout
        selectedElement = null
        listener?.onSelectionChanged(false)
        invalidate()
    }

    fun getLayout(): OverlayLayout {
        return currentLayout
    }

    fun getSelectedElementId(): String? {
        return selectedElement?.id
    }

    /**
     * 仅更新内部布局数据，不触发选中清除等副作用。
     * 用于 Activity 回调更新尺寸或透明度后同步状态。
     */
    fun setLayoutDataOnly(layout: OverlayLayout) {
        this.currentLayout = layout
        // 更新选中元素的引用，以防 copy 后实例变化
        selectedElement?.let { currentSelected -> 
            selectedElement = layout.elements.find { it.id == currentSelected.id }
        }
    }

    // ------------------------------
    // 尺寸/Insets 变化处理
    // ------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateContentOffsetY() // 更新顶部偏移用于顶部下拉判断
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        updateContentOffsetY()
        return super.onApplyWindowInsets(insets)
    }

    // ------------------------------
    // 绘制相关
    // ------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制背景网格
        drawGrid(canvas)
        
        // --- 新增：绘制编辑器 Logo --- 
        drawEditorLogo(canvas)

        // 绘制布局中的元素
        currentLayout.elements.forEach { element ->
            drawElement(canvas, element)
        }

        // 在底部绘制简要使用说明
        drawInstructions(canvas)
    }

    /**
     * 绘制编辑器 Logo 在左下角提示文字上方
     */
    private fun drawEditorLogo(canvas: Canvas) {
        logoDrawable?.let { logo ->
            // --- 计算提示文字区域的顶部 Y 坐标和行高 (按 3 行算) --- 
            val instructionPaint = textPaint 
            val margin = 10f * density
            val lineHeight = instructionPaint.fontSpacing + 4f * density
            val lineCount = 3
            val instructionTopY = height - margin - (lineHeight * lineCount)

            // --- 计算 Logo 绘制区域 --- 
            val logoSizePx = logoSizeDp * density
            // 将 Logo 底部向下移动一点，更靠近文字
            val logoBottom = instructionTopY + (lineHeight / 3f) 
            val logoTop = logoBottom - logoSizePx
            val logoLeft = margin
            val logoRight = logoLeft + logoSizePx
            
            // 保持 Logo 的原始宽高比进行缩放
            val originalWidth = logo.intrinsicWidth
            val originalHeight = logo.intrinsicHeight
            var drawWidth = logoSizePx
            var drawHeight = logoSizePx

            if (originalWidth > 0 && originalHeight > 0) {
                val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
                if (aspectRatio > 1) { // 宽大于高
                    drawHeight = drawWidth / aspectRatio
                } else { // 高大于等于宽
                    drawWidth = drawHeight * aspectRatio
                }
            }
            
            // 确保绘制尺寸不超过 logoSizePx
            drawWidth = min(drawWidth, logoSizePx)
            drawHeight = min(drawHeight, logoSizePx)
            
            // 计算居中后的绘制坐标 (在 logoSizePx x logoSizePx 区域内)
            val finalLeft = logoLeft + (logoSizePx - drawWidth) / 2f
            val finalTop = logoTop + (logoSizePx - drawHeight) / 2f
            val finalRight = finalLeft + drawWidth
            val finalBottom = finalTop + drawHeight

            logo.setBounds(finalLeft.toInt(), finalTop.toInt(), finalRight.toInt(), finalBottom.toInt())
            logo.alpha = logoAlpha // 设置透明度
            logo.draw(canvas)
        }
    }

    /**
     * 在画布上绘制网格线，仅作编辑参考
     */
    private fun drawGrid(canvas: Canvas) {
        val gridSize = 20f * density
        for (x in 0..width step gridSize.toInt()) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint)
        }
        for (y in 0..height step gridSize.toInt()) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), gridPaint)
        }
    }

    /**
     * 底部绘制提示文案，以便操作时了解基本指令
     */
    private fun drawInstructions(canvas: Canvas) {
        val instructionPaint = Paint(textPaint).apply {
            textAlign = Paint.Align.LEFT
            textSize = 10f * resources.displayMetrics.scaledDensity
            alpha = 60 // <-- 再次降低透明度
            color = MaterialColors.getColor(this@EditingCanvasView, com.google.android.material.R.attr.colorOutline)
        }
        val margin = 10f * density
        val lineHeight = instructionPaint.fontSpacing + 4f * density
        val lineCount = 3 // <-- 改为 3 行
        var yPos = height - margin - (lineHeight * lineCount)

        canvas.drawText("· 点按元素进行选中", margin, yPos, instructionPaint)
        yPos += lineHeight
        canvas.drawText("· 长按并拖动元素移动位置", margin, yPos, instructionPaint)
        yPos += lineHeight
        canvas.drawText("· 选中元素后，底部可调节透明度和大小", margin, yPos, instructionPaint)
    }

    /**
     * 根据元素类型和坐标绘制不同外观
     */
    private fun drawElement(canvas: Canvas, element: OverlayElement) {
        // 根据元素的 DP 坐标，乘以 density 转成像素
        val left = element.x * density
        val top = element.y * density
        val right = left + element.width * density
        val bottom = top + element.height * density
        val rect = RectF(left, top, right, bottom)

        val isSelected = (element.id == selectedElement?.id)
        val borderPaint = if (isSelected) selectedBorderPaint else elementBorderPaint

        // --- 修改：优先检查是否为图标类型且有 iconResId --- 
        val elementInfo = AvailableElements.findByType(element.type)
        if (elementInfo?.iconResId != null) {
            val drawable = ContextCompat.getDrawable(context, elementInfo.iconResId)?.mutate()
            if (drawable != null) {
                drawable.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                drawable.alpha = (element.alpha * 255).toInt() // <-- 应用 alpha
                drawable.draw(canvas)
                // --- 修改：绘制双层发光选中边框 --- 
                if (isSelected) {
                    val outerBorderPaint = selectedOuterBorderPaint
                    val innerBorderPaint = selectedBorderPaint
                    
                    if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0 && element.width > element.height * 1.5) { // 长方形
                        canvas.drawRect(rect, outerBorderPaint)
                        canvas.drawRect(rect, innerBorderPaint)
                    } else if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0 && element.width == element.height) { // 正方形
                        val cornerRadius = 8f * density
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, outerBorderPaint)
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, innerBorderPaint)
                    } else { // 其他圆形
                        canvas.drawOval(rect, outerBorderPaint)
                        canvas.drawOval(rect, innerBorderPaint)
                    }
                }
            } else {
                // 如果 Drawable 加载失败，可以画个占位符
                Log.w(TAG, "Failed to load drawable for icon element: ${element.id}")
                canvas.drawRect(rect, elementPaint)
                canvas.drawRect(rect, borderPaint)
                val textX = rect.centerX()
                val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText("?", textX, textY, textPaint)
            }
        } else { // --- 如果不是带 iconResId 的图标类型，执行原有逻辑 --- 
            when {
                // --- 恢复对 TYPE_CLOSE_BUTTON 的特殊处理 ---
                element.type == AvailableElements.TYPE_CLOSE_BUTTON -> {
                    // 应用 alpha 到背景和文字/图标
                    val baseAlpha = (element.alpha * 255).toInt()
                    elementPaint.alpha = (0.9 * baseAlpha).toInt() // 背景稍微透明点
                    borderPaint.alpha = baseAlpha
                    textPaint.alpha = baseAlpha
                    closeIconDrawable?.alpha = baseAlpha

                    val cornerRadius = 8f * density
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, elementPaint)
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
                    
                    // 绘制图标 + 文本 (复用之前的逻辑)
                    val iconSize = 16f * density
                    val padding = 4f * density
                    val textPadding = 2f * density
                    val iconLeft = rect.left + padding
                    val iconTop = rect.centerY() - (iconSize / 2f)
                    closeIconDrawable?.setBounds(
                        iconLeft.toInt(),
                        iconTop.toInt(),
                        (iconLeft + iconSize).toInt(),
                        (iconTop + iconSize).toInt()
                    )
                    closeIconDrawable?.setTint(textPaint.color) // 应用文字颜色作为 tint
                    closeIconDrawable?.draw(canvas)

                    textPaint.textAlign = Paint.Align.LEFT // 左对齐绘制文本
                    val textX = iconLeft + iconSize + textPadding
                    val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText(element.label ?: element.id, textX, textY, textPaint)
                    textPaint.textAlign = Paint.Align.CENTER // 恢复默认对齐

                    // --- 修改：绘制双层发光选中边框 --- 
                    if (isSelected) {
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, selectedOuterBorderPaint)
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, selectedBorderPaint)
                    } else {
                        // 绘制普通边框
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, elementBorderPaint)
                    }

                    // 恢复 paint alpha
                    elementPaint.alpha = 230
                    borderPaint.alpha = 255
                    textPaint.alpha = 255
                    closeIconDrawable?.alpha = 255                    
                }
                // 示例：摇杆类元素以圆形绘制
                element.type.startsWith("joystick") -> {
                    // 应用 alpha
                    val baseAlpha = (element.alpha * 255).toInt()
                    elementPaint.alpha = (0.9 * baseAlpha).toInt()
                    borderPaint.alpha = baseAlpha
                    textPaint.alpha = baseAlpha

                    val radius = rect.width() / 2f
                    val centerX = rect.centerX()
                    val centerY = rect.centerY()
                    canvas.drawCircle(centerX, centerY, radius, elementPaint)
                    canvas.drawCircle(centerX, centerY, radius, borderPaint)
                    val textX = centerX
                    val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText(element.label ?: element.id, textX, textY, textPaint)
                    
                    // --- 修改：绘制双层发光选中边框 --- 
                    if (isSelected) {
                        canvas.drawCircle(centerX, centerY, radius, selectedOuterBorderPaint)
                        canvas.drawCircle(centerX, centerY, radius, selectedBorderPaint)
                    } else {
                        // 绘制普通边框
                        canvas.drawCircle(centerX, centerY, radius, elementBorderPaint)
                    }

                    // 恢复 paint alpha
                    elementPaint.alpha = 230
                    borderPaint.alpha = 255
                    textPaint.alpha = 255
                }
                // 普通 Button 或其他未知类型
                else -> {
                    // 应用 alpha
                    val baseAlpha = (element.alpha * 255).toInt()
                    elementPaint.alpha = (0.9 * baseAlpha).toInt()
                    borderPaint.alpha = baseAlpha
                    textPaint.alpha = baseAlpha

                    val cornerRadius = 8f * density
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, elementPaint)
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
                    val textX = rect.centerX()
                    val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText(element.label ?: element.id, textX, textY, textPaint)

                    // --- 修改：绘制双层发光选中边框 --- 
                    if (isSelected) {
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, selectedOuterBorderPaint)
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, selectedBorderPaint)
                    } else {
                        // 绘制普通边框
                        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, elementBorderPaint)
                    }

                    // 恢复 paint alpha
                    elementPaint.alpha = 230
                    borderPaint.alpha = 255
                    textPaint.alpha = 255
                }
            }
        }
    }

    // ------------------------------
    // 触摸逻辑处理
    // ------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val eventX = event.getX(pointerIndex)
        val eventY = event.getY(pointerIndex)
        
        var handled = false // 初始化为 false

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                isPotentialTopEdgeSwipe = false
                activePointerId = INVALID_POINTER_ID
                isDragging = false

                // 检测是否点中某个元素
                val touchedElement = findElementAt(eventX, eventY)
                if (touchedElement != null) {
                    val previouslySelected = (selectedElement != null)
                    val selectionChanged = (selectedElement?.id != touchedElement.id)

                    selectedElement = touchedElement
                    activePointerId = event.getPointerId(pointerIndex)
                    lastTouchX = eventX
                    lastTouchY = eventY
                    initialTouchX = eventX
                    initialTouchY = eventY
                    invalidate()

                    if (selectionChanged || !previouslySelected) {
                        listener?.onSelectionChanged(true)
                    }
                    handled = true // 选中即处理
                } else {
                    // 点在空白处则清除选中
                    if (selectedElement != null) {
                        selectedElement = null
                        invalidate()
                        listener?.onSelectionChanged(false)
                    }

                    // 如果接近顶部，则判定可能进行下拉操作展开 AppBar
                    val swipeAreaTop = 0 + touchSlop * 2
                    if (eventY < swipeAreaTop) {
                        isPotentialTopEdgeSwipe = true
                        activePointerId = event.getPointerId(pointerIndex)
                        lastTouchX = eventX
                        lastTouchY = eventY
                        initialTouchX = eventX
                        initialTouchY = eventY
                        handled = true // 开始检测下拉即处理
                    } else {
                        // 空白且非顶部
                        handled = false // 点击空白处未处理
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                 var moveHandled = false
                 if (activePointerId == INVALID_POINTER_ID && !isPotentialTopEdgeSwipe) {
                      // 没有活动指针且非下拉尝试，MOVE 无需处理
                 } else {
                    val currentPointerIndex = event.findPointerIndex(activePointerId)
                    if (currentPointerIndex < 0) return false

                    val x = event.getX(currentPointerIndex)
                    val y = event.getY(currentPointerIndex)
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY

                    // 如果用户在顶部区域下拉，则尝试展开 AppBar
                    if (isPotentialTopEdgeSwipe) {
                        val totalDx = x - initialTouchX
                        val totalDy = y - initialTouchY

                        if (totalDy > touchSlop) {
                            expandAppBarLayout()
                            isPotentialTopEdgeSwipe = false
                            activePointerId = INVALID_POINTER_ID
                            moveHandled = true // 顶部滑动逻辑处理了事件
                        } else if (abs(totalDx) > touchSlop || totalDy < -touchSlop) {
                            // 确认下拉或不再视为下拉
                            isPotentialTopEdgeSwipe = false
                            activePointerId = INVALID_POINTER_ID 
                            moveHandled = true
                        } else {
                            // 仍在检测中
                            moveHandled = true
                        }
                    }
                    // 拖动元素
                    else if (selectedElement != null) { // 确保 activePointerId 有效且有选中元素
                        val distanceX = abs(x - initialTouchX)
                        val distanceY = abs(y - initialTouchY)
                        if (!isDragging && (distanceX > touchSlop || distanceY > touchSlop)) {
                            isDragging = true
                        }
        
                        if (isDragging) {
                            val newX = selectedElement!!.x + (dx / density)
                            val newY = selectedElement!!.y + (dy / density)

                            // 对元素进行边界检测，防止超出画布
                            val elementWidthDp = selectedElement!!.width
                            val elementHeightDp = selectedElement!!.height

                            val viewWidthDp = width / density
                            val viewHeightDp = height / density

                            val minX = 0f
                            val minY = 0f
                            val maxX = viewWidthDp - elementWidthDp
                            val maxY = viewHeightDp - elementHeightDp

                            val finalX = newX.coerceIn(minX, maxX)
                            val finalY = newY.coerceIn(minY, maxY)

                            // 若被限制在边界内，则提示用户
                            if (newX != finalX || newY != finalY) {
                                showBoundarySnackbar()
                            }

                            val index = currentLayout.elements.indexOfFirst { it.id == selectedElement!!.id }
                            if (index != -1) {
                                val updatedElement = selectedElement!!.copy(x = finalX, y = finalY)
                                val updatedElements = currentLayout.elements.toMutableList()
                                updatedElements[index] = updatedElement
                                currentLayout = currentLayout.copy(elements = updatedElements)
                                selectedElement = updatedElement
                                listener?.onLayoutChanged(currentLayout)
                                invalidate()
                            }

                            moveHandled = true // 拖动处理了事件
                            lastTouchX = x
                            lastTouchY = y
                        } else {
                            // 未开始拖动，但可能在移动，更新 lastTouch
                            lastTouchX = x
                            lastTouchY = y
                            moveHandled = true // 选中元素后的移动也算处理
                        }
                    }
                 }
                 handled = handled || moveHandled // 如果 MOVE 处理了，则整体 handled 为 true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hideBoundarySnackbar()
                isPotentialTopEdgeSwipe = false
                isDragging = false
                activePointerId = INVALID_POINTER_ID
                handled = true 
            }
            
            MotionEvent.ACTION_POINTER_DOWN -> {
                 // 多指按下，不再需要特殊处理，标记为 handled?
                 // 或者不标记，让系统处理？暂时标记为 handled
                 handled = true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                 // ... (原有更新 activePointerId 逻辑不变) ...
                 handled = true 
            }
        }

        // --- 简化返回值逻辑 --- 
        return handled || super.onTouchEvent(event) // 如果我们处理了事件，返回 true，否则交给父类
    }

    /**
     * 在给定坐标处查找最上层的元素，若无则返回 null
     */
    private fun findElementAt(x: Float, y: Float): OverlayElement? {
        // 因为绘制和坐标一致，不再减 contentOffsetY
        for (element in currentLayout.elements.reversed()) {
            val left = element.x * density
            val topInViewPx = element.y * density
            val right = left + element.width * density
            val bottomInViewPx = topInViewPx + (element.height * density)
            if (x in left..right && y in topInViewPx..bottomInViewPx) {
                return element
            }
        }
        return null
    }

    /**
     * 当检测到下拉操作时，展开 AppBar
     */
    private fun expandAppBarLayout() {
        appBarLayoutRef?.setExpanded(true, true)
            ?: Log.w(TAG, "expandAppBarLayout: AppBarLayout reference is null, cannot expand.")
    }

    // ------------------------------
    // Snackbar 辅助，提示越界情况
    // ------------------------------
    private var boundarySnackbar: Snackbar? = null

    private fun showBoundarySnackbar() {
        if (boundarySnackbar?.isShownOrQueued == true) return
        val selected = selectedElement ?: return

        boundarySnackbar = Snackbar.make(
            this,
            "元素不能移到画布外面",
            Snackbar.LENGTH_INDEFINITE
        ).setAction("恢复默认位置") {
            resetElementPosition(selected.id)
        }
        boundarySnackbar?.show()
    }

    private fun hideBoundarySnackbar() {
        boundarySnackbar?.dismiss()
        boundarySnackbar = null
    }

    /**
     * 当元素拖出边界时，可点击 Snackbar 操作恢复默认位置
     */
    private fun resetElementPosition(elementId: String) {
        val index = currentLayout.elements.indexOfFirst { it.id == elementId }
        if (index != -1) {
            val elementToReset = currentLayout.elements[index]
            val elementInfo = AvailableElements.findByType(elementToReset.type) ?: return

            val screenWidthDp = width / density
            val defaultX = (screenWidthDp / 2f) - (elementInfo.defaultWidthDp / 2f)
            val defaultY = 100f

            val resetElement = elementToReset.copy(
                x = defaultX.coerceAtLeast(0f),
                y = defaultY.coerceAtLeast(0f)
            )
            val updatedElements = currentLayout.elements.toMutableList()
            updatedElements[index] = resetElement
            val newLayout = currentLayout.copy(elements = updatedElements)
            setLayout(newLayout)
            listener?.onLayoutChanged(newLayout)
            Log.d(TAG, "Reset position for element: $elementId")
        }
        hideBoundarySnackbar()
    }
}
