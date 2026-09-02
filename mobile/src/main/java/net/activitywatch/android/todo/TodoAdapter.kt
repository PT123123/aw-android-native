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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 任务列表适配器。
 * 布局（模仿 aw-qtui TodoPage）：未完成任务在前（按优先级→期限排序），
 * 已完成任务折叠在「已完成 (N)」按钮后面，可展开。
 * 行渲染：复选框 + 标题 + 元信息（优先级旗标 / 截止日期 / 标签）。
 */
class TodoAdapter(
    private val onToggle: (TodoResponse, Boolean) -> Unit,
    private val onClick: (TodoResponse) -> Unit,
    private val onToggleCompleted: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val open = mutableListOf<TodoResponse>()
    private val done = mutableListOf<TodoResponse>()
    private var showCompleted = false

    /** 显示顺序：未完成 + [已完成折叠头] + [已完成（展开时）] */
    private val ordered = mutableListOf<TodoResponse>()
    private var headerIndex = -1

    fun submit(openItems: List<TodoResponse>, doneItems: List<TodoResponse>, showDone: Boolean) {
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

    private companion object {
        private const val TYPE_TASK = 0
        private const val TYPE_HEADER = 1
        private val DUMMY = TodoResponse(id = -1, title = "")
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
        fun bind(task: TodoResponse) {
            val ctx = b.root.context
            b.check.isChecked = task.completed
            b.title.text = task.title
            if (task.completed) {
                b.title.paintFlags = b.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.inbox_sub))
            } else {
                b.title.paintFlags = b.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.inbox_text))
            }

            // 元信息行
            val flag = task.priority ?: 0
            b.priorityFlag.visibility = if (flag >= 2) View.VISIBLE else View.GONE
            b.priorityFlag.text = if (flag >= 3) "‼" else "!"
            b.priorityFlag.setTextColor(
                ContextCompat.getColor(ctx, if (flag >= 3) R.color.sync_danger else R.color.sync_warning)
            )

            val due = task.dueDate
            if (due != null) {
                b.dueDate.visibility = View.VISIBLE
                b.dueDate.text = due
                b.dueDate.setTextColor(ContextCompat.getColor(ctx, dueColor(due, task.completed)))
            } else {
                b.dueDate.visibility = View.GONE
            }

            val tagText = task.tags.take(3).joinToString(" ") { "#$it" }
            if (tagText.isNotEmpty()) {
                b.tags.visibility = View.VISIBLE
                b.tags.text = tagText
                b.tags.setTextColor(ContextCompat.getColor(ctx, R.color.inbox_accent))
            } else {
                b.tags.visibility = View.GONE
            }

            b.check.setOnClickListener {
                onToggle(task, b.check.isChecked)
            }
            b.root.setOnClickListener { onClick(task) }
        }
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val label: TextView = v.findViewById(R.id.doneHeader)
        fun bind(count: Int, expanded: Boolean) {
            label.text = if (expanded) "▲ 收起已完成 ($count)" else "▼ 已完成 ($count)"
            label.setOnClickListener { onToggleCompleted() }
        }
    }

    /** 截止日期颜色：过期红 / 今天黄 / 其他次文本色 */
    private fun dueColor(due: String, completed: Boolean): Int {
        if (completed) return R.color.inbox_sub
        return try {
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dueDate = df.parse(due) ?: return R.color.inbox_sub
            val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.time
            val dueCal = Calendar.getInstance().apply { time = dueDate }
            val dueDay = Calendar.getInstance().apply {
                set(Calendar.YEAR, dueCal.get(Calendar.YEAR))
                set(Calendar.MONTH, dueCal.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, dueCal.get(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.time
            when {
                dueDay.before(today) -> R.color.sync_danger
                dueDay == today -> R.color.sync_warning
                else -> R.color.inbox_sub
            }
        } catch (e: Exception) {
            R.color.inbox_sub
        }
    }
}
