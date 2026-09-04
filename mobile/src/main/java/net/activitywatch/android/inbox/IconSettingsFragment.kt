package net.activitywatch.android.inbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import net.activitywatch.android.R

/**
 * 图标版本切换设置页。
 *
 * 通过 activity-alias 机制切换应用图标。
 * 版本图标由 scripts/gen_icon_versions.py 生成。
 */
class IconSettingsFragment : Fragment() {

    /** 所有可用的图标版本（与 AndroidManifest 中的 activity-alias 对应） */
    private val iconVersions = listOf(
        IconVersion("version_01", "图标 1", R.mipmap.aw_launcher_version_01),
        IconVersion("version_02", "图标 2", R.mipmap.aw_launcher_version_02),
        IconVersion("version_03", "图标 3", R.mipmap.aw_launcher_version_03),
    )

    private var selectedVersion: String = "version_01"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.aw_bg))
        }

        // Toolbar
        val toolbar = com.google.android.material.appbar.MaterialToolbar(ctx).apply {
            title = "图标设置"
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.aw_bg))
            setNavigationIcon(R.drawable.ic_menu)
            setNavigationOnClickListener {
                requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                    ?.openDrawer(GravityCompat.START)
            }
            setTitleTextColor(ContextCompat.getColor(ctx, R.color.aw_text_primary))
        }
        layout.addView(toolbar)

        // Scrollable content
        val scroll = android.widget.ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // 当前选中版本
        selectedVersion = getSelectedVersion(ctx)

        // 标题
        val title = TextView(ctx).apply {
            text = "选择应用图标（实验性）"
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_primary))
            textSize = 16f
        }
        content.addView(title)

        // 提示
        val hint = TextView(ctx).apply {
            text = "切换后需返回桌面查看效果，部分 launcher 可能需重启才能刷新"
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_secondary))
            textSize = 12f
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (12 * resources.displayMetrics.density).toInt())
        }
        content.addView(hint)

        // 图标选项
        for (version in iconVersions) {
            content.addView(createIconRow(ctx, version))
        }

        scroll.addView(content)
        layout.addView(scroll)
        return layout
    }

    private fun createIconRow(ctx: Context, version: IconVersion): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            isFocusable = true
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.nav_item_bg)
            val margin = (4 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = margin }
        }

        // 图标预览
        val iconSize = (48 * resources.displayMetrics.density).toInt()
        val icon = ImageView(ctx).apply {
            setImageResource(version.previewRes)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }
        row.addView(icon)

        // 名称
        val name = TextView(ctx).apply {
            text = version.displayName
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_primary))
            textSize = 15f
            val margin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = margin
            }
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(name)

        // 选中标记
        val check = TextView(ctx).apply {
            text = if (version.id == selectedVersion) "✓" else ""
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_accent))
            textSize = 18f
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(check)

        row.setOnClickListener {
            if (version.id != selectedVersion) {
                switchIcon(version.id)
            }
        }

        return row
    }

    private fun switchIcon(versionId: String) {
        val ctx = requireContext()
        val pm = ctx.packageManager

        // 禁用所有 alias，只启用选中的
        // 注意：不使用 DONT_KILL_APP，让 app 被 kill 以触发 launcher 刷新图标缓存
        for (v in iconVersions) {
            val aliasName = "net.activitywatch.android.IconAlias_${v.id}"
            val component = ComponentName(ctx.packageName, aliasName)
            val state = if (v.id == versionId) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(component, state, 0)
        }

        // 保存选择
        ctx.getSharedPreferences("icon_prefs", Context.MODE_PRIVATE)
            .edit().putString("selected_icon", versionId).apply()

        selectedVersion = versionId
        Snackbar.make(requireView(), "图标已切换，正在刷新...", Snackbar.LENGTH_SHORT).show()

        // 延迟后重启 app 以触发 launcher 刷新
        view?.postDelayed({
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) {
                ctx.startActivity(intent)
            }
            activity?.finishAffinity()
        }, 500)
    }

    private fun getSelectedVersion(ctx: Context): String {
        return ctx.getSharedPreferences("icon_prefs", Context.MODE_PRIVATE)
            .getString("selected_icon", "version_01") ?: "version_01"
    }

    private data class IconVersion(
        val id: String,
        val displayName: String,
        val previewRes: Int
    )
}
