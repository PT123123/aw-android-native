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
import androidx.recyclerview.widget.LinearLayoutManager
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
        b.detailToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete_task) {
                confirmDelete()
                true
            } else false
        }

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

        b.detailTitle.setText(task.title)
        b.detailDone.isChecked = task.completed
        b.detailTags.setText(task.tags.joinToString(", "))
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
    }

    private fun parseTags(raw: String?): MutableList<String> =
        raw.orEmpty()
            .split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toMutableList()

    private fun addSubtask() {
        val title = b.subtaskInput.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return
        b.subtaskInput.text?.clear()
        source.addSubtask(taskId, title)
    }

    private fun confirmDelete() {
        val task = base ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("删除任务")
            .setMessage("确定删除「${task.title}」？")
            .setPositiveButton("删除") { _, _ ->
                source.deleteTask(taskId)
                dismissAllowingStateLoss()
            }
            .setNegativeButton("取消", null)
            .show()
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
