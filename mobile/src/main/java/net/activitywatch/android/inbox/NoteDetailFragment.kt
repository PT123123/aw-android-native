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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.NoteDetailBinding
import net.activitywatch.android.databinding.NoteHistoryItemBinding

/**
 * 笔记详情面板：展示元数据 + 嵌入式历史版本列表。
 */
class NoteDetailFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        const val RESULT_KEY = "note_detail_restored"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_CONTENT = "content"

        fun newInstance(noteId: Long): NoteDetailFragment {
            val f = NoteDetailFragment()
            f.arguments = bundleOf(ARG_NOTE_ID to noteId)
            return f
        }
    }

    private var _binding: NoteDetailBinding? = null
    private val binding get() = _binding!!

    private var noteId: Long = 0
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), R.style.InboxBottomSheetDialogTheme)
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        val height = (resources.displayMetrics.heightPixels * 0.75).toInt()
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
        _binding = NoteDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalInboxApi.init(requireContext())

        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.btnClose.setOnClickListener { dismiss() }

        historyAdapter = HistoryAdapter { item -> showHistoryDetail(item) }
        binding.listHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.listHistory.adapter = historyAdapter

        loadNoteDetail()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadNoteDetail() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val note = LocalInboxApi.service.getNote(noteId)

                binding.tvDevice.text = DeviceNameResolver.resolve(requireContext(), note.device_id)
                binding.tvCreatedAt.text = formatDateTime(note.created_at)
                binding.tvUpdatedAt.text = formatDateTime(note.updated_at)
                binding.tvVersion.text = "v${note.version}"
                binding.tvWordCount.text = "${note.content.length} 字"
                binding.tvSyncedAt.text = if (note.synced_at.isNullOrEmpty())
                    "未同步"
                else formatDateTime(note.synced_at)
                binding.tvTags.text = if (note.tags.isEmpty()) "—"
                else note.tags.joinToString(" ") { "#$it" }
                binding.tvNoteId.text = note.id.toString()

                binding.layoutConflict.visibility = if (note.conflict) View.VISIBLE else View.GONE

                val history = LocalInboxApi.service.getNoteHistory(noteId)

                val deviceIds = history.mapNotNull { it.device_id }.distinct()
                DeviceNameResolver.preload(requireContext(), deviceIds)

                binding.tvHistoryTitle.text = "历史版本 (${history.size})"
                binding.tvHistoryTitle.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
                binding.tvHistoryEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE

                historyAdapter.submitList(history)

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载详情失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showHistoryDetail(item: NoteHistoryItem) {
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

        val deviceLabel = item.device_id?.let { DeviceNameResolver.resolve(requireContext(), it) } ?: "未知设备"
        MaterialAlertDialogBuilder(themedCtx)
            .setTitle("v${item.version} · ${formatDateTime(item.updated_at)} · $deviceLabel")
            .setView(scroll)
            .setPositiveButton("恢复此版本") { _, _ -> restore(item) }
            .setNegativeButton("关闭", null)
            .show()
    }

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
                loadNoteDetail()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "恢复失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatDateTime(s: String?): String {
        if (s.isNullOrEmpty()) return "—"
        val d = InboxAdapter.parseTime(s) ?: return s
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return fmt.format(d)
    }
}

/** 历史版本列表 Adapter，提取到文件级避免嵌套 inner class 的编译问题 */
private class HistoryAdapter(
    private val onItemClick: (NoteHistoryItem) -> Unit,
) : ListAdapter<NoteHistoryItem, HistoryAdapter.VH>(HistoryDiff) {

    class VH(val b: NoteHistoryItemBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(NoteHistoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.b.root.context
        holder.b.content.text = MarkdownRenderer.render(ctx, item.content)
        val deviceLabel = item.device_id?.let {
            DeviceNameResolver.resolve(ctx, it)
        } ?: "未知设备"
        holder.b.time.text = "${InboxAdapter.formatTime(item.updated_at)} · v${item.version} · $deviceLabel"
        holder.b.root.setOnClickListener { onItemClick(item) }
    }
}

private object HistoryDiff : DiffUtil.ItemCallback<NoteHistoryItem>() {
    override fun areItemsTheSame(a: NoteHistoryItem, b: NoteHistoryItem) = a.id == b.id
    override fun areContentsTheSame(a: NoteHistoryItem, b: NoteHistoryItem) = a == b
}
