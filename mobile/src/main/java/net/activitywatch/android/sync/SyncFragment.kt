package net.activitywatch.android.sync

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import androidx.core.view.GravityCompat
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
// 配对与设备 / 设置 两个可折叠面板，数据来自本机 Rust server 的 /api/0/sync
class SyncFragment : Fragment(), SyncRowsAdapter.Actions {

    private var _binding: FragmentSyncBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: SyncViewModel
    private lateinit var rowsAdapter: SyncRowsAdapter

    private var settingsHydrated = false
    private var discoveryMethod = "broadcast"

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

        // 实验性 WiFi 热点传输：无路由器 / 局域网时的点对点同步
        binding.btnWifiTransfer.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, WifiTransferFragment())
                .addToBackStack(null)
                .commit()
        }

        setupSettingsControls()

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

    // ==================== 渲染 ====================

    private fun render(s: SyncUiState) {
        if (_binding == null) return
        binding.loading.visibility = if (s.initialLoading) View.VISIBLE else View.GONE
        binding.content.visibility = if (s.initialLoading) View.GONE else View.VISIBLE
        if (s.initialLoading) return

        renderDevices(s)
        renderSettings(s)
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
