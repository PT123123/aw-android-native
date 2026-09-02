package net.activitywatch.android.focus

import android.app.DatePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import net.activitywatch.android.R
import java.util.Calendar

/**
 * 倒数纪念日页（契约 §5.8）：倒数日 + 每年重复的纪念日。
 * 支持新增（标题 + 日期 + 每年重复）与长按删除。
 */
class FocusCountdownFragment : Fragment() {

    private var body: LinearLayout? = null
    private val changed: () -> Unit = { rebuild() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        FocusStore.init(requireContext())
        val (toolbar, content) = FocusUi.buildRoot(this, "倒数纪念日")
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
        if (!FocusModules.enabled(ctx, "countdown")) {
            content.addView(FocusUi.disabledHint(this, "countdown"))
            return
        }

        // ＋ 新增
        content.addView(TextView(ctx).apply {
            text = "＋ 新增倒数日"
            textSize = 15f
            setTextColor(FocusUi.color(ctx, R.color.aw_accent))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, FocusUi.dp(ctx, 12))
            setOnClickListener { showAddDialog() }
        }, LinearLayout.LayoutParams(-1, -2))

        val items = FocusStore.countdowns()
        if (items.isEmpty()) {
            content.addView(FocusUi.label(ctx, "还没有倒数日或纪念日", 14f, R.color.aw_text_disabled).apply {
                gravity = Gravity.CENTER
                setPadding(0, FocusUi.dp(ctx, 24), 0, 0)
            })
            return
        }
        for (c in items) {
            content.addView(countdownRow(c))
        }
    }

    private fun countdownRow(c: CountdownItem): View {
        val ctx = requireContext()
        val days = FocusDates.daysUntil(c.date, c.yearly)
        val status = when {
            days == 0 -> "就是今天"
            days > 0 -> "还有 $days 天"
            else -> "已过 ${-days} 天"
        }
        val accent = days in 0..7 || days == 0
        return FocusUi.card(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, FocusUi.dp(ctx, 8))
            }
            setOnLongClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("删除")
                    .setMessage("删除「${c.title}」？")
                    .setPositiveButton("删除") { _, _ ->
                        FocusStore.deleteCountdown(c.id)
                        Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            addView(FocusUi.label(ctx, c.title, 15f, R.color.aw_text_primary, bold = true))
            addView(FocusUi.label(
                ctx,
                "${c.date}${if (c.yearly) " · 每年" else ""}",
                12f, R.color.aw_text_secondary
            ).apply { setPadding(0, FocusUi.dp(ctx, 2), 0, 0) })
            addView(FocusUi.label(
                ctx, status, 14f,
                if (accent) R.color.aw_accent else R.color.aw_text_secondary,
                bold = accent
            ).apply { setPadding(0, FocusUi.dp(ctx, 6), 0, 0) })
        }
    }

    private fun showAddDialog() {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(FocusUi.dp(ctx, 24), FocusUi.dp(ctx, 12), FocusUi.dp(ctx, 24), 0)
        }
        val titleInput = FocusUi.input(ctx, "标题（如：项目截止 / 生日）")
        box.addView(titleInput)

        var picked: Calendar = FocusDates.today()
        val dateValue = TextView(ctx).apply {
            text = "选择日期（点按）：${FocusDates.DAY.format(picked.time)}"
            textSize = 14f
            setTextColor(FocusUi.color(ctx, R.color.aw_accent))
            setPadding(0, FocusUi.dp(ctx, 16), 0, FocusUi.dp(ctx, 4))
            setOnClickListener {
                DatePickerDialog(
                    ctx,
                    { _, y, m, d ->
                        picked = FocusDates.today().apply { set(y, m, d) }
                        text = "选择日期（点按）：${FocusDates.DAY.format(picked.time)}"
                    },
                    picked.get(Calendar.YEAR),
                    picked.get(Calendar.MONTH),
                    picked.get(Calendar.DAY_OF_MONTH),
                ).show()
            }
        }
        box.addView(dateValue)

        val yearly = CheckBox(ctx).apply {
            text = "每年重复（纪念日）"
            textSize = 14f
            setTextColor(FocusUi.color(ctx, R.color.aw_text_primary))
        }
        box.addView(yearly, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, FocusUi.dp(ctx, 8), 0, 0)
        })

        AlertDialog.Builder(ctx)
            .setTitle("新增倒数日")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(ctx, "标题不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                FocusStore.addCountdown(title, FocusDates.DAY.format(picked.time), yearly.isChecked)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
