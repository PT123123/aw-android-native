package net.activitywatch.android.inbox

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.activitywatch.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 标签自动提示下拉视图，显示在 EditText 下方。
 * 当用户输入 # 后根据已输入的前缀异步提示匹配的标签。
 */
class TagSuggestionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val recyclerView: RecyclerView
    private val adapter: TagSuggestionAdapter

    init {
        orientation = VERTICAL
        visibility = View.GONE

        val view = LayoutInflater.from(context)
            .inflate(R.layout.inbox_tag_suggestions, this, true)
        recyclerView = view.findViewById(R.id.tagSuggestionsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.isNestedScrollingEnabled = false
        adapter = TagSuggestionAdapter(context) { selectedTag ->
            onTagSelected(selectedTag)
        }
        recyclerView.adapter = adapter
    }

    /**
     * 显示匹配的标签建议，根据前缀过滤。
     * @param prefix 用户已输入的标签前缀（不含 #）
     * @param suggestions 匹配的标签列表（已按匹配度排序，最多10个）
     */
    fun show(prefix: String, suggestions: List<String>) {
        if (suggestions.isEmpty()) {
            hide()
            return
        }
        adapter.setData(prefix, suggestions)
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun onTagSelected(tag: String) {
        // 回调给使用者处理（插入到 EditText）
        listener?.invoke(tag)
        hide()
    }

    var listener: ((String) -> Unit)? = null
}
