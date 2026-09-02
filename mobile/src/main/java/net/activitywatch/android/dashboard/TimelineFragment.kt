package net.activitywatch.android.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentTimelineBinding

/**
 * 活动页「时间线」Tab。
 *
 * 顶部色带给出整段时间的活动分布，下面按小时（跨度大时自动降为按天）
 * 列出每个时段的 Top 应用。数据同样取自宿主的 ViewModel。
 */
class TimelineFragment : Fragment() {
    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DashboardViewModel
    private lateinit var rowAdapter: TimelineRowAdapter
    private lateinit var legendAdapter: LegendAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTimelineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireParentFragment())[DashboardViewModel::class.java]

        rowAdapter = TimelineRowAdapter()
        legendAdapter = LegendAdapter()
        binding.rvHours.adapter = rowAdapter
        binding.rvLegend.adapter = legendAdapter

        binding.swipe.setColorSchemeResources(R.color.aw_accent)
        binding.swipe.setProgressBackgroundColorSchemeResource(R.color.aw_surface)
        binding.swipe.setOnRefreshListener { viewModel.reload() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { render(it) }
        }
    }

    private fun render(s: DashboardState) {
        binding.swipe.isRefreshing = s.loading
        binding.strip.submit(s.segments, s.windowStartMs, s.windowEndMs)
        renderAxis(s)

        legendAdapter.submit(s.apps.take(LEGEND_MAX))

        // 列表放在 NestedScrollView 里且关掉了嵌套滚动，等于一次性展开所有行，
        // 行数过多会明显卡顿，所以跨度大时把粒度降到「按天」
        val byHour = s.hours.size <= MAX_HOUR_ROWS
        val rows = if (byHour) {
            s.hours.map { TimelineRow(it.hourStartMs, formatClock(it.hourStartMs), it.totalSec, it.items) }
        } else {
            s.trendDays.map { TimelineRow(it.dayStartMs, formatDayLabel(it.dayStartMs), it.totalSec, it.items) }
        }
        binding.tvRowsTitle.text = if (byHour) "按小时" else "按天（跨度较大，已按日聚合）"
        rowAdapter.submit(rows)
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 色带下方的刻度：跨度超过一天就换成日期，否则显示时刻。 */
    private fun renderAxis(s: DashboardState) {
        val start = s.windowStartMs
        val end = s.windowEndMs
        if (start == null || end == null || end <= start) {
            binding.tvAxis0.text = ""
            binding.tvAxis1.text = ""
            binding.tvAxis2.text = ""
            binding.tvAxis3.text = ""
            return
        }
        val span = end - start
        val asDay = span > 24 * 3600_000L
        val marks = listOf(binding.tvAxis0, binding.tvAxis1, binding.tvAxis2, binding.tvAxis3)
        val fractions = listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0)
        for (i in marks.indices) {
            val t = start + (span * fractions[i]).toLong()
            marks[i].text = if (asDay) formatDayLabel(t) else formatClock(t)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 图例最多展示的条目数（与色板容量一致）。 */
        private const val LEGEND_MAX = ActivityPalette.RANKED
        /** 按小时展开的行数上限，超过则降为按天。 */
        private const val MAX_HOUR_ROWS = 96
    }
}
