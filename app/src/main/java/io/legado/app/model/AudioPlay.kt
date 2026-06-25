package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import splitties.init.appCtx
import java.util.Collections

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object AudioPlay : CoroutineScope by MainScope() {
    /**
     * 播放模式枚举
     */
    enum class PlayMode(val iconRes: Int) {
        LIST_END_STOP(R.drawable.ic_play_mode_list_end_stop),
        SINGLE_LOOP(R.drawable.ic_play_mode_single_loop),
        RANDOM(R.drawable.ic_play_mode_random),
        LIST_LOOP(R.drawable.ic_play_mode_list_loop);

        fun next(): PlayMode {
            return when (this) {
                LIST_END_STOP -> SINGLE_LOOP
                SINGLE_LOOP -> RANDOM
                RANDOM -> LIST_LOOP
                LIST_LOOP -> LIST_END_STOP
            }
        }
    }

    var playMode = PlayMode.LIST_END_STOP
    var status = Status.STOP
    private var activityContext: Context? = null
    private var serviceContext: Context? = null
    private val context: Context get() = activityContext ?: serviceContext ?: appCtx
    var callback: CallBack? = null
    var book: Book? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var durChapter: BookChapter? = null
    var durPlayUrl = ""
    var durAudioSize = 0
    var inBookshelf = false
    var bookSource: BookSource? = null
    val loadingChapters = arrayListOf<Int>()
    private var preloadedPlay: PreloadedPlay? = null
    private var preloadingChapterKey: String? = null
    private var noPreloadChapterKey: String? = null
    private val failedPlayChapterKeys = Collections.synchronizedSet(hashSetOf<String>())
    var hideMiniPlayerWhenPaused = false

    fun changePlayMode() {
        playMode = playMode.next()
        clearPreload()
        postEvent(EventBus.PLAY_MODE_CHANGED, playMode)
    }

    fun upData(book: Book) {
        val keepPlaying = AudioPlay.book?.bookUrl == book.bookUrl && AudioPlayService.isRun
        if (keepPlaying) {
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
        }
        AudioPlay.book = book
        bookSource = book.getBookSource()
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (!keepPlaying && durChapterIndex != book.durChapterIndex) {
            stopPlay()
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            durPlayUrl = ""
            durAudioSize = 0
            clearPreload()
            clearFailedChapters()
        }
        upDurChapter()
    }

    fun resetData(book: Book) {
        stop()
        AudioPlay.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        bookSource = book.getBookSource()
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        durPlayUrl = ""
        durAudioSize = 0
        clearPreload()
        clearFailedChapters()
        upDurChapter()
        postEvent(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    }

    private fun addLoading(index: Int): Boolean {
        synchronized(this) {
            if (loadingChapters.contains(index)) return false
            loadingChapters.add(index)
            return true
        }
    }

    private fun removeLoading(index: Int) {
        synchronized(this) {
            loadingChapters.remove(index)
        }
    }

    fun loadOrUpPlayUrl() {
        upDurChapter()
        if (durPlayUrl.isEmpty()) {
            loadPlayUrl()
        } else {
            upPlayUrl()
        }
    }

    /**
     * 加载播放URL
     */
    private fun loadPlayUrl() {
        val index = durChapterIndex
        if (addLoading(index)) {
            val book = book
            val bookSource = bookSource
            if (book != null && bookSource != null) {
                upDurChapter()
                val chapter = durChapter
                if (chapter == null) {
                    removeLoading(index)
                    handlePlayError(book.bookUrl, index, "音频章节不存在")
                    return
                }
                if (chapter.isVolume) {
                    skipTo(index + 1)
                    removeLoading(index)
                    return
                }
                upLoading(true)
                Coroutine.async(this) {
                    BookHelp.getContent(book, chapter)?.takeIf { it.isNotBlank() }
                }.onSuccess { content ->
                    if (content != null) {
                        removeLoading(index)
                        upLoading(false)
                        contentLoadFinish(chapter, content)
                    } else {
                        loadPlayUrlFromSource(index, bookSource, book, chapter)
                    }
                }.onError {
                    loadPlayUrlFromSource(index, bookSource, book, chapter)
                }
            } else {
                removeLoading(index)
                handlePlayError(book?.bookUrl, index, "book or source is null")
            }
        }
    }

    private fun loadPlayUrlFromSource(
        index: Int,
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter
    ) {
        WebBook.getContent(this, bookSource, book, chapter)
                    .onSuccess { content ->
                        if (content.isEmpty()) {
                            upLoading(false)
                            handlePlayError(book.bookUrl, chapter.index, "未获取到资源链接")
                        } else {
                            contentLoadFinish(chapter, content)
                        }
                    }.onError {
                        AppLog.put("获取资源链接出错\n$it", it, true)
                        upLoading(false)
                        handlePlayError(book.bookUrl, chapter.index, "获取资源链接出错")
                    }.onCancel {
                        removeLoading(index)
                    }.onFinally {
                        removeLoading(index)
                    }
    }

    /**
     * 加载完成
     */
    private fun contentLoadFinish(chapter: BookChapter, content: String) {
        if (chapter.bookUrl == book?.bookUrl && chapter.index == durChapterIndex) {
            durPlayUrl = content
            upPlayUrl()
        }
    }

    private fun upPlayUrl() {
        if (isPlayToEnd()) {
            playNew()
        } else {
            play()
        }
    }

    /**
     * 播放当前章节
     */
    fun play() {
        context.startService<AudioPlayService> {
            action = IntentAction.play
        }
    }

    /**
     * 从头播放新章节
     */
    private fun playNew() {
        context.startService<AudioPlayService> {
            action = IntentAction.playNew
        }
    }

    /**
     * 更新当前章节
     */
    fun upDurChapter() {
        val book = book ?: return
        durChapter = appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
        durAudioSize = durChapter?.end?.toInt() ?: 0
        val title = durChapter?.title ?: appCtx.getString(R.string.data_loading)
        postEvent(EventBus.AUDIO_SUB_TITLE, title)
        postEvent(EventBus.AUDIO_SIZE, durAudioSize)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    fun pause(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.pause
            }
        }
    }

    fun resume(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.resume
            }
        }
    }

    fun stop() {
        clearPreload()
        clearFailedChapters()
        coroutineContext.cancelChildren()
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stop
            }
        }
    }

    fun adjustSpeed(adjust: Float) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.adjustSpeed
                putExtra("adjust", adjust)
            }
        }
    }

    fun adjustProgress(position: Int) {
        durChapterPos = position
        saveRead()
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.adjustProgress
                putExtra("position", position)
            }
        }
    }

    fun skipTo(index: Int) {
        Coroutine.async {
            if (index in 0..<simulatedChapterSize) {
                stopPlay()
                durChapterIndex = index
                durChapterPos = 0
                durPlayUrl = ""
                clearPreload()
                clearFailedChapters()
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun prev() {
        Coroutine.async {
            if (durChapterIndex > 0) {
                stopPlay()
                durChapterIndex -= 1
                durChapterPos = 0
                durPlayUrl = ""
                clearPreload()
                clearFailedChapters()
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun next(auto: Boolean = false) {
        Coroutine.async {
            if (!auto) {
                clearFailedChapters()
            }
            when (playMode) {
                PlayMode.LIST_END_STOP -> {
                    val nextIndex = findNextPlayableIndex(durChapterIndex, PlayMode.LIST_END_STOP)
                    if (nextIndex != null) {
                        if (!auto) stopPlay()
                        durChapterIndex = nextIndex
                        durChapterPos = 0
                        durPlayUrl = consumePreloadedPlay(durChapterIndex) ?: ""
                        saveRead()
                        loadOrUpPlayUrl()
                    } else if (auto) {
                        stopPlay()
                    }
                }

                PlayMode.SINGLE_LOOP -> {
                    if (!auto) stopPlay()
                    durChapterPos = 0
                    durPlayUrl = ""
                    clearPreload()
                    saveRead()
                    loadOrUpPlayUrl()
                }

                PlayMode.RANDOM -> {
                    if (simulatedChapterSize <= 0) {
                        return@async
                    }
                    if (!auto) stopPlay()
                    durChapterIndex = (0 until simulatedChapterSize).random()
                    durChapterPos = 0
                    durPlayUrl = ""
                    clearPreload()
                    saveRead()
                    loadOrUpPlayUrl()
                }

                PlayMode.LIST_LOOP -> {
                    val nextIndex = findNextPlayableIndex(durChapterIndex, PlayMode.LIST_LOOP)
                        ?: return@async
                    if (!auto) stopPlay()
                    durChapterIndex = nextIndex
                    durChapterPos = 0
                    durPlayUrl = consumePreloadedPlay(durChapterIndex) ?: ""
                    saveRead()
                    loadOrUpPlayUrl()
                }
            }
        }
    }

    fun handlePlayError(message: String? = null) {
        handlePlayError(book?.bookUrl, durChapterIndex, message)
    }

    private fun handlePlayError(bookUrl: String?, chapterIndex: Int, message: String? = null) {
        Coroutine.async {
            bookUrl?.let {
                failedPlayChapterKeys.add("$it#$chapterIndex")
            }
            val currentBookUrl = book?.bookUrl
            if (bookUrl != currentBookUrl || chapterIndex != durChapterIndex) {
                return@async
            }
            val nextIndex = when (playMode) {
                PlayMode.LIST_END_STOP -> findNextPlayableIndex(
                    chapterIndex,
                    PlayMode.LIST_END_STOP,
                    excludeFailed = true
                )

                PlayMode.LIST_LOOP -> findNextPlayableIndex(
                    chapterIndex,
                    PlayMode.LIST_LOOP,
                    excludeFailed = true
                )

                PlayMode.RANDOM -> findRandomPlayableIndex(
                    excludeFailed = true
                )

                else -> null
            }
            if (nextIndex != null && nextIndex != chapterIndex) {
                durChapterIndex = nextIndex
                durChapterPos = 0
                durPlayUrl = consumePreloadedPlay(durChapterIndex) ?: ""
                saveRead()
                loadOrUpPlayUrl()
            } else {
                clearPreload()
                status = Status.STOP
                postEvent(EventBus.AUDIO_STATE, Status.STOP)
                message?.let { appCtx.toastOnUi(it) }
            }
        }
    }

    fun setTimer(minute: Int) {
        if (AudioPlayService.isRun) {
            val intent = Intent(context, AudioPlayService::class.java)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startService(intent)
        } else {
            AudioPlayService.timeMinute = minute
            postEvent(EventBus.AUDIO_DS, minute)
        }
    }

    fun addTimer() {
        val intent = Intent(context, AudioPlayService::class.java)
        intent.action = IntentAction.addTimer
        context.startService(intent)
    }

    fun stopPlay() {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stopPlay
            }
        }
    }

    fun saveRead() {
        val book = book ?: return
        Coroutine.async {
            book.lastCheckCount = 0
            book.durChapterTime = System.currentTimeMillis()
            val chapterChanged = book.durChapterIndex != durChapterIndex
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            if (chapterChanged) {
                appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }
            }
            book.update()
        }
    }

    /**
     * 保存章节长度
     */
    fun saveDurChapter(audioSize: Long) {
        val chapter = durChapter ?: return
        Coroutine.async {
            durAudioSize = audioSize.toInt()
            chapter.end = audioSize
            appDb.bookChapterDao.update(chapter)
        }
    }

    fun playPositionChanged(position: Int) {
        durChapterPos = position
        saveRead()
    }

    fun preloadNextChapter() {
        val currentIndex = durChapterIndex
        val preloadPlayMode = playMode
        val book = book ?: return
        val bookSource = bookSource ?: return
        val preloadKey = "${book.bookUrl}#$currentIndex"
        if (preloadedPlay?.preloadKey == preloadKey
            || preloadingChapterKey == preloadKey
            || noPreloadChapterKey == preloadKey
        ) {
            return
        }
        preloadingChapterKey = preloadKey
        Coroutine.async {
            val nextIndex = findNextPlayableIndex(
                book.bookUrl,
                currentIndex,
                preloadPlayMode
            ) ?: return@async null
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, nextIndex)
            if (chapter == null) {
                return@async null
            }
            val content = BookHelp.getContent(book, chapter)?.takeIf { it.isNotBlank() }
                ?: WebBook.getContentAwait(bookSource, book, chapter)
                    .takeIf { it.isNotBlank() }
                ?: return@async null
            PreloadedPlay(
                preloadKey = preloadKey,
                key = "${book.bookUrl}#$nextIndex",
                book = book,
                bookSource = bookSource,
                chapter = chapter,
                url = content
            )
        }.onSuccess { preload ->
            if (preload != null
                && book.bookUrl == AudioPlay.book?.bookUrl
                && currentIndex == durChapterIndex
                && preloadPlayMode == playMode
            ) {
                preloadedPlay = preload
                noPreloadChapterKey = null
                preCacheAudio(preload)
            } else if (preload == null) {
                noPreloadChapterKey = preloadKey
            }
        }.onError {
            if (book.bookUrl == AudioPlay.book?.bookUrl
                && currentIndex == durChapterIndex
                && preloadPlayMode == playMode
            ) {
                noPreloadChapterKey = preloadKey
            }
        }.onFinally {
            if (preloadingChapterKey == preloadKey) {
                preloadingChapterKey = null
            }
        }
    }

    fun upLoading(loading: Boolean) {
        callback?.upLoading(loading)
    }

    private fun isPlayToEnd(): Boolean {
        return durChapterIndex + 1 == simulatedChapterSize
                && durChapterPos == durAudioSize
    }

    private fun findNextPlayableIndex(
        currentIndex: Int,
        mode: PlayMode,
        excludeFailed: Boolean = false
    ): Int? {
        val bookUrl = book?.bookUrl ?: return null
        return findNextPlayableIndex(bookUrl, currentIndex, mode, excludeFailed)
    }

    private fun findNextPlayableIndex(
        bookUrl: String,
        currentIndex: Int,
        mode: PlayMode,
        excludeFailed: Boolean = false
    ): Int? {
        return when (mode) {
            PlayMode.LIST_END_STOP -> {
                (currentIndex + 1 until simulatedChapterSize).firstOrNull { index ->
                    isPlayableChapter(bookUrl, index, excludeFailed)
                }
            }
            PlayMode.LIST_LOOP -> {
                if (simulatedChapterSize <= 0) return null
                (1..simulatedChapterSize).map { offset ->
                    (currentIndex + offset) % simulatedChapterSize
                }.firstOrNull { index ->
                    isPlayableChapter(bookUrl, index, excludeFailed)
                }
            }
            else -> null
        }
    }

    private fun findRandomPlayableIndex(excludeFailed: Boolean = false): Int? {
        val bookUrl = book?.bookUrl ?: return null
        if (simulatedChapterSize <= 0) return null
        return (0 until simulatedChapterSize)
            .filter { index -> isPlayableChapter(bookUrl, index, excludeFailed) }
            .randomOrNull()
    }

    private fun isPlayableChapter(
        bookUrl: String,
        index: Int,
        excludeFailed: Boolean = false
    ): Boolean {
        if (excludeFailed && failedPlayChapterKeys.contains("$bookUrl#$index")) {
            return false
        }
        return appDb.bookChapterDao.getChapter(bookUrl, index)?.isVolume == false
    }

    private fun consumePreloadedPlay(index: Int): String? {
        val bookUrl = book?.bookUrl ?: return null
        val key = "$bookUrl#$index"
        return preloadedPlay?.takeIf { it.key == key }?.url?.also {
            clearPreload()
        } ?: run {
            clearPreload()
            null
        }
    }

    private fun preCacheAudio(preload: PreloadedPlay) {
        Coroutine.async {
            val analyzeUrl = AnalyzeUrl(
                preload.url,
                source = preload.bookSource,
                ruleData = preload.book,
                chapter = preload.chapter,
                coroutineContext = coroutineContext
            )
            analyzeUrl.preCacheMedia()
        }
    }

    private fun clearPreload() {
        preloadedPlay = null
        preloadingChapterKey = null
        noPreloadChapterKey = null
    }

    private fun clearFailedChapters() {
        failedPlayChapterKeys.clear()
    }

    fun register(context: Context) {
        activityContext = context
        callback = context as CallBack
    }

    fun unregister(context: Context) {
        if (activityContext === context) {
            activityContext = null
            callback = null
        }
    }

    fun registerService(context: Context) {
        serviceContext = context
    }

    fun unregisterService() {
        serviceContext = null
    }

    interface CallBack {

        fun upLoading(loading: Boolean)

    }

    private data class PreloadedPlay(
        val preloadKey: String,
        val key: String,
        val book: Book,
        val bookSource: BookSource,
        val chapter: BookChapter,
        val url: String
    )

}
