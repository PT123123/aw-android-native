package net.activitywatch.android.inbox

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.NoteViewBinding
import net.activitywatch.android.ui.GradientBackground

/**
 * 查看笔记（全屏页，替代原「双击 → 弹出编辑面板」）。
 *
 * - 顶部单独显示该笔记的标签（服务端 tags 字段，**不**扫描正文），点标签回列表并按该路径筛选
 * - 正文默认纯文本展示，菜单里可开 Markdown 渲染（默认关，prefs 记忆）
 * - 轻点正文 = 进入编辑（编辑仍是弹出面板）
 * - 菜单「扫描标签」：列出正文里 tag 语法的文字 + 已登记的标签，
 *   区分「标签 / 纯文本」，可逐个 × 移除或 + 登记 —— 这就是纯文本与标签的分离入口
 */
class NoteViewFragment : Fragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"

        private const val MENU_EDIT = 2001
        private const val MENU_MARKDOWN = 2002
        private const val MENU_SCAN = 2003
        private const val MENU_DETAIL = 2004

        fun newInstance(noteId: Long): NoteViewFragment {
            val f = NoteViewFragment()
            f.arguments = bundleOf(ARG_NOTE_ID to noteId)
            return f
        }
    }

    private var _binding: NoteViewBinding? = null
    private val binding get() = _binding!!

    private var noteId: Long = 0L

    /** 当前笔记（内存态为最新：改标签后原地更新，避免重新拉整条） */
    private var note: NoteResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = arguments?.getLong(ARG_NOTE_ID) ?: 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = NoteViewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalInboxApi.init(requireContext())

        // 界面主题：铺渐变背景，工具栏透明以透出顶部渐变
        GradientBackground.applyPage(requireContext(), binding.root, binding.toolbar)

        binding.toolbar.setNavigationOnClickListener { close() }

        binding.toolbar.menu.add(Menu.NONE, MENU_EDIT, 0, "编辑").apply {
            setIcon(android.R.drawable.ic_menu_edit)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                openEditor()
                true
            }
        }
        binding.toolbar.menu.add(Menu.NONE, MENU_MARKDOWN, 1, "Markdown 渲染").apply {
            isCheckable = true
            isChecked = InboxPrefs.viewMarkdown(requireContext())
            setOnMenuItemClickListener {
                val next = !InboxPrefs.viewMarkdown(requireContext())
                InboxPrefs.setViewMarkdown(requireContext(), next)
                it.isChecked = next
                renderMode()
                true
            }
        }
        binding.toolbar.menu.add(Menu.NONE, MENU_SCAN, 2, "扫描标签").apply {
            setOnMenuItemClickListener {
                showScanDialog()
                true
            }
        }
        binding.toolbar.menu.add(Menu.NONE, MENU_DETAIL, 3, "详细信息").apply {
            setOnMenuItemClickListener {
                NoteDetailFragment.newInstance(noteId).show(parentFragmentManager, "note_detail")
                true
            }
        }

        // 轻点正文 → 编辑（查看页多一步才进编辑）
        binding.contentCard.setOnClickListener { openEditor() }

        // 编辑保存后回来刷新正文/标签
        parentFragmentManager.setFragmentResultListener(
            NoteEditorFragment.RESULT_KEY_SAVED, viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getLong(NoteEditorFragment.KEY_NOTE_ID) == noteId) reload()
        }

        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==== 加载 / 渲染 ====

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fresh = LocalInboxApi.service.getNote(noteId)
                note = fresh
                renderAll()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderAll() {
        val n = note ?: return
        renderTags(n.tags)
        renderMode()
        binding.tvMeta.text = buildMeta(n)
    }

    /** 顶部标签区：只显示笔记真正的标签；无标签时给一句提示 */
    private fun renderTags(tags: List<String>) {
        val hasTags = tags.isNotEmpty()
        binding.tagsScroll.visibility = if (hasTags) View.VISIBLE else View.GONE
        binding.tvTagsEmpty.visibility = if (hasTags) View.GONE else View.VISIBLE
        binding.tvTagsTitle.text = if (hasTags) "标签 (${tags.size})" else "标签"
        if (hasTags) buildTagChips(binding.tagsRow, tags) { onTagClick(it) }
    }

    /** 正文展示模式：纯文本（默认）或 Markdown 渲染 */
    private fun renderMode() {
        val n = note ?: return
        binding.toolbar.menu.findItem(MENU_MARKDOWN)?.isChecked =
            InboxPrefs.viewMarkdown(requireContext())
        val markdown = InboxPrefs.viewMarkdown(requireContext())
        binding.content.text = if (markdown) {
            // 正文里的 #xxx 不再着色：标签只在标签区呈现
            MarkdownRenderer.render(requireContext(), n.content, highlightTags = false)
        } else {
            n.content
        }
        binding.tvMode.text = if (markdown) {
            "Markdown 渲染 · 轻点正文进入编辑"
        } else {
            "纯文本显示 · 轻点正文进入编辑"
        }
    }

    private fun buildMeta(n: NoteResponse): String {
        val parts = mutableListOf<String>()
        parts.add("v${n.version}")
        formatDateTime(n.created_at)?.let { parts.add("创建于 $it") }
        formatDateTime(n.updated_at)?.let { parts.add("修改于 $it") }
        n.device_id?.let { parts.add(DeviceNameResolver.resolve(requireContext(), it)) }
        parts.add("${n.content.length} 字")
        return TextUtils.join(" · ", parts)
    }

    private fun formatDateTime(s: String?): String? {
        if (s.isNullOrEmpty()) return null
        val d = InboxAdapter.parseTime(s) ?: return s
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(d)
    }

    // ==== 交互 ====

    private fun close() {
        if (!parentFragmentManager.popBackStackImmediate()) {
            // 兜底：容器里只有本页时（深链冷启动等），回退到收集箱，避免空白页
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, InboxFragment())
                .commit()
        }
    }

    /** 点标签 → 回笔记列表并按该标签路径筛选 */
    private fun onTagClick(tag: String) {
        InboxFragment.pendingTagFilter = tag
        close()
    }

    private fun openEditor() {
        val n = note ?: return
        NoteEditorFragment.newInstance(n).show(parentFragmentManager, "note_editor")
    }

    // ==== 扫描标签：把正文里的 tag 语法与真正的标签分开处理 ====

    /** 扫描结果一行：tag + 是否已登记为标签 */
    private data class ScanRow(val tag: String, val registered: Boolean)

    /** 扫描弹窗的行状态与容器（弹窗内点按后原地刷新，不必重开弹窗） */
    private val scanRows = mutableListOf<ScanRow>()
    private var scanContainer: LinearLayout? = null

    private fun showScanDialog() {
        val n = note ?: return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        scanContainer = container
        val scroll = ScrollView(ctx).apply { addView(container) }

        if (parseTags(n.content).isEmpty() && n.tags.isEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("扫描标签")
                .setMessage("正文里没有 tag 语法的文字，这篇笔记也没有标签。")
                .setPositiveButton("关闭", null)
                .show()
            return
        }

        // 弹窗关闭时清掉容器引用，避免持有已销毁的视图
        refreshScanRows()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("扫描标签")
            .setMessage("正文里 tag 语法的文字未必是标签。「标签」= 已登记；「纯文本」= 只是文字，可点「设为标签」登记。")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setOnDismissListener { scanContainer = null }
            .show()
    }

    /** 重新扫描（正文 + 已登记标签）并重画：先列正文里出现的，再列「已登记但正文里没有」的 */
    private fun refreshScanRows() {
        val cur = note ?: return
        val inBody = parseTags(cur.content)
        scanRows.clear()
        inBody.forEach { scanRows.add(ScanRow(it, it in cur.tags)) }
        cur.tags.filter { it !in inBody }.forEach { scanRows.add(ScanRow(it, true)) }
        renderScanRows()
    }

    private fun renderScanRows() {
        val container = scanContainer ?: return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        container.removeAllViews()
        scanRows.forEach { row ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val name = TextView(ctx).apply {
                text = "#${formatTagBreadcrumb(row.tag)}"
                setTextColor(tagTextColor(ctx, row.registered))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            line.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val state = TextView(ctx).apply {
                text = if (row.registered) "标签" else "纯文本"
                setTextColor(ContextCompat.getColor(ctx, R.color.inbox_sub))
                textSize = 12f
                setPadding(dp(6), 0, dp(10), 0)
            }
            line.addView(state)
            // 已登记 → ✕ 移除标签（正文不动）；未登记 → ＋ 登记为标签
            val button = TextView(ctx).apply {
                text = if (row.registered) "✕ 移除" else "＋ 设为标签"
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (row.registered) R.color.inbox_accent else R.color.inbox_text,
                    )
                )
                textSize = 13f
                background = ContextCompat.getDrawable(ctx, R.drawable.inbox_tag_chip_bg)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (row.registered) removeTag(row.tag) else registerTag(row.tag)
                }
            }
            line.addView(button)
            container.addView(line)
        }
    }

    /** 从笔记标签里去掉一个（正文不动，正文里那串文字从此就是普通文字） */
    private fun removeTag(tag: String) {
        applyTags(note?.tags.orEmpty().filter { it != tag })
    }

    /** 把正文里的纯文本登记成标签 */
    private fun registerTag(tag: String) {
        val tags = note?.tags.orEmpty()
        if (tag in tags) return
        applyTags(tags + tag)
    }

    private fun applyTags(tags: List<String>) {
        val n = note ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = LocalInboxApi.service.updateNote(
                    n.id,
                    UpsertNotePayload(content = n.content, tags = tags),
                )
                note = saved
                renderTags(saved.tags)
                refreshScanRows()
                Toast.makeText(requireContext(), "标签已更新", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "更新失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
