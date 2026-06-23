package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchPage = 1
    private var searchKey: String = ""
    private var bookSourceParts = emptyList<BookSourcePart>()
    private var searchBooks = arrayListOf<SearchBook>()
    private var searchJob: Job? = null
    private var workingState = MutableStateFlow(true)


    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun search(searchId: Long, key: String) {
        if (searchId != mSearchId) {
            if (key.isEmpty()) {
                return
            }
            searchKey = key
            if (mSearchId != 0L) {
                close()
            }
            searchBooks.clear()
            bookSourceParts = callBack.getSearchScope().getBookSourceParts()
            if (bookSourceParts.isEmpty()) {
                callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                return
            }
            mSearchId = searchId
            searchPage = 1
            initSearchPool()
        } else {
            searchPage++
        }
        startSearch()
    }

    private fun startSearch() {
        val searchId = mSearchId
        val key = searchKey
        val page = searchPage
        val sourceParts = bookSourceParts
        val precision = appCtx.getPrefBoolean(PreferKey.precisionSearch)
        var hasMore = false
        searchJob = scope.launch(searchPool!!) {
            flow {
                for (bs in sourceParts) {
                    bs.getBookSource()?.let {
                        emit(it)
                    }
                    workingState.first { it }
                }
            }.onStart {
                if (searchId == mSearchId) {
                    callBack.onSearchStart()
                }
            }.mapParallelSafe(threadCount) {
                withTimeout(30000L) {
                    WebBook.searchBookAwait(
                        it, key, page,
                        filter = { name, author ->
                            !precision || name.contains(key) ||
                                    author.contains(key)
                        })
                }
            }.onEach { items ->
                if (searchId == mSearchId) {
                    for (book in items) {
                        book.releaseHtmlData()
                    }
                    hasMore = hasMore || items.isNotEmpty()
                    appDb.searchBookDao.insert(*items.toTypedArray())
                    mergeItems(items, precision, key)
                    currentCoroutineContext().ensureActive()
                    callBack.onSearchSuccess(searchBooks)
                }
            }.onCompletion {
                if (it == null && searchId == mSearchId) {
                    callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
                }
            }.catch {
                AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
                if (searchId == mSearchId) {
                    callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
                }
            }.collect()
        }
    }

    private suspend fun mergeItems(newDataS: List<SearchBook>, precision: Boolean, key: String) {
        if (newDataS.isNotEmpty()) {
            val merged = LinkedHashMap<String, SearchBook>(searchBooks.size + newDataS.size)
            fun SearchBook.mergeKey(): String = "$name\u0000$author"
            fun SearchBook.copyForMerge(): SearchBook {
                return copy().also { book ->
                    origins.forEach { book.addOrigin(it) }
                }
            }
            fun putOrMerge(book: SearchBook) {
                val oldBook = merged[book.mergeKey()]
                if (oldBook == null) {
                    merged[book.mergeKey()] = book.copyForMerge()
                } else {
                    book.origins.forEach { oldBook.addOrigin(it) }
                }
            }

            searchBooks.forEach {
                coroutineContext.ensureActive()
                putOrMerge(it)
            }
            newDataS.forEach {
                coroutineContext.ensureActive()
                if (!precision
                    || it.name == key
                    || it.author == key
                    || it.name.contains(key)
                    || it.author.contains(key)
                ) {
                    putOrMerge(it)
                }
            }

            coroutineContext.ensureActive()
            val equalData = ArrayList<SearchBook>()
            val containsData = ArrayList<SearchBook>()
            val otherData = ArrayList<SearchBook>()
            merged.values.forEach {
                coroutineContext.ensureActive()
                if (it.name == key || it.author == key) {
                    equalData.add(it)
                } else if (it.name.contains(key) || it.author.contains(key)) {
                    containsData.add(it)
                } else if (!precision) {
                    otherData.add(it)
                }
            }

            equalData.sortByDescending { it.origins.size }
            containsData.sortByDescending { it.origins.size }
            val result = ArrayList<SearchBook>(merged.size)
            result.addAll(equalData)
            result.addAll(containsData)
            if (!precision) {
                result.addAll(otherData)
            }
            coroutineContext.ensureActive()
            searchBooks = result
        }
    }

    fun pause() {
        workingState.value = false
    }

    fun resume() {
        workingState.value = true
    }

    fun cancelSearch() {
        close()
        callBack.onSearchCancel()
    }

    fun close() {
        searchJob?.cancel()
        searchPool?.close()
        searchPool = null
        mSearchId = 0L
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart()
        fun onSearchSuccess(searchBooks: List<SearchBook>)
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
    }

}
