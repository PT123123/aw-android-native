package net.activitywatch.android.hub

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.MenuRes
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import net.activitywatch.android.R

/**
 * 抽屉分组合并后的「Tab 宿主」脚手架（程序化构建，不引新布局资源）。
 *
 * 结构：一个抽屉项 → 一个宿主页（toolbar + TabLayout + ViewPager2）→ 页内 Tab 分隔原子页。
 * 原子页若自带 Tab（活动 = 概览/时间线/趋势、云备份 = WebDAV/S3），就自然形成「tab 内 tab」。
 *
 * 子页由宿主以「内嵌」方式创建（[ARG_EMBEDDED]），据此隐藏自带 toolbar——
 * 标题栏与抽屉键由宿主统一提供，避免出现两层标题栏。
 * 子页原本挂在标题栏上的菜单（专注的「模块开关」、局域网的「立即同步」）
 * 通过 [EmbeddedToolbar] 注册到 [MenuHost]，由宿主转发给当前可见子页。
 */
object TabHub {

    /** 宿主创建内嵌子页时写入的参数：子页据此隐藏自带 toolbar */
    const val ARG_EMBEDDED = "aw_embedded"

    /** 当前页面是否由 Tab 宿主内嵌打开 */
    fun isEmbedded(fragment: Fragment): Boolean =
        fragment.arguments?.getBoolean(ARG_EMBEDDED) == true

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, id: Int): Int = ContextCompat.getColor(context, id)

    /** 宿主代当前子页托管标题栏菜单：子页实现该接口即可被派发 */
    interface MenuTarget {
        fun onHubMenu(itemId: Int): Boolean
    }

    /**
     * 取「当前可见」子页里的菜单处理者。
     * ViewPager2 只把主项那一页置为 RESUMED（预载的邻页停在 STARTED），
     * 所以按 isResumed 筛选能唯一命中正在显示的那页——不能用「后注册覆盖」，
     * 否则预载的第二页会把菜单劫走。
     */
    fun currentMenuTarget(host: Fragment): MenuTarget? =
        host.childFragmentManager.fragments.firstOrNull { it.isResumed } as? MenuTarget

    /** 一个 Tab 对应的子页 */
    class Page(val title: String, val create: () -> Fragment)

    class Views internal constructor(
        val root: View,
        val toolbar: MaterialToolbar,
        val tabLayout: TabLayout,
        val pager: ViewPager2,
        private val mediator: TabLayoutMediator,
    ) {
        /**
         * 必须在 onDestroyView 调用：TabLayoutMediator 会向 pager 注册回调并持有 TabLayout，
         * 不解绑的话每次进出都会泄漏一个 TabLayout；adapter 同理要解绑，否则重建后
         * 子 Fragment 复用旧 view 会抛 NPE。
         */
        fun release() {
            mediator.detach()
            pager.adapter = null
        }
    }

    fun build(
        fragment: Fragment,
        title: String,
        @MenuRes menuRes: Int = 0,
        pages: List<Page>,
    ): Views {
        val ctx = fragment.requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(ctx, R.color.aw_bg))
        }

        val toolbar = MaterialToolbar(ctx).apply {
            setTitle(title)
            setTitleTextColor(color(ctx, R.color.aw_text_primary))
            setBackgroundColor(color(ctx, R.color.aw_bg))
            navigationIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_menu)
            setNavigationIconTint(color(ctx, R.color.aw_text_primary))
            if (menuRes != 0) inflateMenu(menuRes)
        }
        toolbar.setNavigationOnClickListener {
            fragment.requireActivity()
                .findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56)))

        val tabLayout = TabLayout(ctx).apply {
            // 专注有 5 个 Tab，用可滚动模式避免挤成一团
            tabMode = TabLayout.MODE_SCROLLABLE
            tabGravity = TabLayout.GRAVITY_START
            setSelectedTabIndicatorColor(color(ctx, R.color.aw_accent))
            setTabTextColors(color(ctx, R.color.aw_text_secondary), color(ctx, R.color.aw_accent))
            setBackgroundColor(color(ctx, R.color.aw_bg))
        }
        root.addView(tabLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(
            View(ctx).apply { setBackgroundColor(color(ctx, R.color.aw_border)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        )

        val pager = ViewPager2(ctx).apply {
            // 只预载相邻一页：活动页会发网络请求，全部预载没必要
            offscreenPageLimit = 1
            setBackgroundColor(color(ctx, R.color.aw_bg))
            adapter = HubAdapter(fragment, pages)
        }
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val mediator = TabLayoutMediator(tabLayout, pager) { tab, position ->
            tab.text = pages[position].title
        }.apply { attach() }

        return Views(root, toolbar, tabLayout, pager, mediator)
    }

    private class HubAdapter(
        fragment: Fragment,
        private val pages: List<Page>,
    ) : FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int = pages.size

        override fun createFragment(position: Int): Fragment = pages[position].create().apply {
            // 合并原有 args（如专注分析的 mode），只补上「内嵌」标记
            arguments = (arguments ?: Bundle()).apply { putBoolean(ARG_EMBEDDED, true) }
        }
    }
}

/**
 * 子页标题栏适配：独立打开时用自己的 toolbar，被 Tab 宿主持有时隐藏 toolbar——
 * 菜单改由宿主派发给当前可见子页（[TabHub.MenuTarget]），避免两层标题栏。
 */
object EmbeddedToolbar {

    /**
     * 子页有标题栏菜单。
     * 回调用 itemId 而非 MenuItem，避免调用方依赖 Toolbar 的监听器类型。
     */
    fun bind(fragment: Fragment, toolbar: MaterialToolbar, onMenu: (Int) -> Boolean) {
        if (TabHub.isEmbedded(fragment)) {
            toolbar.visibility = View.GONE
        } else {
            toolbar.setOnMenuItemClickListener { item -> onMenu(item.itemId) }
        }
    }

    /** 子页没有标题栏菜单，内嵌时隐藏即可 */
    fun hideWhenEmbedded(fragment: Fragment, toolbar: MaterialToolbar) {
        if (TabHub.isEmbedded(fragment)) toolbar.visibility = View.GONE
    }
}
