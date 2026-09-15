package net.activitywatch.android.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentFragmentsBinding
import net.activitywatch.android.databinding.ItemRankRowBinding

/**
 * 活动页「碎片」Tab：一次只看一天，把当天使用记录摊成「24 时 × 每时 5 分钟」的圆点热力，
 * 并推断起床 / 入睡作息、统计各 App 启动次数。顶部 ←/→ 翻看历史（最多回看 30 天）。
 * 数据全部来自系统 UsageStatsManager。
 */
class FragmentsFragment : Fragment() {
    private var _binding: FragmentFragmentsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: FragmentsViewModel
    private lateinit var launchAdapter: LaunchCountAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFragmentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[FragmentsViewModel::class.java]

        launchAdapter = LaunchCountAdapter()
        binding.rvLaunches.adapter = launchAdapter

        binding.btnPrevDay.setOnClickListener { viewModel.prevDay() }
        binding.btnNextDay.setOnClickListener { viewModel.nextDay() }

        binding.swipe.setColorSchemeResources(R.color.aw_accent)
        binding.swipe.setProgressBackgroundColorSchemeResource(R.color.aw_surface)
        binding.swipe.setOnRefreshListener { viewModel.reload() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { render(it) }
        }
    }

    private fun render(s: FragmentsState) {
        binding.swipe.isRefreshing = s.loading

        binding.tvDay.text = s.dayLabel
        binding.btnPrevDay.isEnabled = s.canPrev
        binding.btnNextDay.isEnabled = s.canNext
        val navEnabled = ContextCompat.getColor(requireContext(), R.color.aw_text_primary)
        val navDisabled = ContextCompat.getColor(requireContext(), R.color.aw_text_disabled)
        binding.btnPrevDay.setTextColor(if (s.canPrev) navEnabled else navDisabled)
        binding.btnNextDay.setTextColor(if (s.canNext) navEnabled else navDisabled)

        binding.tvWake.text = s.wake ?: "—"
        binding.tvSleep.text = s.sleep ?: "—"
        if (!s.permitted) {
            binding.tvWake.text = "—"
            binding.tvSleep.text = "—"
        }

        binding.chart.submit(s.slots)
        binding.tvChartEmpty.visibility = if (s.slots.isEmpty()) View.VISIBLE else View.GONE
        binding.tvChartEmpty.text = if (!s.permitted) "未授予「使用情况访问」权限，无法统计使用碎片" else "当天暂无使用记录"

        launchAdapter.submit(s.launches)
        binding.tvLaunchEmpty.visibility = if (s.launches.isEmpty()) View.VISIBLE else View.GONE
        binding.tvLaunchEmpty.text = if (!s.permitted) "未授予「使用情况访问」权限" else "当天暂无启动记录"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** 启动次数排行行：复用时长排行的行布局，值列显示「N 次」。 */
class LaunchCountAdapter : RecyclerView.Adapter<LaunchCountAdapter.VH>() {
    private var items: List<LaunchCount> = emptyList()

    fun submit(list: List<LaunchCount>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemRankRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRankRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.b.tvRankLabel.text = "${position + 1}. ${it.label}"
        holder.b.tvRankValue.text = "${it.count} 次"
        holder.b.pbRank.progress = (it.ratio * 100).toInt().coerceIn(0, 100)
    }

    override fun getItemCount(): Int = items.size
}
