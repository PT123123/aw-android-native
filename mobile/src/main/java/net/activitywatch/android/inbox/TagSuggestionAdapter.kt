package net.activitywatch.android.inbox

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.R

class TagSuggestionAdapter(
    private val context: Context,
    private val onTagSelected: (String) -> Unit,
) : RecyclerView.Adapter<TagSuggestionAdapter.TagViewHolder>() {

    private var suggestions: List<String> = emptyList()
    private var prefix: String = ""

    fun setData(prefix: String, suggestions: List<String>) {
        this.prefix = prefix
        this.suggestions = suggestions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_tag_suggestion, parent, false)
        return TagViewHolder(view)
    }

    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        val tag = suggestions[position]
        holder.text.text = tag

        // 根据前缀给匹配部分着色（用户输入的前缀部分高亮）
        if (prefix.isNotEmpty() && tag.startsWith(prefix)) {
            val span = android.text.SpannableString(tag)
            span.setSpan(
                android.text.style.ForegroundColorSpan(
                    android.graphics.Color.parseColor("#AAAAAA")
                ),
                0,
                prefix.length,
                android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            holder.text.text = span
        }

        holder.itemView.setOnClickListener {
            onTagSelected(tag)
        }
    }

    override fun getItemCount(): Int = suggestions.size

    class TagViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.tagSuggestionText)
    }
}
