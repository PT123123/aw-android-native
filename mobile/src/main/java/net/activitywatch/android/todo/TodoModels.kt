package net.activitywatch.android.todo

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import net.activitywatch.android.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Todo 数据模型 —— 对齐 aw-qtui《Todo 功能规格与 API 契约》§1。
 *
 * 三份结构：
 *  - [TodoTask] / [TodoSubtask] / [TodoList]：页面与本地持久化用的领域模型（snake_case 落盘）
 *  - [TodoResponse] / [CreateTodoPayload] / [UpdateTodoPayload]：服务端 /inbox/todos 的 DTO
 *  - 两者之间的转换见文件末尾的映射函数（契约 §3.5）
 *
 * 服务端没有「清单 / 子任务 / 重复」概念，REST 数据源按契约做降级映射：
 *   - 清单 = 第一个 tag（listId = tag 哈希，0 = 收集箱）
 *   - 子任务 / 重复：服务端不支持，UI 按 [TodoSource.supportsSubtasks] / [supportsRecurrence] 隐藏
 */

// ===================== 领域模型 =====================

/** 子任务（契约 §1.2；id 与任务共用同一个分配器，全局唯一） */
data class TodoSubtask(
    var id: Long = 0,
    var title: String = "",
    var completed: Boolean = false,
)

/** 任务（契约 §1.1，键名 snake_case） */
data class TodoTask(
    var id: Long = 0,
    var title: String = "",
    var notes: String = "",
    var listId: Long = 0,                       // 0 = 收集箱
    var tags: MutableList<String> = mutableListOf(),
    var priority: Int = 0,                      // 0 无 / 1 低 / 2 中 / 3 高
    var dueDate: String = "",                   // yyyy-MM-dd，空串 = 无期限
    var completed: Boolean = false,
    var completedAt: String = "",               // 未完成时必须为空串
    var recurrence: String = "",                // "" / daily / weekdays / weekly / monthly
    var createdAt: String = "",
    var updatedAt: String = "",
    var sortOrder: Int = 0,
    var subtasks: MutableList<TodoSubtask> = mutableListOf(),
) {
    fun hasDue(): Boolean = dueDate.isNotBlank()

    fun openSubtaskCount(): Int = subtasks.count { !it.completed }

    /** 深拷贝：写操作前先复制，避免改到 UI 正在持有的快照对象 */
    fun deepCopy(): TodoTask = copy(
        tags = tags.toMutableList(),
        subtasks = subtasks.map { it.copy() }.toMutableList(),
    )
}

/** 清单（契约 §1.3；收集箱是虚拟清单，id = 0，不出现在 lists 列表内） */
data class TodoList(
    var id: Long = 0,
    var name: String = "",
    var color: String = "",                     // hex，空串 = 由名称派生
    var sortOrder: Int = 0,
) {
    /** ARGB 色值：有显式色用显式色，否则按名称派生 */
    val argb: Int
        get() = if (color.isBlank()) colorForString(name) else parseHexColor(color, colorForString(name))
}

/** 侧栏视图（契约 §5.2） */
enum class TodoView { INBOX, TODAY, NEXT7, ALL, LIST }

// ===================== 本地持久化文件结构 =====================

/** todo_local.json 的根结构（契约 §4）；键名 next_id 已经是 snake_case */
internal class TodoFile {
    var lists: MutableList<TodoList> = mutableListOf()
    var tasks: MutableList<TodoTask> = mutableListOf()
    var next_id: Long = 1
}

/** 领域模型用的 Gson：snake_case + 缩进（契约 §1 / §4） */
val todoGson = GsonBuilder()
    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
    .setPrettyPrinting()
    .create()

internal fun newTodoFile(): TodoFile = TodoFile()
internal fun parseTodoFile(json: String): TodoFile =
    todoGson.fromJson(json, TodoFile::class.java) ?: TodoFile()
internal fun writeTodoFile(file: TodoFile): String = todoGson.toJson(file)

// ===================== REST DTO（契约 §3.3） =====================

