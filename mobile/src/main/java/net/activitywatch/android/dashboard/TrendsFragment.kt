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
import net.activitywatch.android.databinding.FragmentTrendsBinding

/**
 * 活动页「趋势」Tab：按天看总时长的变化，配总记录 / 日均 / 峰值 / 活跃天数四项汇总。
 * 数据取自宿主的 ViewModel。
 */
class TrendsFragment : Fragment() {
    private var _binding: FragmentTrendsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DashboardViewModel
    private lateinit var appsAdapter: LegendAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTrendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireParentFragment())[DashboardViewModel::class.java]

        appsAdapter = LegendAdapter()
        binding.rvApps.adapter = appsAdapter

        binding.swipe.setColorSchemeResources(R.color.aw_accent)
        binding.swipe.setProgressBackgroundColorSchemeResource(R.color.aw_surface)
        binding.swipe.setOnRefreshListener { viewModel.reload() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { render(it) }
        }
    }

    private fun render(s: DashboardState) {
        binding.swipe.isRefreshing = s.loading

        binding.chart.submit(s.trendDays)
        binding.tvEmpty.visibility = if (s.trendDays.isEmpty()) View.VISIBLE else View.GONE

        renderStats(s)
        renderAfk(s)
        appsAdapter.submit(s.apps.take(TOP_APPS_MAX))
    }

    private fun renderStats(s: DashboardState) {
        val activeDays = s.trendDays.filter { it.totalSec > 0 }
        val total = activeDays.sumOf { it.totalSec }
        val avg = if (activeDays.isEmpty()) 0.0 else total / activeDays.size
        val peak = activeDays.maxByOrNull { it.totalSec }

        binding.tvStatTotal.text = formatDuration(total)
        binding.tvStatAvg.text = formatDuration(avg)
        binding.tvStatPeak.text = if (peak == null) "—" else formatDuration(peak.totalSec)
        binding.tvStatDays.text = "${activeDays.size} 天"
    }

    private fun renderAfk(s: DashboardState) {
        val active = s.activeSec
        val afk = s.afkSec
        if (active == null && afk == null) {
            binding.pbAfk.progress = 0
            binding.tvAfk.text = "本设备暂无专注 / 闲置（AFK）数据"
            return
        }
        val a = active ?: 0.0
        val f = afk ?: 0.0
        val total = a + f
        val pct = if (total > 0) (a / total * 100).toInt() else 0
        binding.pbAfk.progress = pct
        binding.tvAfk.text = "专注 $pct%（${formatDuration(a)}） · 闲置 ${formatDuration(f)}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TOP_APPS_MAX = 10
    }
}
