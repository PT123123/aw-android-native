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
import net.activitywatch.android.databinding.FragmentOverviewBinding

/**
 * 活动页「概览」Tab。
 *
 * 数据来自宿主的 DashboardViewModel（不自己持有），切换时间范围时
 * 由宿主统一拉取，这里只负责渲染。
 */
class OverviewFragment : Fragment() {
    private var _binding: FragmentOverviewBinding? = null
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
        _binding = FragmentOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 与宿主、时间线、趋势共用同一份数据
        viewModel = ViewModelProvider(requireParentFragment())[DashboardViewModel::class.java]

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

        // 深色背景下，SwipeRefreshLayout 默认的黑灰转圈几乎看不见，显式给主题色
        binding.swipe.setColorSchemeResources(R.color.aw_accent)
        binding.swipe.setProgressBackgroundColorSchemeResource(R.color.aw_surface)
        binding.swipe.setOnRefreshListener { viewModel.reload() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { render(it) }
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
        appAdapter.submit(
            s.apps.ifEmpty { listOf(RankItem("本时间段暂无应用记录", 0.0, 0f)) }
        )
        webAdapter.submit(
            s.websites.ifEmpty { listOf(RankItem("本时间段暂无网站记录", 0.0, 0f)) }
        )
        bucketAdapter.submit(
            s.buckets.ifEmpty { listOf(BucketRow("（暂无数据桶）", null, null, false)) }
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
