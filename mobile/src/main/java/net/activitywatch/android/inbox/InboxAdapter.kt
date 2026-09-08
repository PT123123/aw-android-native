package net.activitywatch.android.inbox

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.text.Spanned
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
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
    private val onTagClick: (String) -> Unit,
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

    // ==== 定位高亮（按笔记 id，不按 position）====

    /** 当前被高亮闪烁的笔记 id；高亮走 bind 周期（setCardBackgroundColor），复用/重绑不会残留 */
    private var highlightId: Long? = null

    /** 高亮清除定时器，重复触发时先取消旧的 */
    private var highlightClearRunnable: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 定位高亮：目标卡片背景闪 accent 色 durationMs 后恢复。
     * 以笔记 id 为键，通过 payload 局部重绑刷新背景，避免 DiffUtil 动画/复用导致高亮残留在其他笔记上。
     */
    fun flashHighlight(noteId: Long, durationMs: Long = 500L) {
        highlightClearRunnable?.let { mainHandler.removeCallbacks(it) }

        // 旧高亮先清除（position 可能已变，按 id 重新定位）
        val oldId = highlightId
        highlightId = null
        if (oldId != null) {
            val oldPos = currentList.indexOfFirst { it.id == oldId }
            if (oldPos >= 0) notifyItemChanged(oldPos, PAYLOAD_HIGHLIGHT)
        }

        val pos = currentList.indexOfFirst { it.id == noteId }
        if (pos < 0) return
        highlightId = noteId
        notifyItemChanged(pos, PAYLOAD_HIGHLIGHT)

        highlightClearRunnable = Runnable {
            highlightId = null
            highlightClearRunnable = null
            // 清除时重新按 id 定位（期间列表可能已移动）
            val p = currentList.indexOfFirst { it.id == noteId }
            if (p >= 0) notifyItemChanged(p, PAYLOAD_HIGHLIGHT)
        }
        mainHandler.postDelayed(highlightClearRunnable!!, durationMs)
    }

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
        /** 局部重绑 payload：只刷新定位高亮背景，不重设正文 */
        const val PAYLOAD_HIGHLIGHT = "payload_highlight"

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
                    // 点在 #标签 上 → 按标签筛选，不再走单击手势（双击手势仍可用）
                    val tag = tagAt(e)
                    if (tag != null) {
                        onTagClick(tag)
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

        /**
         * 命中测试：触点是否落在正文里的某个 #标签 上。
         * 卡片根节点的 OnTouchListener 拦截了全部触摸，TextView 的 LinkMovementMethod
         * 收不到事件，所以这里按屏幕坐标换算后自己找 TagSpan。
         */
        private fun tagAt(e: MotionEvent): String? {
            val tv = b.content
            val text = tv.text as? Spanned ?: return null
            val layout = tv.layout ?: return null
            val loc = IntArray(2)
            tv.getLocationOnScreen(loc)
            val localX = e.rawX - loc[0]
            val localY = e.rawY - loc[1]
            if (localX < 0 || localY < 0 || localX > tv.width || localY > tv.height) return null
            val x = localX - tv.totalPaddingLeft + tv.scrollX
            val y = localY - tv.totalPaddingTop + tv.scrollY
            val line = layout.getLineForVertical(y.toInt())
            if (line < 0 || line >= layout.lineCount) return null
            val off = layout.getOffsetForHorizontal(line, x)
            if (off < 0 || off > text.length) return null
            return text.getSpans(off, off, TagSpan::class.java).firstOrNull()?.tag
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(InboxNoteItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        // 定位高亮的局部刷新：只更新卡片背景，不动正文
        if (payloads.contains(PAYLOAD_HIGHLIGHT)) {
            bindCardBackground(holder, getItem(position))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val note = getItem(position)
        val ctx = holder.b.root.context
        val displayContent = if (note.id in pinnedIds) "📌 ${note.content}" else note.content
        holder.b.content.text = MarkdownRenderer.render(ctx, displayContent) { onTagClick(it) }
        // 原笔记预览（仅评论笔记显示）
        if (note.parentId != null && note.parentPreview != null) {
            holder.b.parentPreview.visibility = View.VISIBLE
            holder.b.parentPreview.text = "↖️ ${note.parentPreview}"
        } else {
            holder.b.parentPreview.visibility = View.GONE
        }
        holder.b.time.text = buildTimeString(note)
        bindCardBackground(holder, note)

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

    /** 卡片背景：被定位高亮的笔记闪 accent 色，其余用正常卡片色（走 bind 周期，复用/重绑安全） */
    private fun bindCardBackground(holder: VH, note: NoteResponse) {
        val ctx = holder.b.root.context
        val colorRes = if (note.id == highlightId) R.color.aw_accent else R.color.inbox_card
        holder.b.root.setCardBackgroundColor(ContextCompat.getColor(ctx, colorRes))
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
