package net.activitywatch.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * 全局强制深色外观。
 *
 * 本 app 的 Inbox / Sync(LAN) 界面本就是深色硬编码，抽屉与系统栏原先仍是亮色，
 * 视觉上是割裂的。这里统一切成 MODE_NIGHT_YES，让 AppTheme（DayNight）始终
 * 解析到 values-night 下的语义色，Activity / Timeline / Trends 全部跟随。
 *
 * 注意：setDefaultNightMode 只影响引用主题属性的控件；写了死色值的布局不会变，
 * 因此新增界面必须引用 @color/aw_* 语义色。
 */
class AWApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
