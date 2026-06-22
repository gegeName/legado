package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.views.onLongClick

class BooksAdapterGrid(context: Context, private val callBack: CallBack) :
    BaseBooksAdapter<ItemBookshelfGridBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfGridBinding {
        return ItemBookshelfGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfGridBinding,
        item: Book,
        payloads: MutableList<Any>
    ) = binding.run {
        if (payloads.isEmpty()) {
            tvName.text = item.name
            ivCover.load(item.getDisplayCover(), item.name, item.author, false, item.origin)
            upRefresh(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> tvName.text = item.name
                        "cover" -> ivCover.load(item.getDisplayCover(), item.name, item.author, false, item.origin)
                        "refresh" -> upRefresh(binding, item)
                    }
                }
            }
        }
    }

    private fun upRefresh(binding: ItemBookshelfGridBinding, item: Book) {
        binding.bvUnread.bringToFront()
        binding.rlLoading.bringToFront()
        if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
            binding.rlLoading.visible()
        } else {
            binding.rlLoading.invisible()
        }
        if (AppConfig.showUnread) {
            binding.bvUnread.setBadgeCount(item.getUnreadBadgeCount())
            binding.bvUnread.setUnreadGridStyle()
        } else {
            binding.bvUnread.invisible()
        }
    }

    private fun BadgeView.setUnreadGridStyle() {
        val iconSize = 12.dp
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_refresh_black_24dp)
            ?.mutate()
            ?.let {
                DrawableCompat.setTint(it, Color.parseColor("#33383D"))
                it.setBounds(0, 0, iconSize, iconSize)
                it
        }
        setBackground(8f, Color.parseColor("#D8DADF"))
        setTextColor(Color.parseColor("#30343A"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        minWidth = 44.dp
        minHeight = 20.dp
        setPadding(5.dp, 1.dp, 6.dp, 1.dp)
        compoundDrawablePadding = 2.dp
        setCompoundDrawables(icon, null, null, null)
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density + 0.5f).toInt()

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfGridBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.open(it)
                }
            }

            onLongClick {
                getItem(holder.layoutPosition)?.let {
                    callBack.openBookInfo(it)
                }
            }
        }
    }
}
