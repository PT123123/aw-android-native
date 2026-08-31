package net.activitywatch.android.inbox

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地置顶状态存储（服务端暂不支持 pinned 字段，置顶仅保存在本机）。
 */
object PinStore {
    private const val PREFS = "inbox_pins"
    private const val KEY = "pinned_note_ids"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPinned(context: Context, noteId: Long): Boolean =
        prefs(context).getStringSet(KEY, emptySet())?.contains(noteId.toString()) == true

    /** 返回当前全部置顶笔记 id */
    fun pinnedIdsSet(context: Context): Set<Long> =
        prefs(context).getStringSet(KEY, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    fun toggle(context: Context, noteId: Long): Boolean {
        val p = prefs(context)
        val current = p.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        val key = noteId.toString()
        val nowPinned = if (current.contains(key)) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        p.edit().putStringSet(KEY, current).apply()
        return nowPinned
    }

    /** 返回 0（置顶）或 1（普通），用于排序 */
    fun sortKey(context: Context, noteId: Long): Int =
        if (isPinned(context, noteId)) 0 else 1
}