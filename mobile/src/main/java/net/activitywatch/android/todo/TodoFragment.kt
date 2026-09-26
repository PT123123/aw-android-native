package net.activitywatch.android.todo

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.TodoFragmentBinding
import net.activitywatch.android.inbox.TagSuggestionView
import net.activitywatch.android.inbox.TODO_BATCH_EXAMPLE
import net.activitywatch.android.inbox.buildMarkdownToolbar
import net.activitywatch.android.inbox.formatTagBreadcrumb
import net.activitywatch.android.inbox.promptBatchCommands
import net.activitywatch.android.inbox.tagParentPath
import net.activitywatch.android.sync.LanPull
import net.activitywatch.android.sync.RemoteSyncBus
import net.activitywatch.android.ui.GradientBackground

/**
 * 任务页 —— aw-qtui TodoPage 的手机端映射（契约 §5）。
 *
 * 桌面端三栏（侧栏导航 + 任务列表 + 详情面板）在手机上折叠为：
 * 顶部工具栏 → 视图 chips（收集箱 / 今天 / 最近 7 天 / 全部）→ 清单 chips → 快速添加 → 任务列表 → 全局进度，
 * 详情面板改为全屏 [TodoDetailFragment]。
 *
 * 页面只依赖 [TodoSource]：所有写操作都等数据源的 onChange 后按新快照重渲染，**不做乐观更新**。
 */
class TodoFragment : Fragment() {

    private var _binding: TodoFragmentBinding? = null
    private val binding get() = _binding!!

    private val source: TodoSource get() = TodoRepository.source(requireContext())

    private var mLists: List<TodoList> = emptyList()
    private var mTasks: List<TodoTask> = emptyList()

    private var currentView = TodoView.INBOX
    private var currentListId = 0L
    private var showCompleted = false
    private var currentSortMode = TodoSortMode.DEFAULT

    /** 当前标签筛选（null = 未筛选；层级匹配同收件箱，跨视图/清单切换保留） */
    private var currentTag: String? = null

    /** 反向筛选：被排除的标签路径集合（隐藏含这些标签及其子标签的任务） */
    private val excludedTags = linkedSetOf<String>()

    /** 搜索关键字（null = 未搜索；匹配标题/备注，与视图/清单/标签筛选叠加） */
    private var searchQuery: String? = null

    /**
     * 新建任务后要滚动定位的任务 id（-1 = 无待定位）。
     * 由 [TodoSource.createTask] 的 onCreated 回调写入，下一次 render 时消费。
     */
    private var pendingScrollTaskId: Long = -1L

