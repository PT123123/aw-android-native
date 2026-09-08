package net.activitywatch.android.todo

import android.app.DatePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.TodoDetailFragmentBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 任务详情面板（契约 §5.6）。
 *
 * 桌面端是三栏布局的右栏；手机上没有第三栏，这里做成**全屏 DialogFragment**，
 * 返回键 / 左上角 ✕ 关闭，字段与提交时机与桌面端一致：
 *
 * | 控件 | 提交时机 |
 * | --- | --- |
 * | 标题 / 标签 | 失焦或回车 |
 * | 已完成 | 点击即时 |
 * | 清单 / 优先级 / 重复 | 变更即时 |
 * | 截止（勾选 + 日期） | 变更即时 |
 * | 备注 | 250ms 防抖 |
 * | 子任务 | 点击即时 |
 * | 删除 | 确认后 |
 *
 * 所有提交都以数据源里的最新快照为基线（[base]）拷贝后覆盖控件值，再交给 [TodoSource]；
 * 数据刷新（onChange）回来后按新快照重填，UI 不做乐观更新。
 */
class TodoDetailFragment : DialogFragment() {

    private var _binding: TodoDetailFragmentBinding? = null
    private val b get() = _binding!!

    private val source: TodoSource get() = TodoRepository.source(requireContext())

    private var taskId: Long = 0
    private var base: TodoTask? = null

    /** 正在填充控件：屏蔽期间不触发提交，避免回环 */
    private var loading = false

    private var selectedListId: Long = 0
    private var selectedPriority: Int = 0
    private var selectedDue: String? = null
    private var selectedRecurrence: String = ""

    /** 备注防抖（契约 §5.6：250ms） */
    private val handler = Handler(Looper.getMainLooper())
    private var notesDebounce: Runnable? = null
    private var notesPending = false

    /** 已确认删除：onPause 不再提交，避免把已删任务再 PUT 一次 */
    private var deleted = false

    private lateinit var subtaskAdapter: TodoSubtaskAdapter

    private val dataChanged: () -> Unit = { refreshFromSource() }
    private val errorToast: (String) -> Unit = { msg -> toast(msg) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.TodoFullScreenDialog)
        taskId = arguments?.getLong(ARG_TASK_ID, 0L) ?: 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = TodoDetailFragmentBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        subtaskAdapter = TodoSubtaskAdapter(
            onToggle = { sub -> source.toggleSubtask(taskId, sub.id) },
            onRemove = { sub -> source.removeSubtask(taskId, sub.id) },
        )
        b.subtaskList.layoutManager = LinearLayoutManager(requireContext())
        b.subtaskList.adapter = subtaskAdapter

        b.detailToolbar.setNavigationOnClickListener { dismiss() }

