package net.activitywatch.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.navigation.NavigationView
import androidx.core.view.GravityCompat
import androidx.appcompat.app.AppCompatActivity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import android.util.Log
import net.activitywatch.android.databinding.ActivityMainBinding
import net.activitywatch.android.fragments.TestFragment
import net.activitywatch.android.fragments.WebUIFragment
import net.activitywatch.android.inbox.InboxFragment
import net.activitywatch.android.inbox.InboxPrefs
import net.activitywatch.android.inbox.InboxSettingsFragment
import net.activitywatch.android.inbox.TrashFragment
import net.activitywatch.android.watcher.UsageStatsWatcher

// Firebase 导入
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

private const val TAG = "MainActivity"

const val baseURL = "http://127.0.0.1:5600"

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, WebUIFragment.OnFragmentInteractionListener {

    private lateinit var binding: ActivityMainBinding

    val version: String
        get() {
            return packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }

    override fun onFragmentInteraction(item: Uri) {
        Log.w(TAG, "URI onInteraction listener not implemented")
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

        // 设置导航视图监听器
        binding.navView.setNavigationItemSelectedListener(this)

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

        // 其他页面（如 WebUI），返回时回到原生 Inbox
        supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, InboxFragment())
            .commit()
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

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        var fragmentClass: Class<out Fragment>? = null
        var url: String? = null

        // 处理导航视图点击事件
        when (item.itemId) {
            R.id.nav_activity -> {
                fragmentClass = WebUIFragment::class.java
                url = "$baseURL/#/activity/unknown/"
            }
            R.id.nav_inbox -> {
                fragmentClass = InboxFragment::class.java
            }
            R.id.nav_inbox_settings -> {
                fragmentClass = InboxSettingsFragment::class.java
            }
            R.id.nav_trash -> {
                fragmentClass = TrashFragment::class.java
            }
            R.id.nav_sync -> {
                // 临时回退 WebUI 同步页；后续提交将替换为原生同步页
                fragmentClass = WebUIFragment::class.java
                url = "$baseURL/#/sync/"
            }
            R.id.nav_settings -> {
                fragmentClass = WebUIFragment::class.java
                url = "$baseURL/#/settings/"
            }
        }

        val fragment: Fragment? = try {
            if (fragmentClass === WebUIFragment::class.java && url != null) {
                WebUIFragment.newInstance(url)
            } else {
                fragmentClass?.newInstance()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (fragment != null) {
            // 插入 fragment，替换任何现有的 fragment
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container, fragment).addToBackStack(null).commit()
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}