    /** 局域网拉取：刷新/进页时先跨设备拉一轮，有落地才重拉（同收件箱页） */
    private var lanPullJob: Job? = null
    private var lanPullPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 抽屉视图入口：nav_todo_inbox 通过参数落到收集箱；today/next7/all 已不再由侧边栏传入
        when (arguments?.getString(ARG_VIEW)) {
            "today" -> currentView = TodoView.TODAY
            "next7" -> currentView = TodoView.NEXT7
            "all" -> currentView = TodoView.ALL
        }
    }

    /** 刚新建的清单名：等数据源快照里出现后自动选中 */
    private var pendingSelectListName: String? = null

    private lateinit var adapter: TodoAdapter

    private val dataChanged: () -> Unit = { postRender() }
    private val errorToast: (String) -> Unit = { msg -> toast(msg) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = TodoFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 界面主题：铺渐变背景，工具栏透明以透出顶部渐变
        GradientBackground.applyPage(requireContext(), binding.root, binding.toolbar)

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }
        // 右上角 ⋮ 菜单：新建清单 + 排序子菜单
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> {
                    setSearchBarVisible(binding.searchBar.visibility == View.GONE)
                    true
                }
                R.id.action_multiselect -> {
                    enterSelectionMode()
                    true
                }
                R.id.action_tag_filter -> {
                    openTagFilterDialog()
                    true
                }
                R.id.action_commands -> {
                    showBatchCommandsDialog()
                    true
                }
                R.id.action_copy -> {
                    copySelectedTasks()
                    true
                }
                R.id.action_new_list -> {
                    showNewListDialog()
                    true
                }
                R.id.action_sort_default -> { setSortMode(TodoSortMode.DEFAULT); true }
                R.id.action_sort_recent -> { setSortMode(TodoSortMode.RECENTLY_ADDED); true }
                R.id.action_sort_reverse -> { setSortMode(TodoSortMode.REVERSED); true }
                R.id.action_sort_priority -> { setSortMode(TodoSortMode.BY_PRIORITY); true }
                R.id.action_sort_due -> { setSortMode(TodoSortMode.BY_DUE_DATE); true }
                R.id.action_complete -> {
                    completeSelectedTasks()
                    true
                }
                R.id.action_delete -> {
                    deleteSelectedTasks()
                    true
                }
                R.id.action_select_all -> {
                    val allIds = adapter.getAllTaskIds()
                    adapter.toggleSelectAll(allIds)
                    true
                }
                else -> false
            }
        }

        adapter = TodoAdapter(
            onToggle = { task, checked -> source.setTaskCompleted(task.id, checked) },
            onClick = { task -> openDetail(task) },
            onToggleCompleted = {
                showCompleted = !showCompleted
                adapter.setShowCompleted(showCompleted)
                updateEmptyState()
            },
        )
        adapter.onSelectionChanged = { count ->
            updateSelectionTitle(count)
        }
        // 点任务行内的标签 = 进入/取消该标签筛选（同收件箱）
        adapter.onTagClick = { tag ->
            applyTagFilter(if (currentTag == tag) null else tag)
        }
        // 卡片上直接勾选/取消子任务（写数据源后 reload 会刷新进度与删除线）
        adapter.onSubtaskToggle = { task, subId, completed ->
            val updated = task.copy().apply {
                subtasks = subtasks.map {
                    if (it.id == subId) it.copy(completed = completed) else it.copy()
                }.toMutableList()
            }
            source.updateTask(updated)
        }
        // 筛选条：✕ 清除全部（含反向筛选）；↑ 回到上级标签路径（项目/工作/xx → 项目/工作）
        binding.filterClear.setOnClickListener { clearAllTagFilters() }
        binding.filterUp.setOnClickListener { applyTagFilter(tagParentPath(currentTag.orEmpty())) }

        // 搜索：软键盘搜索键提交；失焦也提交（同收件箱）
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                applySearch()
                true
            } else false
        }
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySearch()
        }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { loadInitial() }
        // 右下角按钮 = 从底部展开快速添加输入层（同笔记页快速输入）
        binding.fab.setOnClickListener { showQuickAddDialog() }

        // 多选模式拦截返回键：退出多选模式
        selectionBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitSelectionMode()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        // 搜索条可见时拦截返回键：收起搜索条并清除搜索（同收件箱）
        searchBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                setSearchBarVisible(false)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackCallback)

        currentSortMode = loadSortMode()

        TodoRepository.addErrorListener(errorToast)
        TodoRepository.addListener(dataChanged)
        loadInitial()
        render()

        // 应用级「远端数据已落地」事件：同步链路成功 / 修订号变化时重拉一次。
        // repeatOnLifecycle(STARTED)：离开页面自动停止订阅与 15s 修订号轮询，回来再续。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RemoteSyncBus.events.collectLatest { loadInitial() }
                }
                launch { RemoteSyncBus.pollRevisionLoop() }
            }
        }
    }

    // ── 跨设备拉取（笔记页 loadInitial/triggerLanPull 同款） ──

    /** 任务页刷新形态（对齐收件箱 loadInitial）：先拉跨设备、有落地才重拉列表 */
    private fun loadInitial() {
        triggerLanPull()
        source.load()
    }

    /**
     * 并行触发一次局域网拉取（不阻塞本机列表先渲染），拉完有落地再重载一次列表。
     * 已有拉取在跑时不重复发起，只记 pending，结束后补一轮（合并连续触发）。
     * 全程静默：非 Wi-Fi / 无配对设备 / 失败都不打扰任务页。
     */
    private fun triggerLanPull() {
        if (lanPullJob?.isActive == true) {
            lanPullPending = true
            return
        }
        lanPullPending = false
        lanPullJob = viewLifecycleOwner.lifecycleScope.launch {
            val synced = runCatching { LanPull.syncAllPairedNow() }.getOrDefault(0)
            if (synced > 0) source.load()
            if (lanPullPending) {
                lanPullPending = false
                triggerLanPull()
            }
        }
    }

    override fun onDestroyView() {
        TodoRepository.removeListener(dataChanged)
        TodoRepository.removeErrorListener(errorToast)
        _binding = null
        super.onDestroyView()
    }

    // ── 多选模式 ────────────────────────────────────────

    private var selectionMode = false
    private lateinit var selectionBackCallback: androidx.activity.OnBackPressedCallback
    private lateinit var searchBackCallback: androidx.activity.OnBackPressedCallback

    private fun enterSelectionMode() {
        selectionMode = true
        selectionBackCallback.isEnabled = true
        adapter.selectionMode = true
        binding.fab.visibility = View.GONE
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.todo_selection_menu)
        updateSelectionTitle(0)
        android.util.Log.d("TodoFragment", "enterSelectionMode called, adapter.selectionMode=${adapter.selectionMode}")
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectionBackCallback.isEnabled = false
        adapter.selectionMode = false
        binding.fab.visibility = View.VISIBLE
        binding.toolbar.title = ""
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.menu_todo)
        // 重新勾选当前排序模式
        updateSortMenuChecks()
    }

    private fun updateSelectionTitle(count: Int) {
        binding.toolbar.title = if (count > 0) "已选 $count 项" else "选择任务"
    }

    private fun completeSelectedTasks() {
        val ids = adapter.selectedIds.toList()
        if (ids.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择任务", Toast.LENGTH_SHORT).show()
            return
        }
        for (id in ids) {
            source.setTaskCompleted(id, true)
        }
        Toast.makeText(requireContext(), "已完成 ${ids.size} 项", Toast.LENGTH_SHORT).show()
        exitSelectionMode()
    }

    private fun deleteSelectedTasks() {
        val ids = adapter.selectedIds.toList()
        if (ids.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择任务", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除任务")
            .setMessage("确定删除选中的 ${ids.size} 项任务？")
            .setPositiveButton("删除") { _, _ ->
                for (id in ids) {
                    source.deleteTask(id)
                }
                Toast.makeText(requireContext(), "已删除 ${ids.size} 项", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun postRender() {
        val v = view ?: return
        v.post { render() }
    }

    private fun toast(msg: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    // ── 渲染（数据快照 → UI） ─────────────────────────────

    private fun render() {
        if (_binding == null) return
        mLists = source.lists()
        mTasks = source.tasks()
        binding.swipe.isRefreshing = false

        // 当前清单被删掉时回落到收集箱
        if (currentView == TodoView.LIST && mLists.none { it.id == currentListId }) {
            currentView = TodoView.INBOX
            currentListId = 0L
        }
        // 新建的清单出现后自动切到它
        pendingSelectListName?.let { name ->
            mLists.firstOrNull { it.name == name }?.let {
                currentView = TodoView.LIST
                currentListId = it.id
                showCompleted = false
                pendingSelectListName = null
            }
        }

        adapter.setListColors(mLists.associate { it.id to it.argb })
        rebuildChips()
        updateSortMenuChecks()
        updateFilterBar()

        val (open0, done0) = visibleTasks(
            mTasks.filter { it.id !in TodoRepository.pendingDeleteIds() },
            currentView, currentListId, currentSortMode,
        )
        // 标签（含反向/排除）+ 搜索筛选叠加在视图/清单之上（视图 AND 清单 AND 标签 AND 搜索）
        val open = open0.filter { tagFilterMatches(it) && matchesSearch(it) }
        val done = done0.filter { tagFilterMatches(it) && matchesSearch(it) }
        binding.toolbar.subtitle = "${viewTitle()} · ${open.size} 项待办"
        adapter.submit(open, done, showCompleted)
        updateEmptyState()
        updateProgress()
        consumePendingScroll()
        // 有待删任务在撤销窗口期内 → 弹撤销悬浮条（笔记同款）
        if (TodoRepository.pendingDeleteIds().isNotEmpty()) showPendingDeleteSnackbar()
    }

    // ── 撤销删除（笔记同款）：详情页删除先从列表隐藏，窗口超时才真正调服务端 ──

    private fun showPendingDeleteSnackbar() {
        val ids = TodoRepository.pendingDeleteIds()
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            if (ids.size == 1) "已删除任务" else "已删除 ${ids.size} 项任务",
            UNDO_DELETE_WINDOW_MS,
        ).setAnchorView(binding.fab)

        snackbar.setAction("撤销") {
            // 撤销：从窗口期取回任务并重新渲染（服务端未动过，无需重插）
            ids.forEach { TodoRepository.takePendingDelete(it) }
            render()
        }
        snackbar.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
            override fun onDismissed(bar: com.google.android.material.snackbar.Snackbar, event: Int) {
                super.onDismissed(bar, event)
                // 点撤销时不删除；其他（超时/被新浮条顶替/手动滑走）执行真正删除
                if (event == DISMISS_EVENT_ACTION) return
                TodoRepository.pendingDeleteIds().forEach { id ->
                    TodoRepository.takePendingDelete(id)
                    source.deleteTask(id)
                }
            }
        })
        snackbar.show()
    }

    /**
     * 新建任务后的定位：数据源异步生效，等快照里出现该任务再滚动。
     * 找不到（如「最近 7 天」视图下新建的无期限任务本就不属于该视图）时保留 pending，
     * 由下一次 render 或切换视图清除，不强行跳转。
     */
    private fun consumePendingScroll() {
        val id = pendingScrollTaskId
        if (id == -1L) return
        val pos = adapter.indexOfTask(id)
        if (pos < 0) return
        pendingScrollTaskId = -1L
        scrollToTaskWithHighlight(pos, id)
    }

    /** 滚动到指定位置并让目标行背景闪烁 ~400ms，让用户一眼看到刚新建的任务 */
    private fun scrollToTaskWithHighlight(pos: Int, taskId: Long) {
        val lm = binding.list.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(pos, 0)
        // 落位后交给 adapter 做基于任务 id 的高亮（主线程定时清除，重绑安全）
        binding.list.post { adapter.flashHighlight(taskId) }
    }

    private fun updateEmptyState() {
        val (open0, done0) = visibleTasks(mTasks, currentView, currentListId)
        val open = open0.filter { tagFilterMatches(it) && matchesSearch(it) }
        val done = done0.filter { tagFilterMatches(it) && matchesSearch(it) }
        val nothing = open.isEmpty() && (done.isEmpty() || !showCompleted)
        binding.empty.visibility = if (nothing) View.VISIBLE else View.GONE
        binding.empty.text = if (open.isEmpty() && done.isEmpty()) {
            when {
                currentTag != null || searchQuery != null || excludedTags.isNotEmpty() -> "没有符合条件的任务"
                else -> "暂无任务\n点右下角 ＋ 添加任务"
            }
        } else {
            "该视图下的任务已全部完成"
        }
    }

    // ── 标签筛选（同收件箱：点行内标签 / 筛选条 ✕ / ↑） ──

    /** 进入/切换/清除标签筛选；视图与清单 chips 不受影响（叠加过滤） */
    private fun applyTagFilter(tag: String?) {
        currentTag = tag?.takeIf { it.isNotBlank() }
        updateFilterBar()
        render()
    }

    /** 一次清掉「仅显示」与全部「排除」筛选 */
    private fun clearAllTagFilters() {
        currentTag = null
        excludedTags.clear()
        updateFilterBar()
        render()
    }

    /** 标签筛选（含反向/排除）：命中「仅显示」且不命中任一「排除」才保留 */
    private fun tagFilterMatches(task: TodoTask): Boolean {
        if (!task.matchesTag(currentTag)) return false
        for (ex in excludedTags) {
            if (task.tags.any { it == ex || it.startsWith("$ex/") }) return false
        }
        return true
    }

    /** 标签筛选对话框：line edit 设「仅显示」，勾选列表设「排除」（反向筛选） */
    private fun openTagFilterDialog() {
        val ctx = requireContext()
        val known = mTasks.flatMap { it.tags }.distinct().sorted()
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        container.addView(TextView(ctx).apply {
            text = "仅显示标签（留空 = 不限，支持 父/子 层级）"
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_secondary))
            textSize = 13f
        })
        val inc = EditText(ctx).apply {
            setText(currentTag ?: "")
            hint = "如 项目/工作"
            setTextColor(ContextCompat.getColor(ctx, R.color.inbox_text))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.inbox_sub))
        }
        container.addView(inc)
        container.addView(TextView(ctx).apply {
            text = "排除标签（反向筛选：不显示含该标签及其子标签的任务）"
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_secondary))
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        val checks = mutableListOf<Pair<String, android.widget.CheckBox>>()
        if (known.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "（暂无标签）"
                setTextColor(ContextCompat.getColor(ctx, R.color.inbox_sub))
                textSize = 13f
            })
        } else {
            known.forEach { tag ->
                val cb = android.widget.CheckBox(ctx).apply {
                    text = tag
                    isChecked = excludedTags.contains(tag)
                    setTextColor(ContextCompat.getColor(ctx, R.color.inbox_text))
                }
                checks.add(tag to cb)
                container.addView(cb)
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle("标签筛选")
            .setView(android.widget.ScrollView(ctx).apply { addView(container) })
            .setPositiveButton("应用") { _, _ ->
                currentTag = inc.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                excludedTags.clear()
                checks.forEach { (tag, cb) -> if (cb.isChecked) excludedTags.add(tag) }
                updateFilterBar()
                render()
            }
            .setNeutralButton("清除筛选") { _, _ -> clearAllTagFilters() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateFilterBar() {
        val tag = currentTag
        if (tag == null && excludedTags.isEmpty()) {
            binding.filterBar.visibility = View.GONE
            return
        }
        binding.filterBar.visibility = View.VISIBLE
        val parts = mutableListOf<String>()
        // 层级 tag 用面包屑展示：项目/工作 → 项目 / 工作
        if (tag != null) parts.add("仅显示 #${formatTagBreadcrumb(tag)}")
        if (excludedTags.isNotEmpty())
            parts.add("排除 " + excludedTags.joinToString(" ") { "⊘#${formatTagBreadcrumb(it)}" })
        binding.filterText.text = parts.joinToString(" · ")
        binding.filterUp.visibility =
            if (tag != null && tagParentPath(tag) != null) View.VISIBLE else View.GONE
    }

    /** 批量操作指令（任务）：粘贴 AI 返回的 JSON 并 POST /inbox/todos/batch */
    private fun showBatchCommandsDialog() {
        promptBatchCommands(
            requireContext(),
            "批量操作指令（任务）",
            "粘贴 AI 返回的 JSON 并执行。action 支持 create / update / delete / restore / add_tags / " +
                "remove_tags / set_tags / set_completed / move / set_priority / set_due / add_subtask / " +
                "remove_subtask / set_subtask / comment；目标用 uuid（推荐）或 id。点「复制示例」可拿到模板。",
            TODO_BATCH_EXAMPLE,
        ) { executeBatchCommands(it) }
    }

    private fun executeBatchCommands(text: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val parsed = com.google.gson.JsonParser.parseString(text)
                val body = when {
                    parsed.isJsonArray ->
                        com.google.gson.JsonObject().apply { add("operations", parsed) }
                    parsed.isJsonObject && parsed.asJsonObject.has("operations") -> parsed.asJsonObject
                    else -> throw IllegalArgumentException("需要包含 operations 字段，或直接给 operations 数组")
                }
                val resp = TodoApi.service.batchTodos(body)
                val applied = resp.get("applied")?.asInt ?: 0
                val failed = resp.get("failed")?.asInt ?: 0
                Toast.makeText(requireContext(), "指令完成：成功 $applied / 失败 $failed", Toast.LENGTH_LONG).show()
                source.load()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "执行失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 多选复制任务：附带唯一 ID（uuid）供 AI 指令引用 */
    private fun copySelectedTasks() {
        val selected = mTasks.filter { it.id in adapter.selectedIds }
        if (selected.isEmpty()) {
            toast("请先选择任务")
            return
        }
        val text = selected.joinToString("\n") { task ->
            val uid = task.uuid?.takeIf { it.isNotBlank() } ?: "local:${task.id}"
            val body = if (task.notes.isBlank()) task.title else "${task.title}\n${task.notes}"
            "$body\nID: $uid"
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("tasks", text))
        Toast.makeText(requireContext(), "已复制 ${selected.size} 条任务（含 ID）", Toast.LENGTH_SHORT).show()
    }

    // ── 搜索（同收件箱：工具栏 🔍 开关搜索条） ───────────

    private fun setSearchBarVisible(visible: Boolean) {
        binding.searchBar.visibility = if (visible) View.VISIBLE else View.GONE
        searchBackCallback.isEnabled = visible
        if (visible) {
            binding.searchInput.requestFocus()
        } else {
            // 先清空再 clearFocus：失焦监听里的 applySearch 会按空关键字重置列表
            binding.searchInput.setText("")
            binding.searchInput.clearFocus()
            searchQuery = null
            render()
        }
    }

    private fun applySearch() {
        val q = binding.searchInput.text?.toString()?.trim()
        val newQuery = q?.takeIf { it.isNotEmpty() }
        if (searchQuery == newQuery) return
        searchQuery = newQuery
        render()
    }

    /** 搜索匹配：标题或备注包含关键字（大小写敏感，与收件箱 content.contains 一致） */
    private fun matchesSearch(task: TodoTask): Boolean {
        val q = searchQuery ?: return true
        return task.title.contains(q) || task.notes.contains(q)
    }

    /** 底部全局进度：已完成 X / Y（契约 §5.4 统计范围是全局而非当前视图） */
    private fun updateProgress() {
        val total = mTasks.size
        val doneCount = mTasks.count { it.completed }
        binding.progressBar.progress = if (total == 0) 0 else doneCount * 100 / total
        binding.progressText.text = "已完成 $doneCount / $total"
    }

    // ── 视图 / 清单导航 ──────────────────────────────────

    private fun viewTitle(): String = when (currentView) {
        TodoView.INBOX -> "收集箱"
        TodoView.TODAY -> "今天"
        TodoView.NEXT7 -> "最近 7 天"
        TodoView.ALL -> "全部"
        TodoView.LIST -> mLists.firstOrNull { it.id == currentListId }?.name ?: "清单"
    }

    private fun rebuildChips() {
        binding.viewChips.removeAllViews()
        binding.listChips.removeAllViews()

        for (v in listOf(TodoView.INBOX, TodoView.TODAY, TodoView.NEXT7, TodoView.ALL)) {
            val selected = currentView == v
            binding.viewChips.addView(
                makeChip(viewTitleOf(v), selected, dotColor = null) {
                    currentView = v
                    currentListId = 0L
                    showCompleted = false           // 切换视图重置折叠（契约 §5.4）
                    pendingScrollTaskId = -1L       // 切视图即放弃待定位，避免之后意外滚动
                    render()
                }
            )
        }

        for (l in mLists) {
            val selected = currentView == TodoView.LIST && currentListId == l.id
            binding.listChips.addView(
                makeChip(l.name, selected, dotColor = l.argb) {
                    currentView = TodoView.LIST
                    currentListId = l.id
                    showCompleted = false
                    pendingScrollTaskId = -1L       // 同上：切换清单即放弃待定位
                    render()
                }.apply {
                    // 长按管理清单：重命名 / 删除
                    setOnLongClickListener {
                        showListMenu(l)
                        true
                    }
                }
            )
        }
    }

    private fun viewTitleOf(v: TodoView): String = when (v) {
        TodoView.INBOX -> "收集箱"
        TodoView.TODAY -> "今天"
        TodoView.NEXT7 -> "最近 7 天"
        TodoView.ALL -> "全部"
        TodoView.LIST -> "清单"
    }

    private fun makeChip(
        label: String,
        selected: Boolean,
        dotColor: Int?,
        onClick: () -> Unit,
    ): TextView {
        val tv = TextView(requireContext())
        if (dotColor != null) {
            val sp = SpannableString("● $label")
            sp.setSpan(ForegroundColorSpan(dotColor), 0, 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = sp
        } else {
            tv.text = label
        }
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.textSize = 13f
        tv.setPadding(dp(12), dp(6), dp(12), dp(6))
        tv.background = ContextCompat.getDrawable(
            requireContext(),
            if (selected) R.drawable.todo_chip_bg_selected else R.drawable.todo_chip_bg,
        )
        tv.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selected) R.color.aw_bg else R.color.aw_text_secondary,
            )
        )
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.marginEnd = dp(8)
        tv.layoutParams = lp
        return tv
    }

    // ── 快速添加（契约 §5.5；底部弹层样式与笔记页快速输入一致） ──

    /** FAB：从底部展开快速添加输入层（BottomSheetDialog，发送后关闭，下滑可关） */
    private fun showQuickAddDialog() {
        val themedCtx = ContextThemeWrapper(requireContext(), R.style.InboxPopupMenu)
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val halfScreenHeight = resources.displayMetrics.heightPixels / 2

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(themedCtx)

        val input = EditText(themedCtx).apply {
            hint = "添加任务…  输入 # 打标签"
            // 单行输入（任务标题不换行），紧凑样式与笔记页快速输入一致；
            // paddingBottom 必须保留：否则文字下端会被蓝色下划线（背景底边）压住
            setMinLines(1)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_text))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_sub))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.inbox_accent),
            )
            // 标签筛选激活时预填 #当前标签（同收件箱快速输入的习惯，新任务自动带上筛选标签）
            currentTag?.let { setText("#$it ") }
        }

        // # 标签建议下拉（显示在输入框上方，与笔记页快速输入一致）
        val suggestionView = TagSuggestionView(themedCtx)
        suggestionView.listener = listener@{ selectedTag ->
            val text = input.text?.toString() ?: return@listener
            val cursorPos = input.selectionStart
            val (prefix, tagStart) = TodoTagSuggest.extractTagPrefix(text, cursorPos)
            if (tagStart >= 0 && cursorPos > 0) {
                // 从 # 的位置开始整体替换为选中的标签
                input.text.replace(tagStart - 1, cursorPos, "#$selectedTag")
                input.setSelection(tagStart + selectedTag.length)
            } else {
                // 没有 # 前缀时直接在光标处插入（与笔记页快速输入一致）
                input.text.insert(cursorPos, "#$selectedTag ")
                input.setSelection(cursorPos + selectedTag.length + 2)
            }
        }

        // 输入 # 后实时给出标签建议（建议源：任务 tag ∪ 笔记标签树）
        var lastChangeWasDeletion = false
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                val cursorPos = input.selectionStart
                if (cursorPos <= 0) {
                    suggestionView.hide()
                    return
                }
                val (prefix, tagStart) = TodoTagSuggest.extractTagPrefix(text, cursorPos)
                // 不在 # 串内 → 不给建议
                if (tagStart < 0) {
                    suggestionView.hide()
                    return
                }
                // 输入标签后又删除回退到只剩 # → 不给建议（避免退格时整个标签列表挂出来）
                if (prefix.isEmpty() && lastChangeWasDeletion) {
                    suggestionView.hide()
                    return
                }
                // 刚输入 #（prefix 为空）或已有前缀 → 给建议（空前缀返回全量标签）
                viewLifecycleOwner.lifecycleScope.launch {
                    val counts = TodoTagSuggest.tagCounts(source.tasks())
                    val matches = TodoTagSuggest.suggestions(prefix, counts.keys.toList(), counts)
                    if (_binding == null || matches.isEmpty()) {
                        suggestionView.hide()
                    } else {
                        suggestionView.show(prefix, matches)
                    }
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 纯删除（退格/剪切）→ 标记；输入 → 非删除
                lastChangeWasDeletion = count == 0 && before > 0
            }
        })

        fun submit() {
            // 任务标题不换行：把换行/连续空白压成单个空格
            val raw = input.text?.toString()?.replace(Regex("\\s+"), " ")?.trim() ?: ""
            if (raw.isNotEmpty()) {
                // #tag 记号解析为标签并从标题剥离（纯数字如 #123 不算）
                val (title, tags) = TodoTagSuggest.parseTagTokens(raw)
                if (title.isNotEmpty() || tags.isNotEmpty()) {
                    addTask(title.ifEmpty { raw }, tags)
                    dialog.dismiss()
                }
            }
        }

        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }

        // 底部同一行：Markdown 工具栏在左、发送按钮在右（与笔记页快速输入一致；无取消按钮，下滑即可关闭）
        val sendButton = com.google.android.material.button.MaterialButton(
            themedCtx, null, com.google.android.material.R.attr.materialButtonStyle,
        ).apply {
            text = "➤"
            contentDescription = "添加任务"
            setOnClickListener { submit() }
        }
        val bottomRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(12), 0)
            addView(
                buildMarkdownToolbar(themedCtx, dp, input),
                LinearLayout.LayoutParams(
                    0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(
                sendButton,
                LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(4) },
            )
        }

        // 垂直布局：建议下拉（收起时无高度）→ 输入框 → 按钮行，弹层整体包裹内容（紧凑输入条）
        val container = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(
                suggestionView,
                LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                input,
                LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                bottomRow,
                LinearLayout.LayoutParams(
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
        dialog.behavior.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        input.requestFocus()
        input.postDelayed({
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun addTask(title: String, tags: List<String> = emptyList()) {
        if (title.isEmpty() && tags.isEmpty()) return
        // 归属规则：清单视图 → 该清单；今天视图 → 收集箱 + 今天到期；其它 → 收集箱无期限
        val (listId, due) = when (currentView) {
            TodoView.LIST -> currentListId to ""
            TodoView.TODAY -> 0L to todayStr()
            else -> 0L to ""
        }
        // 标签筛选激活时新任务自动带上当前标签（快速添加输入框已预填，此处兜底合并去重）
        val allTags = (tags + listOfNotNull(currentTag)).distinct()
        // 登记待定位 id：数据源异步生效，等快照里出现该任务时再滚动
        pendingScrollTaskId = -1L
        source.createTask(title, listId, due, allTags) { id -> pendingScrollTaskId = id }
    }

    // ── 排序（右上角 ⋮ 菜单的「排序」子菜单） ────────────

    private fun setSortMode(mode: TodoSortMode) {
        if (currentSortMode == mode) return
        currentSortMode = mode
        saveSortMode(mode)
        render()
    }

    /** 同步「排序」子菜单的勾选标记到当前排序模式 */
    private fun updateSortMenuChecks() {
        val checkedId = when (currentSortMode) {
            TodoSortMode.DEFAULT -> R.id.action_sort_default
            TodoSortMode.RECENTLY_ADDED -> R.id.action_sort_recent
            TodoSortMode.REVERSED -> R.id.action_sort_reverse
            TodoSortMode.BY_PRIORITY -> R.id.action_sort_priority
            TodoSortMode.BY_DUE_DATE -> R.id.action_sort_due
        }
        for (id in intArrayOf(
            R.id.action_sort_default, R.id.action_sort_recent, R.id.action_sort_reverse,
            R.id.action_sort_priority, R.id.action_sort_due,
        )) {
            binding.toolbar.menu.findItem(id)?.isChecked = (id == checkedId)
        }
    }

    /** 排序模式持久化：切出再回来 / 旋转屏幕保留 */
    private fun saveSortMode(mode: TodoSortMode) {
        requireContext().getSharedPreferences("todo_prefs", Context.MODE_PRIVATE)
            .edit().putString("sort_mode", mode.name).apply()
    }

    private fun loadSortMode(): TodoSortMode {
        val name = requireContext().getSharedPreferences("todo_prefs", Context.MODE_PRIVATE)
            .getString("sort_mode", TodoSortMode.DEFAULT.name)
        return TodoSortMode.entries.firstOrNull { it.name == name } ?: TodoSortMode.DEFAULT
    }

    // ── 清单管理（契约 §5.7） ────────────────────────────

    private fun showNewListDialog() {
        val input = EditText(requireContext()).apply {
            hint = "清单名称"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.aw_text_primary))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.aw_text_disabled))
            setSingleLine(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新建清单")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                source.createList(name, nextListColorHex())
                // 清单 id 由数据源分配（本地自增 / REST 为 tag 哈希），等下一次快照再选中
                pendingSelectListName = name
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新建清单的配色：从 8 色调色板里挑一个尚未被占用的 */
    private fun nextListColorHex(): String {
        val used = mLists.map { it.argb }.toSet()
        val pick = listPalette().firstOrNull { it !in used }
            ?: colorForString(System.currentTimeMillis().toString())
        return String.format("#%06X", pick and 0xFFFFFF)
    }

    private fun showListMenu(list: TodoList) {
        AlertDialog.Builder(requireContext())
            .setTitle(list.name)
            .setItems(arrayOf("重命名", "删除清单")) { _, which ->
                when (which) {
                    0 -> showRenameListDialog(list)
                    1 -> confirmDeleteList(list)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameListDialog(list: TodoList) {
        val input = EditText(requireContext()).apply {
            setText(list.name)
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.aw_text_primary))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("重命名清单")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) source.renameList(list.id, name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteList(list: TodoList) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除清单")
            .setMessage("删除清单后，其中任务将移入收集箱。确定删除？")
            .setPositiveButton("删除") { _, _ -> source.deleteList(list.id) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 数据源切换 ───────────────────────────────────────
    // 数据源固定为服务器（/inbox/todos），本地仅做缓冲，页面上不再提供切换入口。

    // ── 详情 ────────────────────────────────────────────

    private fun openDetail(task: TodoTask) {
        TodoDetailFragment.newInstance(task.id).show(childFragmentManager, "todo_detail")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 抽屉视图入口参数 key（MainActivity.todoArgs 写入） */
        const val ARG_VIEW = "todo_view"

        /** 撤销删除悬浮条的窗口时长（同收件箱笔记） */
        private const val UNDO_DELETE_WINDOW_MS = 3000
    }
}
