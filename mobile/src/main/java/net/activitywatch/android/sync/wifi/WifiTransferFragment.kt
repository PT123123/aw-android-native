package net.activitywatch.android.sync.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.activitywatch.android.R
import net.activitywatch.android.databinding.FragmentWifiTransferBinding
import net.activitywatch.android.sync.SyncApiClient
import net.activitywatch.android.sync.SyncSnapshot

/**
 * WiFi 热点传输（实验性）：无需路由器 / 局域网的两台设备点对点同步。
 *
 * 流程：
 * - 被传送方：开启 Local-only Hotspot → 出示二维码（SSID / 密码 / 服务器地址）；
 * - 传送方：扫码 → WifiNetworkSpecifier 连接对端热点 →
 *   拉对端 /snapshot → 本机 /apply（合并入本机）→ 导出本机 /snapshot → 推对端 /push，
 *   双向都收敛到并集；合并 / 冲突处理复用局域网同步的服务端逻辑
 *   （inbox / activity 幂等 upsert，todo 按 updated_at 新者胜，本地优先保留）。
 */
class WifiTransferFragment : Fragment() {

    private var _binding: FragmentWifiTransferBinding? = null
    private val binding get() = _binding!!

    private val gson = Gson()
    private val localApi = SyncApiClient.api
    private lateinit var hotspot: HotspotHelper

    private var transferJob: Job? = null
    private var receiverJob: Job? = null
    private var lastIncomingMsg: String? = null

    // ==================== 结果契约 ====================

    private data class TransferResult(val appliedLocal: Int, val appliedRemote: Int)

