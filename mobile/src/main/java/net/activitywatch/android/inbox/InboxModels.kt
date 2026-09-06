package net.activitywatch.android.inbox

import java.io.Serializable

data class NoteResponse(
    val id: Long,
    val content: String,
    val tags: List<String> = emptyList(),
    val created_at: String? = null,
    val updated_at: String? = null,
    val version: Long = 0,
    val device_id: String? = null,
    val deleted: Boolean = false,
    val synced_at: String? = null,
    val conflict: Boolean = false,
) : Serializable {
    // 运行时补充字段（服务器不返回，Gson 反序列化时保持 null）：
    // 本笔记若是评论，指向被评论的原笔记
    var parentId: Long? = null
    // 原笔记正文预览（截断 100 字）
    var parentPreview: String? = null
}

data class NoteRelationResponse(
    val id: Long,
    val source_note_id: Long,
    val target_note_id: Long,
    val relation_type: String = "Comment",
    val created_at: String? = null,
)

data class NoteHistoryItem(
    val id: Long,
    val note_id: Long,
    val content: String,
    val tags: List<String> = emptyList(),
    val version: Long = 0,
    val device_id: String? = null,
    val updated_at: String? = null,
    val snapshot_at: String? = null,
) : Serializable

data class UpsertNotePayload(
    val content: String,
    val tags: List<String>? = null,
    val created_at: String? = null,
)

data class CreateCommentPayload(
    val content: String,
    val tags: List<String>? = null,
)

data class DetailedTag(
    val name: String,
    val count: Long = 0,
    val last_modified: String? = null,
)

/** 层级标签树节点（GET /inbox/tags/tree）：path 为完整路径（如 项目/工作），count 含子孙笔记数 */
data class TagNodeResponse(
    val path: String,
    val count: Long = 0,
    val children: List<TagNodeResponse> = emptyList(),
)

data class TagTreeResponse(
    val tags: List<TagNodeResponse> = emptyList(),
)

/** 把层级 tag 按段拆分（`项目/工作` → [项目, 工作]），空段跳过；单段 tag 返回单元素列表 */
fun tagSegments(tag: String): List<String> =
    tag.split('/').map { it.trim() }.filter { it.isNotEmpty() }

/** 层级 tag 的父路径（`项目/工作/xx` → `项目/工作`；单段返回 null） */
fun tagParentPath(tag: String): String? {
    val segs = tagSegments(tag)
    return if (segs.size <= 1) null else segs.dropLast(1).joinToString("/")
}

/** tag 的最后一段（`项目/工作` → `工作`），用于 chips 等紧凑展示 */
fun tagLastSegment(tag: String): String = tagSegments(tag).lastOrNull() ?: tag

/** 面包屑展示：`项目/工作/xx` → `项目 / 工作 / xx` */
fun formatTagBreadcrumb(tag: String): String = tagSegments(tag).joinToString(" / ")

fun parseTags(content: String): List<String> {
    if (content.isBlank()) return emptyList()
    return Regex("#[^#\\s,，。.！!？?；;：:+]+\\.?").findAll(content)
        .map { it.value.removePrefix("#") }
        .toSet()
        .toList()
}