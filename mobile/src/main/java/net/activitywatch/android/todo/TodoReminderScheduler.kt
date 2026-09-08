package net.activitywatch.android.todo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.activitywatch.android.MainActivity
import net.activitywatch.android.R
import java.util.Calendar

/** 到期提醒通知渠道 id（与 MainActivity.CHANNEL_TODO_REMINDER 保持一致） */
private const val REMINDER_CHANNEL = "todo_reminder"

/** 私有广播 action：闹钟触发 */
private const val ACTION_FIRE = "net.activitywatch.android.todo.ACTION_FIRE_REMINDER"

/** 登记表 prefs：task_<id> → "<dueDate>|<title>"，开机/时间变更后据此重挂闹钟 */
private const val PREFS = "todo_reminder_prefs"

/** 到期日当天的提醒时刻（本地时区 09:00） */
private const val REMIND_HOUR = 9

/**
 * 任务到期提醒调度器：
 * - 任务数据每次变化（load/写操作后 TodoRepository 通知）与当前应提醒集合做差量同步：
 *   未完成且到期日 >= 今天的任务 → 在到期日 09:00 挂精确闹钟；已完成/删除/改期的 → 取消旧闹钟
 * - 闹钟不跨重启，登记表持久化在 prefs，开机/时间变更时由 [TodoReminderReceiver] 调 [armAll] 重挂
 * - dueDate 粒度是日期（yyyy-MM-dd），故提醒时刻固定为当天 09:00；已过时刻的今天任务不补发（列表红标已示意）
 */
object TodoReminderScheduler {

    /** 从仓库当前数据源快照做差量同步（数据源尚未创建时忽略，等页面首次加载后再调度） */
    fun syncFromRepository(context: Context) {
        val src = TodoRepository.peekSource() ?: return
        runCatching { sync(context.applicationContext, src.tasks()) }
            .onFailure { Log.w("TodoReminder", "提醒同步失败", it) }
    }

    fun sync(context: Context, tasks: List<TodoTask>) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStr()

        // 应提醒集合：未完成、有到期日、未过期
        val desired = tasks
            .filter { !it.completed && it.hasDue() && it.dueDate >= today }
            .associate { it.id to (it.dueDate to it.title) }

        // 取消：已完成/删除/改期的旧登记（dueDate 变化也视为旧）
        for (key in prefs.all.keys.toList()) {
            val id = key.removePrefix("task_").toLongOrNull() ?: continue
            val want = desired[id]
            val storedDue = prefs.getString(key, "")?.substringBefore('|')
            if (want == null || storedDue != want.first) {
                am.cancel(firePendingIntent(app, id))
                prefs.edit().remove(key).apply()
            }
        }

        // 登记/更新：到期日 09:00 挂闹钟（登记内容未变则跳过，避免每次数据变化都重挂）
        for ((id, pair) in desired) {
            val (due, title) = pair
            val key = "task_$id"
            val stored = prefs.getString(key, null)
            if (stored == "$due|$title") continue
            val triggerAt = dueMillis(due) ?: continue
            if (triggerAt <= System.currentTimeMillis()) {
                // 今天到期但已过 09:00：不补发；若之前登记过则清掉
                if (stored != null) {
                    am.cancel(firePendingIntent(app, id))
                    prefs.edit().remove(key).apply()
                }
                continue
            }
            arm(app, am, id, due, title, triggerAt)
            prefs.edit().putString(key, "$due|$title").apply()
        }
    }

    /** 开机 / 时间或时区变更后，按登记表重挂所有未触发的闹钟（RTC 闹钟不跨重启） */
    fun armAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        for ((key, value) in prefs.all) {
            val id = key.removePrefix("task_").toLongOrNull() ?: continue
            val v = value as? String ?: continue
            val due = v.substringBefore('|')
            val title = v.substringAfter('|', "")
            val triggerAt = dueMillis(due)
            if (triggerAt == null || triggerAt <= now) {
                prefs.edit().remove(key).apply()
                continue
            }
            arm(app, am, id, due, title, triggerAt)
        }
    }

    private fun arm(
        context: Context,
        am: AlarmManager,
        id: Long,
        due: String,
        title: String,
        triggerAt: Long,
    ) {
        val pi = firePendingIntent(context, id, due, title)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            // 缺「闹钟和提醒」权限：退化为约 10 分钟窗口的近似提醒（MainActivity 有引导授权）
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10 * 60_000L, pi)
        }
    }

    /** "yyyy-MM-dd" → 当天本地时区 09:00 的毫秒时间戳；格式非法返回 null */
    private fun dueMillis(due: String): Long? {
        val p = due.split("-")
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, m - 1, d, REMIND_HOUR, 0, 0)
        return c.timeInMillis
    }

    private fun firePendingIntent(
        context: Context,
        id: Long,
        due: String = "",
        title: String = "",
    ): PendingIntent {
        val intent = Intent(context, TodoReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra("taskId", id)
            putExtra("due", due)
            putExtra("title", title)
        }
        return PendingIntent.getBroadcast(
            context,
            (id and 0x7fffffffL).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 闹钟触发 → 发到期提醒通知；开机/时间变更 → 重挂登记表里的闹钟 */
class TodoReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> fire(context, intent)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> TodoReminderScheduler.armAll(context)
        }
    }

    private fun fire(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra("taskId", -1L)
        if (taskId < 0) return
        val title = intent.getStringExtra("title").orEmpty().ifEmpty { "任务到期" }
        val due = intent.getStringExtra("due").orEmpty()

        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            val contentPi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_checkmark)
                .setContentTitle("任务到期")
                .setContentText(if (due.isNotEmpty()) "$title（${dueLabel(due)}）" else title)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(taskId.toInt(), notification) }
        }
        // 已触发：从登记表移除（一次性提醒）
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("task_$taskId").apply()
    }
}