/** tag → listId（0 保留给收集箱；哈希取非负后映射到 [1, 1000000]） */
fun tagToListId(tag: String): Long =
    if (tag.isEmpty()) 0L else (tag.hashCode() and 0x7fffffff) % 1_000_000L + 1L

/** 服务端 GET /inbox/todos 返回的单条任务 */
data class TodoResponse(
    val id: Long,
    val title: String,
    @SerializedName("content") val content: String? = null,     // 备注 / 描述
    val completed: Boolean = false,
    val priority: Int? = null,
    @SerializedName("due_date") val dueDateRaw: String? = null, // RFC3339
    val tags: List<String> = emptyList(),
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("completed_at") val completedAt: String? = null,
    val version: Long = 0,
    @SerializedName("device_id") val deviceId: String? = null,
    val deleted: Boolean = false,
    @SerializedName("synced_at") val syncedAt: String? = null,
    val conflict: Boolean = false,
) {
    /** RFC3339 → yyyy-MM-dd（契约 §3.5） */
    val dueDate: String?
        get() = dueDateRaw?.take(10)?.takeIf { it.length == 10 }

    /** 清单 id：用第一个 tag 模拟 */
    val listId: Long
        get() = tags.firstOrNull()?.let { tagToListId(it) } ?: 0L
}

/** POST /inbox/todos */
data class CreateTodoPayload(
    val title: String,
    val content: String? = null,
    val priority: Int? = null,
    @SerializedName("due_date") val dueDate: String? = null,
    val tags: List<String>? = null,
    @SerializedName("created_at") val createdAt: String? = null,
)

/** PUT /inbox/todos/{id}：Gson 默认不序列化 null，未提供的字段服务端保持原值 */
data class UpdateTodoPayload(
    val title: String? = null,
    val content: String? = null,
    val completed: Boolean? = null,
    val priority: Int? = null,
    @SerializedName("due_date") val dueDate: String? = null,
    val tags: List<String>? = null,
)

// ===================== 领域模型 ↔ REST DTO（契约 §3.5） =====================

/**
 * 服务端 → 领域模型。
 * 约定（契约 §3.5）：服务端 tags[0] 承载清单，其余为自由标签；
 * 因此领域模型的 listId 由 tags[0] 派生，而 tags 只保留自由标签部分。
 */
fun TodoResponse.toTask(): TodoTask = TodoTask(
    id = id,
    title = title,
    notes = content ?: "",
    listId = listId,
    tags = (if (tags.isEmpty()) emptyList() else tags.drop(1)).toMutableList(),
    priority = priority ?: 0,
    dueDate = dueDate ?: "",
    completed = completed,
    completedAt = completedAt ?: "",
    recurrence = "",                     // 服务端无此字段
    createdAt = createdAt ?: "",
    updatedAt = updatedAt ?: "",
    sortOrder = 0,
    subtasks = mutableListOf(),          // 服务端不支持子任务
)

/** 领域 tags（自由标签，不含清单 tag）+ 清单名 → 提交给服务端的 tags（清单 tag 在前） */
fun TodoTask.serverTags(listName: String?): List<String> =
    if (listName.isNullOrBlank()) tags.toList() else listOf(listName) + tags

/** 日期（yyyy-MM-dd）→ 服务端 RFC3339：当天零点按 UTC 表示 */
fun String.toRfc3339(): String = "${this}T00:00:00Z"

// ===================== 展示与计算工具 =====================

/** 清单调色板（契约 §5.7 的 8 色） */
private val LIST_PALETTE = intArrayOf(
    0xFF4C8BF5.toInt(), 0xFF3FB950.toInt(), 0xFFD29922.toInt(), 0xFFE5534B.toInt(),
    0xFFA371F7.toInt(), 0xFF39C5CF.toInt(), 0xFFF778BA.toInt(), 0xFFE3B341.toInt(),
)

/** 按名称派生稳定的清单颜色 */
fun colorForString(name: String): Int =
    LIST_PALETTE[(name.hashCode() and 0x7fffffff) % LIST_PALETTE.size]

