package net.activitywatch.android.inbox

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.TrashFragmentBinding
import net.activitywatch.android.sync.SyncApiClient
import net.activitywatch.android.sync.TrashEntry
import android.text.TextUtils

/**
 * 回收站：双 tab。
 *  - 「笔记回收站」：展示已软删笔记，可恢复到收件箱（PUT /inbox/notes/{id}/restore）。
 *  - 「冲突归档」：展示局域网同步合并时的冲突归档记录（GET /api/0/sync/trash），
 *    支持恢复（POST /trash/{id}/restore）、永久删除（DELETE /trash/{id}）、清空（DELETE /trash）。
 */
class TrashFragment : Fragment() {

    companion object {
        private const val LIMIT = 50
    }

    private var _binding: TrashFragmentBinding? = null
    private val binding get() = _binding!!

    private lateinit var noteAdapter: InboxAdapter
    private lateinit var trashAdapter: TrashAdapter
    private val items = mutableListOf<NoteResponse>()
    private val trashItems = mutableListOf<TrashEntry>()
    private var loadingNotes = false
    private var loadingTrash = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = TrashFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalInboxApi.init(requireContext())

        binding.toolbar.title = "回收站"
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 双 tab 切换
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("笔记回收站"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("冲突归档"))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        binding.swipeTrash.visibility = View.GONE
                        binding.swipeNotes.visibility = View.VISIBLE
                        binding.toolbar.menu.clear()
                        if (items.isEmpty()) loadTrash()
                    }
                    1 -> {
                        binding.swipeNotes.visibility = View.GONE
                        binding.swipeTrash.visibility = View.VISIBLE
                        updateToolbarMenu()
                        if (trashItems.isEmpty()) loadSyncTrash()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        noteAdapter = InboxAdapter(
            onGesture = { note, _, _ -> restoreNote(note) },
            onOverflowClick = { note, anchor -> showItemMenu(note, anchor) },
            onParentClick = { },
            onTagClick = { },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = noteAdapter

        trashAdapter = TrashAdapter { entry, anchor ->
            showTrashMenu(entry, anchor)
        }
        binding.trashList.layoutManager = LinearLayoutManager(requireContext())
        binding.trashList.adapter = trashAdapter

        binding.swipeNotes.setOnRefreshListener { loadTrash() }
        binding.swipeTrash.setOnRefreshListener { loadSyncTrash() }

        loadTrash()
    }

    private fun updateToolbarMenu() {
        binding.toolbar.menu.clear()
        binding.toolbar.menu.add("清空归档").setOnMenuItemClickListener {
            clearSyncTrash()
            true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 笔记回收站 ====================

    private fun loadTrash() {
        if (loadingNotes) return
        loadingNotes = true
        binding.swipeNotes.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = LocalInboxApi.service.getNotes(limit = LIMIT, deleted = true)
                items.clear()
                items.addAll(list)
                noteAdapter.pinnedIds = emptySet()
                noteAdapter.submitList(ArrayList(items))
                if (items.isEmpty()) {
                    Toast.makeText(requireContext(), "回收站为空", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                loadingNotes = false
                binding.swipeNotes.isRefreshing = false
            }
        }
    }

    private fun showItemMenu(note: NoteResponse, anchor: View) {
        val themedCtx = ContextThemeWrapper(requireContext(), R.style.InboxPopupMenu)
        PopupMenu(themedCtx, anchor, Gravity.END).apply {
            menu.add("恢复").setOnMenuItemClickListener {
                restoreNote(note)
                true
            }
            show()
        }
    }

    private fun restoreNote(note: NoteResponse) {
        if (items.none { it.id == note.id }) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                LocalInboxApi.service.restoreNote(note.id)
                items.removeAll { it.id == note.id }
                noteAdapter.submitList(ArrayList(items))
                Toast.makeText(requireContext(), "已恢复笔记", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "恢复失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 冲突归档 ====================

    private fun loadSyncTrash() {
        if (loadingTrash) return
        loadingTrash = true
        binding.swipeTrash.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = SyncApiClient.api.getTrash()
                trashItems.clear()
                trashItems.addAll(resp.trash)
                trashAdapter.submit(ArrayList(trashItems))
                if (trashItems.isEmpty()) {
                    Toast.makeText(requireContext(), "暂无冲突归档", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                loadingTrash = false
                binding.swipeTrash.isRefreshing = false
            }
        }
    }

    private fun showTrashMenu(entry: TrashEntry, anchor: View) {
        val themedCtx = ContextThemeWrapper(requireContext(), R.style.InboxPopupMenu)
        PopupMenu(themedCtx, anchor, Gravity.END).apply {
            menu.add("恢复").setOnMenuItemClickListener {
                restoreTrash(entry)
                true
            }
            menu.add("永久删除").setOnMenuItemClickListener {
                deleteTrash(entry)
                true
            }
            show()
        }
    }

    private fun restoreTrash(entry: TrashEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SyncApiClient.api.restoreTrash(entry.id)
                trashItems.removeAll { it.id == entry.id }
                trashAdapter.submit(ArrayList(trashItems))
                Toast.makeText(requireContext(), "已恢复（合并到本库，如需生效请手动执行一次同步）", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "恢复失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteTrash(entry: TrashEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SyncApiClient.api.deleteTrash(entry.id)
                trashItems.removeAll { it.id == entry.id }
                trashAdapter.submit(ArrayList(trashItems))
                Toast.makeText(requireContext(), "已永久删除", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "删除失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearSyncTrash() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SyncApiClient.api.clearTrash()
                trashItems.clear()
                trashAdapter.submit(ArrayList(trashItems))
                Toast.makeText(requireContext(), "归档已清空", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "清空失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 冲突归档列表 Adapter ====================

    private inner class TrashAdapter(
        private val onItemClick: (TrashEntry, View) -> Unit,
    ) : RecyclerView.Adapter<TrashAdapter.VH>() {

        private val list = mutableListOf<TrashEntry>()

        fun submit(newItems: List<TrashEntry>) {
            list.clear()
            list.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            val tvTitle = TextView(ctx).apply {
                textSize = 15f
                setTextColor(ctx.getColor(R.color.inbox_text))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvSub = TextView(ctx).apply {
                textSize = 12f
                setTextColor(ctx.getColor(R.color.inbox_sub))
                maxLines = 2
            }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                addView(tvTitle)
                addView(tvSub)
            }
            return VH(root, tvTitle, tvSub)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = list[position]
            val kindLabel = when (entry.kind) {
                "inbox" -> "笔记"
                "todo" -> "待办"
                else -> entry.kind
            }
            holder.title.text = "[$kindLabel] ${entry.logicalKey}"
            val reason = when (entry.reason) {
                "remote_newer" -> "对端版本更新，本地被覆盖"
                "local_newer" -> "本地版本更新，对端被覆盖"
                else -> entry.reason
            }
            holder.sub.text = "${entry.archivedAt}  $reason  ${entry.sourceDevice ?: ""}".trim()
            holder.root.setOnClickListener { onItemClick(entry, holder.root) }
            holder.root.setOnLongClickListener {
                onItemClick(entry, holder.root)
                true
            }
        }

        override fun getItemCount() = list.size

        inner class VH(
            val root: View,
            val title: TextView,
            val sub: TextView,
        ) : RecyclerView.ViewHolder(root)
    }
}