    // ==================== 生命周期 ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWifiTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hotspot = HotspotHelper(requireContext())

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }

        binding.btnRoleSender.setOnClickListener { launchScanner() }
        binding.btnRoleReceiver.setOnClickListener { ensureLocationThenStartHotspot() }
        binding.btnStop.setOnClickListener { reset() }
        binding.btnBack.setOnClickListener { reset() }
    }

    override fun onDestroyView() {
        transferJob?.cancel()
        receiverJob?.cancel()
        hotspot.stop()
        WifiConnector.release(requireContext())
        _binding = null
        super.onDestroyView()
    }

    // ==================== 角色选择 ====================

    private fun showRoleChoice() {
        binding.sectionRole.visibility = View.VISIBLE
        binding.sectionReceiver.visibility = View.GONE
        binding.sectionSender.visibility = View.GONE
    }

    private fun reset() {
        transferJob?.cancel()
        transferJob = null
        receiverJob?.cancel()
        receiverJob = null
        hotspot.stop()
        WifiConnector.release(requireContext())
        lastIncomingMsg = null
        binding.tvLog.text = ""
        binding.tvIncoming.text = "等待传送方扫码连接…"
        binding.qrImage.visibility = View.GONE
        binding.qrProgress.visibility = View.VISIBLE
        showRoleChoice()
    }

    // ==================== 被传送方（开热点 / 出码） ====================

    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startHotspot()
            } else {
                showErrorUi("未授予「精确位置」权限，系统不允许应用开启热点")
            }
        }

    private fun ensureLocationThenStartHotspot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showErrorUi("系统版本过低（需 Android 8.0+）")
            return
        }
        if (!isLocationServiceOn()) {
            showErrorUi("请先开启系统「位置服务」后再试（开启热点的前提条件）")
            return
        }
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startHotspot()
        } else {
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun isLocationServiceOn(): Boolean {
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)
        } catch (_: Exception) {
            // 无定位权限时查询 provider 可能抛异常，视作无法判定，放行走热点报错兜底
            true
        }
    }

    private fun startHotspot() {
        binding.sectionRole.visibility = View.GONE
        binding.sectionSender.visibility = View.GONE
        binding.sectionReceiver.visibility = View.VISIBLE
        binding.tvReceiverTitle.text = "正在开启热点…"
        binding.qrImage.visibility = View.GONE
        binding.qrProgress.visibility = View.VISIBLE

        transferJob = viewLifecycleOwner.lifecycleScope.launch {
            val ipsBefore = HotspotHelper.currentIpv4s()
            try {
                // 本机信息（设备名 / id 进二维码，便于对方识别）
                val self = withContext(Dispatchers.IO) {
                    runCatching { localApi.getInfo() }.getOrNull()
                }
                val info = hotspot.start(ipsBefore)
                val payload = QrPayload(
                    ssid = info.ssid,
                    psk = info.psk,
                    ip = info.serverIp,
                    port = self?.port?.takeIf { it > 0 } ?: 5600,
                    id = self?.id.orEmpty(),
                    name = self?.name?.takeIf { it.isNotBlank() } ?: Build.MODEL
                )
                showQr(payload, info)
                startIncomingMonitor()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hotspot.stop()
                binding.tvReceiverTitle.text = "热点开启失败"
                binding.qrProgress.visibility = View.GONE
                binding.tvReceiverInfo.text = e.message ?: e.javaClass.simpleName
                binding.tvReceiverInfo.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.aw_danger)
                )
            }
        }
    }

    private fun showQr(payload: QrPayload, info: HotspotHelper.HotspotInfo) {
        if (_binding == null) return
        binding.tvReceiverTitle.text = "等待传送方扫码…"
        val size = (300 * resources.displayMetrics.density).toInt().coerceAtMost(1080)
        val bmp = QrBitmaps.encode(payload.toJson(), size)
        binding.qrImage.setImageBitmap(bmp)
        binding.qrImage.visibility = View.VISIBLE
        binding.qrProgress.visibility = View.GONE
        binding.tvReceiverInfo.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.aw_text_secondary)
        )
        binding.tvReceiverInfo.text =
            "热点：${info.ssid}\n本机地址：${info.serverIp}:${payload.port}（ID: ${payload.id.ifEmpty { "-" }}）\n传送方扫码连接后数据将自动合并，请保持本页在前台"
    }

    /** 轮询本机同步日志，展示收到的数据（复用局域网同步「显示报文」的数据源）。 */
    private fun startIncomingMonitor() {
        receiverJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && _binding != null && hotspot.isRunning) {
                try {
                    val page = withContext(Dispatchers.IO) {
                        localApi.getLogs(direction = "in", eventType = "sync", protocol = null, limit = 3, offset = 0)
                    }
                    val latest = page.logs.firstOrNull()?.let { log ->
                        "✓ ${log.message ?: "收到同步数据"}（${net.activitywatch.android.sync.SyncFormatters.formatTime(log.timestamp)}）"
                    }
                    if (latest != null && latest != lastIncomingMsg && _binding != null) {
                        lastIncomingMsg = latest
                        binding.tvIncoming.text = latest
                        binding.tvIncoming.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.aw_success)
                        )
                    }
                } catch (_: Exception) {
                    // 服务未就绪时静默重试
                }
                delay(2000)
            }
        }
    }

    // ==================== 传送方（扫码 / 连接 / 传输） ====================

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@registerForActivityResult
        val payload = QrPayload.fromJson(contents)
        if (payload == null) {
            showErrorUi("二维码内容不是有效的 AW WiFi 传输码（需在被传送方的「WiFi 传输」页面生成）")
            return@registerForActivityResult
        }
        senderConnectAndTransfer(payload)
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("对准被传送方出示的二维码")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        scanLauncher.launch(options)
    }

    private fun senderConnectAndTransfer(payload: QrPayload) {
        binding.sectionRole.visibility = View.GONE
        binding.sectionReceiver.visibility = View.GONE
        binding.sectionSender.visibility = View.VISIBLE
        binding.tvSenderTitle.text = "连接中…"
        binding.senderProgress.visibility = View.VISIBLE
        binding.btnBack.visibility = View.GONE
        binding.tvLog.text = ""

        log("对端：${payload.name} @ ${payload.ip}:${payload.port}")
        log("连接热点「${payload.ssid}」…")
        log("（系统将弹窗确认，网络无互联网属正常，请点「连接」）")

        transferJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val network = withContext(Dispatchers.IO) {
                    WifiConnector.connect(requireContext(), payload)
                }
                log("已连接热点，开始传输…")
                binding.tvSenderTitle.text = "传输中…"
                val result = withContext(Dispatchers.IO) { doTransfer(network, payload) }
                WifiConnector.release(requireContext())
                binding.tvSenderTitle.text = "传输完成"
                binding.tvSenderTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.aw_success)
                )
                log("✓ 已合并对端数据 ${result.appliedLocal} 条到本机")
                log("✓ 已向对端推送本机数据（对端应用 ${result.appliedRemote} 条）")
                log("两边数据已收敛为并集（重复数据幂等跳过）")
                binding.senderProgress.visibility = View.GONE
                binding.btnBack.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WifiConnector.release(requireContext())
                binding.tvSenderTitle.text = "传输失败"
                binding.tvSenderTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.aw_danger)
                )
                log("✗ ${e.message ?: e.javaClass.simpleName}")
                binding.senderProgress.visibility = View.GONE
                binding.btnBack.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 传输主体（全部在 IO 线程执行，日志经 [log] 回主线程）：
     * 1. GET 对端 /info —— 验证对端 aw-sync 服务可达；
     * 2. GET 对端 /snapshot —— 拉取对端快照；
     * 3. POST 本机 /apply —— 合并对端数据进本机（127.0.0.1，Retrofit）；
     * 4. GET 本机 /snapshot —— 合并后导出（保证推送的是并集）；
     * 5. POST 对端 /push —— 推送本机快照。
     */
    private suspend fun doTransfer(network: Network?, payload: QrPayload): TransferResult {
        // 1) 验证对端
        WifiHttp.get(network, payload.ip, payload.port, "/api/0/sync/info")
        log("对端服务就绪")

        // 2) 拉对端快照
        log("拉取对端数据…")
        val remoteJson = WifiHttp.get(
            network, payload.ip, payload.port, "/api/0/sync/snapshot", readTimeoutMs = 300_000
        )
        val remoteSnap = gson.fromJson(remoteJson, SyncSnapshot::class.java)
        log(
            "对端数据大小：" + listOfNotNull(
                remoteSnap.activity?.length?.let { "activity ${it / 1024}KB" },
                remoteSnap.inbox?.length?.let { "inbox ${it / 1024}KB" },
                remoteSnap.todo?.length?.let { "todo ${it / 1024}KB" }
            ).joinToString(" / ").ifEmpty { "（对端按设置未开放任何同步目标）" }
        )

        // 3) 合并对端数据到本机
        log("合并对端数据到本机…")
        val appliedLocal = localApi.applySnapshot(remoteSnap).applied

        // 4) 导出本机数据（合并之后导出，推送并集）
        log("导出本机数据…")
        val localSnap = localApi.getSnapshot()

        // 5) 推送到对端
        log("推送本机数据到对端…")
        val pushJson = gson.toJson(localSnap)
        val pushResp = WifiHttp.postJson(
            network, payload.ip, payload.port, "/api/0/sync/push", pushJson, readTimeoutMs = 600_000
        )
        val appliedRemote = runCatching {
            (gson.fromJson(pushResp, Map::class.java) as? Map<*, *>)?.get("applied") as? Double
        }.getOrNull()?.toInt() ?: 0

        return TransferResult(appliedLocal, appliedRemote)
    }

    // ==================== 工具 ====================

    /** IO 线程安全的日志（追加到传送方进度区）。 */
    private fun log(msg: String) {
        val act = activity ?: return
        act.runOnUiThread {
            if (_binding != null) {
                binding.tvLog.append("· $msg\n")
            }
        }
    }

    /** 顶层错误：回到角色选择并弹提示。 */
    private fun showErrorUi(msg: String) {
        showRoleChoice()
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
