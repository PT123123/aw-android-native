package net.activitywatch.android.todo

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.TodoDetailDialogBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 任务详情对话框（新建 / 编辑）。
 * 字段对齐服务端契约：标题 / 完成 / 清单（tag 模拟）/ 优先级 / 截止日期 / 标签 / 备注。
 * 保存用 lifecycleScope 调 TodoApi；完成后回调 onSaved / onDeleted 让列表页刷新。
 */
class TodoDetailDialog : DialogFragment() {

    private lateinit var b: TodoDetailDialogBinding
    private var todo: TodoResponse? = null
    private val listNames = mutableListOf<String>()
    private var defaultListTag: String? = null

    private var selectedListTag: String? = null
    private var selectedPriority = 0
    private var selectedDue: String? = null

    /** 保存成功后回调（Fragment 刷新列表） */
    var onSaved: (() -> Unit)? = null
    /** 删除成功后回调（Fragment 刷新列表） */
    var onDeleted: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            @Suppress("DEPRECATION")
            todo = it.getSerializable(ARG_TODO) as? TodoResponse
            it.getStringArrayList(ARG_LISTS)?.let { names ->
                listNames.clear()
                listNames.addAll(names)
            }
            defaultListTag = it.getString(ARG_DEFAULT_TAG)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        b = TodoDetailDialogBinding.inflate(LayoutInflater.from(requireContext()))
        val dialog = Dialog(requireContext())
        dialog.setContentView(b.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        setup()
        return dialog
    }

    private fun setup() {
        val t = todo
        b.dlgTitle.text = if (t == null) "新建任务" else "编辑任务"

        selectedListTag = t?.tags?.firstOrNull() ?: defaultListTag
        selectedPriority = (t?.priority ?: 0L).toInt()
        selectedDue = t?.dueDate

        b.dTitle.setText(t?.title ?: "")
        b.dDone.isChecked = t?.completed == true
        b.dTags.setText(t?.tags?.joinToString(" ") ?: "")
        b.dNotes.setText(t?.content ?: "")
        b.dDelete.visibility = if (t == null) View.GONE else View.VISIBLE

        refreshListValue()
        refreshPriorityValue()
        refreshDueValue()

        b.dListValue.setOnClickListener { pickList() }
        b.dPriorityValue.setOnClickListener { pickPriority() }
        b.dDueValue.setOnClickListener { showDatePicker() }
        b.dDueClear.setOnClickListener {
            selectedDue = null
            refreshDueValue()
        }
        b.dSave.setOnClickListener { save() }
        b.dDelete.setOnClickListener { delete() }
    }

    // ── 交互 ────────────────────────────────────────────────

    private fun pickList() {
        val names = listOf("收集箱") + listNames
        val current = selectedListTag?.let { tag ->
            names.indexOfFirst { it == tag }.coerceAtLeast(0)
        } ?: 0
        AlertDialog.Builder(requireContext())
            .setTitle("选择清单")
            .setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
                selectedListTag = if (which == 0) null else names[which]
                refreshListValue()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickPriority() {
        val labels = arrayOf("无", "低", "中", "高")
        AlertDialog.Builder(requireContext())
            .setTitle("选择优先级")
            .setSingleChoiceItems(labels, selectedPriority) { d, which ->
                selectedPriority = which
                refreshPriorityValue()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        selectedDue?.let {
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)?.let { parsed -> cal.time = parsed }
            } catch (_: Exception) {
            }
        }
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                selectedDue = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                refreshDueValue()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun refreshListValue() {
        b.dListValue.text = selectedListTag ?: "收集箱"
    }

    private fun refreshPriorityValue() {
        b.dPriorityValue.text = arrayOf("无", "低", "中", "高")[selectedPriority]
    }

    private fun refreshDueValue() {
        b.dDueValue.text = selectedDue ?: "无期限"
    }

    // ── 保存 / 删除 ─────────────────────────────────────────

    private fun save() {
        val title = b.dTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            Toast.makeText(requireContext(), "标题不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val notes = b.dNotes.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val tags = b.dTags.text?.toString()?.trim()
            ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
        val due = selectedDue?.toRfc3339()
        val priority = selectedPriority.takeIf { it > 0 }
        val t = todo

        b.dSave.isEnabled = false
        lifecycleScope.launch {
            try {
                if (t == null) {
                    TodoApi.service.createTodo(
                        CreateTodoPayload(
                            title = title,
                            content = notes,
                            priority = priority,
                            due_date = due,
                            tags = tags,
                        )
                    )
                } else {
                    TodoApi.service.updateTodo(
                        t.id,
                        UpdateTodoPayload(
                            title = title,
                            content = notes,
                            completed = if (b.dDone.isChecked == t.completed) null else b.dDone.isChecked,
                            priority = priority,
                            due_date = due,
                            tags = tags,
                        )
                    )
                }
                dismiss()
                onSaved?.invoke()
            } catch (e: Exception) {
                b.dSave.isEnabled = true
                Toast.makeText(requireContext(), "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun delete() {
        val t = todo ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("删除任务")
            .setMessage("确定删除「${t.title}」？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        TodoApi.service.deleteTodo(t.id)
                        dismiss()
                        onDeleted?.invoke()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val ARG_TODO = "todo"
        private const val ARG_LISTS = "lists"
        private const val ARG_DEFAULT_TAG = "default_tag"

        fun newInstance(
            todo: TodoResponse?,
            listNames: List<String>,
            defaultListTag: String?,
        ) = TodoDetailDialog().apply {
            arguments = Bundle().apply {
                putSerializable(ARG_TODO, todo)
                putStringArrayList(ARG_LISTS, ArrayList(listNames))
                putString(ARG_DEFAULT_TAG, defaultListTag)
            }
        }
    }
}
