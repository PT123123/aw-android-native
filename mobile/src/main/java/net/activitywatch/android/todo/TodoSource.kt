package net.activitywatch.android.todo

/**
 * 数据源契约（对齐 aw-qtui《Todo 功能规格与 API 契约》§2）。
 *
 * 页面只依赖这个抽象类：写操作异步生效后回调 [onChange]，页面重新读取
 * [lists] / [tasks] 快照并重渲染；**UI 不做乐观更新**。
 *
 * 两个实现可互换：
 *  - [LocalTodoStore]  本地：内存 + todo_local.json（支持清单 / 子任务 / 重复）
 *  - [RestTodoSource]  REST：/inbox/todos（清单用 tag 模拟，子任务与重复降级为空操作）
 */
abstract class TodoSource {

    /** 数据已变化，请重读快照并重渲染（契约 §2.2 的 dataChanged） */
    var onChange: (() -> Unit)? = null

    /** 失败静默上报（页面弹 Toast），不阻塞流程 */
    var onError: ((String) -> Unit)? = null

    /** 初始加载；完成后回调 onChange */
    abstract fun load()

    /** 是否已加载完成 */
    abstract val ready: Boolean

    /** 清单快照（收集箱不在其中，以 id=0 表示） */
    abstract fun lists(): List<TodoList>

    /** 任务快照 */
    abstract fun tasks(): List<TodoTask>

    // ── 写操作 ──────────────────────────────────────────
    abstract fun createList(name: String, color: String = "")
    abstract fun renameList(listId: Long, name: String)
    abstract fun deleteList(listId: Long)
    abstract fun createTask(title: String, listId: Long, dueDate: String = "")
    abstract fun updateTask(task: TodoTask)
    abstract fun setTaskCompleted(taskId: Long, completed: Boolean)
    abstract fun deleteTask(taskId: Long)
    abstract fun addSubtask(taskId: Long, title: String)
    abstract fun toggleSubtask(taskId: Long, subtaskId: Long)
    abstract fun removeSubtask(taskId: Long, subtaskId: Long)

    /** 是否支持子任务（REST 不支持） */
    open val supportsSubtasks: Boolean get() = true

    /** 是否支持重复规则（REST 不支持） */
    open val supportsRecurrence: Boolean get() = true

    /** 是否支持真实清单实体（REST 用 tag 模拟，无独立实体） */
    open val supportsLists: Boolean get() = true

    /** 数据源名称（切换菜单里展示） */
    abstract val label: String

    protected fun notifyChanged() {
        try {
            onChange?.invoke()
        } catch (t: Throwable) {
            onError?.invoke("刷新失败：${t.message}")
        }
    }

    protected fun reportError(msg: String) {
        onError?.invoke(msg)
    }
}

// ===================== 视图过滤与排序（契约 §5.2） =====================

/** 视图过滤：逾期任务包含在「今天」与「最近 7 天」内 */
fun filterTasks(
    tasks: List<TodoTask>,
    view: TodoView,
    listId: Long = 0L,
): List<TodoTask> {
    val today = todayStr()
    val next7 = dateStrOffset(6)
    return tasks.filter { t ->
        when (view) {
            TodoView.INBOX -> t.listId == 0L
            TodoView.TODAY -> t.hasDue() && t.dueDate <= today
            TodoView.NEXT7 -> t.hasDue() && t.dueDate <= next7
            TodoView.ALL -> true
            TodoView.LIST -> t.listId == listId
        }
    }
}

/**
 * 排序（契约 §5.2 taskLessThan + 右上角选项菜单可选模式）：
 * - 未完成组按 [mode] 对应的 Comparator 排序
 * - 已完成组：completedAt 降序，不受排序模式影响
 * - 未完成组整体排在已完成组之前
 */
fun sortTasks(tasks: List<TodoTask>, mode: TodoSortMode = TodoSortMode.DEFAULT): List<TodoTask> {
    val openComparator = when (mode) {
        TodoSortMode.DEFAULT -> DEFAULT_OPEN_COMPARATOR
        TodoSortMode.RECENTLY_ADDED -> RECENTLY_ADDED_COMPARATOR
        TodoSortMode.REVERSED -> REVERSED_COMPARATOR
        TodoSortMode.BY_PRIORITY -> BY_PRIORITY_COMPARATOR
        TodoSortMode.BY_DUE_DATE -> BY_DUE_COMPARATOR
    }
    val open = tasks.filter { !it.completed }.sortedWith(openComparator)
    val done = tasks.filter { it.completed }.sortedByDescending { it.completedAt }
    return open + done
}

/** 视图 + 排序一步到位，并拆出未完成 / 已完成两组 */
fun visibleTasks(
    tasks: List<TodoTask>,
    view: TodoView,
    listId: Long = 0L,
    sortMode: TodoSortMode = TodoSortMode.DEFAULT,
): Pair<List<TodoTask>, List<TodoTask>> {
    val filtered = sortTasks(filterTasks(tasks, view, listId), sortMode)
    val open = filtered.filter { !it.completed }
    val done = filtered.filter { it.completed }
    return open to done
}
