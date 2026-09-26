package net.activitywatch.android.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import net.activitywatch.android.R

/**
 * 批量操作指令（AI 返回的 JSON）的示例与输入对话框。
 *
 * 示例覆盖全部动作，本身就是可用的完整 JSON：改改 uuid / id 即可直接执行。
 * 对话框内置「复制示例」按钮，方便把模板复制出来交给 AI 续写。
 */

/** 笔记批量操作示例（create / update / delete / restore / add_tags / remove_tags / set_tags / comment） */
val NOTE_BATCH_EXAMPLE: String = """
{
  "operations": [
    {"action": "create", "content": "新笔记正文 #项目/工作", "tags": ["项目/工作"]},
    {"action": "update", "uuid": "在此填笔记ID", "content": "改后的正文", "tags": ["项目"]},
    {"action": "add_tags", "uuid": "在此填笔记ID", "tags": ["重要", "待办"]},
    {"action": "remove_tags", "uuid": "在此填笔记ID", "tags": ["待办"]},
    {"action": "set_tags", "uuid": "在此填笔记ID", "tags": ["项目/工作", "重要"]},
    {"action": "comment", "uuid": "在此填笔记ID", "content": "给这条笔记加一条评论"},
    {"action": "delete", "uuid": "在此填笔记ID"},
    {"action": "restore", "uuid": "在此填笔记ID"}
  ]
}
""".trimIndent()

/** 任务批量操作示例（含 set_completed / move / set_priority / set_due / 子任务 / comment） */
val TODO_BATCH_EXAMPLE: String = """
{
  "operations": [
    {"action": "create", "title": "新任务", "content": "备注", "tags": ["项目/工作"], "priority": 2, "due_date": "2026-10-01T00:00:00Z", "list_id": 0},
    {"action": "update", "uuid": "在此填任务ID", "title": "改后标题", "content": "改后备注"},
    {"action": "add_tags", "uuid": "在此填任务ID", "tags": ["重要"]},
    {"action": "remove_tags", "uuid": "在此填任务ID", "tags": ["重要"]},
    {"action": "set_completed", "uuid": "在此填任务ID", "completed": true},
    {"action": "move", "uuid": "在此填任务ID", "list_name": "工作"},
    {"action": "set_priority", "uuid": "在此填任务ID", "priority": 3},
    {"action": "set_due", "uuid": "在此填任务ID", "due_date": "2026-10-01T00:00:00Z"},
    {"action": "set_due", "uuid": "在此填任务ID", "clear_due": true},
    {"action": "add_subtask", "uuid": "在此填任务ID", "title": "子任务 1"},
    {"action": "set_subtask", "uuid": "在此填任务ID", "subtask_id": 1, "completed": true},
    {"action": "remove_subtask", "uuid": "在此填任务ID", "subtask_id": 1},
    {"action": "comment", "uuid": "在此填任务ID", "content": "追加到任务备注的评论"},
    {"action": "delete", "uuid": "在此填任务ID"},
    {"action": "restore", "uuid": "在此填任务ID"}
  ]
}
""".trimIndent()

/**
 * 弹出批量指令输入框。[onRun] 在点「执行」且内容非空时回调（JSON 文本）。
 * 「复制示例」把 [example] 拷到剪贴板，不关闭对话框。
 */
fun promptBatchCommands(
    context: Context,
    title: String,
    hintText: String,
    example: String,
    onRun: (String) -> Unit,
) {
    val density = context.resources.displayMetrics.density
    val dp = { v: Int -> (v * density).toInt() }

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(4))
    }
    container.addView(TextView(context).apply {
        text = hintText
        setTextColor(ContextCompat.getColor(context, R.color.inbox_sub))
        textSize = 12f
    })
    val edit = EditText(context).apply {
        hint = "在此粘贴 AI 返回的 JSON…"
        setMinLines(5)
        maxLines = 14
        gravity = Gravity.TOP or Gravity.START
        setTextColor(ContextCompat.getColor(context, R.color.inbox_text))
        setHintTextColor(ContextCompat.getColor(context, R.color.inbox_sub))
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }
    container.addView(edit)
    container.addView(Button(context).apply {
        text = "复制示例"
        setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("batch-example", example))
            Toast.makeText(context, "示例已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    })

    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(container) })
        .setPositiveButton("执行") { _, _ ->
            val text = edit.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) onRun(text)
        }
        .setNegativeButton("取消", null)
        .show()
}
