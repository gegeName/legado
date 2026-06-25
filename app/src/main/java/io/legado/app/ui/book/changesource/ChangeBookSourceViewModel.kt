package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.primaryStr
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.onEachIndexed
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

@Suppress("MemberVisibilityCanBePrivate")
open class ChangeBookSourceViewModel(application: Application) : BaseViewModel(application) {
    private val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    val searchStateData = MutableLiveData<Boolean>()
    var searchFinishCallback: ((isEmpty: Boolean) -> Unit)? = null
    var name: String = ""
    var author: String = ""
    private var origin: String? = null
    private var originSourceType: Int? = null
    private var originGroupApplied = false
    private var fromReadBookActivity = false
    private var oldBook: Book? = null
    @Volatile
    private var screenKey: String = ""
    private var bookSourceParts = arrayListOf<BookSourcePart>()
    val totalSourceCount: Int
        get() = bookSourceParts.size
    private var searchBookList = arrayListOf<SearchBook>()
    private val searchBooksLock = Any()
    private val searchBooks = arrayListOf<SearchBook>()
    private val tocMap = ConcurrentHashMap<String, List<BookChapter>>()
    private val _changeSourceProgress = MutableStateFlow(0 to "")
    val changeSourceProgress = _changeSourceProgress.asStateFlow()
    private var tocMapChapterCount = 0
    private val contentProcessor by lazy {
        ContentProcessor.get(oldBook!!)
    }
    private var searchCallback: SourceCallback? = null
    private val chapterNumRegex = "^\\[(\\d+)]".toRegex()
    private val comparatorBase by lazy {
        compareByDescending<SearchBook> { getBookScore(it) }
            .thenByDescending { SourceConfig.getSourceScore(it.origin) }
    }
    private val defaultComparator by lazy {
        comparatorBase.thenBy { it.originOrder }
    }
    private val wordCountComparator by lazy {
        comparatorBase.thenByDescending { it.chapterWordCount > 1000 }
            .thenByDescending { getChapterNum(it.chapterWordCountText) }
            .thenByDescending { it.chapterWordCount }
            .thenBy { it.originOrder }
    }
    private var task: Job? = null
    private var screenJob: Job? = null
    private val searchGenerationLock = Any()
    @Volatile
    private var searchGeneration = 0L
    val bookMap = ConcurrentHashMap<String, Book>()
    val searchDataFlow = callbackFlow<Unit> {
        var updateJob: Job? = null
        val updateLock = Any()
        fun sendAdapterUpdate(immediate: Boolean = false) {
            var sendImmediately = false
            synchronized(updateLock) {
                if (immediate) {
                    updateJob?.cancel()
                    sendImmediately = true
                } else if (updateJob?.isActive != true) {
                    updateJob = launch {
                        delay(120)
                        trySend(Unit)
                    }
                }
            }
            if (sendImmediately) {
                trySend(Unit)
            }
        }

        searchCallback = object : SourceCallback {

            override fun searchSuccess(searchBook: SearchBook) {
                searchBook.releaseHtmlData()
                appDb.searchBookDao.insert(searchBook)
                if (!searchBook.matchesScreen()) {
                    return
                }
                addOrReplaceSearchBook(searchBook)
                sendAdapterUpdate()
            }

            override fun upAdapter() {
                sendAdapterUpdate(true)
            }

        }

        applyOriginSearchGroup()
        val sourceParts = getCurrentBookSourceParts()
        val dbSearchBooks = getDbSearchBooks()
        replaceSearchBooks(dbSearchBooks)
        sendAdapterUpdate(true)

        if (dbSearchBooks.isEmpty()) {
            startSearch()
        } else {
            val cachedOrigins = dbSearchBooks.asSequence().map { it.origin }.toHashSet()
            val missingSourceParts = sourceParts.filterNot { it.bookSourceUrl in cachedOrigins }
            if (missingSourceParts.isNotEmpty()) {
                startSearchMissing(missingSourceParts)
            }
        }

        awaitClose {
            updateJob?.cancel()
            searchCallback = null
        }
    }.map {
        kotlin.runCatching {
            sortedSearchBooks()
        }.onFailure {
            AppLog.put("换源排序出错\n${it.localizedMessage}", it)
        }.getOrDefault(searchBooksSnapshot())
    }.flowOn(IO)

    override fun onCleared() {
        super.onCleared()
        screenJob?.cancel()
        searchPool?.close()
    }

