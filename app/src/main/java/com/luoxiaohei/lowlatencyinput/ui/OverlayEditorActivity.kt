package com.luoxiaohei.lowlatencyinput.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.luoxiaohei.lowlatencyinput.R
import com.luoxiaohei.lowlatencyinput.databinding.ActivityOverlayEditorBinding
import com.luoxiaohei.lowlatencyinput.model.AvailableElements
import com.luoxiaohei.lowlatencyinput.model.OverlayElement
import com.luoxiaohei.lowlatencyinput.model.OverlayLayout
import com.luoxiaohei.lowlatencyinput.ui.editor.EditingCanvasListener
import com.luoxiaohei.lowlatencyinput.ui.editor.EditingCanvasView
import com.luoxiaohei.lowlatencyinput.ui.fragment.AddElementBottomSheetDialogFragment
import com.luoxiaohei.lowlatencyinput.utils.LayoutManager
import com.luoxiaohei.lowlatencyinput.utils.showStyledSnackbar
import com.luoxiaohei.lowlatencyinput.service.RuntimeOverlayService
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import java.util.UUID
import android.app.ActivityManager
import android.content.Context
import androidx.transition.TransitionManager
import androidx.transition.Fade
import androidx.transition.Slide
import android.view.Gravity

/**
 * OverlayEditorActivity 是主要的编辑器界面，负责管理 EditingCanvasView 与各种交互逻辑：
 * - 显示/添加/删除元素
 * - 保存当前布局
 * - 处理顶部 AppBar、底部按钮 UI 的显示/隐藏
 */
class OverlayEditorActivity : AppCompatActivity(), EditingCanvasListener {

    private lateinit var binding: ActivityOverlayEditorBinding
    private lateinit var editingCanvas: EditingCanvasView

    // 用于记录布局是否被修改
    private var hasChanges = false 

    // 菜单项引用，用于控制元素删除、UI 显示切换等
    private var deleteMenuItem: android.view.MenuItem? = null
    private var toggleUiMenuItem: android.view.MenuItem? = null
    private var previewMenuItem: android.view.MenuItem? = null

    // 用于执行"撤销删除"操作
    private var recentlyDeletedElement: OverlayElement? = null
    private var recentlyDeletedIndex: Int = -1

    // 底部按钮与 FAB 的可见性
    private var isBottomUiVisible = true

    companion object {
        private const val TAG = "OverlayEditorActivity"
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 101 // 用于权限请求
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOverlayEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化编辑画布
        editingCanvas = binding.editingCanvas
        editingCanvas.listener = this

        // 让编辑视图可以感知到 AppBarLayout，用于顶部下拉展开
        editingCanvas.setAppBarLayout(binding.appBarLayout)

        setupToolbar()
        setupFab()

        // 使用 post 延迟执行，以确保画布已完成测量，再加载布局
        binding.root.post {
            loadLayoutAndDraw()
            updatePreviewButtonState()
        }

        // 监听底部保存按钮
        binding.buttonSaveLayout.setOnClickListener {
            saveLayoutAndFinish()
        }
        
        // 初始化新的 Slider 控制面板逻辑
        setupSliderPanelListeners()
        // 移除旧的 setup 调用
        // setupAlphaSlider()
        // setupSizeSlider()

        // 初始状态：尚无修改
        updateSaveButtonState(false)
    }

