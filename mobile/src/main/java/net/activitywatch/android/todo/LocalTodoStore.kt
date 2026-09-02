package net.activitywatch.android.todo

import android.content.Context
import java.io.File

/**
 * 本地数据源（契约 §2.3 / §2.4 的基线实现，对标 aw-qtui TodoStore）。
 *
 * - 内存态 + `filesDir/todo_local.json` 原子写（临时文件 + rename）
 * - 首次运行（lists 与 tasks 同时为空）写入种子数据：3 个清单 + 11 条任务
 * - 完整支持清单 / 子任务 / 重复规则
 * - 写操作同步生效，完成后广播 onChange
 */
class LocalTodoStore(private val context: Context) : TodoSource() {

    private val lock = Any()
    private val file: File get() = File(context.filesDir, FILE_NAME)

    private var mLists = mutableListOf<TodoList>()
    private var mTasks = mutableListOf<TodoTask>()
    private var nextId = 1L

    override var ready: Boolean = false
        private set

    override val label: String get() = "本地"

    // ── 加载 / 持久化 ────────────────────────────────────

    override fun load() {
        synchronized(lock) {
            try {
                if (file.exists()) {
                    val text = file.readText()
                    if (text.isNotBlank()) {
                        val parsed = parseTodoFile(text)
                        mLists = parsed.lists
                        mTasks = parsed.tasks
                        nextId = parsed.next_id.coerceAtLeast(1)
                    }
                }
                if (mLists.isEmpty() && mTasks.isEmpty()) {
                    seed()
                    persist()
                }
                ready = true
            } catch (t: Throwable) {
                reportError("本地数据加载失败：${t.message}")
                ready = true
            }
        }
        notifyChanged()
    }

    override fun lists(): List<TodoList> = synchronized(lock) { mLists.map { it.copy() } }

    override fun tasks(): List<TodoTask> = synchronized(lock) { mTasks.map { it.deepCopy() } }

