package com.luoxiaohei.lowlatencyinput.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.luoxiaohei.lowlatencyinput.databinding.ListItemAvailableElementBinding
import com.luoxiaohei.lowlatencyinput.R
import com.luoxiaohei.lowlatencyinput.model.AvailableElementInfo

/**
 * 用于展示可添加的元素列表，点击后回调 onItemClick。
 */
class AddElementAdapter(
    private val items: List<AvailableElementInfo>,
    private val onItemClick: (AvailableElementInfo) -> Unit
) : RecyclerView.Adapter<AddElementAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ListItemAvailableElementBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    /**
     * ViewHolder 使用 ViewBinding 进行视图绑定
     */
    inner class ViewHolder(private val binding: ListItemAvailableElementBinding)
        : RecyclerView.ViewHolder(binding.root) {

        init {
            // 根布局点击时，触发添加
            binding.root.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onItemClick(items[adapterPosition])
                }
            }
        }

        /**
         * 绑定数据并显示到 UI
         */
        fun bind(item: AvailableElementInfo) {
            binding.elementLabel.text = item.defaultLabel
            
            if (item.iconResId != null) {
                // 如果有自定义图标资源 ID，加载它并移除 tint
                binding.elementIcon.setImageResource(item.iconResId)
                binding.elementIcon.imageTintList = null // 移除 tint 以显示原始图标或 Drawable
                binding.elementIcon.visibility = android.view.View.VISIBLE
            } else {
                // 如果没有自定义图标，显示占位符图标（保持 tint）
            binding.elementIcon.setImageResource(R.drawable.ic_placeholder)
                // 确保 tint 仍然应用 (如果需要的话，或者依赖 XML)
                // binding.elementIcon.imageTintList = ContextCompat.getColorStateList(binding.root.context, R.color.your_tint_color) 
                binding.elementIcon.visibility = android.view.View.VISIBLE // 统一都显示图标
            }
        }
    }
}