        // ── 标题 / 标签：失焦或回车提交 ──
        b.detailTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                b.detailTitle.clearFocus()
                commit()
                true
            } else false
        }
        b.detailTitle.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }

        b.detailTags.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                b.detailTags.clearFocus()
                commit()
                true
            } else false
        }
        b.detailTags.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }

        // ── 标签 # 建议：输入 # 后给建议，点选插入 ──
        b.detailTagSuggestions.listener = listener@{ selectedTag ->
            val text = b.detailTags.text?.toString() ?: return@listener
            val cursorPos = b.detailTags.selectionStart
            if (cursorPos > 0) {
                val (prefix, tagStart) = TodoTagSuggest.extractTagPrefix(text, cursorPos)
                if (tagStart >= 0) {
                    b.detailTags.text.replace(tagStart - 1, cursorPos, selectedTag)
                    b.detailTags.setSelection(tagStart + selectedTag.length)
                }
            }
        }
        // 输入 # 后实时给出标签建议（建议源：任务 tag ∪ 笔记标签树）
        var lastChangeWasDeletion = false
        b.detailTags.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                val cursorPos = b.detailTags.selectionStart
                if (cursorPos <= 0) {
                    b.detailTagSuggestions.hide()
                    return
                }
                val (prefix, tagStart) = TodoTagSuggest.extractTagPrefix(text, cursorPos)
                // 不在 # 串内 → 不给建议
                if (tagStart < 0) {
                    b.detailTagSuggestions.hide()
                    return
                }
                // 输入标签后又删除回退到只剩 # → 不给建议（避免退格时整个标签列表挂出来）
                if (prefix.isEmpty() && lastChangeWasDeletion) {
                    b.detailTagSuggestions.hide()
                    return
                }
                // 刚输入 #（prefix 为空）或已有前缀 → 给建议（空前缀返回全量标签）
                viewLifecycleOwner.lifecycleScope.launch {
                    val counts = TodoTagSuggest.tagCounts(source.tasks())
                    val matches = TodoTagSuggest.suggestions(prefix, counts.keys.toList(), counts)
                    if (_binding == null || matches.isEmpty()) {
                        b.detailTagSuggestions.hide()
                    } else {
                        b.detailTagSuggestions.show(prefix, matches)
                    }
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 纯删除（退格/剪切）→ 标记；输入 → 非删除
                lastChangeWasDeletion = count == 0 && before > 0
            }
        })

        // ── 备注：250ms 防抖 ──
        b.detailNotes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (loading) return
                notesPending = true
                notesDebounce?.let { handler.removeCallbacks(it) }
                notesDebounce = Runnable {
                    notesPending = false
                    commit()
                }
                handler.postDelayed(notesDebounce!!, 250L)
            }
        })

        // ── 已完成：点击即时 ──
        b.detailDone.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            source.setTaskCompleted(taskId, checked)
        }

        // ── 清单 / 优先级 / 重复：变更即时 ──
        b.rowList.setOnClickListener { pickList() }
        b.rowPriority.setOnClickListener { pickPriority() }
        b.rowRecurrence.setOnClickListener { pickRecurrence() }

        // ── 截止：勾选 + 日期选择 ──
        b.detailDueCheck.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            selectedDue = if (checked) selectedDue ?: todayStr() else null
            refreshDue()
            commit()
        }
        b.detailDueValue.setOnClickListener {
            if (b.detailDueCheck.isChecked) showDatePicker()
        }

        // ── 子任务 ──
        b.subtaskInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addSubtask()
                true
            } else false
        }
        b.subtaskAdd.setOnClickListener { addSubtask() }

        b.detailDelete.setOnClickListener { confirmDelete() }

        TodoRepository.addErrorListener(errorToast)
        TodoRepository.addListener(dataChanged)
        refreshFromSource()
    }

    /** 返回键 / ✕ / 切后台离开详情前兜底提交：冲刷未落地的备注防抖并提交全部控件值 */
    override fun onPause() {
        if (_binding != null && !deleted) {
            notesDebounce?.let { handler.removeCallbacks(it) }
            notesDebounce?.run()          // 立即执行备注提交（含 notesPending 置位清理）
            commit()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        notesDebounce?.let { handler.removeCallbacks(it) }
        TodoRepository.removeListener(dataChanged)
        TodoRepository.removeErrorListener(errorToast)
        _binding = null
        super.onDestroyView()
    }

    // ── 数据 → 控件 ──────────────────────────────────────

    private fun refreshFromSource() {
        if (_binding == null) return
        val task = source.tasks().firstOrNull { it.id == taskId }
        if (task == null) {          // 任务被别处删掉了
            dismissAllowingStateLoss()
            return
        }
        base = task
        if (notesPending) {
            // 备注防抖未落地时不要重填控件，否则会打断输入
            renderSubtasks(task)
            return
        }
        bind(task)
    }

    private fun bind(task: TodoTask) {
        loading = true
        selectedListId = task.listId
        selectedPriority = task.priority
        selectedDue = task.dueDate.takeIf { it.isNotBlank() }
        selectedRecurrence = task.recurrence

        // 正在输入的控件不重填（每次写操作后都会全量 reload → bind），
        // 否则服务端快照会覆盖用户未提交的半截输入
        if (!b.detailTitle.hasFocus()) b.detailTitle.setText(task.title)
        b.detailDone.isChecked = task.completed
        if (!b.detailTags.hasFocus()) {
            b.detailTags.setText(task.tags.joinToString(" "))   // 空格分隔（parseTags 同步按空白/逗号切分）
            b.detailTagSuggestions.hide()
        }
        b.detailNotes.setText(task.notes)

        val listName = source.lists().firstOrNull { it.id == selectedListId }?.name
        b.detailListValue.text = listName ?: "收集箱"
        b.detailPriorityValue.text = priorityLabel(selectedPriority)
        b.detailRecurrenceValue.text = recurrenceLabel(selectedRecurrence)
        refreshDue()

        // 能力降级：服务端不支持子任务 / 重复
        val canSubtask = source.supportsSubtasks
        b.subtaskInputRow.visibility = if (canSubtask) View.VISIBLE else View.GONE
        b.subtaskHint.visibility = if (canSubtask) View.GONE else View.VISIBLE
        b.rowRecurrence.visibility = if (source.supportsRecurrence) View.VISIBLE else View.GONE

        renderSubtasks(task)
        loading = false
    }

    private fun renderSubtasks(task: TodoTask) {
        subtaskAdapter.submit(task.subtasks)
        b.subtaskList.visibility =
            if (task.subtasks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshDue() {
        b.detailDueCheck.isChecked = selectedDue != null
        b.detailDueValue.text = selectedDue?.let { dueLabel(it) } ?: "无期限"
        b.detailDueValue.isEnabled = selectedDue != null
        b.detailDueValue.alpha = if (selectedDue != null) 1f else 0.5f
    }

    // ── 选择器 ──────────────────────────────────────────

    private fun pickList() {
        val lists = source.lists()
        val names = listOf("收集箱") + lists.map { it.name }
        val ids = listOf(0L) + lists.map { it.id }
        val current = ids.indexOf(selectedListId).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("选择清单")
            .setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
                selectedListId = ids[which]
                b.detailListValue.text = names[which]
                d.dismiss()
                commit()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickPriority() {
        val labels = arrayOf("无", "低", "中", "高")
        AlertDialog.Builder(requireContext())
            .setTitle("选择优先级")
            .setSingleChoiceItems(labels, selectedPriority) { d, which ->
                selectedPriority = which
                b.detailPriorityValue.text = labels[which]
                d.dismiss()
                commit()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickRecurrence() {
        val labels = RECURRENCE_RULES.map { it.second }.toTypedArray()
        val current = RECURRENCE_RULES.indexOfFirst { it.first == selectedRecurrence }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("选择重复规则")
            .setSingleChoiceItems(labels, current) { d, which ->
                selectedRecurrence = RECURRENCE_RULES[which].first
                b.detailRecurrenceValue.text = labels[which]
                d.dismiss()
                commit()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        selectedDue?.let {
            runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it) }
                .getOrNull()?.let { cal.time = it }
        }
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                selectedDue = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                refreshDue()
                commit()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    // ── 提交 ────────────────────────────────────────────

    /** 以数据源最新快照为基线，用控件值覆盖后提交（契约 §5.6 commitDetail） */
    private fun commit() {
        if (loading) return
        val task = base ?: return
        val title = b.detailTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            toast("标题不能为空")
            return
        }
        val updated = task.deepCopy().apply {
            this.title = title
            this.notes = b.detailNotes.text?.toString() ?: ""
            this.tags = parseTags(b.detailTags.text?.toString())
            this.listId = selectedListId
            this.priority = selectedPriority
            this.dueDate = selectedDue ?: ""
            this.recurrence = selectedRecurrence
        }
        if (updated == task) return               // 无变化则不打扰数据源
        source.updateTask(updated)
        base = updated   // 提交即生效为基线，防止短时间重复提交相同内容（reload 后会被服务端快照覆盖）
    }

    private fun parseTags(raw: String?): MutableList<String> =
        raw.orEmpty()
            .split(Regex("[,，\\s]+"))   // 逗号与空白（空格/换行/Tab）都视为分隔符，空格不再混入 tag 文字
            .map { it.trim().trimStart('#') }   // 容忍 "#标签" 写法
            .filter { it.isNotEmpty() }
            .distinct()
            .toMutableList()

    private fun addSubtask() {
        val title = b.subtaskInput.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return
        b.subtaskInput.text?.clear()
        source.addSubtask(taskId, title)
    }

    /**
     * 删除（无确认对话框，笔记同款撤销流程）：
     * 先登记进撤销窗口（列表隐藏 + 悬浮条可撤销），窗口超时才真正调服务端删除。
     */
    private fun confirmDelete() {
        val task = base ?: return
        deleted = true
        TodoRepository.enqueuePendingDelete(task)
        dismissAllowingStateLoss()
    }

    private fun toast(msg: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_TASK_ID = "task_id"

        fun newInstance(taskId: Long) = TodoDetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_TASK_ID, taskId) }
        }
    }
}
