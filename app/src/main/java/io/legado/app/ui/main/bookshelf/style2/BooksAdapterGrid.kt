package io.legado.app.ui.main.bookshelf.style2

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.databinding.ItemBookshelfGridGroupBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.views.onLongClick

@Suppress("UNUSED_PARAMETER")
class BooksAdapterGrid(context: Context, callBack: CallBack) :
    BaseBooksAdapter<RecyclerView.ViewHolder>(context, callBack) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> GroupViewHolder(ItemBookshelfGridGroupBinding.inflate(inflater, parent, false))
            else -> BookViewHolder(ItemBookshelfGridBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        when (holder) {
            is BookViewHolder -> (getItem(position) as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }

            is GroupViewHolder -> (getItem(position) as? BookGroup)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }
        }
    }

    inner class BookViewHolder(val binding: ItemBookshelfGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book, position: Int) = binding.run {
            tvName.text = item.name
            ivCover.load(item.getDisplayCover(), item.name, item.author, false, item.origin)
            upRefresh(this, item)
        }

        fun onBind(item: Book, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvName.text = item.name
                            "cover" -> ivCover.load(
                                item.getDisplayCover(),
                                item.name,
                                item.author,
                                false,
                                item.origin
                            )

                            "refresh" -> upRefresh(this, item)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
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

    }

    inner class GroupViewHolder(val binding: ItemBookshelfGridGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookGroup, position: Int) = binding.run {
            tvName.text = item.groupName
            ivCover.load(item.cover)
        }

        fun onBind(item: BookGroup, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "groupName" -> tvName.text = item.groupName
                            "cover" -> ivCover.load(item.cover)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

    }

}
