package net.activitywatch.android.inbox

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.activitywatch.android.MainActivity
import net.activitywatch.android.R
import net.activitywatch.android.databinding.InboxSettingsFragmentBinding
import net.activitywatch.android.ui.GradientBackground
import net.activitywatch.android.ui.GradientTheme
import net.activitywatch.android.ui.GradientThemes
import net.activitywatch.android.ui.ThemePrefs

/**
 * 笔记设置页（原 Inbox 设置）：界面主题（渐变背景）+ 为单击/双击/长按分别选择执行的动作
 * （编辑/评论/置顶/删除等），并提供回收站（笔记回收站 / 冲突归档）入口。
 */
class InboxSettingsFragment : Fragment() {

    private var _binding: InboxSettingsFragmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = InboxSettingsFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupThemePicker()

        setupRow(binding.rowDouble, binding.valueDouble, InboxPrefs.Gesture.DOUBLE, "双击行为")
        setupRow(binding.rowLong, binding.valueLong, InboxPrefs.Gesture.LONG, "长按行为")
        setupRow(binding.rowSingle, binding.valueSingle, InboxPrefs.Gesture.SINGLE, "单击行为")

        setupDrawerEdgeSlider()
        setupAutoInputSwitch()

        // 回收站（笔记回收站 + 同步冲突归档）
        binding.rowTrash.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, TrashFragment())
                .addToBackStack(null)
                .commit()
        }

        // 应用图标设置
        binding.rowIconSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, IconSettingsFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    // ── 界面主题（笔记 / 任务模块共用的渐变背景）────────────────────────────

    /** 铺当前主题的渐变背景，并生成一行色卡 */
    private fun setupThemePicker() {
        applyTheme()
        renderThemeSwatches()
    }

    private fun applyTheme() {
        GradientBackground.applyPage(requireContext(), binding.root, binding.toolbar)
    }

    /** 每种主题一张渐变色卡：点一下即保存、立即换背景，选中项加白描边 */
    private fun renderThemeSwatches() {
        val ctx = requireContext()
        val currentId = ThemePrefs.themeId(ctx)
        binding.themeValue.text = GradientThemes.byId(currentId).name
        binding.themeRow.removeAllViews()

        GradientThemes.ALL.forEach { theme ->
            val selected = theme.id == currentId

            val swatch = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(40))
                background = GradientThemes.swatchDrawable(
                    theme,
                    selected,
                    dp(10).toFloat(),
                    dp(2).toFloat(),
                )
            }

            val label = TextView(ctx).apply {
                text = theme.name
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (selected) R.color.inbox_text else R.color.inbox_sub,
                    )
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(5) }
            }

            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            item.addView(swatch)
            item.addView(label)
            item.setOnClickListener { selectTheme(theme) }

            binding.themeRow.addView(item)
        }
    }

    private fun selectTheme(theme: GradientTheme) {
        val ctx = requireContext()
        if (ThemePrefs.themeId(ctx) == theme.id) return
        ThemePrefs.setThemeId(ctx, theme.id)
        applyTheme()
        renderThemeSwatches()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── 其余设置项 ────────────────────────────────────────────────────────

    /** 进入 Inbox 时是否直接弹出记录输入框 */
    private fun setupAutoInputSwitch() {
        binding.autoInputSwitch.isChecked = InboxPrefs.autoInputOnStart(requireContext())
        binding.autoInputSwitch.setOnCheckedChangeListener { _, checked ->
            InboxPrefs.setAutoInputOnStart(requireContext(), checked)
        }
    }

    /** 侧滑打开抽屉的热区范围：5 个固定档位，0=关闭 */
    private fun setupDrawerEdgeSlider() {
        val labels = arrayOf("关闭", "1/3 屏宽", "1/2 屏宽", "3/4 屏宽", "全屏")
        binding.drawerEdgeSlider.value = InboxPrefs.drawerEdgeIndex(requireContext()).toFloat()
        binding.drawerEdgeValue.text = labels[InboxPrefs.drawerEdgeIndex(requireContext())]
        binding.drawerEdgeSlider.setLabelFormatter { value -> labels[value.toInt()] }
        binding.drawerEdgeSlider.addOnChangeListener { _, value, _ ->
            val index = value.toInt()
            InboxPrefs.setDrawerEdgeIndex(requireContext(), index)
            binding.drawerEdgeValue.text = labels[index]
            (requireActivity() as? MainActivity)?.applyDrawerEdgeZone()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRow(row: View, value: TextView, gesture: InboxPrefs.Gesture, title: String) {
        val render = {
            value.text = actionLabel(InboxPrefs.actionFor(requireContext(), gesture))
        }
        render()
        row.setOnClickListener {
            val actions = InboxPrefs.GestureAction.values()
            val labels = actions.map { actionLabel(it) }.toTypedArray()
            val current = InboxPrefs.actionFor(requireContext(), gesture)
            val themedCtx = ContextThemeWrapper(requireContext(), R.style.InboxPopupMenu)
            MaterialAlertDialogBuilder(themedCtx)
                .setTitle("$title：选择要执行的动作")
                .setSingleChoiceItems(labels, actions.indexOf(current)) { dialog, which ->
                    InboxPrefs.setActionFor(requireContext(), gesture, actions[which])
                    render()
                    Toast.makeText(
                        requireContext(),
                        "${title}已设为「${actionLabel(actions[which])}」",
                        Toast.LENGTH_SHORT,
                    ).show()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun actionLabel(action: InboxPrefs.GestureAction): String = when (action) {
        InboxPrefs.GestureAction.EDIT -> "编辑"
        InboxPrefs.GestureAction.COMMENT -> "评论"
        InboxPrefs.GestureAction.PIN -> "置顶/取消置顶"
        InboxPrefs.GestureAction.DELETE -> "删除"
        InboxPrefs.GestureAction.MENU -> "更多菜单"
        InboxPrefs.GestureAction.NONE -> "无操作"
    }
}
