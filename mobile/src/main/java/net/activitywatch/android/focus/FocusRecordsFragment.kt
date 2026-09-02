package net.activitywatch.android.focus

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import net.activitywatch.android.R
import net.activitywatch.android.todo.TodoRepository

/**
 * 专注记录页（契约 §5.8）。
 *
 * 按天分组列出全部会话；点某条 → 专注记录详情弹窗（record_detail 模块）；
 * 长按 → 删除。数据变化由 FocusStore 的 onChange 驱动重渲染。
 */
class FocusRecordsFragment : Fragment() {

    private var body: LinearLayout? = null
    private val changed: () -> Unit = { rebuild() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        FocusStore.init(requireContext())
        val (toolbar, content) = FocusUi.buildRoot(this, "专注记录")
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_focus_modules) {
                FocusUi.showModulesDialog(this) { rebuild() }
                true
            } else false
        }
        body = content
        FocusStore.addListener(changed)
        rebuild()
        return content.parent as View
    }

    override fun onDestroyView() {
        FocusStore.removeListener(changed)
        body = null
        super.onDestroyView()
    }

    private fun rebuild() {
        val ctx = requireContext()
        val content = body ?: return
        content.removeAllViews()
        if (!FocusModules.enabled(ctx, "records")) {
            content.addView(FocusUi.disabledHint(this, "records"))
            return
        }

        val sessions = FocusStore.sessions()
        if (sessions.isEmpty()) {
            content.addView(FocusUi.label(ctx, "还没有专注记录，去「计时」开始一次吧", 14f, R.color.aw_text_disabled))
            return
        }

        // 按天分组（sessions 已按 start 降序）
        val groups = LinkedHashMap<String, MutableList<FocusSession>>()
        for (s in sessions) groups.getOrPut(FocusDates.dayOf(s.start)) { mutableListOf() }.add(s)

        for ((day, list) in groups) {
            val total = list.sumOf { it.minutes }
            val header = FocusUi.label(
                ctx,
                "$day · ${list.size} 次 · $total 分钟",
                13f,
                R.color.aw_text_secondary,
                bold = true,
            ).apply { setPadding(0, FocusUi.dp(ctx, 10), 0, FocusUi.dp(ctx, 4)) }
            content.addView(header)

            for (s in list) {
                content.addView(recordRow(s))
            }
        }
    }

    private fun recordRow(s: FocusSession): View {
        val ctx = requireContext()
        val row = FocusUi.card(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                FocusUi.dp(ctx, 14), FocusUi.dp(ctx, 10),
                FocusUi.dp(ctx, 14), FocusUi.dp(ctx, 10)
            )
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, FocusUi.dp(ctx, 8))
            }
            setOnClickListener { showDetail(s) }
            setOnLongClickListener {
                confirmDelete(s)
                true
            }
        }
        val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(FocusUi.label(ctx, s.title, 15f, R.color.aw_text_primary, bold = true))
        textCol.addView(
            FocusUi.label(ctx, FocusDates.DISPLAY.formatOrNull(s.start), 12f, R.color.aw_text_secondary)
                .apply { setPadding(0, FocusUi.dp(ctx, 2), 0, 0) }
        )
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(
            FocusUi.label(ctx, "${s.minutes} 分钟", 14f, R.color.aw_accent, bold = true),
            LinearLayout.LayoutParams(-2, -2)
        )
        return row
    }

    /** 专注记录详情（契约 §5.8 第 3 个模块） */
    private fun showDetail(s: FocusSession) {
        val ctx = requireContext()
        if (!FocusModules.enabled(ctx, "record_detail")) {
            Toast.makeText(ctx, "「专注记录详情」模块已停用", Toast.LENGTH_SHORT).show()
            return
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(FocusUi.dp(ctx, 24), FocusUi.dp(ctx, 12), FocusUi.dp(ctx, 24), 0)
        }
        fun kv(k: String, v: String) {
            box.addView(FocusUi.label(ctx, k, 12f, R.color.aw_text_secondary))
            box.addView(
                FocusUi.label(ctx, v, 15f, R.color.aw_text_primary)
                    .apply { setPadding(0, FocusUi.dp(ctx, 2), 0, FocusUi.dp(ctx, 10)) }
            )
        }
        kv("事件名", s.title)
        kv("关联任务", taskTitle(s.taskId))
        kv("开始时间", FocusDates.DISPLAY.formatOrNull(s.start))
        kv("时长", "${s.minutes} 分钟")
        AlertDialog.Builder(ctx)
            .setTitle("专注记录详情")
            .setView(box)
            .setPositiveButton("关闭", null)
            .setNeutralButton("删除") { _, _ -> confirmDelete(s) }
            .show()
    }

    private fun confirmDelete(s: FocusSession) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除记录")
            .setMessage("删除「${s.title}」这条专注记录？")
            .setPositiveButton("删除") { _, _ ->
                FocusStore.deleteSession(s.id)
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun taskTitle(taskId: Long): String {
        if (taskId == 0L) return "无"
        val t = runCatching { TodoRepository.source(requireContext()).tasks() }
            .getOrNull()?.firstOrNull { it.id == taskId }
        return t?.title ?: "（已删除的任务）"
    }

    private fun java.text.SimpleDateFormat.formatOrNull(iso: String): String =
        FocusDates.parseIso(iso)?.let { format(it) } ?: iso
}
