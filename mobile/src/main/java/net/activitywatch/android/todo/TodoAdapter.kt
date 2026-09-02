package net.activitywatch.android.todo

import android.graphics.Paint
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

    fun setListColors(colors: Map<Long, Int>) {
        listColors = colors
        notifyDataSetChanged()
    }

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
            b.check.setOnCheckedChangeListener { _, checked -> onToggle(task, checked) }

            b.title.text = task.title
            if (task.completed) {
                b.title.paintFlags = b.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_disabled))
            } else {
                b.title.paintFlags = b.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_primary))
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

            // 标签行
            val tagText = task.tags.joinToString(" · ")
            if (tagText.isNotEmpty()) {
                b.tags.visibility = View.VISIBLE
                b.tags.text = tagText
            } else {
                b.tags.visibility = View.GONE
            }

            b.root.setOnClickListener { onClick(task) }
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
