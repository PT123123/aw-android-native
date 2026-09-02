package net.activitywatch.android.todo

/**
 * Todo 数据模型 —— 字段与服务端 aw-inbox-rust（feature/inbox 分支）的
 * TodoResponse / CreateTodoPayload / UpdateTodoPayload 契约对齐。
 *
 * 清单（List）用 tag 模拟（对齐 aw-qtui TodoApiStore）：
 *   - listId = tag 字符串哈希（正整数），listId == 0 表示「收集箱」（无清单归属）
 *   - 清单本身没有服务端实体，由任务 tags 自动派生
 * 截止日期 due_date 为 ISO 8601，展示时只取前 10 位日期（yyyy-MM-dd）。
 */

/** tag → listId（0 保留给收集箱；哈希取非负后映射到 [1, 1000000]） */
fun tagToListId(tag: String): Long =
    if (tag.isEmpty()) 0L else (tag.hashCode() and 0x7fffffff) % 1_000_000L + 1L

/** 服务端 GET /inbox/todos 返回的单条任务 */
data class TodoResponse(
    val id: Long,
    val title: String,
    val content: String? = null,       // 备注 / 描述
    val completed: Boolean = false,
    val priority: Int? = null,         // 0 无 / 1 低 / 2 中 / 3 高
    val due_date: String? = null,      // ISO 8601（RFC3339）
    val tags: List<String> = emptyList(),
    val created_at: String? = null,
    val updated_at: String? = null,
    val completed_at: String? = null,
    val version: Long = 0,
    val device_id: String? = null,
    val deleted: Boolean = false,
    val synced_at: String? = null,
    val conflict: Boolean = false,
) : java.io.Serializable {
    /** 截止日期只取日期部分 yyyy-MM-dd；非日期/无期限返回 null */
    val dueDate: String? get() = due_date?.take(10)?.takeIf { it.length == 10 }

    /** 清单 id：用第一个 tag 模拟（对齐 aw-qtui） */
    val listId: Long get() = tags.firstOrNull()?.let { tagToListId(it) } ?: 0L
}

/** POST /inbox/todos */
data class CreateTodoPayload(
    val title: String,
    val content: String? = null,
    val priority: Int? = null,
    val due_date: String? = null,
    val tags: List<String>? = null,
    val created_at: String? = null,
)

/** PUT /inbox/todos/{id}：Gson 默认不序列化 null，只传实际修改的字段 */
data class UpdateTodoPayload(
    val title: String? = null,
    val content: String? = null,
    val completed: Boolean? = null,
    val priority: Int? = null,
    val due_date: String? = null,
    val tags: List<String>? = null,
)

/** 侧栏视图（对齐 aw-qtui TodoPage::ViewKind） */
enum class TodoView { INBOX, TODAY, NEXT7, ALL, LIST }

/** 清单（由 tag 派生，供侧栏展示；color 为 ARGB 色值） */
data class TodoListInfo(val id: Long, val name: String, val color: Int)

/** 优先级文案（0 无 / 1 低 / 2 中 / 3 高） */
fun priorityLabel(p: Int?): String = when (p ?: 0) {
    3 -> "高"
    2 -> "中"
    1 -> "低"
    else -> ""
}

/**
 * 日期（yyyy-MM-dd）转服务端要求的 RFC3339：当天本地零点按 UTC 表示。
 * 服务端 due_date 类型为 Option<DateTime<Utc>>，必须传完整时间。
 */
fun String.toRfc3339(): String = "${this}T00:00:00Z"

/** 重复规则文案（预留，当前服务端未实现） */
fun recurrenceLabel(rule: String?): String = when (rule) {
    "daily" -> "每天"
    "weekdays" -> "每个工作日"
    "weekly" -> "每周"
    "monthly" -> "每月"
    else -> "不重复"
}
