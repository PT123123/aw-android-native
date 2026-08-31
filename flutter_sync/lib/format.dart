import 'package:intl/intl.dart';

/// 与 Sync.vue 中同名的展示辅助函数。

String deviceTypeLabel(String? t) => const {
      'windows': 'Windows',
      'android': 'Android',
      'ios': 'iOS',
      'linux': 'Linux',
      'macos': 'macOS',
    }[t] ??
    (t?.isNotEmpty == true ? t! : '-');

String directionLabel(String d) => d == 'out' ? '去向' : '来向';

String eventLabel(String? e) => const {
      'discovery': '发现',
      'pairing': '配对',
      'sync': '同步',
      'conflict': '冲突',
    }[e] ??
    (e?.isNotEmpty == true ? e! : '-');

String protocolLabel(String? p) => const {
      'http': 'HTTP',
      'udp_broadcast': 'UDP 广播',
      'mdns': 'mDNS',
    }[p] ??
    (p?.isNotEmpty == true ? p! : '-');

final DateFormat _fmt = DateFormat('yyyy/MM/dd HH:mm:ss');

/// 服务端时间戳为 UTC RFC3339；按本地时区展示。解析失败原样返回。
String formatTime(String? ts) {
  if (ts == null || ts.isEmpty) return '-';
  final d = DateTime.tryParse(ts);
  if (d == null) return ts;
  return _fmt.format(d.toLocal());
}

String formatDateTime(DateTime? d) => d == null ? '-' : _fmt.format(d.toLocal());

String humanSize(num? n) {
  if (n == null || n == 0) return '-';
  if (n < 1024) return '$n B';
  return '${(n / 1024).toStringAsFixed(1)} KB';
}

bool isLoopback(String? ip) {
  if (ip == null || ip.isEmpty) return true;
  if (ip == 'localhost' || ip == '0.0.0.0' || ip == '::1') return true;
  return ip.startsWith('127.');
}

/// 平均每 N 分钟 / N 小时 M 分钟 / N 天 H 小时（与 vue 的 getSyncFrequencyText 一致）
String syncFrequencyText(int? minutes) {
  if (minutes == null || minutes <= 0) return '尚未同步';
  if (minutes < 60) return '平均每 $minutes 分钟';
  final hours = minutes ~/ 60;
  final remainMins = minutes % 60;
  if (hours < 24) return '平均每 $hours 小时 $remainMins 分钟';
  final days = hours ~/ 24;
  return '平均每 $days 天 ${hours % 24} 小时';
}
