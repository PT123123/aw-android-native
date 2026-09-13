package net.activitywatch.android.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import net.activitywatch.android.R
import net.activitywatch.android.watcher.UsageStatsWatcher

/**
 * 权限设置页（抽屉 → 权限设置）。
 *
 * 原首次启动由 OnboardingActivity 强制引导授权、不授权不让进入，
 * 现已移除该阻塞流程，所有权限集中到本页面按需开启：
 *
 * - 使用情况访问：记录使用数据 / 屏幕使用时间小部件的核心依赖
 * - 桌面快捷方式（澎湃OS/MIUI 特有，MIUIOP 10017）：长按图标「一键添加小部件」依赖；
 *   默认被系统拒绝且弹窗被静默吞掉，需在此跳转应用信息页手动允许
 * - 精确闹钟：任务到期提醒
 * - 通知：任务到期提醒
 * - 无障碍服务（可选）：记录浏览器网址
 * - 电池优化白名单（可选）：后台记录与局域网同步保活
 */
class AppPermissionsFragment : Fragment() {

    companion object {
        private const val TAG = "AppPermissions"
        private const val MIUI_OP_SHORTCUTS = 10017          // 澎湃OS/MIUI 的「桌面快捷方式」AppOp
        private const val REQ_POST_NOTIFICATIONS = 4201
    }

    private lateinit var rowsContainer: LinearLayout

    /** 单个权限条目的展示数据；granted = null 表示当前系统版本无需该权限 */
    private data class Item(
        val title: String,
        val desc: String,
        val granted: Boolean?,
        val action: (() -> Unit)? = null,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_app_permissions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rowsContainer = view.findViewById(R.id.ll_permission_rows)
    }

    override fun onResume() {
        super.onResume()
        rebuildRows()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) rebuildRows()
    }

    // ===================== 权限条目 =====================

    private fun buildItems(): List<Item> {
        val ctx = requireContext()
        val items = mutableListOf<Item>()

        items += Item(
            title = "使用情况访问",
            desc = "记录各应用使用时长、屏幕使用时间小部件与趋势统计的数据来源，核心功能依赖此项。",
            granted = UsageStatsWatcher.isUsageAllowed(ctx),
            action = {
                safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        )

        // 澎湃OS/MIUI 特有权限；非小米系统不展示
        miuiShortcutMode(ctx)?.let { mode ->
            items += Item(
                title = "桌面快捷方式",
                desc = "长按应用图标菜单里的「一键添加小部件」依赖此项。澎湃OS 默认拒绝且不弹提示，" +
                    "被拒时点击会没有反应——点「去开启」后在应用信息页的 权限管理 中允许。",
                granted = mode == AppOpsManager.MODE_ALLOWED,
                action = {
                    safeStart(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
            )
        }

        items += Item(
            title = "精确闹钟",
            desc = "任务到期提醒的准点闹钟（Android 12+ 需单独授权「闹钟和提醒」）。",
            granted = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() == true
            } else null,
            action = if (Build.VERSION.SDK_INT >= 31) {
                {
                    safeStart(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
            } else null
        )

        items += Item(
            title = "通知",
            desc = "任务到期提醒以通知形式弹出（Android 13+ 需授权）。",
            granted = if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else null,
            action = if (Build.VERSION.SDK_INT >= 33) {
                { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS) }
            } else null
        )

        items += Item(
            title = "无障碍服务（可选）",
            desc = "用于记录浏览器访问的网址与标题，不需要浏览记录时可保持关闭。",
            granted = UsageStatsWatcher.isAccessibilityAllowed(ctx),
            action = { safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        items += Item(
            title = "电池优化白名单（可选）",
            desc = "避免后台记录与局域网同步被系统省电策略中断（即「无限制」耗电策略）。",
            granted = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
            action = {
                safeStart(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            }
        )
        return items
    }

    // ===================== 界面构建 =====================

    private fun rebuildRows() {
        if (!::rowsContainer.isInitialized) return
        rowsContainer.removeAllViews()
        for (item in buildItems()) {
            rowsContainer.addView(buildRow(item))
        }
    }

    private fun buildRow(item: Item): View {
        val ctx = requireContext()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_permission_card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = item.title
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(ctx).apply {
            val (statusText, colorRes) = when (item.granted) {
                true -> "已允许" to R.color.aw_success
                false -> "未开启" to R.color.aw_warning
                null -> "无需设置" to R.color.aw_text_disabled
            }
            text = statusText
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, colorRes))
        })
        card.addView(titleRow)

        card.addView(TextView(ctx).apply {
            text = item.desc
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })

        if (item.granted == false && item.action != null) {
            card.addView(MaterialButton(ctx).apply {
                text = "去开启"
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minHeight = 0
                setPadding(dp(16), dp(6), dp(16), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener { item.action?.invoke() }
            })
        }
        return card
    }

    // ===================== 工具 =====================

    /**
     * 读取澎湃OS/MIUI 的「桌面快捷方式」AppOp（系统隐藏的 10017 号自定义 op）。
     * @return op mode；非小米设备或读取失败返回 null
     */
    private fun miuiShortcutMode(context: Context): Int? {
        val manufacturer = Build.MANUFACTURER ?: return null
        val brand = Build.BRAND ?: ""
        val isXiaomi = manufacturer.contains("xiaomi", ignoreCase = true) ||
            brand.contains("xiaomi", ignoreCase = true) ||
            brand.contains("redmi", ignoreCase = true)
        if (!isXiaomi) return null
        return try {
            val check = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            check.invoke(
                context.getSystemService(Context.APP_OPS_SERVICE),
                MIUI_OP_SHORTCUTS,
                Process.myUid(),
                context.packageName
            ) as Int
        } catch (t: Throwable) {
            Log.w(TAG, "检测桌面快捷方式权限失败", t)
            null
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "打开权限设置页失败: ${intent.action}", t)
            Toast.makeText(requireContext(), "无法打开对应设置页，请到系统设置中手动开启", Toast.LENGTH_SHORT).show()
        }
    }
}
