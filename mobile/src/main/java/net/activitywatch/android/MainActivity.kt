package net.activitywatch.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import net.activitywatch.android.databinding.ActivityMainBinding
import net.activitywatch.android.focus.FocusHubFragment
import net.activitywatch.android.inbox.InboxFragment
import net.activitywatch.android.inbox.InboxPrefs
import net.activitywatch.android.inbox.InboxSettingsFragment
import net.activitywatch.android.sync.LanSyncNetworkMonitor
import net.activitywatch.android.sync.SyncHubFragment
import net.activitywatch.android.sync.SyncSettingsFragment
import net.activitywatch.android.sync.SyncDetailsFragment
import net.activitywatch.android.sync.cloud.S3Fragment
import net.activitywatch.android.todo.TodoFragment
import net.activitywatch.android.watcher.UsageStatsWatcher
import net.activitywatch.android.dashboard.ActivityHubFragment

// Firebase 导入
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

private const val TAG = "MainActivity"

/** 通知权限运行时申请 requestCode */
private const val REQ_POST_NOTIFICATIONS = 4101

/** 任务提醒通知渠道 id */
private const val CHANNEL_TODO_REMINDER = "todo_reminder"

/**
 * 抽屉导航的可折叠分组。
 * - Inbox（笔记 / To Do）：默认展开
 * - 专注 / 活动 / 同步：已合并为顶层单项（见 [buildNavRows]），页内用 Tab 分隔子模块
 * - 抽屉最底部固定一行「笔记设置」，回收站入口在设置页内（见 InboxSettingsFragment）
 */
private data class NavRow(
    val id: Int,
    val icon: Drawable,
    val title: String,
    val fragmentClass: Class<out Fragment>,
    /** 传给 Fragment 的参数（如 Todo 视图）；null 表示无参 */
    val args: Bundle? = null
)

private data class NavGroup(
    val title: String,
    val expandedByDefault: Boolean,
    val rows: List<NavRow>
)

