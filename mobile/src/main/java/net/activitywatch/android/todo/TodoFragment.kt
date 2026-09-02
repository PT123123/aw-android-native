package net.activitywatch.android.todo

import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import net.activitywatch.android.R
import net.activitywatch.android.databinding.TodoFragmentBinding

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 抽屉视图入口：nav_todo_today / next7 / all 通过参数直接落到对应视图
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

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_todo_source -> {
                    switchSource()
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
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { source.load() }
        binding.quickAdd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addTask()
                true
            } else false
        }
        binding.addBtn.setOnClickListener { addTask() }
        binding.fab.setOnClickListener { showNewListDialog() }

        TodoRepository.addErrorListener(errorToast)
        TodoRepository.addListener(dataChanged)
        refreshSourceMenu()
        source.load()
        render()
    }

    override fun onDestroyView() {
        TodoRepository.removeListener(dataChanged)
        TodoRepository.removeErrorListener(errorToast)
        _binding = null
        super.onDestroyView()
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

        binding.quickAdd.hint = "添加任务到「${viewTitle()}」…"

        val (open, done) = visibleTasks(mTasks, currentView, currentListId)
        binding.toolbar.subtitle = "${viewTitle()} · ${open.size} 项待办"
        adapter.submit(open, done, showCompleted)
        updateEmptyState()
        updateProgress()
    }

    private fun updateEmptyState() {
        val (open, done) = visibleTasks(mTasks, currentView, currentListId)
        val nothing = open.isEmpty() && (done.isEmpty() || !showCompleted)
        binding.empty.visibility = if (nothing) View.VISIBLE else View.GONE
        binding.empty.text = if (open.isEmpty() && done.isEmpty()) {
            "暂无任务\n在上方输入框回车即可添加"
        } else {
            "该视图下的任务已全部完成"
        }
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

    // ── 快速添加（契约 §5.5） ────────────────────────────

    private fun addTask() {
        val title = binding.quickAdd.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return
        // 归属规则：清单视图 → 该清单；今天视图 → 收集箱 + 今天到期；其它 → 收集箱无期限
        val (listId, due) = when (currentView) {
            TodoView.LIST -> currentListId to ""
            TodoView.TODAY -> 0L to todayStr()
            else -> 0L to ""
        }
        binding.quickAdd.text?.clear()
        source.createTask(title, listId, due)
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

    private fun switchSource() {
        val next = if (TodoRepository.kind(requireContext()) == TodoSourceKind.REST) {
            TodoSourceKind.LOCAL
        } else {
            TodoSourceKind.REST
        }
        TodoRepository.switchTo(requireContext(), next)
        currentView = TodoView.INBOX
        currentListId = 0L
        showCompleted = false
        refreshSourceMenu()
        toast("数据源已切换到：${next.title}")
    }

    private fun refreshSourceMenu() {
        val kind = TodoRepository.kind(requireContext())
        binding.toolbar.menu.findItem(R.id.action_todo_source)?.title = "数据源：${kind.title}"
    }

    // ── 详情 ────────────────────────────────────────────

    private fun openDetail(task: TodoTask) {
        TodoDetailFragment.newInstance(task.id).show(childFragmentManager, "todo_detail")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 抽屉视图入口参数 key（MainActivity.todoArgs 写入） */
        const val ARG_VIEW = "todo_view"
    }
}
