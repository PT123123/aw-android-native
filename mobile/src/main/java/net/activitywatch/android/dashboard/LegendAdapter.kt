package net.activitywatch.android.dashboard

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.databinding.ItemLegendBinding

/**
 * 带分类色块的榜单适配器。
 * 在 Timeline 里当色带图例用，在 Trends 里当整个区间的 Top 应用榜用。
 */
class LegendAdapter : RecyclerView.Adapter<LegendAdapter.VH>() {
    private var items: List<RankItem> = emptyList()

    fun submit(list: List<RankItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemLegendBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemLegendBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.b.tvLabel.text = it.label
        holder.b.tvValue.text = formatDuration(it.durationSec)
        val color = ActivityPalette.color(holder.b.vColor.context, it.colorIndex)
        holder.b.vColor.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 3f * holder.b.vColor.resources.displayMetrics.density
            setColor(color)
        }
    }

    override fun getItemCount(): Int = items.size
}
