// 端点核对脚本：逐一调用 /api/0/sync 的 17 个前端端点，打印真实响应。
// 用法：dart run tool/probe_endpoints.dart [baseUrl]
// 注意：会做少量写操作（保存并恢复配置、增删一台假设备、清空日志/设备），
// 仅对着测试服务器运行（例如桌面版 aw-server 的全新数据目录）。

import 'dart:io';

import 'package:flutter_sync/models.dart';
import 'package:flutter_sync/sync_api.dart';

late final SyncApi api;

var passed = 0;
var failed = 0;

Future<void> step(String name, Future<void> Function() fn) async {
  try {
    await fn();
    passed++;
    print('  PASS  $name');
  } on SyncApiException catch (e) {
    failed++;
    print('  FAIL  $name -> $e');
  } catch (e) {
    failed++;
    print('  FAIL  $name -> ${e.runtimeType}: $e');
  }
}

String short(Object? v) {
  final s = v.toString();
  return s.length > 300 ? '${s.substring(0, 300)}...' : s;
}

Future<void> main(List<String> args) async {
  final base = args.isNotEmpty ? args[0] : 'http://127.0.0.1:5600';
  api = SyncApi(baseUrl: base);
  print('probe target: $base\n');

  // 1-3 设置
  SyncConfig? original;
  await step('GET  /config', () async {
    original = await api.getConfig();
    print('        ${short(original!.toJson())}');
  });
  await step('PUT  /config（保存并恢复原配置）', () async {
    final cfg = original!.copy()..selfAlias = original!.selfAlias;
    final saved = await api.saveConfig(cfg);
    print('        echo ok: enabled=${saved.enabled} alias="${saved.selfAlias}"');
  });
  await step('GET  /info', () async {
    final d = await api.getInfo();
    print('        id=${d.id} kind=${d.deviceKind} ip=${d.ip}:${d.port} iface=${d.ipIface}');
  });

  // 状态
  await step('GET  /status', () async {
    final s = await api.getStatus();
    print('        running=${s.discoveryRunning} udp=${s.udpPort} http=${s.listenPort} self=${s.selfDevice?.id}');
  });

  // 设备
  await step('GET  /devices', () async {
    final ds = await api.getDevices();
    print('        ${ds.length} 台: ${ds.map((d) => '${d.displayName}(paired=${d.paired},incoming=${d.incomingPairRequest})').join(', ')}');
  });

  // 配对（无真实对端，预期报 500/错误，只验证通道）
  await step('POST /pair/initiate（预期失败：无对端）', () async {
    try {
      final r = await api.initiatePair('nonexistent-device');
      print('        unexpected ok: ${short(r)}');
    } on SyncApiException catch (e) {
      print('        expected error: $e');
    }
  });
  await step('POST /pair/accept（预期失败：无对端）', () async {
    try {
      final r = await api.acceptPair('nonexistent-device');
      print('        unexpected ok: ${short(r)}');
    } on SyncApiException catch (e) {
      print('        expected error: $e');
    }
  });

  // 设备增删改
  const fakeId = 'probe-fake-device-0001';
  final fake = Device(
    id: fakeId,
    name: 'ProbeFake',
    deviceKind: 'linux',
    ip: '127.0.0.1',
    port: 59999,
    pairedAt: DateTime.now().toUtc(),
    isOnline: false,
    isSelf: false,
  );
  await step('POST /devices（登记假设备）', () async {
    final r = await api.addDevice(fake);
    print('        ${short(r)}');
  });
  await step('PUT  /devices/<id>/alias', () async {
    final r = await api.updateDeviceAlias(fakeId, '探针别名');
    print('        ${short(r)}');
  });
  await step('GET  /devices/<id>/stats', () async {
    try {
      final s = await api.getDeviceStats(fakeId);
      print('        pending=${s.pendingPushCount} total=${s.totalSyncedCount}');
    } on SyncApiException catch (e) {
      print('        tolerated error: $e');
    }
  });
  await step('GET  /devices/<id>/conflicts', () async {
    try {
      final cs = await api.getDeviceConflicts(fakeId);
      print('        ${cs.length} 条冲突');
    } on SyncApiException catch (e) {
      print('        tolerated error: $e');
    }
  });
  await step('POST /devices/<id>/sync（预期失败：对端离线）', () async {
    try {
      final r = await api.syncDevice(fakeId);
      print('        unexpected ok: ${short(r)}');
    } on SyncApiException catch (e) {
      print('        expected error: $e');
    }
  });

  // 日志
  await step('GET  /log（无过滤）', () async {
    final p = await api.getLogs(limit: 5);
    print('        total=${p.total} 取回=${p.logs.length}');
    if (p.logs.isNotEmpty) print('        首条: ${short(p.logs.first.message)}');
  });
  await step('GET  /log（direction=in&event_type=pairing&limit=3）', () async {
    final p = await api.getLogs(direction: 'in', eventType: 'pairing', limit: 3);
    print('        total=${p.total} 取回=${p.logs.length}');
  });
  await step('GET  /debuglog?after=0', () async {
    final es = await api.getDebugLog(after: 0);
    print('        ${es.length} 条');
    if (es.isNotEmpty) print('        末条 seq=${es.last.seq}: ${short(es.last.msg)}');
  });

  // 清理（破坏性，放最后）
  await step('DELETE /devices/<id>（删除假设备）', () async {
    final r = await api.removeDevice(fakeId);
    print('        ${short(r)}');
  });
  await step('DELETE /log（清空报文）', () async {
    final r = await api.clearLogs();
    print('        ${short(r)}');
  });
  await step('DELETE /devices/all（清空设备，测试服务器无真实数据）', () async {
    final r = await api.clearAllDevices();
    print('        ${short(r)}');
  });

  print('\n==== 结果: $passed PASS / $failed FAIL ====');
  exit(failed == 0 ? 0 : 1);
}
