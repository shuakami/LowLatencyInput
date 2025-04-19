package com.luoxiaohei.lowlatencyinput.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.luoxiaohei.lowlatencyinput.databinding.FragmentAddElementBottomSheetBinding
import com.luoxiaohei.lowlatencyinput.model.AvailableElementInfo
import com.luoxiaohei.lowlatencyinput.model.AvailableElements
import com.luoxiaohei.lowlatencyinput.ui.adapter.AddElementAdapter

/**
 * 通过 BottomSheet 的形式展示可用元素列表，供用户选择并添加到画布。
 */
class AddElementBottomSheetDialogFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentAddElementBottomSheetBinding? = null
    private val binding get() = _binding!!

    /**
     * 供 Activity 调用时，用于接收选中的元素类型的回调
     */
    var listener: ((AvailableElementInfo) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, 
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddElementBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 初始化 RecyclerView 并将所有可用元素列表显示出来，点击后触发回调
     * 设置 BottomSheetDialog 显示时默认展开
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = AddElementAdapter(AvailableElements.all) { selectedInfo ->
            // 将选中的元素类型回传给调用者
            listener?.invoke(selectedInfo)
            dismiss() // 选择后收起 BottomSheet
        }

        binding.availableElementsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.availableElementsRecyclerView.adapter = adapter

        // 当 BottomSheet 显示时尝试展开到全屏
        dialog?.setOnShowListener { dialog ->
            val bottomSheetDialog = dialog as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) as? FrameLayout
            bottomSheet?.let {
                BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 避免视图泄漏
        _binding = null
    }

    companion object {
        const val TAG = "AddElementBottomSheet"
    }
}
