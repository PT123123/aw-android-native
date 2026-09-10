package net.activitywatch.android.sync

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.activitywatch.android.R
import net.activitywatch.android.hub.TabHub
import net.activitywatch.android.sync.cloud.WebDavFragment

/**
 * 同步宿主：抽屉「同步」一项 → 页内 Tab 切换 局域网同步 / 云备份 / CF 同步。
 *
 * 「云备份（冷备）」页内部还有 WebDAV / S3 两个 Tab，构成 tab 内 tab。
 * 局域网同步的标题栏「刷新并立即同步」由子页提供处理逻辑（[SyncFragment.onHubMenu]），
 * 这里只负责派发给当前可见页。
 */
class SyncHubFragment : Fragment() {

    private var views: TabHub.Views? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = TabHub.build(
            this,
            "同步",
            R.menu.menu_sync,
            listOf(
                TabHub.Page("局域网同步") { SyncFragment() },
                TabHub.Page("云备份") { WebDavFragment() },
                TabHub.Page("CF 同步") { D1SyncFragment() },
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
