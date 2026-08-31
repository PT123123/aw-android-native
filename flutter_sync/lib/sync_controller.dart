import 'dart:async';
import 'dart:developer' as developer;

import 'package:flutter/foundation.dart';

import 'models.dart';
import 'sync_api.dart';

/// 局域网同步页的状态控制器。
///
/// 复刻 Sync.vue 的数据流：
/// - 主轮询：每 [refreshSeconds] 秒（1~60 可调）刷新设备 / 报文 / 状态
/// - 调试日志轮询：每 2s 增量拉 /debuglog（after=seq），输出到调试控制台
/// - 离开页面调用 [dispose] 时全部取消
class SyncController extends ChangeNotifier {
  SyncController({SyncApi? api}) : api = api ?? SyncApi();

  final SyncApi api;

  // ---- 数据 ----
  SyncConfig? config;
  DiscoveryStatus? status;
  List<Device> devices = const [];
  LogPage logPage = LogPage(logs: const [], total: 0);
  String? logError;
  Map<String, DeviceSyncStats> deviceStats = {};
  Map<String, List<ConflictSummary>> deviceConflicts = {};
  Set<String> expandedDevices = {};
  bool initialLoading = true;

  // ---- 报文面板筛选（与 vue 一致：空串 = 全部）----
  String filterDirection = '';
  String filterEventType = '';
  String filterProtocol = '';
  int pageSize = 10;

  /// 主轮询间隔（秒），范围 1~60，默认 5
  int get refreshSeconds => _refreshSeconds;
  int _refreshSeconds = 5;

  Device? get selfDevice {
    for (final d in devices) {
      if (d.isSelf) return d;
    }
    return status?.selfDevice;
  }

  List<Device> get discoveredDevices => devices
      .where((d) => !d.isSelf && !d.paired && d.isEffectivelyOnline)
      .toList();

  List<Device> get pairedDevices =>
      devices.where((d) => d.paired).toList();

  // ---- 轮询 ----
  Timer? _mainTimer;
  Timer? _debugTimer;
  int _pollSeq = 0;
  bool _debugWarned = false;
  bool _disposed = false;

  Future<void> start() async {
    await Future.wait([
      loadConfig(),
      loadDevices(),
      loadLogs(),
      loadStatus(),
    ]);
    initialLoading = false;
    _notify();
    _startMainPolling();
    _startDebugPolling();
  }

  void setRefreshSeconds(int seconds) {
    final clamped = seconds.clamp(1, 60);
    if (clamped == _refreshSeconds) return;
    _refreshSeconds = clamped;
    _startMainPolling();
    _notify();
  }

  void _startMainPolling() {
    _mainTimer?.cancel();
    _mainTimer = Timer.periodic(Duration(seconds: _refreshSeconds), (_) {
      loadDevices();
      loadLogs();
      loadStatus();
    });
  }

