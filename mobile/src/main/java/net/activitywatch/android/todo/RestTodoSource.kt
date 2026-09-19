package net.activitywatch.android.todo

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * REST 数据源（对标 aw-qtui TodoApiStore）。
 *
 * 服务端契约（升级后）：
 *  - notes        ↔ content
 *  - dueDate      ← due_date 取前 10 位
 *  - 清单          = 独立实体 /inbox/todo-lists，任务以 list_id 关联（0 = 收集箱），与 tag 无关
 *  - 子任务        = todos.subtasks JSON 列（[SubtaskPayload]），整组读改写
 *  - tags         = 全部自由标签，详情页标签栏原样可见
 *  - 重复          → 不支持（[supportsRecurrence] 为 false，UI 隐藏）
 *  - createTask   → POST（title + tags + list_id），带期限时拿到 id 后再补一次 PUT
 *  - 每个写操作    → 完成后全量 load()（无增量、无乐观更新）
 */
class RestTodoSource(context: Context) : TodoSource() {

    companion object {
        /** 加载失败的最大重试次数（同收件箱页 fetch：上限 3 次） */
        private const val MAX_LOAD_RETRIES = 3

        /** 两次重试之间的退避间隔（内嵌 server 冷启动窗口，同收件箱页 1500ms） */
        private const val LOAD_RETRY_DELAY_MS = 1500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private var mTasks: List<TodoTask> = emptyList()
    private var mLists: List<TodoList> = emptyList()

    /** 在途加载序号：load 请求串行的去重凭据（写后 reload 与手动刷新撞车时只跑一轮） */
    private var loadSeq = 0L

    /** 加载失败重试计数（内嵌 server 冷启动尚未就绪时退避重试） */
    private var retryCount = 0

    override var ready: Boolean = false
        private set

    override val label: String get() = "服务器"
    override val supportsSubtasks: Boolean get() = true
    override val supportsRecurrence: Boolean get() = false
    override val supportsLists: Boolean get() = true

    init {
        TodoApi.init(context.applicationContext)
    }

    // ── 快照 ────────────────────────────────────────────

    override fun lists(): List<TodoList> = synchronized(lock) { mLists.map { it.copy() } }

    override fun tasks(): List<TodoTask> = synchronized(lock) { mTasks.map { it.deepCopy() } }

    // ── 加载 ────────────────────────────────────────────

    /**
     * 全量拉取（在途去重 + 失败退避重试）。
     *
     * - 在途去重：一轮加载在跑时，后续 [load]（含 8 处写后 [reload]）只记 pending，
     *   在途轮结束后补一轮——结果等价于每轮都跑，但不会向本机 server 同时打多个全量请求。
     * - 失败重试：内嵌 Rust server 冷启动需几秒，首次加载失败按 1500ms 退避最多重试 3 次
     *   （同收件箱页 fetch 的重试参数），避免任务页停在空态。
     */
    override fun load() {
        val seq = synchronized(lock) { ++loadSeq }
        scope.launch {
            try {
                val response = TodoApi.service.getTodos()   // 不带 completed → 返回全部（含已完成）
                val lists = runCatching { TodoApi.service.getTodoLists() }.getOrDefault(emptyList())
                val tasks = response.map { it.toTask() }.toMutableList()
                val knownListIds = lists.map { it.id }.toSet()
                // 清单可能已在别处删除：悬空 list_id 的任务按收集箱展示（不丢任务）
                for (t in tasks) {
                    if (t.listId != 0L && t.listId !in knownListIds) t.listId = 0L
                }
                val superseded = synchronized(lock) {
                    mTasks = tasks
                    mLists = lists.map { l ->
                        TodoList(
                            id = l.id,
                            name = l.name,
                            color = l.color,
                            sortOrder = l.sortOrder,
                        )
                    }
                    ready = true
                    seq != loadSeq
                }
                if (superseded) {
                    // 加载期间又来了新的 load 请求（在途合并）：补一轮，让最新请求也拿到结果
                    load()
                    return@launch
                }
                retryCount = 0
                notifyChanged()
            } catch (t: Throwable) {
                val retried = synchronized(lock) { seq == loadSeq && retryCount++ < MAX_LOAD_RETRIES }
                if (retried) {
                    // 退避后重试（仅在仍为最新一轮时）：内嵌 server 未就绪的窗口里不放弃
                    delay(LOAD_RETRY_DELAY_MS)
                    load()
                } else {
                    retryCount = 0
                    reportError("加载失败：${t.message}")
                }
            }
        }
    }

    /** 写操作收尾：全量重新拉取（契约 §3.5）；在途去重逻辑在 [load] 内完成 */
    private fun reload() {
        load()
    }

    // ── 清单（独立实体） ────────────────────────────────

    override fun createList(name: String, color: String) {
        if (name.isBlank()) return
        scope.launch {
            try {
                TodoApi.service.createTodoList(CreateTodoListPayload(name = name.trim(), color = color))
                reload()
            } catch (e: Throwable) {
                reportError("新建清单失败：${e.message}")
            }
        }
    }

    override fun renameList(listId: Long, name: String) {
        if (listId <= 0 || name.isBlank()) return
        scope.launch {
            try {
                TodoApi.service.updateTodoList(listId, UpdateTodoListPayload(name = name.trim()))
                reload()
            } catch (e: Throwable) {
                reportError("重命名清单失败：${e.message}")
            }
        }
    }

    override fun deleteList(listId: Long) {
        if (listId <= 0) return
        scope.launch {
            try {
                TodoApi.service.deleteTodoList(listId)   // 服务端把其下任务 list_id 归零
                reload()
            } catch (e: Throwable) {
                reportError("删除清单失败：${e.message}")
            }
        }
    }

    // ── 任务 ────────────────────────────────────────────

    override fun createTask(
        title: String,
        listId: Long,
        dueDate: String,
        tags: List<String>,
        onCreated: ((Long) -> Unit)?,
    ) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            try {
                val created = TodoApi.service.createTodo(
                    CreateTodoPayload(
                        title = trimmed,
                        tags = tags.takeIf { it.isNotEmpty() },
                        listId = listId.takeIf { it != 0L },
                    )
                )
                // 服务端分配的新任务 id 回传页面，用于创建后的滚动定位（load 是异步的，这里先给 id）
                onCreated?.invoke(created.id)
                if (dueDate.isNotBlank()) {
                    // 服务端 create 不支持 due_date：拿到 id 后补一次 PUT（契约 §3.8 缺口 5）
                    TodoApi.service.updateTodo(
                        created.id,
                        UpdateTodoPayload(dueDate = dueDate.toRfc3339())
                    )
                }
                reload()
            } catch (e: Throwable) {
                reportError("创建任务失败：${e.message}")
            }
        }
    }

    override fun updateTask(task: TodoTask) {
        // 已知缺口：无法清空 due_date，故仅在非空时提交（契约 §3.8 缺口 4）
        val cached = synchronized(lock) { mTasks.firstOrNull { it.id == task.id } }
        val completed = if (cached != null && cached.completed != task.completed) task.completed else null
        scope.launch {
            try {
                TodoApi.service.updateTodo(
                    task.id,
                    UpdateTodoPayload(
                        title = task.title,
                        content = task.notes,
                        priority = task.priority,
                        dueDate = task.dueDate.takeIf { it.isNotBlank() }?.toRfc3339(),
                        tags = task.tags,
                        subtasks = task.subtasks.map { SubtaskPayload(it.id, it.title, it.completed) },
                        completed = completed,
                    )
                )
                reload()
            } catch (e: Throwable) {
                reportError("保存失败：${e.message}")
            }
        }
    }

    override fun setTaskCompleted(taskId: Long, completed: Boolean) {
        scope.launch {
            try {
                TodoApi.service.updateTodo(taskId, UpdateTodoPayload(completed = completed))
                reload()
            } catch (e: Throwable) {
                reportError("更新状态失败：${e.message}")
            }
        }
    }

    override fun deleteTask(taskId: Long) {
        scope.launch {
            try {
                TodoApi.service.deleteTodo(taskId)   // 服务端软删除
                reload()
            } catch (e: Throwable) {
                reportError("删除失败：${e.message}")
            }
        }
    }

    // ── 子任务：todos.subtasks 整组读改写 ────────────────

    /** 全局唯一子任务 id：所有任务已有子任务 id 的最大值 +1 */
    private fun nextSubtaskId(): Long {
        val snapshot = synchronized(lock) { mTasks }
        return (snapshot.maxOfOrNull { t -> t.subtasks.maxOfOrNull { it.id } ?: 0L } ?: 0L) + 1L
    }

    private fun mutateSubtasks(taskId: Long, block: (MutableList<TodoSubtask>) -> Unit) {
        val task = synchronized(lock) { mTasks.firstOrNull { it.id == taskId }?.deepCopy() }
            ?: return
        block(task.subtasks)
        scope.launch {
            try {
                TodoApi.service.updateTodo(
                    taskId,
                    UpdateTodoPayload(
                        subtasks = task.subtasks.map { SubtaskPayload(it.id, it.title, it.completed) }
                    )
                )
                reload()
            } catch (e: Throwable) {
                reportError("子任务保存失败：${e.message}")
            }
        }
    }

    override fun addSubtask(taskId: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        mutateSubtasks(taskId) { it.add(TodoSubtask(id = nextSubtaskId(), title = trimmed)) }
    }

    override fun toggleSubtask(taskId: Long, subtaskId: Long) {
        mutateSubtasks(taskId) { list ->
            list.firstOrNull { it.id == subtaskId }?.let { it.completed = !it.completed }
        }
    }

    override fun removeSubtask(taskId: Long, subtaskId: Long) {
        mutateSubtasks(taskId) { list -> list.removeAll { it.id == subtaskId } }
    }
}
