package net.activitywatch.android.sync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.R

/**
 * D1 同步日志列表适配器。
 * 限定条数（默认 20）的最近同步记录，支持点击展开详情。
 */
class D1SyncLogAdapter :
    ListAdapter<SyncLogEntry, D1SyncLogAdapter.VH>(DIFF) {

    private val expandedIds = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_d1_sync_log, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        holder.bind(entry)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dot: View = itemView.findViewById(R.id.statusDot)
        private val tvSummary: TextView = itemView.findViewById(R.id.tvLogSummary)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvLogMeta)
        private val tvDetail: TextView = itemView.findViewById(R.id.tvLogDetail)

        fun bind(entry: SyncLogEntry) {
            val ctx = itemView.context
            val id = entry.id?.toString() ?: entry.timestamp ?: ""

            // 摘要
            tvSummary.text = buildSummary(entry)

            // 元数据：时间 + 数据量
            val meta = buildMeta(entry)
            tvMeta.text = meta

            // 状态点颜色
            val color = when (entry.status) {
                "success" -> R.color.aw_success
                "failed" -> R.color.aw_danger
                "running" -> R.color.aw_accent
                else -> R.color.aw_text_secondary
            }
            dot.backgroundTintList = ContextCompat.getColorStateList(ctx, color)

            // 详情（可展开）
            val detailText = buildDetail(entry)
            if (detailText != null && expandedIds.contains(id)) {
                tvDetail.text = detailText
                tvDetail.visibility = View.VISIBLE
            } else {
                tvDetail.visibility = View.GONE
            }

            // 点击展开/折叠
            itemView.setOnClickListener {
                if (detailText != null) {
                    if (expandedIds.contains(id)) {
                        expandedIds.remove(id)
                    } else {
                        expandedIds.add(id)
                    }
                    onBindViewHolder(this@VH, bindingAdapterPosition)
                }
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SyncLogEntry>() {
            override fun areItemsTheSame(a: SyncLogEntry, b: SyncLogEntry) =
                a.id == b.id && a.timestamp == b.timestamp

            override fun areContentsTheSame(a: SyncLogEntry, b: SyncLogEntry) = a == b
        }

        private fun buildSummary(e: SyncLogEntry): String {
            val pushParts = mutableListOf<String>()
            if (e.dataSize != null && e.dataSize > 0) {
                pushParts.add(formatSize(e.dataSize))
            }
            return when (e.eventType) {
                "sync" -> {
                    val base = "同步 ${e.direction}"
                    if (pushParts.isNotEmpty()) "$base · ${pushParts.joinToString(" · ")}" else base
                }
                "conflict" -> "冲突: ${e.message ?: "版本冲突"}"
                "pairing" -> "配对: ${e.message ?: ""}"
                else -> "${e.eventType}: ${e.message ?: ""}"
            }
        }

        private fun buildMeta(e: SyncLogEntry): String {
            val parts = mutableListOf<String>()
            parts.add(e.timestamp?.take(19)?.replace("T", " ") ?: "")
            if (e.peerId != null) parts.add("设备: ${e.peerId.take(8)}")
            return parts.joinToString(" · ")
        }

        private fun buildDetail(e: SyncLogEntry): String? {
            val sb = StringBuilder()
            if (!e.message.isNullOrBlank()) sb.appendLine(e.message)
            if (e.details?.isNotEmpty() == true) {
                for (d in e.details) {
                    sb.appendLine("• ${d.kind} ${d.action}: ${d.title} ${d.reason ?: ""}")
                }
            }
            return if (sb.isNotBlank()) sb.toString().trim() else null
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            }
        }
    }
}
