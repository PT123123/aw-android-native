package net.activitywatch.android.sync

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import net.activitywatch.android.R

// 「配对与设备」面板的异构行
sealed class SyncRow {
    data class Banner(val running: Boolean, val udpPort: Int?, val listenPort: Int?) : SyncRow()
    data class SelfAddress(val device: Device?) : SyncRow()
    object Divider : SyncRow()
    data class SectionTitle(val text: String) : SyncRow()
    data class Empty(val text: String) : SyncRow()
    data class Discovered(val device: Device) : SyncRow()
    data class Paired(
        val device: Device,
        val stats: DeviceSyncStats?,
        val conflicts: List<ConflictSummary>,
        val expanded: Boolean,
        val busy: Boolean,
        val renaming: Boolean
    ) : SyncRow()
}

class SyncRowsAdapter(private val actions: Actions) :
    ListAdapter<SyncRow, RecyclerView.ViewHolder>(DIFF) {

    interface Actions {
        fun onInitiatePair(device: Device)
        fun onAcceptPair(device: Device)
        fun onRemove(device: Device)
        fun onStartRename(device: Device)
        fun onCommitRename(device: Device, alias: String)
        fun onCancelRename()
        fun onToggleDetails(device: Device)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SyncRow.Banner -> TYPE_BANNER
        is SyncRow.SelfAddress -> TYPE_SELF
        is SyncRow.Divider -> TYPE_DIVIDER
        is SyncRow.SectionTitle -> TYPE_SECTION
        is SyncRow.Empty -> TYPE_EMPTY
        is SyncRow.Discovered -> TYPE_DISCOVERED
        is SyncRow.Paired -> TYPE_PAIRED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BANNER -> BannerVH(inflater.inflate(R.layout.item_sync_banner, parent, false))
            TYPE_SELF -> SelfVH(inflater.inflate(R.layout.item_sync_self_address, parent, false))
            TYPE_DIVIDER -> object : RecyclerView.ViewHolder(
                inflater.inflate(R.layout.item_sync_divider, parent, false)
            ) {}
            TYPE_SECTION -> SectionVH(inflater.inflate(R.layout.item_sync_section_title, parent, false))
            TYPE_EMPTY -> EmptyVH(inflater.inflate(R.layout.item_sync_empty, parent, false))
            TYPE_DISCOVERED -> DiscoveredVH(inflater.inflate(R.layout.item_sync_device_discovered, parent, false))
            else -> PairedVH(inflater.inflate(R.layout.item_sync_device_paired, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SyncRow.Banner -> (holder as BannerVH).bind(row)
            is SyncRow.SelfAddress -> (holder as SelfVH).bind(row.device)
            is SyncRow.Divider -> Unit
            is SyncRow.SectionTitle -> (holder as SectionVH).bind(row.text)
            is SyncRow.Empty -> (holder as EmptyVH).bind(row.text)
            is SyncRow.Discovered -> (holder as DiscoveredVH).bind(row.device)
            is SyncRow.Paired -> (holder as PairedVH).bind(row)
        }
    }

    // ==================== ViewHolders ====================

    private inner class BannerVH(view: View) : RecyclerView.ViewHolder(view) {
        private val root: View = view.findViewById(R.id.bannerRoot)
        private val badge: TextView = view.findViewById(R.id.bannerBadge)
        private val text: TextView = view.findViewById(R.id.bannerText)

        fun bind(row: SyncRow.Banner) {
            val ctx = itemView.context
            val color = color(ctx, if (row.running) R.color.sync_success else R.color.sync_warning)
            root.backgroundTintList = ColorStateList.valueOf(withAlpha(color, 0x1F))
            badge.backgroundTintList = ColorStateList.valueOf(withAlpha(color, 0x40))
            badge.setTextColor(color)
            badge.text = if (row.running) "● 运行中" else "○ 未开启"
            text.text = if (row.running) {
                "UDP 广播发现运行中，同网段设备将自动互相发现（UDP ${row.udpPort ?: "-"} / HTTP ${row.listenPort ?: "-"}）"
            } else {
                "局域网同步未开启 —— 请先在下方「设置」中打开开关并保存"
            }
        }
    }

    private class SelfVH(view: View) : RecyclerView.ViewHolder(view) {
        private val address: TextView = view.findViewById(R.id.selfAddress)
        private val detail: TextView = view.findViewById(R.id.selfDetail)

        fun bind(device: Device?) {
            val ctx = itemView.context
            if (device == null || device.ip.isEmpty() || SyncFormatters.isLoopback(device.ip)) {
                address.visibility = View.GONE
                detail.text = "未获取到局域网 IP（请检查 Wi-Fi 连接）"
                detail.setTextColor(ContextCompat.getColor(ctx, R.color.sync_warning))
            } else {
                address.visibility = View.VISIBLE
                address.text = "${device.ip}:${device.port}"
                detail.text = "(${device.ipIface ?: "未知网卡"}) (ID: ${device.id.ifEmpty { "-" }})"
                detail.setTextColor(ContextCompat.getColor(ctx, R.color.inbox_sub))
            }
        }
    }

    private class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.sectionTitle)
        fun bind(text: String) {
            title.text = text
        }
    }

    private class EmptyVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.emptyText)
        fun bind(content: String) {
            text.text = content
        }
    }

    private inner class DiscoveredVH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.deviceName)
        private val meta: TextView = view.findViewById(R.id.deviceMeta)
        private val badge: TextView = view.findViewById(R.id.onlineBadge)
        private val btnPair: MaterialButton = view.findViewById(R.id.btnPair)

        fun bind(device: Device) {
            name.text = device.displayName
            meta.text = "${device.ip}:${device.port} · ${SyncFormatters.deviceTypeLabel(device.deviceKind)}"
            bindOnlineBadge(badge, device.isEffectivelyOnline)
            btnPair.isEnabled = true
            if (device.incomingPairRequest) {
                btnPair.text = "接受配对"
                btnPair.backgroundTintList = ColorStateList.valueOf(color(itemView.context, R.color.sync_success))
                btnPair.setOnClickListener { actions.onAcceptPair(device) }
            } else {
                btnPair.text = "发起配对"
                btnPair.backgroundTintList = ColorStateList.valueOf(color(itemView.context, R.color.sync_link))
                btnPair.setOnClickListener { actions.onInitiatePair(device) }
            }
        }
    }

    private inner class PairedVH(view: View) : RecyclerView.ViewHolder(view) {
        private val selfTag: TextView = view.findViewById(R.id.selfTag)
        private val name: TextView = view.findViewById(R.id.deviceName)
        private val meta: TextView = view.findViewById(R.id.deviceMeta)
        private val badge: TextView = view.findViewById(R.id.onlineBadge)
        private val lastSync: TextView = view.findViewById(R.id.lastSync)
        private val renameRow: View = view.findViewById(R.id.renameRow)
        private val renameInput: EditText = view.findViewById(R.id.renameInput)
        private val btnRenameOk: MaterialButton = view.findViewById(R.id.btnRenameOk)
        private val btnRenameCancel: MaterialButton = view.findViewById(R.id.btnRenameCancel)
        private val actionsRow: View = view.findViewById(R.id.actionsRow)
        private val btnRemove: MaterialButton = view.findViewById(R.id.btnRemove)
        private val btnRename: MaterialButton = view.findViewById(R.id.btnRename)
        private val summaryRow: View = view.findViewById(R.id.summaryRow)
        private val summaryText: TextView = view.findViewById(R.id.summaryText)
        private val btnDetails: MaterialButton = view.findViewById(R.id.btnDetails)
        private val detailsCard: View = view.findViewById(R.id.detailsCard)
        private val tvLocalCount: TextView = view.findViewById(R.id.tvLocalCount)
        private val tvRemoteCount: TextView = view.findViewById(R.id.tvRemoteCount)
        private val tvDataDiff: TextView = view.findViewById(R.id.tvDataDiff)
        private val tvLastSync: TextView = view.findViewById(R.id.tvLastSync)
        private val tvLastFullSync: TextView = view.findViewById(R.id.tvLastFullSync)
        private val tvSyncFreq: TextView = view.findViewById(R.id.tvSyncFreq)
        private val conflictsSection: View = view.findViewById(R.id.conflictsSection)
        private val conflictsTitle: TextView = view.findViewById(R.id.conflictsTitle)
        private val conflictsList: LinearLayout = view.findViewById(R.id.conflictsList)
        private val tvLastError: TextView = view.findViewById(R.id.tvLastError)
        private val tvLastErrorAt: TextView = view.findViewById(R.id.tvLastErrorAt)

        fun bind(row: SyncRow.Paired) {
            val d = row.device
            selfTag.visibility = if (d.isSelf) View.VISIBLE else View.GONE
            name.text = d.displayName
            meta.text = "ID: ${d.id} | ${d.ip}:${d.port} · ${SyncFormatters.deviceTypeLabel(d.deviceKind)}"
            bindOnlineBadge(badge, d.isEffectivelyOnline)
            if (d.lastSyncAt != null) {
                lastSync.visibility = View.VISIBLE
                lastSync.text = "上次同步: ${SyncFormatters.formatTime(d.lastSyncAt)}"
            } else {
                lastSync.visibility = View.GONE
            }

            // 重命名态
            if (row.renaming) {
                renameRow.visibility = View.VISIBLE
                actionsRow.visibility = View.GONE
                if (!renameInput.hasFocus()) {
                    renameInput.setText(d.alias ?: d.name)
                }
                btnRenameOk.isEnabled = !row.busy
                btnRenameCancel.isEnabled = !row.busy
                btnRenameOk.setOnClickListener { actions.onCommitRename(d, renameInput.text.toString()) }
                btnRenameCancel.setOnClickListener { actions.onCancelRename() }
            } else {
                renameRow.visibility = View.GONE
                actionsRow.visibility = if (d.isSelf) View.GONE else View.VISIBLE
                btnRemove.isEnabled = !row.busy
                btnRename.isEnabled = !row.busy
                btnRemove.setOnClickListener { actions.onRemove(d) }
                btnRename.setOnClickListener { actions.onStartRename(d) }
            }

            // 同步摘要与详情
            val stats = row.stats
            if (stats != null && !d.isSelf) {
                summaryRow.visibility = View.VISIBLE
                summaryText.text = listOf(
                    "待同步: ${stats.pendingPushCount} 条",
                    "冲突: ${stats.pendingConflictCount} 条",
                    "总同步: ${stats.totalSyncedCount} 条",
                    SyncFormatters.humanSize(stats.totalSyncedSize)
                ).joinToString(" · ")
                btnDetails.text = if (row.expanded) "收起详情" else "展开详情"
                btnDetails.setOnClickListener { actions.onToggleDetails(d) }
                detailsCard.visibility = if (row.expanded) View.VISIBLE else View.GONE
                if (row.expanded) bindDetails(stats, row.conflicts)
            } else {
                summaryRow.visibility = View.GONE
                detailsCard.visibility = View.GONE
            }
        }

        private fun bindDetails(stats: DeviceSyncStats, conflicts: List<ConflictSummary>) {
            val ctx = itemView.context
            tvLocalCount.text = "本地数据: ${stats.localNoteCount} 条"
            tvRemoteCount.text = "远端数据: ${stats.remoteNoteCount} 条"
            val diff = stats.localNoteCount - stats.remoteNoteCount
            tvDataDiff.text = when {
                diff > 0 -> "数据差异: 本地多 $diff 条"
                diff < 0 -> "数据差异: 远端多 ${-diff} 条"
                else -> "数据差异: 数据一致"
            }
            tvLastSync.text = "上次同步: ${stats.lastSyncAt?.let { SyncFormatters.formatTime(it) } ?: "从未同步"}"
            tvLastFullSync.text = "上次全量同步: ${stats.lastFullSyncAt?.let { SyncFormatters.formatTime(it) } ?: "从未全量同步"}"
            tvSyncFreq.text = "同步频率: ${SyncFormatters.syncFrequencyText(stats.syncFrequencyMinutes)}"

            if (conflicts.isNotEmpty()) {
                conflictsSection.visibility = View.VISIBLE
                conflictsTitle.text = "冲突列表 (${conflicts.size} 条)"
                conflictsList.removeAllViews()
                for (cf in conflicts) {
                    conflictsList.addView(buildConflictRow(ctx, cf))
                }
            } else {
                conflictsSection.visibility = View.GONE
            }

            if (stats.lastError.isNullOrEmpty()) {
                tvLastError.text = "无错误记录"
                tvLastError.setTextColor(color(ctx, R.color.inbox_sub))
                tvLastErrorAt.visibility = View.GONE
            } else {
                tvLastError.text = stats.lastError
                tvLastError.setTextColor(color(ctx, R.color.sync_danger))
                if (stats.lastErrorAt != null) {
                    tvLastErrorAt.visibility = View.VISIBLE
                    tvLastErrorAt.text = SyncFormatters.formatTime(stats.lastErrorAt)
                } else {
                    tvLastErrorAt.visibility = View.GONE
                }
            }
        }

        private fun buildConflictRow(ctx: Context, cf: ConflictSummary): View {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
            }
            val badgeColor = color(ctx, if (cf.resolved) R.color.sync_success else R.color.sync_warning)
            val badgeView = TextView(ctx).apply {
                text = if (cf.resolved) "[已解决]" else "[待解决]"
                textSize = 11f
                setTextColor(badgeColor)
                setBackgroundResource(R.drawable.sync_rounded_4)
                backgroundTintList = ColorStateList.valueOf(withAlpha(badgeColor, 0x33))
                setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
            }
            val textView = TextView(ctx).apply {
                text = "${cf.noteTitle} - ${SyncFormatters.formatTime(cf.detectedAt)}"
                textSize = 12.5f
                setTextColor(color(ctx, R.color.inbox_text))
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(ctx, 6)
                }
            }
            row.addView(badgeView)
            row.addView(textView)
            return row
        }
    }

    // ==================== 工具 ====================

    private fun bindOnlineBadge(badge: TextView, online: Boolean) {
        val badgeColor = color(badge.context, if (online) R.color.sync_success else R.color.inbox_sub)
        badge.text = if (online) "在线" else "离线"
        badge.setTextColor(badgeColor)
        badge.backgroundTintList = ColorStateList.valueOf(withAlpha(badgeColor, 0x33))
    }

    private fun color(ctx: Context, resId: Int): Int = ContextCompat.getColor(ctx, resId)

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val TYPE_BANNER = 0
        private const val TYPE_SELF = 1
        private const val TYPE_DIVIDER = 2
        private const val TYPE_SECTION = 3
        private const val TYPE_EMPTY = 4
        private const val TYPE_DISCOVERED = 5
        private const val TYPE_PAIRED = 6

        private val DIFF = object : DiffUtil.ItemCallback<SyncRow>() {
            override fun areItemsTheSame(oldItem: SyncRow, newItem: SyncRow): Boolean = when {
                oldItem is SyncRow.Banner && newItem is SyncRow.Banner -> true
                oldItem is SyncRow.SelfAddress && newItem is SyncRow.SelfAddress -> true
                oldItem is SyncRow.Divider && newItem is SyncRow.Divider -> true
                oldItem is SyncRow.SectionTitle && newItem is SyncRow.SectionTitle -> oldItem.text == newItem.text
                oldItem is SyncRow.Empty && newItem is SyncRow.Empty -> oldItem.text == newItem.text
                oldItem is SyncRow.Discovered && newItem is SyncRow.Discovered -> oldItem.device.id == newItem.device.id
                oldItem is SyncRow.Paired && newItem is SyncRow.Paired -> oldItem.device.id == newItem.device.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: SyncRow, newItem: SyncRow): Boolean =
                oldItem == newItem
        }
    }
}
