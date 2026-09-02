package net.activitywatch.android.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentDashboardBinding

/**
 * 原生实现的 ActivityWatch 仪表盘，替代被移除的 WebUIFragment（aw-webui）。
 * 通过 ActivityApi 访问本机 aw-server，渲染应用/网站时长榜与专注/闲置统计。
 */
class DashboardFragment : Fragment() {
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DashboardViewModel
    private lateinit var appAdapter: RankAdapter
    private lateinit var webAdapter: RankAdapter
    private lateinit var bucketAdapter: BucketAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ActivityApi.init()
        viewModel = ViewModelProvider(this)[DashboardViewModel::class.java]

        appAdapter = RankAdapter()
        webAdapter = RankAdapter()
        bucketAdapter = BucketAdapter()

        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.isNestedScrollingEnabled = false
        binding.rvApps.adapter = appAdapter

        binding.rvWebsites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWebsites.isNestedScrollingEnabled = false
        binding.rvWebsites.adapter = webAdapter

        binding.rvBuckets.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBuckets.isNestedScrollingEnabled = false
        binding.rvBuckets.adapter = bucketAdapter

        setupChips()
        binding.swipe.setOnRefreshListener { viewModel.reload() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { render(it) }
        }
    }

    private fun setupChips() {
        binding.chipToday.isChecked = true
        val map = mapOf(
            binding.chipToday to TimeRange.TODAY,
            binding.chipYesterday to TimeRange.YESTERDAY,
            binding.chipLast7 to TimeRange.LAST7,
            binding.chipLast30 to TimeRange.LAST30,
            binding.chipAll to TimeRange.ALL,
        )
        binding.chipGroup.setOnCheckedChangeListener { _, checkedId ->
            val range = map.entries.firstOrNull { it.key.id == checkedId }?.value ?: return@setOnCheckedChangeListener
            viewModel.load(range)
        }
    }

    private fun render(s: DashboardState) {
        binding.swipe.isRefreshing = s.loading

        if (s.error != null) {
            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = "加载失败：${s.error}"
        } else {
            binding.tvError.visibility = View.GONE
        }

        binding.tvSummary.text = buildSummary(s)

        // 卡片始终可见：空数据用占位行，避免界面整块消失（即便出错也应把结构亮出来）
        appAdapter.submit(if (s.apps.isEmpty()) listOf(RankItem("本时间段暂无应用记录", 0.0, 0f)) else s.apps)
        webAdapter.submit(if (s.websites.isEmpty()) listOf(RankItem("本时间段暂无网站记录", 0.0, 0f)) else s.websites)
        bucketAdapter.submit(
            if (s.buckets.isEmpty()) listOf(BucketRow("（暂无数据桶）", null, null, false)) else s.buckets
        )

        renderAfk(s)
    }

    private fun buildSummary(s: DashboardState): String {
        val parts = mutableListOf<String>()
        parts.add("已记录 ${formatDuration(s.totalTrackedSec)}")
        if (s.activeSec != null || s.afkSec != null) {
            parts.add("专注 ${formatDuration(s.activeSec ?: 0.0)}")
            parts.add("闲置 ${formatDuration(s.afkSec ?: 0.0)}")
        }
        return parts.joinToString("  ·  ")
    }

    private fun renderAfk(s: DashboardState) {
        binding.cardAfk.visibility = View.VISIBLE
        val active = s.activeSec ?: 0.0
        val afk = s.afkSec ?: 0.0
        if (s.activeSec == null && s.afkSec == null) {
            binding.pbAfk.progress = 0
            binding.tvAfk.text = "本设备暂无专注 / 闲置（AFK）数据"
            return
        }
        val total = active + afk
        val activePct = if (total > 0) (active / total * 100).toInt() else 0
        binding.pbAfk.progress = activePct
        binding.tvAfk.text = "专注 $activePct%（${formatDuration(active)}） · 闲置 ${formatDuration(afk)}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
