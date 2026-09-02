package net.activitywatch.android.todo

import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.TodoFragmentBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Todo 页面 —— 界面模仿 aw-qtui TodoPage（TickTick 式三栏在手机上折叠为：
 * 顶部视图导航（收集箱/今天/最近 7 天/全部）→ 清单导航（tag 模拟）→ 快速添加 → 任务列表）。
 *
 * 数据源为远端 feature/inbox 分支的 aw-server-rust：/inbox/todos REST API。
 * 视图过滤 / 排序在客户端内存完成（服务端只支持 completed/limit/offset）。
 */
class TodoFragment : Fragment() {

    private var _binding: TodoFragmentBinding? = null
    private val binding get() = _binding!!

    private val allTasks = mutableListOf<TodoResponse>()
    private val listInfos = mutableListOf<TodoListInfo>()

    /** 本地新建的空清单（尚无任务使用该 tag 时也保留显示，避免清单一闪而过） */
    private val extraLists = mutableListOf<String>()

    private var currentView = TodoView.INBOX
    private var currentListId = 0L
    private var showCompleted = false

    private lateinit var adapter: TodoAdapter

    /** 清单颜色板（深色下可读的明亮色，按 tag 哈希取模） */
    private val listColors = intArrayOf(
        0xFF4F8CFF.toInt(), // 蓝
        0xFF52C41A.toInt(), // 绿
        0xFFFF8C42.toInt(), // 橙
        0xFFE85C8B.toInt(), // 粉
        0xFF9B59F0.toInt(), // 紫
        0xFF00C2B8.toInt(), // 青
        0xFFF5C518.toInt(), // 黄
        0xFFE74C3C.toInt(), // 红
    )

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
        TodoApi.init(requireContext())

        binding.toolbar.title = "任务"
        binding.toolbar.setNavigationOnClickListener { openDrawer() }