    /** 
     * 初始化新的 Slider 控制面板的设置和监听器
     */
    private fun setupSliderPanelListeners() {
        // Alpha Slider in Panel
        binding.sliderAlphaPanel.valueFrom = 0.0f
        binding.sliderAlphaPanel.valueTo = 1.0f
        binding.sliderAlphaPanel.stepSize = 0.05f
        binding.sliderAlphaPanel.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                editingCanvas.getSelectedElementId()?.let { elementId ->
                    onElementAlphaChanged(elementId, value)
                    // 不再需要在这里 invalidate，onElementAlphaChanged 内部会调用
                }
            }
        }

        // Size Slider in Panel
        binding.sliderSizePanel.valueFrom = 0.5f
        binding.sliderSizePanel.valueTo = 5.0f // <-- 更新最大值为 5.0
        binding.sliderSizePanel.stepSize = 0.05f
        binding.sliderSizePanel.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                editingCanvas.getSelectedElementId()?.let { elementId ->
                    // 直接调用更新逻辑
                    updateElementSizeFromSlider(elementId, value)
                }
            }
        }
    }
    
    /** 
     * 根据 Size Slider 的值更新元素尺寸
     */
    private fun updateElementSizeFromSlider(elementId: String, scale: Float) {
        val currentLayout = editingCanvas.getLayout()
        val index = currentLayout.elements.indexOfFirst { it.id == elementId }
        if (index != -1) {
            val currentElement = currentLayout.elements[index]
            AvailableElements.findByType(currentElement.type)?.let { defaultInfo ->
                val baseWidth = defaultInfo.defaultWidthDp
                val baseHeight = defaultInfo.defaultHeightDp
                if (baseWidth > 0 && baseHeight > 0) {
                    val newWidth = baseWidth * scale
                    val newHeight = baseHeight * scale
                    
                    val updatedElement = currentElement.copy(width = newWidth, height = newHeight)
                    val updatedElements = currentLayout.elements.toMutableList()
                    updatedElements[index] = updatedElement
                    val newLayout = currentLayout.copy(elements = updatedElements)
                    editingCanvas.setLayoutDataOnly(newLayout)
                    Log.d(TAG, "Element $elementId resized to ${newWidth}x${newHeight} dp via Slider (Scale: $scale)")
                    onLayoutChanged(newLayout) // 触发布局变化
                    editingCanvas.invalidate() // 请求重绘
                } else {
                    Log.w(TAG, "Cannot resize element ${elementId} with zero base dimension.")
                }
            }
        }
    }

    /**
     * 设置标题栏与菜单事件
     */
    private fun setupToolbar() {
        deleteMenuItem = binding.editorToolbar.menu.findItem(R.id.action_delete_element)
        toggleUiMenuItem = binding.editorToolbar.menu.findItem(R.id.action_toggle_ui_visibility)
        previewMenuItem = binding.editorToolbar.menu.findItem(R.id.action_preview_overlay)

        binding.editorToolbar.setNavigationOnClickListener {
            confirmDiscardOrFinish()
        }
        binding.editorToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_save_layout -> {
                    saveLayoutAndFinish()
                    true
                }
                R.id.action_delete_element -> {
                    deleteSelectedElement()
                    true
                }
                R.id.action_toggle_ui_visibility -> {
                    toggleBottomUiVisibility()
                    true
                }
                R.id.action_preview_overlay -> {
                    toggleOverlayPreview()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 设置右下角 FAB 按钮，用于唤起 BottomSheet 选择元素
     */
    private fun setupFab() {
        binding.fabAddElement.setOnClickListener {
            showAddElementBottomSheet()
        }
    }

    /**
     * 加载保存过的布局并显示
     */
    private fun loadLayoutAndDraw() {
        val currentLayout = LayoutManager.loadLayout(this)
        editingCanvas.setLayout(currentLayout)
        Log.d(TAG, "Layout loaded with ${currentLayout.elements.size} elements.")
        hasChanges = false
        updateSaveButtonState(false)
    }

    /**
     * 将当前画布中的布局信息保存到本地，并更新界面
     */
    private fun saveLayoutAndFinish() {
        val layoutToSave = editingCanvas.getLayout()
        LayoutManager.saveLayout(this, layoutToSave)
        Log.d(TAG, "Layout saved with ${layoutToSave.elements.size} elements.")
        showStyledSnackbar(binding.root, "布局已保存", Snackbar.LENGTH_SHORT).show()
        hasChanges = false
        updateSaveButtonState(false)
    }

    /**
     * 当用户点击返回按钮时，如有未保存更改，先询问是否保存
     */
    private fun confirmDiscardOrFinish() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(this)
                .setTitle("要保存更改吗？")
                .setMessage("你的布局已经修改过了，离开前要保存一下吗？")
                .setPositiveButton("保存并退出") { _, _ ->
                    saveLayoutAndFinish()
                    finish()
                }
                .setNegativeButton("不保存退出") { _, _ -> finish() }
                .setNeutralButton("继续编辑", null)
                .show()
        } else {
            finish()
        }
    }

    /**
     * 底部弹窗：添加新元素
     */
    private fun showAddElementBottomSheet() {
        val bottomSheet = AddElementBottomSheetDialogFragment().apply {
            listener = { selectedInfo ->
                addElementToLayout(selectedInfo.type)
            }
        }
        bottomSheet.show(supportFragmentManager, AddElementBottomSheetDialogFragment.TAG)
    }

    /**
     * 将新元素加入到当前布局，
     * 默认居中放置，并在 Y=100dp 处
     */
    private fun addElementToLayout(elementType: String) {
        val elementInfo = AvailableElements.findByType(elementType) ?: return

        val displayMetrics = resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val defaultX = (screenWidthDp / 2f) - (elementInfo.defaultWidthDp / 2f)
        val defaultY = 100f

        val newElement = OverlayElement(
            id = "element_${UUID.randomUUID()}",
            type = elementInfo.type,
            label = elementInfo.defaultLabel,
            width = elementInfo.defaultWidthDp,
            height = elementInfo.defaultHeightDp,
            x = defaultX.coerceAtLeast(0f),
            y = defaultY.coerceAtLeast(0f)
        )

        val currentLayout = editingCanvas.getLayout()
        val updatedElements = currentLayout.elements + newElement
        val newLayout = currentLayout.copy(elements = updatedElements)

        editingCanvas.setLayout(newLayout)
        onLayoutChanged(newLayout)
        Log.d(TAG, "Added element: ${newElement.id} of type ${newElement.type}")
    }

    /**
     * 删除已选中的元素，如该元素并非必需
     */
    private fun deleteSelectedElement() {
        val selectedId = editingCanvas.getSelectedElementId() ?: return
        val currentLayout = editingCanvas.getLayout()
        val elementToRemove = currentLayout.elements.find { it.id == selectedId }
        val indexToRemove = currentLayout.elements.indexOf(elementToRemove)

        if (elementToRemove != null && indexToRemove != -1) {
            val elementInfo = AvailableElements.findByType(elementToRemove.type)
            // 某些必需元素不允许删除
            if (elementInfo?.isMandatory == true) {
                showStyledSnackbar(binding.root, "此元素为必需项，无法删除", Snackbar.LENGTH_SHORT).show()
                Log.w(TAG, "Attempted to delete mandatory element: $selectedId")
                return
            }

            // 记录已删元素，用于"撤销"操作
            recentlyDeletedElement = elementToRemove
            recentlyDeletedIndex = indexToRemove

            val updatedElements = currentLayout.elements.filterNot { it.id == selectedId }
            val newLayout = currentLayout.copy(elements = updatedElements)
            editingCanvas.setLayout(newLayout)
            Log.d(TAG, "Deleted element: $selectedId at index $indexToRemove")
            onLayoutChanged(newLayout)

            showUndoDeleteSnackbar()
        } else {
            Log.w(TAG, "Attempted to delete element $selectedId, but not found.")
            clearUndoState()
        }
    }

    /**
     * 显示可"撤销删除"的 Snackbar
     */
    private fun showUndoDeleteSnackbar() {
        val snackbar = showStyledSnackbar(
            binding.root,
            "元素已删除",
            Snackbar.LENGTH_LONG,
            "撤销"
        ) {
            undoDelete()
        }
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                // 用户未点击"撤销"时，彻底清空记录
                if (event != DISMISS_EVENT_ACTION) {
                    clearUndoState()
                }
                super.onDismissed(transientBottomBar, event)
            }
        })
        snackbar.show()
    }

    /**
     * 执行"撤销删除"
     */
    private fun undoDelete() {
        if (recentlyDeletedElement != null && recentlyDeletedIndex != -1) {
            val currentLayout = editingCanvas.getLayout()
            val restoredElements = currentLayout.elements.toMutableList()

            // 若记录中位置有效，则将元素插回原索引处
            if (recentlyDeletedIndex <= restoredElements.size) {
                restoredElements.add(recentlyDeletedIndex, recentlyDeletedElement!!)
                val restoredLayout = currentLayout.copy(elements = restoredElements)
                editingCanvas.setLayout(restoredLayout)
                onLayoutChanged(restoredLayout)
                Log.d(TAG, "Undo delete for element: ${recentlyDeletedElement!!.id}")
                clearUndoState()
            } else {
                Log.e(TAG, "Undo failed: Invalid index $recentlyDeletedIndex for list size ${restoredElements.size}")
            }
        } else {
            Log.w(TAG, "Undo called but no recently deleted element found.")
        }
    }

    /**
     * 删除或撤销后，将临时记录清空
     */
    private fun clearUndoState() {
        recentlyDeletedElement = null
        recentlyDeletedIndex = -1
        Log.d(TAG, "Cleared undo state.")
    }

    // ------------------------------
    // EditingCanvasListener 回调
    // ------------------------------

    /**
     * 当布局发生改变时（增删或移动元素），记录并更新按钮状态
     */
    override fun onLayoutChanged(newLayout: OverlayLayout) {
        Log.d(TAG, "Layout changed notification received.")
        hasChanges = true
        updateSaveButtonState(true)
        // 可能需要更新其他依赖布局状态的 UI
    }

    /**
     * 当元素选中状态变化时，更新删除按钮可见性，并动画显示/隐藏控制面板
     */
    override fun onSelectionChanged(isSelected: Boolean) {
        Log.d(TAG, "Selection changed notification received: $isSelected")
        updateDeleteButtonState(isSelected)
        
        TransitionManager.beginDelayedTransition(binding.root, Slide(Gravity.BOTTOM).addTarget(binding.sliderPanel).setDuration(300)) 
        binding.sliderPanel.visibility = if (isSelected) View.VISIBLE else View.GONE
        
        if (isSelected) {
            editingCanvas.getSelectedElementId()?.let { id ->
                editingCanvas.getLayout().elements.find { it.id == id }?.let { element ->
                    // 设置面板内 Alpha Slider 初始值
                    binding.sliderAlphaPanel.value = element.alpha
                    // 设置面板内 Size Slider 初始值
                    AvailableElements.findByType(element.type)?.let { defaultInfo ->
                        if (defaultInfo.defaultWidthDp > 0) { 
                            val currentScale = element.width / defaultInfo.defaultWidthDp
                            binding.sliderSizePanel.value = currentScale.coerceIn(binding.sliderSizePanel.valueFrom, binding.sliderSizePanel.valueTo)
                        } else {
                            binding.sliderSizePanel.value = 1.0f
                        }
                    }
                }
            }
        }
    }

    override fun onElementAlphaChanged(elementId: String, newAlpha: Float) {
        val currentLayout = editingCanvas.getLayout()
        val index = currentLayout.elements.indexOfFirst { it.id == elementId }
        if (index != -1) {
            val updatedElement = currentLayout.elements[index].copy(alpha = newAlpha)
            val updatedElements = currentLayout.elements.toMutableList()
            updatedElements[index] = updatedElement
            val newLayout = currentLayout.copy(elements = updatedElements)
            // 直接更新画布内部的布局数据
            editingCanvas.setLayoutDataOnly(newLayout)
            Log.d(TAG, "Element $elementId alpha changed to $newAlpha")
            onLayoutChanged(newLayout) // 触发布局变化逻辑
            editingCanvas.invalidate() // 请求重绘以显示透明度变化
        } else {
            Log.w(TAG, "onElementAlphaChanged: Element $elementId not found")
        }
    }

    // ------------------------------
    // UI 状态更新
    // ------------------------------

    private fun updateSaveButtonState(enabled: Boolean) {
        binding.editorToolbar.menu.findItem(R.id.action_save_layout)?.isEnabled = enabled
        binding.buttonSaveLayout.isEnabled = enabled
        // 可以在这里添加视觉提示，例如改变按钮颜色或透明度
    }

    private fun updateDeleteButtonState(visible: Boolean) {
        deleteMenuItem?.isVisible = visible
    }

    // ------------------------------
    // Activity 交互
    // ------------------------------

    /**
     * 监听系统返回键，若有修改提示是否保存
     */
    override fun onBackPressed() {
        confirmDiscardOrFinish()
    }

    /**
     * 切换底部按钮与 FAB 的可见性
     */
    private fun toggleBottomUiVisibility() {
        isBottomUiVisible = !isBottomUiVisible
        binding.bottomButtonsContainer.visibility = if (isBottomUiVisible) View.VISIBLE else View.GONE
        val newIconRes = if (isBottomUiVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        toggleUiMenuItem?.setIcon(newIconRes)
        Log.d(TAG, "Toggled bottom UI visibility to: $isBottomUiVisible")
    }

    /**
     * 切换悬浮窗预览状态 (启动或停止)
     */
    private fun toggleOverlayPreview() {
        Log.d(TAG, "Toggle preview button clicked.")
        // 1. 检查权限
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted. Requesting permission.")
            requestOverlayPermission()
            return // 等待权限结果
        }
        
        // 2. 检查服务当前状态
        val serviceIsRunning = isServiceRunning(RuntimeOverlayService::class.java)
        
        if (serviceIsRunning) {
            // --- 如果服务正在运行：停止服务 --- 
            Log.d(TAG, "Service is running. Stopping RuntimeOverlayService...")
            stopService(Intent(this, RuntimeOverlayService::class.java))
            showStyledSnackbar(binding.root, "悬浮窗预览已停止", Snackbar.LENGTH_SHORT).show()
        } else {
            // --- 如果服务未运行：保存并启动服务 ---
            // a. 保存当前布局 (确保预览的是最新状态)
            val layoutToSave = editingCanvas.getLayout()
            LayoutManager.saveLayout(this, layoutToSave)
            Log.d(TAG, "Layout saved for preview with ${layoutToSave.elements.size} elements.")
            // b. 启动新服务以显示预览
            Log.d(TAG, "Service is not running. Starting RuntimeOverlayService for preview...")
            val intent = Intent(this, RuntimeOverlayService::class.java)
            startService(intent)
            showStyledSnackbar(binding.root, "悬浮窗预览已启动", Snackbar.LENGTH_SHORT).show()
        }
        
        // 3. 更新按钮图标
        updatePreviewButtonState(!serviceIsRunning) // 切换到相反状态
    }
    
    /**
     * 更新预览按钮的图标状态
     */
     private fun updatePreviewButtonState(isRunning: Boolean? = null) {
         val currentlyRunning = isRunning ?: isServiceRunning(RuntimeOverlayService::class.java)
         val iconRes = if (currentlyRunning) R.drawable.ic_visibility_off else R.drawable.ic_visibility
         previewMenuItem?.setIcon(iconRes)
         previewMenuItem?.title = if (currentlyRunning) "停止预览" else "预览"
         Log.d(TAG, "Preview button state updated. Running: $currentlyRunning")
     }

    /**
     * 检查指定的服务是否正在运行。
     * 注意：在 Android O 及以上版本有后台限制，可能不总是可靠。
     */
    @Suppress("DEPRECATION") // getRunningServices is deprecated but needed for older APIs potentially
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            // 使用 getAppTasks 或其他更现代的方法可能更好，但 getRunningServices 简单直接
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    Log.d(TAG, "Service ${serviceClass.simpleName} is running.")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking running services", e)
            // 发生异常时保守返回 false
        }
        Log.d(TAG, "Service ${serviceClass.simpleName} is not running.")
        return false
    }

    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
         MaterialAlertDialogBuilder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("为了预览悬浮窗效果，需要授予应用悬浮窗权限。请在接下来的系统设置页面中开启权限。")
            .setPositiveButton("前往设置") { _, _ ->
                 val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
                 startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    // 处理权限请求结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission granted after request.")
                // 权限获取成功，再次尝试切换预览
                toggleOverlayPreview()
            } else {
                Log.w(TAG, "Overlay permission denied after request.")
                showStyledSnackbar(binding.root, "未授予悬浮窗权限，无法预览", Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
