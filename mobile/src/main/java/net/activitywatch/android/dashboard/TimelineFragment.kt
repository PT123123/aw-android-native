package net.activitywatch.android.dashboard

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentTimelineBinding

/**
 * 活动页「时间线」Tab。
 *
 * - 顶部色带支持「分开 / 合并」两种展示：分开时每个数据桶各占一条泳道（应用 / 网页 / 离开 / 秒表），
 *   合并时把所有桶按优先级压进同一条色带；多泳道共享同一 [TimelineViewport]，缩放 / 平移自动对齐。
 * - 色带支持双指捏合缩放、单指平移、双击复位，并提供 − / + / ⟲ 按钮；坐标轴随可视窗口实时刷新。
 * - 单击任意色块弹出详情（桶名、应用、起止、时长）。
 * 数据同样取自宿主的 ViewModel。
 */
class TimelineFragment : Fragment() {
    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DashboardViewModel
    private lateinit var rowAdapter: TimelineRowAdapter
    private lateinit var legendAdapter: LegendAdapter

    /** 当前所有泳道色带视图（用于视口变更时统一重绘，由 buildLanes 重建）。 */
    private val laneViews = ArrayList<TimelineStripView>()
    private var viewport: TimelineViewport? = null
    private var curWinStart: Long = 0L
    private var curWinEnd: Long = 0L
    private var mergedMode = false
    private var lastState: DashboardState? = null

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

        binding.tgMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val wantMerged = checkedId == R.id.btn_merged
            if (wantMerged != mergedMode) {
                mergedMode = wantMerged
                lastState?.let { buildLanes(it) }
            }
        }

        binding.btnZoomIn.setOnClickListener { viewport?.zoom(1.4f, 0.5f); updateAxis() }
        binding.btnZoomOut.setOnClickListener { viewport?.zoom(1f / 1.4f, 0.5f); updateAxis() }
        binding.btnZoomReset.setOnClickListener { viewport?.reset(); updateAxis() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { render(it) }
        }
    }

    private fun render(s: DashboardState) {
        lastState = s
        binding.swipe.isRefreshing = s.loading

        val ws = s.windowStartMs
        val we = s.windowEndMs
        if (ws != null && we != null && we > ws) {
            if (viewport == null || curWinStart != ws || curWinEnd != we) {
                viewport = TimelineViewport(ws, we)
                curWinStart = ws
                curWinEnd = we
            }
        }

        buildLanes(s)
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

    /** 根据当前模式（分开 / 合并）重建泳道；所有泳道绑定同一视口以实现联动缩放。 */
    private fun buildLanes(s: DashboardState) {
        binding.llLanes.removeAllViews()
        laneViews.clear()

        val lanes = if (mergedMode) {
            listOf(LaneRow("", s.segments))
        } else {
            s.bucketTimelines.map { LaneRow(it.displayName, it.segments) }
        }

        val inflater = layoutInflater
        for (lane in lanes) {
            val row = inflater.inflate(R.layout.item_timeline_lane, binding.llLanes, false)
            val tvLabel = row.findViewById<TextView>(R.id.tv_lane_label)
            val strip = row.findViewById<TimelineStripView>(R.id.lane_strip)
            tvLabel.text = lane.label
            strip.submit(lane.segments, s.windowStartMs, s.windowEndMs)
            viewport?.let { strip.setViewport(it) }
            strip.setOnSegmentTap { seg -> showDetail(lane.label.ifEmpty { "合并" }, seg) }
            binding.llLanes.addView(row)
            laneViews.add(strip)
        }
        updateAxis()
    }

    /** 坐标轴 + 缩放倍率随当前视口刷新；无视口（无数据）时清空。 */
    private fun updateAxis() {
        val vp = viewport
        val marks = listOf(binding.tvAxis0, binding.tvAxis1, binding.tvAxis2, binding.tvAxis3)
        if (vp == null) {
            marks.forEach { it.text = "" }
            binding.tvZoomLevel.text = "×1.0"
            return
        }
        val start = vp.visibleStartMs()
        val end = vp.visibleEndMs()
        val span = end - start
        val asDay = span > 24 * 3600_000L
        val fractions = listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0)
        for (i in marks.indices) {
            val t = start + (span * fractions[i]).toLong()
            marks[i].text = if (asDay) formatDayLabel(t) else formatClock(t)
        }
        binding.tvZoomLevel.text = "×%.1f".format(vp.scale)
    }

    /** 点击色块弹出详情：桶名 / 应用 / 起止时间 / 时长。 */
    private fun showDetail(laneName: String, seg: TimelineSegment) {
        val ctx = requireContext()
        val b = BottomSheetDialog(ctx)
        val v = layoutInflater.inflate(R.layout.bottomsheet_segment_detail, null)
        v.findViewById<TextView>(R.id.tv_lane).text = laneName
        v.findViewById<TextView>(R.id.tv_label).text = seg.label
        v.findViewById<View>(R.id.dot)
            .backgroundTintList = ColorStateList.valueOf(ActivityPalette.color(ctx, seg.colorIndex))

        val start = seg.startMs
        val end = seg.endMs
        val sameDay = formatFullDate(start) == formatFullDate(end)
        v.findViewById<TextView>(R.id.tv_time).text = if (sameDay) {
            "${formatFullDate(start)}  ${formatClock(start)} – ${formatClock(end)}"
        } else {
            "${formatFullDate(start)} ${formatClock(start)} – ${formatFullDate(end)} ${formatClock(end)}"
        }
        v.findViewById<TextView>(R.id.tv_duration).text = "时长 ${formatDuration((end - start) / 1000.0)}"
        b.setContentView(v)
        b.show()
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

/** 构建泳道时用的一行数据：桶名 + 该桶的着色段。 */
private data class LaneRow(val label: String, val segments: List<TimelineSegment>)