private data class RowUI(
    val id: Int,
    val container: View,
    val icon: AppCompatImageView,
    val title: TextView
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val rowUIs = mutableListOf<RowUI>()
    private var selectedNavId = View.NO_ID

    val version: String
        get() {
            return packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }

    // 按设置应用抽屉的左滑热区宽度（0=关闭右滑开抽屉）
    fun applyDrawerEdgeZone() {
        binding.drawerLayout.edgeZoneRatio = InboxPrefs.drawerEdgeRatio(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "启动 onCreate, starting onboarding activity")

        // 在 onCreate 方法中初始化 Firebase
        try {
            Log.d(TAG, "尝试在 MainActivity.onCreate 中初始化 FirebaseApp")
            FirebaseApp.initializeApp(this) // 在 MainActivity 的 onCreate 中调用 Firebase 初始化
            Log.d(TAG, "FirebaseApp 初始化完成")

            Log.d(TAG, "尝试在 MainActivity.onCreate 中获取并开启 Crashlytics")
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true) // 启用 Crashlytics 崩溃收集
            Log.d(TAG, "Firebase Crashlytics 开启崩溃收集")
        } catch (e: Throwable) {
            Log.e(TAG, "Firebase 初始化失败 (FirebaseApp 或 Crashlytics) 在 MainActivity.onCreate 中", e)
        }

        // 如果是第一次使用或未授权使用统计，启动 Onboarding Activity
        val prefs = AWPreferences(this)
        if (prefs.isFirstTime() || !UsageStatsWatcher.isUsageAllowed(this)) {
            Log.i(TAG, "First time or usage not allowed, starting onboarding activity")
            val intent = Intent(this, OnboardingActivity::class.java)
            startActivity(intent)
            return
        }

        // 设置 UI
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 按设置应用抽屉的左滑热区宽度（0=关闭右滑开抽屉）
        applyDrawerEdgeZone()

        // 设置心跳发送的闹钟
        val usw = UsageStatsWatcher(this)
        usw.setupAlarm()

        // 构建抽屉导航（可折叠分组）
        setupDrawer()

        // 启动服务器任务
        val ri = RustInterface(this)
        ri.startServerTask(this)

        // Wi-Fi 自动开关：连上 Wi-Fi 自动开启局域网同步，离开自动关闭（无需人工开关）
        LanSyncNetworkMonitor.register(this, ri)

        // 提醒所需权限：通知（13+ 运行时申请）、精确闹钟（12+ 特殊权限，缺失时提示引导）
        ensureReminderPermissions()

        // 如果 savedInstanceState 不为 null，则跳过添加 Fragment
        if (savedInstanceState != null) {
            return
        }

        // 添加初始的 InboxFragment（原生收件箱作为初始页）
        val firstFragment: Fragment = InboxFragment()
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, firstFragment)
            .commit()
        Log.d(TAG, "Fragment 事务执行完成")
    }

    override fun onResume() {
        super.onResume()
        // 确保数据总是最新的
        val usw = UsageStatsWatcher(this)
        usw.sendHeartbeats()
    }

    // ===================== 提醒权限 =====================

    private fun ensureReminderPermissions() {
        createReminderChannel()

        // 通知权限（Android 13+ 运行时申请）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }

        // 精确闹钟（Android 12+ 的「闹钟和提醒」特殊权限，无法弹窗申请）：
        // 缺失时用 Snackbar 引导跳系统设置，不阻塞使用
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                Snackbar.make(
                    binding.coordinatorLayout,
                    "任务到期提醒需要「闹钟和提醒」权限",
                    Snackbar.LENGTH_LONG
                ).setAction("去设置") {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:$packageName"),
                            )
                        )
                    } catch (e: Throwable) {
                        Log.w(TAG, "打开精确闹钟设置页失败", e)
                    }
                }.show()
            }
        }
    }

    /** 任务提醒通知渠道（Importance：弹出横幅 + 声音） */
    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TODO_REMINDER,
                    "任务提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "任务到期提醒通知" }
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)

        // 让片段级返回回调优先处理（如多选模式、搜索栏等）
        // 仅当当前片段不是 InboxFragment 时才先调用 super（InboxFragment 的逻辑在后面）
        if (currentFragment !is InboxFragment) {
            super.onBackPressed()
            // 如果活动仍在运行，说明某个回调处理了返回事件
            if (!isFinishing && !isDestroyed) {
                // 弹栈可能切回了上一页（如 TODO → 笔记），同步侧边栏高亮
                syncSidebarHighlight()
                return
            }
        }

        // 编辑器等子页面在返回栈中，正常弹出返回
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
            syncSidebarHighlight()
            return
        }

        // 已在原生 Inbox 初始页，交由系统处理（退出）
        if (currentFragment is InboxFragment) {
            super.onBackPressed()
            return
        }

        supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, InboxFragment())
            .commit()
        selectRow(R.id.nav_inbox)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                Snackbar.make(binding.coordinatorLayout, "The settings button was clicked, but it's not yet implemented!", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ===================== 抽屉导航（可折叠分组 + 合并单项）=====================

    private fun setupDrawer() {
        val navList = binding.navList
        navList.removeAllViews()
        rowUIs.clear()

        for (group in buildNavGroups()) {
            val (header, children) = buildGroup(group)
            navList.addView(header)
            navList.addView(children)
        }
        // 合并后的顶层单项：一行进一个 Tab 宿主页，页内再分 tab
        for (row in buildNavRows()) {
            val ui = buildRow(row)
            // 与分组标题对齐（分组内行由 children 容器统一缩进，独立行需自行加内边距）
            ui.container.setPaddingRelative(dp(16), 0, dp(16), 0)
            rowUIs.add(ui)
            navList.addView(ui.container)
        }
        // 抽屉最底部固定一行「笔记设置」（回收站入口在设置页内）
        val settingsRow = buildRow(NavRow(
            R.id.nav_inbox_settings,
            ContextCompat.getDrawable(this, android.R.drawable.ic_menu_preferences)!!,
            "笔记设置",
            InboxSettingsFragment::class.java
        ))
        (settingsRow.container.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        // 与分组标题对齐（组内行由 children 容器统一缩进，独立行需自行加内边距）
        settingsRow.container.setPaddingRelative(dp(16), 0, dp(16), 0)
        navList.addView(bottomDivider())
        rowUIs.add(settingsRow)
        navList.addView(settingsRow.container)
        // 初始页是 Inbox，高亮对应项
        selectRow(R.id.nav_inbox)
    }

    /** 底部固定入口与上方分组之间的分隔线 */
    private fun bottomDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            setMargins(dp(16), dp(8), dp(16), 0)
        }
        setBackgroundColor(color(R.color.aw_divider))
    }

    /** 可折叠分组（只剩 Inbox；专注 / 活动 / 同步已合并为 [buildNavRows] 里的顶层单项） */
    private fun buildNavGroups(): List<NavGroup> = listOf(
        NavGroup("Inbox", true, listOf(
            NavRow(
                R.id.nav_inbox,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_edit)!!,
                "笔记",
                InboxFragment::class.java
            ),
            NavRow(
                R.id.nav_todo_inbox,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_agenda)!!,
                "To Do",
                TodoFragment::class.java,
                todoArgs("inbox")
            )
        ))
    )

    /**
     * 合并后的三个顶层导航项：每项一个 Tab 宿主页，页内 TabLayout 再分隔子模块，
     * 子模块自身若带 Tab（活动页的概览/时间线/趋势、云备份的 WebDAV/S3）即为「tab 内 tab」。
     */
    private fun buildNavRows(): List<NavRow> = listOf(
        NavRow(
            R.id.nav_focus_hub,
            ContextCompat.getDrawable(this, R.drawable.ic_focus_timer)!!,
            "专注",
            FocusHubFragment::class.java
        ),
        NavRow(
            R.id.nav_activity_hub,
            ContextCompat.getDrawable(this, android.R.drawable.ic_menu_recent_history)!!,
            "活动",
            ActivityHubFragment::class.java
        ),
        NavRow(
            R.id.nav_sync_hub,
            ContextCompat.getDrawable(this, R.drawable.ic_menu_manage)!!,
            "同步",
            SyncHubFragment::class.java
        )
    )

    /** 抽屉里全部可高亮的行（分组内 + 顶层单项） */
    private fun allNavRows(): List<NavRow> = buildNavGroups().flatMap { it.rows } + buildNavRows()

    private fun buildGroup(group: NavGroup): Pair<View, View> {
        val children = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPaddingRelative(dp(48), 0, dp(16), 0)
            visibility = if (group.expandedByDefault) View.VISIBLE else View.GONE
        }
        for (row in group.rows) {
            val ui = buildRow(row)
            rowUIs.add(ui)
            children.addView(ui.container)
        }

        val chevron = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(color(R.color.aw_text_secondary), PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            rotation = if (group.expandedByDefault) 90f else 0f
        }
        val title = TextView(this).apply {
            text = group.title
            textSize = 13f
            letterSpacing = 0.04f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.aw_text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setPaddingRelative(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            background = navItemBg()
            setOnClickListener {
                val expanded = children.visibility == View.VISIBLE
                children.visibility = if (expanded) View.GONE else View.VISIBLE
                chevron.animate()
                    .rotation(if (expanded) 0f else 90f)
                    .setDuration(200)
                    .start()
            }
        }
        header.addView(chevron)
        header.addView(title)
        return header to children
    }

    private fun buildRow(row: NavRow): RowUI {
        val icon = AppCompatImageView(this).apply {
            setImageDrawable(row.icon)
            setColorFilter(color(R.color.aw_text_secondary), PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(28) }
        }
        val title = TextView(this).apply {
            text = row.title
            textSize = 14f
            setTextColor(color(R.color.aw_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            isClickable = true
            isFocusable = true
            background = navItemBg()
            setId(row.id)
            setOnClickListener {
                selectRow(row.id)
                navigateTo(row.fragmentClass)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
        container.addView(icon)
        container.addView(title)
        return RowUI(row.id, container, icon, title)
    }

    private fun selectRow(id: Int) {
        selectedNavId = id
        for (r in rowUIs) {
            val sel = r.id == id
            r.container.isSelected = sel
            r.title.setTextColor(if (sel) color(R.color.aw_accent) else color(R.color.aw_text_primary))
            r.icon.setColorFilter(
                if (sel) color(R.color.aw_accent) else color(R.color.aw_text_secondary),
                PorterDuff.Mode.SRC_IN
            )
        }
    }

    /**
     * 让侧边栏高亮跟随当前实际显示的片段（返回键弹栈后调用）。
     * 按片段运行时类匹配导航行定义；找不到对应行（如编辑器子页）则保持原高亮。
     */
    private fun syncSidebarHighlight() {
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container) ?: return
        val row = allNavRows().firstOrNull { it.fragmentClass == current.javaClass } ?: return
        selectRow(row.id)
    }

    private fun navigateTo(fragmentClass: Class<out Fragment>, args: Bundle? = null) {
        val fragment = fragmentClass.newInstance()
        if (args != null) fragment.arguments = Bundle(args)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Todo 视图入口的参数包（与 TodoFragment.ARG_VIEW 对应） */
    private fun todoArgs(view: String): Bundle =
        Bundle().apply { putString(TodoFragment.ARG_VIEW, view) }

    private fun navItemBg(): Drawable? = ContextCompat.getDrawable(this, R.drawable.nav_item_bg)

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
