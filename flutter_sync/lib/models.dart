/// 数据模型：与 aw-sync-rust (aw-server-rust/aw-sync-rust/src/models.rs) 的
/// JSON 序列化结果一一对应。字段命名沿用 Dart 驼峰，JSON key 为 snake_case。
library;

DateTime? _parseTs(Object? v) {
  if (v == null) return null;
  return DateTime.tryParse(v.toString());
}

int? _asInt(Object? v) => v == null ? null : int.tryParse(v.toString());

/// GET/PUT /api/0/sync/config
class SyncConfig {
  SyncConfig({
    required this.enabled,
    required this.httpEnabled,
    required this.discoveryMethod,
    required this.listenPort,
    required this.udpPort,
    required this.syncInbox,
    required this.syncActivity,
    required this.selfAlias,
    required this.probeInterval,
  });

  bool enabled;
  bool httpEnabled;

  /// broadcast / mdns / poll
  String discoveryMethod;

  /// 设备间同步 HTTP 端口（与服务器端口一致，固定 5600）
  int listenPort;

  /// UDP 广播/发现固定端口（默认 46000）
  int udpPort;
  bool syncInbox;
  bool syncActivity;
  String selfAlias;

  /// 在线状态探测间隔（秒）
  int probeInterval;

  factory SyncConfig.fromJson(Map<String, dynamic> j) => SyncConfig(
        enabled: j['enabled'] == true,
        httpEnabled: j['http_enabled'] == true,
        discoveryMethod: (j['discovery_method'] ?? 'broadcast').toString(),
        listenPort: _asInt(j['listen_port']) ?? 5600,
        udpPort: _asInt(j['udp_port']) ?? 46000,
        syncInbox: j['sync_inbox'] == true,
        syncActivity: j['sync_activity'] == true,
        selfAlias: (j['self_alias'] ?? '').toString(),
        probeInterval: _asInt(j['probe_interval']) ?? 10,
      );

  Map<String, dynamic> toJson() => {
        'enabled': enabled,
        'http_enabled': httpEnabled,
        'discovery_method': discoveryMethod,
        'listen_port': listenPort,
        'udp_port': udpPort,
        'sync_inbox': syncInbox,
        'sync_activity': syncActivity,
        'self_alias': selfAlias,
        'probe_interval': probeInterval,
      };

  SyncConfig copy() => SyncConfig.fromJson(toJson());
}

/// 设备（/devices、/info、配对相关端点共用）
class Device {
  Device({
    required this.id,
    required this.name,
    required this.deviceKind,
    required this.ip,
    required this.port,
    required this.pairedAt,
    required this.isOnline,
    required this.isSelf,
    this.lastSyncAt,
    this.lastSeenAt,
    this.paired = false,
    this.alias,
    this.incomingPairRequest = false,
    this.ipIface,
  });

  String id;
  String name;

  /// windows / android / ios / linux / macos / unknown
  String deviceKind;
  String ip;
  int port;
  DateTime pairedAt;
  DateTime? lastSyncAt;
  DateTime? lastSeenAt;
  bool isOnline;
  bool isSelf;
  bool paired;
  String? alias;

  /// 仅 GET /devices 附带：是否有待本机确认的配对请求
  bool incomingPairRequest;

  /// 仅 GET /info 附带：本机 IP 所在网卡名
  String? ipIface;

  factory Device.fromJson(Map<String, dynamic> j) => Device(
        id: (j['id'] ?? '').toString(),
        name: (j['name'] ?? '').toString(),
        deviceKind: (j['device_kind'] ?? 'unknown').toString(),
        ip: (j['ip'] ?? '').toString(),
        port: _asInt(j['port']) ?? 0,
        pairedAt: _parseTs(j['paired_at']) ?? DateTime.now(),
        lastSyncAt: _parseTs(j['last_sync_at']),
        lastSeenAt: _parseTs(j['last_seen_at']),
        isOnline: j['is_online'] == true,
        isSelf: j['is_self'] == true,
        paired: j['paired'] == true,
        alias: j['alias']?.toString(),
        incomingPairRequest: j['incoming_pair_request'] == true,
        ipIface: j['ip_iface']?.toString(),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'device_kind': deviceKind,
        'ip': ip,
        'port': port,
        'paired_at': pairedAt.toUtc().toIso8601String(),
        'last_sync_at': lastSyncAt?.toUtc().toIso8601String(),
        'last_seen_at': lastSeenAt?.toUtc().toIso8601String(),
        'is_online': isOnline,
        'is_self': isSelf,
        'paired': paired,
        'alias': alias,
      };

  String get displayName => (alias == null || alias!.isEmpty) ? name : alias!;

  /// 与 Sync.vue 一致：已配对设备信 is_online；未配对设备看 30s 内是否被发现过
  bool get isEffectivelyOnline {
    if (paired) return isOnline;
    final seen = lastSeenAt;
    if (seen == null) return false;
    return DateTime.now().difference(seen).inSeconds < 30;
  }
}

/// GET /api/0/sync/status
class DiscoveryStatus {
  DiscoveryStatus({
    required this.enabled,
    required this.httpEnabled,
    required this.discoveryMethod,
    required this.discoveryRunning,
    required this.udpPort,
    required this.listenPort,
    this.selfDevice,
  });

  bool enabled;
  bool httpEnabled;
  String discoveryMethod;
  bool discoveryRunning;
  int udpPort;
  int listenPort;
  Device? selfDevice;

