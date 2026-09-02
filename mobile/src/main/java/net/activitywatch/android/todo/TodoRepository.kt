package net.activitywatch.android.todo

import android.content.Context

/** 数据源类型（对齐契约 §0：本地实现与 REST 实现可互换） */
enum class TodoSourceKind(val key: String, val title: String) {
    REST("rest", "服务器"),
    LOCAL("local", "本地");

    companion object {
        fun of(key: String): TodoSourceKind =
            values().firstOrNull { it.key == key } ?: REST
    }
}

/**
 * Todo 数据源持有者：页面只跟它打交道，不直接依赖某个实现。
 * 数据源类型持久化在 SharedPreferences，可在任务页右上角菜单里切换。
 */
object TodoRepository {

    private const val PREFS = "todo_prefs"
    private const val KEY_SOURCE = "source"

    private val listeners = LinkedHashSet<() -> Unit>()

    /** 错误上报：列表页与详情页可能同时存在，故用集合而非单个字段 */
    private val errorListeners = LinkedHashSet<(String) -> Unit>()

    private var current: TodoSource? = null
    private var currentKind: TodoSourceKind? = null

    fun kind(context: Context): TodoSourceKind {
        currentKind?.let { return it }
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE, TodoSourceKind.REST.key) ?: TodoSourceKind.REST.key
        return TodoSourceKind.of(key).also { currentKind = it }
    }

    /** 取当前数据源（首次调用会创建并触发 load） */
    fun source(context: Context): TodoSource {
        val app = context.applicationContext
        val wanted = kind(app)
        val existing = current
        if (existing != null && currentKind == wanted) return existing
        return build(app, wanted).also {
            current = it
            currentKind = wanted
            it.load()
        }
    }

    /** 切换数据源（重新创建 + 重新加载） */
    fun switchTo(context: Context, kind: TodoSourceKind) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SOURCE, kind.key).apply()
        currentKind = kind
        current = build(app, kind).also {
            current = it
            it.load()
        }
    }

    private fun build(context: Context, kind: TodoSourceKind): TodoSource {
        val source = when (kind) {
            TodoSourceKind.REST -> RestTodoSource(context)
            TodoSourceKind.LOCAL -> LocalTodoStore(context)
        }
        source.onChange = { notifyChanged() }
        source.onError = { msg ->
            for (l in errorListeners.toList()) {
                runCatching { l(msg) }
            }
        }
        return source
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun addErrorListener(listener: (String) -> Unit) {
        errorListeners.add(listener)
    }

    fun removeErrorListener(listener: (String) -> Unit) {
        errorListeners.remove(listener)
    }

    private fun notifyChanged() {
        for (l in listeners.toList()) {
            try {
                l()
            } catch (_: Throwable) {
            }
        }
    }
}
