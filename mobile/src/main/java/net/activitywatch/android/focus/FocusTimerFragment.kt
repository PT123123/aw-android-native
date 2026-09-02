package net.activitywatch.android.focus

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import net.activitywatch.android.R
import net.activitywatch.android.todo.TodoRepository
import java.util.Date

/**
 * 计时页（契约 §5.8）。
 *
 * 从 TodoSource::tasks() 读任务列表作为可选项（唯一耦合点），
 * 选中任务时用其标题填充事件名；自由输入亦可。停止时写入 FocusStore。
 */
class FocusTimerFragment : Fragment() {

    private var body: LinearLayout? = null
    private var taskValue: TextView? = null
    private var nameInput: android.widget.EditText? = null
    private var elapsedView: TextView? = null
    private var startBtn: Button? = null
    private var stopBtn: Button? = null
    private var discardBtn: Button? = null
    private var statusView: TextView? = null

    private var running = false
    private var startAt = 0L
    private var selectedTaskId = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateElapsed()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        FocusStore.init(requireContext())
        val (toolbar, content) = FocusUi.buildRoot(this, "计时")
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_focus_modules) {
                FocusUi.showModulesDialog(this) { buildBody() }
                true
            } else false
        }
        body = content
        buildBody()
        return content.parent as View
    }

    private fun buildBody() {
        val ctx = requireContext()
        val content = body ?: return
        content.removeAllViews()
        if (!FocusModules.enabled(ctx, "timer")) {
            content.addView(FocusUi.disabledHint(this, "timer"))
            return
        }
        content.gravity = Gravity.CENTER_HORIZONTAL

        val pad = FocusUi.dp(ctx, 20)

        // 关联任务
        content.addView(FocusUi.label(ctx, "关联任务（点按选择）", 13f, R.color.aw_text_secondary))
        taskValue = FocusUi.label(ctx, "无", 15f, R.color.aw_accent, bold = true).apply {
            setPadding(pad, FocusUi.dp(ctx, 6), pad, 0)
            setOnClickListener { showTaskPicker() }
        }
        content.addView(taskValue, vp(-1, -2))

        content.addView(space(12))

        // 事件名
        content.addView(FocusUi.label(ctx, "事件名", 13f, R.color.aw_text_secondary))
        nameInput = FocusUi.input(ctx, "例如：写周报 / 阅读…").apply {
            setPadding(pad, pad / 2, pad, 0)
        }
        content.addView(nameInput, vp(-1, -2))

        content.addView(space(28))

        // 大计时器
        elapsedView = TextView(ctx).apply {
            text = "00:00"
            textSize = 56f
            typeface = Typeface.MONOSPACE
            setTextColor(FocusUi.color(ctx, R.color.aw_text_primary))
            gravity = Gravity.CENTER
        }
        content.addView(elapsedView, vp(-1, -2))

        statusView = FocusUi.label(ctx, "点击「开始」后计时，停止时记录会话", 13f, R.color.aw_text_disabled).apply {
            gravity = Gravity.CENTER
            setPadding(0, FocusUi.dp(ctx, 8), 0, 0)
        }
        content.addView(statusView, vp(-1, -2))

        content.addView(space(28))

        // 按钮行
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        startBtn = Button(ctx).apply {
            text = "开始"
            setTextColor(FocusUi.color(ctx, R.color.aw_bg))
            backgroundTintList = android.content.res.ColorStateList.valueOf(FocusUi.color(ctx, R.color.aw_accent))
            setOnClickListener { startTimer() }
        }
        row.addView(startBtn, LinearLayout.LayoutParams(FocusUi.dp(ctx, 110), -2))

        stopBtn = Button(ctx).apply {
            text = "停止并记录"
            isEnabled = false
            setTextColor(FocusUi.color(ctx, R.color.aw_text_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(FocusUi.color(ctx, R.color.aw_surface_2))
            setOnClickListener { stopAndSave() }
        }
        row.addView(stopBtn, LinearLayout.LayoutParams(FocusUi.dp(ctx, 150), -2).apply {
            setMargins(FocusUi.dp(ctx, 12), 0, 0, 0)
        })

        discardBtn = Button(ctx).apply {
            text = "放弃"
            isEnabled = false
            setTextColor(FocusUi.color(ctx, R.color.aw_danger))
            backgroundTintList = android.content.res.ColorStateList.valueOf(FocusUi.color(ctx, R.color.aw_surface_2))
            setOnClickListener { reset() }
        }
        row.addView(discardBtn, LinearLayout.LayoutParams(FocusUi.dp(ctx, 90), -2).apply {
            setMargins(FocusUi.dp(ctx, 12), 0, 0, 0)
        })
        content.addView(row, vp(-1, -2))
    }

    private fun showTaskPicker() {
        val ctx = requireContext()
        val tasks = TodoRepository.source(ctx).tasks().sortedBy { it.title }
        val names = mutableListOf("无（自由输入）")
        val ids = mutableListOf(0L)
        for (t in tasks) { names += t.title; ids += t.id }
        AlertDialog.Builder(ctx)
            .setTitle("选择关联任务")
            .setItems(names.toTypedArray()) { _, which ->
                selectedTaskId = ids[which]
                taskValue?.text = if (selectedTaskId == 0L) "无" else names[which]
                if (selectedTaskId != 0L && nameInput?.text.isNullOrBlank()) {
                    nameInput?.setText(names[which])
                }
            }
            .show()
    }

    private fun startTimer() {
        running = true
        startAt = System.currentTimeMillis()
        startBtn?.isEnabled = false
        stopBtn?.isEnabled = true
        discardBtn?.isEnabled = true
        statusView?.text = "计时中…"
        handler.post(tick)
    }

    private fun stopAndSave() {
        val minutes = ((System.currentTimeMillis() - startAt) / 60000L).toInt()
        if (minutes < 1) {
            Toast.makeText(requireContext(), "不足 1 分钟，未记录", Toast.LENGTH_SHORT).show()
            reset()
            return
        }
        val typed = nameInput?.text?.toString()?.trim().orEmpty()
        FocusStore.addSession(typed.ifEmpty { "专注" }, selectedTaskId, FocusDates.ISO.format(Date(startAt)), minutes)
        Toast.makeText(requireContext(), "已记录 $minutes 分钟", Toast.LENGTH_SHORT).show()
        reset()
    }

    private fun reset() {
        running = false
        handler.removeCallbacks(tick)
        elapsedView?.text = "00:00"
        startBtn?.isEnabled = true
        stopBtn?.isEnabled = false
        discardBtn?.isEnabled = false
        statusView?.text = "点击「开始」后计时，停止时记录会话"
    }

    private fun updateElapsed() {
        val sec = (System.currentTimeMillis() - startAt) / 1000L
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        elapsedView?.text =
            if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%02d:%02d", m, s)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(tick)
        body = null
        super.onDestroyView()
    }

    private fun vp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun space(hDp: Int): View = View(requireContext()).also {
        it.layoutParams = LinearLayout.LayoutParams(-1, FocusUi.dp(requireContext(), hDp))
    }
}
