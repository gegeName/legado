package io.legado.app.ui.main.explore

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.Item1lineTextBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class ExploreSourceSelectDialog(
    private val sources: List<BookSourcePart>,
    private val selectedSourceUrl: String?,
    private val onSelect: (BookSourcePart) -> Unit
) : BaseDialogFragment(R.layout.dialog_recycler_view) {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { Adapter(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.discovery)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        adapter.setItems(sources)
        val selectedIndex = sources.indexOfFirst { it.bookSourceUrl == selectedSourceUrl }
        if (selectedIndex >= 0) {
            binding.recyclerView.post {
                (binding.recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(selectedIndex, 0)
            }
        }
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<BookSourcePart, Item1lineTextBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): Item1lineTextBinding {
            return Item1lineTextBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: Item1lineTextBinding,
            item: BookSourcePart,
            payloads: MutableList<Any>
        ) {
            binding.textView.text = if (item.bookSourceUrl == selectedSourceUrl) {
                "✓ ${item.bookSourceName}"
            } else {
                item.bookSourceName
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: Item1lineTextBinding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    onSelect.invoke(it)
                    dismissAllowingStateLoss()
                }
            }
        }

    }

}
