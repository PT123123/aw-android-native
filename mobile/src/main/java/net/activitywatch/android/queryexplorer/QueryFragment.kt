package net.activitywatch.android.queryexplorer

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.dashboard.TimeRange
import net.activitywatch.android.databinding.FragmentQueryBinding

/**
 * Query Explorer 页面：手写 / 套用预置脚本，对本机 aw-server 的 /api/0/query 发起请求，
 * 把返回的 JSON 原样 pretty-print 出来。
 */
class QueryFragment : Fragment() {
    private var _binding: FragmentQueryBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: QueryViewModel
    private var range: TimeRange = TimeRange.TODAY

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentQueryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[QueryViewModel::class.java]

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }

        setupRangeChips()
        setupPresets()
        setupEditor()

        binding.btnRun.setOnClickListener {
            viewModel.run(binding.etScript.text?.toString().orEmpty(), range)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { render(it) }
        }
    }

    private fun setupRangeChips() {
        binding.chipToday.isChecked = true
        val map = mapOf(
            binding.chipToday to TimeRange.TODAY,
            binding.chipYesterday to TimeRange.YESTERDAY,
            binding.chipLast7 to TimeRange.LAST7,
            binding.chipLast30 to TimeRange.LAST30,
            binding.chipAll to TimeRange.ALL,
        )
        binding.chipGroup.setOnCheckedChangeListener { _, checkedId ->
            range = map.entries.firstOrNull { it.key.id == checkedId }?.value ?: range
        }
    }

    /** 预置脚本做成 Chip，点击直接铺进编辑框（不自动执行，方便先改再跑）。 */
    private fun setupPresets() {
        QUERY_PRESETS.forEach { binding.chipPresets.addView(buildPresetChip(it)) }
    }

    /**
     * 手工设置 Chip 的配色而不是套 style：ChipDrawable.createFromAttributes 读的是
     * ChipDrawable 那套属性，android:textColor 这类 Chip 自己的属性不会生效，
     * 深色下文字会沿用主题默认色。这里逐个属性显式给定。
     */
    private fun buildPresetChip(preset: QueryPreset): Chip {
        val ctx = requireContext()
        // 用无参构造：Chip 内部已套用 Material 的 chipStyle，无需（也无法）引用本模块 R 里不存在的 attr
        return Chip(ctx).apply {
            text = preset.title
            // 只是「填充脚本」的按钮：Choice 样式默认 checkable，不关掉会多个同时高亮
            isCheckable = false
            setEnsureMinTouchTargetSize(false)
            setChipBackgroundColorResource(R.color.aw_chip_bg)
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_secondary))
            chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.aw_border))
            chipStrokeWidth = 1f * resources.displayMetrics.density
            setOnClickListener {
                binding.etScript.setText(preset.script)
                binding.etScript.setSelection(preset.script.length)
            }
        }
    }

    /** 多行编辑框在 NestedScrollView 里会被父容器抢走上下滑动，这里显式要回来。 */
    private fun setupEditor() {
        binding.etScript.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        if (binding.etScript.text.isNullOrBlank()) {
            binding.etScript.setText(QUERY_PRESETS.first().script)
        }
    }

    private fun render(s: QueryState) {
        binding.btnRun.isEnabled = !s.loading
        binding.btnRun.text = if (s.loading) "执行中…" else "执行查询"

        val hasResult = s.result != null
        binding.tvResult.visibility = if (hasResult) View.VISIBLE else View.GONE
        if (hasResult) binding.tvResult.text = s.result

        if (s.error != null) {
            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = s.error
        } else {
            binding.tvError.visibility = View.GONE
        }

        binding.tvHint.visibility = if (hasResult || s.error != null) View.GONE else View.VISIBLE

        binding.tvMeta.text = when {
            s.loading -> ""
            hasResult -> {
                val rows = s.rowCount?.let { "$it 行 · " } ?: ""
                "$rows${s.elapsedMs} ms"
            }
            s.error != null -> "${s.elapsedMs} ms"
            else -> ""
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
