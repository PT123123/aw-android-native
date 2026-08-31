package net.activitywatch.android.inbox

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.NoteHistoryBinding
import net.activitywatch.android.databinding.NoteHistoryItemBinding

/**
 * 笔记历史版本面板：列出被覆盖前的旧版本，可查看全文并恢复到任一版本。
 * 恢复成功后通过 Fragment Result（RESULT_KEY）通知编辑器/列表刷新。
 */
class NoteHistoryFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"

        const val RESULT_KEY = "note_history_restored"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_CONTENT = "content"

        fun newInstance(noteId: Long): NoteHistoryFragment {
            val f = NoteHistoryFragment()
            f.arguments = bundleOf(ARG_NOTE_ID to noteId)
            return f
        }
    }

    private var _binding: NoteHistoryBinding? = null
    private val binding get() = _binding!!

    private var noteId: Long = 0
    private lateinit var adapter: HistoryAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), R.style.InboxBottomSheetDialogTheme)
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        // 列表场景直接展开到更高，方便浏览多个版本
        val height = (resources.displayMetrics.heightPixels * 0.7).toInt()
        d.behavior.peekHeight = height
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.isHideable = true
        d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = arguments?.getLong(ARG_NOTE_ID) ?: 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = NoteHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalInboxApi.init(requireContext())

        binding.toolbar.setNavigationOnClickListener { dismiss() }

        adapter = HistoryAdapter { showDetail(it) }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        loadHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = LocalInboxApi.service.getNoteHistory(noteId)
                adapter.submitList(items)
                binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载历史失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 点击版本卡片 → 全文查看，并提供恢复按钮 */
    private fun showDetail(item: NoteHistoryItem) {
        val themedCtx = ContextThemeWrapper(requireContext(), R.style.InboxPopupMenu)
        val textView = TextView(themedCtx).apply {
            text = MarkdownRenderer.render(themedCtx, item.content)
            setTextIsSelectable(true)
            textSize = 15f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_text))
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                0,
            )
        }
        val scroll = ScrollView(themedCtx).apply { addView(textView) }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(themedCtx)
            .setTitle(InboxAdapter.formatTime(item.updated_at))
            .setView(scroll)
            .setPositiveButton("恢复此版本") { _, _ -> restore(item) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 恢复 = 用旧内容走一次正常更新，版本递增、自动产生新快照 */
    private fun restore(item: NoteHistoryItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                LocalInboxApi.service.updateNote(
                    noteId,
                    UpsertNotePayload(content = item.content, tags = parseTags(item.content)),
                )
                parentFragmentManager.setFragmentResult(
                    RESULT_KEY,
                    bundleOf(KEY_NOTE_ID to noteId, KEY_CONTENT to item.content),
                )
                Toast.makeText(requireContext(), "已恢复到该版本", Toast.LENGTH_SHORT).show()
                dismiss()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "恢复失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private class HistoryAdapter(
        private val onItemClick: (NoteHistoryItem) -> Unit,
    ) : ListAdapter<NoteHistoryItem, HistoryAdapter.VH>(DIFF) {

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<NoteHistoryItem>() {
                override fun areItemsTheSame(a: NoteHistoryItem, b: NoteHistoryItem) = a.id == b.id
                override fun areContentsTheSame(a: NoteHistoryItem, b: NoteHistoryItem) = a == b
            }
        }

        class VH(val b: NoteHistoryItemBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(NoteHistoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.b.content.text = MarkdownRenderer.render(holder.b.root.context, item.content)
            holder.b.time.text = InboxAdapter.formatTime(item.updated_at)
            holder.b.root.setOnClickListener { onItemClick(item) }
        }
    }
}