  factory DiscoveryStatus.fromJson(Map<String, dynamic> j) => DiscoveryStatus(
        enabled: j['enabled'] == true,
        httpEnabled: j['http_enabled'] == true,
        discoveryMethod: (j['discovery_method'] ?? 'broadcast').toString(),
        discoveryRunning: j['discovery_running'] == true,
        udpPort: _asInt(j['udp_port']) ?? 46000,
        listenPort: _asInt(j['listen_port']) ?? 5600,
        selfDevice: j['self_device'] is Map<String, dynamic>
            ? Device.fromJson(j['self_device'] as Map<String, dynamic>)
            : null,
      );
}

/// 一条同步报文日志
class SyncLogEntry {
  SyncLogEntry({
    this.id,
    required this.timestamp,
    required this.direction,
    required this.protocol,
    required this.eventType,
    required this.status,
    this.peerId,
    this.message,
    this.dataSize,
  });

  int? id;
  DateTime timestamp;

  /// out / in
  String direction;

  /// http / udp_broadcast / mdns
  String protocol;
  String? peerId;

  /// pairing / discovery / sync / conflict
  String eventType;

  /// success / failed / running
  String status;
  String? message;
  int? dataSize;

  factory SyncLogEntry.fromJson(Map<String, dynamic> j) => SyncLogEntry(
        id: _asInt(j['id']),
        timestamp: _parseTs(j['timestamp']) ?? DateTime.now(),
        direction: (j['direction'] ?? '').toString(),
        protocol: (j['protocol'] ?? '').toString(),
        peerId: j['peer_id']?.toString(),
        eventType: (j['event_type'] ?? '').toString(),
        status: (j['status'] ?? '').toString(),
        message: j['message']?.toString(),
        dataSize: _asInt(j['data_size']),
      );
}

/// GET /api/0/sync/log 返回 { logs, total }
class LogPage {
  LogPage({required this.logs, required this.total});

  List<SyncLogEntry> logs;
  int total;

  factory LogPage.fromJson(Map<String, dynamic> j) => LogPage(
        logs: (j['logs'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(SyncLogEntry.fromJson)
            .toList(),
        total: _asInt(j['total']) ?? 0,
      );
}

/// GET `/api/0/sync/devices/<id>/stats`
class DeviceSyncStats {
  DeviceSyncStats({
    required this.deviceId,
    required this.pendingPushCount,
    required this.pendingConflictCount,
    required this.totalSyncedCount,
    required this.totalSyncedSize,
    required this.localNoteCount,
    required this.remoteNoteCount,
    this.lastSyncAt,
    this.lastFullSyncAt,
    this.syncFrequencyMinutes,
    this.lastError,
    this.lastErrorAt,
  });

  String deviceId;
  int pendingPushCount;
  int pendingConflictCount;
  int totalSyncedCount;
  int totalSyncedSize;
  int localNoteCount;
  int remoteNoteCount;
  String? lastSyncAt;
  String? lastFullSyncAt;
  int? syncFrequencyMinutes;
  String? lastError;
  String? lastErrorAt;

  factory DeviceSyncStats.fromJson(Map<String, dynamic> j) => DeviceSyncStats(
        deviceId: (j['device_id'] ?? '').toString(),
        pendingPushCount: _asInt(j['pending_push_count']) ?? 0,
        pendingConflictCount: _asInt(j['pending_conflict_count']) ?? 0,
        totalSyncedCount: _asInt(j['total_synced_count']) ?? 0,
        totalSyncedSize: _asInt(j['total_synced_size']) ?? 0,
        localNoteCount: _asInt(j['local_note_count']) ?? 0,
        remoteNoteCount: _asInt(j['remote_note_count']) ?? 0,
        lastSyncAt: j['last_sync_at']?.toString(),
        lastFullSyncAt: j['last_full_sync_at']?.toString(),
        syncFrequencyMinutes: _asInt(j['sync_frequency_minutes']),
        lastError: j['last_error']?.toString(),
        lastErrorAt: j['last_error_at']?.toString(),
      );
}

/// GET `/api/0/sync/devices/<id>/conflicts` 返回 { conflicts: [...] }
class ConflictSummary {
  ConflictSummary({
    required this.noteId,
    required this.noteTitle,
    required this.detectedAt,
    required this.resolved,
    this.resolution,
  });

  int noteId;
  String noteTitle;
  String detectedAt;
  bool resolved;
  String? resolution;

  factory ConflictSummary.fromJson(Map<String, dynamic> j) => ConflictSummary(
        noteId: _asInt(j['note_id']) ?? 0,
        noteTitle: (j['note_title'] ?? '').toString(),
        detectedAt: (j['detected_at'] ?? '').toString(),
        resolved: j['resolved'] == true,
        resolution: j['resolution']?.toString(),
      );
}

/// GET /api/0/sync/debuglog?after=N 的元素
class DebugEntry {
  DebugEntry({
    required this.seq,
    required this.ts,
    required this.level,
    required this.msg,
  });

  int seq;

  /// HH:MM:SS.mmm 本地可读时间（服务端已格式化）
  String ts;
  String level;
  String msg;

  factory DebugEntry.fromJson(Map<String, dynamic> j) => DebugEntry(
        seq: _asInt(j['seq']) ?? 0,
        ts: (j['ts'] ?? '').toString(),
        level: (j['level'] ?? '').toString(),
        msg: (j['msg'] ?? '').toString(),
      );
}
