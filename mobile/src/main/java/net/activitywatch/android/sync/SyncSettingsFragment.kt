package net.activitywatch.android.sync

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentSyncSettingsBinding

/**
 * 局域网同步详细设置页。
 *
 * 仅包含 Activity 数据同步参数（发现方式、端口、探测间隔、别名）。
 * Inbox / TODO 同步已迁移至 CF D1 云端，不在本页控制。
 */
class SyncSettingsFragment : Fragment() {

    private var _binding: FragmentSyncSettingsBinding? = null
    private val binding get() = _binding!!

    private val repo = SyncRepository()
    private var discoveryMethod = "broadcast"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSyncSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnSaveConfig.setOnClickListener { saveConfig() }

        loadConfig()
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            repo.call { repo.api.getConfig() }.fold(
                onSuccess = { cfg ->
                    binding.cfgHttp.isChecked = cfg.httpEnabled
                    setupDiscoveryDropdown(cfg.discoveryMethod)
                    binding.cfgUdpPort.setText(cfg.udpPort.toString())
                    binding.cfgProbeInterval.setText(cfg.probeInterval.toString())
                    binding.cfgSelfAlias.setText(cfg.selfAlias)
                },
                onFailure = { showError("加载失败: ${it.message}") }
            )
        }
    }

    private fun setupDiscoveryDropdown(current: String) {
        val entries = listOf(
            "broadcast" to "广播 / mDNS + UDP（已实现）",
            "poll" to "轮询遍历（待实现）"
        )
        if (entries.none { it.first == current }) {
            // 未知方式，补一个
        }
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

    private fun saveConfig() {
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

        binding.btnSaveConfig.isEnabled = false
        binding.btnSaveConfig.text = "保存中..."

        lifecycleScope.launch {
            // 先获取完整配置，只改 LAN 相关字段
            val current = try {
                repo.api.getConfig()
            } catch (e: Exception) {
                SyncConfig()
            }

            val updated = current.copy(
                httpEnabled = binding.cfgHttp.isChecked,
                discoveryMethod = discoveryMethod,
                udpPort = udp,
                probeInterval = probe,
                selfAlias = binding.cfgSelfAlias.text.toString().trim()
            )

            repo.call { repo.api.saveConfig(updated) }.fold(
                onSuccess = {
                    showMsg("✅ 设置已保存")
                },
                onFailure = { showError("保存失败: ${it.message}") }
            )

            binding.btnSaveConfig.isEnabled = true
            binding.btnSaveConfig.text = "保存设置"
        }
    }

    private fun showMsg(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }

    private fun showError(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
            .setBackgroundTint(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.aw_error))
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
