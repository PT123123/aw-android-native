package net.activitywatch.android.inbox

import android.app.Dialog
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.NoteEditorBinding

class NoteEditorFragment : BottomSheetDialogFragment() {

    private var _binding: NoteEditorBinding? = null
    private val binding get() = _binding!!

    private var note: NoteResponse? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // MaterialComponents 主题的 BottomSheet，点外部/下拉即关闭
        return BottomSheetDialog(requireContext(), R.style.InboxBottomSheetDialogTheme)
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        // 全展开：键盘弹出时窗口随 adjustResize 缩小，内容完整显示在键盘上方
        d.behavior.peekHeight = resources.displayMetrics.heightPixels / 2
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        d.behavior.isHideable = true
        // 去掉 sheet 默认白色圆角背景，使用我们自己的深色布局背景
        d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
        // Dialog 有独立 window，Activity 的 adjustResize 不生效，必须在这里显式声明：
        // 键盘弹出时缩小 window，保存按钮和 Markdown 工具栏才不会被输入法遮住
        d.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        note = arguments?.getSerializable(ARG_NOTE) as? NoteResponse
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = NoteEditorBinding.inflate(inflater, container, false)
        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalInboxApi.init(requireContext())

        binding.toolbar.setNavigationOnClickListener {
            dismiss()
        }

        if (note != null) {
            binding.editor.setText(note!!.content)
            binding.toolbar.menu.add(Menu.NONE, MENU_HISTORY, Menu.NONE, "历史版本").apply {
                setIcon(R.drawable.ic_history)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener {
                    openHistory()
                    true
                }
            }
        }
        binding.save.setOnClickListener { save() }

        binding.mdHeading.setOnClickListener { MarkdownTextActions.cycleHeading(binding.editor) }
        binding.mdBold.setOnClickListener { MarkdownTextActions.toggleWrap(binding.editor, "**") }
        binding.mdItalic.setOnClickListener { MarkdownTextActions.toggleWrap(binding.editor, "*") }
        binding.mdBullet.setOnClickListener { MarkdownTextActions.toggleBullet(binding.editor) }
        binding.mdOrdered.setOnClickListener { MarkdownTextActions.toggleOrdered(binding.editor) }

        // 历史面板恢复版本后，把恢复的内容回填到编辑框
        parentFragmentManager.setFragmentResultListener(
            NoteHistoryFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getLong(NoteHistoryFragment.KEY_NOTE_ID) == note?.id) {
                bundle.getString(NoteHistoryFragment.KEY_CONTENT)?.let {
                    binding.editor.setText(it)
                }
            }
        }

        // 聚焦编辑框并弹出输入法
        binding.editor.requestFocus()
        view?.postDelayed({
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            imm.showSoftInput(binding.editor, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun save() {
        val content = binding.editor.text?.toString() ?: ""
        if (content.isBlank()) {
            Toast.makeText(requireContext(), "内容不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val tags = parseTags(content)
        val payload = UpsertNotePayload(content = content, tags = tags)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (note == null) {
                    LocalInboxApi.service.createNote(payload)
                } else {
                    LocalInboxApi.service.updateNote(note!!.id, payload)
                }
                dismiss()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openHistory() {
        val n = note ?: return
        NoteHistoryFragment.newInstance(n.id)
            .show(parentFragmentManager, "note_history")
    }

    companion object {
        private const val ARG_NOTE = "arg_note"
        private const val MENU_HISTORY = 1001

        fun newInstance(note: NoteResponse?): NoteEditorFragment {
            val f = NoteEditorFragment()
            val args = Bundle()
            if (note != null) args.putSerializable(ARG_NOTE, note)
            f.arguments = args
            return f
        }
    }
}