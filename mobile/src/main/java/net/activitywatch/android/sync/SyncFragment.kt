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

        // 标题栏刷新 = 立即同步：对全部已配对设备触发一次同步
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_sync_now) {
                viewModel.syncAllPaired()
                true
            } else {
                false
            }
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
        // 总开关：开启/关闭局域网同步
        binding.cfgEnabled.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                val current = try { viewModel.state.value.config } catch (_: Exception) { null }
                val base = current ?: SyncConfig()
                viewModel.saveConfig(base.copy(enabled = checked))
            }
        }

        // 进入详细设置页
        binding.btnSyncSettings.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SyncSettingsFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清空所有配对信息")
                .setMessage("确定要清空所有配对信息吗？\n此操作将移除全部已配对与已发现的设备，且不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定清空") { _, _ -> viewModel.clearAllDevices() }
                .show()
        }
    }

    // 详细设置已迁移至 SyncSettingsFragment

    // ==================== 渲染 ====================

    private fun render(s: SyncUiState) {
        if (_binding == null) return
        binding.loading.visibility = if (s.initialLoading) View.VISIBLE else View.GONE
        binding.content.visibility = if (s.initialLoading) View.GONE else View.VISIBLE
        if (s.initialLoading) return

        renderDevices(s)
        renderSettings(s)
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
        }
    }

    // ==================== SyncRowsAdapter.Actions ====================

    override fun onInitiatePair(device: Device) = viewModel.initiatePair(device.id)

    override fun onAcceptPair(device: Device) = viewModel.acceptPair(device.id)

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
