package io.legado.app.ui.book.search

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.SearchModel
import io.legado.app.utils.ConflateLiveData
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(application: Application) : BaseViewModel(application) {
    val handler = Handler(Looper.getMainLooper())
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<String>()
    var searchBookLiveData = ConflateLiveData<List<SearchBook>>(1000)
    val searchScope: SearchScope = SearchScope(AppConfig.searchScope)
    var searchFinishLiveData = MutableLiveData<Boolean>()
    var isSearchLiveData = MutableLiveData<Boolean>()
    var searchKey: String = ""
    var hasMore = true
    private var searchID = 0L
    private var nextSearchID = 0L
    @Volatile
    private var searchRequesting = false
    @Volatile
    private var searchRequestToken = 0L
    private val searchModel = SearchModel(viewModelScope, object : SearchModel.CallBack {

        override fun getSearchScope(): SearchScope {
            return searchScope
        }

        override fun onSearchStart() {
            searchRequesting = true
            isSearchLiveData.postValue(true)
        }

        override fun onSearchSuccess(searchBooks: List<SearchBook>) {
            searchBookLiveData.postValue(searchBooks)
        }

        override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
            this@SearchViewModel.hasMore = hasMore
            searchRequesting = false
            isSearchLiveData.postValue(false)
            searchFinishLiveData.postValue(isEmpty)
        }

        override fun onSearchCancel(exception: Throwable?) {
            searchRequesting = false
            isSearchLiveData.postValue(false)
            exception?.let {
                context.toastOnUi(it.localizedMessage)
            }
        }

    })

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }
                    .forEach {
                        keys.add("${it.name}-${it.author}")
                        keys.add(it.name)
                        keys.add(it.bookUrl)
                    }
                keys
            }.catch {
                AppLog.put("搜索界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue("isInBookshelf")
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

    /**
     * 开始搜索
     */
    fun search(key: String) {
        if (key.isEmpty() && searchKey.isEmpty()) {
            searchRequestToken++
            searchModel.close()
            hasMore = false
            searchRequesting = false
            isSearchLiveData.postValue(false)
            searchBookLiveData.postValue(emptyList())
            return
        }
        val isLoadMore = key.isEmpty()
        if (isLoadMore && searchRequesting) {
            return
        }
        val requestSearchID = if (isLoadMore) searchID else ++nextSearchID
        if (!isLoadMore) {
            searchID = requestSearchID
            searchKey = key
            hasMore = true
            searchBookLiveData.postValue(emptyList())
        }
        val requestToken = ++searchRequestToken
        searchRequesting = true
        execute {
            if (searchRequestToken != requestToken || searchID != requestSearchID) {
                return@execute
            }
            if (!isLoadMore) {
                searchModel.close()
            }
            if (searchKey.isEmpty()) {
                if (searchRequestToken == requestToken && searchID == requestSearchID) {
                    searchRequesting = false
                }
                return@execute
            }
            searchModel.search(requestSearchID, searchKey)
        }.onError {
            if (searchRequestToken == requestToken && searchID == requestSearchID) {
                searchRequesting = false
                isSearchLiveData.postValue(false)
            }
            AppLog.put("搜索启动失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 停止搜索
     */
    fun stop() {
        searchRequestToken++
        if (searchRequesting) {
            searchModel.cancelSearch()
        }
    }

    fun pause() {
        searchModel.pause()
    }

    fun resume() {
        searchModel.resume()
    }

    /**
     * 保存搜索关键字
     */
    fun saveSearchKey(key: String) {
        execute {
            appDb.searchKeywordDao.get(key)?.let {
                it.usage += 1
                it.lastUseTime = System.currentTimeMillis()
                appDb.searchKeywordDao.update(it)
            } ?: appDb.searchKeywordDao.insert(SearchKeyword(key, 1))
        }
    }

    /**
     * 清楚搜索关键字
     */
    fun clearHistory() {
        execute {
            appDb.searchKeywordDao.deleteAll()
        }
    }

    fun deleteHistory(searchKeyword: SearchKeyword) {
        execute {
            appDb.searchKeywordDao.delete(searchKeyword)
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchModel.close()
    }

}
