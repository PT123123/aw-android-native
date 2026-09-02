package net.activitywatch.android.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.tabs.TabLayoutMediator
import net.activitywatch.android.databinding.FragmentDashboardBinding

/**
 * 活动页宿主：时间范围选择 + 概览/时间线/趋势 三个 Tab。
 *
 * ViewModel 挂在自己身上，子 Fragment 通过 requireParentFragment() 取同一实例，
 * 所以切换时间范围只会触发一次事件拉取，三个 Tab 同时刷新。
 */
class DashboardFragment : Fragment() {
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DashboardViewModel
    private var tabMediator: TabLayoutMediator? = null

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

        setupChips()
        setupTabs()
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

    private fun setupTabs() {
        binding.vpActivity.adapter = ActivityPagerAdapter(this)
        tabMediator = TabLayoutMediator(binding.tabLayout, binding.vpActivity) { tab, position ->
            tab.text = ActivityPagerAdapter.TITLES[position]
        }.apply { attach() }
    }

    override fun onDestroyView() {
        // TabLayoutMediator 会往 ViewPager2 注册 OnPageChangeCallback 并持有 TabLayout，
        // 不解绑的话每次进出活动页都会泄漏一个 TabLayout
        tabMediator?.detach()
        tabMediator = null
        // ViewPager2 的 adapter 持有 childFragmentManager 的 Fragment，
        // 销毁视图时必须解绑，否则重建后 Fragment 复用旧 view 会抛 NPE
        binding.vpActivity.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
