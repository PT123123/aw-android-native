package net.activitywatch.android.dashboard

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/** 活动页的三个子视图，对应被移除的 aw-webui 里的 Activity / Timeline / Trends。 */
class ActivityPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = TITLES.size

    override fun createFragment(position: Int): Fragment = when (position) {
        TAB_OVERVIEW -> OverviewFragment()
        TAB_TIMELINE -> TimelineFragment()
        TAB_TRENDS -> TrendsFragment()
        else -> OverviewFragment()
    }

    companion object {
        const val TAB_OVERVIEW = 0
        const val TAB_TIMELINE = 1
        const val TAB_TRENDS = 2

        val TITLES = arrayOf("概览", "时间线", "趋势")
    }
}
