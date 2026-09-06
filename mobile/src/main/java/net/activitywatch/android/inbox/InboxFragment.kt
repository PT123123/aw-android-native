package net.activitywatch.android.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.InboxFragmentBinding
import net.activitywatch.android.sync.LanPull
import net.activitywatch.android.todo.TodoApi

class InboxFragment : Fragment() {

    companion object {
        /** 撤销浮条显示时长：3 秒 */
        private const val UNDO_DURATION_MS = 3000

        /** savedInstanceState 里保存的当前标签筛选路径（配置变更/进程重建恢复；离开页面不持久化） */
        private const val STATE_TAG = "inbox_current_tag"
    }

    private var _binding: InboxFragmentBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: InboxAdapter

    /** 搜索栏可见时拦截返回键：只收起搜索栏，不退出页面 */
    private lateinit var searchBackCallback: OnBackPressedCallback

    /** 多选模式拦截返回键：退出多选模式 */
    private lateinit var selectionBackCallback: OnBackPressedCallback

    private val items = mutableListOf<NoteResponse>()
    private val limit = 50
    private var hasMore = true
    private var loading = false
    private var currentTag: String? = null
    private var searchQuery: String? = null
    private var sortByUpdated = false
    private var retryCount = 0

    /** 层级标签树（GET /inbox/tags/tree），驱动标签 chips 行 */
    private var tagTree: List<TagNodeResponse> = emptyList()

    /** 局域网拉取：笔记页每次刷新逻辑顺带触发一次，拉完重载列表让远端变更即刷即现 */
    private var lanPullJob: Job? = null
    private var lanPullPending = false

