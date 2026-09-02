package net.activitywatch.android.focus

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import net.activitywatch.android.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日历页（契约 §5.8）：月历视图，格子里显示当日专注分钟数，
 * 点某天在下方列出当天的会话明细。
 */
class FocusCalendarFragment : Fragment() {

    private var body: LinearLayout? = null
    private var month: Calendar = FocusDates.today().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var selectedDay: String = FocusDates.DAY.format(Date())

    private val changed: () -> Unit = { rebuild() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        FocusStore.init(requireContext())
        val (toolbar, content) = FocusUi.buildRoot(this, "日历")
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
        if (!FocusModules.enabled(ctx, "calendar")) {
            content.addView(FocusUi.disabledHint(this, "calendar"))
            return
        }

        val byDay = HashMap<String, Int>()
        for (s in FocusStore.sessions()) {
            val day = FocusDates.dayOf(s.start)
            if (day.isNotEmpty()) byDay[day] = (byDay[day] ?: 0) + s.minutes
        }

        // 月导航
        val monthFmt = SimpleDateFormat("yyyy年M月", Locale.getDefault())
        val nav = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(navBtn(ctx, "‹") {
            month.add(Calendar.MONTH, -1)
            rebuild()
        })
        nav.addView(FocusUi.label(ctx, monthFmt.format(month.time), 16f, R.color.aw_text_primary, bold = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(navBtn(ctx, "›") {
            month.add(Calendar.MONTH, 1)
            rebuild()
        })
        content.addView(nav, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, FocusUi.dp(ctx, 8))
        })

        // 星期表头（周一起始）
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in listOf("一", "二", "三", "四", "五", "六", "日")) {
            header.addView(
                FocusUi.label(ctx, name, 12f, R.color.aw_text_disabled).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, FocusUi.dp(ctx, 4))
        })

        // 月网格
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cal = month.clone() as Calendar
        // Calendar.DAY_OF_WEEK: SUN=1..SAT=7 → 周一偏移
        val leading = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayStr = FocusDates.DAY.format(Date())

        var cellIndex = 0
        var row: LinearLayout? = null
        for (blank in 0 until leading) {
            if (cellIndex % 7 == 0) { row = newRow(ctx); grid.addView(row) }
            row!!.addView(View(ctx), LinearLayout.LayoutParams(0, FocusUi.dp(ctx, 44), 1f))
            cellIndex++
        }
        for (d in 1..daysInMonth) {
            if (cellIndex % 7 == 0) { row = newRow(ctx); grid.addView(row) }
            val dateStr = String.format("%04d-%02d-%02d", month.get(Calendar.YEAR), month.get(Calendar.MONTH) + 1, d)
            row!!.addView(dayCell(ctx, d, byDay[dateStr] ?: 0, dateStr == todayStr, dateStr))
            cellIndex++
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, -2))

        // 选中日明细
        content.addView(FocusUi.label(ctx, "「$selectedDay」的专注", 13f, R.color.aw_text_secondary, bold = true).apply {
            setPadding(0, FocusUi.dp(ctx, 16), 0, FocusUi.dp(ctx, 4))
        })
        val list = FocusStore.sessions().filter { FocusDates.dayOf(it.start) == selectedDay }
        if (list.isEmpty()) {
            content.addView(FocusUi.label(ctx, "无专注记录", 13f, R.color.aw_text_disabled))
        } else {
            for (s in list) {
                content.addView(FocusUi.card(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(0, 0, 0, FocusUi.dp(ctx, 8))
                    }
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(FocusUi.label(ctx, s.title, 14f, R.color.aw_text_primary, bold = true))
                        addView(FocusUi.label(ctx, FocusDates.DISPLAY.formatOrNull(s.start), 12f, R.color.aw_text_secondary).apply {
                            setPadding(0, FocusUi.dp(ctx, 2), 0, 0)
                        })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(FocusUi.label(ctx, "${s.minutes} 分钟", 13f, R.color.aw_accent, bold = true))
                })
            }
        }
    }

    private fun newRow(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    private fun dayCell(
        ctx: Context,
        day: Int,
        minutes: Int,
        isToday: Boolean,
        dateStr: String,
    ): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(FocusUi.dp(ctx, 1), 0, FocusUi.dp(ctx, 1), 0)
            layoutParams = LinearLayout.LayoutParams(0, FocusUi.dp(ctx, 44), 1f)
            if (minutes > 0) background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.todo_due_bg)
            setOnClickListener {
                selectedDay = dateStr
                rebuild()
            }
        }
        col.addView(FocusUi.label(
            ctx, day.toString(), 13f,
            if (isToday) R.color.aw_accent else if (minutes > 0) R.color.aw_text_primary else R.color.aw_text_secondary,
            bold = isToday || minutes > 0,
        ))
        if (minutes > 0) {
            col.addView(FocusUi.label(ctx, "${minutes}m", 9f, R.color.aw_accent))
        }
        return col
    }

    private fun navBtn(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 20f
            setTextColor(FocusUi.color(ctx, R.color.aw_text_primary))
            setPadding(FocusUi.dp(ctx, 12), FocusUi.dp(ctx, 4), FocusUi.dp(ctx, 12), FocusUi.dp(ctx, 4))
            setOnClickListener { onClick() }
        }

    private fun java.text.SimpleDateFormat.formatOrNull(iso: String): String =
        FocusDates.parseIso(iso)?.let { format(it) } ?: iso
}
