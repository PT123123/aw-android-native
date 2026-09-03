package net.activitywatch.android.inbox

import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.databinding.InboxNoteItemBinding
import net.activitywatch.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class InboxAdapter(
    private val onGesture: (NoteResponse, InboxPrefs.Gesture, View) -> Unit,
    private val onOverflowClick: (NoteResponse, View) -> Unit,
    private val onParentClick: (NoteResponse) -> Unit,
) : ListAdapter<NoteResponse, InboxAdapter.VH>(DIFF) {

    /** 当前 Adapter 绑定的置顶集合（Fragment 每次 submit 前刷新） */
    var pinnedIds: Set<Long> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** 是否处于多选模式 */
    var selectionMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    selectedIds.clear()
                }
                notifyDataSetChanged()
            }
        }

    /** 已选中的笔记 id 集合 */
    val selectedIds = mutableSetOf<Long>()

    /** 选中状态变化回调 */
    var onSelectionChanged: ((Int) -> Unit)? = null

    /** 全选/取消全选 */
    fun toggleSelectAll(allIds: List<Long>) {
        if (selectedIds.size == allIds.size) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            selectedIds.addAll(allIds)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    /** 切换单条选中状态 */
    fun toggleSelection(noteId: Long) {
        if (noteId in selectedIds) {
            selectedIds.remove(noteId)
        } else {
            selectedIds.add(noteId)
        }
        onSelectionChanged?.invoke(selectedIds.size)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NoteResponse>() {
            override fun areItemsTheSame(a: NoteResponse, b: NoteResponse) = a.id == b.id
            // parentId/parentPreview 是类体中的 var 属性，不参与 data class 的 equals，
            // 需要显式比较，否则关联解析后（新实例）DiffUtil 仍认为内容相同而不重绑
            override fun areContentsTheSame(a: NoteResponse, b: NoteResponse) =
                a == b && a.parentId == b.parentId && a.parentPreview == b.parentPreview
        }

        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val ISO_Z = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val SHORT = SimpleDateFormat("MM-dd HH:mm", Locale.US)

        private val ISO_FRAC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val ISO_FRAC_Z = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val FRACTION = Regex("\\.\\d+")

        fun parseTime(s: String?): Date? {
            if (s.isNullOrEmpty()) return null
            // 去掉小数秒部分，统一为不带毫秒的格式再解析
            val normalized = FRACTION.replace(s, "")
            return try {
                ISO.parse(normalized)
            } catch (_: Exception) {
                try {
                    ISO_Z.parse(normalized)
                } catch (_: Exception) {
                    null
                }
            }
        }

        fun formatTime(s: String?): String {
            val d = parseTime(s) ?: return ""
            val now = System.currentTimeMillis()
            return if (now - d.time < android.text.format.DateUtils.DAY_IN_MILLIS) {
                android.text.format.DateUtils.getRelativeTimeSpanString(
                    d.time, now, android.text.format.DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            } else {
                SHORT.format(d)
            }
        }
    }

    inner class VH(val b: InboxNoteItemBinding) : RecyclerView.ViewHolder(b.root) {
        private val detector = GestureDetector(
            b.root.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (selectionMode) {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            toggleSelection(getItem(pos).id)
                            notifyItemChanged(pos)
                        }
                        return true
                    }
                    dispatch(InboxPrefs.Gesture.SINGLE)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (selectionMode) return true
                    dispatch(InboxPrefs.Gesture.DOUBLE)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (selectionMode) return
                    b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    dispatch(InboxPrefs.Gesture.LONG)
                }

                private fun dispatch(gesture: InboxPrefs.Gesture) {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return
                    onGesture(getItem(pos), gesture, b.root)
                }
            },
        )

        init {
            b.root.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                true
            }
            b.overflow.setOnClickListener {
                onOverflowClick(getItem(bindingAdapterPosition), it)
            }
            b.parentPreview.setOnClickListener {
                val n = getItem(bindingAdapterPosition)
                if (n.parentId != null) onParentClick(n)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(InboxNoteItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val note = getItem(position)
        val ctx = holder.b.root.context
        val displayContent = if (note.id in pinnedIds) "📌 ${note.content}" else note.content
        holder.b.content.text = MarkdownRenderer.render(ctx, displayContent)
        // 原笔记预览（仅评论笔记显示）
        if (note.parentId != null && note.parentPreview != null) {
            holder.b.parentPreview.visibility = View.VISIBLE
            holder.b.parentPreview.text = "↖️ ${note.parentPreview}"
        } else {
            holder.b.parentPreview.visibility = View.GONE
        }
        if (note.tags.isNotEmpty()) {
            holder.b.tags.visibility = View.VISIBLE
            holder.b.tags.text = note.tags.joinToString(" ") { "#$it" }
        } else {
            holder.b.tags.visibility = View.GONE
        }
        holder.b.time.text = buildTimeString(note)

        // 多选模式：显示/隐藏 checkbox
        if (selectionMode) {
            holder.b.checkboxLayout.visibility = View.VISIBLE
            holder.b.checkbox.visibility = View.VISIBLE
            val isSelected = note.id in selectedIds
            holder.b.checkbox.setBackgroundResource(
                if (isSelected) R.drawable.bg_checkbox_circle_checked
                else R.drawable.bg_checkbox_circle
            )
            holder.b.checkmark.visibility = if (isSelected) View.VISIBLE else View.GONE
            // 多选模式下隐藏 overflow
            holder.b.overflow.visibility = View.GONE
        } else {
            holder.b.checkboxLayout.visibility = View.GONE
            holder.b.checkbox.visibility = View.GONE
            holder.b.checkmark.visibility = View.GONE
            holder.b.overflow.visibility = View.VISIBLE
        }
    }

    private fun buildTimeString(note: NoteResponse): String {
        val created = formatTime(note.created_at)
        val updated = note.updated_at?.let { formatTime(it) } ?: ""
        return when {
            created.isEmpty() && updated.isEmpty() -> ""
            updated.isEmpty() || updated == created || note.updated_at == note.created_at -> "创建于 $created"
            else -> "创建于 $created · 修改于 $updated"
        }
    }
}
