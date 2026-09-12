package net.activitywatch.android.sync

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.activitywatch.android.databinding.FragmentSyncPermissionsBinding

/**
 * 同步权限 / 保活设置页。
 *
 * 用途：局域网自动同步依赖应用内常驻的同步服务，而厂商 ROM（HyperOS/MIUI 等）默认会
 * 限制后台活动。这里把「保活相关的系统权限」集中列出、显示当前状态，并提供跳转入口：
 *  - 电池优化（无限制）
 *  - 自启动 / 后台启动（厂商专有入口，按常见厂商依次尝试）
 *  - 应用详情（通用兜底）
 *  - 通知权限（任务提醒依赖；后续前台保活服务的前置条件）
 *  - Wi-Fi 设置与高级设置（休眠策略 / 静态 IP）
 *
 * 只做「读状态 + 跳转」，不修改系统设置；所有跳转都做异常兜底，找不到页面不崩。
 * 状态在 onResume 与「刷新状态」按钮里重新读取（用户从系统设置返回后即为最新）。
 */
class SyncPermissionsFragment : Fragment() {

    private var _binding: FragmentSyncPermissionsBinding? = null
    private val binding get() = _binding!!

    /** 通知权限（Android 13+ 运行时权限）：结果回来就刷新状态 */
    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSyncPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        binding.btnBattery.setOnClickListener { openBatteryOptimization() }
        binding.btnAutoStart.setOnClickListener { openAutoStart() }
        binding.btnAppDetails.setOnClickListener { openAppDetails() }
        binding.btnNotification.setOnClickListener { openNotification() }
        binding.btnWifiSettings.setOnClickListener { openSystem(Settings.ACTION_WIFI_SETTINGS) }
        binding.btnWifiIpSettings.setOnClickListener {
            // Wi-Fi 高级设置（休眠时保持连接 / 静态 IP）；部分 ROM 无此页 → 退到 Wi-Fi 列表
            if (!openSystem(Settings.ACTION_WIFI_IP_SETTINGS)) {
                openSystem(Settings.ACTION_WIFI_SETTINGS)
            }
        }
        binding.btnRefresh.setOnClickListener {
            refreshStatus()
            Snackbar.make(binding.root, "已刷新状态", Snackbar.LENGTH_SHORT).show()
        }

        refreshStatus()
        refreshServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后重新读取（权限状态没有回调通知）
        refreshStatus()
        refreshServiceStatus()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ==================== 状态读取 ====================

    private fun refreshStatus() {
        if (_binding == null) return
        binding.tvBatteryStatus.text = if (isIgnoringBatteryOptimizations()) {
            "已设为无限制，后台同步不被省电策略打断"
        } else {
            "仍受电池优化限制：息屏/长时间后台后同步可能停止"
        }
        binding.tvNotificationStatus.text = if (notificationsEnabled()) {
            "已允许"
        } else {
            "已关闭，保活与提醒都会受影响"
        }
        binding.tvWifiStatus.text = wifiLine()
    }

    /** 同步服务是否在响应（保活最终要看的就是它） */
    private fun refreshServiceStatus() {
        binding.tvServiceStatus.text = "检测中…"
        lifecycleScope.launch {
            val repo = SyncRepository()
            // 服务已被回收时 OkHttp 默认要等 30s 才超时。本页是诊断用途，压到 6s，
            // 让用户尽快看到「无响应」而不是盯着「检测中…」发呆。
            val result = withTimeoutOrNull(6_000L) { repo.call { repo.api.getConfig() } }
            if (_binding == null) return@launch
            val cfg = result?.getOrNull()
            val err = result?.exceptionOrNull()?.message ?: "等待 6 秒超时"
            binding.tvServiceStatus.text = if (cfg != null) {
                "同步服务运行中；局域网同步当前" +
                    if (cfg.enabled) "已开启" else "已关闭（非 Wi-Fi 时自动关闭）"
            } else {
                "同步服务无响应（$err）：后台很可能已被系统回收，重新打开本应用即可恢复"
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()

    private fun wifiLine(): String {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return "无法读取网络状态"
        val onWifi = cm.allNetworks.any { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        return if (onWifi) {
            "Wi-Fi 已连接：局域网同步自动开启，本机 IP 变化时自动重发现"
        } else {
            "当前不在 Wi-Fi：局域网同步自动关闭（也不会走流量同步）"
        }
    }

    // ==================== 跳转 ====================

    /** 电池优化：优先拉起本应用的「无限制」确认弹窗，无此页时退到优化列表页 */
    private fun openBatteryOptimization() {
        val pkg = requireContext().packageName
        if (openSystem(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) return
        if (openSystem(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) return
        openAppDetails()
    }

    /**
     * 自启动管理页：各厂商入口不同，按常见清单依次尝试。
     * 该状态是厂商私有的、无法程序化读取，页面上只做说明。
     */
    private fun openAutoStart() {
        // 厂商自启动页不是标准 action，只能按 ComponentName 点名；版本变动时类名会改，
        // 所以每家给两个候选。全部失败不兜底到应用详情（页面另有该入口），
        // 而是留在这里提醒用户手动找——否则用户会以为「跳过去就等于开好了」。
        val candidates = listOf(
            // MIUI / HyperOS
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            component(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // 华为 / 荣耀
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            // OPPO / 一加 / realme
            component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            component(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.startup.StartupAppListActivity"
            ),
            component(
                "com.oplus.safecenter",
                "com.oplus.safecenter.startupapp.StartupAppListActivity"
            ),
            // vivo / iQOO
            component(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        for (intent in candidates) {
            if (openSystemIntent(intent)) return
        }
        Snackbar.make(
            binding.root,
            "没找到自启动设置页，请到系统设置里手动开启本应用的「自启动 / 后台运行」",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun openAppDetails() {
        openSystem(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, requireContext().packageName)
    }

    /** 通知：未授权则先请求运行时权限（13+），否则跳到本应用的通知设置页 */
    private fun openNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            if (openSystemIntent(intent)) return
        }
        openAppDetails()
    }

    /** 按「包名 + 类名」点名打开厂商私有页面（非标准 action，只能用 ComponentName） */
    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun openSystem(action: String, pkg: String? = null): Boolean =
        openSystemIntent(
            Intent(action).apply {
                if (pkg != null) data = Uri.parse("package:$pkg")
            }
        )

    /** 打开任意系统页面；无对应 Activity / 被 ROM 拒绝时返回 false，不抛给调用方 */
    private fun openSystemIntent(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
