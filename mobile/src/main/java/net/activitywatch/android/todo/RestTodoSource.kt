package net.activitywatch.android.todo

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * REST 数据源（契约 §3.5，对标 aw-qtui TodoApiStore）。
 *
 * 服务端（aw-server-rust /inbox/todos）只有「任务」一种实体，因此做如下降级映射：
 *  - notes        ↔ content
 *  - dueDate      ← due_date 取前 10 位
 *  - 清单          = 第一个 tag（listId = tag 哈希，无 tag → 0 收集箱）
 *  - lists()      = 虚拟收集箱 + 所有任务 tag 去重 + 本次会话新建的空清单
 *  - 子任务 / 重复  → 不支持（[supportsSubtasks] / [supportsRecurrence] 为 false，UI 隐藏）
 *  - createList   → 空操作（清单随任务 tag 在下一次 load 时自然出现）
 *  - renameList / deleteList → 遍历持有该 tag 的任务逐个 PUT 改 tags
 *  - createTask   → POST（title + tags），带期限时拿到 id 后再补一次 PUT
 *  - 每个写操作    → 完成后全量 load()（无增量、无乐观更新）
 */
class RestTodoSource(context: Context) : TodoSource() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private var mTasks: List<TodoTask> = emptyList()
    private var mLists: List<TodoList> = emptyList()
    /** 本地新建、尚无任务使用的清单名（否则新建的清单会一闪而过） */
    private val pendingLists = LinkedHashSet<String>()

    override var ready: Boolean = false
        private set

    override val label: String get() = "服务器"
    override val supportsSubtasks: Boolean get() = false
    override val supportsRecurrence: Boolean get() = false
    override val supportsLists: Boolean get() = false

    init {
        TodoApi.init(context.applicationContext)
    }

    // ── 快照 ────────────────────────────────────────────

    override fun lists(): List<TodoList> = synchronized(lock) { mLists.map { it.copy() } }

    override fun tasks(): List<TodoTask> = synchronized(lock) { mTasks.map { it.deepCopy() } }

    /** 清单 id → 名称（id 由 tag 哈希派生） */
    fun listName(listId: Long): String? =
        synchronized(lock) { mLists.firstOrNull { it.id == listId }?.name }

    // ── 加载 ────────────────────────────────────────────

    override fun load() {
        scope.launch {
            try {
                val response = TodoApi.service.getTodos()   // 不带 completed → 返回全部（含已完成）
                val tasks = response.map { it.toTask() }
                synchronized(lock) {
                    mTasks = tasks
                    deriveListsLocked()
                    ready = true
                }
                notifyChanged()
            } catch (t: Throwable) {
                reportError("加载失败：${t.message}")
            }
        }
    }

    /** 清单由任务 tag 派生：所有任务 tag 去重 + 本次会话新建的空清单 */
    private fun deriveListsLocked() {
        val names = LinkedHashSet<String>()
        for (t in mTasks) names.addAll(t.tags)
        names.addAll(pendingLists)
        mLists = names.map { name -> TodoList(id = tagToListId(name), name = name) }
    }

    /** 写操作收尾：全量重新拉取（契约 §3.5） */
    private fun reload() {
        load()
    }

    // ── 清单（tag 模拟） ────────────────────────────────

    override fun createList(name: String, color: String) {
        if (name.isBlank()) return
        synchronized(lock) { pendingLists.add(name.trim()) }
        reload()
    }

    override fun renameList(listId: Long, name: String) {
        if (listId <= 0 || name.isBlank()) return
        val oldName = synchronized(lock) { mLists.firstOrNull { it.id == listId }?.name }
            ?: return
        val newName = name.trim()
        val targets = tasks().filter { it.listId == listId }
        scope.launch {
            try {
                for (t in targets) {
                    TodoApi.service.updateTodo(
                        t.id,
                        UpdateTodoPayload(tags = t.serverTags(newName))
                    )
                }
                synchronized(lock) {
                    pendingLists.remove(oldName)
                    pendingLists.add(newName)
                }
                reload()
            } catch (e: Throwable) {
                reportError("重命名清单失败：${e.message}")
            }
        }
    }

    override fun deleteList(listId: Long) {
        if (listId <= 0) return
        val name = synchronized(lock) { mLists.firstOrNull { it.id == listId }?.name } ?: return
        val targets = tasks().filter { it.listId == listId }
        scope.launch {
            try {
                for (t in targets) {
                    // 清掉清单 tag → 任务回落收集箱（契约：清单内任务不删除）
                    TodoApi.service.updateTodo(
                        t.id,
                        UpdateTodoPayload(tags = t.serverTags(null))
                    )
                }
                synchronized(lock) { pendingLists.remove(name) }
                reload()
            } catch (e: Throwable) {
                reportError("删除清单失败：${e.message}")
            }
        }
    }

    // ── 任务 ────────────────────────────────────────────

    override fun createTask(title: String, listId: Long, dueDate: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val tag = listName(listId)
        scope.launch {
            try {
                val created = TodoApi.service.createTodo(
                    CreateTodoPayload(
                        title = trimmed,
                        tags = if (tag != null) listOf(tag) else null,
                    )
                )
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
        val tags = task.serverTags(listName(task.listId))
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
                        tags = tags,
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

    // ── 子任务：服务端不支持，降级为空操作（契约 §3.5） ──

    override fun addSubtask(taskId: Long, title: String) = Unit
    override fun toggleSubtask(taskId: Long, subtaskId: Long) = Unit
    override fun removeSubtask(taskId: Long, subtaskId: Long) = Unit
}