    private fun searchBooksSnapshot(): List<SearchBook> = synchronized(searchBooksLock) {
        searchBooks.toList()
    }

    private fun replaceSearchBooks(books: List<SearchBook>) = synchronized(searchBooksLock) {
        searchBooks.clear()
        searchBooks.addAll(books)
    }

    private fun clearSearchBooks(): List<SearchBook> = synchronized(searchBooksLock) {
        val oldBooks = searchBooks.toList()
        searchBooks.clear()
        oldBooks
    }

    private fun isSearchBooksEmpty(): Boolean = synchronized(searchBooksLock) {
        searchBooks.isEmpty()
    }

    private fun addOrReplaceSearchBook(searchBook: SearchBook) = synchronized(searchBooksLock) {
        val index = searchBooks.indexOfFirst { it.bookUrl == searchBook.bookUrl }
        if (index >= 0) {
            searchBooks[index] = searchBook
        } else {
            searchBooks.add(searchBook)
        }
    }

    private fun removeSearchBooks(predicate: (SearchBook) -> Boolean): List<SearchBook> =
        synchronized(searchBooksLock) {
            val removed = searchBooks.filter(predicate)
            if (removed.isNotEmpty()) {
                searchBooks.removeAll(removed.toSet())
            }
            removed
        }

    private fun sortedSearchBooks(): List<SearchBook> {
        val comparator = if (AppConfig.changeSourceLoadWordCount) {
            wordCountComparator
        } else {
            defaultComparator
        }
        return searchBooksSnapshot().sortedWith(comparator)
    }

    private fun SearchBook.matchesScreen(key: String = screenKey): Boolean {
        if (key.isBlank()) {
            return true
        }
        return name.contains(key, true)
                || author.contains(key, true)
                || originName.contains(key, true)
                || origin.contains(key, true)
                || latestChapterTitle?.contains(key, true) == true
    }

    private fun nextSearchGeneration(): Long = synchronized(searchGenerationLock) {
        searchGeneration += 1
        searchGeneration
    }

