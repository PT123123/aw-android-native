package net.activitywatch.android.inbox

import android.content.Context
import android.content.SharedPreferences

/**
 * Inbox 偏好设置（仅保存在本机）。
 * 每个手势（单击/双击/长按）可独立配置执行的动作。
 */
object InboxPrefs {

    enum class Gesture { SINGLE, DOUBLE, LONG }

    /** 手势可执行的动作 */
    enum class GestureAction { VIEW, EDIT, COMMENT, PIN, DELETE, MENU, NONE }

    private const val PREFS = "inbox_prefs"
    private const val KEY_ACTION_PREFIX = "gesture_action_"
    private const val KEY_DRAWER_EDGE_INDEX = "drawer_edge_index"
    private const val KEY_AUTO_INPUT = "auto_input_on_start"

    /** 列表卡片是否渲染 Markdown（默认关：正文按纯文本显示） */
    private const val KEY_LIST_MARKDOWN = "list_markdown"

    /** 查看笔记页是否渲染 Markdown（默认关：正文按纯文本显示） */
    private const val KEY_VIEW_MARKDOWN = "view_markdown"

    // 旧版只存"哪个手势打开编辑"，用于迁移
    private const val KEY_LEGACY_EDIT_GESTURE = "edit_gesture"

    // 一次性迁移标志：双击行为由「编辑」改为「查看」
    private const val KEY_MIGRATED_DOUBLE_VIEW = "migrated_double_edit_to_view"

    /** 左滑热区宽度可选档位（占屏幕宽度比例），与设置页滑条的 5 个节点一一对应 */
    val DRAWER_EDGE_RATIOS = floatArrayOf(0f, 1f / 3f, 1f / 2f, 3f / 4f, 1f)

    fun drawerEdgeIndex(context: Context): Int =
        prefs(context).getInt(KEY_DRAWER_EDGE_INDEX, 1)
            .coerceIn(DRAWER_EDGE_RATIOS.indices)

    fun setDrawerEdgeIndex(context: Context, index: Int) {
        prefs(context).edit()
            .putInt(KEY_DRAWER_EDGE_INDEX, index.coerceIn(DRAWER_EDGE_RATIOS.indices))
            .apply()
    }

    fun drawerEdgeRatio(context: Context): Float = DRAWER_EDGE_RATIOS[drawerEdgeIndex(context)]

    /** 进入 Inbox 时是否自动弹出输入框 */
    fun autoInputOnStart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_INPUT, false)

    fun setAutoInputOnStart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_INPUT, enabled).apply()
    }

    /** 列表卡片正文是否用 Markdown 渲染（默认 false = 纯文本） */
    fun listMarkdown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LIST_MARKDOWN, false)

    fun setListMarkdown(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIST_MARKDOWN, enabled).apply()
    }

    /** 查看笔记页正文是否用 Markdown 渲染（默认 false = 纯文本） */
    fun viewMarkdown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIEW_MARKDOWN, false)

    fun setViewMarkdown(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIEW_MARKDOWN, enabled).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun actionFor(context: Context, gesture: Gesture): GestureAction {
        val p = prefs(context)
        migrateDoubleEditToView(p)
        p.getString(KEY_ACTION_PREFIX + gesture.name, null)?.let { raw ->
            runCatching { return GestureAction.valueOf(raw) }.getOrNull()?.let { return it }
        }
        // 迁移：旧设置中选中的手势 → 编辑
        if (p.getString(KEY_LEGACY_EDIT_GESTURE, null) == gesture.name) {
            return GestureAction.EDIT
        }
        return defaultAction(gesture)
    }

    /**
     * 一次性迁移：双击原本默认「编辑」，现改为「查看笔记」。
     * 只迁移从没被改过的默认值，用户手动选过的其他动作不受影响。
     */
    private fun migrateDoubleEditToView(p: SharedPreferences) {
        if (p.getBoolean(KEY_MIGRATED_DOUBLE_VIEW, false)) return
        val key = KEY_ACTION_PREFIX + Gesture.DOUBLE.name
        if (p.getString(key, null) == GestureAction.EDIT.name) {
            p.edit().putString(key, GestureAction.VIEW.name).apply()
        }
        p.edit().putBoolean(KEY_MIGRATED_DOUBLE_VIEW, true).apply()
    }

    fun setActionFor(context: Context, gesture: Gesture, action: GestureAction) {
        prefs(context).edit().putString(KEY_ACTION_PREFIX + gesture.name, action.name).apply()
    }

    private fun defaultAction(gesture: Gesture): GestureAction = when (gesture) {
        Gesture.SINGLE -> GestureAction.NONE
        Gesture.DOUBLE -> GestureAction.VIEW
        Gesture.LONG -> GestureAction.MENU
    }
}
