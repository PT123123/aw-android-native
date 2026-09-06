package net.activitywatch.android.inbox

import net.activitywatch.android.todo.CreateTodoPayload
import net.activitywatch.android.todo.TodoApi

/**
 * 笔记转待办：把一条笔记原样迁移为一条 Todo，再删除原笔记。
 * 服务端没有原子接口，按「先建 Todo → 成功后再删笔记」顺序调用，失败按约定兜底：
 *  - 建 Todo 失败 → 不删笔记（原笔记保留，不产生半迁移状态）；
 *  - 删笔记失败 → Todo 已创建、原笔记保留（不重复创建）。
 * 字段映射：title = 笔记标题（无独立标题字段时取正文首行）；content = 正文原文不截断；
 * tags 原样带过去（多级 tag 就是普通字符串）；priority 默认中；不设期限；清单由 tag 决定（无 tag 落收集箱）。
 */
object NoteTodoConverter {

    sealed class Result {
        /** Todo 已创建、原笔记已删除 */
        object Success : Result()

        /** Todo 已创建，但原笔记删除失败（笔记保留） */
        data class TodoCreatedButDeleteFailed(val reason: String) : Result()

        /** Todo 创建失败（原笔记保留） */
        data class CreateFailed(val reason: String) : Result()
    }

    /** todo 模块优先级：0 无 / 1 低 / 2 中 / 3 高 */
    const val DEFAULT_PRIORITY_MEDIUM = 2

    private const val MAX_TITLE_LEN = 50

    /** 笔记没有独立标题字段：取首个非空行，剥掉 markdown 前缀标记与行内强调符号 */
    fun extractTitle(content: String): String {
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() } ?: content
        var title = firstLine
            .replace(Regex("^\\s{0,3}#{1,6}\\s*"), "") // 标题井号
            .replace(Regex("^\\s*[-*+>]\\s+"), "") // 列表符号 / 引用
            .replace(Regex("^\\s*\\d+\\.\\s+"), "") // 有序列表
            .replace(Regex("\\*\\*|\\*|`|~~"), "") // 行内强调
            .trim()
        if (title.isEmpty()) title = content.trim()
        if (title.length > MAX_TITLE_LEN) title = title.take(MAX_TITLE_LEN).trimEnd() + "…"
        return title
    }

    suspend fun convert(note: NoteResponse): Result {
        return try {
            TodoApi.service.createTodo(
                CreateTodoPayload(
                    title = extractTitle(note.content),
                    content = note.content,
                    priority = DEFAULT_PRIORITY_MEDIUM,
                    dueDate = null,
                    tags = note.tags.ifEmpty { null },
                    createdAt = note.created_at,
                ),
            )
            try {
                val resp = LocalInboxApi.service.deleteNote(note.id)
                if (!resp.isSuccessful) {
                    Result.TodoCreatedButDeleteFailed("HTTP ${resp.code()}")
                } else {
                    Result.Success
                }
            } catch (e: Exception) {
                Result.TodoCreatedButDeleteFailed(e.message ?: "未知错误")
            }
        } catch (e: Exception) {
            Result.CreateFailed(e.message ?: "未知错误")
        }
    }
}
