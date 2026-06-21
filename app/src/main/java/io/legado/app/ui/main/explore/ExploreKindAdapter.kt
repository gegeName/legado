package io.legado.app.ui.main.explore

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewGroup
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.utils.dpToPx

class ExploreKindAdapter(
    context: Context,
    private val callback: Callback
) : RecyclerAdapter<ExploreKind, ItemFilletTextBinding>(context) {

    private var selectedUrl: String? = null

    override fun getViewBinding(parent: ViewGroup): ItemFilletTextBinding {
        return ItemFilletTextBinding.inflate(inflater, parent, false).apply {
            root.setBackgroundResource(R.drawable.selector_explore_kind_bg)
            root.setTextColor(context.getColorStateList(R.color.selector_explore_kind_text))
            root.layoutParams = FlexboxLayoutManager.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .apply {
                    val margin = 3.dpToPx()
                    setMargins(margin, margin, margin, margin)
                }
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemFilletTextBinding,
        item: ExploreKind,
        payloads: MutableList<Any>
    ) {
        val isGroup = item.url.isNullOrBlank()
        (binding.root.layoutParams as? FlexboxLayoutManager.LayoutParams)?.isWrapBefore =
            isGroup && holder.layoutPosition > 0
        binding.root.text = item.title
        binding.root.isSelected = !isGroup && item.url == selectedUrl
        binding.root.isEnabled = !isGroup
        if (isGroup) {
            binding.root.setBackgroundResource(R.drawable.shape_explore_kind_group)
            binding.root.setTextColor(context.getColor(R.color.accent))
            binding.root.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        } else {
            binding.root.setBackgroundResource(R.drawable.selector_explore_kind_bg)
            binding.root.setTextColor(context.getColorStateList(R.color.selector_explore_kind_text))
            binding.root.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemFilletTextBinding) {
        binding.root.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let {
                if (it.url.isNullOrBlank()) return@let
                callback.selectKind(it)
            }
        }
    }

    fun setSelected(kind: ExploreKind?) {
        selectedUrl = kind?.url
        notifyItemRangeChanged(0, itemCount, "selected")
    }

    interface Callback {
        fun selectKind(kind: ExploreKind)
    }

}
