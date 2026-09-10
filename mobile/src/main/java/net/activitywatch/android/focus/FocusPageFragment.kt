package net.activitywatch.android.focus

import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import net.activitywatch.android.R
import net.activitywatch.android.hub.EmbeddedToolbar
import net.activitywatch.android.hub.TabHub

/**
 * 专注模块页面基类：统一处理标题栏「模块开关」菜单。
 *
 * - 独立打开：菜单挂在自己 [FocusUi.buildRoot] 出来的标题栏上，点完刷新本页；
 * - 被 [FocusHubFragment] 内嵌（页内 Tab）：自带标题栏隐藏，菜单由宿主按当前可见页派发。
 *
 * 子类只需实现 [refreshPage]，并在 onCreateView 里调用 [bindToolbar]。
 */
abstract class FocusPageFragment : Fragment(), TabHub.MenuTarget {

    /** 模块开关变更或数据变化后刷新本页内容 */
    protected abstract fun refreshPage()

    /** onCreateView 中调用：绑定标题栏（内嵌时隐藏自带标题栏并把菜单交给宿主） */
    protected fun bindToolbar(toolbar: MaterialToolbar) {
        EmbeddedToolbar.bind(this, toolbar) { itemId -> onHubMenu(itemId) }
    }

    final override fun onHubMenu(itemId: Int): Boolean {
        if (itemId != R.id.action_focus_modules) return false
        FocusUi.showModulesDialog(this) { refreshPage() }
        return true
    }
}
