package net.activitywatch.android.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.databinding.ItemBucketRowBinding

/** 数据桶列表行：桶 id、类型（标注是否已聚合）、最后更新时间。 */
class BucketAdapter : RecyclerView.Adapter<BucketAdapter.VH>() {
    private var items: List<BucketRow> = emptyList()

    fun submit(list: List<BucketRow>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemBucketRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemBucketRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.b.tvBucketId.text = it.id
        holder.b.tvBucketType.text = buildString {
            append(it.type ?: "—")
            if (it.aggregated) append("  ·  已聚合")
        }
        holder.b.tvBucketUpdated.text = it.lastUpdated ?: ""
    }

    override fun getItemCount(): Int = items.size
}
