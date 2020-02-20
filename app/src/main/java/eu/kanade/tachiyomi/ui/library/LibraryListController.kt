package eu.kanade.tachiyomi.ui.library

import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.LibraryUpdateService
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.view.inflate
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.updatePaddingRelative
import eu.kanade.tachiyomi.widget.AutofitRecyclerView
import eu.kanade.tachiyomi.widget.IgnoreFirstSpinnerListener
import kotlinx.android.synthetic.main.filter_bottom_sheet.*
import kotlinx.android.synthetic.main.library_list_controller.*
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min

class LibraryListController(bundle: Bundle? = null) : LibraryController(bundle),
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    FlexibleAdapter.OnItemMoveListener,
    LibraryCategoryAdapter.LibraryListener{

    private lateinit var adapter: LibraryCategoryAdapter

    private lateinit var spinner: Spinner

    private var lastClickPosition = -1

    private var updateScroll = true

    private var spinnerAdapter: SpinnerAdapter? = null

    /**
     * Recycler view of the list of manga.
     */
    private lateinit var recycler: RecyclerView

    override fun contentView():View = recycler_layout

    override fun getTitle(): String? {
        return null
    }

    private var scrollListener = object : RecyclerView.OnScrollListener () {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            val position =
                (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            val order = when (val item = adapter.getItem(position)) {
                is LibraryHeaderItem -> item.category.order
                is LibraryItem -> presenter.categories.find { it.id == item.manga.category }?.order
                else -> null
            }
            if (order != null && order != activeCategory) {
                preferences.lastUsedCategory().set(order)
                activeCategory = order
                val category = presenter.categories.find { it.order == order }

                bottom_sheet.lastCategory = category
                bottom_sheet.updateTitle()
                if (spinner.selectedItemPosition != order + 1) {
                    updateScroll = true
                    spinner.setSelection(order + 1, true)
                }
            }
        }
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        val config = resources?.configuration
        val phoneLandscape = (config?.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (config.screenLayout.and(Configuration.SCREENLAYOUT_SIZE_MASK)) <
            Configuration.SCREENLAYOUT_SIZE_LARGE)

        // pad the recycler if the filter bottom sheet is visible
        if (!phoneLandscape) {
            val height = view.context.resources.getDimensionPixelSize(R.dimen.rounder_radius) + 4.dpToPx
            recycler.updatePaddingRelative(bottom = height)
        }
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(R.layout.library_list_controller, container, false)
    }


    override fun layoutView(view: View) {
        adapter = LibraryCategoryAdapter(this)
        recycler =
            (recycler_layout.inflate(R.layout.library_grid_recycler) as AutofitRecyclerView).apply {
                spanCount = if (libraryLayout == 0) 1 else mangaPerRow
                manager.spanSizeLookup = (object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        if (libraryLayout == 0) return 1
                        val item = this@LibraryListController.adapter.getItem(position)
                        return if (item is LibraryHeaderItem) manager.spanCount else 1
                    }
                })
            }

        recycler.setHasFixedSize(true)
        recycler.adapter = adapter
        recycler_layout.addView(recycler, 0)
        adapter.fastScroller = fast_scroller
        recycler.addOnScrollListener(scrollListener)

        spinner = ReSpinner(view.context)

        val tv = TypedValue()
        activity!!.theme.resolveAttribute(R.attr.actionBarTintColor, tv, true)

        spinner.backgroundTintList = ContextCompat.getColorStateList(
            view.context, tv.resourceId
        )
        (activity as MainActivity).supportActionBar?.customView = spinner
        (activity as MainActivity).supportActionBar?.setDisplayShowCustomEnabled(true)
        spinnerAdapter = SpinnerAdapter(
            view.context,
            R.layout.library_spinner_textview,
            arrayOf(resources!!.getString(R.string.label_library))
        )
        spinnerAdapter?.setDropDownViewResource(R.layout.library_spinner_entry_text)
        spinner.adapter = spinnerAdapter
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            (activity as MainActivity).supportActionBar?.setDisplayShowCustomEnabled(true)
        }
        else if (type == ControllerChangeType.PUSH_EXIT) {
            (activity as MainActivity).toolbar.menu.findItem(R.id
                .action_search)?.collapseActionView()
            (activity as MainActivity).supportActionBar?.setDisplayShowCustomEnabled(false)
        }
    }

    override fun onDestroy() {
        (activity as MainActivity).supportActionBar?.setDisplayShowCustomEnabled(false)
        super.onDestroy()
    }

    override fun onNextLibraryUpdate(mangaMap: List<LibraryItem>, freshStart: Boolean) {
        if (mangaMap.isNotEmpty()) {
            empty_view?.hide()
        } else {
            empty_view?.show(R.drawable.ic_book_black_128dp, R.string.information_empty_library)
        }
        adapter.setItems(mangaMap)


        spinner.onItemSelectedListener = null
        spinnerAdapter = SpinnerAdapter(view!!.context, R.layout.library_spinner_textview,
            presenter.categories.map { it.name }.toTypedArray())
        spinnerAdapter?.setDropDownViewResource(R.layout.library_spinner_entry_text)
        spinner.adapter = spinnerAdapter


        spinner.setSelection(min(presenter.categories.size - 1, activeCategory + 1))
        if (!freshStart) {
            justStarted = false
            if (recycler_layout.alpha == 0f)
                recycler_layout.animate().alpha(1f).setDuration(500).start()


        }else {
            val position = if (freshStart) adapter.indexOf(activeCategory) else null
            if (position != null)
                (recycler.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(position, (-30).dpToPx)
        }
        adapter.isLongPressDragEnabled = canDrag()
        tabsVisibilityRelay.call(false)

        bottom_sheet.lastCategory = presenter.categories[MathUtils.clamp(
            activeCategory, 0, presenter.categories.size - 1
        )]
        bottom_sheet.updateTitle()
        updateScroll = false
        spinner.onItemSelectedListener = IgnoreFirstSpinnerListener { pos ->
            if (updateScroll) {
                updateScroll = false
                return@IgnoreFirstSpinnerListener
            }
            val headerPosition = adapter.indexOf(pos - 1)
            if (headerPosition > -1) {
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                    headerPosition, (-30).dpToPx
                )
            }
        }
    }

    override fun reattachAdapter() {
        val position =
            (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        libraryLayout = preferences.libraryLayout().getOrDefault()
        recycler.adapter = adapter
        (recycler as? AutofitRecyclerView)?.spanCount = if (libraryLayout == 0) 1 else mangaPerRow

        (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        setOnQueryTextChangeListener(searchView) {
            query = it ?: ""
            adapter.setFilter(it)
            adapter.performFilter()
            true
        }
    }

    override fun onCatSortChanged(id: Int?) {
        val catId = (id ?: presenter.categories.find { it.order == activeCategory }?.id)
                ?: return
        presenter.requestCatSortUpdate(catId)
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        super.onDestroyActionMode(mode)
        adapter.mode = SelectableAdapter.Mode.SINGLE
        adapter.clearSelection()
        adapter.notifyDataSetChanged()
        lastClickPosition = -1
        adapter.isLongPressDragEnabled = canDrag()
    }

    override fun setSelection(manga: Manga, selected: Boolean) {
        if (selected) {
            if (selectedMangas.add(manga)) {
                val position = adapter.indexOf(manga)
                if (adapter.mode != SelectableAdapter.Mode.MULTI) {
                    adapter.mode = SelectableAdapter.Mode.MULTI
                }
                launchUI {
                    delay(100)
                    adapter.isLongPressDragEnabled = false
                }
                adapter.toggleSelection(position)
                (recycler.findViewHolderForItemId(manga.id!!) as? LibraryHolder)?.toggleActivation()
            }
        } else {
            if (selectedMangas.remove(manga)) {
                val position = adapter.indexOf(manga)
                lastClickPosition = -1
                if (selectedMangas.isEmpty()) {
                    adapter.mode = SelectableAdapter.Mode.SINGLE
                    adapter.isLongPressDragEnabled = canDrag()
                }
                adapter.toggleSelection(position)
                (recycler.findViewHolderForItemId(manga.id!!) as? LibraryHolder)?.toggleActivation()
            }
        }
    }

    /// Method for single list
    override fun startReading(position: Int) {
        if (adapter.mode == SelectableAdapter.Mode.MULTI) {
            toggleSelection(position)
            return
        }
        val manga = (adapter.getItem(position) as? LibraryItem)?.manga ?: return
        startReading(manga)
    }

    /**
     * Tells the presenter to toggle the selection for the given position.
     *
     * @param position the position to toggle.
     */
    private fun toggleSelection(position: Int) {
        val item = adapter.getItem(position) as? LibraryItem ?: return

        setSelection(item.manga, !adapter.isSelected(position))
        invalidateActionMode()
    }


    override fun canDrag(): Boolean {
        val filterOff = preferences.filterCompleted().getOrDefault() +
            preferences.filterTracked().getOrDefault() +
            preferences.filterUnread().getOrDefault() +
            preferences.filterMangaType().getOrDefault() +
            preferences.filterCompleted().getOrDefault() == 0 &&
            !preferences.hideCategories().getOrDefault()
        return filterOff && adapter.mode != SelectableAdapter.Mode.MULTI
    }

    /**
     * Called when a manga is clicked.
     *
     * @param position the position of the element clicked.
     * @return true if the item should be selected, false otherwise.
     */
    override fun onItemClick(view: View?, position: Int): Boolean {
        // If the action mode is created and the position is valid, toggle the selection.
        val item = adapter.getItem(position) as? LibraryItem ?: return false
        return if (adapter.mode == SelectableAdapter.Mode.MULTI) {
            lastClickPosition = position
            toggleSelection(position)
            true
        } else {
            openManga(item.manga, null)
            false
        }
    }

    /**
     * Called when a manga is long clicked.
     *
     * @param position the position of the element clicked.
     */
    override fun onItemLongClick(position: Int) {
        createActionModeIfNeeded()
        when {
            lastClickPosition == -1 -> setSelection(position)
            lastClickPosition > position -> for (i in position until lastClickPosition)
                setSelection(i)
            lastClickPosition < position -> for (i in lastClickPosition + 1..position)
                setSelection(i)
            else -> setSelection(position)
        }
        lastClickPosition = position
    }

    override fun onActionStateChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        val position = viewHolder?.adapterPosition ?: return
        if (actionState == 2) onItemLongClick(position)
    }

    override fun onUpdateManga(manga: LibraryManga) {
        if (manga.id == null) adapter.notifyDataSetChanged()
        else super.onUpdateManga(manga)
    }

    /**
     * Tells the presenter to set the selection for the given position.
     *
     * @param position the position to toggle.
     */
    private fun setSelection(position: Int) {
        val item = adapter.getItem(position) as? LibraryItem ?: return

        setSelection(item.manga, true)
        invalidateActionMode()
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) { }

    override fun onItemReleased(position: Int) {
        if (adapter.selectedItemCount > 0) return
        val item = adapter.getItem(position) as? LibraryItem ?: return
        val newHeader = adapter.getSectionHeader(position) as? LibraryHeaderItem
        val libraryItems = adapter.getSectionItems(adapter.getSectionHeader(position))
            .filterIsInstance<LibraryItem>()
        val mangaIds = libraryItems.mapNotNull { (it as? LibraryItem)?.manga?.id }
        if (newHeader?.category?.id == item.manga.category) {
            presenter.rearrangeCategory(item.manga.category, mangaIds)
        } else {
            if (newHeader?.category?.mangaSort == null) {
                presenter.moveMangaToCategory(item, newHeader?.category?.id, mangaIds, true)
            } else {
                MaterialDialog(activity!!).message(R.string.switch_to_dnd)
                    .positiveButton(R.string.action_switch) {
                        presenter.moveMangaToCategory(item, newHeader.category.id, mangaIds, true)
                    }.negativeButton(
                        text = resources?.getString(
                            R.string.keep_current_sort,
                            resources!!.getString(newHeader.category.sortRes()).toLowerCase
                                (Locale.getDefault())
                        )
                    ) {
                        presenter.moveMangaToCategory(
                            item, newHeader.category.id, mangaIds, false
                        )
                    }
                    .cancelOnTouchOutside(false)
                    .show()
            }
        }
    }

    override fun shouldMoveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (adapter.selectedItemCount > 1)
            return false
        if (adapter.isSelected(fromPosition))
            toggleSelection(fromPosition)
        return true
    }

    override fun updateCategory(catId: Int): Boolean {
        val category = (adapter.getItem(catId) as? LibraryHeaderItem)?.category ?:
        return false
        val inQueue = LibraryUpdateService.categoryInQueue(category.id)
        snack?.dismiss()
        snack = snackbar_layout.snack(resources!!.getString(
            when {
                inQueue -> R.string.category_already_in_queue
                LibraryUpdateService.isRunning() ->
                    R.string.adding_category_to_queue
                else -> R.string.updating_category_x
            }, category.name))
        if (!inQueue)
            LibraryUpdateService.start(view!!.context, category)
        return true
    }

    override fun sortCategory(catId: Int, sortBy: Int): String {
        presenter.sortCategory(catId, sortBy)
        return ""
    }
}