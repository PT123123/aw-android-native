package net.activitywatch.android.todo

import net.activitywatch.android.inbox.LocalInboxApi
import net.activitywatch.android.inbox.TagNodeResponse

/**
 * 任务页 # 标签建议辅助（逻辑与 InboxFragment 的标签自动提示一致）。
 *
 * 建议来源：任务已有 tag（优先，按出现频率）∪ 笔记标签树（GET /inbox/tags/tree，失败静默忽略）。
 */
object TodoTagSuggest {

    /** 单个 tag 的使用计数（当前任务快照内出现次数） */
    fun tagCounts(tasks: List<TodoTask>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (t in tasks) {
            for (tag in t.tags) {
                if (tag.isNotBlank()) counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * 从光标位置向左提取标签前缀。
     * 例如："买牛奶 #牛奶"，光标在末尾 → 返回 ("牛奶", 5)
     * '/' 是层级 tag 的段分隔符（如 项目/工作），必须算作前缀的一部分。
     */
    fun extractTagPrefix(text: String, cursorPos: Int): Pair<String, Int> {
        var pos = cursorPos - 1
        var start = -1
        while (pos >= 0) {
            when (text[pos]) {
                '#' -> {
                    start = pos
                    break
                }
                ' ', '\n', '\t', ',', '.', '!', '?', ';', ':', '+' -> break
                else -> pos--
            }
        }
        if (start < 0) return "" to -1
        val tagStart = start + 1 // 跳过 '#'
        val prefix = text.substring(tagStart, cursorPos)
        return prefix to tagStart
    }

    /**
     * 计算匹配 prefix 的建议列表（调用方在协程里调用）。
     * 空前缀 = 刚输入 #：返回全部标签（按使用频率排序）；非空前缀按前缀匹配。
     * 返回已排序、最多 5 条；顺序反转后展示在输入框上方（最相关的贴着输入行）。
     */
    suspend fun suggestions(
        prefix: String,
        taskTags: List<String>,
        counts: Map<String, Int> = emptyMap(),
    ): List<String> {
        val all = LinkedHashSet<String>()
        taskTags.forEach { if (it.isNotBlank()) all.add(it) }
        runCatching { LocalInboxApi.service.getTagTree() }
            .getOrNull()?.tags?.let { collectTree(it, all) }

        val matches = if (prefix.isEmpty()) all.toList() else all.filter { it.startsWith(prefix) }
        return matches
            .sortedWith(
                compareByDescending<String> { counts[it] ?: 0 }
                    .thenByDescending { taskTags.contains(it) }
                    .thenBy { it }
            )
            .take(5)
            .reversed()
    }

    private fun collectTree(nodes: List<TagNodeResponse>, out: LinkedHashSet<String>) {
        for (n in nodes) {
            if (n.path.isNotBlank()) out.add(n.path)
            collectTree(n.children, out)
        }
    }

    // ── 提交时 #tag 解析 ─────────────────────────────────

    /** 快速添加文本里的 #tag 记号（# 后跟非空白/逗号/# 的连续段） */
    private val TAG_TOKEN = Regex("#([^\\s,，#+]+)")

    /**
     * 把 "买牛奶 #购物 #重要" 解析为 ("买牛奶", ["购物", "重要"])：
     * tag 从标题剥离，去重、去首尾空白。纯数字 token（如 "Issue #123"）不算标签。
     */
    fun parseTagTokens(raw: String): Pair<String, List<String>> {
        val tags = LinkedHashSet<String>()
        for (m in TAG_TOKEN.findAll(raw)) {
            val token = m.groupValues[1]
            if (!token.matches(Regex("\\d+"))) {
                tags.add(token)
            }
        }
        var title = TAG_TOKEN.findAll(raw)
            .filter { !it.groupValues[1].matches(Regex("\\d+")) }
            .fold(raw) { acc, m -> acc.replaceFirst(m.value, " ") }
        // 压掉剥离后残留的连续空白
        title = title.replace(Regex("\\s+"), " ").trim()
        return title to tags.toList()
    }
}
