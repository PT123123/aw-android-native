package net.activitywatch.android.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.databinding.ItemTimelineHourBinding

/**
 * 时间线列表的一行：左侧时间标签、中间 Top3（带堆叠条）、右侧总时长。
 *
 * 行的时间粒度由 Fragment 决定——短时间窗按小时、长时间窗按天，
 * 这里只认已经格式化好的 label，不关心粒度。
 */
data class TimelineRow(
    val keyMs: Long,
    val label: String,
    val totalSec: Double,
    val items: List<RankItem>,
)

class TimelineRowAdapter : RecyclerView.Adapter<TimelineRowAdapter.VH>() {
    private var items: List<TimelineRow> = emptyList()

    fun submit(list: List<TimelineRow>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemTimelineHourBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTimelineHourBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.b.tvHour.text = row.label
        holder.b.tvTotal.text = formatDuration(row.totalSec)
        holder.b.tvItems.text = if (row.items.isEmpty()) {
            "无记录"
        } else {
            row.items.joinToString("  ·  ") { "${it.label} ${formatDuration(it.durationSec)}" }
        }

        // 堆叠条：Top3 各占一段，未占满的部分露出 track 色，代表「其他」
        val segs = listOf(holder.b.seg0, holder.b.seg1, holder.b.seg2)
        for (i in segs.indices) {
            val view = segs[i]
            val item = row.items.getOrNull(i)
            if (item == null) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                val lp = view.layoutParams as LinearLayout.LayoutParams
                lp.weight = item.ratio.coerceAtLeast(0.01f)
                view.setBackgroundColor(ActivityPalette.color(view.context, item.colorIndex))
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
