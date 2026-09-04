package net.activitywatch.android.sync

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentD1SyncBinding

/**
 * Cloudflare D1 云同步设置页。
 *
 * 配置 D1 连接参数（Account ID / Database ID / API Token），
 * 提供「测试连接」「保存设置」「立即同步」操作。
 *
 * 服务端实现：aw-server-rust/aw-sync-rust/src/d1_sync.rs
 * API 端点：POST/GET/POST /api/0/sync/d1/{sync,status,test}
 */
class D1SyncFragment : Fragment() {

    private var _binding: FragmentD1SyncBinding? = null
    private val binding get() = _binding!!

    private val repo = SyncRepository()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentD1SyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }

        // 优先从保存的状态恢复（用户未保存的输入），否则从服务器加载
        if (savedInstanceState != null) {
            restoreFromState(savedInstanceState)
        } else {
            loadConfig()
        }

        // 按钮事件
        binding.btnTestConnection.setOnClickListener { testConnection() }
        binding.btnSaveConfig.setOnClickListener { saveConfig() }
        binding.btnSyncNow.setOnClickListener { syncNow() }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到页面刷新状态
        refreshStatus()
    }

    /**
     * 视图销毁时把当前字段值保存到实例状态 Bundle（内存），
     * 这样导航离开 / 配置变更 / 进程被杀后重建时，用户未保存的输入不会丢失。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ACCOUNT_ID, binding.inputAccountId.text?.toString()?.trim() ?: "")
        outState.putString(KEY_DATABASE_ID, binding.inputDatabaseId.text?.toString()?.trim() ?: "")
        outState.putString(KEY_API_TOKEN, binding.inputApiToken.text?.toString()?.trim() ?: "")
        outState.putString(KEY_SYNC_INTERVAL, binding.inputSyncInterval.text?.toString()?.trim() ?: "")
        outState.putBoolean(KEY_D1_ENABLED, binding.switchD1Enabled.isChecked)
    }

    /** 从保存的状态恢复字段（用户未保存的输入优先于服务器配置） */
    private fun restoreFromState(state: Bundle) {
        binding.switchD1Enabled.isChecked = state.getBoolean(KEY_D1_ENABLED, false)
        binding.inputAccountId.setText(state.getString(KEY_ACCOUNT_ID, ""))
        binding.inputDatabaseId.setText(state.getString(KEY_DATABASE_ID, ""))
        binding.inputApiToken.setText(state.getString(KEY_API_TOKEN, ""))
        binding.inputSyncInterval.setText(state.getString(KEY_SYNC_INTERVAL, "300"))
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            repo.call { repo.api.getConfig() }.fold(
                onSuccess = { cfg ->
                    binding.switchD1Enabled.isChecked = cfg.d1Enabled
                    binding.inputAccountId.setText(cfg.d1AccountId)
                    binding.inputDatabaseId.setText(cfg.d1DatabaseId)
                    binding.inputApiToken.setText(cfg.d1ApiToken)
                    binding.inputSyncInterval.setText(cfg.d1SyncInterval.toString())
                },
                onFailure = { showError("加载配置失败: ${it.message}") }
            )
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            repo.call { repo.api.d1Status() }.fold(
                onSuccess = { status ->
                    if (status.configured) {
                        binding.layoutSyncStatus.visibility = View.VISIBLE
                        val lastSync = status.lastSync ?: "从未同步"
                        binding.tvSyncStatus.text = "上次同步: $lastSync"
                    } else {
                        binding.layoutSyncStatus.visibility = View.VISIBLE
                        binding.tvSyncStatus.text = "D1 未配置完整"
                    }
                },
                onFailure = { /* 静默失败 */ }
            )
        }
    }

    private fun testConnection() {
        if (!validateInput()) return

        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "测试中..."

        lifecycleScope.launch {
            val result = try {
                val resp = repo.api.d1Test()
                if (resp.ok) {
                    Result.success("✅ D1 连接成功\n\nAccount: ${binding.inputAccountId.text}\nDatabase: ${binding.inputDatabaseId.text}")
                } else {
                    Result.success("❌ 连接失败\n\n${resp.message}")
                }
            } catch (e: Exception) {
                Result.success("❌ 请求异常\n\n${e.message ?: e.toString()}")
            }
            result.fold(
                onSuccess = { msg ->
                    AlertDialog.Builder(requireContext())
                        .setTitle("D1 连接测试")
                        .setMessage(msg)
                        .setPositiveButton("确定", null)
                        .show()
                },
                onFailure = {
                    AlertDialog.Builder(requireContext())
                        .setTitle("D1 连接测试")
                        .setMessage("❌ 请求失败\n\n${it.message ?: it.toString()}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            )
            binding.btnTestConnection.isEnabled = true
            binding.btnTestConnection.text = "测试连接"
        }
    }

    private fun saveConfig() {
        if (!validateInput()) return

        binding.btnSaveConfig.isEnabled = false
        binding.btnSaveConfig.text = "保存中..."

        lifecycleScope.launch {
            // 先获取完整配置，只改 D1 字段
            val current = try {
                repo.api.getConfig()
            } catch (e: Exception) {
                SyncConfig()
            }

            val updated = current.copy(
                d1Enabled = binding.switchD1Enabled.isChecked,
                d1AccountId = binding.inputAccountId.text.toString().trim(),
                d1DatabaseId = binding.inputDatabaseId.text.toString().trim(),
                d1ApiToken = binding.inputApiToken.text.toString().trim(),
                d1SyncInterval = binding.inputSyncInterval.text.toString().toLongOrNull() ?: 300
            )

            repo.call { repo.api.saveConfig(updated) }.fold(
                onSuccess = {
                    showMsg("✅ 设置已保存")
                    refreshStatus()
                },
                onFailure = { showError("保存失败: ${it.message}") }
            )

            binding.btnSaveConfig.isEnabled = true
            binding.btnSaveConfig.text = "保存设置"
        }
    }

    private fun syncNow() {
        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = "同步中..."

        lifecycleScope.launch {
            repo.call { repo.api.d1SyncNow() }.fold(
                onSuccess = { result ->
                    if (result.ok) {
                        val msg = buildString {
                            appendLine("✅ 同步完成")
                            appendLine("推送: ${result.pushedNotes} 笔记 / ${result.pushedTodos} TODO")
                            appendLine("拉取: ${result.pulledNotes} 笔记 / ${result.pulledTodos} TODO")
                            if (result.conflicts > 0) {
                                appendLine("冲突归档: ${result.conflicts}")
                            }
                        }
                        showMsg(msg.trimEnd())
                        refreshStatus()
                    } else {
                        val errs = result.errors.joinToString("; ")
                        showError("同步失败: $errs")
                    }
                },
                onFailure = { showError("同步失败: ${it.message}") }
            )

            binding.btnSyncNow.isEnabled = true
            binding.btnSyncNow.text = "立即同步"
        }
    }

    private fun validateInput(): Boolean {
        if (binding.inputAccountId.text.isNullOrBlank()) {
            showError("请输入 Account ID")
            return false
        }
        if (binding.inputDatabaseId.text.isNullOrBlank()) {
            showError("请输入 Database ID")
            return false
        }
        if (binding.inputApiToken.text.isNullOrBlank()) {
            showError("请输入 API Token")
            return false
        }
        return true
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

    companion object {
        private const val KEY_ACCOUNT_ID = "d1_account_id"
        private const val KEY_DATABASE_ID = "d1_database_id"
        private const val KEY_API_TOKEN = "d1_api_token"
        private const val KEY_SYNC_INTERVAL = "d1_sync_interval"
        private const val KEY_D1_ENABLED = "d1_enabled"
    }
}