    @CallSuper
    open fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        arguments?.let { bundle ->
            bundle.getString("name")?.let {
                name = it
            }
            bundle.getString("author")?.let {
                author = it.replace(AppPattern.authorRegex, "")
            }
            origin = bundle.getString("origin")
            this.fromReadBookActivity = fromReadBookActivity
            oldBook = book
        }
    }

    private fun applyOriginSearchGroup() {
        if (originGroupApplied) return
        originGroupApplied = true
        val sourceType = origin
            ?.let { appDb.bookSourceDao.getBookSource(it)?.bookSourceType }
            ?: oldBook?.type?.toBookSourceType()
            ?: return
        originSourceType = sourceType
        AppConfig.searchGroup = defaultGroupForSourceType(sourceType).orEmpty()
    }

    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun refresh(): Boolean {
        getDbSearchBooks().let {
            replaceSearchBooks(it)
            searchCallback?.upAdapter()
        }
        return isSearchBooksEmpty()
    }

    /**
     * 搜索书籍
     */
    fun startSearch() {
        execute {
            stopSearch()
            val oldBooks = clearSearchBooks()
            if (oldBooks.isNotEmpty()) {
                appDb.searchBookDao.delete(*oldBooks.toTypedArray())
            }
            searchCallback?.upAdapter()
            bookSourceParts.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            _changeSourceProgress.value = 0 to ""
            bookSourceParts.addAll(getCurrentBookSourceParts())
            initSearchPool()
            search(nextSearchGeneration(), bookSourceParts.toList())
        }
    }

    private fun startSearchMissing(sourceParts: List<BookSourcePart>) {
        execute {
            stopSearch()
            bookSourceParts.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            _changeSourceProgress.value = 0 to ""
            bookSourceParts.addAll(sourceParts)
            initSearchPool()
            search(nextSearchGeneration(), sourceParts)
        }
    }

    fun startSearch(origin: String) {
        execute {
            stopSearch()
            bookSourceParts.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            bookSourceParts.add(appDb.bookSourceDao.getBookSourcePart(origin)!!)
            removeSearchBooks { it.origin == origin }
            searchCallback?.upAdapter()
            initSearchPool()
            search(nextSearchGeneration(), bookSourceParts.toList())
        }
    }

    private fun search(generation: Long, sourceParts: List<BookSourcePart>) {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (bs in sourceParts) {
                    bs.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                if (generation == searchGeneration) {
                    searchStateData.postValue(true)
                }
            }.mapParallel(threadCount) {
                try {
                    withTimeout(60000L) {
                        search(it, generation)
                    }
                } catch (_: Throwable) {
                    currentCoroutineContext().ensureActive()
                }
                it
            }.onEachIndexed { index, value ->
                if (generation == searchGeneration) {
                    _changeSourceProgress.update { _ ->
                        index + 1 to value.bookSourceName
                    }
                }
            }.onCompletion {
                if (generation == searchGeneration) {
                    searchStateData.postValue(false)
                    searchFinishCallback?.invoke(isSearchBooksEmpty())
                }
            }.catch {
                AppLog.put("换源搜索出错\n${it.localizedMessage}", it)
                if (generation == searchGeneration) {
                    searchStateData.postValue(false)
                }
            }.collect()
        }
    }

    private suspend fun search(source: BookSource, generation: Long) {
        if (generation != searchGeneration) {
            return
        }
        val checkAuthor = AppConfig.changeSourceCheckAuthor
        val loadInfo = AppConfig.changeSourceLoadInfo
        val loadToc = AppConfig.changeSourceLoadToc
        val loadWordCount = AppConfig.changeSourceLoadWordCount
        val resultBooks = WebBook.searchBookAwait(
            source, name,
            filter = { fName, fAuthor ->
                fName == name && (!checkAuthor || fAuthor.contains(author))
            })
        for (searchBook in resultBooks) {
            if (generation != searchGeneration) {
                return
            }
            when {
                loadInfo || loadToc || loadWordCount -> {
                    loadBookInfo(source, searchBook.toBook(), generation)
                }

                else -> {
                    if (generation == searchGeneration) {
                        searchCallback?.searchSuccess(searchBook)
                    }
                }
            }
        }
    }

    private suspend fun loadBookInfo(source: BookSource, book: Book, generation: Long) {
        if (generation != searchGeneration) {
            return
        }
        if (book.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(source, book)
        }
        if (generation != searchGeneration) {
            return
        }
        if (AppConfig.changeSourceLoadToc || AppConfig.changeSourceLoadWordCount) {
            loadBookToc(source, book, generation)
        } else {
            //从详情页里获取最新章节
            val searchBook = book.toSearchBook()
            if (generation == searchGeneration) {
                searchCallback?.searchSuccess(searchBook)
            }
        }
    }

    private suspend fun loadBookToc(source: BookSource, book: Book, generation: Long) {
        if (generation != searchGeneration) {
            return
        }
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        if (generation != searchGeneration) {
            return
        }
        for (chapter in chapters) {
            chapter.internString()
        }
        if (tocMapChapterCount < 30000) {
            tocMapChapterCount += chapters.size
            tocMap[book.primaryStr()] = chapters
        }
        bookMap[book.primaryStr()] = book
        book.releaseHtmlData()
        if (AppConfig.changeSourceLoadWordCount) {
            loadBookWordCount(source, book, chapters, generation)
        } else {
            val searchBook = book.toSearchBook()
            if (generation == searchGeneration) {
                searchCallback?.searchSuccess(searchBook)
            }
        }
    }

    private suspend fun loadBookWordCount(
        source: BookSource,
        book: Book,
        chapters: List<BookChapter>,
        generation: Long
    ) = coroutineScope {
        if (generation != searchGeneration) {
            return@coroutineScope
        }
        val chapterIndex = if (fromReadBookActivity) {
            BookHelp.getDurChapter(oldBook!!, chapters)
        } else {
            chapters.lastIndex
        }
        val bookChapter = chapters[chapterIndex]
        var title = bookChapter.title.trim()
        if (title.length > 20) {
            title = title.substring(0, 20) + "…"
        }
        val startTime = System.currentTimeMillis()
        val pair = try {
            val nextChapterUrl = chapters.getOrNull(chapterIndex + 1)?.url
            var content = WebBook.getContentAwait(source, book, bookChapter, nextChapterUrl, false)
            content = contentProcessor.getContent(oldBook!!, bookChapter, content, false).toString()
            val len = content.length
            len to "[${chapterIndex + 1}] ${title}\n字数：${len}"
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            -1 to "[${chapterIndex + 1}] ${title}\n获取字数失败：${t.localizedMessage}"
        }
        val endTime = System.currentTimeMillis()
        val searchBook = book.toSearchBook().apply {
            chapterWordCountText = pair.second
            chapterWordCount = pair.first
            respondTime = (endTime - startTime).toInt()
        }
        if (generation == searchGeneration) {
            searchCallback?.searchSuccess(searchBook)
        }
    }

    fun onLoadWordCountChecked(isChecked: Boolean) {
        if (isChecked) {
            startRefreshList(true)
        }
    }

    /**
     * 刷新列表
     */
    fun startRefreshList(onlyRefreshNoWordCountBook: Boolean = false) {
        execute {
            stopSearch()
            searchBookList.clear()
            if (onlyRefreshNoWordCountBook) {
                synchronized(searchBooksLock) {
                    searchBooks.filterTo(searchBookList) {
                        it.chapterWordCountText == null
                    }
                    searchBooks.removeIf { it.chapterWordCountText == null }
                }
            } else {
                searchBookList.addAll(clearSearchBooks())
            }
            searchCallback?.upAdapter()
            initSearchPool()
            refreshList(nextSearchGeneration(), searchBookList.toList())
        }
    }

    private fun refreshList(generation: Long, searchBookList: List<SearchBook>) {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (searchBook in searchBookList) {
                    emit(searchBook)
                }
            }.onStart {
                if (generation == searchGeneration) {
                    searchStateData.postValue(true)
                }
            }.mapParallelSafe(threadCount) {
                val source = appDb.bookSourceDao.getBookSource(it.origin)!!
                withTimeout(60000L) {
                    loadBookInfo(source, it.toBook(), generation)
                }
            }.onCompletion {
                if (generation == searchGeneration) {
                    searchStateData.postValue(false)
                }
            }.catch {
                AppLog.put("换源刷新列表出错\n${it.localizedMessage}", it)
                if (generation == searchGeneration) {
                    searchStateData.postValue(false)
                }
            }.collect()
        }
    }

    private fun getDbSearchBooks(): List<SearchBook> {
        val key = screenKey
        val books = getDbSearchBooksWithoutScreen()
        return if (key.isBlank()) {
            books
        } else {
            books.filter { it.matchesScreen(key) }
        }
    }

    private fun getDbSearchBooksWithoutScreen(): List<SearchBook> {
        val sourceType = currentSearchSourceType()
        return if (AppConfig.changeSourceCheckAuthor) {
            if (sourceType != null) {
                appDb.searchBookDao.changeSourceByType(name, author, sourceType)
            } else {
                appDb.searchBookDao.changeSourceByGroup(name, author, AppConfig.searchGroup)
            }
        } else {
            if (sourceType != null) {
                appDb.searchBookDao.changeSourceByType(name, "", sourceType)
            } else {
                appDb.searchBookDao.changeSourceByGroup(name, "", AppConfig.searchGroup)
            }
        }
    }

    private fun getCurrentBookSourceParts(): List<BookSourcePart> {
        val searchGroup = AppConfig.searchGroup
        val sourceType = currentSearchSourceType()
        return if (sourceType != null) {
            appDb.bookSourceDao.getEnabledPartByType(sourceType)
        } else if (searchGroup.isBlank()) {
            appDb.bookSourceDao.allEnabledPart
        } else {
            val sources = appDb.bookSourceDao.getEnabledPartByGroup(searchGroup)
            if (sources.isEmpty()) {
                AppConfig.searchGroup = ""
                appDb.bookSourceDao.allEnabledPart
            } else {
                sources
            }
        }
    }

    fun selectSearchGroup(group: String) {
        AppConfig.searchGroup = group
        originSourceType = sourceTypeForDefaultGroup(group)
    }

    fun searchByGroup() {
        originSourceType = sourceTypeForDefaultGroup(AppConfig.searchGroup)
    }

    private fun currentSearchSourceType(): Int? {
        val searchGroup = AppConfig.searchGroup
        val sourceType = sourceTypeForDefaultGroup(searchGroup)
        originSourceType = sourceType
        return sourceType
    }

    /**
     * 筛选
     */
    fun screen(key: String?) {
        val newKey = key?.trim() ?: ""
        if (screenKey == newKey) {
            return
        }
        screenKey = newKey
        screenJob?.cancel()
        screenJob = viewModelScope.launch(IO) {
            delay(150)
            if (screenKey != newKey) {
                return@launch
            }
            getDbSearchBooks().let {
                replaceSearchBooks(it)
                searchCallback?.upAdapter()
            }
        }
    }

    fun startOrStopSearch() {
        if (task == null || !task!!.isActive) {
            startSearch()
        } else {
            stopSearch()
        }
    }

    fun stopSearch() {
        nextSearchGeneration()
        task?.cancel()
        task = null
        searchPool?.close()
        searchPool = null
        searchStateData.postValue(false)
    }

    fun getToc(
        book: Book,
        onSuccess: (toc: List<BookChapter>, source: BookSource) -> Unit,
        onError: (e: Throwable) -> Unit
    ): Coroutine<Pair<List<BookChapter>, BookSource>> {
        return execute {
            val toc = tocMap[book.primaryStr()]
            if (toc != null) {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                return@execute Pair(toc, source!!)
            }
            val result = getToc(book).getOrThrow()
            tocMap[book.primaryStr()] = result.first
            return@execute result
        }.onSuccess {
            onSuccess.invoke(it.first, it.second)
        }.onError {
            onError.invoke(it)
        }
    }

    suspend fun getToc(book: Book): Result<Pair<List<BookChapter>, BookSource>> {
        return kotlin.runCatching {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            Pair(toc, source)
        }
    }

    fun disableSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                source.enabled = false
                appDb.bookSourceDao.update(source)
            }
            removeSearchBooks { it.bookUrl == searchBook.bookUrl }
            searchCallback?.upAdapter()
        }
    }

    fun topSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val minOrder = appDb.bookSourceDao.minOrder - 1
                source.customOrder = minOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun bottomSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val maxOrder = appDb.bookSourceDao.maxOrder + 1
                source.customOrder = maxOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun updateSource(searchBook: SearchBook) {
        appDb.searchBookDao.update(searchBook)
    }

    fun del(searchBook: SearchBook) {
        execute {
            SourceHelp.deleteBookSource(searchBook.origin)
            appDb.searchBookDao.delete(searchBook)
        }
        removeSearchBooks { it.bookUrl == searchBook.bookUrl }
        searchCallback?.upAdapter()
    }

    fun autoChangeSource(
        bookType: Int?,
        onSuccess: (book: Book, toc: List<BookChapter>, source: BookSource) -> Unit
    ) {
        execute {
            searchBooksSnapshot().forEach {
                if (it.type == bookType) {
                    val book = it.toBook()
                    val result = getToc(book).getOrNull()
                    if (result != null) {
                        return@execute Triple(book, result.first, result.second)
                    }
                }
            }
            throw NoStackTraceException("没有有效源")
        }.onSuccess {
            onSuccess.invoke(it.first, it.second, it.third)
        }.onError {
            context.toastOnUi("自动换源失败\n${it.localizedMessage}")
        }
    }

    fun setBookScore(searchBook: SearchBook, score: Int) {
        execute {
            SourceConfig.setBookScore(searchBook.origin, searchBook.name, searchBook.author, score)
            searchCallback?.upAdapter()
        }
    }

    fun getBookScore(searchBook: SearchBook): Int {
        return SourceConfig.getBookScore(searchBook.origin, searchBook.name, searchBook.author)
    }

    private fun getChapterNum(wordCountText: String?): Int {
        wordCountText ?: return -1
        return chapterNumRegex.find(wordCountText)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    interface SourceCallback {

        fun searchSuccess(searchBook: SearchBook)

        fun upAdapter()

    }

    private fun Int.toBookSourceType(): Int? {
        return when {
            this and BookType.image != 0 -> BookSourceType.image
            this and BookType.audio != 0 -> BookSourceType.audio
            this and BookType.text != 0 -> BookSourceType.default
            else -> null
        }
    }

    companion object {
        const val DEFAULT_TEXT_GROUP = "默认小说"
        const val DEFAULT_IMAGE_GROUP = "默认漫画"
        const val DEFAULT_AUDIO_GROUP = "默认听书"

        val defaultSearchGroups = listOf(
            DEFAULT_TEXT_GROUP,
            DEFAULT_IMAGE_GROUP,
            DEFAULT_AUDIO_GROUP
        )

        fun sourceTypeForDefaultGroup(group: String): Int? {
            return when (group) {
                DEFAULT_TEXT_GROUP -> BookSourceType.default
                DEFAULT_IMAGE_GROUP -> BookSourceType.image
                DEFAULT_AUDIO_GROUP -> BookSourceType.audio
                else -> null
            }
        }

        fun defaultGroupForSourceType(sourceType: Int): String? {
            return when (sourceType) {
                BookSourceType.default -> DEFAULT_TEXT_GROUP
                BookSourceType.image -> DEFAULT_IMAGE_GROUP
                BookSourceType.audio -> DEFAULT_AUDIO_GROUP
                else -> null
            }
        }
    }

}
