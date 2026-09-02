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

        binding.toolbar.title = toolbarTitle
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }

        binding.sectionWebdav.visibility = if (isWebDav) View.VISIBLE else View.GONE
        binding.sectionS3.visibility = if (isWebDav) View.GONE else View.VISIBLE

        loadFields()
        binding.tetFileName.setText(
            prefs.getString("${prefPrefix}file", CloudBackup.DEFAULT_FILE_NAME)
        )

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
