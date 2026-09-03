package net.activitywatch.android.sync

import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TableRow
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentSyncDetailsBinding

// 同步详情页：承载原 SyncFragment「显示报文」面板的全部控件与表格，
// 新增能力：点某一行日志 → 展开该报文的 TransferRecord 明细列表。
// 数据共享宿主 Activity 作用域的 SyncViewModel（与 SyncFragment 同实例）。
class SyncDetailsFragment : Fragment() {

    private var _binding: FragmentSyncDetailsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: SyncViewModel

    private var expandedLogId: Long? = null

    private val directionOptions = listOf("" to "全部", "out" to "去向（发出）", "in" to "来向（接收）")
    private val eventTypeOptions = listOf(
        "" to "全部", "discovery" to "发现", "pairing" to "配对", "sync" to "同步", "conflict" to "冲突"
    )
    private val protocolOptions = listOf(
        "" to "全部", "http" to "HTTP", "udp_broadcast" to "UDP 广播", "mdns" to "mDNS"
    )
    private val pageSizeValues = listOf(5, 10, 50)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSyncDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 与 SyncFragment 共享同一个 Activity 作用域的 ViewModel
        viewModel = ViewModelProvider(requireActivity())[SyncViewModel::class.java]

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }

        setupControls()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.poll() }
                launch { viewModel.state.collect { render(it) } }
                launch {
                    viewModel.messages.collect { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ==================== 控件绑定 ====================

    private fun setupControls() {
        binding.cfgRefreshInterval.doAfterTextChanged { s ->
            if (binding.cfgRefreshInterval.isFocused) {
                s?.toString()?.toIntOrNull()?.let { viewModel.setRefreshSeconds(it) }
            }
        }

        bindDropdown(binding.cfgPageSize, pageSizeValues.map { it.toString() }, 1) { index ->
            viewModel.setPageSize(pageSizeValues[index])
        }
        bindDropdown(binding.cfgFilterDirection, directionOptions.map { it.second }, 0) { index ->
            viewModel.setFilterDirection(directionOptions[index].first)
        }
        bindDropdown(binding.cfgFilterEventType, eventTypeOptions.map { it.second }, 0) { index ->
            viewModel.setFilterEventType(eventTypeOptions[index].first)
        }
        bindDropdown(binding.cfgFilterProtocol, protocolOptions.map { it.second }, 0) { index ->
            viewModel.setFilterProtocol(protocolOptions[index].first)
        }

        binding.btnRefreshLogs.setOnClickListener { viewModel.refreshNow() }
        binding.btnClearLogs.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清空报文日志")
                .setMessage("确定要清空所有报文日志吗？\n此操作仅清除调试记录，不影响设备与配对信息。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定清空") { _, _ -> viewModel.clearLogs() }
                .show()
        }
        binding.btnCloseDetails.setOnClickListener { collapseDetails() }
    }

    private fun bindDropdown(field: AutoCompleteTextView, labels: List<String>, initialIndex: Int, onPick: (Int) -> Unit) {
        field.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels))
        field.setText(labels[initialIndex.coerceIn(0, labels.size - 1)], false)
        field.setOnItemClickListener { _, _, position, _ -> onPick(position) }
    }

    // ==================== 渲染 ====================

    private fun render(s: SyncUiState) {
        if (_binding == null) return
        binding.loading.visibility = if (s.initialLoading) View.VISIBLE else View.GONE
        binding.content.visibility = if (s.initialLoading) View.GONE else View.VISIBLE
        if (s.initialLoading) return

        renderControls(s)
        renderLogs(s)
    }

    private fun renderControls(s: SyncUiState) {
        if (!binding.cfgRefreshInterval.isFocused) {
            val current = binding.cfgRefreshInterval.text.toString()
            if (current != s.refreshSeconds.toString()) {
                binding.cfgRefreshInterval.setText(s.refreshSeconds.toString())
            }
        }
        val pageSizeIndex = pageSizeValues.indexOf(s.pageSize).coerceAtLeast(0)
        val pageSizeLabel = pageSizeValues[pageSizeIndex].toString()
        if (binding.cfgPageSize.text.toString() != pageSizeLabel) {
            binding.cfgPageSize.setText(pageSizeLabel, false)
        }
        syncDropdown(binding.cfgFilterDirection, directionOptions, s.filterDirection)
        syncDropdown(binding.cfgFilterEventType, eventTypeOptions, s.filterEventType)
        syncDropdown(binding.cfgFilterProtocol, protocolOptions, s.filterProtocol)
    }

    private fun syncDropdown(field: AutoCompleteTextView, options: List<Pair<String, String>>, value: String) {
        val label = options.firstOrNull { it.first == value }?.second ?: return
        if (field.text.toString() != label) field.setText(label, false)
    }

    private fun renderLogs(s: SyncUiState) {
        if (s.logError != null) {
            binding.logError.visibility = View.VISIBLE
            binding.logError.text = "报文加载失败：${s.logError}"
        } else {
            binding.logError.visibility = View.GONE
        }

        if (s.logs.isEmpty()) {
            binding.logsScroll.visibility = View.GONE
            binding.logsEmpty.visibility = View.VISIBLE
            binding.logsEmpty.text = "暂无报文记录"
            collapseDetails()
        } else {
            binding.logsEmpty.visibility = View.GONE
            binding.logsScroll.visibility = View.VISIBLE
            rebuildLogsTable(s.logs)

            // 若之前展开的日志仍在列表里，保持展开；否则收起
            if (expandedLogId != null && s.logs.none { it.id == expandedLogId }) {
                collapseDetails()
            }
        }
    }

    // ==================== 日志表格 ====================

    private fun rebuildLogsTable(logs: List<SyncLogEntry>) {
        val table = binding.logsTable
        table.removeAllViews()

        val header = TableRow(requireContext())
        for (title in listOf("时间", "方向", "协议", "对端", "阶段", "状态", "消息", "大小")) {
            header.addView(tableCell(title, header = true))
        }
        table.addView(header)

        for (log in logs) {
            val row = TableRow(requireContext())
            row.addView(tableCell(SyncFormatters.formatTime(log.timestamp)))
            row.addView(tableCell(SyncFormatters.directionLabel(log.direction)))
            row.addView(tableCell(SyncFormatters.protocolLabel(log.protocol)))
            row.addView(tableCell(if (!log.peerId.isNullOrEmpty()) log.peerId else "-"))
            row.addView(tableCell(SyncFormatters.eventLabel(log.eventType)))
            row.addView(statusCell(log.status))
            row.addView(tableCell(if (!log.message.isNullOrEmpty()) log.message else "-", maxWidthDp = 260))
            row.addView(tableCell(SyncFormatters.humanSize(log.dataSize)))

            // 有明细的日志行可点击展开
            val records = log.details
            if (!records.isNullOrEmpty()) {
                row.isClickable = true
                row.setBackgroundResource(R.drawable.nav_item_bg)
                row.setOnClickListener { toggleDetails(log) }
            } else {
                // 无明细的行保持默认背景，不可点击
                row.isClickable = false
            }

            table.addView(row)
        }
    }

    private fun tableCell(text: String, header: Boolean = false, maxWidthDp: Int? = null): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 12.5f
            setTypeface(typeface, if (header) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_primary))
            setPadding(dp(7), dp(8), dp(7), dp(8))
            maxWidthDp?.let { maxWidth = dp(it) }
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
        }
    }

    private fun statusCell(status: String): TextView {
        val ctx = requireContext()
        val color = when (status) {
            "success" -> ContextCompat.getColor(ctx, R.color.aw_success)
            "failed" -> ContextCompat.getColor(ctx, R.color.aw_danger)
            else -> ContextCompat.getColor(ctx, R.color.aw_text_secondary)
        }
        return TextView(ctx).apply {
            text = status
            textSize = 11.5f
            setTextColor(color)
            setBackgroundResource(R.drawable.sync_rounded_4)
            backgroundTintList = ColorStateList.valueOf((color and 0x00FFFFFF) or 0x33000000)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            val params = TableRow.LayoutParams()
            params.setMargins(0, dp(4), 0, dp(4))
            layoutParams = params
        }
    }

    // ==================== 明细展开/收起 ====================

    private fun toggleDetails(log: SyncLogEntry) {
        if (expandedLogId == log.id) {
            collapseDetails()
        } else {
            expandDetails(log)
        }
    }

    private fun expandDetails(log: SyncLogEntry) {
        expandedLogId = log.id
        val records = log.details ?: return

        binding.detailsPanel.visibility = View.VISIBLE
        binding.detailsSummary.text =
            "${SyncFormatters.formatTime(log.timestamp)} · ${SyncFormatters.eventLabel(log.eventType)} · " +
                "${records.size} 条记录"

        val list = binding.detailsList
        list.removeAllViews()

        val counts = records.groupingBy { it.action }.eachCount()
        val summaryParts = mutableListOf<String>()
        counts.forEach { (action, n) -> summaryParts.add("${actionLabel(action)} $n") }
        binding.detailsSummary.text = binding.detailsSummary.text.toString() +
            "（${summaryParts.joinToString(" / ")}）"

        for (r in records) {
            list.addView(detailRow(r))
        }
    }

    private fun collapseDetails() {
        expandedLogId = null
        binding.detailsPanel.visibility = View.GONE
        binding.detailsList.removeAllViews()
    }

    private fun detailRow(r: SyncTransferRecord): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        val badge = TextView(ctx).apply {
            text = actionLabel(r.action)
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, actionColor(r.action)))
            setBackgroundResource(R.drawable.sync_rounded_4)
            backgroundTintList = ColorStateList.valueOf(
                (ContextCompat.getColor(ctx, actionColor(r.action)) and 0x00FFFFFF) or 0x22000000
            )
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

        val title = TextView(ctx).apply {
            text = if (r.title.isNotEmpty()) r.title else r.logicalKey
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
        }

        val kind = TextView(ctx).apply {
            text = kindLabel(r.kind)
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.aw_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(badge)
        row.addView(title)
        row.addView(kind)
        return row
    }

    private fun actionLabel(action: String): String = when (action) {
        "created" -> "新增"
        "updated" -> "更新"
        "deleted" -> "删除"
        "archived" -> "归档"
        "ignored_dup" -> "跳过(重复)"
        "ignored_stale" -> "跳过(过期)"
        else -> action
    }

    private fun actionColor(action: String): Int = when (action) {
        "created" -> R.color.aw_success
        "updated" -> R.color.aw_accent
        "deleted", "archived" -> R.color.aw_danger
        else -> R.color.aw_text_secondary
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "note" -> "Note"
        "todo" -> "Todo"
        "bucket" -> "Bucket"
        "event" -> "Event"
        else -> kind
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
