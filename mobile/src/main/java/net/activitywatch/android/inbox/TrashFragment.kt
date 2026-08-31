package net.activitywatch.android.inbox

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.InboxFragmentBinding

/**
 * 回收站：界面与普通笔记列表一致，展示已删除笔记。
 * 点击列表项（或长按菜单）可将笔记恢复到收件箱。
 */
class TrashFragment : Fragment() {

    companion object {
        private const val LIMIT = 50
    }

    private var _binding: InboxFragmentBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: InboxAdapter
    private val items = mutableListOf<NoteResponse>()
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = InboxFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalInboxApi.init(requireContext())

        binding.toolbar.title = "回收站"
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 回收站不需要搜索/刷新等菜单，仅保留返回
        binding.toolbar.menu.clear()

        // 回收站不显示快速笔记按钮和搜索栏
        binding.fab.visibility = View.GONE
        binding.searchBar.visibility = View.GONE

        adapter = InboxAdapter(
            onGesture = { note, _, _ -> restoreNote(note) },
            onOverflowClick = { note, anchor -> showItemMenu(note, anchor) },
            onParentClick = { },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { loadTrash() }

        loadTrash()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadTrash() {
        if (loading) return
        loading = true
        binding.swipe.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = LocalInboxApi.service.getNotes(limit = LIMIT, deleted = true)
                items.clear()
                items.addAll(list)
                adapter.pinnedIds = emptySet()
                adapter.submitList(ArrayList(items))
                if (items.isEmpty()) {
                    Toast.makeText(requireContext(), "回收站为空", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                loading = false
                binding.swipe.isRefreshing = false
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
        // 双击等场景可能重复触发，笔记已不在列表中就跳过
        if (items.none { it.id == note.id }) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                LocalInboxApi.service.restoreNote(note.id)
                items.removeAll { it.id == note.id }
                adapter.submitList(ArrayList(items))
                Toast.makeText(requireContext(), "已恢复笔记", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "恢复失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
