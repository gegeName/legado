@file:Suppress("DEPRECATION")

package io.legado.app.ui.book.toc

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.Theme
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityChapterListBinding
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.applyTint
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 目录
 */
class TocActivity : VMBaseActivity<ActivityChapterListBinding, TocViewModel>(
    fullScreen = false,
    theme = Theme.Transparent,
    imageBg = false
),
    TxtTocRuleDialog.CallBack,
    PopupMenu.OnMenuItemClickListener {

    override val binding by viewBinding(ActivityChapterListBinding::inflate)
    override val viewModel by viewModels<TocViewModel>()
    override val showMiniAudioPlayer: Boolean = false

    private lateinit var tabLayout: TabLayout
    private lateinit var searchView: SearchView
    private val waitDialog by lazy { WaitDialog(this) }
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.saveBookmark(uri)
                2 -> viewModel.saveBookmarkMd(uri)
            }
        }
    }

    private val tocContentColor: Int
        get() = getCompatColor(R.color.primaryText)

    override fun initTheme() {
        setTheme(R.style.AppTheme_TocBottomSheet)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setupBottomSheetWindow()
        tabLayout = binding.tabLayout
        searchView = binding.searchView
        setupHeader()
        binding.viewPager.adapter = TabFragmentPageAdapter()
        tabLayout.setupWithViewPager(binding.viewPager)
        tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
        tabLayout.isTabIndicatorFullWidth = false
        tabLayout.setSelectedTabIndicatorColor(accentColor)
        tabLayout.setTabTextColors(tocContentColor, accentColor)
        intent.getStringExtra("bookUrl")?.let {
            viewModel.initBook(it)
        }
    }

    private fun setupHeader() {
        binding.btnClose.applyTint(tocContentColor)
        binding.btnSearch.applyTint(tocContentColor)
        binding.btnMore.applyTint(tocContentColor)
        binding.btnClose.setOnClickListener { finish() }
        binding.btnSearch.setOnClickListener {
            searchView.visible(true)
            tabLayout.gone()
            searchView.isIconified = false
            searchView.requestFocus()
            searchView.post { searchView.setQuery("", false) }
        }
        binding.btnMore.setOnClickListener { showTocMenu(it) }
        searchView.apply {
            applyTint(tocContentColor)
            maxWidth = resources.displayMetrics.widthPixels
            onActionViewCollapsed()
            setOnCloseListener {
                tabLayout.visible()
                binding.searchView.gone()
                false
            }
            setOnSearchClickListener { tabLayout.gone() }
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    viewModel.searchKey = query
                    return false
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    viewModel.searchKey = newText
                    if (tabLayout.selectedTabPosition == 1) {
                        viewModel.startBookmarkSearch(newText)
                    } else {
                        viewModel.startChapterListSearch(newText)
                    }
                    return false
                }
            })
            setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    binding.searchView.gone()
                    tabLayout.visible()
                    isIconified = true
                }
            }
        }
        binding.searchView.gone()
    }

    private fun setupBottomSheetWindow() {
        setFinishOnTouchOutside(true)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            dimAmount = 0f
            windowAnimations = R.style.Animation_TocBottomSheet
        }
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        binding.root.setBackgroundColor(Color.TRANSPARENT)
        binding.root.setOnClickListener { finish() }
        binding.tocPanel.setOnClickListener { }
        binding.tocPanel.layoutParams = binding.tocPanel.layoutParams.apply {
            height = resources.displayMetrics.heightPixels * 2 / 3
        }
    }

    private fun showTocMenu(anchor: View) {
        val popupMenu = PopupMenu(this, anchor)
        popupMenu.menuInflater.inflate(R.menu.book_toc, popupMenu.menu)
        popupMenu.setOnMenuItemClickListener(this)
        popupMenu.menu.removeItem(R.id.menu_search)
        popupMenu.menu.setGroupVisible(R.id.menu_group_text, viewModel.bookData.value?.isLocalTxt == true)
        if (tabLayout.selectedTabPosition == 1) {
            popupMenu.menu.setGroupVisible(R.id.menu_group_bookmark, true)
            popupMenu.menu.setGroupVisible(R.id.menu_group_toc, false)
            popupMenu.menu.setGroupVisible(R.id.menu_group_text, false)
        } else {
            popupMenu.menu.setGroupVisible(R.id.menu_group_bookmark, false)
            popupMenu.menu.setGroupVisible(R.id.menu_group_toc, true)
            popupMenu.menu.setGroupVisible(R.id.menu_group_text, viewModel.bookData.value?.isLocalTxt == true)
        }
        popupMenu.menu.findItem(R.id.menu_use_replace)?.isChecked =
            AppConfig.tocUiUseReplace
        popupMenu.menu.findItem(R.id.menu_load_word_count)?.isChecked =
            AppConfig.tocCountWords
        popupMenu.menu.findItem(R.id.menu_split_long_chapter)?.isChecked =
            viewModel.bookData.value?.getSplitLongChapter() == true
        popupMenu.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_toc_regex -> showDialogFragment(
                TxtTocRuleDialog(viewModel.bookData.value?.tocUrl)
            )

            R.id.menu_split_long_chapter -> {
                viewModel.bookData.value?.let { book ->
                    item.isChecked = !item.isChecked
                    book.setSplitLongChapter(item.isChecked)
                    upBookAndToc(book)
                }
            }

            R.id.menu_reverse_toc -> viewModel.reverseToc {
                viewModel.chapterListCallBack?.upChapterList(searchView.query?.toString())
                setResult(RESULT_OK, Intent().apply {
                    putExtra("index", it.durChapterIndex)
                    putExtra("chapterPos", 0)
                })
            }

            R.id.menu_use_replace -> {
                AppConfig.tocUiUseReplace = !item.isChecked
                viewModel.chapterListCallBack?.clearDisplayTitle()
                viewModel.chapterListCallBack?.upChapterList(searchView.query?.toString())
            }

            R.id.menu_load_word_count -> {
                AppConfig.tocCountWords = !item.isChecked
                viewModel.upChapterListAdapter()
            }

            R.id.menu_export_bookmark -> exportDir.launch {
                requestCode = 1
            }

            R.id.menu_export_md -> exportDir.launch {
                requestCode = 2
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return true
    }

    override fun onTocRegexDialogResult(tocRegex: String) {
        viewModel.bookData.value?.let { book ->
            book.tocUrl = tocRegex
            upBookAndToc(book)
        }
    }

    private fun upBookAndToc(book: Book) {
        waitDialog.show()
        viewModel.upBookTocRule(book) {
            waitDialog.dismiss()
            if (ReadBook.book == book) {
                if (it == null) {
                    ReadBook.upMsg(null)
                } else {
                    ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter :
        FragmentPagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItem(position: Int): Fragment {
            return when (position) {
                1 -> BookmarkFragment()
                else -> ChapterListFragment()
            }
        }

        override fun getCount(): Int {
            return 2
        }

        override fun getPageTitle(position: Int): CharSequence {
            return when (position) {
                1 -> getString(R.string.bookmark)
                else -> getString(R.string.chapter_list)
            }
        }
    }
}