        adapter = TodoAdapter(
            onToggle = { task, checked -> toggle(task, checked) },
            onClick = { task -> openDetail(task) },
            onToggleCompleted = { toggleCompleted() },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { load() }
        binding.quickAdd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                addTask()
                true
            } else false
        }
        binding.addBtn.setOnClickListener { addTask() }
        binding.fab.setOnClickListener { showNewListDialog() }

        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openDrawer() {
        requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
            ?.openDrawer(GravityCompat.START)
    }

    // ── 数据加载 ────────────────────────────────────────────

    private fun load() {
        binding.swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                // 拉全量（含已完成），视图过滤/排序在本地做（对齐 aw-qtui getTodos(true)）
                val list = TodoApi.service.getTodos(completed = true)
                allTasks.clear()
                allTasks.addAll(list)
                rebuildLists()
                rebuildChips()
                rebuildList()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipe.isRefreshing = false
            }
        }
    }

    /** 从任务 tags 派生清单（收集箱 id=0 不算清单） */
    private fun rebuildLists() {
        val seen = LinkedHashSet<String>()
        for (t in allTasks) seen.addAll(t.tags)
        listInfos.clear()
        for (name in seen) listInfos.add(TodoListInfo(tagToListId(name), name, colorFor(name)))
        for (name in extraLists) {
            if (seen.contains(name)) continue
            listInfos.add(TodoListInfo(tagToListId(name), name, colorFor(name)))
        }
    }

    private fun colorFor(name: String): Int =
        listColors[(name.hashCode() and 0x7fffffff) % listColors.size]

    // ── Chips 导航 ─────────────────────────────────────────

    private fun rebuildChips() {
        binding.viewChips.removeAllViews()
        binding.listChips.removeAllViews()

        val views = listOf(TodoView.INBOX, TodoView.TODAY, TodoView.NEXT7, TodoView.ALL)
        for (v in views) {
            val selected = currentView == v
            binding.viewChips.addView(makeChip(viewLabel(v), selected, dotColor = null, dot = false) {
                currentView = v
                currentListId = 0L
                rebuildChips()
                rebuildList()
            })
        }

        for (l in listInfos) {
            val selected = currentView == TodoView.LIST && currentListId == l.id
            binding.listChips.addView(makeChip(l.name, selected, dotColor = l.color, dot = true) {
                currentView = TodoView.LIST
                currentListId = l.id
                rebuildChips()
                rebuildList()
            })
        }
    }

    private fun viewLabel(v: TodoView): String = when (v) {
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
        dot: Boolean,
        onClick: () -> Unit,
    ): TextView {
        val tv = TextView(requireContext())
        if (dot && dotColor != null) {
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
                if (selected) android.R.color.white else R.color.inbox_text,
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

    // ── 视图过滤 + 排序（对齐 aw-qtui visibleTasks / taskLessThan） ──

    private fun viewTitle(): String = when (currentView) {
        TodoView.INBOX -> "收集箱"
        TodoView.TODAY -> "今天"
        TodoView.NEXT7 -> "最近 7 天"
        TodoView.ALL -> "全部"
        TodoView.LIST -> listInfos.firstOrNull { it.id == currentListId }?.name ?: "清单"
    }

    private fun rebuildList() {
        val (open, done) = visibleTasks()
        binding.toolbar.subtitle = "${viewTitle()} · ${open.size} 项未完成"
        adapter.submit(open, done, showCompleted)
    }

    private fun visibleTasks(): Pair<List<TodoResponse>, List<TodoResponse>> {
        val today = dateStr(0)
        val next7 = dateStr(6)
        val open = mutableListOf<TodoResponse>()
        val done = mutableListOf<TodoResponse>()
        for (t in allTasks) {
            val inView = when (currentView) {
                TodoView.INBOX -> t.listId == 0L
                TodoView.TODAY -> t.dueDate != null && t.dueDate!! <= today
                TodoView.NEXT7 -> t.dueDate != null && t.dueDate!! <= next7
                TodoView.ALL -> true
                TodoView.LIST -> t.listId == currentListId
            }
            if (!inView) continue
            if (t.completed) done.add(t) else open.add(t)
        }
        // 未完成：优先级降序 → 有期限在前 → 期限升序 → id 升序
        open.sortWith(
            compareByDescending<TodoResponse> { it.priority ?: 0 }
                .thenByDescending { it.dueDate != null }
                .thenBy { it.dueDate ?: "" }
                .thenBy { it.id }
        )
        // 已完成：按完成时间倒序
        done.sortByDescending { it.completed_at ?: "" }
        return open to done
    }

    private fun dateStr(daysFromToday: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, daysFromToday)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    // ── 操作 ────────────────────────────────────────────────

    private fun toggle(task: TodoResponse, checked: Boolean) {
        lifecycleScope.launch {
            try {
                TodoApi.service.updateTodo(task.id, UpdateTodoPayload(completed = checked))
                load()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "更新失败：${e.message}", Toast.LENGTH_SHORT).show()
                load()
            }
        }
    }

    private fun toggleCompleted() {
        showCompleted = !showCompleted
        adapter.setShowCompleted(showCompleted)
    }

    private fun addTask() {
        val title = binding.quickAdd.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return
        // 当前在某个清单视图时，新任务默认归入该清单（tag 模拟）
        val tag = if (currentView == TodoView.LIST)
            listInfos.firstOrNull { it.id == currentListId }?.name else null
        binding.quickAdd.text?.clear()
        lifecycleScope.launch {
            try {
                TodoApi.service.createTodo(
                    CreateTodoPayload(title = title, tags = if (tag != null) listOf(tag) else null)
                )
                load()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "添加失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openDetail(task: TodoResponse) {
        val dlg = TodoDetailDialog.newInstance(task, listInfos.map { it.name }, null)
        dlg.onSaved = { load() }
        dlg.onDeleted = { load() }
        dlg.show(childFragmentManager, "todo_detail")
    }

    private fun showNewListDialog() {
        val input = EditText(requireContext()).apply {
            hint = "清单名称"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_text))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_sub))
            setSingleLine(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新建清单")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                // 清单由任务 tag 派生：本地先保留该清单并切换到它；
                // 之后创建任务时选择此清单，tag 即随任务持久化。
                extraLists.remove(name)
                extraLists.add(name)
                rebuildLists()
                currentView = TodoView.LIST
                currentListId = tagToListId(name)
                rebuildChips()
                rebuildList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
