package net.activitywatch.android

import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
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
import net.activitywatch.android.focus.FocusAnalyticsFragment
import net.activitywatch.android.focus.FocusCalendarFragment
import net.activitywatch.android.focus.FocusCountdownFragment
import net.activitywatch.android.focus.FocusRecordsFragment
import net.activitywatch.android.focus.FocusTimerFragment
import net.activitywatch.android.inbox.InboxFragment
import net.activitywatch.android.inbox.InboxPrefs
import net.activitywatch.android.inbox.InboxSettingsFragment
import net.activitywatch.android.inbox.TrashFragment
import net.activitywatch.android.sync.SyncFragment
import net.activitywatch.android.sync.cloud.S3Fragment
import net.activitywatch.android.sync.cloud.WebDavFragment
import net.activitywatch.android.todo.TodoFragment
import net.activitywatch.android.watcher.UsageStatsWatcher
import net.activitywatch.android.dashboard.DashboardFragment
import net.activitywatch.android.queryexplorer.QueryFragment
import net.activitywatch.android.stopwatch.StopwatchFragment

// Firebase 导入
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

private const val TAG = "MainActivity"

/**
 * 抽屉导航的可折叠分组。
 * - Inbox：默认展开
 * - ActivityWatch（活动 / 秒表 / Query Explorer）：默认折叠
 * - 同步（Sync LAN / WebDAV / S3）：默认展开
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

        // 编辑器等子页面在返回栈中，正常弹出返回
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
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

    // ===================== 抽屉导航（可折叠分组）=====================

    private fun setupDrawer() {
        val navList = binding.navList
        navList.removeAllViews()
        rowUIs.clear()

        for (group in buildNavGroups()) {
            val (header, children) = buildGroup(group)
            navList.addView(header)
            navList.addView(children)
        }
        // 初始页是 Inbox，高亮对应项
        selectRow(R.id.nav_inbox)
    }

    private fun buildNavGroups(): List<NavGroup> = listOf(
        NavGroup("Inbox", true, listOf(
            NavRow(
                R.id.nav_inbox,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_edit)!!,
                "Inbox",
                InboxFragment::class.java
            ),
            NavRow(
                R.id.nav_inbox_settings,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_preferences)!!,
                "Inbox 设置",
                InboxSettingsFragment::class.java
            ),
            NavRow(
                R.id.nav_trash,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_delete)!!,
                "回收站",
                TrashFragment::class.java
            )
        )),
        // 任务（契约 §5.1 左栏）：4 个视图直接作为抽屉入口
        NavGroup("任务", true, listOf(
            NavRow(
                R.id.nav_todo_inbox,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_agenda)!!,
                "收集箱",
                TodoFragment::class.java,
                todoArgs("inbox")
            ),
            NavRow(
                R.id.nav_todo_today,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_today)!!,
                "今天",
                TodoFragment::class.java,
                todoArgs("today")
            ),
            NavRow(
                R.id.nav_todo_next7,
                ContextCompat.getDrawable(this, R.drawable.ic_sort)!!,
                "最近 7 天",
                TodoFragment::class.java,
                todoArgs("next7")
            ),
            NavRow(
                R.id.nav_todo_all,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_sort_by_size)!!,
                "全部",
                TodoFragment::class.java,
                todoArgs("all")
            )
        )),
        // 专注模块（契约 §5.8）：8 个模块，记录详情由点记录弹窗承载
        NavGroup("专注", true, listOf(
            NavRow(
                R.id.nav_focus_timer,
                ContextCompat.getDrawable(this, R.drawable.ic_focus_timer)!!,
                "计时",
                FocusTimerFragment::class.java
            ),
            NavRow(
                R.id.nav_focus_records,
                ContextCompat.getDrawable(this, R.drawable.ic_focus_records)!!,
                "专注记录",
                FocusRecordsFragment::class.java
            ),
            NavRow(
                R.id.nav_focus_timeline,
                ContextCompat.getDrawable(this, R.drawable.ic_focus_timeline)!!,
                "专注时间线",
                FocusAnalyticsFragment::class.java,
                focusArgs(FocusAnalyticsFragment.MODE_TIMELINE)
            ),
            NavRow(
                R.id.nav_focus_heatmap,
                ContextCompat.getDrawable(this, R.drawable.ic_focus_heatmap)!!,
                "热力图",
                FocusAnalyticsFragment::class.java,
                focusArgs(FocusAnalyticsFragment.MODE_HEATMAP)
            ),
            NavRow(
                R.id.nav_focus_best,
                ContextCompat.getDrawable(this, R.drawable.ic_focus_best)!!,
                "最佳专注时间",
                FocusAnalyticsFragment::class.java,
                focusArgs(FocusAnalyticsFragment.MODE_BEST)
            ),
            NavRow(
                R.id.nav_focus_calendar,
                ContextCompat.getDrawable(this, R.drawable.ic_focus_calendar)!!,
                "日历",
                FocusCalendarFragment::class.java
            ),
            NavRow(
                R.id.nav_focus_countdown,
                ContextCompat.getDrawable(this, R.drawable.ic_focus_countdown)!!,
                "倒数纪念日",
                FocusCountdownFragment::class.java
            )
        )),
        NavGroup("ActivityWatch", false, listOf(
            NavRow(
                R.id.nav_dashboard,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_recent_history)!!,
                "活动",
                DashboardFragment::class.java
            ),
            NavRow(
                R.id.nav_stopwatch,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_today)!!,
                "秒表",
                StopwatchFragment::class.java
            ),
            NavRow(
                R.id.nav_query,
                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_search)!!,
                "Query Explorer",
                QueryFragment::class.java
            )
        )),
        NavGroup("同步", true, listOf(
            NavRow(
                R.id.nav_sync,
                ContextCompat.getDrawable(this, R.drawable.ic_menu_manage)!!,
                "Sync (LAN)",
                SyncFragment::class.java
            ),
            NavRow(
                R.id.nav_webdav,
                ContextCompat.getDrawable(this, R.drawable.ic_cloud_webdav)!!,
                "WebDAV（实验性）",
                WebDavFragment::class.java
            ),
            NavRow(
                R.id.nav_s3,
                ContextCompat.getDrawable(this, R.drawable.ic_cloud_s3)!!,
                "S3（实验性）",
                S3Fragment::class.java
            )
        ))
    )

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

    /** 专注分析页的模块参数（与 FocusAnalyticsFragment.ARG_MODE 对应） */
    private fun focusArgs(mode: String): Bundle =
        Bundle().apply { putString(FocusAnalyticsFragment.ARG_MODE, mode) }

    private fun navItemBg(): Drawable? = ContextCompat.getDrawable(this, R.drawable.nav_item_bg)

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
