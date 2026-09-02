package net.activitywatch.android.stopwatch

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.dashboard.formatClock
import net.activitywatch.android.dashboard.formatDayLabel
import net.activitywatch.android.dashboard.formatDuration
import net.activitywatch.android.databinding.ItemStopwatchRecordBinding

/** 秒表近期记录列表。 */
class StopwatchRecordAdapter : RecyclerView.Adapter<StopwatchRecordAdapter.VH>() {
    private var items: List<StopwatchRecord> = emptyList()

    fun submit(list: List<StopwatchRecord>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemStopwatchRecordBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemStopwatchRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.b.tvLabel.text = it.label
        holder.b.tvWhen.text = "${formatDayLabel(it.startMs)} ${formatClock(it.startMs)} 起"
        holder.b.tvDuration.text = formatDuration(it.durationSec)
    }

    override fun getItemCount(): Int = items.size
}
