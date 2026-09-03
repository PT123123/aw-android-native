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
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.InboxFragmentBinding

class InboxFragment : Fragment() {

    companion object {
        /** 撤销浮条显示时长：3 秒 */
        private const val UNDO_DURATION_MS = 3000
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

        // 历史面板恢复版本后刷新列表，让卡片正文与更新时间同步变化
        parentFragmentManager.setFragmentResultListener(
            NoteHistoryFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, _ -> loadInitial() }

        loadInitial()
        // 设置项：进入页面即弹出输入框（等首帧渲染完再弹，避免 BottomSheet 抢焦点失败）
        if (InboxPrefs.autoInputOnStart(requireContext())) {
            view.post { if (isAdded) showQuickNoteDialog() }
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
        loadInitial()
    }

    private fun updateFilterBar() {
        val tag = currentTag
        if (tag == null) {
            binding.filterBar.visibility = View.GONE
        } else {
            binding.filterBar.visibility = View.VISIBLE
            binding.filterText.text = "仅显示 #$tag"
        }
    }

    private fun loadInitial() {
        hasMore = true
        fetch(append = false)
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
            menu.add("历史版本").setOnMenuItemClickListener {
                NoteHistoryFragment.newInstance(note.id)
                    .show(parentFragmentManager, "note_history")
                true
            }
            menu.add("删除").setOnMenuItemClickListener {
                deleteNote(note)
                true
            }
            show()
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
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(16), dp(12), dp(16), 0)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_text))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_sub))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.inbox_accent),
            )
        }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(themedCtx)

        // 底部一行：发送按钮靠右下角（无取消按钮，下滑即可关闭）
        val buttonRow = android.widget.FrameLayout(themedCtx).apply {
            setPadding(dp(16), dp(8), dp(16), dp(12))
            addView(
                com.google.android.material.button.MaterialButton(themedCtx, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "➤"
                    contentDescription = "发送"
                    setOnClickListener {
                        val text = input.text?.toString()?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            createQuickNote(text)
                            dialog.dismiss()
                        }
                    }
                },
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.BOTTOM,
                ),
            )
        }

        // 垂直布局：输入框占主要空间，按钮行固定在底部，整体占半屏高度
        val container = android.widget.LinearLayout(themedCtx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                halfScreenHeight,
            )
            addView(
                input,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            // 与编辑笔记面板一致的 Markdown 工具栏
            addView(
                buildMarkdownToolbar(themedCtx, dp, input),
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                buttonRow,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // 键盘弹出后可用高度可能小于半屏（如横屏），动态收缩容器高度防止被裁剪
        container.viewTreeObserver.addOnGlobalLayoutListener {
            val available = dialog.window?.decorView?.height ?: return@addOnGlobalLayoutListener
            val target = minOf(halfScreenHeight, available)
            val lp = container.layoutParams
            if (lp.height != target) {
                lp.height = target
                container.layoutParams = lp
            }
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
                LocalInboxApi.service.createNote(UpsertNotePayload(content = content, tags = parseTags(content)))
                loadInitial()
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
