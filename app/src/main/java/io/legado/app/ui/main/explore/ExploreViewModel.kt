package io.legado.app.ui.main.explore

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModel(application: Application) : BaseViewModel(application) {
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val sourcesData = MutableLiveData<List<BookSourcePart>>()
    val selectedSourceData = MutableLiveData<BookSourcePart?>()
    val kindsData = MutableLiveData<List<ExploreKind>>()
    val selectedKindData = MutableLiveData<ExploreKind?>()
    val booksData = MutableLiveData<List<SearchBook>>()
    val loadingData = MutableLiveData<Boolean>()
    val errorLiveData = MutableLiveData<String>()
    private val books = linkedSetOf<SearchBook>()
    private var sources = emptyList<BookSourcePart>()
    private var selectedSource: BookSourcePart? = null
    private var selectedKind: ExploreKind? = null
    private var page = 1

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
                AppLog.put("发现界面获取书架数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    fun setSources(newSources: List<BookSourcePart>) {
        sources = newSources
        sourcesData.value = newSources
        val current = selectedSource
        val source = current?.let { old ->
            newSources.firstOrNull { it.bookSourceUrl == old.bookSourceUrl }
        } ?: LocalConfig.lastExploreSourceUrl?.let { url ->
            newSources.firstOrNull { it.bookSourceUrl == url }
        } ?: newSources.firstOrNull()
        if (source?.bookSourceUrl != current?.bookSourceUrl) {
            selectSource(source)
        }
    }

    fun selectSource(source: BookSourcePart?) {
        selectedSource = source
        source?.bookSourceUrl?.let {
            LocalConfig.lastExploreSourceUrl = it
        }
        selectedSourceData.value = source
        selectedKind = null
        kindsData.value = emptyList()
        selectedKindData.value = null
        resetBooks()
        source ?: return
        execute {
            source.exploreKinds()
        }.onSuccess {
            kindsData.value = it
            selectKind(it.firstOrNull()?.takeIf { kind -> !kind.url.isNullOrBlank() }
                ?: it.firstOrNull { kind -> !kind.url.isNullOrBlank() })
        }.onError {
            errorLiveData.value = it.stackTraceStr
            AppLog.put("发现界面获取分类失败\n${it.localizedMessage}", it)
        }
    }

    fun selectKind(kind: ExploreKind?) {
        selectedKind = kind
        selectedKindData.value = kind
        resetBooks()
        explore()
    }

    private fun resetBooks() {
        page = 1
        books.clear()
        booksData.value = emptyList()
    }

    fun explore(forceLoad: Boolean = false) {
        val sourcePart = selectedSource ?: return
        val url = selectedKind?.url ?: return
        val source = sourcePart.getBookSource() ?: return
        if (loadingData.value == true && !forceLoad) return
        WebBook.exploreBook(viewModelScope, source, url, page)
            .timeout(if (BuildConfig.DEBUG) 0L else 30000L)
            .onStart {
                loadingData.value = true
            }
            .onSuccess(IO) { searchBooks ->
                books.addAll(searchBooks)
                booksData.postValue(books.toList())
                appDb.searchBookDao.insert(*searchBooks.toTypedArray())
                page++
            }.onError {
                it.printOnDebug()
                errorLiveData.value = it.stackTraceStr
            }.onFinally {
                loadingData.value = false
            }
    }

    fun topSource(bookSource: BookSourcePart) {
        execute {
            val minXh = appDb.bookSourceDao.minOrder
            bookSource.customOrder = minXh - 1
            appDb.bookSourceDao.upOrder(bookSource)
        }
    }

    fun deleteSource(source: BookSourcePart) {
        execute {
            SourceHelp.deleteBookSource(source.bookSourceUrl)
        }
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

}
