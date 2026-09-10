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

    private var views: TabHub.Views? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = TabHub.build(
            this,
            "活动",
            pages = listOf(
                TabHub.Page("活动") { DashboardFragment() },
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
