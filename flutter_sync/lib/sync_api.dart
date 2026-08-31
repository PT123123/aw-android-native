import 'package:dio/dio.dart';

import 'models.dart';

/// aw-sync-rust REST API 客户端。
/// 对应 aw-webui/src/api/sync.js 的 17 个端点，挂载于 /api/0/sync。
class SyncApi {
  SyncApi({this.baseUrl = 'http://127.0.0.1:5600', Dio? dio})
      : _dio = dio ??
            Dio(BaseOptions(
              baseUrl: baseUrl,
              connectTimeout: const Duration(seconds: 5),
              receiveTimeout: const Duration(seconds: 10),
            ));

  final String baseUrl;
  final Dio _dio;

  static const _prefix = '/api/0/sync';

  // ---- 设置 ----

  Future<SyncConfig> getConfig() async {
    final r = await _get('$_prefix/config');
    return SyncConfig.fromJson(_asMap(r));
  }

  Future<SyncConfig> saveConfig(SyncConfig cfg) async {
    final r = await _put('$_prefix/config', cfg.toJson());
    return SyncConfig.fromJson(_asMap(r));
  }

  Future<Device> getInfo() async {
    final r = await _get('$_prefix/info');
    return Device.fromJson(_asMap(r));
  }

  // ---- 配对 ----

  /// 发起配对。返回 { ok, peer }，peer 为对端回传的设备信息。
  Future<Map<String, dynamic>> initiatePair(String deviceId) async =>
      _asMap(await _post('$_prefix/pair/initiate', {'device_id': deviceId}));

  /// 接受配对。返回 { ok, peer }。
  Future<Map<String, dynamic>> acceptPair(String deviceId) async =>
      _asMap(await _post('$_prefix/pair/accept', {'device_id': deviceId}));

  // ---- 设备 ----

  Future<List<Device>> getDevices() async {
    final r = await _get('$_prefix/devices');
    return (r is List ? r : const [])
        .whereType<Map<String, dynamic>>()
        .map(Device.fromJson)
        .toList();
  }

  /// 立即同步。返回 { device_id, applied }。
  Future<Map<String, dynamic>> syncDevice(String id) async =>
      _asMap(await _post('$_prefix/devices/$id/sync'));

  /// 手动登记一台对端设备（配对反向登记用）。返回 { saved, id }。
  Future<Map<String, dynamic>> addDevice(Device device) async =>
      _asMap(await _post('$_prefix/devices', device.toJson()));

  /// 删除一台设备。返回 { deleted }。
  Future<Map<String, dynamic>> removeDevice(String id) async =>
      _asMap(await _delete('$_prefix/devices/$id'));

  /// 清空所有配对/已发现设备。返回 { cleared }（移除台数）。
  Future<Map<String, dynamic>> clearAllDevices() async =>
      _asMap(await _delete('$_prefix/devices/all'));

  /// 设置/清空设备别名。返回 { updated, id }。
  Future<Map<String, dynamic>> updateDeviceAlias(String id, String? alias) async =>
      _asMap(await _put('$_prefix/devices/$id/alias', {'alias': alias}));

  // ---- 状态与日志 ----

  Future<DiscoveryStatus> getStatus() async {
    final r = await _get('$_prefix/status');
    return DiscoveryStatus.fromJson(_asMap(r));
  }

  /// 同步报文日志。筛选值为空串时服务端视作不过滤。
  Future<LogPage> getLogs({
    String? direction,
    String? protocol,
    String? eventType,
    int limit = 50,
    int offset = 0,
  }) async {
    final r = await _get('$_prefix/log', queryParameters: {
      if (direction != null && direction.isNotEmpty) 'direction': direction,
      if (protocol != null && protocol.isNotEmpty) 'protocol': protocol,
      if (eventType != null && eventType.isNotEmpty) 'event_type': eventType,
      'limit': limit,
      'offset': offset,
    });
    return LogPage.fromJson(_asMap(r));
  }

  /// 清空全部报文日志。返回 { cleared: true }。
  Future<Map<String, dynamic>> clearLogs() async =>
      _asMap(await _delete('$_prefix/log'));

  Future<DeviceSyncStats> getDeviceStats(String id) async {
    final r = await _get('$_prefix/devices/$id/stats');
    return DeviceSyncStats.fromJson(_asMap(r));
  }

  Future<List<ConflictSummary>> getDeviceConflicts(String id) async {
    final r = _asMap(await _get('$_prefix/devices/$id/conflicts'));
    final list = r['conflicts'];
    return (list is List ? list : const [])
        .whereType<Map<String, dynamic>>()
        .map(ConflictSummary.fromJson)
        .toList();
  }

  /// Rust 侧调试日志（环形缓冲）增量拉取，after = 上次收到的最大 seq。
  Future<List<DebugEntry>> getDebugLog({int after = 0}) async {
    final r = await _dio
        .get<dynamic>('$_prefix/debuglog', queryParameters: {'after': after});
    final list = r.data;
    return (list is List ? list : const [])
        .whereType<Map<String, dynamic>>()
        .map(DebugEntry.fromJson)
        .toList();
  }

  // ---- 内部 ----

  Future<dynamic> _get(String path, {Map<String, dynamic>? queryParameters}) =>
      _unwrap(_dio.get<dynamic>(path, queryParameters: queryParameters));

  Future<dynamic> _post(String path, [Object? body]) =>
      _unwrap(_dio.post<dynamic>(path, data: body));

  Future<dynamic> _put(String path, Object body) =>
      _unwrap(_dio.put<dynamic>(path, data: body));

  Future<dynamic> _delete(String path) =>
      _unwrap(_dio.delete<dynamic>(path));

  Future<dynamic> _unwrap(Future<Response<dynamic>> f) async {
    try {
      final r = await f;
      return r.data;
    } on DioException catch (e) {
      throw SyncApiException.fromDio(e);
    }
  }

  static Map<String, dynamic> _asMap(dynamic v) {
    if (v is Map<String, dynamic>) return v;
    throw SyncApiException('响应不是 JSON 对象: $v');
  }
}

class SyncApiException implements Exception {
  SyncApiException(this.message, {this.statusCode});

  factory SyncApiException.fromDio(DioException e) {
    String msg = e.message ?? e.toString();
    final data = e.response?.data;
    if (data is Map) {
      final m = data['message'] ?? data['error'];
      if (m != null) msg = m.toString();
    } else if (e.response != null) {
      // Rust 侧多数错误只返回状态码、无 body，给一句人话而不是 dio 长文
      msg = '服务器错误 (${e.response!.statusCode})';
    } else if (e.type == DioExceptionType.connectionTimeout ||
        e.type == DioExceptionType.receiveTimeout) {
      msg = '连接超时，同步服务未响应';
    } else if (e.type == DioExceptionType.connectionError) {
      msg = '无法连接同步服务，请确认应用内服务已启动';
    }
    return SyncApiException(msg, statusCode: e.response?.statusCode);
  }

  final String message;
  final int? statusCode;

  @override
  String toString() =>
      'SyncApiException(${statusCode ?? 'network'}): $message';
}
