package net.activitywatch.android.todo

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.R
import net.activitywatch.android.databinding.TodoSubtaskItemBinding

/** 详情页的子任务列表：复选框 + 标题 + ✕（契约 §5.6） */
class TodoSubtaskAdapter(
    private val onToggle: (TodoSubtask) -> Unit,
    private val onRemove: (TodoSubtask) -> Unit,
) : RecyclerView.Adapter<TodoSubtaskAdapter.VH>() {

    private val items = mutableListOf<TodoSubtask>()

    fun submit(list: List<TodoSubtask>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(TodoSubtaskItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: TodoSubtaskItemBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(sub: TodoSubtask) {
            val ctx = b.root.context
            b.subCheck.setOnCheckedChangeListener(null)
            b.subCheck.isChecked = sub.completed
            b.subCheck.setOnCheckedChangeListener { _, _ -> onToggle(sub) }

            b.subTitle.text = sub.title
            if (sub.completed) {
                b.subTitle.paintFlags = b.subTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                b.subTitle.setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_disabled))
            } else {
                b.subTitle.paintFlags = b.subTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                b.subTitle.setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_primary))
            }
            b.subRemove.setOnClickListener { onRemove(sub) }
        }
    }
}