/** 清单调色板（契约 §5.7 的 8 色）：新建清单时挑一个未被占用的颜色 */
fun listPalette(): IntArray = LIST_PALETTE

private fun parseHexColor(hex: String, fallback: Int): Int = try {
    android.graphics.Color.parseColor(hex)
} catch (_: Exception) {
    fallback
}

/** 优先级行内图标（契约 §1.4） */
fun priorityIcon(p: Int): String = when (p) {
    3 -> "▲"
    2 -> "◆"
    1 -> "▼"
    else -> ""
}

/** 优先级颜色（低=绿 / 中=蓝 / 高=红） */
fun priorityColorRes(p: Int): Int = when (p) {
    3 -> R.color.aw_danger
    2 -> R.color.aw_accent
    1 -> R.color.aw_success
    else -> R.color.aw_text_secondary
}

/** 优先级文案 */
fun priorityLabel(p: Int): String = when (p) {
    3 -> "高"
    2 -> "中"
    1 -> "低"
    else -> "无"
}

/** 重复规则 ↔ 文案 */
fun recurrenceLabel(rule: String?): String = when (rule) {
    "daily" -> "每天"
    "weekdays" -> "每个工作日"
    "weekly" -> "每周"
    "monthly" -> "每月"
    else -> "不重复"
}

val RECURRENCE_RULES: List<Pair<String, String>> = listOf(
    "" to "不重复",
    "daily" to "每天",
    "weekdays" to "每个工作日",
    "weekly" to "每周",
    "monthly" to "每月",
)

// ── 日期 ──────────────────────────────────────────────

private val DATE_FMT: SimpleDateFormat
    get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private val ISO_FMT: SimpleDateFormat
    get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

/** 当前时间 ISO（与 Qt ISODateWithMs / Rust to_rfc3339 同格式） */
fun nowIso(): String = ISO_FMT.format(java.util.Date())

/** 今天 / 偏移 N 天的日期串（yyyy-MM-dd，本地时区） */
fun dateStrOffset(days: Int): String {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, days)
    return DATE_FMT.format(c.time)
}

fun todayStr(): String = dateStrOffset(0)

/** 期限徽章文案：今天 / 明天 / 昨天 / M月d日（同年）/ yyyy年M月d日（跨年） */
fun dueLabel(due: String): String {
    if (due.length != 10) return due
    val today = todayStr()
    if (due == today) return "今天"
    if (due == dateStrOffset(1)) return "明天"
    if (due == dateStrOffset(-1)) return "昨天"
    val year = due.substring(0, 4)
    val month = due.substring(5, 7).trimStart('0')
    val day = due.substring(8, 10).trimStart('0')
    return if (year == today.substring(0, 4)) "${month}月${day}日" else "${year}年${month}月${day}日"
}

/** 未完成且已逾期（契约 §5.3：文字与边框变红） */
fun isOverdue(task: TodoTask): Boolean =
    !task.completed && task.hasDue() && task.dueDate < todayStr()

/**
 * 重复推进（契约 §2.4）：返回下一次到期日，空串表示不生成实例。
 * basedOn 为空则取今天；结果早于今天时钳到今天。
 */
fun nextRecurrenceDate(rule: String, basedOn: String): String {
    if (rule.isBlank()) return ""
    val today = todayStr()
    val base = basedOn.takeIf { it.length == 10 } ?: today
    val cal = Calendar.getInstance().apply {
        val d = try {
            DATE_FMT.parse(base)
        } catch (_: Exception) {
            null
        } ?: return today
        time = d
    }
    when (rule) {
        "daily" -> cal.add(Calendar.DAY_OF_YEAR, 1)
        "weekdays" -> {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            ) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        "weekly" -> cal.add(Calendar.DAY_OF_YEAR, 7)
        "monthly" -> cal.add(Calendar.MONTH, 1)
        else -> return ""
    }
    val next = DATE_FMT.format(cal.time)
    return if (next < today) today else next
}
