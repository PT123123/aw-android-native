package net.activitywatch.android.todo

import android.graphics.Paint
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.R
import net.activitywatch.android.databinding.TodoTaskItemBinding

/**
 * 任务列表适配器（契约 §5.3 / §5.4）。
 *
 * 显示顺序：未完成任务 → 「显示已完成 (n)」折叠头 →（展开时）已完成任务。
 * 任务行：复选框 + 标题（已完成加删除线）+ 元信息行（清单色点 / 优先级图标 / 期限徽章 / 子任务数 / 标签）。
 */
class TodoAdapter(
    private val onToggle: (TodoTask, Boolean) -> Unit,
    private val onClick: (TodoTask) -> Unit,
    private val onToggleCompleted: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val open = mutableListOf<TodoTask>()
    private val done = mutableListOf<TodoTask>()
    private val ordered = mutableListOf<TodoTask>()
    private var showCompleted = false
    private var headerIndex = -1

    /** 清单 id → 颜色（行首色点用） */
    private var listColors: Map<Long, Int> = emptyMap()

    /** 多选模式 */
    var selectionMode = false
        set(value) {
            field = value
            if (!value) selectedIds.clear()
            notifyDataSetChanged()
        }

    /** 已选任务 id 集合 */
    val selectedIds = mutableSetOf<Long>()

    /** 选择变化回调 */
    var onSelectionChanged: ((Int) -> Unit)? = null

    /** 行内标签点击回调（多选模式自动退化为纯文本，不触发） */
    var onTagClick: ((String) -> Unit)? = null

    fun setListColors(colors: Map<Long, Int>) {
        listColors = colors
        notifyDataSetChanged()
    }

    /** 任务在 ordered（含可选「显示已完成」折叠头）中的 adapter position；不在可见区返回 -1 */
    fun indexOfTask(taskId: Long): Int = ordered.indexOfFirst { it.id == taskId }

    /** 获取所有可见任务的 id 列表（排除折叠头） */
    fun getAllTaskIds(): List<Long> = ordered.filter { it.id != -1L }.map { it.id }

    fun submit(openItems: List<TodoTask>, doneItems: List<TodoTask>, showDone: Boolean) {
        open.clear(); open.addAll(openItems)
        done.clear(); done.addAll(doneItems)
        showCompleted = showDone
        rebuildOrder()
    }

    private fun rebuildOrder() {
        ordered.clear()
        ordered.addAll(open)
        headerIndex = if (done.isEmpty()) -1 else ordered.size.also { ordered.add(DUMMY) }
        if (showCompleted) ordered.addAll(done)
        notifyDataSetChanged()
    }

    fun setShowCompleted(show: Boolean) {
        if (showCompleted == show) return
        showCompleted = show
        rebuildOrder()
    }

    fun toggleSelection(taskId: Long) {
        if (taskId in selectedIds) selectedIds.remove(taskId) else selectedIds.add(taskId)
        onSelectionChanged?.invoke(selectedIds.size)
        notifyDataSetChanged()
    }

    fun toggleSelectAll(allIds: List<Long>) {
        if (selectedIds.size == allIds.size) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            selectedIds.addAll(allIds)
        }
        onSelectionChanged?.invoke(selectedIds.size)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (position == headerIndex) TYPE_HEADER else TYPE_TASK

    override fun getItemCount(): Int = ordered.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.todo_done_header, parent, false)
            return HeaderVH(v)
        }
        val b = TodoTaskItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TaskVH(b)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is TaskVH -> holder.bind(ordered[position])
            is HeaderVH -> holder.bind(done.size, showCompleted)
        }
    }

    inner class TaskVH(private val b: TodoTaskItemBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(task: TodoTask) {
            val ctx = b.root.context

            b.check.setOnCheckedChangeListener(null)
            b.check.isChecked = task.completed
            // 多选模式下不设置 checkbox 监听，避免意外触发
            if (!selectionMode) {
                b.check.setOnCheckedChangeListener { _, checked -> onToggle(task, checked) }
            }

            b.title.text = task.title
            if (task.completed) {
                b.title.paintFlags = b.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_disabled))
            } else {
                b.title.paintFlags = b.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_primary))
            }

            // 多选模式：显示选中背景，隐藏复选框
            if (selectionMode) {
                b.root.setBackgroundColor(
                    if (task.id in selectedIds) ContextCompat.getColor(ctx, R.color.aw_text_disabled)
                    else ContextCompat.getColor(ctx, android.R.color.transparent)
                )
                b.check.visibility = View.GONE
            } else {
                b.root.setBackgroundColor(ContextCompat.getColor(ctx, android.R.color.transparent))
                b.check.visibility = View.VISIBLE
            }

            // 统一点击监听器：点击时实时判断 selectionMode，避免绑定残留问题
            b.root.setOnClickListener {
                android.util.Log.d("TodoAdapter", "click task=${task.id}, selectionMode=$selectionMode, selectedIds=$selectedIds")
                if (selectionMode) {
                    toggleSelection(task.id)
                } else {
                    onClick(task)
                }
            }

            // 清单色点（listId != 0 时显示）
            val dotColor = listColors[task.listId]
            if (task.listId != 0L && dotColor != null) {
                b.listDot.visibility = View.VISIBLE
                b.listDot.background = gradient(dotColor)
            } else {
                b.listDot.visibility = View.GONE
            }

            // 优先级图标（契约 §1.4）
            if (task.priority > 0) {
                b.priorityFlag.visibility = View.VISIBLE
                b.priorityFlag.text = priorityIcon(task.priority)
                b.priorityFlag.setTextColor(ContextCompat.getColor(ctx, priorityColorRes(task.priority)))
            } else {
                b.priorityFlag.visibility = View.GONE
            }

            // 期限徽章：今天 / 明天 / 昨天 / M月d日 / yyyy年M月d日；逾期变红
            if (task.hasDue()) {
                val today = todayStr()
                val overdue = isOverdue(task)
                b.dueDate.visibility = View.VISIBLE
                b.dueDate.text = dueLabel(task.dueDate)
                b.dueDate.setBackgroundResource(
                    if (overdue) R.drawable.todo_due_bg_overdue else R.drawable.todo_due_bg
                )
                b.dueDate.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        when {
                            task.completed -> R.color.aw_text_disabled
                            overdue -> R.color.aw_danger
                            task.dueDate == today -> R.color.aw_warning
                            else -> R.color.aw_text_secondary
                        }
                    )
                )
            } else {
                b.dueDate.visibility = View.GONE
            }

            // 子任务进度
            if (task.subtasks.isNotEmpty()) {
                b.subtasks.visibility = View.VISIBLE
                b.subtasks.text = "☑ ${task.subtasks.size - task.openSubtaskCount()}/${task.subtasks.size}"
            } else {
                b.subtasks.visibility = View.GONE
            }

            // 标签行：非多选时按「 · 」分段可点（点标签 = 进入该标签筛选）
            if (task.tags.isNotEmpty()) {
                b.tags.visibility = View.VISIBLE
                val click = if (selectionMode) null else onTagClick
                if (click == null) {
                    b.tags.text = task.tags.joinToString(" · ")
                    b.tags.movementMethod = null
                } else {
                    b.tags.text = clickableTagText(task.tags, click)
                    b.tags.movementMethod = LinkMovementMethod.getInstance()
                }
            } else {
                b.tags.visibility = View.GONE
            }
        }

        /** 每个标签段挂 ClickableSpan（高亮透明、不下划线，保持行内标签观感） */
        private fun clickableTagText(tags: List<String>, click: (String) -> Unit): CharSequence {
            val sp = SpannableString(tags.joinToString(" · "))
            var start = 0
            for (tag in tags) {
                val end = start + tag.length
                sp.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) = click(tag)
                        override fun updateDrawState(ds: TextPaint) {
                            ds.isUnderlineText = false
                        }
                    },
                    start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                start = end + 3   // " · " 分隔符
            }
            return sp
        }
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val label: TextView = v.findViewById(R.id.doneHeader)
        fun bind(count: Int, expanded: Boolean) {
            label.text = if (expanded) "隐藏已完成 ($count)" else "显示已完成 ($count)"
            label.setOnClickListener { onToggleCompleted() }
        }
    }

    private fun gradient(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }

    private companion object {
        private const val TYPE_TASK = 0
        private const val TYPE_HEADER = 1
        private val DUMMY = TodoTask(id = -1, title = "")
    }
}