    /** 原子写：临时文件写完后 rename 覆盖（避免写一半被杀导致数据损坏） */
    private fun persist() {
        val tmp = File(file.absolutePath + ".tmp")
        val snapshot = newTodoFile().apply {
            lists = mLists
            tasks = mTasks
            next_id = nextId
        }
        try {
            tmp.writeText(writeTodoFile(snapshot))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // rename 失败（少见）时退化为直接写
                file.writeText(writeTodoFile(snapshot))
                tmp.delete()
            }
        } catch (t: Throwable) {
            reportError("本地数据保存失败：${t.message}")
        }
    }

    private fun nextIdLocked(): Long = nextId++

    /** 写操作统一入口：改内存 → 落盘 → 广播 */
    private fun mutate(block: () -> Unit) {
        synchronized(lock) {
            block()
            persist()
        }
        notifyChanged()
    }

    // ── 清单 ────────────────────────────────────────────

    override fun createList(name: String, color: String) {
        if (name.isBlank()) return
        mutate {
            mLists.add(
                TodoList(
                    id = nextIdLocked(),
                    name = name.trim(),
                    color = color,
                    sortOrder = mLists.size,
                )
            )
        }
    }

    override fun renameList(listId: Long, name: String) {
        if (listId <= 0 || name.isBlank()) return
        mutate {
            mLists.firstOrNull { it.id == listId }?.name = name.trim()
        }
    }

    override fun deleteList(listId: Long) {
        if (listId <= 0) return
        mutate {
            val now = nowIso()
            for (t in mTasks) {
                if (t.listId == listId) {
                    t.listId = 0            // 清单内任务迁回收件箱
                    t.updatedAt = now
                }
            }
            mLists.removeAll { it.id == listId }
        }
    }

    // ── 任务 ────────────────────────────────────────────

    override fun createTask(title: String, listId: Long, dueDate: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        mutate {
            val now = nowIso()
            mTasks.add(
                TodoTask(
                    id = nextIdLocked(),
                    title = trimmed,
                    listId = listId,
                    dueDate = dueDate,
                    createdAt = now,
                    updatedAt = now,
                    sortOrder = (mTasks.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                )
            )
        }
    }

    override fun updateTask(task: TodoTask) {
        mutate {
            val idx = mTasks.indexOfFirst { it.id == task.id }
            if (idx < 0) return@mutate
            val createdAt = mTasks[idx].createdAt         // createdAt 不可被覆盖
            val updated = task.deepCopy().apply {
                this.createdAt = createdAt
                this.updatedAt = nowIso()
                if (!completed) completedAt = ""
            }
            mTasks[idx] = updated
        }
    }

    /** 完成 / 取消完成 / 重复推进状态机（契约 §2.4） */
    override fun setTaskCompleted(taskId: Long, completed: Boolean) {
        mutate {
            val idx = mTasks.indexOfFirst { it.id == taskId }
            if (idx < 0) return@mutate
            val task = mTasks[idx]
            if (completed && !task.completed) {
                val now = nowIso()
                if (task.recurrence.isNotBlank()) {
                    val nextDue = nextRecurrenceDate(task.recurrence, task.dueDate)
                    if (nextDue.isNotEmpty()) {
                        val copy = task.deepCopy().apply {
                            id = nextIdLocked()
                            this.completed = false
                            completedAt = ""
                            dueDate = nextDue
                            subtasks = subtasks.map { it.copy(completed = false) }.toMutableList()
                            createdAt = now
                            updatedAt = now
                        }
                        mTasks.add(copy)
                    }
                }
                task.completed = true
                task.completedAt = now
                task.updatedAt = now
            } else if (!completed && task.completed) {
                task.completed = false
                task.completedAt = ""
                task.updatedAt = nowIso()
            }
        }
    }

    override fun deleteTask(taskId: Long) {
        mutate { mTasks.removeAll { it.id == taskId } }
    }

    // ── 子任务 ──────────────────────────────────────────

    override fun addSubtask(taskId: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        mutate {
            val task = mTasks.firstOrNull { it.id == taskId } ?: return@mutate
            task.subtasks.add(TodoSubtask(id = nextIdLocked(), title = trimmed))
            task.updatedAt = nowIso()
        }
    }

    override fun toggleSubtask(taskId: Long, subtaskId: Long) {
        mutate {
            val task = mTasks.firstOrNull { it.id == taskId } ?: return@mutate
            val st = task.subtasks.firstOrNull { it.id == subtaskId } ?: return@mutate
            st.completed = !st.completed        // 不影响父任务 completed
            task.updatedAt = nowIso()
        }
    }

    override fun removeSubtask(taskId: Long, subtaskId: Long) {
        mutate {
            val task = mTasks.firstOrNull { it.id == taskId } ?: return@mutate
            task.subtasks.removeAll { it.id == subtaskId }
            task.updatedAt = nowIso()
        }
    }

    // ── 种子数据（契约 §4：3 个清单 + 11 条任务，覆盖各视图与重复规则） ──

    private fun seed() {
        val work = TodoList(id = nextIdLocked(), name = "工作", color = "#4c8bf5", sortOrder = 0)
        val life = TodoList(id = nextIdLocked(), name = "个人", color = "#3fb950", sortOrder = 1)
        val study = TodoList(id = nextIdLocked(), name = "学习", color = "#d29922", sortOrder = 2)
        mLists = mutableListOf(work, life, study)

        val today = todayStr()
        val t = { offset: Int -> dateStrOffset(offset) }
        val now = nowIso()

        fun task(
            title: String,
            listId: Long,
            priority: Int = 0,
            due: String = "",
            tags: List<String> = emptyList(),
            recurrence: String = "",
            completed: Boolean = false,
            subtasks: List<Pair<String, Boolean>> = emptyList(),
            notes: String = "",
        ) {
            mTasks.add(
                TodoTask(
                    id = nextIdLocked(),
                    title = title,
                    notes = notes,
                    listId = listId,
                    tags = tags.toMutableList(),
                    priority = priority,
                    dueDate = due,
                    completed = completed,
                    completedAt = if (completed) now else "",
                    recurrence = recurrence,
                    createdAt = now,
                    updatedAt = now,
                    sortOrder = mTasks.size,
                    subtasks = subtasks.map { (name, done) ->
                        TodoSubtask(id = nextIdLocked(), title = name, completed = done)
                    }.toMutableList(),
                )
            )
        }

        task("周报：爬虫项目进度同步", work.id, priority = 2, due = today, tags = listOf("周报"),
            recurrence = "weekly",
            notes = "每周一同步上周采集与反爬进展。",
            subtasks = listOf("整理采集量数据" to true, "同步反爬策略变更" to false))
        task("修复 Inbox 同步冲突", work.id, priority = 3, due = t(-2), tags = listOf("Bug"))
        task("设计稿评审", work.id, priority = 2, due = today)
        task("整理季度 OKR 草稿", work.id, due = t(4))
        task("站会", work.id, priority = 1, due = today, recurrence = "weekdays")
        task("买猫粮", life.id, priority = 2, due = today)
        task("给爸妈打电话", life.id, due = t(-1), recurrence = "weekly")
        task("预约体检", life.id, priority = 1, due = t(5))
        task("每天喝水 2L", life.id, priority = 1, due = today, recurrence = "daily")
        task("读《数据密集型应用系统设计》30 页", study.id, priority = 1, due = t(3))
        task("背单词 50 个", study.id, due = "", recurrence = "daily")
        task("提交季度总结", work.id, due = t(-1), completed = true)
        task("续借图书馆的书", study.id, completed = true)
    }

    companion object {
        const val FILE_NAME = "todo_local.json"
    }
}
