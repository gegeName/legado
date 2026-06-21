package io.legado.app.ui.main.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.widget.PopupMenu
import androidx.core.view.isGone
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.FragmentExploreBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.explore.ExploreShowAdapter
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Discovery page.
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_explore),
    MainFragmentInterface,
    ExploreShowAdapter.CallBack,
    ExploreKindAdapter.Callback {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<ExploreViewModel>()
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val adapter by lazy { ExploreShowAdapter(requireContext(), this) }
    private val kindAdapter by lazy { ExploreKindAdapter(requireContext(), this) }
    private val loadMoreView by lazy { LoadMoreView(requireContext()) }
    private val groups = linkedSetOf<String>()
    private var exploreFlowJob: Job? = null
    private var groupsMenu: SubMenu? = null
    private var kindsExpanded = false
    private var hasExploreSources = false
    private var isBookLoading = false
    private val collapsedKindsHeight by lazy { 48.dpToPx() }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initSourceBar()
        initRecyclerView()
        initKindList()
        initLiveData()
        initGroupData()
        upExploreData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        super.onCompatCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main_explore, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupsMenu()
    }

    private fun initSourceBar() {
        binding.sourceBar.setOnClickListener {
            showSourceMenu()
        }
        binding.ivSourceMenu.setOnClickListener {
            showSourceMenu()
        }
        binding.sourceBar.setOnLongClickListener {
            showCurrentSourceMenu()
            true
        }
        binding.ivKindToggle.setOnClickListener {
            kindsExpanded = !kindsExpanded
            upKindBarHeight()
        }
    }

    private fun initRecyclerView() {
        binding.rvFind.setEdgeEffectColor(primaryColor)
        binding.rvFind.adapter = adapter
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                viewModel.explore(true)
            }
        }
        binding.rvFind.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1) && !loadMoreView.isLoading) {
                    viewModel.explore()
                }
            }
        })
    }

    private fun initKindList() {
        binding.rvKinds.setEdgeEffectColor(primaryColor)
        binding.rvKinds.isNestedScrollingEnabled = false
        binding.rvKinds.layoutManager = FlexboxLayoutManager(requireContext()).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
        }
        binding.rvKinds.adapter = kindAdapter
        kindAdapter.setItems(emptyList())
        upKindBarHeight()
    }

    private fun initLiveData() {
        viewModel.selectedSourceData.observe(viewLifecycleOwner) {
            binding.tvSourceName.text = it?.bookSourceName ?: getString(R.string.discovery)
        }
        viewModel.kindsData.observe(viewLifecycleOwner) {
            kindAdapter.setItems(it)
            binding.kindBar.post {
                upKindBarHeight()
            }
        }
        viewModel.selectedKindData.observe(viewLifecycleOwner) {
            kindAdapter.setSelected(it)
            upEmptyView()
        }
        viewModel.booksData.observe(viewLifecycleOwner) {
            upBooks(it)
        }
        viewModel.loadingData.observe(viewLifecycleOwner) {
            isBookLoading = it
            if (it) {
                loadMoreView.hasMore()
            } else {
                loadMoreView.stopLoad()
            }
            upEmptyView()
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            loadMoreView.error(it)
        }
    }

    private fun initGroupData() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    upGroupsMenu()
                    delay(500)
                }
        }
    }

    private fun upExploreData(searchKey: String? = null) {
        exploreFlowJob?.cancel()
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> appDb.bookSourceDao.flowExplore()
                searchKey.startsWith("group:") -> {
                    appDb.bookSourceDao.flowGroupExplore(searchKey.substringAfter("group:"))
                }
                else -> appDb.bookSourceDao.flowExplore(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("Discovery page refresh error", it)
            }.conflate().flowOn(IO).collect {
                hasExploreSources = it.isNotEmpty()
                viewModel.setSources(it)
                upEmptyView()
                delay(500)
            }
        }
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    private val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        if (item.groupId == R.id.menu_group_text) {
            upExploreData("group:${item.title}")
        }
    }

    private fun showSourceMenu() {
        val sources = viewModel.sourcesData.value.orEmpty()
        if (sources.isEmpty()) return
        showDialogFragment(
            ExploreSourceSelectDialog(
                sources,
                viewModel.selectedSourceData.value?.bookSourceUrl
            ) {
                viewModel.selectSource(it)
            }
        )
    }

    private fun showCurrentSourceMenu() {
        val source = viewModel.selectedSourceData.value ?: return
        PopupMenu(requireContext(), binding.sourceBar).apply {
            inflate(R.menu.explore_item)
            menu.findItem(R.id.menu_login).isVisible = source.hasLoginUrl
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_edit -> editSource(source.bookSourceUrl)
                    R.id.menu_top -> viewModel.topSource(source)
                    R.id.menu_search -> searchBook(source)
                    R.id.menu_login -> startActivity<SourceLoginActivity> {
                        putExtra("type", "bookSource")
                        putExtra("key", source.bookSourceUrl)
                    }
                    R.id.menu_refresh -> Coroutine.async(scope) {
                        source.clearExploreKindsCache()
                    }.onSuccess {
                        viewModel.selectSource(source)
                    }
                    R.id.menu_del -> deleteSource(source)
                }
                true
            }
            show()
        }
    }

    private fun upBooks(books: List<SearchBook>) {
        loadMoreView.stopLoad()
        if (adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            adapter.setItems(books)
            if (books.isEmpty()) {
                loadMoreView.noMore()
            }
        }
        upEmptyView()
    }

    private fun upEmptyView() {
        val hasBooks = adapter.getActualItemCount() > 0
        val hasSelectedKind = viewModel.selectedKindData.value != null
        binding.tvEmptyMsg.isGone = isBookLoading || hasBooks || (hasExploreSources && !hasSelectedKind)
    }

    private fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    private fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    private fun searchBook(bookSource: BookSourcePart) {
        startActivity<SearchActivity> {
            putExtra("searchScope", SearchScope(bookSource).toString())
        }
    }

    fun compressExplore() {
        binding.rvFind.smoothScrollToPosition(0)
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun showBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    override fun selectKind(kind: ExploreKind) {
        if (kind.url.isNullOrBlank()) return
        viewModel.selectKind(kind)
    }

    private fun upKindBarHeight() {
        binding.ivKindToggle.rotation = if (kindsExpanded) 180f else 0f
        binding.rvKinds.layoutParams = binding.rvKinds.layoutParams.apply {
            height = if (kindsExpanded) {
                RecyclerView.LayoutParams.WRAP_CONTENT
            } else {
                collapsedKindsHeight
            }
        }
    }

}
