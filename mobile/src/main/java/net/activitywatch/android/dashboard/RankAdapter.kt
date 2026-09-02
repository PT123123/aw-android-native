package net.activitywatch.android.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.databinding.ItemRankRowBinding

/** 时长排行行：序号+标签、时长、横向占比进度条。复用为应用榜与网站榜。 */
class RankAdapter : RecyclerView.Adapter<RankAdapter.VH>() {
    private var items: List<RankItem> = emptyList()

    fun submit(list: List<RankItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemRankRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRankRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.b.tvRankLabel.text = "${position + 1}. ${it.label}"
        holder.b.tvRankValue.text = formatDuration(it.durationSec)
        holder.b.pbRank.progress = (it.ratio * 100).toInt().coerceIn(0, 100)
    }

    override fun getItemCount(): Int = items.size
}
