package net.activitywatch.android.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.activitywatch.android.hub.TabHub
import net.activitywatch.android.queryexplorer.QueryFragment
import net.activitywatch.android.stopwatch.StopwatchFragment

/**
 * 活动宿主：抽屉「活动」一项 → 页内 Tab 切换 活动 / 秒表 / Query Explorer。
 *
 * 「活动」页自身还有 概览/时间线/趋势 三个 Tab，「Query Explorer」有脚本档位 chips，
 * 所以这里是第二个「tab 内 tab」的宿主。子页标题栏由宿主统一提供（内嵌时子页自隐藏）。
 */
class ActivityHubFragment : Fragment() {

    companion object {
        /** 初始选中的宿主页：0=活动 1=秒表 2=Query Explorer（桌面小部件深链用） */
        const val ARG_PAGE = "aw_hub_page"

        /** 传给「活动」子页 DashboardFragment 的初始 Tab（ActivityPagerAdapter.TAB_*） */
        const val ARG_SUB_TAB = "aw_hub_sub_tab"
    }

    private var views: TabHub.Views? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val page = arguments?.getInt(ARG_PAGE, 0) ?: 0
        val subTab = arguments?.getInt(ARG_SUB_TAB, -1) ?: -1
        val v = TabHub.build(
            this,
            "活动",
            initialPage = page,
            pages = listOf(
                TabHub.Page("活动") {
                    DashboardFragment().apply {
                        if (subTab >= 0) {
                            arguments = Bundle().apply { putInt(DashboardFragment.ARG_TAB, subTab) }
                        }
                    }
                },
                TabHub.Page("秒表") { StopwatchFragment() },
                TabHub.Page("Query Explorer") { QueryFragment() },
            )
        )
        views = v
        return v.root
    }

    override fun onDestroyView() {
        views?.release()
        views = null
        super.onDestroyView()
    }
}
