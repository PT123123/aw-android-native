package net.activitywatch.android.focus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.activitywatch.android.R
import net.activitywatch.android.hub.TabHub

/**
 * 专注宿主：抽屉「专注」一项 → 页内 Tab 切换 计时 / 专注记录 / 倒数纪念日 / 专注分析 / 日历。
 *
 * 「专注分析」内部还有 时间线/热力图/最佳时间 三个 chips，构成 tab 内 tab。
 * 标题栏「模块开关」由子页提供处理逻辑（[FocusPageFragment.onHubMenu]），
 * 这里只负责派发给当前可见页。
 */
class FocusHubFragment : Fragment() {

    private var views: TabHub.Views? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        FocusStore.init(requireContext())
        val v = TabHub.build(
            this,
            "专注",
            R.menu.menu_focus_modules,
            listOf(
                TabHub.Page("计时") { FocusTimerFragment() },
                TabHub.Page("专注记录") { FocusRecordsFragment() },
                TabHub.Page("倒数纪念日") { FocusCountdownFragment() },
                TabHub.Page("专注分析") { FocusAnalyticsFragment() },
                TabHub.Page("日历") { FocusCalendarFragment() },
            )
        )
        // 菜单项挂在宿主标题栏上，实际处理交给当前可见子页
        v.toolbar.setOnMenuItemClickListener { item ->
            TabHub.currentMenuTarget(this)?.onHubMenu(item.itemId) ?: false
        }
        views = v
        return v.root
    }

    override fun onDestroyView() {
        views?.release()
        views = null
        super.onDestroyView()
    }
}