  /// 调试日志通道：对应 vue 的 startLogPolling，输出到 Dart 调试控制台
  /// （`flutter attach` / adb logcat 可见）。
  void _startDebugPolling() {
    _debugTimer?.cancel();
    _pollDebugLogs();
    _debugTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      _pollDebugLogs();
    });
  }

  Future<void> _pollDebugLogs() async {
    try {
      final entries = await api.getDebugLog(after: _pollSeq);
      if (entries.isEmpty) return;
      for (final e in entries) {
        developer.log('[aw-sync][${e.level}] ${e.ts} ${e.msg}');
      }
      _pollSeq = entries.last.seq;
    } catch (e) {
      if (!_debugWarned) {
        _debugWarned = true;
        debugPrint('[aw-sync] 调试日志拉取失败（若持续出现，检查内嵌 .so 是否为同一批构建）: $e');
      }
    }
  }

  // ---- 加载 ----

  Future<void> loadStatus() async {
    try {
      status = await api.getStatus();
    } catch (e) {
      debugPrint('[aw-sync] loadStatus 失败: $e');
    }
    _notify();
  }

  Future<void> loadConfig() async {
    try {
      config = await api.getConfig();
    } catch (e) {
      debugPrint('[aw-sync] loadConfig 失败: $e');
    }
    _notify();
  }

  Future<void> loadDevices() async {
    try {
      devices = await api.getDevices();
      // 已配对的远端设备附带统计（与 vue 的 loadAllDeviceStats 一致）
      for (final d in devices.where((d) => d.paired && !d.isSelf)) {
        await loadDeviceStats(d.id);
      }
    } catch (e) {
      debugPrint('[aw-sync] loadDevices 失败: $e');
    }
    _notify();
  }

  Future<void> loadDeviceStats(String deviceId) async {
    try {
      deviceStats[deviceId] = await api.getDeviceStats(deviceId);
      deviceConflicts[deviceId] = await api.getDeviceConflicts(deviceId);
    } catch (e) {
      debugPrint('[aw-sync] loadDeviceStats 失败: $deviceId $e');
    }
  }

  Future<void> loadLogs() async {
    try {
      final page = await api.getLogs(
        direction: filterDirection,
        eventType: filterEventType,
        protocol: filterProtocol,
        limit: pageSize,
      );
      logPage = page;
      logError = null;
    } catch (e) {
      logError = e is SyncApiException ? e.message : e.toString();
    }
    _notify();
  }

  // ---- 操作 ----

  /// 返回错误信息；null 表示成功。
  Future<String?> saveConfig(SyncConfig cfg) async {
    try {
      final saved = await api.saveConfig(cfg);
      config = saved;
      await loadStatus();
      return null;
    } catch (e) {
      return e is SyncApiException ? e.message : e.toString();
    }
  }

  /// 立即同步。返回 (应用条数, 错误信息)，错误非 null 时条数无意义。
  Future<(int, String?)> syncDevice(String id) async {
    try {
      final r = await api.syncDevice(id);
      await loadLogs();
      return ((r['applied'] as num?)?.toInt() ?? 0, null);
    } catch (e) {
      return (0, e is SyncApiException ? e.message : e.toString());
    }
  }

  Future<String?> removeDevice(String id) async {
    try {
      await api.removeDevice(id);
      await loadDevices();
      return null;
    } catch (e) {
      return e is SyncApiException ? e.message : e.toString();
    }
  }

  Future<String?> saveAlias(String id, String alias) async {
    try {
      await api.updateDeviceAlias(id, alias);
      await loadDevices();
      return null;
    } catch (e) {
      return e is SyncApiException ? e.message : e.toString();
    }
  }

  /// 清空所有配对/已发现设备。返回 (移除台数, 错误信息)。
  Future<(int, String?)> clearAllDevices() async {
    try {
      final r = await api.clearAllDevices();
      final cleared = (r['cleared'] as num?)?.toInt() ?? 0;
      await loadDevices();
      await loadLogs();
      return (cleared, null);
    } catch (e) {
      return (0, e is SyncApiException ? e.message : e.toString());
    }
  }

  Future<String?> clearLogs() async {
    try {
      await api.clearLogs();
      await loadLogs();
      return null;
    } catch (e) {
      return e is SyncApiException ? e.message : e.toString();
    }
  }

  /// 发起配对后与 vue 一致：重置筛选并刷新日志，确保新报文可见
  Future<String?> initiatePair(String id) async {
    try {
      await api.initiatePair(id);
      await _afterPairChange();
      return null;
    } catch (e) {
      await loadLogs();
      return e is SyncApiException ? e.message : e.toString();
    }
  }

  Future<String?> acceptPair(String id) async {
    try {
      await api.acceptPair(id);
      await _afterPairChange();
      return null;
    } catch (e) {
      await loadLogs();
      return e is SyncApiException ? e.message : e.toString();
    }
  }

  Future<void> _afterPairChange() async {
    await loadDevices();
    filterDirection = '';
    filterEventType = '';
    filterProtocol = '';
    pageSize = 10;
    _refreshSeconds = 5;
    _startMainPolling();
    await loadLogs();
  }

  void toggleDeviceDetails(String id) {
    if (!expandedDevices.add(id)) expandedDevices.remove(id);
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _mainTimer?.cancel();
    _debugTimer?.cancel();
    super.dispose();
  }
}
