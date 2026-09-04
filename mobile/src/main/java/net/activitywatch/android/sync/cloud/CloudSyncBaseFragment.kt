package net.activitywatch.android.sync.cloud

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentCloudSyncBinding

/**
 * 云备份设置页基类（实验性）。WebDAV 与 S3 共用一套 UI 与流程，
 * 子类只负责：标题、区块显隐、配置读写、客户端构造。
 *
 * 备份内容见 [CloudBackup]；上传/下载由 [CloudClient] 实现类完成。
 */
abstract class CloudSyncBaseFragment : Fragment() {

    private var _binding: FragmentCloudSyncBinding? = null
    protected val binding get() = _binding!!

    protected lateinit var prefs: SharedPreferences

    protected abstract val toolbarTitle: String
    protected abstract val isWebDav: Boolean
    /** 本协议的配置键前缀（如 "webdav_" / "s3_"） */
    protected abstract val prefPrefix: String

    /** 从输入框构造客户端；配置不完整时抛 [CloudSyncException]（信息直接展示） */
    protected abstract fun makeClient(): CloudClient

    protected abstract fun loadFields()
    protected abstract fun saveFields()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCloudSyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        binding.toolbar.title = "云备份（冷备）"
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Tab 切换
        setupTabs()

        loadFields()
        binding.tetFileName.setText(
            prefs.getString("${prefPrefix}file", CloudBackup.DEFAULT_FILE_NAME)
        )

        // 冷备设置
        setupColdBackupSettings()

        binding.btnTest.setOnClickListener {
            runOp("测试连接") {
                saveFields()
                makeClient().test()
            }
        }
        binding.btnBackup.setOnClickListener {
            runOp("备份") {
                saveFields()
                val client = makeClient()
                val name = fileName()
                val data = CloudBackup.build(requireContext())
                client.upload(name, data)
                saveLastBackupTime()
                "备份完成：$name（${data.length / 1024} KB），已上传到云端"
            }
        }
        binding.btnRestore.setOnClickListener {
            runOp("恢复") {
                saveFields()
                val client = makeClient()
                val name = fileName()
                val json = client.download(name)
                val r = CloudBackup.restore(requireContext(), json)
                buildString {
                    append("恢复完成（来自 $name）")
                    if (r.todoRestored) append("：Todo 已写回，重启应用后生效")
                    if (r.notesRestored > 0) append("；Inbox 笔记 ${r.notesRestored} 条")
                }
            }
        }
    }

    private fun setupTabs() {
        val tabLayout = binding.tabLayout
        tabLayout.addTab(tabLayout.newTab().setText("WebDAV"))
        tabLayout.addTab(tabLayout.newTab().setText("S3"))

        // 根据当前协议选中对应 Tab
        tabLayout.getTabAt(if (isWebDav) 0 else 1)?.select()

        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                val webdav = tab.position == 0
                binding.sectionWebdav.visibility = if (webdav) View.VISIBLE else View.GONE
                binding.sectionS3.visibility = if (webdav) View.GONE else View.VISIBLE
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        // 初始显示
        binding.sectionWebdav.visibility = if (isWebDav) View.VISIBLE else View.GONE
        binding.sectionS3.visibility = if (isWebDav) View.GONE else View.VISIBLE
    }

    private fun setupColdBackupSettings() {
        val switchAuto = binding.switchAutoBackup
        val tetInterval = binding.tetBackupInterval
        val tvLast = binding.tvLastBackup

        // 加载保存的设置
        switchAuto.isChecked = prefs.getBoolean("auto_backup", false)
        tetInterval.setText(prefs.getInt("backup_interval_hours", 24).toString())
        val lastBackup = prefs.getLong("last_backup_time", 0)
        tvLast.text = if (lastBackup > 0) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            "上次备份：${sdf.format(java.util.Date(lastBackup))}"
        } else {
            "上次备份：从未"
        }

        // 保存设置
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_backup", checked).apply()
        }
        tetInterval.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val hours = tetInterval.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 24
                prefs.edit().putInt("backup_interval_hours", hours).apply()
            }
        }
    }

    private fun saveLastBackupTime() {
        prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        binding.tvLastBackup.text = "上次备份：${sdf.format(java.util.Date())}"
    }

    protected fun fileName(): String {
        val raw = binding.tetFileName.text?.toString()?.trim().orEmpty()
        return raw.ifEmpty { CloudBackup.DEFAULT_FILE_NAME }
    }

    protected fun setStatus(text: String, ok: Boolean) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (ok) R.color.aw_success else R.color.aw_danger
            )
        )
    }

    private fun setBusy(busy: Boolean) {
        binding.btnTest.isEnabled = !busy
        binding.btnBackup.isEnabled = !busy
        binding.btnRestore.isEnabled = !busy
    }

    private fun runOp(label: String, block: suspend () -> String) {
        setBusy(true)
        setStatus("${label}中…", ok = true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val msg = withContext(Dispatchers.IO) { block() }
                setStatus(msg, ok = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("${label}失败：${e.message ?: e.javaClass.simpleName}", ok = false)
            } finally {
                setBusy(false)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val PREFS_NAME = "cloud_sync"
    }
}
