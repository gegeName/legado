package io.legado.app.ui.book.manga

import androidx.recyclerview.widget.RecyclerView
import io.legado.app.model.ReadManga
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.MangaContent
import io.legado.app.ui.book.manga.recyclerview.MangaAdapter
import io.legado.app.ui.book.manga.recyclerview.MangaLayoutManager
import io.legado.app.ui.book.manga.recyclerview.WebtoonRecyclerView
import io.legado.app.utils.canScroll

/**
 * 漫画翻章控制器，集中管理上滑加载下一章 / 下滑加载上一章的状态与滚动联动。
 */
class MangaFlipController(
    private val recyclerView: WebtoonRecyclerView,
    private val layoutManager: MangaLayoutManager,
    private val adapter: MangaAdapter,
    private val callback: Callback,
) {

    interface Callback {
        fun isHorizontal(): Boolean
        fun isLoadingViewVisible(): Boolean
        fun updateProgress(page: MangaPage)
    }

    private enum class State { IDLE, WAITING_CONTENT }

    private var state = State.IDLE
    private var pendingChapterIndex: Int? = null

    fun reset() {
        state = State.IDLE
        pendingChapterIndex = null
    }

    private fun isLocked(): Boolean {
        return state == State.WAITING_CONTENT || callback.isLoadingViewVisible()
    }

    /**
     * 滚动中回调，[centerPosition] 为屏幕中心 item 的 adapter position。
     */
    fun onScrolled(dx: Int, dy: Int, centerPosition: Int) {
        val direction = scrollDirection(dx, dy)
        if (centerPosition == RecyclerView.NO_POSITION) {
            return
        }
        val item = adapter.getItem(centerPosition) as? MangaPage ?: return
        if (item.chapterIndex != ReadManga.durChapterIndex && !isLocked()) {
            if (item.chapterIndex > ReadManga.durChapterIndex) {
                ReadManga.moveToNextChapter(silent = true)
            } else {
                ReadManga.moveToPrevChapter(silent = true)
            }
        }
        ReadManga.durChapterPos = item.index
        callback.updateProgress(item)
        if (direction != 0 && !isLocked()) {
            tryLoadFartherChapter(direction)
        }
    }

    fun onNestedPreScroll(dx: Int, dy: Int) {
        if (isLocked()) return
        val direction = scrollDirection(dx, dy)
        if (direction == 0) return
        tryLoadFartherChapter(direction)
    }

    fun onScrollStateChanged(newState: Int) {
        if (newState != RecyclerView.SCROLL_STATE_IDLE) return
        if (isLocked()) return
        when {
            !recyclerView.canScroll(1) -> flipPage(1)
            !recyclerView.canScroll(-1) -> flipPage(-1)
        }
    }

    /**
     * 列表滑到物理边缘时，若相邻章未加载则通过 [flipPage] 推进加载。
     * 复用 flipPage 的边缘守卫（要求边缘页属于当前章），避免 durChapterIndex
     * 超前于列表内容导致的连锁跳章。
     */
    private fun tryLoadFartherChapter(direction: Int) {
        if (recyclerView.canScroll(direction)) {
            return
        }
        flipPage(direction)
    }

    /**
     * 横向翻页 / 音量键翻页，返回 true 表示已处理切章，调用方不再滚动。
     */
    fun flipPage(direction: Int): Boolean {
        if (isLocked() || direction == 0) {
            return false
        }
        val edgePage = findCurrentChapterEdgePage(direction) ?: return false
        val page = edgePage.page
        if (page.chapterIndex != ReadManga.durChapterIndex || page.imageCount <= 0) {
            return false
        }
        return if (direction > 0) {
            if (page.index != page.imageCount - 1 || !isPageFullyAtEdge(edgePage, direction)) {
                false
            } else {
                moveAndWait(direction, ReadManga.moveToNextChapter(toFirst = true))
            }
        } else {
            if (page.index != 0 || !isPageFullyAtEdge(edgePage, direction)) {
                false
            } else {
                moveAndWait(direction, ReadManga.moveToPrevChapter(toLast = true))
            }
        }
    }

    private fun moveAndWait(direction: Int, moved: Boolean): Boolean {
        if (moved) {
            state = State.WAITING_CONTENT
            pendingChapterIndex = ReadManga.durChapterIndex
        }
        return moved
    }

    /**
     * 章节内容就绪后由 upContent 回调，处理定位与进度更新。
     */
    fun onContentReady(content: MangaContent) {
        val pending = pendingChapterIndex
        if (state == State.WAITING_CONTENT && content.curFinish &&
            pending == ReadManga.durChapterIndex
        ) {
            recyclerView.stopScroll()
            layoutManager.scrollToPositionWithOffset(content.pos, 0)
            (content.items.getOrNull(content.pos) as? MangaPage)?.let {
                callback.updateProgress(it)
            }
            state = State.IDLE
            pendingChapterIndex = null
        }
    }

    fun onLoadFail() {
        state = State.IDLE
        pendingChapterIndex = null
    }

    private fun scrollDirection(dx: Int, dy: Int): Int {
        return if (callback.isHorizontal()) dx.compareTo(0) else dy.compareTo(0)
    }

    private fun findCurrentChapterEdgePage(direction: Int): EdgeMangaPage? {
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        val lastPosition = layoutManager.findLastVisibleItemPosition()
        if (firstPosition == RecyclerView.NO_POSITION || lastPosition == RecyclerView.NO_POSITION) {
            return findCurrentChapterPageAtRecyclerEdge(direction)
        }
        val positions = if (direction > 0) {
            lastPosition downTo firstPosition
        } else {
            firstPosition..lastPosition
        }
        for (position in positions) {
            val page = adapter.getItem(position) as? MangaPage ?: continue
            if (page.chapterIndex == ReadManga.durChapterIndex) {
                return EdgeMangaPage(position, page, true)
            }
        }
        return findCurrentChapterPageAtRecyclerEdge(direction)
    }

    private fun findCurrentChapterPageAtRecyclerEdge(direction: Int): EdgeMangaPage? {
        if (recyclerView.canScroll(direction)) {
            return null
        }
        val items = adapter.getItems()
        val positions = if (direction > 0) {
            items.indices.reversed()
        } else {
            items.indices
        }
        for (position in positions) {
            val page = items[position] as? MangaPage ?: continue
            if (page.chapterIndex == ReadManga.durChapterIndex) {
                return EdgeMangaPage(position, page, false)
            }
        }
        return null
    }

    private fun isPageFullyAtEdge(edgePage: EdgeMangaPage, direction: Int): Boolean {
        if (!edgePage.requireVisibleEdge) {
            return true
        }
        val view = layoutManager.findViewByPosition(edgePage.position) ?: return false
        return if (callback.isHorizontal()) {
            if (direction > 0) {
                view.right <= recyclerView.width - recyclerView.paddingEnd
            } else {
                view.left >= recyclerView.paddingStart
            }
        } else {
            if (direction > 0) {
                view.bottom <= recyclerView.height - recyclerView.paddingBottom
            } else {
                view.top >= recyclerView.paddingTop
            }
        }
    }

    private data class EdgeMangaPage(
        val position: Int,
        val page: MangaPage,
        val requireVisibleEdge: Boolean
    )
}
