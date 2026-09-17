package net.activitywatch.android.inbox

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
 * 查看 / 编辑笔记（**同一个全屏页**，两态切换；编辑不再是弹出的 BottomSheet）。
 *
 * 查看态：
 * - 顶部单独显示该笔记的标签（服务端 tags 字段，**不**扫描正文），点标签回列表并按该路径筛选
 * - 正文默认纯文本展示，菜单里可开 Markdown 渲染（默认关，prefs 记忆）
 * - 轻点正文 / 菜单「编辑」→ 原地进入编辑态
 *
 * 编辑态：
 * - 正文换成输入框，底部 Markdown 工具栏 + 保存（➤）
 * - 标签可编辑：点 chip 上的 ✕ 移除、点「＋ 添加标签」手输或选常用标签
 * - 正文与标签一起提交（PUT /inbox/notes/<id>），**不从正文重新解析标签**
 *   （否则正文里那串 `#xxx` 会把用户刚移除的标签又登记回来）
 * - 返回键 / 导航箭头 / 菜单「取消编辑」：有改动先确认，再退回查看态
 *
 * 菜单「扫描标签」只在查看态可用（列出正文里的 tag 语法与真正的标签，逐个登记/移除）。
 */
class NoteViewFragment : Fragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_START_EDITING = "arg_start_editing"

        private const val MENU_EDIT = 2001
        private const val MENU_SAVE = 2002
        private const val MENU_MARKDOWN = 2003
        private const val MENU_SCAN = 2004
        private const val MENU_DETAIL = 2005
        private const val MENU_CANCEL_EDIT = 2006

        /** 添加标签弹窗里最多列出多少个已有标签 */
        private const val TAG_SUGGEST_LIMIT = 40

        fun newInstance(noteId: Long, startEditing: Boolean = false): NoteViewFragment {
            val f = NoteViewFragment()
            f.arguments = bundleOf(
                ARG_NOTE_ID to noteId,
                ARG_START_EDITING to startEditing,
            )
            return f
        }
    }

    private var _binding: NoteViewBinding? = null
    private val binding get() = _binding!!

    private var noteId: Long = 0L

    /** 当前笔记（内存态为最新：改标签后原地更新，避免重新拉整条） */
    private var note: NoteResponse? = null

    /** 是否处于编辑态 */
    private var editing = false

    /** 编辑态下待提交的标签（改动先落这里，保存时随正文一起提交） */
    private val editTags = mutableListOf<String>()

    /** 进入编辑态时记下的标签快照，用于判断「有没有改过」 */
    private val tagsSnapshot = mutableListOf<String>()

    /** 编辑态下拦截返回键：先退出编辑，而不是直接离开本页 */
    private val editBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = confirmExitEditing()
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
        _binding = NoteViewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalInboxApi.init(requireContext())

        // 界面主题：铺渐变背景，工具栏透明以透出顶部渐变
        GradientBackground.applyPage(requireContext(), binding.root, binding.toolbar)

        binding.toolbar.setNavigationOnClickListener {
            if (editing) confirmExitEditing() else close()
        }

        binding.toolbar.menu.add(Menu.NONE, MENU_EDIT, 0, "编辑").apply {
            setIcon(android.R.drawable.ic_menu_edit)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                enterEditing()
                true
            }
        }
        binding.toolbar.menu.add(Menu.NONE, MENU_SAVE, 0, "保存").apply {
            setIcon(android.R.drawable.ic_menu_save)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                saveEdit()
                true
            }
        }
        binding.toolbar.menu.add(Menu.NONE, MENU_CANCEL_EDIT, 1, "取消编辑").apply {
            setOnMenuItemClickListener {
                confirmExitEditing()
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

        // 轻点正文 → 进编辑
        binding.contentCard.setOnClickListener { if (!editing) enterEditing() }
        binding.btnAddTag.setOnClickListener { showAddTagDialog() }
        binding.save.setOnClickListener { saveEdit() }
        binding.editor.setOnFocusChangeListener { _, hasFocus ->
            if (editing && hasFocus) scrollToEditor()
        }

        // 井号/斜杠键插入字面字符（打 #标签 与层级 tag 的 a/b 分隔），不是 Markdown 语法
        binding.mdHash.setOnClickListener { MarkdownTextActions.insert(binding.editor, "#") }
        binding.mdBold.setOnClickListener { MarkdownTextActions.toggleWrap(binding.editor, "**") }
        binding.mdSlash.setOnClickListener { MarkdownTextActions.insert(binding.editor, "/") }
        binding.mdBullet.setOnClickListener { MarkdownTextActions.toggleBullet(binding.editor) }
        binding.mdOrdered.setOnClickListener { MarkdownTextActions.toggleOrdered(binding.editor) }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, editBackCallback)

        // 详情面板恢复历史版本后，把恢复的内容回填（编辑态回填输入框，查看态直接重载）
        parentFragmentManager.setFragmentResultListener(
            NoteDetailFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getLong(NoteDetailFragment.KEY_NOTE_ID) == noteId) {
                val restored = bundle.getString(NoteDetailFragment.KEY_CONTENT)
                if (editing) {
                    restored?.let { binding.editor.setText(it) }
                } else {
                    reload()
                }
            }
        }

        val startEditing = arguments?.getBoolean(ARG_START_EDITING) == true
        if (startEditing) {
            // 直接进编辑态：先拉一次笔记，拉到后再切
            loadThen(startEditing = true)
        } else {
            reload()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        editBackCallback.isEnabled = false
        editing = false
        _binding = null
    }

    // ==== 加载 / 渲染 ====

    private fun reload() = loadThen(startEditing = false)

    private fun loadThen(startEditing: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fresh = LocalInboxApi.service.getNote(noteId)
                note = fresh
                renderAll()
                if (startEditing) enterEditing()
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
        applyState()
    }

    /** 查看态 / 编辑态的视图与菜单切换 */
    private fun applyState() {
        binding.toolbar.title = if (editing) "编辑笔记" else "查看笔记"
        binding.content.visibility = if (editing) View.GONE else View.VISIBLE
        binding.editor.visibility = if (editing) View.VISIBLE else View.GONE
        binding.editBar.visibility = if (editing) View.VISIBLE else View.GONE
        binding.btnAddTag.visibility = if (editing) View.VISIBLE else View.GONE
        binding.contentCard.isClickable = !editing
        binding.contentCard.isFocusable = !editing
        binding.tvMeta.text = note?.let { buildMeta(it) } ?: ""
        editBackCallback.isEnabled = editing

        binding.toolbar.menu.findItem(MENU_EDIT)?.isVisible = !editing
        binding.toolbar.menu.findItem(MENU_SAVE)?.isVisible = editing
        binding.toolbar.menu.findItem(MENU_CANCEL_EDIT)?.isVisible = editing
        binding.toolbar.menu.findItem(MENU_MARKDOWN)?.isVisible = !editing
        binding.toolbar.menu.findItem(MENU_SCAN)?.isVisible = !editing
        binding.toolbar.menu.findItem(MENU_DETAIL)?.isVisible = !editing

        if (editing) {
            renderTagsEditor()
        } else {
            renderTags(note?.tags.orEmpty())
            renderMode()
        }
    }

    /** 顶部标签区（查看态）：只显示笔记真正的标签；无标签时给一句提示 */
    private fun renderTags(tags: List<String>) {
        val hasTags = tags.isNotEmpty()
        // 先清空：从编辑态退回查看态时，别把带 ✕ 的编辑态 chip 留在行里
        binding.tagsRow.removeAllViews()
        binding.tagsScroll.visibility = if (hasTags) View.VISIBLE else View.GONE
        binding.tvTagsEmpty.visibility = if (hasTags) View.GONE else View.VISIBLE
        binding.tvTagsTitle.text = if (hasTags) "标签 (${tags.size})" else "标签"
        binding.tvTagsEmpty.text = "无标签"
        if (hasTags) buildTagChips(binding.tagsRow, tags) { onTagClick(it) }
    }

    /**
     * 顶部标签区（编辑态）：每个 chip 带 ✕，点即从待提交的标签里移除；
     * 标题会提示当前数量。改动只在点保存时落库。
     */
    private fun renderTagsEditor() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val accent = ContextCompat.getColor(ctx, R.color.inbox_accent)

        binding.tagsRow.removeAllViews()
        binding.tagsScroll.visibility = if (editTags.isEmpty()) View.GONE else View.VISIBLE
        binding.tvTagsEmpty.visibility = if (editTags.isEmpty()) View.VISIBLE else View.GONE
        binding.tvTagsEmpty.text = "还没有标签，点下面「＋ 添加标签」"
        binding.tvTagsTitle.text =
            if (editTags.isEmpty()) "标签 · 编辑中" else "标签 (${editTags.size}) · 编辑中"

        editTags.forEach { tag ->
            val chip = TextView(ctx).apply {
                text = if (tagSegments(tag).size <= 1) {
                    "#$tag  ✕"
                } else {
                    "#${formatTagBreadcrumb(tag)}  ✕"
                }
                setTextColor(accent)
                textSize = 13f
                background = ContextCompat.getDrawable(ctx, R.drawable.inbox_tag_chip_bg)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                gravity = Gravity.CENTER
                contentDescription = "移除标签 $tag"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    editTags.remove(tag)
                    renderTagsEditor()
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            lp.bottomMargin = dp(4)
            binding.tagsRow.addView(chip, lp)
        }
    }

    /** 正文展示模式：纯文本（默认）或 Markdown 渲染 */
    private fun renderMode() {
        val n = note ?: return
        if (editing) {
            binding.tvMode.text = "编辑中 · 正文与标签保存时一起提交"
            return
        }
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

    // ==== 查看 / 编辑切换 ====

    private fun enterEditing() {
        val n = note ?: return
        if (editing) return
        editing = true
        editTags.clear()
        editTags.addAll(n.tags)
        tagsSnapshot.clear()
        tagsSnapshot.addAll(n.tags)
        binding.editor.setText(n.content)
        applyState()
        binding.editor.requestFocus()
        binding.editor.postDelayed({
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            imm.showSoftInput(binding.editor, InputMethodManager.SHOW_IMPLICIT)
            scrollToEditor()
        }, 150)
    }

    /** 退出编辑态（回到查看态），不落库 */
    private fun exitEditing() {
        editing = false
        hideKeyboard()
        binding.editor.setText("")
        applyState()
    }

    /** 有改动就先确认再退出，避免误触丢掉输入 */
    private fun confirmExitEditing() {
        if (!editing) return
        val content = binding.editor.text?.toString() ?: ""
        val dirty = content != (note?.content ?: "") || editTags != tagsSnapshot
        if (!dirty) {
            exitEditing()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("放弃这次修改？")
            .setMessage("正文或标签改过但还没保存。")
            .setPositiveButton("放弃修改") { _, _ -> exitEditing() }
            .setNegativeButton("继续编辑", null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
            as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editor.windowToken, 0)
    }

    private fun scrollToEditor() {
        binding.scroll.post {
            val target = binding.contentCard.top
            binding.scroll.smoothScrollTo(0, target)
        }
    }

    private fun saveEdit() {
        if (!editing) return
        val n = note ?: return
        val content = binding.editor.text?.toString() ?: ""
        if (content.isBlank()) {
            Toast.makeText(requireContext(), "内容不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        // 标签只在编辑态里增删（不在保存时重新扫描正文）：
        // 正文里那串 #xxx 是纯文本，重新解析会把用户刚移除的标签又登记回来。
        val tags = editTags.toList()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = LocalInboxApi.service.updateNote(
                    n.id,
                    UpsertNotePayload(content = content, tags = tags),
                )
                note = saved
                exitEditing()
                renderAll()
                Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==== 标签编辑（编辑态） ====

    /**
     * 添加标签：可手输（支持 `项目/工作` 这种层级），也可点常用标签。
     * 只改本地 editTags，保存时随正文一起提交。
     */
    private fun showAddTagDialog() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val input = EditText(ctx).apply {
            hint = "标签名，可用 / 分层，如 项目/工作"
            maxLines = 1
            setTextColor(ContextCompat.getColor(ctx, R.color.inbox_text))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.inbox_sub))
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val suggestTitle = TextView(ctx).apply {
            text = "常用标签"
            setTextColor(ContextCompat.getColor(ctx, R.color.inbox_sub))
            textSize = 12f
            setPadding(dp(20), dp(12), dp(20), dp(4))
            visibility = View.GONE
        }
        val suggestBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val suggestScroll = ScrollView(ctx).apply {
            addView(suggestBox)
            visibility = View.GONE
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(suggestTitle)
            addView(
                suggestScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(180),
                ),
            )
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("添加标签")
            .setView(container)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dialog.show()
        // 「添加」不自动关闭：同一次里可以连加几个
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val raw = input.text?.toString().orEmpty()
            val tag = normalizeTag(raw)
            if (tag.isEmpty()) {
                Toast.makeText(ctx, "请输入标签名", Toast.LENGTH_SHORT).show()
            } else {
                addTagToEdit(tag)
                input.setText("")
            }
        }
        input.requestFocus()

        // 常用标签（服务端已有标签，排除当前已在待提交列表里的）
        viewLifecycleOwner.lifecycleScope.launch {
            val all = runCatching { LocalInboxApi.service.getTags() }.getOrNull().orEmpty()
            val candidates = all
                .filter { it.isNotBlank() && it !in editTags }
                .take(TAG_SUGGEST_LIMIT)
            if (candidates.isEmpty() || !isAdded) return@launch
            suggestTitle.visibility = View.VISIBLE
            suggestScroll.visibility = View.VISIBLE
            candidates.forEach { tag ->
                val row = TextView(ctx).apply {
                    text = if (tagSegments(tag).size <= 1) "#$tag" else "#${formatTagBreadcrumb(tag)}"
                    setTextColor(ContextCompat.getColor(ctx, R.color.inbox_accent))
                    textSize = 14f
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                    isClickable = true
                    setOnClickListener {
                        addTagToEdit(tag)
                        dialog.dismiss()
                    }
                }
                suggestBox.addView(row)
            }
        }
    }

    /** 把标签加入待提交列表（重复则提示，不重复加） */
    private fun addTagToEdit(tag: String) {
        if (tag in editTags) {
            Toast.makeText(requireContext(), "已有标签 $tag", Toast.LENGTH_SHORT).show()
            return
        }
        editTags.add(tag)
        renderTagsEditor()
    }

    /** 标签规范化：去掉首尾空白与开头的 #、去掉内部空白、折叠多余的 / */
    private fun normalizeTag(raw: String): String =
        raw.trim().trimStart('#').trim()
            .replace(Regex("\\s+"), "")
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/")

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