    /** 是否处于多选模式 */
    private var selectionMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = InboxFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalInboxApi.init(requireContext())

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }
        binding.toolbar.inflateMenu(R.menu.inbox_menu)
        binding.toolbar.setOnMenuItemClickListener { onMenuItem(it) }

        adapter = InboxAdapter(
            onGesture = { note, gesture, anchor -> performGesture(note, gesture, anchor) },
            onOverflowClick = { note, anchor -> showItemMenu(note, anchor) },
            onParentClick = { note -> openParent(note) },
            onTagClick = { tag -> toggleTagFilter(tag) },
        )
        adapter.onSelectionChanged = { count ->
            updateSelectionTitle(count)
        }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                val lm = rv.layoutManager as LinearLayoutManager
                if (!loading && hasMore && dy > 0 &&
                    lm.findLastVisibleItemPosition() >= items.size - 6
                ) {
                    loadMore()
                }
            }
        })

        binding.swipe.setOnRefreshListener { loadInitial() }
        binding.fab.setOnClickListener { showQuickNoteDialog() }
        binding.filterClear.setOnClickListener { toggleTagFilter(currentTag) }
        // 筛选条上的 ↑：回到上一级标签路径（项目/工作/xx → 项目/工作 → 项目 → 顶层）
        binding.filterUp.setOnClickListener { applyTagFilterPath(tagParentPath(currentTag.orEmpty())) }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                applySearch()
                true
            } else false
        }
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySearch()
        }

        searchBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeSearchBar()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackCallback)

        selectionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitSelectionMode()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        // 详情面板恢复版本后刷新列表并跳转到该笔记，让卡片正文与更新时间同步变化
        parentFragmentManager.setFragmentResultListener(
            NoteDetailFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val noteId = bundle.getLong(NoteDetailFragment.KEY_NOTE_ID)
            if (noteId > 0) {
                // 延迟等列表渲染完毕再定位
                binding.list.postDelayed({ refreshAndScrollToNote(noteId) }, 400)
            } else {
                loadInitial()
            }
        }

        // 恢复标签筛选路径（仅限配置变更/进程重建；离开页面重新进入时从顶层开始）
        savedInstanceState?.getString(STATE_TAG)?.takeIf { it.isNotBlank() }?.let {
            currentTag = it
            updateFilterBar()
        }

        loadInitial()
        // 设置项：进入页面即弹出输入框（等首帧渲染完再弹，避免 BottomSheet 抢焦点失败）
        if (InboxPrefs.autoInputOnStart(requireContext())) {
            view.post { if (isAdded) showQuickNoteDialog() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentTag?.let { outState.putString(STATE_TAG, it) }
    }

    /**
     * 保存/编辑笔记后调用：刷新该笔记并滚动定位（不整页重载）。
     * 单条回源 getNote：已在列表 → 原位替换；不在（新建/被分页或筛选挡住）→ 加入列表重新排序。
     * 单条拉取失败（笔记已被删 / 网络异常）→ 静默降级为完整 loadInitial()。
     * @param noteId 要定位的笔记 ID（新建/刚编辑的）
     */
    fun refreshAndScrollToNote(noteId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val note = runCatching { LocalInboxApi.service.getNote(noteId) }.getOrNull()
            if (note == null) {
                loadInitial()
                return@launch
            }
            // 不属于当前标签筛选/搜索结果的笔记不强行插入列表，退回整页刷新
            val matchesFilter = (currentTag == null || note.tags.contains(currentTag)) &&
                (searchQuery == null || note.content.contains(searchQuery!!))
            if (!matchesFilter) {
                loadInitial()
                return@launch
            }
            val existingIdx = items.indexOfFirst { it.id == noteId }
            if (existingIdx >= 0) {
                // 原位替换保留已解析的关联预览（新对象的 parentId 为空，直接放会丢灰色预览）
                note.parentId = items[existingIdx].parentId
                note.parentPreview = items[existingIdx].parentPreview
                items[existingIdx] = note
            } else {
                items.add(note)
            }
            adapter.pinnedIds = PinStore.pinnedIdsSet(requireContext())
            sortItems()
            hydrateRelations()
            val idx = items.indexOfFirst { it.id == noteId }
            if (idx >= 0) scrollToPositionWithHighlight(idx)
        }
    }

    /** 滚动到指定位置并让目标行背景闪烁 ~400ms，让用户一眼看到跳转到了哪里 */
    private fun scrollToPositionWithHighlight(idx: Int) {
        val lm = binding.list.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(idx, 0)
        binding.list.post {
            binding.list.findViewHolderForAdapterPosition(idx)?.itemView?.let { view ->
                val original = view.background
                view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.aw_accent))
                view.postDelayed({ view.background = original }, 400)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                setSearchBarVisible(binding.searchBar.visibility == View.GONE)
                true
            }
            R.id.action_sort -> {
                sortByUpdated = !sortByUpdated
                reSort()
                Toast.makeText(
                    requireContext(),
                    if (sortByUpdated) "按更新时间排序" else "按创建时间排序",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
            R.id.action_multiselect -> {
                enterSelectionMode()
                true
            }
            R.id.action_copy -> {
                copySelectedNotes()
                true
            }
            R.id.action_delete -> {
                deleteSelectedNotes()
                true
            }
            R.id.action_select_all -> {
                val allIds = items.map { it.id }
                adapter.toggleSelectAll(allIds)
                true
            }
            else -> false
        }
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selectionBackCallback.isEnabled = true
        binding.fab.visibility = View.GONE
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.inbox_selection_menu)
        adapter.selectionMode = true
        updateSelectionTitle(0)
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectionBackCallback.isEnabled = false
        binding.fab.visibility = View.VISIBLE
        binding.toolbar.title = ""
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.inbox_menu)
        adapter.selectionMode = false
    }

    private fun updateSelectionTitle(count: Int) {
        binding.toolbar.title = if (count > 0) "已选 $count 项" else "选择笔记"
    }

    private fun copySelectedNotes() {
        val selectedNotes = items.filter { it.id in adapter.selectedIds }
        if (selectedNotes.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择笔记", Toast.LENGTH_SHORT).show()
            return
        }
        val textToCopy = selectedNotes.joinToString("\n") { it.content }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("notes", textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "已复制 ${selectedNotes.size} 条笔记", Toast.LENGTH_SHORT).show()
        exitSelectionMode()
        loadInitial()
    }

    private fun deleteSelectedNotes() {
        val selectedNotes = items.filter { it.id in adapter.selectedIds }
        if (selectedNotes.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择笔记", Toast.LENGTH_SHORT).show()
            return
        }
        val count = selectedNotes.size
        // 乐观删除：先从列表移除，再批量调服务端删除
        val selectedIds = adapter.selectedIds.toSet()
        items.removeAll { it.id in selectedIds }
        adapter.submitList(ArrayList(items))
        exitSelectionMode()

        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            binding.root, "已删除 $count 条笔记", UNDO_DURATION_MS
        ).setAnchorView(binding.fab)

        snackbar.setAction("撤销") {
            // 撤销：恢复列表
            loadInitial()
            Toast.makeText(requireContext(), "已撤销删除", Toast.LENGTH_SHORT).show()
        }

        snackbar.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: com.google.android.material.snackbar.Snackbar, event: Int) {
                super.onDismissed(transientBottomBar, event)
                if (event == DISMISS_EVENT_ACTION) return
                // 执行服务端删除
                for (note in selectedNotes) {
                    lifecycleScope.launch {
                        try {
                            LocalInboxApi.service.deleteNote(note.id)
                        } catch (_: Exception) {
                            // 忽略单条删除失败
                        }
                    }
                }
            }
        })
        snackbar.show()
    }

    private fun setSearchBarVisible(visible: Boolean) {
        binding.searchBar.visibility = if (visible) View.VISIBLE else View.GONE
        searchBackCallback.isEnabled = visible
        if (visible) binding.searchInput.requestFocus()
    }

    /** 返回键收起搜索栏：清空关键词并恢复完整列表，不退出页面 */
    private fun closeSearchBar() {
        binding.searchInput.setText("")
        // clearFocus 会触发失焦监听里的 applySearch，把列表重置为无搜索状态
        binding.searchInput.clearFocus()
        setSearchBarVisible(false)
    }

    private fun applySearch() {
        val q = binding.searchInput.text?.toString()?.trim()
        searchQuery = if (q.isNullOrEmpty()) null else q
        loadInitial()
    }

    /** 点正文里的 #标签筛选；再点同一标签（或点 ✕）取消筛选 */
    private fun toggleTagFilter(tag: String?) {
        if (tag.isNullOrEmpty()) return
        currentTag = if (currentTag == tag) null else tag
        updateFilterBar()
        renderTagChips()
        loadInitial()
    }

    /**
     * 直接进入某标签路径（null = 顶层）的筛选，不做切换。
     * 供筛选条 ↑（返回上级）、标签 chips、详情页标签面包屑跳转共用。
     */
    fun applyTagFilterPath(tag: String?) {
        currentTag = tag?.takeIf { it.isNotBlank() }
        updateFilterBar()
        renderTagChips()
        loadInitial()
    }

    private fun updateFilterBar() {
        val tag = currentTag
        if (tag == null) {
            binding.filterBar.visibility = View.GONE
        } else {
            binding.filterBar.visibility = View.VISIBLE
            // 层级 tag 用面包屑展示：项目/工作 → 项目 / 工作
            binding.filterText.text = "仅显示 #${formatTagBreadcrumb(tag)}"
            binding.filterUp.visibility =
                if (tagParentPath(tag) != null) View.VISIBLE else View.GONE
        }
    }

    // ==== 层级标签 chips 行 ====

    /** 拉取标签树并刷新 chips（失败静默：不影响列表，chips 维持上次内容） */
    private fun loadTagTree() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { LocalInboxApi.service.getTagTree() }.onSuccess { tree ->
                tagTree = tree.tags
                renderTagChips()
            }
        }
    }

    private fun treeNodeFor(path: String, nodes: List<TagNodeResponse> = tagTree): TagNodeResponse? {
        for (node in nodes) {
            if (node.path == path) return node
            treeNodeFor(path, node.children)?.let { return it }
        }
        return null
    }

    /** chips 行：未筛选时展示顶层标签，筛选中展示当前路径的直接子标签；点击进入该路径筛选 */
    private fun renderTagChips() {
        val nodes = currentTag?.let { p -> treeNodeFor(p)?.children.orEmpty() } ?: tagTree
        if (nodes.isEmpty()) {
            binding.tagChipsBar.visibility = View.GONE
            return
        }
        binding.tagChipsBar.visibility = View.VISIBLE
        val ctx = binding.tagChipsRow.context
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        binding.tagChipsRow.removeAllViews()
        nodes.forEach { node ->
            val chip = android.widget.TextView(ctx).apply {
                text = "${tagLastSegment(node.path)} ${node.count}"
                setTextColor(ContextCompat.getColor(ctx, R.color.inbox_text))
                textSize = 13f
                background = ContextCompat.getDrawable(ctx, R.drawable.inbox_tag_chip_bg)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                gravity = Gravity.CENTER
                setOnClickListener { applyTagFilterPath(node.path) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8)
            binding.tagChipsRow.addView(chip, lp)
        }
    }

    private fun loadInitial() {
        hasMore = true
        triggerLanPull()
        loadTagTree()
        fetch(append = false)
    }

    /**
     * 并行触发一次局域网拉取（不阻塞本地列表先渲染），拉完重载一次列表。
     * 已有拉取在跑时不重复发起，只记 pending，结束后补一轮（合并连续触发，如搜索连续输入）。
     * 全程静默：非 Wi-Fi / 无配对设备 / 失败都不打扰笔记页。
     */
    private fun triggerLanPull() {
        if (lanPullJob?.isActive == true) {
            lanPullPending = true
            return
        }
        lanPullPending = false
        lanPullJob = viewLifecycleOwner.lifecycleScope.launch {
            val synced = runCatching { LanPull.syncAllPairedNow() }.getOrDefault(0)
            if (synced > 0) fetch(append = false)
            if (lanPullPending) {
                lanPullPending = false
                triggerLanPull()
            }
        }
    }

    private fun loadMore() {
        if (loading || !hasMore) return
        fetch(append = true)
    }

    private fun fetch(append: Boolean) {
        if (loading) return
        loading = true
        if (!append) binding.swipe.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = LocalInboxApi.service.getNotes(
                    limit = limit,
                    offset = if (append) items.size else 0,
                    tag = currentTag,
                    search = searchQuery,
                )
                val sorted = if (sortByUpdated) {
                    list.sortedByDescending {
                        InboxAdapter.parseTime(it.updated_at ?: it.created_at)?.time ?: 0L
                    }
                } else {
                    list
                }
                if (!append) {
                    items.clear()
                    // 新对象需要重新解析关联；原笔记正文缓存保留
                    queriedRelations.clear()
                    resolvedParent.clear()
                }
                items.addAll(sorted)
                hasMore = list.size >= limit
                retryCount = 0
                adapter.pinnedIds = PinStore.pinnedIdsSet(requireContext())
                sortItems()
                hydrateRelations()
            } catch (e: Exception) {
                if (!append && items.isEmpty()) {
                    retryCount++
                    if (retryCount <= 3 && isAdded) {
                        view?.postDelayed({ loadInitial() }, 1500)
                    } else {
                        Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                loading = false
                binding.swipe.isRefreshing = false
            }
        }
    }

    private fun reSort() {
        if (sortByUpdated) {
            items.sortByDescending {
                InboxAdapter.parseTime(it.updated_at ?: it.created_at)?.time ?: 0L
            }
        }
        adapter.submitList(ArrayList(items))
    }

    private fun performGesture(note: NoteResponse, gesture: InboxPrefs.Gesture, anchor: View) {
        when (InboxPrefs.actionFor(requireContext(), gesture)) {
            InboxPrefs.GestureAction.EDIT -> openEditor(note)
            InboxPrefs.GestureAction.COMMENT -> showCommentDialog(note)
            InboxPrefs.GestureAction.PIN -> togglePin(note)
            InboxPrefs.GestureAction.DELETE -> deleteNote(note)
            InboxPrefs.GestureAction.MENU -> showItemMenu(note, anchor)
            InboxPrefs.GestureAction.NONE -> {}
        }
    }

    private fun openEditor(note: NoteResponse?) {
        NoteEditorFragment.newInstance(note)
            .show(parentFragmentManager, "note_editor")
    }

    private fun showItemMenu(note: NoteResponse, anchor: View) {
        val pinned = PinStore.isPinned(requireContext(), note.id)
        val themedCtx = ContextThemeWrapper(requireContext(), R.style.InboxPopupMenu)
        PopupMenu(themedCtx, anchor, Gravity.END).apply {
            menu.add("评论").setOnMenuItemClickListener {
                showCommentDialog(note)
                true
            }
            menu.add(if (pinned) "取消置顶" else "置顶").setOnMenuItemClickListener {
                togglePin(note)
                true
            }
            menu.add("详细信息").setOnMenuItemClickListener {
                NoteDetailFragment.newInstance(note.id)
                    .show(parentFragmentManager, "note_detail")
                true
            }
            menu.add("转为待办").setOnMenuItemClickListener {
                confirmConvertToTodo(note)
                true
            }
            menu.add("删除").setOnMenuItemClickListener {
                deleteNote(note)
                true
            }
            show()
        }
    }

    // ==== 笔记转待办（⑦-C） ====

    /** 转待办是「建 Todo + 删原笔记」的组合动作，二次确认防误操作 */
    private fun confirmConvertToTodo(note: NoteResponse) {
        val themedCtx = ContextThemeWrapper(requireContext(), R.style.InboxPopupMenu)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(themedCtx)
            .setTitle("转为待办")
            .setMessage("把该笔记原样转为一条待办，并删除原笔记？")
            .setPositiveButton("转为待办") { _, _ -> convertNoteToTodo(note) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 顺序调用：先 POST /inbox/todos 再 DELETE /inbox/notes/<id>，失败按约定兜底（见 NoteTodoConverter）。
     * 完成后整页重载，fetch 自带 currentTag —— 转换不会丢失筛选上下文。
     */
    private fun convertNoteToTodo(note: NoteResponse) {
        viewLifecycleOwner.lifecycleScope.launch {
            TodoApi.init(requireContext())
            when (val result = NoteTodoConverter.convert(note)) {
                is NoteTodoConverter.Result.Success -> {
                    Toast.makeText(requireContext(), "已转为待办", Toast.LENGTH_SHORT).show()
                    loadInitial()
                }
                is NoteTodoConverter.Result.TodoCreatedButDeleteFailed -> {
                    Toast.makeText(requireContext(), "已转为待办，原笔记删除失败", Toast.LENGTH_LONG).show()
                    loadInitial()
                }
                is NoteTodoConverter.Result.CreateFailed -> {
                    Toast.makeText(requireContext(), "转换失败：${result.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showCommentDialog(note: NoteResponse) {
        val themedCtx = ContextThemeWrapper(requireContext(), R.style.InboxPopupMenu)
        val input = android.widget.EditText(themedCtx).apply {
            hint = "写点什么…"
            setMinLines(2)
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                0,
            )
            setTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_text))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_sub))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.inbox_accent),
            )
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(themedCtx)
            .setTitle("评论")
            .setView(input)
            .setPositiveButton("发布") { _, _ ->
                val text = input.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) postComment(note, text)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
        input.postDelayed({
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    /** FAB：快速发送笔记（输入框，而非编辑笔记面板） */
    private fun showQuickNoteDialog() {
        val themedCtx = ContextThemeWrapper(requireContext(), R.style.InboxPopupMenu)
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val halfScreenHeight = resources.displayMetrics.heightPixels / 2

        val input = android.widget.EditText(themedCtx).apply {
            hint = "记录点什么… 使用 #标签 标记"
            setMinLines(3)
            // 按内容生长：3 行起步，最多 8 行，超出后输入框内部滚动
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(16), dp(12), dp(16), 0)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_text))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_sub))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.inbox_accent),
            )
        }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(themedCtx)

        // 底部同一行：Markdown 工具栏在左、发送按钮在右、垂直居中对齐（与编辑面板一致；无取消按钮，下滑即可关闭）
        val sendButton = com.google.android.material.button.MaterialButton(
            themedCtx, null, com.google.android.material.R.attr.materialButtonStyle,
        ).apply {
            text = "➤"
            contentDescription = "发送"
            setOnClickListener {
                val text = input.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    createQuickNote(text)
                    dialog.dismiss()
                }
            }
        }
        val bottomRow = android.widget.LinearLayout(themedCtx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(12), 0)
            addView(
                buildMarkdownToolbar(themedCtx, dp, input),
                android.widget.LinearLayout.LayoutParams(
                    0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(
                sendButton,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(4) },
            )
        }

        // 垂直布局：输入框按内容生长、工具栏+发送行紧随其下，弹层整体包裹内容（紧凑输入条）
        val container = android.widget.LinearLayout(themedCtx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(
                input,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                bottomRow,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        dialog.setContentView(container)
        dialog.behavior.peekHeight = halfScreenHeight
        // 键盘弹出时窗口要收缩，让输入区整体抬到键盘上方，不能盖住发送按钮
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        input.requestFocus()
        input.postDelayed({
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    /** 快速发送弹窗底部的 Markdown 工具栏（与 note_editor.xml 中的样式一致） */
    private fun buildMarkdownToolbar(
        ctx: android.content.Context,
        dp: (Int) -> Int,
        input: android.widget.EditText,
    ): android.view.View {
        val subColor = ContextCompat.getColor(requireContext(), R.color.inbox_sub)
        fun item(text: String, desc: String, onClick: () -> Unit) =
            android.widget.TextView(ctx).apply {
                this.text = text
                contentDescription = desc
                gravity = Gravity.CENTER
                setTextColor(subColor)
                setOnClickListener { onClick() }
            }

        val row = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val items = listOf(
            item("#", "标题") { MarkdownTextActions.cycleHeading(input) }.apply {
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            item("B", "加粗") { MarkdownTextActions.toggleWrap(input, "**") }.apply {
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            item("I", "斜体") { MarkdownTextActions.toggleWrap(input, "*") }.apply {
                textSize = 16f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
            },
            item("•", "无序列表") { MarkdownTextActions.toggleBullet(input) }.apply { textSize = 18f },
            item("1.", "有序列表") { MarkdownTextActions.toggleOrdered(input) }.apply { textSize = 15f },
        )
        // ?attr/selectableItemBackgroundBorderless 的水波纹背景
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(
            androidx.appcompat.R.attr.selectableItemBackgroundBorderless, tv, true
        )
        val ripple = androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
        items.forEach { v ->
            v.background = ripple?.constantState?.newDrawable()?.mutate()
            val lp = android.widget.LinearLayout.LayoutParams(dp(42), dp(38))
            lp.marginEnd = dp(4)
            row.addView(v, lp)
        }

        return android.widget.HorizontalScrollView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isHorizontalScrollBarEnabled = false
            setPadding(dp(16), dp(2), dp(16), 0)
            addView(
                row,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun createQuickNote(content: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = LocalInboxApi.service.createNote(
                    UpsertNotePayload(content = content, tags = parseTags(content)),
                )
                // 跳转定位到新笔记并高亮（不属于当前筛选/搜索时退回整页刷新）
                refreshAndScrollToNote(saved.id)
                Toast.makeText(requireContext(), "已发送", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "发送失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun postComment(note: NoteResponse, content: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                LocalInboxApi.service.addComment(note.id, CreateCommentPayload(content = content))
                Toast.makeText(requireContext(), "评论已发布", Toast.LENGTH_SHORT).show()
                // 刷新列表，让评论笔记立刻显示并带上 ↖️ 原笔记灰色预览
                loadInitial()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "评论失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePin(note: NoteResponse) {
        val nowPinned = PinStore.toggle(requireContext(), note.id)
        adapter.pinnedIds = PinStore.pinnedIdsSet(requireContext())
        sortItems()
        Toast.makeText(requireContext(), if (nowPinned) "已置顶" else "已取消置顶", Toast.LENGTH_SHORT).show()
    }

    private fun sortItems() {
        val pins = PinStore.pinnedIdsSet(requireContext())
        items.sortWith(
            compareBy(
                { if (it.id in pins) 0 else 1 },
                {
                    -(InboxAdapter.parseTime(it.updated_at ?: it.created_at)?.time ?: 0L)
                },
            ),
        )
        adapter.submitList(ArrayList(items))
    }

    // ==== 关联（评论→原笔记）解析 ====

    /** 已请求过 relations 的笔记 id，避免重复查询 */
    private val queriedRelations = mutableSetOf<Long>()

    /** 原笔记 id → 完整笔记缓存（用于点击跳转编辑页） */
    private val parentNoteCache = mutableMapOf<Long, NoteResponse>()

    /** 已解析出 parentId 的笔记 id */
    private val resolvedParent = mutableSetOf<Long>()

    private fun truncateForPreview(s: String, max: Int = 100): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max) + "..."
    }

    /**
     * 列表加载后异步补齐评论笔记的原笔记预览：
     * 对每条笔记查 relations，若本笔记是评论(source=本笔记, type=Comment)，
     * 取 target 原笔记正文前 100 字做灰色预览。
     */
    private fun hydrateRelations() {
        val toResolve = items.filter {
            it.parentId == null && it.id !in queriedRelations && it.id !in resolvedParent
        }
        if (toResolve.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            for (note in toResolve) {
                queriedRelations.add(note.id)
                try {
                    val relations = LocalInboxApi.service.getNoteRelations(note.id)
                    val parentRel = relations.firstOrNull {
                        it.relation_type.equals("Comment", ignoreCase = true) &&
                            it.source_note_id == note.id
                    } ?: continue
                    val parentId = parentRel.target_note_id
                    val parent = parentNoteCache.getOrPut(parentId) {
                        LocalInboxApi.service.getNote(parentId)
                    }
                    // 用新实例替换（而非原地修改），否则 DiffUtil 检测不到变化，
                    // 灰色预览不会重新绑定显示
                    val updated = note.copy()
                    updated.parentId = parentId
                    updated.parentPreview = truncateForPreview(parent.content)
                    val idx = items.indexOfFirst { it.id == note.id }
                    if (idx >= 0) items[idx] = updated
                    resolvedParent.add(note.id)
                    adapter.submitList(ArrayList(items))
                } catch (_: Exception) {
                    // 单条解析失败不影响其他
                }
            }
        }
    }

    /** 点击评论卡片上的原笔记预览 → 跳到原笔记编辑页 */
    private fun openParent(note: NoteResponse) {
        val parentId = note.parentId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val parent = parentNoteCache.getOrPut(parentId) {
                    LocalInboxApi.service.getNote(parentId)
                }
                openEditor(parent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "无法打开原笔记：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 待真正删除的笔记（撤销窗口期内先从列表移除，3 秒后才调服务端删除） */
    private var pendingDeleteNote: NoteResponse? = null
    private var pendingDeleteIndex = -1

    private fun deleteNote(note: NoteResponse) {
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            binding.root, "已删除笔记", UNDO_DURATION_MS
        ).setAnchorView(binding.fab)

        snackbar.setAction("撤销") {
            // 用户点击撤销：恢复列表项，且不调用服务端删除
            val idx = pendingDeleteIndex.coerceIn(0, items.size)
            pendingDeleteNote?.let { items.add(idx, it) }
            sortItems()
            pendingDeleteNote = null
            pendingDeleteIndex = -1
            Toast.makeText(requireContext(), "已撤销删除", Toast.LENGTH_SHORT).show()
        }

        snackbar.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: com.google.android.material.snackbar.Snackbar, event: Int) {
                super.onDismissed(transientBottomBar, event)
                // 点击"撤销"时不删除；其他情况（超时/被新浮条顶替/手动滑走）执行真正的删除
                if (event == DISMISS_EVENT_ACTION) return
                val target = pendingDeleteNote ?: return
                pendingDeleteNote = null
                pendingDeleteIndex = -1
                executeDeleteOnServer(target)
            }
        })

        // 乐观移除：先从列表中拿掉，等待撤销窗口结束再真正删除
        pendingDeleteNote = note
        pendingDeleteIndex = items.indexOfFirst { it.id == note.id }
        if (pendingDeleteIndex >= 0) {
            items.removeAt(pendingDeleteIndex)
            adapter.submitList(ArrayList(items))
        }
        snackbar.show()
    }

    private fun executeDeleteOnServer(note: NoteResponse) {
        lifecycleScope.launch {
            try {
                val resp = LocalInboxApi.service.deleteNote(note.id)
                if (!resp.isSuccessful) {
                    Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
