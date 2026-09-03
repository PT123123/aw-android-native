package net.activitywatch.android.sync

import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentSyncBinding
import net.activitywatch.android.sync.wifi.WifiTransferFragment

// 局域网同步页：
// 配对与设备 / 设置 / 显示报文 三个可折叠面板，数据来自本机 Rust server 的 /api/0/sync
class SyncFragment : Fragment(), SyncRowsAdapter.Actions {

    private var _binding: FragmentSyncBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: SyncViewModel
    private lateinit var rowsAdapter: SyncRowsAdapter

    private var settingsHydrated = false
    private var discoveryMethod = "broadcast"

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
        _binding = FragmentSyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel = ViewModelProvider(this)[SyncViewModel::class.java]

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }

        rowsAdapter = SyncRowsAdapter(this)
        binding.devicesList.layoutManager = LinearLayoutManager(requireContext())
        binding.devicesList.adapter = rowsAdapter

        setupPanel(binding.peersHeader, binding.peersContent, binding.peersChevron, initiallyExpanded = true)
        setupPanel(binding.settingsHeader, binding.settingsContent, binding.settingsChevron, initiallyExpanded = false)
        setupPanel(binding.logsHeader, binding.logsContent, binding.logsChevron, initiallyExpanded = false)

        // 实验性 WiFi 热点传输：无路由器 / 局域网时的点对点同步
        binding.btnWifiTransfer.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, WifiTransferFragment())
                .addToBackStack(null)
                .commit()
        }

        setupSettingsControls()
        setupLogsControls()

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
        binding.devicesList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    // ==================== 面板折叠 ====================

    private fun setupPanel(header: View, content: View, chevron: ImageView, initiallyExpanded: Boolean) {
        content.visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        chevron.rotation = if (initiallyExpanded) 180f else 0f
        header.setOnClickListener {
            val expand = content.visibility != View.VISIBLE
            content.visibility = if (expand) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (expand) 180f else 0f).setDuration(150).start()
        }
    }

    // ==================== 设置面板 ====================

    private fun setupSettingsControls() {
        binding.btnSaveConfig.setOnClickListener { saveConfigFromInputs() }
        binding.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清空所有配对信息")
                .setMessage("确定要清空所有配对信息吗？\n此操作将移除全部已配对与已发现的设备，且不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定清空") { _, _ -> viewModel.clearAllDevices() }
                .show()
        }
    }

    private fun setupDiscoveryDropdown(current: String) {
        val entries = mutableListOf(
            "broadcast" to "广播 / mDNS + UDP（已实现）",
            "poll" to "轮询遍历（待实现）"
        )
        // 服务端若返回未列出的方式（如 mdns），补一个条目避免下拉断言
        if (entries.none { it.first == current }) entries.add(current to current)
        discoveryMethod = current
        val labels = entries.map { it.second }
        binding.cfgDiscoveryMethod.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        )
        val index = entries.indexOfFirst { it.first == current }.coerceAtLeast(0)
        binding.cfgDiscoveryMethod.setText(labels[index], false)
        binding.cfgDiscoveryMethod.setOnItemClickListener { _, _, position, _ ->
            discoveryMethod = entries[position].first
        }
    }

    private fun saveConfigFromInputs() {
        val udp = binding.cfgUdpPort.text.toString().toIntOrNull()
        if (udp == null || udp < 10000 || udp > 65535) {
            binding.tilUdpPort.error = "端口需在 10000 ~ 65535 之间"
            return
        }
        binding.tilUdpPort.error = null
        val probe = binding.cfgProbeInterval.text.toString().toIntOrNull()
        if (probe == null || probe < 2 || probe > 3600) {
            binding.tilProbeInterval.error = "间隔需在 2 ~ 3600 秒之间"
            return
        }
        binding.tilProbeInterval.error = null
        viewModel.saveConfig(
            SyncConfig(
                enabled = binding.cfgEnabled.isChecked,
                httpEnabled = binding.cfgHttp.isChecked,
                discoveryMethod = discoveryMethod,
                listenPort = viewModel.state.value.config?.listenPort ?: 5600,
                udpPort = udp,
                syncInbox = binding.cfgSyncInbox.isChecked,
                syncActivity = binding.cfgSyncActivity.isChecked,
                syncTodo = binding.cfgSyncTodo.isChecked,
                selfAlias = binding.cfgSelfAlias.text.toString().trim(),
                probeInterval = probe
            )
        )
    }

    // ==================== 报表面板 ====================

    private fun setupLogsControls() {
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
                .setMessage("确定要清空所有报文日志吗？\n此操作仅清除「显示报文」里的调试记录，不影响设备与配对信息。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定清空") { _, _ -> viewModel.clearLogs() }
                .show()
        }
    }

    private fun bindDropdown(field: AutoCompleteTextView, labels: List<String>, initialIndex: Int, onPick: (Int) -> Unit) {
        field.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels))
        field.setText(labels[initialIndex.coerceIn(0, labels.size - 1)], false)
        field.setOnItemClickListener { _, _, position, _ -> onPick(position) }
    }

    private fun syncDropdown(field: AutoCompleteTextView, options: List<Pair<String, String>>, value: String) {
        val label = options.firstOrNull { it.first == value }?.second ?: return
        if (field.text.toString() != label) field.setText(label, false)
    }

    // ==================== 渲染 ====================

    private fun render(s: SyncUiState) {
        if (_binding == null) return
        binding.loading.visibility = if (s.initialLoading) View.VISIBLE else View.GONE
        binding.content.visibility = if (s.initialLoading) View.GONE else View.VISIBLE
        if (s.initialLoading) return

        renderDevices(s)
        renderSettings(s)
        renderLogs(s)
        binding.btnSaveConfig.isEnabled = !s.savingConfig
    }

    private fun renderDevices(s: SyncUiState) {
        val rows = mutableListOf<SyncRow>()
        rows.add(SyncRow.Banner(s.status?.discoveryRunning == true, s.status?.udpPort, s.status?.listenPort))
        rows.add(SyncRow.SelfAddress(s.selfDevice))
        rows.add(SyncRow.Divider)
        rows.add(SyncRow.SectionTitle("已发现未配对的设备"))
        val discovered = s.discoveredDevices
        if (discovered.isEmpty()) {
            rows.add(SyncRow.Empty("未发现设备 —— 确保双方已开启「局域网同步」并处于同一网络。"))
        } else {
            discovered.forEach { rows.add(SyncRow.Discovered(it)) }
        }
        rows.add(SyncRow.Divider)
        rows.add(SyncRow.SectionTitle("已配对设备"))
        val paired = s.pairedDevices
        if (paired.isEmpty()) {
            rows.add(SyncRow.Empty("尚无已配对设备。"))
        } else {
            paired.forEach { d ->
                rows.add(
                    SyncRow.Paired(
                        device = d,
                        stats = s.deviceStats[d.id],
                        conflicts = s.deviceConflicts[d.id] ?: emptyList(),
                        expanded = d.id in s.expandedDevices,
                        busy = d.id in s.busyDevices,
                        renaming = s.renamingDeviceId == d.id
                    )
                )
            }
        }
        rowsAdapter.submitList(rows)
    }

    private fun renderSettings(s: SyncUiState) {
        val cfg = s.config ?: return
        if (!settingsHydrated) {
            settingsHydrated = true
            binding.cfgEnabled.isChecked = cfg.enabled
            binding.cfgHttp.isChecked = cfg.httpEnabled
            setupDiscoveryDropdown(cfg.discoveryMethod)
            binding.cfgListenPort.setText(cfg.listenPort.toString())
            binding.cfgUdpPort.setText(cfg.udpPort.toString())
            binding.cfgProbeInterval.setText(cfg.probeInterval.toString())
            binding.cfgSyncInbox.isChecked = cfg.syncInbox
            binding.cfgSyncActivity.isChecked = cfg.syncActivity
            binding.cfgSyncTodo.isChecked = cfg.syncTodo
            binding.cfgSelfAlias.setText(cfg.selfAlias)
        }
    }

    private fun renderLogs(s: SyncUiState) {
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

        if (s.logError != null) {
            binding.logError.visibility = View.VISIBLE
            binding.logError.text = "报文加载失败：${s.logError}"
        } else {
            binding.logError.visibility = View.GONE
        }

        if (s.logs.isEmpty()) {
            binding.logsScroll.visibility = View.GONE
            binding.logsEmpty.visibility = View.VISIBLE
            val running = s.status?.discoveryRunning == true
            binding.logsEmpty.text = if (running) {
                "暂无报文记录（发现、配对、同步完成后会在这里显示）"
            } else {
                "局域网同步未开启 — 请在上方「设置」中开启并保存后，广播报文将在此显示"
            }
            binding.logsEmpty.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (running) R.color.inbox_sub else R.color.sync_warning
                )
            )
        } else {
            binding.logsEmpty.visibility = View.GONE
            binding.logsScroll.visibility = View.VISIBLE
            rebuildLogsTable(s.logs)
        }
    }

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
            table.addView(row)
        }
    }

    private fun tableCell(text: String, header: Boolean = false, maxWidthDp: Int? = null): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 12.5f
            setTypeface(typeface, if (header) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(ctx, R.color.inbox_text))
            setPadding(dp(7), dp(8), dp(7), dp(8))
            maxWidthDp?.let { maxWidth = dp(it) }
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
        }
    }

    private fun statusCell(status: String): TextView {
        val ctx = requireContext()
        val color = when (status) {
            "success" -> ContextCompat.getColor(ctx, R.color.sync_success)
            "failed" -> ContextCompat.getColor(ctx, R.color.sync_danger)
            else -> ContextCompat.getColor(ctx, R.color.inbox_sub)
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ==================== SyncRowsAdapter.Actions ====================

    override fun onInitiatePair(device: Device) = viewModel.initiatePair(device.id)

    override fun onAcceptPair(device: Device) = viewModel.acceptPair(device.id)

    override fun onSync(device: Device) = viewModel.syncDevice(device.id)

    override fun onRemove(device: Device) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除设备")
            .setMessage("确定删除该设备（${device.displayName}）？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> viewModel.removeDevice(device.id) }
            .show()
    }

    override fun onStartRename(device: Device) = viewModel.startRename(device.id)

    override fun onCommitRename(device: Device, alias: String) =
        viewModel.saveAlias(device.id, alias, device.alias ?: device.name)

    override fun onCancelRename() = viewModel.cancelRename()

    override fun onToggleDetails(device: Device) = viewModel.toggleDeviceDetails(device.id)
}
